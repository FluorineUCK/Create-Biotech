package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerCardProjection.AggregateState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorProjection.Status;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorProjectionService.Query;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorProjectionService.Viewport;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.GoalEvidence;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.InventoryEvidence;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.ProcessEvidence;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.CaptureFacts;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorSnapshot.PublishedSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection.RemoteState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.ExecutionState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNode;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNodeKind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ComputerMonitorProjectionServiceTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), PANEL = id(3), SESSION = id(4);
	private static final UUID COMPUTER_A = id(10), COMPUTER_B = id(11), GOAL = id(20);
	private static final UUID PATTERN = id(21), PROCESS_A = id(30), PROCESS_B = id(31);
	private static final UUID NETWORK = id(40), REMOTE_CLUSTER = id(999);
	private static final SpaceAddress PANEL_ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(1, 64, 1));
	private static final SpaceAddress CREATE_ADDRESS = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(8, 70, 9));

	@Test
	void exactFrozenLevelAndIndependentViewportsAreCompleteWithoutStarvation() {
		PublishedSnapshot snapshot = snapshot();
		var service = service(snapshot, 8);
		Query query = query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A),
			0, 2, 13, 2, 2, 3);

		ComputerMonitorProjection projection = service.project(
			authority(OptionalLong.of(snapshot.viewRevision()), 2, 8,
				Optional.of(PROCESS_A), 8), query);

		assertEquals(5, projection.computers().getFirst().residentLevel());
		assertEquals(List.of(13, 14), projection.slots().stream()
			.map(ComputerSlotProjection::slotIndex).toList());
		assertTrue(projection.hasMoreSlots());
		assertEquals(2, projection.goalTree().orElseThrow().offset());
		assertEquals(3, projection.goalTree().orElseThrow().nodes().size());
		assertTrue(projection.goalTree().orElseThrow().parentProcessContext().isPresent());
		assertEquals(8, projection.returnedRowCount());

		Query continuation = query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A),
			2, 0, 16, 0, 5, 3);
		var continued = service.project(
			authority(OptionalLong.of(snapshot.viewRevision()), 5, 4,
				Optional.of(PROCESS_A), 8), continuation);
		assertTrue(continued.computers().isEmpty());
		assertTrue(continued.slots().isEmpty());
		assertEquals(5, continued.goalTree().orElseThrow().offset());

		Query slotOnly = query(Optional.of(COMPUTER_A), Optional.empty(),
			0, 0, 13, 3, 0, 0);
		var slotContinuation = service.project(
			authority(OptionalLong.of(snapshot.viewRevision()), 13, 3,
				Optional.of(COMPUTER_A), 8), slotOnly);
		assertEquals(List.of(13, 14, 15), slotContinuation.slots().stream()
			.map(ComputerSlotProjection::slotIndex).toList());

		assertThrows(IllegalArgumentException.class, () -> service.project(
			authority(OptionalLong.of(snapshot.viewRevision()), 0, 8,
				Optional.of(PROCESS_A), 8),
			query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A), 0, 3, 0, 3, 0, 3)));
	}

	@Test
	void explicitActiveLaneKeepsAllThreePagesWhileDeeperSelectionExists() {
		PublishedSnapshot snapshot = snapshot();
		var service = service(snapshot, 8);
		for (ComputerMonitorViewRequest.ActiveViewport active
			: ComputerMonitorViewRequest.ActiveViewport.values()) {
			ComputerMonitorViewRequest monitor = new ComputerMonitorViewRequest(active,
				Optional.of(COMPUTER_A), Optional.of(PROCESS_A),
				new ComputerMonitorViewRequest.Viewport(0, 2),
				new ComputerMonitorViewRequest.Viewport(13, 2),
				new ComputerMonitorViewRequest.Viewport(2, 3));
			ComputerMonitorProjection projection = service.project(authority(
				OptionalLong.of(snapshot.viewRevision()), monitor, 8));
			assertEquals(0, projection.offset());
			assertEquals(13, projection.slotOffset());
			assertEquals(2, projection.goalTree().orElseThrow().offset());
		}
	}

	@Test
	void oneRowScrolledTreePageReturnsTheRequestedContextFreeLeaf() {
		PublishedSnapshot snapshot = snapshot();
		ComputerMonitorViewRequest monitor = new ComputerMonitorViewRequest(
			ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE, Optional.of(COMPUTER_A),
			Optional.of(PROCESS_A), new ComputerMonitorViewRequest.Viewport(0, 0),
			new ComputerMonitorViewRequest.Viewport(0, 0),
			new ComputerMonitorViewRequest.Viewport(2, 1), false);

		ComputerMonitorProjection projection = service(snapshot, 1).project(authority(
			OptionalLong.of(snapshot.viewRevision()), monitor, 1));

		assertEquals(Status.CURRENT, projection.status());
		assertEquals(2, projection.goalTree().orElseThrow().offset());
		assertEquals(1, projection.goalTree().orElseThrow().nodes().size());
		assertEquals(TreeNodeKind.LIBRARY,
			projection.goalTree().orElseThrow().nodes().getFirst().kind());
		assertTrue(projection.goalTree().orElseThrow().parentProcessContext().isEmpty());
		assertEquals(1, projection.returnedRowCount());
	}

	@Test
	void staleRetryNormalizesShrunkOffsetsAndClearsRemovedProcess() {
		var sequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot before = sequence.publish(capture("minecraft:iron_ingot"));
		PublishedSnapshot after = sequence.publish(captureWithoutProcessA());
		var service = service(after, 4);
		ComputerMonitorViewRequest retained = new ComputerMonitorViewRequest(
			ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE, Optional.of(COMPUTER_A),
			Optional.of(PROCESS_A), new ComputerMonitorViewRequest.Viewport(2, 1),
			new ComputerMonitorViewRequest.Viewport(16, 1),
			new ComputerMonitorViewRequest.Viewport(5, 1));

		assertEquals(Status.STALE, service.project(authority(OptionalLong.of(before.viewRevision()),
			retained, 4)).status());
		ComputerMonitorProjection recovered = service.project(authority(
			OptionalLong.of(after.viewRevision()), retained, 4));

		assertEquals(Status.CURRENT, recovered.status());
		assertEquals(1, recovered.offset());
		assertEquals(Optional.of(COMPUTER_A), recovered.selectedComputerId());
		assertEquals(15, recovered.slotOffset());
		assertTrue(recovered.selectedProcessId().isEmpty());
		assertTrue(recovered.goalTree().isEmpty());
	}

	@Test
	void staleRetryClearsRemovedComputerAndEveryDependentLane() {
		var sequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot before = sequence.publish(capture("minecraft:iron_ingot"));
		PublishedSnapshot after = sequence.publish(captureOnlyComputerB());
		var service = service(after, 4);
		ComputerMonitorViewRequest retained = new ComputerMonitorViewRequest(
			ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE, Optional.of(COMPUTER_A),
			Optional.of(PROCESS_A), new ComputerMonitorViewRequest.Viewport(2, 1),
			new ComputerMonitorViewRequest.Viewport(16, 1),
			new ComputerMonitorViewRequest.Viewport(5, 1));

		assertEquals(Status.STALE, service.project(authority(OptionalLong.of(before.viewRevision()),
			retained, 4)).status());
		ComputerMonitorProjection recovered = service.project(authority(
			OptionalLong.of(after.viewRevision()), retained, 4));

		assertEquals(Status.CURRENT, recovered.status());
		assertEquals(0, recovered.offset());
		assertTrue(recovered.selectedComputerId().isEmpty());
		assertEquals(0, recovered.slotOffset());
		assertTrue(recovered.selectedProcessId().isEmpty());
		assertTrue(recovered.goalTree().isEmpty());
	}

	@Test
	void residentLevelIsMandatoryAndNeverInferredFromSharedSlotCurve() {
		ClusterEngineState state = formedState();
		assertThrows(IllegalArgumentException.class, () -> new CaptureFacts(state,
			Map.of(), goalEvidence("minecraft:iron_ingot"), processEvidence()));
		assertEquals(5, snapshot().residentLevels().get(COMPUTER_A));
	}

	@Test
	void combinedSnapshotIsCapturedOnceAndItsRevisionIncludesDisplayEvidence() {
		AtomicInteger captures = new AtomicInteger();
		var sequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot previousEvidence = sequence.publish(capture("minecraft:iron_ingot"));
		PublishedSnapshot newerEvidence = sequence.publish(capture("minecraft:gold_ingot"));
		var service = new ComputerMonitorProjectionService(cluster -> {
			assertEquals(CLUSTER, cluster);
			captures.incrementAndGet();
			return Optional.of(newerEvidence);
		}, 8);

		ComputerMonitorProjection stale = service.project(
			authority(OptionalLong.of(previousEvidence.viewRevision()), 0, 2,
				Optional.empty(), 8),
			query(Optional.empty(), Optional.empty(), 0, 2, 0, 0, 0, 0));

		assertEquals(Status.STALE, stale.status());
		assertEquals(newerEvidence.viewRevision(), stale.revision());
		assertEquals(1, captures.get());

		UUID zeroEpoch = id(7_001);
		PublishedSnapshot zeroSnapshot = new ComputerMonitorSnapshot.RevisionSequence()
			.publish(capture(zeroEpoch, "minecraft:iron_ingot"));
		assertEquals(0, zeroSnapshot.viewRevision());
		ComputerMonitorProjection exactZero = service(zeroSnapshot, 8).project(
			authority(zeroEpoch, OptionalLong.of(0), 0, 2, Optional.empty(), 8),
			query(Optional.empty(), Optional.empty(), 0, 2, 0, 0, 0, 0));
		assertEquals(Status.CURRENT, exactZero.status());
	}

	@Test
	void revisionSequenceAdvancesForAnyCombinedEvidenceChangeButNotIdenticalCapture() {
		var sequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot first = sequence.publish(capture("minecraft:iron_ingot"));
		PublishedSnapshot unchanged = sequence.publish(capture("minecraft:iron_ingot"));
		PublishedSnapshot changed = sequence.publish(capture("minecraft:gold_ingot"));

		assertEquals(first.viewRevision(), unchanged.viewRevision());
		assertEquals(ComputerMonitorSnapshot.RevisionSequence.nextRevision(first.viewRevision()),
			changed.viewRevision());
		assertThrows(IllegalStateException.class, () ->
			ComputerMonitorSnapshot.RevisionSequence.nextRevision(Long.MAX_VALUE));
	}

	@Test
	void snapshotBoundaryRedactsRemoteUuidFromEveryRawDisplayTextField() {
		String leaked = REMOTE_CLUSTER.toString();
		Map<UUID, ProcessEvidence> processes = Map.of(
			PROCESS_A, new ProcessEvidence("Library " + leaked, Optional.of(CREATE_ADDRESS),
				List.of(new InventoryEvidence("inventory/" + leaked, 3)), true, false, true,
				OptionalInt.of(2), Optional.of("alias/" + leaked), RemoteState.RUNNING),
			PROCESS_B, new ProcessEvidence(leaked, Optional.empty(), List.of(), false, true,
				false, OptionalInt.of(4), Optional.of(leaked), RemoteState.WAITING));
		var snapshot = new ComputerMonitorSnapshot.RevisionSequence().publish(new CaptureFacts(
			formedState(), residentLevels(), goalEvidence("output/" + leaked), processes));

		ComputerMonitorProjection projection = service(snapshot, 64).project(
			authority(OptionalLong.of(snapshot.viewRevision()), 0, 32,
				Optional.of(PROCESS_A), 64),
			query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A), 0, 2, 0, 2, 0, 28));

		assertFalse(projection.toString().contains(leaked));
		assertTrue(projection.toString().contains("[redacted]"));
		TreeNode directDetail = new TreeNode(1, TreeNodeKind.PROCESS, "output/" + leaked,
			1, "", Optional.empty(), ExecutionState.RUN,
			Optional.of(ComputerCardProjection.shortId(COMPUTER_A)), 0, Optional.empty(),
			Optional.of("detail/" + leaked));
		assertFalse(directDetail.toString().contains(leaked));
		assertTrue(directDetail.detail().orElseThrow().contains("[redacted]"));
	}

	@Test
	void projectionsRejectImpossibleSlotAndTypedTreeStructures() {
		ComputerCardProjection card = new ComputerCardProjection(COMPUTER_A,
			ProductionProfession.LIBRARIAN, 5, 1, 16, AggregateState.SLP, 1);
		assertThrows(IllegalArgumentException.class, () -> new ComputerMonitorProjection(
			CLUSTER, EPOCH, 1, Status.CURRENT, 0, 1, List.of(card), Optional.of(COMPUTER_A),
			4, 16, List.of(ComputerSlotProjection.empty(5), ComputerSlotProjection.empty(5)),
			Optional.empty(), Optional.empty(), 8));
		assertThrows(IllegalArgumentException.class, () -> new TreeNode(2,
			TreeNodeKind.CREATE_ADDRESS, "minecraft:iron", 1, "", Optional.empty(),
			ExecutionState.RUN, Optional.of(ComputerCardProjection.shortId(COMPUTER_A)), 0,
			Optional.empty(), Optional.empty()));
		assertThrows(IllegalArgumentException.class, () -> new TreeNode(1,
			TreeNodeKind.PROCESS, "minecraft:iron", 1, "", Optional.empty(), ExecutionState.RUN,
			Optional.of(COMPUTER_A.toString()), 0, Optional.empty(), Optional.of("kind=GOAL")));
		assertThrows(IllegalArgumentException.class, () -> new TreeNode(2,
			TreeNodeKind.LOGISTICS_ALIAS, "minecraft:iron", 1, "", Optional.empty(),
			ExecutionState.RUN, Optional.of(ComputerCardProjection.shortId(COMPUTER_A)), 0,
			Optional.empty(), Optional.empty()));
	}

	@Test
	void opaqueAuthorityFencesSelectionCardOffsetAndCumulativeWireBudget() {
		var service = service(snapshot(), 8);
		assertThrows(IllegalArgumentException.class, () -> service.project(
			authority(OptionalLong.empty(), 0, 8, Optional.of(COMPUTER_A), 8),
			query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A), 0, 2, 0, 2, 0, 2)));
		assertThrows(IllegalArgumentException.class, () -> service.project(
			authority(OptionalLong.empty(), 0, 8, Optional.of(PROCESS_A), 8),
			query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A), 0, 2, 0, 2, 1, 2)));
		assertThrows(IllegalArgumentException.class, () -> service.project(
			authority(OptionalLong.empty(), 0, 8, Optional.empty(), 8),
			query(Optional.empty(), Optional.empty(), 0, 2, 0, 1, 0, 0)));
	}

	@Test
	void monitorPublicApiCannotForgeARevisionBearingSnapshot() {
		assertEquals(0, Arrays.stream(ComputerMonitorSnapshot.class.getConstructors()).count());
		assertEquals(0, Arrays.stream(PublishedSnapshot.class.getConstructors()).count());
	}

	@Test
	void serviceRejectsSupersededPublicationAndAcceptsAuthoritativeSequenceRebuild() {
		UUID rebuiltEpoch = id(5_001);
		var firstSequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot first = firstSequence.publish(capture(rebuiltEpoch,
			"minecraft:iron_ingot"));
		AtomicReference<PublishedSnapshot> lookup = new AtomicReference<>(first);
		var service = new ComputerMonitorProjectionService(
			ignored -> Optional.of(lookup.get()), 2);
		Query query = query(Optional.empty(), Optional.empty(), 0, 2, 0, 0, 0, 0);
		assertEquals(Status.CURRENT, service.project(
			authority(rebuiltEpoch, OptionalLong.of(first.viewRevision()), 0, 2,
				Optional.empty(), 2), query).status());

		var replacementSequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot changed = replacementSequence.publish(capture(rebuiltEpoch,
			"minecraft:gold_ingot"));
		lookup.set(changed);
		assertEquals(Status.CURRENT, service.project(authority(rebuiltEpoch,
			OptionalLong.of(changed.viewRevision()), 0, 2, Optional.empty(), 2), query).status());
		assertThrows(IllegalStateException.class, () -> service(first, 2).project(
			authority(rebuiltEpoch, OptionalLong.of(first.viewRevision()), 0, 2,
				Optional.empty(), 2), query));
	}

	@Test
	void rebuiltSequenceAndServiceCannotReuseAnEpochRevisionForChangedFacts() {
		UUID rebuiltEpoch = id(4_242);
		var firstSequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot first = firstSequence.publish(capture(rebuiltEpoch,
			"minecraft:iron_ingot"));
		var firstService = service(first, 2);
		ComputerMonitorProjection current = firstService.project(
			authority(rebuiltEpoch, OptionalLong.empty(), 0, 2, Optional.empty(), 2),
			query(Optional.empty(), Optional.empty(), 0, 2, 0, 0, 0, 0));

		var rebuiltSequence = new ComputerMonitorSnapshot.RevisionSequence();
		PublishedSnapshot changed = rebuiltSequence.publish(capture(rebuiltEpoch,
			"minecraft:gold_ingot"));
		var rebuiltService = service(changed, 2);
		ComputerMonitorProjection stale = rebuiltService.project(
			authority(rebuiltEpoch, OptionalLong.of(current.revision()), 0, 2,
				Optional.empty(), 2),
			query(Optional.empty(), Optional.empty(), 0, 2, 0, 0, 0, 0));
		assertEquals(Status.STALE, stale.status());
		assertTrue(changed.viewRevision() > first.viewRevision());

		PublishedSnapshot sameChangedFacts = new ComputerMonitorSnapshot.RevisionSequence()
			.publish(capture(rebuiltEpoch, "minecraft:gold_ingot"));
		assertTrue(sameChangedFacts.viewRevision() > changed.viewRevision());
		assertEquals(Status.STALE, service(sameChangedFacts, 2).project(
			authority(rebuiltEpoch, OptionalLong.of(current.revision()), 0, 2,
				Optional.empty(), 2),
			query(Optional.empty(), Optional.empty(), 0, 2, 0, 0, 0, 0)).status());
	}

	@Test
	void sanitizerRedactsUuidAcrossUnicodeAndEmbeddedHexVariants() {
		String dashed = REMOTE_CLUSTER.toString();
		String compact = dashed.replace("-", "");
		List<String> variants = List.of(
			dashed,
			dashed.toUpperCase(),
			compact,
			compact.toUpperCase(),
			"a" + dashed,
			dashed + "f",
			"a" + compact,
			compact + "f",
			dashed.substring(0, 8) + "\n" + dashed.substring(8),
			compact.substring(0, 8) + "\t" + compact.substring(8),
			dashed.substring(0, 8) + "\u200B" + dashed.substring(8),
			dashed.substring(0, 8) + "\u200D" + dashed.substring(8),
			dashed.substring(0, 8) + "\u202E" + dashed.substring(8),
			compact.substring(0, 8) + "\uFE0F" + compact.substring(8),
			compact.substring(0, 8) + "\u034F" + compact.substring(8),
			dashed.replace('-', '\u2010'),
			dashed.replace('-', '\u2011'),
			dashed.replace('-', '\u2212'),
			dashed.replace('-', '\u2043'),
			dashed.replace('-', '\u02D7'),
			compact.substring(0, 8) + " / " + compact.substring(8, 16) + "…"
				+ compact.substring(16, 24) + "_" + compact.substring(24));
		for (String variant : variants) {
			String clean = ComputerMonitorSnapshot.safeText("before/" + variant + "/after");
			assertTrue(clean.contains("[redacted]"), () -> "not redacted: " + variant);
			assertFalse(clean.matches(".*(?i)[0-9a-f]{32,}.*"));
			assertFalse(clean.contains("\u200B"));
			assertFalse(clean.contains("\u200D"));
			assertFalse(clean.contains("\u202E"));
		}
		String interruptedByNonHexLetter = compact.substring(0, 16) + "g"
			+ compact.substring(16);
		assertFalse(ComputerMonitorSnapshot.safeText(interruptedByNonHexLetter)
			.contains("[redacted]"));
	}

	@Test
	void everyEngineLegalGoalLessProcessStillProjectsCardsAndSlotsButNoTree() {
		for (ProcessKind kind : List.of(ProcessKind.AGENT, ProcessKind.REMOTE_LISTENER,
			ProcessKind.RADIO_LISTENER, ProcessKind.LIBRARY_QUERY,
			ProcessKind.SPECULATIVE_EXPANSION, ProcessKind.PATTERN_PRIORITY_LEASE,
			ProcessKind.PRIORITY_LEASE)) {
			ClusterEngineState state = goalLessState(kind);
			PublishedSnapshot snapshot = new ComputerMonitorSnapshot.RevisionSequence().publish(
				new CaptureFacts(state,
				Map.of(COMPUTER_A, 5), Map.of(), Map.of(PROCESS_A,
					new ProcessEvidence("", Optional.empty(), List.of(), false, false,
						false, OptionalInt.empty(), Optional.empty(), RemoteState.NONE))));
			ComputerMonitorProjection projection = service(snapshot, 4).project(
				authority(OptionalLong.of(snapshot.viewRevision()), 0, 4,
					Optional.of(PROCESS_A), 4),
				query(Optional.of(COMPUTER_A), Optional.of(PROCESS_A), 0, 1, 0, 1, 0, 1));
			assertEquals(1, projection.computers().size());
			assertEquals(PROCESS_A, projection.slots().getFirst().processId().orElseThrow());
			assertTrue(projection.goalTreeUnavailable());
			assertTrue(projection.goalTree().isEmpty());
		}
	}

	@Test
	void projectionsRejectDuplicateProcessSlotsAndTreeLeavesOutsideParentAssignment() {
		ComputerSlotProjection first = ComputerSlotProjection.occupied(0, PROCESS_A,
			ProcessKind.GOAL, GOAL, "minecraft:iron", ProcessState.RUNNABLE,
			Optional.empty(), RemoteState.NONE, true);
		ComputerSlotProjection second = ComputerSlotProjection.occupied(1, PROCESS_A,
			ProcessKind.GOAL, GOAL, "minecraft:iron", ProcessState.RUNNABLE,
			Optional.empty(), RemoteState.NONE, true);
		assertThrows(IllegalArgumentException.class, () -> new ComputerMonitorProjection(
			CLUSTER, EPOCH, 0, Status.CURRENT, 0, 0, List.of(), Optional.of(COMPUTER_A),
			0, 2, List.of(first, second), Optional.empty(), Optional.empty(), 2));

		TreeNode root = node(0, TreeNodeKind.GOAL, Optional.empty(), -1);
		TreeNode process = node(1, TreeNodeKind.PROCESS,
			Optional.of(ComputerCardProjection.shortId(COMPUTER_A)), 0);
		TreeNode mismatchedLeaf = node(2, TreeNodeKind.INVENTORY,
			Optional.of(ComputerCardProjection.shortId(COMPUTER_B)), 1);
		assertThrows(IllegalArgumentException.class, () -> new GoalTreeProjection(GOAL,
			"minecraft:iron", 1, 0, 3, List.of(root, process, mismatchedLeaf)));
		GoalTreeProjection contextFree = new GoalTreeProjection(GOAL,
			"minecraft:iron", 1, 2, 3, List.of(mismatchedLeaf));
		assertEquals(List.of(mismatchedLeaf), contextFree.nodes());
	}

	private static TreeNode node(int depth, TreeNodeKind kind, Optional<String> assigned,
		int slot) {
		return new TreeNode(depth, kind, "minecraft:iron", 1, "", Optional.empty(),
			ExecutionState.RUN, assigned, slot, Optional.empty(),
			kind == TreeNodeKind.PROCESS ? Optional.of("kind=GOAL") : Optional.empty());
	}

	private static ClusterEngineState goalLessState(ProcessKind kind) {
		ProductionResourceProfile owner = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0)
			.orElseThrow();
		ProcessRecord process = new ProcessRecord(PROCESS_A, GOAL, COMPUTER_A, 0, owner,
			kind, ProcessState.RUNNABLE, null, 0, Map.of());
		return new ClusterEngineState(CLUSTER, EPOCH, 0, 0, 0, 0, Map.of(), Map.of(),
			Map.of(), Map.of(PROCESS_A, process),
			List.of(new ClusterEngineState.MemberSnapshot(COMPUTER_A, owner, true, 0, 0)),
			Map.of(), Set.of(), 0, null);
	}

	private static ComputerMonitorProjectionService service(PublishedSnapshot snapshot,
		int rowLimit) {
		return new ComputerMonitorProjectionService(ignored -> Optional.of(snapshot), rowLimit);
	}

	private static PublishedSnapshot snapshot() {
		return new ComputerMonitorSnapshot.RevisionSequence()
			.publish(capture("minecraft:iron_ingot"));
	}

	private static CaptureFacts capture(String output) {
		return new CaptureFacts(formedState(), residentLevels(), goalEvidence(output),
			processEvidence());
	}

	private static CaptureFacts capture(UUID epoch, String output) {
		return new CaptureFacts(formedState(epoch), residentLevels(), goalEvidence(output),
			processEvidence());
	}

	private static CaptureFacts captureWithoutProcessA() {
		ClusterEngineState base = formedState();
		ClusterEngineState changed = new ClusterEngineState(CLUSTER, EPOCH, 7, 1, 0, 0,
			base.goals(), base.submissionSequences(), base.requestedKinds(), Map.of(PROCESS_B,
				base.processes().get(PROCESS_B)), base.members(), Map.of(), Set.of(), 0, null);
		return new CaptureFacts(changed, residentLevels(), goalEvidence("minecraft:iron_ingot"),
			Map.of(PROCESS_B, processEvidence().get(PROCESS_B)));
	}

	private static CaptureFacts captureOnlyComputerB() {
		ClusterEngineState base = formedState();
		ClusterEngineState changed = new ClusterEngineState(CLUSTER, EPOCH, 7, 1, 0, 0,
			base.goals(), base.submissionSequences(), base.requestedKinds(), Map.of(PROCESS_B,
				base.processes().get(PROCESS_B)), List.of(base.member(COMPUTER_B)), Map.of(),
			Set.of(), 0, null);
		return new CaptureFacts(changed, Map.of(COMPUTER_B, 2),
			goalEvidence("minecraft:iron_ingot"),
			Map.of(PROCESS_B, processEvidence().get(PROCESS_B)));
	}

	private static Map<UUID, Integer> residentLevels() {
		return Map.of(COMPUTER_A, 5, COMPUTER_B, 2);
	}

	private static Map<UUID, GoalEvidence> goalEvidence(String output) {
		return Map.of(GOAL, new GoalEvidence(output, 12));
	}

	private static Map<UUID, ProcessEvidence> processEvidence() {
		return Map.of(
			PROCESS_A, new ProcessEvidence("Library Alpha", Optional.of(CREATE_ADDRESS),
				List.of(new InventoryEvidence("minecraft:copper_ingot", 3)), true, false, true,
				OptionalInt.of(2), Optional.of("Main Warehouse"), RemoteState.RUNNING),
			PROCESS_B, new ProcessEvidence("Library Alpha", Optional.empty(), List.of(), false,
				true, false, OptionalInt.of(4), Optional.of("Agent Input"), RemoteState.WAITING));
	}

	private static Query query(Optional<UUID> computer, Optional<UUID> process,
		int computerOffset, int computerLimit, int slotOffset, int slotLimit,
		int treeOffset, int treeLimit) {
		return new Query(computer, process, new Viewport(computerOffset, computerLimit),
			new Viewport(slotOffset, slotLimit), new Viewport(treeOffset, treeLimit));
	}

	private static ClusterEngineState formedState() {
		return formedState(EPOCH);
	}

	private static ClusterEngineState formedState(UUID epoch) {
		ProductionResourceProfile librarian = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 5, 0)
			.orElseThrow();
		ProductionResourceProfile cleric = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 2, 0)
			.orElseThrow();
		GoalRecord goal = new GoalRecord(GOAL, CLUSTER,
			new GoalSubmission(GOAL, PATTERN, 12, InputMode.EXACT),
			new CreateRouteKey(NETWORK, "factory-input"), new PolicySnapshot(1, 5, false),
			GoalState.WAITING, WaitReason.MATERIAL);
		ProcessRecord processA = new ProcessRecord(PROCESS_A, GOAL, COMPUTER_A, 0, librarian,
			ProcessKind.GOAL, ProcessState.WAITING, WaitReason.MATERIAL, 3, Map.of());
		ProcessRecord processB = new ProcessRecord(PROCESS_B, GOAL, COMPUTER_B, 1, cleric,
			ProcessKind.AGENT, ProcessState.RUNNABLE, null, 4, Map.of());
		Map<UUID, ProcessRecord> processes = new LinkedHashMap<>();
		processes.put(PROCESS_B, processB);
		processes.put(PROCESS_A, processA);
		return new ClusterEngineState(CLUSTER, epoch, 7, 1, 0, 0,
			Map.of(GOAL, goal), Map.of(GOAL, 0L), Map.of(GOAL, ProcessKind.GOAL), processes,
			List.of(new ClusterEngineState.MemberSnapshot(COMPUTER_A, librarian, true, 1, 1),
				new ClusterEngineState.MemberSnapshot(COMPUTER_B, cleric, true, 1, 1)),
			Map.of(), Set.of(), 0, null);
	}

	private static FactoryPanelAuthorityValidator.ViewValidation authority(OptionalLong knownRevision,
		int cardOffset, int wireBudget, Optional<UUID> selection, int rowLimit) {
		return authority(EPOCH, knownRevision, cardOffset, wireBudget, selection, rowLimit);
	}

	private static FactoryPanelAuthorityValidator.ViewValidation authority(
		OptionalLong knownRevision, ComputerMonitorViewRequest monitor, int rowLimit) {
		FactoryPanelOpening opening = new FactoryPanelOpening(PANEL_ADDRESS, PANEL, SESSION, CLUSTER,
			EPOCH);
		var request = new FactoryPanelAuthorityValidator.ViewRequest(opening,
			FactoryPanelView.COMPUTER_MONITOR, knownRevision, monitor.activeOffset(), rowLimit,
			monitor.activeSelection(), 7, Optional.of(monitor));
		var facts = new FactoryPanelAuthorityValidator.ViewFacts(PANEL_ADDRESS, PANEL, SESSION,
			CLUSTER, CLUSTER, EPOCH, true, true, true, true, true, true, true,
			Optional.empty(), rowLimit);
		return FactoryPanelAuthorityValidator.validateView(request, facts).orElseThrow();
	}

	private static FactoryPanelAuthorityValidator.ViewValidation authority(UUID epoch,
		OptionalLong knownRevision, int cardOffset, int wireBudget, Optional<UUID> selection,
		int rowLimit) {
		FactoryPanelOpening opening = new FactoryPanelOpening(PANEL_ADDRESS, PANEL, SESSION, CLUSTER, epoch);
		boolean process = selection.filter(PROCESS_A::equals).isPresent();
		boolean computer = selection.filter(COMPUTER_A::equals).isPresent();
		ComputerMonitorViewRequest monitor = new ComputerMonitorViewRequest(
			process ? ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE
				: computer ? ComputerMonitorViewRequest.ActiveViewport.SLOTS
					: ComputerMonitorViewRequest.ActiveViewport.COMPUTERS,
			process || computer ? Optional.of(COMPUTER_A) : Optional.empty(),
			process ? selection : Optional.empty(),
			new ComputerMonitorViewRequest.Viewport(process || computer ? 0 : cardOffset, 0),
			new ComputerMonitorViewRequest.Viewport(computer ? cardOffset : 0, 0),
			new ComputerMonitorViewRequest.Viewport(process ? cardOffset : 0,
				process && cardOffset > 0 ? 1 : 0), process && cardOffset > 0);
		var request = new FactoryPanelAuthorityValidator.ViewRequest(opening,
			FactoryPanelView.COMPUTER_MONITOR, knownRevision, cardOffset, wireBudget, selection,
			0, Optional.of(monitor));
		var facts = new FactoryPanelAuthorityValidator.ViewFacts(PANEL_ADDRESS, PANEL, SESSION,
			CLUSTER, CLUSTER, epoch, true, true, true, true, true, true, true,
			Optional.empty(), rowLimit);
		return FactoryPanelAuthorityValidator.validateView(request, facts).orElseThrow();
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
