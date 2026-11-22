package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;

class ComputerSubgraphScreenTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception { environment.client(); }
    @AfterEach void restoreClient() throws Exception { environment.restore(); }
    @Test void publicWorkstationOpensIndependentSubgraphTransferWithoutInstalling() throws Exception {
        var parent = screen(); var opened = new Screen[1];
        set(parent, "navigator", (Consumer<Screen>)(next -> opened[0] = next));
        var menu = (ComputerProgramMenu)get(parent, "menu");
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, UUID.randomUUID(), 1, 7, "ready", "",
            new ResourceLimits(1000, 100000, 1000), SubgraphWorkspaceTest.LIMITS, new byte[0])));
        parent.init();
        var control = parent.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.editor.subgraph_package")).findFirst();
        assertTrue(control.isPresent(), "The package must have a public workstation entry, not just a codec");
        assertTrue(control.orElseThrow().active); control.orElseThrow().onClick(0, 0);
        assertNotNull(opened[0]); assertEquals("ComputerSubgraphScreen", opened[0].getClass().getSimpleName());
        assertFalse((boolean)get(parent, "waiting"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {320, 426, 640})
    void copyPasteImportsWholePackageIntoDraftAndNeverInstalls(int width) throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var source = new ProgramWorkspace();
        var document = SubgraphDocumentTest.fixture(); source.importSubgraph(document, SubgraphWorkspaceTest.LIMITS);
        var exported = create(menu, parent, source, document.root()); initialize(exported, width);
        final String[] clipboard = {""}; set(exported, "clipboardWrite", (Consumer<String>)(value -> clipboard[0] = value));
        click(exported, "export_subgraph"); click(exported, "copy_package");
        assertFalse(clipboard[0].isEmpty()); bounds(exported);
        var target = new ProgramWorkspace(); var root = target.root(); var applied = new int[1];
        var imported = create(menu, parent, target, null); set(imported, "applied", (Runnable)(() -> applied[0]++)); initialize(imported, width);
        assertFalse(button(imported, "export_subgraph").active);
        set(imported, "clipboardRead", (Supplier<String>)(() -> clipboard[0])); click(imported, "paste_package");
        assertTrue(target.keys().isEmpty(), "paste alone does not import");
        click(imported, "import_subgraph"); assertEquals(1, applied[0]); assertEquals(document.modules().keySet(), new HashSet<>(target.keys()));
        assertSame(root, target.root()); assertTrue(root.nodes().isEmpty()); assertFalse((boolean)get(parent, "waiting"));
        bounds(imported);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "zh_cn"})
    void localizedWidgetActionsDisplayActualRootRevisionAndWholePackageCount(String locale) throws Exception {
        var values = ComputerUiTranslationsTest.load(locale);
        var old = net.minecraft.locale.Language.getInstance();
        try {
            net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
            var parent = screen(); var menu = readyMenu(parent); var source = new ProgramWorkspace();
            var document = SubgraphDocumentTest.fixture(); source.importSubgraph(document, SubgraphWorkspaceTest.LIMITS);
            var exported = create(menu, parent, source, document.root()); initialize(exported, 640);
            for (var child : exported.children()) if (child instanceof CreateTextButton control)
                assertFalse(control.getMessage().getString().startsWith("create_biotech."), control.getMessage().getString());
            click(exported, "export_subgraph");
            assertEquals(values.get("create_biotech.biocomputing.editor.subgraph_exported").formatted(
                document.root().id(), document.root().revision(), document.modules().size()), ((Component)get(exported, "notice")).getString());
            var target = new ProgramWorkspace();
            var imported = create(menu, parent, target, null); initialize(imported, 640);
            ((EditBox)get(imported, "payload")).setValue(((EditBox)get(exported, "payload")).getValue());
            assertTrue(target.keys().isEmpty());
            click(imported, "import_subgraph");
            assertEquals(values.get("create_biotech.biocomputing.editor.subgraph_imported").formatted(
                document.root().id(), document.root().revision(), document.modules().size()), ((Component)get(imported, "notice")).getString());
            assertEquals(document.modules().keySet(), new HashSet<>(target.keys()));
            assertTrue(target.root().nodes().isEmpty());
            assertFalse((boolean)get(parent, "waiting"));
        } finally { net.minecraft.locale.Language.inject(old); }
    }

    @Test void malformedOversizedAndConflictingPackagesDoNotChangeTheDraft() throws Exception {
        var parent = screen(); var menu = readyMenu(parent); var target = new ProgramWorkspace();
        var document = SubgraphDocumentTest.fixture(); target.importSubgraph(document, SubgraphWorkspaceTest.LIMITS);
        var imported = create(menu, parent, target, null); initialize(imported, 320);
        var changed = new int[1]; set(imported, "applied", (Runnable)(() -> changed[0]++));
        var before = target.keys(); ((EditBox)get(imported, "payload")).setValue("not a package"); click(imported, "import_subgraph");
        assertEquals(before, target.keys()); assertEquals(0, changed[0]);
        set(imported, "clipboardRead", (Supplier<String>)(() -> "A".repeat(ComputerSubgraphScreen.MAX_TEXT + 20)));
        String old = ((EditBox)get(imported, "payload")).getValue(); click(imported, "paste_package");
        assertEquals(old, ((EditBox)get(imported, "payload")).getValue(), "oversized clipboard must not replace the edit with a truncated prefix");
        ((EditBox)get(imported, "payload")).setValue("A".repeat(ComputerSubgraphScreen.MAX_TEXT + 20)); click(imported, "import_subgraph");
        assertEquals(0, changed[0]); assertEquals(before, target.keys());
        var valid = Base64.getEncoder().encodeToString(SubgraphDocumentWire.encode(document, SubgraphWorkspaceTest.LIMITS));
        target.graph(document.root()).configure("flag", new ComputationGraph.Constant(new DataValue.BooleanValue(false)));
        var preserved = target.graph(document.root()).node("flag");
        ((EditBox)get(imported, "payload")).setValue(valid); click(imported, "import_subgraph");
        assertEquals(0, changed[0]); assertEquals(preserved, target.graph(document.root()).node("flag"));
        assertEquals(before, target.keys()); assertFalse((boolean)get(parent, "waiting"));
    }

    private static ComputerProgramMenu readyMenu(ComputerProgramScreen parent) throws Exception {
        var menu = (ComputerProgramMenu)get(parent, "menu");
        set(menu, "response", Optional.of(new ProgramEditorResponse(0, UUID.randomUUID(), 1, 7, "ready", "", new ResourceLimits(1000, 100000, 1000), SubgraphWorkspaceTest.LIMITS, new byte[0])));
        return menu;
    }
    private static ComputerSubgraphScreen create(ComputerProgramMenu menu, ComputerProgramScreen parent, ProgramWorkspace workspace, ModuleDefinition.Key key) throws Exception {
        return new ComputerSubgraphScreen(menu, (net.minecraft.world.entity.player.Inventory)get(parent, "editorInventory"), Component.empty(), workspace, key, () -> {}, () -> {});
    }
    private static void initialize(ComputerSubgraphScreen screen, int width) throws Exception {
        set(screen, "width", width); set(screen, "height", 260); set(screen, "minecraft", net.minecraft.client.Minecraft.getInstance());
        set(screen, "font", net.minecraft.client.Minecraft.getInstance().font); screen.init();
    }
    private static void bounds(Screen screen) {
        for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible) {
            assertTrue(widget.getX() >= 0 && widget.getY() >= 0); assertTrue(widget.getX() + widget.getWidth() <= screen.width);
            assertTrue(widget.getY() + widget.getHeight() <= screen.height);
        }
    }
    private static CreateTextButton button(Screen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("create_biotech.biocomputing.editor." + key)).findFirst().orElseThrow();
    }
    private static void click(Screen screen, String key) { var control = button(screen, key); assertTrue(control.active); control.onClick(0, 0); }
}
