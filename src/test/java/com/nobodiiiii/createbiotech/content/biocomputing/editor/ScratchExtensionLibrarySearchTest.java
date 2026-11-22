package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScratchExtensionLibrarySearchTest {
    private final ComputerProgramScreenTest environment = new ComputerProgramScreenTest();
    private Language language;

    @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
    @BeforeEach void client() throws Exception {
        environment.client();
        language = Language.getInstance();
        Language.inject(ComputerUiTranslationsTest.language(ComputerUiTranslationsTest.load("zh_cn")));
    }
    @AfterEach void restoreClient() throws Exception { Language.inject(language); environment.restore(); }

    @ParameterizedTest @ValueSource(ints = {320, 426, 640})
    void searchFindsALocalizedExtensionAndTheExactMissingRevisionWithoutEditingTheDraft(int width) throws Exception {
        var body = FlowDocumentBody.emptyAssembly();
        var layout = body.canvas().withValues(ScratchValueLayout.EMPTY.withParked(List.of(reference(8, 110), reference(18, 160))));
        var workspace = new ProgramWorkspace();
        workspace.setFlow(Optional.of(body.edited(body.inputs(), body.steps(), body.initial(), layout, body.inline())));
        var before = workspace.snapshot();
        var editor = FlowCallScreenTest.editor(workspace, width);
        var opened = new AtomicReference<Screen>();
        set(editor, "navigator", (Consumer<Screen>) opened::set);
        click(editor, "category_extensions");
        click(editor, "extension_library");
        var library = opened.get();
        FlowProcedureScreenTest.initializeForm(library, width);

        var search = boxes(library).stream().findFirst().orElseThrow(() -> new AssertionError("Missing extension search field"));
        bounds(library);
        assertTrue(search.getBottom() <= button(library, "palette_previous").getY());
        search.setValue("文  本"); // Spaces in an unmatched query are not an extension activation.
        assertTrue(visibleButton(library, "extension_enable").isEmpty());
        assertTrue(visibleButton(library, "extension_find_uses").isEmpty());
        assertEquals(before, workspace.snapshot());

        search.setValue("文本工具");
        bounds(library);
        assertSame(search, boxes(library).getFirst(), "Filtering must not replace the field during typing");
        assertFalse(button(library, "palette_next").active);
        click(library, "extension_enable");
        var active = workspace.snapshot().flow().orElseThrow();
        assertEquals(Map.of("create_biotech:text", 1L), active.canvas().extensions());
        assertEquals(layout.values(), active.canvas().values());
        assertEquals("文本工具", boxes(library).getFirst().getValue());
        click(library, "extension_disable");
        assertEquals(before, workspace.snapshot());

        search = boxes(library).getFirst();
        search.setValue("  ADDON:meter   @ 8 ");
        assertFalse(button(library, "palette_next").active, "@ 8 must not also match the sibling revision 18");
        assertFalse(button(library, "extension_enable").active, "Search cannot register a missing extension");
        click(library, "extension_find_uses");
        var references = opened.get();
        FlowProcedureScreenTest.initializeForm(references, width);
        var title = assertInstanceOf(TranslatableContents.class, references.getTitle().getContents());
        assertArrayEquals(new Object[]{"addon:meter", 8L}, title.getArgs());
        click(references, "back");
        assertSame(library, opened.get());
        FlowProcedureScreenTest.initializeForm(library, width);
        assertEquals("  ADDON:meter   @ 8 ", boxes(library).getFirst().getValue());
        boxes(library).getFirst().setValue("");
        assertTrue(button(library, "palette_next").active, "Clearing search returns to the full library");
        assertEquals(before, workspace.snapshot());
    }

    private static Optional<CreateTextButton> visibleButton(Screen screen, String key) {
        return screen.children().stream().filter(CreateTextButton.class::isInstance).map(CreateTextButton.class::cast)
            .filter(b -> b.visible && b.getMessage().getContents() instanceof TranslatableContents t
                && t.getKey().equals("create_biotech.biocomputing.flow_editor." + key)).findFirst();
    }

    private static ScratchValueLayout.Reporter reference(long revision, int y) {
        var key = new ModuleDefinition.Key("cb_extension:addon:meter:reading", revision);
        var graph = new ComputationGraph(Map.of(), Map.of("meter", new ComputationGraph.ProcedureValue(key, "value", Map.of())),
            Map.of("value", "meter"));
        return new ScratchValueLayout.Reporter(new ProgramDocument.Position(22,y), graph, Map.of(), Map.of());
    }
}
