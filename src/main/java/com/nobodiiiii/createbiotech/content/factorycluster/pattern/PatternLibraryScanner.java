package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Pure, bounded breadth-first scan of one connected pattern-library structure. */
public final class PatternLibraryScanner {
	private static final List<Direction> CORE_DIRECTIONS = List.of(Direction.NORTH, Direction.SOUTH,
		Direction.EAST, Direction.WEST, Direction.DOWN);
	private static final List<Direction> SHELF_DIRECTIONS = List.of(Direction.DOWN, Direction.UP,
		Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

	private PatternLibraryScanner() {}

	public interface View {
		boolean isLoaded(BlockPos pos);
		boolean sameSpace(BlockPos first, BlockPos second);
		MemberKind memberAt(BlockPos pos);
	}

	public enum MemberKind {
		NONE, CORE_LOWER, CORE_UPPER, BOOKSHELF, CHISELED_BOOKSHELF
	}

	public enum StructureState {
		UNFORMED, VALID, PARTIAL, TOO_LARGE, TOO_WIDE, CORE_CONFLICT, SPACE_MISMATCH
	}

	public record ScanResult(StructureState state, @Nullable PatternStructureSnapshot snapshot) {
		public ScanResult {
			Objects.requireNonNull(state, "state");
		}
	}

	public static ScanResult scan(View view, BlockPos core, int maxMembers, int maxAxisSpan) {
		Objects.requireNonNull(view, "view");
		core = Objects.requireNonNull(core, "core").immutable();
		if (maxMembers < 1 || maxAxisSpan < 1)
			throw new IllegalArgumentException("scan limits must be positive");
		if (!view.isLoaded(core))
			return new ScanResult(StructureState.PARTIAL, null);
		if (view.memberAt(core) != MemberKind.CORE_LOWER)
			return new ScanResult(StructureState.UNFORMED, null);

		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		List<BlockPos> members = new ArrayList<>();
		List<BlockPos> ordinary = new ArrayList<>();
		List<BlockPos> chiseled = new ArrayList<>();
		Set<BlockPos> discovered = new HashSet<>();
		Set<BlockPos> inspected = new HashSet<>();
		pending.add(core);
		members.add(core);
		discovered.add(core);
		Bounds bounds = Bounds.of(core);
		int dequeued = 0;

		while (!pending.isEmpty()) {
			BlockPos current = pending.removeFirst();
			dequeued++;
			List<Direction> directions = current.equals(core) ? CORE_DIRECTIONS : SHELF_DIRECTIONS;
			for (Direction direction : directions) {
				BlockPos candidate = current.relative(direction).immutable();
				if (!inspected.add(candidate))
					continue;
				if (!view.isLoaded(candidate))
					return result(StructureState.PARTIAL, members, ordinary, chiseled, bounds, dequeued);
				MemberKind kind = view.memberAt(candidate);
				if (kind == MemberKind.NONE)
					continue;
				if (!view.sameSpace(core, candidate))
					return result(StructureState.SPACE_MISMATCH, members, ordinary, chiseled, bounds, dequeued);
				if (candidate.equals(core))
					continue;
				if (kind == MemberKind.CORE_UPPER)
					continue;
				if (kind == MemberKind.CORE_LOWER)
					return result(StructureState.CORE_CONFLICT, members, ordinary, chiseled, bounds, dequeued);
				if (!discovered.add(candidate))
					continue;

				members.add(candidate);
				if (kind == MemberKind.BOOKSHELF)
					ordinary.add(candidate);
				else if (kind == MemberKind.CHISELED_BOOKSHELF)
					chiseled.add(candidate);
				else
					throw new IllegalStateException("Unhandled member kind " + kind);
				bounds = bounds.include(candidate);
				if (members.size() > maxMembers)
					return result(StructureState.TOO_LARGE, members, ordinary, chiseled, bounds, dequeued);
				if (bounds.anySpanExceeds(maxAxisSpan))
					return result(StructureState.TOO_WIDE, members, ordinary, chiseled, bounds, dequeued);
				pending.addLast(candidate);
			}
		}
		return result(StructureState.VALID, members, ordinary, chiseled, bounds, dequeued);
	}

	private static ScanResult result(StructureState state, List<BlockPos> members, List<BlockPos> ordinary,
		List<BlockPos> chiseled, Bounds bounds, int dequeued) {
		return new ScanResult(state, new PatternStructureSnapshot(state, members, ordinary, chiseled,
			bounds.min(), bounds.max(), dequeued));
	}

	private record Bounds(BlockPos min, BlockPos max) {
		private static Bounds of(BlockPos pos) { return new Bounds(pos, pos); }
		private Bounds include(BlockPos pos) {
			return new Bounds(new BlockPos(Math.min(min.getX(), pos.getX()), Math.min(min.getY(), pos.getY()),
				Math.min(min.getZ(), pos.getZ())), new BlockPos(Math.max(max.getX(), pos.getX()),
				Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ())));
		}
		private boolean anySpanExceeds(int maximum) {
			return max.getX() - min.getX() + 1 > maximum || max.getY() - min.getY() + 1 > maximum
				|| max.getZ() - min.getZ() + 1 > maximum;
		}
	}
}
