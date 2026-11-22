package com.nobodiiiii.createbiotech.mixin.client;

import java.lang.ref.WeakReference;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockControls;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.wirelessterminal.WirelessStockKeeperRequestScreen;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

@Mixin(StockKeeperRequestScreen.class)
public abstract class StockKeeperRequestScreenMixin
        extends com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen<StockKeeperRequestMenu>
        implements com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderResponse.Listener, StockControls.Listener {
    protected StockKeeperRequestScreenMixin(StockKeeperRequestMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Shadow boolean encodeRequester;
    @Shadow private com.simibubi.create.content.logistics.packager.InventorySummary forcedEntries;
    @Unique private boolean createBiotech$applicationTakeover;
    @Unique private java.util.UUID createBiotech$pendingPreview;
    @Unique private Component createBiotech$previewError;

    @Unique private java.util.UUID createBiotech$controlsNonce;
    @Unique private StockControls createBiotech$controls;

    @Unique private com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton createBiotech$appButton, createBiotech$routeButton;
    @Override public void showStockControls(StockControls flags) {
        if (menu.containerId != flags.container() || !flags.nonce().equals(createBiotech$controlsNonce)) return;
        createBiotech$controls = flags;
        if (createBiotech$appButton != null) createBiotech$appButton.visible = flags.application();
        if (createBiotech$routeButton != null) createBiotech$routeButton.visible = flags.route();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void createBiotech$addApplicationPage(CallbackInfo ci) {
        if (encodeRequester || createBiotech$screen() instanceof WirelessStockKeeperRequestScreen) return;
        if (createBiotech$controlsNonce == null) {
            createBiotech$controlsNonce = java.util.UUID.randomUUID();
            CBPackets.sendToServer(StockApplicationMenuRequest.controls(menu.containerId, createBiotech$controlsNonce));
        }
        var label = Component.translatable("create_biotech.stock_application.open");
        var button = new com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton(font, getGuiLeft() + 150, getGuiTop() + 1, 62, 14,
            label, () -> CBPackets.sendToServer(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationMenuRequest.open(menu.containerId)));
        button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("create_biotech.stock_application.open_hint")));
        button.visible = createBiotech$controls != null && createBiotech$controls.application();
        createBiotech$appButton = addRenderableWidget(button);
        // Rebuilt on toggle so the route is always visible before the native confirm action.
        var choice = new com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton(font, getGuiLeft() + 75, getGuiTop() + 1, 70, 14,
            createBiotech$routeLabel(), () -> {
                createBiotech$pendingPreview = null; createBiotech$previewError = null;
                createBiotech$applicationTakeover = !createBiotech$applicationTakeover; rebuildWidgets();
            });
        choice.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("create_biotech.stock_application.native_route_hint")));
        choice.visible = createBiotech$controls != null && createBiotech$controls.route();
        createBiotech$routeButton = addRenderableWidget(choice);
    }

    @Unique private Component createBiotech$routeLabel() {
        return Component.translatable("create_biotech.stock_application." + (createBiotech$applicationTakeover ? "native_route_app" : "native_route_create"));
    }

    @Inject(method = "sendIt", at = @At(value = "NEW", target = "com/simibubi/create/content/logistics/stockTicker/PackageOrderRequestPacket", remap = false),
        cancellable = true, remap = false)
    private void createBiotech$routeNativeOrder(CallbackInfo ci,
            @com.llamalad7.mixinextras.sugar.Local com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts order) {
        if (!createBiotech$applicationTakeover || encodeRequester || createBiotech$screen() instanceof WirelessStockKeeperRequestScreen) return;
        // Never construct/send the native dispatch packet on this branch, including on capture failure.
        // Nor keep vanilla's optimistic stock decrement / success animation before application admission.
        forcedEntries = new com.simibubi.create.content.logistics.packager.InventorySummary();
        ci.cancel();
        createBiotech$pendingPreview = null; createBiotech$previewError = null;
        var client = Minecraft.getInstance();
        if (client.level == null) return;
        try {
            var request = com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderRequest.create(
                menu.containerId, order, createBiotech$screen().addressBox.getValue(), client.level.registryAccess());
            createBiotech$pendingPreview = request.request();
            CBPackets.sendToServer(request);
        } catch (IllegalArgumentException failure) {
            createBiotech$showPreviewError(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.RetiredStockOrders.rejectionCode(failure));
        }
    }

    @Override public void rejectNativeOrder(com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockNativeOrderResponse response) {
        if (!response.matches(menu.containerId, createBiotech$pendingPreview)) return;
        createBiotech$showPreviewError(response.code());
    }
    @Unique private void createBiotech$showPreviewError(String code) {
        createBiotech$pendingPreview = null;
        createBiotech$previewError = Component.translatable("create_biotech.stock_application.native_rejected",
            Component.translatableWithFallback("create_biotech.stock_application.code." + code, code));
    }

    @Inject(method = "renderForeground", at = @At("TAIL"))
    private void createBiotech$renderPreviewError(GuiGraphics graphics, int x, int y, float partialTick, CallbackInfo ci) {
        if (createBiotech$previewError == null) return;
        // Stay in the same container: keep the selected items, address and explicit route intact.
        int textWidth = Math.max(1, width - 24);
        var lines = font.split(createBiotech$previewError, textWidth);
        int top = Math.max(4, height - 12 - lines.size() * font.lineHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 450);
        graphics.fill(8, top - 4, width - 8, height - 8, 0xee301c1c);
        for (int i = 0; i < lines.size(); i++) graphics.drawString(font, lines.get(i), 12, top + i * font.lineHeight, 0xffffaaaa, false);
        graphics.pose().popPose();
    }

	@Unique private StockKeeperRequestScreen createBiotech$screen() {
		return (StockKeeperRequestScreen) (Object) this;
	}

	@Shadow
	WeakReference<LivingEntity> stockKeeper;

	@Shadow
	WeakReference<BlazeBurnerBlockEntity> blaze;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void createBiotech$hideWirelessTerminalKeeper(StockKeeperRequestMenu menu, Inventory inventory,
		Component title, CallbackInfo ci) {
		if (!((Object) this instanceof WirelessStockKeeperRequestScreen))
			return;

		stockKeeper = new WeakReference<>(null);
		blaze = new WeakReference<>(null);
	}

	@WrapOperation(
		method = "containerTick",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;closeContainer()V",
			remap = true
		)
	)
	private void createBiotech$keepRemoteWirelessTerminalOpen(Player player, Operation<Void> original) {
		if ((Object) this instanceof WirelessStockKeeperRequestScreen)
			return;
		original.call(player);
	}
}
