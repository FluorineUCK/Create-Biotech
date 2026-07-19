package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class GhastBalloonMagnetSnapOverlay implements LayeredDraw.Layer {

	public static final GhastBalloonMagnetSnapOverlay INSTANCE = new GhastBalloonMagnetSnapOverlay();

	private GhastBalloonMagnetSnapOverlay() {}

	@Override
	public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		if (!GhastHelmClientHandler.shouldShowMagnetPrompt())
			return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui)
			return;

		Component text = Component.translatable("create_biotech.gui.ghast_balloon.magnet_prompt");
		int textWidth = mc.font.width(text);
		int x = (graphics.guiWidth() - textWidth) / 2;
		int y = graphics.guiHeight() - 64;
		graphics.drawString(mc.font, text, x, y, 0xFFFFFFFF, true);
	}
}
