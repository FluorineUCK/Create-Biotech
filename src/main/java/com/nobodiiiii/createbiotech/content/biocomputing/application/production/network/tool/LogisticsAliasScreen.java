package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Minimal first-registration alias editor for one captured logistics network. */
public final class LogisticsAliasScreen extends AbstractContainerScreen<LogisticsAliasMenu> {
	private static final int WIDTH = 220;
	private static final int HEIGHT = 92;
	private EditBox alias;

	public LogisticsAliasScreen(LogisticsAliasMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		imageWidth = WIDTH;
		imageHeight = HEIGHT;
	}

	@Override protected void init() {
		super.init();
		alias = new EditBox(font, leftPos + 16, topPos + 34, 188, 18,
			Component.translatable("create_biotech.logistics_alias.name"));
		alias.setMaxLength(LogisticsAliasRenamePacket.MAX_ALIAS_LENGTH);
		alias.setValue(menu.customAlias().orElse(""));
		addRenderableWidget(alias);
		setInitialFocus(alias);
		addRenderableWidget(new CreateTextButton(font, leftPos + 70, topPos + 62, 80, 18,
			Component.translatable("create_biotech.logistics_alias.save"), this::submit));
	}

	private void submit() {
		CBPackets.sendToServer(menu.renamePacket(alias.getValue()));
		onClose();
	}

	@Override protected void renderBg(GuiGraphics graphics, float partialTick,
		int mouseX, int mouseY) {
		graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff242424);
		graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 24, 0xff151515);
		graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xff765c3d);
	}
	@Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		graphics.drawString(font, title, 10, 8, 0xffe6e6e6, false);
		graphics.drawString(font, Component.translatable("create_biotech.logistics_alias.name"),
			16, 25, 0xffbdbdbd, false);
	}
	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		renderTooltip(graphics, mouseX, mouseY);
	}
}
