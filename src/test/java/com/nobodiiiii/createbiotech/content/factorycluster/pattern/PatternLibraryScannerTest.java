package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner.MemberKind;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner.ScanResult;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner.StructureState;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner.View;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

class PatternLibraryScannerTest {
	private static final BlockPos CORE = new BlockPos(0, 0, 0);

	@Test
	void acceptsExactlySixtyFourMembersAndFourPositionAxisSpan() {
		BlockPos core = new BlockPos(0, 3, 0);
		FakeLibraryView view = compactCuboid(core, 4, 4, 4).withCoreUpper(core.above());
		ScanResult result = PatternLibraryScanner.scan(view, core, 64, 16);
		assertEquals(StructureState.VALID, result.state());
		assertEquals(64, result.snapshot().members().size());
		assertEquals(4, result.snapshot().axisSpanX());
	}

	@Test
	void rejectsSeventeenPositionInclusiveAxisSpan() {
		FakeLibraryView view = connectedLine(CORE, Direction.EAST, 17);
		assertEquals(StructureState.TOO_WIDE, PatternLibraryScanner.scan(view, CORE, 64, 16).state());
	}

	@Test
	void rejectsSixtyFifthMember() {
		BlockPos core = new BlockPos(0, 3, 0);
		assertEquals(StructureState.TOO_LARGE,
			PatternLibraryScanner.scan(compactCuboid(core, 5, 5, 5), core, 64, 16).state());
	}

	@Test
	void rejectsSecondCoreWithoutChoosingByPosition() {
		FakeLibraryView view = connected(CORE, shelf(1, 0, 0), core(2, 0, 0));
		assertEquals(StructureState.CORE_CONFLICT, PatternLibraryScanner.scan(view, CORE, 64, 16).state());
	}

	@Test
	void unloadedFrontierPausesInsteadOfBreaking() {
		FakeLibraryView view = connectedLine(CORE, Direction.EAST, 16).withChunkUnloaded(1, 0);
		assertEquals(StructureState.PARTIAL, PatternLibraryScanner.scan(view, CORE, 64, 16).state());
	}

	@Test
	void upperCoreSideNeverBridgesToShelf() {
		FakeLibraryView view = connected(CORE, coreUpper(0, 1, 0), shelf(1, 1, 0));
		ScanResult result = PatternLibraryScanner.scan(view, CORE, 64, 16);
		assertEquals(StructureState.VALID, result.state());
		assertEquals(List.of(CORE), result.snapshot().members());
	}

	@Test
	void everyNonEmptyCandidateConsultsSameSpaceAndCrossSpaceHasExactState() {
		FakeLibraryView view = connected(CORE, shelf(1, 0, 0)).inOtherSpace(1, 0, 0);
		assertEquals(StructureState.SPACE_MISMATCH, PatternLibraryScanner.scan(view, CORE, 64, 16).state());
		assertEquals(List.of(new BlockPos(1, 0, 0)), view.sameSpaceCalls());
	}

	@Test
	void sameSpaceIsCalledExactlyOnceForEachDistinctNonEmptyCandidate() {
		FakeLibraryView view = connected(CORE, shelf(1, 0, 0));
		assertEquals(StructureState.VALID, PatternLibraryScanner.scan(view, CORE, 64, 16).state());
		assertEquals(List.of(new BlockPos(1, 0, 0), CORE), view.sameSpaceCalls());
	}

	@Test
	void snapshotIsImmutableAndRoundTripsExactNbt() {
		FakeLibraryView view = connected(CORE, shelf(1, 0, 0), shelf(2, 0, 0), chiseledShelf(0, 0, 1));
		PatternStructureSnapshot snapshot = PatternLibraryScanner.scan(view, CORE, 64, 16).snapshot();
		assertThrows(UnsupportedOperationException.class, () -> snapshot.members().add(CORE));
		assertThrows(UnsupportedOperationException.class, () -> snapshot.ordinaryShelves().clear());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.chunks().clear());
		assertEquals(snapshot, PatternStructureSnapshot.load(snapshot.save()).orElseThrow());

		CompoundTag wrongType = snapshot.save();
		wrongType.putString("QueueCount", "3");
		assertTrue(PatternStructureSnapshot.load(wrongType).isEmpty());
		CompoundTag duplicateMember = snapshot.save();
		duplicateMember.getList("Members", net.minecraft.nbt.Tag.TAG_LONG).add(
			duplicateMember.getList("Members", net.minecraft.nbt.Tag.TAG_LONG).get(0));
		assertTrue(PatternStructureSnapshot.load(duplicateMember).isEmpty());
		CompoundTag inconsistentClassification = snapshot.save();
		inconsistentClassification.getList("Ordinary", net.minecraft.nbt.Tag.TAG_LONG).clear();
		assertFalse(PatternStructureSnapshot.load(inconsistentClassification).isPresent());
		CompoundTag reorderedClassification = snapshot.save();
		var ordinary = reorderedClassification.getList("Ordinary", net.minecraft.nbt.Tag.TAG_LONG);
		var first = ordinary.get(0);
		ordinary.set(0, ordinary.get(1));
		ordinary.set(1, first);
		assertTrue(PatternStructureSnapshot.load(reorderedClassification).isEmpty());
	}

	private static FakeLibraryView compactCuboid(BlockPos core, int x, int y, int z) {
		FakeLibraryView view = new FakeLibraryView().put(core, MemberKind.CORE_LOWER);
		for (int dx = 0; dx < x; dx++)
			for (int dy = 0; dy < y; dy++)
				for (int dz = 0; dz < z; dz++) {
					BlockPos pos = core.offset(dx, dy - 3, dz);
					if (!pos.equals(core)) view.put(pos, MemberKind.BOOKSHELF);
				}
		return view;
	}

	private static FakeLibraryView connectedLine(BlockPos core, Direction direction, int members) {
		FakeLibraryView view = new FakeLibraryView().put(core, MemberKind.CORE_LOWER);
		for (int i = 1; i < members; i++) view.put(core.relative(direction, i), MemberKind.BOOKSHELF);
		return view;
	}

	private static FakeLibraryView connected(BlockPos core, BlockPos... members) {
		FakeLibraryView view = new FakeLibraryView().put(core, MemberKind.CORE_LOWER);
		for (BlockPos member : members)
			view.put(member, member instanceof CorePos ? MemberKind.CORE_LOWER
				: member instanceof UpperPos ? MemberKind.CORE_UPPER
				: member instanceof ChiseledPos ? MemberKind.CHISELED_BOOKSHELF
				: MemberKind.BOOKSHELF);
		return view;
	}

	private static BlockPos shelf(int x, int y, int z) { return new BlockPos(x, y, z); }
	private static BlockPos chiseledShelf(int x, int y, int z) { return new ChiseledPos(x, y, z); }
	private static BlockPos core(int x, int y, int z) { return new CorePos(x, y, z); }
	private static BlockPos coreUpper(int x, int y, int z) { return new UpperPos(x, y, z); }

	private static final class CorePos extends BlockPos { private CorePos(int x, int y, int z) { super(x, y, z); } }
	private static final class UpperPos extends BlockPos { private UpperPos(int x, int y, int z) { super(x, y, z); } }
	private static final class ChiseledPos extends BlockPos { private ChiseledPos(int x, int y, int z) { super(x, y, z); } }

	private static final class FakeLibraryView implements View {
		private final Map<BlockPos, MemberKind> members = new HashMap<>();
		private final Set<Long> unloadedChunks = new HashSet<>();
		private final Set<BlockPos> otherSpace = new HashSet<>();
		private final List<BlockPos> sameSpaceCalls = new java.util.ArrayList<>();

		FakeLibraryView put(BlockPos pos, MemberKind kind) {
			members.put(pos.immutable(), kind);
			return this;
		}
		FakeLibraryView withCoreUpper(BlockPos pos) { return put(pos, MemberKind.CORE_UPPER); }
		FakeLibraryView withChunkUnloaded(int chunkX, int chunkZ) {
			unloadedChunks.add(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
			return this;
		}
		FakeLibraryView inOtherSpace(int x, int y, int z) {
			otherSpace.add(new BlockPos(x, y, z));
			return this;
		}
		List<BlockPos> sameSpaceCalls() { return List.copyOf(sameSpaceCalls); }

		@Override public boolean isLoaded(BlockPos pos) {
			return !unloadedChunks.contains(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
		}
		@Override public boolean sameSpace(BlockPos first, BlockPos second) {
			sameSpaceCalls.add(second.immutable());
			return !otherSpace.contains(second);
		}
		@Override public MemberKind memberAt(BlockPos pos) {
			return members.getOrDefault(pos, MemberKind.NONE);
		}
	}
}
