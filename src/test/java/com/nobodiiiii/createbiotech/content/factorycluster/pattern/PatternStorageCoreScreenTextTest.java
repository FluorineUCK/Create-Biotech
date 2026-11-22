package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

class PatternStorageCoreScreenTextTest {
	@Test
	void browserTranslationsNormalizeEveryRenderedDiagnosticValue() throws Exception {
		Method text = PatternStorageCoreScreen.class.getDeclaredMethod("text", String.class, Object[].class);
		text.setAccessible(true);

		assertDoesNotThrow(() -> text.invoke(null, "index", new Object[] {
			State.UNFORMED,
			ResourceLocation.fromNamespaceAndPath("create_biotech", "test"),
			new CompoundTag()
		}));
	}
}
