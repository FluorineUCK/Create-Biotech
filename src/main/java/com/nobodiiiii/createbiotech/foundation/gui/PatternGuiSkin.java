package com.nobodiiiii.createbiotech.foundation.gui;

import com.nobodiiiii.createbiotech.CreateBiotech;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Resource-pack replaceable surfaces shared by every Pattern presentation entry point. */
public final class PatternGuiSkin {
	public static final ResourceLocation SURFACE = id("surface"), HEADER = id("header"),
		SLOT = id("slot"), SLOT_SELECTED = id("slot_selected");
	private PatternGuiSkin() {}
	private static ResourceLocation id(String path) {
		return CreateBiotech.asResource("pattern/" + path);
	}
	public static ResourceLocation button(String state) { return id("button/" + state); }
	public static void blit(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
		GuiSpriteBlitter.blit(graphics, sprite, x, y, width, height);
	}
}
