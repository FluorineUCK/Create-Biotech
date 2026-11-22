package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.*;
import org.lwjgl.glfw.GLFW;

/** Headless GUI regressions use the real widget tree and replace only its network boundary. */
class TerminalInputWidgetsTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426})
    void typingIsLocalAndEnterOrSubmitSendTheSameBoundedPayloadAtBothSupportedSizes(int width) throws Exception {
        var parent = screen(); var menu = (ComputerProgramMenu)get(parent, "menu"); var nonce = UUID.randomUUID();
        var limits = new ArchiveLimits(10_000, 1_000_000, 10_000_000);
        set(menu, "nonce", nonce); set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 7, "ready", "",
            new ResourceLimits(1000, 1_000_000, 1), limits, new byte[0])));
        var viewer = new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), parent);
        var reads = new ArrayList<ComputerControlSession.Action>(); var submits = new ArrayList<TerminalSubmit>();
        set(viewer, "request", (Consumer<ComputerControlSession.Action>)reads::add);
        set(viewer, "submitRequest", (Consumer<TerminalSubmit>)submits::add);
        init(viewer, width); set(menu, "latestControlIntent", 1L); set(menu, "latestControlAction", ComputerControlSession.Action.READ_TERMINAL);
        menu.accept(new ProgramStatusResponse(0, nonce, 1, 7, "ok", "", ProgramEditorWire.encodeValue(envelope(), limits)));
        tick(viewer); bounds(viewer);

        var field = viewer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        field.setValue("typed locally");
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), reads, "the responder never sends per keystroke");
        assertTrue(submits.isEmpty());
        init(viewer, width == 320 ? 426 : 320); bounds(viewer);
        field = viewer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        assertEquals("typed locally", field.getValue(), "resize rebuild retains a local edit in the same frozen context");
        viewer.setFocused(field);
        assertTrue(viewer.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "typed locally")), submits);
        assertFalse(button(viewer, "refresh").active, "a pending submit blocks a refresh supersession");

        set(viewer, "requestPending", false); set(viewer, "clickLocked", false); set(viewer, "publishedView", 1L);
        init(viewer, width); field = viewer.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        field.setValue("clicked"); button(viewer, "submit").onClick(0, 0);
        assertEquals(List.of(new TerminalSubmit(1, "4:root/5:input", "typed locally"), new TerminalSubmit(1, "4:root/5:input", "clicked")), submits);
    }

    @Test void changedFrozenInputContextForgetsTheOldDraftEvenIfTheOldContextReturns() {
        var drafts = new TerminalInputDrafts();
        var oldContext = new DataValue.RecordValue(Map.of("query", new DataValue.TextValue("old"), "category", new DataValue.TextValue("one")));
        var changedContext = new DataValue.RecordValue(Map.of("query", new DataValue.TextValue("replacement"), "category", new DataValue.TextValue("two")));
        drafts.update("input", "4:root/5:input[0]", oldContext, "unsent old row text");
        assertEquals("replacement initial", drafts.value("input", "4:root/5:input[0]", changedContext, "replacement initial"));
        assertEquals("old initial", drafts.value("input", "4:root/5:input[0]", oldContext, "old initial"), "observing another context invalidates the draft even without typing");
        drafts.update("input", "4:root/5:input[0]", changedContext, "new row text");
        assertEquals("old initial", drafts.value("input", "4:root/5:input[0]", oldContext, "old initial"), "stale context must not resurrect its edit");
    }

    @Test void editorConfiguresAllFiveInputPropertiesWithoutReplacingOtherBindingsThenWiresTheDocument() throws Exception {
        var parent = screen(); var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(parent, "navigator", (Consumer<net.minecraft.client.gui.screens.Screen>)(next -> opened[0] = next)); click(parent, "edit");
        var editor = assertInstanceOf(ComputerTerminalEditorScreen.class, opened[0]); init(editor, 320); click(editor, "add.input"); String id = (String)get(editor, "selected");
        ((EditBox)get(editor, "literal")).setValue("initial text"); ((EditBox)get(editor, "entry")).setValue("submit"); ((EditBox)get(editor, "argument")).setValue("query"); click(editor, "configure");
        click(editor, "input_slot"); ((EditBox)get(editor, "literal")).setValue("false"); click(editor, "configure");
        click(editor, "input_slot"); ((EditBox)get(editor, "literal")).setValue("{\"query\":\"old\",\"category\":\"all\"}"); click(editor, "configure");
        var input = assertInstanceOf(TerminalDefinition.Input.class, ((TerminalDraft)get(editor, "draft")).snapshot().nodes().get(id));
        assertEquals("submit", input.entry()); assertEquals("query", input.argument());
        assertEquals(new DataValue.TextValue("initial text"), ((TerminalBinding.Literal)input.initial()).value());
        assertEquals(new DataValue.BooleanValue(false), ((TerminalBinding.Literal)input.enabled()).value());
        var limits = new ArchiveLimits(10_000, 1_000_000, 10_000_000);
        var document = new ProgramDocument(EventEntryFixtures.program(false), Map.of(), Optional.of(((TerminalDraft)get(editor, "draft")).snapshot()));
        assertEquals(input, assertInstanceOf(TerminalDefinition.Input.class, ProgramDocumentWire.decode(ProgramDocumentWire.encode(document, limits), limits).terminal().orElseThrow().nodes().get(id)));
    }

    private static DataValue.RecordValue envelope() {
        var input = new TerminalDefinition.Input(new TerminalBinding.Literal(new DataValue.TextValue("initial")),
            new TerminalBinding.Literal(new DataValue.BooleanValue(true)), "submit", "query",
            new TerminalBinding.Literal(new DataValue.RecordValue(Map.of("query", new DataValue.TextValue("old"), "category", new DataValue.TextValue("all")))));
        var definition = new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Column(List.of("input")), "input", input));
        return new DataValue.RecordValue(Map.of("terminal", TerminalCodec.encode(definition), "memory", new DataValue.RecordValue(Map.of()),
            "output", DataValue.NullValue.INSTANCE, "phase", new DataValue.TextValue("IDLE"), "paused", new DataValue.BooleanValue(false)));
    }
    private static void tick(ComputerTerminalScreen screen) throws Exception {
        var method = ComputerTerminalScreen.class.getDeclaredMethod("containerTick"); method.setAccessible(true); method.invoke(screen);
    }
}
