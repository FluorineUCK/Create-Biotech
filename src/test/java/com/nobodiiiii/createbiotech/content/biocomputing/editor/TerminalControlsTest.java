package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class TerminalControlsTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap() throws Exception { ComputerProgramScreenTest.bootstrap(); TerminalBehaviourTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restore() throws Exception { environment.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426})
    void realEditorCallbacksCreateBindingsTemplatesAndInstallTheirResult(int width) throws Exception {
        var parent = screen(); var workspace = new ProgramWorkspace(ProgramDocument.of(TerminalBehaviourTest.counter()));
        set(parent, "workspace", workspace); set(parent, "draft", workspace.root()); parent.init();
        assertTrue(workspace.terminal().isEmpty());
        var opened = new Screen[1]; set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        click(parent, "edit");
        var editor = assertInstanceOf(ComputerTerminalEditorScreen.class, opened[0]);
        init(editor, width); set(editor, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        bounds(editor); click(editor, "add.column"); String column = (String)get(editor, "selected");
        click(editor, "add.text"); String text = (String)get(editor, "selected");
        click(editor, "bind");
        var browser = assertInstanceOf(ComputerValueTreeScreen.class, opened[0]); init(browser, width);
        @SuppressWarnings("unchecked") var expand = (List<CreateTextButton>)get(browser, "expandButtons"); expand.getFirst().onClick(0, 0);
        @SuppressWarnings("unchecked") var rows = (List<CreateTextButton>)get(browser, "rowButtons");
        rows.stream().filter(b -> b.getMessage().getString().contains("count")).findFirst().orElseThrow().onClick(0, 0);
        click(browser, "select_binding"); assertSame(editor, opened[0]);
        var draft = (TerminalDraft)get(editor, "draft");
        assertEquals(new TerminalBinding.Path(new ValuePath("memory", List.of()).key("count")), ((TerminalDefinition.Text)draft.snapshot().nodes().get(text)).value());
        select(editor, column); click(editor, "add.repeat"); String repeat = (String)get(editor, "selected");
        ((EditBox)get(editor, "literal")).setValue("[1,2,3,4,5,6,7,8,9,10,11,12]"); click(editor, "configure");
        String template = ((TerminalDefinition.Repeat)draft.snapshot().nodes().get(repeat)).template();
        select(editor, template); click(editor, "add.text");
        ((EditBox)get(editor, "literal")).setValue("row label"); click(editor, "configure");
        select(editor, repeat); click(editor, "up");
        assertEquals(repeat, ((TerminalDefinition.Column)draft.snapshot().nodes().get(column)).children().getFirst());
        select(editor, column); click(editor, "add.item"); String removed = (String)get(editor, "selected"); click(editor, "remove");
        assertFalse(draft.snapshot().nodes().containsKey(removed));
        init(editor, width == 320 ? 426 : 320); bounds(editor);
        assertTrue(workspace.terminal().isEmpty()); click(editor, "apply");
        assertEquals(draft.snapshot(), workspace.terminal().orElseThrow()); assertFalse((boolean)get(parent, "requested"));

        var block = new TerminalBehaviourTest.Harness(); var computer = block.getBehaviour(ComputerBehaviour.TYPE);
        var limits = new ArchiveLimits(100000, 1000000, 10000000);
        computer.installDocument(ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(), limits), limits), ComputerRuntime.MemoryPolicy.RESET);
        computer.begin(new DataValue.RecordValue(Map.of())); for (int i = 0; i < 30; i++) block.tick();
        click(editor, "installed"); var viewer = assertInstanceOf(ComputerTerminalScreen.class, opened[0]);
        var requests = new ArrayList<ComputerControlSession.Action>(); set(viewer, "request", (Consumer<ComputerControlSession.Action>)requests::add);
        set(viewer, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); init(viewer, width);
        var menu = (ComputerProgramMenu)get(parent, "menu"); var nonce = UUID.randomUUID();
        set(menu, "nonce", nonce); set(menu, "latestControlIntent", 1L); set(menu, "latestControlAction", ComputerControlSession.Action.READ_TERMINAL);
        set(menu, "controlResponse", Optional.empty());
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, computer.programRevision(), "ready", "", new ResourceLimits(1000, 1000000, 1), limits, new byte[0])));
        var answer = new ComputerControlSession(nonce).apply(nonce, 1, computer.programRevision(), ComputerControlSession.Action.READ_TERMINAL,
            () -> { throw new AssertionError("Terminal read decoded inputs"); }, computer).orElseThrow();
        menu.accept(ProgramStatusResponse.fromAnswer(0, nonce, 1, answer, limits)); tick(viewer);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), requests);
        var beforeUi = computer.snapshot();
        var projected = (TerminalProjection.View)get(viewer, "view");
        var pending = new ArrayDeque<TerminalProjection.View>(); pending.add(projected);
        boolean found = false;
        while (!pending.isEmpty()) { var current = pending.removeFirst(); pending.addAll(current.children());
            if (current.node().equals(text)) { assertEquals(new DataValue.IntegerValue(1), current.value().orElseThrow()); found = true; } }
        assertTrue(found);
        while (button(viewer, "next").active) click(viewer, "next"); bounds(viewer);
        var rawButton = viewer.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(button -> button.getMessage().getString().equals("1")).findFirst().orElseThrow(); rawButton.onClick(0, 0);
        var inspector = assertInstanceOf(ComputerValueTreeScreen.class, opened[0]); init(inspector, width);
        assertNull(get(inspector, "generate")); assertEquals(new DataValue.IntegerValue(1), ((ValueTree)get(inspector, "tree")).inputs().field("value"));
        inspector.onClose(); assertSame(viewer, opened[0]); click(viewer, "back"); assertSame(editor, opened[0]);
        assertEquals(beforeUi, computer.snapshot());
        click(parent, "edit"); var cancelled = assertInstanceOf(ComputerTerminalEditorScreen.class, opened[0]); init(cancelled, width);
        click(cancelled, "add.text"); click(cancelled, "cancel");
        assertEquals(draft.snapshot(), workspace.terminal().orElseThrow());
    }

    @Test void emptyRepeatStillAllowsRealRowPathStepsAndGridColumnsCanBeConfigured() throws Exception {
        var parent = screen(); var opened = new Screen[1];
        set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); click(parent, "edit");
        var editor = (ComputerTerminalEditorScreen)opened[0]; init(editor, 320);
        click(editor, "add.grid"); String grid = (String)get(editor, "selected");
        ((EditBox)get(editor, "columns")).setValue("3"); click(editor, "configure");
        var draft = (TerminalDraft)get(editor, "draft"); assertEquals(3, ((TerminalDefinition.Grid)draft.snapshot().nodes().get(grid)).columns());
        click(editor, "add.repeat"); String repeat = (String)get(editor, "selected");
        select(editor, ((TerminalDefinition.Repeat)draft.snapshot().nodes().get(repeat)).template()); click(editor, "add.text");
        click(editor, "path_root"); click(editor, "path_root");
        ((EditBox)get(editor, "literal")).setValue("products"); click(editor, "path_key");
        ((EditBox)get(editor, "literal")).setValue("2"); click(editor, "path_index");
        String text = (String)get(editor, "selected");
        assertEquals(new TerminalBinding.Path(new ValuePath("row", List.of()).key("products").index(2)), ((TerminalDefinition.Text)draft.snapshot().nodes().get(text)).value());
        click(editor, "path_parent");
        assertEquals(new TerminalBinding.Path(new ValuePath("row", List.of()).key("products")), ((TerminalDefinition.Text)draft.snapshot().nodes().get(text)).value());
        bounds(editor);
    }

    @Test void ordinaryTextDoesNotRequireJsonQuotesAndAdvancedJsonIsExplicit() throws Exception {
        var parent = screen(); var opened = new Screen[1];
        set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); click(parent, "edit");
        var editor = (ComputerTerminalEditorScreen)opened[0]; init(editor, 320); click(editor, "add.text");
        String id = (String)get(editor, "selected"); String literal = "中文 null [不是 JSON] \"文字\"";
        ((EditBox)get(editor, "literal")).setValue(literal); click(editor, "configure");
        var draft = (TerminalDraft)get(editor, "draft");
        assertEquals(new TerminalBinding.Literal(new DataValue.TextValue(literal)), ((TerminalDefinition.Text)draft.snapshot().nodes().get(id)).value());
        click(editor, "literal_mode"); ((EditBox)get(editor, "literal")).setValue("null"); click(editor, "configure");
        assertEquals(new TerminalBinding.Literal(DataValue.NullValue.INSTANCE), ((TerminalDefinition.Text)draft.snapshot().nodes().get(id)).value());
        init(editor, 426); bounds(editor); assertEquals("null", ((EditBox)get(editor, "literal")).getValue());
    }

    @Test void rowAndGridPlacementHasColumnsAndUnknownItemsRemainDiagnostic() {
        var value = new TerminalBinding.Literal(new DataValue.TextValue("value"));
        var nodes = Map.<String, TerminalDefinition.Node>of("root", new TerminalDefinition.Grid(2, List.of("a", "b", "c")),
            "a", new TerminalDefinition.Text(value), "b", new TerminalDefinition.Text(value), "c", new TerminalDefinition.Text(value));
        var projected = TerminalProjection.project(new TerminalDefinition("root", nodes), new DataValue.RecordValue(Map.of()), Map.of(), 2, 20);
        var layout = TerminalLayout.layout(projected, 240); var cells = layout.cells();
        assertEquals(3, cells.size()); assertEquals(0, cells.get(0).x()); assertEquals(0, cells.get(0).y());
        assertTrue(cells.get(1).x() > 0); assertEquals(0, cells.get(1).y()); assertEquals(24, cells.get(2).y());
        assertEquals(Optional.of("unknown_item"), TerminalItemDisplay.resolve(new DataValue.RecordValue(Map.of(
            "item", new DataValue.TextValue("unknown:terminal_missing"), "count", new DataValue.IntegerValue(1)))).problem());
    }

    @Test void installedScreenPagesAndReturnsWithoutRunningAndIgnoresOldResponses() throws Exception {
        var parent = screen(); var opened = new Screen[1];
        set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); click(parent, "edit");
        var editor = (ComputerTerminalEditorScreen)opened[0]; init(editor, 320);
        set(editor, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); click(editor, "installed");
        var viewer = assertInstanceOf(ComputerTerminalScreen.class, opened[0]);
        var actions = new ArrayList<ComputerControlSession.Action>();
        set(viewer, "request", (Consumer<ComputerControlSession.Action>)actions::add);
        set(viewer, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        init(viewer, 320); tick(viewer);
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), actions);
        var menu = (ComputerProgramMenu)get(parent, "menu"); var nonce = UUID.randomUUID();
        set(menu, "nonce", nonce); set(menu, "latestControlIntent", 2L); set(menu, "latestControlAction", ComputerControlSession.Action.READ_TERMINAL);
        set(menu, "controlResponse", Optional.empty());
        var limits = new ArchiveLimits(100000, 1000000, 10000000);
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 1, "ready", "", new ResourceLimits(1000, 1000000, 1), limits, new byte[0])));
        var nodes = Map.<String, TerminalDefinition.Node>of("r", new TerminalDefinition.Repeat(new TerminalBinding.Literal(new DataValue.ListValue(java.util.stream.LongStream.range(0, 40).mapToObj(DataValue.IntegerValue::new).map(DataValue.class::cast).toList())), "t"),
            "t", new TerminalDefinition.Text(new TerminalBinding.Path(new ValuePath("row", List.of()))));
        var definition = new TerminalDefinition("r", nodes);
        var envelope = new DataValue.RecordValue(Map.of("terminal", TerminalCodec.encode(definition), "memory", new DataValue.RecordValue(Map.of()),
            "output", DataValue.NullValue.INSTANCE, "phase", new DataValue.TextValue("IDLE"), "paused", new DataValue.BooleanValue(false)));
        var reply = new ProgramStatusResponse(0, nonce, 2, 1, "ok", "", ProgramEditorWire.encodeValue(envelope, limits));
        menu.accept(reply); tick(viewer); bounds(viewer);
        var before = (TerminalProjection.View)get(viewer, "view");
        assertEquals(4, before.children().size(), "Small viewport reserves a row for the repeat navigation");
        @SuppressWarnings("unchecked") var pageNext = (Map<String, CreateTextButton>)get(viewer, "pageNext");
        pageNext.get(before.instance()).onClick(0, 0);
        var after = (TerminalProjection.View)get(viewer, "view"); assertEquals(1, after.page().orElseThrow().index());
        init(viewer, 426); assertEquals(1, ((TerminalProjection.View)get(viewer, "view")).page().orElseThrow().index()); bounds(viewer);
        assertEquals(7, ((TerminalProjection.View)get(viewer, "view")).children().size());
        menu.accept(new ProgramStatusResponse(0, nonce, 1, 1, "invalid", "old", new byte[0])); tick(viewer);
        assertEquals(definition, ((TerminalStatusView)get(viewer, "snapshot")).terminal().orElseThrow());
        assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL), actions);
        click(viewer, "refresh"); assertEquals(List.of(ComputerControlSession.Action.READ_TERMINAL, ComputerControlSession.Action.READ_TERMINAL), actions);
        click(viewer, "back"); assertSame(editor, opened[0]);
        set(menu, "latestControlAction", ComputerControlSession.Action.REFRESH); assertTrue(menu.terminalStatus().isEmpty());
    }
    @Test void projectionBudgetComesFromTheAuthoritativeMenuArchiveLimits() throws Exception {
        var parent = screen(); var menu = (ComputerProgramMenu)get(parent, "menu"); var nonce = UUID.randomUUID();
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, nonce, 1, 1, "ready", "", new ResourceLimits(1000, 1000000, 1), new ArchiveLimits(1, 100000, 1000000), new byte[0])));
        var definition = new TerminalDefinition("root", Map.of("root", new TerminalDefinition.Column(List.of("text")), "text", new TerminalDefinition.Text(new TerminalBinding.Literal(new DataValue.TextValue("value")))));
        var viewer = new ComputerTerminalScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"), net.minecraft.network.chat.Component.empty(), parent);
        set(viewer, "snapshot", new TerminalStatusView(Optional.of(definition), new DataValue.RecordValue(Map.of()), "IDLE", false));
        init(viewer, 320); assertNull(get(viewer, "view"));
        var notice = assertInstanceOf(TranslatableContents.class, ((net.minecraft.network.chat.Component)get(viewer, "notice")).getContents());
        assertEquals("create_biotech.biocomputing.terminal.invalid", notice.getKey());
    }
    private static void tick(ComputerTerminalScreen screen) throws Exception {
        var method = ComputerTerminalScreen.class.getDeclaredMethod("containerTick"); method.setAccessible(true); method.invoke(screen);
    }
    private static void select(ComputerTerminalEditorScreen screen, String id) throws Exception {
        for (int i = 0; i < 30; i++) {
            @SuppressWarnings("unchecked") var buttons = (Map<String, CreateTextButton>)get(screen, "treeButtons");
            if (buttons.containsKey(id)) { buttons.get(id).onClick(0, 0); return; }
            var next = button(screen, "tree_next");
            if (next.active) next.onClick(0, 0); else { while (button(screen, "tree_previous").active) click(screen, "tree_previous"); }
        }
        fail("Node not accessible: " + id);
    }
    static void init(Screen screen, int width) throws Exception {
        set(screen, "minecraft", Minecraft.getInstance()); set(screen, "font", Minecraft.getInstance().font);
        set(screen, "width", width); set(screen, "height", width == 426 ? 300 : 240);
        var method = screen.getClass().getDeclaredMethod("init"); method.setAccessible(true); method.invoke(screen);
    }
    static CreateTextButton button(Screen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.terminal." + key)).findFirst().orElseThrow();
    }
    static void click(Screen screen, String key) { var button = button(screen, key); assertTrue(button.active, key); button.onClick(0, 0); }
    static void bounds(Screen screen) throws Exception {
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (var w : widgets) assertTrue(w.getX() >= 0 && w.getY() >= 0 && w.getX() + w.getWidth() <= (int)get(screen, "width") && w.getY() + w.getHeight() <= (int)get(screen, "height"), w.getMessage().getString());
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            var a = widgets.get(i); var b = widgets.get(j);
            assertFalse(a.getX() < b.getX() + b.getWidth() && a.getX() + a.getWidth() > b.getX() && a.getY() < b.getY() + b.getHeight() && a.getY() + a.getHeight() > b.getY(), a.getMessage() + " overlaps " + b.getMessage());
        }
    }
}
