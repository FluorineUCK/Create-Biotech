package com.yision.allay.logistics.courier;

import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.yision.allay.block.allayport.AllayPortBlockEntity;
import com.yision.allay.logistics.address.AllayAddressRules;
import com.yision.allay.block.allayport.AllayPortTargetRegistry;
import com.yision.allay.block.allayport.AllayPortTargetRegistry.TargetLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Predicate;

public final class AllayCourierDispatchService {
	private AllayCourierDispatchService() {}

	public static @Nullable AllayCourierTarget resolvePackageTarget(ServerLevel level, ItemStack box,
		Vec3 origin, @Nullable ResourceKey<Level> sourceDimension, @Nullable BlockPos sourcePos) {
		return resolvePackageTarget(level, box, origin, sourceDimension, sourcePos, target -> true);
	}

	public static @Nullable AllayCourierTarget resolvePackageTarget(ServerLevel level, ItemStack box,
		Vec3 origin, @Nullable ResourceKey<Level> sourceDimension, @Nullable BlockPos sourcePos,
		Predicate<AllayCourierTarget.AllayPortTarget> allayPortPredicate) {
		if (!PackageItem.isPackage(box)) {
			return null;
		}

		String rawAddress = PackageItem.getAddress(box);
		String address = AllayAddressRules.normalize(rawAddress);
		if (address.isBlank()) {
			return null;
		}

		if (AllayAddressRules.isExplicitPlayerAddress(rawAddress)) {
			return findPlayer(level, AllayAddressRules.explicitPlayerName(rawAddress), box, origin);
		}

		AllayCourierTarget.AllayPortTarget allayPort =
			findAllayPortExcludingSource(level, address, box, origin, sourceDimension, sourcePos,
				allayPortPredicate);
		return allayPort != null ? allayPort : findPlayer(level, address, box, origin);
	}

	public static boolean dispatchFromPlayer(ServerPlayer player, ItemStack box,
		Vec3 spawnPosition, Vec3 launchDirection) {
		ServerLevel level = player.serverLevel();
		AllayCourierTarget target = resolvePackageTarget(level, box, spawnPosition, null, null);
		if (target == null) {
			return false;
		}

		UUID taskId = UUID.randomUUID();
		AllayCourierTask task;
		if (target instanceof AllayCourierTarget.AllayPortTarget allayPortTarget) {
			task = AllayCourierTask.forPackageToAllayPort(taskId, box, level,
				allayPortTarget.dimension(), allayPortTarget.pos(), allayPortTarget.subLevelId(),
				spawnPosition, launchDirection, null, null, null, player.getUUID(),
				AllayCourierReturnMode.DEFAULT_FOR_PLAYER_LAUNCH);
		} else if (target instanceof AllayCourierTarget.PlayerTarget playerTarget) {
			task = AllayCourierTask.forPackageToPlayer(taskId, box, level,
				playerTarget.playerId(), playerTarget.dimension(), spawnPosition, launchDirection,
				null, null, null, player.getUUID(), AllayCourierReturnMode.DEFAULT_FOR_PLAYER_LAUNCH);
		} else {
			return false;
		}

		if (!AllayCourierTaskManager.addTask(level.getServer(), task)) {
			return false;
		}
		CBAdvancements.award(player, CBAdvancements.ALLAY_COURIER);
		return true;
	}

	private static @Nullable AllayCourierTarget.PlayerTarget findPlayer(ServerLevel level, String playerName,
		ItemStack box, Vec3 origin) {
		ServerPlayer player = AllayCourierDimensionRules.allowCrossDimensionDelivery()
			? AllayCourierHelper.findTargetPlayerAnyDimension(level, playerName)
			: AllayCourierHelper.findTargetPlayer(level, playerName);
		if (player == null) {
			return null;
		}
		AllayCourierTarget.PlayerTarget target =
			new AllayCourierTarget.PlayerTarget(player.getUUID(), player.serverLevel().dimension());
		return canReceivePackageTarget(level, target, box) && withinDeliveryDistance(level, player.serverLevel(),
			player.position(), origin) ? target : null;
	}

	private static @Nullable AllayCourierTarget.AllayPortTarget findAllayPortExcludingSource(ServerLevel level,
		String address, ItemStack box, Vec3 origin, @Nullable ResourceKey<Level> sourceDimension,
		@Nullable BlockPos sourcePos, Predicate<AllayCourierTarget.AllayPortTarget> allayPortPredicate) {
		TargetLocation location = AllayPortTargetRegistry.findMatchingAnyDimension(level, address, origin,
			sourceDimension, sourcePos, target -> AllayCourierDimensionRules.canTarget(level, target.dimension())
					&& canReceiveAllayPortTarget(level, target, box)
					&& withinDeliveryDistance(level, target, origin)
					&& allayPortPredicate.test(new AllayCourierTarget.AllayPortTarget(
						target.dimension(), target.pos(), target.subLevelId())));
		if (location == null) {
			return null;
		}
		return new AllayCourierTarget.AllayPortTarget(location.dimension(), location.pos(),
			location.subLevelId());
	}

	private static boolean withinDeliveryDistance(ServerLevel originLevel, TargetLocation target, Vec3 origin) {
		ServerLevel targetLevel = originLevel.getServer().getLevel(target.dimension());
		if (targetLevel == null || !originLevel.dimension().equals(target.dimension())) {
			return targetLevel != null;
		}
		UUID originSpace = SubLevelCompat.getSpaceId(originLevel, BlockPos.containing(origin));
		if (!java.util.Objects.equals(originSpace, target.subLevelId())) {
			return true;
		}
		return withinDeliveryDistance(originLevel, targetLevel, Vec3.atCenterOf(target.pos()), origin);
	}

	private static boolean withinDeliveryDistance(ServerLevel originLevel, ServerLevel targetLevel,
		Vec3 targetPosition, Vec3 origin) {
		if (originLevel != targetLevel) {
			return true;
		}
		UUID originSpace = SubLevelCompat.getSpaceId(originLevel, BlockPos.containing(origin));
		UUID targetSpace = SubLevelCompat.getSpaceId(targetLevel, BlockPos.containing(targetPosition));
		if (!java.util.Objects.equals(originSpace, targetSpace)) {
			return true;
		}
		double maximum = CBConfigs.SERVER.allayCourier.maxDeliveryDistance.get();
		return maximum <= 0 || origin.distanceToSqr(targetPosition) <= maximum * maximum;
	}

	public static boolean canReceivePackageTarget(ServerLevel level, AllayCourierTarget target, ItemStack box) {
		if (!AllayCourierDimensionRules.canTarget(level, target.dimension())) {
			return false;
		}
		if (target instanceof AllayCourierTarget.PlayerTarget playerTarget) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerTarget.playerId());
			return player != null && player.isAlive()
				&& player.serverLevel().dimension().equals(playerTarget.dimension());
		}
		if (target instanceof AllayCourierTarget.AllayPortTarget allayPortTarget) {
			return canReceiveAllayPortTarget(level,
				new TargetLocation(allayPortTarget.dimension(), allayPortTarget.pos(),
					allayPortTarget.subLevelId(), ""), box);
		}
		return false;
	}

	private static boolean canReceiveAllayPortTarget(ServerLevel level, TargetLocation target, ItemStack box) {
		ServerLevel targetLevel = level.getServer().getLevel(target.dimension());
		if (targetLevel == null) {
			return false;
		}
		if (!SubLevelCompat.matchesSpace(targetLevel, target.pos(), target.subLevelId())) {
			return false;
		}
		BlockEntity blockEntity = targetLevel.getBlockEntity(target.pos());
		return blockEntity instanceof AllayPortBlockEntity allayPort && allayPort.acceptsPackages;
	}
}
