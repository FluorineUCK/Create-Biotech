package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.TerminalControlsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.EventEntryFixtures.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class TerminalButtonWidgetsTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); TerminalBehaviourTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426})
    void editorCreatesAllButtonFieldsAndInstalledControlSendsOnlyViewAndInstance(int width) throws Exception {
        var parent = screen(); var opened = new Screen[1];
        set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); TerminalControlsTest.click(parent, "edit");
        var editor = assertInstanceOf(ComputerTerminalEditorScreen.class, opened[0]); init(editor, width);
        click(editor, "add.button"); String id = (String)get(editor, "selected");
        ((EditBox)get(editor, "literal")).setValue("Add nine");
        ((EditBox)get(editor, "entry")).setValue("message"); click(editor, "configure");
        click(editor, "button_slot"); // enabled
        ((EditBox)get(editor, "literal")).setValue("true"); click(editor, "configure");
        click(editor, "button_slot"); // inputs
        ((EditBox)get(editor, "literal")).setValue("{\"amount\":9}"); click(editor, "configure");
        var draft = (TerminalDraft)get(editor, "draft");
        var button = assertInstanceOf(TerminalDefinition.Button.class, draft.snapshot().nodes().get(id));
        assertEquals("message", button.entry());
        assertEquals(new DataValue.RecordValue(Map.of("amount", new DataValue.IntegerValue(9))),
            ((TerminalBinding.Literal)button.inputs()).value());
        bounds(editor);

        var wired = ProgramDocumentWire.decode(ProgramDocumentWire.encode(
            new ProgramDocument(program(false), Map.of(), Optional.of(draft.snapshot())), ARCHIVE), ARCHIVE);
        var hostBlock = new TerminalBehaviourTest.Harness(); var host = hostBlock.getBehaviour(ComputerBehaviour.TYPE);
        host.installDocument(wired, ComputerRuntime.MemoryPolicy.RESET);
        var serverNonce = UUID.randomUUID(); var serverSession = new ComputerControlSession(serverNonce); long serverRevision = host.programRevision();
        serverSession.apply(serverNonce, 1, serverRevision, ComputerControlSession.Action.READ_TERMINAL, () -> EMPTY, host).orElseThrow();
        assertEquals("ok", serverSession.apply(serverNonce, 2, serverRevision, ComputerControlSession.Action.TERMINAL_CLICK,
            () -> new TerminalClick(1, "4:root/" + id.length() + ":" + id).encode(), host).orElseThrow().code());
        for (int i = 0; i < 100 && host.snapshot().orElseThrow().machine().status() == ComputationMachine.Status.RUNNING; i++) host.step();
        assertEquals(integer(9), host.snapshot().orElseThrow().machine().memory().field("n"));

        var menu = (ComputerProgramMenu)get(parent, "menu"); var nonce = UUID.randomUUID(); var limits = new ArchiveLimits(10000, 1000000, 10000000);
        set(menu, "nonce", nonce); set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 7, "ready", "",
            new ResourceLimits(1000, 1000000, 1), limits, new byte[0])));
        var viewer = new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), editor);
        var reads = new ArrayList<ComputerControlSession.Action>(); set(viewer, "request", (Consumer<ComputerControlSession.Action>)reads::add);
        var clicks = new ArrayList<TerminalClick>(); set(viewer, "clickRequest", (BiConsumer<Long, String>)((view, instance) -> clicks.add(new TerminalClick(view, instance))));
        init(viewer, width); set(menu, "latestControlIntent", 1L); set(menu, "latestControlAction", ComputerControlSession.Action.READ_TERMINAL);
        var envelope = new DataValue.RecordValue(Map.of("terminal", TerminalCodec.encode(draft.snapshot()), "memory", new DataValue.RecordValue(Map.of()),
            "output", DataValue.NullValue.INSTANCE, "phase", new DataValue.TextValue("IDLE"), "paused", new DataValue.BooleanValue(false)));
        menu.accept(new ProgramStatusResponse(0, nonce, 1, 7, "ok", "", ProgramEditorWire.encodeValue(envelope, limits)));
        tickViewer(viewer); bounds(viewer);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), reads);
        var actual = viewer.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(widget -> widget.getMessage().getString().equals("Add nine")).findFirst().orElseThrow();
        assertTrue(actual.active); actual.onClick(0, 0);
        assertEquals(List.of(new TerminalClick(1, "4:root/" + id.length() + ":" + id)), clicks);
        assertTrue((boolean)get(viewer, "clickLocked"));
    }

    @Test void oversizedValidInstanceIsReportedInsteadOfEscapingTheScreenCallback() throws Exception {
        var parent = screen(); var menu = (ComputerProgramMenu)get(parent, "menu");
        var viewer = new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"),
            net.minecraft.network.chat.Component.empty(), parent);
        set(viewer, "publishedView", 1L); set(viewer, "clickLocked", false);
        set(viewer, "clickRequest", (BiConsumer<Long, String>)((view, instance) -> new TerminalClick(view, instance)));
        var click = ComputerTerminalScreen.class.getDeclaredMethod("click", String.class); click.setAccessible(true);
        assertDoesNotThrow(() -> {
            try { click.invoke(viewer, "x".repeat(TerminalClick.MAX_INSTANCE + 1)); }
            catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
        });
        assertTrue((boolean)get(viewer, "clickLocked"));
    }

    private static void tickViewer(ComputerTerminalScreen screen) throws Exception {
        var method = ComputerTerminalScreen.class.getDeclaredMethod("containerTick"); method.setAccessible(true); method.invoke(screen);
    }
}
