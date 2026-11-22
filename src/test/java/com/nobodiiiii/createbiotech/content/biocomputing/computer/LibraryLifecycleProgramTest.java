package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.gametest.ClusterLibraryPrograms.obj;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** A player-importable test fixture made only of public nodes; not a shipped application or installer. */
class LibraryLifecycleProgramTest {
    @Test void exportedStorageProbeUsesOrdinaryRequestAndCorrelatedReplyWithoutWorldReferences() throws Exception {
        var payload = obj("op", "stat", "volume", "日志", "entry", "lifecycle_probe");
        var graph = new ComputationGraph(Map.of(), Map.of(
            "target", new ComputationGraph.Constant(new DataValue.TextValue("books")),
            "payload", new ComputationGraph.Constant(payload)), Map.of("target", "target", "payload", "payload"));
        var reply = new ComputationGraph(Map.of("code", DataType.Scalar.TEXT, "data", DataType.Scalar.ANY), Map.of(
            "code", new ComputationGraph.Input("code"), "data", new ComputationGraph.Input("data"),
            "old", new ComputationGraph.ReadMemory("calls"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")),
            Map.of("code", "code", "data", "data", "calls", "next"));
        var archive = new ProgramArchive(new ProgramDefinition(graph, Map.of(
            "code", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue("")),
            "result", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE),
            "calls", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))), List.of(),
            List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "reply")),
            Map.of("reply", new ProgramDefinition.Entry(reply, List.of(
                new ProgramDefinition.Write("code", "code"), new ProgramDefinition.Write("result", "data"),
                new ProgramDefinition.Write("calls", "calls")), List.of()))), Map.of());
        var document = new ProgramWorkspace(ProgramDocument.of(archive)).snapshot();
        var bytes = ProgramDocumentWire.encode(document, EventEntryFixtures.ARCHIVE);
        assertEquals(document, ProgramDocumentWire.decode(bytes, EventEntryFixtures.ARCHIVE));
        document.archive().compile(new ResourceLimits(32, 32768, 4));
        var computer = new ComputerRuntime(UUID.randomUUID(), document.archive(), HARDWARE);
        computer.begin(obj()); finish(computer);
        var request = computer.outbound().orElseThrow();
        assertEquals("books", request.endpoint()); assertEquals(payload, request.payload());
        acknowledge(computer);
        var absence = obj("present", new DataValue.BooleanValue(false), "value", DataValue.NullValue.INSTANCE,
            "version", obj("medium", UUID.randomUUID().toString(), "entry", "lifecycle_probe", "revision", new DataValue.IntegerValue(0)));
        assertEquals(AdmissionStatus.ACCEPTED, computer.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0,
            obj("code", "ok", "data", absence), new MessageProtocol.Reply(((OutboundProtocol.Request) request.protocol()).correlation()))));
        computer.step(); finish(computer);
        assertEquals(absence, computer.snapshot().machine().memory().field("result"));
        assertEquals(new DataValue.IntegerValue(1), computer.snapshot().machine().memory().field("calls"));
        assertTrue(computer.outbound().isEmpty()); assertTrue(computer.snapshot().rpc().pending().isEmpty());
        var path = Path.of("build", "generated", "biocomputing-verification", "library_lifecycle_probe.bcp");
        Files.createDirectories(path.getParent()); Files.writeString(path, Base64.getEncoder().encodeToString(bytes));
    }
}
