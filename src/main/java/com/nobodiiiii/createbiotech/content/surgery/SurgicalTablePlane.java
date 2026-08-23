package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves one horizontal, same-facing surgical-table work surface. */
public final class SurgicalTablePlane {
	public static final int MAX_TILES = 1024;
	private static final Direction[] HORIZONTAL = {
		Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	private SurgicalTablePlane() {}

	public static Plane scan(Level level, BlockPos start) {
		BlockState origin = level.getBlockState(start);
		if (!(origin.getBlock() instanceof SurgicalTableBlock) || !origin.hasProperty(SurgicalTableBlock.FACING))
			return Plane.EMPTY;

		Direction facing = origin.getValue(SurgicalTableBlock.FACING);
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		LinkedHashSet<BlockPos> tiles = new LinkedHashSet<>();
		LinkedHashSet<BlockPos> owners = new LinkedHashSet<>();
		frontier.add(start.immutable());
		boolean complete = true;
		while (!frontier.isEmpty()) {
			BlockPos current = frontier.removeFirst();
			if (tiles.contains(current))
				continue;
			if (tiles.size() >= MAX_TILES) {
				complete = false;
				break;
			}
			if (!matches(level, current, start.getY(), facing))
				continue;

			tiles.add(current);
			if (level.getBlockEntity(current) instanceof SurgicalTableBlockEntity table && table.hasSubject())
				owners.add(current);
			for (Direction direction : HORIZONTAL) {
				BlockPos next = current.relative(direction);
				if (!tiles.contains(next) && level.isLoaded(next))
					frontier.addLast(next.immutable());
			}
		}
		List<BlockPos> frozenTiles = List.copyOf(tiles);
		return new Plane(frozenTiles, Set.copyOf(owners), facing, complete,
			complete ? largestRectangle(frozenTiles, start.getY()) : WorkArea.EMPTY);
	}

	/** Keeps a newly joined surface within the same bounded scan used by normal interactions. */
	public static boolean canExtendAt(Level level, BlockPos destination, Direction facing) {
		Set<BlockPos> scanned = new HashSet<>();
		for (Direction direction : HORIZONTAL) {
			BlockPos neighbor = destination.relative(direction);
			if (!level.isLoaded(neighbor) || scanned.contains(neighbor)
				|| !matches(level, neighbor, destination.getY(), facing))
				continue;
			Plane plane = scan(level, neighbor);
			if (!plane.complete())
				return false;
			scanned.addAll(plane.tiles());
			if (scanned.size() >= MAX_TILES)
				return false;
		}
		return true;
	}

	/**
	 * Returns the persisted world-space footprints of every subject on a surface. A null result
	 * means at least one legacy subject has no trustworthy footprint and placement must stay
	 * conservative until that subject is packed away.
	 */
	@Nullable
	public static List<SurgicalTableLayout.Footprint> occupiedFootprints(Level level, Plane plane,
		@Nullable BlockPos excludedOwner) {
		if (!plane.valid())
			return null;
		List<SurgicalTableLayout.Footprint> footprints = new ArrayList<>();
		for (BlockPos owner : plane.owners()) {
			if (owner.equals(excludedOwner))
				continue;
			if (!(level.getBlockEntity(owner) instanceof SurgicalTableBlockEntity table) || !table.hasSubject())
				continue;
			List<SurgicalTableLayout.Footprint> occupied = table.getOccupiedFootprints();
			if (occupied.isEmpty())
				return null;
			footprints.addAll(occupied);
		}
		return List.copyOf(footprints);
	}

	@Nullable
	public static SurgicalTableBlockEntity uniqueOwner(Level level, BlockPos member) {
		Plane plane = scan(level, member);
		BlockPos owner = plane.owner();
		return owner != null && level.getBlockEntity(owner) instanceof SurgicalTableBlockEntity table ? table : null;
	}

	private static boolean matches(Level level, BlockPos pos, int y, Direction facing) {
		if (pos.getY() != y)
			return false;
		BlockState state = level.getBlockState(pos);
		return state.getBlock() instanceof SurgicalTableBlock
			&& state.hasProperty(SurgicalTableBlock.FACING)
			&& state.getValue(SurgicalTableBlock.FACING) == facing;
	}

	/**
	 * Finds the largest axis-aligned rectangle made entirely from table tiles. Histogram rows keep
	 * this bounded by the plane's coordinate span rather than trying every possible rectangle.
	 */
	private static WorkArea largestRectangle(List<BlockPos> tiles, int y) {
		if (tiles.isEmpty())
			return WorkArea.EMPTY;
		int minX = tiles.stream().mapToInt(BlockPos::getX).min().orElse(0);
		int maxX = tiles.stream().mapToInt(BlockPos::getX).max().orElse(0);
		int minZ = tiles.stream().mapToInt(BlockPos::getZ).min().orElse(0);
		int maxZ = tiles.stream().mapToInt(BlockPos::getZ).max().orElse(0);
		int width = maxX - minX + 1;
		int[] heights = new int[width];
		Set<Long> occupied = new HashSet<>(tiles.size() * 2);
		for (BlockPos tile : tiles)
			occupied.add(tile.asLong());

		WorkArea best = WorkArea.EMPTY;
		for (int z = minZ; z <= maxZ; z++) {
			for (int xIndex = 0; xIndex < width; xIndex++) {
				BlockPos tile = new BlockPos(minX + xIndex, y, z);
				heights[xIndex] = occupied.contains(tile.asLong()) ? heights[xIndex] + 1 : 0;
			}

			ArrayList<Integer> stack = new ArrayList<>(width + 1);
			for (int xIndex = 0; xIndex <= width; xIndex++) {
				int height = xIndex == width ? 0 : heights[xIndex];
				while (!stack.isEmpty() && heights[stack.getLast()] > height) {
					int bar = stack.removeLast();
					int rectangleHeight = heights[bar];
					int left = stack.isEmpty() ? 0 : stack.getLast() + 1;
					int rightExclusive = xIndex;
					WorkArea candidate = new WorkArea(minX + left, z - rectangleHeight + 1,
						minX + rightExclusive, z + 1, y);
					if (candidate.betterThan(best))
						best = candidate;
				}
				stack.add(xIndex);
			}
		}
		return best;
	}

	public record Plane(List<BlockPos> tiles, Set<BlockPos> owners, @Nullable Direction facing, boolean complete,
		WorkArea workArea) {
		private static final Plane EMPTY = new Plane(List.of(), Set.of(), null, true, WorkArea.EMPTY);

		public Plane {
			tiles = List.copyOf(tiles);
			owners = Set.copyOf(owners);
			workArea = workArea == null ? WorkArea.EMPTY : workArea;
		}

		public boolean valid() {
			return complete;
		}

		@Nullable
		public BlockPos owner() {
			return valid() && owners.size() == 1 ? owners.iterator().next() : null;
		}
	}

	/** World-space horizontal bounds of the only usable part of a table plane. */
	public record WorkArea(int minX, int minZ, int maxXExclusive, int maxZExclusive, int y) {
		private static final WorkArea EMPTY = new WorkArea(0, 0, 0, 0, 0);

		public boolean isEmpty() {
			return maxXExclusive <= minX || maxZExclusive <= minZ;
		}

		public int tileArea() {
			return isEmpty() ? 0 : (maxXExclusive - minX) * (maxZExclusive - minZ);
		}

		public boolean contains(double minX, double minZ, double maxX, double maxZ, double epsilon) {
			return !isEmpty() && minX >= this.minX - epsilon && minZ >= this.minZ - epsilon
				&& maxX <= maxXExclusive + epsilon && maxZ <= maxZExclusive + epsilon;
		}

		private boolean betterThan(WorkArea other) {
			int area = tileArea();
			int otherArea = other.tileArea();
			if (area != otherArea)
				return area > otherArea;
			if (minX != other.minX)
				return minX < other.minX;
			if (minZ != other.minZ)
				return minZ < other.minZ;
			int width = maxXExclusive - minX;
			int otherWidth = other.maxXExclusive - other.minX;
			return width > otherWidth;
		}
	}
}
