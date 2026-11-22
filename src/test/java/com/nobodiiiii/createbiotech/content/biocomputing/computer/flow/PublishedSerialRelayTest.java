package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentWire;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PublishedSerialRelayTest {
    @Test void aReachablePeerThatDoesNotReplyReturnsUnknownWithoutSendingAgain() throws Exception {
        var standard = SerialFlowHostTest.LIMITS;
        var hardware = new ResourceLimits(256, 65_536, 64);
        var limits = new ComputerHostLimits(hardware, standard.archive(), standard.mailbox(), standard.pins(), standard.serial(), 8, true, true);
        try (var stream = getClass().getResourceAsStream("/assets/create_biotech/biocomputing/examples/redstone_output_b.bcp")) {
            assertNotNull(stream);
            var document = ProgramDocumentWire.decode(Base64.getDecoder().decode(
                new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip()), standard.archive());
            document.validate(hardware);
            var body = document.flow().orElseThrow();
            var tx = SerialFlowHostTest.device();
            var peer = SerialFlowHostTest.device();
            tx.configure("s0", Direction.EAST, true);
            peer.configure("s0", Direction.WEST, true);
            var relay = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), body.program(document.archive()),
                body.initial(), limits, FlowControl.INITIAL, tx);
            var packet = new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("production_command"),
                "version", new DataValue.IntegerValue(1), "target", new DataValue.RecordValue(Map.of(
                    "x", new DataValue.IntegerValue(0), "y", new DataValue.IntegerValue(64), "z", new DataValue.IntegerValue(0))),
                "request", new DataValue.TextValue("test-attempt"), "operation", new DataValue.TextValue("order"),
                "arguments", new DataValue.RecordValue(Map.of("order", new DataValue.TextValue("one-job")))));
            var packetType = new DataType.RecordType(Map.of("kind", DataType.Scalar.TEXT, "version", DataType.Scalar.INTEGER,
                "target", DataType.typeOf(packet.field("target")), "request", DataType.Scalar.TEXT,
                "operation", DataType.Scalar.TEXT, "arguments", DataType.Scalar.ANY));
            var outcome = new DataType.RecordType(Map.of("ok", DataType.Scalar.BOOLEAN, "value", DataType.Scalar.ANY));
            var caller = FlowBlockHost.installed(() -> {}, UUID.randomUUID(),
                new FlowProgram(Map.of("packet", packetType), List.of(new FlowProgram.Write("send", "x0", "packet"),
                    new FlowProgram.Read("result", "x0", "outcome", outcome))).withAssembly(),
                new DataValue.RecordValue(Map.of("packet", packet)), limits);
            relay.setPaused(false); caller.setPaused(false);
            var cable = new BlockPos(1, 0, 0);
            var devices = Map.of(BlockPos.ZERO, tx, new BlockPos(2, 0, 0), peer);
            var world = new CableDiscovery.World() {
                public boolean loaded(BlockPos pos) { return true; }
                public CableDiscovery.Cell cell(BlockPos pos) {
                    return new CableDiscovery.Cell(pos.equals(cable) ? CableDiscovery.Kind.CABLE
                        : devices.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
                }
            };
            var network = new SerialNetwork();
            var discovery = new CommunicationNetworkLimits(64, 4);
            var channel = new SharedSerialChannel.Limits(2, 4096, 128, 8);
            var requests = new ArrayList<DataValue.RecordValue>();
            var exchanges = EnumSet.noneOf(XBusExchange.Status.class);
            boolean restoredDuringWait = false;
            for (int i = 0; i < 1600; i++) {
                network.tick(world, devices, discovery, channel);
                peer.poll("s0").ifPresent(requests::add); // device reads the request but sends no response
                caller.tick(64, Map.of());
                int pending = caller.snapshot().orElseThrow().state().status() == FlowRuntime.Status.WAITING_WRITE ? 15 : 0;
                relay.tick(64, Map.of("x0", pending));
                exchanges.add(caller.exchange("x0", relay, "x0"));
                if (!restoredDuringWait && !requests.isEmpty() && relay.snapshot().orElseThrow().tasks().contexts().stream()
                    .anyMatch(task -> task.status() == FlowRuntime.Status.SLEEPING)) {
                    var durable = relay.write();
                    relay = new FlowBlockHost(() -> {}, durable, tx);
                    assertTrue(relay.synchronize(limits, true));
                    restoredDuringWait = true;
                }
            }
            assertTrue(restoredDuringWait, "Exercise actual restoration after the peer receives the only request");
            assertEquals(List.of(packet), requests, "Must send exactly once; bus=" + exchanges + " tx=" + tx.status("s0")
                + " caller=" + caller.snapshot().orElseThrow().state().status() + " relay=" + relay.snapshot().orElseThrow().state().status()
                + " children=" + relay.snapshot().orElseThrow().tasks().contexts().stream().map(FlowRuntime.Checkpoint::status).toList());
            assertEquals("ready", tx.status("s0").connection(), "This is lost reply, not wiring failure");
            assertEquals(FlowRuntime.Status.COMPLETE, caller.snapshot().orElseThrow().state().status(),
                "The caller must not be left waiting forever for a missing response");
            var result = assertInstanceOf(DataValue.RecordValue.class, caller.snapshot().orElseThrow().state().locals().field("outcome"));
            assertEquals(new DataValue.BooleanValue(false), result.field("ok"));
            var error = assertInstanceOf(DataValue.RecordValue.class, result.field("value"));
            assertEquals(new DataValue.TextValue("reply_timeout"), error.field("status"));
            assertEquals(new DataValue.TextValue("ready"), ((DataValue.RecordValue) error.field("line")).field("connection"));
        }
    }
}
