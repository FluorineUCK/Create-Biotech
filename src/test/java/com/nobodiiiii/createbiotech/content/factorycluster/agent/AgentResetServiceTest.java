package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;

class AgentResetServiceTest {
	private static final UUID AGENT = id(1);
	private static final UUID OWNER = id(2);
	private static final UUID OTHER = id(3);
	private static final RemoteRequestKey ACTIVE = new RemoteRequestKey(id(4), id(5), 6, 7, 8);
	private static final SpaceAddress INPUT = RemoteAgentRecoveryTestAccess.space(0);
	private static final SpaceAddress OUTPUT = RemoteAgentRecoveryTestAccess.space(1);

	@Test
	void ordinaryResetAcceptsLocalEndpointOrAuthorizedOwningPanelOnlyWhenEveryFactIsSafe() {
		RecordingStore store = new RecordingStore();
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true, store);
		AgentResetService.AuthorityIssuer issuer = issuer();
		AgentResetService.AuthorityFact local = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		AgentResetService.ResetSafetyFacts safe = issuer.resetSafety(local,
			service.snapshot()).orElseThrow();

		assertEquals(AgentResetService.ResetResult.RESET,
			service.reset(local, safe));
		assertFalse(service.snapshot().resetRequired());

		AgentResetService panelService = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true,
			new RecordingStore());
		AgentResetService.AuthorityFact panel = issuer.owningPanel(OWNER, id(20)).orElseThrow();
		assertEquals(AgentResetService.ResetResult.RESET,
			panelService.reset(panel, issuer.resetSafety(panel,
				panelService.snapshot()).orElseThrow()));
	}

	@Test
	void ordinaryResetRejectsWrongAuthorityAndEachUnsafePrecondition() {
		AgentResetService.AuthorityIssuer issuer = issuer();
		AgentResetService.AuthorityFact wrongPanel = issuer.owningPanel(OTHER, id(21)).orElseThrow();
		AgentResetService wrongService = service();
		AgentResetService.AuthorityFact validLocal = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		assertEquals(AgentResetService.ResetResult.UNAUTHORIZED, wrongService.reset(wrongPanel,
			issuer.resetSafety(validLocal, wrongService.snapshot()).orElseThrow()));

		List<AgentResetService.AuthorityIssuer> unsafeIssuers = List.of(
			issuer(Set.of(INPUT), false, true, true, true, true),
			issuer(Set.of(INPUT, OUTPUT), true, true, true, true, true),
			issuer(Set.of(INPUT, OUTPUT), false, false, true, true, true),
			issuer(Set.of(INPUT, OUTPUT), false, true, false, true, true),
			issuer(Set.of(INPUT, OUTPUT), false, true, true, false, true),
			issuer(Set.of(INPUT, OUTPUT), false, true, true, true, false));
		for (AgentResetService.AuthorityIssuer unsafeIssuer : unsafeIssuers) {
			AgentResetService unsafeService = service();
			AgentResetService.AuthorityFact authority = unsafeIssuer.localEndpoint(AGENT, INPUT)
				.orElseThrow();
			assertEquals(AgentResetService.ResetResult.UNSAFE, unsafeService.reset(authority,
				unsafeIssuer.resetSafety(authority, unsafeService.snapshot()).orElseThrow()));
		}
	}

	@Test
	void panelCanNeverForceResetEvenWhenItOwnsTheCluster() {
		AgentResetService service = service();
		AgentResetService.AuthorityFact panel = issuer().owningPanel(OWNER, id(22)).orElseThrow();
		assertEquals(AgentResetService.ForceResetResult.LOCAL_ENDPOINT_REQUIRED,
			service.forceReset(panel, new AgentResetService.ForceResetEvidence(ACTIVE,
				Set.of(new AgentResetService.NativeOrderTombstone(501, 0)), "operator reset")));
		assertEquals(8, service.snapshot().agentResetGeneration());
	}

	@Test
	void irreversibleRecoveryCanDurablyFenceTheExactActiveContractForReset() {
		RecordingStore store = new RecordingStore();
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE,
			false, store);
		assertTrue(service.requireReset(ACTIVE));
		assertTrue(service.snapshot().resetRequired());
		assertEquals(Optional.of(ACTIVE), service.snapshot().activeKey());
		assertTrue(service.requireReset(ACTIVE), "the exact persisted fence is idempotent");
		assertFalse(service.requireReset(new RemoteRequestKey(id(91), id(92), 6, 7, 8)));
	}

	@Test
	void normallyCompletedContractDurablyReleasesOnlyItsExactActiveFence() {
		RecordingStore store = new RecordingStore();
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE,
			false, store);
		RemoteRequestKey different = new RemoteRequestKey(id(93), id(94), 6, 7, 8);

		assertFalse(service.finishContract(different));
		assertEquals(Optional.of(ACTIVE), service.snapshot().activeKey());
		assertTrue(service.finishContract(ACTIVE));
		assertTrue(service.snapshot().activeKey().isEmpty());
		assertFalse(service.snapshot().resetRequired());
		assertEquals(service.snapshot(), store.state);
	}

	@Test
	void localForceResetIncrementsGenerationAndPreservesTombstonesAndDiagnostics() {
		RecordingStore store = new RecordingStore();
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true, store);
		AgentResetService.ForceResetEvidence evidence = new AgentResetService.ForceResetEvidence(
			ACTIVE, Set.of(new AgentResetService.NativeOrderTombstone(501, 0)), "stalled order");

		assertEquals(AgentResetService.ForceResetResult.FORCED,
			service.forceReset(issuer().localEndpoint(AGENT, INPUT).orElseThrow(), evidence));
		AgentResetService.State state = service.snapshot();
		assertEquals(9, state.agentResetGeneration());
		assertFalse(state.resetRequired());
		assertTrue(state.activeKey().isEmpty());
		assertEquals(1, state.lateInputTombstones().size());
		assertEquals(1, state.diagnostics().size());

		AgentResetService restored = new AgentResetService(config(), AGENT, OWNER, 9, null, false, store);
		assertEquals(state, restored.snapshot());
	}

	@Test
	void preparedForceResetIsSideEffectFreeUntilItsDurableBatchIsInstalled() {
		RecordingStore store = new RecordingStore();
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true, store);
		AgentResetService.State before = service.snapshot();
		int writesBefore = store.writes.size();
		AgentResetService.ForceResetPreparation prepared = service.prepareForceReset(
			issuer().localEndpoint(AGENT, INPUT).orElseThrow(),
			new AgentResetService.ForceResetEvidence(ACTIVE,
				Set.of(new AgentResetService.NativeOrderTombstone(-501, 0)), "durable batch"));

		assertEquals(AgentResetService.ForceResetResult.FORCED, prepared.result());
		assertEquals(before, service.snapshot(), "preflight must not publish Reset state");
		assertEquals(writesBefore, store.writes.size(), "preflight must not write its private store");
		assertTrue(store.compareAndSet(AGENT, prepared.expectedRevision(),
			prepared.nextState().orElseThrow()), "test batch must commit the prepared revision");
		service.installCommittedForceReset(prepared);

		assertEquals(9, service.snapshot().agentResetGeneration());
		assertEquals(service.snapshot(), store.state);
	}

	@Test
	void idleMembershipGenerationIsPreparedWithoutPublishingBeforeTheBatch() {
		RecordingStore store = new RecordingStore();
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, null,
			false, store);
		AgentResetService.State before = service.snapshot();

		AgentResetService.MembershipGenerationPreparation prepared = service
			.prepareMembershipGeneration(9).orElseThrow();
		assertEquals(before, service.snapshot());
		assertEquals(before, store.state);
		assertTrue(store.compareAndSet(AGENT, prepared.expectedRevision(), prepared.nextState()));

		service.installCommittedMembershipGeneration(prepared);
		assertEquals(9, service.snapshot().agentResetGeneration());
		assertTrue(service.snapshot().activeKey().isEmpty());
		assertFalse(service.snapshot().resetRequired());
	}

	@Test
	void lateInputFromForcedGenerationIsDiagnosticAndCanNeverJoinAFutureRun() {
		AgentResetService service = service();
		service.forceReset(issuer().localEndpoint(AGENT, INPUT).orElseThrow(),
			new AgentResetService.ForceResetEvidence(ACTIVE,
				Set.of(new AgentResetService.NativeOrderTombstone(-501, 0)), "late order"));
		RemoteRequestKey future = new RemoteRequestKey(id(30), id(31), 9, 10, 9);
		assertTrue(service.beginFutureContract(future));

		assertEquals(AgentResetService.InputAdmission.LATE_INPUT,
			service.classifyInput(ACTIVE.runId(), 8, -501, 0));
		assertEquals(AgentResetService.InputAdmission.CURRENT_RUN,
			service.classifyInput(future.runId(), 9, 600, 0));
		assertEquals(AgentResetService.InputAdmission.INTERFERENCE,
			service.classifyInput(ACTIVE.runId(), 9, 501, 0));
	}

	@Test
	void staleOrFutureGenerationCannotStartOrResetTheCurrentAgent() {
		AgentResetService service = service();
		AgentResetService.AuthorityIssuer issuer = issuer();
		AgentResetService.AuthorityFact local = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		RemoteRequestKey staleKey = new RemoteRequestKey(id(70), id(71), 6, 7, 7);
		AgentResetService staleService = new AgentResetService(config(), AGENT, OWNER, 7,
			staleKey, true, new RecordingStore());
		AgentResetService.ResetSafetyFacts stale = issuer.resetSafety(local,
			staleService.snapshot()).orElseThrow();
		assertEquals(AgentResetService.ResetResult.STALE_GENERATION, service.reset(local, stale));
		assertFalse(service.beginFutureContract(
			new RemoteRequestKey(id(40), id(41), 1, 1, 7)));
		assertFalse(service.beginFutureContract(
			new RemoteRequestKey(id(42), id(43), 1, 1, 9)));
	}

	@Test
	void forceResetGenerationOverflowFailsClosedWithoutLosingEvidence() {
		RemoteRequestKey exhausted = new RemoteRequestKey(id(60), id(61), 6, 7,
			Long.MAX_VALUE);
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, Long.MAX_VALUE, exhausted,
			true, new RecordingStore());
		AgentResetService.State before = service.snapshot();
		assertEquals(AgentResetService.ForceResetResult.GENERATION_EXHAUSTED,
			service.forceReset(issuer().localEndpoint(AGENT, INPUT).orElseThrow(),
				new AgentResetService.ForceResetEvidence(exhausted, Set.of(), "overflow")));
		assertEquals(before, service.snapshot());
	}

	@Test
	void opaqueAuthorityFactsCannotBePubliclyConstructed() {
		for (var constructor : AgentResetService.AuthorityFact.class.getDeclaredConstructors())
			assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
		for (var constructor : AgentResetService.ResetSafetyFacts.class.getDeclaredConstructors())
			assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()));
		AgentResetService.AuthorityIssuer offThread = new AgentResetService.AuthorityIssuer(() -> false,
			resolver(Set.of(INPUT, OUTPUT), false, true, true, true, true));
		assertTrue(offThread.localEndpoint(AGENT, INPUT).isEmpty());
		AgentResetService.AuthorityIssuer issuer = issuer();
		AgentResetService.AuthorityFact first = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		AgentResetService.AuthorityFact second = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		AgentResetService tokenService = service();
		assertEquals(AgentResetService.ResetResult.STALE_COHERENCE_EPOCH,
			tokenService.reset(second, issuer.resetSafety(first,
				tokenService.snapshot()).orElseThrow()),
			"safety facts are bound to the exact guarded authority capture");
		assertThrows(IllegalArgumentException.class,
			() -> new AgentResetService(config(), AGENT, OWNER, -1, ACTIVE, true, new RecordingStore()));
	}

	@Test
	void resetAndForceResetPublishNoVisibleStateWhenDurableCommitFails() {
		FaultStore resetStore = new FaultStore();
		AgentResetService resetService = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE,
			true, resetStore);
		AgentResetService.State resetBefore = resetService.snapshot();
		AgentResetService.AuthorityIssuer issuer = issuer();
		AgentResetService.AuthorityFact local = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		resetStore.failNext();
		assertThrows(IllegalStateException.class, () -> resetService.reset(local,
			issuer.resetSafety(local, resetService.snapshot()).orElseThrow()));
		assertEquals(resetBefore, resetService.snapshot());

		FaultStore forceStore = new FaultStore();
		AgentResetService forceService = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE,
			true, forceStore);
		AgentResetService.State forceBefore = forceService.snapshot();
		forceStore.failNext();
		assertThrows(IllegalStateException.class, () -> forceService.forceReset(
			issuer.localEndpoint(AGENT, INPUT).orElseThrow(),
			new AgentResetService.ForceResetEvidence(ACTIVE, Set.of(), "durable failure")));
		assertEquals(forceBefore, forceService.snapshot());
	}

	@Test
	void safetyCaptureIsOneShotAndCannotClearANewerSameGenerationContract() {
		AgentResetService service = service();
		AgentResetService.AuthorityIssuer issuer = issuer();
		AgentResetService.AuthorityFact oldAuthority = issuer.localEndpoint(AGENT, INPUT)
			.orElseThrow();
		AgentResetService.ResetSafetyFacts oldFacts = issuer.resetSafety(oldAuthority,
			service.snapshot()).orElseThrow();
		AgentResetService.AuthorityFact winner = issuer.localEndpoint(AGENT, INPUT).orElseThrow();
		assertEquals(AgentResetService.ResetResult.RESET, service.reset(winner,
			issuer.resetSafety(winner, service.snapshot()).orElseThrow()));
		RemoteRequestKey future = new RemoteRequestKey(id(80), id(81), 9, 10, 8);
		assertTrue(service.beginFutureContract(future));

		assertEquals(AgentResetService.ResetResult.STALE_STATE,
			service.reset(oldAuthority, oldFacts));
		assertEquals(Optional.of(future), service.snapshot().activeKey());
	}

	@Test
	void forceResetUsesDurableRevisionCasAcrossConcurrentServices() {
		RecordingStore store = new RecordingStore();
		AgentResetService first = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true,
			store);
		AgentResetService second = new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true,
			store);
		AgentResetService.ForceResetEvidence evidence = new AgentResetService.ForceResetEvidence(
			ACTIVE, Set.of(), "concurrent force reset");
		assertEquals(AgentResetService.ForceResetResult.FORCED, first.forceReset(
			issuer().localEndpoint(AGENT, INPUT).orElseThrow(), evidence));

		assertEquals(AgentResetService.ForceResetResult.STALE_STATE, second.forceReset(
			issuer().localEndpoint(AGENT, INPUT).orElseThrow(), evidence));
		assertEquals(9, store.state.agentResetGeneration());
		assertEquals(1, store.state.lateInputTombstones().size());
	}

	@Test
	void failedPreDispatchAdmissionCanAbandonOnlyItsExactFutureContract() {
		AgentResetService service = new AgentResetService(config(), AGENT, OWNER, 8, null,
			false, new RecordingStore());
		RemoteRequestKey future = new RemoteRequestKey(id(501), id(502), 7, 11, 8);
		assertTrue(service.beginFutureContract(future));

		assertFalse(service.abandonFutureContract(new RemoteRequestKey(id(503), id(504), 7, 11, 8)));
		assertTrue(service.abandonFutureContract(future));
		assertTrue(service.snapshot().activeKey().isEmpty());
		assertTrue(service.beginFutureContract(future), "the exact admission must be retryable");
	}

	private static AgentResetService service() {
		return new AgentResetService(config(), AGENT, OWNER, 8, ACTIVE, true, new RecordingStore());
	}

	private static FactoryRuntimeConfig config() {
		return new FactoryRuntimeConfig(10, 1000, 16, 16, 4, 4, 32, 64, 20, 5, 8, 3,
			8, 64);
	}

	private static AgentResetService.AuthorityIssuer issuer() {
		return issuer(Set.of(INPUT, OUTPUT), false, true, true, true, true);
	}

	private static AgentResetService.AuthorityIssuer issuer(Set<SpaceAddress> loaded,
		boolean leaseActive, boolean drainComplete, boolean inputsClosed,
		boolean packagersEmpty, boolean backlogsEmpty) {
		return new AgentResetService.AuthorityIssuer(() -> true, resolver(loaded, leaseActive,
			drainComplete, inputsClosed, packagersEmpty, backlogsEmpty));
	}

	private static AgentResetService.AuthorityResolver resolver(Set<SpaceAddress> loaded,
		boolean leaseActive, boolean drainComplete, boolean inputsClosed,
		boolean packagersEmpty, boolean backlogsEmpty) {
		return new AgentResetService.AuthorityResolver() {
			@Override public Optional<AgentResetService.ResolvedAuthority> local(UUID agentId,
				SpaceAddress endpoint) {
				return Optional.of(new AgentResetService.ResolvedAuthority(agentId, OWNER,
					AgentResetService.AuthorityKind.LOCAL_ENDPOINT, endpoint, id(90)));
			}
			@Override public Optional<AgentResetService.ResolvedAuthority> panel(UUID clusterId,
				UUID panelId) {
				return Optional.of(new AgentResetService.ResolvedAuthority(AGENT, clusterId,
					AgentResetService.AuthorityKind.OWNING_PANEL, null, id(90)));
			}
			@Override public Optional<AgentResetService.ResolvedResetSafetyFacts> resetSafety(
				AgentResetService.ResetSafetyRequest request) {
				return Optional.of(new AgentResetService.ResolvedResetSafetyFacts(request.agentId(),
					request.ownerClusterId(), request.agentResetGeneration(), request.coherenceEpoch(),
					request.stateRevision(), request.activeKey(),
					Set.of(INPUT, OUTPUT), loaded, leaseActive, drainComplete, inputsClosed,
					packagersEmpty, backlogsEmpty));
			}
		};
	}

	private static UUID id(long value) { return new UUID(0, value); }

	private static class RecordingStore implements AgentResetService.StateStore {
		protected AgentResetService.State state;
		private final List<AgentResetService.State> writes = new ArrayList<>();
		@Override public Optional<AgentResetService.State> load(UUID agentId) {
			return Optional.ofNullable(state);
		}
		@Override public boolean compareAndSet(UUID agentId, long expectedRevision,
			AgentResetService.State next) {
			long currentRevision = state == null ? -1 : state.revision();
			if (currentRevision != expectedRevision) return false;
			state = next;
			writes.add(next);
			return true;
		}
	}

	private static final class FaultStore extends RecordingStore {
		private boolean failNext;
		void failNext() { failNext = true; }
		@Override public boolean compareAndSet(UUID agentId, long expectedRevision,
			AgentResetService.State next) {
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("simulated reset persistence failure");
			}
			return super.compareAndSet(agentId, expectedRevision, next);
		}
	}
}
