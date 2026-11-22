package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.lwjgl.glfw.GLFW;

/** Real editor, menu response acceptance and runtime; only outbound screen requests are intercepted. */
class TerminalInputCompletionTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    private static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 1000000, 10000000);
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); TerminalBehaviourTest.bootstrap(); }
    @BeforeEach void client() throws Exception {
        environment.client();
        set(net.minecraft.client.Minecraft.getInstance(), "soundManager", allocate(SilentSounds.class));
    }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void editorAppliedDocumentTravelsThroughWireAndRealEventCommit(int width) throws Exception {
        var workspace = new ProgramWorkspace(ProgramDocument.of(textProgram()));
        var editor = editor(workspace, width); click(editor, "add.input");
        String id = (String)get(editor, "selected");
        ((EditBox)get(editor, "literal")).setValue("query"); click(editor, "path_key");
        ((EditBox)get(editor, "entry")).setValue("submit");
        ((EditBox)get(editor, "argument")).setValue("query"); click(editor, "configure");
        click(editor, "input_slot"); ((EditBox)get(editor, "literal")).setValue("true"); click(editor, "configure");
        click(editor, "input_slot"); ((EditBox)get(editor, "literal")).setValue("{\"query\":\"old\",\"category\":\"all\"}"); click(editor, "configure");
        click(editor, "apply");
        var document = ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), LIMITS), LIMITS);
        var input = assertInstanceOf(TerminalDefinition.Input.class, document.terminal().orElseThrow().nodes().get(id));
        assertEquals(path("memory", "query"), input.initial()); assertEquals("submit", input.entry()); assertEquals("query", input.argument());
        var block = new TerminalBehaviourTest.Harness(); var host = block.getBehaviour(ComputerBehaviour.TYPE);
        host.installDocument(document, ComputerRuntime.MemoryPolicy.RESET);
        long revision = host.programRevision(); var nonce = UUID.randomUUID(); var session = new ComputerControlSession(nonce);
        var read = session.apply(nonce, 1, revision, ComputerControlSession.Action.READ_TERMINAL, () -> { throw new AssertionError(); }, host).orElseThrow();
        var projected = TerminalProjection.project(document.terminal().orElseThrow(), TerminalStatusView.decode(read.dedicatedStatus().orElseThrow()).roots(), Map.of(), 10, 100);
        assertEquals("old", projected.children().getFirst().input().orElseThrow().text());
        var answer = session.apply(nonce, 2, revision, ComputerControlSession.Action.TERMINAL_SUBMIT,
            () -> new TerminalSubmit(1, "4:root/" + id.length() + ":" + id, "submitted text").encode(), host).orElseThrow();
        assertEquals("ok", answer.code());
        assertEquals(text("old"), host.snapshot().orElseThrow().machine().memory().field("query"), "beginLocal must not commit synchronously");
        for (int i = 0; i < 40 && host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) block.tick();
        assertEquals(text("submitted text"), host.snapshot().orElseThrow().machine().memory().field("query"));
        assertNotEquals(ComputationMachine.Status.RUNNING, host.snapshot().orElseThrow().machine().status());
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void inputEditorHitTargetsDoNotOverlapAndMetadataEditsPreserveAllPaths(int width) throws Exception {
        var editor = editor(new ProgramWorkspace(ProgramDocument.of(textProgram())), width); click(editor, "add.input");
        String id = (String)get(editor, "selected"); var draft = (TerminalDraft)get(editor, "draft");
        for (String key : List.of("query", "enabled", "inputs")) {
            ((EditBox)get(editor, "literal")).setValue(key); click(editor, "path_key"); click(editor, "input_slot");
        }
        for (int slot = 0; slot < 3; slot++) {
            ((EditBox)get(editor, "entry")).setValue("event_" + slot); click(editor, "configure");
            var input = (TerminalDefinition.Input)draft.snapshot().nodes().get(id);
            assertEquals("value", input.argument()); assertEquals("event_" + slot, input.entry());
            assertPaths(input);
            ((EditBox)get(editor, "argument")).setValue("arg_" + slot); click(editor, "configure");
            input = (TerminalDefinition.Input)draft.snapshot().nodes().get(id);
            assertEquals("event_" + slot, input.entry()); assertEquals("arg_" + slot, input.argument()); assertPaths(input);
            ((EditBox)get(editor, "argument")).setValue("value"); click(editor, "configure");
            bounds(editor);
            var slotButton = button(editor, "input_slot");
            assertTrue(editor.mouseClicked(slotButton.getX() + slotButton.getWidth() / 2.0, slotButton.getY() + 10, 0));
        }
    }
    private static void assertPaths(TerminalDefinition.Input input) {
        assertEquals(path("memory", "query"), input.initial()); assertEquals(path("memory", "enabled"), input.enabled()); assertEquals(path("memory", "inputs"), input.inputs());
    }
    @Test void invalidInputMetadataRollsBackLiteralAndOtherMetadataTogether() throws Exception {
        for (String property : List.of("entry", "argument")) {
            var editor = editor(new ProgramWorkspace(), 320); click(editor, "add.input");
            var draft = (TerminalDraft)get(editor, "draft"); var before = draft.snapshot();
            ((EditBox)get(editor, "literal")).setValue("replacement");
            ((EditBox)get(editor, "entry")).setValue("changed_entry"); ((EditBox)get(editor, "argument")).setValue("changed_argument");
            ((EditBox)get(editor, property)).setValue(""); click(editor, "configure");
            assertEquals(before, draft.snapshot(), property + " must reject the whole candidate");
        }
    }
    @Test void observedContextChangesDiscardDraftsWithoutTypingEvenWhenRowIsOffPage() throws Exception {
        var f = fixture(repeated(), "IDLE", false, rows("A"));
        fields(f.viewer).getFirst().setValue("unsent A"); page(f.viewer, 1);
        f.refresh(rows("B")); f.refresh(rows("A")); page(f.viewer, 0);
        assertEquals("initial A", fields(f.viewer).getFirst().getValue(), "off-page refresh must not resurrect A's discarded draft");
    }
    @Test void sameContextSurvivesPagingResizeRefreshButChangedDefinitionDoesNot() throws Exception {
        var f = fixture(repeated(), "IDLE", false, rows("A")); fields(f.viewer).getFirst().setValue("keep me");
        page(f.viewer, 1); page(f.viewer, 0); assertEquals("keep me", fields(f.viewer).getFirst().getValue());
        init(f.viewer, 426); assertEquals("keep me", fields(f.viewer).getFirst().getValue()); bounds(f.viewer);
        f.refresh(rows("A")); assertEquals("keep me", fields(f.viewer).getFirst().getValue());
        var nodes = new LinkedHashMap<>(f.definition.nodes()); var old = (TerminalDefinition.Input)nodes.get("input");
        nodes.put("input", new TerminalDefinition.Input(old.initial(), old.enabled(), "different_entry", old.argument(), old.inputs()));
        var original = f.definition; f.definition = new TerminalDefinition("rows", nodes); f.refresh(rows("A"));
        assertEquals("initial A", fields(f.viewer).getFirst().getValue());
        f.definition = original; f.refresh(rows("A")); assertEquals("initial A", fields(f.viewer).getFirst().getValue());
    }
    @Test void visibleContextChangesResetWithoutTypingAndUntouchedInitialTracksRefresh() throws Exception {
        var f = fixture(repeated(), "IDLE", false, rows("A")); fields(f.viewer).getFirst().setValue("unsent A");
        f.refresh(rows("B")); assertEquals("initial B", fields(f.viewer).getFirst().getValue());
        f.refresh(rows("A")); assertEquals("initial A", fields(f.viewer).getFirst().getValue());
        var values = new ArrayList<>(((DataValue.ListValue)rows("A").field("rows")).values());
        values.set(0, record("initial", text("fresh initial"), "context", record("category", text("A"))));
        f.refresh(record("rows", new DataValue.ListValue(values)));
        assertEquals("fresh initial", fields(f.viewer).getFirst().getValue());
    }
    @Test void pendingSubmitRejectsRefreshClickAndEnterUntilDedicatedReplyThenManualRefreshRecovers() throws Exception {
        var f = fixture(simple(true), "IDLE", false, record());
        fields(f.viewer).getFirst().setValue("local"); click(f.viewer, "submit");
        assertEquals(2, f.sent.size()); assertEquals(ComputerControlSession.Action.TERMINAL_SUBMIT, f.sent.getLast());
        button(f.viewer, "refresh").onClick(0, 0); action(f.viewer).onClick(0, 0);
        f.viewer.setFocused(fields(f.viewer).getFirst()); f.viewer.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals(2, f.sent.size()); assertEquals("local", fields(f.viewer).getFirst().getValue());
        f.menu.accept(new ProgramStatusResponse(0, f.nonce, 1, 7, "ok", "old", ProgramEditorWire.encodeValue(f.envelope(), LIMITS))); tick(f.viewer);
        assertTrue((boolean)get(f.viewer, "requestPending"));
        f.deliver("invalid", record("terminal_submit", text("invalid")));
        assertFalse((boolean)get(f.viewer, "requestPending")); assertTrue((boolean)get(f.viewer, "clickLocked"));
        assertEquals("local", fields(f.viewer).getFirst().getValue());
        assertFalse(button(f.viewer, "submit").active); assertTrue(button(f.viewer, "refresh").active);
        f.refresh(record()); assertTrue(button(f.viewer, "submit").active); assertEquals("local", fields(f.viewer).getFirst().getValue());
        click(f.viewer, "submit"); assertEquals(new TerminalSubmit(3, "4:root/5:input", "local"), f.submits.getLast());
        f.deliver("ok", record("terminal_submit", text("ok")));
        assertFalse((boolean)get(f.viewer, "requestPending")); assertFalse(button(f.viewer, "submit").active);
    }
    @Test void enterSharesButtonGuardsAndNeverFallsBackToAnotherFocusedControl() throws Exception {
        for (String guard : List.of("disabled", "paused", "running", "pending", "consumed")) {
            var f = fixture(simple(!guard.equals("disabled")), guard.equals("running") ? "RUNNING" : "IDLE", guard.equals("paused"), record());
            if (guard.equals("pending")) click(f.viewer, "refresh");
            if (guard.equals("consumed")) { click(f.viewer, "submit"); f.deliver("ok", record("terminal_submit", text("ok"))); }
            int before = f.sent.size(); f.viewer.setFocused(fields(f.viewer).getFirst());
            f.viewer.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0); button(f.viewer, "submit").onClick(0, 0);
            assertEquals(before, f.sent.size(), guard + " must apply to keyboard and pointer paths");
        }
        var f = fixture(simple(true), "IDLE", false, record());
        f.viewer.setFocused(fields(f.viewer).getFirst()); fields(f.viewer).getFirst().setValue("enter text");
        f.viewer.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "enter text")), f.submits);
    }

    @Test void tabLeavesFocusedInputForItsSubmitWithoutTypingOrSubmitting() throws Exception {
        var f = fixture(simple(true), "IDLE", false, record());
        var field = fields(f.viewer).getFirst(); field.setValue("local draft");
        f.viewer.setFocused(field); assertSame(field, f.viewer.getFocused());
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        assertSame(button(f.viewer, "submit"), f.viewer.getFocused());
        assertEquals("local draft", field.getValue());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent);
        assertTrue(f.submits.isEmpty());
    }

    @ParameterizedTest @CsvSource({"320, 257", "426, 257", "320, 32", "426, 32"})
    void tabToSubmitThenExplicitButtonKeySubmitsOnce(int width, int activationKey) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "keyInventory", new net.minecraft.client.KeyMapping("test.terminal.inventory", GLFW.GLFW_KEY_E, "test.terminal"));
        set(options, "keyDrop", new net.minecraft.client.KeyMapping("test.terminal.drop", GLFW.GLFW_KEY_Q, "test.terminal"));
        set(options, "keyPickItem", new net.minecraft.client.KeyMapping("test.terminal.pick", GLFW.GLFW_KEY_F, "test.terminal"));
        set(net.minecraft.client.Minecraft.getInstance(), "options", options);
        var field = fields(f.viewer).getFirst(); field.setValue("keyboard draft"); f.viewer.setFocused(field);
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        assertSame(button(f.viewer, "submit"), f.viewer.getFocused()); assertTrue(f.submits.isEmpty());
        f.viewer.keyPressed(activationKey, 0, 0);
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "keyboard draft")), f.submits);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.TERMINAL_SUBMIT), f.sent);
        f.viewer.keyPressed(activationKey, 0, 0);
        assertEquals(1, f.submits.size());
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void spaceSubmitRebuildsAndAcceptedReplyLeaveDelayedCharactersWithoutAnInputTarget(int width) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        var originalInput = fields(f.viewer).getFirst(); originalInput.setValue("submit draft"); f.viewer.setFocused(originalInput);
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); var originalSubmit = button(f.viewer, "submit");
        assertSame(originalSubmit, f.viewer.getFocused());
        assertTrue(f.viewer.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0));
        assertNull(f.viewer.getFocused()); assertFalse(originalInput.isFocused()); assertFalse(originalSubmit.isFocused());
        var pendingInput = fields(f.viewer).getFirst(); assertNotSame(originalInput, pendingInput); assertFalse(pendingInput.isFocused());
        assertFalse(f.viewer.charTyped(' ', 0));
        assertEquals("submit draft", originalInput.getValue()); assertEquals("submit draft", pendingInput.getValue());
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "submit draft")), f.submits);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.TERMINAL_SUBMIT), f.sent);

        f.deliver("ok", record("terminal_submit", text("ok")));
        assertNull(f.viewer.getFocused()); assertFalse(pendingInput.isFocused());
        var acceptedInput = fields(f.viewer).getFirst(); assertNotSame(pendingInput, acceptedInput); assertFalse(acceptedInput.isFocused());
        assertFalse(f.viewer.charTyped(' ', 0)); assertEquals("submit draft", acceptedInput.getValue());
        assertFalse(button(f.viewer, "submit").active); assertTrue(button(f.viewer, "refresh").active);
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "submit draft")), f.submits);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.TERMINAL_SUBMIT), f.sent);
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void acceptedSpaceSubmitThenSingleTabAndSpaceRefreshNeverRedirectDelayedCharactersIntoInput(int width) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        var originalInput = fields(f.viewer).getFirst(); originalInput.setValue("refresh draft"); f.viewer.setFocused(originalInput);
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); assertSame(button(f.viewer, "submit"), f.viewer.getFocused());
        f.viewer.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0); assertNull(f.viewer.getFocused()); assertFalse(f.viewer.charTyped(' ', 0));
        assertEquals("refresh draft", fields(f.viewer).getFirst().getValue());
        f.deliver("ok", record("terminal_submit", text("ok")));
        var acceptedInput = fields(f.viewer).getFirst(); assertNotSame(originalInput, acceptedInput);
        assertNull(f.viewer.getFocused()); assertFalse(f.viewer.charTyped(' ', 0)); assertEquals("refresh draft", acceptedInput.getValue());
        var refresh = button(f.viewer, "refresh"); assertTrue(refresh.active);
        assertTrue(f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0)); assertSame(refresh, f.viewer.getFocused());
        assertTrue(f.viewer.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0)); assertNull(f.viewer.getFocused()); assertFalse(refresh.isFocused());
        var pendingInput = fields(f.viewer).getFirst(); assertNotSame(acceptedInput, pendingInput); assertFalse(pendingInput.isFocused());
        assertFalse(f.viewer.charTyped(' ', 0)); assertEquals("refresh draft", pendingInput.getValue());
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "refresh draft")), f.submits);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.TERMINAL_SUBMIT, ComputerControlSession.Action.READ_TERMINAL), f.sent);

        f.deliver("ok", f.envelope());
        var refreshedInput = fields(f.viewer).getFirst(); assertNotSame(pendingInput, refreshedInput); assertFalse(refreshedInput.isFocused());
        assertNull(f.viewer.getFocused()); assertFalse(f.viewer.charTyped(' ', 0)); assertEquals("refresh draft", refreshedInput.getValue());
        assertTrue(button(f.viewer, "submit").active); assertTrue(button(f.viewer, "refresh").active);
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "refresh draft")), f.submits);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.TERMINAL_SUBMIT, ComputerControlSession.Action.READ_TERMINAL), f.sent);
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void nativeTabOrderTraversesMultipleInputsAndButtonsInBothDirectionsAndWraps(int width) throws Exception {
        var nodes = new LinkedHashMap<>(simple(true).nodes());
        nodes.put("root", new TerminalDefinition.Column(List.of("input", "second", "button")));
        nodes.put("second", nodes.get("input"));
        var f = fixture(new TerminalDefinition("root", nodes), "IDLE", false, record()); init(f.viewer, width);
        var inputs = fields(f.viewer); assertEquals(2, inputs.size());
        var submits = f.viewer.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.terminal.submit")).toList();
        var order = List.of(button(f.viewer, "refresh"), inputs.get(0), submits.get(0), inputs.get(1), submits.get(1), action(f.viewer), button(f.viewer, "back"));
        inputs.get(0).setValue("first draft"); inputs.get(1).setValue("second draft");
        assertFalse(button(f.viewer, "previous").active); assertFalse(button(f.viewer, "next").active);
        f.viewer.setFocused(null);
        for (int i = 0; i <= order.size(); i++) {
            assertTrue(f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
            assertSame(order.get(i % order.size()), f.viewer.getFocused(), "forward step " + i);
        }
        f.viewer.setFocused(null);
        for (int i = 0; i <= order.size(); i++) {
            assertTrue(f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT));
            assertSame(order.get(order.size() - 1 - i % order.size()), f.viewer.getFocused(), "reverse step " + i);
        }
        assertEquals("first draft", inputs.get(0).getValue()); assertEquals("second draft", inputs.get(1).getValue());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent); assertTrue(f.submits.isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void tabSkipsDisabledInputsAndButtonsAndInvisibleButtons(int width) throws Exception {
        var f = fixture(simple(false), "IDLE", false, record()); init(f.viewer, width);
        var refresh = button(f.viewer, "refresh"); var run = action(f.viewer); var back = button(f.viewer, "back");
        assertFalse(fields(f.viewer).getFirst().active); assertFalse(button(f.viewer, "submit").active);
        f.viewer.setFocused(refresh); f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); assertSame(run, f.viewer.getFocused());
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT); assertSame(refresh, f.viewer.getFocused());
        run.visible = false;
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); assertSame(back, f.viewer.getFocused());
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT); assertSame(refresh, f.viewer.getFocused());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent); assertTrue(f.submits.isEmpty());
    }

    @ParameterizedTest @CsvSource({"320, 257", "426, 257", "320, 32", "426, 32"})
    void focusedButtonActivationHonorsEligibilityAndExistingSubmitGuards(int width, int activationKey) throws Exception {
        for (String guard : List.of("disabled", "invisible", "removed", "paused", "running", "pending", "consumed")) {
            var f = fixture(simple(!guard.equals("disabled")), guard.equals("running") ? "RUNNING" : "IDLE", guard.equals("paused"), record());
            init(f.viewer, width);
            var control = button(f.viewer, "submit");
            if (guard.equals("invisible")) control.visible = false;
            if (guard.equals("removed")) init(f.viewer, width);
            if (guard.equals("pending")) { click(f.viewer, "refresh"); control = button(f.viewer, "submit"); }
            if (guard.equals("consumed")) { click(f.viewer, "submit"); f.deliver("ok", record("terminal_submit", text("ok"))); control = button(f.viewer, "submit"); }
            int before = f.sent.size(); int submissions = f.submits.size();
            f.viewer.setFocused(control); assertTrue(f.viewer.keyPressed(activationKey, 0, 0), guard);
            assertEquals(before, f.sent.size(), guard); assertEquals(submissions, f.submits.size(), guard);
        }
    }

    @ParameterizedTest @CsvSource({"320, 257", "426, 257", "320, 32", "426, 32"})
    void tabToActionButtonThenExplicitActivationUsesOnlyItsExistingClickRequest(int width, int activationKey) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        f.viewer.setFocused(fields(f.viewer).getFirst());
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        assertSame(action(f.viewer), f.viewer.getFocused());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent);
        f.viewer.keyPressed(activationKey, 0, 0);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.TERMINAL_CLICK), f.sent);
        assertTrue(f.submits.isEmpty());
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void focusedInputSpaceStillTypesAndEscapeStillReturnsToParent(int width) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        var navigation = new ArrayList<Screen>(); set(f.viewer, "navigator", (Consumer<Screen>)navigation::add);
        var field = fields(f.viewer).getFirst(); f.viewer.setFocused(field); field.moveCursorToEnd(false);
        f.viewer.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0); assertTrue(f.viewer.charTyped(' ', 0));
        assertEquals("initial ", field.getValue()); assertTrue(f.submits.isEmpty());
        assertTrue(f.viewer.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertEquals(List.of(get(f.viewer, "parent")), navigation);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent);
    }

    @ParameterizedTest @ValueSource(ints = {320, 426})
    void terminalForegroundMarksOnlyCurrentEligibleFocusedButtonWithoutMouseHover(int width) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        var graphics = new OutlineGraphics(); var field = fields(f.viewer).getFirst();
        f.viewer.setFocused(field); f.viewer.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.outlines.isEmpty());
        f.viewer.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0); var control = button(f.viewer, "submit");
        f.viewer.renderForeground(graphics, -100, -100, 0);
        assertEquals(List.of(new Outline(control.getX(), control.getY(), control.getWidth(), control.getHeight(), 0xffaaccee)), graphics.outlines);
        graphics.outlines.clear(); control.active = false; f.viewer.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.outlines.isEmpty());
        control.active = true; control.visible = false; f.viewer.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.outlines.isEmpty());
        control.visible = true; init(f.viewer, width); f.viewer.setFocused(control);
        f.viewer.renderForeground(graphics, -100, -100, 0); assertTrue(graphics.outlines.isEmpty());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent); assertTrue(f.submits.isEmpty());
    }

    private record Outline(int x, int y, int width, int height, int color) { }
    /** Replace only the GPU outline sink; the real terminal foreground chooses when and where to draw. */
    private static final class OutlineGraphics extends net.minecraft.client.gui.GuiGraphics {
        final List<Outline> outlines = new ArrayList<>();
        OutlineGraphics() { super(net.minecraft.client.Minecraft.getInstance(), null); }
        @Override public void renderOutline(int x, int y, int width, int height, int color) { outlines.add(new Outline(x, y, width, height, color)); }
    }

    @ParameterizedTest @CsvSource({"320, 69", "426, 69", "320, 82", "426, 82"})
    void focusedOrdinaryInventoryKeyTypesLocallyInsteadOfClosingTerminal(int width, int inventoryKey) throws Exception {
        var f = fixture(simple(true), "IDLE", false, record()); init(f.viewer, width);
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "keyInventory", new net.minecraft.client.KeyMapping("test.terminal.inventory", inventoryKey, "test.terminal"));
        set(net.minecraft.client.Minecraft.getInstance(), "options", options);
        var navigation = new ArrayList<Screen>(); set(f.viewer, "navigator", (Consumer<Screen>)navigation::add);
        var field = fields(f.viewer).getFirst();
        f.viewer.setFocused(field);
        assertSame(field, f.viewer.getFocused()); field.moveCursorToEnd(false);
        f.viewer.keyPressed(inventoryKey, 0, 0);
        assertTrue(navigation.isEmpty(), "focused ordinary inventory key must not navigate away from terminal");
        assertTrue(f.viewer.charTyped(Character.toLowerCase((char)inventoryKey), 0));
        assertEquals("initial" + Character.toLowerCase((char)inventoryKey), field.getValue());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent);
        assertTrue(f.submits.isEmpty());
        f.viewer.setFocused(null); f.viewer.keyPressed(inventoryKey, 0, 0);
        assertEquals(List.of(get(f.viewer, "parent")), navigation, "the same binding still closes after input loses focus");
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent);
    }

    @Test void unfocusedEnterDoesNotSubmitAndRetainsNativeInventoryShortcut() throws Exception {
        var f = fixture(simple(true), "IDLE", false, record());
        var options = allocate(net.minecraft.client.Options.class);
        set(options, "keyInventory", new net.minecraft.client.KeyMapping("test.terminal.inventory", GLFW.GLFW_KEY_ENTER, "test.terminal"));
        set(net.minecraft.client.Minecraft.getInstance(), "options", options);
        var navigation = new ArrayList<Screen>(); set(f.viewer, "navigator", (Consumer<Screen>)navigation::add);
        f.viewer.setFocused(null); f.viewer.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), f.sent);
        assertEquals(1, navigation.size(), "outside Input, vanilla's explicitly rebound inventory shortcut still works");
    }

    @Test void actualMenuEncodedOversizeFailurePreservesUntruncatedDraftAndManualRefreshRecovery() throws Exception {
        var f = fixture(simple(true), "IDLE", false, record());
        // ValueNbtCodec uses two bytes per UTF-16 char; stay below the editing ceiling, exceed wire bytes.
        String oversized = "界".repeat(13000);
        fields(f.viewer).getFirst().setValue(oversized);
        assertEquals(oversized, fields(f.viewer).getFirst().getValue());
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorWire.encodeValue(new TerminalSubmit(1, "4:root/5:input", oversized).encode(), LIMITS));
        set(f.viewer, "submitRequest", (Consumer<TerminalSubmit>)(submit -> {
            assertEquals(oversized, submit.text());
            f.sent.add(ComputerControlSession.Action.TERMINAL_SUBMIT);
            f.menu.terminalSubmit(submit.view(), submit.instance(), submit.text());
        }));
        click(f.viewer, "submit"); tick(f.viewer);
        assertEquals("invalid", f.menu.controlResponse().orElseThrow().code());
        assertFalse((boolean)get(f.viewer, "requestPending"));
        assertTrue((boolean)get(f.viewer, "clickLocked"));
        assertEquals(oversized, fields(f.viewer).getFirst().getValue());
        f.refresh(record()); assertEquals(oversized, fields(f.viewer).getFirst().getValue());
        assertTrue(button(f.viewer, "submit").active);
    }

    /** Only the external audio device is omitted; real mouse dispatch and widget callbacks remain intact. */
    private static final class SilentSounds extends net.minecraft.client.sounds.SoundManager {
        private SilentSounds() { super(null); }
        @Override public void play(net.minecraft.client.resources.sounds.SoundInstance sound) { }
    }

    private ComputerTerminalEditorScreen editor(ProgramWorkspace workspace, int width) throws Exception {
        var parent = screen(); var menu = (ComputerProgramMenu)get(parent, "menu");
        var editor = new ComputerTerminalEditorScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"), Component.empty(), parent, workspace, () -> {}, () -> {});
        init(editor, width); return editor;
    }
    static ProgramArchive textProgram() {
        var root = new ComputationGraph(Map.of(), Map.of("constant", new ComputationGraph.Constant(text("root-only"))), Map.of("root", "constant"));
        var entry = new ComputationGraph(Map.of("query", DataType.Scalar.TEXT, "category", DataType.Scalar.TEXT), Map.of("input", new ComputationGraph.Input("query")), Map.of("text", "input"));
        return new ProgramArchive(new ProgramDefinition(root, Map.of("query", new ProgramDefinition.Cell(DataType.Scalar.TEXT, text("old"))), List.of(), List.of(),
            Map.of("submit", new ProgramDefinition.Entry(entry, List.of(new ProgramDefinition.Write("query", "text")), List.of()))), Map.of());
    }
    private static TerminalDefinition simple(boolean enabled) {
        return new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Column(List.of("input", "button")),
            "input", new TerminalDefinition.Input(literal(text("initial")), literal(new DataValue.BooleanValue(enabled)), "submit", "query", literal(record("category", text("all")))),
            "button", new TerminalDefinition.Button(literal(text("Run")), literal(new DataValue.BooleanValue(true)), "submit", literal(record("query", text("button"), "category", text("all"))))));
    }
    private static TerminalDefinition repeated() {
        return new TerminalDefinition("rows", Map.of("rows", new TerminalDefinition.Repeat(path("memory", "rows"), "input"),
            "input", new TerminalDefinition.Input(path("row", "initial"), literal(new DataValue.BooleanValue(true)), "submit", "query", path("row", "context"))));
    }
    private static DataValue.RecordValue rows(String category) {
        var rows = new ArrayList<DataValue>();
        for (int i = 0; i < 12; i++) rows.add(record("initial", text("initial " + (i == 0 ? category : i)), "context", record("category", text(i == 0 ? category : "row" + i))));
        return record("rows", new DataValue.ListValue(rows));
    }
    private Fixture fixture(TerminalDefinition definition, String phase, boolean paused, DataValue.RecordValue memory) throws Exception {
        var f = new Fixture(definition, phase, paused, memory); init(f.viewer, 320); tick(f.viewer); f.deliver("ok", f.envelope()); bounds(f.viewer); return f;
    }
    private final class Fixture {
        final ComputerProgramMenu menu; final ComputerTerminalScreen viewer; final UUID nonce = UUID.randomUUID();
        final List<ComputerControlSession.Action> sent = new ArrayList<>(); final List<TerminalSubmit> submits = new ArrayList<>();
        TerminalDefinition definition; final String phase; final boolean paused; DataValue.RecordValue memory;
        Fixture(TerminalDefinition definition, String phase, boolean paused, DataValue.RecordValue memory) throws Exception {
            this.definition = definition; this.phase = phase; this.paused = paused; this.memory = memory;
            var parent = screen(); menu = (ComputerProgramMenu)get(parent, "menu"); set(menu, "nonce", nonce);
            set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 7, "ready", "", new ResourceLimits(1000, 1000000, 1), LIMITS, new byte[0])));
            viewer = new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"), Component.empty(), parent);
            set(viewer, "request", (Consumer<ComputerControlSession.Action>)this::sent);
            set(viewer, "submitRequest", (Consumer<TerminalSubmit>)(submit -> { submits.add(submit); sent(ComputerControlSession.Action.TERMINAL_SUBMIT); }));
            set(viewer, "clickRequest", (BiConsumer<Long, String>)((view, instance) -> sent(ComputerControlSession.Action.TERMINAL_CLICK)));
        }
        void sent(ComputerControlSession.Action action) {
            sent.add(action); try { set(menu, "latestControlIntent", (long)sent.size()); set(menu, "latestControlAction", action); } catch (Exception error) { throw new AssertionError(error); }
        }
        DataValue.RecordValue envelope() { return record("terminal", TerminalCodec.encode(definition), "memory", memory, "output", DataValue.NullValue.INSTANCE, "phase", text(phase), "paused", new DataValue.BooleanValue(paused)); }
        void deliver(String code, DataValue.RecordValue value) throws Exception {
            menu.accept(new ProgramStatusResponse(0, nonce, sent.size(), 7, code, "", ProgramEditorWire.encodeValue(value, LIMITS))); tick(viewer);
        }
        void refresh(DataValue.RecordValue memory) throws Exception { this.memory = memory; click(viewer, "refresh"); deliver("ok", envelope()); }
    }
    private static void page(ComputerTerminalScreen viewer, int index) throws Exception {
        if (index == 1) { @SuppressWarnings("unchecked") var next = (Map<String, CreateTextButton>)get(viewer, "pageNext"); next.values().iterator().next().onClick(0, 0); }
        else viewer.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b -> b.getMessage().getString().equals("<")).findFirst().orElseThrow().onClick(0, 0);
    }
    private static List<EditBox> fields(ComputerTerminalScreen viewer) { return viewer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).toList(); }
    private static CreateTextButton action(ComputerTerminalScreen viewer) { return viewer.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast).filter(b -> b.getMessage().getString().equals("Run")).findFirst().orElseThrow(); }
    private static void tick(ComputerTerminalScreen viewer) throws Exception { var method = ComputerTerminalScreen.class.getDeclaredMethod("containerTick"); method.setAccessible(true); method.invoke(viewer); }
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static TerminalBinding literal(DataValue value) { return new TerminalBinding.Literal(value); }
    private static TerminalBinding path(String root, String key) { return new TerminalBinding.Path(new ValuePath(root, List.of()).key(key)); }
    private static DataValue.RecordValue record(Object... pairs) { var result = new LinkedHashMap<String, DataValue>(); for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], (DataValue)pairs[i + 1]); return new DataValue.RecordValue(result); }
}
