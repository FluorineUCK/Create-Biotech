package com.nobodiiiii.createbiotech.content.dingdongchicken;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-level runtime index for active entity-backed redstone sources. Nothing in this cache is
 * persisted: loaded entities rebuild it as they enter the level.
 */
public final class EntityRedstoneIndex {

	private static final Direction[] DIRECTIONS = Direction.values();

	private final Long2IntOpenHashMap sourceReferences = new Long2IntOpenHashMap();
	private final Long2IntOpenHashMap directReceiverReferences = new Long2IntOpenHashMap();
	private final Long2IntOpenHashMap twoHopCandidateReferences = new Long2IntOpenHashMap();

	private int activePositionCount;
	private long singleSourcePosition;

	public static EntityRedstoneIndex get(ServerLevel level) {
		return ((EntityRedstoneLevelAccess) level).createBiotech$getEntityRedstoneIndex();
	}

	public boolean hasAnySources() {
		return activePositionCount != 0;
	}

	public boolean isSource(BlockPos pos) {
		return isSource(pos.asLong());
	}

	public boolean isSource(long packedPos) {
		if (activePositionCount == 0)
			return false;
		if (activePositionCount == 1)
			return packedPos == singleSourcePosition;
		return sourceReferences.containsKey(packedPos);
	}

	/**
	 * Adds one entity reference. The return value reports whether this position changed from an
	 * inactive position into an active source position.
	 */
	public boolean addSource(long packedPos) {
		int previous = sourceReferences.addTo(packedPos, 1);
		if (previous != 0)
			return false;

		activePositionCount++;
		if (activePositionCount == 1)
			singleSourcePosition = packedPos;
		updateNearbyCaches(packedPos, 1);
		return true;
	}

	/**
	 * Removes one entity reference. The return value reports whether this position ceased to be a
	 * source after the removal.
	 */
	public boolean removeSource(long packedPos) {
		int previous = sourceReferences.get(packedPos);
		if (previous == 0)
			return false;
		if (previous > 1) {
			sourceReferences.put(packedPos, previous - 1);
			return false;
		}

		sourceReferences.remove(packedPos);
		activePositionCount--;
		updateNearbyCaches(packedPos, -1);
		if (activePositionCount == 1)
			singleSourcePosition = sourceReferences.keySet().iterator().nextLong();
		return true;
	}

	public void notifySourceChanged(ServerLevel level, long packedPos) {
		BlockPos pos = BlockPos.of(packedPos);
		level.updateNeighborsAt(pos, Blocks.REDSTONE_BLOCK);
		// The entity also supplies direct power. Notify weak-change consumers on the far side of
		// adjacent conductors without taking over Minecraft's neighbor-update ordering.
		level.updateNeighbourForOutputSignal(pos, Blocks.REDSTONE_BLOCK);
	}

	/**
	 * Supplies an external-power result to redstone-wire evaluators which do not necessarily call
	 * {@code SignalGetter#getSignal}. Far-away wires stop after the primitive candidate lookup.
	 */
	public int getExternalPowerForWire(ServerLevel level, BlockPos wirePos) {
		if (activePositionCount == 0)
			return 0;

		long packedWirePos = wirePos.asLong();
		if (!twoHopCandidateReferences.containsKey(packedWirePos))
			return 0;
		if (directReceiverReferences.containsKey(packedWirePos))
			return 15;

		for (Direction direction : DIRECTIONS) {
			BlockPos conductorPos = wirePos.relative(direction);
			long packedConductorPos = conductorPos.asLong();
			if (!directReceiverReferences.containsKey(packedConductorPos))
				continue;

			BlockState state = level.getBlockState(conductorPos);
			if (!state.isRedstoneConductor(level, conductorPos))
				continue;
			if (hasAdjacentSourceExcept(packedConductorPos, packedWirePos))
				return 15;
		}

		return 0;
	}

	private boolean hasAdjacentSourceExcept(long packedCenter, long packedExcludedPos) {
		int x = BlockPos.getX(packedCenter);
		int y = BlockPos.getY(packedCenter);
		int z = BlockPos.getZ(packedCenter);
		for (Direction direction : DIRECTIONS) {
			long neighbor = BlockPos.asLong(
				x + direction.getStepX(),
				y + direction.getStepY(),
				z + direction.getStepZ());
			if (neighbor != packedExcludedPos && isSource(neighbor))
				return true;
		}
		return false;
	}

	private void updateNearbyCaches(long packedSourcePos, int delta) {
		int x = BlockPos.getX(packedSourcePos);
		int y = BlockPos.getY(packedSourcePos);
		int z = BlockPos.getZ(packedSourcePos);

		for (Direction direction : DIRECTIONS) {
			updateReference(directReceiverReferences, BlockPos.asLong(
				x + direction.getStepX(),
				y + direction.getStepY(),
				z + direction.getStepZ()), delta);
		}

		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2)
						continue;
					updateReference(twoHopCandidateReferences,
						BlockPos.asLong(x + dx, y + dy, z + dz), delta);
				}
			}
		}
	}

	private static void updateReference(Long2IntOpenHashMap references, long packedPos, int delta) {
		if (delta > 0) {
			references.addTo(packedPos, delta);
			return;
		}

		int previous = references.get(packedPos);
		if (previous <= 1)
			references.remove(packedPos);
		else
			references.put(packedPos, previous - 1);
	}
}
