package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowProgramCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowUntilInputTest {
    static final ResourceLimits LIMITS = new ResourceLimits(1000, 100000, 1);

    @Test void localRepeatKeepsItsCanonicalLegacyShapeRatherThanAcceptingAnUntaggedNewShape() {
        assertThrows(ArchiveException.class, () -> counted(record("kind", text("local"), "name", text("n"))));
    }

    @Test void localBooleanIsReadAgainAndBodyOnlyNamesDoNotEscape() {
        var graph = new ComputationGraph(Map.of(), Map.of("true", new ComputationGraph.Constant(new DataValue.BooleanValue(true))), Map.of("done", "true"));
        var expressions = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("set", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var program = new FlowProgram(Map.of("done", DataType.Scalar.BOOLEAN), List.of(new FlowProgram.Until("until", "done"),
            new FlowProgram.Evaluate("set", "set"), new FlowProgram.EndUntil("end")), expressions);
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, new DataValue.RecordValue(Map.of("done", new DataValue.BooleanValue(false))), LIMITS);
        for (int i = 0; i < 30 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            runtime.advance(1); runtime = FlowForeverTest.roundTrip(runtime);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.BooleanValue(true), runtime.checkpoint().locals().field("done"));
        var leaked = new FlowProgram(Map.of(), List.of(new FlowProgram.Until("until", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.Read("read", "x0", "body_only", XBusFlowTest.PACKET), new FlowProgram.EndUntil("end"),
            new FlowProgram.Write("write", "x0", "body_only")));
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> leaked.validate(LIMITS)).code());
    }

    @Test void inputTypesReferencesAndStructuredDelimitersAreCheckedBeforeExecution() {
        for (var input : List.of(literal(new DataValue.IntegerValue(1)), literal(new DataValue.DecimalValue(1)), literal(text("true")),
                literal(DataValue.NullValue.INSTANCE), record("kind", text("local"), "name", text("missing")), expression("condition", "count"))) {
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> until(input).validate(LIMITS)).code());
        }
        for (var input : List.of(expression("missing", "done"), expression("condition", "missing")))
            assertEquals(ComputationException.Code.MISSING_REFERENCE, assertThrows(ComputationException.class, () -> until(input).validate(LIMITS)).code());
        for (var value : List.of(new DataValue.BooleanValue(true), new DataValue.DecimalValue(2)))
            assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> counted(literal(value)).validate(LIMITS)).code());
        var wrongInputs = new FlowProgram(Map.of("count", DataType.Scalar.INTEGER), until(expression("condition", "done")).steps(), archive());
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class, () -> wrongInputs.validate(LIMITS)).code());
        var negative = FlowRepeatTest.runtime(counted(literal(new DataValue.IntegerValue(-1))), 3); negative.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, negative.checkpoint().status());
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, negative.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(0, negative.checkpoint().cursor()); FlowForeverTest.roundTrip(negative);
        var condition = new FlowInput.Literal(new DataValue.BooleanValue(true));
        for (var end : List.<FlowProgram.Step>of(new FlowProgram.Otherwise("end"), new FlowProgram.EndForever("end"), new FlowProgram.EndRepeat("end")))
            assertThrows(ComputationException.class, () -> FlowBranches.compile(List.of(new FlowProgram.Until("until", condition), end)));
    }

    @Test void untilWithSleepSignalsAndXBusKeepsNewOperationIdentitiesAcrossEveryRestore() {
        var template = until(expression("condition", "done"));
        var inputs = new LinkedHashMap<>(template.inputs()); inputs.put("ticks", DataType.Scalar.INTEGER);
        var program = new FlowProgram(inputs, List.of(template.steps().getFirst(), new FlowProgram.Sleep("pace", "ticks"),
            new FlowProgram.SignalWrite("drive", "p1", "n"), new FlowProgram.SignalRead("sample", "p0", "temporary"),
            new FlowProgram.Read("read", "x1", "packet", XBusFlowTest.PACKET), new FlowProgram.Evaluate("increment", "increment"),
            new FlowProgram.EndUntil("end")), template.expressions());
        for (long n : List.of(0L, 2L)) {
            var initial = new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(0), "n", new DataValue.IntegerValue(n), "ticks", new DataValue.IntegerValue(2)));
            var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), program, initial, LIMITS);
            int exchanges = 0; long operation = -1;
            for (int i = 0; i < 160 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
                runtime.elapseTick(); runtime.advanceRound(Map.of("p0", 12));
                if (runtime.checkpoint().status() == FlowRuntime.Status.WAITING_READ) {
                    assertTrue(runtime.checkpoint().operation() > operation); operation = runtime.checkpoint().operation();
                    var writer = XBusFlowTest.writer(); writer.advance(1);
                    assertEquals(XBusExchange.Status.EXCHANGED, XBusFlowTest.exchange(writer, runtime).commit()); exchanges++;
                }
                runtime = FlowForeverTest.roundTrip(runtime);
            }
            assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status()); assertEquals(n, exchanges);
            assertEquals(new DataValue.IntegerValue(n), runtime.checkpoint().locals().field("count"));
            assertEquals(initial.fields().keySet(), runtime.checkpoint().locals().fields().keySet());
            assertEquals(n == 0 ? Map.of() : Map.of("p1", 2), runtime.checkpoint().signals().outputs());
        }
    }

    @Test void repeatAndUntilNestWithoutLosingTheOuterCapturedCount() {
        var template = until(expression("condition", "done"));
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(new FlowProgram.Repeat("outer", new FlowInput.Literal(new DataValue.IntegerValue(2))));
        steps.addAll(template.steps()); steps.add(new FlowProgram.EndRepeat("outer_end"));
        var runtime = FlowRepeatTest.runtime(new FlowProgram(template.inputs(), steps, template.expressions()), 3);
        for (int i = 0; i < 250 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            runtime.advance(1); runtime = FlowForeverTest.roundTrip(runtime);
        }
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(3), runtime.checkpoint().locals().field("count"), "The second Until entrance is already true");
        assertTrue(runtime.checkpoint().repeats().frames().isEmpty());
    }

    @Test void literalSlotsAreChargedAtInstallationAndWhileOwningAContinuation() {
        var program = new FlowProgram(Map.of(), List.of(new FlowProgram.Until("until", new FlowInput.Literal(new DataValue.BooleanValue(true))),
            new FlowProgram.EndUntil("end")));
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class,
            () -> program.validate(new ResourceLimits(100, 2, 1))).code(), "Empty pure RAM uses two units; the slot literal is additional");
        program.validate(new ResourceLimits(100, 3, 1));
        var empty = new DataValue.RecordValue(Map.of());
        var computer = UUID.randomUUID(); var flow = UUID.randomUUID();
        var probe = new FlowRuntime(computer, flow, program, empty, LIMITS);
        long control = probe.checkpoint().controlUnits();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class,
            () -> new FlowRuntime(computer, flow, program, empty, new ResourceLimits(100, control + 2, 1))).code());
        var exact = new FlowRuntime(computer, flow, program, empty, new ResourceLimits(100, control + 3, 1));
        exact.advance(1); assertEquals(FlowRuntime.Status.COMPLETE, exact.checkpoint().status());
    }

    @Test void countedInputCapturesLiteralOrExpressionOnceDespiteBodyChangingTheParameter() {
        for (var input : List.of(literal(new DataValue.IntegerValue(3)), expression("times", "times"))) {
            var runtime = FlowRepeatTest.runtime(counted(input), 3);
            for (int i = 0; i < 100 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++)
                assertEquals(1, runtime.advanceRound(Map.of("p0", 0)));
            assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
            assertEquals(new DataValue.IntegerValue(3), runtime.checkpoint().locals().field("count"));
            assertEquals(new DataValue.IntegerValue(0), runtime.checkpoint().locals().field("n"));
        }
    }

    @Test void literalTrueSkipsTheBodyAndLiteralFalseRemainsBudgeted() {
        var zero = FlowRepeatTest.runtime(until(literal(new DataValue.BooleanValue(true))), 3);
        assertEquals(1, zero.advance(1));
        assertEquals(FlowRuntime.Status.COMPLETE, zero.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(0), zero.checkpoint().locals().field("count"));
        var endless = FlowRepeatTest.runtime(until(literal(new DataValue.BooleanValue(false))), 3);
        for (int i = 0; i < 20; i++) assertEquals(1, endless.advance(1));
        assertEquals(20, endless.checkpoint().work());
        assertNotEquals(FlowRuntime.Status.COMPLETE, endless.checkpoint().status());
        assertTrue(((DataValue.IntegerValue)endless.checkpoint().locals().field("count")).value() > 0);
    }

    @Test void untilReevaluatesNestedCallWithCurrentArgumentsAndDoesNotPublishOtherOutputs() {
        var program = until(expression("condition", "done"));
        var runtime = FlowRepeatTest.runtime(program, 3);
        assertEquals(1, runtime.advance(1));
        assertEquals(FlowRuntime.Status.COMPUTING, runtime.checkpoint().status());
        assertEquals(0, runtime.checkpoint().cursor(), "Body waits for the condition graph");
        assertEquals(new DataValue.IntegerValue(0), runtime.checkpoint().locals().field("count"));
        for (int step = 0; step < 200 && runtime.checkpoint().status() != FlowRuntime.Status.COMPLETE; step++)
            assertEquals(1, runtime.advance(1));
        assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
        assertEquals(new DataValue.IntegerValue(3), runtime.checkpoint().locals().field("count"),
            "Nested Call takes current parameters on each check; unrelated count=999 output is not assigned");
        assertEquals(Set.of("count", "n"), runtime.checkpoint().locals().fields().keySet());
    }

    static FlowProgram until(DataValue condition) {
        return decode(List.of(record("kind", text("until"), "source", text("until"), "condition", condition),
            record("kind", text("evaluate"), "source", text("increment"), "expression", text("increment")),
            FlowForeverTest.delimiter("end_until", "end")), archive());
    }
    static FlowProgram counted(DataValue input) {
        var archive = archive(); var entries = new LinkedHashMap<>(archive.program().entries());
        entries.put("times", new ProgramDefinition.Entry(new ComputationGraph(Map.of("n", DataType.Scalar.INTEGER),
            Map.of("value", new ComputationGraph.Input("n")), Map.of("times", "value")), List.of(), List.of()));
        var expressions = new ProgramArchive(new ProgramDefinition(archive.program().graph(), Map.of(), List.of(), List.of(), entries), archive.modules());
        return decode(List.of(record("kind", text("repeat"), "source", text("repeat"), "count", input),
            record("kind", text("signal_read"), "source", text("mutate"), "port", text("p0"), "local", text("n")),
            record("kind", text("evaluate"), "source", text("increment"), "expression", text("increment")),
            FlowForeverTest.delimiter("end_repeat", "end")), expressions);
    }
    static FlowProgram decode(List<DataValue> steps, ProgramArchive archive) {
        var template = new FlowProgram(Map.of("count", DataType.Scalar.INTEGER, "n", DataType.Scalar.INTEGER), List.of(), archive);
        var data = new LinkedHashMap<>(((DataValue.RecordValue)FlowProgramCodec.encode(template)).fields());
        data.put("steps", new DataValue.ListValue(steps));
        return FlowProgramCodec.decode(new DataValue.RecordValue(data));
    }
    static ProgramArchive archive() {
        var key = new ModuleDefinition.Key("at_limit", 1);
        var module = new ModuleDefinition(key, new ComputationGraph(Map.of("x", DataType.Scalar.INTEGER, "limit", DataType.Scalar.INTEGER),
            Map.of("x", new ComputationGraph.Input("x"), "limit", new ComputationGraph.Input("limit"),
                "test", new ComputationGraph.Binary(ComputationGraph.BinaryOp.EQUAL_INTEGER, "x", "limit")), Map.of("done", "test")));
        var graph = new ComputationGraph(Map.of("count", DataType.Scalar.INTEGER, "n", DataType.Scalar.INTEGER), Map.of(
            "count", new ComputationGraph.Input("count"), "n", new ComputationGraph.Input("n"),
            "call", new ComputationGraph.Call(key, Map.of("x", "count", "limit", "n")),
            "done", new ComputationGraph.Field("call", "done"), "other", new ComputationGraph.Constant(new DataValue.IntegerValue(999))),
            Map.of("done", "done", "count", "other"));
        var increment = FlowRepeatTest.counter().expressions().program().entries().get("increment");
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("increment", increment, "condition", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of(key, module));
    }
    static DataValue expression(String entry, String output) {
        return record("kind", text("expression"), "entry", text(entry), "output", text(output));
    }
    static DataValue literal(DataValue value) { return record("kind", text("literal"), "value", value); }
    static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    static DataValue.RecordValue record(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], (DataValue)pairs[i + 1]);
        return new DataValue.RecordValue(fields);
    }
}
