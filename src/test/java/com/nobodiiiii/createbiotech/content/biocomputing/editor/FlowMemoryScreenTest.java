package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;

class FlowMemoryScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void legacyStructuredStorageRemainsAvailableOnlyThroughAdvancedControls(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, width);
        click(screen, "category_variables");
        assertTrue(FlowBlockCatalog.search(Map.of(), FlowBlockPalette.Category.VARIABLES, "").stream()
            .noneMatch(e -> e.id().startsWith("memory_")));
        FlowPaletteScreenTest.search(screen).setValue("memory_begin");
        assertTrue(((List<?>)get(screen, "catalogEntries")).isEmpty(), "Global search must not leak advanced controls by default");
        click(screen, "initials"); boxes(screen).get(0).setValue("value"); boxes(screen).get(1).setValue("42"); click(screen, "save_initial"); click(screen, "back");
        click(screen, "category_storage"); click(screen, "declare_cell");
        boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("0"); click(screen, "save_initial"); bounds(screen); click(screen, "back");
        click(screen, "advanced_off");
        click(screen, "add_atomic"); boxes(screen).getFirst().setValue("result"); bounds(screen); click(screen, "apply");
        assertInstanceOf(FlowProgram.Atomic.class, workspace.flow().orElseThrow().steps().getFirst());
        assertInstanceOf(FlowProgram.EndAtomic.class, workspace.flow().orElseThrow().steps().getLast());
        click(screen, "add_memory_write"); boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("value"); click(screen, "apply");
        click(screen, "add_memory_read"); boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("old"); click(screen, "apply");
        var document = workspace.snapshot(); assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1), new ArchiveLimits(100_000, 1_000_000, 1_000_000));
        var owner = new ComputerBlockHost(() -> {}); var body = document.flow().orElseThrow();
        owner.installFlow(body.program(document.archive()), body.initial(), limits, true);
        for (int i = 0; i < 6; i++) owner.step(limits, true);
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.IntegerValue(42), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        // The header edits its result, and removing it removes its entire body in one undo step.
        int x = (int)get(get(screen, "canvas"), "x") + 24, y = (int)get(get(screen, "canvas"), "y") + 50;
        screen.mouseClicked(x, y, 0); screen.mouseReleased(x, y, 0); click(screen, "edit");
        assertEquals("result", boxes(screen).getFirst().getValue()); click(screen, "apply");
        click(screen, "remove"); assertTrue(workspace.flow().orElseThrow().steps().isEmpty()); click(screen, "undo"); assertEquals(document, workspace.snapshot());
        click(screen, "category_storage");
        FlowPaletteScreenTest.search(screen).setValue("memory_begin"); assertNotNull(button(screen, "add_memory_begin"));
        bounds(screen); assertFalse((boolean)get(screen, "waiting")); assertEquals(document, workspace.snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void declarationsAndTransactionBlocksRemainEditableThenInstallThroughThePublicDocument(int width) throws Exception {
        var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var screen = FlowCallScreenTest.editor(workspace, width); click(screen, "initials");
        boxes(screen).get(0).setValue("value"); boxes(screen).get(1).setValue("42"); click(screen, "save_initial");
        click(screen, "initials"); // Explicitly switch this form from task inputs to shared cells.
        boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("0"); click(screen, "save_initial");
        assertEquals(new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(0)), workspace.root().cells().get("counter"));
        assertEquals(Set.of("value"), workspace.flow().orElseThrow().initial().fields().keySet()); bounds(screen); click(screen, "back");
        click(screen, "category_storage"); click(screen, "advanced_off");
        click(screen, "add_memory_begin"); bounds(screen); click(screen, "apply");
        click(screen, "add_memory_write"); boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("value"); bounds(screen); click(screen, "apply");
        click(screen, "edit"); assertEquals("counter", boxes(screen).get(0).getValue()); assertEquals("value", boxes(screen).get(1).getValue()); click(screen, "apply");
        click(screen, "add_memory_read"); boxes(screen).get(0).setValue("counter"); boxes(screen).get(1).setValue("old"); click(screen, "apply");
        click(screen, "add_memory_commit"); boxes(screen).get(0).setValue("result"); click(screen, "apply");
        var document = workspace.snapshot(); assertFalse((boolean)get(screen, "waiting")); bounds(screen);
        assertEquals(document, ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        var limits = new ComputerHostLimits(new ResourceLimits(1000, 100_000, 1), new ArchiveLimits(100_000, 1_000_000, 1_000_000),
            MailboxLimits.DEFAULT, HardwarePins.DEFAULT, SerialHardware.NONE, 3);
        var owner = new ComputerBlockHost(() -> {}); var body = document.flow().orElseThrow();
        owner.installFlow(body.program(document.archive()), body.initial(), limits, true);
        for (int i = 0; i < 6; i++) owner.step(limits, true);
        assertEquals(FlowRuntime.Status.COMPLETE, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.IntegerValue(0), owner.flowSnapshot().orElseThrow().state().locals().field("old"));
        assertEquals(new DataValue.IntegerValue(42), owner.flowSnapshot().orElseThrow().memory().cells().field("counter"));
        var terminal = com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(Optional.empty(), owner.flowSnapshot());
        assertEquals(owner.flowSnapshot().orElseThrow().memory().cells(), terminal.field("memory"));
        click(screen, "undo"); assertEquals(3, workspace.flow().orElseThrow().steps().size());
        click(screen, "redo"); assertEquals(document, workspace.snapshot());
    }
}
