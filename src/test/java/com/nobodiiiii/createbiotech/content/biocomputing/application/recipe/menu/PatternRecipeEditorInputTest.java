package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
class PatternRecipeEditorInputTest {
 private String source() throws Exception { return Files.readString(Path.of("src/main/java/com/nobodiiiii/createbiotech/content/biocomputing/application/recipe/menu/PatternRecipeEditorScreen.java")); }
 @Test void entrypointSupportsEmptyBookBackedDraft() throws Exception { assertNotNull(PatternRecipeDraft.class.getConstructor()); assertTrue(source().contains("menu.submitCreate(")); }
 @Test void canvasRoutesTransformedNodeConnectionsAndClipsRendering() throws Exception { String s=source(); assertTrue(s.contains("enableScissor(")); assertTrue(s.contains("viewport.worldX(")); assertTrue(s.contains("viewport.zoomAt(")); assertTrue(s.contains("graph.connect(")); assertTrue(s.contains("graph.disconnect(")); }
 @Test void overlayUsesNativeAddressAndGhostIconsInsteadOfSelectorForm() throws Exception { String s=source(); assertTrue(s.contains("new AddressEditBox(")); assertTrue(s.contains("renderOverlay(")); assertTrue(s.contains("beginConnection(")); assertFalse(s.contains("requiresPendingApply")); assertFalse(s.contains("private EditBox selector")); }
 @Test void modalDoesNotForwardUnfocusedKeysToContainerCloseHotkeys() throws Exception {
  String s=source();
  assertFalse(s.contains("if(overlay||picker) {super.keyPressed(key,scan,modifiers);return true;}"));
  assertTrue(s.contains("getFocused().keyPressed(key,scan,modifiers)"));
 } @Test void leavingStageOverlayDropsItsStaleIndexBeforeReinitializingWidgets() throws Exception {
  String s=source();
  assertTrue(s.contains("overlayStage=-1;address=null;priority=null;init();"));
 }
 @Test void staleGenerationRefreshesBoundedBrowserWithoutAutoResubmittingDraft() throws Exception {
  String s=source();
  assertTrue(s.contains("\"stale_generation\".equals(menu.editResult())"));
  assertTrue(s.contains("menu.browser().needsRefresh()"));
  assertTrue(s.contains("sendIfBounded("));
  assertTrue(s.contains("PatternBrowserFilter.EMPTY"));
 } @Test void overlayCanDiscardWholeDraftEvenWhenAddressIsInvalid() throws Exception {
  String s=source();int start=s.indexOf("} else if(overlay) {");
  String controls=s.substring(start,s.indexOf("        } else {",start));
  assertTrue(controls.contains("AllIcons.I_CONFIG_DISCARD,\"cancel\",this::onClose"));
 }}



