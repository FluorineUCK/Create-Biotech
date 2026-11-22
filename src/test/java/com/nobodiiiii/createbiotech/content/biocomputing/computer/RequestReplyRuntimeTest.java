package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestReplyRuntimeTest {
    @Test void runningReplyContextRestoresButCannotLoseItsPendingGeneration() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); runtime.begin(input("query", 1)); finish(runtime);
        var correlation = ((OutboundProtocol.Request)runtime.outbound().orElseThrow().protocol()).correlation(); acknowledge(runtime);
        runtime.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("answer", 12), new MessageProtocol.Reply(correlation)));
        runtime.step(); runtime.setPaused(true); var saved = runtime.snapshot();
        var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(saved, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE), HARDWARE);
        assertEquals(saved, restored.snapshot()); restored.load(); finish(restored);
        assertEquals(new DataValue.IntegerValue(12), restored.memory().field("result")); assertTrue(restored.snapshot().rpc().pending().isEmpty());
        var withoutPending = new RpcState(Map.of(), Map.of(), Optional.empty(), saved.rpc().reply());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(withRpc(saved, withoutPending), HARDWARE)).code());
    }
    @Test void immediateInstallationCompilesAgainstClearedRpcCapacity() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); runtime.begin(input("query", 1)); finish(runtime); acknowledge(runtime);
        var saved = runtime.snapshot();
        long rpcUnits = saved.rpc().storageUnits(); long capacity = saved.machine().arenaUnits() + rpcUnits;
        var limited = ComputerRuntime.restore(saved, new ResourceLimits(1000, capacity, 1)); limited.load();
        var graph = new ComputationGraph(Map.of(), Map.of("text", new ComputationGraph.Constant(new DataValue.TextValue("x".repeat(Math.toIntExact(rpcUnits))))), Map.of("text", "text"));
        var replacement = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        assertEquals(ComputerRuntime.InstallResult.APPLIED, limited.requestInstall(replacement, ComputerRuntime.MemoryPolicy.RESET));
        assertEquals(RpcState.empty(), limited.snapshot().rpc());
    }
    @Test void replyRoutingIntoReplyEffectHandlerIsUnsupportedWithoutRequestContext() {
        var base = client().program(); var entries = new LinkedHashMap<>(base.entries());
        entries.put("request", new ProgramDefinition.Entry(base.entries().get("result").graph(), List.of(), List.of(ProgramDefinition.Send.reply("payload"))));
        var archive = new ProgramArchive(new ProgramDefinition(base.graph(), base.cells(), base.writes(), List.of(ProgramDefinition.Send.request("target", "payload", "lookup", "request")), entries), Map.of());
        var runtime = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE); runtime.begin(input("query", 1)); finish(runtime);
        var correlation = ((OutboundProtocol.Request)runtime.outbound().orElseThrow().protocol()).correlation();
        var before = runtime.snapshot();
        assertEquals(AdmissionStatus.UNSUPPORTED, runtime.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("answer", 1), new MessageProtocol.Reply(correlation))));
        assertEquals(before, runtime.snapshot());
    }
    @Test void newerRequestCommitInvalidatesAlreadyQueuedOldReply() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); runtime.begin(input("query", 1)); finish(runtime);
        var old = ((OutboundProtocol.Request)runtime.outbound().orElseThrow().protocol()).correlation(); acknowledge(runtime);
        runtime.begin(input("query", 2));
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("answer", 99), new MessageProtocol.Reply(old))));
        finish(runtime); var current = ((OutboundProtocol.Request)runtime.outbound().orElseThrow().protocol()).correlation(); acknowledge(runtime);
        runtime.step(); assertTrue(runtime.snapshot().mailbox().queued().isEmpty());
        assertEquals(current, runtime.snapshot().rpc().pending().get("lookup").correlation());
        assertEquals(new DataValue.IntegerValue(0), runtime.memory().field("result"));
    }
    @Test void executionFailureKeepsEarlierWaitAndDropsNewStage() {
        var base = client().program(); var nodes = new LinkedHashMap<>(base.graph().nodes());
        nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
        nodes.put("overflow", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "input", "one"));
        var outputs = new LinkedHashMap<>(base.graph().outputs()); outputs.put("value", "overflow");
        var graph = new ComputationGraph(base.graph().inputs(), nodes, outputs);
        var archive = new ProgramArchive(new ProgramDefinition(graph, base.cells(), base.writes(), base.sends(), base.entries()), Map.of());
        var runtime = new ComputerRuntime(UUID.randomUUID(), archive, HARDWARE); runtime.begin(input("query", 1)); finish(runtime); acknowledge(runtime);
        var pending = runtime.snapshot().rpc().pending(); runtime.begin(input("query", Long.MAX_VALUE));
        for (int i = 0; i < 100 && runtime.report().status() == ComputationMachine.Status.RUNNING; i++) runtime.step();
        assertEquals(ComputationMachine.Status.FAILED, runtime.report().status()); assertEquals(pending, runtime.snapshot().rpc().pending());
        assertTrue(runtime.snapshot().rpc().staged().isEmpty()); assertTrue(runtime.outbound().isEmpty());
        assertEquals(runtime.snapshot(), ComputerRuntime.restore(runtime.snapshot(), HARDWARE).snapshot());
    }
    @Test void requestContextIsDurableWhilePausedInsideHandlerAndOutbox() {
        var client = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); client.begin(input("query", 9)); finish(client);
        var server = new ComputerRuntime(UUID.randomUUID(), server(), HARDWARE);
        server.receive(request(client.id(), client.outbound().orElseThrow(), "literal-return")); server.step(); server.setPaused(true);
        var running = server.snapshot(); var resumed = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(running, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE), HARDWARE);
        assertEquals(running, resumed.snapshot()); resumed.load(); finish(resumed);
        var done = ComputerRuntime.restore(resumed.snapshot(), HARDWARE);
        assertEquals("literal-return", done.outbound().orElseThrow().endpoint()); assertEquals(resumed.snapshot(), done.snapshot());
        var wrong = new ComputerRuntime.Snapshot(running.id(), running.program(), running.machine(), running.pending(), running.installationDiagnostic(), running.periodic(), running.delivery(), running.mailbox(), running.nextSequence(), running.activeEntry(), RpcState.empty());
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(wrong, HARDWARE)).code());
    }
    @Test void restoreRejectsForeignOwnerAndUnknownHandlerAndMissingCommittedRequest() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); runtime.begin(input("query", 1)); finish(runtime); var saved = runtime.snapshot();
        for (var pending : List.of(new RpcState.Pending(new RequestCorrelation(UUID.randomUUID(), saved.delivery().orElseThrow().eventId(), 0), "result"),
            new RpcState.Pending(new RequestCorrelation(runtime.id(), saved.delivery().orElseThrow().eventId(), 0), "unknown"))) {
            var forged = withRpc(saved, new RpcState(Map.of("lookup", pending), Map.of(), Optional.empty(), Optional.empty()));
            assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(forged, HARDWARE)).code());
        }
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(withRpc(saved, RpcState.empty()), HARDWARE)).code());
    }
    private static ComputerRuntime.Snapshot withRpc(ComputerRuntime.Snapshot saved, RpcState rpc) {
        return new ComputerRuntime.Snapshot(saved.id(), saved.program(), saved.machine(), saved.pending(), saved.installationDiagnostic(), saved.periodic(), saved.delivery(), saved.mailbox(), saved.nextSequence(), saved.activeEntry(), rpc);
    }
    @Test void requestMetadataCapacityFailureDoesNotPublishMachineOrSequence() {
        var archive = client(); var usage = archive.compile(HARDWARE).usage();
        long minimal = usage.constantUnits() + usage.initialMemoryUnits() * 2 + input("query", 1).storageUnits();
        var runtime = new ComputerRuntime(UUID.randomUUID(), archive, new ResourceLimits(1000, minimal, 1));
        var before = runtime.snapshot();
        assertEquals(ComputationException.Code.MEMORY_LIMIT, assertThrows(ComputationException.class, () -> runtime.begin(input("query", 1))).code());
        assertEquals(before, runtime.snapshot());
    }
    @Test void invalidBeginDiscardsStagedWaitsAndRestoreRejectsImpossibleActiveMetadata() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        runtime.begin(input("wrong", 1));
        assertEquals(ComputationMachine.Status.FAILED, runtime.report().status());
        assertTrue(runtime.snapshot().rpc().staged().isEmpty()); assertTrue(runtime.snapshot().rpc().pending().isEmpty()); assertTrue(runtime.snapshot().delivery().isEmpty());
        runtime.reset(); var saved = runtime.snapshot();
        var request = new RequestContext(new RequestCorrelation(UUID.randomUUID(), UUID.randomUUID(), 0), "peer");
        var forged = new ComputerRuntime.Snapshot(saved.id(), saved.program(), saved.machine(), saved.pending(), saved.installationDiagnostic(), saved.periodic(), saved.delivery(), saved.mailbox(), saved.nextSequence(), saved.activeEntry(), new RpcState(Map.of(), Map.of(), Optional.of(request), Optional.empty()));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(forged, HARDWARE)).code());
        runtime.begin(input("query", 1)); var running = runtime.snapshot();
        var bad = new RpcState(Map.of(), Map.of("lookup", new RpcState.Pending(new RequestCorrelation(runtime.id(), UUID.randomUUID(), 0), "result")), Optional.empty(), Optional.empty());
        var forgedStage = new ComputerRuntime.Snapshot(running.id(), running.program(), running.machine(), running.pending(), running.installationDiagnostic(), running.periodic(), running.delivery(), running.mailbox(), running.nextSequence(), running.activeEntry(), bad);
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT, assertThrows(ComputationException.class, () -> ComputerRuntime.restore(forgedStage, HARDWARE)).code());
    }
    @Test void requestReplySelectsNominatedHandlerWithoutSharingRamAndCommitsOnlyOnSuccess() {
        var client = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        var server = new ComputerRuntime(UUID.randomUUID(), server(), HARDWARE);
        client.begin(input("query", 7));
        assertTrue(client.snapshot().rpc().pending().isEmpty()); assertEquals(1, client.snapshot().rpc().staged().size());
        finish(client); var request = client.outbound().orElseThrow();
        assertEquals(1, client.snapshot().rpc().pending().size()); assertTrue(client.snapshot().rpc().staged().isEmpty());
        var correlation = ((OutboundProtocol.Request)request.protocol()).correlation();
        assertEquals(new RequestCorrelation(client.id(), request.eventId(), 0), correlation);
        assertEquals(AdmissionStatus.ACCEPTED, server.receive(request(client.id(), request, "frozen-client")));
        acknowledge(client); server.step(); finish(server);
        assertEquals(new DataValue.IntegerValue(7), server.memory().field("result"));
        assertEquals(new DataValue.IntegerValue(0), client.memory().field("result"));
        var response = server.outbound().orElseThrow(); assertEquals("frozen-client", response.endpoint());
        assertEquals(correlation, ((OutboundProtocol.Reply)response.protocol()).correlation());
        var envelope = reply(server.id(), response);
        assertEquals(AdmissionStatus.ACCEPTED, client.receive(envelope)); assertEquals(AdmissionStatus.DUPLICATE, client.receive(envelope));
        client.step(); finish(client);
        assertEquals(new DataValue.IntegerValue(7), client.memory().field("result")); assertTrue(client.snapshot().rpc().pending().isEmpty());
        var saved = client.snapshot(); client.step(); assertEquals(saved, client.snapshot());
        assertEquals(AdmissionStatus.STALE_REPLY, client.receive(envelope)); assertEquals(saved, client.snapshot());
    }
    @Test void staleReplyAndWrongRequesterAreInertAndRequestNeverFallsBackToRoot() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        var correlation = new RequestCorrelation(runtime.id(), UUID.randomUUID(), 0); var before = runtime.snapshot();
        assertEquals(AdmissionStatus.STALE_REPLY, runtime.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("untrusted", 3), new MessageProtocol.Reply(correlation))));
        assertEquals(before, runtime.snapshot());
        assertEquals(AdmissionStatus.UNSUPPORTED, runtime.receive(new MessageEnvelope(UUID.randomUUID(), 0, UUID.randomUUID(), 0, input("query", 3), new MessageProtocol.Reply(new RequestCorrelation(UUID.randomUUID(), UUID.randomUUID(), 0)))));
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        assertEquals(AdmissionStatus.UNSUPPORTED, runtime.receive(new MessageEnvelope(source, 0, event, 0, input("query", 3), new MessageProtocol.Request(new RequestContext(new RequestCorrelation(source, event, 0), "peer")))));
        assertEquals(before, runtime.snapshot());
    }
    @Test void runningAndOutboxRoundTripPreservesMetadataPauseAndUnloadAndClearsOnResetInstall() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); runtime.begin(input("query", 8)); runtime.setPaused(true);
        var saved = runtime.snapshot();
        var decoded = ComputerNbtCodec.decode(ComputerNbtCodec.encode(saved, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE); assertEquals(saved, decoded);
        var restored = ComputerRuntime.restore(decoded, HARDWARE); restored.tick(); restored.step(); assertEquals(saved, restored.snapshot());
        restored.load(); finish(restored); var outbox = restored.snapshot();
        var restoredOutbox = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(outbox, EventEntryFixtures.ARCHIVE), EventEntryFixtures.ARCHIVE), HARDWARE);
        assertEquals(restored.outbound(), restoredOutbox.outbound());
        restored.reset(); assertEquals(RpcState.empty(), restored.snapshot().rpc());
        restoredOutbox.load(); acknowledge(restoredOutbox); restoredOutbox.requestInstall(client(), ComputerRuntime.MemoryPolicy.PRESERVE);
        assertEquals(RpcState.empty(), restoredOutbox.snapshot().rpc());
    }
    @Test void queuedReplyBecomesStaleWhenEarlierReplyConsumesPending() {
        var runtime = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE); runtime.begin(input("query", 4)); finish(runtime);
        var correlation = ((OutboundProtocol.Request)runtime.outbound().orElseThrow().protocol()).correlation(); acknowledge(runtime);
        var source = UUID.randomUUID(); var event = UUID.randomUUID();
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(new MessageEnvelope(source, 0, event, 0, input("answer", 4), new MessageProtocol.Reply(correlation))));
        assertEquals(AdmissionStatus.ACCEPTED, runtime.receive(new MessageEnvelope(source, 1, event, 1, input("answer", 99), new MessageProtocol.Reply(correlation))));
        runtime.step(); finish(runtime);
        var restored = ComputerRuntime.restore(runtime.snapshot(), HARDWARE); restored.load(); restored.step();
        assertTrue(restored.snapshot().mailbox().queued().isEmpty()); assertEquals(new DataValue.IntegerValue(4), restored.memory().field("result"));
        assertEquals(1, restored.snapshot().mailbox().receipts().size());
    }
}
