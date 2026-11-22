package com.nobodiiiii.createbiotech.content.biocomputing.application.production.output;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/**
 * Durable control-plane boundary for output attribution, physical dispatch, and receipt.
 * Mutable world state enters only as immutable events and stock observations.
 */
public final class ProductionOutputContract {
	private final UUID agentId;
	private final long agentResetGeneration;
	private final UUID outputObservationEpoch;
	private final ProductionRun run;
	private final ProductionOutputPolicy policy;
	private final int priorNoProgressRuns;
	private final Store store;
	private final LinkedHashMap<UUID, PendingOutput> pending = new LinkedHashMap<>();
	private final LinkedHashMap<CreateRouteKey, Map<StackKey, Long>> dispatched =
		new LinkedHashMap<>();
	private final LinkedHashMap<UUID, ProductionOutputLedger.DispatchedEvent> dispatchedEvents =
		new LinkedHashMap<>();
	private final LinkedHashMap<StackKey, Long> credited = new LinkedHashMap<>();
	private final LinkedHashMap<StackKey, Long> diagnostics = new LinkedHashMap<>();
	private final LinkedHashSet<UUID> seenOutputEventIds = new LinkedHashSet<>();
	private final LinkedHashMap<CreateRouteKey, ProcessLease> observationLeases =
		new LinkedHashMap<>();
	private final LinkedHashMap<CreateRouteKey, ProcessLease> destinationLeases =
		new LinkedHashMap<>();
	private final LinkedHashSet<StockObservationScope> satisfiedStockScopes =
		new LinkedHashSet<>();
	private final LinkedHashSet<ReceiverDestination> acceptedAgentReceipts =
		new LinkedHashSet<>();
	private final ArrayList<LateWarehouseMaterial> lateWarehouseMaterial = new ArrayList<>();

	private Milestone milestone = Milestone.WAITING_INPUT_READY;
	@Nullable private WaitReason waitReason;
	@Nullable private OutputProcessCapacity capacity;
	@Nullable private SealOutcome sealOutcome;
	@Nullable private DispatchInstruction dispatchIntent;
	@Nullable private ReadinessEvidence readinessEvidence;
	@Nullable private FailureState failure;
	private boolean inputEnded;
	private boolean signalObserved;
	private boolean firstOutputObserved;
	private boolean agentReleased;
	private long firstOutputElapsedTicks;
	private long quietElapsedTicks;
	private long probabilityElapsedTicks;
	private long lastReadinessObservationSequence = -1;
	private long confirmedDispatchSequence;

	public ProductionOutputContract(UUID agentId, long agentResetGeneration,
		UUID outputObservationEpoch, ProductionRun run, ProductionOutputPolicy policy,
		int priorNoProgressRuns, Store store) {
		this.agentId = Objects.requireNonNull(agentId, "agentId");
		if (agentResetGeneration < 0 || priorNoProgressRuns < 0)
			throw new IllegalArgumentException("Agent output generations cannot be negative");
		this.agentResetGeneration = agentResetGeneration;
		this.outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
			"outputObservationEpoch");
		this.run = Objects.requireNonNull(run, "run");
		this.policy = Objects.requireNonNull(policy, "policy");
		this.priorNoProgressRuns = priorNoProgressRuns;
		this.store = Objects.requireNonNull(store, "store");
		if (run.outputPolicyGeneration() != policy.generation()
			|| !run.returnWarehouseRoute().equals(policy.returnWarehouseRoute()))
			throw new IllegalArgumentException("Run and frozen output policy differ");
		persist();
	}

	private ProductionOutputContract(Snapshot snapshot, ProductionOutputPolicy policy,
		Optional<ProductionOutputLedger> durableLedger, Store store) {
		snapshot = Objects.requireNonNull(snapshot, "snapshot");
		this.policy = Objects.requireNonNull(policy, "policy");
		this.store = Objects.requireNonNull(store, "store");
		this.agentId = snapshot.agentId();
		this.agentResetGeneration = snapshot.agentResetGeneration();
		this.outputObservationEpoch = snapshot.outputObservationEpoch();
		this.run = snapshot.run();
		this.priorNoProgressRuns = snapshot.priorNoProgressRuns();
		if (run.outputPolicyGeneration() != policy.generation()
			|| !run.returnWarehouseRoute().equals(policy.returnWarehouseRoute()))
			throw new IllegalArgumentException("Run and frozen output policy differ");

		this.milestone = snapshot.milestone();
		this.waitReason = snapshot.waitReason().orElse(null);
		this.inputEnded = snapshot.inputEnded();
		this.signalObserved = snapshot.signalObserved();
		this.firstOutputObserved = snapshot.firstOutputObserved();
		this.firstOutputElapsedTicks = snapshot.firstOutputElapsedTicks();
		this.quietElapsedTicks = snapshot.quietElapsedTicks();
		this.probabilityElapsedTicks = snapshot.probabilityElapsedTicks();
		this.lastReadinessObservationSequence = snapshot.lastReadinessObservationSequence();
		this.confirmedDispatchSequence = snapshot.confirmedDispatchSequence();
		this.credited.putAll(snapshot.creditedOutputs());
		this.diagnostics.putAll(snapshot.diagnosticOutputs());
		if (snapshot.pendingOutputs().stream().map(PendingOutput::eventId).distinct().count()
			!= snapshot.pendingOutputs().size())
			throw new IllegalArgumentException("Snapshot has duplicate pending output events");
		snapshot.pendingOutputs().forEach(output -> pending.put(output.eventId(), output));
		snapshot.dispatchedOutputs().forEach((route, counts) ->
			dispatched.put(route, new LinkedHashMap<>(counts)));
		this.dispatchedEvents.putAll(snapshot.dispatchedEvents());
		this.seenOutputEventIds.addAll(snapshot.seenOutputEventIds());
		this.satisfiedStockScopes.addAll(snapshot.satisfiedStockScopes());
		this.acceptedAgentReceipts.addAll(snapshot.acceptedAgentReceipts());
		this.lateWarehouseMaterial.addAll(snapshot.lateWarehouseMaterial());
		this.dispatchIntent = snapshot.dispatchIntent().orElse(null);
		this.readinessEvidence = snapshot.readinessEvidence().orElse(null);
		this.sealOutcome = snapshot.sealOutcome().orElse(null);
		this.failure = snapshot.failure().orElse(null);
		this.agentReleased = snapshot.agentReleased();
		validateRestoredSnapshot();

		durableLedger.ifPresent(this::applyDurableLedger);
	}

	/** Rehydrates durable output state without trusting any pre-crash process lease. */
	public static ProductionOutputContract restore(Snapshot snapshot, ProductionOutputPolicy policy,
		Optional<ProductionOutputLedger> durableLedger, Store store) {
		return new ProductionOutputContract(snapshot, policy,
			Objects.requireNonNull(durableLedger, "durableLedger"), store);
	}

	/** Rebinds restored control state to the current authoritative capacity allocator. */
	public boolean reattachCapacity(OutputProcessCapacity capacity) {
		Objects.requireNonNull(capacity, "capacity");
		if (this.capacity != null && this.capacity != capacity)
			throw new IllegalStateException("Output capacity is already attached");
		this.capacity = capacity;
		boolean ready = true;
		if (milestone == Milestone.OBSERVING && policy.completionMode() == AgentCompletionMode.QUIET)
			ready = acquireObservationLeases();
		if (milestone == Milestone.OUTPUT_SEALED)
			ready &= acquirePendingDestinations();
		persist();
		return ready;
	}

	/** Atomically acquires all QUIET observers before input dispatch is allowed. */
	public StartResult beginAtInputReady(OutputProcessCapacity capacity) {
		Objects.requireNonNull(capacity, "capacity");
		if (milestone != Milestone.WAITING_INPUT_READY)
			return new StartResult(milestone == Milestone.OBSERVING, Optional.ofNullable(waitReason));
		this.capacity = capacity;
		if (policy.completionMode() == AgentCompletionMode.QUIET && !acquireObservationLeases()) {
			persist();
			return new StartResult(false, Optional.of(waitReason));
		}
		milestone = Milestone.OBSERVING;
		waitReason = null;
		persist();
		return new StartResult(true, Optional.empty());
	}

	public boolean inputDispatchAllowed() {
		return milestone == Milestone.OBSERVING && capacity != null
			&& observationLeases.values().stream()
			.allMatch(lease -> capacity.isLeaseActive(lease,
				LeasePurpose.QUIET_OBSERVATION, run.runId(), run.goalId()));
	}

	/** Releases provisional output compute only before any input/output world boundary exists. */
	public boolean abortBeforeInputDispatch() {
		if (milestone != Milestone.OBSERVING || inputEnded || signalObserved
			|| firstOutputObserved || !seenOutputEventIds.isEmpty() || !pending.isEmpty()
			|| dispatchIntent != null || agentReleased) return false;
		releaseAll(observationLeases);
		releaseAll(destinationLeases);
		capacity = null;
		milestone = Milestone.WAITING_INPUT_READY;
		waitReason = null;
		readinessEvidence = null;
		lastReadinessObservationSequence = -1;
		persist();
		return true;
	}
	public Milestone milestone() { return milestone; }
	public Optional<WaitReason> waitReason() { return Optional.ofNullable(waitReason); }
	public Map<StackKey, Long> creditedOutputs() { return Map.copyOf(credited); }
	public Map<StackKey, Long> diagnosticOutputs() { return Map.copyOf(diagnostics); }
	public Optional<SealOutcome> sealOutcome() { return Optional.ofNullable(sealOutcome); }
	public int pendingOutputCount() { return pending.size(); }
	public boolean agentReleased() { return agentReleased; }
	public List<LateWarehouseMaterial> lateWarehouseMaterial() {
		return List.copyOf(lateWarehouseMaterial);
	}

	public OutputObservation observeOutput(OutputEvent event) {
		event = Objects.requireNonNull(event, "event");
		if (milestone == Milestone.FAILED) return OutputObservation.CONTRACT_FAILED;
		if (milestone == Milestone.WAITING_INPUT_READY)
			return OutputObservation.OBSERVATION_NOT_STARTED;
		if (!event.outputObservationEpoch().equals(outputObservationEpoch))
			return OutputObservation.WRONG_EPOCH;
		if (!policy.observedOutputRoutes().contains(event.outputAddress()))
			return OutputObservation.UNWATCHED_ADDRESS;
		if (seenOutputEventIds.contains(event.eventId())) return OutputObservation.DUPLICATE;
		if (milestone != Milestone.OBSERVING) {
			final long nextDiagnostic;
			try {
				nextDiagnostic = Math.addExact(diagnostics.getOrDefault(event.stack(), 0L),
					event.count());
			} catch (ArithmeticException overflow) {
				failArithmetic(event.eventId(), null);
				return OutputObservation.ARITHMETIC_FAILURE;
			}
			seenOutputEventIds.add(event.eventId());
			diagnostics.put(event.stack(), nextDiagnostic);
			lateWarehouseMaterial.add(new LateWarehouseMaterial(event.eventId(),
				policy.returnWarehouseRoute(), event.stack(), event.count()));
			if (milestone == Milestone.OUTPUT_SEALED) {
				pending.put(event.eventId(), new PendingOutput(event.eventId(),
					policy.returnWarehouseRoute(), event.stack(), event.count(), false));
				acquirePendingDestinations();
			}
			persist();
			return OutputObservation.LATE_DIAGNOSTIC;
		}

		boolean required = policy.requiredOutputs().containsKey(event.stack());
		long nextCredited = 0;
		if (required) {
			try {
				nextCredited = Math.addExact(credited.getOrDefault(event.stack(), 0L),
					event.count());
			} catch (ArithmeticException overflow) {
				failArithmetic(event.eventId(), null);
				return OutputObservation.ARITHMETIC_FAILURE;
			}
		}
		CreateRouteKey destination = policy.deliveryMode() == AgentEndpointState.OutputMode.DIRECT_REQUIRED
			&& required ? policy.requiredOutputs().get(event.stack()).destination()
			: policy.returnWarehouseRoute();
		seenOutputEventIds.add(event.eventId());
		if (required) credited.put(event.stack(), nextCredited);
		pending.put(event.eventId(), new PendingOutput(event.eventId(), destination, event.stack(),
			event.count(), required));
		firstOutputObserved = true;
		quietElapsedTicks = 0;
		waitReason = null;
		acquirePendingDestinations();
		persist();
		return OutputObservation.ATTRIBUTED;
	}

	public boolean markInputEnded(long fence) {
		if (milestone != Milestone.OBSERVING || fence != run.fence()) return false;
		inputEnded = true;
		persist();
		return true;
	}

	public boolean publishSignal(long fence) {
		if (milestone != Milestone.OBSERVING || fence != run.fence()
			|| policy.completionMode() != AgentCompletionMode.SIGNAL) return false;
		signalObserved = true;
		persist();
		return true;
	}

	/** Advances only from a fresh opaque server-thread readiness observation. */
	public void advanceCompletionClock(OutputLogisticsGateway.ReadinessSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		if (milestone != Milestone.OBSERVING) return;
		if (!snapshot.request().equals(readinessRequest())) {
			waitReason = WaitReason.AGENT_UNLOADED;
			persist();
			return;
		}
		lastReadinessObservationSequence = snapshot.state().observationSequence();
		readinessEvidence = new ReadinessEvidence(snapshot.state().endpoints());
		if (!readinessReady()) {
			waitReason = WaitReason.AGENT_UNLOADED;
			persist();
			return;
		}
		if (policy.completionMode() == AgentCompletionMode.QUIET
			&& !acquireObservationLeases()) {
			persist();
			return;
		}
		waitReason = null;
		long elapsedTicks = snapshot.state().continuouslyReadyElapsedTicks();
		if (!firstOutputObserved || policy.noOutput())
			firstOutputElapsedTicks = saturatedAdd(firstOutputElapsedTicks, elapsedTicks);
		if (policy.probabilisticPrimary())
			probabilityElapsedTicks = saturatedAdd(probabilityElapsedTicks, elapsedTicks);
		if (policy.completionMode() == AgentCompletionMode.QUIET && firstOutputObserved)
			quietElapsedTicks = saturatedAdd(quietElapsedTicks,
				snapshot.state().continuouslyDrainedElapsedTicks());
		acquirePendingDestinations();
		trySeal();
		persist();
	}

	public OutputLogisticsGateway.ReadinessRequest readinessRequest() {
		return new OutputLogisticsGateway.ReadinessRequest(agentId, agentResetGeneration,
			run.runId(), run.goalId(), run.fence(), outputObservationEpoch,
			policy.observedOutputRoutes(), lastReadinessObservationSequence);
	}

	public OutputLogisticsGateway.DrainRequest drainRequest() {
		return new OutputLogisticsGateway.DrainRequest(agentId, agentResetGeneration, run.runId(),
			run.goalId(), run.fence(), outputObservationEpoch, policy.observedOutputRoutes(),
			confirmedDispatchSequence);
	}

	/** Oldest-pending FIFO; a DIRECT_REQUIRED output never moves without its address lease. */
	public DispatchDecision nextDispatch() {
		if (milestone != Milestone.OUTPUT_SEALED || pending.isEmpty())
			return new DispatchDecision(Optional.empty(), Optional.empty());
		if (dispatchIntent != null)
			return new DispatchDecision(Optional.empty(), Optional.empty());
		acquirePendingDestinations();
		PendingOutput next = pending.values().stream().filter(output ->
			policy.deliveryMode() != AgentEndpointState.OutputMode.DIRECT_REQUIRED
				|| destinationLeases.containsKey(output.destination())).findFirst().orElse(null);
		if (next == null) {
			waitReason = WaitReason.OUTPUT_COMPUTE;
			persist();
			return new DispatchDecision(Optional.empty(), Optional.of(waitReason));
		}
		waitReason = sealOutcome != null && sealOutcome.probabilityStalled()
			? WaitReason.PROBABILITY_STALLED : null;
		dispatchIntent = new DispatchInstruction(UUID.randomUUID(), next.eventId(), agentId,
			agentResetGeneration, run.runId(), run.goalId(), run.fence(), outputObservationEpoch,
			next.destination(), next.stack(), next.count(), next.credited());
		persist();
		return new DispatchDecision(Optional.of(dispatchIntent), Optional.empty());
	}

	public boolean confirmDispatch(OutputLogisticsGateway.CommitReceipt receipt) {
		receipt = Objects.requireNonNull(receipt, "receipt");
		if (milestone != Milestone.OUTPUT_SEALED) return false;
		if (dispatchIntent == null || !dispatchIntent.equals(receipt.instruction())) return false;
		PendingOutput output = pending.get(dispatchIntent.eventId());
		if (output == null) return false;
		final long nextDispatched;
		final long nextConfirmedDispatchSequence;
		try {
			nextDispatched = Math.addExact(dispatched
				.getOrDefault(output.destination(), Map.of()).getOrDefault(output.stack(), 0L),
				output.count());
			nextConfirmedDispatchSequence = Math.addExact(confirmedDispatchSequence, 1);
		} catch (ArithmeticException overflow) {
			failArithmetic(output.eventId(), receipt.operation().operationId());
			return false;
		}
		LinkedHashMap<StackKey, Long> nextCounts = new LinkedHashMap<>(
			dispatched.getOrDefault(output.destination(), Map.of()));
		nextCounts.put(output.stack(), nextDispatched);
		ProductionOutputLedger.DispatchedEvent dispatchedEvent =
			new ProductionOutputLedger.DispatchedEvent(output.eventId(),
				receipt.operation().operationId(), output.destination(), output.stack(), output.count(),
				output.credited());
		pending.remove(output.eventId());
		dispatchIntent = null;
		dispatched.put(output.destination(), nextCounts);
		dispatchedEvents.put(output.eventId(), dispatchedEvent);
		confirmedDispatchSequence = nextConfirmedDispatchSequence;
		if (pending.values().stream().noneMatch(candidate ->
			candidate.destination().equals(output.destination())))
			releaseDestination(output.destination());
		acquirePendingDestinations();
		persist();
		return true;
	}

	/** OUTPUT_DISPATCHED requires a fresh opaque server-thread drain observation. */
	public boolean completeDispatch(OutputLogisticsGateway.DrainSnapshot drain) {
		drain = Objects.requireNonNull(drain, "drain");
		boolean drained = drain.request().equals(drainRequest())
			&& drain.state().packagerHeldAndQueuedEmpty()
			&& drain.state().endpoints().values().stream().allMatch(endpoint -> endpoint.loaded()
				&& endpoint.empty()
				&& endpoint.observationEpoch().equals(outputObservationEpoch));
		if (milestone != Milestone.OUTPUT_SEALED || !pending.isEmpty() || dispatchIntent != null
			|| !drained) return false;
		SealOutcome outcome = Objects.requireNonNull(sealOutcome, "sealOutcome");
		ProductionOutputLedger ledger = new ProductionOutputLedger(run.runId(), run.goalId(), run.fence(),
			outputObservationEpoch, policy.generation(), policy.returnWarehouseRoute(),
			dispatchedEvents, dispatched, credited, new ProductionOutputLedger.SealFact(
				outcome.primaryCount(), outcome.probabilisticShortfall(), outcome.requiresAnotherRun(),
				outcome.consecutiveNoProgressRuns(), outcome.probabilityStalled(),
				outcome.completionBoundary()));
		store.persistLedger(ledger);
		releaseAll(destinationLeases);
		milestone = Milestone.OUTPUT_DISPATCHED;
		agentReleased = true;
		waitReason = WaitReason.OUTPUT_DELIVERY;
		persist();
		if (receiptSatisfied()) reconcileReceipt();
		persist();
		return true;
	}

	/** Bare targets have no way to manufacture this Agent-identity-bearing ACK. */
	public boolean publishAgentReceipt(AgentReceiptAck receipt) {
		AgentReceiptAck ack = Objects.requireNonNull(receipt, "receipt");
		if (!ack.runId().equals(run.runId()) || ack.fence() != run.fence()
			|| !ack.outputObservationEpoch().equals(outputObservationEpoch)) return false;
		ReceiverDestination receiver = new ReceiverDestination(ack.receiverAgentId(),
			ack.destination());
		boolean expected = policy.requiredOutputs().values().stream().anyMatch(required ->
			required.destination().equals(ack.destination())
				&& required.receiver() instanceof ProductionOutputPolicy.OutputReceiver.Agent agent
				&& agent.agentId().equals(ack.receiverAgentId()));
		if (!expected) return false;
		acceptedAgentReceipts.add(receiver);
		reconcileReceipt();
		persist();
		return true;
	}

	/** A stock fact may arrive before the dispatch control result and is retained by scope identity. */
	public boolean reconcileStock(StockObservationService.Observation observation) {
		observation = Objects.requireNonNull(observation, "observation");
		if (observation.status() != StockObservationService.Status.TARGET_REACHED
			|| observation.actualCount() < stockTarget(observation.scope())
			|| requiredStockScopes().stream().noneMatch(observation.scope()::equals)) return false;
		satisfiedStockScopes.add(observation.scope());
		reconcileReceipt();
		persist();
		return true;
	}

	public Snapshot snapshot() {
		return new Snapshot(agentId, agentResetGeneration, outputObservationEpoch, run,
			priorNoProgressRuns,
			milestone, Optional.ofNullable(waitReason), inputEnded, signalObserved,
			firstOutputObserved, firstOutputElapsedTicks, quietElapsedTicks, probabilityElapsedTicks,
			Map.copyOf(credited), Map.copyOf(diagnostics), List.copyOf(pending.values()),
			immutableNestedCounts(dispatched), Map.copyOf(dispatchedEvents),
			Set.copyOf(seenOutputEventIds),
			Set.copyOf(satisfiedStockScopes), Set.copyOf(acceptedAgentReceipts),
			List.copyOf(lateWarehouseMaterial), Optional.ofNullable(dispatchIntent),
			lastReadinessObservationSequence, confirmedDispatchSequence,
			Optional.ofNullable(readinessEvidence), Optional.ofNullable(sealOutcome),
			Optional.ofNullable(failure), agentReleased);
	}

	private void trySeal() {
		if (!inputEnded || milestone != Milestone.OBSERVING) return;
		if (!readinessReady()) {
			waitReason = WaitReason.AGENT_UNLOADED;
			return;
		}
		ProductionOutputLedger.BoundaryReason reason = completionBoundaryReason();
		if (reason == null) return;
		long primaryCount = primaryCount();
		boolean shortfall = policy.probabilisticPrimary() && primaryCount < primaryTarget();
		final int noProgress;
		try {
			noProgress = primaryCount == 0 && policy.probabilisticPrimary()
				? Math.addExact(priorNoProgressRuns, 1) : 0;
		} catch (ArithmeticException overflow) {
			failArithmetic(null, null);
			return;
		}
		boolean stalled = shortfall && noProgress >= policy.maxNoProgressRuns();
		SealOutcome computed = new SealOutcome(primaryCount, shortfall, shortfall && !stalled,
			noProgress, stalled, freezeCompletionBoundary(reason));
		milestone = Milestone.OUTPUT_SEALED;
		sealOutcome = computed;
		releaseAll(observationLeases);
		waitReason = stalled ? WaitReason.PROBABILITY_STALLED : null;
		acquirePendingDestinations();
	}

	private boolean readinessReady() {
		return readinessEvidence != null
			&& readinessEvidence.endpoints().keySet().equals(policy.observedOutputRoutes())
			&& readinessEvidence.endpoints().values().stream().allMatch(endpoint -> endpoint.loaded()
				&& endpoint.observationEpoch().equals(outputObservationEpoch));
	}

	private boolean readinessDrained() {
		return readinessReady() && readinessEvidence.endpoints().values().stream()
			.allMatch(OutputLogisticsGateway.EndpointReadiness::drained);
	}

	@Nullable
	private ProductionOutputLedger.BoundaryReason completionBoundaryReason() {
		if (policy.noOutput() && firstOutputElapsedTicks >= policy.firstOutputTimeoutTicks())
			return ProductionOutputLedger.BoundaryReason.NO_OUTPUT_TIMEOUT;
		if (policy.probabilisticPrimary()
			&& (!firstOutputObserved
				&& firstOutputElapsedTicks >= policy.firstOutputTimeoutTicks()
				|| policy.completionMode() == AgentCompletionMode.TARGET
				&& primaryCount() < primaryTarget()
				&& probabilityElapsedTicks >= policy.firstOutputTimeoutTicks()))
			return ProductionOutputLedger.BoundaryReason.PROBABILITY_TIMEOUT;
		return switch (policy.completionMode()) {
			case TARGET -> !policy.noOutput() && primaryCount() >= primaryTarget()
				? ProductionOutputLedger.BoundaryReason.TARGET_REACHED : null;
			case SIGNAL -> signalObserved
				? ProductionOutputLedger.BoundaryReason.SIGNAL_OBSERVED : null;
			case QUIET -> firstOutputObserved && quietElapsedTicks >= policy.quietWindowTicks()
				&& readinessDrained()
				? ProductionOutputLedger.BoundaryReason.QUIET_WINDOW : null;
		};
	}

	private ProductionOutputLedger.CompletionBoundary freezeCompletionBoundary(
		ProductionOutputLedger.BoundaryReason reason) {
		if (!inputEnded || !readinessReady() || lastReadinessObservationSequence < 0)
			throw new IllegalStateException("Completion boundary lacks fresh authoritative readiness");
		Map<CreateRouteKey, OutputLogisticsGateway.EndpointReadiness> endpoints =
			Objects.requireNonNull(readinessEvidence, "readinessEvidence").endpoints();
		return new ProductionOutputLedger.CompletionBoundary(policy.completionMode(), reason, inputEnded,
			signalObserved, firstOutputObserved, firstOutputElapsedTicks, quietElapsedTicks,
			probabilityElapsedTicks, lastReadinessObservationSequence, outputObservationEpoch,
			endpoints);
	}

	private void failArithmetic(@Nullable UUID eventId, @Nullable UUID nativeOperationId) {
		failure = new FailureState(FailureReason.ARITHMETIC_OVERFLOW,
			Optional.ofNullable(eventId), Optional.ofNullable(nativeOperationId));
		milestone = Milestone.FAILED;
		waitReason = WaitReason.RESET_REQUIRED;
		persist();
		releaseAll(observationLeases);
		releaseAll(destinationLeases);
	}

	private long primaryCount() {
		return policy.primaryOutput().map(item -> credited.getOrDefault(item, 0L)).orElse(0L);
	}

	private long primaryTarget() {
		return policy.primaryOutput().map(item -> policy.requiredOutputs().get(item).targetCount())
			.orElse(0L);
	}

	private Set<StockObservationScope> requiredStockScopes() {
		LinkedHashSet<StockObservationScope> scopes = new LinkedHashSet<>();
		policy.requiredOutputs().values().forEach(required ->
		{
			if (required.receiver() instanceof ProductionOutputPolicy.OutputReceiver.Bare bare)
				scopes.add(bare.scope());
		});
		return Set.copyOf(scopes);
	}

	private static long stockTarget(StockObservationScope scope) {
		return scope instanceof StockObservationScope.NetworkSummary network
			? network.targetLevel()
			: ((StockObservationScope.AttachedInventory) scope).targetLevel();
	}

	private boolean receiptSatisfied() {
		if (policy.noOutput()) return true;
		return !policy.requiredOutputs().isEmpty()
			&& policy.requiredOutputs().values().stream().allMatch(required -> {
				if (required.receiver() instanceof ProductionOutputPolicy.OutputReceiver.Bare bare)
					return satisfiedStockScopes.contains(bare.scope());
				ProductionOutputPolicy.OutputReceiver.Agent agent =
					(ProductionOutputPolicy.OutputReceiver.Agent) required.receiver();
				return acceptedAgentReceipts.contains(new ReceiverDestination(agent.agentId(),
					required.destination()));
			});
	}

	private void reconcileReceipt() {
		if (milestone == Milestone.OUTPUT_DISPATCHED && receiptSatisfied()) {
			milestone = Milestone.OUTPUT_RECEIVED;
			waitReason = null;
		}
	}

	private boolean acquireObservationLeases() {
		if (policy.completionMode() != AgentCompletionMode.QUIET) return true;
		if (capacity == null) {
			waitReason = WaitReason.OUTPUT_COMPUTE;
			return false;
		}
		observationLeases.entrySet().removeIf(entry -> !capacity.isLeaseActive(entry.getValue(),
			LeasePurpose.QUIET_OBSERVATION, run.runId(), run.goalId()));
		List<CreateRouteKey> addresses = policy.observedOutputRoutes().stream()
			.sorted(Comparator.comparing(CreateRouteKey::address)
				.thenComparing(route -> route.logisticsNetworkId().toString()))
			.toList();
		for (CreateRouteKey address : addresses) {
			if (observationLeases.containsKey(address)) continue;
			Optional<ProcessLease> acquired = capacity.tryAcquireObservation(run.runId(),
				run.goalId(), address);
			if (acquired.isEmpty()) {
				releaseAll(observationLeases);
				waitReason = WaitReason.OUTPUT_COMPUTE;
				return false;
			}
			ProcessLease lease = acquired.orElseThrow();
			if (!lease.address().equals(address) || !capacity.isLeaseActive(lease,
				LeasePurpose.QUIET_OBSERVATION, run.runId(), run.goalId())) {
				capacity.release(lease);
				releaseAll(observationLeases);
				throw new IllegalStateException("Observation capacity returned a lease for another address");
			}
			observationLeases.put(address, lease);
		}
		return true;
	}

	private void releaseDestination(CreateRouteKey destination) {
		ProcessLease lease = destinationLeases.remove(destination);
		if (lease != null) Objects.requireNonNull(capacity, "capacity").release(lease);
	}

	/** Acquires distinct destinations in first-pending order, sharing one lease per address. */
	private boolean acquirePendingDestinations() {
		if (policy.deliveryMode() != AgentEndpointState.OutputMode.DIRECT_REQUIRED
			|| pending.isEmpty()) return true;
		OutputProcessCapacity allocator = capacity;
		if (allocator == null) {
			waitReason = WaitReason.OUTPUT_COMPUTE;
			return false;
		}
		destinationLeases.entrySet().removeIf(entry -> !allocator.isLeaseActive(entry.getValue(),
			LeasePurpose.OUTPUT_DESTINATION, run.runId(), run.goalId()));
		LinkedHashSet<CreateRouteKey> addresses = new LinkedHashSet<>();
		pending.values().forEach(output -> addresses.add(output.destination()));
		boolean allAcquired = true;
		for (CreateRouteKey address : addresses) {
			if (destinationLeases.containsKey(address)) continue;
			Optional<ProcessLease> acquired = allocator.tryAcquireOutputDestination(run.runId(),
				run.goalId(), address);
			if (acquired.isEmpty()) {
				waitReason = WaitReason.OUTPUT_COMPUTE;
				allAcquired = false;
				continue;
			}
			ProcessLease lease = acquired.orElseThrow();
			if (!lease.address().equals(address) || !allocator.isLeaseActive(lease,
				LeasePurpose.OUTPUT_DESTINATION, run.runId(), run.goalId())) {
				allocator.release(lease);
				throw new IllegalStateException("Destination capacity returned a lease for another address");
			}
			destinationLeases.put(address, lease);
		}
		if (allAcquired && waitReason == WaitReason.OUTPUT_COMPUTE)
			waitReason = sealOutcome != null && sealOutcome.probabilityStalled()
				? WaitReason.PROBABILITY_STALLED : null;
		return allAcquired;
	}

	private void validateRestoredSnapshot() {
		LinkedHashSet<UUID> partitionedEvents = new LinkedHashSet<>(pending.keySet());
		partitionedEvents.addAll(dispatchedEvents.keySet());
		lateWarehouseMaterial.forEach(material -> partitionedEvents.add(material.eventId()));
		if (!seenOutputEventIds.equals(partitionedEvents))
			throw new IllegalArgumentException("Observed output identity partition is not exact");
		if (pending.keySet().stream().anyMatch(dispatchedEvents::containsKey))
			throw new IllegalArgumentException("An output event cannot be pending and dispatched");
		if (confirmedDispatchSequence != dispatchedEvents.size())
			throw new IllegalArgumentException("Dispatch sequence violates event conservation");
		if (!ProductionOutputLedger.aggregateDispatched(dispatchedEvents)
			.equals(immutableNestedCounts(dispatched)))
			throw new IllegalArgumentException("Dispatched aggregate violates event conservation");
		validateLateEvidence();
		LinkedHashMap<StackKey, Long> restoredCredited = new LinkedHashMap<>();
		for (PendingOutput output : pending.values()) {
			validateRestoredEvent(output.eventId(), output.destination(), output.stack(), output.count(),
				output.credited());
			if (output.credited()) mergeRestoredCredit(restoredCredited, output.stack(), output.count());
		}
		for (Map.Entry<UUID, ProductionOutputLedger.DispatchedEvent> entry
			: dispatchedEvents.entrySet()) {
			ProductionOutputLedger.DispatchedEvent output = entry.getValue();
			if (!entry.getKey().equals(output.eventId()))
				throw new IllegalArgumentException("Dispatched event key differs from its identity");
			validateRestoredEvent(output.eventId(), output.destination(), output.stack(), output.count(),
				output.credited());
			if (output.credited()) mergeRestoredCredit(restoredCredited, output.stack(), output.count());
		}
		if (!restoredCredited.equals(credited))
			throw new IllegalArgumentException("Credited aggregate violates event classification");
		if (dispatchIntent != null) {
			PendingOutput output = pending.get(dispatchIntent.eventId());
			if (milestone != Milestone.OUTPUT_SEALED && milestone != Milestone.FAILED || output == null
				|| !dispatchIntent.agentId().equals(agentId)
				|| dispatchIntent.agentResetGeneration() != agentResetGeneration
				|| !dispatchIntent.runId().equals(run.runId())
				|| !dispatchIntent.goalId().equals(run.goalId())
				|| dispatchIntent.fence() != run.fence()
				|| !dispatchIntent.outputObservationEpoch().equals(outputObservationEpoch)
				|| !dispatchIntent.destination().equals(output.destination())
				|| !dispatchIntent.stack().equals(output.stack())
				|| dispatchIntent.count() != output.count()
				|| dispatchIntent.credited() != output.credited())
				throw new IllegalArgumentException("Dispatch intent does not match sealed pending output");
		}
		boolean sealedOrLater = milestone == Milestone.OUTPUT_SEALED
			|| milestone == Milestone.OUTPUT_DISPATCHED || milestone == Milestone.OUTPUT_RECEIVED;
		if (sealedOrLater && sealOutcome == null
			|| !sealedOrLater && milestone != Milestone.FAILED && sealOutcome != null)
			throw new IllegalArgumentException("Seal outcome does not match output milestone");
		if (sealOutcome != null && !inputEnded)
			throw new IllegalArgumentException("Sealed output requires durable input-end evidence");
		if (sealOutcome != null) validateRestoredSealOutcome();
		boolean releasedMilestone = milestone == Milestone.OUTPUT_DISPATCHED
			|| milestone == Milestone.OUTPUT_RECEIVED;
		if (agentReleased != releasedMilestone)
			throw new IllegalArgumentException("Agent release does not match output milestone");
		if (releasedMilestone && (!pending.isEmpty() || dispatchIntent != null))
			throw new IllegalArgumentException("Released Agent cannot retain a dispatch backlog");
		if (milestone == Milestone.OUTPUT_DISPATCHED && waitReason != WaitReason.OUTPUT_DELIVERY)
			throw new IllegalArgumentException("Dispatched output must wait only for delivery");
		if (milestone == Milestone.OUTPUT_RECEIVED && waitReason != null)
			throw new IllegalArgumentException("Received output cannot retain a wait reason");
		if ((milestone == Milestone.FAILED) != (failure != null)
			|| milestone == Milestone.FAILED && waitReason != WaitReason.RESET_REQUIRED)
			throw new IllegalArgumentException("Failure evidence does not match output milestone");
		if (readinessEvidence != null
			&& !readinessEvidence.endpoints().keySet().equals(policy.observedOutputRoutes()))
			throw new IllegalArgumentException("Readiness evidence differs from watched routes");
		if (!requiredStockScopes().containsAll(satisfiedStockScopes))
			throw new IllegalArgumentException("Snapshot contains an unrelated stock fact");
		for (ReceiverDestination receipt : acceptedAgentReceipts) {
			boolean expected = policy.requiredOutputs().values().stream().anyMatch(required ->
				required.destination().equals(receipt.destination())
					&& required.receiver() instanceof ProductionOutputPolicy.OutputReceiver.Agent agent
					&& agent.agentId().equals(receipt.receiverAgentId()));
			if (!expected)
				throw new IllegalArgumentException("Snapshot contains an unrelated Agent receipt");
		}
	}

	private void validateLateEvidence() {
		LinkedHashSet<UUID> lateIds = new LinkedHashSet<>();
		LinkedHashMap<StackKey, Long> aggregateDiagnostics = new LinkedHashMap<>();
		for (LateWarehouseMaterial material : lateWarehouseMaterial) {
			if (!lateIds.add(material.eventId()) || !seenOutputEventIds.contains(material.eventId()))
				throw new IllegalArgumentException("Late material lacks unique observation identity");
			if (!material.destination().equals(policy.returnWarehouseRoute()))
				throw new IllegalArgumentException("Late material must be attributed to the warehouse");
			PendingOutput pendingEvent = pending.get(material.eventId());
			ProductionOutputLedger.DispatchedEvent dispatchedEvent =
				dispatchedEvents.get(material.eventId());
			if (pendingEvent != null && (!pendingEvent.destination().equals(material.destination())
				|| !pendingEvent.stack().equals(material.stack())
				|| pendingEvent.count() != material.count() || pendingEvent.credited())
				|| dispatchedEvent != null
				&& (!dispatchedEvent.destination().equals(material.destination())
					|| !dispatchedEvent.stack().equals(material.stack())
					|| dispatchedEvent.count() != material.count() || dispatchedEvent.credited()))
				throw new IllegalArgumentException(
					"Late material rewrites its physical dispatch manifest");
			try {
				aggregateDiagnostics.merge(material.stack(), material.count(), Math::addExact);
			} catch (ArithmeticException overflow) {
				throw new IllegalArgumentException("Late diagnostic aggregate overflows", overflow);
			}
		}
		if (!aggregateDiagnostics.equals(diagnostics))
			throw new IllegalArgumentException("Diagnostic aggregate violates late-event conservation");
		if ((milestone == Milestone.WAITING_INPUT_READY || milestone == Milestone.OBSERVING)
			&& !lateIds.isEmpty())
			throw new IllegalArgumentException("Pre-seal snapshot cannot contain late material");
		if (milestone == Milestone.OUTPUT_SEALED && lateIds.stream().anyMatch(eventId ->
			!pending.containsKey(eventId) && !dispatchedEvents.containsKey(eventId)))
			throw new IllegalArgumentException("Sealed late material must remain in dispatch conservation");
	}

	private void validateRestoredSealOutcome() {
		if (!readinessReady() || lastReadinessObservationSequence < 0)
			throw new IllegalArgumentException("Sealed output lacks fresh authoritative readiness");
		ProductionOutputLedger.BoundaryReason reason = completionBoundaryReason();
		if (reason == null)
			throw new IllegalArgumentException("Sealed output lacks its completion boundary");
		long primaryCount = primaryCount();
		boolean shortfall = policy.probabilisticPrimary() && primaryCount < primaryTarget();
		final int noProgress;
		try {
			noProgress = primaryCount == 0 && policy.probabilisticPrimary()
				? Math.addExact(priorNoProgressRuns, 1) : 0;
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Restored no-progress state overflows", overflow);
		}
		boolean stalled = shortfall && noProgress >= policy.maxNoProgressRuns();
		SealOutcome expected = new SealOutcome(primaryCount, shortfall, shortfall && !stalled,
			noProgress, stalled, freezeCompletionBoundary(reason));
		if (!expected.equals(sealOutcome))
			throw new IllegalArgumentException("Seal outcome differs from frozen output policy");
	}

	private void validateRestoredEvent(UUID eventId, CreateRouteKey destination, StackKey stack,
		long count, boolean eventCredited) {
		boolean required = policy.requiredOutputs().containsKey(stack);
		boolean exactLateMaterial = lateWarehouseMaterial.stream().anyMatch(material ->
			material.eventId().equals(eventId) && material.destination().equals(destination)
				&& material.stack().equals(stack) && material.count() == count);
		if (eventCredited && !required || eventCredited && exactLateMaterial
			|| required && !eventCredited && !exactLateMaterial)
			throw new IllegalArgumentException("Restored event violates frozen credit classification");
		CreateRouteKey expectedDestination = policy.deliveryMode()
			== AgentEndpointState.OutputMode.DIRECT_REQUIRED && eventCredited
				? policy.requiredOutputs().get(stack).destination()
				: policy.returnWarehouseRoute();
		if (!destination.equals(expectedDestination))
			throw new IllegalArgumentException("Restored output destination differs from frozen policy");
	}

	private static void mergeRestoredCredit(Map<StackKey, Long> counts, StackKey stack, long count) {
		try {
			counts.merge(stack, count, Math::addExact);
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Restored credited output overflows", overflow);
		}
	}

	private void applyDurableLedger(ProductionOutputLedger ledger) {
		Objects.requireNonNull(ledger, "ledger");
		if (sealOutcome == null)
			throw new IllegalArgumentException("Dispatch ledger needs the sealed boundary snapshot");
		if (!ledger.runId().equals(run.runId()) || !ledger.goalId().equals(run.goalId())
			|| ledger.fence() != run.fence()
			|| !ledger.outputObservationEpoch().equals(outputObservationEpoch)
			|| ledger.outputPolicyGeneration() != policy.generation()
			|| !ledger.returnWarehouseRoute().equals(policy.returnWarehouseRoute()))
			throw new IllegalArgumentException("Dispatch ledger belongs to another frozen Run");
		LinkedHashSet<UUID> knownPhysicalEvents = new LinkedHashSet<>(pending.keySet());
		knownPhysicalEvents.addAll(dispatchedEvents.keySet());
		if (!ledger.dispatchedEvents().keySet().equals(knownPhysicalEvents))
			throw new IllegalArgumentException("Dispatch ledger differs from the sealed event manifest");
		for (PendingOutput known : pending.values()) {
			ProductionOutputLedger.DispatchedEvent event = ledger.dispatchedEvents().get(known.eventId());
			if (!sameManifestEvent(known, event))
				throw new IllegalArgumentException("Dispatch ledger rewrites a known sealed output event");
		}
		for (ProductionOutputLedger.DispatchedEvent known : dispatchedEvents.values())
			if (!known.equals(ledger.dispatchedEvents().get(known.eventId())))
				throw new IllegalArgumentException("Dispatch ledger rewrites prior native dispatch proof");
		for (ProductionOutputLedger.DispatchedEvent event : ledger.dispatchedEvents().values()) {
			boolean required = policy.requiredOutputs().containsKey(event.stack());
			if (event.credited() && !required)
				throw new IllegalArgumentException("Ledger credits an undeclared output");
			CreateRouteKey expected = policy.deliveryMode()
				== AgentEndpointState.OutputMode.DIRECT_REQUIRED && event.credited()
					? policy.requiredOutputs().get(event.stack()).destination()
					: policy.returnWarehouseRoute();
			if (!event.destination().equals(expected))
				throw new IllegalArgumentException("Ledger event violates frozen output routing");
		}
		long primaryCount = policy.primaryOutput()
			.map(item -> ledger.creditedOutputs().getOrDefault(item, 0L)).orElse(0L);
		boolean shortfall = policy.probabilisticPrimary() && primaryCount < primaryTarget();
		final int noProgress;
		try {
			noProgress = primaryCount == 0 && policy.probabilisticPrimary()
				? Math.addExact(priorNoProgressRuns, 1) : 0;
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Ledger no-progress count overflows frozen state", overflow);
		}
		boolean stalled = shortfall && noProgress >= policy.maxNoProgressRuns();
		ProductionOutputLedger.SealFact expectedFact = new ProductionOutputLedger.SealFact(primaryCount,
			shortfall, shortfall && !stalled, noProgress, stalled,
			sealOutcome.completionBoundary());
		if (!ledger.sealFact().equals(expectedFact))
			throw new IllegalArgumentException("Ledger seal fact differs from frozen policy");
		if (!ledger.sealFact().equals(new ProductionOutputLedger.SealFact(
			sealOutcome.primaryCount(), sealOutcome.probabilisticShortfall(),
			sealOutcome.requiresAnotherRun(), sealOutcome.consecutiveNoProgressRuns(),
			sealOutcome.probabilityStalled(), sealOutcome.completionBoundary())))
			throw new IllegalArgumentException("Ledger differs from the sealed manifest outcome");
		pending.clear();
		dispatchIntent = null;
		dispatched.clear();
		ledger.dispatchedOutputs().forEach((route, counts) ->
			dispatched.put(route, new LinkedHashMap<>(counts)));
		dispatchedEvents.clear();
		dispatchedEvents.putAll(ledger.dispatchedEvents());
		confirmedDispatchSequence = dispatchedEvents.size();
		credited.clear();
		credited.putAll(ledger.creditedOutputs());
		sealOutcome = new SealOutcome(expectedFact.primaryCount(),
			expectedFact.probabilisticShortfall(), expectedFact.requiresAnotherRun(),
			expectedFact.consecutiveNoProgressRuns(), expectedFact.probabilityStalled(),
			expectedFact.completionBoundary());
		milestone = Milestone.OUTPUT_DISPATCHED;
		agentReleased = true;
		waitReason = WaitReason.OUTPUT_DELIVERY;
		reconcileReceipt();
	}

	private static boolean sameManifestEvent(PendingOutput known,
		@Nullable ProductionOutputLedger.DispatchedEvent event) {
		return event != null && event.eventId().equals(known.eventId())
			&& event.destination().equals(known.destination()) && event.stack().equals(known.stack())
			&& event.count() == known.count() && event.credited() == known.credited();
	}

	private void releaseAll(Map<CreateRouteKey, ProcessLease> leases) {
		if (capacity == null) return;
		List<ProcessLease> copied = new ArrayList<>(leases.values());
		leases.clear();
		copied.forEach(capacity::release);
	}

	private static Map<CreateRouteKey, Map<StackKey, Long>> immutableNestedCounts(
		Map<CreateRouteKey, ? extends Map<StackKey, Long>> values) {
		LinkedHashMap<CreateRouteKey, Map<StackKey, Long>> copied = new LinkedHashMap<>();
		values.forEach((route, counts) -> copied.put(route, Map.copyOf(counts)));
		return Map.copyOf(copied);
	}

	private static long saturatedAdd(long value, long addend) {
		try { return Math.addExact(value, addend); }
		catch (ArithmeticException exhausted) { return Long.MAX_VALUE; }
	}

	private void persist() { store.persist(snapshot()); }

	public enum Milestone {
		WAITING_INPUT_READY,
		OBSERVING,
		OUTPUT_SEALED,
		OUTPUT_DISPATCHED,
		OUTPUT_RECEIVED,
		FAILED
	}

	public enum OutputObservation {
		ATTRIBUTED,
		OBSERVATION_NOT_STARTED,
		DUPLICATE,
		WRONG_EPOCH,
		UNWATCHED_ADDRESS,
		LATE_DIAGNOSTIC,
		ARITHMETIC_FAILURE,
		CONTRACT_FAILED
	}

	public record StartResult(boolean started, Optional<WaitReason> waitReason) {
		public StartResult { waitReason = Objects.requireNonNull(waitReason, "waitReason"); }
	}

	public record OutputEvent(UUID eventId, UUID outputObservationEpoch,
		CreateRouteKey outputAddress, StackKey stack, long count) {
		public OutputEvent {
			eventId = Objects.requireNonNull(eventId, "eventId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			outputAddress = Objects.requireNonNull(outputAddress, "outputAddress");
			stack = Objects.requireNonNull(stack, "stack");
			if (count <= 0) throw new IllegalArgumentException("Output count must be positive");
		}
	}

	public record DispatchInstruction(UUID intentId, UUID eventId, UUID agentId,
		long agentResetGeneration, UUID runId, UUID goalId, long fence,
		UUID outputObservationEpoch, CreateRouteKey destination, StackKey stack,
		long count, boolean credited) {
		public DispatchInstruction {
			intentId = Objects.requireNonNull(intentId, "intentId");
			eventId = Objects.requireNonNull(eventId, "eventId");
			agentId = Objects.requireNonNull(agentId, "agentId");
			runId = Objects.requireNonNull(runId, "runId");
			goalId = Objects.requireNonNull(goalId, "goalId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			destination = Objects.requireNonNull(destination, "destination");
			stack = Objects.requireNonNull(stack, "stack");
			if (agentResetGeneration < 0 || fence < 0)
				throw new IllegalArgumentException("Dispatch fences cannot be negative");
			if (count <= 0) throw new IllegalArgumentException("Dispatch count must be positive");
		}
	}

	public record DispatchDecision(Optional<DispatchInstruction> instruction,
		Optional<WaitReason> waitReason) {
		public DispatchDecision {
			instruction = Objects.requireNonNull(instruction, "instruction");
			waitReason = Objects.requireNonNull(waitReason, "waitReason");
			if (instruction.isPresent() && waitReason.isPresent())
				throw new IllegalArgumentException("A dispatch decision cannot move and wait");
		}
	}

	public record AgentReceiptAck(UUID receiverAgentId, CreateRouteKey destination,
		UUID runId, long fence,
		UUID outputObservationEpoch) {
		public AgentReceiptAck {
			receiverAgentId = Objects.requireNonNull(receiverAgentId, "receiverAgentId");
			destination = Objects.requireNonNull(destination, "destination");
			runId = Objects.requireNonNull(runId, "runId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			if (fence < 0) throw new IllegalArgumentException("Receipt fence cannot be negative");
		}
	}

	public record SealOutcome(long primaryCount, boolean probabilisticShortfall,
		boolean requiresAnotherRun, int consecutiveNoProgressRuns,
		boolean probabilityStalled,
		ProductionOutputLedger.CompletionBoundary completionBoundary) {
		public SealOutcome {
			completionBoundary = Objects.requireNonNull(completionBoundary, "completionBoundary");
			if (primaryCount < 0 || consecutiveNoProgressRuns < 0)
				throw new IllegalArgumentException("Seal outcome counts cannot be negative");
			if (probabilityStalled && requiresAnotherRun)
				throw new IllegalArgumentException("A stalled probability cannot auto-run again");
		}
	}

	public record ReadinessEvidence(
		Map<CreateRouteKey, OutputLogisticsGateway.EndpointReadiness> endpoints) {
		public ReadinessEvidence {
			endpoints = Map.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
		}
	}

	public enum FailureReason { ARITHMETIC_OVERFLOW }

	public record FailureState(FailureReason reason, Optional<UUID> eventId,
		Optional<UUID> nativeOperationId) {
		public FailureState {
			reason = Objects.requireNonNull(reason, "reason");
			eventId = Objects.requireNonNull(eventId, "eventId");
			nativeOperationId = Objects.requireNonNull(nativeOperationId, "nativeOperationId");
			if (nativeOperationId.isPresent() && eventId.isEmpty())
				throw new IllegalArgumentException("Native failure evidence needs an event identity");
		}
	}

	public record ProcessLease(UUID processId, CreateRouteKey address) {
		public ProcessLease {
			processId = Objects.requireNonNull(processId, "processId");
			address = Objects.requireNonNull(address, "address");
		}
	}

	public enum LeasePurpose { QUIET_OBSERVATION, OUTPUT_DESTINATION }

	public record Snapshot(UUID agentId, long agentResetGeneration,
		UUID outputObservationEpoch, ProductionRun run, int priorNoProgressRuns,
		Milestone milestone,
		Optional<WaitReason> waitReason, boolean inputEnded, boolean signalObserved,
		boolean firstOutputObserved, long firstOutputElapsedTicks, long quietElapsedTicks,
		long probabilityElapsedTicks,
		Map<StackKey, Long> creditedOutputs, Map<StackKey, Long> diagnosticOutputs,
		List<PendingOutput> pendingOutputs,
		Map<CreateRouteKey, Map<StackKey, Long>> dispatchedOutputs,
		Map<UUID, ProductionOutputLedger.DispatchedEvent> dispatchedEvents,
		Set<UUID> seenOutputEventIds, Set<StockObservationScope> satisfiedStockScopes,
		Set<ReceiverDestination> acceptedAgentReceipts,
		List<LateWarehouseMaterial> lateWarehouseMaterial,
		Optional<DispatchInstruction> dispatchIntent,
		long lastReadinessObservationSequence,
		long confirmedDispatchSequence,
		Optional<ReadinessEvidence> readinessEvidence,
		Optional<SealOutcome> sealOutcome,
		Optional<FailureState> failure,
		boolean agentReleased) {
		public Snapshot {
			agentId = Objects.requireNonNull(agentId, "agentId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			run = Objects.requireNonNull(run, "run");
			milestone = Objects.requireNonNull(milestone, "milestone");
			waitReason = Objects.requireNonNull(waitReason, "waitReason");
			creditedOutputs = Map.copyOf(Objects.requireNonNull(creditedOutputs,
				"creditedOutputs"));
			diagnosticOutputs = Map.copyOf(Objects.requireNonNull(diagnosticOutputs,
				"diagnosticOutputs"));
			pendingOutputs = List.copyOf(Objects.requireNonNull(pendingOutputs, "pendingOutputs"));
			dispatchedOutputs = immutableNestedCounts(Objects.requireNonNull(dispatchedOutputs,
				"dispatchedOutputs"));
			dispatchedEvents = Map.copyOf(Objects.requireNonNull(dispatchedEvents,
				"dispatchedEvents"));
			seenOutputEventIds = Set.copyOf(Objects.requireNonNull(seenOutputEventIds,
				"seenOutputEventIds"));
			satisfiedStockScopes = Set.copyOf(Objects.requireNonNull(satisfiedStockScopes,
				"satisfiedStockScopes"));
			acceptedAgentReceipts = Set.copyOf(Objects.requireNonNull(acceptedAgentReceipts,
				"acceptedAgentReceipts"));
			lateWarehouseMaterial = List.copyOf(Objects.requireNonNull(lateWarehouseMaterial,
				"lateWarehouseMaterial"));
			dispatchIntent = Objects.requireNonNull(dispatchIntent, "dispatchIntent");
			readinessEvidence = Objects.requireNonNull(readinessEvidence, "readinessEvidence");
			sealOutcome = Objects.requireNonNull(sealOutcome, "sealOutcome");
			failure = Objects.requireNonNull(failure, "failure");
			if (agentResetGeneration < 0 || priorNoProgressRuns < 0
				|| firstOutputElapsedTicks < 0 || quietElapsedTicks < 0
				|| probabilityElapsedTicks < 0 || lastReadinessObservationSequence < -1
				|| confirmedDispatchSequence < 0)
				throw new IllegalArgumentException("Snapshot generations cannot be negative");
		}

		public UUID runId() { return run.runId(); }
	}

	public record PendingOutput(UUID eventId, CreateRouteKey destination, StackKey stack,
		long count, boolean credited) {
		public PendingOutput {
			eventId = Objects.requireNonNull(eventId, "eventId");
			destination = Objects.requireNonNull(destination, "destination");
			stack = Objects.requireNonNull(stack, "stack");
			if (count <= 0) throw new IllegalArgumentException("Pending output must be positive");
		}
	}

	public record ReceiverDestination(UUID receiverAgentId, CreateRouteKey destination) {
		public ReceiverDestination {
			receiverAgentId = Objects.requireNonNull(receiverAgentId, "receiverAgentId");
			destination = Objects.requireNonNull(destination, "destination");
		}
	}

	public record LateWarehouseMaterial(UUID eventId, CreateRouteKey destination, StackKey stack,
		long count) {
		public LateWarehouseMaterial {
			eventId = Objects.requireNonNull(eventId, "eventId");
			destination = Objects.requireNonNull(destination, "destination");
			stack = Objects.requireNonNull(stack, "stack");
			if (count <= 0) throw new IllegalArgumentException("Late output must be positive");
		}
	}

	public interface OutputProcessCapacity {
		Optional<ProcessLease> tryAcquireObservation(UUID runId, UUID goalId,
			CreateRouteKey outputAddress);
		/** Runtime implementations must allocate a ProcessKind.OUTPUT_DESTINATION slot. */
		Optional<ProcessLease> tryAcquireOutputDestination(UUID runId, UUID goalId,
			CreateRouteKey destination);
		boolean isLeaseActive(ProcessLease lease, LeasePurpose purpose, UUID runId, UUID goalId);
		void release(ProcessLease lease);
	}

	public interface Store {
		void persist(Snapshot snapshot);
		void persistLedger(ProductionOutputLedger ledger);
	}
}
