package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowProcedureHostTest {
    static DataValue.RecordValue obj(Object... pairs) {
        var fields = new LinkedHashMap<String, DataValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String)pairs[i], switch (pairs[i + 1]) {
            case DataValue value -> value;
            case String value -> new DataValue.TextValue(value);
            case Integer value -> new DataValue.IntegerValue(value);
            default -> throw new IllegalArgumentException();
        });
        return new DataValue.RecordValue(fields);
    }
    static final DataValue.RecordValue INITIAL = new DataValue.RecordValue(Map.of(
        "delay", new DataValue.IntegerValue(2), "source", new DataValue.IntegerValue(41), "n", new DataValue.IntegerValue(999)));
    static FlowProgram program() {
        var steps = List.of(
            call("first", "answer"), call("second", "again"),
            obj("kind", "procedure", "source", "definition", "name", "wait_increment", "revision", 1,
                "inputs", TypeArchiveCodec.encode(new DataType.RecordType(Map.of("ticks", DataType.Scalar.INTEGER, "n", DataType.Scalar.INTEGER))),
                "outputs", TypeArchiveCodec.encode(new DataType.RecordType(Map.of("out", DataType.Scalar.INTEGER))), "returns", obj("out", "n")),
            obj("kind", "sleep", "source", "wait", "ticks", "ticks"),
            obj("kind", "evaluate_bound", "source", "increment", "expression", "increment", "arguments", obj("n", "n"), "returns", obj("value", "n")),
            obj("kind", "end_procedure", "source", "return"));
        return FlowProgramCodec.decode(obj("inputs", TypeArchiveCodec.encode(DataType.typeOf(INITIAL)),
            "steps", new DataValue.ListValue(new ArrayList<>(steps)), "expressions", ProgramArchiveCodec.encode(FlowControlInputsHostTest.expressions())));
    }
    static DataValue.RecordValue call(String source, String target) {
        return obj("kind", "invoke", "source", source, "name", "wait_increment", "revision", 1,
            "arguments", obj("ticks", "delay", "n", "source"), "returns", obj("out", target));
    }
    @Test void oneSharedProcedureWaitsAndReturnsExplicitValuesAcrossOwnerReloads() {
        var p = program(); var result = restoredResult(p);
        assertEquals(new DataValue.IntegerValue(42), result.locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(42), result.locals().field("again"));
        assertEquals(new DataValue.IntegerValue(999), result.locals().field("n"));
        assertEquals(new DataValue.IntegerValue(41), result.locals().field("source"));
        assertFalse(result.locals().fields().containsKey("ticks"));
    }
    @Test void nestedCallsKeepTheCallersCapturedLoopAndOnlyPublishSelectedReturns() {
        var base = program(); var key = new ModuleDefinition.Key("outer", 1);
        var steps = new ArrayList<FlowProgram.Step>(List.of(
            new FlowProgram.Invoke("run", key, new FlowCallBindings(Map.of("delay", "delay", "source", "source"), Map.of("out", "answer"))),
            new FlowProgram.Procedure("outer", key, Map.of("delay", DataType.Scalar.INTEGER, "source", DataType.Scalar.INTEGER), Map.of("out", DataType.Scalar.INTEGER), Map.of("out", "source")),
            new FlowProgram.Repeat("twice", "delay"),
            new FlowProgram.Invoke("inner", new ModuleDefinition.Key("wait_increment", 1), new FlowCallBindings(Map.of("ticks", "delay", "n", "source"), Map.of("out", "source"))),
            new FlowProgram.EndRepeat("end_twice"), new FlowProgram.EndProcedure("outer_return")));
        steps.addAll(base.steps().subList(2, base.steps().size()));
        var result = restoredResult(new FlowProgram(base.inputs(), steps, base.expressions()));
        assertEquals(new DataValue.IntegerValue(43), result.locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(41), result.locals().field("source"));
        assertTrue(result.calls().frames().isEmpty());
    }
    @Test void oldDocumentVersionsCannotImportUnversionedProcedureBlocksIntoTheEditor() {
        var workspace = new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();
        workspace.setFlow(Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.empty()));
        var encoded = com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(workspace.snapshot());
        var fields = new LinkedHashMap<>(encoded.fields());
        var key = new ModuleDefinition.Key("identity", 1);
        var p = new FlowProgram(Map.of("x", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("in", "x"), Map.of("out", "x"))),
            new FlowProgram.Procedure("definition", key, Map.of("in", DataType.Scalar.INTEGER), Map.of("out", DataType.Scalar.INTEGER), Map.of("out", "in")),
            new FlowProgram.EndProcedure("end")));
        fields.put("program", ProgramArchiveCodec.encode(p.expressions()));
        fields.put("flow", obj("control", FlowProgramCodec.control(p.inputs(), p.steps()), "initial", obj("x", 1)));
        assertThrows(ArchiveException.class, () -> com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
    }
    @Test void xbusInsideAProcedureRepairsAnOlderPeerWaitWithoutDroppingItsCallerOrResending() {
        var packet = ComputerFlowHostTest.PACKET; var limits = ComputerFlowHostTest.LIMITS;
        var key = new ModuleDefinition.Key("transfer", 1);
        var writerProgram = new FlowProgram(Map.of("packet", packet), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("packet", "packet"), Map.of())),
            new FlowProgram.Procedure("definition", key, Map.of("packet", packet), Map.of(), Map.of()),
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.EndProcedure("end")));
        var readerProgram = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of(), Map.of("out", "received"))),
            new FlowProgram.Procedure("definition", key, Map.of(), Map.of("out", packet), Map.of("out", "packet")),
            new FlowProgram.Read("read", "x1", "packet", packet), new FlowProgram.EndProcedure("end")));
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        ComputerXBusExchangeTest.start(writer, writerProgram, ComputerFlowHostTest.INPUTS);
        ComputerXBusExchangeTest.start(reader, readerProgram, new DataValue.RecordValue(Map.of()));
        writer.tick(limits, true); reader.tick(limits, true);
        assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowSnapshot().orElseThrow().state().status());
        var oldReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerXBusExchangeTest.exchange(writer, reader));
        for (int i = 0; i < 2; i++) { writer.tick(limits, true); reader.tick(limits, true); }
        assertEquals(FlowRuntime.Status.COMPLETE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.COMPLETE, reader.flowSnapshot().orElseThrow().state().status());
        var recoveredWriter = ComputerXBusExchangeTest.restore(writer.write());
        var recoveredReader = ComputerXBusExchangeTest.restore(oldReader);
        var before = recoveredWriter.write();
        assertEquals(XBusExchange.Status.EXCHANGED, ComputerXBusExchangeTest.exchange(recoveredWriter, recoveredReader));
        assertEquals(before, recoveredWriter.write(), "Repair cannot replay a completed sender's procedure");
        for (int i = 0; i < 2; i++) recoveredReader.tick(limits, true);
        assertEquals(reader.flowSnapshot(), recoveredReader.flowSnapshot());
        assertEquals(ComputerFlowHostTest.INPUTS.field("packet"), recoveredReader.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(XBusExchange.Status.NOT_READY, ComputerXBusExchangeTest.exchange(recoveredWriter, recoveredReader));
    }
    @Test void sharedCodeCostsOnceButCapturedCallerFramesConsumeTheSameMemoryBudget() {
        var p = program(); var limits = FlowControlInputsHostTest.LIMITS.hardware();
        long pureUnits = p.expressions().compile(limits).usage().programUnits();
        var exactCode = new ResourceLimits(pureUnits + p.steps().size(), limits.memoryUnits(), 1);
        assertDoesNotThrow(() -> p.validate(exactCode));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, assertThrows(ComputationException.class,
            () -> p.validate(new ResourceLimits(exactCode.programUnits() - 1, limits.memoryUnits(), 1))).code());
        var roomy = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, INITIAL, limits);
        var usage = p.expressions().compile(limits).usage();
        long initialMemory = roomy.checkpoint().controlUnits() + usage.constantUnits() + 2 * usage.initialMemoryUnits();
        var tight = new ResourceLimits(exactCode.programUnits(), initialMemory, 1);
        var limited = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, INITIAL, tight);
        assertEquals(1, limited.advance(1));
        var failed = limited.checkpoint();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, failed.diagnostic().orElseThrow().code());
        assertEquals(0, failed.cursor()); assertEquals(INITIAL, failed.locals()); assertTrue(failed.calls().frames().isEmpty());
        assertEquals(failed, FlowRuntime.restore(p, failed, tight).checkpoint());
    }
    @Test void savedCallStackAndFixedVersionsCannotBeDroppedReboundOrMadeRecursive() {
        var p = program(); var limits = FlowControlInputsHostTest.LIMITS;
        var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, INITIAL, limits.hardware());
        runtime.advance(1); var state = runtime.checkpoint();
        var encoded = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p, state), limits.archive()), limits.archive());
        assertEquals(new DataValue.IntegerValue(18), encoded.field("version"));
        var old = new LinkedHashMap<>(encoded.fields()); old.put("version", new DataValue.IntegerValue(17));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(old), limits.archive()), limits.archive()));
        var missing = new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(), state.work(), state.paused(),
            state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(), state.repeats(), state.inputInvocation(), FlowCallStack.EMPTY);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> FlowRuntime.restore(p, missing, limits.hardware())).code());
        var steps = new ArrayList<>(p.steps()); var call = (FlowProgram.Invoke)steps.getFirst();
        steps.set(0, new FlowProgram.Invoke(call.sourceId(), new ModuleDefinition.Key(call.procedure().id(), 2), call.bindings()));
        assertEquals(ComputationException.Code.MISSING_REFERENCE, assertThrows(ComputationException.class, () -> new FlowProgram(p.inputs(), steps, p.expressions()).validate(limits.hardware())).code());
        steps.set(0, call); steps.set(3, new FlowProgram.Invoke("recursive", call.procedure(), new FlowCallBindings(Map.of("ticks", "ticks", "n", "n"), Map.of("out", "n"))));
        assertEquals(ComputationException.Code.INVALID_GRAPH, assertThrows(ComputationException.class, () -> new FlowProgram(p.inputs(), steps, p.expressions()).validate(limits.hardware())).code());
    }
    static FlowRuntime.Checkpoint restoredResult(FlowProgram p) {
        var limits = FlowControlInputsHostTest.LIMITS;
        var host = new ComputerBlockHost(() -> {}); host.installFlow(p, INITIAL, limits, true);
        boolean sleeping = false, computing = false;
        for (int i = 0; host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
            assertTrue(i < 150, () -> "Procedure failed to return");
            host.step(limits, true);
            var snapshot = host.flowSnapshot().orElseThrow(); var state = snapshot.state();
            assertNotEquals(FlowRuntime.Status.FAILED, state.status(), () -> state.diagnostic().toString());
            sleeping |= state.status() == FlowRuntime.Status.SLEEPING;
            computing |= state.status() == FlowRuntime.Status.COMPUTING;
            var saved = host.write(); host.unload();
            var restored = new ComputerBlockHost(() -> {}); restored.read(saved); restored.tick(limits, true);
            assertEquals(snapshot, restored.flowSnapshot().orElseThrow());
            host = restored;
        }
        var result = host.flowSnapshot().orElseThrow().state();
        assertTrue(sleeping); assertTrue(computing);
        assertEquals(p, host.flowSnapshot().orElseThrow().program());
        return result;
    }
}
