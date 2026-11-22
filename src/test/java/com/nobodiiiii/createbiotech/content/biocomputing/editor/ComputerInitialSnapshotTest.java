package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.bounds;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.click;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.button;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises real widget callbacks; no installation or control packet is permitted. */
class ComputerInitialSnapshotTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { client.client(); }
    @AfterEach void restore() throws Exception { client.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void selectedSnapshotValueUpdatesOnlyTheChosenDraftInitialAndCanBeUndone(int width) throws Exception {
        var fixture = fixture(width);
        var before = fixture.workspace.snapshot();
        assertFalse(button(fixture.editor, "initial_snapshot").active, "Choose an existing destination first");
        selectInitial(fixture.editor, "level");
        click(fixture.editor, "initial_snapshot");
        var tree = assertInstanceOf(ComputerValueTreeScreen.class, fixture.opened[0]);
        initializeTree(tree, width);
        bounds(tree);
        assertEquals(before, fixture.workspace.snapshot(), "Opening the browser must not edit");
        assertFalse(button(tree, "apply_snapshot_initial").active);
        expand(tree, 0);
        select(tree, 1);
        assertEquals(before, fixture.workspace.snapshot(), "Selection is only a preview");
        // A later debugger refresh must not replace the snapshot being viewed.
        fixture.menu.rememberDebuggerInputs(snapshot(new DataValue.IntegerValue(3)));
        click(tree, "apply_snapshot_initial");
        assertSame(fixture.editor, fixture.opened[0]);
        var after = fixture.workspace.snapshot();
        assertEquals(new DataValue.IntegerValue(9), after.flow().orElseThrow().initial().field("level"));
        assertEquals(new DataValue.TextValue("keep"), after.flow().orElseThrow().initial().field("other"));
        assertEquals(before.flow().orElseThrow().inputs(), after.flow().orElseThrow().inputs());
        assertEquals(before.archive(), after.archive());
        assertEquals(before.flow().orElseThrow().steps(), after.flow().orElseThrow().steps());
        assertEquals(new DataValue.IntegerValue(3), new ValuePath("reader", List.of()).key("power").resolve(fixture.menu.debuggerInputs()));
        assertFalse((boolean)get(fixture.editor, "waiting"), "Never install, reset or run on import");
        bounds(fixture.editor);
        click(fixture.editor, "back");
        click(fixture.editor, "undo"); assertEquals(before, fixture.workspace.snapshot());
        click(fixture.editor, "redo"); assertEquals(after, fixture.workspace.snapshot());
    }

    private record Fixture(ComputerFlowScreen editor, ProgramWorkspace workspace, ComputerProgramMenu menu, Screen[] opened) {}
    @Test void mismatchedAndFailedReadValuesRemainVisibleWithoutChangingTheDestinationType() throws Exception {
        for (DataValue value : List.of(new DataValue.TextValue("9"), new DataValue.ErrorValue("unloaded", "Target not loaded"),
                DataValue.NullValue.INSTANCE, new DataValue.ListValue(List.of(new DataValue.IntegerValue(9))))) {
            var fixture = fixture(320);
            fixture.menu.rememberDebuggerInputs(snapshot(value));
            selectInitial(fixture.editor, "level");
            var before = fixture.workspace.snapshot();
            click(fixture.editor, "initial_snapshot");
            var tree = assertInstanceOf(ComputerValueTreeScreen.class, fixture.opened[0]);
            initializeTree(tree, 320); expand(tree, 0); select(tree, 1);
            click(tree, "apply_snapshot_initial");
            assertSame(tree, fixture.opened[0], "Failed apply stays in the browser");
            assertEquals(before, fixture.workspace.snapshot());
            assertFalse(((FlowDraft)get(fixture.editor, "draft")).canUndo(), "No partial draft change");
            assertEquals(value, new ValuePath("reader", List.of()).key("power").resolve(fixture.menu.debuggerInputs()));
            var notice = assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class,
                ((Component)get(tree, "notice")).getContents());
            assertEquals("create_biotech.biocomputing.tree.invalid", notice.getKey());
        }
    }

    @Test void cancelRenameEmptySnapshotAndPublicDeclarationCannotImplicitlyImport() throws Exception {
        var fixture = fixture(320);
        selectInitial(fixture.editor, "level");
        var before = fixture.workspace.snapshot();
        var boxes = ComputerFlowScreenTest.boxes(fixture.editor);
        boxes.getFirst().setValue("renamed");
        assertFalse(button(fixture.editor, "initial_snapshot").active);
        boxes.getFirst().setValue("level"); boxes.get(1).setValue("8");
        click(fixture.editor, "initial_snapshot");
        var tree = assertInstanceOf(ComputerValueTreeScreen.class, fixture.opened[0]);
        initializeTree(tree, 320); expand(tree, 0); select(tree, 1); tree.onClose();
        assertSame(fixture.editor, fixture.opened[0]);
        assertEquals(before, fixture.workspace.snapshot());
        assertEquals("8", ComputerFlowScreenTest.boxes(fixture.editor).get(1).getValue());
        fixture.menu.rememberDebuggerInputs(new DataValue.RecordValue(Map.of()));
        ComputerFlowScreenTest.initialize(fixture.editor, 320);
        assertFalse(button(fixture.editor, "initial_snapshot").active);
        fixture.menu.rememberDebuggerInputs(snapshot(new DataValue.IntegerValue(9)));
        click(fixture.editor, "initials");
        assertFalse(button(fixture.editor, "initial_snapshot").active, "No public variable/runtime memory write");
        bounds(fixture.editor);
        assertEquals(before, fixture.workspace.snapshot());
    }

    @Test void draftReplacementPreservesGeneralTypesAndDoesNotChangeAnInstalledDocument() {
        var original = FlowDocumentBody.emptyAssembly();
        var draft = new FlowDraft(original.edited(Map.of("value", DataType.Scalar.ANY), original.steps(),
            new DataValue.RecordValue(Map.of("value", DataValue.NullValue.INSTANCE)), original.canvas(), original.inline()));
        var installed = draft.snapshot();
        var payload = new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(
            new DataValue.TextValue("sample"), new DataValue.IntegerValue(9)))));
        var frozen = snapshot(payload);
        draft.replaceInitial("value", new ValuePath("reader", List.of()).key("power").resolve(frozen));
        assertEquals(DataType.Scalar.ANY, draft.snapshot().inputs().get("value"));
        assertEquals(payload, draft.snapshot().initial().field("value"));
        assertEquals(DataValue.NullValue.INSTANCE, installed.initial().field("value"));
        assertEquals(payload, frozen.field("reader") instanceof DataValue.RecordValue record ? record.field("power") : null);
        var applied = draft.snapshot();
        assertThrows(IllegalArgumentException.class, () -> draft.replaceInitial("missing", payload));
        assertEquals(applied, draft.snapshot());
        draft.undo(); assertEquals(installed, draft.snapshot());
        draft.redo(); assertEquals(applied, draft.snapshot());
    }

    private Fixture fixture(int width) throws Exception {
        var parent = screen(); var menu = ComputerFlowScreenTest.readyMenu(parent);
        menu.rememberDebuggerInputs(snapshot(new DataValue.IntegerValue(9)));
        var workspace = new ProgramWorkspace();
        var draft = new FlowDraft(FlowDocumentBody.emptyAssembly());
        draft.initial(new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(0), "other", new DataValue.TextValue("keep"))));
        workspace.setFlow(Optional.of(draft.snapshot()));
        var editor = new ComputerFlowScreen(menu, (Inventory)get(parent, "editorInventory"), Component.empty(),
            workspace, ignored -> fail("Must not replace the document"), () -> {}, () -> {}, () -> {}, () -> {});
        var opened = new Screen[1];
        set(editor, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        ComputerFlowScreenTest.initialize(editor, width);
        click(editor, "initials"); bounds(editor);
        return new Fixture(editor, workspace, menu, opened);
    }
    private static DataValue.RecordValue snapshot(DataValue value) {
        return new DataValue.RecordValue(Map.of("reader", new DataValue.RecordValue(Map.of("power", value))));
    }
    private static void selectInitial(ComputerFlowScreen screen, String name) {
        while (true) {
            var found = screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                .filter(b -> b.getMessage().getString().equals(name)).findFirst();
            if (found.isPresent()) { found.orElseThrow().onClick(0, 0); return; }
            click(screen, "next");
        }
    }
    private static void initializeTree(ComputerValueTreeScreen screen, int width) throws Exception {
        set(screen, "width", width); set(screen, "height", 260);
        set(screen, "minecraft", Minecraft.getInstance()); set(screen, "font", Minecraft.getInstance().font); screen.init();
    }
    @SuppressWarnings("unchecked")
    private static void expand(ComputerValueTreeScreen screen, int row) throws Exception {
        ((List<CreateTextButton>)get(screen, "expandButtons")).get(row).onClick(0, 0);
    }
    @SuppressWarnings("unchecked")
    private static void select(ComputerValueTreeScreen screen, int row) throws Exception {
        ((List<CreateTextButton>)get(screen, "rowButtons")).get(row).onClick(0, 0);
    }
}
