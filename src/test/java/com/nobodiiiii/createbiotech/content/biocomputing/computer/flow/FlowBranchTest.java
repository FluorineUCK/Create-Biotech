package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusFlowTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import org.junit.jupiter.api.Test;

class FlowBranchTest {
    @Test void bothArmsMayRebindAnExistingLocalToTheSameNewType() {
        var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN, "result", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.If("if", "ok"), new FlowProgram.Evaluate("a", "a"), new FlowProgram.Otherwise("else"),
            new FlowProgram.Evaluate("b", "b"), new FlowProgram.EndIf("end"), new FlowProgram.Write("write", "x0", "result")),
            archive(Map.of("a", constants(Map.of("result", packet(12))), "b", constants(Map.of("result", packet(19))))));
        for (boolean flag : List.of(false, true)) {
            var flow = runtime(program, new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(flag), "result", new DataValue.IntegerValue(0))));
            int slices = 0;
            while (flow.checkpoint().status() != FlowRuntime.Status.WAITING_WRITE) {
                assertTrue(slices++ < 30); flow.advance(1); flow = FlowArchiveTest.roundTrip(flow);
            }
            assertEquals(packet(flag ? 12 : 19), flow.checkpoint().locals().field("result"));
        }
    }
    @Test void ordinaryComparisonGraphSuppliesTheConditionAndWaitingConsumesNoExtraBudget() {
        var graph = new ComputationGraph(Map.of("value", DataType.Scalar.INTEGER), Map.of(
            "value", new ComputationGraph.Input("value"), "zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
            "negative", new ComputationGraph.Binary(ComputationGraph.BinaryOp.LESS_INTEGER, "value", "zero")), Map.of("ok", "negative"));
        var program = new FlowProgram(Map.of("value", DataType.Scalar.INTEGER), List.of(new FlowProgram.Evaluate("compare", "compare"),
            new FlowProgram.If("if", "ok"), new FlowProgram.AwaitReadable("negative", "x0"), new FlowProgram.Otherwise("else"),
            new FlowProgram.AwaitReadable("nonnegative", "x1"), new FlowProgram.EndIf("end")), archive(Map.of("compare", graph)));
        for (long value : List.of(-1L, 0L, 5L)) {
            var flow = runtime(program, new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))));
            int slices = 0;
            while (flow.checkpoint().status() != FlowRuntime.Status.WAITING_READABLE) {
                assertTrue(slices++ < 30); flow.advance(1); flow = FlowArchiveTest.roundTrip(flow);
            }
            assertEquals(Optional.of(value < 0 ? "negative" : "nonnegative"), flow.sourceId());
            var before = flow.checkpoint(); assertEquals(0, flow.advance(1)); assertEquals(before, flow.checkpoint());
        }
    }
    @Test void branchesUseVersionedDocumentsAndSnapshotsWithoutUpgradingOldLinearDocuments() {
        var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(new FlowProgram.If("if", "ok"), new FlowProgram.EndIf("end")));
        var initial = new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false)));
        var document = new ProgramDocument(program.expressions(), Map.of(), Optional.empty(), Optional.of(new FlowDocumentBody(program.inputs(), program.steps(), initial)));
        var encoded = ProgramDocumentCodec.encode(document);
        assertEquals(new DataValue.IntegerValue(4), encoded.field("version"));
        assertEquals(document, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, FlowArchiveTest.ARCHIVE), FlowArchiveTest.ARCHIVE));
        var runtime = runtime(program, initial); runtime.advance(1);
        var tag = FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(program, runtime.checkpoint()), FlowArchiveTest.ARCHIVE);
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(tag, FlowArchiveTest.ARCHIVE);
        assertEquals(new DataValue.IntegerValue(2), data.field("version"));
        assertEquals(runtime.checkpoint(), FlowArchiveTest.roundTrip(runtime).checkpoint());
        var downgraded = new LinkedHashMap<>(encoded.fields()); downgraded.put("version", new DataValue.IntegerValue(3));
        assertThrows(ArchiveException.class, () -> ProgramDocumentCodec.decode(new DataValue.RecordValue(downgraded)));
        var old = new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x0")));
        assertEquals(new DataValue.IntegerValue(3), ProgramDocumentCodec.encode(new ProgramDocument(old.expressions(), Map.of(), Optional.empty(),
            Optional.of(new FlowDocumentBody(old.inputs(), old.steps(), EMPTY)))).field("version"));
    }

    @Test void nestedFalseBranchesNeverExecuteSkippedReadOrFailingExpression() {
        var failure = new ComputationGraph(Map.of(), Map.of("max", new ComputationGraph.Constant(new DataValue.IntegerValue(Long.MAX_VALUE)),
            "bad", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "max", "max")), Map.of("unused", "bad"));
        var archive = archive(Map.of("bad", failure));
        var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(
            new FlowProgram.If("outer", "ok"), new FlowProgram.Read("unconnected", "x9", "temp", PACKET),
            new FlowProgram.Otherwise("else"), new FlowProgram.If("inner", "ok"),
            new FlowProgram.Evaluate("would_fail", "bad"), new FlowProgram.EndIf("inner_end"), new FlowProgram.EndIf("outer_end")), archive);
        var flow = runtime(program, new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false))));
        for (int i = 0; i < 3; i++) { flow.advance(1); flow = FlowArchiveTest.roundTrip(flow); }
        assertEquals(FlowRuntime.Status.COMPLETE, flow.checkpoint().status());
        assertEquals(3, flow.checkpoint().work()); assertEquals(Set.of("ok"), flow.checkpoint().locals().fields().keySet());
    }

    @Test void bothBranchesExportSameTypedLocalAndOneSidedTempsLeaveScope() {
        var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(
            new FlowProgram.If("if", "ok"), new FlowProgram.Evaluate("a", "a"), new FlowProgram.Otherwise("else"),
            new FlowProgram.Evaluate("b", "b"), new FlowProgram.EndIf("end"), new FlowProgram.Write("write", "x0", "result")),
            archive(Map.of("a", constants(Map.of("result", packet(12), "temporary", new DataValue.TextValue("only a"))),
                "b", constants(Map.of("result", packet(19))))));
        for (boolean flag : List.of(false, true)) {
            var flow = runtime(program, new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(flag))));
            int slices = 0;
            while (flow.checkpoint().status() != FlowRuntime.Status.WAITING_WRITE) {
                assertTrue(slices++ < 40); flow.advance(1); flow = FlowArchiveTest.roundTrip(flow);
            }
            assertEquals(packet(flag ? 12 : 19), flow.checkpoint().locals().field("result"));
            assertFalse(flow.checkpoint().locals().fields().containsKey("temporary"));
        }
    }

    @Test void malformedStructuresUndefinedConditionsAndLeakingLocalsAreRejected() {
        for (var steps : List.of(List.of(new FlowProgram.Otherwise("else")), List.of(new FlowProgram.EndIf("end")),
            List.of(new FlowProgram.If("if", "ok")), List.of(new FlowProgram.If("if", "ok"), new FlowProgram.Otherwise("e1"),
                new FlowProgram.Otherwise("e2"), new FlowProgram.EndIf("end")))) {
            var error = assertThrows(ComputationException.class, () -> new FlowProgram(Map.of(), new ArrayList<>(steps)).validate(LIMITS));
            assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        }
        var branch = List.<FlowProgram.Step>of(new FlowProgram.If("if", "ok"), new FlowProgram.EndIf("end"));
        assertThrows(ComputationException.class, () -> new FlowProgram(Map.of("ok", DataType.Scalar.INTEGER), branch).validate(LIMITS));
        assertThrows(ComputationException.class, () -> new FlowProgram(Map.of(), branch).validate(LIMITS));
        var leaking = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(new FlowProgram.If("if", "ok"),
            new FlowProgram.Read("read", "x0", "temp", PACKET), new FlowProgram.EndIf("end"), new FlowProgram.Write("write", "x0", "temp")));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> leaking.validate(LIMITS)).code());
    }

    @Test void incompatibleJoinAndForgedWaitingWorkAreRejectedAndBlocksCostCapacity() {
        var conflicting = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN, "packet", DataType.Scalar.INTEGER),
            List.of(new FlowProgram.If("if", "ok"), new FlowProgram.Read("read", "x0", "packet", PACKET), new FlowProgram.EndIf("end")));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> conflicting.validate(LIMITS)).code());
        var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN), List.of(new FlowProgram.If("if", "ok"),
            new FlowProgram.Read("skip", "x0", "temp", PACKET), new FlowProgram.EndIf("end"), new FlowProgram.Read("read", "x1", "packet", PACKET)));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, assertThrows(ComputationException.class,
            () -> program.validate(new ResourceLimits(3, 100_000, 1))).code());
        var flow = runtime(program, new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(false)))); flow.advance(1);
        var state = flow.checkpoint();
        var forged = new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), FlowRuntime.Status.WAITING_READ, state.locals(), state.work(), false);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> FlowRuntime.restore(program, forged, LIMITS)).code());
        flow.advance(1); assertEquals(FlowRuntime.Status.WAITING_READ, FlowArchiveTest.roundTrip(flow).checkpoint().status());
    }

    private static ComputationGraph constants(Map<String, DataValue> values) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>(); var outputs = new LinkedHashMap<String, String>();
        values.forEach((name, value) -> { nodes.put(name, new ComputationGraph.Constant(value)); outputs.put(name, name); });
        return new ComputationGraph(Map.of(), nodes, outputs);
    }
    private static ProgramArchive archive(Map<String, ComputationGraph> graphs) {
        var entries = new LinkedHashMap<String, ProgramDefinition.Entry>(); graphs.forEach((name, graph) -> entries.put(name, new ProgramDefinition.Entry(graph, List.of(), List.of())));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(), entries), Map.of());
    }
    @Test void onlyChosenBranchWaitsAndSkippedInstructionsDoNotConsumeWork() {
        for (boolean condition : List.of(false, true)) {
            var program = new FlowProgram(Map.of("ok", DataType.Scalar.BOOLEAN, "packet", PACKET), List.of(
                new FlowProgram.If("choose", "ok"), new FlowProgram.Write("yes", "x0", "packet"),
                new FlowProgram.Otherwise("otherwise"), new FlowProgram.Write("no", "x1", "packet"),
                new FlowProgram.EndIf("join")));
            var initial = new DataValue.RecordValue(Map.of("ok", new DataValue.BooleanValue(condition), "packet", packet(12)));
            var flow = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial, LIMITS);
            assertEquals(1, flow.advance(1));
            assertEquals(Optional.of(condition ? "yes" : "no"), flow.sourceId());
            assertEquals(1, flow.checkpoint().work());
            flow = restored(flow);
            assertEquals(1, flow.advance(1));
            assertEquals(FlowRuntime.Status.WAITING_WRITE, flow.checkpoint().status());
            var receiver = runtime(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x2", "input", PACKET))), EMPTY);
            receiver.advance(1);
            assertEquals(XBusExchange.Status.EXCHANGED, XBusExchange.prepare(flow, condition ? "x0" : "x1", receiver, "x2").commit());
            flow = restored(flow);
            flow.advance(1);
            assertEquals(FlowRuntime.Status.COMPLETE, flow.checkpoint().status());
            assertEquals(3, flow.checkpoint().work());
            assertEquals(packet(12), receiver.checkpoint().locals().field("input"));
            assertEquals(initial, restored(flow).checkpoint().locals());
        }
    }
    private static FlowRuntime restored(FlowRuntime runtime) {
        var next = FlowRuntime.restore(runtime.program(), runtime.checkpoint(), LIMITS); next.load(); return next;
    }
}
