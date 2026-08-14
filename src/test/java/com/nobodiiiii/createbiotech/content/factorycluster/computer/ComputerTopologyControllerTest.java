package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

class ComputerTopologyControllerTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final ComputerStructureScanner.Limits LIMITS =
		new ComputerStructureScanner.Limits(3, 4, 64, 16);
	private static final UUID LOW = uuid(1);
	private static final UUID MID = uuid(2);
	private static final UUID HIGH = uuid(3);
	private static final ComputerProfile PROFILE = ComputerProfile
		.forKind(NodeKind.LIBRARIAN, 3, 0).orElseThrow();
	@BeforeAll
	static void bootstrapMinecraft() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void firstFullyLoadedValidScanCreatesOneRandomStructureMemberIdAndReplicatesIt() {
		Fixture fixture = fixture(profiled(LOW, pos(2)), profiled(HIGH, pos(0)));

		fixture.refresh(LOW);

		UUID member = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		assertEquals(fixture.generatedMemberId, member);
		assertEquals(member, fixture.be(HIGH).computerStructureMemberId().orElseThrow());
		assertEquals(0, fixture.be(LOW).currentStructureRecord().orElseThrow().revision());
		assertEquals(fixture.be(LOW).currentStructureRecord(), fixture.be(HIGH).currentStructureRecord());
	}

	@Test
	void oneExistingStructureIdClaimsNullNewMembersButTwoExistingIdsFault() {
		Fixture fixture = fixture(profiled(LOW, pos(2)), profiled(HIGH, pos(0)));
		UUID member = uuid(40);
		ComputerStructureRecord record = new ComputerStructureRecord(member, 4, LOW, fixture.snapshot);
		fixture.be(LOW).applyTopologyState(record, null, true, null, Set.of());

		fixture.refresh(LOW);

		assertEquals(member, fixture.be(HIGH).computerStructureMemberId().orElseThrow());
		ComputerStructureRecord foreign = new ComputerStructureRecord(uuid(41), 4, LOW, fixture.snapshot);
		fixture.be(HIGH).applyTopologyState(foreign, null, true, null, Set.of());
		CompoundTag lowBefore = save(fixture.be(LOW));
		CompoundTag highBefore = save(fixture.be(HIGH));
		fixture.refresh(LOW);
		assertEquals(ComputerAvailabilityReason.IDENTITY_CONFLICT,
			fixture.be(LOW).availabilityReason());
		assertEquals(lowBefore, save(fixture.be(LOW)));
		assertEquals(highBefore, save(fixture.be(HIGH)));
	}

	@Test
	void lowestComputerUuidCoordinatesRegardlessOfBlockPosition() {
		Fixture fixture = fixture(profiled(HIGH, pos(0)), profiled(LOW, pos(2)));

		fixture.refresh(HIGH);
		assertTrue(fixture.be(HIGH).computerStructureMemberId().isEmpty());
		fixture.refresh(LOW);

		assertEquals(LOW, fixture.be(LOW).currentStructureRecord().orElseThrow().coordinatorId());
		assertEquals(LOW, fixture.be(HIGH).currentStructureRecord().orElseThrow().coordinatorId());
	}

	@Test
	void legitimateEmptyComputerFormsValidNotReadyWithoutElectingOrStartingEpoch() {
		Fixture fixture = fixture(empty(LOW, pos(0)));
		fixture.scanState = ComputerStructureScanner.State.VALID_NOT_READY;

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.NOT_READY, fixture.be(LOW).availabilityReason());
		assertFalse(fixture.be(LOW).coordinatorOnline());
		assertTrue(fixture.be(LOW).currentStructureRecord().isPresent());
		assertEquals(ComputerBlockEntity.EpochStartResult.NOT_READY,
			ComputerTopologyController.startEpoch(fixture.be(LOW), uuid(50), fixture, LIMITS));
		assertTrue(fixture.be(LOW).epoch().isEmpty());
	}

	@Test
	void missingDuplicateOrCorruptIdentityDoesNotElectOrOverwritePersistedData() {
		Fixture fixture = fixture(profiled(LOW, pos(0)));
		fixture.refresh(LOW);
		CompoundTag before = save(fixture.be(LOW));
		fixture.scanState = ComputerStructureScanner.State.IDENTITY_INVALID;

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.IDENTITY_CONFLICT,
			fixture.be(LOW).availabilityReason());
		assertEquals(before, save(fixture.be(LOW)));

		CompoundTag corrupt = save(fixture.be(LOW));
		corrupt.getCompound("ComputerData").putString("Profile", "corrupt");
		fixture.be(LOW).read(corrupt, REGISTRIES, false);
		CompoundTag corruptBefore = save(fixture.be(LOW));
		fixture.refresh(LOW);
		assertEquals(corruptBefore, save(fixture.be(LOW)));
		assertEquals(ComputerAvailabilityReason.IDENTITY_CONFLICT,
			fixture.be(LOW).availabilityReason());
	}

	@Test
	void knownChunkUnloadRetainsSnapshotCoordinatorAndRevisionWithoutReelection() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerStructureRecord before = fixture.be(LOW).currentStructureRecord().orElseThrow();
		ClusterEpoch epochBefore = fixture.be(LOW).epoch().orElseThrow();
		fixture.unloadedChunks.add(before.snapshot().containingChunks().iterator().next());
		fixture.scanState = ComputerStructureScanner.State.VALID;
		fixture.scanCalls = 0;

		fixture.refresh(HIGH);

		assertEquals(0, fixture.scanCalls);
		assertEquals(before, fixture.be(HIGH).currentStructureRecord().orElseThrow());
		assertEquals(epochBefore, fixture.be(HIGH).epoch().orElseThrow());
		assertEquals(ComputerAvailabilityReason.PARTIAL_UNLOADED,
			fixture.be(HIGH).availabilityReason());
	}

	@Test
	void fullyLoadedCoordinatorDestructionReelectsOnlyWhenNoEpochNoFaultAndQuiescent() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.refresh(LOW);
		UUID member = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		long revision = fixture.be(LOW).currentStructureRecord().orElseThrow().revision();
		fixture.remove(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));

		fixture.refresh(HIGH);

		ComputerStructureRecord record = fixture.be(HIGH).currentStructureRecord().orElseThrow();
		assertEquals(member, record.computerStructureMemberId());
		assertEquals(HIGH, record.coordinatorId());
		assertEquals(revision + 1, record.revision());
	}

	@Test
	void splitShellWithLiveOldCoordinatorRefusesInactiveReelectionByteIdentically() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.refresh(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);

		fixture.refresh(HIGH);

		assertEquals(ComputerAvailabilityReason.IDENTITY_CONFLICT,
			fixture.be(HIGH).availabilityReason());
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void lowerUuidActiveHotAddIsPendingAndDoesNotChangeRecordCoordinatorFrozenOrderOrRangeBonus() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ClusterEpoch before = fixture.be(MID).epoch().orElseThrow();
		ComputerBlockEntity added = computer(LOW, pos(1), PROFILE);
		fixture.put(added);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)),
			profiledNode(LOW, pos(1)), profiledNode(HIGH, pos(2))));

		fixture.refresh(LOW);

		ClusterEpoch after = fixture.be(LOW).epoch().orElseThrow();
		assertEquals(before.epochId(), after.epochId());
		assertEquals(before.coordinatorId(), after.coordinatorId());
		assertEquals(before.nodes(), after.nodes());
		assertEquals(before.totalPatternRangeBonus(), after.totalPatternRangeBonus());
		assertEquals(before.coordinatorId(),
			fixture.be(LOW).currentStructureRecord().orElseThrow().coordinatorId());
		assertEquals(List.of(LOW), fixture.be(LOW).pendingNodes().stream()
			.map(ComputerNodeView::computerId).toList());
	}

	@Test
	void emptyPendingHotAddCanInstallOnceWithoutChangingEpochOrFaults() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ClusterEpoch before = fixture.be(MID).epoch().orElseThrow();
		ComputerBlockEntity pending = computer(LOW, pos(1), null);
		fixture.put(pending);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)), emptyNode(LOW, pos(1)),
			profiledNode(HIGH, pos(2))));
		fixture.scanState = ComputerStructureScanner.State.VALID_NOT_READY;

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.NONE, pending.availabilityReason());
		assertFalse(pending.pendingNodes().getFirst().online());
		pending.installResidentSnapshot(new ItemStack(Items.PAPER), PROFILE);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)), profiledNode(LOW, pos(1)),
			profiledNode(HIGH, pos(2))));
		fixture.scanState = ComputerStructureScanner.State.VALID;
		fixture.refresh(LOW);
		assertTrue(pending.pendingNodes().getFirst().online());
		assertEquals(before.epochId(), pending.epoch().orElseThrow().epochId());
		assertEquals(Set.of(), pending.latchedEpochFaults());
	}

	@Test
	void activeEpochMissingNonCoordinatorKeepsCoordinatorAndMarksOnlyThatFrozenNodeOffline() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ClusterEpoch before = fixture.be(LOW).epoch().orElseThrow();
		fixture.remove(HIGH);
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0))));

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.FROZEN_MEMBER_MISSING,
			fixture.be(LOW).availabilityReason());
		assertTrue(fixture.be(LOW).structureOnline());
		assertTrue(fixture.be(LOW).coordinatorOnline());
		assertEquals(before.coordinatorId(),
			fixture.be(LOW).currentStructureRecord().orElseThrow().coordinatorId());
		Map<UUID, Boolean> online = nodeOnline(fixture.be(LOW).frozenNodes());
		assertEquals(true, online.get(LOW));
		assertEquals(false, online.get(HIGH));
	}

	@Test
	void activeEpochMissingCoordinatorGoesOfflineAndNeverReelects() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerStructureRecord record = fixture.be(HIGH).currentStructureRecord().orElseThrow();
		ClusterEpoch epoch = fixture.be(HIGH).epoch().orElseThrow();
		fixture.remove(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));

		fixture.refresh(HIGH);

		assertEquals(ComputerAvailabilityReason.COORDINATOR_MISSING,
			fixture.be(HIGH).availabilityReason());
		assertFalse(fixture.be(HIGH).structureOnline());
		assertEquals(record, fixture.be(HIGH).currentStructureRecord().orElseThrow());
		assertEquals(epoch, fixture.be(HIGH).epoch().orElseThrow());
	}

	@Test
	void activeEpochRigidMoveUpdatesEpochBoundsAndCoordinatorAddress() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ClusterEpoch before = fixture.be(LOW).epoch().orElseThrow();
		long revision = fixture.be(LOW).currentStructureRecord().orElseThrow().revision();
		fixture.move(LOW, pos(4));
		fixture.move(HIGH, pos(6));
		fixture.snapshot = snapshotAt(4, List.of(profiledNode(LOW, pos(4)),
			profiledNode(HIGH, pos(6))));

		fixture.refresh(LOW);

		ClusterEpoch moved = fixture.be(LOW).epoch().orElseThrow();
		assertEquals(before.epochId(), moved.epochId());
		assertNotEquals(before.bounds(), moved.bounds());
		assertEquals(address(pos(4)), moved.coordinatorAddress());
		assertEquals(revision + 1,
			fixture.be(LOW).currentStructureRecord().orElseThrow().revision());
	}

	@Test
	void partialOrOpenShellRetainsLastActiveSnapshotWithDifferentReasons() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerStructureRecord before = fixture.be(LOW).currentStructureRecord().orElseThrow();
		fixture.scanState = ComputerStructureScanner.State.PARTIAL_UNLOADED;
		fixture.refresh(LOW);
		assertEquals(ComputerAvailabilityReason.PARTIAL_UNLOADED,
			fixture.be(LOW).availabilityReason());
		assertEquals(before, fixture.be(LOW).currentStructureRecord().orElseThrow());
		fixture.scanState = ComputerStructureScanner.State.OPEN_SHELL;
		fixture.refresh(LOW);
		assertEquals(ComputerAvailabilityReason.STRUCTURE_INVALID,
			fixture.be(LOW).availabilityReason());
		assertEquals(before, fixture.be(LOW).currentStructureRecord().orElseThrow());
	}

	@Test
	void widthDepthUnionSurvivesStaleReplicaLoadCancellationAndNbtRoundTrip() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerStructureRecord record = fixture.be(LOW).currentStructureRecord().orElseThrow();
		ClusterBinding binding = fixture.be(LOW).bindingState();
		ClusterEpoch epoch = fixture.be(LOW).epoch().orElseThrow();
		fixture.be(LOW).applyTopologyState(record, binding, true, epoch, Set.of(EpochFault.WIDTH));
		fixture.be(HIGH).applyTopologyState(record, binding, true, epoch, Set.of(EpochFault.DEPTH));

		fixture.refresh(LOW);

		Set<EpochFault> expected = Set.of(EpochFault.WIDTH, EpochFault.DEPTH);
		assertEquals(expected, fixture.be(LOW).latchedEpochFaults());
		assertEquals(expected, fixture.be(HIGH).latchedEpochFaults());
		ComputerBlockEntity decoded = computer(HIGH, pos(2), PROFILE);
		decoded.read(save(fixture.be(HIGH)), REGISTRIES, false);
		assertEquals(expected, decoded.latchedEpochFaults());
	}

	@Test
	void equalRevisionIndependentlyDecodedSnapshotReplicasAreAccepted() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		UUID member = uuid(70);
		ComputerStructureRecord first = new ComputerStructureRecord(member, 6, LOW, fixture.snapshot);
		ComputerStructureRecord decoded = ComputerStructureRecord.load(first.save()).orElseThrow();
		assertNotEquals(System.identityHashCode(first.snapshot()),
			System.identityHashCode(decoded.snapshot()));
		fixture.be(LOW).applyTopologyState(first, null, true, null, Set.of());
		fixture.be(HIGH).applyTopologyState(decoded, null, true, null, Set.of());

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.NONE, fixture.be(LOW).availabilityReason());
		assertEquals(first, fixture.be(HIGH).currentStructureRecord().orElseThrow());
	}

	@Test
	void naturalLastRootCompletionClosesIdleFaultFreeEpochAtomically() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		UUID member = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		ClusterBinding binding = fixture.be(LOW).bindingState();
		Map<UUID, byte[]> residents = residentBytes(fixture);
		ProbeQuiescence quiescence = ProbeQuiescence.ready();
		UUID epochId = fixture.be(LOW).epoch().orElseThrow().epochId();

		assertEquals(ComputerBlockEntity.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(HIGH), epochId,
				quiescence, fixture, LIMITS));

		for (ComputerBlockEntity computer : fixture.computers.values()) {
			assertTrue(computer.epoch().isEmpty());
			assertEquals(Set.of(), computer.latchedEpochFaults());
			assertEquals(member, computer.computerStructureMemberId().orElseThrow());
			assertEquals(binding, computer.bindingState());
			assertArrayEquals(residents.get(computer.computerId().orElseThrow()),
				computer.serializedResident(REGISTRIES));
		}
		assertEquals(List.of("roots", "idle:" + LOW, "rootFree:" + LOW,
			"mailbox:" + LOW, "idle:" + HIGH, "rootFree:" + HIGH,
			"mailbox:" + HIGH), quiescence.events);
	}

	@Test
	void sameEpochIdDivergentReplicaEpochRefusesNormalCloseByteIdentically() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		installEpochMissingHigh(fixture, LOW);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);

		assertEquals(ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void detachedCallerRefusesNormalCloseBeforeQuiescenceAndWithoutMutation() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerBlockEntity detached = fixture.replaceLoadedObject(LOW);
		CompoundTag detachedBefore = save(detached);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeQuiescence quiescence = ProbeQuiescence.ready();

		assertEquals(ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT,
			ComputerTopologyController.closeIdleEpoch(detached,
				detached.epoch().orElseThrow().epochId(), quiescence, fixture, LIMITS));
		assertEquals(List.of(), quiescence.events);
		assertEquals(detachedBefore, save(detached));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void staleEpochCloseDoesNotProbeScanOrMutate() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		Map<UUID, CompoundTag> before = bytes(fixture);
		ProbeQuiescence quiescence = ProbeQuiescence.ready();
		fixture.resetProbes();

		assertEquals(ComputerBlockEntity.EpochCloseResult.EPOCH_MISMATCH,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), uuid(999),
				quiescence, fixture, LIMITS));
		assertEquals(0, fixture.worldProbes());
		assertEquals(List.of(), quiescence.events);
		assertEquals(before, bytes(fixture));
	}

	@Test
	void closeRevalidatesStructuralSnapshotBeforeAtomicCommit() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		Map<UUID, CompoundTag> before = bytes(fixture);
		Set<BlockPos> changedCasing = new HashSet<>(fixture.snapshot.casingPositions());
		changedCasing.add(new BlockPos(1, 1, 1));
		fixture.revalidationSnapshot = new ComputerStructureSnapshot(fixture.snapshot.bounds(),
			fixture.snapshot.nodes(), changedCasing, fixture.snapshot.containingChunks());
		fixture.scanCalls = 0;

		assertEquals(ComputerBlockEntity.EpochCloseResult.IDENTITY_CONFLICT,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void widthOrDepthRejectsNormalCloseAsRequiresReform() {
		for (EpochFault fault : EpochFault.values()) {
			Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
			fixture.latch(fault);
			ProbeQuiescence quiescence = ProbeQuiescence.ready();
			fixture.resetProbes();
			assertEquals(ComputerBlockEntity.EpochCloseResult.REQUIRES_REFORM,
				ComputerTopologyController.closeIdleEpoch(fixture.be(LOW),
					fixture.epochId(), quiescence, fixture, LIMITS));
			assertEquals(0, fixture.worldProbes());
			assertEquals(List.of(), quiescence.events);
		}
	}

	@Test
	void unloadedOrSpaceUncertainMemberRefusesNormalCloseByteIdentically() {
		Fixture unloaded = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		Map<UUID, CompoundTag> beforeUnload = bytes(unloaded);
		unloaded.unloadedPositions.add(pos(1));
		assertEquals(ComputerBlockEntity.EpochCloseResult.PARTIAL_UNLOADED,
			ComputerTopologyController.closeIdleEpoch(unloaded.be(LOW), unloaded.epochId(),
				ProbeQuiescence.ready(), unloaded, LIMITS));
		assertEquals(beforeUnload, bytes(unloaded));

		Fixture wrongSpace = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		Map<UUID, CompoundTag> beforeSpace = bytes(wrongSpace);
		wrongSpace.wrongSpacePositions.add(pos(1));
		assertEquals(ComputerBlockEntity.EpochCloseResult.SPACE_UNCERTAIN,
			ComputerTopologyController.closeIdleEpoch(wrongSpace.be(LOW), wrongSpace.epochId(),
				ProbeQuiescence.ready(), wrongSpace, LIMITS));
		assertEquals(beforeSpace, bytes(wrongSpace));
	}

	@Test
	void missingFrozenMemberCannotBeAbandonedByNormalClose() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.remove(HIGH);
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0))));
		Map<UUID, CompoundTag> before = bytes(fixture);

		assertEquals(ComputerBlockEntity.EpochCloseResult.FROZEN_MEMBER_MISSING,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void nonIdleRootOwningOrNonemptyMailboxMemberRefusesNormalClose() {
		for (String failure : List.of("roots", "idle", "rootFree", "mailbox")) {
			Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
			ProbeQuiescence probe = ProbeQuiescence.failing(failure, HIGH);
			Map<UUID, CompoundTag> before = bytes(fixture);
			ComputerBlockEntity.EpochCloseResult expected = failure.equals("roots")
				? ComputerBlockEntity.EpochCloseResult.ROOTS_REMAIN
				: ComputerBlockEntity.EpochCloseResult.RUNTIME_NOT_QUIESCENT;
			assertEquals(expected, ComputerTopologyController.closeIdleEpoch(fixture.be(LOW),
				fixture.epochId(), probe, fixture, LIMITS));
			assertEquals(before, bytes(fixture));
		}
	}

	@Test
	void emptyPendingExtraCanCloseButLeavesInactiveStructureNotReady() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ComputerBlockEntity pending = computer(LOW, pos(1), null);
		fixture.put(pending);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)), emptyNode(LOW, pos(1)),
			profiledNode(HIGH, pos(2))));
		fixture.scanState = ComputerStructureScanner.State.VALID_NOT_READY;
		fixture.refresh(LOW);

		assertEquals(ComputerBlockEntity.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(pending, fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertTrue(pending.epoch().isEmpty());
		assertEquals(ComputerAvailabilityReason.NOT_READY, pending.availabilityReason());
		assertFalse(pending.coordinatorOnline());
	}

	@Test
	void coordinatorDestroyedAfterCloseReelectsAndPreservesStructureIdentity() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		UUID member = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		assertEquals(ComputerBlockEntity.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		fixture.remove(LOW);
		fixture.snapshot = snapshot(List.of(profiledNode(HIGH, pos(2))));

		fixture.refresh(HIGH);

		assertEquals(member, fixture.be(HIGH).computerStructureMemberId().orElseThrow());
		assertEquals(HIGH, fixture.be(HIGH).currentStructureRecord().orElseThrow().coordinatorId());
	}

	@Test
	void onlyVerifiedStopAllReformClearsLatchedFaultsOnEveryReplica() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.latch(EpochFault.WIDTH);
		UUID oldEpoch = fixture.epochId();
		ProbeReform control = ProbeReform.ready();

		assertEquals(ComputerBlockEntity.ReformResult.REFORMED,
			ComputerTopologyController.stopAllAndReform(fixture.be(HIGH), oldEpoch,
				control, fixture, LIMITS));

		assertEquals("stop", control.events.getFirst());
		for (ComputerBlockEntity computer : fixture.computers.values()) {
			assertEquals(Set.of(), computer.latchedEpochFaults());
			assertNotEquals(oldEpoch, computer.epoch().orElseThrow().epochId());
		}
	}

	@Test
	void splitShellWithLiveFrozenComputerRefusesPermanentLossReformByteIdentically() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.latch(EpochFault.WIDTH);
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0))));
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeReform control = ProbeReform.ready();

		assertEquals(ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), fixture.epochId(),
				control, fixture, LIMITS));
		assertEquals("stop", control.events.getFirst());
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void sameEpochIdDivergentReplicaEpochRefusesReformByteIdentically() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		installEpochMissingHigh(fixture, LOW);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);

		assertEquals(ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), fixture.epochId(),
				ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void higherRevisionNonMinimumCallerCannotAuthorReform() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerBlockEntity high = fixture.be(HIGH);
		ComputerStructureRecord record = high.currentStructureRecord().orElseThrow();
		ComputerStructureRecord ahead = new ComputerStructureRecord(
			record.computerStructureMemberId(), record.revision() + 1,
			record.coordinatorId(), record.snapshot());
		high.applyTopologyState(ahead, high.bindingState(), true,
			high.epoch().orElseThrow(), high.latchedEpochFaults());
		Map<UUID, CompoundTag> before = bytes(fixture);

		assertEquals(ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(high, fixture.epochId(),
				ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void equalRevisionDivergentNonMinimumCallerCannotAuthorReform() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerBlockEntity high = fixture.be(HIGH);
		ComputerStructureRecord record = high.currentStructureRecord().orElseThrow();
		Set<BlockPos> divergentCasing = new HashSet<>(record.snapshot().casingPositions());
		divergentCasing.add(new BlockPos(1, 1, 1));
		ComputerStructureSnapshot divergentSnapshot = new ComputerStructureSnapshot(
			record.snapshot().bounds(), record.snapshot().nodes(), divergentCasing,
			record.snapshot().containingChunks());
		ComputerStructureRecord divergent = new ComputerStructureRecord(
			record.computerStructureMemberId(), record.revision(),
			record.coordinatorId(), divergentSnapshot);
		high.applyTopologyState(divergent, high.bindingState(), true,
			high.epoch().orElseThrow(), high.latchedEpochFaults());
		Map<UUID, CompoundTag> before = bytes(fixture);

		assertEquals(ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(high, fixture.epochId(),
				ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void detachedCallerRefusesReformBeforeStopAndWithoutMutation() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ComputerBlockEntity detached = fixture.replaceLoadedObject(LOW);
		CompoundTag detachedBefore = save(detached);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeReform control = ProbeReform.ready();

		assertEquals(ComputerBlockEntity.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(detached,
				detached.epoch().orElseThrow().epochId(), control, fixture, LIMITS));
		assertEquals(List.of(), control.events);
		assertEquals(detachedBefore, save(detached));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void staleEpochReformRequestDoesNotCallStopOrMutateTheNewEpoch() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		Map<UUID, CompoundTag> before = bytes(fixture);
		ProbeReform control = ProbeReform.ready();
		fixture.resetProbes();

		assertEquals(ComputerBlockEntity.ReformResult.EPOCH_MISMATCH,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), uuid(999),
				control, fixture, LIMITS));
		assertEquals(List.of(), control.events);
		assertEquals(0, fixture.worldProbes());
		assertEquals(before, bytes(fixture));
	}

	@Test
	void confirmedPermanentFrozenMemberLossReformsWithNewEpochAndSameStructureIdentity() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		UUID member = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		UUID oldEpoch = fixture.epochId();
		fixture.remove(HIGH);
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0))));

		assertEquals(ComputerBlockEntity.ReformResult.REFORMED,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), oldEpoch,
				ProbeReform.ready(), fixture, LIMITS));

		ClusterEpoch replacement = fixture.be(LOW).epoch().orElseThrow();
		assertNotEquals(oldEpoch, replacement.epochId());
		assertEquals(member, replacement.computerStructureMemberId());
		assertEquals(List.of(LOW), replacement.nodes().stream().map(EpochNode::computerId).toList());
	}

	@Test
	void unloadedOrSpaceUncertainFrozenMemberCannotReformOrClearFaults() {
		Fixture unloaded = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		unloaded.latch(EpochFault.DEPTH);
		Map<UUID, CompoundTag> unloadedBefore = bytes(unloaded);
		unloaded.unloadedPositions.add(pos(2));
		assertEquals(ComputerBlockEntity.ReformResult.PARTIAL_UNLOADED,
			ComputerTopologyController.stopAllAndReform(unloaded.be(LOW), unloaded.epochId(),
				ProbeReform.ready(), unloaded, LIMITS));
		assertEquals(unloadedBefore, bytes(unloaded));

		Fixture wrongSpace = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		wrongSpace.latch(EpochFault.WIDTH);
		Map<UUID, CompoundTag> spaceBefore = bytes(wrongSpace);
		wrongSpace.wrongSpacePositions.add(pos(2));
		assertEquals(ComputerBlockEntity.ReformResult.SPACE_UNCERTAIN,
			ComputerTopologyController.stopAllAndReform(wrongSpace.be(LOW), wrongSpace.epochId(),
				ProbeReform.ready(), wrongSpace, LIMITS));
		assertEquals(spaceBefore, bytes(wrongSpace));
	}

	@Test
	void failedReformLeavesEveryReplicaByteIdentical() {
		for (String failure : List.of("stop", "roots", "idle", "rootFree", "mailbox")) {
			Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
			fixture.latch(EpochFault.WIDTH);
			Map<UUID, CompoundTag> before = bytes(fixture);
			ProbeReform control = ProbeReform.failing(failure, HIGH);
			ComputerBlockEntity.ReformResult expected = switch (failure) {
				case "stop" -> ComputerBlockEntity.ReformResult.STOP_FAILED;
				case "roots" -> ComputerBlockEntity.ReformResult.ROOTS_REMAIN;
				default -> ComputerBlockEntity.ReformResult.RUNTIME_NOT_QUIESCENT;
			};
			assertEquals(expected, ComputerTopologyController.stopAllAndReform(fixture.be(LOW),
				fixture.epochId(), control, fixture, LIMITS));
			assertEquals(before, bytes(fixture));
		}
	}

	@Test
	void accessorsReturnImmutableCurrentFrozenAndPendingViewsWithPendingFlag() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ComputerBlockEntity pending = computer(LOW, pos(1), PROFILE);
		fixture.put(pending);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)), profiledNode(LOW, pos(1)),
			profiledNode(HIGH, pos(2))));
		fixture.refresh(LOW);

		ComputerClusterView view = pending.clusterView();
		assertEquals(List.of(LOW, MID, HIGH), view.current().stream()
			.map(ComputerNodeView::computerId).toList());
		assertEquals(List.of(MID, HIGH), view.frozen().stream()
			.map(ComputerNodeView::computerId).toList());
		assertEquals(List.of(LOW), view.pending().stream()
			.map(ComputerNodeView::computerId).toList());
		assertTrue(view.pending().getFirst().pending());
		assertThrows(UnsupportedOperationException.class,
			() -> view.current().add(view.current().getFirst()));
		assertThrows(UnsupportedOperationException.class,
			() -> view.faults().add(EpochFault.WIDTH));
	}

	@Test
	void classifierHonorsInternalTagOnlyAfterCasingComputerAndAir() {
		assertEquals(ComputerStructureScanner.CellKind.ALLOWED_INTERNAL,
			ComputerWorldView.classify(Blocks.STONE.defaultBlockState(), state -> true));
		assertEquals(ComputerStructureScanner.CellKind.AIR,
			ComputerWorldView.classify(Blocks.AIR.defaultBlockState(), state -> true));
		assertEquals(ComputerStructureScanner.CellKind.CASING,
			ComputerWorldView.classify(new ComputerCasingBlock(BlockBehaviourProperties.copy())
				.defaultBlockState(), state -> true));
		assertEquals(ComputerStructureScanner.CellKind.COMPUTER,
			ComputerWorldView.classify(new ComputerBlock(BlockBehaviourProperties.copy())
				.defaultBlockState(), state -> true));
	}

	private static Map<UUID, Boolean> nodeOnline(List<ComputerNodeView> nodes) {
		Map<UUID, Boolean> result = new HashMap<>();
		for (ComputerNodeView node : nodes) result.put(node.computerId(), node.online());
		return result;
	}

	private static Fixture fixture(NodeSpec... specs) {
		return new Fixture(specs);
	}

	private static Fixture activeFixture(NodeSpec... specs) {
		Fixture fixture = fixture(specs);
		UUID owner = fixture.snapshot.nodes().getFirst().computerId();
		fixture.refresh(owner);
		UUID cluster = uuid(500);
		ClusterBinding binding = new ClusterBinding(cluster, 0, null, List.of());
		for (ComputerBlockEntity computer : fixture.computers.values()) {
			ComputerStructureRecord record = computer.currentStructureRecord().orElseThrow();
			computer.applyTopologyState(record, binding, true, null, Set.of());
		}
		assertEquals(ComputerBlockEntity.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(owner), cluster, fixture, LIMITS));
		return fixture;
	}

	private static NodeSpec profiled(UUID id, BlockPos pos) { return new NodeSpec(id, pos, PROFILE); }
	private static NodeSpec empty(UUID id, BlockPos pos) { return new NodeSpec(id, pos, null); }
	private static ComputerStructureNode profiledNode(UUID id, BlockPos pos) {
		return new ComputerStructureNode(id, address(pos), PROFILE);
	}
	private static ComputerStructureNode emptyNode(UUID id, BlockPos pos) {
		return new ComputerStructureNode(id, address(pos), null);
	}

	private static ComputerBlockEntity computer(UUID id, BlockPos pos,
		ComputerProfile profile) {
		ComputerBlockEntity computer = new ComputerBlockEntity(BlockEntityType.FURNACE, pos,
			Blocks.FURNACE.defaultBlockState());
		CompoundTag saved = save(computer);
		saved.getCompound("ComputerData").putUUID("ComputerId", id);
		computer.read(saved, REGISTRIES, false);
		if (profile != null) computer.installResidentSnapshot(new ItemStack(Items.PAPER), profile);
		return computer;
	}

	private static ComputerStructureSnapshot snapshot(List<ComputerStructureNode> nodes) {
		return snapshotAt(0, nodes);
	}

	private static ComputerStructureSnapshot snapshotAt(int x, List<ComputerStructureNode> nodes) {
		BoundingBox bounds = new BoundingBox(x, 0, 0, x + 2, 2, 2);
		Set<BlockPos> casing = new HashSet<>();
		for (int px = bounds.minX(); px <= bounds.maxX(); px++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (px == bounds.minX() || px == bounds.maxX() || y == 0 || y == 2
						|| z == 0 || z == 2) casing.add(new BlockPos(px, y, z));
		Set<ChunkPos> chunks = Set.of(new ChunkPos(bounds.minX() >> 4, 0));
		List<ComputerStructureNode> sorted = nodes.stream()
			.sorted(java.util.Comparator.comparing(ComputerStructureNode::computerId)).toList();
		return new ComputerStructureSnapshot(bounds, sorted, casing, chunks);
	}

	private static BlockPos pos(int x) { return new BlockPos(x, 0, 0); }
	private static SpaceAddress address(BlockPos pos) {
		return new SpaceAddress(Level.OVERWORLD, null, pos);
	}
	private static UUID uuid(long value) { return new UUID(0, value); }

	private static CompoundTag save(ComputerBlockEntity computer) {
		CompoundTag tag = new CompoundTag();
		computer.write(tag, REGISTRIES, false);
		return tag;
	}

	private static Map<UUID, CompoundTag> bytes(Fixture fixture) {
		Map<UUID, CompoundTag> result = new LinkedHashMap<>();
		for (ComputerBlockEntity computer : fixture.computers.values())
			result.put(computer.computerId().orElseThrow(), save(computer));
		return result;
	}

	private static Map<UUID, byte[]> residentBytes(Fixture fixture) {
		Map<UUID, byte[]> result = new HashMap<>();
		for (ComputerBlockEntity computer : fixture.computers.values())
			result.put(computer.computerId().orElseThrow(), computer.serializedResident(REGISTRIES));
		return result;
	}

	private static void assertResidentBytesEqual(Map<UUID, byte[]> expected,
		Map<UUID, byte[]> actual) {
		assertEquals(expected.keySet(), actual.keySet());
		for (UUID id : expected.keySet()) assertArrayEquals(expected.get(id), actual.get(id));
	}

	private static void installEpochMissingHigh(Fixture fixture, UUID target) {
		ComputerBlockEntity computer = fixture.be(target);
		ClusterEpoch full = computer.epoch().orElseThrow();
		EpochNode low = full.nodes().stream().filter(node -> node.computerId().equals(LOW))
			.findFirst().orElseThrow();
		ClusterEpoch divergent = new ClusterEpoch(full.epochId(), full.clusterId(),
			full.computerStructureMemberId(), LOW, low.address(), full.bounds(), List.of(low));
		computer.applyTopologyState(computer.currentStructureRecord().orElseThrow(),
			computer.bindingState(), true, divergent, computer.latchedEpochFaults());
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record NodeSpec(UUID id, BlockPos pos, ComputerProfile profile) {}

	private static final class Fixture implements ComputerTopologyController.WorldAccess {
		private final Map<UUID, ComputerBlockEntity> computers = new LinkedHashMap<>();
		private final Map<BlockPos, ComputerBlockEntity> byPosition = new HashMap<>();
		private final Set<BlockPos> unloadedPositions = new HashSet<>();
		private final Set<BlockPos> wrongSpacePositions = new HashSet<>();
		private final Set<ChunkPos> unloadedChunks = new HashSet<>();
		private final UUID generatedMemberId = uuid(600);
		private ComputerStructureSnapshot snapshot;
		private ComputerStructureSnapshot revalidationSnapshot;
		private ComputerStructureScanner.State scanState;
		private int scanCalls;
		private int loadedCalls;
		private int sameSpaceCalls;
		private int computerCalls;

		private Fixture(NodeSpec... specs) {
			List<ComputerStructureNode> nodes = new ArrayList<>();
			for (NodeSpec spec : specs) {
				ComputerBlockEntity computer = computer(spec.id(), spec.pos(), spec.profile());
				put(computer);
				nodes.add(new ComputerStructureNode(spec.id(), address(spec.pos()), spec.profile()));
			}
			snapshot = snapshot(nodes);
			scanState = nodes.stream().anyMatch(node -> node.profile() == null)
				? ComputerStructureScanner.State.VALID_NOT_READY
				: ComputerStructureScanner.State.VALID;
		}

		private void put(ComputerBlockEntity computer) {
			UUID id = computer.computerId().orElseThrow();
			computers.put(id, computer);
			byPosition.put(computer.getBlockPos(), computer);
		}

		private ComputerBlockEntity be(UUID id) { return Objects.requireNonNull(computers.get(id)); }

		private void remove(UUID id) {
			ComputerBlockEntity removed = computers.remove(id);
			if (removed != null) byPosition.remove(removed.getBlockPos());
		}

		private void move(UUID id, BlockPos target) {
			ComputerBlockEntity old = be(id);
			ComputerProfile profile = old.installedProfile().orElse(null);
			ComputerBlockEntity moved = computer(id, target, profile);
			ComputerStructureRecord record = old.currentStructureRecord().orElse(null);
			moved.applyTopologyState(record, old.bindingState(), old.bindingStateValid(),
				old.epoch().orElse(null), old.latchedEpochFaults());
			remove(id);
			put(moved);
		}

		private ComputerBlockEntity replaceLoadedObject(UUID id) {
			ComputerBlockEntity detached = be(id);
			ComputerBlockEntity replacement = new ComputerBlockEntity(BlockEntityType.FURNACE,
				detached.getBlockPos(), Blocks.FURNACE.defaultBlockState());
			replacement.read(save(detached), REGISTRIES, false);
			put(replacement);
			return detached;
		}

		private void refresh(UUID caller) {
			ComputerTopologyController.refresh(be(caller), this, LIMITS);
		}

		private UUID epochId() { return computers.values().iterator().next().epoch().orElseThrow().epochId(); }

		private void latch(EpochFault fault) {
			ComputerBlockEntity caller = computers.values().iterator().next();
			assertEquals(ComputerBlockEntity.FaultLatchResult.LATCHED,
				ComputerTopologyController.latchEpochFault(caller, epochId(), fault, this));
		}

		private void resetProbes() {
			scanCalls = 0;
			loadedCalls = 0;
			sameSpaceCalls = 0;
			computerCalls = 0;
		}

		private int worldProbes() { return scanCalls + loadedCalls + sameSpaceCalls + computerCalls; }

		@Override
		public ComputerStructureScanner.ScanResult scan(BlockPos seed,
			ComputerStructureScanner.Limits limits) {
			scanCalls++;
			ComputerStructureSnapshot current = scanCalls == 2 && revalidationSnapshot != null
				? revalidationSnapshot : snapshot;
			return new ComputerStructureScanner.ScanResult(scanState,
				(scanState == ComputerStructureScanner.State.VALID
					|| scanState == ComputerStructureScanner.State.VALID_NOT_READY) ? current : null,
				scanState == ComputerStructureScanner.State.IDENTITY_INVALID ? null
					: computers.values().stream().map(ComputerBlockEntity::computerStructureMemberId)
						.flatMap(java.util.Optional::stream).findFirst().orElse(null));
		}

		@Override public boolean isLoaded(BlockPos pos) {
			loadedCalls++;
			return !unloadedPositions.contains(pos);
		}

		@Override public boolean isChunkLoaded(ChunkPos chunk) {
			loadedCalls++;
			return !unloadedChunks.contains(chunk);
		}

		@Override public boolean sameSpace(BlockPos origin, BlockPos pos) {
			sameSpaceCalls++;
			return !wrongSpacePositions.contains(pos);
		}

		@Override public ComputerBlockEntity loadedComputer(BlockPos pos) {
			computerCalls++;
			if (unloadedPositions.contains(pos) || wrongSpacePositions.contains(pos))
				throw new AssertionError("BE read after failed loaded/same-space gate");
			return byPosition.get(pos);
		}

		@Override public SpaceAddress address(BlockPos pos) {
			return ComputerTopologyControllerTest.address(pos);
		}
		@Override public UUID newStructureMemberId() { return generatedMemberId; }
	}

	private static class ProbeQuiescence implements EpochQuiescence {
		final List<String> events = new ArrayList<>();
		boolean roots = true;
		String failure;
		UUID failedNode;

		static ProbeQuiescence ready() { return new ProbeQuiescence(); }
		static ProbeQuiescence failing(String failure, UUID node) {
			ProbeQuiescence probe = new ProbeQuiescence();
			probe.failure = failure;
			probe.failedNode = node;
			if (failure.equals("roots")) probe.roots = false;
			return probe;
		}

		@Override public boolean allRootsStopped() { events.add("roots"); return roots; }
		@Override public boolean nodeIdle(UUID computerId) {
			events.add("idle:" + computerId);
			return !("idle".equals(failure) && computerId.equals(failedNode));
		}
		@Override public boolean nodeRootFree(UUID computerId) {
			events.add("rootFree:" + computerId);
			return !("rootFree".equals(failure) && computerId.equals(failedNode));
		}
		@Override public boolean nodeMailboxEmpty(UUID computerId) {
			events.add("mailbox:" + computerId);
			return !("mailbox".equals(failure) && computerId.equals(failedNode));
		}
	}

	private static final class ProbeReform extends ProbeQuiescence implements EpochReformControl {
		private boolean stop = true;

		static ProbeReform ready() { return new ProbeReform(); }
		static ProbeReform failing(String failure, UUID node) {
			ProbeReform probe = new ProbeReform();
			probe.failure = failure;
			probe.failedNode = node;
			if (failure.equals("stop")) probe.stop = false;
			if (failure.equals("roots")) probe.roots = false;
			return probe;
		}

		@Override public boolean stopAllAndClear() { events.add("stop"); return stop; }
	}

	/** Avoid relying on the registered mod blocks in a pure unit test. */
	private static final class BlockBehaviourProperties {
		static net.minecraft.world.level.block.state.BlockBehaviour.Properties copy() {
			return net.minecraft.world.level.block.state.BlockBehaviour.Properties.of();
		}
	}
}
