package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TaskDebugSessionTest {
    @BeforeAll static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }
    private static DataValue.RecordValue request(String first, long a, String second, long b) {
        return new DataValue.RecordValue(Map.of(first, new DataValue.IntegerValue(a), second, new DataValue.IntegerValue(b)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void localTaskInspectionIsReadOnlyAndBoundToTheAuthorizedListing(boolean software) {
        var computer = new FlowTerminalSessionTest.Harness().getBehaviour(ComputerBehaviour.TYPE);
        var document = FlowTerminalSessionTest.taskStartWithoutLayout();
        var body = document.flow().orElseThrow();
        body = new FlowDocumentBody(body.inputs(), body.steps(), FlowTerminalSessionTest.value(120),
            body.canvas(), body.inline(), body.execution(), body.compilation());
        document = new ProgramDocument(document.archive(), document.positions(), document.terminal(),
            Optional.of(software ? body.withSoftwareAny() : body));
        computer.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        for (int i = 0; i < 12; i++) computer.step();
        var before = computer.flowSnapshot().orElseThrow();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.valueOf("READ_TASK_DEBUG");
        long revision = computer.programRevision();
        var denied = session.apply(nonce, 1, revision, action, () -> { throw new AssertionError("Read before authorization"); }, computer,
            () -> { throw new IllegalArgumentException("access_denied"); }).orElseThrow();
        assertEquals("invalid", denied.code());
        assertTrue(denied.dedicatedStatus().orElseThrow().fields().isEmpty());
        var page = session.apply(nonce, 2, revision, action, () -> request("page", 0, "size", 5), computer).orElseThrow();
        var directory = (DataValue.RecordValue)page.dedicatedStatus().orElseThrow().field("debug_tasks");
        var rows = (DataValue.ListValue)directory.field("rows"); assertEquals(1, rows.values().size());
        var row = (DataValue.RecordValue)rows.values().getFirst();
        assertEquals(new DataValue.TextValue("wait_copy"), row.field("procedure"));
        assertFalse(row.fields().containsKey("locals")); assertFalse(row.fields().containsKey("handle"));
        var foreign = new ComputerControlSession(UUID.randomUUID());
        assertTrue(foreign.apply(nonce, 3, revision, action, () -> request("view", 2, "row", 0), computer).isEmpty());
        var reply = session.apply(nonce, 3, revision, action, () -> request("view", 2, "row", 0), computer).orElseThrow();
        var task = (DataValue.RecordValue)reply.dedicatedStatus().orElseThrow().field("debug_task");
        assertEquals(new DataValue.TextValue("SLEEPING"), task.field("phase"));
        assertEquals(new DataValue.IntegerValue(120), ((DataValue.RecordValue)task.field("locals")).field("value"));
        assertEquals(before, computer.flowSnapshot().orElseThrow(), "Inspecting must not claim a result or retire a task");
        session.apply(nonce, 4, revision, action, () -> request("page", Integer.MAX_VALUE, "size", 5), computer).orElseThrow();
        assertEquals("stale", session.apply(nonce, 5, revision, action, () -> request("view", 2, "row", 0), computer).orElseThrow().code());
        computer.reset();
        assertEquals("stale", session.apply(nonce, 6, computer.programRevision(), action,
            () -> request("view", 4, "row", 0), computer).orElseThrow().code());
    }

    @Test void unavailableHardwareDoesNotPublishPreviouslyListedPrivateState() {
        var harness = new FlowTerminalSessionTest.Harness();
        var computer = harness.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(FlowTerminalSessionTest.taskStartWithoutLayout(), ComputerRuntime.MemoryPolicy.RESET);
        for (int i = 0; i < 12; i++) computer.step();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.valueOf("READ_TASK_DEBUG"); long revision = computer.programRevision();
        assertEquals("ok", session.apply(nonce, 1, revision, action,
            () -> request("page", 0, "size", 5), computer).orElseThrow().code());
        harness.available = false;
        var reply = session.apply(nonce, 2, revision, action, () -> request("view", 1, "row", 0), computer).orElseThrow();
        assertEquals("unavailable", reply.code());
        assertTrue(reply.dedicatedStatus().orElseThrow().fields().isEmpty());
    }

    @Test void unrelatedActionsInvalidateTheTaskSelectionAndPageSizesAreBounded() {
        var computer = FlowTerminalSessionTest.completedTaskWithoutLayout();
        var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var action = ComputerControlSession.Action.valueOf("READ_TASK_DEBUG");
        long revision = computer.programRevision();
        assertEquals("invalid", session.apply(nonce, 1, revision, action,
            () -> request("page", 0, "size", 6), computer).orElseThrow().code());
        session.apply(nonce, 2, revision, action, () -> request("page", 0, "size", 5), computer).orElseThrow();
        session.apply(nonce, 3, revision, ComputerControlSession.Action.REFRESH,
            () -> FlowTerminalSessionTest.EMPTY, computer).orElseThrow();
        assertEquals("stale", session.apply(nonce, 4, revision, action,
            () -> request("view", 2, "row", 0), computer).orElseThrow().code());
        assertThrows(IllegalArgumentException.class,
            () -> new ProgramControlRequest(1, nonce, 5, revision, action, new byte[1024]));
    }
}
