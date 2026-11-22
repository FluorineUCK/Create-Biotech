package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.Map;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;

class ComputerInlineParameterTest {
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void valueRowEditsOnCanvasAndEnterChangesOnlyTheLocalCompiledProgram() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String id = draft.add(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), 20, 20); draft.output("answer", id);
        clickValue(screen, 20, 20);
        assertNull(get(screen, "editing"), "The ordinary modal must stay closed");
        var field = field(screen); field.setValue("17");
        assertEquals(new DataValue.IntegerValue(5), run(draft));
        assertTrue(screen.keyPressed(257, 0, 0));
        assertEquals(new DataValue.IntegerValue(17), run(draft));
        assertEquals(Map.of("answer", id), draft.outputs()); assertEquals(20, draft.node(id).x()); assertEquals(20, draft.node(id).y());
        assertFalse((boolean)get(screen, "requested")); assertNull(get(screen, "editing"));
    }

    private static EditBox field(ComputerProgramScreen screen) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }

    @Test void invalidParameterBlocksToolbarAndInstallUntilCorrectedOrCancelled() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String id = draft.add(ProgramDraftTest.number(5), 20, 20); draft.output("answer", id);
        clickValue(screen, 20, 20); field(screen).setValue("bad");
        button(screen, "add").onClick(0, 0);
        assertFalse((boolean)get(screen, "paletteOpen"));
        assertEquals("bad", field(screen).getValue());
        button(screen, "install").onClick(0, 0);
        assertNotNull(get(screen, "inline")); assertFalse((boolean)get(screen, "waiting"));
        assertEquals(new DataValue.IntegerValue(5), run(draft));
        screen.keyPressed(256, 0, 0);
        assertNull(get(screen, "inline")); assertEquals(new DataValue.IntegerValue(5), run(draft));
        clickValue(screen, 20, 20); field(screen).setValue("19");
        var add = button(screen, "add");
        screen.mouseClicked(add.getX() + 2, add.getY() + 2, 0);
        screen.mouseReleased(add.getX() + 2, add.getY() + 2, 0);
        assertTrue((boolean)get(screen, "paletteOpen"));
        assertEquals(new DataValue.IntegerValue(19), run(draft));
    }

    private static com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton button(ComputerProgramScreen screen, String key) {
        return screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance)
            .map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.editor." + key)).findFirst().orElseThrow();
    }

    @Test void transformedFieldAndButtonsPreservePendingTextAcrossPanZoomAndResize() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String id = draft.add(ProgramDraftTest.number(5), 20, 20); draft.output("answer", id);
        clickValue(screen, 20, 20); var input = field(screen); input.setValue("bad");
        double x = coordinate(screen, "canvasX") + 220, y = coordinate(screen, "canvasY") + 100;
        screen.mouseClicked(x, y, 2); screen.mouseDragged(x + 15, y + 10, 2, 15, 10); screen.mouseReleased(x + 15, y + 10, 2);
        screen.mouseScrolled(x, y, 0, -2);
        set(screen, "width", 620); screen.init();
        assertSame(input, field(screen)); assertEquals("bad", input.getValue());
        pointer(screen, 20 + 118, 20 + 24); // transformed cancel, even with invalid text
        assertNull(get(screen, "inline")); assertEquals(new DataValue.IntegerValue(5), run(draft));
        clickValue(screen, 20, 20); input = field(screen); input.setValue("23");
        // Native left-click cursor placement reads GLFW modifier state; verify it in the rendered client.
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(screen, "viewport");
        screen.mouseClicked(coordinate(screen, "canvasX") + viewport.screenX(26), coordinate(screen, "canvasY") + viewport.screenY(44), 1);
        assertSame(input, field(screen)); assertEquals(new DataValue.IntegerValue(5), run(draft));
        pointer(screen, 20 + 99, 20 + 24);
        assertNull(get(screen, "inline")); assertEquals(new DataValue.IntegerValue(23), run(draft));
        assertNull(get(screen, "editing")); assertNull(screen.getFocused());
    }

    private static void pointer(ComputerProgramScreen screen, double x, double y) throws Exception {
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(screen, "viewport");
        double sx = coordinate(screen, "canvasX") + viewport.screenX(x), sy = coordinate(screen, "canvasY") + viewport.screenY(y);
        screen.mouseClicked(sx, sy, 0); screen.mouseReleased(sx, sy, 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"INTEGER,17", "DECIMAL,2.5", "BOOLEAN,true", "TEXT,hello", "FIELD,b", "INDEX,1", "INPUT,b", "MEMORY,b"})
    void typedEditsKeepConnectionsAndExecuteThroughTheNormalCompiler(String kindName, String value) throws Exception {
        var kind = ComputerNodePalette.Kind.valueOf(kindName);
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        var a = new DataValue.IntegerValue(4); var b = new DataValue.IntegerValue(9);
        var record = new DataValue.RecordValue(Map.of("a", a, "b", b));
        String source = draft.add(new ComputationGraph.Constant(kind == ComputerNodePalette.Kind.INDEX
            ? new DataValue.ListValue(java.util.List.of(a, b)) : record), 180, 20);
        draft.putInput("a", DataType.Scalar.INTEGER); draft.putInput("b", DataType.Scalar.INTEGER);
        draft.putCell("a", DataType.Scalar.INTEGER, a); draft.putCell("b", DataType.Scalar.INTEGER, b);
        String initial = switch(kind) { case INTEGER, DECIMAL, INDEX -> "0"; case BOOLEAN -> "false"; default -> "a"; };
        String id = draft.add(NodeParameter.parse(kind, initial), 20, 20); draft.output("answer", id);
        if (kind == ComputerNodePalette.Kind.FIELD || kind == ComputerNodePalette.Kind.INDEX) draft.connect(id, "source", source);
        var connections = draft.node(id).connections();
        // Keep a real installed machine running the pre-edit archive; editing is not installation.
        var installed = new ComputationMachine(draft.snapshot().compile(ProgramDraftTest.ROOM));
        installed.begin(record); installed.tick(); var old = installed.report().output().orElseThrow();
        clickValue(screen, 20, 20); field(screen).setValue(value); screen.keyPressed(257, 0, 0);
        assertNull(get(screen, "inline")); assertEquals(connections, draft.node(id).connections());
        var edited = new ComputationMachine(draft.snapshot().compile(ProgramDraftTest.ROOM)); edited.begin(record); edited.tick();
        DataValue expected = switch(kind) {
            case INTEGER -> new DataValue.IntegerValue(17); case DECIMAL -> new DataValue.DecimalValue(2.5);
            case BOOLEAN -> new DataValue.BooleanValue(true); case TEXT -> new DataValue.TextValue("hello"); default -> b;
        };
        assertEquals(expected, edited.report().output().orElseThrow().field("answer"));
        installed.begin(record); installed.tick(); assertEquals(old, installed.report().output().orElseThrow());
        assertEquals(a, installed.memory().field("a")); assertEquals(b, installed.memory().field("b"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"INTEGER,9223372036854775808", "DECIMAL,NaN", "DECIMAL,Infinity", "BOOLEAN,TRUE", "INDEX,-1", "INDEX,2147483648", "INPUT,''", "MEMORY,''"})
    void malformedValuesAreRetainedForCorrectionWithoutPartialChange(String kindName, String value) throws Exception {
        var kind = ComputerNodePalette.Kind.valueOf(kindName); var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String initial = switch(kind) { case INPUT, MEMORY -> "a"; case BOOLEAN -> "false"; default -> "0"; };
        var definition = NodeParameter.parse(kind, initial); String id = draft.add(definition, 20, 20);
        clickValue(screen, 20, 20); field(screen).setValue(value); screen.keyPressed(257, 0, 0);
        assertNotNull(get(screen, "inline")); assertEquals(value, field(screen).getValue()); assertEquals(definition, draft.node(id).definition());
        screen.keyPressed(256, 0, 0); assertEquals(definition, draft.node(id).definition());
    }

    @Test void oversizedPasteCannotCommitATruncatedPrefixAndLongImportedValuesSurvive() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft");
        String id = draft.add(new ComputationGraph.Constant(new DataValue.TextValue("keep")), 20, 20);
        clickValue(screen, 20, 20); field(screen).setValue("x".repeat(ProgramEditorWire.MAX_BYTES + 2));
        screen.keyPressed(257, 0, 0); assertNotNull(get(screen, "inline"));
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("keep")), draft.node(id).definition());
        screen.keyPressed(256, 0, 0);
        String longValue = "界".repeat(ProgramEditorWire.MAX_BYTES + 2);
        draft.configure(id, new ComputationGraph.Constant(new DataValue.TextValue(longValue)));
        clickValue(screen, 20, 20); assertEquals(longValue, field(screen).getValue()); screen.init();
        screen.keyPressed(257, 0, 0); assertEquals(new ComputationGraph.Constant(new DataValue.TextValue(longValue)), draft.node(id).definition());
    }

    @Test void leavingAModuleOrEventCommitsOnlyItsOwnDraftEvenWhenNodeIdsMatch() throws Exception {
        var screen = screen(); var root = (ProgramDraft)get(screen, "draft");
        String rootId = root.add(ProgramDraftTest.number(5), 20, 20); root.output("answer", rootId);
        button(screen, "modules").onClick(0, 0); ((EditBox)get(screen, "moduleName")).setValue("leaf");
        button(screen, "create_module").onClick(0, 0);
        var child = (ProgramDraft)get(screen, "draft"); String childId = child.add(ProgramDraftTest.number(7), 20, 20); child.output("answer", childId);
        assertEquals(rootId, childId);
        clickValue(screen, 20, 20); field(screen).setValue("11"); button(screen, "modules").onClick(0, 0);
        button(screen, "root_graph").onClick(0, 0);
        assertSame(root, get(screen, "draft")); assertEquals(new DataValue.IntegerValue(5), run(root));
        assertEquals(ProgramDraftTest.number(11), child.node(childId).definition()); assertNull(get(screen, "inline"));
        button(screen, "entries").onClick(0, 0); ((EditBox)get(screen, "entryName")).setValue("message");
        button(screen, "create_entry").onClick(0, 0);
        var event = (ProgramDraft)get(screen, "draft"); String eventId = event.add(ProgramDraftTest.number(3), 20, 20); event.output("answer", eventId);
        clickValue(screen, 20, 20); field(screen).setValue("13"); button(screen, "entries").onClick(0, 0);
        button(screen, "root_graph").onClick(0, 0);
        assertSame(root, get(screen, "draft")); assertEquals(ProgramDraftTest.number(13), event.node(eventId).definition());
        assertEquals(ProgramDraftTest.number(11), child.node(childId).definition()); assertEquals(new DataValue.IntegerValue(5), run(root));
        pointer(screen, 28, 25); assertEquals(rootId, get(screen, "editing")); assertNull(get(screen, "inline"));
    }

    @Test void movingTheCursorDoesNotClearAnErrorAndCancelRemovesStaleFooterError() throws Exception {
        var screen = screen(); var draft = (ProgramDraft)get(screen, "draft"); draft.add(ProgramDraftTest.number(5), 20, 20);
        clickValue(screen, 20, 20); var input = field(screen); input.setValue("bad"); screen.keyPressed(257, 0, 0);
        var session = (ComputerInlineParameterEditor)get(screen, "inline");
        var error = session.tooltip(input.getX() + 1, input.getY() + 1);
        assertEquals("create_biotech.biocomputing.editor.invalid_parameter",
            ((net.minecraft.network.chat.contents.TranslatableContents)error.getContents()).getKey());
        // EditBox invokes its responder on cursor movement even though its value is unchanged.
        input.moveCursorTo(0, false);
        assertEquals(error, session.tooltip(input.getX() + 1, input.getY() + 1));
        screen.keyPressed(256, 0, 0);
        assertEquals("create_biotech.biocomputing.editor.result.ready",
            ((net.minecraft.network.chat.contents.TranslatableContents)((net.minecraft.network.chat.Component)get(screen, "notice")).getContents()).getKey());
    }
    private static void clickValue(ComputerProgramScreen screen, double x, double y) throws Exception {
        var viewport = (com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(screen, "viewport");
        double sx = coordinate(screen, "canvasX") + viewport.screenX(x + 8), sy = coordinate(screen, "canvasY") + viewport.screenY(y + 24);
        screen.mouseClicked(sx, sy, 0); screen.mouseReleased(sx, sy, 0);
    }
    private static int coordinate(ComputerProgramScreen screen, String name) throws Exception {
        var method = ComputerProgramScreen.class.getDeclaredMethod(name); method.setAccessible(true); return (int)method.invoke(screen);
    }
    private static DataValue run(ProgramDraft draft) {
        var machine = new ComputationMachine(draft.snapshot().compile(ProgramDraftTest.ROOM));
        machine.begin(new DataValue.RecordValue(Map.of())); machine.tick(); return machine.report().output().orElseThrow().field("answer");
    }
}
