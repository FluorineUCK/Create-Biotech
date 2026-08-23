package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayDeque;
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
		return new Plane(List.copyOf(tiles), Set.copyOf(owners), facing, complete);
	}

	/** Prevents one placement from joining two independently occupied planes. */
	public static boolean canExtendAt(Level level, BlockPos destination, Direction facing) {
		Set<BlockPos> owners = new HashSet<>();
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
			owners.addAll(plane.owners());
			if (owners.size() > 1)
				return false;
		}
		return true;
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

	public record Plane(List<BlockPos> tiles, Set<BlockPos> owners, @Nullable Direction facing, boolean complete) {
		private static final Plane EMPTY = new Plane(List.of(), Set.of(), null, true);

		public Plane {
			tiles = List.copyOf(tiles);
			owners = Set.copyOf(owners);
		}

		public boolean valid() {
			return complete && owners.size() <= 1;
		}

		@Nullable
		public BlockPos owner() {
			return valid() && owners.size() == 1 ? owners.iterator().next() : null;
		}
	}
}
