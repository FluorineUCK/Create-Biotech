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
import java.util.UUID;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

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
		FakeControlledRemoval controlled = new FakeControlledRemoval(true, false);

		assertFalse(PatternStorageCoreLifecycle.runControlledRemoval(controlled));

		assertTrue(controlled.pending);
		assertFalse(controlled.outputPresent);
		assertArrayEquals(FakeControlledRemoval.ORIGINAL_SNAPSHOT, controlled.snapshot);
		assertEquals(List.of("output-emitted", "entity-rejected", "rollback-output", "pending"),
			controlled.events);
	}

	@Test
	void controlledSuccessClearsOnlyAfterEntitySpawnConfirmation() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(true, true);

		assertTrue(PatternStorageCoreLifecycle.runControlledRemoval(controlled));

		assertNull(controlled.snapshot);
		assertFalse(controlled.pending);
		assertTrue(controlled.outputPresent);
		assertEquals(List.of("output-emitted", "entity-accepted", "clear-and-replace"),
			controlled.events);
	}

	@Test
	void rejectedRequiredLootOutputPreventsEntitySpawnAndPreservesSnapshot() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(false, true);

		assertFalse(PatternStorageCoreLifecycle.runControlledRemoval(controlled));

		assertEquals(0, controlled.entityAttempts);
		assertTrue(controlled.pending);
		assertFalse(controlled.outputPresent);
		assertArrayEquals(FakeControlledRemoval.ORIGINAL_SNAPSHOT, controlled.snapshot);
		assertEquals(List.of("output-rejected", "pending"), controlled.events);
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

	@Test
	void movingRemovalEntryLeavesSerializedSnapshotAndAllOutputsUntouched() {
		byte[] original = { 9, 7, 9, 3 };
		byte[] snapshot = original.clone();
		int[] effects = new int[3];

		PatternStorageCoreLifecycle.RemovalDisposition disposition =
			PatternStorageCoreLifecycle.runRemovalEntry(true, () -> {
				snapshot[0] = 0;
				effects[0]++;
				effects[1]++;
				effects[2]++;
				return PatternStorageCoreLifecycle.RemovalDisposition.RESTORED;
			});

		assertEquals(PatternStorageCoreLifecycle.RemovalDisposition.CALL_SUPER, disposition);
		assertArrayEquals(original, snapshot);
		assertEquals(0, effects[0], "entity spawn");
		assertEquals(0, effects[1], "item spawn");
		assertEquals(0, effects[2], "counterpart mutation");
	}

	@Test
	void sameBlockHalfTransitionIsARealRemovalButSameLogicalHalfIsNot() {
		PatternStorageCoreBlock block = new PatternStorageCoreBlock(BlockBehaviour.Properties.of());
		var lower = block.defaultBlockState()
			.setValue(PatternStorageCoreBlock.HALF, DoubleBlockHalf.LOWER);
		var rotatedLower = lower.setValue(PatternStorageCoreBlock.FACING, Direction.EAST);
		var upper = lower.setValue(PatternStorageCoreBlock.HALF, DoubleBlockHalf.UPPER);

		assertTrue(PatternStorageCoreLifecycle.sameLogicalPart(lower, rotatedLower));
		assertFalse(PatternStorageCoreLifecycle.sameLogicalPart(lower, upper));
		assertFalse(PatternStorageCoreLifecycle.sameLogicalPart(upper, lower));
	}

	@Test
	void entityCoordinatesPassThroughNormallyAndUseSubLevelCompatProjection() {
		Vec3 local = new Vec3(2, 3, 4);
		assertEquals(local, PatternStorageCoreLifecycle.entityWorldPosition(null, local));

		Object translated = translatedRotatedPose(100, 50, -20);
		SubLevelAccess subLevel = (SubLevelAccess) Proxy.newProxyInstance(
			SubLevelAccess.class.getClassLoader(), new Class<?>[] { SubLevelAccess.class },
			(proxy, method, arguments) -> switch (method.getName()) {
				case "logicalPose", "lastPose" -> translated;
				case "boundingBox" -> new BoundingBox3d(0, 0, 0, 16, 16, 16);
				case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000099");
				case "getName" -> "projected-test";
				default -> throw new UnsupportedOperationException(method.getName());
			});

		assertEquals(new Vec3(104, 53, -22),
			PatternStorageCoreLifecycle.entityWorldPosition(subLevel, local));
	}

	private static Object translatedRotatedPose(double x, double y, double z) {
		try {
			Class<?> vector = Class.forName("org.joml.Vector3d");
			Class<?> quaternion = Class.forName("org.joml.Quaterniond");
			Object position = vector.getConstructor(double.class, double.class, double.class)
				.newInstance(x, y, z);
			Object orientation = quaternion.getConstructor().newInstance();
			quaternion.getMethod("rotateY", double.class).invoke(orientation, Math.PI / 2);
			Object rotationPoint = vector.getConstructor().newInstance();
			Object scale = vector.getConstructor(double.class, double.class, double.class)
				.newInstance(1, 1, 1);
			return Class.forName("dev.ryanhcode.sable.companion.math.Pose3d")
				.getConstructor(vector, quaternion, vector, vector)
				.newInstance(position, orientation, rotationPoint, scale);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void clientProjectionNeitherWritesNorConsumesAuthoritativeServerState() {
		CompoundTag clientUpdate = new CompoundTag();
		PatternStorageCoreBlockEntity.writeAuthoritativeProjection(clientUpdate, true,
			tag -> tag.putByteArray("ServerSecret", new byte[] { 1, 2, 3 }));
		assertFalse(clientUpdate.contains("ServerSecret"));

		CompoundTag diskSave = new CompoundTag();
		PatternStorageCoreBlockEntity.writeAuthoritativeProjection(diskSave, false,
			tag -> tag.putByteArray("ServerSecret", new byte[] { 1, 2, 3 }));
		assertArrayEquals(new byte[] { 1, 2, 3 }, diskSave.getByteArray("ServerSecret"));

		byte[][] authoritative = { new byte[] { 8, 5, 3 } };
		CompoundTag hostileClientUpdate = new CompoundTag();
		hostileClientUpdate.putByteArray("ServerSecret", new byte[] { 0 });
		PatternStorageCoreBlockEntity.readAuthoritativeProjection(hostileClientUpdate, true,
			tag -> authoritative[0] = tag.getByteArray("ServerSecret"));
		assertArrayEquals(new byte[] { 8, 5, 3 }, authoritative[0]);

		PatternStorageCoreBlockEntity.readAuthoritativeProjection(diskSave, false,
			tag -> authoritative[0] = tag.getByteArray("ServerSecret"));
		assertArrayEquals(new byte[] { 1, 2, 3 }, authoritative[0]);
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
		private final boolean outputReady;
		private final boolean acceptEntity;
		private final List<String> events = new ArrayList<>();
		private byte[] snapshot = ORIGINAL_SNAPSHOT.clone();
		private boolean pending;
		private boolean outputPresent;
		private int entityAttempts;

		private FakeControlledRemoval(boolean outputReady, boolean acceptEntity) {
			this.outputReady = outputReady;
			this.acceptEntity = acceptEntity;
		}

		@Override
		public boolean emitRequiredOutput() {
			events.add(outputReady ? "output-emitted" : "output-rejected");
			outputPresent = outputReady;
			return outputReady;
		}

		@Override
		public boolean tryReleaseEntity() {
			entityAttempts++;
			events.add(acceptEntity ? "entity-accepted" : "entity-rejected");
			return acceptEntity;
		}

		@Override
		public void rollbackPreparedOutput() {
			events.add("rollback-output");
			outputPresent = false;
		}

		@Override
		public void markPendingSafeRelease() {
			events.add("pending");
			pending = true;
		}

		@Override
		public void commitSuccessfulRelease() {
			if (!events.equals(List.of("output-emitted", "entity-accepted")))
				throw new AssertionError("snapshot cleared before output and entity confirmation");
			if (!outputPresent)
				throw new AssertionError("required output missing at commit");
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
