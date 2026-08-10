package com.nobodiiiii.createbiotech.content.beltsurface;

import java.util.function.Supplier;

import org.apache.commons.lang3.mutable.MutableBoolean;

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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Mechanical-arm interaction shared by standard item belt surfaces. */
public final class StandardItemBeltArmInteraction {
	private StandardItemBeltArmInteraction() {}

	public static final class Type extends ArmInteractionPointType {
		private final Supplier<? extends Block> beltBlock;

		public Type(Supplier<? extends Block> beltBlock) {
			this.beltBlock = beltBlock;
		}

		@Override
		public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
			if (state.getBlock() != beltBlock.get() || !(state.getBlock() instanceof StandardItemBeltBlock belt))
				return false;
			BeltSlope slope = state.getValue(belt.createBiotech$slopeProperty());
			if (slope == BeltSlope.VERTICAL || slope == BeltSlope.SIDEWAYS)
				return false;
			return !(level.getBlockState(pos.above()).getBlock() instanceof BeltTunnelBlock)
				&& belt.createBiotech$canTransportItems(state);
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
