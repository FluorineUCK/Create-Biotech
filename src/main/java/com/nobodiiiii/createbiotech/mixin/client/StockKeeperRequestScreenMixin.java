package com.nobodiiiii.createbiotech.mixin.client;

import java.lang.ref.WeakReference;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.wirelessterminal.WirelessStockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestScreen;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperRequestMenu;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;

@Mixin(StockKeeperRequestScreen.class)
public abstract class StockKeeperRequestScreenMixin {

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
