package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Ordinary editor documents: no application-specific node, install hook or transport bypass. */
class XBusCalculatorExampleTest {
    private static final ArchiveLimits ARCHIVE = new ArchiveLimits(10000, 100000, 1000000);
    private static final ResourceLimits HARDWARE = new ResourceLimits(32, 32768, 4);
    private static final DataType.RecordType PACKET = new DataType.RecordType(Map.of("value", DataType.Scalar.INTEGER));
    private static DataValue.RecordValue record(long value) {
        return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value)));
    }
    private static ProgramDocument caller() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("packet", PACKET), List.of(
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.Read("receive", "x0", "result", PACKET)),
            new DataValue.RecordValue(Map.of("packet", record(12))))));
        return workspace.snapshot();
    }
    private static ProgramDocument worker() {
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(), List.of(
            new FlowProgram.Read("receive", "x1", "packet", PACKET), new FlowProgram.Evaluate("calculate", "sum"),
            new FlowProgram.Write("return", "x1", "packet")), new DataValue.RecordValue(Map.of()))));
        var graph = workspace.createEntry("sum"); graph.putInput("packet", PACKET);
        String input = graph.add(new ComputationGraph.Input("packet"), 20, 30);
        String value = graph.add(new ComputationGraph.Field(input, "value"), 210, 30);
        String seven = graph.add(new ComputationGraph.Constant(new DataValue.IntegerValue(7)), 210, 165);
        String sum = graph.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, value, seven), 410, 65);
        String result = graph.add(new ComputationGraph.MakeRecord(Map.of("value", sum)), 610, 65);
        graph.connect(value, "source", input);
        graph.connect(sum, "left", value); graph.connect(sum, "right", seven);
        graph.connect(result, "value", sum);
        graph.output("packet", result);
        return workspace.snapshot();
    }
    @Test void exportPublicDocumentsForResourcePackaging() throws Exception {
        var directory = Path.of("build/generated/biocomputing-examples"); Files.createDirectories(directory);
        for (var example : Map.of("xbus_calculator_caller", caller(), "xbus_calculator_worker", worker()).entrySet()) {
            var document = example.getValue(); document.validate(HARDWARE);
            byte[] bytes = ProgramDocumentWire.encode(document, ARCHIVE);
            assertEquals(document, ProgramDocumentWire.decode(bytes, ARCHIVE));
            assertEquals(document, new ProgramWorkspace(document).snapshot());
            Files.writeString(directory.resolve(example.getKey() + ".bcp"), Base64.getEncoder().encodeToString(bytes));
        }
    }
    @Test void shippedExamplesBlockThenReturnCalculatedRecordOnTheSameWire() {
        var aDocument = packaged("xbus_calculator_caller"); var bDocument = packaged("xbus_calculator_worker");
        assertEquals(caller(), aDocument); assertEquals(worker(), bDocument);
        var a = runtime(aDocument); var b = runtime(bDocument);
        a.advance(1); var waiting = a.checkpoint();
        assertEquals(XBusExchange.Status.NOT_READY, XBusExchange.prepare(a, "x0", b, "x1").status());
        assertEquals(waiting, a.checkpoint()); assertEquals(0, b.checkpoint().work());
        b.advance(1);
        assertEquals(XBusExchange.Status.EXCHANGED, XBusExchange.prepare(a, "x0", b, "x1").commit());
        a.advance(1); int slices = 0;
        while (b.checkpoint().status() != FlowRuntime.Status.WAITING_WRITE) {
            assertTrue(++slices < 100); b.advance(1);
        }
        assertTrue(slices > 2, "The worker executes ordinary budgeted pure nodes");
        assertEquals(record(19), b.checkpoint().locals().field("packet"));
        assertEquals(XBusExchange.Status.EXCHANGED, XBusExchange.prepare(a, "x0", b, "x1").commit());
        assertEquals(FlowRuntime.Status.COMPLETE, a.checkpoint().status());
        assertEquals(FlowRuntime.Status.COMPLETE, b.checkpoint().status());
        assertEquals(record(12), a.checkpoint().locals().field("packet"));
        assertEquals(record(19), a.checkpoint().locals().field("result"));
        assertEquals(caller(), aDocument, "Running never changes the portable initial values");
        assertEquals(XBusExchange.Status.NOT_READY, XBusExchange.prepare(a, "x0", b, "x1").status());
    }
    private static FlowRuntime runtime(ProgramDocument document) {
        var body = document.flow().orElseThrow();
        return new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), body.program(document.archive()), body.initial(), HARDWARE);
    }
    private static ProgramDocument packaged(String id) {
        java.util.function.Function<net.minecraft.resources.ResourceLocation, java.io.InputStream> open = location ->
            XBusCalculatorExampleTest.class.getClassLoader().getResourceAsStream("assets/" + location.getNamespace() + "/" + location.getPath());
        var example = ProgramExampleCatalog.load(open).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        return ProgramExampleCatalog.preview(example, open, ARCHIVE);
    }
}
