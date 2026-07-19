package com.nobodiiiii.createbiotech.mixin;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nobodiiiii.createbiotech.content.wirelessterminal.WirelessStockKeeperRequestMenu;
import com.simibubi.create.content.logistics.stockTicker.LogisticalStockRequestPacket;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderRequestPacket;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperCategoryHidingPacket;
import com.simibubi.create.content.logistics.stockTicker.StockKeeperLockPacket;
import com.simibubi.create.foundation.networking.BlockEntityConfigurationPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

@Mixin(BlockEntityConfigurationPacket.class)
public abstract class BlockEntityConfigurationPacketMixin {

	@WrapOperation(
		method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/world/level/Level;"
		)
	)
	private Level createBiotech$routeWirelessTerminalPacketsToTargetLevel(ServerPlayer player,
		Operation<Level> original) {
		Level playerLevel = original.call(player);
		if (!createBiotech$isWirelessTerminalPacket()
			|| !(player.containerMenu instanceof WirelessStockKeeperRequestMenu menu)
			|| menu.contentHolder == null
			|| menu.contentHolder.isRemoved()
			|| menu.contentHolder.getLevel() == null)
			return playerLevel;

		return menu.contentHolder.getLevel();
	}

	@WrapOperation(
		method = "handle(Lnet/minecraft/server/level/ServerPlayer;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerPlayer;canInteractWithBlock(Lnet/minecraft/core/BlockPos;D)Z"
		)
	)
	private boolean createBiotech$allowWirelessTerminalPackets(ServerPlayer player, BlockPos targetPos,
		double maxRange, Operation<Boolean> original) {
		boolean withinDefaultRange = original.call(player, targetPos, maxRange);
		if (player == null || !createBiotech$isWirelessTerminalPacket()
			|| !(player.containerMenu instanceof WirelessStockKeeperRequestMenu menu))
			return withinDefaultRange;

		boolean crossDimension = menu.contentHolder != null && menu.contentHolder.getLevel() != player.level();
		boolean validTarget = menu.contentHolder != null
			&& !menu.contentHolder.isRemoved()
			&& targetPos.equals(menu.contentHolder.getBlockPos())
			&& menu.stillValid(player);
		if (validTarget)
			return true;
		return crossDimension ? false : withinDefaultRange;
	}

	@Unique
	private boolean createBiotech$isWirelessTerminalPacket() {
		Object packet = this;
		return packet instanceof LogisticalStockRequestPacket
			|| packet instanceof PackageOrderRequestPacket
			|| packet instanceof StockKeeperCategoryHidingPacket
			|| packet instanceof StockKeeperLockPacket;
	}
}
