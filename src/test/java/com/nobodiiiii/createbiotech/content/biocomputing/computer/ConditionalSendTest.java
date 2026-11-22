package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ConditionalSendTest {
    @Test void selectedReturnDirectionIsExclusiveAndBothTrueRollBackRam() {
        for (boolean continueRequest : List.of(false, true)) {
            var runtime = requested(conditionalWorker(!continueRequest, continueRequest));
            finish(runtime);
            assertEquals(new DataValue.IntegerValue(8), runtime.memory().field("result"));
            var message = runtime.outbound().orElseThrow();
            assertEquals(continueRequest ? 1 : 0, message.ordinal());
            assertEquals(continueRequest ? "library" : "caller", message.endpoint());
            assertEquals(continueRequest, message.protocol() instanceof OutboundProtocol.Request);
            assertEquals(continueRequest ? Set.of("storage") : Set.of(), runtime.snapshot().rpc().pending().keySet());
        }
        var conflicting = requested(conditionalWorker(true, true));
        for (int i = 0; i < 100 && conflicting.report().status() == ComputationMachine.Status.RUNNING; i++) conflicting.step();
        assertEquals(ComputationMachine.Status.FAILED, conflicting.report().status());
        assertEquals(ComputationException.Code.WRITE_CONFLICT, conflicting.report().diagnostic().orElseThrow().code());
        assertEquals(new DataValue.IntegerValue(0), conflicting.memory().field("result"));
        assertTrue(conflicting.outbound().isEmpty());
        assertTrue(conflicting.snapshot().rpc().pending().isEmpty());
        assertTrue(conflicting.snapshot().rpc().staged().isEmpty());
    }
    @Test void everyStepAndSkippedAcknowledgementSurviveDiskButImpossibleCursorIsRejected() {
        var runtime = requested(conditionalWorker(false, true));
        for (int i = 0; i < 100; i++) {
            runtime.step(); runtime = roundTrip(runtime);
            if (runtime.report().status() == ComputationMachine.Status.SUCCEEDED) break;
            assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
        }
        var saved = runtime.snapshot(); var delivery = saved.delivery().orElseThrow();
        assertEquals(1, runtime.outbound().orElseThrow().ordinal());
        assertFalse(runtime.acknowledge(delivery.eventId(), 0));
        var wrong = new ComputerRuntime.Snapshot(saved.id(), saved.program(), saved.machine(), saved.pending(),
            saved.installationDiagnostic(), saved.periodic(), Optional.of(new ComputerRuntime.DeliveryState(delivery.eventId(), 0, delivery.sequenceBase())),
            saved.mailbox(), saved.nextSequence(), saved.activeEntry(), saved.rpc());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> ComputerRuntime.restore(wrong, HARDWARE)).code());
        acknowledge(runtime); runtime = roundTrip(runtime); assertTrue(runtime.outbound().isEmpty());
    }
    @Test void savedSuccessCannotEnableASecondOriginalReturnDirection() {
        var runtime = requested(conditionalWorker(false, true)); finish(runtime);
        var saved = runtime.snapshot(); var machine = saved.machine();
        var fields = new LinkedHashMap<>(machine.output().orElseThrow().fields()); fields.put("reply", new DataValue.BooleanValue(true));
        var forged = new MachineCheckpoint(machine.memory(), machine.status(), machine.paused(), machine.workUnits(), machine.arenaUnits(),
            Optional.of(new DataValue.RecordValue(fields)), machine.diagnostic(), machine.frames());
        var wrong = new ComputerRuntime.Snapshot(saved.id(), saved.program(), forged, saved.pending(), saved.installationDiagnostic(),
            saved.periodic(), saved.delivery(), saved.mailbox(), saved.nextSequence(), saved.activeEntry(), saved.rpc());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> ComputerRuntime.restore(wrong, HARDWARE)).code());
    }
    private static ComputerRuntime requested(ProgramArchive archive) {
        var caller = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); caller.begin(input("query", 7)); finish(caller);
        var worker = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE);
        assertEquals(AdmissionStatus.ACCEPTED, worker.receive(request(caller.id(), caller.outbound().orElseThrow(), "caller")));
        worker.step(); return worker;
    }
    private static ProgramArchive conditionalWorker(boolean reply, boolean continueRequest) {
        var archive = RequestContinuationTest.worker(); var program = archive.program(); var entries = new LinkedHashMap<>(program.entries());
        var request = entries.get("request"); var graph = request.graph(); var nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put("reply", new ComputationGraph.Constant(new DataValue.BooleanValue(reply)));
        nodes.put("continue", new ComputationGraph.Constant(new DataValue.BooleanValue(continueRequest)));
        var outputs = new LinkedHashMap<>(graph.outputs()); outputs.put("reply", "reply"); outputs.put("continue", "continue"); outputs.put("value", "sum");
        entries.put("request", new ProgramDefinition.Entry(new ComputationGraph(graph.inputs(), nodes, outputs),
            List.of(new ProgramDefinition.Write("result", "value")),
            List.of(ProgramDefinition.Send.reply("saved").when("reply"), request.sends().getFirst().when("continue"))));
        return new ProgramArchive(new ProgramDefinition(program.graph(), program.cells(), program.writes(), program.sends(), entries), archive.modules());
    }
    private static ComputerRuntime roundTrip(ComputerRuntime runtime) {
        var saved = runtime.snapshot();
        var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(saved, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE);
        var restored = ComputerRuntime.restore(decoded, HARDWARE); assertEquals(saved, restored.snapshot());
        restored.load(); return restored;
    }
    @Test void falseRequestCommitsOwnRamButDoesNotSendOrReplacePreviousWait() {
        var base = client(); var original = base.program();
        var inputs = new LinkedHashMap<>(original.graph().inputs()); inputs.put("send", DataType.Scalar.BOOLEAN);
        var nodes = new LinkedHashMap<>(original.graph().nodes()); nodes.put("send", new ComputationGraph.Input("send"));
        var outputs = new LinkedHashMap<>(original.graph().outputs()); outputs.put("send", "send");
        var graph = new ComputationGraph(inputs, nodes, outputs);
        var archive = new ProgramArchive(new ProgramDefinition(graph, original.cells(),
            List.of(new ProgramDefinition.Write("result", "value")),
            List.of(original.sends().getFirst().when("send")), original.entries()), base.modules());
        var runtime = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE);
        runtime.begin(inputs(7, true)); finish(runtime); acknowledge(runtime);
        var waiting = runtime.snapshot().rpc().pending();
        runtime.begin(inputs(99, false)); finish(runtime);
        assertEquals(new DataValue.IntegerValue(99), runtime.memory().field("result"));
        assertTrue(runtime.outbound().isEmpty());
        assertEquals(waiting, runtime.snapshot().rpc().pending());
        assertTrue(runtime.snapshot().rpc().staged().isEmpty());
        var restored = roundTrip(runtime);
        assertTrue(restored.outbound().isEmpty()); assertEquals(waiting, restored.snapshot().rpc().pending());
    }
    @Test void skippedMessageOrdinalsRemainStableAndAcknowledgementsCannotSkipSelectedSend() {
        var base = client().program();
        var nodes = new LinkedHashMap<>(base.graph().nodes()); nodes.put("off", new ComputationGraph.Constant(new DataValue.BooleanValue(false)));
        var outputs = new LinkedHashMap<>(base.graph().outputs()); outputs.put("off", "off");
        var graph = new ComputationGraph(base.graph().inputs(), nodes, outputs);
        var send = new ProgramDefinition.Send("target", "payload");
        var runtime = new ComputerRuntime(UUID.randomUUID(), new ProgramArchive(
            new ProgramDefinition(graph, Map.of(), List.of(), List.of(send.when("off"), send, send.when("off"))), Map.of()), HARDWARE);
        runtime.begin(input("query", 7)); finish(runtime);
        var saved = runtime.snapshot(); var message = runtime.outbound().orElseThrow();
        assertEquals(1, message.ordinal()); assertEquals(1, message.sequence()); assertEquals(3, saved.nextSequence());
        assertFalse(runtime.acknowledge(message.eventId(), 0)); assertFalse(runtime.acknowledge(message.eventId(), 2));
        runtime = roundTrip(runtime); assertEquals(Optional.of(message), runtime.outbound());
        assertTrue(runtime.acknowledge(message.eventId(), 1));
        assertEquals(3, runtime.snapshot().delivery().orElseThrow().acknowledged());
        var done = roundTrip(runtime); done.step(); assertEquals(done.snapshot(), roundTrip(done).snapshot());
        assertTrue(done.outbound().isEmpty());
    }
    @Test void conditionMustReferenceBooleanOutputEvenWhenPayloadWouldBeSkipped() {
        var archive = client(); var base = archive.program();
        for (String name : List.of("missing", "target")) {
            var invalid = new ProgramArchive(new ProgramDefinition(base.graph(), base.cells(), base.writes(),
                List.of(base.sends().getFirst().when(name)), base.entries()), archive.modules());
            assertEquals(name.equals("missing") ? ComputationException.Code.MISSING_REFERENCE : ComputationException.Code.TYPE_MISMATCH,
                assertThrows(ComputationException.class, () -> invalid.compile(HARDWARE)).code());
        }
    }
    private static DataValue.RecordValue inputs(long query, boolean send) {
        return new DataValue.RecordValue(Map.of("query", new DataValue.IntegerValue(query), "send", new DataValue.BooleanValue(send)));
    }
}
