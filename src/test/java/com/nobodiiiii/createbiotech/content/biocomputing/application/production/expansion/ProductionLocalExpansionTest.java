package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Job;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Lifecycle;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.Phase;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class ProductionLocalExpansionTest {
	@BeforeAll static void bootstrap() throws Exception {
		Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (type.getMethod("get").invoke(null) == null)
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		field.setAccessible(true); field.setBoolean(null, true);
	}

	@Test void failedBeginSaveReleasesOnlyTheActualAllocationAndPublishesNothing() throws Exception {
		try (Fixture f = new Fixture()) {
			f.store.fail = true;
			assertThrows(SaveFailure.class, f::begin);
			assertEquals(0, f.owner.count());
			assertTrue(f.store.load().isEmpty());
			ClusterEngineState after = f.runtime.snapshotForServerMutation(f.cluster).orElseThrow();
			assertTrue(after.processes().containsKey(f.source.processId()));
			assertTrue(after.processes().values().stream().noneMatch(p -> p.kind() == ProcessKind.PURE_EXPANSION));
			assertEquals(f.initialPlan, f.livePlan);
		}
	}

	@Test void ledgerCapacityRejectsBeforeAnyRuntimeAllocation() throws Exception {
		try (Fixture f = new Fixture()) {
			Job template = f.begin().job().orElseThrow();
			assertTrue(f.owner.terminate(template, false));
			Job blocker = alternateJob(template, id(120), id(121));
			f.store.commit(new Lifecycle(Map.of(blocker.runId(), blocker)), Optional.empty());
			f.owner = f.newOwner(1); f.owner.restore();
			assertEquals(ProductionLocalExpansion.BeginStatus.RECOVERY_LEDGER_FULL, f.begin().status());
			assertEquals(1, f.owner.count());
			assertTrue(f.runtime.snapshotForServerMutation(f.cluster).orElseThrow().processes().values().stream()
				.noneMatch(process -> process.kind() == ProcessKind.PURE_EXPANSION));
		}
	}

	@Test void failedRevisionBatchDoesNotPublishPartialLifecycleOrPlanAndCanRetry() throws Exception {
		try (Fixture f = new Fixture()) {
			Job before = f.begin().job().orElseThrow();
			f.store.fail = true;
			assertThrows(SaveFailure.class, () -> f.awaitPhase(Phase.RESULT_WORK_PENDING));
			assertEquals(before, f.job());
			assertEquals(before, f.store.load().orElseThrow().jobs().get(before.runId()));
			assertEquals(f.initialPlan, f.livePlan);
			assertEquals(0, f.store.batchWrites);
			f.store.fail = false;
			f.awaitPhase(Phase.RESULT_WORK_PENDING);
			assertEquals(1, f.store.batchWrites);
			assertTrue(f.livePlan.remoteExpansion().isPresent());
			assertEquals(f.job().revisionState().orElseThrow(), f.livePlan.remoteExpansion().orElseThrow().revision());
		}
	}

	@Test void bothPendingPhasesReloadExactlyAndOnlyDurableResultOpensMaterial() throws Exception {
		try (Fixture f = new Fixture()) {
			Job revisionPending = f.begin().job().orElseThrow();
			f.owner = f.newOwner(32); f.owner.restore();
			assertEquals(revisionPending, f.job());
			assertTrue(f.owner.materialReady(f.livePlan, f.job()).isEmpty());
			f.awaitPhase(Phase.RESULT_WORK_PENDING);
			Job resultPending = f.job();
			f.owner = f.newOwner(32); f.owner.restore();
			assertEquals(resultPending, f.job());
			assertTrue(f.owner.materialReady(f.livePlan, f.job()).isEmpty());
			f.store.fail = true;
			FrozenProductionPlan before = f.livePlan;
			assertThrows(SaveFailure.class, () -> f.awaitPhase(Phase.COMPLETED));
			assertEquals(resultPending, f.job());
			assertEquals(before, f.livePlan);
			assertTrue(f.runtime.snapshotForServerMutation(f.cluster).orElseThrow().processes().containsKey(resultPending.processId()));
			f.store.fail = false; f.awaitPhase(Phase.COMPLETED);
			assertEquals(2, f.store.batchWrites);
			assertTrue(f.owner.materialReady(f.livePlan, f.job()).isPresent());
			assertEquals(f.job(), f.store.load().orElseThrow().jobs().get(f.job().runId()));
		}
	}

	@Test void recoveryRejectsDuplicateExactChildAndCurrentRemoteOverlapWithoutInstallingLive() throws Exception {
		try (Fixture f = new Fixture()) {
			Job job = f.begin().job().orElseThrow();
			Job duplicate = alternateJob(job, id(120), job.goalId());
			f.store.commit(new Lifecycle(Map.of(job.runId(), job, duplicate.runId(), duplicate)), Optional.empty());
			ProductionLocalExpansion fresh = f.newOwner(32);
			assertThrows(IllegalStateException.class, fresh::restore);
			assertEquals(0, fresh.count());
			f.store.commit(new Lifecycle(Map.of(job.runId(), job)), Optional.empty());
			f.remoteChildren = Set.of(RemoteExpansionRevision.logicalChildId(job.lease().expansionRequest()));
			assertThrows(IllegalStateException.class, fresh::restore);
			assertEquals(0, fresh.count());
			f.remoteChildren = Set.of(); fresh.restore();
			assertEquals(job, fresh.jobForGoal(f.goal).orElseThrow());
			f.remoteChildren = Set.of(RemoteExpansionRevision.logicalChildId(job.lease().expansionRequest()));
			assertThrows(IllegalStateException.class, fresh::restore, "remote lineage must remain a dynamic fact after construction");
		}
	}

	@Test void staleSourceFenceTerminatesTheExactJobAndClearsMatchingPlan() throws Exception {
		try (Fixture f = new Fixture()) {
			f.begin(); f.awaitPhase(Phase.RESULT_WORK_PENDING);
			Job job = f.job();
			ClusterEngineState state = f.runtime.snapshotForServerMutation(f.cluster).orElseThrow();
			ProcessRecord source = state.processes().get(f.source.processId());
			ProcessRecord stale = new ProcessRecord(source.processId(), source.goalId(), source.owningComputerId(), source.owningSlot(), source.owningComputer(), source.kind(), source.state(), source.waitReason(), source.generation() + 1, source.childRevisionStates());
			assertFalse(f.owner.sourceFenceCurrent(job, stale, state));
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes()); processes.put(stale.processId(), stale);
			f.runtime.restoreSnapshot(withProcesses(state, processes));
			f.owner.advance();
			assertEquals(0, f.owner.count());
			assertTrue(f.livePlan.remoteExpansion().isEmpty());
			assertTrue(f.store.load().orElseThrow().jobs().isEmpty());
		}
	}

	@Test void failedExactReleaseKeepsDurableCleanupAndDoesNotReleaseAMismatchedProcess() throws Exception {
		try (Fixture f = new Fixture()) {
			Job job = f.begin().job().orElseThrow();
			ClusterEngineState state = f.runtime.snapshotForServerMutation(f.cluster).orElseThrow();
			ProcessRecord original = state.processes().get(job.processId());
			ProcessRecord mismatched = new ProcessRecord(original.processId(), original.goalId(), original.owningComputerId(), original.owningSlot(), original.owningComputer(), ProcessKind.LIBRARY_QUERY, original.state(), original.waitReason(), original.generation(), original.childRevisionStates());
			LinkedHashMap<UUID, ProcessRecord> processes = new LinkedHashMap<>(state.processes()); processes.put(mismatched.processId(), mismatched);
			f.runtime.restoreSnapshot(withProcesses(state, processes));
			assertFalse(f.owner.terminate(job, true));
			assertEquals(job, f.job());
			assertEquals(job, f.store.load().orElseThrow().jobs().get(job.runId()));
			assertEquals(mismatched, f.runtime.snapshotForServerMutation(f.cluster).orElseThrow().processes().get(job.processId()));
		}
	}

	@Test void completedRetirementRequiresAnExactDurableOutputReceipt() throws Exception {
		try (Fixture f = new Fixture()) {
			f.begin(); f.awaitPhase(Phase.COMPLETED);
			Job completed = f.job();
			f.livePlan = f.livePlan.cancelAfterDrainNow();
			f.output = receivedOutput(f.initialPlan.runs().currentRun());
			f.owner.retireCompleted();
			assertEquals(completed, f.job(), "a live receipt is not a durable output boundary");
			f.store.journal.write("output_contract/" + completed.runId(), f.output, RemoteExpansionTestRegistries.provider());
			long deadline = System.nanoTime() + 3_000_000_000L;
			while (f.owner.count() != 0 && System.nanoTime() < deadline) { f.owner.retireCompleted(); Thread.sleep(5); }
			assertEquals(0, f.owner.count());
			assertTrue(f.store.load().orElseThrow().jobs().isEmpty());
		}
	}

	private static Job alternateJob(Job template, UUID runId, UUID goalId) {
		var original = template.lease().expansionRequest();
		var request = new RemoteExpansionRequest(goalId, original.parentProcessId(), original.parentProcessGeneration(), original.parentStateGeneration(), original.patternId(), original.patternVersion(), runId, original.runFence(), original.inputMode(), original.runMultiplier(), original.stages(), original.dependencyManifest(), original.exactStackSnapshot());
		var lease = new RemoteProcessLease(id(150), goalId, request.parentProcessId(), template.lease().sourceClusterId(), template.lease().receiverClusterId(), template.lease().sourceGeneration(), template.lease().receiverGeneration(), ProductionCapability.ALCHEMIST, request, request.parentStateGeneration());
		return Job.begin(runId, goalId, lease, template.sourceMemberId(), new FactoryRuntimeService.RemoteComputationAllocation(template.processId(), template.receiverMemberId(), template.owningSlot()));
	}
	private static ClusterEngineState withProcesses(ClusterEngineState state, Map<UUID, ProcessRecord> processes) {
		return new ClusterEngineState(state.clusterId(), state.epochId(), state.generation(), state.nextSubmissionSequence(), state.nextRouteSelectionSequence(), state.memberCursor(), state.processCursor(), state.goals(), state.submissionSequences(), state.requestedKinds(), processes, state.members(), state.pendingIntents(), state.uncertainIntentIds(), state.goalScanOffset(), state.goalScanCandidate(), state.routeCursor(), state.coordinationAssociations(), state.priorityLeaseAssociations());
	}
	private static ProductionOutputContract.Snapshot receivedOutput(ProductionRun run) {
		return new ProductionOutputContract.Snapshot(id(180), 0, id(181), run, 0, ProductionOutputContract.Milestone.OUTPUT_RECEIVED, Optional.empty(), true, false, true, 0, 0, 0, Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), List.of(), Optional.empty(), -1, 0, Optional.empty(), Optional.empty(), Optional.empty(), true);
	}

	private static final class SaveFailure extends RuntimeException {}
	private static final class Journal implements ProductionLocalExpansion.Persistence {
		private final FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		boolean fail;
		int batchWrites;
		public Optional<Lifecycle> load() {
			return journal.read(ProductionLocalExpansion.LIFECYCLE_KEY, Lifecycle.class, RemoteExpansionTestRegistries.provider());
		}
		public void commit(Lifecycle lifecycle, Optional<FrozenProductionPlan> plan) {
			if (fail) throw new SaveFailure();
			LinkedHashMap<String, Object> writes = new LinkedHashMap<>();
			writes.put(ProductionLocalExpansion.LIFECYCLE_KEY, lifecycle);
			plan.ifPresent(value -> writes.put("production_plan/" + value.goalId(), value));
			journal.writeBatch(writes, RemoteExpansionTestRegistries.provider());
			if (plan.isPresent()) batchWrites++;
		}
		public Optional<ProductionOutputContract.Snapshot> output(UUID runId) {
			return journal.read("output_contract/" + runId, ProductionOutputContract.Snapshot.class, RemoteExpansionTestRegistries.provider());
		}
	}
	private static final class Fixture implements AutoCloseable {
		final UUID cluster = id(1), epoch = id(2), goal = id(3), sourceId = id(4), receiverId = id(5);
		final FrozenProductionPlan initialPlan = plan(cluster, goal);
		FrozenProductionPlan livePlan = initialPlan;
		final Journal store = new Journal();
		Set<UUID> remoteChildren = Set.of();
		ProductionOutputContract.Snapshot output;
		final ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(ProductionProfession.NITWIT, 1, 0).orElseThrow();
		final ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow();
		final ProcessRecord source = new ProcessRecord(id(6), goal, sourceId, 0, sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 4, Map.of());
		final ClusterEngineState initialState;
		final FactoryRuntimeService runtime = new FactoryRuntimeService(new FactoryRuntimeConfig(1, 1_000, 4, 32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16));
		ProductionLocalExpansion owner;
		Fixture() {
			GoalRecord record = new GoalRecord(goal, cluster,
				new GoalSubmission(goal, initialPlan.patternId(), 1, InputMode.EXACT),
				initialPlan.deliveryRoute(), new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
			initialState = new ClusterEngineState(cluster, epoch, 7, 1, 0, 0,
				Map.of(goal, record), Map.of(goal, 0L), Map.of(goal, ProcessKind.GOAL), Map.of(source.processId(), source),
				List.of(new ClusterEngineState.MemberSnapshot(sourceId, sourceProfile, true, 8, 8),
					new ClusterEngineState.MemberSnapshot(receiverId, receiverProfile, true, 9, 9)), Map.of(), Set.of(), 0, null);
			runtime.start(); runtime.restoreSnapshot(initialState); owner = newOwner(32); owner.restore();
		}
		ProductionLocalExpansion newOwner(int capacity) {
			return new ProductionLocalExpansion(RemoteExpansionTestRegistries.provider(), runtime, capacity, store,
				new ProductionLocalExpansion.Evidence() {
					public FrozenProductionPlan plan(UUID goalId) { return goal.equals(goalId) ? livePlan : null; }
					public ProductionOutputContract.Snapshot output(UUID runId) { return output; }
					public Set<UUID> remoteChildIds() { return remoteChildren; }
					public void publishPlan(FrozenProductionPlan plan) { livePlan = plan; }
				});
		}
		ProductionLocalExpansion.BeginResult begin() {
			return owner.begin(livePlan, livePlan.runs().currentRun(), source, initialState,
				ProductionLocalExpansion.selectTarget(initialState, source, ProductionCapability.ALCHEMIST).orElseThrow());
		}
		Job job() { return owner.job(initialPlan.runs().currentRun().runId()).orElseThrow(); }
		void awaitPhase(Phase phase) throws Exception {
			long deadline = System.nanoTime() + 5_000_000_000L;
			while (System.nanoTime() < deadline) {
				owner.advance();
				if (job().phase() == phase) return;
				Thread.sleep(5);
			}
			fail("pure worker did not reach " + phase + "; job=" + owner.jobForGoal(goal));
		}
		public void close() { runtime.close(); }
	}
	private static FrozenProductionPlan plan(UUID cluster, UUID goal) {
		UUID network = id(90), pattern = id(91);
		CreateRouteKey input = new CreateRouteKey(network, "input"), delivery = new CreateRouteKey(network, "warehouse");
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT)), ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		ProductionRun run = new ProductionRun(id(92), goal, pattern, 0, 1, InputMode.EXACT, List.of(input), delivery, 0, Map.of(ingredient, 1L));
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(new RouteSelectionScope(id(94), id(95), output, 0, 0, id(96)), id(93), pattern, pattern.toString(), RouteCandidate.ProviderKind.PATTERN, 0, RouteCandidate.Availability.AVAILABLE);
		return new FrozenProductionPlan(goal, cluster, provider, PatternKind.STANDARD, output, 1, delivery,
			List.of(new FrozenProductionPlan.FrozenStage(0, input, Map.of(ingredient, 1L), List.of(new OrderedIngredient(ingredient, 1L)))),
			new ProductionRun.RunSequence(run, 1), new StockObservationScope.NetworkSummary(network, output, 1), 0, 1, false, false, Optional.empty());
	}
	private static UUID id(long value) { return new UUID(0, value); }
}
