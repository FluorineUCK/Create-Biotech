package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowCallBindingsTest {
    static final ResourceLimits LIMITS = new ResourceLimits(100, 100000, 1);
    static final ArchiveLimits ARCHIVE = new ArchiveLimits(100000, 100000, 1000000);

    @Test void explicitPureCallRestoresThroughTheActualComputerOwnerAtEveryContinuationIncludingComplete() {
        var program = bound(Map.of("a", "counter"), Map.of("sum", "counter"));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial(), LIMITS);
        var hostLimits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(LIMITS, ARCHIVE);
        boolean complete = false;
        for (int i = 0; i < 40; i++) {
            var saved = new FlowBlockHost.Saved(program, initial(), runtime.checkpoint());
            var encoded = FlowBlockNbtCodec.encode(saved, ARCHIVE);
            assertEquals(10, encoded.getInt(FlowBlockNbtCodec.MARKER));
            assertEquals(saved, FlowBlockNbtCodec.decode(encoded, ARCHIVE));
            var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
            host.read(encoded); assertTrue(host.outbound(hostLimits, true).isEmpty());
            assertTrue(host.diagnostic().isEmpty(), () -> host.diagnostic().toString());
            assertEquals(saved, host.flowSnapshot().orElseThrow(), "Restoring must not execute the call again");
            assertEquals(encoded, host.write());
            if (saved.state().status() == FlowRuntime.Status.COMPLETE) { complete = true; break; }
            runtime.advance(1);
        }
        assertTrue(complete);
        var serial = new FlowProgram(Map.of(), List.of(new FlowProgram.SerialStatus("s", "s0", "status")));
        var empty = new DataValue.RecordValue(Map.of());
        var state = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), serial, empty, LIMITS).checkpoint();
        var owner = FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(serial, empty, state), ARCHIVE);
        assertEquals(11, owner.getInt(FlowBlockNbtCodec.MARKER));
        owner.putInt(FlowBlockNbtCodec.MARKER, 10);
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(owner, ARCHIVE));
    }

    @Test void explicitArgumentsAndReturnsNeverReadOrOverwriteUnrelatedSameNamedLocals() {
        var program = bound(Map.of("a", "counter"), Map.of("sum", "counter"));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial(), LIMITS);
        for (int i = 0; i < 40 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) runtime.advance(1);
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(42), runtime.checkpoint().locals().field("counter"));
        assertEquals(initial().field("a"), runtime.checkpoint().locals().field("a"));
        assertEquals(initial().field("sum"), runtime.checkpoint().locals().field("sum"));
        assertFalse(runtime.checkpoint().locals().fields().containsKey("echo"));
    }

    @Test void documentAndEveryPausedContinuationKeepTheExplicitMappingAndRejectOlderSchemaClaims() {
        var program = bound(Map.of("a", "counter"), Map.of("sum", "counter"));
        var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(),
            Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial())));
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(16), encoded.field("version"));
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, ARCHIVE), ARCHIVE));
        var downgraded = new LinkedHashMap<>(encoded.fields()); downgraded.put("version", new DataValue.IntegerValue(15));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial(), LIMITS);
        boolean computed = false;
        for (int i = 0; i < 40 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            runtime.advance(1); runtime.setPaused(true);
            computed |= runtime.checkpoint().status() == FlowRuntime.Status.COMPUTING;
            var snapshot = new FlowNbtCodec.Snapshot(program, runtime.checkpoint());
            var saved = FlowNbtCodec.encode(snapshot, ARCHIVE);
            var value = (DataValue.RecordValue) ValueNbtCodec.decode(saved, ARCHIVE);
            assertEquals(new DataValue.IntegerValue(16), value.field("version"));
            var old = new LinkedHashMap<>(value.fields()); old.put("version", new DataValue.IntegerValue(15));
            assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(old), ARCHIVE), ARCHIVE));
            var decoded = FlowNbtCodec.decode(saved, ARCHIVE); assertEquals(snapshot, decoded);
            runtime = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
            assertEquals(0, runtime.advance(1)); runtime.load(); assertEquals(0, runtime.advance(1));
            runtime.setPaused(false);
        }
        assertTrue(computed); assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(42), runtime.checkpoint().locals().field("counter"));
        assertEquals(initial().field("sum"), runtime.checkpoint().locals().field("sum"));
    }

    @Test void missingParametersUnknownPortsDuplicateTargetsAndTypeChangesAreNotSilentlyRebound() {
        for (var args : List.<Map<String, String>>of(Map.of(), Map.of("unknown", "counter"), Map.of("a", "missing")))
            assertThrows(ComputationException.class, () -> bound(args, Map.of("sum", "counter")).validate(LIMITS));
        assertThrows(ComputationException.class, () -> bound(Map.of("a", "counter"), Map.of("unknown", "counter")).validate(LIMITS));
        assertThrows(ComputationException.class, () -> bound(Map.of("a", "counter"), Map.of("sum", "counter", "echo", "counter")));
        var good = bound(Map.of("a", "counter"), Map.of("sum", "text"));
        var inputs = new LinkedHashMap<>(types()); inputs.put("text", DataType.Scalar.TEXT);
        assertThrows(ComputationException.class, () -> new FlowProgram(inputs, good.steps(), good.expressions()).validate(LIMITS));
        inputs.put("counter", DataType.Scalar.TEXT);
        assertThrows(ComputationException.class, () -> new FlowProgram(inputs, good.steps(), good.expressions()).validate(LIMITS));
    }

    @Test void unchangedLegacyCallsStillReadAndPublishByNameWithoutAutomaticMigration() {
        var program = new FlowProgram(types(), List.of(new FlowProgram.Evaluate("old", "increment")), archive());
        var saved = FlowProgramCodec.encode(program); assertEquals(program, FlowProgramCodec.decode(saved));
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial(), LIMITS);
        for (int i = 0; i < 40 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) runtime.advance(1);
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(1001), runtime.checkpoint().locals().field("sum"));
        assertEquals(initial().field("counter"), runtime.checkpoint().locals().field("counter"));
        assertEquals(initial().field("a"), runtime.checkpoint().locals().field("echo"));
        assertEquals(saved, FlowProgramCodec.encode(program));
    }

    static FlowProgram bound(Map<String, String> arguments, Map<String, String> returns) {
        var original = (DataValue.RecordValue) FlowProgramCodec.control(types(), List.of(new FlowProgram.Evaluate("call", "increment")));
        var control = new LinkedHashMap<>(original.fields());
        control.put("steps", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue("evaluate_bound"), "source", new DataValue.TextValue("call"),
            "expression", new DataValue.TextValue("increment"), "arguments", names(arguments), "returns", names(returns))))));
        return FlowProgramCodec.readControl(new DataValue.RecordValue(control), archive());
    }
    static DataValue.RecordValue names(Map<String, String> names) {
        var result = new LinkedHashMap<String, DataValue>(); names.forEach((key, name) -> result.put(key, new DataValue.TextValue(name)));
        return new DataValue.RecordValue(result);
    }
    static Map<String, DataType> types() { return Map.of("counter", DataType.Scalar.INTEGER, "a", DataType.Scalar.INTEGER, "sum", DataType.Scalar.INTEGER); }
    static DataValue.RecordValue initial() { return new DataValue.RecordValue(Map.of("counter", new DataValue.IntegerValue(41),
        "a", new DataValue.IntegerValue(1000), "sum", new DataValue.IntegerValue(2000))); }
    static ProgramArchive archive() {
        var graph = new ComputationGraph(Map.of("a", DataType.Scalar.INTEGER), Map.of(
            "in", new ComputationGraph.Input("a"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "add", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "in", "one")), Map.of("sum", "add", "echo", "in"));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("increment", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
    }
}
