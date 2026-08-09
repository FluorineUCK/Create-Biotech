package com.nobodiiiii.createbiotech.foundation.block;

import java.util.function.Predicate;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;

import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Stateless geometry and player-material operations shared by all belt slicers. */
public final class CBBeltSlicer {

	private CBBeltSlicer() {}

	public static boolean beltStatesCompatible(BlockState first, BlockState second) {
		if (first.getBlock() != second.getBlock() || !(first.getBlock() instanceof CBBeltChainBlock belt))
			return false;
		Direction facing1 = first.getValue(BlockStateProperties.HORIZONTAL_FACING);
		Direction facing2 = second.getValue(BlockStateProperties.HORIZONTAL_FACING);
		BeltSlope slope1 = first.getValue(belt.createBiotech$slopeProperty());
		BeltSlope slope2 = second.getValue(belt.createBiotech$slopeProperty());
		return switch (slope1) {
		case UPWARD -> slope2 == BeltSlope.DOWNWARD ? facing1 == facing2.getOpposite()
			: slope2 == slope1 && facing1 == facing2;
		case DOWNWARD -> slope2 == BeltSlope.UPWARD ? facing1 == facing2.getOpposite()
			: slope2 == slope1 && facing1 == facing2;
		default -> slope2 == slope1 && facing2.getAxis() == facing1.getAxis();
		};
	}

	public static BlockState flipBelt(BlockState state) {
		CBBeltChainBlock belt = (CBBeltChainBlock) state.getBlock();
		BeltSlope slope = state.getValue(belt.createBiotech$slopeProperty());
		BeltPart part = state.getValue(belt.createBiotech$partProperty());
		if (slope == BeltSlope.UPWARD)
			state = state.setValue(belt.createBiotech$slopeProperty(), BeltSlope.DOWNWARD);
		else if (slope == BeltSlope.DOWNWARD)
			state = state.setValue(belt.createBiotech$slopeProperty(), BeltSlope.UPWARD);
		if (part == BeltPart.END)
			state = state.setValue(belt.createBiotech$partProperty(), BeltPart.START);
		else if (part == BeltPart.START)
			state = state.setValue(belt.createBiotech$partProperty(), BeltPart.END);
		return state.setValue(BlockStateProperties.HORIZONTAL_FACING,
			state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
	}

	public static boolean hoveringEnd(BlockState state, BlockHitResult hit, Vec3 beltVector) {
		CBBeltChainBlock belt = (CBBeltChainBlock) state.getBlock();
		BeltPart part = state.getValue(belt.createBiotech$partProperty());
		if (part == BeltPart.MIDDLE || part == BeltPart.PULLEY)
			return false;
		Vec3 offset = hit.getLocation().subtract(VecHelper.getCenterOf(hit.getBlockPos()));
		return offset.dot(beltVector) > 0 == (part == BeltPart.END);
	}

	public static boolean hasSplitMaterials(Player player, Predicate<ItemStack> connector, int requiredShafts) {
		if (player == null)
			return false;
		int connectors = 0;
		int shafts = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty())
				continue;
			if (connector.test(stack))
				connectors += stack.getCount();
			if (AllBlocks.SHAFT.isIn(stack))
				shafts += stack.getCount();
		}
		return connectors >= 1 && shafts >= requiredShafts;
	}

	public static void consumeSplitMaterials(Player player, Predicate<ItemStack> connector, int requiredShafts) {
		consume(player, connector, 1);
		consume(player, AllBlocks.SHAFT::isIn, requiredShafts);
	}

	private static void consume(Player player, Predicate<ItemStack> predicate, int amount) {
		int remaining = amount;
		for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty() || !predicate.test(stack))
				continue;
			int taken = Math.min(stack.getCount(), remaining);
			stack.shrink(taken);
			remaining -= taken;
		}
	}
}
