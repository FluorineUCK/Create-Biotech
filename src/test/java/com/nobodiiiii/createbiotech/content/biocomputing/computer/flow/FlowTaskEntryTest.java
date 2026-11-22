package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowTaskEntryTest {
    private static final ResourceLimits LIMITS = new ResourceLimits(1_000, 100_000, 10);
    private static final ModuleDefinition.Key ENTRY = new ModuleDefinition.Key("wait_copy", 1);
    private static DataValue.RecordValue input(long value) {
        return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value)));
    }
    private static FlowProgram program() {
        return new FlowProgram(Map.of("main_only", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Sleep("main_wait", "main_only"),
            new FlowProgram.Procedure("definition", ENTRY, Map.of("value", DataType.Scalar.INTEGER),
                Map.of("result", DataType.Scalar.INTEGER), Map.of("result", "value")),
            new FlowProgram.Sleep("task_wait", "value"), new FlowProgram.EndProcedure("return")));
    }
    @Test void twoProcedureContextsShareDefinitionsButNotLocalsOrSleepContinuations() {
        var program = program(); var computer = UUID.randomUUID();
        var a = FlowRuntime.startProcedure(computer, UUID.randomUUID(), program, ENTRY, input(2), LIMITS);
        var b = FlowRuntime.startProcedure(computer, UUID.randomUUID(), program, ENTRY, input(1), LIMITS);
        assertSame(program, a.program()); assertSame(program, b.program());
        assertEquals("task_wait", a.sourceId().orElseThrow());
        assertEquals(1, a.advance(1)); assertEquals(FlowRuntime.Status.SLEEPING, a.checkpoint().status());
        var waiting = a.checkpoint();
        assertEquals(1, b.advance(1)); b.elapseTick(); assertEquals(1, b.advance(1));
        assertEquals(FlowRuntime.Status.COMPLETE, b.checkpoint().status());
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(1))), b.checkpoint().locals());
        assertEquals(waiting, a.checkpoint(), "Running another context cannot advance this task's clock or locals");
        a.elapseTick(); a.elapseTick(); a.advance(1);
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(2))), a.checkpoint().locals());
        assertEquals(2, a.checkpoint().work(), "No main instructions or synthetic copied call tape execute");
        assertEquals(Optional.of(ENTRY), a.checkpoint().entry());
        assertNotEquals(a.checkpoint().flow(), b.checkpoint().flow());
    }
    @Test void everyTaskBoundaryRoundTripsWithoutFallingIntoMainOrAdvancingPausedTime() {
        var task = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), program(), ENTRY, input(2), LIMITS);
        for (int i = 0; i < 5; i++) {
            task = roundTrip(task);
            task.setPaused(true); var frozen = task.checkpoint();
            task.elapseTick(); assertEquals(0, task.advance(1)); assertEquals(frozen, task.checkpoint());
            task = roundTrip(task); task.unload(); task.elapseTick(); assertEquals(frozen, task.checkpoint());
            task.load(); task.setPaused(false);
            if (task.checkpoint().status() == FlowRuntime.Status.SLEEPING) task.elapseTick(); else task.advance(1);
        }
        task = roundTrip(task);
        assertEquals(FlowRuntime.Status.COMPLETE, task.checkpoint().status());
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(2))), task.checkpoint().locals());
    }
    @Test void nestedCallsAndCapturedLoopsRestoreRelativeToTheTaskEntryNotTheMainTape() {
        var inner = new ModuleDefinition.Key("increment", 1); var outer = new ModuleDefinition.Key("loop", 1);
        var integer = DataType.Scalar.INTEGER;
        var graph = new ComputationGraph(Map.of("n", integer), Map.of("n", new ComputationGraph.Input("n"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "n", "one")), Map.of("value", "sum"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(),
            List.of(), List.of(), Map.of("increment", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var p = new FlowProgram(Map.of("main_only", integer), List.of(
            new FlowProgram.Sleep("main_wait", "main_only"),
            new FlowProgram.Procedure("increment_definition", inner, Map.of("n", integer), Map.of("out", integer), Map.of("out", "n")),
            new FlowProgram.Evaluate("increment", "increment", new FlowCallBindings(Map.of("n", "n"), Map.of("value", "n"))),
            new FlowProgram.EndProcedure("inner_return"),
            new FlowProgram.Procedure("loop_definition", outer, Map.of("value", integer, "count", integer), Map.of("result", integer), Map.of("result", "value")),
            new FlowProgram.Repeat("repeat", "count"),
            new FlowProgram.Invoke("call", inner, new FlowCallBindings(Map.of("n", "value"), Map.of("out", "value"))),
            new FlowProgram.EndRepeat("end_repeat"),
            new FlowProgram.SignalWrite("output", "p0", "value"), new FlowProgram.EndProcedure("loop_return")), archive);
        var task = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), p, outer,
            new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(40), "count", new DataValue.IntegerValue(2))), LIMITS);
        boolean computing = false, calling = false;
        for (int i = 0; task.checkpoint().status() != FlowRuntime.Status.COMPLETE; i++) {
            assertTrue(i < 100); task = roundTrip(task);
            computing |= task.checkpoint().status() == FlowRuntime.Status.COMPUTING;
            calling |= !task.checkpoint().calls().frames().isEmpty();
            assertEquals(1, task.advance(1));
            assertNotEquals(FlowRuntime.Status.FAILED, task.checkpoint().status(), () -> "Task must stay on its own reachable call chain");
        }
        task = roundTrip(task); assertTrue(computing); assertTrue(calling);
        assertEquals(new DataValue.RecordValue(Map.of("result", new DataValue.IntegerValue(42))), task.checkpoint().locals());
        assertEquals(Map.of("p0", 15), task.checkpoint().signals().outputs());
        assertTrue(task.checkpoint().calls().frames().isEmpty()); assertTrue(task.checkpoint().repeats().frames().isEmpty());
    }
    @Test void legacySingleFlowOwnerCannotAccidentallyPublishAnUnmanagedTask() {
        var p = program(); var task = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), p, ENTRY, input(1), LIMITS);
        var mainInputs = new DataValue.RecordValue(Map.of("main_only", new DataValue.IntegerValue(100)));
        assertThrows(IllegalArgumentException.class, () -> new FlowBlockHost.Saved(p, mainInputs, task.checkpoint()));
        var main = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p, mainInputs, LIMITS);
        var carrier = FlowBlockNbtCodec.encode(new FlowBlockHost.Saved(p, mainInputs, main.checkpoint()), FlowArchiveTest.ARCHIVE);
        var data = (DataValue.RecordValue)ValueNbtCodec.decode(carrier.getCompound("Data"), FlowArchiveTest.ARCHIVE);
        var fields = new LinkedHashMap<>(data.fields());
        fields.put("flow", ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p, task.checkpoint()), FlowArchiveTest.ARCHIVE), FlowArchiveTest.ARCHIVE));
        carrier.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), FlowArchiveTest.ARCHIVE));
        assertThrows(ArchiveException.class, () -> FlowBlockNbtCodec.decode(carrier, FlowArchiveTest.ARCHIVE));
    }
    @Test void taskEntryIsPinnedTypedChargedAndCannotBeDowngradedIntoAnOldCheckpoint() {
        var p = program(); var computer = UUID.randomUUID(); var id = UUID.randomUUID();
        var task = FlowRuntime.startProcedure(computer, id, p, ENTRY, input(2), LIMITS);
        assertEquals(ComputationException.Code.MISSING_REFERENCE, assertThrows(ComputationException.class,
            () -> FlowRuntime.startProcedure(computer, id, p, new ModuleDefinition.Key("wait_copy", 2), input(2), LIMITS)).code());
        assertEquals(ComputationException.Code.TYPE_MISMATCH, assertThrows(ComputationException.class,
            () -> FlowRuntime.startProcedure(computer, id, p, ENTRY, new DataValue.RecordValue(Map.of("main_only", new DataValue.IntegerValue(2))), LIMITS)).code());
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, assertThrows(ComputationException.class,
            () -> FlowRuntime.startProcedure(computer, id, p, ENTRY, input(2), new ResourceLimits(p.steps().size() - 1, LIMITS.memoryUnits(), 1))).code());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class,
            () -> FlowRuntime.startProcedure(computer, id, p, ENTRY, input(2), new ResourceLimits(LIMITS.programUnits(), task.checkpoint().controlUnits() - 1, 1))).code());
        var encoded = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p, task.checkpoint()), FlowArchiveTest.ARCHIVE), FlowArchiveTest.ARCHIVE);
        var downgraded = new LinkedHashMap<>(encoded.fields()); downgraded.put("version", new DataValue.IntegerValue(18));
        assertThrows(ArchiveException.class, () -> FlowNbtCodec.decode(ValueNbtCodec.encode(new DataValue.RecordValue(downgraded), FlowArchiveTest.ARCHIVE), FlowArchiveTest.ARCHIVE));
        var state = task.checkpoint();
        var lostRoot = new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), state.locals(), state.work(),
            state.paused(), state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(), state.repeats(), state.inputInvocation(), state.calls());
        assertTrue(state.controlUnits() > lostRoot.controlUnits(), "Per-task entry metadata is not free memory");
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> FlowRuntime.restore(p, lostRoot, LIMITS)).code());
        var main = new FlowRuntime(computer, UUID.randomUUID(), p, new DataValue.RecordValue(Map.of("main_only", new DataValue.IntegerValue(2))), LIMITS);
        var oldValue = (DataValue.RecordValue)ValueNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p, main.checkpoint()), FlowArchiveTest.ARCHIVE), FlowArchiveTest.ARCHIVE);
        assertEquals(new DataValue.IntegerValue(18), oldValue.field("version"), "Existing single-flow saves keep their version");
    }
    @Test void xbusInsideTaskEntryStillRequiresRendezvousAndKeepsItsRootAcrossTheHandoff() {
        var send = new ModuleDefinition.Key("send", 1); var receive = new ModuleDefinition.Key("receive", 1);
        var packet = input(12); var type = (DataType.RecordType)DataType.typeOf(packet);
        var p = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("send_definition", send, Map.of("packet", type), Map.of(), Map.of()),
            new FlowProgram.Write("write", "x0", "packet"), new FlowProgram.EndProcedure("send_return"),
            new FlowProgram.Procedure("receive_definition", receive, Map.of(), Map.of("result", type), Map.of("result", "packet")),
            new FlowProgram.AwaitReadable("ready", "x1"), new FlowProgram.Read("read", "x1", "packet", type),
            new FlowProgram.EndProcedure("receive_return")));
        var a = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), p, send, new DataValue.RecordValue(Map.of("packet", packet)), LIMITS);
        var b = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), p, receive, new DataValue.RecordValue(Map.of()), LIMITS);
        a.advance(1);
        assertEquals(XBusExchange.Status.NOT_READY, XBusExchange.prepare(a, "x0", b, "x1").status());
        b.advance(1); a = roundTrip(a); b = roundTrip(b);
        b.setPaused(true); assertEquals(XBusExchange.Status.PAUSED, XBusExchange.prepare(a, "x0", b, "x1").status());
        b.setPaused(false); var senderWait = a.checkpoint();
        assertEquals(XBusExchange.Status.READABLE, XBusExchange.prepare(a, "x0", b, "x1").commit());
        assertEquals(senderWait, a.checkpoint());
        b = roundTrip(b); b.advance(1); var read = b.checkpoint();
        var preparation = XBusExchange.prepare(a, "x0", b, "x1");
        assertEquals(XBusExchange.Status.EXCHANGED, preparation.commit());
        assertEquals(XBusExchange.Status.STALE, preparation.commit());
        var receipt = new XBusReceipt(new XBusReceipt.Position(senderWait.computer(), senderWait.flow(), senderWait.cursor(), "x0", 0, senderWait.operation(), 1, 0),
            new XBusReceipt.Position(read.computer(), read.flow(), read.cursor(), "x1", 0, read.operation(), 1, 0), packet);
        receipt.validate(p, a.checkpoint()); receipt.validate(p, b.checkpoint());
        a = roundTrip(a); b = roundTrip(b); a.advance(1); b.advance(1);
        a = roundTrip(a); b = roundTrip(b);
        assertEquals(FlowRuntime.Status.COMPLETE, a.checkpoint().status());
        assertEquals(new DataValue.RecordValue(Map.of("result", packet)), b.checkpoint().locals());
        assertEquals(Optional.of(send), a.checkpoint().entry()); assertEquals(Optional.of(receive), b.checkpoint().entry());
    }
    @Test void pausedPeerFailureRemainsAValueOrDiagnosticAndDoesNotLoseTheTaskRoot() {
        for (boolean result : List.of(false, true)) {
            var packet = (DataType.RecordType)DataType.typeOf(input(1));
            var p = new FlowProgram(Map.of(), List.of(
                new FlowProgram.Procedure("definition", ENTRY, Map.of(), Map.of(), Map.of()),
                new FlowProgram.Read("read", "x0", "response", packet, result), new FlowProgram.EndProcedure("return")));
            var task = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), p, ENTRY, new DataValue.RecordValue(Map.of()), LIMITS);
            task.advance(1); assertTrue(task.rejectPausedXBus()); task = roundTrip(task);
            if (result) { task.advance(1); assertEquals(FlowRuntime.Status.COMPLETE, task.checkpoint().status()); }
            else assertEquals(ComputationException.Code.XBUS_PEER_PAUSED, task.checkpoint().diagnostic().orElseThrow().code());
            assertEquals(Optional.of(ENTRY), task.checkpoint().entry()); roundTrip(task);
        }
    }
    @Test void serialCompletionAndFailureAlsoKeepTaskIdentityAcrossRestoration() {
        var status = SerialResult.STATUS;
        var p = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Procedure("definition", ENTRY, Map.of(), Map.of("result", status), Map.of("result", "state")),
            new FlowProgram.SerialStatus("status", "s0", "state"), new FlowProgram.EndProcedure("return")));
        var task = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), p, ENTRY, new DataValue.RecordValue(Map.of()), LIMITS);
        assertTrue(task.serialReady()); assertEquals(0, task.advance(1), "Only the owner may consume or publish physical device state");
        var result = SerialResult.status(new SerialDevice.Status("unavailable", "missing_port", "", new SerialReceiveBuffer.Status(0, 0, Set.of())));
        task.completeSerial(result); task = roundTrip(task); task.advance(1); task = roundTrip(task);
        assertEquals(result, task.checkpoint().locals().field("result"));
        var failed = FlowRuntime.startProcedure(UUID.randomUUID(), UUID.randomUUID(), program(), ENTRY, input(-1), LIMITS);
        failed.advance(1); failed = roundTrip(failed);
        assertEquals(ComputationException.Code.INVALID_ARGUMENT, failed.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(Optional.of(ENTRY), failed.checkpoint().entry());
    }
    private static FlowRuntime roundTrip(FlowRuntime task) {
        var snapshot = new FlowNbtCodec.Snapshot(task.program(), task.checkpoint());
        var raw = FlowNbtCodec.encode(snapshot, FlowArchiveTest.ARCHIVE);
        var value = (DataValue.RecordValue)ValueNbtCodec.decode(raw, FlowArchiveTest.ARCHIVE);
        assertEquals(new DataValue.IntegerValue(FlowProgram.hasSignals(task.program().steps()) ? 20 : 19), value.field("version"));
        var decoded = FlowNbtCodec.decode(raw, FlowArchiveTest.ARCHIVE);
        assertEquals(snapshot, decoded);
        var result = FlowRuntime.restore(decoded.program(), decoded.state(), LIMITS);
        assertEquals(task.checkpoint(), result.checkpoint()); assertFalse(result.loaded());
        result.load(); return result;
    }
}
