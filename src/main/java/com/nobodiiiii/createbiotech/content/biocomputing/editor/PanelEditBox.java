package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.gui.GuiSpriteBlitter;

/** Skin only the background; retain vanilla editing, clipping, cursor and selection behavior. */
public final class PanelEditBox extends EditBox {
	private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("create_biotech", "textures/gui/factory_panel_monitor.png");
	private static final ResourceLocation BIOCOMPUTING_INPUT = CreateBiotech.asResource("biocomputing/input");
	private boolean renderingText;
	private boolean biocomputingSkin;

	public PanelEditBox(Font font, int x, int y, int width, int height, Component label) {
		super(font, x, y, width, height, label);
	}

	public static PanelEditBox forBiocomputing(Font font, int x, int y, int width, int height, Component label) {
		var box = new PanelEditBox(font, x, y, width, height, label);
		box.biocomputingSkin = true;
		return box;
	}

	@Override
	public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (!isVisible()) return;
		if (biocomputingSkin) GuiSpriteBlitter.blit(graphics, BIOCOMPUTING_INPUT, getX(), getY(), getWidth(), getHeight());
		else graphics.blit(TEXTURE, getX(), getY(), getWidth(), getHeight(), 304, 0, 160, 20, 512, 512);
		renderingText = true;
		try {
			super.renderWidget(graphics, mouseX, mouseY, partialTick);
		} finally {
			renderingText = false;
		}
	}

	@Override
	public boolean isBordered() {
		// Vanilla consults this method for its sprite, but its private bordered field for padding.
		return !renderingText && super.isBordered();
	}

	@Override
	public int getInnerWidth() {
		// Do not let suppressing the sprite change horizontal text scrolling or click mapping.
		return super.isBordered() ? getWidth() - 8 : getWidth();
	}
}
