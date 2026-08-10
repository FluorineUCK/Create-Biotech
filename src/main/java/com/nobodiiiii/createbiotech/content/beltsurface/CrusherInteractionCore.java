package com.nobodiiiii.createbiotech.content.beltsurface;

import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/** Create's horizontal crushing-wheel hand-off, shared by each standard item belt surface. */
public final class CrusherInteractionCore {

	private CrusherInteractionCore() {}

	public static boolean check(CrusherTickContext ctx, TransportedItemStack currentItem, float nextOffset) {
		boolean forward = ctx.movingTowardHigherSegments();
		int firstSegment = Mth.clamp((int) Math.floor(ctx.currentSegmentPosition(currentItem)), 0,
			ctx.beltLength() - 1);
		int step = forward ? 1 : -1;

		for (int segment = firstSegment; forward ? segment <= nextOffset : segment + 1 >= nextOffset;
			segment += step) {
			BlockPos crusherPos = ctx.crusherPosFor(segment);
			Level world = ctx.level();
			BlockState crusherState = world.getBlockState(crusherPos);
			if (!(crusherState.getBlock() instanceof CrushingWheelControllerBlock))
				continue;
			Direction crusherFacing = crusherState.getValue(CrushingWheelControllerBlock.FACING);
			if (crusherFacing != ctx.movementFacing())
				continue;

			float crusherEntry = segment + .5f + .399f * (forward ? -1 : 1);
			float postCrusherEntry = crusherEntry + .799f * (!forward ? -1 : 1);
			boolean crossed = forward
				? nextOffset > crusherEntry && nextOffset < postCrusherEntry
				: nextOffset < crusherEntry && nextOffset > postCrusherEntry;
			if (!crossed)
				return false;

			ctx.lockItemAtEntry(currentItem, crusherEntry);
			BlockEntity blockEntity = world.getBlockEntity(crusherPos);
			if (!(blockEntity instanceof CrushingWheelControllerBlockEntity crusher))
				return true;

			ItemStack toInsert = currentItem.stack.copy();
			ItemStack remainder = ItemHandlerHelper.insertItemStacked(crusher.inventory, toInsert, false);
			if (ItemStack.matches(toInsert, remainder))
				return true;

			int notFilled = currentItem.stack.getCount() - toInsert.getCount();
			if (!remainder.isEmpty())
				remainder.grow(notFilled);
			else if (notFilled > 0)
				remainder = currentItem.stack.copyWithCount(notFilled);
			currentItem.stack = remainder;
			ctx.notifyUpdate();
			return true;
		}
		return false;
	}
}
