package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class TerminalButtonReviewRegressionTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();

    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); TerminalBehaviourTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @Test void entryOnlyConfigurePreservesEveryPathBindingInARepeatTemplate() throws Exception {
        var editor = editor();
        click(editor, "add.repeat");
        var draft = (TerminalDraft)get(editor, "draft");
        String template = ((TerminalDefinition.Repeat)draft.snapshot().nodes().get((String)get(editor, "selected"))).template();
        select(editor, template); click(editor, "add.button"); String id = (String)get(editor, "selected");
        var title = new TerminalBinding.Path(new ValuePath("row", List.of()).key("title"));
        var enabled = new TerminalBinding.Path(new ValuePath("memory", List.of()).key("enabled"));
        var inputs = new TerminalBinding.Path(new ValuePath("output", List.of()).key("inputs"));
        draft.replace(id, new TerminalDefinition.Button(title, enabled, "initial", inputs));
        init(editor, 320);

        for (int slot = 0; slot < 3; slot++) {
            ((EditBox)get(editor, "entry")).setValue("event_" + slot);
            click(editor, "configure");
            var button = assertInstanceOf(TerminalDefinition.Button.class, draft.snapshot().nodes().get(id));
            assertEquals(title, button.title()); assertEquals(enabled, button.enabled()); assertEquals(inputs, button.inputs());
            assertEquals("event_" + slot, button.entry());
            if (slot < 2) click(editor, "button_slot");
        }
        bounds(editor);
    }

    @Test void invalidButtonEntryDoesNotPartiallyApplyLiteralEdit() throws Exception {
        var editor = editor(); click(editor, "add.button");
        var draft = (TerminalDraft)get(editor, "draft"); String id = (String)get(editor, "selected");
        var before = draft.snapshot();
        ((EditBox)get(editor, "literal")).setValue("changed");
        ((EditBox)get(editor, "entry")).setValue("");
        click(editor, "configure");
        assertEquals(before, draft.snapshot());
        bounds(editor);
    }

    @Test void pathSelectionThenEntryOnlyConfigureDoesNotReuseTheLiteralBuffer() throws Exception {
        var editor = editor(); click(editor, "add.button");
        var draft = (TerminalDraft)get(editor, "draft"); String id = (String)get(editor, "selected");
        ((EditBox)get(editor, "literal")).setValue("title"); click(editor, "path_key");
        var selected = ((TerminalDefinition.Button)draft.snapshot().nodes().get(id)).title();
        ((EditBox)get(editor, "entry")).setValue("event"); click(editor, "configure");
        var button = assertInstanceOf(TerminalDefinition.Button.class, draft.snapshot().nodes().get(id));
        assertEquals(selected, button.title()); assertEquals("event", button.entry());
        bounds(editor);
    }

    @Test void clickPendingRejectsRefreshUntilItsAcceptedReplyIsDelivered() throws Exception {
        var fixture = terminalWithButton(); var viewer = fixture.viewer(); var menu = fixture.menu();
        var reads = new ArrayList<ComputerControlSession.Action>(); var clicks = new ArrayList<TerminalClick>();
        set(viewer, "request", (Consumer<ComputerControlSession.Action>)reads::add);
        set(viewer, "clickRequest", (BiConsumer<Long, String>)((view, instance) -> clicks.add(new TerminalClick(view, instance))));
        init(viewer, 320); deliverRead(menu, fixture.nonce(), fixture.limits(), 1, fixture.definition()); tick(viewer);
        CreateTextButton action = action(viewer); action.onClick(0, 0);
        set(menu, "latestControlIntent", 2L); set(menu, "latestControlAction", ComputerControlSession.Action.TERMINAL_CLICK);
        menu.accept(new ProgramStatusResponse(0, fixture.nonce(), 1, 1, "ok", "old", new byte[0])); tick(viewer);
        assertTrue((boolean)get(viewer, "requestPending"));
        button(viewer, "refresh").onClick(0, 0);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), reads);
        assertEquals(1, clicks.size());
        var clickStatus = new DataValue.RecordValue(Map.of("terminal_click", new DataValue.TextValue("ok")));
        menu.accept(new ProgramStatusResponse(0, fixture.nonce(), 2, 1, "ok", "accepted", ProgramEditorWire.encodeValue(clickStatus, fixture.limits()))); tick(viewer);
        var notice = assertInstanceOf(TranslatableContents.class, ((Component)get(viewer, "notice")).getContents());
        assertEquals("create_biotech.biocomputing.terminal.click_accepted", notice.getKey());
        assertTrue(button(viewer, "refresh").active);
    }

    @Test void refreshPendingRejectsOldActionAndKeepsTheNewReadResult() throws Exception {
        var fixture = terminalWithButton(); var viewer = fixture.viewer(); var menu = fixture.menu();
        var reads = new ArrayList<ComputerControlSession.Action>(); var clicks = new ArrayList<TerminalClick>();
        set(viewer, "request", (Consumer<ComputerControlSession.Action>)reads::add);
        set(viewer, "clickRequest", (BiConsumer<Long, String>)((view, instance) -> clicks.add(new TerminalClick(view, instance))));
        init(viewer, 320); deliverRead(menu, fixture.nonce(), fixture.limits(), 1, fixture.definition()); tick(viewer);
        click(viewer, "refresh"); action(viewer).onClick(0, 0);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.READ_TERMINAL), reads);
        assertTrue(clicks.isEmpty());
        deliverRead(menu, fixture.nonce(), fixture.limits(), 2, fixture.definition()); tick(viewer);
        assertNotNull(get(viewer, "snapshot"));
    }

    @Test void localOversizedClickRejectionClearsPendingAndPermitsRefresh() throws Exception {
        var fixture = terminalWithButton(); var viewer = fixture.viewer();
        set(viewer, "publishedView", 1L);
        set(viewer, "clickRequest", (BiConsumer<Long, String>)((view, instance) -> new TerminalClick(view, instance)));
        init(viewer, 320);
        var click = ComputerTerminalScreen.class.getDeclaredMethod("click", String.class); click.setAccessible(true);
        click.invoke(viewer, "x".repeat(TerminalClick.MAX_INSTANCE + 1));
        assertFalse((boolean)get(viewer, "requestPending"));
        assertTrue((boolean)get(viewer, "clickLocked"));
        assertTrue(button(viewer, "refresh").active);
    }

    private ComputerTerminalEditorScreen editor() throws Exception {
        var parent = screen(); var opened = new Screen[1]; set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        click(parent, "edit"); var editor = assertInstanceOf(ComputerTerminalEditorScreen.class, opened[0]); init(editor, 320); return editor;
    }
    private static void select(ComputerTerminalEditorScreen screen, String id) throws Exception {
        for (int i = 0; i < 30; i++) {
            @SuppressWarnings("unchecked") var buttons = (Map<String, CreateTextButton>)get(screen, "treeButtons");
            if (buttons.containsKey(id)) { buttons.get(id).onClick(0, 0); return; }
            var next = button(screen, "tree_next");
            if (next.active) next.onClick(0, 0); else while (button(screen, "tree_previous").active) click(screen, "tree_previous");
        }
        fail("Node not accessible: " + id);
    }
    private record Fixture(ComputerTerminalScreen viewer, ComputerProgramMenu menu, UUID nonce, ArchiveLimits limits, TerminalDefinition definition) {}
    private Fixture terminalWithButton() throws Exception {
        var parent = screen(); var menu = (ComputerProgramMenu)get(parent, "menu"); var nonce = UUID.randomUUID(); var limits = new ArchiveLimits(1000, 100000, 1000000);
        set(menu, "nonce", nonce); set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 1, "ready", "", new ResourceLimits(1000, 1000000, 1), limits, new byte[0])));
        var definition = new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Button(new TerminalBinding.Literal(new DataValue.TextValue("Run")), new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "go", new TerminalBinding.Literal(new DataValue.RecordValue(Map.of())))));
        return new Fixture(new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"), Component.empty(), parent), menu, nonce, limits, definition);
    }
    private static void deliverRead(ComputerProgramMenu menu, UUID nonce, ArchiveLimits limits, long intent, TerminalDefinition definition) throws Exception {
        set(menu, "latestControlIntent", intent); set(menu, "latestControlAction", ComputerControlSession.Action.READ_TERMINAL);
        var status = new DataValue.RecordValue(Map.of("terminal", TerminalCodec.encode(definition), "memory", new DataValue.RecordValue(Map.of()), "output", DataValue.NullValue.INSTANCE, "phase", new DataValue.TextValue("IDLE"), "paused", new DataValue.BooleanValue(false)));
        menu.accept(new ProgramStatusResponse(0, nonce, intent, 1, "ok", "", ProgramEditorWire.encodeValue(status, limits)));
    }
    private static CreateTextButton action(Screen viewer) {
        return viewer.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(button -> button.getMessage().getString().equals("Run")).findFirst().orElseThrow();
    }
    private static void tick(ComputerTerminalScreen viewer) throws Exception { var method = ComputerTerminalScreen.class.getDeclaredMethod("containerTick"); method.setAccessible(true); method.invoke(viewer); }
}
