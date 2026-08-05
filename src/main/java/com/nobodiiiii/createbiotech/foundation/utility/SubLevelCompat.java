package com.nobodiiiii.createbiotech.foundation.utility;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps optional Sable sublevel handling in one place. A Sable sublevel stores blocks in plot
 * coordinates inside the parent level while entities remain in projected world coordinates.
 */
public final class SubLevelCompat {

	private static final SableCompanion COMPANION = SableCompanion.INSTANCE;
	private SubLevelCompat() {}

	@Nullable
	public static SubLevelAccess getContaining(Level level, BlockPos pos) {
		return COMPANION.getContaining(level, pos);
	}

	public static Iterable<? extends SubLevelAccess> getAllIntersecting(Level level, AABB worldBounds) {
		return COMPANION.getAllIntersecting(level, new BoundingBox3d(worldBounds));
	}

	@Nullable
	public static UUID getSpaceId(Level level, BlockPos pos) {
		SubLevelAccess subLevel = getContaining(level, pos);
		return subLevel == null ? null : subLevel.getUniqueId();
	}

	@Nullable
	public static SubLevelAccess getTrackingOrVehicleSubLevel(Entity entity) {
		return COMPANION.getTrackingOrVehicleSubLevel(entity);
	}

	/** Returns the interpolated outer-world eye position used by Sable-aware client picking. */
	public static Vec3 getEyePositionInterpolated(Entity entity, float partialTicks) {
		return COMPANION.getEyePositionInterpolated(entity, partialTicks);
	}

	public static boolean isValidSpacePosition(Level level, BlockPos pos) {
		return getContaining(level, pos) != null || !COMPANION.isInPlotGrid(level, pos);
	}

	public static boolean matchesSpace(Level level, BlockPos pos, @Nullable UUID expectedSubLevelId) {
		SubLevelAccess subLevel = getContaining(level, pos);
		if (expectedSubLevelId != null)
			return subLevel != null && expectedSubLevelId.equals(subLevel.getUniqueId());
		return subLevel == null && !COMPANION.isInPlotGrid(level, pos);
	}

	/**
	 * Validates a saved raw block-storage position against its persistent space identity.
	 *
	 * <p>A {@code null} UUID explicitly means the outer world. Plot-grid positions are therefore
	 * rejected rather than accidentally being treated as ordinary world coordinates. Callers
	 * holding an outer-world position must convert it explicitly before using this method.</p>
	 */
	@Nullable
	public static BlockPos resolveRawPosition(Level level, BlockPos rawPos,
		@Nullable UUID expectedSubLevelId) {
		return level.isInWorldBounds(rawPos) && matchesSpace(level, rawPos, expectedSubLevelId)
			? rawPos : null;
	}

	/**
	 * Strictly resolves a block entity by raw position and optional sublevel UUID. It performs only
	 * address validation and one non-loading chunk lookup; it never scans neighboring spaces.
	 */
	@Nullable
	public static BlockEntity resolveBlockEntityFast(Level level, BlockPos rawPos,
		@Nullable UUID expectedSubLevelId) {
		BlockPos resolvedPos = resolveRawPosition(level, rawPos, expectedSubLevelId);
		return resolvedPos == null ? null : getLoadedBlockEntity(level, resolvedPos);
	}

	/** Reads a block entity at an already-resolved raw position without loading its chunk. */
	@Nullable
	public static BlockEntity getLoadedBlockEntity(Level level, BlockPos pos) {
		if (!level.isInWorldBounds(pos))
			return null;
		ChunkAccess chunk = level.getChunk(SectionPos.blockToSectionCoord(pos.getX()),
			SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
		if (!(chunk instanceof LevelChunk levelChunk))
			return null;
		return levelChunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
	}

	public static boolean sameSpace(Level level, BlockPos first, BlockPos second) {
		if (!isValidSpacePosition(level, first) || !isValidSpacePosition(level, second))
			return false;
		return sameSpace(getContaining(level, first), getContaining(level, second));
	}

	public static boolean sameSpace(@Nullable SubLevelAccess first, @Nullable SubLevelAccess second) {
		if (first == null || second == null)
			return first == second;
		return Objects.equals(first.getUniqueId(), second.getUniqueId());
	}

	/**
	 * Prevents callbacks from an overlapping logical space from affecting this block. An untracked
	 * entity is still allowed to enter a sublevel and become attached to it.
	 */
	public static boolean canEntityInteractWith(Level level, BlockPos blockPos, Entity entity) {
		if (!isValidSpacePosition(level, blockPos))
			return false;
		SubLevelAccess trackedSubLevel = COMPANION.getTrackingOrVehicleSubLevel(entity);
		return trackedSubLevel == null || sameSpace(getContaining(level, blockPos), trackedSubLevel);
	}

	@Nullable
	public static <T> T runIncludingEntitySpace(Level level, Position worldOrigin, Entity entity,
		BiFunction<SubLevelAccess, BlockPos, T> resolver) {
		SubLevelAccess trackedSubLevel = COMPANION.getTrackingOrVehicleSubLevel(entity);
		return COMPANION.runIncludingSubLevels(level, worldOrigin, true, (SubLevelAccess) null,
			(candidateSubLevel, candidatePos) -> {
				if (trackedSubLevel != null && !sameSpace(trackedSubLevel, candidateSubLevel))
					return null;
				return resolver.apply(candidateSubLevel, candidatePos);
			});
	}

	public static void forEachIncludingEntitySpace(Level level, Position worldOrigin, Entity entity,
		BiConsumer<SubLevelAccess, BlockPos> consumer) {
		runIncludingEntitySpace(level, worldOrigin, entity, (subLevel, pos) -> {
			consumer.accept(subLevel, pos);
			// Sable continues visiting candidate spaces only while the resolver returns null.
			return null;
		});
	}

	public static Vec3 toWorld(Level level, Position localPos) {
		return COMPANION.projectOutOfSubLevel(level, localPos);
	}

	/**
	 * Projects a local point using the space containing {@code spaceAnchor}. This differs from
	 * {@link #toWorld(Level, Position)} when the local point itself lies beyond the plot footprint,
	 * which is common for approach waypoints offset from a block near a sublevel edge.
	 */
	public static Vec3 toWorld(Level level, BlockPos spaceAnchor, Position localPos) {
		return toWorld(getContaining(level, spaceAnchor), localPos);
	}

	public static Vec3 toWorld(@Nullable SubLevelAccess subLevel, Position localPos) {
		Vec3 position = localPos instanceof Vec3 vec ? vec : new Vec3(localPos.x(), localPos.y(), localPos.z());
		return subLevel == null ? position : subLevel.logicalPose().transformPosition(position);
	}

	public static Vec3 toPreviousWorld(@Nullable SubLevelAccess subLevel, Position localPos) {
		Vec3 position = localPos instanceof Vec3 vec ? vec : new Vec3(localPos.x(), localPos.y(), localPos.z());
		return subLevel == null ? position : subLevel.lastPose().transformPosition(position);
	}

	/** Projects a client point with the same interpolated pose used to render its sublevel. */
	public static Vec3 toRenderWorld(@Nullable SubLevelAccess subLevel, Position localPos, float partialTick) {
		Vec3 position = localPos instanceof Vec3 vec ? vec : new Vec3(localPos.x(), localPos.y(), localPos.z());
		if (subLevel == null)
			return position;
		if (subLevel instanceof ClientSubLevelAccess clientSubLevel)
			return clientSubLevel.renderPose(partialTick)
				.transformPosition(position);
		return subLevel.logicalPose()
			.transformPosition(position);
	}

	/** Inverse of {@link #toRenderWorld(SubLevelAccess, Position, float)}. */
	public static Vec3 toRenderLocal(@Nullable SubLevelAccess subLevel, Position worldPos, float partialTick) {
		Vec3 position = worldPos instanceof Vec3 vec ? vec
			: new Vec3(worldPos.x(), worldPos.y(), worldPos.z());
		if (subLevel == null)
			return position;
		if (subLevel instanceof ClientSubLevelAccess clientSubLevel)
			return clientSubLevel.renderPose(partialTick)
				.transformPositionInverse(position);
		return subLevel.logicalPose()
			.transformPositionInverse(position);
	}

	/** Returns the outer-world velocity of a local point in blocks per second. */
	public static Vec3 getWorldVelocity(Level level, @Nullable SubLevelAccess subLevel, Position localPos) {
		return subLevel == null ? Vec3.ZERO : COMPANION.getVelocity(level, subLevel, localPos);
	}

	public static Vec3 toLocal(Level level, BlockPos blockPos, Position worldPos) {
		return toLocal(getContaining(level, blockPos), worldPos);
	}

	public static Vec3 toLocal(@Nullable SubLevelAccess subLevel, Position worldPos) {
		Vec3 position = worldPos instanceof Vec3 vec ? vec : new Vec3(worldPos.x(), worldPos.y(), worldPos.z());
		return subLevel == null ? position : subLevel.logicalPose().transformPositionInverse(position);
	}

	public static Vec3 toPreviousLocal(@Nullable SubLevelAccess subLevel, Position previousWorldPos) {
		Vec3 position = previousWorldPos instanceof Vec3 vec ? vec
			: new Vec3(previousWorldPos.x(), previousWorldPos.y(), previousWorldPos.z());
		return subLevel == null ? position : subLevel.lastPose().transformPositionInverse(position);
	}

	public static Vec3 localNormalToWorld(@Nullable SubLevelAccess subLevel, Vec3 localNormal) {
		return subLevel == null ? localNormal : subLevel.logicalPose().transformNormal(localNormal);
	}

	public static Vec3 localNormalToWorld(Level level, BlockPos spaceAnchor, Vec3 localNormal) {
		return localNormalToWorld(getContaining(level, spaceAnchor), localNormal);
	}

	public static Vec3 worldNormalToLocal(@Nullable SubLevelAccess subLevel, Vec3 worldNormal) {
		return subLevel == null ? worldNormal : subLevel.logicalPose().transformNormalInverse(worldNormal);
	}

	/** Projects a local normal with the same interpolated pose used to render its sublevel. */
	public static Vec3 renderNormalToWorld(@Nullable SubLevelAccess subLevel, Vec3 localNormal,
		float partialTick) {
		if (subLevel == null)
			return localNormal;
		if (subLevel instanceof ClientSubLevelAccess clientSubLevel)
			return clientSubLevel.renderPose(partialTick)
				.transformNormal(localNormal);
		return subLevel.logicalPose()
			.transformNormal(localNormal);
	}

	/** Returns the outer-world velocity of an anchored local point in blocks per second. */
	public static Vec3 getWorldVelocity(Level level, BlockPos spaceAnchor, Position localPos) {
		return getWorldVelocity(level, getContaining(level, spaceAnchor), localPos);
	}

	/**
	 * Projects a local, yaw-only heading into the outer world's horizontal plane. Ghast balloons do
	 * not support pitch or roll, so a tilted sublevel necessarily loses those two components. The
	 * secondary right-axis projection keeps the yaw deterministic when the local forward axis is
	 * nearly vertical.
	 */
	public static float localYawToWorld(@Nullable SubLevelAccess subLevel, float localYaw) {
		if (subLevel == null)
			return localYaw;
		Vec3 worldForward = localNormalToWorld(subLevel, Vec3.directionFromRotation(0, localYaw));
		Float projectedYaw = horizontalYaw(worldForward);
		if (projectedYaw != null)
			return projectedYaw;

		Vec3 worldRight = localNormalToWorld(subLevel, Vec3.directionFromRotation(0, localYaw + 90));
		Float projectedRightYaw = horizontalYaw(worldRight);
		return projectedRightYaw == null ? localYaw : Mth.wrapDegrees(projectedRightYaw - 90);
	}

	/** Inverse of {@link #localYawToWorld(SubLevelAccess, float)} for yaw-only entities. */
	public static float worldYawToLocal(@Nullable SubLevelAccess subLevel, float worldYaw) {
		if (subLevel == null)
			return worldYaw;
		Vec3 localForward = worldNormalToLocal(subLevel, Vec3.directionFromRotation(0, worldYaw));
		Float projectedYaw = horizontalYaw(localForward);
		if (projectedYaw != null)
			return projectedYaw;

		Vec3 localRight = worldNormalToLocal(subLevel, Vec3.directionFromRotation(0, worldYaw + 90));
		Float projectedRightYaw = horizontalYaw(localRight);
		return projectedRightYaw == null ? worldYaw : Mth.wrapDegrees(projectedRightYaw - 90);
	}

	@Nullable
	private static Float horizontalYaw(Vec3 direction) {
		if (direction.horizontalDistanceSqr() < 1.0E-12)
			return null;
		return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
	}

	/**
	 * Transforms a client-side local offset with the same interpolated pose Sable uses to render
	 * the containing sublevel. Static-world offsets pass through unchanged.
	 */
	public static Vec3 localOffsetToRenderWorld(Level level, BlockPos blockPos, Vec3 localOffset, float partialTick) {
		return localOffsetToRenderWorld(getContaining(level, blockPos), localOffset, partialTick);
	}

	public static Vec3 localOffsetToRenderWorld(@Nullable SubLevelAccess subLevel, Vec3 localOffset,
		float partialTick) {
		return renderNormalToWorld(subLevel, localOffset, partialTick);
	}

	public static AABB toWorldBounds(Level level, BlockPos blockPos, AABB localBounds) {
		SubLevelAccess subLevel = getContaining(level, blockPos);
		return subLevel == null ? localBounds : new BoundingBox3d(localBounds).transform(subLevel.logicalPose()).toMojang();
	}

	public static AABB toLocalBounds(Level level, BlockPos blockPos, AABB worldBounds) {
		return toLocalBounds(getContaining(level, blockPos), worldBounds);
	}

	public static AABB toLocalBounds(@Nullable SubLevelAccess subLevel, AABB worldBounds) {
		return subLevel == null ? worldBounds
			: new BoundingBox3d(worldBounds).transformInverse(subLevel.logicalPose()).toMojang();
	}

	public static double distanceSquared(Level level, Position first, Position second) {
		// Companion 1.6.0's no-Sable Position overload drops the Y/Z deltas. The
		// primitive overload has the same Sable-aware semantics and is correct in both
		// the bundled fallback and Sable's runtime implementation.
		return distanceSquared(level, first.x(), first.y(), first.z(),
			second.x(), second.y(), second.z());
	}

	public static double distanceSquared(Level level, double firstX, double firstY, double firstZ,
		double secondX, double secondY, double secondZ) {
		return COMPANION.distanceSquaredWithSubLevels(level, firstX, firstY, firstZ,
			secondX, secondY, secondZ);
	}

}
