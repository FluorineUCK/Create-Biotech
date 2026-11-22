package com.nobodiiiii.createbiotech.content.biocomputing.device.terminal;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialControlAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialPeripheralBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import static com.nobodiiiii.createbiotech.content.biocomputing.device.terminal.TerminalUiState.*;

/** Physical authorization and atomic finite reply publication. No CC classes load on this path. */
final class TerminalUiSerialBehaviour extends SerialPeripheralBehaviour {
    static final BehaviourType<TerminalUiSerialBehaviour> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingTerminalUiSerial", PIN = "s0";
    private final TerminalUiBlockEntity terminal;
    private final SerialDevice device;
    private Optional<SerialControlAccess.Binding> binding = Optional.empty();
    private Tag invalid;
    private String last = "idle";

    TerminalUiSerialBehaviour(TerminalUiBlockEntity terminal) {
        super(terminal);
        this.terminal = terminal;
        device = new SerialDevice(() -> CBConfigs.SERVER.biocomputing.terminalUiSerial(CBConfigs.SERVER_SPEC.isLoaded()),
            this::hardwareAvailable, () -> CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded()),
            terminal::setChanged);
    }
    @Override public BehaviourType<?> getType() { return TYPE; }
    @Override public SerialDevice device() { return device; }
    private boolean hardwareAvailable() {
        return terminal.available() && invalid == null
            && terminal.state().controller().equals(binding.map(SerialControlAccess.Binding::computer));
    }
    @Override protected boolean available() { return hardwareAvailable() && binding.isPresent(); }
    Optional<SerialControlAccess.Binding> binding() { return binding; }
    private SerialControlAccess world(ServerPlayer actor) {
        return new SerialControlAccess((ServerLevel)terminal.getLevel(), terminal.getBlockPos(), actor);
    }
    boolean mayUse(ServerPlayer actor) {
        return terminal.available() && actor.serverLevel() == terminal.getLevel() && actor.isAlive() && !actor.isSpectator()
            && actor.serverLevel().mayInteract(actor, terminal.getBlockPos())
            && actor.distanceToSqr(Vec3.atCenterOf(terminal.getBlockPos())) <= 64;
    }
    boolean mayManage(ServerPlayer actor) { return mayUse(actor) && actor.mayBuild(); }
    List<SerialControlAccess.Binding> discover(ServerPlayer actor) {
        if (!mayManage(actor)) throw new IllegalArgumentException("access_denied");
        if (!hardwareAvailable()) throw new IllegalArgumentException("invalid_configuration");
        return world(actor).discover();
    }
    String configure(ServerPlayer actor, UUID session, Optional<SerialControlAccess.Binding> next) {
        if (!mayManage(actor)) return "access_denied";
        if (!hardwareAvailable()) return "invalid_configuration";
        var before = terminal.state();
        if (!before.session().equals(session)) return "changed";
        if (binding.equals(next)) return "configured";
        if (next.isPresent() && before.ccPublisher().isPresent()) return "publisher_in_use";
        next.ifPresent(selected -> world(actor).verify(selected));
        var after = grant(before, next.map(SerialControlAccess.Binding::computer), UUID.randomUUID());
        var diagnostic = device.diagnostic();
        if (next.isPresent() && diagnostic.isPresent()) return diagnostic.orElseThrow();
        if (diagnostic.isEmpty()) {
            try { device.configure(PIN, terminal.getBlockState().getValue(TerminalUiBlock.FACING).getOpposite(), next.isPresent()); }
            catch (IllegalArgumentException failure) {
                if (next.isPresent()) throw failure;
                unload(); // Revocation remains authoritative even with an exhausted interface.
            }
        } else unload();
        if (!terminal.publish(before, after)) return "changed";
        binding = next;
        terminal.setChanged();
        return "configured";
    }
    String interact(ServerPlayer actor, UUID session, long revision, String instance, Optional<String> input) {
        if (!mayUse(actor)) return "access_denied";
        var before = terminal.state();
        if (before.ccPublisher().isPresent()) {
            if (!TerminalUiBlockEntity.ccInstalled()) return "closed";
        } else if (!hardwareAvailable()) return "invalid_configuration";
        var result = TerminalUiState.interact(before, session, revision, instance, input, limits());
        return terminal.publish(before, result.state()) ? result.code() : "changed";
    }
    boolean canInteract() {
        return terminal.state().ccPublisher().isPresent() ? TerminalUiBlockEntity.ccInstalled() : available();
    }
    static Limits limits() { return CBConfigs.SERVER.biocomputing.terminalUiLimits(CBConfigs.SERVER_SPEC.isLoaded()); }
    DataValue.RecordValue address() {
        var pos = terminal.getBlockPos();
        return record(Map.of("x", number(pos.getX()), "y", number(pos.getY()), "z", number(pos.getZ())));
    }
    DataValue.RecordValue diagnostics() {
        var serial = device.status(PIN);
        return record(Map.of("connection", text(serial.connection()), "last", text(last),
            "binding", binding.<DataValue>map(SerialControlAccess.Binding::summary).orElse(DataValue.NullValue.INSTANCE)));
    }

    @Override public void tick() {
        if (available() && device.diagnostic().isEmpty()) {
            var side = terminal.getBlockState().getValue(TerminalUiBlock.FACING).getOpposite();
            var port = device.view().ports().get(PIN);
            if (port == null || port.side().filter(side::equals).isEmpty()) {
                try { device.configure(PIN, side, true); }
                catch (IllegalArgumentException failure) { last = String.valueOf(failure.getMessage()); unload(); return; }
            }
        }
        super.tick();
    }
    @Override protected void processReceived() {
        var serial = device.status(PIN);
        if (binding.isEmpty() || device.diagnostic().isPresent() || serial.receive().frames() == 0
            || Set.of("queued", "transmitting").contains(serial.transmit())) return;
        var receive = device.prepareReceive(PIN);
        if (!text("received").equals(receive.result().field("status"))) {
            discard("invalid_frame");
            return;
        }
        UUID source = null;
        try {
            if (receive.source().isPresent()) source = world(null).source(binding.orElseThrow(), receive.source().orElseThrow());
        } catch (CableDiscovery.DiscoveryException invalidSource) { /* Unverified metadata remains unauthorized. */ }
        var before = terminal.state();
        var planned = TerminalUiProtocol.plan(before, source, address(),
            (DataValue.RecordValue)receive.result().field("value"), limits());
        if (planned.isEmpty()) { discard("not_addressed"); return; }
        var plan = planned.orElseThrow();
        try {
            last = terminal.publish(before, plan.next(), receive.replying(plan.reply()))
                ? ((DataValue.TextValue)plan.reply().field("code")).value() : "changed";
        } catch (SerialRecordCodec.Failure limit) {
            // Even if port capacity was reduced after enqueue, preserve the exact event head.
            var fields = new LinkedHashMap<>(plan.reply().fields());
            fields.remove("event"); fields.remove("state"); fields.put("code", text("reply_capacity"));
            try {
                last = terminal.publish(before, before, receive.replying(record(fields))) ? "reply_capacity" : "changed";
            } catch (SerialRecordCodec.Failure noSpaceEvenForError) { discard("reply_capacity"); }
        } catch (IllegalArgumentException changed) { last = String.valueOf(changed.getMessage()); }
    }
    private void discard(String code) {
        if (device.prepareDiscard(PIN).publish()) { last = code; terminal.setChanged(); }
    }

    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT);
        if (clientPacket) return;
        if (invalid != null) { tag.put(ROOT, invalid.copy()); return; }
        var root = new CompoundTag();
        root.putInt("Version", 1);
        binding.ifPresent(value -> root.put("Binding", value.save()));
        var serial = device.write();
        if (serial != null) root.put("Serial", serial);
        tag.put(ROOT, root);
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (clientPacket) return;
        resetActivity(); binding = Optional.empty(); device.read(null); last = "idle";
        var saved = tag.get(ROOT);
        invalid = saved == null ? null : saved.copy();
        if (!(invalid instanceof CompoundTag root)) return;
        try {
            var keys = new HashSet<>(Set.of("Version"));
            if (root.contains("Binding")) keys.add("Binding");
            if (root.contains("Serial")) keys.add("Serial");
            if (!root.getAllKeys().equals(keys) || !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != 1) return;
            var selected = root.contains("Binding") ? Optional.of(SerialControlAccess.Binding.load(root.get("Binding")))
                : Optional.<SerialControlAccess.Binding>empty();
            if (selected.isPresent() && !root.contains("Serial", Tag.TAG_COMPOUND)) return;
            device.read(root.get("Serial")); binding = selected; invalid = null;
        } catch (IllegalArgumentException rejected) { /* Preserve exact rejected archive; never reopen a corrupt grant. */ }
    }
}
