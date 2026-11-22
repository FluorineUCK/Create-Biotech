package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class FactoryComputeWorkerTest {
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(200, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);

	@BeforeAll
	static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test void oneNamedDaemonWakesImmediatelyAndDoesNotLoseBackToBackSubmissions() throws Exception {
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG)) {
			worker.start();
			assertTrue(worker.isDaemon());
			assertTrue(worker.threadName().startsWith("create-biotech-factory-compute-"));
			worker.submit(ClusterEngineState.empty(id(1), id(11)), List.of());
			worker.submit(ClusterEngineState.empty(id(2), id(12)), List.of());
			assertEquals(List.of(id(1), id(2)), waitFor(worker, 2).stream().map(FactoryComputeWorker.ComputeResult::clusterId).toList());
		}
	}

	@Test void workerYieldsToWallTimeBudgetAndContinuesPendingCluster() throws Exception {
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG)) {
			worker.start();
			worker.submit(ClusterEngineState.empty(id(3), id(13)), List.of());
			assertEquals(id(3), waitFor(worker, 1).getFirst().clusterId());
			assertFalse(worker.isClosed());
		}
	}

	@Test void oneCadenceRoundAdvancesEachClusterAtMostOnce() throws Exception {
		FactoryRuntimeConfig cycleConfig = new FactoryRuntimeConfig(200, 100_000, 4,
			32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), cycleConfig)) {
			worker.start();
			var profile = com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile.forProfession(
				com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
			UUID cluster = id(8), epoch = id(18);
			worker.submit(ClusterEngineState.empty(cluster, epoch), List.of(
				new WorldFact.MemberLoadState(cluster, epoch, 0, id(80), profile, true, 1, 1),
				new WorldFact.MemberLoadState(cluster, epoch, 0, id(81), profile, true, 1, 1)));
			Thread.sleep(cycleConfig.workerCadenceMillis() * 3L / 4L);
			assertTrue(worker.drainResults(1).isEmpty());
			FactoryComputeWorker.ComputeResult result = waitFor(worker, 1).getFirst();
			assertEquals(2, result.state().members().size());
		}
	}

	@Test void transitionFailureBecomesBoundedDiagnosticAndWorkerStaysAlive() throws Exception {
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG)) {
			worker.start();
			worker.submit(ClusterEngineState.empty(id(4), id(14)), List.of(new WorldFact.Signal(id(999), id(14), 1, "bad-cluster", 1)));
			FactoryComputeWorker.ComputeResult result = waitFor(worker, 1).getFirst();
			assertNotNull(result.diagnostic());
			assertTrue(result.diagnostic().message().length() <= FactoryComputeWorker.MAX_DIAGNOSTIC_LENGTH);
			assertTrue(worker.isAlive());
		}
	}

	@Test void laterFactFailurePreservesEarlierIntentAndContinuesWithTheSuffix() throws Exception {
		UUID cluster = id(40), epoch = id(140), computer = id(240);
		ClusterEngineState state = dispatchable(cluster, epoch, computer, id(340));
		ProductionResourceProfile profile = state.members().getFirst().profile();
		List<WorldFact> batch = List.of(dispatch(cluster, state, id(440)),
			new WorldFact.Signal(id(999), epoch, 2, "wrong-cluster", 1),
			new WorldFact.MemberLoadState(cluster, epoch, 3, id(241), profile, true, 1, 1));
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG)) {
			worker.start(); worker.submit(state, batch);
			FactoryComputeWorker.ComputeResult result = waitFor(worker, 1).getFirst();
			assertNotNull(result.diagnostic());
			assertEquals(1, result.intents().size());
			assertTrue(result.state().pendingIntents().containsKey(result.intents().getFirst().intentId()));
			assertNotNull(result.state().member(id(241)));
		}
	}

	@Test void workerQueuesAreActuallyBounded() throws Exception {
		for (String fieldName : List.of("input", "output")) {
			var field = FactoryComputeWorker.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			assertInstanceOf(ArrayBlockingQueue.class,
				field.get(new FactoryComputeWorker(new ClusterEngine(), CONFIG)));
		}
	}

	@Test void closeProcessesAcceptedWorkInOrderThenRejectsLaterSubmission() throws Exception {
		FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG);
		worker.start();
		worker.submit(ClusterEngineState.empty(id(5), id(15)), List.of());
		worker.submit(ClusterEngineState.empty(id(6), id(16)), List.of());
		worker.close();
		assertEquals(List.of(id(5), id(6)), worker.drainResults(8).stream().map(FactoryComputeWorker.ComputeResult::clusterId).toList());
		assertTrue(worker.isClosed());
		assertFalse(worker.isAlive());
		assertThrows(IllegalStateException.class, () -> worker.submit(ClusterEngineState.empty(id(7), id(17)), List.of()));
	}

	@Test void workerDoesNotLoseRepeatedOrDistinctClusterSubmissions() throws Exception {
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG)) {
			worker.start();
			worker.submit(ClusterEngineState.empty(id(20), id(120)), List.of());
			worker.submit(ClusterEngineState.empty(id(20), id(120)), List.of());
			worker.submit(ClusterEngineState.empty(id(21), id(121)), List.of());
			Map<UUID, Long> counts = waitFor(worker, 3).stream().collect(java.util.stream.Collectors.groupingBy(
				FactoryComputeWorker.ComputeResult::clusterId, java.util.stream.Collectors.counting()));
			assertEquals(Map.of(id(20), 2L, id(21), 1L), counts);
		}
	}

	@Test void continuationGoesBehindEveryOtherReadyCluster() throws Exception {
		ConcurrentLinkedQueue<UUID> transitions = new ConcurrentLinkedQueue<>();
		FactoryRuntimeConfig cycleConfig = new FactoryRuntimeConfig(1, 100_000, 4,
			32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);
		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), cycleConfig,
			transitions::add)) {
			worker.start();
			UUID a = id(50), b = id(51);
			worker.submit(ClusterEngineState.empty(a, id(150)), List.of(
				new WorldFact.ClusterEpochFact(a, id(150), 0),
				new WorldFact.ClusterEpochFact(a, id(150), 1)));
			worker.submit(ClusterEngineState.empty(b, id(151)),
				List.of(new WorldFact.ClusterEpochFact(b, id(151), 0)));
			waitFor(worker, 2);
			assertEquals(List.of(a, b, a), List.copyOf(transitions));
		}
	}

	@Test void receiverWorkerExpandsTypedPatternManifestFromItsImmutableAllocatedProcessSnapshot()
		throws Exception {
		UUID cluster = id(60), epoch = id(160), member = id(260), processId = id(360);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 5, 0)
			.orElseThrow();
		ProcessRecord process = new ProcessRecord(processId, id(460), member, 0, profile,
			ProcessKind.REMOTE_LISTENER, ProcessState.RUNNABLE, null, 7, Map.of());
		ClusterEngineState receiverSnapshot = new ClusterEngineState(cluster, epoch, 1, 0,
			0, 0, Map.of(), Map.of(), Map.of(), Map.of(processId, process),
			List.of(new ClusterEngineState.MemberSnapshot(member, profile, true, 1, 1)),
			Map.of(), Set.of(), 0, null);
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		CreateRouteKey input = new CreateRouteKey(id(561), "typed-input");
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(460), id(462), 7, 11,
			id(563), 2, id(564), 5, InputMode.BULK, 3,
			List.of(new RemoteExpansionRequest.StageInput(0, input, Map.of(iron, 2L))),
			Map.of(iron, 2L));
		FactoryComputeWorker.RemotePureWork revisionWork = new FactoryComputeWorker.RemotePureWork(
			id(560), cluster, member, processId, 0, 7, 11, ProductionCapability.ALCHEMIST,
			request, FactoryComputeWorker.RemotePureStage.REVISION,
			Optional.empty());

		try (FactoryComputeWorker worker = new FactoryComputeWorker(new ClusterEngine(), CONFIG)) {
			worker.start();
			assertTrue(worker.trySubmitRemote(receiverSnapshot, revisionWork));
			FactoryComputeWorker.RemotePureResult revision = waitFor(worker, 1).getFirst()
				.remotePureResult().orElseThrow();
			assertEquals(revisionWork.leaseId(), revision.leaseId());
			assertEquals(FactoryComputeWorker.RemotePureStage.REVISION, revision.stage());
			var accepted = revision.revisionState().orElseThrow();
			assertEquals(request.patternId(), accepted.patternId());
			assertEquals(request.parentStateGeneration(), accepted.parentStateGeneration());

			FactoryComputeWorker.RemotePureWork resultWork = new FactoryComputeWorker.RemotePureWork(
				id(560), cluster, member, processId, 0, 7, 11, ProductionCapability.ALCHEMIST,
				request, FactoryComputeWorker.RemotePureStage.RESULT, Optional.of(accepted));
			assertTrue(worker.trySubmitRemote(receiverSnapshot, resultWork));
			RemoteExpansionResult expansion = waitFor(worker, 1).getFirst().remotePureResult()
				.orElseThrow().expansionResult().orElseThrow();
			assertEquals(Map.of(iron, 6L), expansion.manifest(),
				"receiver work must perform the Pattern multiplier expansion");
			assertEquals(Map.of(iron, 6L), expansion.stages().getFirst().manifest());
			assertEquals(accepted.childRevision(), expansion.childRevision(),
				"the terminal expansion must stay on the acknowledged child lineage");
		}
	}

	private static List<FactoryComputeWorker.ComputeResult> waitFor(FactoryComputeWorker worker, int count) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
		ArrayList<FactoryComputeWorker.ComputeResult> results = new ArrayList<>();
		while (results.size() < count && System.nanoTime() < deadline) {
			results.addAll(worker.drainResults(count - results.size()));
			if (results.size() < count) Thread.sleep(5);
		}
		assertEquals(count, results.size());
		return List.copyOf(results);
	}
	private static UUID id(long value) { return new UUID(0, value); }

	private static ClusterEngineState dispatchable(UUID cluster, UUID epoch, UUID computer, UUID processId) {
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
		UUID goalId = id(processId.getLeastSignificantBits() + 1000);
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, id(90), 1, InputMode.BULK), new CreateRouteKey(id(91), "route"),
			new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goalId, computer, 0, profile,
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		return new ClusterEngineState(cluster, epoch, 1, 1, 0, 0, Map.of(goalId, goal), Map.of(goalId, 0L),
			Map.of(goalId, ProcessKind.GOAL), Map.of(processId, process),
			List.of(new ClusterEngineState.MemberSnapshot(computer, profile, true, 1, 1)), Map.of(), Set.of(), 0, null);
	}

	private static WorldFact dispatch(UUID cluster, ClusterEngineState state, UUID runId) {
		return new WorldFact.PlayerCommand(cluster, state.epochId(), state.generation(),
			new WorldFact.Command.DispatchCreate(state.processes().keySet().iterator().next(), runId,
				new CreateRouteKey(id(92), "route"),
				List.of(new OrderedIngredient(
					new StackKey(new ItemStack(Items.STONE)), 1))));
	}
}
