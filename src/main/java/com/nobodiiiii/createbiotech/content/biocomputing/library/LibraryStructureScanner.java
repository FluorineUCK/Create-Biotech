package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Bounded physical shelf discovery. A shelf location is not proof of stored media or capacity. */
public final class LibraryStructureScanner {
	private static final List<Direction> CORE_DIRECTIONS = List.of(Direction.NORTH, Direction.SOUTH,
		Direction.EAST, Direction.WEST, Direction.DOWN);
	private static final List<Direction> SHELF_DIRECTIONS = List.of(Direction.DOWN, Direction.UP,
		Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

	private LibraryStructureScanner() {}

	public interface View {
		boolean isLoaded(BlockPos pos);
		boolean sameSpace(BlockPos core, BlockPos candidate);
		MemberKind memberAt(BlockPos pos);
	}

	public enum MemberKind { NONE, CORE_LOWER, CORE_UPPER, BOOKSHELF, MEDIA_SHELF }
	public enum State { UNFORMED, VALID, PARTIAL, TOO_LARGE, TOO_WIDE, CORE_CONFLICT, SPACE_MISMATCH }

	/** Owns only immutable positions, never world objects, slot contents or recipe-specific budgets. */
	public record Snapshot(List<BlockPos> members, List<BlockPos> ordinaryShelves, List<BlockPos> mediaShelves,
		BlockPos min, BlockPos max) {
		public Snapshot {
			members = members.stream().map(BlockPos::immutable).toList();
			ordinaryShelves = ordinaryShelves.stream().map(BlockPos::immutable).toList();
			mediaShelves = mediaShelves.stream().map(BlockPos::immutable).toList();
			min = min.immutable();
			max = max.immutable();
		}
	}

	/** Non-VALID snapshots are diagnostic partial geometry, not authorization to access storage. */
	public record ScanResult(State state, @Nullable Snapshot snapshot) {
		public ScanResult { Objects.requireNonNull(state, "state"); }
	}

	public static ScanResult scan(View view, BlockPos core, int maxMembers, int maxAxisSpan) {
		Objects.requireNonNull(view, "view");
		core = Objects.requireNonNull(core, "core").immutable();
		if (maxMembers < 1 || maxAxisSpan < 1)
			throw new IllegalArgumentException("scan limits must be positive");
		if (!view.isLoaded(core)) return new ScanResult(State.PARTIAL, null);
		if (view.memberAt(core) != MemberKind.CORE_LOWER) return new ScanResult(State.UNFORMED, null);

		var pending = new ArrayDeque<BlockPos>();
		var members = new ArrayList<BlockPos>();
		var ordinary = new ArrayList<BlockPos>();
		var media = new ArrayList<BlockPos>();
		var discovered = new HashSet<BlockPos>();
		var inspected = new HashSet<BlockPos>();
		pending.add(core);
		members.add(core);
		discovered.add(core);
		Bounds bounds = new Bounds(core, core);

		while (!pending.isEmpty()) {
			BlockPos current = pending.removeFirst();
			for (Direction direction : current.equals(core) ? CORE_DIRECTIONS : SHELF_DIRECTIONS) {
				BlockPos candidate = current.relative(direction).immutable();
				if (!inspected.add(candidate)) continue;
				if (!view.isLoaded(candidate)) return result(State.PARTIAL, members, ordinary, media, bounds);
				MemberKind kind = view.memberAt(candidate);
				if (kind == MemberKind.NONE) continue;
				if (!view.sameSpace(core, candidate)) return result(State.SPACE_MISMATCH, members, ordinary, media, bounds);
				if (candidate.equals(core) || kind == MemberKind.CORE_UPPER) continue;
				if (kind == MemberKind.CORE_LOWER) return result(State.CORE_CONFLICT, members, ordinary, media, bounds);
				if (!discovered.add(candidate)) continue;
				members.add(candidate);
				if (kind == MemberKind.BOOKSHELF) ordinary.add(candidate);
				else if (kind == MemberKind.MEDIA_SHELF) media.add(candidate);
				else throw new IllegalStateException("Unhandled member kind " + kind);
				bounds = bounds.include(candidate);
				if (members.size() > maxMembers) return result(State.TOO_LARGE, members, ordinary, media, bounds);
				if (bounds.exceeds(maxAxisSpan)) return result(State.TOO_WIDE, members, ordinary, media, bounds);
				pending.addLast(candidate);
			}
		}
		return result(State.VALID, members, ordinary, media, bounds);
	}

	private static ScanResult result(State state, List<BlockPos> members, List<BlockPos> ordinary,
		List<BlockPos> media, Bounds bounds) {
		return new ScanResult(state, new Snapshot(members, ordinary, media, bounds.min(), bounds.max()));
	}

	private record Bounds(BlockPos min, BlockPos max) {
		private Bounds include(BlockPos pos) {
			return new Bounds(new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()),
				Math.min(min.getZ(), pos.getZ())), new BlockPos(Math.max(max.getX(), pos.getX()),
				Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ())));
		}
		private boolean exceeds(int maximum) {
			return (long) max.getX() - min.getX() + 1 > maximum || (long) max.getY() - min.getY() + 1 > maximum
				|| (long) max.getZ() - min.getZ() + 1 > maximum;
		}
	}
}
