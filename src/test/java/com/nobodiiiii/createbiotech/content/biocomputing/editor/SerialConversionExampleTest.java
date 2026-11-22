package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** These are editable documents, not an implicit resident bridge or an external XBus route. */
class SerialConversionExampleTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    static final ResourceLimits BUDGET = new ResourceLimits(16, 16384, 2);
    static final SerialHardware HARDWARE = new SerialHardware(Map.of("s0", new SerialHardware.Port(128, 4096, 2, 8192)));
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(BUDGET, ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, HARDWARE));
    static final DataValue.RecordValue PACKET = new DataValue.RecordValue(Map.of("reading", new DataValue.IntegerValue(37),
        "note", new DataValue.TextValue("ordinary villager")));
    static final DataType.RecordType PACKET_TYPE = (DataType.RecordType)DataType.typeOf(PACKET);

    @Test void packagedConversionOnlyEmitsAfterItsOwnInstructionThenReceiverConsumesOneWholeFrame() {
        var source = host(packaged("serial_bridge_source"), null);
        var world = new World(); var transmit = world.add(World.A, Direction.EAST); var receive = world.add(World.B, Direction.WEST);
        var converter = host(packaged("serial_bridge_converter"), transmit);
        var receiver = host(packaged("serial_bridge_receiver"), receive);
        source.setPaused(false); converter.setPaused(false); receiver.setPaused(false);
        for (int i = 0; i < 30 && state(source).status() != FlowRuntime.Status.WAITING_WRITE; i++) source.tick(BUDGET.workPerSlice(), Map.of());
        converter.advanceRound(Map.of());
        assertEquals(XBusExchange.Status.EXCHANGED, source.exchange("x0", converter, "x1"));
        assertEquals("idle", transmit.status("s0").transmit(), "Rendezvous is not automatic forwarding");
        var sourceSaved = source.write(); var converterSaved = converter.write();
        converter = new FlowBlockHost(() -> {}, converterSaved, transmit); assertTrue(converter.synchronize(LIMITS, true));
        finish(converter);
        assertEquals(SerialResult.send("queued"), state(converter).locals().field("sent"));
        assertEquals(sourceSaved, source.write(), "Converter uses no source RAM or budget");
        assertEquals(0, receive.status("s0").receive().frames(), "Queuing is not physical delivery");
        var network = new SerialNetwork();
        for (int i = 0; i < 150 && state(receiver).status() != FlowRuntime.Status.COMPLETE; i++) {
            receiver.tick(BUDGET.workPerSlice(), Map.of());
            network.tick(world, world.devices, new CommunicationNetworkLimits(128, 8), new SharedSerialChannel.Limits(8, 4096, 64, 32));
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(receiver).status());
        assertEquals(SerialResult.receive("received", PACKET), state(receiver).locals().field("received"));
        assertEquals("transmitted", transmit.status("s0").transmit());
        assertEquals(0, receive.status("s0").receive().frames());
        assertEquals(sourceSaved, source.write());
    }

    @Test void exportOrdinaryDocumentsForPackaging() throws Exception {
        var directory = Path.of("build/generated/biocomputing-examples"); Files.createDirectories(directory);
        for (var item : documents().entrySet()) {
            item.getValue().validate(BUDGET);
            byte[] bytes = ProgramDocumentWire.encode(item.getValue(), ARCHIVE);
            assertEquals(item.getValue(), ProgramDocumentWire.decode(bytes, ARCHIVE));
            assertEquals(item.getValue(), new ProgramWorkspace(item.getValue()).snapshot());
            Files.writeString(directory.resolve(item.getKey() + ".bcp"), Base64.getEncoder().encodeToString(bytes));
        }
    }

    @Test void failedXBusResultSkipsPhysicalSendAndRetainsAnHonestNotAttemptedResult() {
        var device = new World().add(World.A, Direction.EAST);
        var converter = host(packaged("serial_bridge_converter"), device);
        converter.setPaused(false); converter.advanceRound(Map.of());
        assertEquals(XBusExchange.Status.PAUSED, converter.rejectPausedXBus("x1"));
        finish(converter);
        assertEquals(XBusResult.value(null, true, true), state(converter).locals().field("input"));
        assertEquals(SerialResult.send("not_attempted"), state(converter).locals().field("sent"));
        assertEquals("idle", device.status("s0").transmit());
    }

    @Test void explicitSourceLoopAllowsStartingTheSourceBeforeTheConverterWithoutRetryingSerial() {
        var source = host(packaged("serial_bridge_source"), null); source.setPaused(false);
        for (int i = 0; i < 40 && state(source).status() != FlowRuntime.Status.WAITING_WRITE; i++) source.tick(2, Map.of());
        assertEquals(XBusExchange.Status.PAUSED, source.rejectPausedXBus("x0"));
        assertEquals(XBusResult.value(null, false, true), state(source).locals().field("handoff"));
        for (int i = 0; i < 60 && state(source).status() != FlowRuntime.Status.WAITING_WRITE; i++) source.tick(2, Map.of());
        assertEquals(FlowRuntime.Status.WAITING_WRITE, state(source).status());
        var transmit = new World().add(World.A, Direction.EAST);
        var converter = host(packaged("serial_bridge_converter"), transmit); converter.setPaused(false); converter.advanceRound(Map.of());
        assertEquals(XBusExchange.Status.EXCHANGED, source.exchange("x0", converter, "x1"));
        finish(source); finish(converter);
        assertEquals(XBusResult.value(null, false, false), state(source).locals().field("handoff"));
        assertEquals("queued", transmit.status("s0").transmit());
        assertEquals(0, converter.tick(2, Map.of()), "There is no implicit serial retransmission loop");
    }

    @Test void receiverWaitsOnlyForEmptyStopsOnClosedAndKeepsItsEditableInitialValues() {
        var document = packaged("serial_bridge_receiver"); var device = new World().add(World.B, Direction.WEST);
        var receiver = host(document, device); receiver.setPaused(false);
        for (int i = 0; i < 50; i++) receiver.tick(2, Map.of());
        assertNotEquals(FlowRuntime.Status.COMPLETE, state(receiver).status());
        assertEquals(SerialResult.receive("empty", null), state(receiver).locals().field("received"));
        var saved = receiver.write(); receiver = new FlowBlockHost(() -> {}, saved, device); assertTrue(receiver.synchronize(LIMITS, true));
        device.configure("s0", Direction.WEST, false); finish(receiver);
        assertEquals(SerialResult.receive("closed", null), state(receiver).locals().field("received"));
        assertEquals(SerialResult.receive("empty", null), document.flow().orElseThrow().initial().field("received"));
    }

    static Map<String, ProgramDocument> documents() {
        var source = new ProgramWorkspace();
        source.setFlow(Optional.of(new FlowDocumentBody(Map.of("packet", PACKET_TYPE, "handoff", XBusResult.type(), "interval", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Until("retry_paused", new FlowInput.Expression("accepted", "done")),
            new FlowProgram.Write("send", "x0", "packet", Optional.of("handoff")),
            new FlowProgram.Sleep("yield", "interval"), new FlowProgram.EndUntil("end_retry")),
            new DataValue.RecordValue(Map.of("packet", PACKET, "handoff", new DataValue.RecordValue(Map.of(
                "ok", new DataValue.BooleanValue(false), "error", DataValue.NullValue.INSTANCE)), "interval", new DataValue.IntegerValue(20))))));
        var accepted = source.createEntry("accepted"); accepted.putInput("handoff", XBusResult.type());
        String handoff = accepted.add(new ComputationGraph.Input("handoff"), 20, 30);
        String acceptedValue = accepted.add(new ComputationGraph.Field(handoff, "ok"), 210, 30);
        accepted.connect(acceptedValue, "source", handoff); accepted.output("done", acceptedValue);
        var converter = new ProgramWorkspace();
        converter.setFlow(Optional.of(new FlowDocumentBody(Map.of("sent", SerialResult.SEND), List.of(
            new FlowProgram.Read("read", "x1", "input", PACKET_TYPE, true),
            new FlowProgram.Evaluate("inspect", "unpack"), new FlowProgram.If("valid", "ok"),
            new FlowProgram.SerialSend("emit", "s0", "packet", "sent"), new FlowProgram.EndIf("end_valid"),
            new FlowProgram.SerialStatus("inspect_serial", "s0", "serial")), new DataValue.RecordValue(Map.of(
                "sent", SerialResult.send("not_attempted"))))));
        var unpack = converter.createEntry("unpack"); unpack.putInput("input", XBusResult.readType(PACKET_TYPE));
        String input = unpack.add(new ComputationGraph.Input("input"), 20, 40);
        String ok = unpack.add(new ComputationGraph.Field(input, "ok"), 220, 40);
        String key = unpack.add(new ComputationGraph.Constant(new DataValue.TextValue("value")), 20, 170);
        String value = unpack.add(new ComputationGraph.DynamicField(input, key), 220, 170);
        unpack.connect(ok, "source", input); unpack.connect(value, "source", input); unpack.connect(value, "name", key);
        unpack.output("ok", ok); unpack.output("packet", value);
        var receiver = new ProgramWorkspace();
        receiver.setFlow(Optional.of(new FlowDocumentBody(Map.of("received", SerialResult.RECEIVE, "interval", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Until("poll", new FlowInput.Expression("finished", "done")),
            new FlowProgram.SerialReceive("receive", "s0", "received"), new FlowProgram.Sleep("yield", "interval"),
            new FlowProgram.EndUntil("end_poll")), new DataValue.RecordValue(Map.of(
                "received", SerialResult.receive("empty", null), "interval", new DataValue.IntegerValue(1))))));
        var done = receiver.createEntry("finished"); done.putInput("received", SerialResult.RECEIVE);
        String received = done.add(new ComputationGraph.Input("received"), 20, 30);
        String status = done.add(new ComputationGraph.Field(received, "status"), 210, 30);
        String empty = done.add(new ComputationGraph.Constant(new DataValue.TextValue("empty")), 210, 160);
        String equals = done.add(new ComputationGraph.ValueEquals(status, empty), 400, 60);
        String stop = done.add(new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", equals)), 590, 60);
        done.connect(status, "source", received); done.connect(equals, "left", status); done.connect(equals, "right", empty);
        done.connect(stop, "value", equals); done.output("done", stop);
        return Map.of("serial_bridge_source", source.snapshot(), "serial_bridge_converter", converter.snapshot(), "serial_bridge_receiver", receiver.snapshot());
    }
    static ProgramDocument packaged(String id) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = location ->
            SerialConversionExampleTest.class.getClassLoader().getResourceAsStream("assets/" + location.getNamespace() + "/" + location.getPath());
        var example = ProgramExampleCatalog.load(open).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        var document = ProgramExampleCatalog.preview(example, open, ARCHIVE);
        assertEquals(documents().get(id), document);
        return document;
    }
    static FlowBlockHost host(ProgramDocument document, SerialDevice serial) {
        var flow = document.flow().orElseThrow();
        return FlowBlockHost.installed(() -> {}, UUID.randomUUID(), flow.program(document.archive()), flow.initial(), LIMITS, FlowControl.INITIAL, serial);
    }
    static FlowRuntime.Checkpoint state(FlowBlockHost host) { return host.snapshot().orElseThrow().state(); }
    static void finish(FlowBlockHost host) {
        for (int i = 0; i < 100 && state(host).status() != FlowRuntime.Status.COMPLETE; i++) {
            host.tick(BUDGET.workPerSlice(), Map.of());
        }
        assertEquals(FlowRuntime.Status.COMPLETE, state(host).status(), () -> host.diagnostic().toString());
    }
    static final class World implements CableDiscovery.World {
        static final BlockPos A = BlockPos.ZERO, B = new BlockPos(2, 0, 0), CABLE = new BlockPos(1, 0, 0);
        final Map<BlockPos, SerialDevice> devices = new LinkedHashMap<>();
        public boolean loaded(BlockPos pos) { return true; }
        public CableDiscovery.Cell cell(BlockPos pos) { return new CableDiscovery.Cell(pos.equals(CABLE) ? CableDiscovery.Kind.CABLE
            : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null); }
        SerialDevice add(BlockPos pos, Direction side) {
            var device = new SerialDevice(() -> HARDWARE, () -> true, () -> new SerialRecordCodec.Limits(4096, 1000000, ARCHIVE), () -> {});
            device.configure("s0", side, true); devices.put(pos, device); return device;
        }
    }
}
