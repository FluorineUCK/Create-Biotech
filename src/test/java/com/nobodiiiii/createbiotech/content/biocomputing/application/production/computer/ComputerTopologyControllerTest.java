package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
	private static final ComputerStructureScanner.Limits MAX_LIMITS =
		new ComputerStructureScanner.Limits(3, 16, 4096, 256);
	private static final UUID LOW = uuid(1);
	private static final UUID MID = uuid(2);
	private static final UUID HIGH = uuid(3);
	private static final ProductionResourceProfile PROFILE = ProductionResourceProfile
		.forProfession(ProductionProfession.LIBRARIAN, 3, 0).orElseThrow();
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
	void validatedEmbeddedPanelPublishesOnceAsPanelOnlyForStartedEpoch() {
		Fixture fixture = fixture(profiled(LOW, pos(0)));
		ProductionLayout.Panel panel = new ProductionLayout.Panel(uuid(44), address(new BlockPos(0, 1, 1)),
			Direction.WEST, ComputerShellMaterial.TRANSPARENT);
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0))), List.of(panel));
		fixture.installPanel(panel);

		fixture.refresh(LOW);
		assertEquals(List.of(), fixture.panelPublications);
		ProductionStructure.Revision record = fixture.be(LOW).currentStructureRecord().orElseThrow();
		ClusterBinding binding = new ClusterBinding(uuid(500), 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				record.structureId()), List.of());
		fixture.be(LOW).applyTopologyState(record, binding, true, null, Set.of());

		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(LOW), binding.clusterId(), fixture, LIMITS));
		assertEquals(List.of(new PanelPublication(ClusterMemberType.PANEL, panel.panelId(),
			binding.clusterId(), fixture.be(LOW).epoch().orElseThrow().epochId())),
			fixture.panelPublications);
		assertEquals(List.of(panel), fixture.be(LOW).epoch().orElseThrow().layout().panels());
		fixture.refresh(LOW);
		assertEquals(1, fixture.panelPublications.size(), "refresh must not republish frozen panels");
	}

	@Test
	void startingHeterogeneousMembersFreezesTheirOwnProfilesAndRepeatStartDoesNotRewriteThem() {
		var fletcher = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), new NodeSpec(HIGH, pos(2), fletcher));
		var caller = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(caller, caller.bindingState().clusterId(), fixture, LIMITS));
		var epoch = caller.epoch().orElseThrow();
		assertEquals(List.of(PROFILE, fletcher), epoch.layout().members().stream().map(ProductionLayout.Member::profile).toList());
		assertEquals(epoch, fixture.be(HIGH).epoch().orElseThrow());
		assertEquals(PROFILE, caller.installedProfile().orElseThrow());
		assertEquals(fletcher, fixture.be(HIGH).installedProfile().orElseThrow());
		var saved = fixture.computers.values().stream().map(ComputerTopologyControllerTest::save).toList();
		fixture.resetProbes();
		assertEquals(ProductionComputer.EpochStartResult.ALREADY_ACTIVE,
			ComputerTopologyController.startEpoch(caller, caller.bindingState().clusterId(), fixture, LIMITS));
		assertEquals(0, fixture.worldProbes());
		assertEquals(saved, fixture.computers.values().stream().map(ComputerTopologyControllerTest::save).toList());
	}

	@Test
	void secondPanelPublicationFailureRollsBackStartAndRetryPublishesExactlyOnce() {
		Fixture fixture = panelEpochFixture();
		ProductionComputer computer = fixture.be(LOW);
		ProductionComputer.TopologyState before = computer.topologyState();
		fixture.failPanelPublishAttempt = 2;

		assertEquals(ProductionComputer.EpochStartResult.NOT_READY,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		assertTrue(fixture.publishedPanelEpochs.isEmpty());
		assertTrue(computer.epoch().isEmpty());
		assertEquals(before.record(), computer.topologyState().record());
		assertEquals(before.binding(), computer.topologyState().binding());
		assertEquals(before.epoch(), computer.topologyState().epoch());
		assertEquals(before.faults(), computer.topologyState().faults());

		fixture.failPanelPublishAttempt = Integer.MAX_VALUE;
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		assertEquals(2, fixture.publishedPanelEpochs.size());
		fixture.refresh(LOW);
		assertEquals(3, fixture.panelPublications.size(), "retry plus refresh must not duplicate frozen members");
	}

	@Test
	void secondReplacementPanelFailureRestoresOldEpochAndPanelSetAfterReform() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		fixture.refresh(LOW);
		assertTrue(computer.isCoordinatorMemberPublished());
		UUID oldEpoch = fixture.epochId();
		List<TopologyProof> oldStates = topologyProofs(fixture);
		List<ComputerAvailabilityReason> oldReasons = fixture.computers.values().stream()
			.map(ProductionComputer::availabilityReason).toList();
		List<Boolean> oldCoordinators = fixture.computers.values().stream()
			.map(ProductionComputer::isCoordinatorMemberPublished).toList();
		Map<UUID, UUID> oldPanels = Map.copyOf(fixture.publishedPanelEpochs);
		fixture.failPanelPublishAttempt = fixture.panelPublishAttempts + 2;

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(computer, oldEpoch, ProbeReform.ready(), fixture, LIMITS));
		assertEquals(oldEpoch, fixture.epochId());
		assertEquals(oldStates, topologyProofs(fixture));
		assertEquals(oldReasons, fixture.computers.values().stream()
			.map(ProductionComputer::availabilityReason).toList());
		assertEquals(oldCoordinators, fixture.computers.values().stream()
			.map(ProductionComputer::isCoordinatorMemberPublished).toList());
		assertEquals(oldPanels, fixture.publishedPanelEpochs);
	}

	@Test
	void failedReplacementAndFailedOldPanelRestorationLeavesTheEpochFailClosed() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		fixture.refresh(LOW);
		assertTrue(computer.isCoordinatorMemberPublished());
		UUID oldEpoch = fixture.epochId();
		List<TopologyProof> oldStates = topologyProofs(fixture);
		int beforeReform = fixture.panelPublishAttempts;
		fixture.failedPanelPublishAttempts.add(beforeReform + 2);
		fixture.failedPanelPublishAttempts.add(beforeReform + 4);

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(computer, oldEpoch, ProbeReform.ready(), fixture, LIMITS));

		assertEquals(oldEpoch, fixture.epochId());
		assertEquals(oldStates, topologyProofs(fixture),
			"the durable old epoch remains the only topology after a failed replacement");
		assertTrue(fixture.publishedPanelEpochs.isEmpty(),
			"a partially restored old Panel set must be withdrawn again");
		assertTrue(fixture.computers.values().stream()
			.noneMatch(ProductionComputer::isCoordinatorMemberPublished),
			"the old coordinator cannot advertise a healthy service without every frozen Panel");
		assertTrue(fixture.computers.values().stream().allMatch(computerMember ->
			computerMember.availabilityReason() == ComputerAvailabilityReason.PERSISTENCE_INVALID
				&& !computerMember.structureOnline() && !computerMember.coordinatorOnline()));
	}

	@Test
	void failedMultiMemberStartRestoresEveryEpochBeforeNotifyingObservers() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		var observations = new ArrayList<List<Boolean>>();
		for (var member : fixture.computers.values())
			((ObservedComputer) member.owner()).onTopologyChange = () -> observations.add(fixture.computers.values().stream()
				.map(c -> c.epoch().isPresent()).toList());
		fixture.failPanelPublishAttempt = 2;
		var computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.NOT_READY,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		assertTrue(observations.contains(List.of(true, true)), "Observers see the prepared epoch");
		assertTrue(observations.contains(List.of(false, false)), "Observers see completed recovery");
		assertTrue(observations.stream().allMatch(values -> values.getFirst().equals(values.getLast())),
			"No notification may expose a half-restored member cohort: " + observations);
	}

	@Test
	void failedReformOfAFaultedEpochDoesNotReopenPreviouslyWithdrawnPanels() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		fixture.latch(EpochFault.WIDTH);
		assertTrue(fixture.publishedPanelEpochs.isEmpty());
		assertTrue(fixture.computers.values().stream().noneMatch(ProductionComputer::isCoordinatorMemberPublished));
		var before = topologyProofs(fixture);
		fixture.failPanelPublishAttempt = fixture.panelPublishAttempts + 2;

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(computer, fixture.epochId(), ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, topologyProofs(fixture));
		assertTrue(fixture.publishedPanelEpochs.isEmpty(), "Rollback must not open services withdrawn by the latched fault");
		assertTrue(fixture.computers.values().stream().noneMatch(ProductionComputer::isCoordinatorMemberPublished));
	}

	@Test
	void failedReformRestoresExactlyThePreviouslyPublishedPanelSubset() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		var computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		fixture.withdrawPanel(fixture.snapshot.layout().panels().getLast());
		var panelsBefore = Map.copyOf(fixture.publishedPanelEpochs);
		assertEquals(1, panelsBefore.size());
		var statesBefore = topologyProofs(fixture);
		fixture.failPanelPublishAttempt = fixture.panelPublishAttempts + 2;

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(computer, fixture.epochId(), ProbeReform.ready(), fixture, LIMITS));
		assertEquals(statesBefore, topologyProofs(fixture));
		assertEquals(panelsBefore, fixture.publishedPanelEpochs, "Do not infer prior publication from frozen membership");
	}

	@Test
	void reformReusesEpochProjectionsAcrossPanelQueriesPublicationAndRecovery() {
		for (boolean failPublication : List.of(false, true)) {
			Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
			var computer = fixture.be(LOW);
			assertEquals(ProductionComputer.EpochStartResult.STARTED,
				ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
			fixture.panelEpochViews.clear();
			if (failPublication) fixture.failPanelPublishAttempt = fixture.panelPublishAttempts + 2;
			assertEquals(failPublication ? ProductionComputer.ReformResult.IDENTITY_CONFLICT : ProductionComputer.ReformResult.REFORMED,
				ComputerTopologyController.stopAllAndReform(computer, fixture.epochId(), ProbeReform.ready(), fixture, LIMITS));
			assertEquals(2, fixture.panelEpochViews.size(), "Old and replacement epoch are both queried");
			fixture.panelEpochViews.forEach((id, views) -> assertEquals(1, views.size(),
				"Do not rebuild every member/panel view for each panel in epoch " + id));
		}
	}

	@Test
	void panelBearingNormalReformPublishesTheReplacementEpoch() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		UUID oldEpoch = fixture.epochId();

		assertEquals(ProductionComputer.ReformResult.REFORMED,
			ComputerTopologyController.stopAllAndReform(computer, oldEpoch, ProbeReform.ready(), fixture, LIMITS));

		UUID replacement = fixture.epochId();
		assertNotEquals(oldEpoch, replacement);
		assertEquals(Set.of(replacement), new HashSet<>(fixture.publishedPanelEpochs.values()));
		assertEquals(2, fixture.publishedPanelEpochs.size());
		assertEquals(List.of(ComputerAvailabilityReason.NONE, ComputerAvailabilityReason.NONE),
			fixture.computers.values().stream().map(ProductionComputer::availabilityReason).toList());
		assertEquals(List.of(true, false), fixture.computers.values().stream()
			.map(ProductionComputer::isCoordinatorMemberPublished).toList());
	}

	@Test
	void failedActiveRefreshLeavesNoPartialPanelServiceThenRetryIsSuccessfulAndIdempotent() {
		Fixture fixture = panelEpochFixture();
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		UUID epoch = fixture.epochId();
		fixture.publishedPanelEpochs.clear();
		computer.withdrawCoordinatorMember();
		assertFalse(computer.isCoordinatorMemberPublished());
		fixture.failPanelPublishAttempt = fixture.panelPublishAttempts + 2;

		ComputerTopologyController.RefreshOutcome failed = ComputerTopologyController.refresh(
			computer, fixture, LIMITS);

		assertFalse(failed.committed());
		assertEquals(epoch, fixture.epochId());
		assertFalse(computer.isCoordinatorMemberPublished(),
			"a failed refresh must restore the absence of coordinator service");
		assertTrue(fixture.publishedPanelEpochs.isEmpty(),
			"a rolled-back refresh must not leave one newly published Panel behind");

		fixture.failPanelPublishAttempt = Integer.MAX_VALUE;
		ComputerTopologyController.RefreshOutcome retried = ComputerTopologyController.refresh(
			computer, fixture, LIMITS);
		assertTrue(retried.committed());
		assertEquals(Set.of(epoch), new HashSet<>(fixture.publishedPanelEpochs.values()));
		assertEquals(2, fixture.publishedPanelEpochs.size());
		int attemptsAfterRetry = fixture.panelPublishAttempts;

		assertTrue(ComputerTopologyController.refresh(computer, fixture, LIMITS).committed());
		assertEquals(attemptsAfterRetry, fixture.panelPublishAttempts,
			"an already restored active publication must not register again");
	}

	@Test
	void failedRefreshAndFailedPriorPanelRestorationWithdrawTheCoordinatorService() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		fixture.refresh(LOW);
		assertTrue(computer.isCoordinatorMemberPublished());
		var oldStates = topologyProofs(fixture);
		var oldEpoch = fixture.epochId();
		fixture.publishedPanelEpochs.remove(uuid(45));
		fixture.beforePanelPublish = fixture.publishedPanelEpochs::clear;
		fixture.failedPanelPublishAttempts.add(fixture.panelPublishAttempts + 1);
		fixture.failedPanelPublishAttempts.add(fixture.panelPublishAttempts + 2);

		assertFalse(ComputerTopologyController.refresh(computer, fixture, LIMITS).committed());
		assertEquals(oldStates, topologyProofs(fixture), "Keep the durable old topology, not a half replacement");
		assertEquals(oldEpoch, fixture.epochId());
		assertTrue(fixture.publishedPanelEpochs.isEmpty());
		assertTrue(fixture.computers.values().stream().noneMatch(ProductionComputer::isCoordinatorMemberPublished),
			"A coordinator must not advertise successful restoration after restoring its old panel failed");
		assertTrue(fixture.computers.values().stream().allMatch(c -> c.availabilityReason() == ComputerAvailabilityReason.PERSISTENCE_INVALID
			&& !c.structureOnline() && !c.coordinatorOnline()));
	}

	@Test
	void scheduledFailedRestorationDoesNotOverwriteItsFaultWithHealthyAvailability() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		var computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		fixture.refresh(LOW);
		var oldStates = topologyProofs(fixture);
		fixture.publishedPanelEpochs.remove(uuid(45));
		fixture.beforePanelPublish = fixture.publishedPanelEpochs::clear;
		fixture.failedPanelPublishAttempts.add(fixture.panelPublishAttempts + 1);
		fixture.failedPanelPublishAttempts.add(fixture.panelPublishAttempts + 2);

		computer.scheduledTopologyTick(1000, fixture, LIMITS);

		assertEquals(oldStates, topologyProofs(fixture));
		for (var member : fixture.computers.values()) {
			assertEquals(ComputerAvailabilityReason.PERSISTENCE_INVALID, member.availabilityReason(),
				"The scheduling fan-out must retain the actual recovery failure");
			assertFalse(member.isCoordinatorMemberPublished());
			assertFalse(member.structureOnline());
		}
	}

	@Test
	void scanAndResolvedPanelValidationLossWithdrawPriorEpochPanels() {
		Fixture scanLoss = panelEpochFixture();
		ProductionComputer scanOwner = scanLoss.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(scanOwner, scanOwner.bindingState().clusterId(), scanLoss, LIMITS));
		scanLoss.scanState = ComputerStructureScanner.State.OPEN_SHELL;

		assertFalse(ComputerTopologyController.refresh(scanOwner, scanLoss, LIMITS).committed());
		assertTrue(scanLoss.publishedPanelEpochs.isEmpty());

		Fixture validationLoss = panelEpochFixture();
		ProductionComputer validationOwner = validationLoss.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(validationOwner,
				validationOwner.bindingState().clusterId(), validationLoss, LIMITS));
		BlockPos panelPos = validationLoss.snapshot.layout().panels().getFirst().address().localPos();
		validationLoss.panels.remove(panelPos);

		assertFalse(ComputerTopologyController.refresh(validationOwner, validationLoss, LIMITS).committed());
		assertTrue(validationLoss.publishedPanelEpochs.isEmpty());
	}

	@Test
	void epochFaultWithdrawsPriorPanelPublication() {
		Fixture fixture = panelEpochFixture();
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));

		fixture.latch(EpochFault.WIDTH);

		assertTrue(fixture.publishedPanelEpochs.isEmpty());
	}

	@Test
	void faultUnionFromAFollowerPreservesEachProfileAndNotifiesOnlyAfterAllReplicasAndPanels() {
		var other = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), new NodeSpec(HIGH, pos(1), other));
		var caller = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(caller, caller.bindingState().clusterId(), fixture, LIMITS));
		var follower = fixture.be(HIGH);
		var epoch = follower.epoch().orElseThrow();
		follower.applyTopologyState(follower.currentStructureRecord().orElseThrow(), follower.bindingState(),
			true, epoch, Set.of(EpochFault.DEPTH));
		var residents = residentBytes(fixture);
		var expected = Set.of(EpochFault.WIDTH, EpochFault.DEPTH);
		var notifications = new ArrayList<UUID>();
		for (var computer : fixture.computers.values()) ((ObservedComputer) computer.owner()).onTopologyChange = () -> {
			for (var replica : fixture.computers.values()) assertEquals(expected, replica.latchedEpochFaults());
			assertTrue(fixture.publishedPanelEpochs.isEmpty());
			notifications.add(computer.computerId().orElseThrow());
		};
		assertEquals(ProductionComputer.FaultLatchResult.LATCHED,
			ComputerTopologyController.latchEpochFault(follower, epoch.epochId(), EpochFault.WIDTH, fixture));
		assertEquals(List.of(LOW, HIGH), notifications);
		assertEquals(PROFILE, caller.installedProfile().orElseThrow());
		assertEquals(other, follower.installedProfile().orElseThrow());
		for (var computer : fixture.computers.values()) assertEquals(epoch, computer.epoch().orElseThrow());
		assertResidentBytesEqual(residents, residentBytes(fixture));
		var after = bytes(fixture);
		assertEquals(ProductionComputer.FaultLatchResult.ALREADY_LATCHED,
			ComputerTopologyController.latchEpochFault(follower, epoch.epochId(), EpochFault.WIDTH, fixture));
		assertEquals(after, bytes(fixture));
	}

	@Test
	void faultLatchSkipsUnloadedMembersAndRepeatedLatchCatchesThemUpAfterReload() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(1)));
		var before = save(fixture.be(HIGH));
		fixture.unloadedPositions.add(pos(1));
		fixture.resetProbes();
		fixture.latch(EpochFault.WIDTH);
		assertEquals(before, save(fixture.be(HIGH)));
		assertEquals(Set.of(EpochFault.WIDTH), fixture.be(LOW).latchedEpochFaults());
		assertEquals(0, fixture.scanCalls, "Fault withdrawal does not require a complete shell rescan");
		fixture.unloadedPositions.clear();
		assertEquals(ProductionComputer.FaultLatchResult.ALREADY_LATCHED,
			ComputerTopologyController.latchEpochFault(fixture.be(HIGH), fixture.epochId(), EpochFault.WIDTH, fixture));
		assertEquals(Set.of(EpochFault.WIDTH), fixture.be(HIGH).latchedEpochFaults());
	}

	@Test
	void normalEpochCloseWithdrawsPriorPanelPublication() {
		Fixture fixture = panelEpochFixture();
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		UUID epoch = fixture.epochId();

		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(computer, epoch, ProbeQuiescence.ready(),
				fixture, LIMITS));
		assertTrue(fixture.publishedPanelEpochs.isEmpty());
	}

	@Test
	void normalEpochCloseRejectsChangedFrozenPanelIdentityWithoutWithdrawingOldService() {
		Fixture fixture = panelEpochFixture();
		ProductionComputer computer = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(computer, computer.bindingState().clusterId(), fixture, LIMITS));
		UUID epoch = fixture.epochId();
		Map<UUID, UUID> oldPanels = Map.copyOf(fixture.publishedPanelEpochs);
		ProductionLayout.Panel oldFirst = fixture.snapshot.layout().panels().getFirst();
		ProductionLayout.Panel changed = new ProductionLayout.Panel(uuid(43),
			oldFirst.address(), oldFirst.facing(), oldFirst.baseMaterial());
		List<ProductionLayout.Panel> changedPanels = List.of(changed,
			fixture.snapshot.layout().panels().get(1));
		fixture.snapshot = snapshot(fixture.snapshot.layout().members(), changedPanels);
		fixture.panels.remove(oldFirst.address().localPos());
		fixture.installPanel(changed);

		assertEquals(ProductionComputer.EpochCloseResult.FROZEN_MEMBER_MISSING,
			ComputerTopologyController.closeIdleEpoch(computer, epoch, ProbeQuiescence.ready(),
				fixture, LIMITS));
		assertEquals(oldPanels, fixture.publishedPanelEpochs);
		assertEquals(epoch, fixture.epochId());
	}

	@Test
	void oneExistingStructureIdClaimsNullNewMembersButTwoExistingIdsFault() {
		Fixture fixture = fixture(profiled(LOW, pos(2)), profiled(HIGH, pos(0)));
		UUID member = uuid(40);
		ProductionStructure.Revision record = new ProductionStructure.Revision(member, 4, LOW, fixture.snapshot);
		fixture.be(LOW).applyTopologyState(record, null, true, null, Set.of());

		fixture.refresh(LOW);

		assertEquals(member, fixture.be(HIGH).computerStructureMemberId().orElseThrow());
		ProductionStructure.Revision foreign = new ProductionStructure.Revision(uuid(41), 4, LOW, fixture.snapshot);
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
		assertEquals(ProductionComputer.EpochStartResult.NOT_READY,
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
		physical(fixture.be(LOW)).read(corrupt, REGISTRIES, false);
		CompoundTag corruptBefore = save(fixture.be(LOW));
		fixture.refresh(LOW);
		assertEquals(corruptBefore, save(fixture.be(LOW)));
		assertEquals(ComputerAvailabilityReason.IDENTITY_CONFLICT,
			fixture.be(LOW).availabilityReason());
	}

	@Test
	void knownChunkUnloadRetainsSnapshotCoordinatorAndRevisionWithoutReelection() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionStructure.Revision before = fixture.be(LOW).currentStructureRecord().orElseThrow();
		ProductionEpoch epochBefore = fixture.be(LOW).epoch().orElseThrow();
		fixture.unloadedChunks.add(before.structure().containingChunks().iterator().next());
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

		ProductionStructure.Revision record = fixture.be(HIGH).currentStructureRecord().orElseThrow();
		assertEquals(member, record.structureId());
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
	void inactiveSameShellLowerUuidHotAddUpdatesCoordinatorAndPreservesIdentityBinding() {
		Fixture fixture = fixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		fixture.refresh(MID);
		UUID member = fixture.be(MID).computerStructureMemberId().orElseThrow();
		long revision = fixture.be(MID).currentStructureRecord().orElseThrow().revision();
		ClusterBinding binding = new ClusterBinding(uuid(700), 0, null, List.of());
		for (ProductionComputer computer : fixture.computers.values()) {
			ProductionStructure.Revision record = computer.currentStructureRecord().orElseThrow();
			computer.applyTopologyState(record, binding, true, null, Set.of());
		}
		fixture.put(computer(LOW, pos(1), PROFILE));
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)),
			profiledNode(LOW, pos(1)), profiledNode(HIGH, pos(2))));

		fixture.refresh(LOW);

		for (ProductionComputer computer : fixture.computers.values()) {
			ProductionStructure.Revision record = computer.currentStructureRecord().orElseThrow();
			assertEquals(member, record.structureId());
			assertEquals(revision + 1, record.revision());
			assertEquals(LOW, record.coordinatorId());
			assertEquals(binding, computer.bindingState());
		}
	}

	@Test
	void lowerUuidActiveHotAddIsPendingAndDoesNotChangeRecordCoordinatorFrozenOrderOrRangeBonus() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ProductionEpoch before = fixture.be(MID).epoch().orElseThrow();
		ProductionComputer added = computer(LOW, pos(1), PROFILE);
		fixture.put(added);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)),
			profiledNode(LOW, pos(1)), profiledNode(HIGH, pos(2))));

		fixture.refresh(LOW);

		ProductionEpoch after = fixture.be(LOW).epoch().orElseThrow();
		assertEquals(before.epochId(), after.epochId());
		assertEquals(before.coordinatorId(), after.coordinatorId());
		assertEquals(before.layout().members(), after.layout().members());
		assertEquals(before.coordinatorId(),
			fixture.be(LOW).currentStructureRecord().orElseThrow().coordinatorId());
		assertEquals(List.of(LOW), fixture.be(LOW).pendingNodes().stream()
			.map(ComputerNodeView::computerId).toList());
	}

	@Test
	void heterogeneousActiveMembersKeepTheirOwnProfilesWhenALowerIdMemberJoins() {
		var fletcher = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
		var junior = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
		Fixture fixture = activeFixture(profiled(MID, pos(0)), new NodeSpec(HIGH, pos(2), fletcher));
		ProductionEpoch frozen = fixture.be(MID).epoch().orElseThrow();
		fixture.put(computer(LOW, pos(1), junior));
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)),
			new ProductionLayout.Member(HIGH, address(pos(2)), fletcher),
			new ProductionLayout.Member(LOW, address(pos(1)), junior)));

		assertTrue(ComputerTopologyController.refresh(fixture.be(LOW), fixture, LIMITS).committed());
		assertEquals(frozen, fixture.be(LOW).epoch().orElseThrow());
		assertEquals(List.of(PROFILE, fletcher), frozen.layout().members().stream().map(ProductionLayout.Member::profile).toList());
		assertEquals(List.of(junior, PROFILE, fletcher), fixture.be(LOW).currentStructureSnapshot().orElseThrow()
			.layout().members().stream().map(ProductionLayout.Member::profile).toList());
		assertEquals(List.of(LOW), fixture.be(LOW).pendingNodes().stream().map(ComputerNodeView::computerId).toList());
		assertEquals(junior, fixture.be(LOW).installedProfile().orElseThrow());
		assertEquals(PROFILE, fixture.be(MID).installedProfile().orElseThrow());
		assertEquals(fletcher, fixture.be(HIGH).installedProfile().orElseThrow());
	}

	@Test
	void emptyPendingHotAddCanInstallOnceWithoutChangingEpochOrFaults() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ProductionEpoch before = fixture.be(MID).epoch().orElseThrow();
		ProductionComputer pending = computer(LOW, pos(1), null);
		fixture.put(pending);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)), emptyNode(LOW, pos(1)),
			profiledNode(HIGH, pos(2))));
		fixture.scanState = ComputerStructureScanner.State.VALID_NOT_READY;

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.NONE, pending.availabilityReason());
		assertFalse(pending.pendingNodes().getFirst().online());
		physical(pending).installResidentSnapshot(new ItemStack(Items.PAPER), PROFILE);
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
		ProductionEpoch before = fixture.be(LOW).epoch().orElseThrow();
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
		ProductionStructure.Revision record = fixture.be(HIGH).currentStructureRecord().orElseThrow();
		ProductionEpoch epoch = fixture.be(HIGH).epoch().orElseThrow();
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
		ProductionEpoch before = fixture.be(LOW).epoch().orElseThrow();
		long revision = fixture.be(LOW).currentStructureRecord().orElseThrow().revision();
		fixture.move(LOW, pos(4));
		fixture.move(HIGH, pos(6));
		fixture.snapshot = snapshotAt(4, List.of(profiledNode(LOW, pos(4)),
			profiledNode(HIGH, pos(6))));

		fixture.refresh(LOW);

		ProductionEpoch moved = fixture.be(LOW).epoch().orElseThrow();
		assertEquals(before.epochId(), moved.epochId());
		assertNotEquals(before.layout().bounds(), moved.layout().bounds());
		assertEquals(address(pos(4)), moved.coordinatorAddress());
		assertEquals(revision + 1,
			fixture.be(LOW).currentStructureRecord().orElseThrow().revision());
	}

	@Test
	void partialOrOpenShellRetainsLastActiveSnapshotWithDifferentReasons() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionStructure.Revision before = fixture.be(LOW).currentStructureRecord().orElseThrow();
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
		ProductionStructure.Revision record = fixture.be(LOW).currentStructureRecord().orElseThrow();
		ClusterBinding binding = fixture.be(LOW).bindingState();
		ProductionEpoch epoch = fixture.be(LOW).epoch().orElseThrow();
		fixture.be(LOW).applyTopologyState(record, binding, true, epoch, Set.of(EpochFault.WIDTH));
		fixture.be(HIGH).applyTopologyState(record, binding, true, epoch, Set.of(EpochFault.DEPTH));

		fixture.refresh(LOW);

		Set<EpochFault> expected = Set.of(EpochFault.WIDTH, EpochFault.DEPTH);
		assertEquals(expected, fixture.be(LOW).latchedEpochFaults());
		assertEquals(expected, fixture.be(HIGH).latchedEpochFaults());
		ProductionComputer decoded = computer(HIGH, pos(2), PROFILE);
		physical(decoded).read(save(fixture.be(HIGH)), REGISTRIES, false);
		assertEquals(expected, decoded.latchedEpochFaults());
	}

	@Test
	void equalRevisionIndependentlyDecodedSnapshotReplicasAreAccepted() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		UUID member = uuid(70);
		ProductionStructure.Revision first = new ProductionStructure.Revision(member, 6, LOW, fixture.snapshot);
		ProductionStructure.Revision decoded = ProductionStructureCodec.loadRevision(ProductionStructureCodec.saveRevision(first)).orElseThrow();
		assertNotEquals(System.identityHashCode(first.structure()),
			System.identityHashCode(decoded.structure()));
		fixture.be(LOW).applyTopologyState(first, null, true, null, Set.of());
		fixture.be(HIGH).applyTopologyState(decoded, null, true, null, Set.of());

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.NONE, fixture.be(LOW).availabilityReason());
		assertEquals(first, fixture.be(HIGH).currentStructureRecord().orElseThrow());
	}

	@Test
	void stableMaximumCohortUsesOneProductionScheduledScanPerCycle() {
		Fixture fixture = maximumFixture(256);
		UUID owner = fixture.snapshot.layout().members().getFirst().computerId();
		ComputerTopologyController.refresh(fixture.be(owner), fixture, MAX_LIMITS);
		fixture.resetProbes();

		List<ProductionComputer> reverse = new ArrayList<>(fixture.computers.values());
		java.util.Collections.reverse(reverse);
		for (ProductionComputer computer : reverse)
			computer.scheduledTopologyTick(40, fixture, MAX_LIMITS);

		assertEquals(1, fixture.scanCalls);
	}

	@Test
	void freshNonMinimumProductionScheduledTickDiscoversAndAttachesWholeCohortWithinTwoScans() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));

		fixture.be(HIGH).scheduledTopologyTick(0, fixture, LIMITS);
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);

		assertTrue(fixture.scanCalls <= 2, "fresh non-owner discovery must be structure-constant");
		assertTrue(fixture.computers.values().stream()
			.allMatch(computer -> computer.currentStructureRecord().isPresent()));
		fixture.resetProbes();
		fixture.be(HIGH).scheduledTopologyTick(1, fixture, LIMITS);
		fixture.be(LOW).scheduledTopologyTick(1, fixture, LIMITS);
		assertEquals(0, fixture.scanCalls);
	}

	@Test
	void failedDiscoveryKeepsTheTwentyTickRetryCadence() {
		Fixture fixture = fixture(profiled(LOW, pos(0)));
		fixture.scanState = ComputerStructureScanner.State.OPEN_SHELL;
		ProductionComputer computer = fixture.be(LOW);

		computer.scheduledTopologyTick(0, fixture, LIMITS);
		computer.scheduledTopologyTick(1, fixture, LIMITS);
		computer.scheduledTopologyTick(19, fixture, LIMITS);
		assertEquals(1, fixture.scanCalls,
			"A lease-less invalid Computer must not repeat its full scan every tick");

		computer.scheduledTopologyTick(20, fixture, LIMITS);
		assertEquals(2, fixture.scanCalls,
			"A failed discovery must become eligible again at the 20-tick boundary");
	}

	@Test
	void topologyDirtyWakesFailedDiscoveryOnceAndRearmsItsCadence() {
		Fixture fixture = fixture(profiled(LOW, pos(0)));
		fixture.scanState = ComputerStructureScanner.State.OPEN_SHELL;
		ProductionComputer computer = fixture.be(LOW);

		computer.scheduledTopologyTick(0, fixture, LIMITS);
		computer.markTopologyDirty();
		computer.scheduledTopologyTick(1, fixture, LIMITS);
		computer.scheduledTopologyTick(2, fixture, LIMITS);

		assertEquals(2, fixture.scanCalls,
			"Dirty invalid topology must retry immediately once, then return to cooldown");
	}

	@Test
	void nonOwnerDirtyProductionScheduledTickWakesLeaseOwnerOnly() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(HIGH).scheduledTopologyTick(0, fixture, LIMITS);
		fixture.resetProbes();

		fixture.be(HIGH).markTopologyDirty();
		fixture.be(HIGH).scheduledTopologyTick(1, fixture, LIMITS);
		assertEquals(0, fixture.scanCalls);
		fixture.be(LOW).scheduledTopologyTick(1, fixture, LIMITS);

		assertEquals(1, fixture.scanCalls);
	}

	@Test
	void reloadedMemberRebuildsProductionLeaseFromPersistedSnapshotWithoutExtraScan() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);
		fixture.resetProbes();
		fixture.replaceLoadedObject(HIGH);

		fixture.be(HIGH).scheduledTopologyTick(1, fixture, LIMITS);

		assertEquals(0, fixture.scanCalls);
		fixture.be(LOW).scheduledTopologyTick(20, fixture, LIMITS);
		assertEquals(1, fixture.scanCalls);
	}

	@Test
	void knownLeaseWithUnloadedChunkFansPartialWithoutFullScan() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);
		fixture.resetProbes();
		fixture.unloadedChunks.addAll(fixture.snapshot.containingChunks());

		fixture.be(LOW).scheduledTopologyTick(20, fixture, LIMITS);

		assertEquals(0, fixture.scanCalls);
		assertTrue(fixture.computers.values().stream().allMatch(computer ->
			computer.availabilityReason() == ComputerAvailabilityReason.PARTIAL_UNLOADED));
	}

	@Test
	void staleUnloadedInstanceCannotDetachTheRebuiltProductionScanCohort() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);
		ProductionComputer oldHigh = fixture.replaceLoadedObject(HIGH);
		ProductionComputer nextHigh = fixture.be(HIGH);
		nextHigh.scheduledTopologyTick(1, fixture, LIMITS);
		var before = bytes(fixture);
		oldHigh.owner().invalidate();
		assertEquals(before, bytes(fixture));
		fixture.resetProbes();
		nextHigh.scheduledTopologyTick(20, fixture, LIMITS);
		fixture.be(LOW).scheduledTopologyTick(20, fixture, LIMITS);
		nextHigh.scheduledTopologyTick(21, fixture, LIMITS);
		assertEquals(1, fixture.scanCalls);
		assertEquals(before, bytes(fixture));
	}

	@Test
	void persistedLeasePartialRebuildWithdrawsEpochPanelsAndCoordinatorServiceWithoutScan() {
		Fixture fixture = panelEpochFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer owner = fixture.be(LOW);
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(owner, owner.bindingState().clusterId(), fixture, LIMITS));
		fixture.refresh(LOW);
		UUID epochId = fixture.epochId();
		assertEquals(2, fixture.publishedPanelEpochs.size());
		assertTrue(owner.isCoordinatorMemberPublished());
		fixture.resetProbes();
		fixture.unloadedChunks.addAll(fixture.snapshot.containingChunks());

		owner.scheduledTopologyTick(40, fixture, LIMITS);

		assertEquals(0, fixture.scanCalls,
			"the persisted-lease partial branch must be exercised before any full scan");
		assertTrue(fixture.publishedPanelEpochs.isEmpty());
		assertTrue(fixture.computers.values().stream()
			.noneMatch(ProductionComputer::isCoordinatorMemberPublished));
		assertTrue(fixture.computers.values().stream().allMatch(computer ->
			computer.availabilityReason() == ComputerAvailabilityReason.PARTIAL_UNLOADED
				&& computer.epoch().filter(epoch -> epoch.epochId().equals(epochId)).isPresent()));
	}

	@Test
	void inactiveSplitDetachesExcludedMembersAndEachProductionLeaseRediscovers() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);
		UUID structureId = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		ProductionStructure lowSide = snapshotAt(0, List.of(profiledNode(LOW, pos(0))));
		ProductionStructure highSide = snapshotAt(2, List.of(profiledNode(HIGH, pos(2))));
		fixture.scanSnapshotsBySeed.put(pos(0), lowSide);
		fixture.scanSnapshotsBySeed.put(pos(2), highSide);
		fixture.resetProbes();

		fixture.be(LOW).scheduledTopologyTick(20, fixture, LIMITS);
		fixture.be(HIGH).scheduledTopologyTick(20, fixture, LIMITS);

		assertEquals(2, fixture.scanCalls);
		assertEquals(lowSide, fixture.be(LOW).currentStructureSnapshot().orElseThrow());
		assertEquals(ComputerAvailabilityReason.IDENTITY_CONFLICT,
			fixture.be(HIGH).availabilityReason());
		assertEquals(structureId, fixture.be(HIGH).computerStructureMemberId().orElseThrow());
		fixture.be(HIGH).scheduledTopologyTick(21, fixture, LIMITS);
		fixture.be(LOW).scheduledTopologyTick(21, fixture, LIMITS);
		assertEquals(2, fixture.scanCalls);
	}

	@Test
	void inactiveOwnerRemovalTransfersProductionProbeToRemainingMinimum() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);
		UUID structureId = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		ProductionComputer removed = fixture.be(LOW);
		fixture.remove(LOW);
		removed.owner().invalidate();
		fixture.snapshot = snapshotAt(2, List.of(profiledNode(HIGH, pos(2))));
		fixture.resetProbes();

		fixture.be(HIGH).scheduledTopologyTick(1, fixture, LIMITS);

		assertEquals(1, fixture.scanCalls);
		assertEquals(HIGH, fixture.be(HIGH).currentStructureRecord().orElseThrow().coordinatorId());
		assertEquals(structureId, fixture.be(HIGH).computerStructureMemberId().orElseThrow());
	}

	@Test
	void activeOwnerRemovalKeepsFrozenEpochAndReportsCoordinatorMissingThroughLease() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.be(LOW).scheduledTopologyTick(0, fixture, LIMITS);
		CompoundTag frozen = ProductionEpochCodec.save(fixture.be(HIGH).epoch().orElseThrow());
		ProductionComputer removed = fixture.be(LOW);
		fixture.remove(LOW);
		removed.owner().invalidate();
		fixture.snapshot = snapshotAt(2, List.of(profiledNode(HIGH, pos(2))));
		fixture.resetProbes();

		fixture.be(HIGH).scheduledTopologyTick(1, fixture, LIMITS);

		assertEquals(1, fixture.scanCalls);
		assertEquals(ComputerAvailabilityReason.COORDINATOR_MISSING,
			fixture.be(HIGH).availabilityReason());
		assertEquals(frozen, ProductionEpochCodec.save(fixture.be(HIGH).epoch().orElseThrow()));
	}

	@Test
	void sableAddressOrSpaceMismatchUsesDiscoveryInsteadOfPersistedLease() {
		Fixture addressMismatch = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		addressMismatch.refresh(LOW);
		addressMismatch.resetProbes();
		addressMismatch.addressOverrides.put(pos(2), address(pos(2).above()));
		addressMismatch.be(HIGH).scheduledTopologyTick(0, addressMismatch, LIMITS);
		assertEquals(1, addressMismatch.scanCalls);

		Fixture spaceMismatch = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		spaceMismatch.refresh(LOW);
		spaceMismatch.resetProbes();
		spaceMismatch.wrongSpacePositions.add(pos(2));
		spaceMismatch.be(HIGH).scheduledTopologyTick(0, spaceMismatch, LIMITS);
		assertEquals(1, spaceMismatch.scanCalls);
	}

	@Test
	void maxRevisionChangedReconcileMarksPersistenceInvalidWithoutRewritingRecords() {
		Fixture fixture = fixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.refresh(LOW);
		installMaximumRevision(fixture);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		fixture.snapshot = withAdditionalInteriorCasing(fixture.snapshot);

		fixture.refresh(LOW);

		assertEquals(ComputerAvailabilityReason.PERSISTENCE_INVALID,
			fixture.be(LOW).availabilityReason());
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void maxRevisionChangedCloseReturnsPersistenceInvalidWithoutStaging() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		installMaximumRevision(fixture);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		fixture.snapshot = withAdditionalInteriorCasing(fixture.snapshot);

		assertEquals(ProductionComputer.EpochCloseResult.PERSISTENCE_INVALID,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void maxRevisionReformRefusesBeforeStoppingRuntime() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		installMaximumRevision(fixture);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeReform control = ProbeReform.ready();

		assertEquals(ProductionComputer.ReformResult.PERSISTENCE_INVALID,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), fixture.epochId(),
				control, fixture, LIMITS));
		assertEquals(List.of(), control.events);
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void naturalLastRootCompletionClosesIdleFaultFreeEpochAtomically() {
		var other = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), new NodeSpec(HIGH, pos(2), other));
		UUID member = fixture.be(LOW).computerStructureMemberId().orElseThrow();
		ClusterBinding binding = fixture.be(LOW).bindingState();
		Map<UUID, byte[]> residents = residentBytes(fixture);
		ProbeQuiescence quiescence = ProbeQuiescence.ready();
		UUID epochId = fixture.be(LOW).epoch().orElseThrow().epochId();

		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
			ComputerTopologyController.closeIdleEpoch(fixture.be(HIGH), epochId,
				quiescence, fixture, LIMITS));

		for (ProductionComputer computer : fixture.computers.values()) {
			assertTrue(computer.epoch().isEmpty());
			assertEquals(Set.of(), computer.latchedEpochFaults());
			assertEquals(member, computer.computerStructureMemberId().orElseThrow());
			assertEquals(binding, computer.bindingState());
			assertArrayEquals(residents.get(computer.computerId().orElseThrow()),
				computer.owner().serializedResident(REGISTRIES));
		}
		assertEquals(List.of("roots", "idle:" + LOW, "rootFree:" + LOW,
			"mailbox:" + LOW, "idle:" + HIGH, "rootFree:" + HIGH,
			"mailbox:" + HIGH), quiescence.events);
		assertEquals(PROFILE, fixture.be(LOW).installedProfile().orElseThrow());
		assertEquals(other, fixture.be(HIGH).installedProfile().orElseThrow());
	}

	@Test
	void closeRefusesReentryAndReleasesItsGuardAfterAQuiescenceException() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		var before = bytes(fixture);
		var expectedEpoch = fixture.epochId();
		var calls = new ArrayList<ProductionComputer.EpochCloseResult>();
		var failing = new ProbeQuiescence() {
			@Override public boolean allRootsStopped() {
				calls.add(ComputerTopologyController.closeIdleEpoch(fixture.be(HIGH), expectedEpoch,
					ProbeQuiescence.ready(), fixture, LIMITS));
				throw new IllegalStateException("quiescence probe unavailable");
			}
		};
		assertThrows(IllegalStateException.class, () -> ComputerTopologyController.closeIdleEpoch(
			fixture.be(LOW), expectedEpoch, failing, fixture, LIMITS));
		assertEquals(List.of(ProductionComputer.EpochCloseResult.RUNTIME_NOT_QUIESCENT), calls);
		assertEquals(before, bytes(fixture));
		assertEquals(ProductionComputer.EpochCloseResult.CLOSED, ComputerTopologyController.closeIdleEpoch(
			fixture.be(HIGH), expectedEpoch, ProbeQuiescence.ready(), fixture, LIMITS));
	}

	@Test
	void sameEpochIdDivergentReplicaEpochRefusesNormalCloseByteIdentically() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		installEpochMissingHigh(fixture, LOW);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);

		assertEquals(ProductionComputer.EpochCloseResult.IDENTITY_CONFLICT,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void detachedCallerRefusesNormalCloseBeforeQuiescenceAndWithoutMutation() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer detached = fixture.replaceLoadedObject(LOW);
		CompoundTag detachedBefore = save(detached);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeQuiescence quiescence = ProbeQuiescence.ready();

		assertEquals(ProductionComputer.EpochCloseResult.IDENTITY_CONFLICT,
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

		assertEquals(ProductionComputer.EpochCloseResult.EPOCH_MISMATCH,
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
		fixture.revalidationSnapshot = new ProductionStructure(new ProductionLayout(fixture.snapshot.layout().bounds(), fixture.snapshot.layout().members(), List.of()), changedCasing, fixture.snapshot.containingChunks());
		fixture.scanCalls = 0;

		assertEquals(ProductionComputer.EpochCloseResult.IDENTITY_CONFLICT,
			ComputerTopologyController.closeIdleEpoch(fixture.be(LOW), fixture.epochId(),
				ProbeQuiescence.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void closeRejectsWorldInstanceChangesDuringQuiescenceWithoutChangingProductionState() {
		for (boolean replaceMember : List.of(true,false)) {
			Fixture fixture = activeFixture(profiled(LOW,pos(0)),profiled(HIGH,pos(2)));
			var before = bytes(fixture);
			var quiescence = new ProbeQuiescence() {
				@Override public boolean allRootsStopped() {
					if (replaceMember) fixture.replaceLoadedObject(HIGH);
					else {
						var previouslyEmpty = new BlockPos(1,1,1);
						fixture.byPosition.put(previouslyEmpty,computer(uuid(88),previouslyEmpty,PROFILE));
					}
					return super.allRootsStopped();
				}
			};
			assertEquals(ProductionComputer.EpochCloseResult.IDENTITY_CONFLICT,
				ComputerTopologyController.closeIdleEpoch(fixture.be(LOW),fixture.epochId(),quiescence,fixture,LIMITS));
			assertEquals(before,bytes(fixture));
		}
	}

	@Test
	void widthOrDepthRejectsNormalCloseAsRequiresReform() {
		for (EpochFault fault : EpochFault.values()) {
			Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
			fixture.latch(fault);
			ProbeQuiescence quiescence = ProbeQuiescence.ready();
			fixture.resetProbes();
			assertEquals(ProductionComputer.EpochCloseResult.REQUIRES_REFORM,
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
		assertEquals(ProductionComputer.EpochCloseResult.PARTIAL_UNLOADED,
			ComputerTopologyController.closeIdleEpoch(unloaded.be(LOW), unloaded.epochId(),
				ProbeQuiescence.ready(), unloaded, LIMITS));
		assertEquals(beforeUnload, bytes(unloaded));

		Fixture wrongSpace = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		Map<UUID, CompoundTag> beforeSpace = bytes(wrongSpace);
		wrongSpace.wrongSpacePositions.add(pos(1));
		assertEquals(ProductionComputer.EpochCloseResult.SPACE_UNCERTAIN,
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

		assertEquals(ProductionComputer.EpochCloseResult.FROZEN_MEMBER_MISSING,
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
			ProductionComputer.EpochCloseResult expected = failure.equals("roots")
				? ProductionComputer.EpochCloseResult.ROOTS_REMAIN
				: ProductionComputer.EpochCloseResult.RUNTIME_NOT_QUIESCENT;
			assertEquals(expected, ComputerTopologyController.closeIdleEpoch(fixture.be(LOW),
				fixture.epochId(), probe, fixture, LIMITS));
			assertEquals(before, bytes(fixture));
		}
	}

	@Test
	void emptyPendingExtraCanCloseButLeavesInactiveStructureNotReady() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer pending = computer(LOW, pos(1), null);
		fixture.put(pending);
		fixture.snapshot = snapshot(List.of(profiledNode(MID, pos(0)), emptyNode(LOW, pos(1)),
			profiledNode(HIGH, pos(2))));
		fixture.scanState = ComputerStructureScanner.State.VALID_NOT_READY;
		fixture.refresh(LOW);

		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
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
		assertEquals(ProductionComputer.EpochCloseResult.CLOSED,
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
		var other = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), new NodeSpec(HIGH, pos(2), other));
		fixture.latch(EpochFault.WIDTH);
		UUID oldEpoch = fixture.epochId();
		ProbeReform control = ProbeReform.ready();

		assertEquals(ProductionComputer.ReformResult.REFORMED,
			ComputerTopologyController.stopAllAndReform(fixture.be(HIGH), oldEpoch,
				control, fixture, LIMITS));

		assertEquals("stop", control.events.getFirst());
		for (ProductionComputer computer : fixture.computers.values()) {
			assertEquals(Set.of(), computer.latchedEpochFaults());
			assertNotEquals(oldEpoch, computer.epoch().orElseThrow().epochId());
			assertEquals(computer.installedProfile().orElseThrow(), computer.epoch().orElseThrow().layout().members().stream()
				.filter(n -> n.computerId().equals(computer.computerId().orElseThrow())).findFirst().orElseThrow().profile());
		}
		assertEquals(PROFILE, fixture.be(LOW).installedProfile().orElseThrow());
		assertEquals(other, fixture.be(HIGH).installedProfile().orElseThrow());
	}

	@Test
	void reformRejectsReentryAndReleasesTheGuardWhenStoppingThrows() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.latch(EpochFault.WIDTH);
		var expectedEpoch = fixture.epochId();
		var before = bytes(fixture);
		var reentered = new ArrayList<ProductionComputer.ReformResult>();
		var failing = new ProbeReform() {
			@Override public boolean stopAllAndClear() {
				reentered.add(ComputerTopologyController.stopAllAndReform(fixture.be(HIGH), expectedEpoch,
					ProbeReform.ready(), fixture, LIMITS));
				throw new IllegalStateException("production stop unavailable");
			}
		};
		assertThrows(IllegalStateException.class, () -> ComputerTopologyController.stopAllAndReform(
			fixture.be(LOW), expectedEpoch, failing, fixture, LIMITS));
		assertEquals(List.of(ProductionComputer.ReformResult.RUNTIME_NOT_QUIESCENT), reentered);
		assertEquals(before, bytes(fixture));
		assertEquals(ProductionComputer.ReformResult.REFORMED, ComputerTopologyController.stopAllAndReform(
			fixture.be(HIGH), expectedEpoch, ProbeReform.ready(), fixture, LIMITS));
	}

	@Test
	void splitShellWithLiveFrozenComputerRefusesPermanentLossReformByteIdentically() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		fixture.latch(EpochFault.WIDTH);
		fixture.snapshot = snapshot(List.of(profiledNode(LOW, pos(0))));
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeReform control = ProbeReform.ready();

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
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

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), fixture.epochId(),
				ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
		assertResidentBytesEqual(residentsBefore, residentBytes(fixture));
	}

	@Test
	void higherRevisionNonMinimumCallerCannotAuthorReform() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer high = fixture.be(HIGH);
		ProductionStructure.Revision record = high.currentStructureRecord().orElseThrow();
		ProductionStructure.Revision ahead = new ProductionStructure.Revision(
			record.structureId(), record.revision() + 1,
			record.coordinatorId(), record.structure());
		high.applyTopologyState(ahead, high.bindingState(), true,
			high.epoch().orElseThrow(), high.latchedEpochFaults());
		Map<UUID, CompoundTag> before = bytes(fixture);

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(high, fixture.epochId(),
				ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void equalRevisionDivergentNonMinimumCallerCannotAuthorReform() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer high = fixture.be(HIGH);
		ProductionStructure.Revision record = high.currentStructureRecord().orElseThrow();
		Set<BlockPos> divergentCasing = new HashSet<>(record.structure().casingPositions());
		divergentCasing.add(new BlockPos(1, 1, 1));
		ProductionStructure divergentSnapshot = new ProductionStructure(new ProductionLayout(record.structure().layout().bounds(), record.structure().layout().members(), List.of()), divergentCasing, record.structure().containingChunks());
		ProductionStructure.Revision divergent = new ProductionStructure.Revision(
			record.structureId(), record.revision(),
			record.coordinatorId(), divergentSnapshot);
		high.applyTopologyState(divergent, high.bindingState(), true,
			high.epoch().orElseThrow(), high.latchedEpochFaults());
		Map<UUID, CompoundTag> before = bytes(fixture);

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
			ComputerTopologyController.stopAllAndReform(high, fixture.epochId(),
				ProbeReform.ready(), fixture, LIMITS));
		assertEquals(before, bytes(fixture));
	}

	@Test
	void detachedCallerRefusesReformBeforeStopAndWithoutMutation() {
		Fixture fixture = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer detached = fixture.replaceLoadedObject(LOW);
		CompoundTag detachedBefore = save(detached);
		Map<UUID, CompoundTag> before = bytes(fixture);
		Map<UUID, byte[]> residentsBefore = residentBytes(fixture);
		ProbeReform control = ProbeReform.ready();

		assertEquals(ProductionComputer.ReformResult.IDENTITY_CONFLICT,
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

		assertEquals(ProductionComputer.ReformResult.EPOCH_MISMATCH,
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

		assertEquals(ProductionComputer.ReformResult.REFORMED,
			ComputerTopologyController.stopAllAndReform(fixture.be(LOW), oldEpoch,
				ProbeReform.ready(), fixture, LIMITS));

		ProductionEpoch replacement = fixture.be(LOW).epoch().orElseThrow();
		assertNotEquals(oldEpoch, replacement.epochId());
		assertEquals(member, replacement.structureId());
		assertEquals(List.of(LOW), replacement.layout().members().stream().map(ProductionLayout.Member::computerId).toList());
	}

	@Test
	void unloadedOrSpaceUncertainFrozenMemberCannotReformOrClearFaults() {
		Fixture unloaded = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		unloaded.latch(EpochFault.DEPTH);
		Map<UUID, CompoundTag> unloadedBefore = bytes(unloaded);
		unloaded.unloadedPositions.add(pos(2));
		assertEquals(ProductionComputer.ReformResult.PARTIAL_UNLOADED,
			ComputerTopologyController.stopAllAndReform(unloaded.be(LOW), unloaded.epochId(),
				ProbeReform.ready(), unloaded, LIMITS));
		assertEquals(unloadedBefore, bytes(unloaded));

		Fixture wrongSpace = activeFixture(profiled(LOW, pos(0)), profiled(HIGH, pos(2)));
		wrongSpace.latch(EpochFault.WIDTH);
		Map<UUID, CompoundTag> spaceBefore = bytes(wrongSpace);
		wrongSpace.wrongSpacePositions.add(pos(2));
		assertEquals(ProductionComputer.ReformResult.SPACE_UNCERTAIN,
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
			ProductionComputer.ReformResult expected = switch (failure) {
				case "stop" -> ProductionComputer.ReformResult.STOP_FAILED;
				case "roots" -> ProductionComputer.ReformResult.ROOTS_REMAIN;
				default -> ProductionComputer.ReformResult.RUNTIME_NOT_QUIESCENT;
			};
			assertEquals(expected, ComputerTopologyController.stopAllAndReform(fixture.be(LOW),
				fixture.epochId(), control, fixture, LIMITS));
			assertEquals(before, bytes(fixture));
		}
	}

	@Test
	void accessorsReturnImmutableCurrentFrozenAndPendingViewsWithPendingFlag() {
		Fixture fixture = activeFixture(profiled(MID, pos(0)), profiled(HIGH, pos(2)));
		ProductionComputer pending = computer(LOW, pos(1), PROFILE);
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
		UUID owner = fixture.snapshot.layout().members().getFirst().computerId();
		fixture.refresh(owner);
		UUID cluster = uuid(500);
		ClusterBinding binding = new ClusterBinding(cluster, 0, null, List.of());
		for (ProductionComputer computer : fixture.computers.values()) {
			ProductionStructure.Revision record = computer.currentStructureRecord().orElseThrow();
			computer.applyTopologyState(record, binding, true, null, Set.of());
		}
		assertEquals(ProductionComputer.EpochStartResult.STARTED,
			ComputerTopologyController.startEpoch(fixture.be(owner), cluster, fixture, LIMITS));
		return fixture;
	}

	private static List<TopologyProof> topologyProofs(Fixture fixture) {
		return fixture.computers.values().stream().map(ProductionComputer::topologyState)
			.map(state -> new TopologyProof(state.record(), state.binding(), state.bindingValid(),
				state.epoch(), state.faults())).toList();
	}

	private static Fixture panelEpochFixture(NodeSpec... specs) {
		Fixture fixture = fixture(specs.length == 0
			? new NodeSpec[] {profiled(LOW, pos(0))} : specs);
		List<ProductionLayout.Panel> panels = List.of(
			new ProductionLayout.Panel(uuid(44), address(new BlockPos(0, 1, 1)), Direction.WEST,
				ComputerShellMaterial.OPAQUE),
			new ProductionLayout.Panel(uuid(45), address(new BlockPos(2, 1, 1)), Direction.EAST,
				ComputerShellMaterial.TRANSPARENT));
		fixture.snapshot = snapshot(fixture.snapshot.layout().members(), panels);
		panels.forEach(fixture::installPanel);
		fixture.refresh(LOW);
		ProductionStructure.Revision record = fixture.be(LOW).currentStructureRecord().orElseThrow();
		ClusterBinding binding = new ClusterBinding(uuid(500), 1,
			new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
				record.structureId()), List.of());
		for (ProductionComputer computer : fixture.computers.values())
			computer.applyTopologyState(record, binding, true, null, Set.of());
		return fixture;
	}

	private static NodeSpec profiled(UUID id, BlockPos pos) { return new NodeSpec(id, pos, PROFILE); }
	private static NodeSpec empty(UUID id, BlockPos pos) { return new NodeSpec(id, pos, null); }
	private static ProductionLayout.Member profiledNode(UUID id, BlockPos pos) {
		return new ProductionLayout.Member(id, address(pos), PROFILE);
	}
	private static ProductionLayout.Member emptyNode(UUID id, BlockPos pos) {
		return new ProductionLayout.Member(id, address(pos), null);
	}

	private static ProductionComputer computer(UUID id, BlockPos pos,
		ProductionResourceProfile profile) {
		ProductionComputer computer = new ObservedComputer(pos).production();
		CompoundTag saved = save(computer);
		saved.getCompound("ComputerData").putUUID("ComputerId", id);
		physical(computer).read(saved, REGISTRIES, false);
		if (profile != null) physical(computer).installResidentSnapshot(new ItemStack(Items.PAPER), profile);
		return computer;
	}

	private static ProductionStructure snapshot(List<ProductionLayout.Member> nodes) {
		return snapshotAt(0, nodes, List.of());
	}

	private static final class ObservedComputer extends TestComputer {
		Runnable onTopologyChange = () -> {};
		ObservedComputer(BlockPos pos) { super(BlockEntityType.FURNACE, pos, Blocks.FURNACE.defaultBlockState()); }
		@Override public void sendData() {
			super.sendData();
			onTopologyChange.run();
		}
	}

	private static ProductionStructure snapshot(List<ProductionLayout.Member> nodes,
		List<ProductionLayout.Panel> panels) {
		return snapshotAt(0, nodes, panels);
	}

	private static ProductionStructure snapshotAt(int x, List<ProductionLayout.Member> nodes) {
		return snapshotAt(x, nodes, List.of());
	}

	private static ProductionStructure snapshotAt(int x, List<ProductionLayout.Member> nodes,
		List<ProductionLayout.Panel> panels) {
		BoundingBox bounds = new BoundingBox(x, 0, 0, x + 2, 2, 2);
		Set<BlockPos> casing = new HashSet<>();
		for (int px = bounds.minX(); px <= bounds.maxX(); px++)
			for (int y = 0; y <= 2; y++)
				for (int z = 0; z <= 2; z++)
					if (px == bounds.minX() || px == bounds.maxX() || y == 0 || y == 2
						|| z == 0 || z == 2) casing.add(new BlockPos(px, y, z));
		Set<ChunkPos> chunks = Set.of(new ChunkPos(bounds.minX() >> 4, 0));
		List<ProductionLayout.Member> sorted = nodes.stream()
			.sorted(java.util.Comparator.comparing(ProductionLayout.Member::computerId)).toList();
		return new ProductionStructure(new ProductionLayout(productionBounds(bounds), sorted, panels), casing, chunks);
	}

	private static Fixture maximumFixture(int count) {
		List<NodeSpec> specs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int x = 1 + i % 14;
			int y = 1 + i / 14 % 14;
			int z = 1 + i / (14 * 14);
			specs.add(profiled(new UUID(1, i + 1L), new BlockPos(x, y, z)));
		}
		Fixture fixture = fixture(specs.getFirst());
		for (int i = 1; i < specs.size(); i++) {
			NodeSpec spec = specs.get(i);
			fixture.put(computer(spec.id(), spec.pos(), spec.profile()));
		}
		BoundingBox bounds = new BoundingBox(0, 0, 0, 15, 15, 15);
		Set<BlockPos> casing = new HashSet<>();
		for (int x = 0; x <= 15; x++)
			for (int y = 0; y <= 15; y++)
				for (int z = 0; z <= 15; z++)
					if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15)
						casing.add(new BlockPos(x, y, z));
		List<ProductionLayout.Member> nodes = specs.stream()
			.map(spec -> new ProductionLayout.Member(spec.id(), address(spec.pos()), spec.profile()))
			.toList();
		fixture.snapshot = new ProductionStructure(new ProductionLayout(productionBounds(bounds), nodes, List.of()), casing, Set.of(new ChunkPos(0, 0)));
		return fixture;
	}

	private static ProductionStructure withAdditionalInteriorCasing(
		ProductionStructure snapshot) {
		Set<BlockPos> casing = new HashSet<>(snapshot.casingPositions());
		casing.add(new BlockPos(1, 1, 1));
		return new ProductionStructure(new ProductionLayout(snapshot.layout().bounds(), snapshot.layout().members(), List.of()), casing, snapshot.containingChunks());
	}

	private static void installMaximumRevision(Fixture fixture) {
		for (ProductionComputer computer : fixture.computers.values()) {
			ProductionStructure.Revision record = computer.currentStructureRecord().orElseThrow();
			computer.applyTopologyState(new ProductionStructure.Revision(
				record.structureId(), Long.MAX_VALUE, record.coordinatorId(),
				record.structure()), computer.bindingState(), computer.bindingStateValid(),
				computer.epoch().orElse(null), computer.latchedEpochFaults());
		}
	}

	private static BlockPos pos(int x) { return new BlockPos(x, 0, 0); }
	private static ProductionLayout.Address address(BlockPos pos) {
		return new ProductionLayout.Address(Level.OVERWORLD, null, pos);
	}
	private static UUID uuid(long value) { return new UUID(0, value); }

	private static CompoundTag save(ProductionComputer computer) {
		CompoundTag tag = new CompoundTag();
		physical(computer).write(tag, REGISTRIES, false);
		return tag;
	}

	private static Map<UUID, CompoundTag> bytes(Fixture fixture) {
		Map<UUID, CompoundTag> result = new LinkedHashMap<>();
		for (ProductionComputer computer : fixture.computers.values())
			result.put(computer.computerId().orElseThrow(), save(computer));
		return result;
	}

	private static Map<UUID, byte[]> residentBytes(Fixture fixture) {
		Map<UUID, byte[]> result = new HashMap<>();
		for (ProductionComputer computer : fixture.computers.values())
			result.put(computer.computerId().orElseThrow(), computer.owner().serializedResident(REGISTRIES));
		return result;
	}

	private static void assertResidentBytesEqual(Map<UUID, byte[]> expected,
		Map<UUID, byte[]> actual) {
		assertEquals(expected.keySet(), actual.keySet());
		for (UUID id : expected.keySet()) assertArrayEquals(expected.get(id), actual.get(id));
	}

	private static void installEpochMissingHigh(Fixture fixture, UUID target) {
		ProductionComputer computer = fixture.be(target);
		ProductionEpoch full = computer.epoch().orElseThrow();
		ProductionLayout.Member low = full.layout().members().stream().filter(node -> node.computerId().equals(LOW))
			.findFirst().orElseThrow();
		ProductionEpoch divergent = new ProductionEpoch(full.epochId(), full.groupId(), full.structureId(), LOW, new ProductionLayout(full.layout().bounds(), List.of(low), List.of()));
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

	private record NodeSpec(UUID id, BlockPos pos, ProductionResourceProfile profile) {}
	private record TopologyProof(@javax.annotation.Nullable ProductionStructure.Revision record,
		@javax.annotation.Nullable ClusterBinding binding, boolean bindingValid,
		@javax.annotation.Nullable ProductionEpoch epoch, Set<EpochFault> faults) {}
	private record PanelPublication(ClusterMemberType type, UUID panelId, UUID clusterId,
		UUID epochId) {}

	private static final class Fixture implements ComputerTopologyController.WorldAccess {
		private final Map<UUID, ProductionComputer> computers = new LinkedHashMap<>();
		private final Map<BlockPos, ProductionComputer> byPosition = new HashMap<>();
		private final Map<BlockPos, ProductionLayout.Panel> panels = new HashMap<>();
		private final Set<BlockPos> unloadedPositions = new HashSet<>();
		private final Set<BlockPos> wrongSpacePositions = new HashSet<>();
		private final Set<ChunkPos> unloadedChunks = new HashSet<>();
		private final Map<BlockPos, ProductionStructure> scanSnapshotsBySeed = new HashMap<>();
		private final Map<BlockPos, ProductionLayout.Address> addressOverrides = new HashMap<>();
		private final UUID generatedMemberId = uuid(600);
		private ProductionStructure snapshot;
		private ProductionStructure revalidationSnapshot;
		private ComputerStructureScanner.State scanState;
		private int scanCalls;
		private int loadedCalls;
		private int sameSpaceCalls;
		private int computerCalls;
		private final List<PanelPublication> panelPublications = new ArrayList<>();
		private final Map<UUID, UUID> publishedPanelEpochs = new HashMap<>();
		private final Map<UUID, Set<ProductionEpoch>> panelEpochViews = new HashMap<>();
		private int panelPublishAttempts;
		private int failPanelPublishAttempt = Integer.MAX_VALUE;
		private final Set<Integer> failedPanelPublishAttempts = new HashSet<>();
		private Runnable beforePanelPublish = () -> {};

		private Fixture(NodeSpec... specs) {
			List<ProductionLayout.Member> nodes = new ArrayList<>();
			for (NodeSpec spec : specs) {
				ProductionComputer computer = computer(spec.id(), spec.pos(), spec.profile());
				put(computer);
				nodes.add(new ProductionLayout.Member(spec.id(), address(spec.pos()), spec.profile()));
			}
			snapshot = snapshot(nodes);
			scanState = nodes.stream().anyMatch(node -> node.profile() == null)
				? ComputerStructureScanner.State.VALID_NOT_READY
				: ComputerStructureScanner.State.VALID;
		}

		private void put(ProductionComputer computer) {
			UUID id = computer.computerId().orElseThrow();
			computers.put(id, computer);
			byPosition.put(computer.owner().getBlockPos(), computer);
		}

		private ProductionComputer be(UUID id) { return Objects.requireNonNull(computers.get(id)); }
		private void installPanel(ProductionLayout.Panel panel) {
			panels.put(panel.address().localPos(), panel);
		}

		private void remove(UUID id) {
			ProductionComputer removed = computers.remove(id);
			if (removed != null) byPosition.remove(removed.owner().getBlockPos());
		}

		private void move(UUID id, BlockPos target) {
			ProductionComputer old = be(id);
			ProductionResourceProfile profile = old.installedProfile().orElse(null);
			ProductionComputer moved = computer(id, target, profile);
			ProductionStructure.Revision record = old.currentStructureRecord().orElse(null);
			moved.applyTopologyState(record, old.bindingState(), old.bindingStateValid(),
				old.epoch().orElse(null), old.latchedEpochFaults());
			remove(id);
			put(moved);
		}

		private ProductionComputer replaceLoadedObject(UUID id) {
			ProductionComputer detached = be(id);
			ProductionComputer replacement = TestComputer.create(BlockEntityType.FURNACE,
				detached.owner().getBlockPos(), Blocks.FURNACE.defaultBlockState());
			physical(replacement).read(save(detached), REGISTRIES, false);
			put(replacement);
			return detached;
		}

		private void refresh(UUID caller) {
			ComputerTopologyController.refresh(be(caller), this, LIMITS);
		}

		private UUID epochId() { return computers.values().iterator().next().epoch().orElseThrow().epochId(); }

		private void latch(EpochFault fault) {
			ProductionComputer caller = computers.values().iterator().next();
			assertEquals(ProductionComputer.FaultLatchResult.LATCHED,
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
			ProductionStructure current = scanCalls == 2 && revalidationSnapshot != null
				? revalidationSnapshot : scanSnapshotsBySeed.getOrDefault(seed, snapshot);
			return new ComputerStructureScanner.ScanResult(scanState,
				(scanState == ComputerStructureScanner.State.VALID
					|| scanState == ComputerStructureScanner.State.VALID_NOT_READY) ? current : null,
				scanState == ComputerStructureScanner.State.IDENTITY_INVALID ? null
					: computers.values().stream().map(ProductionComputer::computerStructureMemberId)
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

		@Override public ProductionComputer loadedComputer(BlockPos pos) {
			computerCalls++;
			if (unloadedPositions.contains(pos) || wrongSpacePositions.contains(pos))
				throw new AssertionError("BE read after failed loaded/same-space gate");
			return byPosition.get(pos);
		}

		@Override public ProductionLayout.Address address(BlockPos pos) {
			return addressOverrides.getOrDefault(pos, ComputerTopologyControllerTest.address(pos));
		}
		@Override public UUID newStructureMemberId() { return generatedMemberId; }
		@Override public boolean publishPanel(ProductionLayout.Panel panel,
			ClusterBinding binding, ProductionEpoch epoch) {
			beforePanelPublish.run();
			if (++panelPublishAttempts == failPanelPublishAttempt
				|| failedPanelPublishAttempts.contains(panelPublishAttempts)) return false;
			panelPublications.add(new PanelPublication(ClusterMemberType.PANEL, panel.panelId(),
				binding.clusterId(), epoch.epochId()));
			publishedPanelEpochs.put(panel.panelId(), epoch.epochId());
			return true;
		}
		@Override public boolean panelPublishedFor(ProductionLayout.Panel panel, ProductionEpoch epoch) {
			panelEpochViews.computeIfAbsent(epoch.epochId(), key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(epoch);
			return epoch.epochId().equals(publishedPanelEpochs.get(panel.panelId()));
		}
		@Override public void withdrawPanel(ProductionLayout.Panel panel) {
			publishedPanelEpochs.remove(panel.panelId());
		}
		@Override public ProductionLayout.Panel productionPanel(BlockPos pos) { return panels.get(pos); }
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

	private static class ProbeReform extends ProbeQuiescence implements EpochReformControl {
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
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
