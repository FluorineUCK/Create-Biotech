package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltHelper;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
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

/** Horizontal crushing-wheel interaction for the slime belt's FRONT track only. */
public final class SlimeBeltCrusherInteractionHandler {

	private SlimeBeltCrusherInteractionHandler() {}

	public static boolean checkForCrushers(SlimeBeltInventory beltInventory, TransportedItemStack currentItem,
		float nextOffset) {
		SlimeBeltBlockEntity belt = beltInventory.belt;
		boolean movementPositive = beltInventory.beltMovementPositive;
		float currentOffset = SlimeBeltHelper.getFrontOffsetForLoopPosition(belt, currentItem.beltPosition);
		int firstUpcomingSegment = Mth.clamp((int) Math.floor(currentOffset), 0, belt.beltLength - 1);
		int step = movementPositive ? 1 : -1;

		for (int segment = firstUpcomingSegment; movementPositive ? segment <= nextOffset
			: segment + 1 >= nextOffset; segment += step) {
			BlockPos crusherPos = SlimeBeltHelper.getPositionForOffset(belt, segment).above();
			Level world = belt.getLevel();
			BlockState crusherState = world.getBlockState(crusherPos);
			if (!(crusherState.getBlock() instanceof CrushingWheelControllerBlock))
				continue;
			Direction crusherFacing = crusherState.getValue(CrushingWheelControllerBlock.FACING);
			Direction movementFacing = belt.getMovementFacing();
			if (crusherFacing != movementFacing)
				continue;

			float crusherEntry = segment + .5f;
			crusherEntry += .399f * (movementPositive ? -1 : 1);
			float postCrusherEntry = crusherEntry + .799f * (!movementPositive ? -1 : 1);
			boolean hasCrossed = movementPositive
				? nextOffset > crusherEntry && nextOffset < postCrusherEntry
				: nextOffset < crusherEntry && nextOffset > postCrusherEntry;
			if (!hasCrossed)
				return false;

			setFrontPosition(beltInventory, currentItem, crusherEntry);
			BlockEntity blockEntity = world.getBlockEntity(crusherPos);
			if (!(blockEntity instanceof CrushingWheelControllerBlockEntity crusherBE))
				return true;

			ItemStack toInsert = currentItem.stack.copy();
			ItemStack remainder = ItemHandlerHelper.insertItemStacked(crusherBE.inventory, toInsert, false);
			if (ItemStack.matches(toInsert, remainder))
				return true;

			int notFilled = currentItem.stack.getCount() - toInsert.getCount();
			if (!remainder.isEmpty())
				remainder.grow(notFilled);
			else if (notFilled > 0)
				remainder = currentItem.stack.copyWithCount(notFilled);
			currentItem.stack = remainder;
			belt.notifyUpdate();
			return true;
		}

		return false;
	}

	private static void setFrontPosition(SlimeBeltInventory beltInventory, TransportedItemStack item,
		float frontOffset) {
		float progress = beltInventory.getTrackProgressForFrontOffset(Track.FRONT, frontOffset);
		beltInventory.setLoopPositionFromTrackProgress(item, Track.FRONT, progress);
	}
}
