package com.nobodiiiii.createbiotech.foundation.gui;

import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.function.BiConsumer;

/** Text content on Create's native button: keeps its hover, disabled and click behavior. */
public final class CreateTextButton extends IconButton {
	private final boolean patternSkin;
    private BiConsumer<GuiGraphics, AllGuiTextures> background;
	private static final int CORNER = 3;
	private static final int TEXTURE_SIZE = 256;
    public CreateTextButton(Font font, int x, int y, int width, int height, Component label, Runnable action) {
		this(font, x, y, width, height, label, action, false);
	}
	public CreateTextButton(Font font, int x, int y, int width, int height, Component label, Runnable action,
		boolean patternSkin) {
        super(x, y, width, height, (graphics, left, top) -> {});
		this.patternSkin = patternSkin;
        setMessage(label);
        setIcon((graphics, left, top) -> graphics.drawCenteredString(font,
            font.plainSubstrByWidth(getMessage().getString(), getWidth() - 8),
            left + (getWidth() - 2) / 2, top + (getHeight() - 10) / 2, active ? 0xffeeeeee : 0xff999999));
        withCallback(action);
    }

	@Override
	protected void drawBg(GuiGraphics graphics, AllGuiTextures button) {
		if (background != null) {
			background.accept(graphics, button);
			return;
		}
		if (patternSkin) {
			PatternGuiSkin.blit(graphics, PatternGuiSkin.button(patternButtonState(button)), getX(), getY(), getWidth(), getHeight());
			return;
		}
		int centerWidth = Math.max(0, getWidth() - 2 * CORNER);
		int centerHeight = Math.max(0, getHeight() - 2 * CORNER);
		int right = getX() + getWidth() - CORNER;
		int bottom = getY() + getHeight() - CORNER;
		int u = button.getStartX();
		int v = button.getStartY();
		int sourceRight = u + button.getWidth() - CORNER;
		int sourceBottom = v + button.getHeight() - CORNER;
		blit(graphics, button, getX(), getY(), CORNER, CORNER, u, v, CORNER, CORNER);
		blit(graphics, button, right, getY(), CORNER, CORNER, sourceRight, v, CORNER, CORNER);
		blit(graphics, button, getX(), bottom, CORNER, CORNER, u, sourceBottom, CORNER, CORNER);
		blit(graphics, button, right, bottom, CORNER, CORNER, sourceRight, sourceBottom, CORNER, CORNER);
		blit(graphics, button, getX() + CORNER, getY(), centerWidth, CORNER,
			u + CORNER, v, button.getWidth() - 2 * CORNER, CORNER);
		blit(graphics, button, getX() + CORNER, bottom, centerWidth, CORNER,
			u + CORNER, sourceBottom, button.getWidth() - 2 * CORNER, CORNER);
		blit(graphics, button, getX(), getY() + CORNER, CORNER, centerHeight,
			u, v + CORNER, CORNER, button.getHeight() - 2 * CORNER);
		blit(graphics, button, right, getY() + CORNER, CORNER, centerHeight,
			sourceRight, v + CORNER, CORNER, button.getHeight() - 2 * CORNER);
		blit(graphics, button, getX() + CORNER, getY() + CORNER, centerWidth, centerHeight,
			u + CORNER, v + CORNER, button.getWidth() - 2 * CORNER,
			button.getHeight() - 2 * CORNER);
	}

	public void setBackground(BiConsumer<GuiGraphics, AllGuiTextures> background) {
		this.background = background;
	}

	public static String patternButtonState(AllGuiTextures button) {
		return switch (button) {
			case BUTTON_DISABLED -> "disabled";
			case BUTTON_DOWN -> "pressed";
			case BUTTON_HOVER -> "hovered";
			case BUTTON_GREEN -> "toggled";
			default -> "normal";
		};
	}

	private static void blit(GuiGraphics graphics, AllGuiTextures texture, int x, int y,
		int width, int height, int u, int v, int sourceWidth, int sourceHeight) {
		if (width <= 0 || height <= 0) return;
		graphics.blit(texture.location, x, y, width, height, u, v, sourceWidth, sourceHeight,
			TEXTURE_SIZE, TEXTURE_SIZE);
	}
}
