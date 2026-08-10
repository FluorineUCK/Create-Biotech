package com.nobodiiiii.createbiotech.content.buttercat.block;

import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class ButterCatArmInteraction {
	private ButterCatArmInteraction() {}

	public static class Type extends ArmInteractionPointType {
		@Override
		public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
			return state.is(CBBlocks.BUTTER_CAT_ENGINE.get());
		}

		@Override
		public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
			return new Point(this, level, pos, state);
		}
	}

	public static class Point extends AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint {
		public Point(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
			super(type, level, pos, state);
		}

		@Override
		public ItemStack insert(ArmBlockEntity armBlockEntity, ItemStack stack, boolean simulate) {
			ItemStack input = stack.copy();
			InteractionResultHolder<ItemStack> holder =
				ButterCatEngineBlock.armInsert(cachedState, level, pos, input, simulate);
			ItemStack remainder = holder.getObject();
			if (input.isEmpty())
				return remainder;
			if (!simulate)
				Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remainder);
			return input;
		}
	}
}
