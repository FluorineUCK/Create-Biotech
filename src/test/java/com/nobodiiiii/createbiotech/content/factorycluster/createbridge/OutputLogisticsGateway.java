package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.DispatchInstruction;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract.Milestone;

/**
 * Server-thread authority boundary for irreversible output movement and mutable drain/readiness
 * observations. Only opaque facts issued here may advance the output contract.
 */
public final class OutputLogisticsGateway {
	private final ServerThreadGuard serverThread;
	private final Authority authority;
	private final NativeMover mover;
	private final CommitStore commits;

	OutputLogisticsGateway(ServerThreadGuard serverThread, Authority authority,
		NativeMover mover, CommitStore commits) {
		this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
		this.authority = Objects.requireNonNull(authority, "authority");
		this.mover = Objects.requireNonNull(mover, "mover");
		this.commits = Objects.requireNonNull(commits, "commits");
	}

	/** Production composition entry without exposing construction of opaque gateway evidence. */
	public static OutputLogisticsGateway create(ServerThreadGuard serverThread, Authority authority,
		NativeMover mover, CommitStore commits) {
		return new OutputLogisticsGateway(serverThread, authority, mover, commits);
	}

	public synchronized CommitResult commit(DispatchInstruction instruction) {
		instruction = Objects.requireNonNull(instruction, "instruction");
		if (!serverThread.isServerThread())
			return CommitResult.rejected(instruction, Rejection.NOT_SERVER_THREAD);
		final Optional<PersistedCommit> loaded;
		try {
			loaded = commits.load(instruction.intentId());
		} catch (RuntimeException unavailable) {
			return CommitResult.rejected(instruction, Rejection.DURABLE_STORE_UNAVAILABLE);
		}
		if (loaded.isPresent()) return restore(instruction, loaded.orElseThrow());
		ProductionOutputContract.Snapshot state = Objects.requireNonNull(authority.captureContract(),
			"authoritative output contract");
		if (!matchesDurableIntent(instruction, state))
			return persistCompleted(CommitResult.rejected(instruction,
				Rejection.DURABLE_INTENT_MISMATCH));
		try {
			commits.persist(PersistedCommit.attempting(instruction));
		} catch (RuntimeException unavailable) {
			return CommitResult.rejected(instruction, Rejection.DURABLE_STORE_UNAVAILABLE);
		}
		try {
			NativeOperation operation = Objects.requireNonNull(mover.move(instruction),
				"native output operation");
			return persistCompleted(CommitResult.committed(instruction, operation));
		} catch (RuntimeException failure) {
			return persistUncertain(instruction);
		}
	}

	public ReadinessResult observeReadiness(ReadinessRequest request) {
		request = Objects.requireNonNull(request, "request");
		if (!serverThread.isServerThread())
			return ReadinessResult.rejected(Rejection.NOT_SERVER_THREAD);
		ReadinessState state = Objects.requireNonNull(authority.captureReadiness(request),
			"readiness state");
		if (!state.endpoints().keySet().equals(request.watchedOutputRoutes()))
			return ReadinessResult.rejected(Rejection.OBSERVATION_SCOPE_MISMATCH);
		UUID expectedEpoch = request.outputObservationEpoch();
		boolean currentlyReady = state.endpoints().values().stream().allMatch(endpoint ->
			endpoint.loaded() && endpoint.observationEpoch().equals(expectedEpoch));
		boolean currentlyDrained = currentlyReady && state.endpoints().values().stream()
			.allMatch(EndpointReadiness::drained);
		if (state.observationSequence() <= request.afterObservationSequence()
			|| !currentlyReady && state.continuouslyReadyElapsedTicks() != 0
			|| !currentlyDrained && state.continuouslyDrainedElapsedTicks() != 0)
			return ReadinessResult.rejected(Rejection.OBSERVATION_CLOCK_MISMATCH);
		return ReadinessResult.observed(new ReadinessSnapshot(request, state));
	}

	public DrainResult observeDrain(DrainRequest request) {
		request = Objects.requireNonNull(request, "request");
		if (!serverThread.isServerThread())
			return DrainResult.rejected(Rejection.NOT_SERVER_THREAD);
		DrainState state = Objects.requireNonNull(authority.captureDrain(request), "drain state");
		if (!state.endpoints().keySet().equals(request.watchedOutputRoutes()))
			return DrainResult.rejected(Rejection.OBSERVATION_SCOPE_MISMATCH);
		return DrainResult.observed(new DrainSnapshot(request, state));
	}

	private CommitResult restore(DispatchInstruction instruction, PersistedCommit prior) {
		if (!prior.instruction().equals(instruction))
			return CommitResult.rejected(instruction, Rejection.INTENT_ID_COLLISION);
		return prior.state() == AttemptState.ATTEMPTING
			? CommitResult.rejected(instruction, Rejection.NATIVE_MOVEMENT_UNCERTAIN)
			: prior.result().orElseThrow();
	}

	private CommitResult persistCompleted(CommitResult result) {
		try {
			commits.persist(PersistedCommit.completed(result.instruction(), result));
			return result;
		} catch (RuntimeException failure) {
			return CommitResult.rejected(result.instruction(), Rejection.NATIVE_MOVEMENT_UNCERTAIN);
		}
	}

	private CommitResult persistUncertain(DispatchInstruction instruction) {
		CommitResult uncertain = CommitResult.rejected(instruction,
			Rejection.NATIVE_MOVEMENT_UNCERTAIN);
		try {
			commits.persist(PersistedCommit.completed(instruction, uncertain));
		} catch (RuntimeException ignored) {
			// The durable ATTEMPTING marker is fail-closed evidence against a resend.
		}
		return uncertain;
	}

	private static boolean matchesDurableIntent(DispatchInstruction instruction,
		ProductionOutputContract.Snapshot state) {
		return state.agentId().equals(instruction.agentId())
			&& state.agentResetGeneration() == instruction.agentResetGeneration()
			&& state.run().runId().equals(instruction.runId())
			&& state.run().goalId().equals(instruction.goalId())
			&& state.run().fence() == instruction.fence()
			&& state.outputObservationEpoch().equals(instruction.outputObservationEpoch())
			&& state.milestone() == Milestone.OUTPUT_SEALED
			&& state.dispatchIntent().filter(instruction::equals).isPresent();
	}

	@FunctionalInterface
	public interface ServerThreadGuard { boolean isServerThread(); }

	public interface Authority {
		ProductionOutputContract.Snapshot captureContract();
		ReadinessState captureReadiness(ReadinessRequest request);
		DrainState captureDrain(DrainRequest request);
	}

	@FunctionalInterface
	public interface NativeMover { NativeOperation move(DispatchInstruction instruction); }

	public interface CommitStore {
		Optional<PersistedCommit> load(UUID intentId);
		void persist(PersistedCommit commit);
	}

	public record NativeOperation(UUID operationId) {
		public NativeOperation { operationId = Objects.requireNonNull(operationId, "operationId"); }
	}

	public record ReadinessRequest(UUID agentId, long agentResetGeneration, UUID runId,
		UUID goalId, long fence, UUID outputObservationEpoch,
		Set<CreateRouteKey> watchedOutputRoutes, long afterObservationSequence) {
		public ReadinessRequest {
			agentId = Objects.requireNonNull(agentId, "agentId");
			runId = Objects.requireNonNull(runId, "runId");
			goalId = Objects.requireNonNull(goalId, "goalId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			watchedOutputRoutes = Set.copyOf(Objects.requireNonNull(watchedOutputRoutes,
				"watchedOutputRoutes"));
			if (agentResetGeneration < 0 || fence < 0 || afterObservationSequence < -1)
				throw new IllegalArgumentException("Readiness fences cannot be negative");
		}
	}

	public record EndpointReadiness(boolean loaded, UUID observationEpoch, boolean drained) {
		public EndpointReadiness {
			observationEpoch = Objects.requireNonNull(observationEpoch, "observationEpoch");
		}
	}

	public record ReadinessState(Map<CreateRouteKey, EndpointReadiness> endpoints,
		long observationSequence, long continuouslyReadyElapsedTicks,
		long continuouslyDrainedElapsedTicks) {
		public ReadinessState {
			endpoints = immutableMap(endpoints, "readiness endpoints");
			if (observationSequence < 0 || continuouslyReadyElapsedTicks < 0
				|| continuouslyDrainedElapsedTicks < 0
				|| continuouslyDrainedElapsedTicks > continuouslyReadyElapsedTicks)
				throw new IllegalArgumentException("Invalid authoritative readiness clock");
		}
	}

	public static final class ReadinessSnapshot {
		private final ReadinessRequest request;
		private final ReadinessState state;
		private ReadinessSnapshot(ReadinessRequest request, ReadinessState state) {
			this.request = request;
			this.state = state;
		}
		public ReadinessRequest request() { return request; }
		public ReadinessState state() { return state; }
	}

	public record ReadinessResult(Optional<ReadinessSnapshot> snapshot, Rejection rejection) {
		public ReadinessResult {
			snapshot = Objects.requireNonNull(snapshot, "snapshot");
			rejection = Objects.requireNonNull(rejection, "rejection");
			if (snapshot.isPresent() != (rejection == Rejection.NONE))
				throw new IllegalArgumentException("Invalid readiness result");
		}
		private static ReadinessResult observed(ReadinessSnapshot snapshot) {
			return new ReadinessResult(Optional.of(snapshot), Rejection.NONE);
		}
		private static ReadinessResult rejected(Rejection rejection) {
			return new ReadinessResult(Optional.empty(), rejection);
		}
	}

	public record DrainRequest(UUID agentId, long agentResetGeneration, UUID runId, UUID goalId,
		long fence, UUID outputObservationEpoch, Set<CreateRouteKey> watchedOutputRoutes,
		long afterConfirmedDispatchSequence) {
		public DrainRequest {
			agentId = Objects.requireNonNull(agentId, "agentId");
			runId = Objects.requireNonNull(runId, "runId");
			goalId = Objects.requireNonNull(goalId, "goalId");
			outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
				"outputObservationEpoch");
			watchedOutputRoutes = Set.copyOf(Objects.requireNonNull(watchedOutputRoutes,
				"watchedOutputRoutes"));
			if (agentResetGeneration < 0 || fence < 0 || afterConfirmedDispatchSequence < 0)
				throw new IllegalArgumentException("Drain fences cannot be negative");
		}
	}

	public record EndpointDrain(boolean loaded, UUID observationEpoch, boolean empty) {
		public EndpointDrain {
			observationEpoch = Objects.requireNonNull(observationEpoch, "observationEpoch");
		}
	}

	public record DrainState(Map<CreateRouteKey, EndpointDrain> endpoints,
		boolean packagerHeldAndQueuedEmpty) {
		public DrainState { endpoints = immutableMap(endpoints, "drain endpoints"); }
	}

	public static final class DrainSnapshot {
		private final DrainRequest request;
		private final DrainState state;
		private DrainSnapshot(DrainRequest request, DrainState state) {
			this.request = request;
			this.state = state;
		}
		public DrainRequest request() { return request; }
		public DrainState state() { return state; }
	}

	public record DrainResult(Optional<DrainSnapshot> snapshot, Rejection rejection) {
		public DrainResult {
			snapshot = Objects.requireNonNull(snapshot, "snapshot");
			rejection = Objects.requireNonNull(rejection, "rejection");
			if (snapshot.isPresent() != (rejection == Rejection.NONE))
				throw new IllegalArgumentException("Invalid drain result");
		}
		private static DrainResult observed(DrainSnapshot snapshot) {
			return new DrainResult(Optional.of(snapshot), Rejection.NONE);
		}
		private static DrainResult rejected(Rejection rejection) {
			return new DrainResult(Optional.empty(), rejection);
		}
	}

	public record CommitResult(DispatchInstruction instruction, Optional<NativeOperation> operation,
		Rejection rejection, Optional<CommitReceipt> confirmationReceipt) {
		public CommitResult {
			instruction = Objects.requireNonNull(instruction, "instruction");
			operation = Objects.requireNonNull(operation, "operation");
			rejection = Objects.requireNonNull(rejection, "rejection");
			confirmationReceipt = Objects.requireNonNull(confirmationReceipt,
				"confirmationReceipt");
			boolean committed = rejection == Rejection.NONE;
			if (committed != operation.isPresent() || committed != confirmationReceipt.isPresent())
				throw new IllegalArgumentException("Invalid output commit result");
		}

		private static CommitResult committed(DispatchInstruction instruction,
			NativeOperation operation) {
			return new CommitResult(instruction, Optional.of(operation), Rejection.NONE,
				Optional.of(new CommitReceipt(instruction, operation)));
		}

		private static CommitResult rejected(DispatchInstruction instruction, Rejection rejection) {
			if (rejection == Rejection.NONE)
				throw new IllegalArgumentException("A rejected commit needs a reason");
			return new CommitResult(instruction, Optional.empty(), rejection, Optional.empty());
		}
	}

	/** Capability constructible only after a server-thread native operation was durably journaled. */
	public static final class CommitReceipt {
		private final DispatchInstruction instruction;
		private final NativeOperation operation;
		private CommitReceipt(DispatchInstruction instruction, NativeOperation operation) {
			this.instruction = instruction;
			this.operation = operation;
		}
		public DispatchInstruction instruction() { return instruction; }
		public NativeOperation operation() { return operation; }
	}

	public enum AttemptState { ATTEMPTING, COMPLETED }

	public record PersistedCommit(DispatchInstruction instruction, AttemptState state,
		Optional<CommitResult> result) {
		public PersistedCommit {
			instruction = Objects.requireNonNull(instruction, "instruction");
			state = Objects.requireNonNull(state, "state");
			result = Objects.requireNonNull(result, "result");
			if (state == AttemptState.ATTEMPTING && result.isPresent()
				|| state == AttemptState.COMPLETED && result.isEmpty())
				throw new IllegalArgumentException("Invalid output commit journal state");
		}
		private static PersistedCommit attempting(DispatchInstruction instruction) {
			return new PersistedCommit(instruction, AttemptState.ATTEMPTING, Optional.empty());
		}
		private static PersistedCommit completed(DispatchInstruction instruction,
			CommitResult result) {
			return new PersistedCommit(instruction, AttemptState.COMPLETED, Optional.of(result));
		}
	}

	public enum Rejection {
		NONE,
		NOT_SERVER_THREAD,
		DURABLE_INTENT_MISMATCH,
		INTENT_ID_COLLISION,
		OBSERVATION_SCOPE_MISMATCH,
		OBSERVATION_CLOCK_MISMATCH,
		NATIVE_MOVEMENT_UNCERTAIN,
		DURABLE_STORE_UNAVAILABLE
	}

	private static <K, V> Map<K, V> immutableMap(Map<K, V> source, String name) {
		LinkedHashMap<K, V> copy = new LinkedHashMap<>();
		Objects.requireNonNull(source, name).forEach((key, value) -> copy.put(
			Objects.requireNonNull(key, name + " key"), Objects.requireNonNull(value, name + " value")));
		return Map.copyOf(copy);
	}
}
