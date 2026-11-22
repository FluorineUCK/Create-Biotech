package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
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
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal.ProductionGoalSettlement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ProductionRunRemoteArtifactRetirementTest {
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
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void normalCompletedRemoteRunRetirementRemovesItsTransportFromMemoryAndReload() {
		UUID runId = id(10), goalId = id(11), clusterId = id(12), agentId = id(13);
		RemoteRequestKey key = new RemoteRequestKey(id(14), runId, 5, 8, 9);
		RemoteArtifacts artifacts = remoteArtifacts(key, goalId, clusterId, agentId,
			FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, 100);
		FactoryOrchestrationSavedData journal = journalWithRunArtifacts(runId);
		GoalObservationState credited = goalObservation(
			goalId, clusterId, runId);
		RecoveryLedger.State recoveryEvidence = acknowledgedCompletion(
			key, clusterId, agentId, artifacts.inputRoute());
		String goalKey = "goal_observation/" + goalId;
		journal.write("remote_agent_dispatch/" + artifacts.dispatch().messageId(),
			artifacts.dispatch(), RegistryAccess.EMPTY);
		journal.write("remote_recovery_exchange/" + key.requestId(), artifacts.exchange(),
			RegistryAccess.EMPTY);
		journal.write("recovery/global", recoveryEvidence, RegistryAccess.EMPTY);
		LinkedHashMap<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches =
			new LinkedHashMap<>(Map.of(artifacts.dispatch().messageId(), artifacts.dispatch()));
		LinkedHashMap<RemoteRequestKey, FactoryOrchestrationServer.RemoteRecoveryExchange> exchanges =
			new LinkedHashMap<>(Map.of(key, artifacts.exchange()));

		ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement retirement =
			ProductionGoalSettlement.completedRunRemoteArtifactRetirement(runId, goalId, key,
				recoveryEvidence, dispatches, exchanges,
				new DurableRadioOutbox.State(Map.of()), ignored -> true).orElseThrow();
		ProductionGoalSettlement.publishCompletedRunRemoteArtifactRetirement(
			terminalArtifacts -> journal.writeBatchAndRemoveActiveRunArtifacts(
				Map.of(goalKey, credited), runId, terminalArtifacts.terminalJournalArtifacts(), RegistryAccess.EMPTY),
			retirement, transport(dispatches, exchanges));

		assertTrue(dispatches.isEmpty(), "a completed dispatch must leave the per-tick map");
		assertTrue(exchanges.isEmpty(), "a reconciled exchange must leave the per-tick map");
		FactoryOrchestrationSavedData reloaded = reload(journal);
		assertEquals(credited, reloaded.read(goalKey,
			GoalObservationState.class, RegistryAccess.EMPTY)
			.orElseThrow());
		assertEquals(recoveryEvidence, reloaded.read("recovery/global", RecoveryLedger.State.class,
			RegistryAccess.EMPTY).orElseThrow(),
			"bounded terminal RecoveryLedger evidence remains the anti-resurrection authority");
		assertTrue(reloaded.readPrefix("remote_agent_dispatch/",
			FactoryOrchestrationServer.RemoteAgentRadioDispatch.class,
			RegistryAccess.EMPTY).isEmpty());
		assertTrue(reloaded.readPrefix("remote_recovery_exchange/",
			FactoryOrchestrationServer.RemoteRecoveryExchange.class,
			RegistryAccess.EMPTY).isEmpty());
		for (String artifactKey : FactoryOrchestrationSavedData.activeRunArtifactKeys(runId))
			assertFalse(reloaded.read(artifactKey, ProductionAdmissionLedger.State.class,
				RegistryAccess.EMPTY).isPresent(), artifactKey);
	}

	@Test
	void remoteRunRetirementWaitsForReconciliationAndDurableOutboxRetirement() {
		UUID runId = id(20), goalId = id(21), clusterId = id(22), agentId = id(23);
		RemoteRequestKey key = new RemoteRequestKey(id(24), runId, 5, 8, 9);
		RemoteArtifacts notReconciled = remoteArtifacts(key, goalId, clusterId, agentId,
			FactoryOrchestrationServer.RemoteRecoveryPhase.STATUS_CONFIRMED, 200);
		RecoveryLedger.State recoveryEvidence = acknowledgedCompletion(
			key, clusterId, agentId, notReconciled.inputRoute());
		Map<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches = Map.of(
			notReconciled.dispatch().messageId(), notReconciled.dispatch());

		assertTrue(ProductionGoalSettlement.completedRunRemoteArtifactRetirement(runId,
			goalId, key, recoveryEvidence, dispatches, Map.of(key, notReconciled.exchange()),
			new DurableRadioOutbox.State(Map.of()), ignored -> true).isEmpty());

		RemoteArtifacts reconciled = remoteArtifacts(key, goalId, clusterId, agentId,
			FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, 200);
		DurableRadioOutbox.Entry deliveredButUnretired = new DurableRadioOutbox.Entry(
			reconciled.exchange().acknowledgementEnvelope(), List.of(),
			RadioRouter.RouteStatus.DELIVERED);
		DurableRadioOutbox.State pendingOutbox = new DurableRadioOutbox.State(Map.of(
			deliveredButUnretired.envelope().messageId(), deliveredButUnretired));
		assertTrue(ProductionGoalSettlement.completedRunRemoteArtifactRetirement(runId,
			goalId, key, recoveryEvidence,
			Map.of(reconciled.dispatch().messageId(), reconciled.dispatch()),
			Map.of(key, reconciled.exchange()), pendingOutbox, ignored -> true).isEmpty(),
			"a durable outbox replay owner cannot be orphaned by Run retirement");
		assertTrue(ProductionGoalSettlement.completedRunRemoteArtifactRetirement(runId,
			goalId, key, new RecoveryLedger.State(Map.of(), Map.of(), Map.of(), Map.of()),
			Map.of(reconciled.dispatch().messageId(), reconciled.dispatch()),
			Map.of(key, reconciled.exchange()), new DurableRadioOutbox.State(Map.of()),
			ignored -> true).isEmpty(),
			"transport records cannot replace the retained RecoveryLedger tombstone");
		assertTrue(ProductionGoalSettlement.completedRunRemoteArtifactRetirement(runId,
			goalId, key, recoveryEvidence,
			Map.of(reconciled.dispatch().messageId(), reconciled.dispatch()),
			Map.of(key, reconciled.exchange()), new DurableRadioOutbox.State(Map.of()),
			ignored -> false).isEmpty(),
			"a non-final router route must keep the durable terminal transport owner live");
	}

	@Test
	void compactedRecoveryExchangeStillReportsItsDurableReconciledFence() {
		UUID runId = id(30), goalId = id(31), clusterId = id(32), agentId = id(33);
		RemoteRequestKey key = new RemoteRequestKey(id(34), runId, 5, 8, 9);
		RemoteArtifacts artifacts = remoteArtifacts(key, goalId, clusterId, agentId,
			FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, 300);
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		String goalKey = "goal_observation/" + goalId;
		journal.write(goalKey, goalObservation(goalId, clusterId, runId), RegistryAccess.EMPTY);
		journal.write("recovery/global", acknowledgedCompletion(key, clusterId, agentId,
			artifacts.inputRoute()), RegistryAccess.EMPTY);
		FactoryOrchestrationSavedData reloaded = reload(journal);

		assertEquals(Optional.of(FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED),
			ProductionGoalSettlement.visibleRemoteRecoveryPhase(goalId, Map.of(),
				reloaded.read(goalKey, GoalObservationState.class,
					RegistryAccess.EMPTY).orElseThrow(),
				reloaded.read("recovery/global", RecoveryLedger.State.class,
					RegistryAccess.EMPTY).orElseThrow()),
			"transport compaction must not erase the durable reconciliation status");
	}

	@Test
	void durablePublicationFailureLeavesBothTerminalTransportMapsUnchanged() {
		UUID runId = id(40), goalId = id(41), clusterId = id(42), agentId = id(43);
		RemoteRequestKey key = new RemoteRequestKey(id(44), runId, 5, 8, 9);
		RemoteArtifacts artifacts = remoteArtifacts(key, goalId, clusterId, agentId,
			FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, 400);
		RecoveryLedger.State recoveryEvidence = acknowledgedCompletion(
			key, clusterId, agentId, artifacts.inputRoute());
		LinkedHashMap<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches =
			new LinkedHashMap<>(Map.of(artifacts.dispatch().messageId(), artifacts.dispatch()));
		LinkedHashMap<RemoteRequestKey, FactoryOrchestrationServer.RemoteRecoveryExchange> exchanges =
			new LinkedHashMap<>(Map.of(key, artifacts.exchange()));
		ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement retirement =
			ProductionGoalSettlement.completedRunRemoteArtifactRetirement(runId, goalId, key,
				recoveryEvidence, dispatches, exchanges,
				new DurableRadioOutbox.State(Map.of()), ignored -> true).orElseThrow();

		assertThrows(SimulatedCrash.class, () ->
			ProductionGoalSettlement.publishCompletedRunRemoteArtifactRetirement(
				ignored -> { throw new SimulatedCrash(); }, retirement, transport(dispatches, exchanges)));

		assertEquals(Map.of(artifacts.dispatch().messageId(), artifacts.dispatch()), dispatches);
		assertEquals(Map.of(key, artifacts.exchange()), exchanges);
	}

	@Test
	void changedTerminalRemoteRecordCannotPartiallyPublishGoalOrActiveArtifactRemoval() {
		UUID runId = id(50), goalId = id(51), clusterId = id(52), agentId = id(53);
		RemoteRequestKey key = new RemoteRequestKey(id(54), runId, 5, 8, 9);
		RemoteArtifacts artifacts = remoteArtifacts(key, goalId, clusterId, agentId,
			FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, 500);
		FactoryOrchestrationSavedData journal = journalWithRunArtifacts(runId);
		GoalObservationState credited = goalObservation(
			goalId, clusterId, runId);
		GoalObservationState prior =
			new GoalObservationState(goalId, clusterId,
				credited.scope(), 0, 6, 0, 0, Optional.empty());
		String goalKey = "goal_observation/" + goalId;
		String dispatchKey = "remote_agent_dispatch/" + artifacts.dispatch().messageId();
		String exchangeKey = "remote_recovery_exchange/" + key.requestId();
		journal.write(goalKey, prior, RegistryAccess.EMPTY);
		journal.write(dispatchKey, artifacts.dispatch(), RegistryAccess.EMPTY);
		journal.write(exchangeKey, artifacts.exchange(), RegistryAccess.EMPTY);
		Map<String, ?> staleExpectedPair = Map.of(dispatchKey, artifacts.dispatch(), exchangeKey,
			artifacts.exchange().withPhase(
				FactoryOrchestrationServer.RemoteRecoveryPhase.STATUS_CONFIRMED));

		assertThrows(IllegalStateException.class, () ->
			journal.writeBatchAndRemoveActiveRunArtifacts(Map.of(goalKey, credited), runId,
				staleExpectedPair, RegistryAccess.EMPTY));

		assertEquals(prior, journal.read(goalKey,
			GoalObservationState.class, RegistryAccess.EMPTY)
			.orElseThrow());
		assertEquals(artifacts.dispatch(), journal.read(dispatchKey,
			FactoryOrchestrationServer.RemoteAgentRadioDispatch.class,
			RegistryAccess.EMPTY).orElseThrow());
		assertEquals(artifacts.exchange(), journal.read(exchangeKey,
			FactoryOrchestrationServer.RemoteRecoveryExchange.class,
			RegistryAccess.EMPTY).orElseThrow());
		for (String artifactKey : FactoryOrchestrationSavedData.activeRunArtifactKeys(runId))
			assertTrue(journal.read(artifactKey, ProductionAdmissionLedger.State.class,
				RegistryAccess.EMPTY).isPresent(), artifactKey);
	}


 private static ProductionGoalSettlement.Transport transport(
  Map<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches,
  Map<RemoteRequestKey, FactoryOrchestrationServer.RemoteRecoveryExchange> exchanges) {
  return new ProductionGoalSettlement.Transport() {
   public Map<UUID, FactoryOrchestrationServer.RemoteAgentRadioDispatch> dispatches() { return Map.copyOf(dispatches); }
   public Map<RemoteRequestKey, FactoryOrchestrationServer.RemoteRecoveryExchange> exchanges() { return Map.copyOf(exchanges); }
   public DurableRadioOutbox.State outbox() { return new DurableRadioOutbox.State(Map.of()); }
   public boolean canRetireDeliveredRoute(UUID id) { return true; }
   public boolean matchesCurrentPair(ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement pair) {
    return pair.dispatch().isEmpty() || pair.dispatch().orElseThrow().equals(dispatches.get(pair.dispatch().orElseThrow().messageId())) && pair.recoveryExchange().orElseThrow().equals(exchanges.get(pair.recoveryExchange().orElseThrow().key()));
   }
   public void removeExactPair(ProductionGoalSettlement.CompletedRunRemoteArtifactRetirement pair) {
    if (pair.dispatch().isEmpty()) return;
    if (!dispatches.remove(pair.dispatch().orElseThrow().messageId(), pair.dispatch().orElseThrow()) || !exchanges.remove(pair.recoveryExchange().orElseThrow().key(), pair.recoveryExchange().orElseThrow())) throw new IllegalStateException("pair changed");
   }
  };
 }
	private static FactoryOrchestrationSavedData journalWithRunArtifacts(UUID runId) {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		ProductionAdmissionLedger.State value = new ProductionAdmissionLedger.State(Map.of(),
			Map.of(), 0, Optional.empty());
		for (String key : FactoryOrchestrationSavedData.activeRunArtifactKeys(runId))
			journal.write(key, value, RegistryAccess.EMPTY);
		return journal;
	}

	private static GoalObservationState goalObservation(UUID goalId,
		UUID clusterId, UUID runId) {
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT));
		return new GoalObservationState(goalId, clusterId,
			new StockObservationScope.NetworkSummary(id(30), output, 6), 0, 6, 2, 2,
			Optional.of(runId));
	}

	private static RecoveryLedger.State acknowledgedCompletion(RemoteRequestKey key,
		UUID sourceClusterId, UUID agentId, CreateRouteKey targetRoute) {
		RecoveryLedger.LineageId lineageId = new RecoveryLedger.LineageId(key.requestId(),
			key.runId());
		RecoveryLedger.AcknowledgedTombstone tombstone = new RecoveryLedger.AcknowledgedTombstone(
			key, RemoteAgentRecord.TerminalOutcome.COMPLETED, 4);
		RecoveryLedger.LineageHighWatermark lineage = new RecoveryLedger.LineageHighWatermark(
			lineageId, key, sourceClusterId, agentId, targetRoute, true);
		return new RecoveryLedger.State(Map.of(), Map.of(key, tombstone), Map.of(),
			Map.of(lineageId, lineage));
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
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private record RemoteArtifacts(
		FactoryOrchestrationServer.RemoteAgentRadioDispatch dispatch,
		FactoryOrchestrationServer.RemoteRecoveryExchange exchange,
		CreateRouteKey inputRoute) {}

	private static final class SimulatedCrash extends RuntimeException {}
}
