package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/** A deliberately small, editable single-command tutorial, not the forthcoming multi-operation driver. */
public class LibrarySerialExampleTest {
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    static final ResourceLimits BUDGET = new ResourceLimits(32, 32768, 4);
    static final SerialHardware HARDWARE = new SerialHardware(Map.of("s0", new SerialHardware.Port(128, 4096, 2, 8192)));
    static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(BUDGET, ARCHIVE, MailboxLimits.DEFAULT, HardwarePins.DEFAULT, HARDWARE));
    @Test void packagedTutorialUsesOnlyEditablePublicInstructionsAndCanRejectAnOldReply() throws Exception {
        String resource = "assets/create_biotech/biocomputing/examples/library_serial_command.bcp";
        byte[] bytes;
        try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "Published single-command tutorial"); bytes = Base64.getDecoder().decode(new String(stream.readAllBytes()).strip());
        }
        var document = ProgramDocumentWire.decode(bytes, ARCHIVE); assertEquals(document(), document); document.validate(BUDGET);
        var seek = new LinkedHashMap<String, DataValue>(packet().fields()); seek.put("operation", new DataValue.TextValue("seek")); seek.put("data", packet().field("target"));
        assertTrue(document.flow().orElseThrow().inputs().get("packet").accepts(new DataValue.RecordValue(seek)), "Editable command data can be a track, slot or entry name");
        assertEquals(document, new ProgramWorkspace(document).snapshot());
        var device = new SerialDevice(() -> HARDWARE, () -> true, () -> new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE), () -> {});
        device.configure("s0", Direction.EAST, true);
        var body = document.flow().orElseThrow();
        var host = FlowBlockHost.installed(() -> {}, UUID.randomUUID(), body.program(document.archive()), body.initial(), LIMITS, FlowControl.INITIAL, device);
        host.setPaused(false);
        var reply = new LinkedHashMap<String, DataValue>(Map.of("kind", new DataValue.TextValue("library_reply"), "target", packet().field("target"),
            "request", new DataValue.IntegerValue(0), "sequence", new DataValue.IntegerValue(0), "code", new DataValue.TextValue("idle"), "value", DataValue.NullValue.INSTANCE));
        var receiver = device.attachments().getFirst().receiver();
        receiver.offer(SerialRecordCodec.encode(new DataValue.RecordValue(reply), new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE)));
        for (int i = 0; i < 80; i++) host.tick(4, Map.of());
        assertEquals(new DataValue.BooleanValue(false), host.snapshot().orElseThrow().state().locals().field("matched"));
        assertNotEquals(FlowRuntime.Status.COMPLETE, host.snapshot().orElseThrow().state().status());
        var saved = host.write(); host = new FlowBlockHost(() -> {}, saved, device); assertTrue(host.synchronize(LIMITS, true));
        reply.put("request", packet().field("request"));
        receiver.offer(SerialRecordCodec.encode(new DataValue.RecordValue(reply), new SerialRecordCodec.Limits(4096, 1048576, ARCHIVE)));
        for (int i = 0; i < 80 && host.snapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) host.tick(4, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, host.snapshot().orElseThrow().state().status());
        assertEquals(new DataValue.BooleanValue(true), host.snapshot().orElseThrow().state().locals().field("matched"));
        assertEquals(0, receiver.status().frames()); assertEquals("queued", device.status("s0").transmit(), "Only one explicit send; no hardware time or retries invented");
    }
    static DataValue.RecordValue packet() {
        return new DataValue.RecordValue(Map.of("kind", new DataValue.TextValue("library_command"),
            "target", new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(0), "y", new DataValue.IntegerValue(0), "z", new DataValue.IntegerValue(0))),
            "request", new DataValue.IntegerValue(1), "operation", new DataValue.TextValue("status"), "sequence", new DataValue.IntegerValue(0), "data", DataValue.NullValue.INSTANCE));
    }
    public static ProgramDocument document() {
        var entries = new LinkedHashMap<String, ProgramDefinition.Entry>();
        var fields = new LinkedHashMap<>(((DataType.RecordType)DataType.typeOf(packet())).fields()); fields.put("data", DataType.Scalar.ANY);
        var packetType = new DataType.RecordType(fields);
        var receivedNodes = new LinkedHashMap<String, ComputationGraph.Node>();
        receivedNodes.put("input", new ComputationGraph.Input("received")); receivedNodes.put("ok", new ComputationGraph.Field("input", "ok"));
        entries.put("received_ok", new ProgramDefinition.Entry(new ComputationGraph(Map.of("received", SerialResult.RECEIVE), receivedNodes, Map.of("got", "ok")), List.of(), List.of()));
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        nodes.put("received", new ComputationGraph.Input("received")); nodes.put("packet", new ComputationGraph.Input("packet"));
        nodes.put("reply", new ComputationGraph.Field("received", "value"));
        for (String key : List.of("kind", "target", "request")) {
            nodes.put("key_" + key, new ComputationGraph.Constant(new DataValue.TextValue(key)));
            nodes.put("reply_" + key, new ComputationGraph.DynamicField("reply", "key_" + key));
            nodes.put("expected_" + key, key.equals("kind") ? new ComputationGraph.Constant(new DataValue.TextValue("library_reply")) : new ComputationGraph.Field("packet", key));
            nodes.put("same_" + key, new ComputationGraph.ValueEquals("reply_" + key, "expected_" + key));
        }
        nodes.put("same_envelope", new ComputationGraph.Scalar(ScalarOperation.AND_BOOLEAN, Map.of("left", "same_kind", "right", "same_target")));
        nodes.put("matches", new ComputationGraph.Scalar(ScalarOperation.AND_BOOLEAN, Map.of("left", "same_envelope", "right", "same_request")));
        entries.put("match_reply", new ProgramDefinition.Entry(new ComputationGraph(Map.of("received", SerialResult.RECEIVE, "packet", packetType), nodes,
            Map.of("matched", "matches")), List.of(), List.of()));
        var definition = new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), entries);
        var workspace = new ProgramWorkspace(ProgramDocument.of(new ProgramArchive(definition, Map.of())));
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("packet", packetType, "matched", DataType.Scalar.BOOLEAN,
            "received", SerialResult.RECEIVE, "got", DataType.Scalar.BOOLEAN, "interval", DataType.Scalar.INTEGER), List.of(
                new FlowProgram.SerialSend("submit_once", "s0", "packet", "sent"),
                new FlowProgram.Until("await_reply", "matched"), new FlowProgram.SerialReceive("receive", "s0", "received"),
                new FlowProgram.Evaluate("inspect", "received_ok"), new FlowProgram.If("has_frame", "got"),
                new FlowProgram.Evaluate("correlate", "match_reply"), new FlowProgram.EndIf("end_frame"),
                new FlowProgram.Sleep("yield", "interval"), new FlowProgram.EndUntil("end_wait"),
                new FlowProgram.SerialStatus("inspect_hardware", "s0", "serial")),
            new DataValue.RecordValue(Map.of("packet", packet(), "matched", new DataValue.BooleanValue(false), "got", new DataValue.BooleanValue(false),
                "received", SerialResult.receive("empty", null), "interval", new DataValue.IntegerValue(1))))));
        return workspace.snapshot();
    }
    /** Explicit maintainer generator; ordinary test execution never rewrites packaged assets. */
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]).toAbsolutePath().normalize();
        var encoded = Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document(), ARCHIVE)) + "\n";
        Files.writeString(root.resolve("src/main/resources/assets/create_biotech/biocomputing/examples/library_serial_command.bcp"), encoded);
        Files.writeString(root.resolve("examples/biocomputing/library_serial_command.bcp"), encoded);
    }
}
