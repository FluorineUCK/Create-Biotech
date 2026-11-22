package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.Vec3;

/** Native Stock Keeper frontend only. No serial hardware, graph execution or logistics dispatch. */
public final class StockKeeperApplicationBinding extends BlockEntityBehaviour {
    public static final BehaviourType<StockKeeperApplicationBinding> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingStockFrontend";
    private final StockTickerBlockEntity ticker;
    private Tag invalid;
    private Selection selected;
    private UUID revision = UUID.randomUUID();
    UUID revision() { return revision; }

    record Selection(BlockPos adapter, UUID device, StockLinkTarget target) {
        CompoundTag save() {
            var tag = new CompoundTag(); tag.putLong("Adapter",adapter.asLong()); tag.putUUID("Device",device);
            tag.put("Target",target.save()); return tag;
        }
        static Selection load(Tag raw) {
            if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Adapter","Device","Target"))
                || !tag.contains("Adapter",Tag.TAG_LONG) || !tag.hasUUID("Device")) throw new IllegalArgumentException("invalid_binding");
            return new Selection(BlockPos.of(tag.getLong("Adapter")), tag.getUUID("Device"), StockLinkTarget.load(tag.get("Target")));
        }
    }

    public StockKeeperApplicationBinding(StockTickerBlockEntity ticker) { super(ticker); this.ticker = ticker; }
    @Override public BehaviourType<?> getType() { return TYPE; }
    StockTickerBlockEntity ticker() { return ticker; }
    Optional<Selection> selection() { return Optional.ofNullable(selected); }
    private boolean mayUse(ServerPlayer actor) {
        return !ticker.isRemoved() && actor.serverLevel() == ticker.getLevel() && actor.isAlive() && !actor.isSpectator()
            && StockLinkTarget.loaded(actor.serverLevel(), ticker.getBlockPos()) == ticker
            && actor.serverLevel().shouldTickBlocksAt(ticker.getBlockPos())
            && actor.distanceToSqr(Vec3.atCenterOf(ticker.getBlockPos())) <= 64
            && actor.serverLevel().mayInteract(actor,ticker.getBlockPos()) && ticker.isKeeperPresent()
            && ticker.behaviour != null && ticker.behaviour.mayInteract(actor);
    }
    boolean mayView(ServerPlayer actor) { return mayUse(actor); }
    boolean mayManage(ServerPlayer actor) { return mayUse(actor) && actor.mayBuild() && ticker.behaviour.mayAdministrate(actor); }
    StockApplicationBehaviour resolve(ServerPlayer actor) { return resolve(actor, selected); }
    private StockApplicationBehaviour resolve(ServerPlayer actor, Selection selected) {
        if (invalid != null || selected == null || !mayUse(actor) || !selected.target().network().equals(ticker.behaviour.freqId)) return null;
        var level = actor.serverLevel();
        var link = selected.target().resolve(level,ticker.getBlockPos());
        if (link == null || !level.mayInteract(actor, link.getBlockPos()) || !link.behaviour.mayInteract(actor)
            || !level.mayInteract(actor,selected.adapter()) || !SubLevelCompat.sameSpace(level,ticker.getBlockPos(),selected.adapter())) return null;
        if (!(StockLinkTarget.loaded(level, selected.adapter()) instanceof StockLinkOutputBlockEntity output)) return null;
        var service = output.getBehaviour(StockApplicationBehaviour.TYPE);
        if (service == null || !service.targets(link)) return null;
        var state = service.state();
        return state != null && selected.device().equals(state.device()) ? service : null;
    }
    List<Selection> discover(ServerPlayer actor) {
        if (invalid != null || !mayManage(actor)) return List.of();
        var level = actor.serverLevel();
        var network = Create.LOGISTICS.logisticsNetworks.get(ticker.behaviour.freqId);
        if (network == null) return List.of();
        var maximum = CBConfigs.SERVER.biocomputing.communication(CBConfigs.SERVER_SPEC.isLoaded()).maxEndpoints();
        if (network.loadedLinks.size() > maximum) throw new IllegalArgumentException("endpoint_limit");
        var options = new ArrayList<Selection>();
        for (var known : network.loadedLinks.stream().filter(pos -> pos.dimension().equals(level.dimension()))
            .sorted(Comparator.comparingLong(pos -> pos.pos().asLong())).toList()) {
            if (!(StockLinkTarget.loaded(level, known.pos()) instanceof PackagerLinkBlockEntity link) || link.behaviour == null
                || !ticker.behaviour.freqId.equals(link.behaviour.freqId)) continue;
            for (var service : StockLinkTarget.adjacentOutputs(link)) {
                var state = service.state();
                if (state == null || !state.open()) continue;
                var option = new Selection(service.output().getBlockPos(), state.device(), service.target().orElseThrow());
                if (resolve(actor,option) != null) options.add(option);
                if (options.size() > maximum) throw new IllegalArgumentException("endpoint_limit");
            }
        }
        return List.copyOf(options);
    }
    String select(ServerPlayer actor, Optional<Selection> next) {
        if (!mayManage(actor)) return "access_denied";
        if (invalid != null) return "invalid_configuration";
        if (next.isPresent() && resolve(actor,next.orElseThrow()) == null) return "changed";
        selected = next.orElse(null); revision = UUID.randomUUID(); ticker.setChanged();
        return next.isPresent() ? "frontend_bound" : "frontend_unbound";
    }
    String status(ServerPlayer actor) {
        if (invalid != null) return "invalid_configuration";
        if (selected == null) return "unbound";
        var service = resolve(actor);
        return service == null ? "link_unavailable" : service.status();
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null; selected = null; revision = UUID.randomUUID();
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root) || !root.getAllKeys().equals(Set.of("Version","Selection"))
                || !root.contains("Version",Tag.TAG_INT) || root.getInt("Version") != 1) return;
            selected = Selection.load(root.get("Selection")); invalid = null;
        } catch (IllegalArgumentException malformed) { /* Preserve the archive and stay closed. */ }
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT);
        if (clientPacket) return;
        if (invalid != null) tag.put(ROOT,invalid.copy());
        else if (selected != null) {
            var root = new CompoundTag(); root.putInt("Version",1); root.put("Selection", selected.save()); tag.put(ROOT,root);
        }
    }
}
