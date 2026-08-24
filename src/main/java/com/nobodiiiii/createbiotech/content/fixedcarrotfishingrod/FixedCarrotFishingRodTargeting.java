package com.nobodiiiii.createbiotech.content.fixedcarrotfishingrod;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.foundation.feature.CBFeature;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBPoiTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class FixedCarrotFishingRodTargeting {

	private static final double ITEM_XZ_OFFSET = 6.5 / 16.0;

	private FixedCarrotFishingRodTargeting() {}

	@Nullable
	public static FixedCarrotFishingRodTarget findNearest(PathfinderMob mob,
		Predicate<ItemStack> temptations) {
		if (!(mob.level() instanceof ServerLevel level) || !CBFeature.FIXED_CARROT_FISHING_ROD.isEnabled())
			return null;

		double searchRange = getSearchRange();
		int poiRange = Mth.ceil(searchRange) + 1;
		BlockPos origin = mob.blockPosition();

		return level.getPoiManager()
			.findAll(holder -> holder.is(CBPoiTypes.FIXED_CARROT_FISHING_ROD_KEY), level::isLoaded,
				origin, poiRange, PoiManager.Occupancy.ANY)
			.map(pos -> candidateAt(mob, pos, temptations, searchRange))
			.flatMap(Optional::stream)
			.min(Comparator.comparingDouble(Candidate::distanceSqr))
			.map(Candidate::target)
			.orElse(null);
	}

	@Nullable
	public static Vec3 getValidBaitPosition(PathfinderMob mob, FixedCarrotFishingRodTarget target) {
		if (!(mob.level() instanceof ServerLevel level) || !CBFeature.FIXED_CARROT_FISHING_ROD.isEnabled())
			return null;
		if (!level.isLoaded(target.rodPos()))
			return null;

		Vec3 baitPosition = getMatchingBaitPosition(level, target.rodPos(), target.temptations());
		if (baitPosition == null)
			return null;

		double searchRange = getSearchRange();
		return mob.distanceToSqr(baitPosition) <= searchRange * searchRange ? baitPosition : null;
	}

	public static void awardIfAnimalReachedPowerBelt(PathfinderMob mob,
		FixedCarrotFishingRodTarget target) {
		if (!(mob.level() instanceof ServerLevel level))
			return;
		if (!isOnPowerBelt(level, mob.blockPosition()) && !isOnPowerBelt(level, mob.blockPosition().below()))
			return;
		if (!(level.getBlockEntity(target.rodPos()) instanceof FixedCarrotFishingRodBlockEntity rodEntity))
			return;

		UUID owner = rodEntity.getAdvancementOwner();
		if (owner != null)
			CBAdvancements.awardPlayer(level, owner, CBAdvancements.VOLUNTARY_OVERTIME);
	}

	private static Optional<Candidate> candidateAt(PathfinderMob mob, BlockPos pos,
		Predicate<ItemStack> temptations, double searchRange) {
		if (!(mob.level() instanceof ServerLevel level))
			return Optional.empty();

		Vec3 baitPosition = getMatchingBaitPosition(level, pos, temptations);
		if (baitPosition == null)
			return Optional.empty();

		double distanceSqr = mob.distanceToSqr(baitPosition);
		if (distanceSqr > searchRange * searchRange)
			return Optional.empty();

		return Optional.of(new Candidate(new FixedCarrotFishingRodTarget(pos, temptations), distanceSqr));
	}

	@Nullable
	private static Vec3 getMatchingBaitPosition(ServerLevel level, BlockPos pos,
		Predicate<ItemStack> temptations) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(CBBlocks.FIXED_CARROT_FISHING_ROD.get()))
			return null;
		if (!(level.getBlockEntity(pos) instanceof FixedCarrotFishingRodBlockEntity rodEntity))
			return null;

		ItemStack bait = rodEntity.getBaitItem();
		if (bait.isEmpty() || !temptations.test(bait))
			return null;

		Direction facing = state.getValue(FixedCarrotFishingRodBlock.FACING);
		return new Vec3(
			pos.getX() + 0.5 + facing.getStepX() * ITEM_XZ_OFFSET,
			pos.getY(),
			pos.getZ() + 0.5 + facing.getStepZ() * ITEM_XZ_OFFSET);
	}

	private static boolean isOnPowerBelt(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).is(CBBlocks.POWER_BELT.get());
	}

	private static double getSearchRange() {
		return CBConfigs.SERVER.fixedCarrotFishingRod.searchRange.get();
	}

	private record Candidate(FixedCarrotFishingRodTarget target, double distanceSqr) {}
}
