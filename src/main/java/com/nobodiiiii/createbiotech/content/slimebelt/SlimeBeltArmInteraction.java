package com.nobodiiiii.createbiotech.content.slimebelt;

import org.apache.commons.lang3.mutable.MutableBoolean;

import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import com.simibubi.create.content.logistics.tunnel.BeltTunnelBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class SlimeBeltArmInteraction {
	private SlimeBeltArmInteraction() {}

	public static final class Type extends ArmInteractionPointType {
		@Override
		public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
			if (!state.is(CBBlocks.SLIME_BELT.get())
				|| state.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.VERTICAL
				|| state.getValue(SlimeBeltBlock.SLOPE) == BeltSlope.SIDEWAYS)
				return false;
			return !(level.getBlockState(pos.above()).getBlock() instanceof BeltTunnelBlock)
				&& SlimeBeltBlock.canTransportObjects(state);
		}

		@Override
		public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
			return new Point(this, level, pos, state);
		}
	}

	public static final class Point extends AllArmInteractionPointTypes.BeltPoint {
		public Point(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
			super(type, level, pos, state);
		}

		@Override
		public void keepAlive() {
			super.keepAlive();
			SlimeBeltBlockEntity belt = SlimeBeltHelper.getSegmentBE(level, pos);
			if (belt == null)
				return;
			TransportedItemStackHandlerBehaviour transport =
				BlockEntityBehaviour.get(level, pos, TransportedItemStackHandlerBehaviour.TYPE);
			if (transport == null)
				return;
			MutableBoolean found = new MutableBoolean(false);
			transport.handleProcessingOnAllItems(item -> {
				if (found.isTrue())
					return TransportedResult.doNothing();
				item.lockedExternally = true;
				found.setTrue();
				return TransportedResult.doNothing();
			});
		}
	}
}
