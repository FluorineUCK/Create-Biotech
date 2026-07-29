package com.nobodiiiii.createbiotech.content.automaticfishreleasemachine;

import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;
import com.simibubi.create.foundation.fluid.FluidHelper;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AutomaticFishReleaseMachineBlockEntity extends LargeWaterWheelBlockEntity {
	private static final String REPUTATION_OWNER_TAG = "ReputationOwner";
	private static final String REPUTATION_PROGRESS_TAG = "ReputationProgress";
	private static final int LAZY_TICK_INTERVAL = 60;
	private static final int EMPTY_SCAN_RETRY_TICKS = 200;
	private static final int REPUTATION_PER_PULSE = 2;
	private static final double DIMINISHING_RETURNS_START_RPM = 16.0;
	private static final double MAX_SUPPORTED_RPM = 256.0;
	private static final double MINUTES_PER_PULSE_AT_16_RPM = 16.0;
	private static final double PROGRESS_SAVE_STEP = 0.25;
	private static final AABB LOCAL_REPUTATION_BOUNDS = new AABB(BlockPos.ZERO).inflate(12);

	@Nullable
	private UUID reputationOwner;
	private double reputationProgress;
	private int savedProgressStep;
	private long nextReputationScanTime;

	public AutomaticFishReleaseMachineBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.AUTOMATIC_FISH_RELEASE_MACHINE.get(), pos, state);
		setLazyTickRate(LAZY_TICK_INTERVAL);
	}

	public void setReputationOwner(@Nullable LivingEntity placer) {
		reputationOwner = placer instanceof Player ? placer.getUUID() : null;
		reputationProgress = 0;
		savedProgressStep = 0;
		nextReputationScanTime = 0;
		setChanged();
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (!(level instanceof ServerLevel serverLevel) || reputationOwner == null)
			return;
		if (!isProducingStressFromWater())
			return;

		double rpm = Math.min(Math.abs(getSpeed()), MAX_SUPPORTED_RPM);
		if (rpm <= 0)
			return;

		reputationProgress = Math.min(REPUTATION_PER_PULSE,
			reputationProgress + getReputationProgressPerLazyTick(rpm));
		checkpointProgressIfNeeded();
		if (reputationProgress + 1.0e-9 < REPUTATION_PER_PULSE
			|| serverLevel.getGameTime() < nextReputationScanTime)
			return;

		AABB bounds = LOCAL_REPUTATION_BOUNDS.move(worldPosition);
		var villagers = serverLevel.getEntitiesOfClass(Villager.class, bounds, Villager::isAlive);
		if (villagers.isEmpty()) {
			nextReputationScanTime = serverLevel.getGameTime() + EMPTY_SCAN_RETRY_TICKS;
			return;
		}

		for (Villager villager : villagers)
			villager.getGossips()
				.add(reputationOwner, GossipType.MINOR_POSITIVE, REPUTATION_PER_PULSE);

		reputationProgress = Math.max(0, reputationProgress - REPUTATION_PER_PULSE);
		savedProgressStep = 0;
		nextReputationScanTime = 0;
		setChanged();
	}

	private static double getReputationProgressPerLazyTick(double rpm) {
		double speedFactor = rpm <= DIMINISHING_RETURNS_START_RPM
			? rpm / DIMINISHING_RETURNS_START_RPM
			: Math.sqrt(rpm / DIMINISHING_RETURNS_START_RPM);
		double lazyTicksPerMinute = 20.0 * 60.0 / LAZY_TICK_INTERVAL;
		return REPUTATION_PER_PULSE * speedFactor
			/ (MINUTES_PER_PULSE_AT_16_RPM * lazyTicksPerMinute);
	}

	private void checkpointProgressIfNeeded() {
		int currentStep = Mth.floor(reputationProgress / PROGRESS_SAVE_STEP);
		if (currentStep == savedProgressStep)
			return;
		savedProgressStep = currentStep;
		setChanged();
	}

	private boolean isProducingStressFromWater() {
		if (flowScore == 0 || calculateAddedStressCapacity() <= 0)
			return false;

		Vec3 wheelPlane =
			Vec3.atLowerCornerOf(new Vec3i(1, 1, 1).subtract(Direction.get(AxisDirection.POSITIVE, getAxis())
				.getNormal()));
		int waterFlowScore = 0;
		for (BlockPos offset : getOffsetsToCheck()) {
			BlockPos targetPos = offset.offset(worldPosition);
			FluidState fluidState = level.getFluidState(targetPos);
			if (!fluidState.is(FluidTags.WATER) && !FluidHelper.isWater(fluidState.getType()))
				continue;

			Vec3 flowAtPos = getFlowVectorAtPosition(targetPos).multiply(wheelPlane);
			if (flowAtPos.lengthSqr() == 0)
				continue;

			Vec3 normal = Vec3.atLowerCornerOf(offset)
				.normalize();
			Vec3 positiveMotion = VecHelper.rotate(normal, 90, getAxis());
			double contribution = flowAtPos.normalize()
				.dot(positiveMotion);
			if (Math.abs(contribution) > .5)
				waterFlowScore += Math.signum(contribution);
		}

		return waterFlowScore != 0 && Math.signum(waterFlowScore) == Math.signum(flowScore);
	}

	@Override
	protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(compound, registries, clientPacket);
		reputationOwner = compound.hasUUID(REPUTATION_OWNER_TAG) ? compound.getUUID(REPUTATION_OWNER_TAG) : null;
		reputationProgress = Mth.clamp(compound.getDouble(REPUTATION_PROGRESS_TAG), 0, REPUTATION_PER_PULSE);
		savedProgressStep = Mth.floor(reputationProgress / PROGRESS_SAVE_STEP);
	}

	@Override
	public void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(compound, registries, clientPacket);
		if (reputationOwner != null)
			compound.putUUID(REPUTATION_OWNER_TAG, reputationOwner);
		compound.putDouble(REPUTATION_PROGRESS_TAG, reputationProgress);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		return new AABB(worldPosition).inflate(3);
	}
}
