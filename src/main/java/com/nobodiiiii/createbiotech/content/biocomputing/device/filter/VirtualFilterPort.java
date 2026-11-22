package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialPeripheralBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.filter.VirtualFilterProtocol.*;

/** Physically adjacent filter controller. A grant names one source, one actor, one target, and one face. */
final class VirtualFilterPort extends SerialPeripheralBehaviour {
    static final BehaviourType<VirtualFilterPort> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingVirtualFilterPort", PIN = "s0";
    private record Grant(SerialControlAccess.Binding source, GameProfile actor, UUID target, Direction face, boolean list) {}
    private record Target(Optional<VirtualFilterTarget> slot, String status) {}
    private final SerialDevice serial;
    private Grant grant;
    private UUID session = UUID.randomUUID();
    private Tag invalid;
    private String last = "idle";

    VirtualFilterPort(VirtualFilterBlockEntity owner) {
        super(owner);
        serial = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.virtualFilterSerial(CBConfigs.SERVER_SPEC.isLoaded()),
            this::hardwareReady, () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), owner::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return serial; }
    private Direction face() { return blockEntity.getBlockState().getValue(VirtualFilterBlock.FACING); }
    private BlockPos targetPos() { return getPos().relative(face()); }
    private boolean hardwareReady() {
        if (invalid != null || blockEntity.isRemoved() || !(getWorld() instanceof ServerLevel level)
            || !level.getWorldBorder().isWithinBounds(getPos()) || !level.shouldTickBlocksAt(getPos())) return false;
        var chunk = level.getChunkSource().getChunkNow(getPos().getX() >> 4, getPos().getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(getPos()) == blockEntity;
    }
    @Override protected boolean available() { return hardwareReady() && grant != null && grant.face() == face(); }
    UUID session() { return session; }

    private Target target(ServerLevel level) {
        var p = targetPos();
        var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (chunk == null || !level.shouldTickBlocksAt(p)) return new Target(Optional.empty(), "target_unloaded");
        if (!level.getWorldBorder().isWithinBounds(p) || !SubLevelCompat.sameSpace(level, getPos(), p))
            return new Target(Optional.empty(), "target_unavailable");
        var filter = BlockEntityBehaviour.get(chunk.getBlockEntity(p), FilteringBehaviour.TYPE);
        var slot = VirtualFilterTarget.of(filter);
        return new Target(slot, slot.map(VirtualFilterTarget::status).orElse("unsupported_filter_target"));
    }
    boolean mayManage(ServerPlayer player) {
        return hardwareReady() && player.serverLevel() == getWorld() && player.isAlive() && !player.isSpectator()
            && player.mayBuild() && player.distanceToSqr(Vec3.atCenterOf(getPos())) <= 64
            && player.serverLevel().mayInteract(player, getPos());
    }
    List<SerialControlAccess.Binding> discover(ServerPlayer actor) {
        if (!mayManage(actor)) throw new IllegalArgumentException("access_denied");
        return new SerialControlAccess(actor.serverLevel(), getPos(), actor).discover();
    }
    UUID observedTarget() { return target((ServerLevel)getWorld()).slot().map(VirtualFilterTarget::menuIdentity).orElse(null); }
    DataValue.RecordValue serialAddress() { return address(); }
    String configure(ServerPlayer actor, UUID observed, UUID observedTarget, Optional<SerialControlAccess.Binding> source, boolean list) {
        if (!mayManage(actor)) return "access_denied";
        if (!session.equals(observed)) return "changed";
        if (source.isEmpty() && grant == null) return "configured";
        Grant next = null;
        if (source.isPresent()) {
            var target = target(actor.serverLevel());
            if (!target.status().equals("ready")) return target.status();
            var slot = target.slot().orElseThrow();
            if (!slot.menuIdentity().equals(observedTarget)) return "target_changed";
            if (!actor.serverLevel().mayInteract(actor, targetPos())) return "access_denied";
            if (!list && slot.occupied()) return "filter_slot_occupied";
            if (list && !slot.supportsList()) return "unsupported_list_filter";
            if (list && !slot.writeStatus().equals("ready")) return slot.writeStatus();
            if (list) VirtualListFilter.revision(slot.nativeStack()); // Validate before opening the serial interface.
            var selected = source.orElseThrow();
            new SerialControlAccess(actor.serverLevel(), getPos(), actor).verify(selected);
            if (serial.diagnostic().isPresent()) return serial.diagnostic().orElseThrow();
            serial.configure(PIN, face().getOpposite(), true);
            next = new Grant(selected, actor.getGameProfile(), list ? slot.retainListBindingId() : slot.retainBindingId(), face(), list);
        } else {
            try { serial.configure(PIN, face().getOpposite(), false); }
            catch (IllegalArgumentException exhausted) { serial.unload(); }
        }
        grant = next;
        rotateSession();
        return "configured";
    }
    private void rotateSession() { session = UUID.randomUUID(); resetActivity(); blockEntity.setChanged(); }
    void reorient() {
        if (!(getWorld() instanceof ServerLevel)) return;
        grant = null;
        try { serial.configure(PIN, face().getOpposite(), false); }
        catch (IllegalArgumentException exhausted) { serial.unload(); }
        rotateSession();
    }
    @Override public void unload() {
        super.unload();
        if (getWorld() instanceof ServerLevel) rotateSession();
    }

    private DataValue.RecordValue address() { return record(Map.of("x", number(getPos().getX()), "y", number(getPos().getY()), "z", number(getPos().getZ()))); }
    DataValue.RecordValue view() {
        var target = target((ServerLevel)getWorld());
        var slot = target.slot();
        String targetStatus = target.status().equals("ready") && grant != null
            && (grant.face() != face() || !grant.target().equals(binding(slot.orElseThrow(), grant)))
            ? "target_changed" : target.status();
        return record(Map.of("target_pos", text(targetPos().toShortString()), "face", text(face().getName()), "target_status", text(targetStatus),
            "slot", text(slot.map(value -> value.occupied() ? "native_occupied" : value.snapshot().isPresent() ? "virtual" : "empty").orElse("unavailable")),
            "rule", slot.flatMap(VirtualFilterTarget::snapshot).<DataValue>map(v -> v).orElse(DataValue.NullValue.INSTANCE),
            "supports_list", new DataValue.BooleanValue(slot.filter(VirtualFilterTarget::supportsList).isPresent()),
            "scope", text(grant != null && grant.list() ? "list" : "direct"),
            "binding", grant == null ? DataValue.NullValue.INSTANCE : grant.source().summary(),
            "connection", text(serial.status(PIN).connection()), "last", text(last)));
    }
    @Override protected void processReceived() {
        if (!available() || serial.diagnostic().isPresent() || Set.of("queued", "transmitting").contains(serial.status(PIN).transmit())) return;
        var input = serial.prepareReceive(PIN);
        if (!text("received").equals(input.result().field("status"))) {
            if (serial.status(PIN).receive().frames() > 0) {
                var nativeFrame = serial.prepareReceive(PIN, true);
                if (text("received").equals(nativeFrame.result().field("status")) && nativeFrame.publish()) last = "unsupported_format";
            }
            return;
        }
        try {
            var level = (ServerLevel)getWorld();
            var current = grant;
            UUID verified = null;
            var actor = level.getServer().getPlayerList().getPlayer(current.actor().getId());
            if (actor == null || actor.serverLevel() != level) {
                actor = FakePlayerFactory.get(level, current.actor());
                actor.moveTo(Vec3.atCenterOf(getPos()));
            }
            if (actor.mayBuild() && level.mayInteract(actor, getPos()) && level.mayInteract(actor, targetPos())) {
                try {
                    if (input.source().isPresent())
                        verified = new SerialControlAccess(level, getPos(), actor).source(current.source(), input.source().orElseThrow());
                } catch (CableDiscovery.DiscoveryException denied) { last = denied.code(); }
            }
            var target = target(level);
            var live = target.slot().orElse(null);
            var status = target.status();
            if (status.equals("ready") && !current.target().equals(binding(live, current))) status = "target_changed";
            var command = (DataValue.RecordValue)input.result().field("value");
            int limit = CBConfigs.SERVER.biocomputing.virtualFilterMatchers(CBConfigs.SERVER_SPEC.isLoaded());
            if (current.list()) {
                processList(input, current, verified, live, status, command, limit);
                return;
            }
            var before = live == null ? VirtualFilterSlot.empty(VirtualFilterRule.Domain.ITEM, false) : live.state();
            var plan = VirtualFilterProtocol.plan(before, live == null ? current.target() : live.identity(), session, current.source().computer(),
                verified, address(), command, limit, VirtualFilterTarget.REGISTRY, status, live == null ? "ready" : live.writeStatus());
            if (plan.isEmpty()) { if (input.publish()) last = "not_addressed"; return; }
            var prepared = plan.orElseThrow();
            var exchange = input.replying(prepared.reply());
            if (current != grant || !available()) return;
            boolean committed = prepared.next() == before ? exchange.publish() : live.publish(before, prepared.next(), exchange);
            if (committed) last = ((DataValue.TextValue)prepared.reply().field("code")).value();
        } catch (SerialRecordCodec.Failure failure) {
            if (input.publish()) last = "reply_capacity";
        }
    }
    private static UUID binding(VirtualFilterTarget slot, Grant grant) { return grant.list() ? slot.listBindingId() : slot.bindingId(); }

    private void processList(SerialDevice.Prepared input, Grant grant, UUID source, VirtualFilterTarget live, String status,
            DataValue.RecordValue command, int limit) {
        var plan = VirtualListFilterProtocol.plan(live, status, session, grant.source().computer(), source, address(), command, limit);
        if (plan.isEmpty()) { if (input.publish()) last = "not_addressed"; return; }
        var prepared = plan.orElseThrow();
        var exchange = input.replying(prepared.reply());
        if (this.grant != grant || !available()) return;
        boolean committed = prepared.after().isEmpty() ? exchange.publish()
            : live.publishList(prepared.before(), prepared.after().orElseThrow(), exchange);
        if (committed) last = ((DataValue.TextValue)prepared.reply().field("code")).value();
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT);
        if (clientPacket) return;
        if (invalid != null) { tag.put(ROOT, invalid.copy()); return; }
        var state = serial.write();
        if (grant == null && state == null) return;
        var root = new CompoundTag();
        root.putInt("Version", 1);
        if (grant != null) {
            root.put("Source", grant.source().save());
            root.putUUID("Grantor", grant.actor().getId());
            root.putString("Name", grant.actor().getName());
            root.putUUID("Target", grant.target());
            root.putString("Face", grant.face().getName());
            if (grant.list()) root.putBoolean("List", true);
        }
        if (state != null) root.put("Serial", state);
        tag.put(ROOT, root);
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        resetActivity();
        grant = null; serial.read(null); session = UUID.randomUUID(); last = "idle";
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root) || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1) return;
            var keys = new HashSet<>(Set.of("Version"));
            if (root.contains("Serial", Tag.TAG_COMPOUND)) keys.add("Serial");
            Grant restored = null;
            if (root.contains("Source")) {
                keys.addAll(Set.of("Source", "Grantor", "Name", "Target", "Face"));
                if (root.contains("List", Tag.TAG_BYTE)) keys.add("List");
                if (!root.hasUUID("Grantor") || !root.hasUUID("Target") || !root.contains("Face", Tag.TAG_STRING)
                    || !root.contains("Name", Tag.TAG_STRING) || !root.contains("Serial", Tag.TAG_COMPOUND)) return;
                var face = Direction.byName(root.getString("Face"));
                var name = root.getString("Name");
                if (face == null || name.isBlank() || name.length() > 64) return;
                restored = new Grant(SerialControlAccess.Binding.load(root.get("Source")),
                    new GameProfile(root.getUUID("Grantor"), name), root.getUUID("Target"), face, root.getBoolean("List"));
            }
            if (!keys.equals(root.getAllKeys())) return;
            serial.read(root.get("Serial")); grant = restored; invalid = null;
        } catch (IllegalArgumentException rejected) { /* Keep the exact invalid archive and stay closed. */ }
    }
}
