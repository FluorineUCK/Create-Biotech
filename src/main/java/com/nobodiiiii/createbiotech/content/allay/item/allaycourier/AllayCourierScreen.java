package com.nobodiiiii.createbiotech.content.allay.item.allaycourier;

import com.simibubi.create.content.logistics.AddressEditBox;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.utility.CreateLang;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.content.allay.network.allay.AllayCourierConfirmPacket;
import java.util.List;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class AllayCourierScreen extends AbstractSimiContainerScreen<AllayCourierMenu> {
	private AddressEditBox addressBox;
	private IconButton confirmButton;
	private List<Rect2i> extraAreas = List.of();
	private boolean addressSubmitted;

	public AllayCourierScreen(AllayCourierMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void init() {
		int guiWidth = AllGuiTextures.REDSTONE_REQUESTER.getWidth();
		int guiHeight = AllGuiTextures.REDSTONE_REQUESTER.getHeight();
		setWindowSize(guiWidth, guiHeight + AllGuiTextures.PLAYER_INVENTORY.getHeight());
		super.init();
		clearWidgets();

		int x = getGuiLeft();
		int y = getGuiTop();
		extraAreas = List.of(new Rect2i(x + guiWidth, y + guiHeight - 50, 70, 60));

		String previousAddress = addressBox == null ? menu.initialAddress : addressBox.getValue();
		addressBox = new AddressEditBox(this, new NoShadowFontWrapper(font), x + 55, y + 68, 110, 10, false);
		addressBox.setValue(previousAddress);
		addressBox.setTextColor(0x3D3C48);
		addRenderableWidget(addressBox);

		confirmButton = new IconButton(x + guiWidth - 30, y + guiHeight - 25, AllIcons.I_CONFIRM);
		confirmButton.withCallback(this::onClose);
		addRenderableWidget(confirmButton);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		addressBox.tick();
	}

	@Override
	protected void renderBg(@NotNull GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = getGuiLeft();
		int y = getGuiTop();

		AllGuiTextures.REDSTONE_REQUESTER.render(graphics, x + 3, y);
		renderPlayerInventory(graphics, x - 3, y + 124);

		Component title = CreateLang.text(menu.openedStack.getHoverName().getString()).component();
		graphics.drawString(font, title, x + 117 - font.width(title) / 2, y + 4, 0x3D3C48, false);
		GuiGameElement.of(new ItemStack(menu.openedStack.getItem()))
			.<GuiGameElement.GuiRenderBuilder>at(x + 245, y + 80, 0)
			.scale(3)
			.render(graphics);

	}

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		super.renderForeground(graphics, mouseX, mouseY, partialTicks);
		if (!addressBox.isHovered() || addressBox.isFocused()) {
			return;
		}

		if (addressBox.getValue().isBlank()) {
			graphics.renderComponentTooltip(font,
				List.of(
					CreateLang.translate("gui.redstone_requester.requester_address")
						.color(ScrollInput.HEADER_RGB)
						.component(),
					CreateLang.translate("gui.redstone_requester.requester_address_tip")
						.style(ChatFormatting.GRAY)
						.component(),
					CreateLang.translate("gui.redstone_requester.requester_address_tip_1")
						.style(ChatFormatting.GRAY)
						.component(),
					CreateLang.translate("gui.schedule.lmb_edit")
						.style(ChatFormatting.DARK_GRAY)
						.style(ChatFormatting.ITALIC)
						.component()),
				mouseX, mouseY);
			return;
		}

		graphics.renderComponentTooltip(font,
			List.of(
				CreateLang.translate("gui.redstone_requester.requester_address_given")
					.color(ScrollInput.HEADER_RGB)
					.component(),
				CreateLang.text("'" + addressBox.getValue() + "'")
					.style(ChatFormatting.GRAY)
					.component()),
			mouseX, mouseY);
	}

	@Override
	public List<Rect2i> getExtraAreas() {
		return extraAreas;
	}

	@Override
	public void onClose() {
		submitAddress();
		super.onClose();
	}

	@Override
	public void removed() {
		submitAddress();
		super.removed();
	}

	private void submitAddress() {
		if (addressSubmitted) {
			return;
		}
		addressSubmitted = true;
		String address = addressBox == null ? menu.initialAddress : addressBox.getValue();
		CBPackets.sendToServer(new AllayCourierConfirmPacket(menu.hand, address));
	}
}
