package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.*;

class ComputerProgramDocumentScreenTest {
    static final ArchiveLimits LIMITS = new ArchiveLimits(10000, 100000, 1000000);
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void existingScratchDocumentCanBeExplicitlyReplacedWithANewAssemblyDraftWithoutInstalling(int width) throws Exception {
        var fixture = screen(); var menu = ComputerFlowScreenTest.readyMenu(fixture);
        var hub = new ComputerScratchScreen(menu,
            (net.minecraft.world.entity.player.Inventory)get(fixture, "editorInventory"), net.minecraft.network.chat.Component.empty());
        var original = new ProgramWorkspace();
        original.setFlow(Optional.of(new FlowDocumentBody(Map.of("level", DataType.Scalar.INTEGER),
            List.of(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.SignalWrite("old", "p0", "level")),
            new DataValue.RecordValue(Map.of("level", new DataValue.IntegerValue(12))))));
        set(hub, "workspace", original); ComputerScratchNavigationTest.initialize(hub);
        Screen[] shown = {hub}; set(hub, "navigator", (Consumer<Screen>)(next -> shown[0] = next));
        var before = original.snapshot(); var authority = menu.response(); var intent = get(menu, "latestIntent");
        click(hub, "program_package"); var page = assertInstanceOf(ComputerProgramDocumentScreen.class, shown[0]);
        set(page, "width", width); set(page, "height", 260); set(page, "minecraft", Minecraft.getInstance());
        set(page, "font", Minecraft.getInstance().font); page.init();
        ((EditBox)get(page, "payload")).setValue("keep clipboard text");
        click(page, "new_program"); bounds(page);
        assertSame(original, get(hub, "workspace"), "Opening the confirmation must not erase a draft");
        click(page, "new_program_cancel");
        assertEquals("keep clipboard text", ((EditBox)get(page, "payload")).getValue());
        click(page, "new_program"); page.onClose();
        assertSame(page, shown[0], "Escape cancels the confirmation, not the document tool");
        assertSame(original, get(hub, "workspace"));
        click(page, "new_program"); click(page, "new_program_confirm");
        var replacement = (ProgramWorkspace)get(hub, "workspace");
        assertNotSame(original, replacement); assertEquals(before, original.snapshot());
        assertEquals(FlowDocumentBody.emptySoftwareAssembly(), replacement.flow().orElseThrow());
        assertTrue(replacement.entryNames().isEmpty());
        assertEquals(authority, menu.response()); assertEquals(intent, get(menu, "latestIntent"), "No install or control request");
        page.onClose(); assertSame(hub, shown[0]); click(hub, "flow");
        var blocks = assertInstanceOf(ComputerFlowScreen.class, shown[0]);
        assertSame(replacement, get(blocks, "workspace"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void publicExportPasteImportPreservesCompleteDocumentAndOnlyReplacesTheDraft(int width) throws Exception {
        var source = parent(); var original = fixture();
        set(source, "workspace", new ProgramWorkspace(original));
        set(source, "draft", ((ProgramWorkspace)get(source, "workspace")).root());
        var exported = open(source, width); String[] clipboard = {""};
        set(exported, "clipboardWrite", (Consumer<String>)(value -> clipboard[0] = value));
        click(exported, "export_program"); click(exported, "copy_package");
        assertEquals(original, ProgramDocumentWire.decode(Base64.getDecoder().decode(clipboard[0]), LIMITS));
        var target = parent(); var old = (ProgramWorkspace)get(target, "workspace");
        var imported = open(target, width);
        set(imported, "clipboardRead", (Supplier<String>)(() -> clipboard[0]));
        click(imported, "paste_package"); assertSame(old, get(target, "workspace"));
        click(imported, "import_program");
        assertNotSame(old, get(target, "workspace"));
        assertEquals(original, ((ProgramWorkspace)get(target, "workspace")).snapshot());
        assertFalse((boolean)get(target, "waiting")); assertFalse((boolean)get(target, "requested"));
        assertEquals(original, ((ProgramWorkspace)get(source, "workspace")).snapshot());
        bounds(imported); bounds(exported);
    }

    @Test void malformedWrongKindAndOversizedPackagesLeaveTheWholeDraftUntouched() throws Exception {
        var parent = parent(); var before = get(parent, "workspace"); var page = open(parent, 320);
        String wrongKind = Base64.getEncoder().encodeToString(SubgraphDocumentWire.encode(SubgraphDocumentTest.fixture(), LIMITS));
        for (String invalid : List.of("not base64", wrongKind, "A".repeat(32769))) {
            ((EditBox)get(page, "payload")).setValue(invalid); click(page, "import_program");
            assertSame(before, get(parent, "workspace")); assertFalse((boolean)get(parent, "waiting"));
        }
        ((EditBox)get(page, "payload")).setValue("kept");
        set(page, "clipboardRead", (Supplier<String>)(() -> "A".repeat(32769)));
        click(page, "paste_package"); assertEquals("kept", get(page, "payloadText"));
        assertSame(before, get(parent, "workspace"));
    }

    @Test void importingFromOldModuleClearsNavigationButDoesNotInstallOrRewriteTheOldWorkspace() throws Exception {
        var parent = parent(); var old = (ProgramWorkspace)get(parent, "workspace");
        var key = old.createModule("old"); old.createEntry("old-event");
        set(parent, "activeGraph", key); set(parent, "activeEntry", "old-event"); set(parent, "draft", old.graph(key));
        set(parent, "selectedModule", key); set(parent, "selectedWhenTrue", key); set(parent, "selectedWhenFalse", key);
        var page = open(parent, 426);
        ((EditBox)get(page, "payload")).setValue(Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(fixture(), LIMITS)));
        click(page, "import_program");
        assertNull(get(parent, "activeGraph")); assertNull(get(parent, "activeEntry"));
        assertNull(get(parent, "selectedModule")); assertNull(get(parent, "selectedWhenTrue")); assertNull(get(parent, "selectedWhenFalse"));
        assertSame(((ProgramWorkspace)get(parent, "workspace")).root(), get(parent, "draft"));
        assertEquals(List.of(key), old.keys()); assertEquals(List.of("old-event"), old.entryNames());
        assertFalse((boolean)get(parent, "waiting"));
        click(page, "export_program");
        assertEquals(fixture(), ProgramDocumentWire.decode(Base64.getDecoder().decode((String)get(page, "payloadText")), LIMITS));
    }

    @Test void programTransferIsDisabledWithoutServerLimitsOrWhileInstalling() throws Exception {
        var parent = screen(); parent.init(); assertFalse(button(parent, "program_package").active);
        parent = parent(); set(parent, "waiting", true); parent.init(); assertFalse(button(parent, "program_package").active);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void examplesPreviewWithoutMutationAndExplicitlyReplaceOnlyTheDraft(int width) throws Exception {
        var parent = parent(); var before = get(parent, "workspace"); var page = examplesPage(parent, width);
        assertSame(before, get(parent, "workspace"));
        click(page, "examples_reload");
        click(page, "example.cluster_library_caller.title");
        assertSame(before, get(parent, "workspace"));
        click(page, "example.cluster_library_worker.title");
        assertSame(before, get(parent, "workspace"));
        assertFalse((boolean)get(parent, "waiting")); assertFalse((boolean)get(parent, "requested"));
        bounds(page);
        if (button(page, "instructions_next").active) {
            click(page, "instructions_next");
            assertTrue((int)get(page, "descriptionPage") > 0);
        }
        click(page, "example_replace");
        assertEquals(0, get(page, "descriptionPage"), "Import feedback must return to the visible first page");
        var expected = ProgramDocumentWire.decode(Base64.getDecoder().decode(java.nio.file.Files.readString(
            java.nio.file.Path.of("examples/biocomputing/cluster_library_worker.bcp")).strip()), LIMITS);
        assertEquals(expected, ((ProgramWorkspace)get(parent, "workspace")).snapshot());
        assertFalse((boolean)get(parent, "waiting")); assertFalse((boolean)get(parent, "requested"));
        click(page, "document_exchange"); click(page, "export_program");
        assertEquals(expected, ProgramDocumentWire.decode(Base64.getDecoder().decode((String)get(page, "payloadText")), LIMITS));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void sequentialExamplesKeepPreviewSeparateFromDraftAndInstallation(int width) throws Exception {
        var parent = parent(); var page = examplesPage(parent, width);
        for (String id : List.of("library_serial_command", "xbus_calculator_caller", "xbus_calculator_worker", "serial_bridge_source", "serial_bridge_converter", "serial_bridge_receiver")) {
            var before = get(parent, "workspace");
            while (page.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                    .noneMatch(b -> b.getMessage().getContents() instanceof TranslatableContents t
                        && t.getKey().equals("create_biotech.biocomputing.editor.example." + id + ".title"))) {
                click(page, "examples_next");
            }
            click(page, "example." + id + ".title");
            assertSame(before, get(parent, "workspace"));
            click(page, "example_replace");
            var actual = ((ProgramWorkspace)get(parent, "workspace")).snapshot();
            assertTrue(actual.flow().isPresent()); assertTrue(actual.terminal().isEmpty());
            var expected = ProgramDocumentWire.decode(Base64.getDecoder().decode(java.nio.file.Files.readString(
                java.nio.file.Path.of("examples/biocomputing/" + id + ".bcp")).strip()), LIMITS);
            assertEquals(expected, actual);
            assertFalse((boolean)get(parent, "waiting")); assertFalse((boolean)get(parent, "requested"));
            assertTrue(((ProgramWorkspace)get(parent, "workspace")).root().sends().isEmpty());
            bounds(page);
        }
    }

    private static Screen examplesPage(ComputerProgramScreen parent, int width) throws Exception {
        var page = open(parent, width);
        set(page, "exampleResourceOpener", (Function<net.minecraft.resources.ResourceLocation, java.io.InputStream>)(id ->
            ComputerProgramDocumentScreenTest.class.getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath())));
        set(page, "font", new Metrics() {
            @Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText text, int width) {
                var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
                var value = text.getString();
                int chars = Math.max(1, width / 6);
                for (int start = 0; start < value.length(); start += chars)
                    lines.add(net.minecraft.util.FormattedCharSequence.forward(value.substring(start, Math.min(value.length(), start + chars)), net.minecraft.network.chat.Style.EMPTY));
                return lines;
            }
        });
        click(page, "examples");
        return page;
    }

    @Test void searchingCombinedExamplesKeepsFocusAndInvalidatesThePreviousPreview() throws Exception {
        var parent = parent(); var before = get(parent, "workspace");
        var page = (ComputerProgramDocumentScreen)examplesPage(parent, 320);
        page.init();
        var search = page.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        page.setFocused(search);
        search.setValue("music output a");
        assertSame(search, page.getFocused(), "Filtering rows must not replace the search field or disrupt typing");
        assertFalse(button(page, "examples_next").active);
        button(page, "example.music_output.title").onClick(0, 0);
        assertTrue(button(page, "example_replace").active);
        var nextSearch = page.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        nextSearch.setValue("no-such-example");
        assertFalse(button(page, "example_replace").active, "A previous match cannot be imported after changing the search");
        assertFalse(button(page, "examples_next").active);
        assertEquals("create_biotech.biocomputing.editor.examples_no_matches",
            ((TranslatableContents)((net.minecraft.network.chat.Component)get(page, "notice")).getContents()).getKey());
        assertSame(before, get(parent, "workspace"));
        nextSearch.setValue("text_output_a");
        assertTrue(button(page, "media_library").active, "Integrating example search must retain media import");
        button(page, "example.text_output_a.title").onClick(0, 0);
        assertTrue(button(page, "example_replace").active);
        assertSame(before, get(parent, "workspace"));
        assertFalse((boolean)get(parent, "waiting")); assertFalse((boolean)get(parent, "requested"));
        bounds(page);
    }

    @Test void failedPreviewAndReloadClearOldSelectionWithoutChangingDraft() throws Exception {
        var parent = parent(); var before = get(parent, "workspace"); var page = examplesPage(parent, 320);
        click(page, "example.cluster_library_caller.title"); assertTrue(button(page, "example_replace").active);
        set(page, "exampleResourceOpener", (Function<net.minecraft.resources.ResourceLocation, java.io.InputStream>)(id -> null));
        click(page, "example.cluster_library_worker.title");
        assertFalse(button(page, "example_replace").active); assertSame(before, get(parent, "workspace"));
        assertEquals("create_biotech.biocomputing.editor.invalid_parameter",
            ((TranslatableContents)((net.minecraft.network.chat.Component)get(page, "notice")).getContents()).getKey());
        click(page, "examples_reload");
        assertFalse(button(page, "example_replace").active); assertSame(before, get(parent, "workspace"));
        assertFalse((boolean)get(parent, "waiting")); bounds(page);
    }

    @Test void importRechecksChangedServerLimitsAndDoesNotPartiallyReplaceDraft() throws Exception {
        var parent = parent(); var before = get(parent, "workspace"); var page = examplesPage(parent, 320);
        click(page, "example.cluster_library_worker.title");
        var menu = (ComputerProgramMenu)get(parent, "menu");
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, UUID.randomUUID(), 1, 7, "ready", "",
            new ResourceLimits(1000, 100000, 1000), new ArchiveLimits(1, 0, 1), new byte[0])));
        click(page, "example_replace");
        assertSame(before, get(parent, "workspace")); assertFalse((boolean)get(parent, "waiting"));
    }

    @Test void longerResourceCatalogPaginatesWithoutDroppingEntriesOrChangingDraft() throws Exception {
        var parent = parent(); var before = get(parent, "workspace"); var page = examplesPage(parent, 320);
        var items = new com.google.gson.JsonArray();
        for (int i = 0; i < 11; i++) {
            var item = new com.google.gson.JsonObject();
            item.addProperty("id", "fixture_" + i);
            item.addProperty("title", "create_biotech.biocomputing.editor.fixture_" + i);
            item.addProperty("document", "create_biotech:biocomputing/examples/cluster_library_caller.bcp");
            var guide = new com.google.gson.JsonArray(); guide.add("create_biotech.biocomputing.editor.examples_hint");
            item.add("instructions", guide); items.add(item);
        }
        var index = new com.google.gson.JsonObject(); index.addProperty("version", 1); index.add("examples", items);
        set(page, "exampleResourceOpener", (Function<net.minecraft.resources.ResourceLocation, java.io.InputStream>)(id ->
            id.equals(ProgramExampleCatalog.INDEX)
                ? new java.io.ByteArrayInputStream(index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                : getClass().getClassLoader().getResourceAsStream("assets/" + id.getNamespace() + "/" + id.getPath())));
        click(page, "examples_reload"); var seen = new HashSet<String>();
        do {
            bounds(page);
            for (var child : page.children()) if (child instanceof CreateTextButton row
                    && row.getMessage().getContents() instanceof TranslatableContents label
                    && label.getKey().contains(".fixture_")) seen.add(label.getKey());
            if (!button(page, "examples_next").active) break;
            click(page, "examples_next");
        } while (true);
        assertEquals(11, seen.size()); click(page, "fixture_10");
        assertTrue(button(page, "example_replace").active); assertSame(before, get(parent, "workspace"));
        click(page, "examples_previous"); assertSame(before, get(parent, "workspace"));
        assertFalse((boolean)get(parent, "waiting"));
    }

    @Test void guideDoesNotAppendEmptyParagraphsThatCreateABlankLastPage() throws Exception {
        var page = examplesPage(parent(), 640); String[] rendered = {""};
        set(page, "font", new Metrics() {
            @Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText text, int width) {
                rendered[0] = text.getString();
                return List.of(net.minecraft.util.FormattedCharSequence.forward(rendered[0], net.minecraft.network.chat.Style.EMPTY));
            }
        });
        click(page, "example.cluster_library_worker.title");
        assertFalse(rendered[0].isEmpty());
        assertEquals(rendered[0].stripTrailing(), rendered[0], "Trailing blank guide lines can produce an entirely blank page");
    }

    static ProgramDocument fixture() {
        var workspace = new ProgramWorkspace(); var key = workspace.createModule("portable");
        var leaf = workspace.graph(key); String value = leaf.add(new ComputationGraph.Constant(new DataValue.DecimalValue(-0.0)), 12.5, -7);
        leaf.output("answer", value);
        var root = workspace.root(); String call = root.add(new ComputationGraph.Call(key, Map.of()), 20, 30); root.output("result", call);
        root.putCell("counter", DataType.Scalar.INTEGER, new DataValue.IntegerValue(3));
        var event = workspace.createEntry("tick"); String n = event.add(new ComputationGraph.Constant(new DataValue.IntegerValue(9)), 40, 50);
        event.output("value", n); event.setWrite("counter", "value");
        var snapshot = workspace.snapshot();
        return new ProgramDocument(snapshot.archive(), snapshot.positions(), Optional.of(TerminalDefinition.empty()));
    }
    private static ComputerProgramScreen parent() throws Exception {
        var parent = screen(); var menu = (ComputerProgramMenu)get(parent, "menu");
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, UUID.randomUUID(), 1, 7, "ready", "",
            new ResourceLimits(1000, 100000, 1000), LIMITS, new byte[0])));
        parent.init(); return parent;
    }
    private static Screen open(ComputerProgramScreen parent, int width) throws Exception {
        Screen[] opened = {null}; set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next)); parent.init();
        click(parent, "program_package"); assertNotNull(opened[0]); var page = opened[0];
        assertEquals("ComputerProgramDocumentScreen", page.getClass().getSimpleName());
        set(page, "width", width); set(page, "height", 260); set(page, "minecraft", Minecraft.getInstance());
        set(page, "font", Minecraft.getInstance().font);
        var init = page.getClass().getDeclaredMethod("init"); init.setAccessible(true); init.invoke(page); return page;
    }
    private static void bounds(Screen page) {
        for (var child : page.children()) if (child instanceof AbstractWidget widget && widget.visible) {
            assertTrue(widget.getX() >= 0 && widget.getY() >= 0);
            assertTrue(widget.getX() + widget.getWidth() <= page.width); assertTrue(widget.getY() + widget.getHeight() <= page.height);
        }
    }
    private static CreateTextButton button(Screen page, String key) {
        return page.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.editor." + key)).findFirst()
            .orElseThrow(() -> new AssertionError("Missing public control: " + key));
    }
    private static void click(Screen page, String key) {
        if (key.startsWith("example.")) {
            // The public catalog grows; finding an example must use its visible pagination, not assume page one.
            while (button(page, "examples_previous").active) button(page, "examples_previous").onClick(0, 0);
            while (page.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
                .noneMatch(b -> b.getMessage().getContents() instanceof TranslatableContents t
                    && t.getKey().equals("create_biotech.biocomputing.editor." + key))) {
                var next = button(page, "examples_next"); assertTrue(next.active, "Missing catalog example: " + key); next.onClick(0, 0);
            }
        }
        var button = button(page, key); assertTrue(button.active); button.onClick(0, 0);
    }
    @Test void searchingKeepsSensingScopeAndInvalidatesThePreviousPreview() throws Exception {
        var parent = parent(); var before = get(parent, "workspace");
        var page = (ComputerProgramDocumentScreen)examplesPage(parent, 320);
        page.sensingExamples(FlowBlockCatalog.SensingSection.INPUT);
        page.init();
        var search = page.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        page.setFocused(search);
        search.setValue("reader output e");
        assertSame(search, page.getFocused(), "Filtering rows must not replace the search field or disrupt typing");
        assertFalse(button(page, "examples_next").active);
        button(page, "example.reader_output_e.title").onClick(0, 0);
        assertTrue(button(page, "example_replace").active);
        var nextSearch = page.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
        nextSearch.setValue("text_output_a");
        assertFalse(button(page, "example_replace").active, "A previous match cannot be imported after changing the search");
        assertFalse(button(page, "examples_next").active);
        assertEquals("create_biotech.biocomputing.editor.examples_no_matches",
            ((TranslatableContents)((net.minecraft.network.chat.Component)get(page, "notice")).getContents()).getKey());
        assertSame(before, get(parent, "workspace"));
        click(page, "examples_all");
        button(page, "example.text_output_a.title").onClick(0, 0);
        assertTrue(button(page, "example_replace").active);
        assertSame(before, get(parent, "workspace"));
        assertFalse((boolean)get(parent, "waiting")); assertFalse((boolean)get(parent, "requested"));
        bounds(page);
    }

}
