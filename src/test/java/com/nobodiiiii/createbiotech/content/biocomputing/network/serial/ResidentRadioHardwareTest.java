package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.carrier.CarrierClock;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResidentRadioHardwareTest {
    private static final ResidentHardwareIdentity TRADER = resident("wandering_trader", "none");
    private static final ResidentHardwareIdentity VILLAGER = resident("villager", "none");
    private static final ResidentHardwareIdentity ZOMBIE = resident("zombie_villager", "none");
    private static final HardwarePins PINS = HardwarePins.DEFAULT;
    private static final SerialHardware.Port WIRE_PORT = new SerialHardware.Port(128, 1024, 3, 4096);
    private static final SerialHardware WIRED = new SerialHardware(Map.of("s0", WIRE_PORT));
    private static final SerialHardware.Port RF_PORT = new SerialHardware.Port(48, 512, 2, 1024);
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(262144, 2097152, 16777216);
    private static final SerialRecordCodec.Limits CODEC = new SerialRecordCodec.Limits(4096, 65536, ARCHIVE);
    private static final ResidentRadioHardware RF = new ResidentRadioHardware(
        new ResidentRadioHardware.Oscillator(16, 100, 8, 6, 12),
        new ResidentRadioHardware.Link(16, 2, 32, 1, 8, 32), RF_PORT);
    private static final DataValue.RecordValue SAMPLE = new DataValue.RecordValue(Map.of("temperature", new DataValue.IntegerValue(23)));
    private static final DataValue.RecordValue OTHER = new DataValue.RecordValue(Map.of("temperature", new DataValue.IntegerValue(42)));
    private static final DataValue.NbtValue NATIVE = nativeSample();

    private static DataValue.NbtValue nativeSample() {
        var record = new CompoundTag();
        record.putInt("temperature", 23);
        var limits = new NativeNbtCodec.Limits(CODEC.maxEncodedBytes(), CODEC.maxDecodeHeapBytes(), CODEC.values());
        return NativeNbtCodec.snapshot(record, limits);
    }

    @Test
    void onlyAReadyWanderingTraderAddsRadioEvenWhenTwoResidentsShareOneBalancedProfile() {
        var trader = installed(HardwareSelection.Status.READY, TRADER, RF.expose(TRADER, WIRED, PINS));
        var villager = installed(HardwareSelection.Status.READY, VILLAGER, RF.expose(VILLAGER, WIRED, PINS));
        assertEquals(villager.profile(), trader.profile());
        assertSame(WIRE_PORT, trader.limits().serial().ports().get("s0"));
        assertEquals(RF_PORT, trader.limits().serial().ports().get(ResidentRadioHardware.PORT));
        assertSame(WIRED, villager.limits().serial());
        assertSame(WIRED, RF.expose(ZOMBIE, WIRED, PINS));
        assertTrue(RF.attached(trader, device(trader.limits().serial()), BlockPos.ZERO, 0).isPresent());
        assertTrue(RF.attached(villager, device(WIRED), BlockPos.ZERO, 0).isEmpty());
        assertTrue(RF.attached(installed(HardwareSelection.Status.DISABLED, TRADER, trader.limits().serial()),
            device(trader.limits().serial()), BlockPos.ZERO, 0).isEmpty());
    }

    @Test
    void radioCannotSilentlyBorrowAnExistingWireOrAnotherHardwarePin() {
        var custom = new SerialHardware(Map.of(ResidentRadioHardware.PORT, WIRE_PORT, "s0", WIRE_PORT));
        assertSame(custom, RF.expose(VILLAGER, custom, PINS), "An existing custom wired name is not reinterpreted");
        assertThrows(IllegalArgumentException.class, () -> RF.expose(TRADER, custom, PINS));
        var pins = new HardwarePins(List.of(ResidentRadioHardware.PORT), List.of());
        assertThrows(IllegalArgumentException.class, () -> RF.expose(TRADER, WIRED, pins));
    }

    @Test
    void tuningSelectsOnlyConfiguredPresetsAndBandwidthIsNotTheCenterFrequencyOrCodecRate() {
        assertEquals(new RadioSerialNetwork.Band(100, 6), RF.oscillator().emission(0));
        assertEquals(new RadioSerialNetwork.Band(220, 12), RF.oscillator().reception(15));
        assertThrows(IllegalArgumentException.class, () -> RF.oscillator().emission(-1));
        assertThrows(IllegalArgumentException.class, () -> RF.oscillator().reception(16));
        assertThrows(IllegalArgumentException.class, () -> new ResidentRadioHardware.Oscillator(2, Integer.MAX_VALUE, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ResidentRadioHardware.Oscillator(2, 5, 1, 10, 3));
        assertThrows(IllegalArgumentException.class, () -> new ResidentRadioHardware.Link(1, 1, 1, 1, 129, 32));
    }

    @Test
    void defaultHardwareCanBeReadBeforeLoginAndUsesTheRegisteredServerSpec() {
        var builder = new net.neoforged.neoforge.common.ModConfigSpec.Builder();
        var config = new ResidentRadioConfig(builder);
        var spec = builder.build();
        assertTrue(spec.getSpec().contains("radio"));
        var hardware = config.current(false);
        assertEquals(16, hardware.oscillator().presets());
        assertTrue(hardware.interfaceLimit().receiveBytes() >= hardware.interfaceLimit().maxFrameBytes());
        assertEquals(hardware.link().symbolsPerTick() * hardware.link().bitsPerSymbol(), 32);
        assertTrue(hardware.link().rangeBlocks() > 0);
    }

    @Test
    void thirdStationBlocksAnOverlappingReceiveBandButDoesNotJamAnUnrelatedBand() {
        assertEquals(RadioSerialNetwork.Reception.ACCEPTED, radioTrial(3, 4));
        assertEquals(RadioSerialNetwork.Reception.ACCEPTED, radioTrial(1, 4)); // partial overlap, strong wanted signal
        assertEquals(RadioSerialNetwork.Reception.INTERFERENCE, radioTrial(1, 29)); // partial overlap, weak wanted signal
        assertEquals(RadioSerialNetwork.Reception.LOST_LOCK, radioTrial(3, 40));
    }

    @Test
    void selectedCarrierSideRoundTripsWithoutChangingPresetOrEnablingThePort() {
        var initial = new ResidentRadioTuning();
        initial.tune(5, false);
        var versionOne = initial.write();
        var restored = new ResidentRadioTuning();
        restored.read(versionOne);
        assertEquals(versionOne, restored.write(), "An old unconnected tune remains unchanged when loaded");
        assertEquals(Optional.empty(), restored.carrierInput());

        restored.selectCarrierInput(Optional.of(Direction.WEST));
        var freshProcess = new ResidentRadioTuning();
        freshProcess.read(restored.write());
        assertEquals(Optional.of(Direction.WEST), freshProcess.carrierInput());
        assertEquals(5, freshProcess.preset());
        assertFalse(freshProcess.armed(), "Selecting a carrier face cannot arm rf0");

        freshProcess.selectCarrierInput(Optional.empty());
        assertEquals(versionOne, freshProcess.write(), "An explicit unplug restores the original configuration");
    }

    @Test
    void independentlyLockedExternalCarriersTransmitAndLossDoesNotFallBackToThePreset() {
        var selected = installed(HardwareSelection.Status.READY, TRADER, RF.expose(TRADER, WIRED, PINS));
        var tx = station(selected, new BlockPos(0, 64, 0), 0);
        var rx = station(selected, new BlockPos(4, 64, 0), 5);
        var fallback = station(selected, new BlockPos(8, 64, 0), 0);
        var divider = new CarrierClock.Divider(3);
        var driveA = new CarrierClock.Multiplier(2, 2);
        var driveB = new CarrierClock.Multiplier(2, 2);
        var refA = divider.output(RF.oscillator().reference(0).output()).frequency();
        assertTrue(RF.attached(selected, tx.device(), tx.position(), 0,
            Optional.of(driveA.tick(refA))).isEmpty(), "Acquiring is not an unplugged carrier input");
        var externalTx = RF.attached(selected, tx.device(), tx.position(), 0,
            Optional.of(driveA.tick(refA))).orElseThrow();

        var refB = divider.output(RF.oscillator().reference(0).output()).frequency();
        driveB.tick(refB); // Independent receiver starts after the sender has already locked.
        var externalRx = RF.attached(selected, rx.device(), rx.position(), 5,
            Optional.of(driveB.tick(refB))).orElseThrow();
        var radio = RF.newDomain();
        var locked = List.of(externalTx, externalRx, fallback);

        var send = tx.device().prepareSend(ResidentRadioHardware.PORT, NATIVE);
        assertEquals(SerialResult.send("queued"), send.result());
        assertTrue(send.publish());
        for (int tick = 0; tick < 120; tick++) radio.tick(locked);
        assertEquals(1, rx.device().status(ResidentRadioHardware.PORT).receive().frames());
        assertTrue(fallback.device().poll(ResidentRadioHardware.PORT).isEmpty());

        assertEquals("queued", tx.device().send(ResidentRadioHardware.PORT, OTHER));
        radio.tick(locked);
        var lost = RF.attached(selected, tx.device(), tx.position(), 0,
            Optional.of(driveA.tick(Optional.empty())));
        assertTrue(lost.isEmpty(), "The input is still wired, but its reference is unavailable");
        for (int tick = 0; tick < 120; tick++) radio.tick(List.of(externalRx, fallback));
        assertEquals("interrupted", tx.device().status(ResidentRadioHardware.PORT).transmit());
        var receive = rx.device().prepareReceive(ResidentRadioHardware.PORT, true);
        assertEquals(SerialResult.receive("received", NATIVE), receive.result(), "Earlier full frames remain available");
        assertTrue(receive.publish());
        assertTrue(rx.device().poll(ResidentRadioHardware.PORT).isEmpty(), "No partial frame delivery");
        assertTrue(fallback.device().poll(ResidentRadioHardware.PORT).isEmpty(), "No built-in carrier fallback");

        var unplugged = RF.attached(selected, tx.device(), tx.position(), 0, Optional.empty()).orElseThrow();
        assertEquals("queued", tx.device().send(ResidentRadioHardware.PORT, SAMPLE));
        for (int tick = 0; tick < 120; tick++) radio.tick(List.of(unplugged, externalRx, fallback));
        assertEquals(Optional.of(SAMPLE), fallback.device().poll(ResidentRadioHardware.PORT), "Only a later fresh send uses the preset");
        assertTrue(rx.device().poll(ResidentRadioHardware.PORT).isEmpty());
    }

    @Test
    void ordinaryTraderCanRunEditableProgramsToSendAndClaimOpaqueNbtWithoutParserInstructions() {
        var config = new BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
        var selected = config.select(TRADER, false);
        assertTrue(selected.ready());
        var limits = selected.limits();
        assertFalse(limits.isa1a());
        assertFalse(limits.isa1b());
        var radio = config.residentRadio(false);
        assertTrue(radio.supports(selected));
        var a = station(radio, selected, new BlockPos(0, 64, 0), 5);
        var b = station(radio, selected, new BlockPos(4, 64, 0), 5);
        var net = radio.newDomain();
        var visible = List.of(a, b);
        var sender = installed(sender(), a.device(), limits);
        var receiver = installed(receiver(), b.device(), limits);
        assertEquals(0, b.device().status(ResidentRadioHardware.PORT).receive().frames());
        net.tick(visible);
        sender.setPaused(false, limits, true);
        long tick = 0;
        for (; tick < 256 && b.device().status(ResidentRadioHardware.PORT).receive().frames() == 0; tick++) {
            sender.tick(tick, limits, true, Map.of());
            net.tick(visible);
        }
        assertEquals(1, b.device().status(ResidentRadioHardware.PORT).receive().frames(), "Actual assembly publishes one frame");
        assertEquals(SerialResult.send("queued"), sender.flowSnapshot().orElseThrow().state().locals().field("sent"));
        assertEquals("transmitted", a.device().status(ResidentRadioHardware.PORT).transmit());
        receiver.setPaused(false, limits, true);
        for (; tick < 512 && receiver.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; tick++) {
            receiver.tick(tick, limits, true, Map.of());
            net.tick(visible);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, receiver.flowSnapshot().orElseThrow().state().status());
        assertEquals(SerialResult.receive("received", NATIVE), receiver.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(0, b.device().status(ResidentRadioHardware.PORT).receive().frames());
    }

    static ProgramDocument sender() {
        return archive(Map.of("payload", DataType.typeOf(NATIVE)), new DataValue.RecordValue(Map.of("payload", NATIVE)),
            new FlowProgram.SerialSend("emit", ResidentRadioHardware.PORT, "payload", "sent"));
    }

    static ProgramDocument receiver() {
        return archive(Map.of(), new DataValue.RecordValue(Map.of()),
            new FlowProgram.SerialReceive("receive", ResidentRadioHardware.PORT, "received", true));
    }

    private static ProgramDocument archive(Map<String, DataType> inputs, DataValue.RecordValue initial, FlowProgram.Step step) {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(inputs, List.of(step)).withAssembly(), initial)));
        return workspace.snapshot();
    }

    private static ComputerBlockHost installed(ProgramDocument archive, SerialDevice device, ComputerHostLimits limits) {
        var host = new ComputerBlockHost(() -> {}, pin -> {}, device);
        assertEquals(ComputerRuntime.InstallResult.APPLIED,
            host.installProgram(PreparedProgram.prepare(archive, limits), ComputerRuntime.MemoryPolicy.RESET, limits, true));
        return host;
    }

    private static RadioSerialNetwork.Reception radioTrial(int interfererPreset, int receiverX) {
        var hardware = installed(HardwareSelection.Status.READY, TRADER, RF.expose(TRADER, WIRED, PINS));
        var a = station(hardware, new BlockPos(0, 64, 0), 0);
        var b = station(hardware, new BlockPos(receiverX, 64, 0), 0);
        var c = station(hardware, new BlockPos(receiverX, 64, 3), interfererPreset);
        var network = RF.newDomain();
        assertEquals("queued", a.device().send(ResidentRadioHardware.PORT, SAMPLE));
        assertEquals("queued", c.device().send(ResidentRadioHardware.PORT, OTHER));
        var roster = List.of(a, b, c);
        var received = new ArrayList<RadioSerialNetwork.Observation>();
        var receiverId = b.device().attachments().getFirst().identity();
        for (int tick = 0; tick < 160; tick++) {
            var interval = network.tick(roster);
            assertTrue(interval.fault().isEmpty());
            received.addAll(interval.receptions().stream().filter(x -> x.receiver().device().equals(receiverId)).toList());
        }
        assertEquals("transmitted", a.device().status(ResidentRadioHardware.PORT).transmit(), "Emission is not a receiver acknowledgement");
        assertEquals(1, received.size());
        var observation = received.getFirst();
        var observed = b.device().poll(ResidentRadioHardware.PORT);
        if (observation.outcome() == RadioSerialNetwork.Reception.ACCEPTED) assertEquals(Optional.of(SAMPLE), observed);
        else assertTrue(observed.isEmpty(), "No corrupted value is delivered");
        if (observation.outcome() == RadioSerialNetwork.Reception.INTERFERENCE) assertTrue(observation.interference() > 0);
        assertTrue(b.device().poll(ResidentRadioHardware.PORT).isEmpty(), "No hidden retry");
        return observation.outcome();
    }

    private static RadioSerialNetwork.Radio station(HardwareSelection hardware, BlockPos pos, int preset) {
        return station(RF, hardware, pos, preset);
    }

    private static RadioSerialNetwork.Radio station(ResidentRadioHardware radio, HardwareSelection hardware, BlockPos pos, int preset) {
        var device = device(hardware.limits().serial());
        device.configure(ResidentRadioHardware.PORT, Direction.UP, true);
        return radio.attached(hardware, device, pos, preset).orElseThrow();
    }

    private static SerialDevice device(SerialHardware hardware) {
        return new SerialDevice(() -> hardware, () -> true, () -> CODEC, () -> {});
    }

    private static ResidentHardwareIdentity resident(String type, String profession) {
        return new ResidentHardwareIdentity(ResourceLocation.withDefaultNamespace(type), ResourceLocation.withDefaultNamespace(profession), 1);
    }

    private static HardwareSelection installed(HardwareSelection.Status status, ResidentHardwareIdentity resident, SerialHardware serial) {
        return new HardwareSelection(status, Optional.of(resident), "balanced",
            new ComputerHostLimits(new ResourceLimits(256, 65536, 128), ARCHIVE, new MailboxLimits(16, 8), PINS, serial));
    }
}

