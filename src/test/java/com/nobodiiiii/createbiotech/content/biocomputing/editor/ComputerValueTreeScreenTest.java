package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ComputerValueTreeScreenTest {
    private final ComputerProgramScreenTest client = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapTree() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { client.client(); }
    @AfterEach void restoreClient() throws Exception { client.restore(); }

    @Test void generationActionNamesTheDestinationBeforeThePlayerConfirms() throws Exception {
        var parent = ComputerProgramScreenTest.screen();
        assertEquals("create_biotech.biocomputing.tree.generate",
            ((TranslatableContents)parent.valuePathActionTitle().getContents()).getKey());
        ((ProgramWorkspace)get(parent, "workspace")).setFlow(Optional.of(FlowDocumentBody.empty()));
        assertEquals("create_biotech.biocomputing.tree.generate_expression",
            ((TranslatableContents)parent.valuePathActionTitle().getContents()).getKey());
    }

    @Test void expandingAndSelectingAFieldGeneratesAnOrdinaryRunnableRootProgram() throws Exception {
        var parent = ComputerProgramScreenTest.screen();
        var frozen = new DataValue.RecordValue(Map.of("in", new DataValue.RecordValue(Map.of("items",
            new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("count", new DataValue.IntegerValue(7)))))))));
        var screen = browser(frozen, path -> parent.importValuePath(frozen, path), 640, 480);
        assertFalse(button(screen, "generate").active);
        expand(screen, 0); expand(screen, 1); expand(screen, 2); select(screen, 3);
        button(screen, "generate").onClick(0, 0);
        var workspace = (ProgramWorkspace)get(parent, "workspace");
        var machine = new ComputationMachine(workspace.snapshot().archive().compile(ProgramDraftTest.ROOM));
        machine.begin(frozen); machine.tick();
        assertEquals(new DataValue.IntegerValue(7), machine.report().output().orElseThrow().field("read_value"));
        assertSame(frozen, ((ValueTree)get(screen, "tree")).inputs());
    }

    @Test void errorDetailsAreLosslessPageableAndDoNotReplaceTheFrozenInput() throws Exception {
        String message = "line\n\t" + "长字段".repeat(1000);
        var frozen = new DataValue.RecordValue(Map.of("bad", new DataValue.ErrorValue("access_denied", message)));
        var screen = browser(frozen, ignored -> {}, 320, 240);
        select(screen, 0);
        var lines = (List<String>)get(screen, "detailLines");
        assertEquals(new DataValue.ErrorValue("access_denied", message), frozen.field("bad"));
        assertTrue(String.join("", lines).contains("access_denied"));
        assertTrue(String.join("", lines).contains("长字段".repeat(1000)));
        assertTrue(button(screen, "detail_next").active);
        button(screen, "detail_next").onClick(0, 0); assertEquals(1, get(screen, "detailPage"));
        assertFalse(((List<CreateTextButton>)get(screen, "expandButtons")).getFirst().active);
    }

    @Test void flowFieldSelectionCreatesAnOrdinaryExpressionWithoutChangingControlOrInitialValues() throws Exception {
        var parent = ComputerProgramScreenTest.screen();
        var workspace = (ProgramWorkspace)get(parent, "workspace");
        workspace.setFlow(Optional.of(FlowDocumentBody.empty()));
        var before = workspace.snapshot();
        var frozen = new DataValue.RecordValue(Map.of("network", new DataValue.RecordValue(Map.of("game_time", new DataValue.IntegerValue(42)))));
        var screen = browser(frozen, path -> parent.importValuePath(frozen, path), 640, 480);
        expand(screen, 0); select(screen, 1);
        button(screen, "generate").onClick(0, 0);
        assertEquals(List.of("read_path"), workspace.entryNames());
        assertTrue(workspace.root().nodes().isEmpty(), "A Flow cannot accept an event root");
        assertEquals(before.flow(), workspace.snapshot().flow());
        assertEquals(before.archive().program().graph(), workspace.snapshot().archive().program().graph());
        var expression = workspace.entry("read_path");
        assertEquals(2, expression.nodes().size());
        assertTrue(expression.nodes().stream().noneMatch(node -> node.definition() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph.Constant));
        assertEquals("read_path", get(parent, "activeEntry"));
        assertSame(expression, get(parent, "draft"));
        assertFalse((boolean)get(parent, "requested"));
        assertEquals(new ValuePath("network", List.of()).key("game_time"), get(screen, "selected"));
        var notice = assertInstanceOf(TranslatableContents.class, ((Component)get(parent, "notice")).getContents());
        assertEquals("create_biotech.biocomputing.editor.path_expression_generated", notice.getKey());
        assertArrayEquals(new Object[]{"read_path", "read_value"}, notice.getArgs());
    }

    @Test void rowsAndWrappedLeafDetailsContainOnlySafeReversibleLiteralText() throws Exception {
        String key = "key§";
        String text = "prefix\\u00a7\\t:" + "w".repeat(34) + "§c-tail\t\r";
        var value = new DataValue.TextValue(text);
        var frozen = new DataValue.RecordValue(Map.of("in", new DataValue.RecordValue(Map.of(key, value))));
        var generated = new ArrayList<ValuePath>();
        var screen = browser(frozen, generated::add, 320, 240);

        expand(screen, 0);
        var rowLabel = ((List<CreateTextButton>)get(screen, "rowButtons")).get(1).getMessage().getString();
        assertTrue(rowLabel.contains("key\\u00a7"));
        assertFalse(rowLabel.contains("§"));
        select(screen, 1);
        var rendered = String.join("", (List<String>)get(screen, "detailLines"));
        assertTrue(rendered.contains("\"prefix\\\\u00a7\\\\t:"));
        assertTrue(rendered.contains("\\u00a7c-tail\\t\\r\""));
        assertFalse(rendered.contains("§"));
        assertFalse(rendered.contains("\t"));
        assertFalse(rendered.contains("\r"));

        button(screen, "generate").onClick(0, 0);
        assertEquals(List.of(new ValuePath("in", List.of()).key(key)), generated);
        assertSame(value, generated.getFirst().resolve(frozen));
    }

    @Test void errorCodeAndMessageUseTheSameSafeLiteralBoundary() throws Exception {
        var error = new DataValue.ErrorValue("bad§x\\u00a7", "tail§\t\r\\t");
        var screen = browser(new DataValue.RecordValue(Map.of("bad", error)), ignored -> {}, 426, 240);
        select(screen, 0);
        String rendered = String.join("", (List<String>)get(screen, "detailLines"));
        assertTrue(rendered.contains("code:\"bad\\u00a7x\\\\u00a7\""));
        assertTrue(rendered.contains("message:\"tail\\u00a7\\t\\r\\\\t\""));
        assertFalse(rendered.contains("§"));
    }

    @Test void failedGenerationKeepsTheSelectionAndReportsTheError() throws Exception {
        var screen = browser(new DataValue.RecordValue(Map.of("in", DataValue.NullValue.INSTANCE)),
            ignored -> { throw new IllegalArgumentException("Existing input declaration conflicts"); }, 320, 240);
        select(screen, 0); button(screen, "generate").onClick(0, 0);
        assertEquals(new ValuePath("in", List.of()), get(screen, "selected"));
        // Headless bootstrap has no client Language table; assert the diagnostic payload, not localization fallback.
        var notice = assertInstanceOf(TranslatableContents.class, ((Component)get(screen, "notice")).getContents());
        assertArrayEquals(new Object[]{"Existing input declaration conflicts"}, notice.getArgs());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426})
    void compactBrowserControlsNeverOverlapOrLeaveTheWindow(int width) throws Exception {
        var screen = browser(new DataValue.RecordValue(Map.of("in", new DataValue.ListValue(
            java.util.stream.IntStream.range(0, 50).mapToObj(i -> (DataValue)new DataValue.IntegerValue(i)).toList()))), ignored -> {}, width, 240);
        expand(screen, 0); select(screen, 1);
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w -> w.visible).toList();
        for (var w : widgets) assertTrue(w.getX() >= 0 && w.getY() >= 0 && w.getWidth() > 0 && w.getHeight() > 0
            && w.getX() + w.getWidth() <= width && w.getY() + w.getHeight() <= 240);
        for (int i = 0; i < widgets.size(); i++) for (int j = i + 1; j < widgets.size(); j++) {
            var a = widgets.get(i); var c = widgets.get(j);
            assertFalse(a.getX() < c.getX() + c.getWidth() && a.getX() + a.getWidth() > c.getX()
                && a.getY() < c.getY() + c.getHeight() && a.getY() + a.getHeight() > c.getY());
        }
        assertTrue(button(screen, "tree_next").active);
        button(screen, "tree_next").onClick(0, 0); assertEquals(1, get(screen, "treePage"));
    }
    private static ComputerValueTreeScreen browser(DataValue.RecordValue inputs, Consumer<ValuePath> generate, int width, int height) throws Exception {
        var screen = allocate(ComputerValueTreeScreen.class); var menu = allocate(ComputerProgramMenu.class);
        set(screen, "menu", menu); set(screen, "tree", new ValueTree(inputs)); set(screen, "generate", generate);
        set(screen, "actionTitle", Component.translatable("create_biotech.biocomputing.tree.generate"));
        set(screen, "frozenNotice", Component.translatable("create_biotech.biocomputing.tree.frozen"));
        set(screen, "notice", Component.empty()); set(screen, "detailLines", List.of());
        set(screen, "title", Component.literal("Fields")); set(screen, "playerInventoryTitle", Component.empty());
        set(screen, "children", new ArrayList<>()); set(screen, "renderables", new ArrayList<>()); set(screen, "narratables", new ArrayList<>());
        set(screen, "width", width); set(screen, "height", height);
        set(screen, "minecraft", Minecraft.getInstance()); set(screen, "font", Minecraft.getInstance().font);
        screen.init(); return screen;
    }
    private static void expand(ComputerValueTreeScreen screen, int row) throws Exception {
        ((List<CreateTextButton>)get(screen, "expandButtons")).get(row).onClick(0, 0);
    }
    private static void select(ComputerValueTreeScreen screen, int row) throws Exception {
        ((List<CreateTextButton>)get(screen, "rowButtons")).get(row).onClick(0, 0);
    }
    private static CreateTextButton button(ComputerValueTreeScreen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.tree." + key))
            .findFirst().orElseThrow();
    }
}
