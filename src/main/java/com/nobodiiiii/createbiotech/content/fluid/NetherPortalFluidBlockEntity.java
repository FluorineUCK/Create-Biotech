package com.nobodiiiii.createbiotech.content.fluid;

import net.minecraft.core.HolderLookup;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBFluids;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class NetherPortalFluidBlockEntity extends BlockEntity {
	public static final int DEFAULT_CAPACITY = 250;

	private final PortalFluidHandler fluidHandler = new PortalFluidHandler();
	private int remainingFluid = capacity();

	public NetherPortalFluidBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.NETHER_PORTAL_FLUID.get(), pos, state);
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("RemainingFluid", remainingFluid);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		remainingFluid = tag.contains("RemainingFluid", Tag.TAG_INT)
			? Mth.clamp(tag.getInt("RemainingFluid"), 0, capacity())
			: capacity();
	}

	@Override
	public void onLoad() {
		super.onLoad();
		destroyPortalIfEmpty();
		refreshAdjacentCreateFluidNetworks();
	}

	public IFluidHandler getFluidCapability(@Nullable Direction side) {
		return CBConfigs.SERVER.teleportationFluid.enablePortalExtraction.get() ? fluidHandler : null;
	}

	private FluidStack drain(int requestedAmount, IFluidHandler.FluidAction action) {
		if (!CBConfigs.SERVER.teleportationFluid.enablePortalExtraction.get())
			return FluidStack.EMPTY;
		int available = Math.min(remainingFluid, capacity());
		int drainedAmount = Math.min(Math.max(requestedAmount, 0), available);
		if (drainedAmount == 0)
			return FluidStack.EMPTY;

		FluidStack drained = new FluidStack(CBFluids.TELEPORTATION.get(), drainedAmount);
		if (action.execute()) {
			remainingFluid = available - drainedAmount;
			setChanged();
			destroyPortalIfEmpty();
		}
		return drained;
	}

	private void destroyPortalIfEmpty() {
		if (!CBConfigs.SERVER.teleportationFluid.enablePortalExtraction.get()
			|| !CBConfigs.SERVER.teleportationFluid.destroyPortalBlockWhenDrained.get()
			|| remainingFluid > 0 || level == null || level.isClientSide || isRemoved())
			return;
		if (level.getBlockState(worldPosition)
			.is(Blocks.NETHER_PORTAL))
			level.destroyBlock(worldPosition, false);
	}

	private void refreshAdjacentCreateFluidNetworks() {
		if (!CBConfigs.SERVER.teleportationFluid.enablePortalExtraction.get()
			|| remainingFluid <= 0 || level == null || level.isClientSide || isRemoved())
			return;

		for (Direction direction : Direction.values()) {
			BlockPos adjacentPos = worldPosition.relative(direction);
			BlockState adjacentState = level.getBlockState(adjacentPos);

			if (level.getBlockEntity(adjacentPos) instanceof PumpBlockEntity pump) {
				pump.updatePipesOnSide(direction.getOpposite());
				continue;
			}

			FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, adjacentPos);
			if (pipe != null && pipe.canHaveFlowToward(adjacentState, direction.getOpposite()))
				FluidPropagator.propagateChangedPipe(level, adjacentPos, adjacentState);
		}
	}

	private static int capacity() {
		return CBConfigs.SERVER.teleportationFluid.fluidPerPortalBlock.get();
	}

	private class PortalFluidHandler implements IFluidHandler {
		@Override
		public int getTanks() {
			return 1;
		}

		@Override
		public FluidStack getFluidInTank(int tank) {
			return CBConfigs.SERVER.teleportationFluid.enablePortalExtraction.get()
				&& tank == 0 && remainingFluid > 0
				? new FluidStack(CBFluids.TELEPORTATION.get(), Math.min(remainingFluid, capacity()))
				: FluidStack.EMPTY;
		}

		@Override
		public int getTankCapacity(int tank) {
			return tank == 0 ? capacity() : 0;
		}

		@Override
		public boolean isFluidValid(int tank, FluidStack stack) {
			return false;
		}

		@Override
		public int fill(FluidStack resource, FluidAction action) {
			return 0;
		}

		@Override
		public FluidStack drain(FluidStack resource, FluidAction action) {
			if (resource.isEmpty() || resource.getFluid() != CBFluids.TELEPORTATION.get())
				return FluidStack.EMPTY;
			return NetherPortalFluidBlockEntity.this.drain(resource.getAmount(), action);
		}

		@Override
		public FluidStack drain(int maxDrain, FluidAction action) {
			return NetherPortalFluidBlockEntity.this.drain(maxDrain, action);
		}
	}
}
