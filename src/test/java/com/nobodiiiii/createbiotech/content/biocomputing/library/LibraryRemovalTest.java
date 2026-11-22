package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

class LibraryRemovalTest {
    @BeforeAll static void bootstrap() throws Exception { LibraryBookMediaTest.bootstrap(); }

	@Test
	void blockedControlledReleaseMarksPendingWithoutClearingSnapshot() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(true, false);

		assertFalse(LibraryRemoval.runControlledRemoval(controlled));

		assertTrue(controlled.pending);
		assertFalse(controlled.outputPresent);
		assertArrayEquals(FakeControlledRemoval.ORIGINAL_SNAPSHOT, controlled.snapshot);
		assertEquals(List.of("output-emitted", "entity-rejected", "rollback-output", "pending"),
			controlled.events);
	}

	@Test
	void controlledSuccessClearsOnlyAfterEntitySpawnConfirmation() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(true, true);

		assertTrue(LibraryRemoval.runControlledRemoval(controlled));

		assertNull(controlled.snapshot);
		assertFalse(controlled.pending);
		assertTrue(controlled.outputPresent);
		assertEquals(List.of("output-emitted", "entity-accepted", "clear-and-replace"),
			controlled.events);
	}

	@Test
	void rejectedRequiredLootOutputPreventsEntitySpawnAndPreservesSnapshot() {
		FakeControlledRemoval controlled = new FakeControlledRemoval(false, true);

		assertFalse(LibraryRemoval.runControlledRemoval(controlled));

		assertEquals(0, controlled.entityAttempts);
		assertTrue(controlled.pending);
		assertFalse(controlled.outputPresent);
		assertArrayEquals(FakeControlledRemoval.ORIGINAL_SNAPSHOT, controlled.snapshot);
		assertEquals(List.of("output-rejected", "pending"), controlled.events);
	}

	@Test
	void entityRejectionThenAcceptedRecoveryBoxClearsOnlyAfterBoxConfirmation() {
		FakeForcedRemoval forced = new FakeForcedRemoval(false, true,
			LibraryRemoval.RemovalDisposition.RESTORED);

		assertEquals(LibraryRemoval.RemovalDisposition.CALL_SUPER,
			LibraryRemoval.runForcedRemoval(forced));

		assertNull(forced.snapshot);
		assertEquals(List.of("core-output", "entity-rejected", "box-accepted", "clear-and-remove"),
			forced.events);
	}

	@Test
	void successfulDelegatedRemovalRunsTheSharedTransactionExactlyOnce() {
		FakeForcedRemoval forced = new FakeForcedRemoval(true, false,
			LibraryRemoval.RemovalDisposition.RESTORED);

		assertEquals(LibraryRemoval.RemovalDisposition.CALL_SUPER,
			LibraryRemoval.runForcedRemoval(forced));

		assertEquals(1, forced.entityAttempts);
		assertEquals(0, forced.boxAttempts);
		assertEquals(1, forced.outputAttempts);
		assertEquals(1, forced.commitCalls);
	}

	@Test
	void doubleSpawnFailureRunsOrderedRestoreAndReturnsRestoredDisposition() {
		FakeForcedRemoval forced = new FakeForcedRemoval(false, false,
			LibraryRemoval.RemovalDisposition.RESTORED);

		assertEquals(LibraryRemoval.RemovalDisposition.RESTORED,
			LibraryRemoval.runForcedRemoval(forced));

		assertArrayEquals(FakeForcedRemoval.ORIGINAL_SNAPSHOT, forced.snapshot);
		assertEquals(List.of("core-output", "entity-rejected", "box-rejected", "rollback-core",
			"remove-old-be",
			"place-lower", "place-upper", "install-snapshot"), forced.events);
		assertEquals(0, forced.commitCalls);
	}

	@Test
	void forcedResidentlessRemovalStillEmitsExactlyOneConfiguredCoreItem() {
		FakeForcedRemoval forced = new FakeForcedRemoval(true, false,
			LibraryRemoval.RemovalDisposition.RESTORED);

		assertEquals(LibraryRemoval.RemovalDisposition.CALL_SUPER,
			LibraryRemoval.runForcedRemoval(forced, false));
		assertEquals(1, forced.outputAttempts);
		assertEquals(0, forced.entityAttempts);
		assertEquals(0, forced.boxAttempts);
		assertEquals(1, forced.commitCalls);
		assertEquals(List.of("core-output", "clear-and-remove"), forced.events);
	}

	@Test
	void rejectedConfiguredCoreOutputRestoresWithoutTouchingResident() {
		FakeForcedRemoval forced = new FakeForcedRemoval(false, true, true,
			LibraryRemoval.RemovalDisposition.RESTORED);

		assertEquals(LibraryRemoval.RemovalDisposition.RESTORED,
			LibraryRemoval.runForcedRemoval(forced));
		assertEquals(1, forced.outputAttempts);
		assertEquals(0, forced.entityAttempts);
		assertEquals(0, forced.boxAttempts);
		assertEquals(List.of("core-output-rejected", "remove-old-be", "place-lower",
			"place-upper", "install-snapshot"), forced.events);
	}

	@Test
	void removalGuardUsesLevelIdentityAndImmutableAnchorAndAlwaysClearsInFinally() {
		Object level = new Object();
		Object otherLevel = new Object();
		BlockPos.MutableBlockPos mutableSource = new BlockPos.MutableBlockPos(4, 70, -3);
		BlockPos anchor = mutableSource.immutable();

		assertFalse(LibraryRemoval.isRemovalActive(level, anchor));
		assertThrows(IllegalStateException.class, () ->
			LibraryRemoval.withRemovalGuard(level, mutableSource, () -> {
				mutableSource.move(1, 0, 0);
				assertTrue(LibraryRemoval.isRemovalActive(level, anchor));
				assertFalse(LibraryRemoval.isRemovalActive(otherLevel, anchor));
				LibraryRemoval.withRemovalGuard(level, anchor,
					() -> assertTrue(LibraryRemoval.isRemovalActive(level, anchor)));
				assertTrue(LibraryRemoval.isRemovalActive(level, anchor));
				throw new IllegalStateException("exercise finally");
			}));
		assertFalse(LibraryRemoval.isRemovalActive(level, anchor));
	}

	@Test
	void movingRemovalEntryLeavesSerializedSnapshotAndAllOutputsUntouched() {
		byte[] original = { 9, 7, 9, 3 };
		byte[] snapshot = original.clone();
		int[] effects = new int[3];

		LibraryRemoval.RemovalDisposition disposition =
			LibraryRemoval.runRemovalEntry(true, () -> {
				snapshot[0] = 0;
				effects[0]++;
				effects[1]++;
				effects[2]++;
				return LibraryRemoval.RemovalDisposition.RESTORED;
			});

		assertEquals(LibraryRemoval.RemovalDisposition.CALL_SUPER, disposition);
		assertArrayEquals(original, snapshot);
		assertEquals(0, effects[0], "entity spawn");
		assertEquals(0, effects[1], "item spawn");
		assertEquals(0, effects[2], "counterpart mutation");
	}

	@Test
	void sameBlockHalfTransitionIsARealRemovalButSameLogicalHalfIsNot() {
		LibraryCoreBlock block = new LibraryCoreBlock(BlockBehaviour.Properties.of());
		var lower = block.defaultBlockState()
			.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
		var rotatedLower = lower.setValue(LibraryCoreBlock.FACING, Direction.EAST);
		var upper = lower.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER);

		assertTrue(LibraryRemoval.sameLogicalPart(lower, rotatedLower));
		assertFalse(LibraryRemoval.sameLogicalPart(lower, upper));
		assertFalse(LibraryRemoval.sameLogicalPart(upper, lower));
	}

	@Test
	void residentlessControlledRemovalCommitsWithoutResidentOutput() {
		FakeResidentlessRemoval survival = new FakeResidentlessRemoval(1);
		assertTrue(LibraryRemoval.runControlledRemoval(survival, false));
		assertEquals(1, survival.emittedBlockDrops);
		assertEquals(0, survival.entityAttempts);
		assertEquals(1, survival.commitCalls);
		assertFalse(survival.pending);
		assertEquals(List.of("block-output:1", "clear-and-remove"), survival.events);
	}

	@Test
	void onlyNoSnapshotAndNoRawSnapshotMaySkipResidentRelease() {
		assertFalse(new LibraryRemovalHost.State(ItemStack.EMPTY, false, ItemStack.EMPTY).residentRequired());
		assertTrue(new LibraryRemovalHost.State(new ItemStack(Items.STONE), false, ItemStack.EMPTY).residentRequired());
		assertTrue(new LibraryRemovalHost.State(ItemStack.EMPTY, true, ItemStack.EMPTY).residentRequired(),
			"malformed retained raw data must keep the rollback/pending-safe-release path");
		assertTrue(new LibraryRemovalHost.State(new ItemStack(Items.STONE), true, ItemStack.EMPTY).residentRequired());
	}

	@Test
	void upperHalfHasCenteredNonEmptyOutlineAndCollisionShapes() {
		LibraryCoreBlock block = new LibraryCoreBlock(BlockBehaviour.Properties.of());
		var upper = block.defaultBlockState()
			.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER);

		VoxelShape outline = block.getShape(upper, null, BlockPos.ZERO, CollisionContext.empty());
		VoxelShape collision = block.getCollisionShape(upper, null, BlockPos.ZERO,
			CollisionContext.empty());

		assertFalse(outline.isEmpty(), "UPPER must provide a visible selection outline");
		assertFalse(collision.isEmpty(), "UPPER must physically occupy its cell");
		assertCentered(outline);
		assertCentered(collision);
	}

	@Test
	void entityCoordinatesPassThroughNormallyAndUseSubLevelCompatProjection() {
		Vec3 local = new Vec3(2, 3, 4);
		assertEquals(local, LibraryRemoval.entityWorldPosition(null, local));

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
			LibraryRemoval.entityWorldPosition(subLevel, local));
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

	private static void assertCentered(VoxelShape shape) {
		AABB bounds = shape.bounds();
		assertEquals(0.5d, (bounds.minX + bounds.maxX) / 2.0d, 0.000001d);
		assertEquals(0.5d, (bounds.minZ + bounds.maxZ) / 2.0d, 0.000001d);
	}

	private static final class FakeControlledRemoval
		implements LibraryRemoval.ControlledRemovalOps {
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

	private static final class FakeResidentlessRemoval
		implements LibraryRemoval.ControlledRemovalOps {
		private final int requestedBlockDrops;
		private final List<String> events = new ArrayList<>();
		private int emittedBlockDrops;
		private int entityAttempts;
		private int commitCalls;
		private boolean pending;

		private FakeResidentlessRemoval(int requestedBlockDrops) {
			this.requestedBlockDrops = requestedBlockDrops;
		}

		@Override
		public boolean emitRequiredOutput() {
			emittedBlockDrops = requestedBlockDrops;
			events.add("block-output:" + requestedBlockDrops);
			return true;
		}

		@Override
		public boolean tryReleaseEntity() {
			entityAttempts++;
			return false;
		}

		@Override
		public void rollbackPreparedOutput() {
			events.add("rollback-output");
			emittedBlockDrops = 0;
		}

		@Override
		public void markPendingSafeRelease() {
			pending = true;
			events.add("pending");
		}

		@Override
		public void commitSuccessfulRelease() {
			commitCalls++;
			events.add("clear-and-remove");
		}
	}

	private static final class FakeForcedRemoval
		implements LibraryRemoval.ForcedRemovalOps {
		private static final byte[] ORIGINAL_SNAPSHOT = { 1, 6, 1, 8 };
		private final boolean acceptOutput;
		private final boolean acceptEntity;
		private final boolean acceptBox;
		private final LibraryRemoval.RemovalDisposition restoreDisposition;
		private final List<String> events = new ArrayList<>();
		private byte[] snapshot = ORIGINAL_SNAPSHOT.clone();
		private boolean outputPresent;
		private int outputAttempts;
		private int entityAttempts;
		private int boxAttempts;
		private int commitCalls;

		private FakeForcedRemoval(boolean acceptEntity, boolean acceptBox,
			LibraryRemoval.RemovalDisposition restoreDisposition) {
			this(true, acceptEntity, acceptBox, restoreDisposition);
		}

		private FakeForcedRemoval(boolean acceptOutput, boolean acceptEntity, boolean acceptBox,
			LibraryRemoval.RemovalDisposition restoreDisposition) {
			this.acceptOutput = acceptOutput;
			this.acceptEntity = acceptEntity;
			this.acceptBox = acceptBox;
			this.restoreDisposition = restoreDisposition;
		}

		@Override
		public boolean emitRequiredOutput() {
			outputAttempts++;
			outputPresent = acceptOutput;
			events.add(acceptOutput ? "core-output" : "core-output-rejected");
			return acceptOutput;
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
			if (!outputPresent || entityAttempts > 0 && !events.getLast().endsWith("accepted"))
				throw new AssertionError("snapshot cleared before output/entity confirmation");
			commitCalls++;
			events.add("clear-and-remove");
			snapshot = null;
		}

		@Override
		public void rollbackPreparedOutput() {
			outputPresent = false;
			events.add("rollback-core");
		}

		@Override
		public LibraryRemoval.RemovalDisposition restoreAfterDoubleFailure() {
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
