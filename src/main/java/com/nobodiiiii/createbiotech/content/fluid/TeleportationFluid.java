package com.nobodiiiii.createbiotech.content.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/**
 * A Forge fluid whose world-spreading behavior always matches vanilla lava in
 * a normal (non-ultra-warm) dimension.
 */
public abstract class TeleportationFluid extends BaseFlowingFluid {

	protected TeleportationFluid(Properties properties) {
		super(properties);
	}

	@Override
	protected int getSlopeFindDistance(LevelReader level) {
		return 2;
	}

	@Override
	protected int getDropOff(LevelReader level) {
		return 2;
	}

	@Override
	public int getTickDelay(LevelReader level) {
		return 30;
	}

	@Override
	protected int getSpreadDelay(Level level, BlockPos pos, FluidState currentState, FluidState newState) {
		int delay = getTickDelay(level);
		if (!currentState.isEmpty()
			&& !newState.isEmpty()
			&& !currentState.getValue(FALLING)
			&& !newState.getValue(FALLING)
			&& newState.getHeight(level, pos) > currentState.getHeight(level, pos)
			&& level.getRandom().nextInt(4) != 0) {
			delay *= 4;
		}
		return delay;
	}

	public static class Flowing extends TeleportationFluid {

		public Flowing(Properties properties) {
			super(properties);
			registerDefaultState(getStateDefinition().any().setValue(LEVEL, 7));
		}

		@Override
		protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
			super.createFluidStateDefinition(builder);
			builder.add(LEVEL);
		}

		@Override
		public int getAmount(FluidState state) {
			return state.getValue(LEVEL);
		}

		@Override
		public boolean isSource(FluidState state) {
			return false;
		}
	}

	public static class Source extends TeleportationFluid {

		public Source(Properties properties) {
			super(properties);
		}

		@Override
		public int getAmount(FluidState state) {
			return 8;
		}

		@Override
		public boolean isSource(FluidState state) {
			return true;
		}
	}
}
