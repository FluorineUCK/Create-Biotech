package com.nobodiiiii.createbiotech.content.allay.logistics.courier;

import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.nobodiiiii.createbiotech.registry.CBItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;

public final class AllayCourierHelper {
	private AllayCourierHelper() {}

	public static ServerPlayer findTargetPlayer(ServerLevel level, String playerName) {
		return findTargetPlayer(level, playerName, true);
	}

	public static ServerPlayer findTargetPlayerAnyDimension(ServerLevel level, String playerName) {
		return findTargetPlayer(level, playerName, false);
	}

	private static ServerPlayer findTargetPlayer(ServerLevel level, String playerName, boolean requireSameDimension) {
		String normalizedName = playerName == null ? "" : playerName.trim();
		if (normalizedName.isBlank()) {
			return null;
		}
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (requireSameDimension && !player.serverLevel().dimension().equals(level.dimension())) {
				continue;
			}
			if (!player.isAlive()) {
				continue;
			}
			if (!normalizedName.equalsIgnoreCase(player.getGameProfile().getName())) {
				continue;
			}
			return player;
		}
		return null;
	}

	public static boolean canReceiveDelivery(ServerPlayer player, ItemStack box) {
		return PackageItem.isPackage(box)
			&& ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()), box.copy(), true)
				.isEmpty();
	}

	public static boolean deliverPackage(ServerPlayer player, ItemStack box) {
		if (!PackageItem.isPackage(box)) {
			return false;
		}
		ItemHandlerHelper.giveItemToPlayer(player, box.copy());
		return true;
	}

	public static boolean deliverPackageOnly(ServerPlayer player, ItemStack box) {
		if (!PackageItem.isPackage(box)) {
			return false;
		}
		return ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()), box.copy(), false)
			.isEmpty();
	}

	public static boolean canReceiveCarrier(ServerPlayer player) {
		return ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(player.getInventory()),
			new ItemStack(CBItems.ALLAY_COURIER.get()), true).isEmpty();
	}

	public static boolean deliverCarrier(ServerPlayer player) {
		ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(CBItems.ALLAY_COURIER.get()));
		return true;
	}

	public static void dropPackage(ServerLevel level, Vec3 position, ItemStack box) {
		if (PackageItem.isPackage(box)) {
			level.addFreshEntity(PackageEntity.fromItemStack(level, position, box.copy()));
		}
		level.addFreshEntity(new ItemEntity(level, position.x, position.y, position.z, new ItemStack(CBItems.ALLAY_COURIER.get())));
	}

	public static void dropPackageOnly(ServerLevel level, Vec3 position, ItemStack box) {
		if (level != null && PackageItem.isPackage(box)) {
			PackageEntity packageEntity = PackageEntity.fromItemStack(level, position, box.copy());
			packageEntity.insertionDelay = 0;
			level.addFreshEntity(packageEntity);
		}
	}

}
