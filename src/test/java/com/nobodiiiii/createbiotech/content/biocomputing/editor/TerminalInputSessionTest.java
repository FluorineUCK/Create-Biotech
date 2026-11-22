package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Exercises the published-view fence through the real ComputerBehaviour. */
class TerminalInputSessionTest {
    @BeforeAll static void bootstrap() throws Exception { TerminalBehaviourTest.bootstrap(); }

    @Test void acceptedSubmitRunsTheNamedTextEntryWithoutCallingTheRootGraph() {
        var block = new TerminalBehaviourTest.Harness();
        var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(true), ComputerRuntime.MemoryPolicy.RESET);
        long revision = computer.programRevision(); var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);

        assertEquals("ok", session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL,
            () -> { throw new AssertionError("terminal read must not decode a payload"); }, computer).orElseThrow().code());
        var result = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_SUBMIT,
            () -> new TerminalSubmit(1, "4:root/5:input", "new").encode(), computer).orElseThrow();
        assertEquals("ok", result.code());
        for (int i = 0; i < 20 && computer.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) block.tick();
        assertEquals(text("new"), computer.snapshot().orElseThrow().machine().memory().field("query"));

        var status = result.dedicatedStatus().orElseThrow();
        assertEquals(Set.of("terminal_submit"), status.fields().keySet(), "submit replies remain dedicated rather than a READ envelope");
    }

    @Test void wrongControlDisabledInputAndReplayDoNotChangeTheExistingRuntime() {
        var block = new TerminalBehaviourTest.Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        computer.installDocument(document(false), ComputerRuntime.MemoryPolicy.RESET);
        long revision = computer.programRevision(); var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var before = computer.snapshot();
        assertEquals("ok", session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> { throw new AssertionError(); }, computer).orElseThrow().code());
        assertEquals("invalid", session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(1, "4:root/5:input").encode(), computer).orElseThrow().code(), "an Input cannot be clicked as a Button");
        assertEquals("ok", session.apply(nonce, 3, revision, ComputerControlSession.Action.READ_TERMINAL, () -> { throw new AssertionError(); }, computer).orElseThrow().code());
        assertEquals("invalid", session.apply(nonce, 4, revision, ComputerControlSession.Action.TERMINAL_SUBMIT,
            () -> new TerminalSubmit(3, "4:root/5:input", "new").encode(), computer).orElseThrow().code(), "disabled controls have no action authority");
        assertEquals(before, computer.snapshot());
        assertTrue(session.apply(nonce, 4, revision, ComputerControlSession.Action.TERMINAL_SUBMIT,
            () -> new TerminalSubmit(3, "4:root/5:input", "new").encode(), computer).isEmpty(), "replay has no authority");
    }

    @Test void wrongNonceRevisionViewForgedFieldsAndOversizeCannotReplaceRuntime() {
        for (String rejection : List.of("nonce", "revision", "view", "forged", "oversize", "button", "busy", "paused")) {
            var block = new TerminalBehaviourTest.Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
            var installed = document(true);
            if (rejection.equals("button")) {
                var nodes = new LinkedHashMap<>(installed.terminal().orElseThrow().nodes());
                nodes.put("input", new TerminalDefinition.Button(new TerminalBinding.Literal(text("Button")), new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "submit",
                    new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("query", text("old"), "category", text("all"))))));
                installed = new ProgramDocument(installed.archive(), Map.of(), Optional.of(new TerminalDefinition("root", nodes)));
            }
            computer.installDocument(installed, ComputerRuntime.MemoryPolicy.RESET);
            if (rejection.equals("busy")) computer.begin(new DataValue.RecordValue(Map.of()));
            if (rejection.equals("paused")) computer.setPaused(true);
            var before = computer.snapshot(); long revision = computer.programRevision();
            var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
            session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> { throw new AssertionError(); }, computer).orElseThrow();
            var payload = new LinkedHashMap<>(new TerminalSubmit(1, "4:root/5:input", "new").encode().fields());
            if (rejection.equals("forged")) payload.put("entry", text("forged"));
            if (rejection.equals("view")) payload.put("view", new DataValue.IntegerValue(99));
            if (rejection.equals("oversize")) payload.put("text", text("界".repeat(30000)));
            var result = session.apply(rejection.equals("nonce") ? UUID.randomUUID() : nonce, 2, rejection.equals("revision") ? revision - 1 : revision,
                ComputerControlSession.Action.TERMINAL_SUBMIT, () -> new DataValue.RecordValue(payload), computer);
            if (rejection.equals("nonce")) assertTrue(result.isEmpty());
            else {
                assertNotEquals("ok", result.orElseThrow().code(), rejection);
                assertEquals(Set.of("terminal_submit"), result.orElseThrow().dedicatedStatus().orElseThrow().fields().keySet());
            }
            assertEquals(before, computer.snapshot(), rejection + " changed runtime");
            assertEquals(revision, computer.programRevision());
            if (!rejection.equals("nonce")) {
                assertTrue(session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_SUBMIT, () -> { throw new AssertionError("replay decoded"); }, computer).isEmpty());
                assertEquals("invalid", session.apply(nonce, 3, revision, ComputerControlSession.Action.TERMINAL_SUBMIT,
                    () -> new TerminalSubmit(1, "4:root/5:input", "new").encode(), computer).orElseThrow().code(), "rejected submission consumes publication");
                assertEquals(before, computer.snapshot());
            }
        }
    }

    private static ProgramDocument document(boolean enabled) {
        var root = new ComputationGraph(Map.of(), Map.of("constant", new ComputationGraph.Constant(text("root"))), Map.of("out", "constant"));
        var entry = new ComputationGraph(Map.of("query", DataType.Scalar.TEXT, "category", DataType.Scalar.TEXT), Map.of("input", new ComputationGraph.Input("query")), Map.of("text", "input"));
        var archive = new ProgramArchive(new ProgramDefinition(root, Map.of("query", new ProgramDefinition.Cell(DataType.Scalar.TEXT, text("old"))), List.of(), List.of(),
            Map.of("submit", new ProgramDefinition.Entry(entry, List.of(new ProgramDefinition.Write("query", "text")), List.of()))), Map.of());
        var input = new TerminalDefinition.Input(new TerminalBinding.Literal(text("old")), new TerminalBinding.Literal(new DataValue.BooleanValue(enabled)),
            "submit", "query", new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("query", text("old"), "category", text("all")))));
        return new ProgramDocument(archive, Map.of(), Optional.of(new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Column(List.of("input")), "input", input))));
    }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
