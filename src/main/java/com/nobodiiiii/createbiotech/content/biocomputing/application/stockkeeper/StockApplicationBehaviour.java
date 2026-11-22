package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPackagingBackend;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.BlockReaderBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.peripheral.*;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

import net.minecraft.world.phys.Vec3;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplication.*;

public final class StockApplicationBehaviour extends SerialPeripheralBehaviour {
    public static final BehaviourType<StockApplicationBehaviour> TYPE = new BehaviourType<>();
    private static final String CORE = "BiocomputingStockApplication", BINDING = "BiocomputingStockGrant", SERIAL = "BiocomputingStockSerial", TARGET = "BiocomputingStockTarget";
    private static final List<String> ROOTS = List.of(CORE, BINDING, SERIAL, TARGET);
    private final StockLinkOutputBlockEntity output;
    private final SerialDevice device;
    private final StockMaterialOutput materials = new StockMaterialOutput(this);
    private StockApplicationSerial owner;
    private Grant grant;
    private StockLinkTarget anchor;
    private long withdrawn = Long.MIN_VALUE;
    private final Map<String, Tag> retained = new LinkedHashMap<>();
    private boolean invalid, decoded = true;
    private String last = "idle";

    record Candidate(SerialControlAccess.Binding binding, Application application) {}
    private record Grant(Candidate candidate, UUID network, GameProfile grantor) {
        CompoundTag save() {
            var tag = new CompoundTag(); tag.putInt("Version", 1);
            tag.put("Source", candidate.binding().save()); tag.putUUID("Installation", candidate.application().program());
            tag.putUUID("Network", network); tag.putUUID("Grantor", grantor.getId()); tag.putString("Name",grantor.getName());
            return tag;
        }
        static Grant load(Tag raw) {
            if (!(raw instanceof CompoundTag tag) || !tag.getAllKeys().equals(Set.of("Version", "Source", "Installation", "Network", "Grantor", "Name"))
                || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1 || !tag.hasUUID("Installation")
                || !tag.hasUUID("Network") || !tag.hasUUID("Grantor") || !tag.contains("Name",Tag.TAG_STRING)
                || tag.getString("Name").isBlank() || tag.getString("Name").length() > 16) throw new IllegalArgumentException("invalid_grant");
            var binding = SerialControlAccess.Binding.load(tag.get("Source"));
            return new Grant(new Candidate(binding, new Application(binding.computer(), tag.getUUID("Installation"))),
                tag.getUUID("Network"), new GameProfile(tag.getUUID("Grantor"),tag.getString("Name")));
        }
    }

    public StockApplicationBehaviour(StockLinkOutputBlockEntity output) {
        super(output); this.output = output;
        device = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.stockSerial(CBConfigs.SERVER_SPEC.isLoaded()),
            this::loaded, () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), output::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return device; }
    public StockLinkOutputBlockEntity output() { return output; }
    Limits limits() { return CBConfigs.SERVER.biocomputing.stockApplication(CBConfigs.SERVER_SPEC.isLoaded()); }
    private boolean loaded() {
        if (output.isRemoved() || !(output.getLevel() instanceof ServerLevel level)) return false;
        var pos = output.getBlockPos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(pos) == output && level.shouldTickBlocksAt(pos);
    }
    @Override protected boolean available() { return loaded() && decoded && !invalid && owner != null && owner.state().open(); }
    private Direction facing() { return output.getBlockState().getValue(StockLinkOutputBlock.FACING); }
    private Direction rear() { return facing().getOpposite(); }
    BlockPos targetPosition() { return output.getBlockPos().relative(facing()); }
    Optional<StockLinkTarget> target() { restore(); return Optional.ofNullable(anchor); }
    boolean targets(PackagerLinkBlockEntity link) {
        restore();
        return !invalid && anchor != null && loaded() && targetPosition().equals(anchor.position())
            && anchor.resolve((ServerLevel)output.getLevel(), output.getBlockPos()) == link;
    }
    private PackagerLinkBlockEntity adjacentLink() {
        if (!loaded()) return null;
        var level = (ServerLevel)output.getLevel();
        return SubLevelCompat.sameSpace(level, output.getBlockPos(), targetPosition())
            && StockLinkTarget.loaded(level, targetPosition()) instanceof PackagerLinkBlockEntity link && link.behaviour != null ? link : null;
    }
    private PackagerLinkBlockEntity link() {
        var link = adjacentLink();
        return link != null && targets(link) ? link : null;
    }
    private SerialControlAccess world(ServerPlayer actor) { return new SerialControlAccess((ServerLevel)output.getLevel(), output.getBlockPos(), actor); }
    boolean mayView(ServerPlayer actor) {
        return loaded() && actor.serverLevel() == output.getLevel() && actor.isAlive() && !actor.isSpectator()
            && actor.distanceToSqr(Vec3.atCenterOf(output.getBlockPos())) <= 64 && actor.serverLevel().mayInteract(actor, output.getBlockPos());
    }
    boolean mayManage(ServerPlayer actor) {
        if (!mayView(actor) || !actor.mayBuild()) return false;
        var link = adjacentLink();
        return link != null && actor.serverLevel().mayInteract(actor, link.getBlockPos()) && link.behaviour.mayAdministrate(actor);
    }
    private boolean mayWithdraw(ServerPlayer actor) {
        if (!mayView(actor) || !actor.mayBuild()) return false;
        restore();
        if (anchor == null || invalid) return false;
        var level = actor.serverLevel();
        var retainedLink = anchor.resolve(level,output.getBlockPos());
        if (retainedLink != null)
            return level.mayInteract(actor,retainedLink.getBlockPos()) && retainedLink.behaviour.mayAdministrate(actor);
        // The saved grantor can explicitly recover after loss, subject to the OLD network's
        // current ownership. Authority over whichever replacement Link faces us is irrelevant.
        return grant != null && actor.getUUID().equals(grant.grantor().getId()) && grantAuthorizedNetwork();
    }
    private boolean grantAuthorizedNetwork() {
        if (grant == null || anchor == null || !grant.network().equals(anchor.network())) return false;
        var network = Create.LOGISTICS.logisticsNetworks.get(grant.network());
        return network == null || network.owner == null || network.owner.equals(grant.grantor().getId());
    }
    private boolean grantAuthorized(PackagerLinkBlockEntity link, ServerPlayer actor) {
        var level = actor.serverLevel();
        return level.mayInteract(actor,output.getBlockPos()) && level.mayInteract(actor,link.getBlockPos())
            && link.behaviour.mayAdministrate(actor);
    }

    private record Verified(String code, Optional<Access> access, Optional<SerialRecordCodec.Limits> receiver) {}
    private Verified verify(SharedSerialChannel.Port source) {
        if (!available() || grant == null) return denied(invalid ? "invalid_configuration" : "closed");
        if (source != null && !grant.candidate().binding().source().equals(source)) return denied("permission");
        var link = link();
        if (link == null) return denied("link_unavailable");
        if (!grantAuthorizedNetwork()) return denied("permission");
        var actor = StockApplicationGrantor.worldActor((ServerLevel)output.getLevel(),grant.grantor(),output.getBlockPos()).orElse(null);
        if (actor == null || !grantAuthorized(link,actor)) return denied("permission");
        if (StockLinkTarget.adjacentOutputs(link).stream().filter(other -> other.owner != null && other.owner.state().open()).count() != 1)
            return denied("ambiguous_output");
        var current = device.view().ports().get(StockApplicationHost.PORT);
        if (current == null || !current.enabled() || current.side().filter(rear()::equals).isEmpty()) return denied("closed");
        try {
            var candidate = grant.candidate();
            var computer = world(actor).verify(candidate.binding());
            var installed = computer.installedProgram();
            if (installed.isEmpty()) return denied("unavailable");
            var found = installed.orElseThrow();
            if (!candidate.application().equals(new Application(found.computer(), found.installation()))) return denied("program_changed");
            var access = new Access(candidate.application(), true, found.paused());
            return new Verified(found.paused() ? "paused" : "ready", Optional.of(access),
                Optional.of(computer.serialDevice().encodingLimits(candidate.binding().pin())));
        } catch (CableDiscovery.DiscoveryException failure) { return denied(failure.code()); }
    }
    private static Verified denied(String code) { return new Verified(code, Optional.empty(), Optional.empty()); }
    List<Candidate> discover(ServerPlayer actor) {
        if (!mayManage(actor) || invalid) return List.of();
        var world = world(actor);
        var result = new ArrayList<Candidate>();
        for (var binding : world.discover()) world.verify(binding).installedProgram().ifPresent(found ->
            result.add(new Candidate(binding, new Application(found.computer(), found.installation()))));
        return List.copyOf(result);
    }
    State state() {
        restore();
        if (owner == null && !invalid && loaded()) {
            owner = new StockApplicationSerial(closed(UUID.randomUUID(), UUID.randomUUID())); output.setChanged();
        }
        return owner == null ? null : owner.state();
    }
    String configure(ServerPlayer actor, Fence fence, Optional<Candidate> choice) {
        if (!(choice.isPresent() ? mayManage(actor) : mayWithdraw(actor))) return "access_denied";
        var before = state();
        if (before == null || invalid) return "invalid_configuration";
        if (!before.fence().equals(fence)) return "changed";
        if (choice.isEmpty()) {
            materials.revoke(); // Independent catalogue withdrawal must not depend on material storage.
            owner.revoke(UUID.randomUUID()); withdraw(); output.setChanged();
            // Revocation remains effective even if the retained serial interface cannot be reconfigured.
            try { device.configure(StockApplicationHost.PORT, rear(), false); }
            catch (IllegalArgumentException ignored) { device.unload(); }
            return "revoked";
        }
        var selected = choice.orElseThrow();
        var program = world(actor).verify(selected.binding()).installedProgram();
        if (program.isEmpty()) return "unavailable";
        if (!selected.application().equals(new Application(program.orElseThrow().computer(), program.orElseThrow().installation())))
            return "program_changed";
        var link = adjacentLink();
        if (link == null) return "link_unavailable";
        boolean sameTarget = targets(link);
        if (!before.inputs().isEmpty() && !sameTarget) return "busy";
        if (StockLinkTarget.adjacentOutputs(link).stream().anyMatch(other -> other != this && other.owner != null && other.owner.state().open()))
            return "ambiguous_output";
        var update = StockApplication.bind(before, selected.application(), UUID.randomUUID());
        if (!update.code().equals("bound")) return update.code();
        if (device.diagnostic().isPresent()) return "invalid_configuration";
        var nextTarget = StockLinkTarget.capture(link);
        materials.revoke(); // Any unreconciled material grant stays locally suspended and separately visible.
        device.configure(StockApplicationHost.PORT, rear(), true);
        withdraw();
        var next = update.state();
        if (!sameTarget) next = new State(next.device(),next.session(),next.revision(),next.application(),next.open(),
            next.nextSequence(),List.of(),next.inputs());
        owner = new StockApplicationSerial(next);
        anchor = nextTarget;
        grant = new Grant(selected, anchor.network(), actor.getGameProfile());
        output.setChanged();
        return update.code();
    }
    UUID materialSession() { return materials.session(); }
    String materialAddress() { return materials.address(); }
    String materialStatus() { return materials.status(); }
    String materialStatus(StockMaterialGrant material) {
        String physical = outputStatus();
        return Set.of("ready", "paused").contains(physical) && materialAuthority(material.source().computer()).isEmpty()
            ? "permission" : physical;
    }
    boolean mayGrantMaterials(ServerPlayer actor) {
        return mayManage(actor) && link() != null && verify(null).access().isPresent();
    }
    String configureMaterials(ServerPlayer actor, Fence fence, UUID session, Optional<String> route) {
        if (!(route.isPresent() ? mayGrantMaterials(actor) : mayWithdraw(actor))) return "access_denied";
        var current = state();
        if (current == null || !current.fence().equals(fence) || !materialSession().equals(session)) return "changed";
        if (route.isPresent()) return materials.configure(route.orElseThrow(), actor.getGameProfile(),
            grant.candidate().binding(), grant.candidate().application().program(), anchor);
        var failure = materials.revoke();
        return failure == null ? "materials_revoked" : failure;
    }
    Optional<ProductionPackagingBackend.Route> materialAuthority(UUID source) {
        return materialAuthority(source, null);
    }
    Optional<ProductionPackagingBackend.Route> materialAuthority(UUID source, SharedSerialChannel.Port sender) {
        var selected = materials.grant();
        if (selected == null || grant == null || !selected.source().computer().equals(source)
            || !selected.source().equals(grant.candidate().binding())
            || !selected.installation().equals(grant.candidate().application().program())
            || !selected.target().equals(anchor)) return Optional.empty();
        var proof = verify(sender).access();
        if (proof.isEmpty() || sender != null && proof.orElseThrow().paused()) return Optional.empty();
        var target = link();
        if (target == null) return Optional.empty();
        var actor = StockApplicationGrantor.worldActor((ServerLevel)output.getLevel(), selected.grantor(), output.getBlockPos());
        return actor.filter(value -> grantAuthorized(target, value))
            .map(value -> new ProductionPackagingBackend.Route(target.behaviour.freqId, selected.address(), selected.revision()));
    }
    String discard(ServerPlayer actor, Fence fence, long sequence, int count) {
        if (!mayWithdraw(actor)) return "access_denied";
        var before = state();
        if (before == null || invalid) return "invalid_configuration";
        if (!before.fence().equals(fence) || before.nextSequence() != sequence || before.inputs().size() != count || count == 0) return "changed";
        owner.discardInputs(UUID.randomUUID()); output.setChanged();
        return "discarded";
    }

    private record ReadPath(String code, ReaderSerialBehaviour.ApplicationPath path) {}
    private ReadPath readerPath() {
        var link = link();
        if (link == null || grant == null) return new ReadPath("link_unavailable", null);
        var level = (ServerLevel)output.getLevel();
        var found = new ArrayList<ReaderSerialBehaviour.ApplicationPath>();
        for (var direction : Direction.values()) {
            var pos = link.getBlockPos().relative(direction);
            if (!SubLevelCompat.sameSpace(level, link.getBlockPos(), pos)
                || !(StockLinkTarget.loaded(level,pos) instanceof BlockReaderBlockEntity reader)
                || reader.getBlockState().getValue(BlockReaderBlock.FACING) != direction.getOpposite()) continue;
            var serial = reader.getBehaviour(ReaderSerialBehaviour.TYPE);
            if (serial != null) serial.applicationPath(link.getBlockPos(), grant.candidate().application().computer(),
                CreateReadComponents.STOCK_APPLICATION).ifPresent(found::add);
        }
        if (found.isEmpty()) return new ReadPath("reader_unavailable",null);
        return found.size() == 1 ? new ReadPath("ready",found.getFirst()) : new ReadPath("ambiguous_reader",null);
    }

    String confirm(ServerPlayer actor, Request request) {
        if (state() == null) return "invalid_configuration";
        var proof = verify(null);
        if (!proof.code().equals("ready")) return proof.code();
        var link = link();
        if (link == null || !link.behaviour.mayInteract(actor) || !actor.serverLevel().mayInteract(actor,link.getBlockPos())) return "access_denied";
        if (request.nativeOrder().isPresent()) {
            try { StockNativeOrder.recapture(request.nativeOrder().orElseThrow(),actor.registryAccess(),limits()); }
            catch (IllegalArgumentException invalid) { return RetiredStockOrders.rejectionCode(invalid); }
        }
        for (var selection : request.selections()) {
            var row = owner.state().catalog().stream().filter(e -> e.key().equals(selection.key())).findFirst();
            if (row.isPresent() && StockApplicationIcons.resolve(row.orElseThrow().icon(), actor.registryAccess()).isEmpty()) return "invalid_icon";
        }
        var path = readerPath();
        if (path.path() == null) return path.code();
        var update = owner.confirm(proof.access().orElseThrow(), actor.getUUID(), request, limits(),
            (next, input) -> StockApplicationHost.fitsReader(next, input, path.path())
                && StockApplicationHost.fitsOutput(next, StockApplicationHost.address(output.getBlockPos()),
                    device.encodingLimits(StockApplicationHost.PORT), proof.receiver().orElseThrow()));
        if (update.code().equals("input_queued")) output.setChanged();
        return update.code();
    }
    String outputStatus() { return invalid ? "invalid_configuration" : verify(null).code(); }
    String status() {
        String code = outputStatus();
        return code.equals("ready") ? readerPath().code() : code;
    }
    static DataValue readPublic(PackagerLinkBlockEntity link) {
        var owners = StockLinkTarget.adjacentOutputs(link).stream().filter(owner -> owner.owner != null && owner.owner.state().open()).toList();
        if (owners.size() != 1) return new DataValue.ErrorValue(owners.isEmpty() ? "output_unavailable" : "ambiguous_output", "");
        var service = owners.getFirst();
        var status = service.outputStatus();
        if (!status.equals("ready")) return new DataValue.ErrorValue(status, "");
        var state = service.owner.state();
        return StockApplicationHost.publicInput(state, state.inputs().stream().findFirst());
    }
    public static DataValue readComponent(BlockReadContext context) {
        if (!(context.entity() instanceof PackagerLinkBlockEntity link) || link.behaviour == null
            || !link.behaviour.mayInteract(context.actor())) return new DataValue.ErrorValue("access_denied", "");
        return readPublic(link);
    }
    boolean canWithdraw(ServerPlayer actor) { return mayWithdraw(actor); }
    String last() { return last; }
    String face() { return rear().getName(); }
    Optional<DataValue.RecordValue> binding() { return Optional.ofNullable(grant).map(value -> value.candidate().binding().summary()); }

    private void withdraw() {
        device.unload(); resetActivity();
        if (output.getLevel() instanceof ServerLevel level) withdrawn = level.getGameTime() + 1;
    }
    void reorient() {
        if (!loaded()) return;
        restore();
        if (owner != null) owner.refreshSession(UUID.randomUUID());
        materials.revoke();
        withdraw(); output.setChanged();
    }
    @Override public void tick() {
        if (!loaded() || output.getLevel().getGameTime() <= withdrawn) return;
        restore();
        if (available()) {
            var port = device.view().ports().get(StockApplicationHost.PORT);
            if (port != null && port.side().filter(rear()::equals).isEmpty()) {
                try { device.configure(StockApplicationHost.PORT, rear(), true); }
                catch (IllegalArgumentException failure) { resetActivity(); device.unload(); last = "invalid_configuration"; return; }
            }
        }
        super.tick();
    }
    void depart() {
        if (output.getLevel() instanceof ServerLevel) materials.revoke();
        unload();
    }
    @Override public void unload() { materials.unload(); super.unload(); }
    @Override protected void processReceived() {
        // SerialWorldTicks invokes this once after all block entities have ticked.
        // Gateway authority requires the current tick, so advancing in block tick
        // would starve orders whenever this output precedes its controller.
        materials.tick();
        if (!available() || device.diagnostic().isPresent()) return;
        var code = StockApplicationHost.receive(owner, device, source -> verify(source).access(),
            StockApplicationHost.address(output.getBlockPos()), limits(), output::setChanged, materials::respond);
        if (!code.equals("idle") && !code.equals("busy")) last = code;
    }

    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        materials.read(tag.get(StockMaterialOutput.ROOT));
        resetActivity(); owner = null; grant = null; anchor = null; withdrawn = Long.MIN_VALUE; device.read(null); invalid = false; last = "idle"; retained.clear();
        for (var key : ROOTS) if (tag.contains(key)) retained.put(key, tag.get(key).copy());
        decoded = retained.isEmpty();
    }
    private void restore() {
        if (decoded || !loaded()) return;
        decoded = true;
        try {
            if (!(retained.get(CORE) instanceof CompoundTag tag)) throw new IllegalArgumentException("invalid_configuration");
            var state = StockApplicationCodec.load(tag, UUID.randomUUID());
            var selected = retained.containsKey(BINDING) ? Grant.load(retained.get(BINDING)) : null;
            var selectedTarget = retained.containsKey(TARGET) ? StockLinkTarget.load(retained.get(TARGET)) : null;
            if (state.open() && selected == null
                || selected != null && (selectedTarget == null || !selectedTarget.network().equals(selected.network())
                    || !state.application().filter(selected.candidate().application()::equals).isPresent() || !retained.containsKey(SERIAL)))
                throw new IllegalArgumentException("invalid_configuration");
            device.read(retained.get(SERIAL));
            owner = new StockApplicationSerial(state); grant = selected; anchor = selectedTarget; retained.clear();
        } catch (IllegalArgumentException failure) { invalid = true; }
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        ROOTS.forEach(tag::remove);
        tag.remove(StockMaterialOutput.ROOT);
        if (clientPacket) return;
        var materialArchive = materials.save();
        if (materialArchive != null) tag.put(StockMaterialOutput.ROOT, materialArchive);
        if (!decoded || invalid) { retained.forEach((key, value) -> tag.put(key, value.copy())); return; }
        if (owner != null) tag.put(CORE, StockApplicationCodec.save(owner.state()));
        if (grant != null) tag.put(BINDING, grant.save());
        if (anchor != null) tag.put(TARGET,anchor.save());
        var serial = device.write();
        if (serial != null) tag.put(SERIAL, serial);
    }
}
