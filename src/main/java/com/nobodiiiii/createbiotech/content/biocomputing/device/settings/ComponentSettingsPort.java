package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

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
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.settings.ComponentSettingsProtocol.*;

/** One adjacent component, one source, one short-lived compare token. Never resumes an old write on load. */
final class ComponentSettingsPort extends SerialPeripheralBehaviour {
    static final BehaviourType<ComponentSettingsPort> TYPE = new BehaviourType<>();
    private static final String PIN = "s0";
    private record Grant(SerialControlAccess.Binding source, GameProfile actor, Object witness, Direction face) {}
    private record Target(String code, ComponentSettings.Observation settings, Object witness) {
        SequencedGearshiftBlockEntity sequencer() {
            return witness instanceof SequencedGearshiftBlockEntity gearshift ? gearshift : null;
        }
        ArmBlockEntity arm() { return witness instanceof ArmBlockEntity arm ? arm : null; }
        DeployerBlockEntity deployer() { return witness instanceof DeployerBlockEntity deployer ? deployer : null; }
    }
    private record ReadToken(UUID nonce, ComponentSettings.Observation snapshot, long modeRevision) {}
    private record MotionToken(UUID nonce, String readiness, UUID previousAction) {}
    private record Reply(String code, DataValue state) {
        Reply(String code) { this(code, DataValue.NullValue.INSTANCE); }
    }
    private final SerialDevice serial;
    private Grant grant;
    private ReadToken readToken;
    private MotionToken motionToken;
    private ArmControl.Read armToken;
    private record DeployerToken(UUID nonce, Grant grant, DeployerBlockEntity device, UUID previous, long modeRevision, long filterRevision, String availability,
            TransportedItemStackHandlerBehaviour worksite, ItemStack tool, ItemStack filter) {}
    private DeployerToken deployerToken;
    private Target prior;
    private Object neighbour = new Object();
    private Block neighbourKind;
    private String last = "idle";

    ComponentSettingsPort(ComponentSettingsBlockEntity owner) {
        super(owner);
        serial = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.componentSettingsSerial(CBConfigs.SERVER_SPEC.isLoaded()),
            this::hardwareReady, () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), owner::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return serial; }
    private Direction face() { return blockEntity.getBlockState().getValue(ComponentSettingsBlock.FACING); }
    private BlockPos targetPos() { return getPos().relative(face()); }
    private boolean hardwareReady() {
        if (!(getWorld() instanceof ServerLevel level) || blockEntity.isRemoved()
            || !level.getWorldBorder().isWithinBounds(getPos()) || !level.shouldTickBlocksAt(getPos())) return false;
        var chunk = level.getChunkSource().getChunkNow(getPos().getX() >> 4, getPos().getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(getPos()) == blockEntity;
    }
    @Override protected boolean available() { return hardwareReady() && grant != null && grant.face() == face(); }

    void neighbourChanged(BlockState state) {
        if (neighbourKind == state.getBlock()) return;
        neighbourKind = state.getBlock(); neighbour = new Object(); clearReadTokens();
    }

    private Target target() {
        var level = (ServerLevel)getWorld();
        var p = targetPos();
        var chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
        if (chunk == null || !level.shouldTickBlocksAt(p)) return new Target("target_unloaded", null, null);
        if (!level.getWorldBorder().isWithinBounds(p) || !SubLevelCompat.sameSpace(level, getPos(), p))
            return new Target("target_unavailable", null, null);
        var state = chunk.getBlockState(p);
        neighbourChanged(state);
        if (state.isAir()) return new Target("target_missing", null, null);
        if (ComponentSettings.noPlayerSettings(state)) return new Target("no_player_settings", null, null);
        var entity = chunk.getBlockEntity(p);
        if (AllBlocks.SEQUENCED_GEARSHIFT.has(state) && entity instanceof SequencedGearshiftBlockEntity gearshift && !gearshift.isRemoved())
            return new Target("ready", null, gearshift);
        if (AllBlocks.MECHANICAL_ARM.has(state) && entity instanceof ArmBlockEntity arm && !arm.isRemoved())
            return new Target("ready", ComponentSettings.observe(level, p).orElse(null), arm);
        if (AllBlocks.DEPLOYER.has(state) && entity instanceof DeployerBlockEntity deployer && !deployer.isRemoved() && DeployerControl.supported())
            return new Target("ready", ComponentSettings.observe(level, p).orElse(null), deployer);
        var settings = ComponentSettings.observe(level, p).orElse(null);
        if (settings == null) return new Target("unsupported_component", null, null);
        return new Target("ready", settings, entity == null ? neighbour : entity);
    }

    private Target observe() {
        var next = target();
        if (prior == null || prior.witness() != next.witness() || !prior.code().equals(next.code())) {
            clearReadTokens();
        }
        if (readToken != null && next.settings() != null) {
            var previous = readToken.snapshot();
            var selected = next.settings().field().equals(previous.field()) ? next.settings()
                : ComponentSettings.observe((ServerLevel)getWorld(), targetPos(), previous.field().name()).orElse(null);
            if (!sameSetting(previous, selected) || readToken.modeRevision() != modeRevision(next)) readToken = null;
        }
        if (prior != null && prior.code().equals("ready") && next.code().equals("target_unloaded")) {
            // On load even the same block-state-only component is not provably the same placement.
            neighbour = new Object();
        }
        prior = next;
        return next;
    }

    @Override public void tick() {
        if (available()) observe();
        super.tick();
    }

    boolean mayManage(ServerPlayer player) {
        return hardwareReady() && player.serverLevel() == getWorld() && player.isAlive() && !player.isSpectator()
            && player.mayBuild() && SubLevelCompat.sameSpace(player.serverLevel(), getPos(), targetPos())
            && SubLevelCompat.distanceSquared(player.serverLevel(), Vec3.atCenterOf(getPos()), player.position()) <= 64
            && player.serverLevel().mayInteract(player, getPos());
    }
    List<SerialControlAccess.Binding> discover(ServerPlayer actor) {
        return new SerialControlAccess(actor.serverLevel(), getPos(), actor).discover();
    }
    Object observedTarget() { return observe().witness(); }
    Object observedGrant() { return grant; }
    String configure(ServerPlayer actor, Object seenGrant, Object seenTarget, Optional<SerialControlAccess.Binding> binding) {
        if (!mayManage(actor)) return "access_denied";
        if (seenGrant != grant) return "changed";
        if (binding.isEmpty()) { close(); return "configured"; }
        var target = observe();
        if (!target.code().equals("ready")) return target.code();
        if (target.witness() != seenTarget) return "target_changed";
        if (!actor.serverLevel().mayInteract(actor, targetPos())) return "access_denied";
        var selected = binding.orElseThrow();
        new SerialControlAccess(actor.serverLevel(), getPos(), actor).verify(selected);
        if (serial.diagnostic().isPresent()) return serial.diagnostic().orElseThrow();
        serial.configure(PIN, face().getOpposite(), true);
        grant = new Grant(selected, actor.getGameProfile(), target.witness(), face());
        clearReadTokens(); last = "idle"; resetActivity();
        return "configured";
    }
    void close() {
        grant = null; clearReadTokens(); prior = null; last = "idle"; resetActivity();
        try { serial.configure(PIN, face().getOpposite(), false); }
        catch (IllegalArgumentException unsupported) { serial.unload(); }
    }
    @Override public void unload() {
        grant = null; clearReadTokens(); prior = null; neighbour = new Object(); neighbourKind = null;
        super.unload();
    }
    private void clearReadTokens() {
        readToken = null;
        motionToken = null;
        armToken = null;
        deployerToken = null;
    }

    private static long modeRevision(Target target) {
        return target.deployer() instanceof DeployerControl.Access access ? access.biocomputingModeRevision() : 0L;
    }
    DataValue.RecordValue address() {
        return record(Map.of("x", number(getPos().getX()), "y", number(getPos().getY()), "z", number(getPos().getZ())));
    }
    DataValue.RecordValue view() {
        var actual = observe();
        var settings = actual.settings();
        var gearshift = actual.sequencer();
        var arm = actual.arm();
        var deployer = actual.deployer();
        var inversion = settings == null || !ComponentSettings.fields(settings).contains("inverted") ? DataValue.NullValue.INSTANCE
            : ComponentSettings.observe((ServerLevel)getWorld(), targetPos(), "inverted").map(ComponentSettings.Observation::value)
                .orElse(DataValue.NullValue.INSTANCE);
        var doorMode = settings == null || !settings.field().name().equals("floor_names") ? DataValue.NullValue.INSTANCE
            : ComponentSettings.observe((ServerLevel)getWorld(), targetPos(), "door_control").map(ComponentSettings.Observation::value)
                .orElse(DataValue.NullValue.INSTANCE);
        return record(Map.ofEntries(Map.entry("target", text(targetPos().toShortString())), Map.entry("face", text(face().getName())),
            Map.entry("target_status", text(status(actual))), Map.entry("device", gearshift != null ? text("create:sequenced_gearshift")
                : arm != null ? text("create:mechanical_arm")
                : deployer != null ? text("create:deployer")
                : settings == null ? text("—") : text(settings.device().toString())),
            Map.entry("field", settings == null ? text("—") : text(settings.field().name())), Map.entry("unit", settings == null ? text("—") : text(settings.field().unit())),
            Map.entry("value", settings == null ? DataValue.NullValue.INSTANCE : settings.value()), Map.entry("last", text(last)),
            Map.entry("binding", grant == null ? DataValue.NullValue.INSTANCE : grant.source().summary()),
            Map.entry("line", text(controlLine())),
            Map.entry("inversion", inversion),
            Map.entry("door_control", doorMode),
            Map.entry("sequence", gearshift == null ? DataValue.NullValue.INSTANCE : SequencedMotion.state(gearshift, null)),
            Map.entry("arm", arm == null ? DataValue.NullValue.INSTANCE : ArmControl.summary(arm)),
            Map.entry("deployer", deployer == null ? DataValue.NullValue.INSTANCE : DeployerControl.summary(deployer)),
            Map.entry("motion", settings == null ? DataValue.NullValue.INSTANCE
                : ComponentSettings.motion((ServerLevel)getWorld(), targetPos()).<DataValue>map(v -> v).orElse(DataValue.NullValue.INSTANCE))));
    }
    private String status(Target actual) {
        return actual.code().equals("ready") && grant != null && actual.witness() != grant.witness() ? "target_changed" : actual.code();
    }
    private String controlLine() {
        var current = grant;
        if (current == null) return "closed";
        var physical = serial.status(PIN).connection();
        if (!physical.equals("ready")) return physical;
        // A local cable stub can have carrier without reaching the authorized source.
        var actor = controlActor(current);
        if (actor == null) return "access_denied";
        try {
            new SerialControlAccess((ServerLevel)getWorld(), getPos(), actor).verify(current.source());
            return physical;
        } catch (CableDiscovery.DiscoveryException failure) { return failure.code(); }
    }
    private static boolean sameSetting(ComponentSettings.Observation first, ComponentSettings.Observation second) {
        return second != null && first.device().equals(second.device())
            && first.field().equals(second.field()) && first.value().equals(second.value())
            && java.util.Objects.equals(first.binding(), second.binding())
            // An observation of a joined/waxed plate must not become a write authorization after the plate changes.
            && (!(first.field().format() instanceof ComponentSettings.LabelText) || first.busy() == second.busy());
    }

    @Override protected void processReceived() {
        if (!available() || serial.diagnostic().isPresent() || List.of("queued", "transmitting").contains(serial.status(PIN).transmit())) return;
        var received = serial.prepareReceive(PIN);
        if (!text("received").equals(received.result().field("status"))) {
            if (serial.status(PIN).receive().frames() > 0) {
                var raw = serial.prepareReceive(PIN, true);
                if (text("received").equals(raw.result().field("status")) && raw.publish()) last = "unsupported_format";
            }
            return;
        }
        var addressed = addressed(address(), (DataValue.RecordValue)received.result().field("value"));
        if (addressed.isEmpty()) { if (received.publish()) last = "not_addressed"; return; }
        var command = addressed.orElseThrow();
        try {
            var current = grant;
            var actual = observe();
            var actor = authorizedActor(current, received);
            var result = actor != null ? process(command, received, actual, actor) : new Reply("denied");
            if (result != null) publishReply(command, received, result);
        } catch (SerialRecordCodec.Failure failure) {
            if (received.publish()) last = "reply_capacity";
        } catch (IllegalArgumentException rejected) {
            if (received.publish()) last = "changed";
        }
    }

    private Reply process(Command command, SerialDevice.Prepared input, Target actual, ServerPlayer actor) {
        var code = validate(command);
        if (!code.equals("ok")) return new Reply(code);
        code = status(actual);
        if (!code.equals("ready")) return new Reply(code);
        var args = (DataValue.RecordValue)command.frame().field("arguments");
        var operation = command.frame().field("operation");
        if (text("deployer_status").equals(operation) || text("deployer_process").equals(operation)) {
            var deployer = actual.deployer();
            if (deployer == null) return new Reply("unsupported_operation");
            var access = (DeployerControl.Access)deployer;
            if (text("deployer_status").equals(operation)) {
                var nonce = UUID.randomUUID();
                var worksite = DeployerControl.target(deployer, actor);
                var availability = DeployerControl.readiness(deployer, worksite);
                var player = deployer.getPlayer();
                var tool = player == null ? ItemStack.EMPTY : player.getMainHandItem().copy();
                var filter = access.biocomputingFilter();
                var state = DeployerControl.snapshot(deployer, actor, worksite, availability, nonce, tool, filter);
                try { input.replying(reply(address(), command, "ok", state)); }
                catch (SerialRecordCodec.Failure tooLarge) { return new Reply("reply_capacity"); }
                boolean ready = availability.equals("ok");
                deployerToken = new DeployerToken(nonce, grant, deployer, access.biocomputingProcessId(), access.biocomputingModeRevision(),
                    access.biocomputingFilterRevision(), availability, worksite,
                    ready ? tool : ItemStack.EMPTY, ready ? filter : ItemStack.EMPTY);
                return new Reply("ok", state);
            }
            var token = deployerToken;
            if (token == null || token.grant() != grant || token.device() != deployer
                || !text(token.nonce().toString()).equals(args.field("token"))
                || token.modeRevision() != access.biocomputingModeRevision()
                || token.filterRevision() != access.biocomputingFilterRevision()
                || !java.util.Objects.equals(token.previous(), access.biocomputingProcessId())) return new Reply("version_conflict");
            deployerToken = null;
            if (!token.availability().equals("ok")) return new Reply(token.availability());
            var target = DeployerControl.target(deployer, actor);
            var readiness = DeployerControl.readiness(deployer, target);
            if (!readiness.equals("ok")) return new Reply(readiness);
            if (target != token.worksite() || !ItemStack.matches(token.tool(), deployer.getPlayer().getMainHandItem())
                || !ItemStack.matches(token.filter(), access.biocomputingFilter())) return new Reply("version_conflict");
            var action = UUID.randomUUID();
            var state = record(Map.of("action", text(action.toString())));
            input.replying(reply(address(), command, "accepted", state));
            var expected = grant;
            var source = input.source().orElseThrow();
            return access.biocomputingAdmit(action, target, () -> {
                if (expected != grant || !available() || target().witness() != deployer) return false;
                var current = authorizedActor(expected, source);
                return current != null && DeployerControl.target(deployer, current) == target
                    && ((ServerLevel)getWorld()).hasNeighborSignal(deployer.getBlockPos());
            }) ? new Reply("accepted", state) : new Reply("changed");
        }
        if (text("arm_points_nbt").equals(operation)) {
            var arm = actual.arm();
            if (arm == null) return new Reply("unsupported_operation");
            try {
                var result = ArmControl.nativePoints(arm, actor);
                input.replying(reply(address(), command, "ok", result));
                return new Reply("ok", result);
            } catch (com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec.Failure exceeded) {
                return new Reply("snapshot_limit");
            } catch (SerialRecordCodec.Failure frameTooLarge) {
                return new Reply("reply_capacity");
            } catch (IllegalArgumentException unavailable) {
                return new Reply(java.util.Set.of("access_denied", "point_unloaded", "point_other_space", "snapshot_limit")
                    .contains(String.valueOf(unavailable.getMessage())) ? unavailable.getMessage() : "device_error");
            }
        }
        if (text("arm_prepare").equals(operation)) {
            var arm = actual.arm();
            if (arm == null) return new Reply("unsupported_operation");
            var access = (ArmControl.Access)arm;
            var availability = ArmControl.availability(arm, access);
            if (!availability.equals("ok")) return new Reply(availability);
            ArmControl.Read next;
            try { next = ArmControl.read(arm, actor, availability); }
            catch (RuntimeException unavailable) { return new Reply("device_error"); }
            if (next == null) return new Reply("snapshot_limit");
            var state = record(Map.of("token", text(next.token().toString())));
            input.replying(reply(address(), command, "ok", state));
            armToken = next;
            return new Reply("ok", state);
        }
        if (text("arm_status").equals(operation)) {
            var arm = actual.arm();
            if (arm == null) return new Reply("unsupported_operation");
            try {
                var snapshot = ArmControl.snapshot(arm, actor);
                if (snapshot.fields().isEmpty()) return new Reply("snapshot_limit");
                var access = (ArmControl.Access)arm;
                var next = ArmControl.read(arm, actor, ArmControl.availability(arm, access));
                if (next == null) return new Reply("snapshot_limit");
                var fields = new java.util.LinkedHashMap<>(snapshot.fields());
                fields.put("token", text(next.token().toString()));
                var replyState = record(fields);
                input.replying(reply(address(), command, "ok", replyState));
                armToken = next;
                return new Reply("ok", replyState);
            } catch (com.nobodiiiii.createbiotech.content.biocomputing.peripheral.SnapshotLimits.LimitExceeded exceeded) {
                return new Reply("snapshot_limit");
            } catch (SerialRecordCodec.Failure frameTooLarge) {
                return new Reply("reply_capacity");
            } catch (RuntimeException unavailable) {
                return new Reply("device_error");
            }
        }
        if (text("arm_move").equals(operation)) {
            var arm = actual.arm();
            if (arm == null) return new Reply("unsupported_operation");
            var observed = armToken;
            if (observed == null || !text(observed.token().toString()).equals(args.field("token"))) return new Reply("version_conflict");
            armToken = null;
            var admission = ArmControl.plan(arm, actor, observed, args);
            if (admission.route() == null) return new Reply(admission.code());
            var receipt = record(Map.of("action", text(admission.route().id().toString())));
            input.replying(reply(address(), command, "accepted", receipt));
            var expected = grant;
            var source = input.source().orElseThrow();
            return ((ArmControl.Access)arm).biocomputingAdmit(admission.route(),
                () -> expected == grant && available() && target().witness() == arm && authorizedActor(expected, source) != null)
                    ? new Reply("accepted", receipt) : new Reply("changed");
        }
        if (text("motion_status").equals(operation) || text("motion_start").equals(operation)) {
            var gearshift = actual.sequencer();
            if (gearshift == null) return new Reply("unsupported_operation");
            var driver = (SequencedMotion.Access)gearshift;
            if (text("motion_status").equals(operation)) {
                motionToken = new MotionToken(UUID.randomUUID(), SequencedMotion.readiness(gearshift), driver.biocomputingMotionId());
                return new Reply("ok", SequencedMotion.state(gearshift, motionToken.nonce()));
            }
            var observed = motionToken;
            if (observed == null || !text(observed.nonce().toString()).equals(args.field("token"))) return new Reply("version_conflict");
            motionToken = null; // Rejected and uncertain starts are not queued or retryable with the old token.
            var action = SequencedMotion.validateStart(args);
            if (!action.code().equals("ok")) return new Reply(action.code());
            // An observation made while busy is not permission to start after the old action happens to finish on the wire.
            if (!observed.readiness().equals("ok")) return new Reply(observed.readiness());
            if (!java.util.Objects.equals(observed.previousAction(), driver.biocomputingMotionId())) return new Reply("version_conflict");
            var availability = SequencedMotion.readiness(gearshift);
            if (!availability.equals("ok")) return new Reply(availability);
            var id = UUID.randomUUID();
            var receipt = record(Map.of("action", text(id.toString()), "arrival", text("not_measured")));
            input.replying(reply(address(), command, "accepted", receipt));
            input.replying(reply(address(), command, "device_error", DataValue.NullValue.INSTANCE));
            try {
                return driver.biocomputingStart(id, action.instruction(), action.speed())
                    ? new Reply("accepted", receipt) : new Reply("changed");
            } catch (RuntimeException failure) { return new Reply("device_error"); }
        }
        if (actual.settings() == null) return new Reply("unsupported_operation");
        if (text("status").equals(operation)) {
            var settings = actual.settings();
            if (args.fields().get("field") instanceof DataValue.TextValue field && !field.value().equals(settings.field().name())) {
                settings = ComponentSettings.observe((ServerLevel)getWorld(), targetPos(), field.value()).orElse(null);
                if (settings == null) return new Reply("unknown_setting");
            }
            if (readToken == null || !sameSetting(readToken.snapshot(), settings)
                || ((actual.deployer() != null || actual.arm() != null) && readToken.snapshot().busy() != settings.busy()))
                readToken = new ReadToken(UUID.randomUUID(), settings, modeRevision(actual));
            return new Reply("ok", snapshot(settings, readToken.nonce(), ComponentSettings.motion((ServerLevel)getWorld(), targetPos()),
                ComponentSettings.fields(actual.settings())));
        }
        var token = readToken;
        if (!(args.field("token") instanceof DataValue.TextValue) || !(args.field("field") instanceof DataValue.TextValue))
            return new Reply("invalid_command");
        if (token == null || !text(token.nonce().toString()).equals(args.field("token"))) return new Reply("version_conflict");
        if (!text(token.snapshot().field().name()).equals(args.field("field"))) return new Reply("unknown_setting");
        var edit = ComponentSettings.plan((ServerLevel)getWorld(), targetPos(), token.snapshot(), args.field("value"), actor);
        if (!edit.code().equals("applied")) return new Reply(edit.code());
        // All possible result codes must fit before making the native write. No callback to the transport is made by these adapters.
        input.replying(reply(address(), command, "applied", DataValue.NullValue.INSTANCE));
        input.replying(reply(address(), command, "device_error", DataValue.NullValue.INSTANCE));
        readToken = null; // Consumed even if the native call has an uncertain outcome; never auto-retry.
        try { return new Reply(edit.apply() ? "applied" : "changed"); }
        catch (RuntimeException failed) { return new Reply("device_error"); }
    }

    private void publishReply(Command command, SerialDevice.Prepared input, Reply result) {
        if (input.replying(reply(address(), command, result.code(), result.state())).publish()) last = result.code();
    }
    private ServerPlayer authorizedActor(Grant current, SerialDevice.Prepared input) {
        return input.source().map(source -> authorizedActor(current, source)).orElse(null);
    }
    private ServerPlayer authorizedActor(Grant current,
            com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SharedSerialChannel.Port source) {
        if (current == null) return null;
        var actor = controlActor(current);
        if (actor == null) return null;
        try { return current.source().computer().equals(new SerialControlAccess((ServerLevel)getWorld(), getPos(), actor)
            .source(current.source(), source)) ? actor : null; }
        catch (CableDiscovery.DiscoveryException denied) { return null; }
    }
    private ServerPlayer controlActor(Grant current) {
        var level = (ServerLevel)getWorld();
        var actor = level.getServer().getPlayerList().getPlayer(current.actor().getId());
        if (actor == null || actor.serverLevel() != level) {
            actor = FakePlayerFactory.get(level, current.actor());
            actor.moveTo(Vec3.atCenterOf(getPos()));
        }
        return actor.mayBuild() && level.mayInteract(actor, getPos()) && level.mayInteract(actor, targetPos()) ? actor : null;
    }
}
