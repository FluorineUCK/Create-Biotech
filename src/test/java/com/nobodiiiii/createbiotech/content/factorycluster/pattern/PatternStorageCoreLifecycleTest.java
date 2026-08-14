package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class PatternStorageCoreLifecycleTest {
	private static final PatternStorageCoreConversionHandler.CandidateFacts ADULT_LIBRARIAN =
		new PatternStorageCoreConversionHandler.CandidateFacts(true, true, true);

	@Test
	void adultLibrarianConversionInstallsSnapshotBeforeClearingHeldBox() {
		FakeConversion conversion = new FakeConversion();

		assertTrue(PatternStorageCoreConversionHandler.runConversionTransaction(
			ADULT_LIBRARIAN, conversion));

		assertEquals(List.of("lower", "upper", "install", "clear-held"), conversion.events);
		assertArrayEquals(FakeConversion.ORIGINAL_BOX, conversion.installedSnapshot);
		assertNull(conversion.heldBox);
		assertFalse(conversion.restored);
	}

	@Test
	void childUnemployedFarmerWanderingTraderAndZombieVillagerAreRejectedWithoutMutation() {
		List<PatternStorageCoreConversionHandler.CandidateFacts> rejected = List.of(
			new PatternStorageCoreConversionHandler.CandidateFacts(true, false, true),
			new PatternStorageCoreConversionHandler.CandidateFacts(true, true, false),
			new PatternStorageCoreConversionHandler.CandidateFacts(true, true, false),
			new PatternStorageCoreConversionHandler.CandidateFacts(false, true, false),
			new PatternStorageCoreConversionHandler.CandidateFacts(false, true, false));

		for (PatternStorageCoreConversionHandler.CandidateFacts candidate : rejected) {
			FakeConversion conversion = new FakeConversion();
			assertFalse(PatternStorageCoreConversionHandler.runConversionTransaction(
				candidate, conversion));
			assertTrue(conversion.events.isEmpty());
			assertArrayEquals(FakeConversion.ORIGINAL_BOX, conversion.heldBox);
			assertFalse(conversion.restored);
		}
	}

	@Test
	void upperPlacementAndBlockEntityFailuresRestoreLecternAndHeldBoxAtomically() {
		for (FailurePoint failure : List.of(FailurePoint.LOWER, FailurePoint.UPPER,
			FailurePoint.BLOCK_ENTITY)) {
			FakeConversion conversion = new FakeConversion();
			conversion.failure = failure;

			assertFalse(PatternStorageCoreConversionHandler.runConversionTransaction(
				ADULT_LIBRARIAN, conversion));

			assertTrue(conversion.restored);
			assertArrayEquals(FakeConversion.ORIGINAL_BOX, conversion.heldBox);
			assertNull(conversion.installedSnapshot);
			assertEquals("restore", conversion.events.getLast());
		}
	}

	@Test
	void blockedControlledReleaseMarksPendingWithoutClearingSnapshot() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(false);

		assertFalse(PatternStorageCoreLifecycle.runControlledRemoval(controlled));

		assertTrue(controlled.pending);
		assertArrayEquals(FakeControlledRemoval.ORIGINAL_SNAPSHOT, controlled.snapshot);
		assertEquals(List.of("entity-rejected", "pending"), controlled.events);
	}

	@Test
	void controlledSuccessClearsOnlyAfterEntitySpawnConfirmation() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(true);

		assertTrue(PatternStorageCoreLifecycle.runControlledRemoval(controlled));

		assertNull(controlled.snapshot);
		assertFalse(controlled.pending);
		assertEquals(List.of("entity-accepted", "clear-and-replace"), controlled.events);
	}

	@Test
	void entityRejectionThenAcceptedRecoveryBoxClearsOnlyAfterBoxConfirmation() {
		FakeForcedRemoval forced = new FakeForcedRemoval(false, true,
			PatternStorageCoreLifecycle.RemovalDisposition.RESTORED);

		assertEquals(PatternStorageCoreLifecycle.RemovalDisposition.CALL_SUPER,
			PatternStorageCoreLifecycle.runForcedRemoval(forced));

		assertNull(forced.snapshot);
		assertEquals(List.of("entity-rejected", "box-accepted", "clear-and-remove"),
			forced.events);
	}

	@Test
	void successfulDelegatedRemovalRunsTheSharedTransactionExactlyOnce() {
		FakeForcedRemoval forced = new FakeForcedRemoval(true, false,
			PatternStorageCoreLifecycle.RemovalDisposition.RESTORED);

		assertEquals(PatternStorageCoreLifecycle.RemovalDisposition.CALL_SUPER,
			PatternStorageCoreLifecycle.runForcedRemoval(forced));

		assertEquals(1, forced.entityAttempts);
		assertEquals(0, forced.boxAttempts);
		assertEquals(1, forced.commitCalls);
	}

	@Test
	void doubleSpawnFailureRunsOrderedRestoreAndReturnsRestoredDisposition() {
		FakeForcedRemoval forced = new FakeForcedRemoval(false, false,
			PatternStorageCoreLifecycle.RemovalDisposition.RESTORED);

		assertEquals(PatternStorageCoreLifecycle.RemovalDisposition.RESTORED,
			PatternStorageCoreLifecycle.runForcedRemoval(forced));

		assertArrayEquals(FakeForcedRemoval.ORIGINAL_SNAPSHOT, forced.snapshot);
		assertEquals(List.of("entity-rejected", "box-rejected", "remove-old-be",
			"place-lower", "place-upper", "install-snapshot"), forced.events);
		assertEquals(0, forced.commitCalls);
	}

	@Test
	void removalGuardUsesLevelIdentityAndImmutableAnchorAndAlwaysClearsInFinally() {
		Object level = new Object();
		Object otherLevel = new Object();
		BlockPos.MutableBlockPos mutableSource = new BlockPos.MutableBlockPos(4, 70, -3);
		BlockPos anchor = mutableSource.immutable();

		assertFalse(PatternStorageCoreLifecycle.isRemovalActive(level, anchor));
		assertThrows(IllegalStateException.class, () ->
			PatternStorageCoreLifecycle.withRemovalGuard(level, mutableSource, () -> {
				mutableSource.move(1, 0, 0);
				assertTrue(PatternStorageCoreLifecycle.isRemovalActive(level, anchor));
				assertFalse(PatternStorageCoreLifecycle.isRemovalActive(otherLevel, anchor));
				PatternStorageCoreLifecycle.withRemovalGuard(level, anchor,
					() -> assertTrue(PatternStorageCoreLifecycle.isRemovalActive(level, anchor)));
				assertTrue(PatternStorageCoreLifecycle.isRemovalActive(level, anchor));
				throw new IllegalStateException("exercise finally");
			}));
		assertFalse(PatternStorageCoreLifecycle.isRemovalActive(level, anchor));
	}

	private enum FailurePoint { NONE, LOWER, UPPER, BLOCK_ENTITY }

	private static final class FakeConversion
		implements PatternStorageCoreConversionHandler.ConversionOps {
		private static final byte[] ORIGINAL_BOX = { 3, 1, 4, 1, 5 };
		private final List<String> events = new ArrayList<>();
		private byte[] heldBox = ORIGINAL_BOX.clone();
		private byte[] installedSnapshot;
		private boolean restored;
		private FailurePoint failure = FailurePoint.NONE;

		@Override
		public boolean placeLower() {
			events.add("lower");
			return failure != FailurePoint.LOWER;
		}

		@Override
		public boolean placeUpper() {
			events.add("upper");
			return failure != FailurePoint.UPPER;
		}

		@Override
		public boolean installSnapshot() {
			events.add("install");
			if (failure == FailurePoint.BLOCK_ENTITY)
				return false;
			installedSnapshot = heldBox.clone();
			return true;
		}

		@Override
		public void clearHeldBox() {
			if (installedSnapshot == null)
				throw new AssertionError("held box cleared before snapshot installation");
			events.add("clear-held");
			heldBox = null;
		}

		@Override
		public void restoreLectern() {
			events.add("restore");
			restored = true;
		}
	}

	private static final class FakeControlledRemoval
		implements PatternStorageCoreLifecycle.ControlledRemovalOps {
		private static final byte[] ORIGINAL_SNAPSHOT = { 2, 7, 1, 8 };
		private final boolean acceptEntity;
		private final List<String> events = new ArrayList<>();
		private byte[] snapshot = ORIGINAL_SNAPSHOT.clone();
		private boolean pending;

		private FakeControlledRemoval(boolean acceptEntity) {
			this.acceptEntity = acceptEntity;
		}

		@Override
		public boolean tryReleaseEntity() {
			events.add(acceptEntity ? "entity-accepted" : "entity-rejected");
			return acceptEntity;
		}

		@Override
		public void markPendingSafeRelease() {
			events.add("pending");
			pending = true;
		}

		@Override
		public void commitSuccessfulRelease() {
			if (!events.equals(List.of("entity-accepted")))
				throw new AssertionError("snapshot cleared before spawn confirmation");
			events.add("clear-and-replace");
			snapshot = null;
		}
	}

	private static final class FakeForcedRemoval
		implements PatternStorageCoreLifecycle.ForcedRemovalOps {
		private static final byte[] ORIGINAL_SNAPSHOT = { 1, 6, 1, 8 };
		private final boolean acceptEntity;
		private final boolean acceptBox;
		private final PatternStorageCoreLifecycle.RemovalDisposition restoreDisposition;
		private final List<String> events = new ArrayList<>();
		private byte[] snapshot = ORIGINAL_SNAPSHOT.clone();
		private int entityAttempts;
		private int boxAttempts;
		private int commitCalls;

		private FakeForcedRemoval(boolean acceptEntity, boolean acceptBox,
			PatternStorageCoreLifecycle.RemovalDisposition restoreDisposition) {
			this.acceptEntity = acceptEntity;
			this.acceptBox = acceptBox;
			this.restoreDisposition = restoreDisposition;
		}

		@Override
		public boolean tryReleaseEntity() {
			entityAttempts++;
			events.add(acceptEntity ? "entity-accepted" : "entity-rejected");
			return acceptEntity;
		}

		@Override
		public boolean tryDropRecoveryBox() {
			boxAttempts++;
			events.add(acceptBox ? "box-accepted" : "box-rejected");
			return acceptBox;
		}

		@Override
		public void commitSuccessfulRelease() {
			if (!events.getLast().endsWith("accepted"))
				throw new AssertionError("snapshot cleared before spawn confirmation");
			commitCalls++;
			events.add("clear-and-remove");
			snapshot = null;
		}

		@Override
		public PatternStorageCoreLifecycle.RemovalDisposition restoreAfterDoubleFailure() {
			if (!Arrays.equals(ORIGINAL_SNAPSHOT, snapshot))
				throw new AssertionError("snapshot changed before restoration");
			events.add("remove-old-be");
			events.add("place-lower");
			events.add("place-upper");
			events.add("install-snapshot");
			return restoreDisposition;
		}
	}
}
