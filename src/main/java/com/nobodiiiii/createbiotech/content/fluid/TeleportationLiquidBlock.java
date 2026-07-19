package com.nobodiiiii.createbiotech.content.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.material.FlowingFluid;

public class TeleportationLiquidBlock extends LiquidBlock implements Portal {

	public TeleportationLiquidBlock(FlowingFluid fluid, BlockBehaviour.Properties properties) {
		super(fluid, properties);
	}

	@Override
	public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		if (entity.canUsePortal(false))
			entity.setAsInsidePortal(this, pos);
	}

	@Override
	public int getPortalTransitionTime(ServerLevel level, Entity entity) {
		return ((Portal) Blocks.NETHER_PORTAL).getPortalTransitionTime(level, entity);
	}

	@Override
	public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos pos) {
		return ((Portal) Blocks.NETHER_PORTAL).getPortalDestination(level, entity, pos);
	}

	@Override
	public Transition getLocalTransition() {
		return ((Portal) Blocks.NETHER_PORTAL).getLocalTransition();
	}
}
