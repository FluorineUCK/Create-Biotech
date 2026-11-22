package com.nobodiiiii.createbiotech.content.biocomputing.device.music;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Explicit control grant and finite serial adapter for one-shot note-block playback, not a general world-write dispatcher. */
public final class MusicOutputSerialBehaviour extends SerialPeripheralBehaviour {
    public static final BehaviourType<MusicOutputSerialBehaviour> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingMusicSerial";
    private final MusicOutputBlockEntity output;
    private final SerialDevice device;
    private Optional<SerialControlAccess.Binding> binding = Optional.empty();
    private long revision;
    private Tag invalid;
    private String lastCode = "idle";
    public MusicOutputSerialBehaviour(MusicOutputBlockEntity output) {
        super(output); this.output = output;
        device = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.musicOutputSerial(CBConfigs.SERVER_SPEC.isLoaded()), this::hardwareAvailable,
            () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()), output::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return device; }
    private boolean hardwareAvailable() { return invalid == null && output.available(); }
    @Override protected boolean available() { return hardwareAvailable() && binding.isPresent(); }
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
        if (next.isPresent() && !actor.serverLevel().mayInteract(actor, output.target())) return "access_denied";
        next.ifPresent(selected -> world(actor).verify(selected));
        var before = output.state();
        var after = MusicOutputState.grant(before, next.map(SerialControlAccess.Binding::computer), UUID.randomUUID());
        if (next.isPresent() && device.diagnostic().isPresent()) return device.diagnostic().orElseThrow();
        // Revocation must close even if the finite serial archive/hardware no longer fits its current limits.
        String serialIssue = null;
        if (device.diagnostic().isEmpty()) try {
            device.configure("s0", output.getBlockState().getValue(MusicOutputBlock.FACING).getOpposite(), next.isPresent());
        } catch (IllegalArgumentException failure) {
            if (next.isPresent()) throw failure;
            // The authorization revocation is authoritative even if an exhausted interface cannot
            // advance its configuration epoch. Preserve its archive; withdraw it from scheduling.
            serialIssue = String.valueOf(failure.getMessage()); device.unload();
        }
        if (!output.publish(before, after)) return "changed";
        binding = next; if (revision < Long.MAX_VALUE) revision++;
        output.setChanged(); lastCode = serialIssue == null ? "configured" : serialIssue; return "configured";
    }
    public DataValue.RecordValue address() {
        var p = output.getBlockPos();
        return new DataValue.RecordValue(Map.of("x", integer(p.getX()), "y", integer(p.getY()), "z", integer(p.getZ())));
    }
    public DataValue.RecordValue status() {
        var fields = new LinkedHashMap<String, DataValue>(); var serial = device.status("s0");
        fields.put("revision", integer(revision));
        fields.put("target_status", text(output.targetStatus())); fields.put("outcome", text(output.state().last()));
        fields.put("target", address()); fields.put("face", text(output.getBlockState().getValue(MusicOutputBlock.FACING).getName()));
        fields.put("binding", binding.<DataValue>map(SerialControlAccess.Binding::summary).orElse(DataValue.NullValue.INSTANCE));
        fields.put("last", text(lastCode)); fields.put("connection", text(serial.connection())); fields.put("transmit", text(serial.transmit()));
        fields.put("failure", text(serial.failure())); fields.put("frames", integer(serial.receive().frames()));
        fields.put("diagnostic", text(invalid != null ? "invalid_configuration" : device.diagnostic().orElse("")));
        return new DataValue.RecordValue(fields);
    }
    @Override public void tick() {
        if (available() && device.diagnostic().isEmpty()) {
            var port = device.view().ports().get("s0");
            if (port != null && port.side().isPresent() && port.side().orElseThrow() != output.getBlockState().getValue(MusicOutputBlock.FACING).getOpposite()) {
                try {
                    device.configure("s0", output.getBlockState().getValue(MusicOutputBlock.FACING).getOpposite(), binding.isPresent());
                } catch (IllegalArgumentException failure) {
                    // A physical rotation cannot bypass exhausted/locked interface configuration.
                    // Keep its history, but remove today's tick witness so the old face never transmits.
                    lastCode = String.valueOf(failure.getMessage()); resetActivity(); device.unload(); return;
                }
            }
        }
        if (hardwareAvailable()) output.refreshTarget();
        super.tick();
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
            output.refreshTarget();
            var before = output.state();
            var plan = MusicOutputProtocol.plan(before, source, address(), (DataValue.RecordValue)receive.result().field("value"));
            if (plan.isEmpty()) { discard(receive, "not_addressed"); return; }
            var prepared = plan.orElseThrow(); var exchange = receive.replying(prepared.reply());
            if (!output.publish(before, prepared.next(), exchange)) { lastCode = "changed"; return; }
            lastCode = ((DataValue.TextValue)prepared.reply().field("code")).value();
            if (before != prepared.next() && prepared.next().pending().isPresent()) {
                var grant = binding.orElseThrow();
                var sender = receive.source().orElseThrow();
                output.playCommitted(prepared.next(), () -> binding.filter(grant::equals).isPresent()
                    && grant.computer().equals(world(null).source(grant, sender)));
            }
        } catch (SerialRecordCodec.Failure failure) { lastCode = failure.code().name().toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException failure) { lastCode = String.valueOf(failure.getMessage()); }
    }
    private void discard(SerialDevice.Prepared frame, String code) { if (frame.publish()) { lastCode = code; output.setChanged(); } }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (clientPacket) return;
        if (invalid != null) { tag.put(ROOT, invalid.copy()); return; }
        var root = new CompoundTag(); root.putInt("Version", 1); root.putLong("Revision", revision);
        binding.ifPresent(value -> root.put("Binding", value.save()));
        var serial = device.write(); if (serial != null) root.put("Serial", serial); tag.put(ROOT, root);
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        resetActivity(); binding = Optional.empty(); revision = 0; device.read(null); lastCode = "idle";
        invalid = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
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
