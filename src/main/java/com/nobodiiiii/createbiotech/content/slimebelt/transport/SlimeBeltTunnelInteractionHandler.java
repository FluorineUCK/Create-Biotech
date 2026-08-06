package com.nobodiiiii.createbiotech.content.slimebelt.transport;

import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlockEntity;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltHelper;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltLoopGeometry.Track;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlock;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlockEntity;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlock;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlock;
import com.simibubi.create.content.redstone.displayLink.source.AccumulatedItemCountDisplaySource;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Create's tunnel protocol adapted to the slime belt's FRONT track coordinate. */
public final class SlimeBeltTunnelInteractionHandler {

	private SlimeBeltTunnelInteractionHandler() {}

	public static boolean flapTunnelsAndCheckIfStuck(SlimeBeltInventory beltInventory,
		TransportedItemStack current, float nextOffset) {
		SlimeBeltBlockEntity belt = beltInventory.belt;
		int currentSegment = (int) Math.floor(SlimeBeltHelper.getFrontOffsetForLoopPosition(belt, current.beltPosition));
		int upcomingSegment = (int) Math.floor(nextOffset);
		Direction movementFacing = belt.getMovementFacing();
		if (!beltInventory.beltMovementPositive && nextOffset == 0)
			upcomingSegment = -1;
		if (currentSegment == upcomingSegment)
			return false;

		if (stuckAtTunnel(beltInventory, upcomingSegment, current.stack, movementFacing)) {
			setFrontPosition(beltInventory, current, currentSegment
				+ (beltInventory.beltMovementPositive ? .99f : .01f));
			return true;
		}

		Level world = belt.getLevel();
		boolean onServer = !world.isClientSide || belt.isVirtual();
		boolean removed = false;
		BeltTunnelBlockEntity nextTunnel = getTunnelOnSegment(beltInventory, upcomingSegment);
		int transferred = current.stack.getCount();

		if (nextTunnel instanceof BrassTunnelBlockEntity brassTunnel) {
			if (brassTunnel.hasDistributionBehaviour()) {
				if (!brassTunnel.canTakeItems())
					return true;
				if (onServer) {
					brassTunnel.setStackToDistribute(current.stack, movementFacing.getOpposite());
					current.stack = ItemStack.EMPTY;
					belt.notifyUpdate();
				}
				removed = true;
			}
		} else if (nextTunnel != null) {
			BlockState blockState = nextTunnel.getBlockState();
			if (current.stack.getCount() > 1 && AllBlocks.ANDESITE_TUNNEL.has(blockState)
				&& BeltTunnelBlock.isJunction(blockState)
				&& movementFacing.getAxis() == blockState.getValue(BeltTunnelBlock.HORIZONTAL_AXIS)) {
				for (Direction direction : Iterate.horizontalDirections) {
					if (direction.getAxis() == blockState.getValue(BeltTunnelBlock.HORIZONTAL_AXIS))
						continue;
					if (!nextTunnel.flaps.containsKey(direction))
						continue;
					BlockPos outputPos = nextTunnel.getBlockPos().below().relative(direction);
					if (!world.isLoaded(outputPos))
						return true;
					DirectBeltInputBehaviour behaviour =
						BlockEntityBehaviour.get(world, outputPos, DirectBeltInputBehaviour.TYPE);
					if (behaviour == null || !behaviour.canInsertFromSide(direction))
						continue;

					ItemStack toInsert = current.stack.copyWithCount(1);
					if (!behaviour.handleInsertion(toInsert, direction, false).isEmpty())
						return true;
					if (onServer)
						flapTunnel(beltInventory, upcomingSegment, direction, false);
					current.stack.shrink(1);
					belt.notifyUpdate();
					if (current.stack.getCount() <= 1)
						break;
				}
			}
		}

		if (onServer) {
			flapTunnel(beltInventory, currentSegment, movementFacing, false);
			flapTunnel(beltInventory, upcomingSegment, movementFacing.getOpposite(), true);
			if (nextTunnel != null)
				DisplayLinkBlock.sendToGatherers(world, nextTunnel.getBlockPos(),
					(dgte, behaviour) -> behaviour.itemReceived(dgte, transferred),
					AccumulatedItemCountDisplaySource.class);
		}

		return removed;
	}

	public static boolean stuckAtTunnel(SlimeBeltInventory beltInventory, int offset, ItemStack stack,
		Direction movementDirection) {
		SlimeBeltBlockEntity belt = beltInventory.belt;
		BlockPos pos = SlimeBeltHelper.getPositionForOffset(belt, offset).above();
		if (!(belt.getLevel().getBlockState(pos).getBlock() instanceof BrassTunnelBlock))
			return false;
		BlockEntity blockEntity = belt.getLevel().getBlockEntity(pos);
		return blockEntity instanceof BrassTunnelBlockEntity tunnel
			&& !tunnel.canInsert(movementDirection.getOpposite(), stack);
	}

	public static void flapTunnel(SlimeBeltInventory beltInventory, int offset, Direction side, boolean inward) {
		BeltTunnelBlockEntity blockEntity = getTunnelOnSegment(beltInventory, offset);
		if (blockEntity != null)
			blockEntity.flap(side, inward);
	}

	protected static BeltTunnelBlockEntity getTunnelOnSegment(SlimeBeltInventory beltInventory, int offset) {
		SlimeBeltBlockEntity belt = beltInventory.belt;
		if (belt.getBlockState().getValue(SlimeBeltBlock.SLOPE)
			!= com.simibubi.create.content.kinetics.belt.BeltSlope.HORIZONTAL)
			return null;
		return getTunnelOnPosition(belt.getLevel(), SlimeBeltHelper.getPositionForOffset(belt, offset));
	}

	public static BeltTunnelBlockEntity getTunnelOnPosition(Level world, BlockPos pos) {
		BlockPos tunnelPos = pos.above();
		if (!(world.getBlockState(tunnelPos).getBlock() instanceof BeltTunnelBlock))
			return null;
		BlockEntity blockEntity = world.getBlockEntity(tunnelPos);
		return blockEntity instanceof BeltTunnelBlockEntity tunnel ? tunnel : null;
	}

	private static void setFrontPosition(SlimeBeltInventory beltInventory, TransportedItemStack item,
		float frontOffset) {
		float progress = beltInventory.getTrackProgressForFrontOffset(Track.FRONT, frontOffset);
		beltInventory.setLoopPositionFromTrackProgress(item, Track.FRONT, progress);
	}
}
