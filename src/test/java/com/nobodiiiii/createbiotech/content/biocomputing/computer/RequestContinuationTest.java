package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestContinuationTest {
    @Test void chainedContinuationAndAnotherCallerRetainIndependentOriginsThroughDisk() {
        var base = worker(); var program = base.program(); var entries = new LinkedHashMap<>(program.entries());
        var end = entries.get("stored");
        var nodes = new LinkedHashMap<>(end.graph().nodes());
        nodes.put("target", new ComputationGraph.Constant(new DataValue.TextValue("second-service")));
        var graph = new ComputationGraph(end.graph().inputs(), nodes, Map.of("payload", "saved", "target", "target"));
        entries.put("stored", new ProgramDefinition.Entry(graph, List.of(), List.of(
            ProgramDefinition.Send.continueRequest("target", "payload", "final", "done", "payload", "saved"))));
        entries.put("done", end);
        var archive = new ProgramArchive(new ProgramDefinition(program.graph(), program.cells(), program.writes(), program.sends(), entries), base.modules());
        var worker = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE);
        var first = incoming(UUID.randomUUID(), 0, 2, "first");
        var second = incoming(UUID.randomUUID(), 0, 9, "second");
        worker.receive(first); worker.step(); finish(worker);
        var firstChild = worker.outbound().orElseThrow(); acknowledge(worker);
        worker.receive(childReply(firstChild, input("answer", 0))); worker.step(); finish(worker);
        var firstGrandchild = worker.outbound().orElseThrow(); acknowledge(worker);
        worker.receive(second); worker.step(); finish(worker);
        var secondChild = worker.outbound().orElseThrow(); acknowledge(worker);
        assertEquals(Set.of("storage", "final"), worker.snapshot().rpc().pending().keySet());
        worker = roundTrip(worker);
        worker.receive(childReply(firstGrandchild, input("answer", 0))); worker = stepThroughArchives(worker);
        var firstAnswer = worker.outbound().orElseThrow(); acknowledge(worker);
        assertEquals("first", firstAnswer.endpoint()); assertEquals(input("answer", 3), firstAnswer.payload());
        assertEquals(((MessageProtocol.Request)first.protocol()).context().correlation(),
            ((OutboundProtocol.Reply)firstAnswer.protocol()).correlation());
        worker.receive(childReply(secondChild, input("answer", 0))); worker = stepThroughArchives(worker);
        var secondGrandchild = worker.outbound().orElseThrow(); acknowledge(worker);
        worker.receive(childReply(secondGrandchild, input("answer", 0))); worker = stepThroughArchives(worker);
        var secondAnswer = worker.outbound().orElseThrow();
        assertEquals("second", secondAnswer.endpoint()); assertEquals(input("answer", 10), secondAnswer.payload());
        assertEquals(((MessageProtocol.Request)second.protocol()).context().correlation(),
            ((OutboundProtocol.Reply)secondAnswer.protocol()).correlation());
        assertTrue(worker.snapshot().rpc().pending().isEmpty());
    }
    @ParameterizedTest(name = "final capture failure, budgeted slice = {0}")
    @ValueSource(booleans = {false, true})
    void finalCaptureBudgetFailureRollsBackRamAndKeepsEarlierWait(boolean sliced) {
        var archive = workerWithRequestWrite();
        var runtime = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE);
        var caller = UUID.randomUUID();
        runtime.begin(input("manual", 2)); finish(runtime); acknowledge(runtime);
        var oldPending = runtime.snapshot().rpc().pending();
        runtime.receive(incoming(caller, 1, 9, "second")); runtime.step();
        var running = runtime.snapshot();
        long peakRpc = running.rpc().storageUnits();
        while (runtime.report().status() == ComputationMachine.Status.RUNNING) {
            peakRpc = Math.max(peakRpc, runtime.snapshot().rpc().storageUnits());
            runtime.step();
        }
        var success = runtime.snapshot();
        assertEquals(ComputationMachine.Status.SUCCEEDED, success.machine().status());
        long capacity = success.machine().arenaUnits() + peakRpc + success.mailbox().storageUnits();
        assertTrue(capacity < success.machine().arenaUnits() + success.rpc().storageUnits() + success.mailbox().storageUnits());
        var limited = new ResourceLimits(HARDWARE.programUnits(), capacity, HARDWARE.workPerSlice());
        var restored = ComputerRuntime.restore(running, limited); restored.load();
        for (int i = 0; i < 100 && restored.report().status() == ComputationMachine.Status.RUNNING; i++) {
            if (sliced) restored.runSlice(1); else restored.step();
        }
        assertEquals(ComputationMachine.Status.FAILED, restored.report().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT, restored.report().diagnostic().orElseThrow().code());
        assertEquals(running.machine().memory(), restored.memory());
        assertEquals(oldPending, restored.snapshot().rpc().pending());
        assertTrue(restored.snapshot().rpc().staged().isEmpty()); assertTrue(restored.outbound().isEmpty());
        assertEquals(restored.snapshot(), ComputerRuntime.restore(restored.snapshot(), limited).snapshot());
    }
    @Test void newerGenerationKeepsItsOwnOriginAndRejectsStaleOrCollidingReplies() {
        var worker = new ComputerRuntime(UUID.randomUUID(), worker(), HARDWARE);
        worker.receive(incoming(UUID.randomUUID(), 0, 2, "first")); worker.step(); finish(worker);
        var old = worker.outbound().orElseThrow(); acknowledge(worker);
        worker.receive(incoming(UUID.randomUUID(), 0, 9, "second")); worker.step(); finish(worker);
        var current = worker.outbound().orElseThrow(); acknowledge(worker);
        var before = worker.snapshot();
        assertEquals(AdmissionStatus.STALE_REPLY, worker.receive(childReply(old, input("answer", 0))));
        assertEquals(AdmissionStatus.TYPE_MISMATCH, worker.receive(childReply(current,
            new DataValue.RecordValue(Map.of("answer", new DataValue.IntegerValue(0), "saved", input("answer", -1))))));
        assertEquals(before, worker.snapshot());
        assertEquals(AdmissionStatus.ACCEPTED, worker.receive(childReply(current, input("answer", 0))));
        worker.step(); finish(worker);
        assertEquals("second", worker.outbound().orElseThrow().endpoint());
        assertEquals(input("answer", 10), worker.outbound().orElseThrow().payload());
    }
    @Test void continuationCannotBeForgedByInvokingCallbackAsLocalEvent() {
        var worker = new ComputerRuntime(UUID.randomUUID(), worker(), HARDWARE);
        var before = worker.snapshot();
        var payload = new DataValue.RecordValue(Map.of("answer", new DataValue.IntegerValue(1), "saved", input("answer", 7)));
        assertEquals(ComputationException.Code.INVALID_GRAPH,
            assertThrows(ComputationException.class, () -> worker.beginLocal("stored", payload)).code());
        assertEquals(before, worker.snapshot());
    }
    private static MessageEnvelope incoming(UUID source, long sequence, long number, String port) {
        var event = UUID.randomUUID();
        return new MessageEnvelope(source, sequence, event, 0, input("number", number),
            new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), port)));
    }
    private static MessageEnvelope childReply(OutboundMessage child, DataValue.RecordValue payload) {
        return new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, payload,
            new MessageProtocol.Reply(((OutboundProtocol.Request)child.protocol()).correlation()));
    }
    private static ProgramArchive workerWithRequestWrite() {
        var base = worker(); var program = base.program(); var entries = new LinkedHashMap<>(program.entries());
        var request = entries.get("request"); var outputs = new LinkedHashMap<>(request.graph().outputs()); outputs.put("value", "sum");
        var graph = new ComputationGraph(request.graph().inputs(), request.graph().nodes(), outputs);
        entries.put("request", new ProgramDefinition.Entry(graph, List.of(new ProgramDefinition.Write("result", "value")), request.sends()));
        var rootNodes = new LinkedHashMap<>(program.graph().nodes());
        rootNodes.put("target", new ComputationGraph.Constant(new DataValue.TextValue("other")));
        rootNodes.put("payload", new ComputationGraph.MakeRecord(Map.of("value", "input")));
        var root = new ComputationGraph(program.graph().inputs(), rootNodes, Map.of("value", "input", "target", "target", "payload", "payload"));
        entries.put("ordinary", new ProgramDefinition.Entry(root, List.of(), List.of()));
        return new ProgramArchive(new ProgramDefinition(root, program.cells(), program.writes(),
            List.of(ProgramDefinition.Send.request("target", "payload", "other-wait", "ordinary")), entries), base.modules());
    }
    @Test void eachPausedStepAndCommittedOutboxSurviveArchiveRoundTrip() {
        var caller = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        caller.begin(input("query", 7)); finish(caller);
        var worker = new ComputerRuntime(UUID.randomUUID(), worker(), HARDWARE);
        worker.receive(request(caller.id(), caller.outbound().orElseThrow(), "original-port"));
        worker.setPaused(true);
        worker = stepThroughArchives(worker);
        var child = worker.outbound().orElseThrow(); acknowledge(worker);
        var incoming = new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("answer", 42),
            new MessageProtocol.Reply(((OutboundProtocol.Request)child.protocol()).correlation()));
        assertEquals(AdmissionStatus.ACCEPTED, worker.receive(incoming));
        worker = roundTrip(worker);
        worker = stepThroughArchives(worker);
        assertEquals("original-port", worker.outbound().orElseThrow().endpoint());
        assertEquals(input("answer", 8), worker.outbound().orElseThrow().payload());
        assertTrue(worker.snapshot().rpc().pending().isEmpty());
    }
    private static ComputerRuntime stepThroughArchives(ComputerRuntime runtime) {
        for (int step = 0; step < 100; step++) {
            runtime.step(); runtime = roundTrip(runtime);
            if (runtime.report().status() == ComputationMachine.Status.SUCCEEDED) return runtime;
            assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
        }
        throw new AssertionError("Event did not finish");
    }
    private static ComputerRuntime roundTrip(ComputerRuntime runtime) {
        var before = runtime.snapshot();
        var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(before, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE);
        var restored = ComputerRuntime.restore(decoded, HARDWARE);
        assertEquals(before, restored.snapshot()); assertFalse(restored.loaded());
        restored.load(); return restored;
    }
    @Test void childReplyReturnsToOriginalCallerWithFrozenComputedRecordNotLaterRam() {
        var caller = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        var worker = new ComputerRuntime(UUID.randomUUID(), worker(), HARDWARE);
        caller.begin(input("query", 7)); finish(caller);
        var original = caller.outbound().orElseThrow();
        assertEquals(AdmissionStatus.ACCEPTED, worker.receive(request(caller.id(), original, "cluster-return")));
        worker.step(); finish(worker);
        var child = worker.outbound().orElseThrow(); acknowledge(worker);
        assertEquals(input("number", 8), child.payload());
        worker.begin(input("manual", 99)); finish(worker);
        assertEquals(new DataValue.IntegerValue(99), worker.memory().field("result"));
        var reply = new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("answer", 42),
            new MessageProtocol.Reply(((OutboundProtocol.Request)child.protocol()).correlation()));
        assertEquals(AdmissionStatus.ACCEPTED, worker.receive(reply)); worker.step(); finish(worker);
        var answer = worker.outbound().orElseThrow();
        assertEquals("cluster-return", answer.endpoint());
        assertEquals(((OutboundProtocol.Request)original.protocol()).correlation(),
            ((OutboundProtocol.Reply)answer.protocol()).correlation());
        assertEquals(input("answer", 8), answer.payload());
        assertEquals(new DataValue.IntegerValue(99), worker.memory().field("result"));
        assertTrue(worker.snapshot().rpc().pending().isEmpty());
    }

    static ProgramArchive worker() {
        var root = new ComputationGraph(Map.of("manual", DataType.Scalar.INTEGER),
            Map.of("input", new ComputationGraph.Input("manual")), Map.of("value", "input"));
        var graph = new ComputationGraph(Map.of("number", DataType.Scalar.INTEGER), Map.of(
            "input", new ComputationGraph.Input("number"),
            "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "one"),
            "payload", new ComputationGraph.MakeRecord(Map.of("number", "sum")),
            "capture", new ComputationGraph.MakeRecord(Map.of("answer", "sum")),
            "target", new ComputationGraph.Constant(new DataValue.TextValue("library"))),
            Map.of("target", "target", "payload", "payload", "saved", "capture"));
        var callback = new ComputationGraph(Map.of("answer", DataType.Scalar.INTEGER, "saved",
            new DataType.RecordType(Map.of("answer", DataType.Scalar.INTEGER))),
            Map.of("saved", new ComputationGraph.Input("saved")), Map.of("payload", "saved"));
        return new ProgramArchive(new ProgramDefinition(root,
            Map.of("result", new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0))),
            List.of(new ProgramDefinition.Write("result", "value")), List.of(), Map.of(
                "request", new ProgramDefinition.Entry(graph, List.of(), List.of(
                    ProgramDefinition.Send.continueRequest("target", "payload", "storage", "stored", "saved", "saved"))),
                "stored", new ProgramDefinition.Entry(callback, List.of(), List.of(ProgramDefinition.Send.reply("payload"))))), Map.of());
    }
}
