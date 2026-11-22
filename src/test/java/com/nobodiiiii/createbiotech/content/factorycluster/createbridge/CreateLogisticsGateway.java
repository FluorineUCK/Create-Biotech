package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.create.CreatePackageDispatch;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** The sole server-thread boundary allowed to turn a fenced Agent intent into Create orders. */
public final class CreateLogisticsGateway {
	private final AuthoritativeState state;
	private final NativeDispatcher dispatcher;
	private final ServerThreadGuard serverThread;
	private final CommitStore commitStore;
	private final DurableAttemptBoundary durableAttemptBoundary;

	public CreateLogisticsGateway(AuthoritativeState state, NativeDispatcher dispatcher,
		ServerThreadGuard serverThread, CommitStore commitStore) {
		this(state, dispatcher, serverThread, commitStore, ignored -> true);
	}

	public CreateLogisticsGateway(AuthoritativeState state, NativeDispatcher dispatcher,
		ServerThreadGuard serverThread, CommitStore commitStore,
		DurableAttemptBoundary durableAttemptBoundary) {
		this.state = Objects.requireNonNull(state, "state");
		this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
		this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
		this.commitStore = Objects.requireNonNull(commitStore, "commitStore");
		this.durableAttemptBoundary = Objects.requireNonNull(durableAttemptBoundary,
			"durableAttemptBoundary");
	}

	public static CreateLogisticsGateway usingCreate(AuthoritativeState state,
		ServerThreadGuard serverThread, CommitStore commitStore) {
		return new CreateLogisticsGateway(state, request -> dispatchCreate(request, serverThread), serverThread,
			commitStore);
	}

	public static CreateLogisticsGateway usingCreate(AuthoritativeState state,
		ServerThreadGuard serverThread, CommitStore commitStore,
		DurableAttemptBoundary durableAttemptBoundary) {
		return new CreateLogisticsGateway(state, request -> dispatchCreate(request, serverThread), serverThread,
			commitStore, durableAttemptBoundary);
	}

	public synchronized CommitResult commit(CreateDispatchCommand command) {
		command = Objects.requireNonNull(command, "command");
		if (!serverThread.isServerThread())
			return CommitResult.rejected(command, Rejection.NOT_SERVER_THREAD);
		final Optional<PersistedCommit> loaded;
		try {
			loaded = commitStore.load(command.intentId());
		} catch (RuntimeException unavailable) {
			return CommitResult.rejected(command, Rejection.DURABLE_STORE_UNAVAILABLE);
		}
		if (loaded.isPresent()) {
			PersistedCommit prior = loaded.orElseThrow();
			if (!prior.command().equals(command))
				return CommitResult.rejected(command, Rejection.INTENT_ID_COLLISION);
			if (!prior.allowsRetryWithoutMaterial()) return restore(command, prior);
		}

		CommitContext captured = Objects.requireNonNull(state.capture(command), "commit context");
		Rejection invalid = validate(command, captured);
		if (invalid != Rejection.NONE)
			return persistCompleted(command, CommitResult.rejected(command, invalid));
		try {
			commitStore.persist(PersistedCommit.attempting(command));
		} catch (RuntimeException unavailable) {
			return CommitResult.rejected(command, Rejection.DURABLE_STORE_UNAVAILABLE);
		}
		// A server shutdown may land exactly here. A suspended boundary deliberately leaves the
		// ATTEMPTING proof intact, so reload follows the same fail-closed path as a real crash.
		if (!durableAttemptBoundary.proceed(command))
			return CommitResult.rejected(command, Rejection.NATIVE_DISPATCH_UNCERTAIN);
		try {
			NativeDispatchFact fact = Objects.requireNonNull(dispatcher.dispatch(command),
				"native dispatch fact");
			CommitResult result = fact.accepted()
				? CommitResult.committed(command, fact.nativeOrders())
				: CommitResult.rejected(command, Rejection.NATIVE_DISPATCH_REJECTED);
			return persistCompleted(command, result);
		} catch (RuntimeException failure) {
			return persistUncertain(command);
		}
	}

	private CommitResult restore(CreateDispatchCommand command, PersistedCommit prior) {
		if (!prior.command().equals(command))
			return CommitResult.rejected(command, Rejection.INTENT_ID_COLLISION);
		return prior.state() == CommitAttemptState.ATTEMPTING
			? CommitResult.rejected(command, Rejection.NATIVE_DISPATCH_UNCERTAIN)
			: prior.result().orElseThrow();
	}

	private CommitResult persistCompleted(CreateDispatchCommand command, CommitResult result) {
		try {
			commitStore.persist(PersistedCommit.completed(command, result));
			return result;
		} catch (RuntimeException failure) {
			return CommitResult.rejected(command, Rejection.NATIVE_DISPATCH_UNCERTAIN);
		}
	}

	private CommitResult persistUncertain(CreateDispatchCommand command) {
		CommitResult uncertain = CommitResult.rejected(command,
			Rejection.NATIVE_DISPATCH_UNCERTAIN);
		try {
			commitStore.persist(PersistedCommit.completed(command, uncertain));
		} catch (RuntimeException ignored) {
			// The previously durable ATTEMPTING marker is itself fail-closed evidence.
		}
		return uncertain;
	}

	private static Rejection validate(CreateDispatchCommand command, CommitContext actual) {
		if (!actual.agentId().equals(command.agentId())) return Rejection.AGENT_MISMATCH;
		if (actual.agentResetGeneration() != command.agentResetGeneration())
			return Rejection.RESET_GENERATION_MISMATCH;
		if (actual.fence() != command.fence()) return Rejection.FENCE_MISMATCH;
		if (!actual.inputRoute().equals(command.route())) return Rejection.INPUT_ROUTE_MISMATCH;
		if (!actual.packagerLoaded()) return Rejection.PACKAGER_UNLOADED;
		if (actual.sourceInventorySnapshotGeneration()
			!= command.sourceInventorySnapshotGeneration())
			return Rejection.SOURCE_SNAPSHOT_MISMATCH;
		if (actual.durablePhase() != AgentInputPhase.DISPATCH_INTENT)
			return Rejection.DURABLE_PHASE_MISMATCH;
		if (!actual.durableCommand().equals(command))
			return Rejection.DURABLE_COMMAND_MISMATCH;
		return Rejection.NONE;
	}

	public interface AuthoritativeState {
		/** Captures mutable world/Agent facts on the Minecraft server thread. */
		CommitContext capture(CreateDispatchCommand command);
	}

	@FunctionalInterface
	public interface ServerThreadGuard { boolean isServerThread(); }

	@FunctionalInterface
	public interface DurableAttemptBoundary {
		boolean proceed(CreateDispatchCommand command);
	}

	public interface CommitStore {
		Optional<PersistedCommit> load(UUID intentId);
		void persist(PersistedCommit commit);
	}

	public interface DispatchRequest {
		UUID intentId();
		UUID runId();
		CreateRouteKey route();
		long sourceInventorySnapshotGeneration();
		Map<StackKey, Long> manifest();
	}

	@FunctionalInterface
	public interface NativeDispatcher {
		NativeDispatchFact dispatch(DispatchRequest request);
	}

	public record CommitContext(UUID agentId, long agentResetGeneration, long fence,
		CreateRouteKey inputRoute, boolean packagerLoaded, long sourceInventorySnapshotGeneration,
		AgentInputPhase durablePhase, CreateDispatchCommand durableCommand) {
		public CommitContext {
			agentId = Objects.requireNonNull(agentId, "agentId");
			inputRoute = Objects.requireNonNull(inputRoute, "inputRoute");
			durablePhase = Objects.requireNonNull(durablePhase, "durablePhase");
			durableCommand = Objects.requireNonNull(durableCommand, "durableCommand");
			if (agentResetGeneration < 0 || fence < 0 || sourceInventorySnapshotGeneration < 0)
				throw new IllegalArgumentException("Authoritative fence values cannot be negative");
		}
	}

	public record NativeDispatchFact(boolean accepted,
		List<NativeOrderLedger.NativeOrderIdentity> nativeOrders) {
		public NativeDispatchFact {
			nativeOrders = List.copyOf(Objects.requireNonNull(nativeOrders, "nativeOrders"));
			if (accepted == nativeOrders.isEmpty())
				throw new IllegalArgumentException("Only accepted dispatches have native orders");
		}
	}

	public record CommitResult(CreateDispatchCommand command, boolean committed,
		List<NativeOrderLedger.NativeOrderIdentity> nativeOrders, Rejection rejection,
		Optional<CommitReceipt> confirmationReceipt) {
		public CommitResult {
			command = Objects.requireNonNull(command, "command");
			nativeOrders = List.copyOf(Objects.requireNonNull(nativeOrders, "nativeOrders"));
			rejection = Objects.requireNonNull(rejection, "rejection");
			confirmationReceipt = Objects.requireNonNull(confirmationReceipt,
				"confirmationReceipt");
			if (committed != (rejection == Rejection.NONE) || committed == nativeOrders.isEmpty()
				|| committed != confirmationReceipt.isPresent())
				throw new IllegalArgumentException("Invalid Create commit result");
			if (confirmationReceipt.isPresent()) {
				CommitReceipt receipt = confirmationReceipt.orElseThrow();
				if (!receipt.command().equals(command) || !receipt.nativeOrders().equals(nativeOrders))
					throw new IllegalArgumentException("Confirmation receipt differs from commit fact");
			}
		}

		private static CommitResult committed(CreateDispatchCommand command,
			List<NativeOrderLedger.NativeOrderIdentity> nativeOrders) {
			CommitReceipt receipt = new CommitReceipt(command, nativeOrders);
			return new CommitResult(command, true, nativeOrders, Rejection.NONE,
				Optional.of(receipt));
		}

		public static CommitResult rejected(CreateDispatchCommand command, Rejection reason) {
			if (reason == Rejection.NONE)
				throw new IllegalArgumentException("A rejection needs a reason");
			return new CommitResult(command, false, List.of(), reason, Optional.empty());
		}
	}

	/** Unforgeable capability issued only after the gateway durably journals a successful fact. */
	public static final class CommitReceipt {
		private final CreateDispatchCommand command;
		private final List<NativeOrderLedger.NativeOrderIdentity> nativeOrders;

		private CommitReceipt(CreateDispatchCommand command,
			List<NativeOrderLedger.NativeOrderIdentity> nativeOrders) {
			this.command = Objects.requireNonNull(command, "command");
			this.nativeOrders = List.copyOf(Objects.requireNonNull(nativeOrders, "nativeOrders"));
		}

		public CreateDispatchCommand command() { return command; }
		public List<NativeOrderLedger.NativeOrderIdentity> nativeOrders() { return nativeOrders; }
	}

	public enum Rejection {
		NONE,
		INTENT_ID_COLLISION,
		NOT_SERVER_THREAD,
		AGENT_MISMATCH,
		RESET_GENERATION_MISMATCH,
		FENCE_MISMATCH,
		INPUT_ROUTE_MISMATCH,
		PACKAGER_UNLOADED,
		SOURCE_SNAPSHOT_MISMATCH,
		DURABLE_PHASE_MISMATCH,
		DURABLE_COMMAND_MISMATCH,
		DIRECTORY_STATE_MISMATCH,
		BARE_PERMISSION_MISMATCH,
		NATIVE_DISPATCH_REJECTED,
		NATIVE_DISPATCH_UNCERTAIN,
		DURABLE_STORE_UNAVAILABLE
	}

	public enum CommitAttemptState { ATTEMPTING, COMPLETED }

	public record PersistedCommit(CreateDispatchCommand command, CommitAttemptState state,
		Optional<CommitResult> result) {
		public PersistedCommit {
			command = Objects.requireNonNull(command, "command");
			state = Objects.requireNonNull(state, "state");
			result = Objects.requireNonNull(result, "result");
			if (state == CommitAttemptState.ATTEMPTING && result.isPresent()
				|| state == CommitAttemptState.COMPLETED && result.isEmpty()
				|| result.isPresent() && !result.orElseThrow().command().equals(command))
				throw new IllegalArgumentException("Invalid durable Create commit record");
		}

		public static PersistedCommit attempting(CreateDispatchCommand command) {
			return new PersistedCommit(command, CommitAttemptState.ATTEMPTING, Optional.empty());
		}

		public static PersistedCommit completed(CreateDispatchCommand command,
			CommitResult result) {
			return new PersistedCommit(command, CommitAttemptState.COMPLETED,
				Optional.of(result));
		}

		/** True only for a durably completed native order; artifact presence is not proof. */
		public boolean provesMaterialCommit() {
			return state == CommitAttemptState.COMPLETED
				&& result.filter(CommitResult::committed).isPresent();
		}

		/**
		 * The gateway has durable proof that this attempt crossed no material boundary, so the
		 * exact same approved command may be tried again after its transient cause clears.
		 */
		public boolean allowsRetryWithoutMaterial() {
			if (state != CommitAttemptState.COMPLETED) return false;
			CommitResult completed = result.orElseThrow();
			return !completed.committed() && switch (completed.rejection()) {
				case PACKAGER_UNLOADED, SOURCE_SNAPSHOT_MISMATCH,
					NATIVE_DISPATCH_REJECTED -> true;
				default -> false;
			};
		}
	}

	/** Direct Create adapter for the deliberately weaker, never-registered STANDARD route. */
	public static final class BareDispatchAdapter {
		private final NativeDispatcher dispatcher;
		private final BareAuthoritativeState state;
		private final ServerThreadGuard serverThread;
		private final BareCommitStore commitStore;

		public BareDispatchAdapter(NativeDispatcher dispatcher, BareAuthoritativeState state,
			ServerThreadGuard serverThread, BareCommitStore commitStore) {
			this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
			this.state = Objects.requireNonNull(state, "state");
			this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
			this.commitStore = Objects.requireNonNull(commitStore, "commitStore");
		}

		public static BareDispatchAdapter usingCreate(BareAuthoritativeState state,
			ServerThreadGuard serverThread, BareCommitStore commitStore) {
			return new BareDispatchAdapter(request -> dispatchCreate(request, serverThread), state, serverThread,
				commitStore);
		}

		public synchronized BareCommitResult commit(BareDispatchRequest request) {
			request = Objects.requireNonNull(request, "request");
			if (!serverThread.isServerThread()) return rejected(request, Rejection.NOT_SERVER_THREAD);
			final Optional<PersistedBareCommit> loaded;
			try {
				loaded = commitStore.load(request.intentId());
			} catch (RuntimeException unavailable) {
				return rejected(request, Rejection.DURABLE_STORE_UNAVAILABLE);
			}
			if (loaded.isPresent()) {
				PersistedBareCommit prior = loaded.orElseThrow();
				if (!prior.request().equals(request))
					return rejected(request, Rejection.INTENT_ID_COLLISION);
				return prior.state() == CommitAttemptState.ATTEMPTING
					? rejected(request, Rejection.NATIVE_DISPATCH_UNCERTAIN)
					: prior.result().orElseThrow();
			}
			BareCommitContext actual = Objects.requireNonNull(state.capture(request),
				"bare commit context");
			if (actual.directoryRegistrationGeneration() != request.directoryRegistrationGeneration()
				|| !(actual.resolution() instanceof AgentResolution.NeverRegistered never)
				|| !never.route().equals(request.route()))
				return persistCompleted(rejected(request, Rejection.DIRECTORY_STATE_MISMATCH));
			if (request.patternKind() != PatternKind.STANDARD
				|| !request.standardPatternPermitsBare()
				|| actual.patternKind() != request.patternKind()
				|| actual.standardPatternPermitsBare() != request.standardPatternPermitsBare())
				return persistCompleted(rejected(request, Rejection.BARE_PERMISSION_MISMATCH));
			if (actual.sourceInventorySnapshotGeneration()
				!= request.sourceInventorySnapshotGeneration())
				return persistCompleted(rejected(request, Rejection.SOURCE_SNAPSHOT_MISMATCH));
			try {
				commitStore.persist(PersistedBareCommit.attempting(request));
			} catch (RuntimeException unavailable) {
				return rejected(request, Rejection.DURABLE_STORE_UNAVAILABLE);
			}
			try {
				NativeDispatchFact fact = dispatcher.dispatch(request);
				return persistCompleted(fact.accepted()
					? new BareCommitResult(request, true, fact.nativeOrders(), Rejection.NONE)
					: rejected(request, Rejection.NATIVE_DISPATCH_REJECTED));
			} catch (RuntimeException failure) {
				BareCommitResult uncertain = rejected(request,
					Rejection.NATIVE_DISPATCH_UNCERTAIN);
				try {
					commitStore.persist(PersistedBareCommit.completed(request, uncertain));
				} catch (RuntimeException ignored) {
					// The durable ATTEMPTING marker remains fail-closed evidence.
				}
				return uncertain;
			}
		}

		private BareCommitResult persistCompleted(BareCommitResult result) {
			try {
				commitStore.persist(PersistedBareCommit.completed(result.request(), result));
				return result;
			} catch (RuntimeException failure) {
				return rejected(result.request(), Rejection.NATIVE_DISPATCH_UNCERTAIN);
			}
		}

		private static BareCommitResult rejected(BareDispatchRequest request, Rejection reason) {
			return new BareCommitResult(request, false, List.of(), reason);
		}
	}

	@FunctionalInterface
	public interface BareAuthoritativeState {
		BareCommitContext capture(BareDispatchRequest request);
	}

	public record BareCommitContext(AgentResolution resolution,
		long directoryRegistrationGeneration, PatternKind patternKind,
		boolean standardPatternPermitsBare, long sourceInventorySnapshotGeneration) {
		public BareCommitContext {
			resolution = Objects.requireNonNull(resolution, "resolution");
			patternKind = Objects.requireNonNull(patternKind, "patternKind");
			if (directoryRegistrationGeneration < 0 || sourceInventorySnapshotGeneration < 0)
				throw new IllegalArgumentException("Bare authoritative generations cannot be negative");
		}
	}

	public record BareDispatchRequest(UUID intentId, UUID runId, CreateRouteKey route,
		long sourceInventorySnapshotGeneration, long directoryRegistrationGeneration,
		PatternKind patternKind, boolean standardPatternPermitsBare, Map<StackKey, Long> manifest)
		implements DispatchRequest {
		public BareDispatchRequest {
			intentId = Objects.requireNonNull(intentId, "intentId");
			runId = Objects.requireNonNull(runId, "runId");
			route = Objects.requireNonNull(route, "route");
			patternKind = Objects.requireNonNull(patternKind, "patternKind");
			if (sourceInventorySnapshotGeneration < 0 || directoryRegistrationGeneration < 0)
				throw new IllegalArgumentException("Bare frozen generations cannot be negative");
			manifest = immutablePositiveManifest(manifest);
		}
	}

	public record BareCommitResult(BareDispatchRequest request, boolean committed,
		List<NativeOrderLedger.NativeOrderIdentity> nativeOrders, Rejection rejection) {
		public BareCommitResult {
			request = Objects.requireNonNull(request, "request");
			nativeOrders = List.copyOf(nativeOrders);
			rejection = Objects.requireNonNull(rejection, "rejection");
			if (committed != (rejection == Rejection.NONE) || committed == nativeOrders.isEmpty())
				throw new IllegalArgumentException("Invalid bare commit result");
		}
	}

	public interface BareCommitStore {
		Optional<PersistedBareCommit> load(UUID intentId);
		void persist(PersistedBareCommit commit);
	}

	public record PersistedBareCommit(BareDispatchRequest request, CommitAttemptState state,
		Optional<BareCommitResult> result) {
		public PersistedBareCommit {
			request = Objects.requireNonNull(request, "request");
			state = Objects.requireNonNull(state, "state");
			result = Objects.requireNonNull(result, "result");
			if (state == CommitAttemptState.ATTEMPTING && result.isPresent()
				|| state == CommitAttemptState.COMPLETED && result.isEmpty()
				|| result.isPresent() && !result.orElseThrow().request().equals(request))
				throw new IllegalArgumentException("Invalid durable bare commit record");
		}

		public static PersistedBareCommit attempting(BareDispatchRequest request) {
			return new PersistedBareCommit(request, CommitAttemptState.ATTEMPTING,
				Optional.empty());
		}

		public static PersistedBareCommit completed(BareDispatchRequest request,
			BareCommitResult result) {
			return new PersistedBareCommit(request, CommitAttemptState.COMPLETED,
				Optional.of(result));
		}
	}

    /** Temporary old-intent adapter only; actual Create effects are now owned by the replacement. */
    private static NativeDispatchFact dispatchCreate(DispatchRequest request, ServerThreadGuard serverThread) {
        var capacity = CBConfigs.SERVER.biocomputing.productionNativeRequestEntries;
        var result = CreatePackageDispatch.dispatch(request.route().logisticsNetworkId(), request.route().address(),
            request.manifest(), CBConfigs.SERVER_SPEC.isLoaded() ? capacity.get() : capacity.getDefault(),
            serverThread::isServerThread);
        return new NativeDispatchFact(result.accepted(), result.orders().stream()
            .map(order -> new NativeOrderLedger.NativeOrderIdentity(order.id(), order.links())).toList());
    }

	private static Map<StackKey, Long> immutablePositiveManifest(Map<StackKey, Long> values) {
		values = Map.copyOf(Objects.requireNonNull(values, "manifest"));
		if (values.isEmpty()) throw new IllegalArgumentException("Dispatch manifest cannot be empty");
		for (Map.Entry<StackKey, Long> entry : values.entrySet())
			if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException("Dispatch quantities must be positive");
		return values;
	}
}
