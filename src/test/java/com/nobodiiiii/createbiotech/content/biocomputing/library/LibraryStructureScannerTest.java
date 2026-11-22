package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.MemberKind;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.ScanResult;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.Snapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.View;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

class LibraryStructureScannerTest {
	private static final BlockPos CORE = new BlockPos(0, 64, 0);

	@Test void ordinaryShelvesConnectPhysicalMediaShelvesWithoutBecomingMedia() {
		var view = core().put(CORE.east(), MemberKind.BOOKSHELF)
			.put(CORE.east(2), MemberKind.MEDIA_SHELF).put(CORE.east(2).above(), MemberKind.MEDIA_SHELF);
		var result = scan(view);
		assertEquals(State.VALID, result.state());
		assertEquals(List.of(CORE, CORE.east(), CORE.east(2), CORE.east(2).above()), result.snapshot().members());
		assertEquals(List.of(CORE.east()), result.snapshot().ordinaryShelves());
		assertEquals(List.of(CORE.east(2), CORE.east(2).above()), result.snapshot().mediaShelves());
		assertEquals(CORE, result.snapshot().min());
		assertEquals(CORE.east(2).above(), result.snapshot().max());
	}

	@Test void coreAloneIsValidGeometryButContainsNoStorageShelf() {
		var result = LibraryStructureScanner.scan(core(), CORE, 1, 1);
		assertEquals(State.VALID, result.state());
		assertEquals(List.of(CORE), result.snapshot().members());
		assertEquals(List.of(), result.snapshot().ordinaryShelves());
		assertEquals(List.of(), result.snapshot().mediaShelves());
	}

	@Test void disconnectedShelvesAndAirAreNotInferredFromTheBoundingBox() {
		var view = core().put(CORE.east(), MemberKind.BOOKSHELF)
			.put(CORE.east(2), MemberKind.BOOKSHELF).put(CORE.east(2).south(), MemberKind.BOOKSHELF)
			.put(CORE.east(2).south(2), MemberKind.BOOKSHELF).put(CORE.south(2), MemberKind.MEDIA_SHELF);
		var result = scan(view);
		assertEquals(State.VALID, result.state());
		assertEquals(CORE.east(2).south(2), result.snapshot().max());
		assertEquals(5, result.snapshot().members().size());
		assertEquals(List.of(), result.snapshot().mediaShelves());
		assertFalse(result.snapshot().members().contains(CORE.east().south()));
	}

	@Test void coreUpperNeverBridgesEvenWhenReachedViaAnotherShelf() {
		var view = core().put(CORE.above(), MemberKind.CORE_UPPER)
			.put(CORE.east(), MemberKind.BOOKSHELF).put(CORE.east().above(), MemberKind.BOOKSHELF)
			.put(CORE.above().west(), MemberKind.MEDIA_SHELF);
		var result = scan(view);
		assertEquals(State.VALID, result.state());
		assertEquals(List.of(CORE, CORE.east(), CORE.east().above()), result.snapshot().members());
		assertTrue(view.spaceCalls.contains(CORE.above()));
		assertEquals(List.of(), result.snapshot().mediaShelves());
	}

	@Test void coreTopIsNotAnInitialConnection() {
		var result = scan(core().put(CORE.above(), MemberKind.MEDIA_SHELF));
		assertEquals(State.VALID, result.state());
		assertEquals(List.of(CORE), result.snapshot().members());
	}

	@Test void coreBottomAndEveryHorizontalSideAreConnections() {
		for (Direction direction : List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)) {
			var result = scan(core().put(CORE.relative(direction), MemberKind.MEDIA_SHELF));
			assertEquals(State.VALID, result.state(), direction.toString());
			assertEquals(List.of(CORE.relative(direction)), result.snapshot().mediaShelves());
		}
	}

	@Test void cyclesAreDeduplicatedAndOnlyNonEmptyCandidatesConsultSpace() {
		var view = core().put(CORE.east(), MemberKind.BOOKSHELF)
			.put(CORE.south(), MemberKind.MEDIA_SHELF).put(CORE.east().south(), MemberKind.BOOKSHELF);
		var result = scan(view);
		assertEquals(State.VALID, result.state());
		assertEquals(4, result.snapshot().members().size());
		assertEquals(Set.copyOf(result.snapshot().members()), Set.copyOf(view.spaceCalls));
		assertEquals(4, view.spaceCalls.size());
		view.classifications.forEach((pos, count) -> assertEquals(pos.equals(CORE) ? 2 : 1, count.intValue()));
	}

	@Test void memberLimitAcceptsExactSizeAndStopsAtFirstExcessMember() {
		var view = core().put(CORE.east(), MemberKind.MEDIA_SHELF).put(CORE.east(2), MemberKind.BOOKSHELF);
		assertEquals(State.VALID, LibraryStructureScanner.scan(view, CORE, 3, 3).state());
		view.classifications.clear();
		var limited = LibraryStructureScanner.scan(view, CORE, 2, 3);
		assertEquals(State.TOO_LARGE, limited.state());
		assertEquals(3, limited.snapshot().members().size());
		assertFalse(view.classifications.containsKey(CORE.east(3)));
	}

	@Test void inclusiveAxisSpanAcceptsExactSizeAndReportsTheExcessBounds() {
		var view = core().put(CORE.west(), MemberKind.MEDIA_SHELF).put(CORE.west(2), MemberKind.BOOKSHELF);
		assertEquals(State.VALID, LibraryStructureScanner.scan(view, CORE, 3, 3).state());
		var limited = LibraryStructureScanner.scan(view, CORE, 3, 2);
		assertEquals(State.TOO_WIDE, limited.state());
		assertEquals(CORE.west(2), limited.snapshot().min());
		assertEquals(CORE, limited.snapshot().max());
	}

	@Test void secondCoreIsAnExplicitConflictFromEitherEnd() {
		var view = core().put(CORE.east(), MemberKind.BOOKSHELF).put(CORE.east(2), MemberKind.CORE_LOWER);
		assertEquals(State.CORE_CONFLICT, scan(view).state());
		assertEquals(State.CORE_CONFLICT, LibraryStructureScanner.scan(view, CORE.east(2), 10, 10).state());
	}

	@Test void unloadedCoreIsNotClassifiedAndHasNoSnapshot() {
		var view = core();
		view.unloaded.add(CORE);
		var result = scan(view);
		assertEquals(State.PARTIAL, result.state());
		assertNull(result.snapshot());
		assertTrue(view.classifications.isEmpty());
		assertTrue(view.spaceCalls.isEmpty());
	}

	@Test void unloadedFrontierHasPartialDiagnosticsNotAnEmptySuccessfulLibrary() {
		var view = core().put(CORE.east(), MemberKind.MEDIA_SHELF);
		view.unloaded.add(CORE.east(2));
		var result = scan(view);
		assertEquals(State.PARTIAL, result.state());
		assertEquals(List.of(CORE, CORE.east()), result.snapshot().members());
		assertFalse(view.classifications.containsKey(CORE.east(2)));
		assertFalse(view.spaceCalls.contains(CORE.east(2)));
	}

	@Test void absentOrUpperCoreIsUnformedNotLoadedEmptyStorage() {
		for (MemberKind kind : List.of(MemberKind.NONE, MemberKind.CORE_UPPER, MemberKind.BOOKSHELF, MemberKind.MEDIA_SHELF)) {
			var result = scan(new Fixture().put(CORE, kind));
			assertEquals(State.UNFORMED, result.state(), kind.toString());
			assertNull(result.snapshot());
		}
	}

	@Test void crossSpaceHasItsOwnErrorAndDoesNotIncludeTheForeignShelf() {
		var view = core().put(CORE.east(), MemberKind.MEDIA_SHELF);
		view.foreign.add(CORE.east());
		var result = scan(view);
		assertEquals(State.SPACE_MISMATCH, result.state());
		assertEquals(List.of(CORE.east()), view.spaceCalls);
		assertEquals(List.of(CORE), result.snapshot().members());
		assertTrue(result.snapshot().mediaShelves().isEmpty());
	}

	@Test void invalidLimitsAreRejectedBeforeAnyWorldAccess() {
		View forbidden = new View() {
			@Override public boolean isLoaded(BlockPos pos) { throw new AssertionError("world access"); }
			@Override public boolean sameSpace(BlockPos first, BlockPos second) { throw new AssertionError("world access"); }
			@Override public MemberKind memberAt(BlockPos pos) { throw new AssertionError("world access"); }
		};
		assertThrows(IllegalArgumentException.class, () -> LibraryStructureScanner.scan(forbidden, CORE, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> LibraryStructureScanner.scan(forbidden, CORE, 1, 0));
		assertThrows(IllegalArgumentException.class, () -> LibraryStructureScanner.scan(forbidden, CORE, -1, 1));
		assertThrows(IllegalArgumentException.class, () -> LibraryStructureScanner.scan(forbidden, CORE, 1, -1));
	}

	@Test void snapshotDoesNotRetainMutablePositionsOrCallerLists() {
		var core = CORE.mutable();
		var shelf = CORE.east().mutable();
		var media = CORE.east(2).mutable();
		var members = new ArrayList<BlockPos>(List.of(core, shelf, media));
		var ordinary = new ArrayList<BlockPos>(List.of(shelf));
		var storage = new ArrayList<BlockPos>(List.of(media));
		var snapshot = new Snapshot(members, ordinary, storage, core, media);
		core.set(10, 10, 10);
		shelf.set(20, 20, 20);
		media.set(30, 30, 30);
		members.clear(); ordinary.clear(); storage.clear();
		assertEquals(List.of(CORE, CORE.east(), CORE.east(2)), snapshot.members());
		assertEquals(List.of(CORE.east()), snapshot.ordinaryShelves());
		assertEquals(List.of(CORE.east(2)), snapshot.mediaShelves());
		assertEquals(CORE, snapshot.min());
		assertEquals(CORE.east(2), snapshot.max());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.members().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.ordinaryShelves().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.mediaShelves().clear());
	}

	@Test void scanFreezesTheSuppliedCorePosition() {
		var mutable = CORE.mutable();
		var result = LibraryStructureScanner.scan(core(), mutable, 1, 1);
		mutable.set(7, 8, 9);
		assertEquals(State.VALID, result.state());
		assertEquals(List.of(CORE), result.snapshot().members());
		assertEquals(CORE, result.snapshot().min());
		assertEquals(CORE, result.snapshot().max());
	}

	private static Fixture core() { return new Fixture().put(CORE, MemberKind.CORE_LOWER); }
	private static ScanResult scan(Fixture view) { return LibraryStructureScanner.scan(view, CORE, 32, 16); }

	private static final class Fixture implements View {
		private final Map<BlockPos, MemberKind> members = new HashMap<>();
		private final Set<BlockPos> unloaded = new HashSet<>();
		private final Set<BlockPos> foreign = new HashSet<>();
		private final Map<BlockPos, Integer> classifications = new HashMap<>();
		private final List<BlockPos> spaceCalls = new ArrayList<>();

		Fixture put(BlockPos pos, MemberKind kind) { members.put(pos.immutable(), kind); return this; }
		@Override public boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
		@Override public MemberKind memberAt(BlockPos pos) {
			assertTrue(isLoaded(pos), "must not read an unloaded position");
			classifications.merge(pos.immutable(), 1, Integer::sum);
			return members.getOrDefault(pos, MemberKind.NONE);
		}
		@Override public boolean sameSpace(BlockPos first, BlockPos second) {
			assertTrue(isLoaded(first) && isLoaded(second), "must not inspect unloaded space");
			assertNotEquals(MemberKind.NONE, members.getOrDefault(second, MemberKind.NONE));
			spaceCalls.add(second.immutable());
			return !foreign.contains(second);
		}
	}
}
