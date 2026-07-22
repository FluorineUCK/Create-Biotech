package com.yision.allay.block.allayport;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.platform.InputConstants;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.simibubi.create.content.trains.station.NoShadowFontWrapper;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import com.simibubi.create.foundation.utility.CreateLang;
import com.yision.allay.CreateAllay;
import com.yision.allay.logistics.courier.AllayCourierReturnMode;
import com.yision.allay.logistics.address.AllayAddressRules;

import net.createmod.catnip.gui.element.GuiGameElement;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class AllayPortScreen extends AbstractSimiContainerScreen<AllayPortMenu> {
	private static final ResourceLocation ALLAY_PORT_GUI =
		CreateAllay.asResource("textures/gui/allay_port_gui.png");
	private static final int TEXTURE_SIZE = 256;
	private static final int WINDOW_WIDTH = 220;
	private static final int WINDOW_HEIGHT = 82;
	private static final int UI_X_OFFSET = 8;
	private static final int BACKGROUND_X = 10;
	private static final int BACKGROUND_Y = 32;
	private static final int BACKGROUND_WIDTH = 242;
	private static final int BACKGROUND_HEIGHT = 97;
	private static final int PACKAGE_SLOTS_X = 49;
	private static final int PACKAGE_SLOTS_Y = 54;
	private static final int PACKAGE_SLOTS_SCREEN_X = 25 + UI_X_OFFSET;
	private static final int PACKAGE_SLOTS_SCREEN_Y = 7;
	private static final int BACKGROUND_SCREEN_X = PACKAGE_SLOTS_SCREEN_X - (PACKAGE_SLOTS_X - BACKGROUND_X);
	private static final int BACKGROUND_SCREEN_Y = PACKAGE_SLOTS_SCREEN_Y - (PACKAGE_SLOTS_Y - BACKGROUND_Y);
	private static final int ALLAY_SLOT_X = 37;
	private static final int ALLAY_SLOT_Y = 105;
	private static final int ALLAY_SLOT_SCREEN_X = BACKGROUND_SCREEN_X + ALLAY_SLOT_X - BACKGROUND_X;
	private static final int ALLAY_SLOT_SCREEN_Y = BACKGROUND_SCREEN_Y + ALLAY_SLOT_Y - BACKGROUND_Y;
	private static final int RETURN_MODE_X = 63;
	private static final int RETURN_MODE_Y = 104;
	private static final int RETURN_MODE_SCREEN_X = BACKGROUND_SCREEN_X + RETURN_MODE_X - BACKGROUND_X;
	private static final int RETURN_MODE_SCREEN_Y = BACKGROUND_SCREEN_Y + RETURN_MODE_Y - BACKGROUND_Y;
	private static final int EDIT_NAME_X = 230;
	private static final int EDIT_NAME_Y = 3;
	private static final int EDIT_NAME_SIZE = 13;

	private EditBox addressBox;
	private IconButton confirmButton;
	private IconButton dontAcceptPackages;
	private IconButton acceptPackages;
	private ScrollInput returnModeScroll;
	private Label returnModeLabel;
	private AllayCourierReturnMode selectedReturnMode;
	private ItemStack icon;
	private List<Rect2i> extraAreas = Collections.emptyList();
	private boolean settingsSent;
	private final List<Component> returnModeOptions = List.of(
		Component.translatable("gui.create_biotech.allay_port.return_mode.always_dock"),
		Component.translatable("gui.create_biotech.allay_port.return_mode.always_return"),
		Component.translatable("gui.create_biotech.allay_port.return_mode.return_when_unable"));
	private final List<Component> returnModeShortOptions = List.of(
		Component.translatable("gui.create_biotech.allay_port.return_mode.always_dock.short"),
		Component.translatable("gui.create_biotech.allay_port.return_mode.always_return.short"),
		Component.translatable("gui.create_biotech.allay_port.return_mode.return_when_unable.short"));

	public AllayPortScreen(AllayPortMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		icon = new ItemStack(menu.contentHolder.getBlockState()
			.getBlock()
			.asItem());
	}

	@Override
	protected void init() {
		setWindowSize(WINDOW_WIDTH, WINDOW_HEIGHT + AllGuiTextures.PLAYER_INVENTORY.getHeight());
		super.init();
		clearWidgets();

		int x = getGuiLeft();
		int y = getGuiTop();

		Consumer<String> onTextChanged;
		onTextChanged = s -> addressBox.setX(nameBoxX(s, addressBox));
		addressBox = new EditBox(new NoShadowFontWrapper(font), x + UI_X_OFFSET + 23, y - 11, WINDOW_WIDTH - 20, 10,
			Component.empty());
		addressBox.setBordered(false);
		addressBox.setMaxLength(AllayAddressRules.MAX_PORT_ADDRESS_LENGTH);
		addressBox.setTextColor(0x3D3C48);
		addressBox.setValue(menu.contentHolder.addressFilter);
		addressBox.setFocused(false);
		addressBox.mouseClicked(0, 0, 0);
		addressBox.setResponder(onTextChanged);
		addressBox.setX(nameBoxX(addressBox.getValue(), addressBox));
		addRenderableWidget(addressBox);

		confirmButton =
			new IconButton(x + UI_X_OFFSET + WINDOW_WIDTH - 33, y + WINDOW_HEIGHT - 24, AllIcons.I_CONFIRM);
		confirmButton.withCallback(this::onClose);
		addRenderableWidget(confirmButton);

		acceptPackages =
			new IconButton(x + UI_X_OFFSET + 102, y + WINDOW_HEIGHT - 24, AllIcons.I_SEND_AND_RECEIVE);
		acceptPackages.withCallback(() -> {
			acceptPackages.green = true;
			dontAcceptPackages.green = false;
		});
		acceptPackages.green = menu.contentHolder.acceptsPackages;
		acceptPackages.setToolTip(CreateLang.translateDirect("gui.package_port.send_and_receive"));
		addRenderableWidget(acceptPackages);

		dontAcceptPackages =
			new IconButton(x + UI_X_OFFSET + 102 + 18, y + WINDOW_HEIGHT - 24, AllIcons.I_SEND_ONLY);
		dontAcceptPackages.withCallback(() -> {
			acceptPackages.green = false;
			dontAcceptPackages.green = true;
		});
		dontAcceptPackages.green = !menu.contentHolder.acceptsPackages;
		dontAcceptPackages.setToolTip(CreateLang.translateDirect("gui.package_port.send_only"));
		addRenderableWidget(dontAcceptPackages);

		int returnModeX = x + RETURN_MODE_SCREEN_X;
		int returnModeY = y + RETURN_MODE_SCREEN_Y;
		returnModeLabel = new Label(returnModeX + 4, returnModeY + 6, Component.empty()).withShadow();
		selectedReturnMode = port().getReturnMode();
		returnModeScroll = new SelectionScrollInput(returnModeX, returnModeY, 53, 16)
			.forOptions(returnModeOptions)
			.titled(Component.translatable("gui.create_biotech.allay_port.return_mode"))
			.format(returnModeShortOptions::get)
			.calling(state -> selectedReturnMode = AllayCourierReturnMode.byId(state))
			.writingTo(returnModeLabel)
			.setState(selectedReturnMode.id());
		returnModeScroll.onChanged();
		addRenderableWidget(returnModeScroll);
		addRenderableWidget(returnModeLabel);

		containerTick();

		extraAreas =
			ImmutableList.of(new Rect2i(x + UI_X_OFFSET + WINDOW_WIDTH, y + WINDOW_HEIGHT - 50, 70, 60));
	}

	private int nameBoxX(String s, EditBox nameBox) {
		return getGuiLeft() + UI_X_OFFSET + WINDOW_WIDTH / 2
			- (Math.min(font.width(s), nameBox.getWidth()) + 10) / 2;
	}

	@Override
	protected void containerTick() {
		acceptPackages.visible = menu.contentHolder.target != null;
		dontAcceptPackages.visible = menu.contentHolder.target != null;
		super.containerTick();
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = getGuiLeft();
		int y = getGuiTop();

		blitAllayPortGui(graphics, x + BACKGROUND_SCREEN_X, y + BACKGROUND_SCREEN_Y, BACKGROUND_X, BACKGROUND_Y,
			BACKGROUND_WIDTH, BACKGROUND_HEIGHT);
		AllGuiTextures.FROGPORT_SLOT.render(graphics, x + ALLAY_SLOT_SCREEN_X, y + ALLAY_SLOT_SCREEN_Y);

		String text = addressBox.getValue();
		if (!addressBox.isFocused()) {
			if (addressBox.getValue()
				.isEmpty()) {
				text = icon.getHoverName()
					.getString();
				graphics.drawString(font, text, nameBoxX(text, addressBox), y - 11, 0x3D3C48, false);
			}
			blitAllayPortGui(graphics, nameBoxX(text, addressBox) + font.width(text) + 5, y - 14, EDIT_NAME_X,
				EDIT_NAME_Y, EDIT_NAME_SIZE, EDIT_NAME_SIZE);
		}

		GuiGameElement.of(icon).<GuiGameElement.GuiRenderBuilder>at(x + UI_X_OFFSET + WINDOW_WIDTH + 6,
			y + WINDOW_HEIGHT - 56, -200)
			.scale(4)
			.render(graphics);

		int invX = leftPos + UI_X_OFFSET + 30;
		int invY = topPos + 8 + imageHeight - AllGuiTextures.PLAYER_INVENTORY.getHeight();
		renderPlayerInventory(graphics, invX, invY);

		if (menu.contentHolder.target == null)
			return;

		x += ALLAY_SLOT_SCREEN_X;
		y += ALLAY_SLOT_SCREEN_Y;
		graphics.renderItem(menu.contentHolder.target.getIcon(), x + 1, y + 1);

		if (addressBox.isHovered()) {
			graphics.renderComponentTooltip(font, List.of(CreateLang.translate("gui.package_port.catch_packages")
				.color(AbstractSimiWidget.HEADER_RGB)
				.component(),
				CreateLang.translate("gui.package_port.catch_packages_empty")
					.style(ChatFormatting.GRAY)
					.component(),
				CreateLang.translate("gui.package_port.catch_packages_wildcard")
					.style(ChatFormatting.GRAY)
					.component()),
				mouseX, mouseY);
		}
	}

	private void blitAllayPortGui(GuiGraphics graphics, int x, int y, int u, int v, int width, int height) {
		graphics.blit(ALLAY_PORT_GUI, x, y, u, v, width, height, TEXTURE_SIZE, TEXTURE_SIZE);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		boolean hitEnter = getFocused() instanceof EditBox
			&& (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER);

		if (hitEnter && addressBox.isFocused()) {
			addressBox.setFocused(false);
			return true;
		}

		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void onClose() {
		sendSettingsOnce();
		super.onClose();
	}

	@Override
	public void removed() {
		sendSettingsOnce();
		super.removed();
	}

	private void sendSettingsOnce() {
		if (settingsSent || addressBox == null || acceptPackages == null || selectedReturnMode == null) {
			return;
		}
		settingsSent = true;
		CBPackets.sendToServer(new AllayPortConfigurationPacket(menu.contentHolder.getBlockPos(),
			addressBox.getValue(), acceptPackages.green, selectedReturnMode,
			menu.contentHolder.getLevel() == null ? null
				: SubLevelCompat.getSpaceId(menu.contentHolder.getLevel(), menu.contentHolder.getBlockPos())));
	}

	private AllayPortBlockEntity port() {
		return (AllayPortBlockEntity) menu.contentHolder;
	}

	@Override
	public List<Rect2i> getExtraAreas() {
		return extraAreas;
	}
}
