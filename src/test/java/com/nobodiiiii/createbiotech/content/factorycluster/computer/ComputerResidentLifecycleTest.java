package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

class ComputerResidentLifecycleTest {
	private static final UUID UUID_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final ResourceLocation VILLAGER = ResourceLocation.withDefaultNamespace("villager");

	@Test
	void movingRemovalDoesNotDecodeSpawnRecoverOrClear() {
		FakeOps ops = new FakeOps();

		assertEquals(ComputerResidentLifecycle.RemovalDisposition.CALL_SUPER,
			ComputerResidentLifecycle.runRemovalEntry(true,
				() -> ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops)));

		assertEquals(0, ops.totalEffects());
	}

	@Test
	void successfulSpawnClearsSourceExactlyOnceAndSetsHealthToOne() {
		FakeOps ops = new FakeOps();

		assertEquals(ComputerResidentLifecycle.RemovalDisposition.CALL_SUPER,
			ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops));

		assertEquals(1, ops.residentAdds);
		assertEquals(1, ops.handle.healthSets);
		assertEquals(1.0F, ops.handle.health);
		assertEquals(1, ops.clears);
		assertEquals(0, ops.recoveries);
	}

	@Test
	void exactUuidAndTypeCollisionCountsAsConfirmedReleaseWithoutDuplicateSpawn() {
		FakeOps ops = new FakeOps();
		ops.loaded = ops.handle;

		ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops);

		assertEquals(0, ops.decodes);
		assertEquals(0, ops.residentAdds);
		assertEquals(1, ops.handle.healthSets);
		assertEquals(1, ops.clears);
	}

	@Test
	void sameUuidDifferentTypeFallsBackToUntouchedRecoveryBox() {
		FakeOps ops = new FakeOps();
		ops.loaded = new FakeResident(UUID_A,
			ResourceLocation.withDefaultNamespace("zombie"));

		ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops);

		assertEquals(0, ops.decodes);
		assertEquals(1, ops.recoveries);
		assertArrayEquals(new byte[] {3, 1, 4}, ops.recoveryBytes);
		assertEquals(1, ops.clears);
	}

	@Test
	void rejectedSpawnProducesOneByteIdenticalRecoveryBox() {
		FakeOps ops = new FakeOps();
		ops.acceptResident = false;

		ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops);

		assertEquals(1, ops.residentAdds);
		assertEquals(1, ops.discards);
		assertEquals(1, ops.recoveries);
		assertArrayEquals(new byte[] {3, 1, 4}, ops.recoveryBytes);
		assertEquals(1, ops.clears);
	}

	@Test
	void decodedIdentityMismatchIsDiscardedBeforeRecovery() {
		FakeOps ops = new FakeOps();
		ops.decoded = new FakeResident(UUID.fromString("90000000-0000-0000-0000-000000000009"),
			VILLAGER);

		ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops);

		assertEquals(1, ops.discards);
		assertEquals(1, ops.recoveries);
		assertEquals(1, ops.clears);
	}

	@Test
	void spawnAndRecoveryFailureRestoresByteIdenticalFullServerNbt() {
		FakeOps ops = new FakeOps();
		ops.acceptResident = false;
		ops.acceptRecovery = false;

		assertEquals(ComputerResidentLifecycle.RemovalDisposition.RESTORED,
			ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops));

		assertEquals(input().fullServerNbt(), ops.restored);
		assertEquals(0, ops.clears);
		assertEquals(1, ops.restores);
	}

	@Test
	void productionForcedEntryGuardsSynchronousResidentReentry() {
		FakeOps ops = new FakeOps();
		Object level = new Object();
		BlockPos pos = new BlockPos(4, 5, 6);
		int[] nestedTransactions = {0};
		ops.onResidentAdded = () -> {
			ComputerResidentLifecycle.RemovalDisposition nested =
				ComputerResidentLifecycle.runGuardedRemoval(level, pos, () -> {
					nestedTransactions[0]++;
					return ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops);
				});
			assertEquals(ComputerResidentLifecycle.RemovalDisposition.CALL_SUPER, nested);
		};

		assertEquals(ComputerResidentLifecycle.RemovalDisposition.CALL_SUPER,
			ComputerResidentLifecycle.runGuardedRemoval(level, pos,
				() -> ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops)));

		assertEquals(0, nestedTransactions[0]);
		assertEquals(1, ops.residentAdds);
		assertEquals(1, ops.clears);
	}

	@Test
	void productionControlledEntryGuardsSynchronousLootReentry() {
		FakeOps ops = new FakeOps();
		Object level = new Object();
		BlockPos pos = new BlockPos(7, 8, 9);
		int[] nestedTransactions = {0};
		ops.onLootEmitted = () -> assertFalse(
			ComputerResidentLifecycle.runGuardedControlledBreak(level, pos, () -> {
				nestedTransactions[0]++;
				return ComputerResidentLifecycle.runControlledBreak(input(), ops, ops);
			}));

		assertTrue(ComputerResidentLifecycle.runGuardedControlledBreak(level, pos,
			() -> ComputerResidentLifecycle.runControlledBreak(input(), ops, ops)));

		assertEquals(0, nestedTransactions[0]);
		assertEquals(1, ops.residentAdds);
		assertEquals(1, ops.clears);
	}

	@Test
	void wanderingTraderDespawnDelayIsUnchangedExceptHealth() {
		FakeOps ops = new FakeOps();
		ops.handle.despawnDelay = 117;

		ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops);

		assertEquals(117, ops.handle.despawnDelay);
		assertEquals(1.0F, ops.handle.health);
	}

	@Test
	void controlledBreakRollsBackPreparedComputerLootOnDoubleOutputFailure() {
		FakeOps ops = new FakeOps();
		ops.acceptResident = false;
		ops.acceptRecovery = false;

		assertFalse(ComputerResidentLifecycle.runControlledBreak(input(), ops, ops));

		assertEquals(List.of("loot", "resident", "recovery", "rollback", "restore"), ops.events);
		assertEquals(1, ops.rollbacks);
		assertEquals(0, ops.clears);
	}

	@Test
	void controlledBreakCommitsOnlyAfterLootAndOneResidentOutputAreConfirmed() {
		FakeOps ops = new FakeOps();

		assertTrue(ComputerResidentLifecycle.runControlledBreak(input(), ops, ops));

		assertEquals(List.of("loot", "resident", "clear", "remove"), ops.events);
		assertEquals(1, ops.clears);
		assertEquals(1, ops.finalRemovalAttempts);
		assertEquals(0, ops.rollbacks);
	}

	@Test
	void failedFinalRemovalRollsBackSpawnAndLootThenRestoresFullTag() {
		FakeOps ops = new FakeOps();
		ops.finalRemovalSucceeds = false;

		assertFalse(ComputerResidentLifecycle.runControlledBreak(input(), ops, ops));

		assertEquals(List.of("loot", "resident", "clear", "remove", "rollback",
			"restore"), ops.events);
		assertEquals(1, ops.discards, "accepted resident output must be revoked");
		assertEquals(1, ops.rollbacks);
		assertEquals(1, ops.restores);
		assertEquals(input().fullServerNbt(), ops.restored);
	}

	@Test
	void failedFinalRemovalRollsBackRecoveryAndLootThenRestoresFullTag() {
		FakeOps ops = new FakeOps();
		ops.acceptResident = false;
		ops.finalRemovalSucceeds = false;

		assertFalse(ComputerResidentLifecycle.runControlledBreak(input(), ops, ops));

		assertEquals(1, ops.recoveries);
		assertEquals(1, ops.recoveryRollbacks);
		assertEquals(1, ops.rollbacks);
		assertEquals(1, ops.restores);
	}

	@Test
	void forcedFinalRemovalFailureRevokesResidentAndRetainsAuthoritativeTag() {
		FakeOps ops = new FakeOps();
		ops.finalRemovalSucceeds = false;

		assertEquals(ComputerResidentLifecycle.RemovalDisposition.RESTORED,
			ComputerResidentLifecycle.runForcedRemoval(input(), ops, ops));

		assertEquals(List.of("resident", "clear", "remove", "restore"), ops.events);
		assertEquals(1, ops.discards, "accepted resident output must be revoked");
		assertEquals(input().fullServerNbt(), ops.restored);
		assertEquals(1, ops.restores);
	}

	@Test
	void failedRestoreNeverAllowsForcedRemovalToContinueOrLoseOpaqueState() {
		ComputerResidentLifecycle.RemovalInput opaque = opaqueInput();
		FakeOps ops = new FakeOps();
		ops.restoreSucceeds = false;

		assertEquals(ComputerResidentLifecycle.RemovalDisposition.RESTORED,
			ComputerResidentLifecycle.runForcedRemoval(opaque, ops, ops));

		assertEquals(opaque.fullServerNbt(), ops.restored);
		assertEquals(1, ops.restores);
		assertEquals(0, ops.clears);
		assertEquals(0, ops.inspections);
		assertEquals(0, ops.residentAdds);
		assertEquals(0, ops.recoveries);
	}

	@Test
	void translatedRotatedSubLevelProjectsReleaseRecoveryCollisionFallbackAndLoot() {
		Vec3 local = new Vec3(2, 3, 4);
		assertEquals(local, ComputerResidentLifecycle.entityWorldPosition(null, local));
		SubLevelAccess subLevel = translatedRotatedSubLevel();
		assertEquals(new Vec3(104, 53, -22),
			ComputerResidentLifecycle.entityWorldPosition(subLevel, local));

		FakeOps ops = new FakeOps();
		ops.subLevel = subLevel;
		ops.acceptResident = false;
		ops.rejectedCollisions = 6;
		ComputerResidentLifecycle.runControlledBreak(input(), ops, ops);

		assertFalse(ops.projectedTargets.isEmpty());
		assertEquals(7, ops.collisionTargets.size(), "six neighbours plus fallback");
		for (int i = 0; i < ops.collisionTargets.size(); i++)
			assertEquals(ops.projectedTargets.get(i + 1), ops.collisionTargets.get(i));
		assertTrue(ops.collisionTargets.stream().noneMatch(ops.rawTargets::contains));
		assertEquals(ops.project(new Vec3(0.5, 0.5, 0.5)), ops.lootTarget);
		assertEquals(ops.project(new Vec3(0.5, 0.5, 0.5)), ops.recoveryTarget);
	}

	private static ComputerResidentLifecycle.RemovalInput input() {
		CompoundTag full = new CompoundTag();
		full.putString("ComputerData", "authoritative");
		ItemStack snapshot = new ItemStack(Items.PAPER);
		snapshot.setCount(3);
		return new ComputerResidentLifecycle.RemovalInput(BlockPos.ZERO, snapshot,
			new byte[] {3, 1, 4}, null, full);
	}

	private static ComputerResidentLifecycle.RemovalInput opaqueInput() {
		CompoundTag full = new CompoundTag();
		CompoundTag computerData = new CompoundTag();
		computerData.put("Resident", StringTag.valueOf("opaque-resident-payload"));
		computerData.putByteArray("UnknownOpaqueBytes", new byte[] {8, 5, 3, 1});
		full.put("ComputerData", computerData);
		return new ComputerResidentLifecycle.RemovalInput(BlockPos.ZERO, ItemStack.EMPTY,
			new byte[0], computerData.get("Resident"), full);
	}

	private static SubLevelAccess translatedRotatedSubLevel() {
		Object pose = translatedRotatedPose(100, 50, -20);
		return (SubLevelAccess) Proxy.newProxyInstance(SubLevelAccess.class.getClassLoader(),
			new Class<?>[] {SubLevelAccess.class}, (proxy, method, arguments) -> switch (method.getName()) {
				case "logicalPose", "lastPose" -> pose;
				case "boundingBox" -> new BoundingBox3d(0, 0, 0, 16, 16, 16);
				case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000099");
				case "getName" -> "computer-projection-test";
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	private static Object translatedRotatedPose(double x, double y, double z) {
		try {
			Class<?> vector = Class.forName("org.joml.Vector3d");
			Class<?> quaternion = Class.forName("org.joml.Quaterniond");
			Object position = vector.getConstructor(double.class, double.class, double.class)
				.newInstance(x, y, z);
			Object orientation = quaternion.getConstructor().newInstance();
			quaternion.getMethod("rotateY", double.class).invoke(orientation, Math.PI / 2);
			return Class.forName("dev.ryanhcode.sable.companion.math.Pose3d")
				.getConstructor(vector, quaternion, vector, vector)
				.newInstance(position, orientation, vector.getConstructor().newInstance(),
					vector.getConstructor(double.class, double.class, double.class).newInstance(1, 1, 1));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class FakeResident implements ComputerResidentLifecycle.ResidentHandle {
		private final UUID uuid;
		private final ResourceLocation type;
		private Vec3 position = Vec3.ZERO;
		private float health = 20;
		private int healthSets;
		private int despawnDelay;

		private FakeResident(UUID uuid, ResourceLocation type) {
			this.uuid = uuid;
			this.type = type;
		}

		@Override public UUID uuid() { return uuid; }
		@Override public ResourceLocation type() { return type; }
		@Override public Vec3 position() { return position; }
		@Override public AABB bounds() { return new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3); }
		@Override public void moveTo(Vec3 target) { position = target; }
		@Override public void setHealthToOne() { health = 1; healthSets++; }
	}

	private static final class FakeOps implements ComputerResidentLifecycle.SpawnOps,
		ComputerResidentLifecycle.RestoreOps {
		private final FakeResident handle = new FakeResident(UUID_A, VILLAGER);
		private final List<String> events = new ArrayList<>();
		private final List<Vec3> rawTargets = new ArrayList<>();
		private final List<Vec3> projectedTargets = new ArrayList<>();
		private final List<Vec3> collisionTargets = new ArrayList<>();
		private FakeResident loaded;
		private FakeResident decoded;
		private SubLevelAccess subLevel;
		private boolean acceptResident = true;
		private boolean acceptRecovery = true;
		private boolean finalRemovalSucceeds = true;
		private boolean restoreSucceeds = true;
		private Runnable onResidentAdded = () -> {};
		private Runnable onLootEmitted = () -> {};
		private int rejectedCollisions;
		private int inspections;
		private int projections;
		private int collisionChecks;
		private int decodes;
		private int residentAdds;
		private int recoveries;
		private int discards;
		private int clears;
		private int restores;
		private int rollbacks;
		private int recoveryRollbacks;
		private int finalRemovalAttempts;
		private byte[] recoveryBytes;
		private Vec3 recoveryTarget;
		private Vec3 lootTarget;
		private CompoundTag restored;

		@Override
		public ComputerResidentLifecycle.ResidentIdentity inspect(ItemStack snapshot) {
			inspections++;
			return new ComputerResidentLifecycle.ResidentIdentity(UUID_A, VILLAGER);
		}

		@Override public ComputerResidentLifecycle.ResidentHandle findLoaded(UUID uuid) { return loaded; }
		@Override public ComputerResidentLifecycle.ResidentHandle decode(ItemStack snapshot) {
			decodes++;
			return decoded == null ? handle : decoded;
		}
		@Override public boolean isLoaded(BlockPos pos) { return true; }
		@Override public boolean sameSpace(BlockPos first, BlockPos second) { return true; }

		@Override
		public Vec3 project(Position local) {
			projections++;
			Vec3 raw = new Vec3(local.x(), local.y(), local.z());
			rawTargets.add(raw);
			Vec3 projected = ComputerResidentLifecycle.entityWorldPosition(subLevel, local);
			projectedTargets.add(projected);
			return projected;
		}

		@Override
		public boolean noCollision(ComputerResidentLifecycle.ResidentHandle resident, AABB movedBounds,
			Vec3 target) {
			collisionChecks++;
			collisionTargets.add(target);
			return collisionChecks > rejectedCollisions
				&& target.equals(projectedTargets.getLast());
		}

		@Override
		public boolean addResident(ComputerResidentLifecycle.ResidentHandle resident) {
			residentAdds++;
			events.add("resident");
			onResidentAdded.run();
			return acceptResident;
		}

		@Override public boolean confirmResident(ComputerResidentLifecycle.ResidentIdentity identity) {
			return acceptResident;
		}

		@Override public void discard(ComputerResidentLifecycle.ResidentHandle resident) { discards++; }

		@Override
		public boolean addRecovery(ItemStack snapshot, byte[] serializedSnapshot, Vec3 target) {
			recoveries++;
			events.add("recovery");
			recoveryBytes = serializedSnapshot.clone();
			recoveryTarget = target;
			return acceptRecovery;
		}

		@Override public void rollbackRecovery() { recoveryRollbacks++; }

		@Override
		public boolean emitComputerLoot(Vec3 target) {
			events.add("loot");
			lootTarget = target;
			onLootEmitted.run();
			return true;
		}

		@Override public void rollbackComputerLoot() { events.add("rollback"); rollbacks++; }
		@Override public void clearSource() { events.add("clear"); clears++; }
		@Override public boolean commitFinalRemoval() {
			events.add("remove");
			finalRemovalAttempts++;
			return finalRemovalSucceeds;
		}

		@Override
		public boolean restore(CompoundTag fullServerNbt) {
			events.add("restore");
			restores++;
			restored = fullServerNbt.copy();
			return restoreSucceeds;
		}

		private int totalEffects() {
			return inspections + projections + collisionChecks + decodes + residentAdds + recoveries
				+ discards + clears + restores + rollbacks + recoveryRollbacks
				+ finalRemovalAttempts + events.size();
		}
	}
}
