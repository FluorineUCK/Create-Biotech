package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.CellKind.AIR;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.CellKind.ALLOWED_INTERNAL;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.CellKind.CASING;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.CellKind.COMPUTER;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.CellKind.ILLEGAL;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.ProfileLoadState.CORRUPT;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.ProfileLoadState.EMPTY;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.ProfileLoadState.VALID;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.AMBIGUOUS;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.DISCONNECTED_BUS;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.IDENTITY_INVALID;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.ILLEGAL_INTERNAL;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.NODE_LIMIT;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.OPEN_SHELL;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.PARTIAL_UNLOADED;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.SIZE;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.SPACE;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.STRUCTURE_IDENTITY_CONFLICT;
import static com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.State.VALID_NOT_READY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.ComputerObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.Limits;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureScanner.ScanResult;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class ComputerStructureScannerTest {
	private static final BlockPos SEED = BlockPos.ZERO;
	private static final Limits LIMITS = Limits.fromValues(3, 7, 32);
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
		net.minecraft.core.registries.Registries.DIMENSION,
		ResourceLocation.fromNamespaceAndPath("create_biotech", "scanner_test"));
	private static final UUID MEMBER = new UUID(90, 1);
	private static final ComputerProfile PROFILE = ComputerProfile.forKind(NodeKind.VILLAGER, 1, 64).orElseThrow();

	@Test
	void acceptsThreeByFiveBySevenAndReturnsExactBoundsNodesBusAndChunks() {
		BoundingBox bounds = box(-1, -2, -3, 1, 2, 3);
		ThrowingView view = sealed(bounds, SEED, observation(1, SEED));
		BlockPos bus = new BlockPos(0, 1, 0);
		view.put(bus, CASING);

		ScanResult result = ComputerStructureScanner.scan(view, SEED, LIMITS);

		assertEquals(ComputerStructureScanner.State.VALID, result.state());
		assertEquals(MEMBER, result.observedStructureMemberId());
		ComputerStructureSnapshot snapshot = result.snapshot();
		assertBounds(bounds, snapshot.bounds());
		assertEquals(List.of(new ComputerStructureNode(id(1), address(SEED), PROFILE)), snapshot.nodes());
		assertEquals(shell(bounds).size() + 1, snapshot.casingPositions().size());
		assertTrue(snapshot.casingPositions().containsAll(shell(bounds)));
		assertTrue(snapshot.casingPositions().contains(bus));
		assertEquals(Set.of(new ChunkPos(-1, -1), new ChunkPos(-1, 0),
			new ChunkPos(0, -1), new ChunkPos(0, 0)), snapshot.containingChunks());
	}

	@Test
	void acceptsConfiguredSixteenCubedAndUsesSafeVolume4096() {
		Limits limits = Limits.fromValues(3, 16, 32);
		BoundingBox bounds = box(-1, -1, -1, 14, 14, 14);

		ScanResult result = ComputerStructureScanner.scan(sealed(bounds, SEED, observation(1, SEED)), SEED, limits);

		assertEquals(4096, limits.maxVolume());
		assertEquals(29 * 29 * 29, limits.observationWindowVolume());
		assertEquals(ComputerStructureScanner.State.VALID, result.state());
		assertBounds(bounds, result.snapshot().bounds());
	}

	@Test
	void rejectsConfiguredMinimumMinusOne() {
		Limits limits = Limits.fromValues(4, 7, 32);
		assertEquals(SIZE, ComputerStructureScanner.scan(
			sealed(box(-1, -1, -1, 1, 1, 1), SEED, observation(1, SEED)), SEED, limits).state());
	}

	@Test
	void rejectsConfiguredMaximumPlusOneWithoutReadingPastTheBoundedWindow() {
		Limits limits = Limits.fromValues(3, 7, 32);
		ThrowingView view = sealed(box(-3, -3, -3, 4, 4, 4), SEED, observation(1, SEED));

		assertEquals(SIZE, ComputerStructureScanner.scan(view, SEED, limits).state());
		assertTrue(view.requested().stream().allMatch(pos -> chebyshev(SEED, pos) <= 5));
		assertTrue(view.cellReads() <= limits.observationWindowVolume());
	}

	@Test
	void externalCasingSpurDoesNotExpandTheValidCandidate() {
		BoundingBox bounds = box(-1, -1, -1, 1, 1, 1);
		ThrowingView view = sealed(bounds, SEED, observation(1, SEED));
		view.put(new BlockPos(2, 0, 0), CASING);

		ScanResult result = ComputerStructureScanner.scan(view, SEED, LIMITS);

		assertEquals(ComputerStructureScanner.State.VALID, result.state());
		assertBounds(bounds, result.snapshot().bounds());
		assertFalse(result.snapshot().casingPositions().contains(new BlockPos(2, 0, 0)));
	}

	@Test
	void twoCasingConnectedStructuresAreNotMergedIntoOneComponent() {
		BoundingBox first = box(-1, -1, -1, 1, 1, 1);
		BoundingBox second = box(4, -1, -1, 6, 1, 1);
		ThrowingView view = new ThrowingView();
		view.seal(first).computer(SEED, observation(1, SEED));
		view.seal(second).computer(new BlockPos(5, 0, 0), observation(2, new BlockPos(5, 0, 0)));
		view.put(new BlockPos(2, 0, 0), CASING).put(new BlockPos(3, 0, 0), CASING);

		ScanResult one = ComputerStructureScanner.scan(view, SEED, LIMITS);
		ScanResult two = ComputerStructureScanner.scan(view, new BlockPos(5, 0, 0), LIMITS);

		assertEquals(ComputerStructureScanner.State.VALID, one.state());
		assertEquals(ComputerStructureScanner.State.VALID, two.state());
		assertBounds(first, one.snapshot().bounds());
		assertBounds(second, two.snapshot().bounds());
	}

	@Test
	void nestedCompleteShellsReturnAmbiguousWithNoSnapshot() {
		ThrowingView view = new ThrowingView();
		view.seal(box(-3, -3, -3, 3, 3, 3));
		view.seal(box(-1, -1, -1, 1, 1, 1));
		view.put(new BlockPos(2, 0, 0), CASING).computer(SEED, observation(1, SEED));

		ScanResult result = ComputerStructureScanner.scan(view, SEED, LIMITS);

		assertEquals(AMBIGUOUS, result.state());
		assertEquals(null, result.snapshot());
	}

	@Test
	void shellHoleReturnsOpenShell() {
		ThrowingView view = sealed(box(-1, -1, -1, 1, 1, 1), SEED, observation(1, SEED));
		view.put(new BlockPos(-1, -1, 0), AIR);
		assertEquals(OPEN_SHELL, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void computerOnShellIsRejectedBecauseShellRequiresCasing() {
		BlockPos shellComputer = new BlockPos(-1, -1, 0);
		ThrowingView view = sealed(box(-1, -1, -1, 1, 1, 1), SEED, observation(1, SEED));
		view.computer(shellComputer, observation(2, shellComputer));
		assertEquals(OPEN_SHELL, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void taggedComponentIsAcceptedOnlyInTheInterior() {
		ThrowingView view = sealed(box(-1, -1, -1, 3, 3, 3), SEED, observation(1, SEED));
		view.put(new BlockPos(1, 1, 1), ALLOWED_INTERNAL);
		assertEquals(ComputerStructureScanner.State.VALID,
			ComputerStructureScanner.scan(view, SEED, LIMITS).state());

		view.put(new BlockPos(-1, 1, 1), ALLOWED_INTERNAL);
		assertEquals(OPEN_SHELL, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void illegalInteriorReturnsIllegalInternal() {
		ThrowingView view = sealed(box(-1, -1, -1, 3, 3, 3), SEED, observation(1, SEED));
		view.put(new BlockPos(1, 1, 1), ILLEGAL);
		assertEquals(ILLEGAL_INTERNAL, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void isolatedInternalBusReturnsDisconnectedBus() {
		ThrowingView view = sealed(box(-3, -3, -3, 3, 3, 3), SEED, observation(1, SEED));
		view.put(new BlockPos(1, 0, 0), CASING);
		assertEquals(DISCONNECTED_BUS, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void thirtyThirdComputerReturnsNodeLimitAtDefaultLimits() {
		ThrowingView view = new ThrowingView().seal(box(-1, -1, -1, 5, 5, 5));
		int ordinal = 1;
		for (int y = 0; y <= 4; y++) for (int z = 0; z <= 4; z++) {
			BlockPos pos = new BlockPos(0, y, z);
			view.computer(pos, observation(ordinal++, pos));
		}
		for (int y = 0; y < 2; y++) for (int z = 0; z < 4; z++) {
			BlockPos pos = new BlockPos(4, y, z);
			view.computer(pos, observation(ordinal++, pos));
		}

		assertEquals(NODE_LIMIT, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void emptyComputerCountsTowardLimitAndReturnsValidNotReady() {
		Limits oneNode = Limits.fromValues(3, 7, 1);
		ComputerObservation empty = new ComputerObservation(id(1), MEMBER, EMPTY, null, address(SEED));
		ScanResult result = ComputerStructureScanner.scan(
			sealed(box(-1, -1, -1, 1, 1, 1), SEED, empty), SEED, oneNode);

		assertEquals(VALID_NOT_READY, result.state());
		assertEquals(1, result.snapshot().nodes().size());
		assertEquals(null, result.snapshot().nodes().getFirst().profile());
	}

	@Test
	void missingOrDuplicateComputerIdReturnsIdentityInvalidWithoutCoordinator() {
		ComputerObservation missing = new ComputerObservation(null, MEMBER, VALID, PROFILE, address(SEED));
		assertEquals(IDENTITY_INVALID, ComputerStructureScanner.scan(
			sealed(box(-1, -1, -1, 1, 1, 1), SEED, missing), SEED, LIMITS).state());

		BoundingBox bounds = box(-1, -1, -1, 1, 1, 3);
		ThrowingView duplicate = sealed(bounds, SEED, observation(1, SEED));
		BlockPos other = new BlockPos(0, 0, 2);
		duplicate.computer(other, observation(1, other));
		assertEquals(IDENTITY_INVALID, ComputerStructureScanner.scan(duplicate, SEED, LIMITS).state());
	}

	@Test
	void conflictingStructureMemberIdsReturnsStructureIdentityConflict() {
		BoundingBox bounds = box(-1, -1, -1, 1, 1, 3);
		ThrowingView view = sealed(bounds, SEED, observation(1, SEED));
		BlockPos other = new BlockPos(0, 0, 2);
		view.computer(other, new ComputerObservation(id(2), new UUID(91, 2), VALID, PROFILE, address(other)));
		assertEquals(STRUCTURE_IDENTITY_CONFLICT, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
	}

	@Test
	void corruptPersistedProfileReturnsIdentityInvalidRatherThanEmpty() {
		ComputerObservation corrupt = new ComputerObservation(id(1), MEMBER, CORRUPT, null, address(SEED));
		assertEquals(IDENTITY_INVALID, ComputerStructureScanner.scan(
			sealed(box(-1, -1, -1, 1, 1, 1), SEED, corrupt), SEED, LIMITS).state());
	}

	@Test
	void sameSpaceFailureReturnsSpace() {
		ThrowingView view = sealed(box(-1, -1, -1, 1, 1, 1), SEED, observation(1, SEED));
		view.foreign.add(new BlockPos(-1, -1, 0));
		assertEquals(SPACE, ComputerStructureScanner.scan(view, SEED, LIMITS).state());
		assertEquals(0, view.readsAfterForeign());
	}

	@Test
	void neverReadsStateOrBlockEntityAfterIsLoadedReturnsFalse() {
		ThrowingView view = sealed(box(-1, -1, -1, 1, 1, 1), SEED, observation(1, SEED));
		view.unloaded.add(new BlockPos(-1, -1, 0));

		ScanResult result = ComputerStructureScanner.scan(view, SEED, LIMITS);

		assertEquals(PARTIAL_UNLOADED, result.state());
		assertEquals(0, view.readsAfterUnloaded());
		assertTrue(view.cellReads() <= LIMITS.observationWindowVolume());
	}

	@Test
	void independentlyDecodedSnapshotsAreEqualAndHaveEqualHashes() {
		ComputerStructureSnapshot first = ComputerStructureScanner.scan(
			sealed(box(-1, -1, -1, 1, 1, 1), SEED, observation(1, SEED)), SEED, LIMITS).snapshot();
		ComputerStructureSnapshot second = ComputerStructureSnapshot.load(first.save()).orElseThrow();
		ComputerStructureSnapshot third = ComputerStructureSnapshot.load(first.save()).orElseThrow();

		assertNotSame(first, second);
		assertNotSame(second, third);
		assertEquals(first, second);
		assertEquals(second, third);
		assertEquals(first.hashCode(), second.hashCode());
		assertEquals(second.hashCode(), third.hashCode());
	}

	@Test
	void changingAnyBoundsNodeCasingOrChunkComponentBreaksEquality() {
		ComputerStructureSnapshot base = snapshot(box(-2, -2, -2, 2, 2, 2), 1, Set.of());
		ComputerStructureSnapshot changedBoundsAndChunks = snapshot(box(15, -2, -2, 19, 2, 2), 1, Set.of());
		ComputerStructureSnapshot changedNode = snapshot(box(-2, -2, -2, 2, 2, 2), 2, Set.of());
		ComputerStructureSnapshot changedCasing = snapshot(box(-2, -2, -2, 2, 2, 2), 1,
			Set.of(new BlockPos(0, 0, 0)));

		assertNotEquals(base, changedBoundsAndChunks);
		assertNotEquals(base, changedNode);
		assertNotEquals(base, changedCasing);
		assertNotEquals(base.containingChunks(), changedBoundsAndChunks.containingChunks());
	}

	@Test
	void snapshotCodecUsesStrictDeterministicLongArrays() {
		ComputerStructureSnapshot snapshot = snapshot(box(-1, -1, -1, 1, 1, 1), 1, Set.of());
		CompoundTag tag = snapshot.save();
		assertEquals(Set.of("Version", "Bounds", "Nodes", "CasingPositions", "ContainingChunks"), tag.getAllKeys());
		assertTrue(tag.contains("CasingPositions", Tag.TAG_LONG_ARRAY));
		assertTrue(tag.contains("ContainingChunks", Tag.TAG_LONG_ARRAY));
		long[] positions = tag.getLongArray("CasingPositions");
		long[] chunks = tag.getLongArray("ContainingChunks");
		assertStrictlySorted(positions);
		assertStrictlySorted(chunks);

		CompoundTag duplicatePosition = tag.copy();
		long[] duplicated = java.util.Arrays.copyOf(positions, positions.length + 1);
		duplicated[duplicated.length - 1] = positions[0];
		duplicatePosition.putLongArray("CasingPositions", duplicated);
		assertTrue(ComputerStructureSnapshot.load(duplicatePosition).isEmpty());

		CompoundTag duplicateChunk = tag.copy();
		duplicateChunk.putLongArray("ContainingChunks", new long[] {chunks[0], chunks[0]});
		assertTrue(ComputerStructureSnapshot.load(duplicateChunk).isEmpty());
	}

	@Test
	void oversizedCanonicalChunkArrayIsRejectedWithinBoundsDerivedWorkBudget() {
		CompoundTag tag = snapshot(box(-1, -1, -1, 1, 1, 1), 1, Set.of()).save();
		long[] oversized = new long[1_000_000];
		for (int i = 0; i < oversized.length; i++) oversized[i] = i;
		tag.putLongArray("ContainingChunks", oversized);

		assertTimeoutPreemptively(Duration.ofMillis(100),
			() -> assertTrue(ComputerStructureSnapshot.load(tag).isEmpty()));
	}

	@Test
	void limitsClampConfigInputsAndRejectDirectAlternateVolumePolicy() {
		assertEquals(new Limits(3, 16, 4096, 256), Limits.fromValues(-20, 99, 999));
		assertThrows(IllegalArgumentException.class, () -> new Limits(3, 7, 342, 32));
		assertThrows(IllegalArgumentException.class, () -> new Limits(2, 7, 343, 32));
		assertThrows(IllegalArgumentException.class, () -> new Limits(3, 17, 4913, 32));
	}

	private static ComputerStructureSnapshot snapshot(BoundingBox bounds, int nodeOrdinal,
		Set<BlockPos> extraCasing) {
		Set<BlockPos> casing = new LinkedHashSet<>(shell(bounds));
		casing.addAll(extraCasing);
		BlockPos nodePos = new BlockPos(bounds.minX() + 1, bounds.minY() + 1, bounds.minZ() + 1);
		List<ComputerStructureNode> nodes = List.of(new ComputerStructureNode(id(nodeOrdinal), address(nodePos), PROFILE));
		return new ComputerStructureSnapshot(bounds, nodes, casing, chunks(bounds));
	}

	private static Set<ChunkPos> chunks(BoundingBox bounds) {
		Set<ChunkPos> result = new LinkedHashSet<>();
		for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX(), 16); x++)
			for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ(), 16); z++)
				result.add(new ChunkPos(x, z));
		return result;
	}

	private static ThrowingView sealed(BoundingBox bounds, BlockPos computer, ComputerObservation observation) {
		return new ThrowingView().seal(bounds).computer(computer, observation);
	}

	private static Set<BlockPos> shell(BoundingBox bounds) {
		Set<BlockPos> result = new LinkedHashSet<>();
		for (int x = bounds.minX(); x <= bounds.maxX(); x++)
			for (int y = bounds.minY(); y <= bounds.maxY(); y++)
				for (int z = bounds.minZ(); z <= bounds.maxZ(); z++)
					if (x == bounds.minX() || x == bounds.maxX() || y == bounds.minY()
						|| y == bounds.maxY() || z == bounds.minZ() || z == bounds.maxZ())
						result.add(new BlockPos(x, y, z));
		return result;
	}

	private static BoundingBox box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
	}

	private static ComputerObservation observation(int ordinal, BlockPos pos) {
		return new ComputerObservation(id(ordinal), MEMBER, VALID, PROFILE, address(pos));
	}

	private static UUID id(int ordinal) {
		return new UUID(0, ordinal);
	}

	private static SpaceAddress address(BlockPos pos) {
		return new SpaceAddress(DIMENSION, null, pos);
	}

	private static int chebyshev(BlockPos a, BlockPos b) {
		return Math.max(Math.abs(a.getX() - b.getX()),
			Math.max(Math.abs(a.getY() - b.getY()), Math.abs(a.getZ() - b.getZ())));
	}

	private static void assertBounds(BoundingBox expected, BoundingBox actual) {
		assertEquals(List.of(expected.minX(), expected.minY(), expected.minZ(), expected.maxX(), expected.maxY(), expected.maxZ()),
			List.of(actual.minX(), actual.minY(), actual.minZ(), actual.maxX(), actual.maxY(), actual.maxZ()));
	}

	private static void assertStrictlySorted(long[] values) {
		for (int i = 1; i < values.length; i++) assertTrue(values[i - 1] < values[i]);
	}

	private static final class ThrowingView implements ComputerStructureScanner.View {
		private final Map<BlockPos, ComputerStructureScanner.CellKind> cells = new HashMap<>();
		private final Map<BlockPos, ComputerObservation> observations = new HashMap<>();
		private final Set<BlockPos> unloaded = new HashSet<>();
		private final Set<BlockPos> foreign = new HashSet<>();
		private final Set<BlockPos> requested = new HashSet<>();
		private final Set<BlockPos> stateReads = new HashSet<>();
		private int readsAfterUnloaded;
		private int readsAfterForeign;

		ThrowingView seal(BoundingBox bounds) {
			shell(bounds).forEach(pos -> put(pos, CASING));
			return this;
		}

		ThrowingView put(BlockPos pos, ComputerStructureScanner.CellKind kind) {
			cells.put(pos.immutable(), kind);
			if (kind != COMPUTER) observations.remove(pos);
			return this;
		}

		ThrowingView computer(BlockPos pos, ComputerObservation observation) {
			put(pos, COMPUTER);
			if (observation != null) observations.put(pos.immutable(), observation);
			return this;
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			requested.add(pos.immutable());
			return !unloaded.contains(pos);
		}

		@Override
		public boolean sameSpace(BlockPos origin, BlockPos pos) {
			if (unloaded.contains(pos)) {
				readsAfterUnloaded++;
				throw new AssertionError("sameSpace called after unloaded at " + pos);
			}
			return !foreign.contains(pos);
		}

		@Override
		public ComputerStructureScanner.CellKind cellAt(BlockPos pos) {
			if (unloaded.contains(pos)) {
				readsAfterUnloaded++;
				throw new AssertionError("cellAt called after unloaded at " + pos);
			}
			if (foreign.contains(pos)) {
				readsAfterForeign++;
				throw new AssertionError("cellAt called after wrong space at " + pos);
			}
			stateReads.add(pos.immutable());
			return cells.getOrDefault(pos, AIR);
		}

		@Override
		public ComputerObservation computerAt(BlockPos pos) {
			if (unloaded.contains(pos)) {
				readsAfterUnloaded++;
				throw new AssertionError("computerAt called after unloaded at " + pos);
			}
			if (foreign.contains(pos)) {
				readsAfterForeign++;
				throw new AssertionError("computerAt called after wrong space at " + pos);
			}
			return observations.get(pos);
		}

		int readsAfterUnloaded() { return readsAfterUnloaded; }
		int readsAfterForeign() { return readsAfterForeign; }
		int cellReads() { return stateReads.size(); }
		Set<BlockPos> requested() { return Set.copyOf(requested); }
	}
}
