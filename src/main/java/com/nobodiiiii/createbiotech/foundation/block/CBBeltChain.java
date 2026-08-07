package com.nobodiiiii.createbiotech.foundation.block;

import java.util.Queue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.magmabelt.MagmaBeltBlock;
import com.nobodiiiii.createbiotech.content.powerbelt.PowerBeltBlock;
import com.nobodiiiii.createbiotech.content.slimebelt.SlimeBeltBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class CBBeltChain {

	private CBBeltChain() {}

	public static void addConnectedSegments(BlockState state, BlockPos pos, Queue<BlockPos> frontier,
		Set<BlockPos> visited) {
		if (!isBiotechBelt(state))
			return;

		addIfUnvisited(nextSegmentPosition(state, pos, true), frontier, visited);
		addIfUnvisited(nextSegmentPosition(state, pos, false), frontier, visited);
	}

	public static boolean isBiotechBelt(BlockState state) {
		Block block = state.getBlock();
		return block instanceof SlimeBeltBlock || block instanceof MagmaBeltBlock
			|| block instanceof PowerBeltBlock;
	}

	@Nullable
	private static BlockPos nextSegmentPosition(BlockState state, BlockPos pos, boolean forward) {
		return switch (state.getBlock()) {
			case SlimeBeltBlock ignored -> SlimeBeltBlock.nextSegmentPosition(state, pos, forward);
			case MagmaBeltBlock ignored -> MagmaBeltBlock.nextSegmentPosition(state, pos, forward);
			case PowerBeltBlock ignored -> PowerBeltBlock.nextSegmentPosition(state, pos, forward);
			default -> null;
		};
	}

	private static void addIfUnvisited(@Nullable BlockPos candidate, Queue<BlockPos> frontier,
		Set<BlockPos> visited) {
		if (candidate != null && !visited.contains(candidate))
			frontier.add(candidate);
	}
}
