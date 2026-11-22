package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.get;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.AdmissionStatus;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

/** Actual event controls -> ordinary editable nodes -> document wire -> durable production host. */
class EventEntryEditorHostTest {
    private final ComputerProgramScreenTest widgets = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { widgets.client(); }
    @AfterEach void restoreClient() throws Exception { widgets.restore(); }

    @Test void playerCreatedEntriesSurviveWireAndReopenThenRouteManualMessageAndTimer() throws Exception {
        var screen = ComputerProgramScreenTest.screen();
        var workspace = (ProgramWorkspace)get(screen, "workspace");
        var root = workspace.root(); root.putCell("n", INTEGER, integer(0));
        inputWriter(root, "manual");
        var message = createEntry(screen, "message"); inputWriter(message, "amount");
        var timer = createEntry(screen, "timer");
        String old = timer.add(new ComputationGraph.ReadMemory("n"), 10, 20);
        String one = timer.add(new ComputationGraph.Constant(integer(1)), 10, 80);
        String sum = timer.add(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "left", "right"), 180, 50);
        timer.connect(sum, "left", old); timer.connect(sum, "right", one);
        timer.output("out", sum); timer.setWrite("n", "out");
        assertFalse((boolean)get(screen, "requested"));

        var document = workspace.snapshot();
        var received = ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, ARCHIVE), ARCHIVE);
        assertEquals(document, received);
        var reopened = new ProgramWorkspace(received); assertEquals(document, reopened.snapshot());
        assertEquals(new ProgramDocument.Position(180, 50), received.positions().get(ProgramDocument.entryGraphId("timer")).get(sum));
        var host = new ComputerBlockHost(() -> {});
        assertEquals(ComputerRuntime.InstallResult.APPLIED, host.install(reopened.snapshot().archive(), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true));
        host.begin(input("manual", 1), LIMITS, true); finish(host); assertMemory(host, 1);
        assertEquals(AdmissionStatus.ACCEPTED, host.receive(message(7), LIMITS, true));
        assertMemory(host, 1); // Durable admission is not remote execution.
        host.step(LIMITS, true); finish(host); assertMemory(host, 7);
        assertEquals(Optional.of("message"), host.snapshot().orElseThrow().activeEntry());
        host.configurePeriodic(1, new DataValue.RecordValue(Map.of()), LIMITS, true);
        host.tick(LIMITS, true); finish(host); assertMemory(host, 8);
        host.disablePeriodic(LIMITS, true);
        assertEquals(Optional.of("timer"), host.snapshot().orElseThrow().activeEntry());

        var loaded = new ComputerBlockHost(() -> {}); loaded.read(host.write()); loaded.observe(LIMITS, true);
        assertEquals(host.snapshot(), loaded.snapshot());
        loaded.begin(input("manual", 11), LIMITS, true); finish(loaded); assertMemory(loaded, 11);
        assertEquals(Optional.empty(), loaded.snapshot().orElseThrow().activeEntry());
    }

    private static ProgramDraft createEntry(ComputerProgramScreen screen, String name) throws Exception {
        click(screen, "entries"); ((EditBox)get(screen, "entryName")).setValue(name); click(screen, "create_entry");
        assertEquals(name, get(screen, "activeEntry")); return (ProgramDraft)get(screen, "draft");
    }
    private static void inputWriter(ProgramDraft draft, String input) {
        draft.putInput(input, INTEGER); String node = draft.add(new ComputationGraph.Input(input), 20, 30);
        draft.output("out", node); draft.setWrite("n", "out");
    }
    private static void click(ComputerProgramScreen screen, String key) {
        screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getContents() instanceof TranslatableContents text
                && text.getKey().equals("create_biotech.biocomputing.editor." + key))
            .findFirst().orElseThrow().onClick(0, 0);
    }
    private static void finish(ComputerBlockHost host) {
        for (int i = 0; i < 100 && host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++)
            host.step(LIMITS, true);
        assertEquals(ComputationMachine.Status.SUCCEEDED, host.snapshot().orElseThrow().machine().status());
    }
    private static void assertMemory(ComputerBlockHost host, long expected) {
        assertEquals(integer(expected), host.snapshot().orElseThrow().machine().memory().field("n"));
    }
}
