package com.nobodiiiii.createbiotech.foundation.block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity.CasingType;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.common.util.BlockSnapshot;

/** Shared schematic payload and transactional placement for all Biotech belt variants. */
public final class CBBeltChainPlacement {
	public static final String PULLEY_OFFSETS_TAG = "CreateBiotechPulleyOffsets";

	public record Payload(int length, int[] pulleyOffsets, CasingType[] casings) {
		public Payload {
			pulleyOffsets = pulleyOffsets.clone();
			casings = casings.clone();
		}
	}

	private CBBeltChainPlacement() {}

	public static boolean isPlacementBelt(BlockState state) {
		return state.getBlock() instanceof CBBeltPlacementBlock;
	}

	public static boolean isLastEndpoint(BlockState state) {
		if (!(state.getBlock() instanceof CBBeltPlacementBlock belt))
			return false;
		BeltPart part = state.getValue(belt.createBiotech$partProperty());
		boolean positive = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxisDirection()
			== AxisDirection.POSITIVE;
		return switch (state.getValue(belt.createBiotech$slopeProperty())) {
		case DOWNWARD -> part == BeltPart.START;
		case UPWARD -> part == BeltPart.END;
		default -> positive && part == BeltPart.END || !positive && part == BeltPart.START;
		};
	}

	public static BlockState shaftState(BlockState state) {
		CBBeltPlacementBlock belt = (CBBeltPlacementBlock) state.getBlock();
		Axis axis = state.getValue(belt.createBiotech$slopeProperty()) == BeltSlope.SIDEWAYS ? Axis.Y
			: state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise().getAxis();
		return AllBlocks.SHAFT.getDefaultState().setValue(AbstractSimpleShaftBlock.AXIS, axis);
	}

	@Nullable
	public static Payload collectPayload(Level level, BlockPos endpoint, BlockState endpointState,
		CBBeltPlacementSegment endpointSegment) {
		if (!(endpointState.getBlock() instanceof CBBeltPlacementBlock belt))
			return null;
		int length = endpointSegment.createBiotech$getBeltLength();
		if (length < 2 || length > CBBeltChain.MAX_SEGMENTS)
			return null;

		boolean forward = endpointState.getValue(belt.createBiotech$partProperty()) == BeltPart.START;
		List<Integer> pulleys = new ArrayList<>();
		CasingType[] casings = new CasingType[length];
		Arrays.fill(casings, CasingType.NONE);
		BlockPos current = endpoint;
		for (int segment = 0; segment < length; segment++) {
			if (!CBBeltChain.isLoadedInSameSpace(level, endpoint, current))
				return null;
			BlockState currentState = level.getBlockState(current);
			BlockEntity blockEntity = level.getBlockEntity(current);
			if (currentState.getBlock() != belt || !(blockEntity instanceof CBBeltPlacementSegment metadata))
				return null;
			if (metadata.createBiotech$hasPulley())
				pulleys.add(segment);
			casings[segment] = metadata.createBiotech$getCasingType();
			if (segment + 1 < length) {
				current = belt.createBiotech$nextSegmentPosition(currentState, current, forward);
				if (current == null)
					return null;
			}
		}
		return new Payload(length, pulleys.stream().mapToInt(Integer::intValue).toArray(), casings);
	}

	@Nullable
	public static List<BlockPos> readChain(LevelAccessor schematic, BlockPos endpoint) {
		BlockState endpointState = schematic.getBlockState(endpoint);
		if (!(endpointState.getBlock() instanceof CBBeltPlacementBlock belt))
			return null;
		BlockPos start = endpoint;
		for (int i = 0; i < CBBeltChain.MAX_SEGMENTS; i++) {
			BlockState state = schematic.getBlockState(start);
			if (state.getBlock() != belt)
				return null;
			if (state.getValue(belt.createBiotech$partProperty()) == BeltPart.START)
				break;
			BlockPos previous = belt.createBiotech$nextSegmentPosition(state, start, false);
			if (previous == null || previous.equals(start))
				return null;
			start = previous;
			if (i == CBBeltChain.MAX_SEGMENTS - 1)
				return null;
		}

		List<BlockPos> chain = new ArrayList<>();
		BlockPos current = start;
		for (int i = 0; i < CBBeltChain.MAX_SEGMENTS; i++) {
			BlockState state = schematic.getBlockState(current);
			if (state.getBlock() != belt)
				return null;
			chain.add(current);
			if (state.getValue(belt.createBiotech$partProperty()) == BeltPart.END)
				return chain;
			BlockPos next = belt.createBiotech$nextSegmentPosition(state, current, true);
			if (next == null || next.equals(current))
				return null;
			current = next;
		}
		return null;
	}

	public static int[] collectPulleyOffsets(LevelAccessor schematic, List<BlockPos> chain) {
		List<Integer> pulleys = new ArrayList<>();
		for (int i = 0; i < chain.size(); i++) {
			BlockState state = schematic.getBlockState(chain.get(i));
			if (state.getBlock() instanceof CBBeltPlacementBlock belt
				&& state.getValue(belt.createBiotech$partProperty()) == BeltPart.PULLEY)
				pulleys.add(i);
		}
		return pulleys.stream().mapToInt(Integer::intValue).toArray();
	}

	public static CasingType[] collectCasings(LevelAccessor schematic, List<BlockPos> chain) {
		CasingType[] casings = new CasingType[chain.size()];
		Arrays.fill(casings, CasingType.NONE);
		for (int i = 0; i < chain.size(); i++) {
			BlockEntity blockEntity = schematic.getBlockEntity(chain.get(i));
			if (blockEntity instanceof CBBeltPlacementSegment segment)
				casings[i] = segment.createBiotech$getCasingType();
		}
		return casings;
	}

	public static boolean canPlaceChain(Level level, List<BlockPos> chain, int[] pulleyOffsets) {
		for (int i = 0; i < chain.size(); i++) {
			BlockPos pos = chain.get(i);
			if (!CBBeltChain.isLoadedInSameSpace(level, chain.getFirst(), pos))
				return false;
			BlockState existing = level.getBlockState(pos);
			boolean shaftExpected = i == 0 || i == chain.size() - 1 || contains(pulleyOffsets, i);
			if (!existing.canBeReplaced() && !(shaftExpected && AllBlocks.SHAFT.has(existing)))
				return false;
		}
		return true;
	}

	public static boolean placeAtomically(Level level, BlockState endpointState, List<BlockPos> chain,
		int[] pulleyOffsets, CasingType[] casings) {
		if (chain.size() < 2 || !(endpointState.getBlock() instanceof CBBeltPlacementBlock belt)
			|| casings.length != chain.size() || !canPlaceChain(level, chain, pulleyOffsets))
			return false;

		List<BlockSnapshot> snapshots = new ArrayList<>(chain.size());
		for (BlockPos pos : chain)
			snapshots.add(BlockSnapshot.create(level.dimension(), level, pos));

		boolean committed = false;
		try {
			BlockState shaft = shaftState(endpointState);
			level.setBlockAndUpdate(chain.getFirst(), shaft);
			level.setBlockAndUpdate(chain.getLast(), shaft);
			for (int pulleyOffset : pulleyOffsets)
				if (pulleyOffset > 0 && pulleyOffset < chain.size() - 1)
					level.setBlockAndUpdate(chain.get(pulleyOffset), shaft);

			belt.createBiotech$createChain(level, chain.getFirst(), chain.getLast());
			for (int i = 0; i < chain.size(); i++) {
				BlockPos pos = chain.get(i);
				if (level.getBlockState(pos).getBlock() != belt)
					return false;
				if (casings[i] != CasingType.NONE) {
					BlockEntity blockEntity = level.getBlockEntity(pos);
					if (!(blockEntity instanceof CBBeltPlacementSegment segment))
						return false;
					segment.createBiotech$setCasingType(casings[i]);
				}
			}
			committed = true;
			return true;
		} finally {
			if (!committed)
				restoreSnapshots(snapshots);
		}
	}

	public static List<BlockPos> positionsFromPayload(BlockState endpointState, BlockPos target, int length) {
		if (!(endpointState.getBlock() instanceof CBBeltPlacementBlock belt) || length < 2)
			return List.of();
		boolean forward = endpointState.getValue(belt.createBiotech$partProperty()) == BeltPart.START;
		BlockPos offset = belt.createBiotech$nextSegmentPosition(endpointState, BlockPos.ZERO, forward);
		if (offset == null)
			return List.of();
		List<BlockPos> positions = new ArrayList<>(length);
		for (int i = 0; i < length; i++)
			positions.add(target.offset(offset.getX() * i, offset.getY() * i, offset.getZ() * i));
		return positions;
	}

	public static void restoreSnapshots(List<BlockSnapshot> snapshots) {
		for (int i = snapshots.size() - 1; i >= 0; i--)
			snapshots.get(i).restore(Block.UPDATE_ALL);
	}

	private static boolean contains(int[] values, int target) {
		for (int value : values)
			if (value == target)
				return true;
		return false;
	}
}
