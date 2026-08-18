package com.nobodiiiii.createbiotech.content.allay.logistics.courier;

import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.content.allay.block.allayport.AllayPortBlockEntity;
import com.nobodiiiii.createbiotech.content.allay.entity.courier.AllayCourierEntity;
import com.nobodiiiii.createbiotech.registry.CBItems;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class AllayCourierDeliveryService {

	private AllayCourierDeliveryService() {}

	public record DeliveryResult(boolean handled, boolean returnCarrier, boolean packageDelivered) {
		static DeliveryResult done() {
			return done(true);
		}

		static DeliveryResult done(boolean packageDelivered) {
			return new DeliveryResult(true, false, packageDelivered);
		}

		static DeliveryResult returning() {
			return returning(true);
		}

		static DeliveryResult returning(boolean packageDelivered) {
			return new DeliveryResult(true, true, packageDelivered);
		}

		static DeliveryResult failed() {
			return new DeliveryResult(true, false, false);
		}

		static DeliveryResult unhandled() {
			return new DeliveryResult(false, false, false);
		}
	}

	public static DeliveryResult finishDelivery(
		MinecraftServer server,
		ItemStack box,
		AllayCourierEntity.Mission mission,
		AllayCourierReturnMode returnMode,
		@Nullable ResourceKey<Level> targetDimension,
		@Nullable BlockPos targetAllayPortPos,
		@Nullable UUID targetAllayPortSubLevelId,
		@Nullable UUID targetPlayerId,
		@Nullable ServerLevel currentLevel,
		Vec3 currentPosition,
		Vec3 landingTarget
	) {
		ServerLevel targetLevel = resolveTargetLevel(server, targetDimension, targetAllayPortPos, targetPlayerId);
		AllayPortBlockEntity targetAllayPort = resolveTargetAllayPort(targetLevel, targetAllayPortPos,
			targetAllayPortSubLevelId);
		ServerPlayer targetPlayer = targetAllayPort == null ? resolveTargetPlayer(server, targetPlayerId, targetAllayPort) : null;

		switch (mission) {
			case PACKAGE_TO_PLAYER -> {
				if (targetPlayer == null) {
					failAndDrop(box, mission, currentLevel, landingTarget);
					return DeliveryResult.failed();
				}
				return finishPlayerDelivery(box, targetPlayer, returnMode);
			}
			case PACKAGE_TO_ALLAY_PORT -> {
				if (targetAllayPort == null) {
					failAndDrop(box, mission, currentLevel, landingTarget);
					return DeliveryResult.failed();
				}
				return finishAllayPortDelivery(box, targetAllayPort, returnMode, landingTarget);
			}
			case CARRIER_RETURN -> {
				if (targetAllayPort != null) {
					if (!targetAllayPort.receiveCarrier()) {
						dropCarrierOnly(currentLevel, landingTarget);
					}
				} else {
					dropCarrierOnly(currentLevel, landingTarget);
				}
				return DeliveryResult.done();
			}
			case CARRIER_RETURN_TO_PLAYER -> {
				if (targetPlayer != null) {
					AllayCourierHelper.deliverCarrier(targetPlayer);
				} else {
					dropCarrierOnly(currentLevel, currentPosition);
				}
				return DeliveryResult.done();
			}
		}
		return DeliveryResult.unhandled();
	}

	private static DeliveryResult finishAllayPortDelivery(ItemStack box, AllayPortBlockEntity targetAllayPort,
		AllayCourierReturnMode returnMode, Vec3 landingTarget) {
		boolean packageAccepted = targetAllayPort.receivePackage(box);
		ServerLevel targetLevel = targetAllayPort.getLevel() instanceof ServerLevel serverLevel ? serverLevel : null;

		if (!packageAccepted) {
			AllayCourierHelper.dropPackageOnly(targetLevel, landingTarget, box);
		}

		return switch (returnMode) {
			case ALWAYS_RETURN -> DeliveryResult.returning();
			case ALWAYS_DOCK -> {
				if (!targetAllayPort.receiveCarrier()) {
					dropCarrierOnly(targetLevel, landingTarget);
				}
				yield DeliveryResult.done();
			}
			case RETURN_WHEN_UNABLE -> targetAllayPort.receiveCarrier()
				? DeliveryResult.done()
				: DeliveryResult.returning();
		};
	}

	private static DeliveryResult finishPlayerDelivery(ItemStack box, ServerPlayer targetPlayer,
		AllayCourierReturnMode returnMode) {
		boolean packageDelivered = AllayCourierHelper.deliverPackage(targetPlayer, box);
		if (packageDelivered)
			CBAdvancements.award(targetPlayer, CBAdvancements.ALLAY_PORT);

		return switch (returnMode) {
			case ALWAYS_RETURN -> DeliveryResult.returning(packageDelivered);
			case ALWAYS_DOCK -> {
				AllayCourierHelper.deliverCarrier(targetPlayer);
				yield DeliveryResult.done(packageDelivered);
			}
			case RETURN_WHEN_UNABLE -> {
				if (!packageDelivered || !AllayCourierHelper.canReceiveCarrier(targetPlayer)) {
					yield DeliveryResult.returning(packageDelivered);
				}
				AllayCourierHelper.deliverCarrier(targetPlayer);
				yield DeliveryResult.done(packageDelivered);
			}
		};
	}

	public static void failAndDrop(
		ItemStack box,
		AllayCourierEntity.Mission mission,
		@Nullable ServerLevel currentLevel,
		Vec3 dropPos
	) {
		if (currentLevel != null) {
			if (mission == AllayCourierEntity.Mission.CARRIER_RETURN
				|| mission == AllayCourierEntity.Mission.CARRIER_RETURN_TO_PLAYER) {
				dropCarrierOnly(currentLevel, dropPos);
			} else {
				AllayCourierHelper.dropPackage(currentLevel, dropPos, box);
			}
			currentLevel.playSound(null, BlockPos.containing(dropPos),
				SoundEvents.ITEM_FRAME_BREAK, SoundSource.NEUTRAL, 0.7f, 0.9f);
		}
	}

	public static void spawnDeliveryParticles(@Nullable ServerLevel level, Vec3 pos) {
		if (level != null) {
			level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z,
				10, 0.15, 0.15, 0.15, 0.01);
		}
	}

	public static void dropCarrierOnly(@Nullable ServerLevel level, Vec3 pos) {
		if (level == null) return;
		level.addFreshEntity(new ItemEntity(level, pos.x, pos.y, pos.z,
			new ItemStack(CBItems.ALLAY_COURIER.get())));
		level.playSound(null, BlockPos.containing(pos),
			SoundEvents.ITEM_FRAME_BREAK, SoundSource.NEUTRAL, 0.7f, 0.9f);
	}

	public static @Nullable ServerLevel resolveTargetLevel(
		net.minecraft.server.MinecraftServer server,
		@Nullable ResourceKey<Level> targetDimension,
		@Nullable BlockPos targetAllayPortPos,
		@Nullable UUID targetPlayerId
	) {
		if (targetAllayPortPos != null && targetDimension != null) {
			return server.getLevel(targetDimension);
		}
		ServerPlayer player = resolvePlayer(server, targetPlayerId);
		if (player != null) {
			return player.serverLevel();
		}
		return targetDimension != null ? server.getLevel(targetDimension) : null;
	}

	public static @Nullable AllayPortBlockEntity resolveTargetAllayPort(
		@Nullable ServerLevel level, @Nullable BlockPos pos, @Nullable UUID subLevelId
	) {
		if (level == null || pos == null) return null;
		if (!SubLevelCompat.matchesSpace(level, pos, subLevelId)) return null;
		return level.getBlockEntity(pos) instanceof AllayPortBlockEntity be ? be : null;
	}

	public static @Nullable ServerPlayer resolveTargetPlayer(
		net.minecraft.server.MinecraftServer server, @Nullable UUID targetPlayerId,
		@Nullable AllayPortBlockEntity allayPort
	) {
		if (allayPort != null) return null;
		return resolvePlayer(server, targetPlayerId);
	}

	public static @Nullable ServerPlayer resolvePlayer(
		net.minecraft.server.MinecraftServer server, @Nullable UUID playerId
	) {
		if (playerId == null) return null;
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		return player != null && player.isAlive() ? player : null;
	}
}
