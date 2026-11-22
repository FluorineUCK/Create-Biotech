package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PatternCoreCanvasSourceTest {
    private String source() throws Exception {
        return Files.readString(Path.of("src/main/java/com/nobodiiiii/createbiotech/content/biocomputing/application/recipe/menu/PatternStorageCoreScreen.java"));
    }
    @Test void coreUsesMovableScissoredCanvasInsteadOfRowButtons() throws Exception {
        String code = source();
        assertTrue(code.contains("CanvasViewport"));
        assertTrue(code.contains("enableScissor"));
        assertTrue(code.contains("viewport.pan("));
        assertTrue(code.contains("viewport.zoomAt("));
        assertFalse(code.contains("ROW_HEIGHT"));
        assertFalse(code.contains("List<CreateTextButton> rows"));
    }
    @Test void bothNewAndExistingPatternsEnterSameEditorAndKeepBoundedSearch() throws Exception {
        String code = source();
        assertTrue(code.contains("new PatternRecipeEditorScreen(this, menu, inventory)"));
        assertTrue(code.contains("entry.pattern()"));
        assertTrue(code.contains("PatternBrowserPage.PAGE_SIZE"));
        assertTrue(code.contains("PatternBrowserFilter.localized("));
        assertTrue(code.contains("menu.editorPattern()"));
    }
}
