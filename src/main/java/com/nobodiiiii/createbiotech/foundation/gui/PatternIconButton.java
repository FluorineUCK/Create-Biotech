package com.nobodiiiii.createbiotech.foundation.gui;

import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.widget.IconButton;
import net.minecraft.client.gui.GuiGraphics;

/** Create IconButton behavior with only its background supplied by the Pattern resource skin. */
public final class PatternIconButton extends IconButton {
	public PatternIconButton(int x, int y, net.createmod.catnip.gui.element.ScreenElement icon) {
		super(x, y, icon);
	}
	public PatternIconButton(int x, int y, com.simibubi.create.foundation.gui.AllIcons icon) {
		super(x, y, icon);
	}
	@Override protected void drawBg(GuiGraphics graphics, AllGuiTextures button) {
		PatternGuiSkin.blit(graphics, PatternGuiSkin.button(CreateTextButton.patternButtonState(button)),
			getX(), getY(), getWidth(), getHeight());
	}
}
