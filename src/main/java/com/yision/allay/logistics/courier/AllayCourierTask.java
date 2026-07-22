package com.yision.allay.logistics.courier;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import com.yision.allay.block.allayport.AllayPortBlock;
import com.yision.allay.block.allayport.AllayPortBlockEntity;
import com.yision.allay.entity.courier.AllayCourierEntity;
import com.yision.allay.logistics.courier.hud.AllayCourierHudSync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

public final class AllayCourierTask {

	public static final int TELEPORT_AFTER_TICKS = 300;
	public static final int FORCE_ARRIVAL_TICKS = 600;

	private static final int TAKEOFF_PHASE_TICKS = 20;
	private static final int LOST_TARGET_RESCAN_INTERVAL_TICKS = 20;
	private static final double LOST_TARGET_COMPLETION_DISTANCE = 0.75;
	private static final double PRECISE_APPROACH_DISTANCE = 4.0;
	private static final float VANILLA_ALLAY_CRUISE_SPEED = 2.25f;
	private static final double VANILLA_ALLAY_APPROACH_SPEED = 1.5;
	private static final double PORT_HIGH_APPROACH_OFFSET = 3.25;
	private static final double PORT_HIGH_APPROACH_HEIGHT = 2.25;
	private static final double PORT_LINEUP_OFFSET = 2.5;
	private static final double PORT_MOUTH_OFFSET = 0.62;
	private static final double PORT_INSIDE_OFFSET = -0.15;
	private static final double PORT_DEPARTURE_CLEAR_OFFSET = 2.75;
	private static final double PORT_DEPARTURE_CLEAR_HEIGHT = 0.55;
	private static final double PORT_CAPTURE_RADIUS = 1.15;
	private static final double PORT_STAGE_COMPLETION_DISTANCE = 0.055;
	private static final double PORT_ALIGNMENT_SPEED = 0.22;
	private static final double PORT_ALIGNMENT_ACCELERATION = 0.045;
	private static final double PORT_ENTRY_SPEED = 0.16;
	private static final double PORT_ENTRY_ACCELERATION = 0.035;
	private static final double PORT_DEPARTURE_SPEED = 0.19;
	private static final double PORT_DEPARTURE_ACCELERATION = 0.04;
	private static final double PORT_ROUTE_CLEARANCE_HEIGHT = 3.0;
	private static final double PORT_ROUTE_SAFETY_INFLATION = 0.8;
	private static final int PORT_TURNAROUND_PAUSE_TICKS = 8;
	private static final int ESTIMATED_GUIDED_PORT_ARRIVAL_TICKS = 34;
	private static final int ESTIMATED_GUIDED_PORT_DEPARTURE_TICKS = 24;
	private static final double PLAYER_COMPLETION_DISTANCE = 1.5;
	private static final double PLAYER_TARGET_HEIGHT = 1.2;
	private static final double PLAYER_FORWARD_OFFSET = 0.15;
	private static final double ESTIMATED_CRUISE_BLOCKS_PER_TICK = 0.55;
	private static final double ESTIMATED_APPROACH_BLOCKS_PER_TICK = 0.24;
	private static final int ESTIMATED_ACCELERATION_TICKS = 4;
	private static final int GREETING_ETA_TICKS = 60;

	private final UUID id;
	private ItemStack box;
	private ResourceKey<Level> currentDimension;
	private ResourceKey<Level> targetDimension;
	private @Nullable BlockPos sourceAllayPortPos;
	private @Nullable BlockPos targetAllayPortPos;
	private @Nullable UUID sourceAllayPortSubLevelId;
	private @Nullable UUID targetAllayPortSubLevelId;
	private @Nullable UUID targetPlayerId;
	private @Nullable UUID sourcePlayerId;
	private @Nullable ResourceKey<Level> sourceDimension;
	private final String sourceAddress;
	private AllayCourierReturnMode returnMode;
	private AllayCourierEntity.Mission mission;
	private AllayCourierEntity.Phase phase;
	private Vec3 position;
	private Vec3 launchDirection;
	private PortMotion portMotion = PortMotion.NONE;
	private @Nullable Vec3 portDepartureOrigin;
	private @Nullable Vec3 portDepartureClearTarget;
	private @Nullable BlockPos portDepartureAllayPortPos;
	private @Nullable UUID portDepartureAllayPortSubLevelId;
	private int phaseTicks;
	private int portMotionTicks;
	private int deliveryElapsedTicks;
	private boolean teleportedNearTarget;
	private boolean forceArrivalPending;
	private @Nullable ResourceKey<Level> lastKnownTargetDimension;
	private @Nullable Vec3 lastKnownTargetPosition;
	private int lostTargetTicks;
	private boolean placeCourierWhenRemoved;
	private boolean relocatedThisTick;
	private boolean removed;
	private boolean worldCoordinates = true;

	private AllayCourierTask(
		UUID id, ItemStack box,
		ResourceKey<Level> currentDimension, ResourceKey<Level> targetDimension,
		@Nullable BlockPos sourceAllayPortPos, @Nullable BlockPos targetAllayPortPos,
		@Nullable UUID sourceAllayPortSubLevelId, @Nullable UUID targetAllayPortSubLevelId,
		@Nullable UUID targetPlayerId,
		@Nullable UUID sourcePlayerId, @Nullable ResourceKey<Level> sourceDimension,
		@Nullable String sourceAddress,
		AllayCourierReturnMode returnMode,
		AllayCourierEntity.Mission mission, Vec3 position, Vec3 launchDirection
	) {
		this.id = id;
		this.box = box.copy();
		this.currentDimension = currentDimension;
		this.targetDimension = targetDimension;
		this.sourceAllayPortPos = sourceAllayPortPos != null ? sourceAllayPortPos.immutable() : null;
		this.targetAllayPortPos = targetAllayPortPos != null ? targetAllayPortPos.immutable() : null;
		this.sourceAllayPortSubLevelId = sourceAllayPortSubLevelId;
		this.targetAllayPortSubLevelId = targetAllayPortSubLevelId;
		this.targetPlayerId = targetPlayerId;
		this.sourcePlayerId = sourcePlayerId;
		this.sourceDimension = sourceDimension;
		this.sourceAddress = sourceAddress == null ? "" : sourceAddress.trim();
		this.returnMode = returnMode == null ? defaultReturnMode(sourceAllayPortPos, sourcePlayerId) : returnMode;
		this.mission = mission;
		this.phase = AllayCourierEntity.Phase.TAKEOFF;
		this.position = position;
		this.launchDirection = normalizedDirection(launchDirection);
		this.lastKnownTargetDimension = targetDimension;
	}

	public static AllayCourierTask forPackageToAllayPort(
		UUID id, ItemStack box,
		ServerLevel spawnLevel, ResourceKey<Level> targetDimension, BlockPos targetAllayPortPos,
		@Nullable UUID targetAllayPortSubLevelId,
		Vec3 spawnPos, Vec3 launchDirection,
		@Nullable ResourceKey<Level> sourceDimension, @Nullable BlockPos sourceAllayPortPos,
		@Nullable UUID sourceAllayPortSubLevelId,
		@Nullable UUID sourcePlayerId,
		AllayCourierReturnMode returnMode
	) {
		return new AllayCourierTask(id, box, spawnLevel.dimension(), targetDimension,
			sourceAllayPortPos, targetAllayPortPos, sourceAllayPortSubLevelId,
			targetAllayPortSubLevelId, null, sourcePlayerId, sourceDimension,
			sourceAddress(spawnLevel, sourceDimension, sourceAllayPortPos, sourceAllayPortSubLevelId),
			returnMode, AllayCourierEntity.Mission.PACKAGE_TO_ALLAY_PORT,
			spawnPos, launchDirection);
	}

	public static AllayCourierTask forPackageToPlayer(
		UUID id, ItemStack box,
		ServerLevel spawnLevel, UUID targetPlayerId, ResourceKey<Level> targetDimension,
		Vec3 spawnPos, Vec3 launchDirection,
		@Nullable ResourceKey<Level> sourceDimension, @Nullable BlockPos sourceAllayPortPos,
		@Nullable UUID sourceAllayPortSubLevelId,
		@Nullable UUID sourcePlayerId,
		AllayCourierReturnMode returnMode
	) {
		return new AllayCourierTask(id, box, spawnLevel.dimension(), targetDimension,
			sourceAllayPortPos, null, sourceAllayPortSubLevelId, null, targetPlayerId,
			sourcePlayerId, sourceDimension,
			sourceAddress(spawnLevel, sourceDimension, sourceAllayPortPos, sourceAllayPortSubLevelId),
			returnMode, AllayCourierEntity.Mission.PACKAGE_TO_PLAYER,
			spawnPos, launchDirection);
	}

	public static AllayCourierTask forCarrierReturn(
		UUID id, ServerLevel spawnLevel,
		ResourceKey<Level> targetDimension, BlockPos targetAllayPortPos,
		@Nullable UUID targetAllayPortSubLevelId,
		Vec3 spawnPos, Vec3 launchDirection
	) {
		return new AllayCourierTask(id, ItemStack.EMPTY, spawnLevel.dimension(), targetDimension,
			null, targetAllayPortPos, null, targetAllayPortSubLevelId, null,
			null, null, "", AllayCourierReturnMode.DEFAULT_FOR_PORT, AllayCourierEntity.Mission.CARRIER_RETURN,
			spawnPos, launchDirection);
	}

	public static AllayCourierTask forCarrierReturnToPlayer(
		UUID id, ServerLevel spawnLevel, UUID targetPlayerId,
		ResourceKey<Level> targetDimension,
		Vec3 spawnPos, Vec3 launchDirection
	) {
		return new AllayCourierTask(id, ItemStack.EMPTY, spawnLevel.dimension(), targetDimension,
			null, null, null, null, targetPlayerId,
			null, null, "", AllayCourierReturnMode.DEFAULT_FOR_PORT,
			AllayCourierEntity.Mission.CARRIER_RETURN_TO_PLAYER, spawnPos, launchDirection);
	}

	public AllayCourierTask departFromAllayPort(AllayPortBlockEntity allayPort) {
		beginPortDeparture(allayPort, false);
		return this;
	}

	/** Migrates pre-Sable tasks before the manager performs any world-position chunk checks. */
	public boolean prepareForTick(MinecraftServer server) {
		if (worldCoordinates) {
			return false;
		}
		ServerLevel currentLevel = server.getLevel(currentDimension);
		if (currentLevel == null) {
			return false;
		}

		if (sourceAllayPortPos != null && sourceDimension != null) {
			ServerLevel sourceLevel = server.getLevel(sourceDimension);
			if (sourceLevel != null) {
				sourceAllayPortSubLevelId = SubLevelCompat.getSpaceId(sourceLevel, sourceAllayPortPos);
			}
		}
		if (targetAllayPortPos != null) {
			ServerLevel targetLevel = server.getLevel(targetDimension);
			if (targetLevel != null) {
				targetAllayPortSubLevelId = SubLevelCompat.getSpaceId(targetLevel, targetAllayPortPos);
			}
		}

		BlockPos legacyPositionBlock = BlockPos.containing(position);
		SubLevelAccess legacyPositionSubLevel = SubLevelCompat.getContaining(currentLevel,
			legacyPositionBlock);
		boolean invalidLegacyPosition = !SubLevelCompat.isValidSpacePosition(currentLevel, legacyPositionBlock);
		if (legacyPositionSubLevel != null) {
			position = SubLevelCompat.toWorld(legacyPositionSubLevel, position);
			invalidLegacyPosition = false;
		}
		AllayPortBlockEntity departurePort = findLegacyDeparturePort(currentLevel);
		boolean legacyDepartureSpaceResolved = false;
		if (departurePort == null && portDepartureOrigin != null) {
			BlockPos legacyDepartureBlock = BlockPos.containing(portDepartureOrigin);
			if (SubLevelCompat.getContaining(currentLevel, legacyDepartureBlock) != null) {
				projectLegacyDepartureFrame(currentLevel, legacyDepartureBlock);
				portDepartureAllayPortPos = legacyDepartureBlock.immutable();
				portDepartureAllayPortSubLevelId = SubLevelCompat.getSpaceId(currentLevel,
					legacyDepartureBlock);
				legacyDepartureSpaceResolved = true;
			} else if (isPackageDeliveryMission() && sourceAllayPortPos != null
				&& currentDimension.equals(sourceDimension)
				&& SubLevelCompat.getContaining(currentLevel, sourceAllayPortPos) != null) {
				projectLegacyDepartureFrame(currentLevel, sourceAllayPortPos);
				portDepartureAllayPortPos = sourceAllayPortPos.immutable();
				portDepartureAllayPortSubLevelId = SubLevelCompat.getSpaceId(currentLevel,
					sourceAllayPortPos);
				legacyDepartureSpaceResolved = true;
			} else if (!SubLevelCompat.isValidSpacePosition(currentLevel, legacyDepartureBlock)) {
				// The old source sublevel vanished, so no safe transform exists. Skip the stale
				// plot-local docking leg and continue toward the live target in world coordinates.
				clearPortDeparture();
				portMotion = PortMotion.NONE;
				portMotionTicks = 0;
			}
		}
		if (departurePort != null) {
			portDepartureAllayPortPos = departurePort.getBlockPos().immutable();
			portDepartureAllayPortSubLevelId = SubLevelCompat.getSpaceId(currentLevel,
				portDepartureAllayPortPos);
			portDepartureOrigin = allayPortCenter(departurePort);
			portDepartureClearTarget = portDepartureTarget(departurePort);
			launchDirection = portOutward(departurePort);
		} else if (!legacyDepartureSpaceResolved && legacyPositionSubLevel != null) {
			launchDirection = normalizedDirection(SubLevelCompat.localNormalToWorld(
				legacyPositionSubLevel, launchDirection));
		} else if (!legacyDepartureSpaceResolved && isPackageDeliveryMission()
			&& sourceAllayPortPos != null && currentDimension.equals(sourceDimension)
			&& SubLevelCompat.matchesSpace(currentLevel, sourceAllayPortPos,
				sourceAllayPortSubLevelId)) {
			launchDirection = normalizedDirection(SubLevelCompat.localNormalToWorld(
				currentLevel, sourceAllayPortPos, launchDirection));
		}

		ResolvedTarget resolvedTarget = resolveTarget(server);
		if (resolvedTarget != null) {
			if (invalidLegacyPosition) {
				currentDimension = resolvedTarget.level.dimension();
				position = resolvedTarget.allayPort != null
					? portHighApproach(resolvedTarget.allayPort)
					: landingTarget(null, resolvedTarget.player).add(0, 4.0, 0);
				phase = AllayCourierEntity.Phase.CRUISE;
				phaseTicks = 0;
				portMotion = PortMotion.NONE;
				clearPortDeparture();
				portMotionTicks = 0;
				teleportedNearTarget = true;
				forceArrivalPending = false;
			}
			rememberResolvedTarget(resolvedTarget);
		} else if (targetAllayPortPos != null) {
			ServerLevel targetLevel = server.getLevel(targetDimension);
			if (targetLevel != null && (!SubLevelCompat.isValidSpacePosition(targetLevel, targetAllayPortPos)
				|| SubLevelCompat.getContaining(targetLevel, targetAllayPortPos) != null)) {
				// Never reinterpret an unresolved plot coordinate as an outer-world fallback.
				lastKnownTargetDimension = null;
				lastKnownTargetPosition = null;
			}
		}
		if (invalidLegacyPosition && resolvedTarget == null) {
			position = Vec3.atBottomCenterOf(currentLevel.getSharedSpawnPos()).add(0, 2.0, 0);
			phase = AllayCourierEntity.Phase.CRUISE;
			phaseTicks = 0;
			portMotion = PortMotion.NONE;
			clearPortDeparture();
			portMotionTicks = 0;
		}
		worldCoordinates = true;
		return true;
	}

	private @Nullable AllayPortBlockEntity findLegacyDeparturePort(ServerLevel currentLevel) {
		if (!isGuidedPortDeparture()) {
			return null;
		}
		if (portDepartureOrigin != null
			&& currentLevel.getBlockEntity(BlockPos.containing(portDepartureOrigin))
				instanceof AllayPortBlockEntity departurePort) {
			return departurePort;
		}
		if (isPackageDeliveryMission() && sourceAllayPortPos != null
			&& currentDimension.equals(sourceDimension)
			&& currentLevel.getBlockEntity(sourceAllayPortPos) instanceof AllayPortBlockEntity sourcePort) {
			return sourcePort;
		}
		return null;
	}

	public void tick(MinecraftServer server, @Nullable AllayCourierEntity entity) {
		if (removed) {
			return;
		}
		relocatedThisTick = false;
		Vec3 previousPosition = position;

		ServerLevel currentLevel = server.getLevel(currentDimension);
		if (currentLevel == null) {
			AllayCourierHudSync.onFailed(server, this);
			markRemoved();
			return;
		}

		boolean hasActiveEntity = entity != null && entity.isAlive() && entity.level() == currentLevel;
		if (hasActiveEntity) {
			position = entity.position();
		}
		deliveryElapsedTicks++;
		ResolvedTarget target = resolveTarget(server);
		if (target == null) {
			target = tickLostTarget(server, currentLevel, previousPosition, entity, hasActiveEntity);
			if (target == null) {
				return;
			}
		}
		rememberResolvedTarget(target);

		if (forceArrivalPending) {
			forceArrivalPending = false;
			doFinishDeliveryAt(server, currentLevel);
			return;
		}

		if (deliveryElapsedTicks > FORCE_ARRIVAL_TICKS && !isGuidedPortArrival()) {
			if (hasActiveEntity && teleportToForcedArrivalTarget(server)) {
				return;
			}
			forceArrive(server, currentLevel);
			return;
		}

		if (!teleportedNearTarget && deliveryElapsedTicks >= TELEPORT_AFTER_TICKS) {
			teleportNearTarget(server);
			return;
		}

		startTargetWavingIfArriving(server, target.allayPort);
		if (tickPortDeparture(currentLevel, entity, hasActiveEntity)) {
			return;
		}

		if (!target.level.dimension().equals(currentDimension)) {
			tickCrossDimensionExit(entity, hasActiveEntity);
			return;
		}

		tickTowardTarget(server, currentLevel, target, previousPosition, entity, hasActiveEntity);
	}

	private @Nullable ResolvedTarget tickLostTarget(MinecraftServer server, ServerLevel currentLevel,
		Vec3 previousPosition, @Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		if (lostTargetTicks == 0) {
			stopGuidedArrivalAfterTargetLoss(entity, hasActiveEntity);
		}
		lostTargetTicks++;

		if (lostTargetTicks == 1 || lostTargetTicks % LOST_TARGET_RESCAN_INTERVAL_TICKS == 0) {
			ResolvedTarget replacement = scanForReplacementTarget(server, currentLevel);
			if (replacement != null) {
				return replacement;
			}
		}

		if (tickPortDeparture(currentLevel, entity, hasActiveEntity)) {
			return null;
		}
		tickTowardLastKnownTarget(server, currentLevel, previousPosition, entity, hasActiveEntity);
		return null;
	}

	private void stopGuidedArrivalAfterTargetLoss(@Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		if (!isGuidedPortArrival()) {
			return;
		}
		portMotion = PortMotion.NONE;
		portMotionTicks = 0;
		if (hasActiveEntity) {
			entity.clearCourierDestination();
		}
	}

	private @Nullable ResolvedTarget scanForReplacementTarget(MinecraftServer server, ServerLevel currentLevel) {
		if (!PackageItem.isPackage(box)
			|| mission == AllayCourierEntity.Mission.CARRIER_RETURN
			|| mission == AllayCourierEntity.Mission.CARRIER_RETURN_TO_PLAYER) {
			return null;
		}

		AllayCourierTarget replacement = AllayCourierDispatchService.resolvePackageTarget(
			currentLevel, box, position, sourceDimension, sourceAllayPortPos,
			this::isSafeReplacementAllayPort);
		if (replacement instanceof AllayCourierTarget.AllayPortTarget allayPortTarget) {
			targetDimension = allayPortTarget.dimension();
			targetAllayPortPos = allayPortTarget.pos().immutable();
			targetAllayPortSubLevelId = allayPortTarget.subLevelId();
			targetPlayerId = null;
			mission = AllayCourierEntity.Mission.PACKAGE_TO_ALLAY_PORT;
		} else if (replacement instanceof AllayCourierTarget.PlayerTarget playerTarget) {
			targetDimension = playerTarget.dimension();
			targetAllayPortPos = null;
			targetAllayPortSubLevelId = null;
			targetPlayerId = playerTarget.playerId();
			mission = AllayCourierEntity.Mission.PACKAGE_TO_PLAYER;
		} else {
			return null;
		}

		deliveryElapsedTicks = 0;
		teleportedNearTarget = false;
		forceArrivalPending = false;
		phaseTicks = 0;
		if (!isGuidedPortDeparture()) {
			phase = AllayCourierEntity.Phase.CRUISE;
		}
		return resolveTarget(server);
	}

	private boolean isSafeReplacementAllayPort(AllayCourierTarget.AllayPortTarget candidate) {
		return targetAllayPortPos == null
			|| !targetDimension.equals(candidate.dimension())
			|| !targetAllayPortPos.equals(candidate.pos())
			|| Objects.equals(targetAllayPortSubLevelId, candidate.subLevelId());
	}

	private void rememberResolvedTarget(ResolvedTarget target) {
		lastKnownTargetDimension = target.level.dimension();
		lastKnownTargetPosition = landingTarget(target.allayPort, target.player);
		if (target.player != null) {
			targetDimension = target.player.serverLevel().dimension();
		}
		lostTargetTicks = 0;
	}

	private void tickTowardLastKnownTarget(MinecraftServer server, ServerLevel currentLevel,
		Vec3 previousPosition, @Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		if (lastKnownTargetDimension == null || lastKnownTargetPosition == null) {
			lastKnownTargetDimension = currentDimension;
			lastKnownTargetPosition = position;
		}

		if (lostTargetTicks > FORCE_ARRIVAL_TICKS) {
			if (recoverBeforePlacement(server, currentLevel)) {
				return;
			}
			placeAtLastKnownTarget(server, currentLevel);
			return;
		}

		if (!lastKnownTargetDimension.equals(currentDimension)) {
			if (lostTargetTicks >= TELEPORT_AFTER_TICKS) {
				teleportNearLastKnownTarget(server, currentLevel);
			} else {
				tickCrossDimensionExit(entity, hasActiveEntity);
			}
			return;
		}

		Vec3 target = lastKnownTargetPosition;
		if (position.distanceTo(target) <= LOST_TARGET_COMPLETION_DISTANCE
			|| segmentDistanceToPointSqr(previousPosition, position, target)
				<= LOST_TARGET_COMPLETION_DISTANCE * LOST_TARGET_COMPLETION_DISTANCE) {
			if (recoverBeforePlacement(server, currentLevel)) {
				return;
			}
			position = target;
			placeAtLastKnownTarget(server, currentLevel);
			return;
		}

		double distance = position.distanceTo(target);
		if (distance <= PRECISE_APPROACH_DISTANCE) {
			phase = AllayCourierEntity.Phase.LANDING;
		} else if (phase == AllayCourierEntity.Phase.TAKEOFF && phaseTicks >= TAKEOFF_PHASE_TICKS) {
			phase = AllayCourierEntity.Phase.CRUISE;
			phaseTicks = 0;
		}
		phaseTicks++;

		if (!hasActiveEntity) {
			return;
		}
		if (distance <= PRECISE_APPROACH_DISTANCE) {
			entity.approachPreciselyAsVanillaAllay(target, VANILLA_ALLAY_APPROACH_SPEED);
		} else {
			entity.flyDirectlyAsVanillaAllay(target, VANILLA_ALLAY_CRUISE_SPEED);
		}
	}

	private boolean recoverBeforePlacement(MinecraftServer server, ServerLevel currentLevel) {
		ResolvedTarget replacement = scanForReplacementTarget(server, currentLevel);
		if (replacement == null) {
			return false;
		}
		rememberResolvedTarget(replacement);
		return true;
	}

	private void teleportNearLastKnownTarget(MinecraftServer server, ServerLevel originLevel) {
		ServerLevel targetLevel = server.getLevel(lastKnownTargetDimension);
		if (targetLevel == null || lastKnownTargetPosition == null) {
			return;
		}

		Vec3 originPosition = position;
		targetLevel.getChunkAt(BlockPos.containing(lastKnownTargetPosition));
		Vec3 away = position.subtract(lastKnownTargetPosition).multiply(1, 0, 1);
		if (away.lengthSqr() < 1.0E-6) {
			away = launchDirection.scale(-1);
		}
		Vec3 preferredSpawn = lastKnownTargetPosition.add(away.normalize().scale(32.0)).add(0, 4.0, 0);
		position = findTickingPosTowardTarget(targetLevel, preferredSpawn, lastKnownTargetPosition);
		currentDimension = targetLevel.dimension();
		phase = AllayCourierEntity.Phase.CRUISE;
		phaseTicks = 0;
		portMotion = PortMotion.NONE;
		clearPortDeparture();
		portMotionTicks = 0;
		teleportedNearTarget = true;
		relocatedThisTick = true;
		spawnTeleportParticles(originLevel, originPosition);
		spawnTeleportParticles(targetLevel, position);
	}

	private void placeAtLastKnownTarget(MinecraftServer server, ServerLevel fallbackLevel) {
		ServerLevel placementLevel = lastKnownTargetDimension != null
			? server.getLevel(lastKnownTargetDimension)
			: null;
		if (placementLevel == null) {
			placementLevel = fallbackLevel;
			lastKnownTargetDimension = fallbackLevel.dimension();
			lastKnownTargetPosition = position;
		}

		Vec3 placementPosition = lastKnownTargetPosition != null ? lastKnownTargetPosition : position;
		placementLevel.getChunkAt(BlockPos.containing(placementPosition));
		currentDimension = placementLevel.dimension();
		position = placementPosition;
		phase = AllayCourierEntity.Phase.WAITING;
		portMotion = PortMotion.NONE;
		clearPortDeparture();
		portMotionTicks = 0;
		forceArrivalPending = false;
		placeCourierWhenRemoved = true;
		AllayCourierHudSync.onFailed(server, this);
		markRemoved();
	}

	private boolean tickPortDeparture(ServerLevel currentLevel,
		@Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		if (!isGuidedPortDeparture() || portDepartureOrigin == null) {
			return false;
		}
		phase = AllayCourierEntity.Phase.TAKEOFF;
		AllayPortBlockEntity departurePort = resolveDepartureAllayPort(currentLevel);
		if (departurePort != null) {
			portDepartureOrigin = allayPortCenter(departurePort);
			launchDirection = portOutward(departurePort);
			portDepartureClearTarget = portDepartureTarget(departurePort);
		}
		Vec3 outward = launchDirection;
		Vec3 target;
		Vec3 targetVelocity = Vec3.ZERO;
		double speed;
		boolean lockToPath;

		if (portMotion == PortMotion.DEPARTURE_PAUSE) {
			target = departurePort != null ? portInside(departurePort)
				: portDepartureOrigin.add(outward.scale(PORT_INSIDE_OFFSET));
			if (departurePort != null) {
				targetVelocity = portVelocityPerTick(departurePort, portInsideLocal(departurePort));
			}
			speed = PORT_ENTRY_SPEED;
			lockToPath = true;
			if (++portMotionTicks >= PORT_TURNAROUND_PAUSE_TICKS) {
				advancePortMotion(PortMotion.DEPARTURE_MOUTH);
				if (departurePort != null) {
					departurePort.flap(false);
				}
			}
		} else if (portMotion == PortMotion.DEPARTURE_MOUTH) {
			target = departurePort != null ? portMouth(departurePort)
				: portDepartureOrigin.add(outward.scale(PORT_MOUTH_OFFSET));
			if (departurePort != null) {
				targetVelocity = portVelocityPerTick(departurePort, portMouthLocal(departurePort));
			}
			speed = PORT_DEPARTURE_SPEED;
			lockToPath = true;
			if (reachedPortStage(target)) {
				advancePortMotion(PortMotion.DEPARTURE_CLEAR);
				target = portDepartureTarget(departurePort);
				if (departurePort != null) {
					targetVelocity = portVelocityPerTick(departurePort,
						portDepartureTargetLocal(departurePort));
				}
			}
		} else {
			target = portDepartureTarget(departurePort);
			if (departurePort != null) {
				targetVelocity = portVelocityPerTick(departurePort,
					portDepartureTargetLocal(departurePort));
			}
			speed = PORT_DEPARTURE_SPEED;
			lockToPath = true;
			if (reachedPortStage(target)) {
				portMotion = PortMotion.NONE;
				clearPortDeparture();
				portMotionTicks = 0;
				phaseTicks = 0;
				if (hasActiveEntity) {
					entity.clearCourierDestination();
				}
				return false;
			}
		}

		if (hasActiveEntity) {
			entity.guideAlongDockingPath(target, outward, speed,
				PORT_DEPARTURE_ACCELERATION, lockToPath, targetVelocity);
		}
		return true;
	}

	private void tickCrossDimensionExit(@Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		phaseTicks++;
		if (phaseTicks >= TAKEOFF_PHASE_TICKS) {
			phase = AllayCourierEntity.Phase.EXITING_DIMENSION;
		}
		if (hasActiveEntity) {
			Vec3 exitTarget = position.add(launchDirection.scale(24.0)).add(0, 6.0, 0);
			entity.flyDirectlyAsVanillaAllay(exitTarget, VANILLA_ALLAY_CRUISE_SPEED);
		}
	}

	private void tickTowardTarget(MinecraftServer server, ServerLevel currentLevel, ResolvedTarget target,
		Vec3 previousPosition, @Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		phaseTicks++;
		if (target.allayPort != null) {
			tickTowardAllayPort(server, currentLevel, target.allayPort,
				previousPosition, entity, hasActiveEntity);
			return;
		}

		Vec3 landingTarget = landingTarget(null, target.player);
		double distance = position.distanceTo(landingTarget);
		if (hasReachedPlayer(target.player, landingTarget)) {
			doFinishDeliveryAt(server, currentLevel);
			return;
		}

		boolean preciseApproach = distance <= PRECISE_APPROACH_DISTANCE;
		if (preciseApproach) {
			if (phase != AllayCourierEntity.Phase.LANDING) {
				phaseTicks = 0;
			}
			phase = AllayCourierEntity.Phase.LANDING;
		} else if (phase == AllayCourierEntity.Phase.TAKEOFF && phaseTicks >= TAKEOFF_PHASE_TICKS) {
			phase = AllayCourierEntity.Phase.CRUISE;
			phaseTicks = 0;
		}

		if (!hasActiveEntity) {
			return;
		}
		if (preciseApproach) {
			entity.approachPreciselyAsVanillaAllay(landingTarget, VANILLA_ALLAY_APPROACH_SPEED);
		} else {
			entity.flyDirectlyAsVanillaAllay(landingTarget, VANILLA_ALLAY_CRUISE_SPEED);
		}
	}

	private void tickTowardAllayPort(MinecraftServer server, ServerLevel currentLevel,
		AllayPortBlockEntity allayPort, Vec3 previousPosition,
		@Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		if (isGuidedPortArrival()) {
			tickGuidedPortArrival(server, currentLevel, allayPort, entity, hasActiveEntity);
			return;
		}

		Vec3 highApproach = portHighApproach(allayPort);
		if (segmentDistanceToPointSqr(previousPosition, position, highApproach)
			<= PORT_CAPTURE_RADIUS * PORT_CAPTURE_RADIUS) {
			portMotion = PortMotion.ARRIVAL_ALIGN;
			portMotionTicks = 0;
			teleportedNearTarget = true;
			phaseTicks = 0;
			if (hasActiveEntity) {
				entity.clearCourierDestination();
			}
			tickGuidedPortArrival(server, currentLevel, allayPort, entity, hasActiveEntity);
			return;
		}

		double distance = position.distanceTo(highApproach);
		if (distance <= PRECISE_APPROACH_DISTANCE * 2.0) {
			if (phase != AllayCourierEntity.Phase.LANDING) {
				phaseTicks = 0;
			}
			phase = AllayCourierEntity.Phase.LANDING;
		} else if (phase == AllayCourierEntity.Phase.TAKEOFF && phaseTicks >= TAKEOFF_PHASE_TICKS) {
			phase = AllayCourierEntity.Phase.CRUISE;
			phaseTicks = 0;
		}

		if (!hasActiveEntity) {
			return;
		}
		Vec3 cruiseTarget = safePortCruiseTarget(allayPort, highApproach);
		if (position.distanceTo(cruiseTarget) <= PRECISE_APPROACH_DISTANCE) {
			entity.approachPreciselyAsVanillaAllay(cruiseTarget, VANILLA_ALLAY_APPROACH_SPEED);
		} else {
			entity.flyDirectlyAsVanillaAllay(cruiseTarget, VANILLA_ALLAY_CRUISE_SPEED);
		}
	}

	private void tickGuidedPortArrival(MinecraftServer server, ServerLevel currentLevel,
		AllayPortBlockEntity allayPort, @Nullable AllayCourierEntity entity, boolean hasActiveEntity) {
		phase = AllayCourierEntity.Phase.LANDING;
		Vec3 outward = portOutward(allayPort);
		Vec3 target;
		Vec3 targetLocal;
		double speed;
		double acceleration;
		boolean lockToPath;

		if (portMotion == PortMotion.ARRIVAL_ALIGN) {
			targetLocal = portLineupLocal(allayPort);
			target = projectPortPoint(allayPort, targetLocal);
			speed = PORT_ALIGNMENT_SPEED;
			acceleration = PORT_ALIGNMENT_ACCELERATION;
			lockToPath = false;
			if (reachedPortStage(target)) {
				advancePortMotion(PortMotion.ARRIVAL_MOUTH);
				targetLocal = portMouthLocal(allayPort);
				target = projectPortPoint(allayPort, targetLocal);
				speed = PORT_ENTRY_SPEED;
				acceleration = PORT_ENTRY_ACCELERATION;
				lockToPath = true;
			}
		} else if (portMotion == PortMotion.ARRIVAL_MOUTH) {
			targetLocal = portMouthLocal(allayPort);
			target = projectPortPoint(allayPort, targetLocal);
			speed = PORT_ENTRY_SPEED;
			acceleration = PORT_ENTRY_ACCELERATION;
			lockToPath = true;
			if (reachedPortStage(target)) {
				advancePortMotion(PortMotion.ARRIVAL_INSIDE);
				allayPort.flap(true);
				targetLocal = portInsideLocal(allayPort);
				target = projectPortPoint(allayPort, targetLocal);
			}
		} else {
			targetLocal = portInsideLocal(allayPort);
			target = projectPortPoint(allayPort, targetLocal);
			speed = PORT_ENTRY_SPEED;
			acceleration = PORT_ENTRY_ACCELERATION;
			lockToPath = true;
			if (reachedPortStage(target)) {
				if (hasActiveEntity) {
					entity.clearCourierDestination();
					entity.setDeltaMovement(Vec3.ZERO);
				}
				position = target;
				doFinishDeliveryAt(server, currentLevel);
				return;
			}
		}

		if (hasActiveEntity) {
			entity.guideAlongDockingPath(target, outward.scale(-1), speed, acceleration, lockToPath,
				portVelocityPerTick(allayPort, targetLocal));
		}
		portMotionTicks++;
	}

	private void teleportNearTarget(MinecraftServer server) {
		ResolvedTarget target = resolveTarget(server);
		if (target == null) {
			return;
		}
		ServerLevel originLevel = server.getLevel(currentDimension);
		Vec3 originPosition = position;
		if (targetAllayPortPos != null) {
			target.level.getChunkAt(targetAllayPortPos);
		}

		Vec3 waypoint = nearTargetWaypoint(target.allayPort, target.player);
		Vec3 preferredSpawn = computeNearTargetSpawn(target.allayPort, target.player, waypoint);
		position = findTickingPosTowardTarget(target.level, preferredSpawn, waypoint);
		currentDimension = target.level.dimension();
		if (target.player != null) {
			targetDimension = target.player.serverLevel().dimension();
		}
		phase = AllayCourierEntity.Phase.CRUISE;
		phaseTicks = 0;
		portMotion = PortMotion.NONE;
		clearPortDeparture();
		portMotionTicks = 0;
		teleportedNearTarget = true;
		relocatedThisTick = true;
		spawnTeleportParticles(originLevel, originPosition);
		spawnTeleportParticles(target.level, position);
	}

	private boolean teleportToForcedArrivalTarget(MinecraftServer server) {
		ResolvedTarget target = resolveTarget(server);
		if (target == null) {
			return false;
		}
		ServerLevel originLevel = server.getLevel(currentDimension);
		Vec3 originPosition = position;
		if (targetAllayPortPos != null) {
			target.level.getChunkAt(targetAllayPortPos);
		}

		if (target.allayPort != null) {
			position = portHighApproach(target.allayPort);
			portMotion = PortMotion.ARRIVAL_ALIGN;
			portMotionTicks = 0;
			forceArrivalPending = false;
		} else {
			position = landingTarget(null, target.player);
			forceArrivalPending = true;
		}
		currentDimension = target.level.dimension();
		if (target.player != null) {
			targetDimension = target.player.serverLevel().dimension();
		}
		phase = AllayCourierEntity.Phase.LANDING;
		phaseTicks = 0;
		teleportedNearTarget = true;
		relocatedThisTick = true;
		spawnTeleportParticles(originLevel, originPosition);
		spawnTeleportParticles(target.level, position);
		return true;
	}

	private Vec3 computeNearTargetSpawn(@Nullable AllayPortBlockEntity allayPort,
		@Nullable ServerPlayer player, Vec3 waypoint) {
		if (allayPort != null) {
			Vec3 localSpawn = portHighApproachLocal(allayPort)
				.add(portOutwardLocal(allayPort).scale(48.0))
				.add(0, 8.0, 0);
			return projectPortPoint(allayPort, localSpawn);
		}

		Vec3 landingTarget = landingTarget(null, player);
		Vec3 away = new Vec3(position.x - landingTarget.x, 0, position.z - landingTarget.z);
		if (away.lengthSqr() < 1.0E-6) {
			away = launchDirection.scale(-1);
		}
		if (away.lengthSqr() < 1.0E-6) {
			away = new Vec3(0, 0, 1);
		}
		away = away.normalize();

		return waypoint.add(away.scale(32.0)).add(0, 4.0, 0);
	}

	private Vec3 findTickingPosTowardTarget(ServerLevel level, Vec3 preferredSpawn, Vec3 waypoint) {
		Vec3 path = waypoint.subtract(preferredSpawn);
		if (path.lengthSqr() < 1.0E-6) {
			return preferredSpawn;
		}

		Vec3 step = path.normalize().scale(8.0);
		Vec3 candidate = preferredSpawn;
		int iterations = Math.max(1, Mth.ceil(path.length() / 8.0));
		for (int i = 0; i <= iterations; i++) {
			if (level.isPositionEntityTicking(BlockPos.containing(candidate))) {
				return candidate;
			}
			candidate = candidate.add(step);
		}
		return preferredSpawn;
	}

	private void forceArrive(MinecraftServer server, ServerLevel fallbackLevel) {
		ResolvedTarget target = resolveTarget(server);
		if (target == null) {
			doFail(server, fallbackLevel);
			return;
		}
		Vec3 originPosition = position;
		if (targetAllayPortPos != null) {
			target.level.getChunkAt(targetAllayPortPos);
		}
		position = target.allayPort != null
			? portInside(target.allayPort)
			: landingTarget(null, target.player);
		currentDimension = target.level.dimension();
		spawnTeleportParticles(fallbackLevel, originPosition);
		spawnTeleportParticles(target.level, position);
		doFinishDeliveryAt(server, target.level);
	}

	private static void spawnTeleportParticles(@Nullable ServerLevel level, Vec3 effectPosition) {
		if (level == null) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			level.sendParticles(player, ParticleTypes.PORTAL, true,
				effectPosition.x, effectPosition.y + 0.3, effectPosition.z,
				32, 0.35, 0.4, 0.35, 0.1);
		}
	}

	private void doFinishDeliveryAt(MinecraftServer server, @Nullable ServerLevel level) {
		if (level == null) {
			AllayCourierHudSync.onFailed(server, this);
			markRemoved();
			return;
		}

		ResolvedTarget target = resolveTarget(server);
		Vec3 landingTarget = target != null ? landingTarget(target.allayPort, target.player) : position;
		setTargetWaving(target != null ? target.allayPort : null, false);

		AllayCourierDeliveryService.DeliveryResult result = AllayCourierDeliveryService.finishDelivery(
			server, box, mission, returnMode,
			targetDimension, targetAllayPortPos, targetAllayPortSubLevelId, targetPlayerId,
			level, position, landingTarget);

		if (result.handled()) {
			AllayCourierDeliveryService.spawnDeliveryParticles(level, position);
			if (PackageItem.isPackage(box)) {
				if (result.packageDelivered()) {
					AllayCourierHudSync.onDelivered(server, this);
				} else {
					AllayCourierHudSync.onFailed(server, this);
				}
			}
			if (result.returnCarrier()) {
				startCarrierReturn(server);
				return;
			}
		} else {
			AllayCourierHudSync.onFailed(server, this);
		}
		markRemoved();
	}

	private void doFail(MinecraftServer server, @Nullable ServerLevel currentLevel) {
		AllayCourierHudSync.onFailed(server, this);
		if (currentLevel == null) {
			markRemoved();
			return;
		}
		ResolvedTarget target = resolveTarget(server);
		Vec3 dropTarget = target != null ? landingTarget(target.allayPort, null) : position;
		Vec3 dropPos = target != null && target.allayPort != null ? dropTarget : position;
		setTargetWaving(target != null ? target.allayPort : null, false);
		AllayCourierDeliveryService.failAndDrop(box, mission, currentLevel, dropPos);
		markRemoved();
	}

	private void startCarrierReturn(MinecraftServer server) {
		AllayPortBlockEntity departurePort = resolveTargetAllayPort(server.getLevel(currentDimension));
		if (sourceAllayPortPos != null && sourceDimension != null) {
			targetAllayPortPos = sourceAllayPortPos;
			targetAllayPortSubLevelId = sourceAllayPortSubLevelId;
			targetDimension = sourceDimension;
			targetPlayerId = null;
			resetForReturn(AllayCourierEntity.Mission.CARRIER_RETURN);
			beginPortDeparture(departurePort, true);
		} else if (sourcePlayerId != null) {
			ServerPlayer sourcePlayer = server.getPlayerList().getPlayer(sourcePlayerId);
			if (sourcePlayer != null && sourcePlayer.isAlive()) {
				targetAllayPortPos = null;
				targetAllayPortSubLevelId = null;
				targetPlayerId = sourcePlayerId;
				targetDimension = sourcePlayer.serverLevel().dimension();
				resetForReturn(AllayCourierEntity.Mission.CARRIER_RETURN_TO_PLAYER);
				beginPortDeparture(departurePort, true);
			} else {
				AllayCourierDeliveryService.dropCarrierOnly(server.getLevel(currentDimension), position);
				markRemoved();
			}
		} else {
			AllayCourierDeliveryService.dropCarrierOnly(server.getLevel(currentDimension), position);
			markRemoved();
		}
	}

	private void resetForReturn(AllayCourierEntity.Mission nextMission) {
		box = ItemStack.EMPTY;
		mission = nextMission;
		phase = AllayCourierEntity.Phase.TAKEOFF;
		phaseTicks = 0;
		portMotion = PortMotion.NONE;
		clearPortDeparture();
		portMotionTicks = 0;
		deliveryElapsedTicks = 0;
		teleportedNearTarget = false;
		forceArrivalPending = false;
	}

	private void beginPortDeparture(@Nullable AllayPortBlockEntity departurePort, boolean pause) {
		if (departurePort == null) {
			return;
		}
		launchDirection = portOutward(departurePort);
		portDepartureOrigin = allayPortCenter(departurePort);
		portDepartureClearTarget = portDepartureTarget(departurePort);
		portDepartureAllayPortPos = departurePort.getBlockPos().immutable();
		portDepartureAllayPortSubLevelId = departurePort.getLevel() == null ? null
			: SubLevelCompat.getSpaceId(departurePort.getLevel(), portDepartureAllayPortPos);
		portMotion = pause ? PortMotion.DEPARTURE_PAUSE : PortMotion.DEPARTURE_MOUTH;
		portMotionTicks = 0;
	}

	private void clearPortDeparture() {
		portDepartureOrigin = null;
		portDepartureClearTarget = null;
		portDepartureAllayPortPos = null;
		portDepartureAllayPortSubLevelId = null;
	}

	private @Nullable ServerLevel resolveTargetLevel(MinecraftServer server) {
		if (targetAllayPortPos != null) {
			return server.getLevel(targetDimension);
		}
		ServerPlayer player = resolveTargetPlayer(server);
		return player != null ? player.serverLevel() : server.getLevel(targetDimension);
	}

	private @Nullable AllayPortBlockEntity resolveTargetAllayPort(@Nullable ServerLevel level) {
		return AllayCourierDeliveryService.resolveTargetAllayPort(level, targetAllayPortPos,
			targetAllayPortSubLevelId);
	}

	private @Nullable AllayPortBlockEntity resolveDepartureAllayPort(@Nullable ServerLevel level) {
		return AllayCourierDeliveryService.resolveTargetAllayPort(level, portDepartureAllayPortPos,
			portDepartureAllayPortSubLevelId);
	}

	private @Nullable ServerPlayer resolveTargetPlayer(MinecraftServer server) {
		return AllayCourierDeliveryService.resolvePlayer(server, targetPlayerId);
	}

	private Vec3 landingTarget(@Nullable AllayPortBlockEntity allayPort, @Nullable ServerPlayer player) {
		if (allayPort != null) {
			return portInside(allayPort);
		}
		return player != null ? playerDeliveryTarget(player) : position;
	}

	private Vec3 allayPortCenter(AllayPortBlockEntity allayPort) {
		return projectPortPoint(allayPort, allayPortCenterLocal(allayPort));
	}

	private Vec3 portOutward(AllayPortBlockEntity allayPort) {
		Vec3 localOutward = portOutwardLocal(allayPort);
		Level level = allayPort.getLevel();
		if (level == null) {
			return localOutward;
		}
		Vec3 worldOutward = SubLevelCompat.localNormalToWorld(level, allayPort.getBlockPos(), localOutward);
		return worldOutward.lengthSqr() < 1.0E-8 ? localOutward : worldOutward.normalize();
	}

	private Vec3 portOutwardLocal(AllayPortBlockEntity allayPort) {
		Direction facing = allayPort.getBlockState().getValue(AllayPortBlock.FACING);
		return Vec3.atLowerCornerOf(facing.getNormal());
	}

	private Vec3 allayPortCenterLocal(AllayPortBlockEntity allayPort) {
		return Vec3.atCenterOf(allayPort.getBlockPos()).add(0, -0.25, 0);
	}

	private Vec3 portHighApproach(AllayPortBlockEntity allayPort) {
		return projectPortPoint(allayPort, portHighApproachLocal(allayPort));
	}

	private Vec3 portHighApproachLocal(AllayPortBlockEntity allayPort) {
		return allayPortCenterLocal(allayPort)
			.add(portOutwardLocal(allayPort).scale(PORT_HIGH_APPROACH_OFFSET))
			.add(0, PORT_HIGH_APPROACH_HEIGHT, 0);
	}

	private Vec3 portLineup(AllayPortBlockEntity allayPort) {
		return projectPortPoint(allayPort, portLineupLocal(allayPort));
	}

	private Vec3 portLineupLocal(AllayPortBlockEntity allayPort) {
		return allayPortCenterLocal(allayPort)
			.add(portOutwardLocal(allayPort).scale(PORT_LINEUP_OFFSET));
	}

	private Vec3 portMouth(AllayPortBlockEntity allayPort) {
		return projectPortPoint(allayPort, portMouthLocal(allayPort));
	}

	private Vec3 portMouthLocal(AllayPortBlockEntity allayPort) {
		return allayPortCenterLocal(allayPort)
			.add(portOutwardLocal(allayPort).scale(PORT_MOUTH_OFFSET));
	}

	private Vec3 portInside(AllayPortBlockEntity allayPort) {
		return projectPortPoint(allayPort, portInsideLocal(allayPort));
	}

	private Vec3 portInsideLocal(AllayPortBlockEntity allayPort) {
		return allayPortCenterLocal(allayPort)
			.add(portOutwardLocal(allayPort).scale(PORT_INSIDE_OFFSET));
	}

	private Vec3 portDepartureTarget(@Nullable AllayPortBlockEntity allayPort) {
		if (allayPort != null) {
			return projectPortPoint(allayPort, portDepartureTargetLocal(allayPort));
		}
		if (portDepartureClearTarget != null) {
			return portDepartureClearTarget;
		}
		return portDepartureOrigin
			.add(launchDirection.scale(PORT_DEPARTURE_CLEAR_OFFSET))
			.add(0, PORT_DEPARTURE_CLEAR_HEIGHT, 0);
	}

	private Vec3 portDepartureTargetLocal(AllayPortBlockEntity allayPort) {
		return allayPortCenterLocal(allayPort)
			.add(portOutwardLocal(allayPort).scale(PORT_DEPARTURE_CLEAR_OFFSET))
			.add(0, PORT_DEPARTURE_CLEAR_HEIGHT, 0);
	}

	private Vec3 projectPortPoint(AllayPortBlockEntity allayPort, Vec3 localPoint) {
		Level level = allayPort.getLevel();
		return level == null ? localPoint
			: SubLevelCompat.toWorld(level, allayPort.getBlockPos(), localPoint);
	}

	private Vec3 portVelocityPerTick(AllayPortBlockEntity allayPort, Vec3 localPoint) {
		Level level = allayPort.getLevel();
		return level == null ? Vec3.ZERO
			: SubLevelCompat.getWorldVelocity(level, allayPort.getBlockPos(), localPoint).scale(1 / 20d);
	}

	private Vec3 safePortCruiseTarget(AllayPortBlockEntity allayPort, Vec3 highApproach) {
		Level level = allayPort.getLevel();
		if (level == null) {
			return highApproach;
		}
		BlockPos anchor = allayPort.getBlockPos();
		Vec3 localPosition = SubLevelCompat.toLocal(level, anchor, position);
		Vec3 localHighApproach = portHighApproachLocal(allayPort);
		AABB safetyBounds = new AABB(allayPort.getBlockPos()).inflate(PORT_ROUTE_SAFETY_INFLATION);
		if (safetyBounds.clip(localPosition, localHighApproach).isEmpty()) {
			return highApproach;
		}

		Vec3 localCenter = allayPortCenterLocal(allayPort);
		boolean insideHorizontalFootprint = localPosition.x >= safetyBounds.minX
			&& localPosition.x <= safetyBounds.maxX
			&& localPosition.z >= safetyBounds.minZ && localPosition.z <= safetyBounds.maxZ;
		if (insideHorizontalFootprint && localPosition.y < safetyBounds.maxY) {
			Vec3 escape = localPosition.subtract(localCenter).multiply(1, 0, 1);
			if (escape.lengthSqr() < 1.0E-6) {
				escape = portOutwardLocal(allayPort);
			}
			return SubLevelCompat.toWorld(level, anchor,
				localPosition.add(escape.normalize().scale(PORT_ROUTE_SAFETY_INFLATION + 1.0)));
		}

		double clearanceY = Math.max(localPosition.y, localCenter.y + PORT_ROUTE_CLEARANCE_HEIGHT);
		return SubLevelCompat.toWorld(level, anchor,
			new Vec3(localPosition.x, clearanceY, localPosition.z));
	}

	private boolean reachedPortStage(Vec3 target) {
		return position.distanceToSqr(target)
			<= PORT_STAGE_COMPLETION_DISTANCE * PORT_STAGE_COMPLETION_DISTANCE;
	}

	private void advancePortMotion(PortMotion nextMotion) {
		portMotion = nextMotion;
		portMotionTicks = 0;
	}

	private boolean isGuidedPortArrival() {
		return portMotion == PortMotion.ARRIVAL_ALIGN
			|| portMotion == PortMotion.ARRIVAL_MOUTH
			|| portMotion == PortMotion.ARRIVAL_INSIDE;
	}

	private boolean isGuidedPortDeparture() {
		return portMotion == PortMotion.DEPARTURE_PAUSE
			|| portMotion == PortMotion.DEPARTURE_MOUTH
			|| portMotion == PortMotion.DEPARTURE_CLEAR;
	}

	private static double segmentDistanceToPointSqr(Vec3 start, Vec3 end, Vec3 point) {
		Vec3 segment = end.subtract(start);
		double lengthSqr = segment.lengthSqr();
		if (lengthSqr <= 1.0E-8) {
			return start.distanceToSqr(point);
		}
		double progress = Mth.clamp(point.subtract(start).dot(segment) / lengthSqr, 0.0, 1.0);
		return start.add(segment.scale(progress)).distanceToSqr(point);
	}

	private Vec3 nearTargetWaypoint(@Nullable AllayPortBlockEntity allayPort, @Nullable ServerPlayer player) {
		if (allayPort != null) {
			return portHighApproach(allayPort);
		}
		return player != null ? playerDeliveryTarget(player).add(0, 0.6, 0) : position;
	}

	private Vec3 playerDeliveryTarget(ServerPlayer player) {
		Vec3 horizontalLook = player.getLookAngle().multiply(1, 0, 1);
		if (horizontalLook.lengthSqr() > 1.0E-6) {
			horizontalLook = horizontalLook.normalize();
		} else {
			float yaw = player.yBodyRot * Mth.DEG_TO_RAD;
			horizontalLook = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
		}
		return player.position().add(0, PLAYER_TARGET_HEIGHT, 0)
			.add(horizontalLook.scale(PLAYER_FORWARD_OFFSET));
	}

	private boolean hasReachedPlayer(@Nullable ServerPlayer player, Vec3 landingTarget) {
		return player != null && (player.getBoundingBox().inflate(0.45, 0.6, 0.45).contains(position)
			|| position.distanceTo(landingTarget) <= PLAYER_COMPLETION_DISTANCE);
	}

	private void startTargetWavingIfArriving(MinecraftServer server,
		@Nullable AllayPortBlockEntity allayPort) {
		if (allayPort == null) {
			return;
		}
		int remainingTicks = estimateRemainingTicks(server);
		if (remainingTicks >= 0 && remainingTicks < GREETING_ETA_TICKS) {
			setTargetWaving(allayPort, true);
		}
	}

	private void setTargetWaving(@Nullable AllayPortBlockEntity allayPort, boolean waving) {
		if (allayPort != null) {
			allayPort.setCourierWaving(id, waving);
		}
	}

	private @Nullable ResolvedTarget resolveTarget(MinecraftServer server) {
		ServerLevel level = resolveTargetLevel(server);
		if (level == null) {
			return null;
		}
		AllayPortBlockEntity allayPort = resolveTargetAllayPort(level);
		ServerPlayer player = allayPort == null ? resolveTargetPlayer(server) : null;
		return allayPort == null && player == null ? null : new ResolvedTarget(level, allayPort, player);
	}

	/**
	 * Estimates the sooner of physical arrival, the near-target relocation, and the hard arrival
	 * deadline. The speed constants match the steady movement of the vanilla Allay flight control
	 * used by this task.
	 */
	public int estimateRemainingTicks(MinecraftServer server) {
		ResolvedTarget target = resolveTarget(server);
		if (target == null) {
			return -1;
		}
		if (forceArrivalPending) {
			return 0;
		}

		int forceRemaining = Math.max(0, FORCE_ARRIVAL_TICKS - deliveryElapsedTicks);
		Vec3 previewPosition = previewNearTargetPosition(target.allayPort, target.player);
		int afterRelocation = estimateTravelTicksFrom(previewPosition, target.allayPort, target.player, false);
		int untilRelocation = Math.max(0, TELEPORT_AFTER_TICKS - deliveryElapsedTicks);

		if (!teleportedNearTarget && !target.level.dimension().equals(currentDimension)) {
			return Math.min(forceRemaining, untilRelocation + afterRelocation);
		}

		int physicalEstimate;
		if (isGuidedPortArrival() && target.allayPort != null) {
			physicalEstimate = estimateGuidedPortArrivalTicks(target.allayPort);
		} else if (isGuidedPortDeparture() && portDepartureOrigin != null) {
			AllayPortBlockEntity departurePort = resolveDepartureAllayPort(server.getLevel(currentDimension));
			Vec3 departureTarget = portDepartureTarget(departurePort);
			physicalEstimate = estimateGuidedPortDepartureTicks(departurePort)
				+ estimateTravelTicksFrom(departureTarget, target.allayPort, target.player, false);
		} else {
			physicalEstimate = estimateTravelTicksFrom(position, target.allayPort, target.player,
				phase == AllayCourierEntity.Phase.LANDING);
		}

		if (!teleportedNearTarget) {
			physicalEstimate = Math.min(physicalEstimate, untilRelocation + afterRelocation);
		}
		return Math.min(forceRemaining, physicalEstimate);
	}

	private int estimateTravelTicksFrom(Vec3 from, @Nullable AllayPortBlockEntity allayPort,
		@Nullable ServerPlayer player, boolean landingOnly) {
		Vec3 target = allayPort != null ? portHighApproach(allayPort) : landingTarget(null, player);
		double completionDistance = allayPort != null ? PORT_CAPTURE_RADIUS : PLAYER_COMPLETION_DISTANCE;
		double distance = from.distanceTo(target);
		double remainingDistance = Math.max(0, distance - completionDistance);

		int travelTicks = 0;
		if (remainingDistance > 0) {
			if (landingOnly || distance <= PRECISE_APPROACH_DISTANCE) {
				travelTicks = Mth.ceil(remainingDistance / ESTIMATED_APPROACH_BLOCKS_PER_TICK);
			} else {
				double cruiseDistance = distance - PRECISE_APPROACH_DISTANCE;
				double approachDistance = PRECISE_APPROACH_DISTANCE - completionDistance;
				travelTicks = Mth.ceil(cruiseDistance / ESTIMATED_CRUISE_BLOCKS_PER_TICK)
					+ Mth.ceil(Math.max(0, approachDistance) / ESTIMATED_APPROACH_BLOCKS_PER_TICK);
			}
			travelTicks += ESTIMATED_ACCELERATION_TICKS;
		}

		return travelTicks + (allayPort != null ? ESTIMATED_GUIDED_PORT_ARRIVAL_TICKS : 0);
	}

	private int estimateGuidedPortArrivalTicks(AllayPortBlockEntity allayPort) {
		return switch (portMotion) {
			case ARRIVAL_ALIGN -> estimateLinearTicks(position, portLineup(allayPort),
				PORT_STAGE_COMPLETION_DISTANCE, PORT_ALIGNMENT_SPEED * 0.8)
				+ estimateLinearTicks(portLineup(allayPort), portMouth(allayPort),
					PORT_STAGE_COMPLETION_DISTANCE, PORT_ENTRY_SPEED * 0.85)
				+ estimateLinearTicks(portMouth(allayPort), portInside(allayPort),
					PORT_STAGE_COMPLETION_DISTANCE, PORT_ENTRY_SPEED * 0.85);
			case ARRIVAL_MOUTH -> estimateLinearTicks(position, portMouth(allayPort),
				PORT_STAGE_COMPLETION_DISTANCE, PORT_ENTRY_SPEED * 0.85)
				+ estimateLinearTicks(portMouth(allayPort), portInside(allayPort),
					PORT_STAGE_COMPLETION_DISTANCE, PORT_ENTRY_SPEED * 0.85);
			case ARRIVAL_INSIDE -> estimateLinearTicks(position, portInside(allayPort),
				PORT_STAGE_COMPLETION_DISTANCE, PORT_ENTRY_SPEED * 0.85);
			default -> ESTIMATED_GUIDED_PORT_ARRIVAL_TICKS;
		};
	}

	private int estimateGuidedPortDepartureTicks(@Nullable AllayPortBlockEntity departurePort) {
		return switch (portMotion) {
			case DEPARTURE_PAUSE -> Math.max(0, PORT_TURNAROUND_PAUSE_TICKS - portMotionTicks)
				+ ESTIMATED_GUIDED_PORT_DEPARTURE_TICKS;
			case DEPARTURE_MOUTH -> ESTIMATED_GUIDED_PORT_DEPARTURE_TICKS;
			case DEPARTURE_CLEAR -> estimateLinearTicks(position, portDepartureTarget(departurePort),
				PORT_STAGE_COMPLETION_DISTANCE, PORT_DEPARTURE_SPEED * 0.85);
			default -> 0;
		};
	}

	private int estimateLinearTicks(Vec3 from, Vec3 target, double completionDistance, double speed) {
		double distance = Math.max(0, from.distanceTo(target) - completionDistance);
		return distance <= 0 ? 0 : Mth.ceil(distance / speed) + ESTIMATED_ACCELERATION_TICKS;
	}

	private Vec3 previewNearTargetPosition(@Nullable AllayPortBlockEntity allayPort,
		@Nullable ServerPlayer player) {
		Vec3 waypoint = nearTargetWaypoint(allayPort, player);
		return computeNearTargetSpawn(allayPort, player, waypoint);
	}

	private static Vec3 normalizedDirection(Vec3 direction) {
		return direction.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : direction.normalize();
	}

	private void projectLegacyDepartureFrame(ServerLevel level, BlockPos spaceAnchor) {
		if (portDepartureOrigin == null) {
			return;
		}
		Vec3 worldOrigin = SubLevelCompat.toWorld(level, spaceAnchor, portDepartureOrigin);
		Vec3 worldOutward = normalizedDirection(SubLevelCompat.localNormalToWorld(
			level, spaceAnchor, launchDirection));
		Vec3 worldUp = normalizedDirection(SubLevelCompat.localNormalToWorld(
			level, spaceAnchor, new Vec3(0, 1, 0)));
		portDepartureOrigin = worldOrigin;
		launchDirection = worldOutward;
		portDepartureClearTarget = worldOrigin
			.add(worldOutward.scale(PORT_DEPARTURE_CLEAR_OFFSET))
			.add(worldUp.scale(PORT_DEPARTURE_CLEAR_HEIGHT));
	}

	private static AllayCourierReturnMode defaultReturnMode(@Nullable BlockPos sourceAllayPortPos,
		@Nullable UUID sourcePlayerId) {
		return sourcePlayerId != null && sourceAllayPortPos == null
			? AllayCourierReturnMode.DEFAULT_FOR_PLAYER_LAUNCH
			: AllayCourierReturnMode.DEFAULT_FOR_PORT;
	}

	private static String sourceAddress(ServerLevel spawnLevel,
		@Nullable ResourceKey<Level> sourceDimension, @Nullable BlockPos sourceAllayPortPos,
		@Nullable UUID sourceAllayPortSubLevelId) {
		if (sourceDimension == null || sourceAllayPortPos == null
			|| !sourceDimension.equals(spawnLevel.dimension())) {
			return "";
		}
		if (!SubLevelCompat.matchesSpace(spawnLevel, sourceAllayPortPos, sourceAllayPortSubLevelId)) {
			return "";
		}
		if (spawnLevel.getBlockEntity(sourceAllayPortPos) instanceof AllayPortBlockEntity sourcePort) {
			return sourcePort.addressFilter == null ? "" : sourcePort.addressFilter.trim();
		}
		return "";
	}

	/**
	 * The two halves of the station animation are deliberately mirrored around the front-facing
	 * axis. Cruise flight may reach the high approach point from any safe direction, but every
	 * courier must align at the front before crossing the mouth.
	 */
	private enum PortMotion {
		NONE,
		ARRIVAL_ALIGN,
		ARRIVAL_MOUTH,
		ARRIVAL_INSIDE,
		DEPARTURE_PAUSE,
		DEPARTURE_MOUTH,
		DEPARTURE_CLEAR;

		private static PortMotion byName(String name) {
			try {
				return valueOf(name);
			} catch (IllegalArgumentException ignored) {
				return NONE;
			}
		}
	}

	private record ResolvedTarget(
		ServerLevel level,
		@Nullable AllayPortBlockEntity allayPort,
		@Nullable ServerPlayer player
	) {}

	public UUID id() { return id; }
	public ItemStack box() { return box; }
	public ResourceKey<Level> currentDimension() { return currentDimension; }
	public AllayCourierEntity.Mission mission() { return mission; }
	public AllayCourierEntity.Phase phase() { return phase; }
	public Vec3 position() { return position; }
	public Vec3 launchDirection() { return launchDirection; }
	public Vec3 velocityOnSpawn(ServerLevel level) {
		AllayPortBlockEntity departurePort = resolveDepartureAllayPort(level);
		if (departurePort == null) {
			return Vec3.ZERO;
		}
		Vec3 localPosition = SubLevelCompat.toLocal(level, departurePort.getBlockPos(), position);
		return portVelocityPerTick(departurePort, localPosition);
	}

	private boolean isPackageDeliveryMission() {
		return mission == AllayCourierEntity.Mission.PACKAGE_TO_ALLAY_PORT
			|| mission == AllayCourierEntity.Mission.PACKAGE_TO_PLAYER;
	}
	public boolean relocatedThisTick() { return relocatedThisTick; }
	public boolean isRemoved() { return removed; }
	public boolean placeCourierWhenRemoved() { return placeCourierWhenRemoved; }
	public void markRemoved() { removed = true; }

	public @Nullable UUID hudTrackingPlayerId() {
		if (!PackageItem.isPackage(box)) {
			return null;
		}
		return sourcePlayerId != null ? sourcePlayerId : targetPlayerId;
	}

	public boolean hudIncoming() {
		return sourcePlayerId == null && targetPlayerId != null;
	}

	public String hudCounterpartyAddress() {
		if (hudIncoming()) {
			return sourceAddress;
		}
		return PackageItem.isPackage(box) ? PackageItem.getAddress(box).trim() : "";
	}

	public CompoundTag save(HolderLookup.Provider registries, CompoundTag tag) {
		tag.putInt("CoordinateModelVersion", 1);
		tag.putUUID("Id", id);
		tag.put("Box", box.saveOptional(registries));
		tag.putString("CurrentDimension", currentDimension.location().toString());
		tag.putString("TargetDimension", targetDimension.location().toString());
		if (sourceDimension != null) tag.putString("SourceDimension", sourceDimension.location().toString());
		if (sourceAllayPortPos != null) tag.put("SourceAllayPortPos", NbtUtils.writeBlockPos(sourceAllayPortPos));
		if (targetAllayPortPos != null) tag.put("TargetAllayPortPos", NbtUtils.writeBlockPos(targetAllayPortPos));
		if (sourceAllayPortSubLevelId != null) tag.putUUID("SourceAllayPortSubLevelId", sourceAllayPortSubLevelId);
		if (targetAllayPortSubLevelId != null) tag.putUUID("TargetAllayPortSubLevelId", targetAllayPortSubLevelId);
		if (targetPlayerId != null) tag.putUUID("TargetPlayer", targetPlayerId);
		if (sourcePlayerId != null) tag.putUUID("SourcePlayer", sourcePlayerId);
		tag.putString("SourceAddress", sourceAddress);
		tag.putString("ReturnMode", returnMode.serializedName());
		tag.putByte("Mission", (byte) mission.ordinal());
		tag.putByte("Phase", (byte) phase.ordinal());
		tag.put("Position", vecToTag(position));
		tag.put("LaunchDirection", vecToTag(launchDirection));
		tag.putString("PortMotion", portMotion.name());
		if (portDepartureOrigin != null) tag.put("PortDepartureOrigin", vecToTag(portDepartureOrigin));
		if (portDepartureClearTarget != null) {
			tag.put("PortDepartureClearTarget", vecToTag(portDepartureClearTarget));
		}
		if (portDepartureAllayPortPos != null) {
			tag.put("PortDepartureAllayPortPos", NbtUtils.writeBlockPos(portDepartureAllayPortPos));
		}
		if (portDepartureAllayPortSubLevelId != null) {
			tag.putUUID("PortDepartureAllayPortSubLevelId", portDepartureAllayPortSubLevelId);
		}
		tag.putInt("PhaseTicks", phaseTicks);
		tag.putInt("PortMotionTicks", portMotionTicks);
		tag.putInt("DeliveryElapsedTicks", deliveryElapsedTicks);
		tag.putBoolean("TeleportedNearTarget", teleportedNearTarget);
		tag.putBoolean("ForceArrivalPending", forceArrivalPending);
		if (lastKnownTargetDimension != null) {
			tag.putString("LastKnownTargetDimension", lastKnownTargetDimension.location().toString());
		}
		if (lastKnownTargetPosition != null) {
			tag.put("LastKnownTargetPosition", vecToTag(lastKnownTargetPosition));
		}
		tag.putInt("LostTargetTicks", lostTargetTicks);
		return tag;
	}

	public static AllayCourierTask load(HolderLookup.Provider registries, CompoundTag tag) {
		UUID id = tag.getUUID("Id");
		ItemStack box = ItemStack.parseOptional(registries, tag.getCompound("Box"));
		ResourceKey<Level> currentDimension = dimensionKey(tag.getString("CurrentDimension"));
		ResourceKey<Level> targetDimension = dimensionKey(tag.getString("TargetDimension"));
		ResourceKey<Level> sourceDimension = tag.contains("SourceDimension")
			? dimensionKey(tag.getString("SourceDimension")) : null;
		BlockPos sourceAllayPort = NbtUtils.readBlockPos(tag, "SourceAllayPortPos")
			.orElse(null);
		BlockPos targetAllayPort = NbtUtils.readBlockPos(tag, "TargetAllayPortPos")
			.orElse(null);
		UUID sourceAllayPortSubLevelId = tag.hasUUID("SourceAllayPortSubLevelId")
			? tag.getUUID("SourceAllayPortSubLevelId") : null;
		UUID targetAllayPortSubLevelId = tag.hasUUID("TargetAllayPortSubLevelId")
			? tag.getUUID("TargetAllayPortSubLevelId") : null;
		UUID targetPlayer = tag.hasUUID("TargetPlayer") ? tag.getUUID("TargetPlayer") : null;
		UUID sourcePlayer = tag.hasUUID("SourcePlayer") ? tag.getUUID("SourcePlayer") : null;
		AllayCourierReturnMode returnMode = tag.contains("ReturnMode")
			? AllayCourierReturnMode.byName(tag.getString("ReturnMode"))
			: defaultReturnMode(sourceAllayPort, sourcePlayer);
		AllayCourierEntity.Mission mission = AllayCourierEntity.Mission.values()[tag.getByte("Mission")];
		AllayCourierEntity.Phase phase = AllayCourierEntity.Phase.values()[tag.getByte("Phase")];

		AllayCourierTask task = new AllayCourierTask(id, box, currentDimension, targetDimension,
			sourceAllayPort, targetAllayPort, sourceAllayPortSubLevelId,
			targetAllayPortSubLevelId, targetPlayer,
			sourcePlayer, sourceDimension, tag.getString("SourceAddress"), returnMode, mission,
			vecFromTag(tag, "Position"), vecFromTag(tag, "LaunchDirection"));
		task.worldCoordinates = tag.getInt("CoordinateModelVersion") >= 1;
		task.phase = phase;
		task.phaseTicks = tag.getInt("PhaseTicks");
		task.portMotionTicks = tag.getInt("PortMotionTicks");
		task.deliveryElapsedTicks = tag.getInt("DeliveryElapsedTicks");
		task.teleportedNearTarget = tag.getBoolean("TeleportedNearTarget");
		task.forceArrivalPending = tag.getBoolean("ForceArrivalPending");
		if (tag.contains("LastKnownTargetDimension")) {
			task.lastKnownTargetDimension = dimensionKey(tag.getString("LastKnownTargetDimension"));
		}
		if (tag.contains("LastKnownTargetPosition")) {
			task.lastKnownTargetPosition = vecFromTag(tag, "LastKnownTargetPosition");
		}
		task.lostTargetTicks = tag.getInt("LostTargetTicks");
		if (tag.contains("PortMotion")) {
			task.portMotion = PortMotion.byName(tag.getString("PortMotion"));
			task.portDepartureOrigin = tag.contains("PortDepartureOrigin")
				? vecFromTag(tag, "PortDepartureOrigin") : null;
			task.portDepartureClearTarget = tag.contains("PortDepartureClearTarget")
				? vecFromTag(tag, "PortDepartureClearTarget") : null;
			task.portDepartureAllayPortPos = NbtUtils.readBlockPos(tag, "PortDepartureAllayPortPos")
				.orElse(null);
			task.portDepartureAllayPortSubLevelId = tag.hasUUID("PortDepartureAllayPortSubLevelId")
				? tag.getUUID("PortDepartureAllayPortSubLevelId") : null;
		} else if (tag.getInt("AllayPortEntryTicks") >= 0 && tag.contains("AllayPortEntryTicks")) {
			task.portMotion = PortMotion.ARRIVAL_INSIDE;
		} else if (tag.contains("InitialWaypoint")) {
			Vec3 legacyWaypoint = vecFromTag(tag, "InitialWaypoint");
			task.portDepartureOrigin = legacyWaypoint.subtract(task.launchDirection.scale(0.5));
			task.portMotion = PortMotion.DEPARTURE_MOUTH;
		}
		return task;
	}

	private static ResourceKey<Level> dimensionKey(String id) {
		return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
	}

	private static CompoundTag vecToTag(Vec3 vector) {
		CompoundTag tag = new CompoundTag();
		tag.putDouble("X", vector.x);
		tag.putDouble("Y", vector.y);
		tag.putDouble("Z", vector.z);
		return tag;
	}

	private static Vec3 vecFromTag(CompoundTag tag, String key) {
		CompoundTag vector = tag.getCompound(key);
		return new Vec3(vector.getDouble("X"), vector.getDouble("Y"), vector.getDouble("Z"));
	}
}
