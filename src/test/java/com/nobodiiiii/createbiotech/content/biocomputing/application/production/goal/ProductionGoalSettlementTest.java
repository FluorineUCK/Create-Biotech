package com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionGoalSettlementTest {

	@BeforeAll
	static void bootstrapMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
			type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
			.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		}
		catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void stockPersistsItsBaselineDeltaWithoutAddingReceiptCredit() {
		try (Scenario s = new Scenario()) {
			s.owner.observeStocks();
			assertEquals(3, s.owner.observation(s.plan.goalId()).orElseThrow().creditedCount());
			assertEquals(2, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
			assertEquals(s.owner.observation(s.plan.goalId()), s.observation(s.plan.goalId()));
		}
	}

	@Test
	void actualReceivedRunIsCommittedBeforePublishingItsCreditAndRetiringLiveArtifacts() {
		try (Scenario s = new Scenario()) {
			s.journal.write("production_plan/" + s.plan.goalId(), s.plan, RegistryAccess.EMPTY);
			s.journal.write("output_contract/" + s.receipt.runId(), s.receipt, RegistryAccess.EMPTY);
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			assertEquals(4, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
			assertEquals(s.owner.observation(s.plan.goalId()), s.observation(s.plan.goalId()));
			assertFalse(s.liveArtifacts);
			assertTrue(s.journal.read("output_contract/" + s.receipt.runId(), ProductionOutputContract.Snapshot.class, RegistryAccess.EMPTY).isEmpty());
			assertFalse(s.owner.retireReceivedRun(s.receipt), "durable artifact removal makes duplicate settlement a no-op");
			assertEquals(4, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
		}
	}

	@Test
	void waitDoesNotSavePromisesOrAdvanceTheOwner() {
		try (Scenario s = new Scenario()) {
			var prior = s.owner.observation(s.plan.goalId());
			s.loaded = false;
			s.stockCount = 100;
			s.owner.observeStocks();
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertEquals(prior, s.observation(s.plan.goalId()));
			assertEquals(GoalState.RUNNABLE, s.goal().state());
		}
	}

	@Test
	void baselineAndConfirmedFloorSurviveExternalStockConsumption() {
		try (Scenario s = new Scenario()) {
			var prior = new GoalObservationState(s.plan.goalId(), s.plan.clusterId(), new StockObservationScope.NetworkSummary(id(4), s.plan.output(), 16), 10, 6, 2, 2, Optional.of(id(900)));
			s.saveObservation(prior);
			s.reconstruct();
			s.stockCount = 13;
			s.owner.observeStocks();
			assertEquals(3, s.owner.observation(s.plan.goalId()).orElseThrow().creditedCount());
			s.stockCount = 3;
			s.owner.observeStocks();
			assertEquals(2, s.owner.observation(s.plan.goalId()).orElseThrow().creditedCount());
		}
	}

	@Test
	void stockEncodeFailureDoesNotPublishObservationOrEngineCredit() {
		try (Scenario s = new Scenario()) {
			var prior = s.owner.observation(s.plan.goalId());
			s.stockCount = 100;
			s.failWrite = true;
			assertThrows(RuntimeException.class, s.owner::observeStocks);
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertEquals(prior, s.observation(s.plan.goalId()));
			assertEquals(GoalState.RUNNABLE, s.goal().state());
		}
	}

	@Test
	void ownerCopiesItsSourceAndExposesOnlyImmutableQueries() {
		try (Scenario s = new Scenario()) {
			s.sourceSnapshot = new LinkedHashMap<>(s.loadObservations());
			s.reconstruct();
			var snapshot = s.owner.observations();
			s.sourceSnapshot.clear();
			assertEquals(snapshot, s.owner.observations());
			assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
		}
	}

	@Test
	void staleAdmissionPublicationReadsDurableCreditAndNeverOverwritesInstalledEvidence() {
		try (Scenario s = new Scenario()) {
			s.seedReceipt();
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			var advanced = s.owner.observation(s.plan.goalId());
			s.owner.admissionCommitted(s.plan.goalId());
			assertEquals(advanced, s.owner.observation(s.plan.goalId()));
			s.sourceSnapshot = new LinkedHashMap<>();
			s.reconstruct();
			assertTrue(s.owner.observation(s.plan.goalId()).isEmpty());
			s.owner.admissionCommitted(s.plan.goalId());
			assertEquals(advanced, s.owner.observation(s.plan.goalId()), "missing publication reads the actual journal, not stale PREPARED payload");
		}
	}

	@Test
	void exactReceiptCapsActualPrimaryAtReceiverTargetAndNeverCountsSealedOutput() {
		try (Scenario s = new Scenario()) {
			s.receipt = outputSnapshot(s.fixture, 4, ProductionOutputContract.Milestone.OUTPUT_DISPATCHED);
			s.seedReceipt();
			assertFalse(s.owner.retireReceivedRun(s.receipt));
			s.receipt = receivedOutput(s.fixture, 4);
			s.seedReceipt();
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			assertEquals(4, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount(), "only two of four actual outputs have frozen receiver authority");
		}
	}

	@Test
	void noOutputReceiptRetiresWithoutManufacturingGoalCredit() {
		try (Scenario s = new Scenario()) {
			s.policy = noOutputPolicy(s.fixture);
			s.receipt = receivedOutput(s.fixture, 0);
			s.seedReceipt();
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			assertEquals(2, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
			assertFalse(s.liveArtifacts);
		}
	}

	@Test
	void missingMismatchedAndChildActiveEvidenceRefuseSettlement() {
		try (Scenario s = new Scenario()) {
			assertFalse(s.owner.retireReceivedRun(s.receipt), "no durable output/plan");
			s.seedReceipt();
			s.childActive = true;
			assertFalse(s.owner.retireReceivedRun(s.receipt));
			s.childActive = false;
			s.journal.write("production_plan/" + s.plan.goalId(), s.fixture.beforeAdvance(), RegistryAccess.EMPTY);
			assertFalse(s.owner.retireReceivedRun(s.receipt), "live and durable plan differ");
			s.seedReceipt();
			s.journal.write("output_contract/" + s.receipt.runId(), receivedOutput(s.fixture, 1), RegistryAccess.EMPTY);
			assertFalse(s.owner.retireReceivedRun(s.receipt), "live and durable receipt differ");
			s.seedReceipt();
			s.recovery = new RecoveryLedger.State(Map.of(), Map.of(), Map.of(), Map.of());
			assertFalse(s.owner.retireReceivedRun(s.receipt), "no exact recovery lineage/outcome");
			s.recovery = acknowledgedCompletion(s.fixture);
			s.journal.remove("goal_observation/" + s.plan.goalId());
			assertFalse(s.owner.retireReceivedRun(s.receipt), "missing durable Goal observation");
			assertTrue(s.liveArtifacts);
			assertEquals(2, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
		}
	}

	@Test
	void cancellationFencesStockReplayAndRetiresOnlyItsExactReceivedRunWithoutCredit() {
		try (Scenario s = new Scenario()) {
			s.plan = s.fixture.beforeAdvance().cancelAfterDrainNow();
			s.recovery = acknowledgedTerminal(s.fixture, RemoteAgentRecord.TerminalOutcome.CANCEL_DRAINED);
			s.seedReceipt();
			var prior = s.owner.observation(s.plan.goalId());
			s.stockCount = 100;
			s.owner.observeStocks();
			s.owner.replayDurableCredits();
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertEquals(prior, s.observation(s.plan.goalId()));
			assertFalse(s.liveArtifacts);
			assertEquals(GoalState.RUNNABLE, s.goal().state());
		}
	}

	@Test
	void actualJournalEncodeFailurePreservesTheOwnerTransportArtifactsAndEngine() {
		try (Scenario s = new Scenario()) {
			s.seedReceipt();
			s.addRemotePair();
			s.failWrite = true;
			var prior = s.owner.observation(s.plan.goalId());
			var dispatches = s.dispatches();
			var exchanges = s.exchanges();
			assertThrows(RuntimeException.class, () -> s.owner.retireReceivedRun(s.receipt));
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertEquals(prior, s.observation(s.plan.goalId()));
			assertEquals(dispatches, s.dispatches());
			assertEquals(exchanges, s.exchanges());
			assertTrue(s.liveArtifacts);
			assertTrue(s.persistence().output(s.receipt.runId()).isPresent());
			assertTrue(s.savedKeys().containsAll(s.activeArtifactKeys()));
			assertEquals(GoalState.RUNNABLE, s.goal().state());
			s.failWrite = false;
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			assertTrue(s.dispatches().isEmpty());
			assertTrue(s.exchanges().isEmpty());
			assertFalse(s.liveArtifacts);
		}
	}

	@Test
	void changedLivePairFailsPreflightBeforeTheActualJournalTransaction() {
		try (Scenario s = new Scenario()) {
			s.seedReceipt();
			s.addRemotePair();
			s.changePairOnPreflight = true;
			var prior = s.owner.observation(s.plan.goalId());
			assertThrows(IllegalStateException.class, () -> s.owner.retireReceivedRun(s.receipt));
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertEquals(prior, s.observation(s.plan.goalId()));
			assertTrue(s.persistence().output(s.receipt.runId()).isPresent());
			assertTrue(s.liveArtifacts);
			assertFalse(s.dispatches().isEmpty());
			assertFalse(s.exchanges().isEmpty());
		}
	}

	@Test
	void changedDurablePairFailsCompareAndRemoveWithoutAdvancingLiveState() {
		try (Scenario s = new Scenario()) {
			s.seedReceipt();
			s.addRemotePair();
			var prior = s.owner.observation(s.plan.goalId());
			var pair = s.remote;
			s.journal.write("remote_recovery_exchange/" + s.fixture.key().requestId(), withPhase(pair.exchange(), FactoryOrchestrationServer.RemoteRecoveryPhase.STATUS_CONFIRMED), RegistryAccess.EMPTY);
			assertThrows(IllegalStateException.class, () -> s.owner.retireReceivedRun(s.receipt));
			assertEquals(prior, s.owner.observation(s.plan.goalId()));
			assertEquals(prior, s.observation(s.plan.goalId()));
			assertEquals(Map.of(pair.dispatch().messageId(), pair.dispatch()), s.dispatches());
			assertEquals(Map.of(s.fixture.key(), pair.exchange()), s.exchanges());
			assertTrue(s.liveArtifacts);
			assertTrue(s.persistence().output(s.receipt.runId()).isPresent());
		}
	}

	@Test
	void persistedReceiptSurvivesInterruptedEngineNotificationAndReplaysAfterRoundTrip() throws Exception {
		try (Scenario s = new Scenario()) {
			s.saveObservation(new GoalObservationState(s.plan.goalId(), s.plan.clusterId(), s.plan.observationScope(), 0, 6, 4, 4, Optional.of(id(900))));
			s.reconstruct();
			s.seedReceipt();
			s.addRemotePair();
			s.interruptAfterCleanup = true;
			assertThrows(SimulatedInterruption.class, () -> s.owner.retireReceivedRun(s.receipt));
			assertEquals(6, s.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
			assertEquals(GoalState.RUNNABLE, s.goal().state());
			s.journal = reload(s.journal);
			s.reconstruct();
			assertEquals(GoalState.RUNNABLE, s.goal().state(), "constructing/restoring an owner never credits");
			assertTrue(s.journal.readPrefix("remote_agent_dispatch/", FactoryOrchestrationServer.RemoteAgentRadioDispatch.class, RegistryAccess.EMPTY).isEmpty());
			assertTrue(s.journal.readPrefix("remote_recovery_exchange/", FactoryOrchestrationServer.RemoteRecoveryExchange.class, RegistryAccess.EMPTY).isEmpty());
			assertTrue(s.persistence().output(s.receipt.runId()).isEmpty());
			assertEquals(s.recovery, s.journal.read("recovery/global", RecoveryLedger.State.class, RegistryAccess.EMPTY).orElseThrow());
			long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
			while (s.goal().state() != GoalState.TERMINAL && System.nanoTime() < deadline) {
				s.owner.replayDurableCredits();
				Thread.sleep(5);
			}
			assertEquals(GoalState.TERMINAL, s.goal().state());
		}
	}

	@Test
	void resetRequiredEngineFenceRemainsAuthoritativeDuringOwnerReplayAndStockCredit() {
		try (Scenario s = new Scenario()) {
			var current = s.runtime.snapshot(s.plan.clusterId()).orElseThrow();
			GoalRecord waiting = new GoalRecord(s.plan.goalId(), s.plan.clusterId(), s.goal().submission(), s.plan.deliveryRoute(), new PolicySnapshot(0, 0, false), GoalState.WAITING, WaitReason.RESET_REQUIRED);
			s.runtime.restoreSnapshot(new ClusterEngineState(current.clusterId(), current.epochId(), current.generation(), current.nextSubmissionSequence(), current.nextRouteSelectionSequence(), current.memberCursor(), current.processCursor(), Map.of(waiting.goalId(), waiting), current.submissionSequences(), current.requestedKinds(), current.processes(), current.members(), current.pendingIntents(), current.uncertainIntentIds(), current.goalScanOffset(), current.goalScanCandidate(), current.routeCursor(), current.coordinationAssociations(), current.priorityLeaseAssociations()));
			s.saveObservation(new GoalObservationState(s.plan.goalId(), s.plan.clusterId(), s.plan.observationScope(), 0, 6, 6, 6, Optional.of(id(900))));
			s.reconstruct();
			s.stockCount = 100;
			s.owner.replayDurableCredits();
			s.owner.observeStocks();
			assertEquals(GoalState.WAITING, s.goal().state());
			assertEquals(WaitReason.RESET_REQUIRED, s.goal().waitReason());
		}
	}

	@Test
	void wrongRecoveryFenceAndWrongLineageAgentDoNotAuthorizeCredit() {
		try (Scenario s = new Scenario()) {
			s.seedReceipt();
			RemoteRequestKey wrong = new RemoteRequestKey(s.fixture.key().requestId(), s.receipt.runId(), s.receipt.run().fence() + 1, s.fixture.key().sourceGeneration(), s.receipt.agentResetGeneration());
			var wrongLineage = new RecoveryLedger.LineageHighWatermark(new RecoveryLedger.LineageId(wrong.requestId(), wrong.runId()), wrong, s.plan.clusterId(), s.receipt.agentId(), s.receipt.run().inputRoutes().getFirst(), true);
			s.recovery = new RecoveryLedger.State(Map.of(), Map.of(wrong, new RecoveryLedger.AcknowledgedTombstone(wrong, RemoteAgentRecord.TerminalOutcome.COMPLETED, 4)), Map.of(), Map.of(wrongLineage.id(), wrongLineage));
			assertFalse(s.owner.retireReceivedRun(s.receipt));
			var key = s.fixture.key();
			var lineage = new RecoveryLedger.LineageHighWatermark(new RecoveryLedger.LineageId(key.requestId(), key.runId()), key, s.plan.clusterId(), id(999), s.receipt.run().inputRoutes().getFirst(), true);
			s.recovery = new RecoveryLedger.State(Map.of(), acknowledgedCompletion(s.fixture).acknowledgedTombstones(), Map.of(), Map.of(lineage.id(), lineage));
			assertFalse(s.owner.retireReceivedRun(s.receipt));
			assertEquals(2, s.owner.observation(s.plan.goalId()).orElseThrow().confirmedOutputCount());
		}
	}

	@Test
	void exactKeyedPairAndAllFiveDeliveredRoutesAreRequiredAtTheOwnerBoundary() {
		try (Scenario s = new Scenario()) {
			s.seedReceipt();
			s.addRemotePair();
			var remote = s.remote;
			s.dispatches.clear();
			s.dispatches.put(id(999), remote.dispatch());
			assertFalse(s.owner.retireReceivedRun(s.receipt), "dispatch key/value mismatch");
			s.dispatches.clear();
			s.dispatches.put(remote.dispatch().messageId(), remote.dispatch());
			s.exchanges.clear();
			var wrongKey = new RemoteRequestKey(id(998), s.receipt.runId(), s.fixture.key().fence(), s.fixture.key().sourceGeneration(), s.fixture.key().agentGeneration());
			s.exchanges.put(wrongKey, remote.exchange());
			assertFalse(s.owner.retireReceivedRun(s.receipt), "exchange key/value mismatch");
			s.exchanges.clear();
			s.exchanges.put(s.fixture.key(), remote.exchange());
			s.routeRetirable = false;
			assertFalse(s.owner.retireReceivedRun(s.receipt));
			s.routeRetirable = true;
			var message = remote.exchange().acknowledgementEnvelope();
			s.outboxState = new DurableRadioOutbox.State(Map.of(message.messageId(), new DurableRadioOutbox.Entry(message, List.of(), RadioRouter.RouteStatus.DELIVERED)));
			assertFalse(s.owner.retireReceivedRun(s.receipt), "unretired durable outbox owner");
			s.outboxState = new DurableRadioOutbox.State(Map.of());
			s.exchanges.remove(s.fixture.key());
			assertFalse(s.owner.retireReceivedRun(s.receipt), "half pair");
			s.exchanges.put(s.fixture.key(), remote.exchange());
			assertTrue(s.owner.retireReceivedRun(s.receipt));
			assertTrue(s.activeArtifactKeys().stream().noneMatch(s.savedKeys()::contains));
		}
	}

	private static final class SimulatedInterruption extends RuntimeException {
	}

	private static class Scenario implements AutoCloseable, ProductionGoalSettlement.Evidence, ProductionGoalSettlement.Transport {
		final Fixture fixture = fixture();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		FrozenProductionPlan plan = fixture.afterAdvance();
		final FactoryRuntimeService runtime = new FactoryRuntimeService(new FactoryRuntimeConfig(1, 1000, 4, 32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16));
		ProductionGoalSettlement owner;
		long stockCount = 3;
		boolean loaded = true;
		boolean failWrite;
		Map<String, GoalObservationState> sourceSnapshot;
		boolean changePairOnPreflight;
		boolean interruptAfterCleanup;
		boolean routeRetirable = true;
		DurableRadioOutbox.State outboxState = new DurableRadioOutbox.State(Map.of());
		final Map<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches = new LinkedHashMap<>();
		final Map<RemoteRequestKey, FactoryOrchestrationServer.RemoteRecoveryExchange> exchanges = new LinkedHashMap<>();
		RemoteArtifacts remote;
		final StockObservationService stock = new StockObservationService(() -> true, new StockObservationService.StockReader() {
				public StockObservationService.NetworkSnapshot readNetwork(UUID network, StackKey item) {
					return new StockObservationService.NetworkSnapshot(loaded, stockCount, 99);
			}
				public StockObservationService.AttachedSnapshot readAttached(SpaceAddress address, StackKey item) {
					throw new AssertionError();
			}
		});
		Scenario() {
			GoalObservationState prior = new GoalObservationState(plan.goalId(), plan.clusterId(), plan.observationScope(), 0, 6, 2, 2, Optional.of(id(900)));
			journal.write("goal_observation/" + prior.goalId(), prior, RegistryAccess.EMPTY);
			GoalRecord goal = new GoalRecord(plan.goalId(), plan.clusterId(), new GoalSubmission(plan.goalId(), plan.patternId(), 6, InputMode.EXACT), plan.deliveryRoute(), new PolicySnapshot(0, 0, false), GoalState.RUNNABLE, null);
			ClusterEngineState empty = ClusterEngineState.empty(plan.clusterId(), id(800));
			runtime.start();
			runtime.restoreSnapshot(new ClusterEngineState(plan.clusterId(), id(800), 0, 1, 0, 0, Map.of(plan.goalId(), goal), Map.of(plan.goalId(), 0L), Map.of(plan.goalId(), ProcessKind.GOAL), Map.of(), empty.members(), empty.pendingIntents(), empty.uncertainIntentIds(), 0, null));
			owner = new ProductionGoalSettlement(persistence(), runtime, stock, this, this);
		}
		ProductionGoalSettlement.Persistence persistence() {
			return new ProductionGoalSettlement.Persistence() {
				public Map<String, GoalObservationState> loadObservations() {
					return Scenario.this.loadObservations();
				}
				public Optional<GoalObservationState> observation(UUID id) {
					return Scenario.this.observation(id);
				}
				public void saveObservation(GoalObservationState value) {
					Scenario.this.saveObservation(value);
				}
				public Optional<FrozenProductionPlan> plan(UUID goalId) {
					return journal.read("production_plan/" + goalId, FrozenProductionPlan.class, RegistryAccess.EMPTY);
				}
				public Optional<ProductionOutputContract.Snapshot> output(UUID runId) {
					return journal.read("output_contract/" + runId, ProductionOutputContract.Snapshot.class, RegistryAccess.EMPTY);
				}
				public void commitRetirement(UUID runId, Optional<GoalObservationState> updated, ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement remote) {
					Map<String, Object> writes = new LinkedHashMap<>();
					updated.ifPresent(v -> writes.put("goal_observation/" + v.goalId(), v));
					if (failWrite) writes.put("invalid", new Object());
					journal.writeBatchAndRemoveActiveRunArtifacts(writes, runId, remote.terminalJournalArtifacts(), RegistryAccess.EMPTY);
				}
			};
		}
		public Map<String, GoalObservationState> loadObservations() {
			return sourceSnapshot != null ? sourceSnapshot : journal.readPrefix("goal_observation/", GoalObservationState.class, RegistryAccess.EMPTY);
		}
		public Optional<GoalObservationState> observation(UUID goalId) {
			return journal.read("goal_observation/" + goalId, GoalObservationState.class, RegistryAccess.EMPTY);
		}
		public void saveObservation(GoalObservationState observation) {
			if (failWrite) journal.write("invalid", new Object(), RegistryAccess.EMPTY);
			journal.write("goal_observation/" + observation.goalId(), observation, RegistryAccess.EMPTY);
		}
		public FrozenProductionPlan plan(UUID goalId) {
			return plan;
		}
		ProductionOutputPolicy policy = ProductionGoalSettlementTest.outputPolicy(fixture, 2);
		ProductionOutputContract.Snapshot receipt = receivedOutput(fixture, 2);
		boolean childActive;
		boolean liveArtifacts = true;
		RecoveryLedger.State recovery = acknowledgedCompletion(fixture);
		public ProductionOutputPolicy outputPolicy(UUID runId) {
			return policy;
		}
		public List<ProductionOutputContract.Snapshot> outputReceipts() {
			return liveArtifacts ? List.of(receipt) : List.of();
		}
		public boolean hasLiveChild(UUID runId) {
			return childActive;
		}
		public RecoveryLedger.State recovery() {
			return recovery;
		}
		public void removeRetiredArtifacts(UUID runId) {
			liveArtifacts = false;
			if (interruptAfterCleanup) throw new SimulatedInterruption();
		}
		public Map<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches() {
			return Map.copyOf(dispatches);
		}
		public Map<RemoteRequestKey, FactoryOrchestrationServer.RemoteRecoveryExchange> exchanges() {
			return Map.copyOf(exchanges);
		}
		public DurableRadioOutbox.State outbox() {
			return outboxState;
		}
		public boolean canRetireDeliveredRoute(UUID id) {
			return routeRetirable;
		}
		public boolean matchesCurrentPair(ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement pair) {
			if (changePairOnPreflight) exchanges.put(fixture.key(), withPhase(remote.exchange(), FactoryOrchestrationServer.RemoteRecoveryPhase.STATUS_CONFIRMED));
			return pair.dispatch().isEmpty() || pair.dispatch().orElseThrow().equals(dispatches.get(pair.dispatch().orElseThrow().messageId())) && pair.recoveryExchange().orElseThrow().equals(exchanges.get(pair.recoveryExchange().orElseThrow().key()));
		}
		public void removeExactPair(ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement pair) {
			if (pair.dispatch().isEmpty()) return;
			assertTrue(dispatches.remove(pair.dispatch().orElseThrow().messageId(), pair.dispatch().orElseThrow()));
			assertTrue(exchanges.remove(pair.recoveryExchange().orElseThrow().key(), pair.recoveryExchange().orElseThrow()));
		}
		void seedReceipt() {
			ProductionAdmissionLedger.State placeholder = new ProductionAdmissionLedger.State(Map.of(), Map.of(), 0, Optional.empty());
			for (String key : activeArtifactKeys()) if (!key.startsWith("output_contract/")) journal.write(key, placeholder, RegistryAccess.EMPTY);
			journal.write("input_commit/irreversible", placeholder, RegistryAccess.EMPTY);
			journal.write("output_commit/irreversible", placeholder, RegistryAccess.EMPTY);
			journal.write("production_plan/" + plan.goalId(), plan, RegistryAccess.EMPTY);
			journal.write("output_contract/" + receipt.runId(), receipt, RegistryAccess.EMPTY);
		}
		Set<String> activeArtifactKeys() {
			String id = receipt.runId().toString();
			return Set.of("input_contract/" + id, "output_policy/" + id, "output_contract/" + id, "output_probe/" + id, "signal_probe/" + id, "output_ledger/" + id, "safe_create_retry/" + id);
		}
		Set<String> savedKeys() {
			var entries = journal.save(new CompoundTag(), RegistryAccess.EMPTY).getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
			Set<String> keys = new java.util.HashSet<>();
			for (int i = 0; i < entries.size(); i++) keys.add(entries.getCompound(i).getString("Key"));
			return Set.copyOf(keys);
		}
		void reconstruct() {
			owner = new ProductionGoalSettlement(persistence(), runtime, stock, this, this);
		}
		GoalRecord goal() {
			return runtime.snapshot(plan.clusterId()).orElseThrow().goals().get(plan.goalId());
		}
		void addRemotePair() {
			remote = remoteArtifacts(fixture.key(), plan.goalId(), plan.clusterId(), receipt.agentId(), FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, 1000);
			dispatches.put(remote.dispatch().messageId(), remote.dispatch());
			exchanges.put(fixture.key(), remote.exchange());
			journal.write("remote_agent_dispatch/" + remote.dispatch().messageId(), remote.dispatch(), RegistryAccess.EMPTY);
			journal.write("remote_recovery_exchange/" + fixture.key().requestId(), remote.exchange(), RegistryAccess.EMPTY);
			journal.write("recovery/global", recovery, RegistryAccess.EMPTY);
		}
		public void close() {
			runtime.close();
		}
	}

	private static RecoveryLedger.State acknowledgedCompletion(Fixture fixture) {
		return acknowledgedTerminal(fixture, RemoteAgentRecord.TerminalOutcome.COMPLETED);
	}

	private static RecoveryLedger.State acknowledgedTerminal(Fixture fixture,
		RemoteAgentRecord.TerminalOutcome outcome) {
		RemoteRequestKey key = fixture.key();
		RecoveryLedger.LineageId lineageId = new RecoveryLedger.LineageId(key.requestId(),
			key.runId());
		RecoveryLedger.AcknowledgedTombstone tombstone = new RecoveryLedger.AcknowledgedTombstone(
			key, outcome, 4);
		RecoveryLedger.LineageHighWatermark lineage = new RecoveryLedger.LineageHighWatermark(
			lineageId, key, fixture.beforeAdvance().clusterId(), agentId(),
			fixture.received().inputRoutes().getFirst(), true);
		return new RecoveryLedger.State(Map.of(), Map.of(key, tombstone), Map.of(),
			Map.of(lineageId, lineage));
	}

	private static ProductionOutputPolicy outputPolicy(Fixture fixture, long target) {
		return new ProductionOutputPolicy(0, AgentEndpointState.OutputMode.DIRECT_REQUIRED,
			AgentCompletionMode.TARGET, Optional.empty(), fixture.beforeAdvance().deliveryRoute(),
			Set.of(fixture.beforeAdvance().deliveryRoute()), Map.of(fixture.beforeAdvance().output(),
			new ProductionOutputPolicy.RequiredOutput(fixture.beforeAdvance().deliveryRoute(), target,
			new ProductionOutputPolicy.OutputReceiver.Bare(
			new ProductionOutputPolicy.BareReceiptProof.Network(
			(StockObservationScope.NetworkSummary) fixture.beforeAdvance()
			.observationScope())))), Set.of(),
			Optional.of(fixture.beforeAdvance().output()), false, false, 1, 1, 1);
	}

	private static ProductionOutputPolicy noOutputPolicy(Fixture fixture) {
		return new ProductionOutputPolicy(0, AgentEndpointState.OutputMode.WAREHOUSE_ALL,
			AgentCompletionMode.QUIET, Optional.empty(), fixture.beforeAdvance().deliveryRoute(),
			Set.of(fixture.beforeAdvance().deliveryRoute()), Map.of(), Set.of(), Optional.empty(),
			true, false, 1, 1, 1);
	}

	private static ProductionOutputContract.Snapshot receivedOutput(Fixture fixture, long primary) {
		return outputSnapshot(fixture, primary, ProductionOutputContract.Milestone.OUTPUT_RECEIVED);
	}

	private static ProductionOutputContract.Snapshot outputSnapshot(Fixture fixture, long primary,
		ProductionOutputContract.Milestone milestone) {
		Map<StackKey, Long> credited = primary == 0 ? Map.of()
		: Map.of(fixture.beforeAdvance().output(), primary);
		return new ProductionOutputContract.Snapshot(agentId(), fixture.key().agentGeneration(), id(500),
			fixture.received(), 0, milestone, Optional.empty(), true, false, primary > 0,
			0, 0, 0, credited, Map.of(), List.of(), Map.of(), Map.of(), Set.of(), Set.of(),
			Set.of(), List.of(), Optional.empty(), 0, 0, Optional.empty(), Optional.empty(),
			Optional.empty(), true);
	}

	private static UUID agentId() {
		return id(302);
	}

	private static Fixture fixture() {
		UUID cluster = id(1), goal = id(2), pattern = id(3), network = id(4);
		CreateRouteKey input = new CreateRouteKey(network, "input");
		CreateRouteKey delivery = new CreateRouteKey(network, "warehouse");
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT));
		ProductionRun first = new ProductionRun(id(10), goal, pattern, 5, 1, InputMode.EXACT,
			List.of(input), delivery, 0, Map.of(ingredient, 1L));
		ProductionRun next = new ProductionRun(id(11), goal, pattern, 6, 1, InputMode.EXACT,
			List.of(input), delivery, 0, Map.of(ingredient, 1L));
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(
			new RouteSelectionScope(id(12), id(13), output, 1, 1, id(14)), id(15), pattern,
			pattern.toString(), RouteCandidate.ProviderKind.PATTERN, 0,
			RouteCandidate.Availability.AVAILABLE);
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 6);
		List<FrozenProductionPlan.FrozenStage> stages = List.of(
			new FrozenProductionPlan.FrozenStage(0, input, Map.of(ingredient, 1L),
			List.of(new OrderedIngredient(ingredient, 1L))));
		FrozenProductionPlan before = new FrozenProductionPlan(goal, cluster, provider,
			PatternKind.STANDARD, output, 2, delivery, stages,
			new ProductionRun.RunSequence(first, 3), scope, 0, 6, false, false,
			Optional.empty());
		FrozenProductionPlan after = new FrozenProductionPlan(goal, cluster, provider,
			PatternKind.STANDARD, output, 2, delivery, stages,
			new ProductionRun.RunSequence(next, 2), scope, 0, 6, false, false,
			Optional.empty());
		FrozenProductionPlan completed = new FrozenProductionPlan(goal, cluster, provider,
			PatternKind.STANDARD, output, 2, delivery, stages,
			ProductionRun.RunSequence.completed(), scope, 0, 6, false, false,
			Optional.empty());
		RemoteRequestKey key = new RemoteRequestKey(id(20), first.runId(), first.fence(), 8, 9);
		return new Fixture(first, next, key, before, after, completed);
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
	private record Fixture(ProductionRun received, ProductionRun next, RemoteRequestKey key,
		FrozenProductionPlan beforeAdvance, FrozenProductionPlan afterAdvance,
		FrozenProductionPlan completed) {
	}

	private static RemoteArtifacts remoteArtifacts(RemoteRequestKey key, UUID goalId,
		UUID sourceClusterId, UUID agentId,
		FactoryOrchestrationServer.RemoteRecoveryPhase phase, long idBase) {
		UUID targetClusterId = id(idBase + 1);
		RadioEndpoint source = endpoint(sourceClusterId, id(idBase + 2), 3, 0);
		RadioEndpoint target = endpoint(targetClusterId, id(idBase + 3), 4, 8);
		CreateRouteKey inputRoute = new CreateRouteKey(id(idBase + 4), "agent-input");
		ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(key.requestId(),
			sourceClusterId, key.fence(), new ProductionEffect.TargetFence(id(idBase + 6),
			agentId, 0, key.fence()), id(idBase + 7), key.runId(), inputRoute,
			List.of(new OrderedIngredient(
			new StackKey(new ItemStack(Items.IRON_INGOT)), 1)));
		UUID dispatchMessageId = id(idBase + 8);
		RadioEnvelope dispatchEnvelope = RadioEnvelope.create(dispatchMessageId, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("agent-dispatch:" + intent.intentId()));
		FactoryOrchestrationServer.RemoteAgentRadioDispatch dispatch =
		new FactoryOrchestrationServer.RemoteAgentRadioDispatch(dispatchMessageId, intent,
			agentId, key.agentGeneration(), CommunicationFrequency.of("17"), dispatchEnvelope, List.of(), true);
		RadioEnvelope offer = dataEnvelope(id(idBase + 9), source, target, "offer");
		RadioEnvelope terminal = dataEnvelope(id(idBase + 10), target, source, "terminal");
		RadioEnvelope query = dataEnvelope(id(idBase + 11), source, target, "query");
		RadioEnvelope acknowledgement = dataEnvelope(id(idBase + 12), source, target, "ack");
		FactoryOrchestrationServer.RemoteRecoveryExchange exchange =
		new FactoryOrchestrationServer.RemoteRecoveryExchange(key, goalId, 4, offer, List.of(),
			terminal, List.of(), query, List.of(), acknowledgement, List.of(), phase);
		return new RemoteArtifacts(dispatch, exchange, inputRoute);
	}

	private static RadioEndpoint endpoint(UUID clusterId, UUID memberId, long generation, int x) {
		return new RadioEndpoint(clusterId, memberId,
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 64, 0)), generation, CommunicationFrequency.of("17"), 1,
			Set.of());
	}

	private static RadioEnvelope dataEnvelope(UUID messageId, RadioEndpoint source,
		RadioEndpoint target, String value) {
		return RadioEnvelope.create(messageId, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data(value));
	}

	private static FactoryOrchestrationSavedData reload(FactoryOrchestrationSavedData journal) {
		try {
			CompoundTag root = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
			Method load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load",
				CompoundTag.class, HolderLookup.Provider.class);
			load.setAccessible(true);
			return (FactoryOrchestrationSavedData) load.invoke(null, root, RegistryAccess.EMPTY);
		}
		catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}
	private record RemoteArtifacts(
		FactoryOrchestrationServer.RemoteAgentRadioDispatch dispatch,
		FactoryOrchestrationServer.RemoteRecoveryExchange exchange,
		CreateRouteKey inputRoute) {
	}

	private static FactoryOrchestrationServer.RemoteRecoveryExchange withPhase(FactoryOrchestrationServer.RemoteRecoveryExchange value, FactoryOrchestrationServer.RemoteRecoveryPhase phase) {
		return new FactoryOrchestrationServer.RemoteRecoveryExchange(value.key(), value.goalId(), value.terminalRevision(), value.offerEnvelope(), value.offerRelays(), value.terminalEnvelope(), value.terminalRelays(), value.statusQueryEnvelope(), value.statusQueryRelays(), value.acknowledgementEnvelope(), value.acknowledgementRelays(), phase);
	}
}
