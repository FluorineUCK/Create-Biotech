package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Durable control-plane state for one admitted Run's fenced Agent inputs. */
public final class AgentInputContract {
	private final UUID agentId;
	private final long agentResetGeneration;
	private final UUID outputObservationEpoch;
	private final ProductionRun run;
	private final ExecutionShape executionShape;
	private final List<StageInput> stages;
	private final int inputStallTicks;
	private final StateStore stateStore;

	private AgentInputPhase phase = AgentInputPhase.QUEUED;
	private int stageIndex;
	private OptionalLong readySourceInventorySnapshotGeneration = OptionalLong.empty();
	@Nullable private CreateDispatchCommand currentCommand;
	@Nullable private NativeOrderLedger nativeOrders;
	private long loadedNoProgressTicks;
	private boolean waitingForAgentLoad;
	private int interferenceCount;
	private boolean leaseRetained;
	private boolean cancellationDrain;
	private long cancellationDrainRevision;
	@Nullable private State durableState;

	public AgentInputContract(UUID agentId, long agentResetGeneration,
		UUID outputObservationEpoch, ProductionRun run, ExecutionShape executionShape,
		List<StageInput> stages, int inputStallTicks, StateStore stateStore) {
		this.agentId = Objects.requireNonNull(agentId, "agentId");
		if (agentResetGeneration < 0)
			throw new IllegalArgumentException("Agent reset generation cannot be negative");
		this.agentResetGeneration = agentResetGeneration;
		this.outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
			"outputObservationEpoch");
		this.run = Objects.requireNonNull(run, "run");
		this.executionShape = Objects.requireNonNull(executionShape, "executionShape");
		this.stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
		if (inputStallTicks <= 0)
			throw new IllegalArgumentException("Input stall interval must be positive");
		this.inputStallTicks = inputStallTicks;
		this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
		validateStages();
		persist();
	}

	private AgentInputContract(State state, StateStore stateStore) {
		state = Objects.requireNonNull(state, "state");
		this.agentId = state.agentId();
		this.agentResetGeneration = state.agentResetGeneration();
		this.outputObservationEpoch = state.outputObservationEpoch();
		this.run = state.run();
		this.executionShape = state.executionShape();
		this.stages = state.stages();
		this.inputStallTicks = state.inputStallTicks();
		this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
		this.phase = state.phase();
		this.stageIndex = state.stageIndex();
		this.readySourceInventorySnapshotGeneration =
			state.readySourceInventorySnapshotGeneration();
		this.currentCommand = state.currentCommand().orElse(null);
		this.nativeOrders = state.nativeOrders().map(NativeOrderLedger::restore).orElse(null);
		this.loadedNoProgressTicks = state.loadedNoProgressTicks();
		this.waitingForAgentLoad = state.waitingForAgentLoad();
		this.interferenceCount = state.interferenceCount();
		this.leaseRetained = state.leaseRetained();
		this.cancellationDrain = state.cancellationDrain();
		this.cancellationDrainRevision = state.cancellationDrainRevision();
		this.durableState = state;
		validateStages();
		validateRestoredState();
	}

	/** Rehydrates the durable protocol exactly, without manufacturing a new transition. */
	public static AgentInputContract restore(State state, StateStore stateStore) {
		return new AgentInputContract(state, stateStore);
	}

	public AgentInputPhase phase() { return phase; }
	public long loadedNoProgressTicks() { return loadedNoProgressTicks; }
	public boolean waitingForAgentLoad() { return waitingForAgentLoad; }
	public int interferenceCount() { return interferenceCount; }
	public boolean leaseRetained() { return leaseRetained; }
	public boolean cancellationDrain() { return cancellationDrain; }
	public StageInput currentStage() { return stages.get(stageIndex); }

	public boolean markCurrentStageReady(long fence, Map<StackKey, Long> availableManifest,
		Set<CreateRouteKey> loadedInputRoutes, long sourceInventorySnapshotGeneration) {
		availableManifest = Map.copyOf(Objects.requireNonNull(availableManifest,
			"availableManifest"));
		loadedInputRoutes = Set.copyOf(Objects.requireNonNull(loadedInputRoutes,
			"loadedInputRoutes"));
		if (sourceInventorySnapshotGeneration < 0)
			throw new IllegalArgumentException("Source generation cannot be negative");
		StageInput stage = currentStage();
		if (cancellationDrain || phase != AgentInputPhase.QUEUED || fence != run.fence()
			|| !containsManifest(availableManifest, stage.manifest())
			|| !loadedInputRoutes.contains(stage.route()))
			return false;
		phase = AgentInputPhase.READY;
		readySourceInventorySnapshotGeneration =
			OptionalLong.of(sourceInventorySnapshotGeneration);
		leaseRetained = true;
		persist();
		return true;
	}

	/** Persists DISPATCH_INTENT before returning the world command to its caller. */
	public Optional<CreateDispatchCommand> beginDispatch(UUID intentId, long fence) {
		if (cancellationDrain || phase != AgentInputPhase.READY || fence != run.fence()
			|| readySourceInventorySnapshotGeneration.isEmpty()) return Optional.empty();
		StageInput stage = currentStage();
		CreateDispatchCommand command = new CreateDispatchCommand(intentId, agentId,
			agentResetGeneration, fence, run.runId(), outputObservationEpoch, stage.index(),
			stage.route(), readySourceInventorySnapshotGeneration.getAsLong(), stage.manifest());
		currentCommand = command;
		readySourceInventorySnapshotGeneration = OptionalLong.empty();
		phase = AgentInputPhase.DISPATCH_INTENT;
		persist();
		return Optional.of(command);
	}

	/** Only the immutable result of the server-thread gateway may confirm a dispatch. */
	public boolean confirmDispatch(CreateLogisticsGateway.CommitReceipt receipt) {
		receipt = Objects.requireNonNull(receipt, "receipt");
		if (phase != AgentInputPhase.DISPATCH_INTENT || currentCommand == null
			|| !currentCommand.equals(receipt.command())) return false;
		nativeOrders = new NativeOrderLedger(currentStage().manifest(), receipt.nativeOrders());
		phase = AgentInputPhase.DISPATCH_CONFIRMED;
		persist();
		return true;
	}

	public boolean closeInput(long fence) {
		if (phase != AgentInputPhase.DISPATCH_CONFIRMED || fence != run.fence()
			|| nativeOrders == null) return false;
		phase = AgentInputPhase.INPUT_CLOSED;
		persist();
		return true;
	}

	public ObservationResult observe(NativePackageObservation observation) {
		observation = Objects.requireNonNull(observation, "observation");
		if (!currentStage().route().equals(observation.route())) {
			CreateRouteKey observedRoute = observation.route();
			boolean managedOtherStage = executionShape == ExecutionShape.SEQUENCED
				&& stages.stream().anyMatch(stage -> stage.route().equals(observedRoute));
			return managedOtherStage ? interference() : ObservationResult.IGNORED_OTHER_ADDRESS;
		}
		if (phase == AgentInputPhase.RESET_REQUIRED || phase == AgentInputPhase.INPUT_STALLED)
			return ObservationResult.RESET_REQUIRED;
		if (nativeOrders == null) return interference();
		NativeOrderLedger.ObservationResult result = nativeOrders.observe(observation);
		if (result == NativeOrderLedger.ObservationResult.INTERFERENCE) return interference();
		if (result == NativeOrderLedger.ObservationResult.ACCEPTED) {
			loadedNoProgressTicks = 0;
			waitingForAgentLoad = false;
			persist();
		}
		return result == NativeOrderLedger.ObservationResult.DUPLICATE
			? ObservationResult.DUPLICATE : ObservationResult.ACCEPTED;
	}

	public boolean endInput(long fence) {
		if (phase != AgentInputPhase.INPUT_CLOSED || fence != run.fence()
			|| nativeOrders == null || !nativeOrders.complete()) return false;
		phase = AgentInputPhase.INPUT_ENDED;
		persist();
		return true;
	}

	/** Begins no later stage until the preceding native input has proved INPUT_ENDED. */
	public Optional<StageInput> advanceStage() {
		if (cancellationDrain || executionShape != ExecutionShape.SEQUENCED
			|| phase != AgentInputPhase.INPUT_ENDED
			|| stageIndex + 1 >= stages.size()) return Optional.empty();
		stageIndex++;
		phase = AgentInputPhase.QUEUED;
		readySourceInventorySnapshotGeneration = OptionalLong.empty();
		currentCommand = null;
		nativeOrders = null;
		loadedNoProgressTicks = 0;
		waitingForAgentLoad = false;
		persist();
		return Optional.of(currentStage());
	}

	/** Cancellation or uncertainty after intent is irreversible and keeps the Agent lease fenced. */
	public boolean stopAfterIrreversibleBoundary(long fence) {
		boolean betweenSequencedStages = phase == AgentInputPhase.INPUT_ENDED
			&& executionShape == ExecutionShape.SEQUENCED && stageIndex + 1 < stages.size();
		if (fence != run.fence() || !hasCrossedDispatchIntent()
			|| phase == AgentInputPhase.INPUT_ENDED && !betweenSequencedStages
			|| phase == AgentInputPhase.RESET_REQUIRED)
			return false;
		phase = AgentInputPhase.RESET_REQUIRED;
		loadedNoProgressTicks = 0;
		waitingForAgentLoad = false;
		leaseRetained = true;
		persist();
		return true;
	}

	/** Local-only Force Reset fence; preserves native evidence even after normal input closure. */
	public boolean forceResetFence(long fence) {
		Optional<ForceResetPreparation> prepared = prepareForceResetFence(fence);
		if (prepared.isEmpty()) return false;
		stateStore.persist(prepared.orElseThrow().nextState());
		installCommittedForceResetFence(prepared.orElseThrow());
		return true;
	}

	/** Pure input-fence preflight for the composition root's atomic Force Reset batch. */
	public Optional<ForceResetPreparation> prepareForceResetFence(long fence) {
		if (fence != run.fence() || !hasCrossedDispatchIntent()) return Optional.empty();
		State expected = snapshot();
		State next = phase == AgentInputPhase.RESET_REQUIRED ? expected
			: new State(expected.agentId(), expected.agentResetGeneration(),
				expected.outputObservationEpoch(), expected.run(), expected.executionShape(),
				expected.stages(), expected.inputStallTicks(), AgentInputPhase.RESET_REQUIRED,
				expected.stageIndex(), OptionalLong.empty(), expected.currentCommand(),
				expected.nativeOrders(), 0, false, expected.interferenceCount(), true, false, 0);
		return Optional.of(new ForceResetPreparation(expected, next));
	}

	/** Installs the exact input fence already published by the outer durable batch. */
	public void installCommittedForceResetFence(ForceResetPreparation prepared) {
		prepared = Objects.requireNonNull(prepared, "prepared");
		if (!snapshot().equals(prepared.expectedState()))
			throw new IllegalStateException("Force Reset input state changed after preflight");
		State next = prepared.nextState();
		phase = next.phase();
		stageIndex = next.stageIndex();
		readySourceInventorySnapshotGeneration = next.readySourceInventorySnapshotGeneration();
		currentCommand = next.currentCommand().orElse(null);
		nativeOrders = next.nativeOrders().map(NativeOrderLedger::restore).orElse(null);
		loadedNoProgressTicks = next.loadedNoProgressTicks();
		waitingForAgentLoad = next.waitingForAgentLoad();
		interferenceCount = next.interferenceCount();
		leaseRetained = next.leaseRetained();
		cancellationDrain = next.cancellationDrain();
		cancellationDrainRevision = next.cancellationDrainRevision();
		durableState = next;
		validateRestoredState();
	}

	/**
	 * Fences new Runs and later sequence stages without destroying the native input ledger that
	 * must continue observing already-dispatched packages during CANCEL_DRAIN.
	 */
	public boolean beginCancellationDrain(long fence) {
		if (fence != run.fence() || !hasCrossedDispatchIntent()
			|| phase == AgentInputPhase.RESET_REQUIRED || cancellationDrain) {
			State current = snapshot();
			return cancellationDrain && fence == run.fence()
				&& cancellationDrainRevision > 0 && current.equals(durableState);
		}
		long nextRevision = Math.incrementExact(cancellationDrainRevision);
		State current = snapshot();
		State proposed = new State(current.agentId(), current.agentResetGeneration(),
			current.outputObservationEpoch(), current.run(), current.executionShape(),
			current.stages(), current.inputStallTicks(), current.phase(), current.stageIndex(),
			current.readySourceInventorySnapshotGeneration(), current.currentCommand(),
			current.nativeOrders(), current.loadedNoProgressTicks(), current.waitingForAgentLoad(),
			current.interferenceCount(), true, true, nextRevision);
		stateStore.persist(proposed);
		leaseRetained = true;
		cancellationDrain = true;
		cancellationDrainRevision = nextRevision;
		durableState = proposed;
		return true;
	}

	/** Counts loaded server ticks only; unloaded time is explicitly paused. */
	public void advanceStallClock(long elapsedTicks, boolean allInputsLoaded,
		boolean progressObserved) {
		if (elapsedTicks < 0) throw new IllegalArgumentException("Elapsed ticks cannot be negative");
		if (phase != AgentInputPhase.DISPATCH_INTENT
			&& phase != AgentInputPhase.DISPATCH_CONFIRMED
			&& phase != AgentInputPhase.INPUT_CLOSED) return;
		if (!allInputsLoaded) {
			waitingForAgentLoad = true;
			persist();
			return;
		}
		waitingForAgentLoad = false;
		if (progressObserved) loadedNoProgressTicks = 0;
		else {
			try {
				loadedNoProgressTicks = Math.addExact(loadedNoProgressTicks, elapsedTicks);
			} catch (ArithmeticException exhausted) {
				loadedNoProgressTicks = Long.MAX_VALUE;
			}
		}
		if (loadedNoProgressTicks >= inputStallTicks) {
			phase = AgentInputPhase.INPUT_STALLED;
			leaseRetained = true;
		}
		persist();
	}

	public State snapshot() {
		return new State(agentId, agentResetGeneration, outputObservationEpoch, run, executionShape,
			stages, inputStallTicks, phase, stageIndex, readySourceInventorySnapshotGeneration,
			Optional.ofNullable(currentCommand),
			Optional.ofNullable(nativeOrders).map(NativeOrderLedger::snapshot),
			loadedNoProgressTicks, waitingForAgentLoad, interferenceCount, leaseRetained,
			cancellationDrain, cancellationDrainRevision);
	}

	private ObservationResult interference() {
		interferenceCount = Math.incrementExact(interferenceCount);
		if (run.inputMode() == InputMode.EXACT || executionShape == ExecutionShape.SEQUENCED) {
			phase = AgentInputPhase.RESET_REQUIRED;
			readySourceInventorySnapshotGeneration = OptionalLong.empty();
			loadedNoProgressTicks = 0;
			waitingForAgentLoad = false;
			leaseRetained = true;
			persist();
			return ObservationResult.RESET_REQUIRED;
		}
		persist();
		return ObservationResult.INTERFERENCE_DIAGNOSTIC;
	}

	private void validateStages() {
		if (stages.isEmpty()) throw new IllegalArgumentException("Input contract needs a stage");
		if (executionShape == ExecutionShape.STANDARD && stages.size() != 1)
			throw new IllegalArgumentException("A STANDARD input contract has exactly one stage");
		LinkedHashMap<StackKey, Long> combined = new LinkedHashMap<>();
		for (int index = 0; index < stages.size(); index++) {
			StageInput stage = stages.get(index);
			if (stage.index() != index)
				throw new IllegalArgumentException("Input stages must be contiguous from zero");
			stage.manifest().forEach((stack, count) -> combined.merge(stack, count, Math::addExact));
		}
		List<CreateRouteKey> stageRoutes = stages.stream().map(StageInput::route).toList();
		if (!stageRoutes.equals(run.inputRoutes()))
			throw new IllegalArgumentException("Frozen stage addresses differ from the admitted Run");
		if (executionShape == ExecutionShape.STANDARD && !combined.equals(run.manifest()))
			throw new IllegalArgumentException("Frozen per-address manifests are incomplete");
	}

	private boolean hasCrossedDispatchIntent() {
		return phase != AgentInputPhase.QUEUED && phase != AgentInputPhase.READY;
	}

	private static boolean containsManifest(Map<StackKey, Long> available,
		Map<StackKey, Long> expected) {
		for (Map.Entry<StackKey, Long> entry : expected.entrySet())
			if (available.getOrDefault(entry.getKey(), 0L) < entry.getValue()) return false;
		return true;
	}

	private void validateRestoredState() {
		if (stageIndex >= stages.size())
			throw new IllegalArgumentException("Durable stage index is out of range");
		boolean hasCommand = currentCommand != null;
		boolean hasLedger = nativeOrders != null;
		boolean hasReadySource = readySourceInventorySnapshotGeneration.isPresent();
		if (hasReadySource && readySourceInventorySnapshotGeneration.getAsLong() < 0)
			throw new IllegalArgumentException("Ready source generation cannot be negative");
		switch (phase) {
			case QUEUED -> {
				if (hasCommand || hasLedger)
					throw new IllegalArgumentException("Pre-dispatch state contains native evidence");
				if (hasReadySource || leaseRetained != (stageIndex > 0)
					|| loadedNoProgressTicks != 0 || waitingForAgentLoad)
					throw new IllegalArgumentException("Invalid queued stage readiness state");
			}
			case READY -> {
				if (hasCommand || hasLedger || !hasReadySource || !leaseRetained
					|| loadedNoProgressTicks != 0 || waitingForAgentLoad)
					throw new IllegalArgumentException("Invalid durable ready state");
			}
			case DISPATCH_INTENT -> {
				if (!hasCommand || hasLedger || hasReadySource || !leaseRetained)
					throw new IllegalArgumentException("Invalid durable dispatch intent");
			}
			case DISPATCH_CONFIRMED, INPUT_CLOSED, INPUT_ENDED -> {
				if (!hasCommand || !hasLedger || hasReadySource || !leaseRetained)
					throw new IllegalArgumentException("Confirmed input state lacks native evidence");
			}
			case INPUT_STALLED -> {
				if (!hasCommand || hasReadySource || !leaseRetained)
					throw new IllegalArgumentException("Irreversible input state lacks its intent");
			}
			case RESET_REQUIRED -> {
				boolean resetBeforeIntent = !hasCommand && !hasLedger && interferenceCount > 0;
				if ((!resetBeforeIntent && !hasCommand) || hasReadySource || !leaseRetained
					|| loadedNoProgressTicks != 0 || waitingForAgentLoad)
					throw new IllegalArgumentException("Invalid durable reset-required state");
			}
		}
		if (phase == AgentInputPhase.INPUT_STALLED
			&& (loadedNoProgressTicks < inputStallTicks || waitingForAgentLoad))
			throw new IllegalArgumentException("Invalid durable input stall evidence");
		if (phase != AgentInputPhase.INPUT_STALLED && loadedNoProgressTicks >= inputStallTicks)
			throw new IllegalArgumentException("Non-stalled state exceeded its durable stall interval");
		if (cancellationDrain && (!hasCrossedDispatchIntent() || !leaseRetained
			|| phase == AgentInputPhase.RESET_REQUIRED))
			throw new IllegalArgumentException("Invalid durable cancellation drain fence");
		if (hasCommand) {
			StageInput stage = currentStage();
			CreateDispatchCommand expected = new CreateDispatchCommand(currentCommand.intentId(),
				agentId, agentResetGeneration, run.fence(), run.runId(), outputObservationEpoch,
				stage.index(), stage.route(), currentCommand.sourceInventorySnapshotGeneration(),
				stage.manifest());
			if (!expected.equals(currentCommand))
				throw new IllegalArgumentException("Durable command does not match its fenced stage");
		}
		if (hasLedger) {
			NativeOrderLedger.State ledger = nativeOrders.snapshot();
			if (!ledger.expectedManifest().equals(currentStage().manifest()))
				throw new IllegalArgumentException("Native evidence manifest differs from current stage");
			for (NativePackageObservation observation : ledger.observations())
				if (!observation.route().equals(currentStage().route()))
					throw new IllegalArgumentException("Native evidence route differs from current stage");
			if (phase == AgentInputPhase.INPUT_ENDED && !nativeOrders.complete())
				throw new IllegalArgumentException("Ended input lacks complete native evidence");
		}
	}

	private void persist() {
		State next = snapshot();
		stateStore.persist(next);
		durableState = next;
	}

	public enum ExecutionShape { STANDARD, SEQUENCED }
	public enum ObservationResult {
		ACCEPTED, DUPLICATE, IGNORED_OTHER_ADDRESS, INTERFERENCE_DIAGNOSTIC, RESET_REQUIRED
	}

	public record StageInput(int index, CreateRouteKey route, Map<StackKey, Long> manifest) {
		public StageInput {
			if (index < 0) throw new IllegalArgumentException("Stage index cannot be negative");
			route = Objects.requireNonNull(route, "route");
			manifest = Map.copyOf(Objects.requireNonNull(manifest, "manifest"));
			if (manifest.isEmpty()) throw new IllegalArgumentException("Stage manifest cannot be empty");
			for (Map.Entry<StackKey, Long> entry : manifest.entrySet())
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0)
					throw new IllegalArgumentException("Stage manifest quantities must be positive");
		}
	}

	public record State(UUID agentId, long agentResetGeneration, UUID outputObservationEpoch,
		ProductionRun run, ExecutionShape executionShape, List<StageInput> stages,
		int inputStallTicks, AgentInputPhase phase, int stageIndex,
		OptionalLong readySourceInventorySnapshotGeneration,
		Optional<CreateDispatchCommand> currentCommand, Optional<NativeOrderLedger.State> nativeOrders,
		long loadedNoProgressTicks, boolean waitingForAgentLoad, int interferenceCount,
		boolean leaseRetained, boolean cancellationDrain, long cancellationDrainRevision) {
		public State {
			agentId = Objects.requireNonNull(agentId, "agentId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			run = Objects.requireNonNull(run, "run");
			executionShape = Objects.requireNonNull(executionShape, "executionShape");
			stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
			phase = Objects.requireNonNull(phase, "phase");
			readySourceInventorySnapshotGeneration = Objects.requireNonNull(
				readySourceInventorySnapshotGeneration, "readySourceInventorySnapshotGeneration");
			currentCommand = Objects.requireNonNull(currentCommand, "currentCommand");
			nativeOrders = Objects.requireNonNull(nativeOrders, "nativeOrders");
			if (agentResetGeneration < 0 || inputStallTicks <= 0 || stageIndex < 0
				|| loadedNoProgressTicks < 0 || interferenceCount < 0
				|| cancellationDrainRevision < 0
				|| cancellationDrain != (cancellationDrainRevision > 0))
				throw new IllegalArgumentException("Invalid durable Agent input state");
		}
	}

	public record ForceResetPreparation(State expectedState, State nextState) {
		public ForceResetPreparation {
			expectedState = Objects.requireNonNull(expectedState, "expectedState");
			nextState = Objects.requireNonNull(nextState, "nextState");
			if (!expectedState.agentId().equals(nextState.agentId())
				|| expectedState.agentResetGeneration() != nextState.agentResetGeneration()
				|| !expectedState.run().equals(nextState.run())
				|| nextState.phase() != AgentInputPhase.RESET_REQUIRED)
				throw new IllegalArgumentException("Invalid Force Reset input preparation");
		}
	}

	@FunctionalInterface
	public interface StateStore { void persist(State state); }
}
