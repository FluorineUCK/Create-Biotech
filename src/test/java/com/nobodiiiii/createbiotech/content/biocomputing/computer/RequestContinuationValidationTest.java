package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.RequestReplyFixtures.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestContinuationValidationTest {
    @Test void compilerRejectsForkedReplyMissingRecordAndUnreachableContinuation() {
        var good = RequestContinuationTest.worker();
        var request = good.program().entries().get("request");
        var continuation = request.sends().getFirst();
        assertCompileCode(ComputationException.Code.INVALID_GRAPH, withRequestSends(good, List.of(continuation, ProgramDefinition.Send.reply("payload"))));
        assertCompileCode(ComputationException.Code.INVALID_GRAPH, withRequestSends(good, List.of(continuation,
            ProgramDefinition.Send.continueRequest("target", "payload", "second", "stored", "saved", "saved"))));
        assertCompileCode(ComputationException.Code.TYPE_MISMATCH, withRequestSends(good,
            List.of(ProgramDefinition.Send.continueRequest("target", "payload", "storage", "stored", "target", "saved"))));
        assertCompileCode(ComputationException.Code.MISSING_REFERENCE, withRequestSends(good,
            List.of(ProgramDefinition.Send.continueRequest("target", "payload", "storage", "stored", "saved", "absent"))));
        var entries = new LinkedHashMap<>(good.program().entries()); entries.remove("request");
        entries.put("orphan", request);
        var definition = good.program();
        assertCompileCode(ComputationException.Code.INVALID_GRAPH, new ProgramArchive(new ProgramDefinition(
            definition.graph(), definition.cells(), definition.writes(), definition.sends(), entries), good.modules()));
    }
    @Test void strictSavedContinuationRejectsMissingCaptureWrongOriginAndChangedSuccessfulCopy() {
        var caller = new ComputerRuntime(UUID.randomUUID(), client(), HARDWARE);
        caller.begin(input("query", 7)); finish(caller);
        var worker = new ComputerRuntime(UUID.randomUUID(), RequestContinuationTest.worker(), HARDWARE);
        worker.receive(request(caller.id(), caller.outbound().orElseThrow(), "caller")); worker.step();
        var running = worker.snapshot();
        var staged = running.rpc().staged().get("storage");
        assertThrows(IllegalArgumentException.class, () -> new RpcState(Map.of("storage", staged), Map.of(), running.rpc().request(), Optional.empty()));
        var premature = new RpcState(Map.of(), Map.of("storage", staged.capture(input("answer", 8))), running.rpc().request(), Optional.empty());
        assertRestoreRejects(withRpc(running, premature));
        finish(worker); var complete = worker.snapshot();
        var waiting = complete.rpc().pending().get("storage");
        assertRestoreRejects(withRpc(complete, new RpcState(Map.of("storage", waiting.capture(input("answer", -1))),
            Map.of(), complete.rpc().request(), Optional.empty())));
        var foreign = new RequestContext(new RequestCorrelation(UUID.randomUUID(), UUID.randomUUID(), 0), "other");
        assertThrows(IllegalArgumentException.class, () -> new RpcState(complete.rpc().pending(), Map.of(),
            Optional.of(foreign), Optional.of(new ReplyContext("storage", waiting))));
        var unbound = new RpcState.Pending(waiting.correlation(), waiting.replyEntry(), Optional.of(
            new RequestContinuation(complete.rpc().request().orElseThrow(), "missing", Optional.of(input("answer", 8)))));
        assertRestoreRejects(withRpc(complete, new RpcState(Map.of("storage", unbound), Map.of(), complete.rpc().request(), Optional.empty())));
    }
    @Test void portableDocumentAndDraftEditsRetainExplicitBindingWithoutRuntimeState() {
        var document = ProgramDocument.of(RequestContinuationTest.worker());
        var wire = ProgramDocumentWire.encode(document, EventEntryFixtures.ARCHIVE);
        var decoded = ProgramDocumentWire.decode(wire, EventEntryFixtures.ARCHIVE);
        assertEquals(document, decoded);
        var workspace = new ProgramWorkspace(decoded);
        var beforeEdit = workspace.snapshot();
        workspace.entry("request").setSend(0, "target", "payload");
        assertEquals(beforeEdit, workspace.snapshot());
        assertEquals(document.archive(), workspace.snapshot().archive());
        assertEquals(document.archive(), ProgramArchiveCodec.decode(ProgramArchiveCodec.encode(document.archive())));
    }
    private static ProgramArchive withRequestSends(ProgramArchive original, List<ProgramDefinition.Send> sends) {
        var program = original.program(); var entries = new LinkedHashMap<>(program.entries());
        var request = entries.get("request"); entries.put("request", new ProgramDefinition.Entry(request.graph(), request.writes(), sends));
        return new ProgramArchive(new ProgramDefinition(program.graph(), program.cells(), program.writes(), program.sends(), entries), original.modules());
    }
    private static void assertCompileCode(ComputationException.Code code, ProgramArchive archive) {
        assertEquals(code, assertThrows(ComputationException.class, () -> archive.compile(HARDWARE)).code());
    }
    private static void assertRestoreRejects(ComputerRuntime.Snapshot saved) {
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,
            assertThrows(ComputationException.class, () -> ComputerRuntime.restore(saved, HARDWARE)).code());
    }
    private static ComputerRuntime.Snapshot withRpc(ComputerRuntime.Snapshot saved, RpcState rpc) {
        return new ComputerRuntime.Snapshot(saved.id(), saved.program(), saved.machine(), saved.pending(),
            saved.installationDiagnostic(), saved.periodic(), saved.delivery(), saved.mailbox(), saved.nextSequence(), saved.activeEntry(), rpc);
    }
}
