package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion.*;

/** Ready-plan material dispatch authority; production slots never become generic compute budgets. */
public final class ProductionPlanDispatch {
	public interface Evidence {
		Collection<FrozenProductionPlan> plans();
		@Nullable FrozenProductionPlan plan(UUID goalId);
		@Nullable GoalObservationState observation(UUID goalId);
		boolean runHasDurableMaterialEvidence(ClusterEngineState state, UUID runId);
		boolean loadedAgentOwns(CreateRouteKey route, UUID clusterId);
		/** Commit the plan before making the updated value visible to subsequent lookups. */
		void persistPlan(FrozenProductionPlan plan);
		/** Refresh the source snapshot again before publishing the wait observation. */
		boolean waitSource(ClusterEngineState state, GoalRecord goal, WaitReason reason);
	}
	private final FactoryRuntimeService runtime;
	private final HolderLookup.Provider registries;
	private final ProductionLocalExpansion localExpansion;
	private final ProductionRemoteExpansion remoteExpansion;
	private final Evidence evidence;
	private final Set<UUID> submittedRuns = new HashSet<>();

	public ProductionPlanDispatch(FactoryRuntimeService runtime, HolderLookup.Provider registries,
		ProductionLocalExpansion localExpansion, ProductionRemoteExpansion remoteExpansion, Evidence evidence) {
		this.runtime = Objects.requireNonNull(runtime);
		this.registries = Objects.requireNonNull(registries);
		this.localExpansion = Objects.requireNonNull(localExpansion);
		this.remoteExpansion = Objects.requireNonNull(remoteExpansion);
		this.evidence = Objects.requireNonNull(evidence);
	}
	public boolean isSubmitted(UUID runId) { return submittedRuns.contains(Objects.requireNonNull(runId)); }
	public void markSubmitted(UUID runId) { submittedRuns.add(Objects.requireNonNull(runId)); }
	public void forgetSubmitted(UUID runId) { submittedRuns.remove(Objects.requireNonNull(runId)); }
	public Set<UUID> submittedRunIds() { return Set.copyOf(submittedRuns); }
	private FrozenProductionPlan currentPlanOr(FrozenProductionPlan fallback) {
		FrozenProductionPlan current = evidence.plan(fallback.goalId());
		return current == null ? fallback : current;
	}
	public void advance() {
		for (FrozenProductionPlan plan : List.copyOf(evidence.plans())) {
			if (plan.cancelAfterDrain() || plan.runs().currentRun() == null) continue;
			GoalObservationState durableCredit = evidence.observation(plan.goalId());
			if (durableCredit != null
				&& durableCredit.creditedCount() >= durableCredit.targetCount()) continue;
			resumeAfterSealedOutput(runtime, plan);
			ProductionRun run = plan.runs().currentRun();
			RemoteComputationExchange remoteComputation =
				remoteExpansion.exchange(run.runId()).orElse(null);
			Job localComputation = localExpansion.job(run.runId()).orElse(null);
			// A reverse lease delivery must get a server-authoritative mutation window.
			// A normal snapshot redispatches the just-completed Goal worker immediately and can
			// otherwise starve installRemoteChildRevision forever after composition-root replay.
			ClusterEngineState state = (remoteComputation == null && localComputation == null
				? runtime.snapshot(plan.clusterId())
				: runtime.snapshotForServerMutation(plan.clusterId())).orElse(null);
			GoalRecord goal = state == null ? null : state.goals().get(plan.goalId());
			if (goal != null && goal.state() == GoalState.WAITING
				&& goal.waitReason() == WaitReason.AGENT_UNLOADED
				&& evidence.loadedAgentOwns(plan.stages().getFirst().route(), plan.clusterId())
				&& runtime.resumePanelGoal(state.clusterId(), state.epochId(),
					state.generation(), goal.goalId())) {
				state = runtime.snapshot(plan.clusterId()).orElse(null);
				goal = state == null ? null : state.goals().get(plan.goalId());
			}
			if (goal == null) continue;
			UUID goalId = plan.goalId();
			UUID exactSourceProcessId = localComputation != null
				? localComputation.lease().sourceProcessId()
				: remoteComputation == null ? null
					: remoteComputation.lease().sourceProcessId();
			ProcessRecord process = state.processes().values().stream()
				.filter(candidate -> candidate.goalId().equals(goalId))
				.filter(candidate -> exactSourceProcessId == null
					|| candidate.processId().equals(exactSourceProcessId))
				.filter(candidate -> candidate.state()
					!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL)
				.findFirst().orElse(null);
			if (process == null) continue;
			boolean durableEvidence = evidence.runHasDurableMaterialEvidence(state, run.runId());
			boolean durableHandoff = remoteMaterialHandoffOwnsRun(plan, run.runId());
			if (remoteComputation != null
				&& !remoteExpansion.sourceFenceCurrent(remoteComputation, process, state)
				&& shouldMigrateRemoteComputation(durableEvidence, durableHandoff,
					isSubmitted(run.runId()))) {
				if (!remoteExpansion.terminate(remoteComputation,
					RemoteComputationTerminalOutcome.MIGRATED)) continue;
				plan = currentPlanOr(plan);
				remoteComputation = null;
			}

			if (localComputation != null
				&& !localExpansion.sourceFenceCurrent(localComputation, process, state)
				&& shouldMigrateRemoteComputation(durableEvidence, durableHandoff,
					isSubmitted(run.runId()))) {
				if (!localExpansion.terminate(localComputation, true)) continue;
				plan = currentPlanOr(plan);
				localComputation = null;
			}
			boolean exhausted = sourceComputerExhausted(state, process);
			if (remoteComputation == null && localComputation == null && exhausted
				&& (goal.state() == GoalState.RUNNABLE || goal.state() == GoalState.WAITING
					&& (goal.waitReason() == WaitReason.ROUTE
						|| goal.waitReason() == WaitReason.RECOVERY_LEDGER_FULL
						|| goal.waitReason() == WaitReason.PROCESS))) {
				Optional<Target> localTarget =
					ProductionLocalExpansion.selectTarget(state, process, ProductionCapability.ALCHEMIST);
				if (localTarget.isPresent()) {
					ClusterEngineState mutationState = runtime.snapshotForServerMutation(
						plan.clusterId()).orElse(state);
					ProcessRecord mutationSource = mutationState.processes().get(process.processId());
					BeginResult begun = mutationSource == null
						? new BeginResult(
							BeginStatus.NO_PROCESS_CAPACITY, Optional.empty())
						: localExpansion.begin(plan, run, mutationSource, mutationState,
							localTarget.orElseThrow());
					localComputation = begun.job().orElse(null);
					if (localComputation == null) {
						WaitReason localWait = ProductionLocalExpansion.beginWaitReason(begun.status());
						if (goal.state() != GoalState.WAITING || goal.waitReason() != localWait)
							evidence.waitSource(state, goal, localWait);
					}
				} else {
					RemoteComputationBeginResult begun = remoteExpansion.begin(plan, run,
						process, state);
					remoteComputation = begun.exchange().orElse(null);
					if (remoteComputation == null && goal.state() == GoalState.RUNNABLE)
						evidence.waitSource(state, goal,
							remoteComputationBeginWaitReason(begun.status()));
				}
			}
			if (goal.state() == GoalState.WAITING
				&& waitsForPureComputation(goal)
				&& remoteComputation != null && remoteComputation.materialDispatchAllowed()) {
				remoteExpansion.resumeSource(remoteComputation);
				state = runtime.snapshot(plan.clusterId()).orElse(null);
				goal = state == null ? null : state.goals().get(plan.goalId());
				process = state == null ? null : state.processes().get(process.processId());
			}
			if (waitsForPureComputation(goal)
				&& localComputation != null
				&& localComputation.phase() == Phase.COMPLETED) {
				localExpansion.resumeSource(localComputation);
				state = runtime.snapshot(plan.clusterId()).orElse(null);
				goal = state == null ? null : state.goals().get(plan.goalId());
				process = state == null ? null : state.processes().get(process.processId());
			}
			if (goal == null || goal.state() != GoalState.RUNNABLE || process == null
				|| process.state()
					!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.RUNNABLE)
				continue;
			if (durableEvidence || isSubmitted(run.runId())) continue;
			if (remoteComputation != null) {
				plan = remoteExpansion.materialReady(plan, remoteComputation)
					.orElse(null);
				if (plan == null) continue;
			}
			if (localComputation != null) {
				plan = localExpansion.materialReady(plan, localComputation).orElse(null);
				if (plan == null) continue;
			}
			if (exhausted && remoteComputation == null && localComputation == null) continue;
			if (!plan.dispatchQueued()) {
				plan = plan.markDispatchQueued();
				evidence.persistPlan(plan);
			}
			if (publishSelectedProviderCreateDispatch(runtime, state, process, plan,
				registries))
				markSubmitted(run.runId());
		}
	}

	private boolean sourceComputerExhausted(ClusterEngineState state, ProcessRecord source) {
		long occupied = state.processes().values().stream()
			.filter(candidate -> candidate.owningComputerId().equals(source.owningComputerId()))
			.filter(candidate -> candidate.state()
				!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL)
			.count();
		return occupied >= source.owningComputer().processSlots();
	}

	public static boolean resumeAfterSealedOutput(FactoryRuntimeService runtime,
		FrozenProductionPlan plan) {
		Objects.requireNonNull(runtime, "runtime");
		plan = Objects.requireNonNull(plan, "plan");
		if (plan.runs().currentRun() == null || plan.cancelAfterDrain()) return false;
		ClusterEngineState state = runtime.snapshot(plan.clusterId()).orElse(null);
		GoalRecord goal = state == null ? null : state.goals().get(plan.goalId());
		return goal != null && goal.state() == GoalState.WAITING
			&& goal.waitReason() == WaitReason.OUTPUT_DELIVERY
			&& runtime.resumePanelGoalAfterOutputDelivery(state.clusterId(), state.epochId(),
				state.generation(), goal.goalId());
	}

	/**
	 * Production dispatch boundary shared by the live server loop and integration tests. The
	 * selected provider decides only where work runs; the frozen Pattern remains the sole authority
	 * for Create's ordered ingredient request.
	 */
	public static boolean publishSelectedProviderCreateDispatch(FactoryRuntimeService runtime,
		ClusterEngineState state, ProcessRecord process, FrozenProductionPlan plan,
		net.minecraft.core.HolderLookup.Provider registries) {
		runtime = Objects.requireNonNull(runtime, "runtime");
		state = Objects.requireNonNull(state, "state");
		process = Objects.requireNonNull(process, "process");
		plan = Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(registries, "registries");
		ProductionRun run = plan.runs().currentRun();
		GoalRecord goal = state.goals().get(plan.goalId());
		ClusterEngineState.MemberSnapshot owner = state.member(process.owningComputerId());
		if (run == null || state.generation() == Long.MAX_VALUE
			|| !state.clusterId().equals(plan.clusterId())
			|| !process.equals(state.processes().get(process.processId()))
			|| !process.goalId().equals(plan.goalId()) || goal == null
			|| goal.state() == GoalState.TERMINAL || owner == null || !owner.loaded()) return false;
		return runtime.publishFact(new WorldFact.PlayerCommand(state.clusterId(), state.epochId(),
			state.generation(), new WorldFact.Command.DispatchCreate(process.processId(), run.runId(),
				plan.stages().getFirst().route(), plan.orderedIngredientsForCurrentRun(state.generation()))));
	}

	public static boolean waitsForPureComputation(GoalRecord goal) {
		return goal != null && goal.state() == GoalState.WAITING
			&& (goal.waitReason() == WaitReason.ROUTE
				|| goal.waitReason() == WaitReason.RECOVERY_LEDGER_FULL
				|| goal.waitReason() == WaitReason.PROCESS);
	}
}
