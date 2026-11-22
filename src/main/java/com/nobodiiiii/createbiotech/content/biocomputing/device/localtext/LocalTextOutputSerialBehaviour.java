package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Explicit control grant and finite serial adapter for the one-shot text extensions, not a general world-write dispatcher. */
public final class LocalTextOutputSerialBehaviour extends SerialPeripheralBehaviour {
    public static final BehaviourType<LocalTextOutputSerialBehaviour> TYPE = new BehaviourType<>();
    private final LocalTextOutputBlockEntity output;
    private final SerialDevice device;
    private Optional<SerialControlAccess.Binding> binding = Optional.empty();
    private long revision;
    private Tag invalid;
    private String lastCode = "idle";
    public LocalTextOutputSerialBehaviour(LocalTextOutputBlockEntity output) {
        super(output); this.output = output;
        device = new SerialDevice(() -> output.kind().serial(CBConfigs.SERVER.biocomputing, CBConfigs.SERVER_SPEC.isLoaded()), this::hardwareAvailable,
            () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), output::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return device; }
    private boolean hardwareAvailable() { return output.available() && archiveMatches(); }
    @Override protected boolean available() { return hardwareAvailable() && binding.isPresent(); }
    boolean archiveMatches() {
        return invalid == null && output.state().controller().equals(binding.map(SerialControlAccess.Binding::computer));
    }
    private String rootKey() { return output.kind().archivePrefix + "Serial"; }
    public long revision() { return revision; }
    public Optional<SerialControlAccess.Binding> binding() { return binding; }
    private SerialControlAccess world(ServerPlayer actor) { return new SerialControlAccess((ServerLevel)output.getLevel(), output.getBlockPos(), actor); }
    public boolean mayManage(ServerPlayer actor) {
        return output.available() && actor.serverLevel() == output.getLevel() && actor.isAlive() && actor.mayBuild() && !actor.isSpectator()
            && actor.serverLevel().mayInteract(actor, output.getBlockPos()) && actor.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(output.getBlockPos())) <= 64;
    }
    public List<SerialControlAccess.Binding> discover(ServerPlayer actor) {
        if (!mayManage(actor)) throw new IllegalArgumentException("access_denied");
        if (!hardwareAvailable()) throw new IllegalArgumentException("invalid_configuration");
        return world(actor).discover();
    }
    /** Menu supplies only a current server-discovered choice, never player-authored coordinates/identities. */
    public String configure(ServerPlayer actor, long expected, Optional<SerialControlAccess.Binding> next) {
        if (!mayManage(actor)) return "access_denied";
        if (!hardwareAvailable()) return "invalid_configuration";
        if (expected != revision) return "changed";
        if (binding.equals(next)) return "configured";
        if (revision == Long.MAX_VALUE && next.isPresent()) return "version_exhausted";
        next.ifPresent(selected -> world(actor).verify(selected));
        var before = output.state();
        var after = LocalTextOutputState.grant(before, next.map(SerialControlAccess.Binding::computer), UUID.randomUUID());
        if (next.isPresent() && device.diagnostic().isPresent()) return device.diagnostic().orElseThrow();
        // Revocation must close even if the finite serial archive/hardware no longer fits its current limits.
        String serialIssue = null;
        if (device.diagnostic().isEmpty()) try {
            device.configure("s0", output.getBlockState().getValue(LocalTextOutputBlock.FACING).getOpposite(), next.isPresent());
        } catch (IllegalArgumentException failure) {
            if (next.isPresent()) throw failure;
            // The authorization revocation is authoritative even if an exhausted interface cannot
            // advance its configuration epoch. Preserve its archive; withdraw it from scheduling.
            serialIssue = String.valueOf(failure.getMessage()); device.unload();
        }
        if (!output.publish(before, after)) return "changed";
        output.cancelMedia("interrupted");
        binding = next; if (revision < Long.MAX_VALUE) revision++;
        output.setChanged(); lastCode = serialIssue == null ? "configured" : serialIssue; return "configured";
    }
    public DataValue.RecordValue address() {
        var p = output.getBlockPos();
        return new DataValue.RecordValue(Map.of("x", integer(p.getX()), "y", integer(p.getY()), "z", integer(p.getZ())));
    }
    public DataValue.RecordValue status() {
        var fields = new LinkedHashMap<String, DataValue>(); var serial = device.status("s0");
        fields.put("revision", integer(revision)); fields.put("result", text(output.state().last()));
        fields.put("outputKind", text(output.kind().id));
        if (output.kind() == LocalTextOutputKind.NARRATION) fields.put("media", text(output.playback.status()));
        fields.put("target", address()); fields.put("face", text(output.getBlockState().getValue(LocalTextOutputBlock.FACING).getName()));
        fields.put("binding", binding.<DataValue>map(SerialControlAccess.Binding::summary).orElse(DataValue.NullValue.INSTANCE));
        fields.put("last", text(lastCode)); fields.put("connection", text(serial.connection())); fields.put("transmit", text(serial.transmit()));
        fields.put("failure", text(serial.failure())); fields.put("frames", integer(serial.receive().frames()));
        fields.put("diagnostic", text(!archiveMatches() ? "invalid_configuration" : device.diagnostic().orElse("")));
        return new DataValue.RecordValue(fields);
    }
    @Override public void tick() {
        if (available() && device.diagnostic().isEmpty()) {
            var port = device.view().ports().get("s0");
            if (port != null && port.side().isPresent() && port.side().orElseThrow() != output.getBlockState().getValue(LocalTextOutputBlock.FACING).getOpposite()) {
                try {
                    device.configure("s0", output.getBlockState().getValue(LocalTextOutputBlock.FACING).getOpposite(), binding.isPresent());
                } catch (IllegalArgumentException failure) {
                    // A physical rotation cannot bypass exhausted/locked interface configuration.
                    // Keep its history, but remove today's tick witness so the old face never transmits.
                    lastCode = String.valueOf(failure.getMessage()); resetActivity(); device.unload();
                    output.cancelMedia("interrupted"); return;
                }
            }
        }
        super.tick();
        if (output.kind() == LocalTextOutputKind.NARRATION && output.getLevel() instanceof ServerLevel level) {
            if (available()) output.playback.tick(level, output.getBlockPos());
            else if (output.playback.busy()) output.cancelMedia("interrupted");
        }
    }
    @Override protected void processReceived() {
        if (binding.isEmpty() || device.diagnostic().isPresent()) return;
        if (Set.of("queued", "transmitting").contains(device.status("s0").transmit())) return; // Finite backpressure, not loss.
        var receive = device.prepareReceive("s0");
        if (!text("received").equals(receive.result().field("status"))) {
            // Native NBT is legal on the shared wire, but not this opt-in typed device protocol.
            if (device.status("s0").receive().frames() > 0) {
                var nativeFrame = device.prepareReceive("s0", true);
                if (text("received").equals(nativeFrame.result().field("status"))) discard(nativeFrame, "unsupported_format");
                else lastCode = "invalid_frame";
            }
            return;
        }
        try {
            UUID source = null;
            try { if (receive.source().isPresent()) source = world(null).source(binding.orElseThrow(), receive.source().orElseThrow()); }
            catch (CableDiscovery.DiscoveryException failure) { lastCode = failure.code(); }
            var before = output.state();
            var level = (ServerLevel)output.getLevel();
            var config = CBConfigs.SERVER.biocomputing;
            boolean loaded = CBConfigs.SERVER_SPEC.isLoaded();
            var kind = output.kind();
            var frame = (DataValue.RecordValue)receive.result().field("value");
            if (kind == LocalTextOutputKind.NARRATION && SpeakerPlaybackProtocol.usesMediaProtocol(frame)) {
                processMedia(receive, before, source, level, frame, config.speakerPlaybackRange(loaded), config.speakerPlaybackListeners(loaded));
                return;
            }
            var limits = kind.limits(config, loaded);
            var plan = LocalTextOutputProtocol.plan(kind, before, source, address(), frame,
                limits.bytes(), level.getGameTime(), limits.cooldown(), kind.hasRoom(level, level.getGameTime(), limits.dimensionQuota()));
            if (plan.isEmpty()) { discard(receive, "not_addressed"); return; }
            var prepared = plan.orElseThrow();
            if (!output.publish(before, prepared.next(), receive.replying(prepared.reply()))) { lastCode = "changed"; return; }
            lastCode = ((DataValue.TextValue)prepared.reply().field("code")).value();
            if (prepared.utterance().isPresent()) {
                var grant = binding.orElseThrow();
                var sender = receive.source().orElseThrow();
                output.dispatchCommitted(prepared.next(), prepared.utterance().orElseThrow(), limits, () ->
                    binding.filter(grant::equals).isPresent() && grant.computer().equals(world(null).source(grant, sender)));
            }
        } catch (SerialRecordCodec.Failure failure) { lastCode = failure.code().name().toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException failure) { lastCode = String.valueOf(failure.getMessage()); }
    }

    private void processMedia(SerialDevice.Prepared receive, LocalTextOutputState.State before, UUID source, ServerLevel level,
            DataValue.RecordValue frame, int range, int listeners) {
        var command = SpeakerPlaybackProtocol.decode(frame, address(), before, source);
        if (command == null) { discard(receive, "not_addressed"); return; }
        String code = command.code(), mediaStatus = output.playback.status();
        var next = before;
        SpeakerMediaPlaybackSource.Prepared prepared = null;
        Supplier<SpeakerMediaPlaybackSource.Prepared> access = null;
        if (command.action() == SpeakerPlaybackProtocol.Action.PLAY) {
            if (output.playback.busy()) code = "media_busy";
            else if (!output.playback.hasRoom()) code = "media_budget_exhausted";
            else {
                var grant = binding.orElseThrow();
                var sender = receive.source().orElseThrow();
                access = () -> {
                    if (!available() || !binding.filter(grant::equals).isPresent()) throw new LibraryBookAccess.Failure("interrupted");
                    return SpeakerMediaPlaybackSource.prepare(level, output.getBlockPos(), grant, sender, command.reference());
                };
                try { prepared = access.get(); }
                catch (LibraryBookAccess.Failure failure) { code = failure.code(); }
                catch (CableDiscovery.DiscoveryException failure) { code = failure.code(); }
                if (prepared != null) { next = LocalTextOutputState.advanceControl(before); mediaStatus = "admitted"; }
            }
        } else if (command.action() == SpeakerPlaybackProtocol.Action.STOP) {
            next = LocalTextOutputState.advanceControl(before);
            mediaStatus = "stopped";
        }
        if (!output.publish(before, next, receive.replying(command.reply(address(), next, code, mediaStatus)))) {
            lastCode = "changed";
            return;
        }
        lastCode = code;
        if (prepared != null) output.playback.start(level, output.getBlockPos(), range, listeners, prepared, access);
        else if (command.action() == SpeakerPlaybackProtocol.Action.STOP) output.cancelMedia("stopped");
    }
    private void discard(SerialDevice.Prepared frame, String code) { if (frame.publish()) { lastCode = code; output.setChanged(); } }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(rootKey()); if (clientPacket) return;
        if (invalid != null) { tag.put(rootKey(), invalid.copy()); return; }
        var root = new CompoundTag(); root.putInt("Version", 1); root.putLong("Revision", revision);
        binding.ifPresent(value -> root.put("Binding", value.save()));
        var serial = device.write(); if (serial != null) root.put("Serial", serial); tag.put(rootKey(), root);
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        resetActivity(); binding = Optional.empty(); revision = 0; device.read(null); lastCode = "idle";
        Tag saved = tag.get(rootKey());
        invalid = saved == null ? null : saved.copy();
        if (invalid == null) return;
        try {
            if (!(invalid instanceof CompoundTag root)) return;
            var keys = new HashSet<>(Set.of("Version", "Revision"));
            if (root.contains("Binding")) keys.add("Binding"); if (root.contains("Serial")) keys.add("Serial");
            if (!root.getAllKeys().equals(keys) || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1
                || !root.contains("Revision", Tag.TAG_LONG) || root.getLong("Revision") < 0) return;
            var selected = root.contains("Binding") ? Optional.of(SerialControlAccess.Binding.load(root.get("Binding"))) : Optional.<SerialControlAccess.Binding>empty();
            if (selected.isPresent() && (root.getLong("Revision") == 0 || !root.contains("Serial"))) return;
            device.read(root.get("Serial")); binding = selected; revision = root.getLong("Revision"); invalid = null;
        } catch (IllegalArgumentException malformed) { /* Preserve the exact archive and stay closed. */ }
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
