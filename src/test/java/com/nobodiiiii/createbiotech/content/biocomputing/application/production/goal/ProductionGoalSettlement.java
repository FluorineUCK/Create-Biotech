package com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer.RemoteAgentRadioDispatch;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer.RemoteRecoveryExchange;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer.RemoteRecoveryPhase;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;

/** Single live owner of durable Goal observation evidence. Restoring never notifies the engine. */
public final class ProductionGoalSettlement {

	private static final String REMOTE_AGENT_DISPATCH_PREFIX = "remote_agent_dispatch/";
	private static final String REMOTE_RECOVERY_EXCHANGE_PREFIX = "remote_recovery_exchange/";

	public interface Persistence {
		Map<String, GoalObservationState> loadObservations();
		Optional<GoalObservationState> observation(UUID goalId);
		void saveObservation(GoalObservationState observation);
		Optional<FrozenProductionPlan> plan(UUID goalId);
		Optional<ProductionOutputContract.Snapshot> output(UUID runId);
		void commitRetirement(UUID runId, Optional<GoalObservationState> observation,
			CompletedRunRemoteArtifactRetirement remote);
	}

	public interface Evidence {
		@Nullable FrozenProductionPlan plan(UUID goalId);
		@Nullable ProductionOutputPolicy outputPolicy(UUID runId);
		List<ProductionOutputContract.Snapshot> outputReceipts();
		boolean hasLiveChild(UUID runId);
		RecoveryLedger.State recovery();
		void removeRetiredArtifacts(UUID runId);
	}

	/** Raw immutable transport facts and exact Map operations; no readiness or commit decisions. */
	public interface Transport {
		Map<UUID, RemoteAgentRadioDispatch> dispatches();
		Map<RemoteRequestKey, RemoteRecoveryExchange> exchanges();
		DurableRadioOutbox.State outbox();
		boolean canRetireDeliveredRoute(UUID messageId);
		boolean matchesCurrentPair(CompletedRunRemoteArtifactRetirement retirement);
		void removeExactPair(CompletedRunRemoteArtifactRetirement retirement);
	}

	public record CompletedRunRemoteArtifactRetirement(UUID runId,
		Optional<RemoteAgentRadioDispatch> dispatch,
		Optional<RemoteRecoveryExchange> recoveryExchange) {
		public CompletedRunRemoteArtifactRetirement {
			runId = Objects.requireNonNull(runId, "runId");
			dispatch = Objects.requireNonNull(dispatch, "dispatch");
			recoveryExchange = Objects.requireNonNull(recoveryExchange, "recoveryExchange");
			if (dispatch.isPresent() != recoveryExchange.isPresent())
				throw new IllegalArgumentException("Remote Run retirement needs an exact pair");
			if (dispatch.isPresent()) {
				RemoteAgentRadioDispatch exactDispatch = dispatch.orElseThrow();
				RemoteRecoveryExchange exactRecovery = recoveryExchange.orElseThrow();
				LinkedHashSet<UUID> messages = new LinkedHashSet<>();
				messages.add(exactDispatch.messageId());
				messages.addAll(recoveryMessageIds(exactRecovery));
				if (!exactDispatch.intent().runId().equals(runId)
					|| !exactRecovery.key().runId().equals(runId)
					|| !exactDispatch.committed()
					|| exactRecovery.phase() != RemoteRecoveryPhase.RECONCILED
					|| messages.size() != 5)
					throw new IllegalArgumentException(
					"Invalid completed remote Run retirement pair");
			}
		}
		public Map<String, ?> terminalJournalArtifacts() {
			if (dispatch.isEmpty()) return Map.of();
			RemoteAgentRadioDispatch exactDispatch = dispatch.orElseThrow();
			RemoteRecoveryExchange exactRecovery = recoveryExchange.orElseThrow();
			return Map.of(REMOTE_AGENT_DISPATCH_PREFIX + exactDispatch.messageId(), exactDispatch,
				REMOTE_RECOVERY_EXCHANGE_PREFIX + exactRecovery.key().requestId(), exactRecovery);
		}
		public Set<UUID> messageIds() {
			if (dispatch.isEmpty()) return Set.of();
			LinkedHashSet<UUID> messages = new LinkedHashSet<>(
				recoveryMessageIds(recoveryExchange.orElseThrow()));
			messages.add(dispatch.orElseThrow().messageId());
			return Set.copyOf(messages);
		}
	}

	private final FactoryRuntimeService runtime;
	private final StockObservationService stock;
	private final Evidence evidence;
	private final Transport transport;
	private final Persistence persistence;

	private final Map<UUID, GoalObservationState> observations = new LinkedHashMap<>();

	public ProductionGoalSettlement(Persistence persistence, FactoryRuntimeService runtime,
		StockObservationService stock, Evidence evidence, Transport transport) {
		this.transport = Objects.requireNonNull(transport, "transport");
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.stock = Objects.requireNonNull(stock, "stock");
		this.evidence = Objects.requireNonNull(evidence, "evidence");
		this.persistence = Objects.requireNonNull(persistence, "persistence");
		Map.copyOf(persistence.loadObservations()).values()
			.forEach(state -> observations.put(state.goalId(), state));
	}

	public Optional<GoalObservationState> observation(UUID goalId) {
		return Optional.ofNullable(observations.get(goalId));
	}

	public Map<UUID, GoalObservationState> observations() {
		return Map.copyOf(observations);
	}

	/** A stale PREPARED callback is not newer than either the journal or installed credit. */
	public void admissionCommitted(UUID goalId) {
		if (!observations.containsKey(goalId))
			persistence.observation(goalId).ifPresent(state -> observations.putIfAbsent(goalId, state));
	}

	public void observeStocks() {
		for (GoalObservationState evidence : List.copyOf(observations.values())) {
			ClusterEngineState state = runtime.snapshot(evidence.clusterId()).orElse(null);
			GoalRecord goal = state == null ? null : state.goals().get(evidence.goalId());
			FrozenProductionPlan plan = this.evidence.plan(evidence.goalId());
			if (goal == null || plan == null || !shouldCreditGoalStock(plan, goal)) continue;
			StockObservationService.Observation observed = stock.observe(evidence.scope());
			if (observed.status() == StockObservationService.Status.WAIT_STOCK_OBSERVATION) continue;
			GoalObservationState updated = evidence.withActualCount(observed.actualCount());
			if (!updated.equals(evidence)) saveObservation(updated);
			if (updated.creditedCount() >= updated.targetCount())
				runtime.creditPanelGoalActualStock(state.clusterId(), state.epochId(),
					state.generation(), goal.goalId(), updated.creditedCount());
		}
	}

	public void replayDurableCredits() {
		for (GoalObservationState evidence : List.copyOf(observations.values())) {
			if (evidence.creditedCount() < evidence.targetCount()) continue;
			ClusterEngineState state = runtime.snapshot(evidence.clusterId()).orElse(null);
			GoalRecord goal = state == null ? null : state.goals().get(evidence.goalId());
			FrozenProductionPlan plan = this.evidence.plan(evidence.goalId());
			if (goal == null || plan == null || !shouldCreditGoalStock(plan, goal)) continue;
			runtime.creditPanelGoalActualStock(state.clusterId(), state.epochId(),
				state.generation(), goal.goalId(), evidence.creditedCount());
		}
	}

	public boolean retireReceivedRun(ProductionOutputContract.Snapshot output) {
		Objects.requireNonNull(output, "output");
		ProductionRun run = output.run();
		if (output.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED
			|| evidence.hasLiveChild(run.runId())) return false;
		FrozenProductionPlan plan = evidence.plan(run.goalId());
		GoalObservationState observation = observations.get(run.goalId());
		ProductionOutputPolicy policy = evidence.outputPolicy(run.runId());
		if (plan == null || policy == null) return false;
		if (!output.equals(persistence.output(run.runId()).orElse(null))
			|| !plan.equals(persistence.plan(plan.goalId()).orElse(null))) return false;
		RecoveryLedger.State recovery = evidence.recovery();
		RemoteRequestKey key = exactRecoveryKeyForOutput(recovery, output,
			plan.cancelAfterDrain() ? RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED
			: RemoteAgentRecord.TerminalOutcome.COMPLETED).orElse(null);
		if (key == null) return false;
		CompletedRunRemoteArtifactRetirement remote = completedRunRemoteArtifactRetirement(
			run.runId(), run.goalId(), key, recovery, transport.dispatches(), transport.exchanges(),
			transport.outbox(), transport::canRetireDeliveredRoute).orElse(null);
		if (remote == null) return false;
		if (plan.cancelAfterDrain()) {
			if (!cancelledRunRetirementReady(recovery, key, output, policy, plan)) return false;
			publishCompletedRunRemoteArtifactRetirement(pair -> persistence.commitRetirement(
				run.runId(), Optional.empty(), pair), remote, transport);
			evidence.removeRetiredArtifacts(run.runId());
			return true;
		}
		if (observation == null || !observation.equals(persistence.observation(run.goalId()).orElse(null))) return false;
		OptionalLong credit = activeRunRetirementCredit(recovery, key, output, policy, plan, observation);
		if (credit.isEmpty()) return false;
		GoalObservationState updated = observation.withNewlyConfirmedOutput(run.runId(), credit.orElseThrow());
		publishCompletedRunRemoteArtifactRetirement(pair -> persistence.commitRetirement(
			run.runId(), Optional.of(updated), pair), remote, transport);
		observations.put(updated.goalId(), updated);
		evidence.removeRetiredArtifacts(run.runId());
		ClusterEngineState source = runtime.snapshot(plan.clusterId()).orElse(null);
		GoalRecord goal = source == null ? null : source.goals().get(plan.goalId());
		if (goal != null && updated.creditedCount() >= updated.targetCount() && shouldCreditGoalStock(plan, goal))
			runtime.creditPanelGoalActualStock(source.clusterId(), source.epochId(), source.generation(),
			goal.goalId(), updated.creditedCount());
		return true;
	}

	public void retireReceivedRuns() {
		for (var output : List.copyOf(evidence.outputReceipts())) retireReceivedRun(output);
	}

	private void saveObservation(GoalObservationState observation) {
		persistence.saveObservation(observation);
		observations.put(observation.goalId(), observation);
	}

	public static boolean shouldCreditGoalStock(FrozenProductionPlan plan, GoalRecord goal) {
		return !Objects.requireNonNull(plan, "plan").cancelAfterDrain()
			&& Objects.requireNonNull(goal, "goal").state() != GoalState.TERMINAL;
	}

	private static Set<UUID> recoveryMessageIds(RemoteRecoveryExchange exchange) {
		exchange = Objects.requireNonNull(exchange, "exchange");
		return Set.of(exchange.offerEnvelope().messageId(),
			exchange.terminalEnvelope().messageId(),
			exchange.statusQueryEnvelope().messageId(),
			exchange.acknowledgementEnvelope().messageId());
	}

	public static Optional<CompletedRunRemoteArtifactRetirement> completedRunRemoteArtifactRetirement(
		UUID runId, UUID goalId, RemoteRequestKey key, RecoveryLedger.State recoveryState,
		Map<UUID, RemoteAgentRadioDispatch> dispatches,
		Map<RemoteRequestKey, RemoteRecoveryExchange> recoveryExchanges,
		DurableRadioOutbox.State outbox,
		java.util.function.Predicate<UUID> deliveredRouteRetirable) {
		runId = Objects.requireNonNull(runId, "runId");
		goalId = Objects.requireNonNull(goalId, "goalId");
		key = Objects.requireNonNull(key, "key");
		recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
		dispatches = Objects.requireNonNull(dispatches, "dispatches");
		recoveryExchanges = Objects.requireNonNull(recoveryExchanges, "recoveryExchanges");
		outbox = Objects.requireNonNull(outbox, "outbox");
		deliveredRouteRetirable = Objects.requireNonNull(deliveredRouteRetirable,
			"deliveredRouteRetirable");
		if (!key.runId().equals(runId)) return Optional.empty();
		UUID exactRunId = runId;
		List<Map.Entry<UUID, RemoteAgentRadioDispatch>> runDispatches = dispatches.entrySet()
			.stream().filter(entry -> entry.getValue().intent().runId().equals(exactRunId)).toList();
		List<Map.Entry<RemoteRequestKey, RemoteRecoveryExchange>> runExchanges =
			recoveryExchanges.entrySet().stream()
				.filter(entry -> entry.getValue().key().runId().equals(exactRunId)).toList();
		if (runDispatches.isEmpty() && runExchanges.isEmpty())
			return Optional.of(new CompletedRunRemoteArtifactRetirement(runId,
				Optional.empty(), Optional.empty()));
		if (runDispatches.size() != 1 || runExchanges.size() != 1) return Optional.empty();
		Map.Entry<UUID, RemoteAgentRadioDispatch> dispatchEntry = runDispatches.getFirst();
		Map.Entry<RemoteRequestKey, RemoteRecoveryExchange> exchangeEntry =
			runExchanges.getFirst();
		RemoteAgentRadioDispatch dispatch = dispatchEntry.getValue();
		RemoteRecoveryExchange exchange = exchangeEntry.getValue();
		RecoveryLedger.AcknowledgedTombstone tombstone =
			recoveryState.acknowledgedTombstones().get(key);
		RecoveryLedger.LineageHighWatermark lineage = recoveryState.lineages().get(
			new RecoveryLedger.LineageId(key.requestId(), key.runId()));
		if (!dispatchEntry.getKey().equals(dispatch.messageId())
			|| !exchangeEntry.getKey().equals(key) || !exchange.key().equals(key)
			|| !exchange.goalId().equals(goalId)
			|| !dispatch.committed()
			|| !dispatch.intent().intentId().equals(key.requestId())
			|| dispatch.intent().generation() != key.fence()
			|| dispatch.agentGeneration() != key.agentGeneration()
			|| exchange.phase() != RemoteRecoveryPhase.RECONCILED
			|| tombstone == null || tombstone.outcome() != RemoteAgentRecord.TerminalOutcome.COMPLETED
				&& tombstone.outcome() != RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED
			|| tombstone.revision() != exchange.terminalRevision()
			|| lineage == null || !lineage.key().equals(key) || !lineage.terminalAcknowledged()
			|| !lineage.sourceClusterId().equals(dispatch.intent().clusterId())
			|| !lineage.agentId().equals(dispatch.agentId())) return Optional.empty();
		LinkedHashSet<UUID> messageIds = new LinkedHashSet<>(recoveryMessageIds(exchange));
		messageIds.add(dispatch.messageId());
		if (messageIds.size() != 5 || messageIds.stream()
			.anyMatch(outbox.entries()::containsKey)
			|| !messageIds.stream().allMatch(deliveredRouteRetirable)) return Optional.empty();
		return Optional.of(new CompletedRunRemoteArtifactRetirement(runId,
			Optional.of(dispatch), Optional.of(exchange)));
	}

	private static long confirmedOutputCountForRun(FrozenProductionPlan plan,
		ProductionRun receivedRun, Map<StackKey, Long> creditedOutputs) {
		plan = Objects.requireNonNull(plan, "plan");
		receivedRun = Objects.requireNonNull(receivedRun, "receivedRun");
		creditedOutputs = Map.copyOf(Objects.requireNonNull(creditedOutputs,
			"creditedOutputs"));
		if (!receivedRun.goalId().equals(plan.goalId())
			|| !receivedRun.patternId().equals(plan.patternId()))
			throw new IllegalArgumentException("Received Run differs from its production plan");
		if (creditedOutputs.entrySet().stream().anyMatch(entry -> entry.getValue() < 0))
			throw new IllegalArgumentException("Received Run has negative output evidence");
		return creditedOutputs.getOrDefault(plan.output(), 0L);
	}

	public static OptionalLong activeRunRetirementCredit(RecoveryLedger.State recoveryState,
		RemoteRequestKey key, ProductionOutputContract.Snapshot output, ProductionOutputPolicy policy,
		FrozenProductionPlan plan, GoalObservationState observation) {
		recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
		key = Objects.requireNonNull(key, "key");
		RemoteRequestKey exactKey = key;
		output = Objects.requireNonNull(output, "output");
		policy = Objects.requireNonNull(policy, "policy");
		plan = Objects.requireNonNull(plan, "plan");
		observation = Objects.requireNonNull(observation, "observation");
		ProductionRun receivedRun = output.run();
		if (!key.runId().equals(receivedRun.runId()) || key.fence() != receivedRun.fence()
			|| key.agentGeneration() != output.agentResetGeneration()
			|| output.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED
			|| plan.cancelAfterDrain() || !plan.goalId().equals(receivedRun.goalId())
			|| receivedRun.outputPolicyGeneration() != policy.generation()
			|| !receivedRun.returnWarehouseRoute().equals(policy.returnWarehouseRoute())
			|| !observation.goalId().equals(plan.goalId())
			|| !observation.clusterId().equals(plan.clusterId())
			|| !observation.scope().equals(plan.observationScope())
			|| observation.targetCount() != plan.requestedOutputCount()
			|| plan.runs().currentRun() != null
				&& plan.runs().currentRun().runId().equals(receivedRun.runId()))
			return OptionalLong.empty();
		RemoteAgentRecord record = recoveryState.records().get(key);
		RecoveryLedger.AcknowledgedTombstone tombstone =
			recoveryState.acknowledgedTombstones().get(key);
		RecoveryLedger.LineageHighWatermark lineage = recoveryState.lineages().values().stream()
			.filter(candidate -> candidate.key().equals(exactKey)).findFirst().orElse(null);
		boolean exactLineage = lineage != null
			&& lineage.sourceClusterId().equals(plan.clusterId())
			&& lineage.agentId().equals(output.agentId())
			&& receivedRun.inputRoutes().contains(lineage.targetRoute());
		boolean terminalCompleted = exactLineage && ((record != null && record.isTerminal()
			&& record.ticket().sourceClusterId().equals(plan.clusterId())
			&& record.ticket().agentId().equals(output.agentId())
			&& record.ticket().patternId().equals(receivedRun.patternId())
			&& record.ticket().mode() == receivedRun.inputMode()
			&& record.ticket().multiplier() == receivedRun.runMultiplier()
			&& record.terminalOutcome().filter(
				RemoteAgentRecord.TerminalOutcome.COMPLETED::equals).isPresent()
			&& !recoveryState.pendingEffects().containsKey(key))
			|| tombstone != null
				&& lineage.terminalAcknowledged()
				&& tombstone.outcome() == RemoteAgentRecord.TerminalOutcome.COMPLETED);
		if (!terminalCompleted) return OptionalLong.empty();
		ProductionOutputPolicy.RequiredOutput required = policy.requiredOutputs().get(plan.output());
		CreateRouteKey expectedDestination = policy.deliveryMode()
			== AgentEndpointState.OutputMode.DIRECT_REQUIRED
			? plan.deliveryRoute() : policy.returnWarehouseRoute();
		if (!policy.noOutput() && (required == null
			|| !required.destination().equals(expectedDestination))) return OptionalLong.empty();
		long produced = confirmedOutputCountForRun(plan, receivedRun,
			output.creditedOutputs());
		long receiptAuthorized = policy.noOutput() ? 0
			: Math.min(produced, required.targetCount());
		long remaining = Math.subtractExact(observation.targetCount(),
			observation.confirmedOutputCount());
		return OptionalLong.of(Math.min(receiptAuthorized, remaining));
	}

	public static boolean cancelledRunRetirementReady(RecoveryLedger.State recoveryState,
		RemoteRequestKey key, ProductionOutputContract.Snapshot output, ProductionOutputPolicy policy,
		FrozenProductionPlan plan) {
		recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
		key = Objects.requireNonNull(key, "key");
		RemoteRequestKey exactKey = key;
		output = Objects.requireNonNull(output, "output");
		policy = Objects.requireNonNull(policy, "policy");
		plan = Objects.requireNonNull(plan, "plan");
		ProductionRun run = output.run();
		ProductionRun current = plan.runs().currentRun();
		if (!plan.cancelAfterDrain() || current == null || !current.equals(run)
			|| !key.runId().equals(run.runId()) || key.fence() != run.fence()
			|| key.agentGeneration() != output.agentResetGeneration()
			|| output.milestone() != ProductionOutputContract.Milestone.OUTPUT_RECEIVED
			|| !output.agentReleased() || !plan.goalId().equals(run.goalId())
			|| run.outputPolicyGeneration() != policy.generation()
			|| !run.returnWarehouseRoute().equals(policy.returnWarehouseRoute())) return false;
		RemoteAgentRecord record = recoveryState.records().get(key);
		RecoveryLedger.AcknowledgedTombstone tombstone =
			recoveryState.acknowledgedTombstones().get(key);
		RecoveryLedger.LineageHighWatermark lineage = recoveryState.lineages().values().stream()
			.filter(candidate -> candidate.key().equals(exactKey)).findFirst().orElse(null);
		boolean exactLineage = lineage != null
			&& lineage.sourceClusterId().equals(plan.clusterId())
			&& lineage.agentId().equals(output.agentId())
			&& run.inputRoutes().contains(lineage.targetRoute());
		return exactLineage && ((record != null && record.isTerminal()
			&& record.condition() == RemoteAgentRecord.RecoveryCondition.CANCEL_DRAIN
			&& record.terminalOutcome().filter(
				RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED::equals).isPresent()
			&& !recoveryState.pendingEffects().containsKey(key))
			|| tombstone != null && lineage.terminalAcknowledged()
				&& tombstone.outcome() == RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED);
	}

	public static Optional<RemoteRecoveryPhase> visibleRemoteRecoveryPhase(UUID goalId,
		Map<RemoteRequestKey, RemoteRecoveryExchange> activeExchanges,
		@Nullable GoalObservationState observation, RecoveryLedger.State recoveryState) {
		UUID exactGoalId = Objects.requireNonNull(goalId, "goalId");
		activeExchanges = Map.copyOf(Objects.requireNonNull(activeExchanges,
			"activeExchanges"));
		recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
		Optional<RemoteRecoveryPhase> active = activeExchanges.values().stream()
			.filter(exchange -> exchange.goalId().equals(exactGoalId))
			.map(RemoteRecoveryExchange::phase).findFirst();
		if (active.isPresent() || observation == null
			|| !observation.goalId().equals(exactGoalId)) return active;
		UUID receivedRunId = observation.lastReceivedRunId().orElse(null);
		if (receivedRunId == null) return Optional.empty();
		for (Map.Entry<RemoteRequestKey, RecoveryLedger.AcknowledgedTombstone> entry
			: recoveryState.acknowledgedTombstones().entrySet()) {
			RemoteRequestKey key = entry.getKey();
			RecoveryLedger.AcknowledgedTombstone tombstone = entry.getValue();
			if (!key.runId().equals(receivedRunId)
				|| tombstone.outcome() != RemoteAgentRecord.TerminalOutcome.COMPLETED) continue;
			RecoveryLedger.LineageHighWatermark lineage = recoveryState.lineages().get(
				new RecoveryLedger.LineageId(key.requestId(), key.runId()));
			if (lineage != null && lineage.key().equals(key)
				&& lineage.sourceClusterId().equals(observation.clusterId())
				&& lineage.terminalAcknowledged())
				return Optional.of(RemoteRecoveryPhase.RECONCILED);
		}
		return Optional.empty();
	}

	private static Optional<RemoteRequestKey> exactRecoveryKeyForOutput(
		RecoveryLedger.State recoveryState, ProductionOutputContract.Snapshot output,
		RemoteAgentRecord.TerminalOutcome outcome) {
		recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
		output = Objects.requireNonNull(output, "output");
		outcome = Objects.requireNonNull(outcome, "outcome");
		ArrayList<RemoteRequestKey> candidates = new ArrayList<>();
		for (Map.Entry<RemoteRequestKey, RemoteAgentRecord> entry
			: recoveryState.records().entrySet())
			if (entry.getKey().runId().equals(output.runId())
				&& entry.getKey().fence() == output.run().fence()
				&& entry.getKey().agentGeneration() == output.agentResetGeneration()
				&& entry.getValue().ticket().agentId().equals(output.agentId())
				&& entry.getValue().terminalOutcome().filter(outcome::equals).isPresent())
				candidates.add(entry.getKey());
		for (Map.Entry<RemoteRequestKey, RecoveryLedger.AcknowledgedTombstone> entry
			: recoveryState.acknowledgedTombstones().entrySet()) {
			RemoteRequestKey key = entry.getKey();
			RecoveryLedger.LineageHighWatermark lineage = recoveryState.lineages().values().stream()
				.filter(candidate -> candidate.key().equals(key)).findFirst().orElse(null);
			if (key.runId().equals(output.runId()) && key.fence() == output.run().fence()
				&& key.agentGeneration() == output.agentResetGeneration()
				&& entry.getValue().outcome() == outcome
				&& lineage != null && lineage.agentId().equals(output.agentId())) candidates.add(key);
		}
		return candidates.stream().max(Comparator
			.comparingLong(RemoteRequestKey::sourceGeneration)
			.thenComparingLong(RemoteRequestKey::agentGeneration));
	}
	@FunctionalInterface
	public interface CompletedRunRemoteArtifactRetirementWriter {
		void persist(CompletedRunRemoteArtifactRetirement retirement);
	}

	/** Recheck the pair, persist atomically, then and only then retire the live transport. */
	public static void publishCompletedRunRemoteArtifactRetirement(
		CompletedRunRemoteArtifactRetirementWriter durablePublication,
		CompletedRunRemoteArtifactRetirement retirement, Transport transport) {
		Objects.requireNonNull(durablePublication, "durablePublication");
		Objects.requireNonNull(retirement, "retirement");
		Objects.requireNonNull(transport, "transport");
		if (!transport.matchesCurrentPair(retirement))
			throw new IllegalStateException("Completed remote Run artifacts changed before durable retirement");
		durablePublication.persist(retirement);
		transport.removeExactPair(retirement);
	}
}
