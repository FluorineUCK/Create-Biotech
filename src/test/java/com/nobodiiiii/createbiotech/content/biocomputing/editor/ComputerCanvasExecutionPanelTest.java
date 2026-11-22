package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ComputerCanvasExecutionPanelTest {
    @Test void flowSnapshotDisablesLegacyEventTriggersButKeepsResumeAndStep() {
        var panel = new ComputerCanvasExecutionPanel(new Metrics(), Component.empty(), action -> {}, () -> {});
        panel.layout(0, 0, 240, 220);
        panel.accept(reply("ok", 3), Optional.of(new DataValue.RecordValue(Map.of(
            "phase", new DataValue.TextValue("WAITING_READ"), "mode", new DataValue.TextValue("flow")))));
        assertFalse(button(panel, "debug.run").active); assertFalse(button(panel, "debug.start_paused").active);
        assertTrue(button(panel, "debug.resume").active); assertTrue(button(panel, "debug.step").active);
        panel.request(ComputerControlSession.Action.REFRESH);
        assertFalse(button(panel, "debug.run").active, "Pending refresh must not re-enable known event-only controls");
        panel.accept(reply("ok", 4), Optional.of(status("event")));
        assertTrue(button(panel, "debug.run").active);
    }
    private final ComputerProgramScreenTest fixture = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { fixture.client(); }
    @AfterEach void restore() throws Exception { fixture.restore(); }

    @Test void realRunControlDisplaysActualInstalledComputerResultWithoutInstallingDrafts() {
        var limits = new ComputerHostLimits(new ResourceLimits(100, 100_000, 100), new ArchiveLimits(10_000, 1_000_000, 10_000_000));
        var graph = new ComputationGraph(Map.of("input", DataType.Scalar.INTEGER), Map.of(
            "value", new ComputationGraph.Input("input"), "one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
            "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "value", "one")), Map.of("answer", "sum"));
        var host = new ComputerBlockHost(() -> {});
        var installed = new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), Map.of());
        host.install(installed, ComputerRuntime.MemoryPolicy.RESET, limits, true);
        var inputs = new DataValue.RecordValue(Map.of("input", new DataValue.IntegerValue(16)));
        var actions = new ArrayList<ComputerControlSession.Action>();
        var panel = new ComputerCanvasExecutionPanel(new ComputerProgramScreenTest.Metrics(), Component.literal("Computer"), action -> {
            actions.add(action);
            if (action == ComputerControlSession.Action.RUN) { host.begin(inputs, limits, true); host.tick(limits, true); }
        }, () -> fail("Run must not navigate to another screen"));
        panel.layout(20, 40, 220, 200);
        button(panel, "debug.run").onClick(0, 0);
        assertEquals(List.of(ComputerControlSession.Action.RUN), actions);
        assertEquals(new DataValue.IntegerValue(17), host.snapshot().orElseThrow().machine().output().orElseThrow().field("answer"));
        assertTrue(panel.page().lines().isEmpty(), "Old values disappear while awaiting the response");
        panel.accept(new ProgramStatusResponse(1, UUID.randomUUID(), 1, 4, "ok", "", new byte[0]),
            Optional.of(ComputerStatusView.of(host.snapshot().orElseThrow())));
        var lines = new StringBuilder();
        while (true) {
            lines.append(String.join("", panel.page().lines()));
            var next = literal(panel, ">"); if (!next.active) break; next.onClick(0, 0);
        }
        assertTrue(lines.toString().contains("SUCCEEDED"));
        assertTrue(lines.toString().contains("\"answer\":17"));
        assertTrue(lines.toString().contains("revision: 4"));
    }

    @Test void everyPublicActionIsExplicitAndRefreshFailuresNeverKeepOldOutput() {
        var actions = new ArrayList<ComputerControlSession.Action>(); var edits = new int[1];
        var panel = new ComputerCanvasExecutionPanel(new Metrics(), Component.literal("Computer"), actions::add, () -> edits[0]++);
        panel.layout(0, 0, 206, 190);
        for (var action : List.of(ComputerControlSession.Action.REFRESH, ComputerControlSession.Action.RUN,
            ComputerControlSession.Action.START_PAUSED, ComputerControlSession.Action.PAUSE, ComputerControlSession.Action.RESUME, ComputerControlSession.Action.STEP)) {
            panel.accept(reply("ok", 1), Optional.of(status("old output")));
            assertFalse(panel.page().lines().isEmpty());
            button(panel, "debug." + action.name().toLowerCase(Locale.ROOT)).onClick(0, 0);
            assertEquals(action, actions.getLast()); assertTrue(panel.page().lines().isEmpty());
        }
        assertEquals(6, actions.size());
        panel.accept(reply("too_large", 1), Optional.empty()); assertTrue(panel.page().lines().isEmpty());
        panel.accept(reply("ok", 1), Optional.of(new DataValue.RecordValue(Map.of("periodic", DataValue.NullValue.INSTANCE))));
        assertTrue(panel.page().lines().isEmpty(), "Dedicated settings are not an execution snapshot");
        button(panel, "editor.edit_values").onClick(0, 0); assertEquals(1, edits[0]); assertEquals(6, actions.size());
    }

    @Test void fullUnicodeOutputIsPagedWithoutLossAndResizeClampsRatherThanMutatingData() {
        var panel = new ComputerCanvasExecutionPanel(new Metrics(), Component.literal("Computer"), action -> fail(), () -> {});
        String original = "文😀".repeat(700) + "TAIL";
        var value = status(original); panel.layout(5, 7, 206, 150); panel.accept(reply("ok", 7), Optional.of(value));
        var visible = new StringBuilder(); int pages = panel.page().count(); assertTrue(pages > 1);
        for (int i = 0; i < pages; i++) {
            for (String line : panel.page().lines()) {
                assertFalse(Character.isLowSurrogate(line.charAt(0)));
                assertFalse(Character.isHighSurrogate(line.charAt(line.length() - 1)));
                visible.append(line);
            }
            if (literal(panel, ">").active) literal(panel, ">").onClick(0, 0);
        }
        assertTrue(visible.toString().contains(original)); assertTrue(literal(panel, "<").active);
        panel.layout(5, 7, 220, 240); assertTrue(panel.page().index() < panel.page().count());
        panel.accept(reply("ok", 8), Optional.of(status("short"))); assertEquals(0, panel.page().index());
        assertEquals(new DataValue.TextValue(original), value.field("output"));
        for (var a : panel.widgets()) {
            assertTrue(a.getX() >= 5 && a.getY() >= 7);
            assertTrue(a.getX() + a.getWidth() <= 225 && a.getY() + a.getHeight() <= 247);
            for (var b : panel.widgets()) if (a != b)
                assertFalse(a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX()
                    && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY());
        }
    }

    @Test void canvasToolbarStatusAndInlineDraftCoexistWithoutRebuildingOnReply() throws Exception {
        var screen = screen(); var menu = (ComputerProgramMenu)get(screen, "menu");
        var nonce = UUID.randomUUID(); var limits = new ArchiveLimits(10_000, 1_000_000, 10_000_000);
        set(menu, "nonce", nonce); set(menu, "containerId", 1); set(menu, "latestControlIntent", 2L);
        set(menu, "response", Optional.of(new ProgramEditorResponse(1, nonce, 1, 4, "ready", "", ProgramDraftTest.ROOM, limits, new byte[0])));
        set(menu, "controlResponse", Optional.empty()); set(menu, "status", Optional.empty());
        var inputs = new DataValue.RecordValue(Map.of("saved", new DataValue.IntegerValue(42)));
        menu.rememberDebuggerInputs(inputs); set(screen, "requested", true);
        var actions = new ArrayList<ComputerControlSession.Action>();
        var monitor = new ComputerCanvasExecutionPanel(new Metrics(), Component.literal("Computer"), actions::add, () -> {});
        set(screen, "execution", monitor); screen.init();
        var draft = (ProgramDraft)get(screen, "draft");
        var id = draft.add(ProgramDraftTest.number(5), 20, 20); draft.output("answer", id);
        editorButton(screen, "debugger").onClick(0, 0);
        assertEquals(List.of(ComputerControlSession.Action.REFRESH), actions);
        assertTrue(screen.children().contains(button(monitor, "debug.run")));
        var m = ComputerProgramScreen.class.getDeclaredMethod("canvasWidth"); m.setAccessible(true);
        assertTrue((int)m.invoke(screen) < 400);
        ComputerCanvasPortsTest.press(screen, 25, 44, 0);
        var inline = get(screen, "inline"); var box = (net.minecraft.client.gui.components.EditBox)get(inline, "field");
        box.setValue("unfinished");
        var refresh = button(monitor, "debug.refresh"); screen.mouseClicked(refresh.getX() + 2, refresh.getY() + 2, 0);
        assertSame(inline, get(screen, "inline")); assertEquals("unfinished", box.getValue());
        byte[] bytes = ProgramEditorWire.encodeValue(status("current"), limits);
        menu.accept(new ProgramStatusResponse(1, nonce, 1, 4, "ok", "", bytes)); screen.containerTick();
        assertTrue(monitor.page().lines().isEmpty(), "Old control intent rejected by real menu");
        menu.accept(new ProgramStatusResponse(1, nonce, 2, 4, "ok", "", bytes)); screen.containerTick();
        assertFalse(monitor.page().lines().isEmpty()); assertSame(inline, get(screen, "inline"));
        assertEquals("unfinished", box.getValue()); assertEquals(inputs, menu.debuggerInputs());
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(5)), draft.node(id).definition());
        set(menu, "latestControlIntent", 3L);
        menu.accept(new ProgramStatusResponse(1, nonce, 3, 3, "ok", "", bytes)); screen.containerTick();
        assertTrue(monitor.page().lines().isEmpty(), "An older installed revision must not be current canvas status");
    }

    @Test void narrowCanvasKeepsFullDebuggerAndNoInvisiblePanelHitArea() throws Exception {
        var screen = screen(); set(screen, "width", 320); set(screen, "height", 240);
        var opened = new net.minecraft.client.gui.screens.Screen[1];
        set(screen, "navigator", (java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)(s -> opened[0] = s));
        screen.init(); editorButton(screen, "debugger").onClick(0, 0);
        assertInstanceOf(ComputerExecutionScreen.class, opened[0]);
        assertFalse((boolean)get(screen, "executionOpen"));
    }

    private static CreateTextButton editorButton(ComputerProgramScreen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.editor." + key)).findFirst().orElseThrow();
    }
    private static ProgramStatusResponse reply(String code, long revision) {
        return new ProgramStatusResponse(1, UUID.randomUUID(), 1, revision, code, "", new byte[0]);
    }
    private static DataValue.RecordValue status(String value) {
        return new DataValue.RecordValue(Map.of("phase", new DataValue.TextValue("SUCCEEDED"), "output", new DataValue.TextValue(value)));
    }

    static CreateTextButton button(ComputerCanvasExecutionPanel panel, String key) {
        return panel.widgets().stream().filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
            && t.getKey().equals("create_biotech.biocomputing." + key)).findFirst().orElseThrow();
    }
    static CreateTextButton literal(ComputerCanvasExecutionPanel panel, String text) {
        return panel.widgets().stream().filter(b -> b.getMessage().getString().equals(text)).findFirst().orElseThrow();
    }
}
