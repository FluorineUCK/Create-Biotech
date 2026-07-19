package com.nobodiiiii.createbiotech.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.wirelessterminal.WirelessStockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.LogisticalStockResponsePacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

@Mixin(LogisticalStockResponsePacket.class)
public abstract class LogisticalStockResponsePacketMixin {

	@WrapOperation(
		method = "handle",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"
		)
	)
	private BlockEntity createBiotech$routeWirelessStockResponse(ClientLevel level, BlockPos targetPos,
		Operation<BlockEntity> original) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null
			&& minecraft.player.containerMenu instanceof WirelessStockKeeperRequestMenu menu
			&& menu.contentHolder != null
			&& targetPos.equals(menu.contentHolder.getBlockPos()))
			return menu.contentHolder;

		return original.call(level, targetPos);
	}
}
