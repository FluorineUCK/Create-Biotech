package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PatternGuiResourcesTest {
	private static final Path ROOT = Path.of("art/biocomputing/pack/assets/create_biotech/textures/gui/sprites/pattern");
	@Test void canvasLabelsAndRefreshFailuresHaveBothTranslations() throws Exception {
		String editor = Files.readString(Path.of("src/main/java/com/nobodiiiii/createbiotech/content/biocomputing/application/recipe/menu/PatternRecipeEditorScreen.java"));
		var keys = new java.util.HashSet<String>(java.util.List.of("result.no_capacity", "result.too_large",
			"result.stale_generation", "refreshing", "retry_save"));
		var literals = java.util.regex.Pattern.compile("tr\\(\"([^\"]+)\"|AllIcons\\.\\w+,\"([^\"]+)\"").matcher(editor);
		while (literals.find()) {
			String key = literals.group(1) == null ? literals.group(2) : literals.group(1);
			if (!key.endsWith(".")) keys.add(key);
		}
		for (String locale : java.util.List.of("en_us", "zh_cn")) {
			var language = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
				"src/main/resources/assets/create_biotech/lang/" + locale + ".json"))).getAsJsonObject();
			for (String key : keys) assertTrue(language.has("create_biotech.pattern_editor." + key), locale + ": " + key);
			assertTrue(language.has("create_biotech.pattern_core.canvas.new"), locale);
		}
	}
	@Test void replaceablePatternSpritesAreValidPngsWithNineSliceMetadata() throws Exception {
		for (String name : java.util.List.of("surface", "header", "slot", "slot_selected",
			"button/normal", "button/hovered", "button/pressed", "button/disabled", "button/toggled")) {
			Path png = ROOT.resolve(name + ".png");
			assertTrue(Files.isRegularFile(png), name);
			var image = ImageIO.read(png.toFile());
			assertNotNull(image, name); assertTrue(image.getWidth() > 0 && image.getHeight() > 0, name);
			var scaling = com.google.gson.JsonParser.parseString(Files.readString(Path.of(png + ".mcmeta")))
				.getAsJsonObject().getAsJsonObject("gui").getAsJsonObject("scaling");
			assertEquals("nine_slice", scaling.get("type").getAsString(), name);
			assertEquals(image.getWidth(), scaling.get("width").getAsInt(), name);
			assertEquals(image.getHeight(), scaling.get("height").getAsInt(), name);
		}
	}
	@Test void createNativeButtonStatesMapExactlyToReplaceablePatternStates() {
		assertEquals("disabled", com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.patternButtonState(
			com.simibubi.create.foundation.gui.AllGuiTextures.BUTTON_DISABLED));
		assertEquals("pressed", com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.patternButtonState(
			com.simibubi.create.foundation.gui.AllGuiTextures.BUTTON_DOWN));
		assertEquals("hovered", com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.patternButtonState(
			com.simibubi.create.foundation.gui.AllGuiTextures.BUTTON_HOVER));
		assertEquals("toggled", com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.patternButtonState(
			com.simibubi.create.foundation.gui.AllGuiTextures.BUTTON_GREEN));
		assertEquals("normal", com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.patternButtonState(
			com.simibubi.create.foundation.gui.AllGuiTextures.BUTTON));
	}
	@Test void allPatternEntryPointsUseTheSharedSkin() throws Exception {
		Path javaRoot = Path.of("src/main/java/com/nobodiiiii/createbiotech");
		for (String file : java.util.List.of(
			"content/biocomputing/application/recipe/menu/PatternStorageCoreScreen.java",
			"content/biocomputing/application/recipe/menu/PatternRecipeEditorScreen.java",
			"content/biocomputing/application/recipe/menu/PatternRecipeView.java"))
			assertTrue(Files.readString(javaRoot.resolve(file)).contains("PatternGuiSkin"), file);
		assertTrue(Files.readString(javaRoot.resolve("content/biocomputing/application/recipe/menu/PatternRecipeEditorScreen.java"))
			.contains("new PatternIconButton("), "editor icon buttons use skin");
		assertTrue(Files.readString(javaRoot.resolve("content/biocomputing/application/recipe/menu/PatternStorageCoreScreen.java"))
			.contains("new PatternIconButton("), "Core icon button uses skin");
	}
}
