package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;

/**
 * Immutable target-side truth for a remote request. It stores ticket identity separately from
 * native input evidence, output evidence, and terminal evidence so none can imply another.
 */
public record RemoteAgentRecord(RemoteRequestKey key, UUID targetClusterId,
	ProductionRequestTicket ticket, RemoteAgentPhase phase, RecoveryCondition condition,
	boolean cancellationRequested, boolean futureExecutionStopped,
	Optional<AgentInputContract.State> inputState,
	Optional<ProductionOutputContract.Snapshot> outputState,
	Optional<ProductionOutputLedger> outputLedger,
	Optional<TerminalOutcome> terminalOutcome, long revision) {
	public RemoteAgentRecord {
		key = Objects.requireNonNull(key, "key");
		targetClusterId = Objects.requireNonNull(targetClusterId, "targetClusterId");
		ticket = Objects.requireNonNull(ticket, "ticket");
		phase = Objects.requireNonNull(phase, "phase");
		condition = Objects.requireNonNull(condition, "condition");
		inputState = Objects.requireNonNull(inputState, "inputState");
		outputState = Objects.requireNonNull(outputState, "outputState");
		outputLedger = Objects.requireNonNull(outputLedger, "outputLedger");
		terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
		if (revision < 0) throw new IllegalArgumentException("Record revision cannot be negative");
		validateKey(key, ticket);
		if (inputState.isPresent()) validateInput(key, ticket, inputState.orElseThrow());
		if (outputState.isPresent()) validateOutput(key, ticket, outputState.orElseThrow());
		if (outputLedger.isPresent())
			validateLedger(key, ticket, outputState, outputLedger.orElseThrow());
		validateLifecycle(phase, condition, cancellationRequested, futureExecutionStopped,
			inputState, outputState, outputLedger, terminalOutcome);
	}

	static RemoteAgentRecord queued(RemoteRequestKey key, UUID targetClusterId,
		ProductionRequestTicket ticket) {
		return new RemoteAgentRecord(key, targetClusterId, ticket, RemoteAgentPhase.QUEUED,
			RecoveryCondition.NORMAL, false, false, Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty(), 0);
	}

	RemoteAgentRecord reserve() {
		if (phase != RemoteAgentPhase.QUEUED || condition != RecoveryCondition.NORMAL)
			return this;
		return copy(RemoteAgentPhase.RESERVED, condition, false, false, inputState, outputState,
			outputLedger, terminalOutcome);
	}

	RemoteAgentRecord withInput(AgentInputContract.State state) {
		Objects.requireNonNull(state, "state");
		if (isTerminal()) return this;
		if (phase == RemoteAgentPhase.QUEUED)
			throw new IllegalStateException("Input evidence arrived before Agent reservation");
		RemoteAgentPhase evidenced = phaseFrom(state.phase());
		if (condition == RecoveryCondition.CANCEL_DRAIN) {
			RemoteAgentPhase drainedPhase = rank(evidenced) > rank(phase) ? evidenced : phase;
			return copy(drainedPhase, condition, true, true, Optional.of(state), outputState,
				outputLedger, terminalOutcome);
		}
		if (rank(evidenced) < rank(phase))
			throw new IllegalArgumentException("Input evidence cannot move a remote phase backwards");
		RecoveryCondition nextCondition = state.phase() == AgentInputPhase.RESET_REQUIRED
			|| state.phase() == AgentInputPhase.INPUT_STALLED
			? RecoveryCondition.FAULT : condition;
		Optional<TerminalOutcome> outcome = nextCondition == RecoveryCondition.FAULT
			? Optional.of(TerminalOutcome.FAULT_RESET_REQUIRED) : terminalOutcome;
		return copy(evidenced, nextCondition, cancellationRequested,
			futureExecutionStopped || nextCondition == RecoveryCondition.FAULT, Optional.of(state),
			outputState, outputLedger, outcome);
	}

	RemoteAgentRecord beginCancelDrain(AgentInputContract.State stoppedInput) {
		if (!crossedDispatchIntent() || isTerminal()) return this;
		return copy(phase, RecoveryCondition.CANCEL_DRAIN, true, true,
			Optional.of(Objects.requireNonNull(stoppedInput, "stoppedInput")), outputState,
			outputLedger, Optional.empty());
	}

	RemoteAgentRecord requestCancelDrain() {
		if (!crossedDispatchIntent() || isTerminal()) return this;
		return copy(phase, RecoveryCondition.CANCEL_DRAIN, true, true, inputState,
			outputState, outputLedger, Optional.empty());
	}

	RemoteAgentRecord withOutput(ProductionOutputContract.Snapshot state) {
		Objects.requireNonNull(state, "state");
		if (isTerminal()) return this;
		if (rank(phase) < rank(RemoteAgentPhase.DISPATCH_CONFIRMED))
			throw new IllegalStateException("Output evidence arrived before confirmed dispatch");
		if (state.milestone() == ProductionOutputContract.Milestone.FAILED)
			return copy(RemoteAgentPhase.RESET_REQUIRED, RecoveryCondition.FAULT,
				cancellationRequested, true, inputState, Optional.of(state), outputLedger,
				Optional.of(TerminalOutcome.FAULT_RESET_REQUIRED));
		RemoteAgentPhase evidenced = switch (state.milestone()) {
			case WAITING_INPUT_READY, OBSERVING -> RemoteAgentPhase.INPUT_ACTIVE;
			case OUTPUT_SEALED, OUTPUT_DISPATCHED, OUTPUT_RECEIVED ->
				RemoteAgentPhase.OUTPUT_DRAIN;
			case FAILED -> throw new IllegalStateException("handled above");
		};
		if (rank(evidenced) < rank(phase))
			throw new IllegalArgumentException("Output evidence cannot move a remote phase backwards");
		return copy(evidenced, condition, cancellationRequested, futureExecutionStopped,
			inputState, Optional.of(state), outputLedger, terminalOutcome);
	}

	RemoteAgentRecord uncertainAfterReload() {
		if (condition != RecoveryCondition.NORMAL
			|| phase != RemoteAgentPhase.DISPATCH_INTENT || inputState.isEmpty()
			|| inputState.orElseThrow().phase() != AgentInputPhase.DISPATCH_INTENT)
			return this;
		return copy(RemoteAgentPhase.RESET_REQUIRED, RecoveryCondition.UNCERTAIN_DISPATCH,
			cancellationRequested, true, inputState, outputState, outputLedger,
			Optional.of(TerminalOutcome.UNCERTAIN_DISPATCH));
	}

	RemoteAgentRecord terminal(RemoteAgentPhase terminalPhase, RecoveryCondition nextCondition,
		TerminalOutcome outcome, Optional<AgentInputContract.State> input,
		Optional<ProductionOutputContract.Snapshot> output, Optional<ProductionOutputLedger> ledger) {
		if (terminalPhase != RemoteAgentPhase.RELEASED
			&& terminalPhase != RemoteAgentPhase.RESET_REQUIRED)
			throw new IllegalArgumentException("Terminal record needs a terminal phase");
		return copy(terminalPhase, nextCondition, cancellationRequested,
			futureExecutionStopped || outcome != TerminalOutcome.COMPLETED, input, output, ledger,
			Optional.of(outcome));
	}

	RemoteAgentRecord markCancelledPreIntent(TerminalOutcome outcome) {
		if (crossedDispatchIntent()) throw new IllegalStateException("Cannot pre-intent cancel");
		return copy(RemoteAgentPhase.RELEASED, RecoveryCondition.NORMAL, true, true,
			inputState, outputState, outputLedger, Optional.of(outcome));
	}

	public boolean crossedDispatchIntent() {
		return switch (phase) {
			case QUEUED, RESERVED -> false;
			default -> true;
		};
	}

	public boolean isTerminal() {
		return phase == RemoteAgentPhase.RELEASED || phase == RemoteAgentPhase.RESET_REQUIRED;
	}

	/** Stable revision identity used by the radio protocol; evidence itself remains separate. */
	public String evidenceIdentity() {
		return CurrentSchemaEvidenceDigest.sha256(this);
	}

	private RemoteAgentRecord copy(RemoteAgentPhase nextPhase, RecoveryCondition nextCondition,
		boolean nextCancellation, boolean stopFuture,
		Optional<AgentInputContract.State> input, Optional<ProductionOutputContract.Snapshot> output,
		Optional<ProductionOutputLedger> ledger, Optional<TerminalOutcome> outcome) {
		return new RemoteAgentRecord(key, targetClusterId, ticket, nextPhase, nextCondition,
			nextCancellation, stopFuture, input, output, ledger, outcome,
			Math.incrementExact(revision));
	}

	private static RemoteAgentPhase phaseFrom(AgentInputPhase phase) {
		return switch (phase) {
			case QUEUED, READY -> RemoteAgentPhase.RESERVED;
			case DISPATCH_INTENT -> RemoteAgentPhase.DISPATCH_INTENT;
			case DISPATCH_CONFIRMED -> RemoteAgentPhase.DISPATCH_CONFIRMED;
			case INPUT_CLOSED, INPUT_ENDED -> RemoteAgentPhase.INPUT_ACTIVE;
			case INPUT_STALLED, RESET_REQUIRED -> RemoteAgentPhase.RESET_REQUIRED;
		};
	}

	private static int rank(RemoteAgentPhase phase) {
		return switch (phase) {
			case QUEUED -> 0;
			case RESERVED -> 1;
			case DISPATCH_INTENT -> 2;
			case DISPATCH_CONFIRMED -> 3;
			case INPUT_ACTIVE -> 4;
			case OUTPUT_DRAIN -> 5;
			case RELEASED, RESET_REQUIRED -> 6;
		};
	}

	private static void validateKey(RemoteRequestKey key, ProductionRequestTicket ticket) {
		if (!key.requestId().equals(ticket.requestId()) || !key.runId().equals(ticket.runId())
			|| key.fence() != ticket.fence())
			throw new IllegalArgumentException("Remote key differs from its frozen ticket");
	}

	private static void validateInput(RemoteRequestKey key, ProductionRequestTicket ticket,
		AgentInputContract.State state) {
		ProductionRun run = state.run();
		if (!state.agentId().equals(ticket.agentId())
			|| state.agentResetGeneration() != key.agentGeneration()
			|| !run.runId().equals(key.runId()) || run.fence() != key.fence()
			|| !run.patternId().equals(ticket.patternId()) || run.inputMode() != ticket.mode()
			|| !run.inputRoutes().contains(ticket.targetRoute()))
			throw new IllegalArgumentException("Input evidence differs from its remote request");
	}

	private static void validateOutput(RemoteRequestKey key, ProductionRequestTicket ticket,
		ProductionOutputContract.Snapshot state) {
		if (!state.agentId().equals(ticket.agentId())
			|| state.agentResetGeneration() != key.agentGeneration()
			|| !state.runId().equals(key.runId()) || state.run().fence() != key.fence())
			throw new IllegalArgumentException("Output evidence differs from its remote request");
	}

	private static void validateLedger(RemoteRequestKey key, ProductionRequestTicket ticket,
		Optional<ProductionOutputContract.Snapshot> outputState, ProductionOutputLedger ledger) {
		if (!ledger.runId().equals(key.runId()) || ledger.fence() != key.fence())
			throw new IllegalArgumentException("Output ledger differs from its remote request");
		outputState.ifPresent(output -> {
			if (!output.outputObservationEpoch().equals(ledger.outputObservationEpoch())
				|| output.run().outputPolicyGeneration() != ledger.outputPolicyGeneration()
				|| !output.run().returnWarehouseRoute().equals(ledger.returnWarehouseRoute())
				|| !output.run().goalId().equals(ledger.goalId())
				|| !output.dispatchedEvents().equals(ledger.dispatchedEvents())
				|| !output.dispatchedOutputs().equals(ledger.dispatchedOutputs())
				|| !output.creditedOutputs().equals(ledger.creditedOutputs()))
				throw new IllegalArgumentException("Output snapshot and ledger disagree");
			ProductionOutputContract.SealOutcome outcome = output.sealOutcome().orElse(null);
			if (outcome == null || outcome.primaryCount() != ledger.sealFact().primaryCount()
				|| outcome.probabilisticShortfall()
					!= ledger.sealFact().probabilisticShortfall()
				|| outcome.requiresAnotherRun() != ledger.sealFact().requiresAnotherRun()
				|| outcome.consecutiveNoProgressRuns()
					!= ledger.sealFact().consecutiveNoProgressRuns()
				|| outcome.probabilityStalled() != ledger.sealFact().probabilityStalled()
				|| !outcome.completionBoundary().equals(
					ledger.sealFact().completionBoundary()))
				throw new IllegalArgumentException("Output seal and dispatch ledger disagree");
		});
	}

	private static void validateLifecycle(RemoteAgentPhase phase, RecoveryCondition condition,
		boolean cancellationRequested, boolean futureExecutionStopped,
		Optional<AgentInputContract.State> inputState,
		Optional<ProductionOutputContract.Snapshot> outputState,
		Optional<ProductionOutputLedger> outputLedger, Optional<TerminalOutcome> outcome) {
		boolean terminal = phase == RemoteAgentPhase.RELEASED
			|| phase == RemoteAgentPhase.RESET_REQUIRED;
		if (terminal != outcome.isPresent())
			throw new IllegalArgumentException("Terminal phase and terminal outcome disagree");
		if (condition == RecoveryCondition.UNCERTAIN_DISPATCH
			&& (phase != RemoteAgentPhase.RESET_REQUIRED || inputState.isEmpty()
				|| inputState.orElseThrow().phase() != AgentInputPhase.DISPATCH_INTENT
				|| !futureExecutionStopped))
			throw new IllegalArgumentException("Uncertain dispatch lacks its durable intent fence");
		if (condition == RecoveryCondition.CANCEL_DRAIN
			&& (!cancellationRequested || !futureExecutionStopped
				|| phase == RemoteAgentPhase.QUEUED || phase == RemoteAgentPhase.RESERVED))
			throw new IllegalArgumentException("Cancel drain lacks its irreversible boundary");
		if (phase == RemoteAgentPhase.DISPATCH_INTENT && inputState.stream()
			.noneMatch(input -> input.phase() == AgentInputPhase.DISPATCH_INTENT
				|| input.phase() == AgentInputPhase.RESET_REQUIRED))
			throw new IllegalArgumentException("Dispatch intent phase lacks durable intent evidence");
		if (phase == RemoteAgentPhase.DISPATCH_CONFIRMED && inputState.stream()
			.noneMatch(input -> input.nativeOrders().isPresent()))
			throw new IllegalArgumentException("Confirmed dispatch lacks native order evidence");
		if (phase == RemoteAgentPhase.OUTPUT_DRAIN && outputState.isEmpty())
			throw new IllegalArgumentException("Output drain lacks its frozen output snapshot");
		if (outputLedger.isPresent() && !terminal && phase != RemoteAgentPhase.OUTPUT_DRAIN)
			throw new IllegalArgumentException("Non-terminal phase cannot own a dispatch ledger");
		if (outcome.isPresent()) {
			TerminalOutcome terminalOutcome = outcome.orElseThrow();
			switch (terminalOutcome) {
				case COMPLETED -> {
					if (phase != RemoteAgentPhase.RELEASED || outputLedger.isEmpty())
						throw new IllegalArgumentException("Completed terminal lacks dispatch evidence");
				}
				case CANCELLED_PRE_INTENT, RETRIED_PRE_INTENT -> {
					if (phase != RemoteAgentPhase.RELEASED || !cancellationRequested
						|| inputState.isPresent() || outputLedger.isPresent())
						throw new IllegalArgumentException("Pre-intent terminal contains physical evidence");
				}
				case CANCEL_DRAINED -> {
					if (phase != RemoteAgentPhase.RESET_REQUIRED
						|| condition != RecoveryCondition.CANCEL_DRAIN
						|| inputState.isEmpty() || outputState.isEmpty()
						|| outputLedger.isEmpty())
						throw new IllegalArgumentException("Cancelled drain lacks terminal evidence");
				}
				case UNCERTAIN_DISPATCH -> {
					if (condition != RecoveryCondition.UNCERTAIN_DISPATCH)
						throw new IllegalArgumentException("Uncertain outcome lacks uncertainty");
				}
				case FAULT_RESET_REQUIRED -> {
					if (phase != RemoteAgentPhase.RESET_REQUIRED
						|| condition != RecoveryCondition.FAULT)
						throw new IllegalArgumentException("Fault outcome lacks reset state");
				}
			}
		}
	}

	public enum RecoveryCondition {
		NORMAL,
		CANCEL_DRAIN,
		UNCERTAIN_DISPATCH,
		FAULT
	}

	public enum TerminalOutcome {
		COMPLETED,
		CANCELLED_PRE_INTENT,
		CANCEL_DRAINED,
		UNCERTAIN_DISPATCH,
		FAULT_RESET_REQUIRED,
		RETRIED_PRE_INTENT
	}
}
