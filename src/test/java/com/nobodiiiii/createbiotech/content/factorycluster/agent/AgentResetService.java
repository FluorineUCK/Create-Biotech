package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;

/** Server-authoritative ordinary Reset and local-only Force Reset state. */
public final class AgentResetService {
	private final UUID agentId;
	private final UUID ownerClusterId;
	private final int evidenceCapacity;
	private final StateStore store;
	private State state;

	public AgentResetService(FactoryRuntimeConfig config, UUID agentId, UUID ownerClusterId,
		long agentResetGeneration, @Nullable RemoteRequestKey activeKey, boolean resetRequired,
		StateStore store) {
		this.agentId = Objects.requireNonNull(agentId, "agentId");
		this.ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		if (agentResetGeneration < 0)
			throw new IllegalArgumentException("Agent reset generation cannot be negative");
		this.evidenceCapacity = Objects.requireNonNull(config, "config").recoveryLedgerCapacity();
		this.store = Objects.requireNonNull(store, "store");
		Optional<State> restored = store.load(agentId);
		if (restored.isPresent()) {
			State durable = restored.orElseThrow();
			if (!durable.agentId().equals(agentId)
				|| !durable.ownerClusterId().equals(ownerClusterId)
				|| durable.agentResetGeneration() != agentResetGeneration)
				throw new IllegalArgumentException("Persisted Reset identity differs from observed Agent");
			this.state = durable;
		} else {
			Optional<RemoteRequestKey> active = Optional.ofNullable(activeKey);
			active.ifPresent(key -> {
				if (key.agentGeneration() != agentResetGeneration)
					throw new IllegalArgumentException("Active key differs from Agent reset generation");
			});
			this.state = new State(agentId, ownerClusterId, agentResetGeneration, active,
				resetRequired, List.of(), List.of(), 0);
			if (!store.compareAndSet(agentId, -1, state))
				throw new IllegalStateException("Reset state initialization lost its durable CAS");
		}
	}

	public ResetResult reset(AuthorityFact authority, ResetSafetyFacts facts) {
		authority = Objects.requireNonNull(authority, "authority");
		facts = Objects.requireNonNull(facts, "facts");
		if (!authorized(authority)) return ResetResult.UNAUTHORIZED;
		if (!facts.agentId().equals(agentId) || !facts.ownerClusterId().equals(ownerClusterId))
			return ResetResult.UNAUTHORIZED;
		if (!facts.captureToken.equals(authority.captureToken)
			|| !facts.coherenceEpoch().equals(authority.coherenceEpoch()))
			return ResetResult.STALE_COHERENCE_EPOCH;
		if (facts.agentResetGeneration() != state.agentResetGeneration())
			return ResetResult.STALE_GENERATION;
		if (facts.stateRevision() != state.revision()
			|| !facts.activeKey().equals(state.activeKey()))
			return ResetResult.STALE_STATE;
		if (!facts.safe()) return ResetResult.UNSAFE;
		State next = new State(agentId, ownerClusterId, state.agentResetGeneration(), Optional.empty(),
			false, state.lateInputTombstones(), state.diagnostics(), nextRevision());
		if (!commit(next)) return ResetResult.STALE_STATE;
		return ResetResult.RESET;
	}

	public ForceResetResult forceReset(AuthorityFact authority, ForceResetEvidence evidence) {
		ForceResetPreparation prepared = prepareForceReset(authority, evidence);
		if (prepared.result() != ForceResetResult.FORCED) return prepared.result();
		if (!store.compareAndSet(agentId, prepared.expectedRevision(),
			prepared.nextState().orElseThrow())) return ForceResetResult.STALE_STATE;
		installCommittedForceReset(prepared);
		return ForceResetResult.FORCED;
	}

	/** Pure Force Reset preflight for a wider journal batch owned by the composition root. */
	public ForceResetPreparation prepareForceReset(AuthorityFact authority,
		ForceResetEvidence evidence) {
		authority = Objects.requireNonNull(authority, "authority");
		evidence = Objects.requireNonNull(evidence, "evidence");
		if (!authorized(authority) || authority.kind() != AuthorityKind.LOCAL_ENDPOINT)
			return ForceResetPreparation.rejected(ForceResetResult.LOCAL_ENDPOINT_REQUIRED,
				state.revision());
		if (state.activeKey().isEmpty()
			|| !state.activeKey().orElseThrow().equals(evidence.key()))
			return ForceResetPreparation.rejected(ForceResetResult.STALE_ACTIVE_CONTRACT,
				state.revision());
		if (state.agentResetGeneration() == Long.MAX_VALUE)
			return ForceResetPreparation.rejected(ForceResetResult.GENERATION_EXHAUSTED,
				state.revision());
		if (state.lateInputTombstones().size() >= evidenceCapacity)
			return ForceResetPreparation.rejected(ForceResetResult.EVIDENCE_CAPACITY_FULL,
				state.revision());

		long nextGeneration = Math.incrementExact(state.agentResetGeneration());
		ArrayList<LateInputGenerationTombstone> tombstones =
			new ArrayList<>(state.lateInputTombstones());
		tombstones.add(new LateInputGenerationTombstone(evidence.key(),
			state.agentResetGeneration(), evidence.nativeOrders()));
		ArrayList<ResetDiagnostic> diagnostics = new ArrayList<>(state.diagnostics());
		diagnostics.add(new ResetDiagnostic(state.agentResetGeneration(), nextGeneration,
			evidence.key(), evidence.diagnostic()));
		State next = new State(agentId, ownerClusterId, nextGeneration, Optional.empty(), false,
			tombstones, diagnostics, nextRevision());
		return new ForceResetPreparation(ForceResetResult.FORCED, state.revision(),
			Optional.of(next));
	}

	/** Publishes only a state already committed by the composition root's durable batch. */
	public void installCommittedForceReset(ForceResetPreparation prepared) {
		prepared = Objects.requireNonNull(prepared, "prepared");
		State next = prepared.nextState().orElseThrow(() ->
			new IllegalArgumentException("Rejected Force Reset has no state to install"));
		if (prepared.result() != ForceResetResult.FORCED
			|| prepared.expectedRevision() != state.revision()
			|| next.revision() != state.revision() + 1
			|| !next.agentId().equals(agentId) || !next.ownerClusterId().equals(ownerClusterId)
			|| next.agentResetGeneration() != state.agentResetGeneration() + 1
			|| store.load(agentId).filter(next::equals).isEmpty())
			throw new IllegalStateException("Force Reset batch does not match live durable state");
		state = next;
	}

	/** Pure generation migration used by an idle formation-wide endpoint configuration batch. */
	public Optional<MembershipGenerationPreparation> prepareMembershipGeneration(
		long nextGeneration) {
		if (state.activeKey().isPresent() || state.resetRequired()
			|| state.agentResetGeneration() == Long.MAX_VALUE
			|| nextGeneration != state.agentResetGeneration() + 1) return Optional.empty();
		State next = new State(agentId, ownerClusterId, nextGeneration, Optional.empty(), false,
			state.lateInputTombstones(), state.diagnostics(), nextRevision());
		return Optional.of(new MembershipGenerationPreparation(state.revision(), next));
	}

	/** Publishes only an idle membership generation already committed by the outer batch. */
	public void installCommittedMembershipGeneration(MembershipGenerationPreparation prepared) {
		prepared = Objects.requireNonNull(prepared, "prepared");
		State next = prepared.nextState();
		if (prepared.expectedRevision() != state.revision()
			|| next.revision() != state.revision() + 1
			|| !next.agentId().equals(agentId) || !next.ownerClusterId().equals(ownerClusterId)
			|| next.agentResetGeneration() != state.agentResetGeneration() + 1
			|| state.activeKey().isPresent() || state.resetRequired()
			|| next.activeKey().isPresent() || next.resetRequired()
			|| store.load(agentId).filter(next::equals).isEmpty())
			throw new IllegalStateException("Membership batch does not match live Reset state");
		state = next;
	}

	/** Only the current reset generation may acquire a new contract after availability is published. */
	public boolean beginFutureContract(RemoteRequestKey key) {
		key = Objects.requireNonNull(key, "key");
		if (state.resetRequired() || state.activeKey().isPresent()
			|| key.agentGeneration() != state.agentResetGeneration()) return false;
		State next = new State(agentId, ownerClusterId, state.agentResetGeneration(), Optional.of(key),
			false, state.lateInputTombstones(), state.diagnostics(), nextRevision());
		return commit(next);
	}

	/** Compensates an admission that failed before any irreversible Create dispatch. */
	public boolean abandonFutureContract(RemoteRequestKey key) {
		key = Objects.requireNonNull(key, "key");
		if (state.resetRequired() || state.activeKey().filter(key::equals).isEmpty()) return false;
		State next = new State(agentId, ownerClusterId, state.agentResetGeneration(),
			Optional.empty(), false, state.lateInputTombstones(), state.diagnostics(),
			nextRevision());
		return commit(next);
	}

	/** Fences the exact active irreversible contract until an authorized Reset succeeds. */
	public boolean requireReset(RemoteRequestKey key) {
		key = Objects.requireNonNull(key, "key");
		if (state.activeKey().filter(key::equals).isEmpty()) return false;
		if (state.resetRequired()) return true;
		State next = new State(agentId, ownerClusterId, state.agentResetGeneration(),
			state.activeKey(), true, state.lateInputTombstones(), state.diagnostics(),
			nextRevision());
		return commit(next);
	}

	/** Clears the exact active fence only after normal durable output release. */
	public boolean finishContract(RemoteRequestKey key) {
		key = Objects.requireNonNull(key, "key");
		if (state.resetRequired() || state.activeKey().filter(key::equals).isEmpty()) return false;
		State next = new State(agentId, ownerClusterId, state.agentResetGeneration(),
			Optional.empty(), false, state.lateInputTombstones(), state.diagnostics(),
			nextRevision());
		return commit(next);
	}

	/** Classifies immutable server-thread package identity facts; no world object crosses here. */
	public InputAdmission classifyInput(UUID runId, long agentResetGeneration, int orderId,
		int linkIndex) {
		UUID observedRunId = Objects.requireNonNull(runId, "runId");
		// Create order ids are signed hashes; only the link index has a non-negative domain.
		if (linkIndex < 0)
			throw new IllegalArgumentException("Native input link index cannot be negative");
		if (agentResetGeneration == state.agentResetGeneration()
			&& state.activeKey().filter(key -> key.runId().equals(observedRunId)).isPresent())
			return InputAdmission.CURRENT_RUN;
		if (agentResetGeneration < state.agentResetGeneration()
			&& state.lateInputTombstones().stream().anyMatch(tombstone ->
				tombstone.oldGeneration() == agentResetGeneration
					&& tombstone.key().runId().equals(observedRunId)))
			return InputAdmission.LATE_INPUT;
		return InputAdmission.INTERFERENCE;
	}

	public State snapshot() { return state; }

	private boolean authorized(AuthorityFact authority) {
		return authority.agentId().equals(agentId)
			&& authority.ownerClusterId().equals(ownerClusterId);
	}

	private long nextRevision() {
		return Math.incrementExact(state.revision());
	}
	private boolean commit(State next) {
		next = Objects.requireNonNull(next, "next");
		if (next.revision() != state.revision() + 1)
			throw new IllegalArgumentException("Reset state revision did not advance exactly once");
		if (!store.compareAndSet(agentId, state.revision(), next)) return false;
		state = next;
		return true;
	}

	public interface StateStore {
		default Optional<State> load(UUID agentId) { return Optional.empty(); }
		boolean compareAndSet(UUID agentId, long expectedRevision, State state);
	}

	/** Composition-boundary issuer: the resolver and guard remain on the server thread. */
	public static final class AuthorityIssuer {
		private final ServerThreadGuard guard;
		private final AuthorityResolver resolver;

		public AuthorityIssuer(ServerThreadGuard guard, AuthorityResolver resolver) {
			this.guard = Objects.requireNonNull(guard, "guard");
			this.resolver = Objects.requireNonNull(resolver, "resolver");
		}

		public Optional<AuthorityFact> localEndpoint(UUID agentId, SpaceAddress endpoint) {
			agentId = Objects.requireNonNull(agentId, "agentId");
			endpoint = Objects.requireNonNull(endpoint, "endpoint");
			if (!guard.onServerThread()) return Optional.empty();
			ResolvedAuthority resolved = resolver.local(agentId, endpoint).orElse(null);
			return resolved != null && resolved.kind() == AuthorityKind.LOCAL_ENDPOINT
				&& resolved.agentId().equals(agentId) && endpoint.equals(resolved.endpoint())
				? Optional.of(new AuthorityFact(resolved.agentId(), resolved.ownerClusterId(),
					resolved.kind(), endpoint, resolved.coherenceEpoch(), UUID.randomUUID()))
				: Optional.empty();
		}

		public Optional<AuthorityFact> owningPanel(UUID clusterId, UUID panelId) {
			clusterId = Objects.requireNonNull(clusterId, "clusterId");
			panelId = Objects.requireNonNull(panelId, "panelId");
			if (!guard.onServerThread()) return Optional.empty();
			ResolvedAuthority resolved = resolver.panel(clusterId, panelId).orElse(null);
			return resolved != null && resolved.kind() == AuthorityKind.OWNING_PANEL
				&& resolved.ownerClusterId().equals(clusterId)
				? Optional.of(new AuthorityFact(resolved.agentId(), resolved.ownerClusterId(),
					resolved.kind(), null, resolved.coherenceEpoch(), UUID.randomUUID()))
				: Optional.empty();
		}

		public Optional<ResetSafetyFacts> resetSafety(AuthorityFact authority, State observedState) {
			authority = Objects.requireNonNull(authority, "authority");
			observedState = Objects.requireNonNull(observedState, "observedState");
			if (!guard.onServerThread() || !authority.agentId().equals(observedState.agentId())
				|| !authority.ownerClusterId().equals(observedState.ownerClusterId()))
				return Optional.empty();
			ResetSafetyRequest request = new ResetSafetyRequest(authority.agentId(),
				authority.ownerClusterId(), observedState.agentResetGeneration(),
				authority.coherenceEpoch(), observedState.revision(), observedState.activeKey());
			ResolvedResetSafetyFacts resolved = resolver.resetSafety(request).orElse(null);
			if (resolved == null || !resolved.matches(request)) return Optional.empty();
			return Optional.of(new ResetSafetyFacts(resolved, authority.captureToken));
		}
	}

	public static final class AuthorityFact {
		private final UUID agentId;
		private final UUID ownerClusterId;
		private final AuthorityKind kind;
		@Nullable private final SpaceAddress endpoint;
		private final UUID coherenceEpoch;
		private final UUID captureToken;

		private AuthorityFact(UUID agentId, UUID ownerClusterId, AuthorityKind kind,
			@Nullable SpaceAddress endpoint, UUID coherenceEpoch, UUID captureToken) {
			this.agentId = Objects.requireNonNull(agentId, "agentId");
			this.ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			this.kind = Objects.requireNonNull(kind, "kind");
			this.endpoint = endpoint;
			this.coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
			this.captureToken = Objects.requireNonNull(captureToken, "captureToken");
			if ((kind == AuthorityKind.LOCAL_ENDPOINT) != (endpoint != null))
				throw new IllegalArgumentException("Reset authority endpoint differs from its kind");
		}

		public UUID agentId() { return agentId; }
		public UUID ownerClusterId() { return ownerClusterId; }
		public AuthorityKind kind() { return kind; }
		public Optional<SpaceAddress> endpoint() { return Optional.ofNullable(endpoint); }
		public UUID coherenceEpoch() { return coherenceEpoch; }
	}

	@FunctionalInterface
	public interface ServerThreadGuard { boolean onServerThread(); }

	public interface AuthorityResolver {
		Optional<ResolvedAuthority> local(UUID agentId, SpaceAddress endpoint);
		Optional<ResolvedAuthority> panel(UUID clusterId, UUID panelId);
		Optional<ResolvedResetSafetyFacts> resetSafety(ResetSafetyRequest request);
	}

	public record ResolvedAuthority(UUID agentId, UUID ownerClusterId, AuthorityKind kind,
		@Nullable SpaceAddress endpoint, UUID coherenceEpoch) {
		public ResolvedAuthority {
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			kind = Objects.requireNonNull(kind, "kind");
			coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
			if ((kind == AuthorityKind.LOCAL_ENDPOINT) != (endpoint != null))
				throw new IllegalArgumentException("Resolved authority endpoint differs from its kind");
		}
	}

	public static final class ResetSafetyFacts {
		private final ResolvedResetSafetyFacts resolved;
		private final UUID captureToken;

		private ResetSafetyFacts(ResolvedResetSafetyFacts resolved, UUID captureToken) {
			this.resolved = Objects.requireNonNull(resolved, "resolved");
			this.captureToken = Objects.requireNonNull(captureToken, "captureToken");
		}

		public UUID agentId() { return resolved.agentId(); }
		public UUID ownerClusterId() { return resolved.ownerClusterId(); }
		public long agentResetGeneration() { return resolved.agentResetGeneration(); }
		public UUID coherenceEpoch() { return resolved.coherenceEpoch(); }
		public long stateRevision() { return resolved.stateRevision(); }
		public Optional<RemoteRequestKey> activeKey() { return resolved.activeKey(); }
		public Set<SpaceAddress> allEndpoints() { return resolved.allEndpoints(); }
		public Set<SpaceAddress> loadedEndpoints() { return resolved.loadedEndpoints(); }
		public boolean safe() {
			return loadedEndpoints().equals(allEndpoints()) && !resolved.leaseActive()
				&& resolved.cancellationDrainComplete()
				&& resolved.inputsClosedOrTombstoned()
				&& resolved.outputPackagerBuffersEmpty()
				&& resolved.destinationBacklogsEmpty();
		}
	}

	public record ResetSafetyRequest(UUID agentId, UUID ownerClusterId,
		long agentResetGeneration, UUID coherenceEpoch, long stateRevision,
		Optional<RemoteRequestKey> activeKey) {
		public ResetSafetyRequest {
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
			activeKey = Objects.requireNonNull(activeKey, "activeKey");
			if (agentResetGeneration < 0 || stateRevision < 0)
				throw new IllegalArgumentException("Reset generation cannot be negative");
		}
	}

	public record ResolvedResetSafetyFacts(UUID agentId, UUID ownerClusterId,
		long agentResetGeneration, UUID coherenceEpoch, long stateRevision,
		Optional<RemoteRequestKey> activeKey, Set<SpaceAddress> allEndpoints,
		Set<SpaceAddress> loadedEndpoints, boolean leaseActive,
		boolean cancellationDrainComplete, boolean inputsClosedOrTombstoned,
		boolean outputPackagerBuffersEmpty, boolean destinationBacklogsEmpty) {
		public ResolvedResetSafetyFacts {
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			coherenceEpoch = Objects.requireNonNull(coherenceEpoch, "coherenceEpoch");
			activeKey = Objects.requireNonNull(activeKey, "activeKey");
			allEndpoints = Set.copyOf(Objects.requireNonNull(allEndpoints, "allEndpoints"));
			loadedEndpoints = Set.copyOf(Objects.requireNonNull(loadedEndpoints, "loadedEndpoints"));
			if (agentResetGeneration < 0 || stateRevision < 0 || allEndpoints.isEmpty()
				|| !allEndpoints.containsAll(loadedEndpoints))
				throw new IllegalArgumentException("Invalid resolved Reset facts");
		}
		boolean matches(ResetSafetyRequest request) {
			return agentId.equals(request.agentId())
				&& ownerClusterId.equals(request.ownerClusterId())
				&& agentResetGeneration == request.agentResetGeneration()
				&& coherenceEpoch.equals(request.coherenceEpoch())
				&& stateRevision == request.stateRevision()
				&& activeKey.equals(request.activeKey());
		}
	}

	public record ForceResetEvidence(RemoteRequestKey key, Set<NativeOrderTombstone> nativeOrders,
		String diagnostic) {
		public ForceResetEvidence {
			key = Objects.requireNonNull(key, "key");
			nativeOrders = Set.copyOf(Objects.requireNonNull(nativeOrders, "nativeOrders"));
			diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
			if (diagnostic.isBlank()
				|| diagnostic.getBytes(StandardCharsets.UTF_8).length
					> ProcessProtocolMessage.MAX_TEXT_BYTES)
				throw new IllegalArgumentException("Force Reset diagnostic is empty or too large");
		}
	}

	public record ForceResetPreparation(ForceResetResult result, long expectedRevision,
		Optional<State> nextState) {
		public ForceResetPreparation {
			result = Objects.requireNonNull(result, "result");
			nextState = Objects.requireNonNull(nextState, "nextState");
			if (expectedRevision < 0 || (result == ForceResetResult.FORCED) != nextState.isPresent())
				throw new IllegalArgumentException("Invalid Force Reset preparation");
		}
		private static ForceResetPreparation rejected(ForceResetResult result,
			long expectedRevision) {
			return new ForceResetPreparation(result, expectedRevision, Optional.empty());
		}
	}

	public record NativeOrderTombstone(int orderId, int linkIndex) {
		public NativeOrderTombstone {
			// Create order ids are signed hashes; only the link index has a non-negative domain.
			if (linkIndex < 0)
				throw new IllegalArgumentException("Native tombstone link index cannot be negative");
		}
	}

	public record LateInputGenerationTombstone(RemoteRequestKey key, long oldGeneration,
		Set<NativeOrderTombstone> nativeOrders) {
		public LateInputGenerationTombstone {
			key = Objects.requireNonNull(key, "key");
			nativeOrders = Set.copyOf(Objects.requireNonNull(nativeOrders, "nativeOrders"));
			if (oldGeneration < 0 || key.agentGeneration() != oldGeneration)
				throw new IllegalArgumentException("Late input tombstone generation mismatch");
		}
	}

	public record ResetDiagnostic(long oldGeneration, long newGeneration,
		RemoteRequestKey key, String detail) {
		public ResetDiagnostic {
			key = Objects.requireNonNull(key, "key");
			detail = Objects.requireNonNull(detail, "detail");
			if (oldGeneration < 0 || newGeneration != oldGeneration + 1
				|| key.agentGeneration() != oldGeneration)
				throw new IllegalArgumentException("Reset diagnostic generation mismatch");
		}
	}

	public record State(UUID agentId, UUID ownerClusterId, long agentResetGeneration,
		Optional<RemoteRequestKey> activeKey, boolean resetRequired,
		List<LateInputGenerationTombstone> lateInputTombstones,
		List<ResetDiagnostic> diagnostics, long revision) {
		public State {
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			activeKey = Objects.requireNonNull(activeKey, "activeKey");
			lateInputTombstones = List.copyOf(Objects.requireNonNull(lateInputTombstones,
				"lateInputTombstones"));
			diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
			if (agentResetGeneration < 0 || revision < 0
				|| lateInputTombstones.size() != diagnostics.size())
				throw new IllegalArgumentException("Invalid durable Reset state");
			activeKey.ifPresent(key -> {
				if (key.agentGeneration() != agentResetGeneration)
					throw new IllegalArgumentException("Active key differs from current generation");
			});
			for (int index = 0; index < lateInputTombstones.size(); index++) {
				LateInputGenerationTombstone tombstone = lateInputTombstones.get(index);
				ResetDiagnostic diagnostic = diagnostics.get(index);
				if (!tombstone.key().equals(diagnostic.key())
					|| tombstone.oldGeneration() != diagnostic.oldGeneration())
					throw new IllegalArgumentException("Reset evidence pairs disagree");
			}
		}
	}

	public record MembershipGenerationPreparation(long expectedRevision, State nextState) {
		public MembershipGenerationPreparation {
			nextState = Objects.requireNonNull(nextState, "nextState");
			if (expectedRevision < 0 || nextState.revision() != expectedRevision + 1
				|| nextState.activeKey().isPresent() || nextState.resetRequired())
				throw new IllegalArgumentException("Invalid membership Reset preparation");
		}
	}

	public enum AuthorityKind { LOCAL_ENDPOINT, OWNING_PANEL }
	public enum ResetResult {
		RESET,
		UNAUTHORIZED,
		STALE_GENERATION,
		STALE_COHERENCE_EPOCH,
		STALE_STATE,
		UNSAFE
	}
	public enum ForceResetResult {
		FORCED,
		LOCAL_ENDPOINT_REQUIRED,
		STALE_ACTIVE_CONTRACT,
		GENERATION_EXHAUSTED,
		EVIDENCE_CAPACITY_FULL,
		STALE_STATE
	}
	public enum InputAdmission { CURRENT_RUN, LATE_INPUT, INTERFERENCE }
}
