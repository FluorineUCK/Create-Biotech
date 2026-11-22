package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAssemblyCallTest {
    @Test void debuggerShowsTheScratchCallerAndPinnedProcedureWithoutExportingPrivateFrames() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(FlowProcedureHostTest.program().withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        for (int i = 0; i < 10 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.SLEEPING; i++) host.step(LIMITS, true);
        var status = com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerStatusView.flow(host.flowSnapshot(), true, Optional.empty());
        var stack = (DataValue.ListValue)status.field("call_stack"); assertEquals(1, stack.values().size());
        var frame = (DataValue.RecordValue)stack.values().getFirst();
        assertEquals(new DataValue.TextValue("first"), frame.field("source"));
        assertEquals(new DataValue.TextValue("wait_increment"), frame.field("procedure"));
        assertEquals(new DataValue.IntegerValue(1), frame.field("revision"));
        assertEquals(Set.of("source", "procedure", "revision"), frame.fields().keySet());
        assertEquals(new DataValue.TextValue("wait"), status.field("source"));
    }
    @Test void xbusInPrivateCallRepairsAnOldReceiverWithoutReplayingTheCompletedCaller() {
        var key = new ModuleDefinition.Key("transfer", 1); var packet = ComputerFlowHostTest.PACKET;
        var writerProgram = new FlowProgram(Map.of("packet", packet), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("packet", "packet"), Map.of())),
            new FlowProgram.Procedure("definition", key, Map.of("packet", packet), Map.of(), Map.of()),
            new FlowProgram.Write("send", "x0", "packet"), new FlowProgram.EndProcedure("end"))).withAssembly();
        var readerProgram = new FlowProgram(Map.of(), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of(), Map.of("out", "received"))),
            new FlowProgram.Procedure("definition", key, Map.of(), Map.of("out", packet), Map.of("out", "packet")),
            new FlowProgram.Read("read", "x1", "packet", packet), new FlowProgram.EndProcedure("end"))).withAssembly();
        var writer = new ComputerBlockHost(() -> {}); var reader = new ComputerBlockHost(() -> {});
        writer.installFlow(writerProgram, ComputerFlowHostTest.INPUTS, LIMITS, true);
        reader.installFlow(readerProgram, new DataValue.RecordValue(Map.of()), LIMITS, true);
        writer.setPaused(false, LIMITS, true); reader.setPaused(false, LIMITS, true);
        for (int i = 0; i < 8; i++) { writer.tick(LIMITS, true); reader.tick(LIMITS, true); writer = restored(writer); reader = restored(reader); }
        assertEquals(FlowRuntime.Status.WAITING_WRITE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.WAITING_READ, reader.flowSnapshot().orElseThrow().state().status());
        var oldReader = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0", LIMITS, true, reader, "x1", LIMITS, true));
        for (int i = 0; i < 8; i++) { writer.tick(LIMITS, true); reader.tick(LIMITS, true); writer = restored(writer); reader = restored(reader); }
        assertEquals(FlowRuntime.Status.COMPLETE, writer.flowSnapshot().orElseThrow().state().status());
        var expected = reader.flowSnapshot().orElseThrow(); var completedWriter = writer.write();
        reader = new ComputerBlockHost(() -> {}); reader.read(oldReader); assertTrue(reader.synchronize(LIMITS, true));
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0", LIMITS, true, reader, "x1", LIMITS, true));
        assertEquals(completedWriter, writer.write(), "Repair must not execute the sender or recreate a caller frame");
        for (int i = 0; i < 8; i++) { reader.tick(LIMITS, true); reader = restored(reader); }
        assertEquals(expected, reader.flowSnapshot().orElseThrow());
        assertEquals(ComputerFlowHostTest.INPUTS.field("packet"), expected.state().locals().field("received"));
    }
    @Test void frameAdmissionAndArchiveLimitsNeverPublishHalfACall() {
        var p = FlowProcedureHostTest.program().withAssembly(); var host = new ComputerBlockHost(() -> {});
        host.installFlow(p, FlowProcedureHostTest.INITIAL, LIMITS, true);
        // The two explicit arguments are staged before entering the private activation.
        host.step(LIMITS, true); host.step(LIMITS, true);
        var before = host.flowSnapshot().orElseThrow(); var rawBefore = (CompoundTag)host.write();
        host.step(LIMITS, true); var after = host.flowSnapshot().orElseThrow();
        assertEquals(1, after.state().assembly().orElseThrow().calls().orElseThrow().frames().size());
        assertTrue(after.state().controlUnits() > before.state().controlUnits());
        var tight = new ResourceLimits(1000, before.state().controlUnits() + p.usage(LIMITS.hardware()).constantUnits(), 1);
        var runtime = FlowRuntime.restore(p, before.state(), tight); runtime.load(); runtime.setPaused(false); runtime.advance(1);
        assertEquals(FlowRuntime.Status.FAILED, runtime.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, runtime.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(before.state().assembly(), runtime.checkpoint().assembly());
        assertEquals(runtime.checkpoint(), FlowRuntime.restore(p, runtime.checkpoint(), tight).checkpoint());
        long archiveUnits = ValueNbtCodec.decode(((CompoundTag)host.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
        var archiveLimit = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, archiveUnits - 1)));
        var rejected = new ComputerBlockHost(() -> {}); rejected.read(rawBefore); assertTrue(rejected.synchronize(archiveLimit, true));
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, assertThrows(ComputerBlockHost.HostException.class, () -> rejected.step(archiveLimit, true)).code());
        assertEquals(rawBefore, rejected.write()); rejected.step(LIMITS, true); assertEquals(after, rejected.flowSnapshot().orElseThrow());
    }
    @Test void bodyCodeIsStoredOnceAndOversizedDefinitionsAreRejectedBeforeImageAnalysis() {
        var p = FlowProcedureHostTest.program().withAssembly(); var one = new ArrayList<>(p.steps()); one.remove(1);
        var extended = new ArrayList<>(p.steps()); extended.add(extended.size() - 1, FlowControlInputsHostTest.increment("more", "n"));
        var extendedOne = new ArrayList<>(extended); extendedOne.remove(1);
        long twoDelta = new FlowProgram(p.inputs(), extended, p.expressions()).withAssembly().usage(LIMITS.hardware()).programUnits() - p.usage(LIMITS.hardware()).programUnits();
        long oneDelta = new FlowProgram(p.inputs(), extendedOne, p.expressions()).withAssembly().usage(LIMITS.hardware()).programUnits()
            - new FlowProgram(p.inputs(), one, p.expressions()).withAssembly().usage(LIMITS.hardware()).programUnits();
        assertTrue(oneDelta > 0); assertEquals(oneDelta, twoDelta, "Another call must not duplicate the procedure body");
        var huge = new ArrayList<FlowProgram.Step>();
        for (int i = 0; i < 1000; i++) {
            huge.add(new FlowProgram.Procedure("definition_" + i, new ModuleDefinition.Key("routine_" + i, 1), Map.of(), Map.of(), Map.of()));
            huge.add(new FlowProgram.EndProcedure("end_" + i));
        }
        var error = assertThrows(ComputationException.class, () -> new FlowProgram(Map.of(), huge).withAssembly().validate(new ResourceLimits(32, 100000, 1)));
        assertEquals(ComputationException.Code.PROGRAM_LIMIT, error.code());
        assertEquals("end_10", error.location(), "Reject while emitting the first over-capacity instruction");
    }
    @Test void aDynamicNarrowingCallIsExplicitlyRejectedWithoutReplacingTheInstallation() {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(FlowProcedureHostTest.program().withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        var before = host.write(); var key = new ModuleDefinition.Key("typed", 1);
        var p = new FlowProgram(Map.of("x", DataType.Scalar.ANY), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("x", "x"), Map.of())),
            new FlowProgram.Procedure("definition", key, Map.of("x", DataType.Scalar.INTEGER), Map.of(), Map.of()), new FlowProgram.EndProcedure("end"))).withAssembly();
        var error = assertThrows(ComputationException.class, () -> host.installFlow(p, new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(2))), LIMITS, true));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code()); assertEquals("call", error.location());
        assertEquals(before, host.write());
    }
    @Test void narrowingAnExistingLocalHasASourceDiagnosticAndPreservesTheInstallation() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(FlowProcedureHostTest.program().withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        var before = host.write(); var key = new ModuleDefinition.Key("identity", 1);
        var p = new FlowProgram(Map.of("x", DataType.Scalar.ANY, "n", DataType.Scalar.INTEGER), List.of(
            new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("arg", "n"), Map.of("value", "x"))),
            new FlowProgram.Sleep("wait", "x"),
            new FlowProgram.Procedure("definition", key, Map.of("arg", DataType.Scalar.INTEGER),
                Map.of("value", DataType.Scalar.INTEGER), Map.of("value", "arg")),
            new FlowProgram.EndProcedure("return"))).withAssembly();
        var error = assertThrows(ComputationException.class, () -> host.installFlow(p,
            new DataValue.RecordValue(Map.of("x", new DataValue.TextValue("before"), "n", new DataValue.IntegerValue(2))), LIMITS, true));
        assertEquals(ComputationException.Code.INVALID_GRAPH, error.code());
        assertEquals("call", error.location());
        assertEquals(before, host.write());
    }
    @Test void callArgumentsAndReturnsSupportStaticallyProvenTypeWidening() {
        var limits = ParserHardwareFixture.withMasterParser(LIMITS);
        var key = new ModuleDefinition.Key("identity", 1);
        for (DataType declared : List.of(DataType.Scalar.ANY, new DataType.OptionalType(DataType.Scalar.INTEGER),
                new DataType.RecordType(Map.of("v", DataType.Scalar.ANY)))) {
            DataValue value = declared instanceof DataType.RecordType ? new DataValue.RecordValue(Map.of("v", new DataValue.IntegerValue(41))) : new DataValue.IntegerValue(41);
            var p = new FlowProgram(Map.of("seed", DataType.typeOf(value)), List.of(
                new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("arg", "seed"), Map.of("value", "answer"))),
                new FlowProgram.Procedure("definition", key, Map.of("arg", declared), Map.of("value", declared), Map.of("value", "arg")),
                new FlowProgram.EndProcedure("return"))).withAssembly();
            var host = new ComputerBlockHost(() -> {}); host.installFlow(p, new DataValue.RecordValue(Map.of("seed", value)), limits, true);
            for (int i = 0; i < 20 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) {
                host.step(limits, true); host = restored(host, limits);
            }
            assertEquals(value, host.flowSnapshot().orElseThrow().state().locals().field("answer"));
        }
    }
    @Test void nestedRepeatedCallsRestoreTheirPrivateLoopRegistersAndReleaseTheirFrames() {
        var base = FlowProcedureHostTest.program(); var key = new ModuleDefinition.Key("outer", 1);
        var steps = new ArrayList<FlowProgram.Step>(List.of(
            new FlowProgram.Invoke("run", key, new FlowCallBindings(Map.of("delay", "delay", "source", "source"), Map.of("out", "answer"))),
            new FlowProgram.Procedure("outer", key, Map.of("delay", DataType.Scalar.INTEGER, "source", DataType.Scalar.INTEGER), Map.of("out", DataType.Scalar.INTEGER), Map.of("out", "source")),
            new FlowProgram.Repeat("twice", "delay"),
            new FlowProgram.Invoke("inner", new ModuleDefinition.Key("wait_increment", 1), new FlowCallBindings(Map.of("ticks", "delay", "n", "source"), Map.of("out", "source"))),
            new FlowProgram.EndRepeat("end_twice"), new FlowProgram.EndProcedure("outer_return")));
        steps.addAll(base.steps().subList(2, base.steps().size()));
        var host = new ComputerBlockHost(() -> {}); host.installFlow(new FlowProgram(base.inputs(), steps, base.expressions()).withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        int deepest = 0;
        for (int i = 0; i < 150; i++) {
            var s = host.flowSnapshot().orElseThrow().state(); deepest = Math.max(deepest, s.assembly().orElseThrow().calls().orElseThrow().frames().size());
            host = restored(host); if (s.status() == FlowRuntime.Status.COMPLETE) break;
            assertNotEquals(FlowRuntime.Status.FAILED, s.status()); host.step(LIMITS, true);
        }
        var result = host.flowSnapshot().orElseThrow().state(); assertEquals(FlowRuntime.Status.COMPLETE, result.status());
        assertEquals(2, deepest); assertTrue(result.assembly().orElseThrow().calls().orElseThrow().frames().isEmpty());
        assertEquals(new DataValue.IntegerValue(43), result.locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(41), result.locals().field("source"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"owner", "snapshot", "data", "missing_frame", "caller_pc", "entered", "frame_type"})
    void damagedOrDowngradedCallStackIsRetainedWithoutRunning(String mutation) {
        var host = new ComputerBlockHost(() -> {}); host.installFlow(FlowProcedureHostTest.program().withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        for (int i = 0; i < 20 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.SLEEPING; i++) host.step(LIMITS, true);
        assertEquals(FlowRuntime.Status.SLEEPING, host.flowSnapshot().orElseThrow().state().status());
        var raw = (CompoundTag)host.write(); assertEquals(26, raw.getInt(FlowBlockNbtCodec.MARKER));
        var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        var assembly = (DataValue.RecordValue)state.field("assembly"); var calls = (DataValue.RecordValue)assembly.field("calls");
        var frames = (DataValue.ListValue)calls.field("frames"); var frame = (DataValue.RecordValue)frames.values().getFirst();
        switch (mutation) {
            case "owner" -> raw.putInt(FlowBlockNbtCodec.MARKER, 25);
            case "snapshot" -> flow = patch(flow, "version", new DataValue.IntegerValue(26));
            case "data" -> assembly = patch(assembly, "data_version", new DataValue.IntegerValue(1));
            case "missing_frame" -> calls = patch(calls, "frames", new DataValue.ListValue(List.of()));
            case "caller_pc" -> frame = patch(frame, "pc", new DataValue.IntegerValue(0));
            case "entered" -> frame = patch(frame, "entered", new DataValue.IntegerValue(4));
            case "frame_type" -> {
                var values = (DataValue.RecordValue)frame.field("registers");
                frame = patch(frame, "registers", patch(values, values.fields().keySet().iterator().next(), new DataValue.TextValue("wrong")));
            }
        }
        if (Set.of("caller_pc", "entered", "frame_type").contains(mutation)) calls = patch(calls, "frames", new DataValue.ListValue(List.of(frame)));
        state = patch(state, "assembly", patch(assembly, "calls", calls));
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", state)), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code()); assertEquals(raw, restored.write());
    }
    @Test void meteredTextInsideACallCanRestoreAtEveryCodePoint() {
        var key = new ModuleDefinition.Key("measure", 1);
        var graph = new ComputationGraph(Map.of("text", DataType.Scalar.TEXT), Map.of("text", new ComputationGraph.Input("text"),
            "length", new ComputationGraph.Text(TextOperation.LENGTH, Map.of("text", "text"))), Map.of("value", "length"));
        var expressions = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of(), List.of(),
            Map.of("measure", new ProgramDefinition.Entry(graph, List.of(), List.of()))), Map.of());
        var p = new FlowProgram(Map.of("text", DataType.Scalar.TEXT), List.of(
            new FlowProgram.Invoke("run", key, new FlowCallBindings(Map.of("text", "text"), Map.of("length", "length"))),
            new FlowProgram.Procedure("definition", key, Map.of("text", DataType.Scalar.TEXT), Map.of("length", DataType.Scalar.INTEGER), Map.of("length", "length")),
            new FlowProgram.Evaluate("measure", "measure", new FlowCallBindings(Map.of("text", "text"), Map.of("value", "length"))),
            new FlowProgram.EndProcedure("return")), expressions).withAssembly();
        var host = new ComputerBlockHost(() -> {}); host.installFlow(p, new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("测😀abc"))), LIMITS, true);
        int scanned = 0;
        for (int i = 0; i < 50; i++) {
            var s = host.flowSnapshot().orElseThrow(); if (s.state().assembly().orElseThrow().scan().isPresent()) scanned++;
            host = restored(host);
            if (s.state().status() == FlowRuntime.Status.COMPLETE) break;
            host.step(LIMITS, true);
        }
        assertEquals(5, scanned); assertEquals(new DataValue.IntegerValue(5), host.flowSnapshot().orElseThrow().state().locals().field("length"));
    }
    private static ComputerBlockHost restored(ComputerBlockHost host) {
        return restored(host, LIMITS);
    }
    private static ComputerBlockHost restored(ComputerBlockHost host, ComputerHostLimits limits) {
        var s = host.flowSnapshot().orElseThrow(); var next = new ComputerBlockHost(() -> {}); next.read(host.write());
        assertTrue(next.synchronize(limits, true), () -> "At " + s.state() + " : " + next.diagnostic());
        assertEquals(s, next.flowSnapshot().orElseThrow()); return next;
    }
    @Test void inventedCompletedChildWorkCannotWarmAContinuationPastItsRealCallCost() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(FlowProcedureHostTest.program().withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        for (int i = 0; i < 50; i++) {
            var c = host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().calls().orElseThrow();
            if (c.frames().isEmpty() && c.excludedWork() > 0) break;
            host.step(LIMITS, true);
        }
        var current = host.flowSnapshot().orElseThrow().state();
        assertTrue(current.assembly().orElseThrow().calls().orElseThrow().excludedWork() > 0);
        var raw = (CompoundTag)host.write(); var owner = (DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"), LIMITS.archive());
        var flow = (DataValue.RecordValue)owner.field("flow"); var state = (DataValue.RecordValue)flow.field("state");
        var assembly = (DataValue.RecordValue)state.field("assembly"); var calls = (DataValue.RecordValue)assembly.field("calls");
        long extra = current.assembly().orElseThrow().calls().orElseThrow().excludedWork() + 1;
        assembly = patch(assembly, "calls", patch(calls, "excluded", new DataValue.IntegerValue(extra)));
        state = patch(patch(patch(state, "assembly", assembly), "work", new DataValue.IntegerValue(current.work() + 1)),
            "operation", new DataValue.IntegerValue(Math.max(current.work() + 1, current.cursor())));
        raw.put("Data", ValueNbtCodec.encode(patch(owner, "flow", patch(flow, "state", state)), LIMITS.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(LIMITS, true);
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertEquals(raw, restored.write());
    }
    private static DataValue.RecordValue patch(DataValue.RecordValue value, String key, DataValue replacement) {
        var fields = new LinkedHashMap<>(value.fields()); fields.put(key, replacement); return new DataValue.RecordValue(fields);
    }
    @Test void explicitCallsKeepPrivateLocalsAndResumeInsideSleepWithoutGraphFrames() {
        var host = new ComputerBlockHost(() -> {});
        host.installFlow(FlowProcedureHostTest.program().withAssembly(), FlowProcedureHostTest.INITIAL, LIMITS, true);
        int sleeping = 0;
        for (int i = 0; i < 100; i++) {
            var before = host.flowSnapshot().orElseThrow();
            assertTrue(before.state().expression().isEmpty());
            assertTrue(before.state().calls().frames().isEmpty(), "No legacy interpreted call frames");
            assertTrue(before.state().assembly().isPresent());
            var restored = new ComputerBlockHost(() -> {}); restored.read(host.write());
            assertTrue(restored.synchronize(LIMITS, true), () -> restored.diagnostic().toString());
            assertEquals(before, restored.flowSnapshot().orElseThrow()); host = restored;
            if (before.state().status() == FlowRuntime.Status.COMPLETE) break;
            assertNotEquals(FlowRuntime.Status.FAILED, before.state().status());
            if (before.state().status() == FlowRuntime.Status.SLEEPING) sleeping++;
            host.step(LIMITS, true);
        }
        var result = host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE, result.status());
        assertEquals(4, sleeping, "Two calls each retain two active sleep ticks");
        assertEquals(new DataValue.IntegerValue(42), result.locals().field("answer"));
        assertEquals(new DataValue.IntegerValue(42), result.locals().field("again"));
        assertEquals(new DataValue.IntegerValue(999), result.locals().field("n"));
        assertEquals(new DataValue.IntegerValue(41), result.locals().field("source"));
        assertFalse(result.locals().fields().containsKey("ticks"));
    }
}
