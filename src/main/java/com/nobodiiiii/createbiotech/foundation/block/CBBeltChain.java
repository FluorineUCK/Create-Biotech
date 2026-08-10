package com.nobodiiiii.createbiotech.foundation.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

public final class CBBeltChain {
	public static final int MAX_SEGMENTS = 1000;

	public enum WalkStatus {
		COMPLETE,
		UNLOADED,
		CROSS_SPACE,
		INVALID,
		TOO_LONG
	}

	public record WalkResult(WalkStatus status, List<BlockPos> positions) {
		public boolean complete() {
			return status == WalkStatus.COMPLETE;
		}

		@Nullable
		public BlockPos lastPosition() {
			return positions.isEmpty() ? null : positions.getLast();
		}
	}

	private CBBeltChain() {}

	public static void addConnectedSegments(BlockState state, BlockPos pos, Queue<BlockPos> frontier,
		Set<BlockPos> visited) {
		if (!isBiotechBelt(state))
			return;

		addIfUnvisited(nextSegmentPosition(state, pos, true), frontier, visited);
		addIfUnvisited(nextSegmentPosition(state, pos, false), frontier, visited);
	}

	public static boolean isBiotechBelt(BlockState state) {
		return state.getBlock() instanceof CBBeltChainBlock;
	}

	@Nullable
	public static BlockPos nextSegmentPosition(BlockState state, BlockPos pos, boolean forward) {
		return state.getBlock() instanceof CBBeltChainBlock belt
			? belt.createBiotech$nextSegmentPosition(state, pos, forward)
			: null;
	}

	/** Collects the loaded, same-space portion of one concrete belt variant. */
	public static List<BlockPos> getBeltChain(LevelAccessor world, BlockPos controllerPos, int limit) {
		return walk(world, controllerPos, true, limit).positions();
	}

	/**
	 * Walks one concrete belt variant without loading chunks and reports why it stopped.
	 * Callers rebuilding a chain must only accept {@link WalkStatus#COMPLETE}; render and
	 * destruction callers may still use the safe loaded prefix in {@link WalkResult#positions()}.
	 */
	public static WalkResult walk(LevelAccessor world, BlockPos start, boolean forward, int limit) {
		List<BlockPos> positions = new ArrayList<>();
		if (!isLoaded(world, start))
			return new WalkResult(WalkStatus.UNLOADED, positions);
		BlockState controllerState = world.getBlockState(start);
		if (!(controllerState.getBlock() instanceof CBBeltChainBlock chainBlock))
			return new WalkResult(WalkStatus.INVALID, positions);

		BlockPos current = start;
		while (limit-- > 0) {
			if (!isLoaded(world, current))
				return new WalkResult(WalkStatus.UNLOADED, positions);
			if (!isSameSpace(world, start, current))
				return new WalkResult(WalkStatus.CROSS_SPACE, positions);
			BlockState state = world.getBlockState(current);
			if (state.getBlock() != chainBlock)
				return new WalkResult(WalkStatus.INVALID, positions);
			positions.add(current);
			BlockPos next = chainBlock.createBiotech$nextSegmentPosition(state, current, forward);
			if (next == null)
				return new WalkResult(WalkStatus.COMPLETE, positions);
			current = next;
		}
		return new WalkResult(WalkStatus.TOO_LONG, positions);
	}

	public static boolean isLoadedInSameSpace(LevelAccessor world, BlockPos anchor, BlockPos target) {
		return isLoaded(world, target) && isSameSpace(world, anchor, target);
	}

	private static boolean isLoaded(LevelAccessor world, BlockPos pos) {
		return !(world instanceof Level level) || level.isLoaded(pos);
	}

	private static boolean isSameSpace(LevelAccessor world, BlockPos anchor, BlockPos target) {
		return !(world instanceof Level level) || SubLevelCompat.sameSpace(level, anchor, target);
	}

	private static void addIfUnvisited(@Nullable BlockPos candidate, Queue<BlockPos> frontier,
		Set<BlockPos> visited) {
		if (candidate != null && !visited.contains(candidate))
			frontier.add(candidate);
	}
}
