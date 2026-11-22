package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffectPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionInputDispatch;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionSafeRetryRecovery;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.SafeCreateRetryProof;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionSafeRetryStore;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.DispatchManifest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanRepository;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch.ProductionPlanDispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionPublication;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissions;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.PendingProductionAdmission;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentProcess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentPendingRunQueue;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentSignalSelector;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionRecipeCatalog;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryRegistration;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.AgentProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ProductionAgentRuntimeAssemblyTest {
	@BeforeAll
	static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
	void remoteComputationCapacityAndRouteLossRemainDifferentTypedWaits() {
		assertEquals(WaitReason.ROUTE,
			RemoteExpansionProtocol.remoteComputationBeginWaitReason(
				RemoteExpansionProtocol.RemoteComputationBeginStatus.NO_ROUTE));
		assertEquals(WaitReason.RECOVERY_LEDGER_FULL,
			RemoteExpansionProtocol.remoteComputationBeginWaitReason(
				RemoteExpansionProtocol.RemoteComputationBeginStatus.RECOVERY_LEDGER_FULL));
		assertThrows(IllegalArgumentException.class,
			() -> RemoteExpansionProtocol.remoteComputationBeginWaitReason(
				RemoteExpansionProtocol.RemoteComputationBeginStatus.STARTED));
	}

	@Test
	void everyRecoveryOfferDispositionHasAnExactProductionOutcome() {
		assertEquals(FactoryOrchestrationServer.RecoveryOfferAction.PROCEED,
			FactoryOrchestrationServer.recoveryOfferAction(
				RecoveryLedger.OfferDisposition.ACCEPTED));
		assertEquals(FactoryOrchestrationServer.RecoveryOfferAction.PROCEED,
			FactoryOrchestrationServer.recoveryOfferAction(
				RecoveryLedger.OfferDisposition.DUPLICATE));
		assertEquals(FactoryOrchestrationServer.RecoveryOfferAction.WAIT_RECOVERY_LEDGER_FULL,
			FactoryOrchestrationServer.recoveryOfferAction(
				RecoveryLedger.OfferDisposition.RECOVERY_LEDGER_FULL));
		assertEquals(FactoryOrchestrationServer.RecoveryOfferAction.WAIT_PROCESS,
			FactoryOrchestrationServer.recoveryOfferAction(
				RecoveryLedger.OfferDisposition.QUEUE_REJECTED));
		for (RecoveryLedger.OfferDisposition remote : List.of(
			RecoveryLedger.OfferDisposition.ACKNOWLEDGED_DUPLICATE,
			RecoveryLedger.OfferDisposition.REMOTE_RECOVERY))
			assertEquals(FactoryOrchestrationServer.RecoveryOfferAction.WAIT_REMOTE_RECOVERY,
				FactoryOrchestrationServer.recoveryOfferAction(remote));
		for (RecoveryLedger.OfferDisposition staleOrInvalid : List.of(
			RecoveryLedger.OfferDisposition.STALE_FENCE,
			RecoveryLedger.OfferDisposition.STALE_SOURCE_GENERATION,
			RecoveryLedger.OfferDisposition.STALE_AGENT_GENERATION,
			RecoveryLedger.OfferDisposition.POST_INTENT_RETRY_REJECTED,
			RecoveryLedger.OfferDisposition.LINEAGE_MUTATION_REJECTED))
			assertEquals(FactoryOrchestrationServer.RecoveryOfferAction.FAIL_RESET_REQUIRED,
				FactoryOrchestrationServer.recoveryOfferAction(staleOrInvalid));
		assertEquals(WaitReason.ROUTE,
			FactoryOrchestrationServer.recoveryOfferWaitReason(
				FactoryOrchestrationServer.RecoveryOfferAction.WAIT_ROUTE));
	}

	@Test
	void nativeGaugeCandidatesRequireBareRouteAuthorityAndUseTheirOwnPersistedPriority() {
		UUID cluster = id(970), network = id(971), library = id(972), pattern = id(973);
		CreateRouteKey route = new CreateRouteKey(network, "claimed-input");
		RouteSelectionScope scope = new RouteSelectionScope(id(974), id(975),
			new StackKey(new ItemStack(Items.GOLD_INGOT)), 0, 0, id(976));
		var physicalGauge = new FactoryOrchestrationServer.NativeGaugeProvider(
			"factory-gauge/other-position/0", 37, RouteCandidate.Availability.AVAILABLE,
			false);
		var agentOccupiedGauge = new FactoryOrchestrationServer.NativeGaugeProvider(
			"factory-gauge/agent-endpoint/0", 99, RouteCandidate.Availability.AVAILABLE,
			true);
		AgentAddressRegistration registration = new AgentAddressRegistration(route, id(977), cluster,
			4, CommunicationFrequency.of("0"), AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);

		assertEquals(1, FactoryOrchestrationServer.collectLegalNativeGaugeProviders(scope, library,
			pattern, new AgentResolution.Unique(route, registration), List.of(physicalGauge)).size(),
			"an independent Gauge stays directly interactive alongside an Agent route");
		assertEquals(1, FactoryOrchestrationServer.collectLegalNativeGaugeProviders(scope, library,
			pattern, new AgentResolution.KnownUnavailable(route, List.of(), 4),
			List.of(physicalGauge)).size(),
			"an unavailable Agent does not remove an independent Gauge candidate");
		assertTrue(FactoryOrchestrationServer.collectLegalNativeGaugeProviders(scope, library,
			pattern, new AgentResolution.NeverRegistered(route), List.of(agentOccupiedGauge)).isEmpty(),
			"an endpoint under any physical Agent contract cannot execute as a bare Gauge");
		ProductionRouteTable.ProviderSource admitted = FactoryOrchestrationServer
			.collectLegalNativeGaugeProviders(scope, library, pattern,
				new AgentResolution.NeverRegistered(route), List.of(physicalGauge)).getFirst();
		assertEquals(37, admitted.effectivePriority(),
			"native Gauge priority must not be copied from its Pattern");
		assertEquals(RouteCandidate.ProviderKind.FACTORY_GAUGE, admitted.providerKind());
	}

	@Test
	void durableProductionSignalProbePublishesOneExactRunRisingEdgeOnly() {
		AgentSignalSelector selector = new AgentSignalSelector(new SpaceAddress(Level.OVERWORLD,
			null, new BlockPos(4, 5, 6)), Direction.WEST, 9);
		ProductionRun run = new ProductionRun(id(700), id(701), id(702), 7, 1,
			InputMode.EXACT, List.of(new CreateRouteKey(id(703), "input")),
			new CreateRouteKey(id(703), "warehouse"), 3,
			Map.of(new StackKey(new net.minecraft.world.item.ItemStack(
				net.minecraft.world.item.Items.IRON_INGOT)), 1L));
		FactoryOrchestrationServer.SignalProbeState initial =
			FactoryOrchestrationServer.SignalProbeState.initial(run, selector);

		FactoryOrchestrationServer.SignalSample initialHigh = initial.sample(run, selector, true, 15);
		assertFalse(initialHigh.publish(), "a pre-existing high level is not a new Run edge");
		FactoryOrchestrationServer.SignalSample low = initialHigh.state().sample(run, selector,
			true, 0);
		var lowReloaded = CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(low.state(),
			RegistryAccess.EMPTY), RegistryAccess.EMPTY,
			FactoryOrchestrationServer.SignalProbeState.class).orElseThrow();
		FactoryOrchestrationServer.SignalSample edge = lowReloaded.sample(run, selector, true, 9);
		assertTrue(edge.publish());
		FactoryOrchestrationServer.SignalProbeState published = edge.state().markPublished();
		assertFalse(published.sample(run, selector, true, 15).publish(),
			"replayed high samples cannot publish twice");
		assertEquals(published, published.sample(run, selector, false, 0).state(),
			"unloaded selectors do not synthesize a low edge");
		ProductionRun newer = new ProductionRun(id(704), run.goalId(), run.patternId(), 8, 1,
			run.inputMode(), run.inputRoutes(), run.returnWarehouseRoute(),
			run.outputPolicyGeneration(), run.manifest());
		assertEquals(published, published.sample(newer, selector, true, 15).state(),
			"a stale Run probe cannot address a newer Run fence");

		var encoded = CurrentSchemaStateNbt.save(published, RegistryAccess.EMPTY);
		assertEquals(published, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FactoryOrchestrationServer.SignalProbeState.class).orElseThrow());
	}

	@Test
	void agentResidencyOccupiesAndReleasesOneRealAlchemistSlotWithoutCreatingAGoal() {
		UUID cluster = id(1), epoch = id(2), computer = id(3), agent = id(4);
		ProductionResourceProfile alchemist = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 2, 0)
			.orElseThrow();
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState formed = new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			List.of(new ClusterEngineState.MemberSnapshot(computer, alchemist, true, 0, 0)),
			empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(formed);
			AgentProcess.ResidentSlot slot = runtime.acquireAgentResidentSlot(agent, cluster)
				.orElseThrow();
			assertEquals(computer, slot.computerId());
			assertTrue(runtime.agentResidentSlotValid(agent, cluster, slot));
			ClusterEngineState reserved = runtime.snapshot(cluster).orElseThrow();
			assertTrue(reserved.goals().isEmpty(), "an Agent process never owns a synthetic Goal");
			assertEquals(1, reserved.processes().size());
			assertTrue(runtime.releaseAgentResidentSlot(agent, cluster, slot));
			assertFalse(runtime.agentResidentSlotValid(agent, cluster, slot));
		}
	}

	@Test
	void onlyActualStockAtTheAbsoluteTargetCreditsAndCompletesTheOwningGoal() throws Exception {
		UUID cluster = id(11), epoch = id(12);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
			UUID goal = runtime.submitPanelGoal(cluster, epoch, 0, id(13),
				new CreateRouteKey(id(14), "warehouse"), 2, InputMode.BULK, 0).orElseThrow();
			long generation = runtime.snapshot(cluster).orElseThrow().generation();
			assertFalse(runtime.creditPanelGoalActualStock(cluster, epoch, generation, goal, 1));
			assertEquals(GoalState.RUNNABLE,
				runtime.snapshot(cluster).orElseThrow().goals().get(goal).state());
			boolean credited = false;
			long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
			while (!credited && System.nanoTime() < deadline) {
				ClusterEngineState current = runtime.snapshot(cluster).orElseThrow();
				credited = runtime.creditPanelGoalActualStock(cluster, epoch,
					current.generation(), goal, 2);
				if (!credited) Thread.sleep(5);
			}
			assertTrue(credited);
			GoalState observed = GoalState.RUNNABLE;
			while (observed != GoalState.TERMINAL && System.nanoTime() < deadline) {
				observed = runtime.snapshot(cluster).orElseThrow().goals().get(goal).state();
				if (observed != GoalState.TERMINAL) Thread.sleep(5);
			}
			assertEquals(GoalState.TERMINAL, observed);
		}
	}

	@Test
	void resetRequiredGoalCannotBeTerminalizedByLateActualStockCredit() {
		UUID cluster = id(15), epoch = id(16), goalId = id(17);
		GoalSubmission submission = new GoalSubmission(goalId, id(18), 1, InputMode.BULK);
		GoalRecord goal = new GoalRecord(goalId, cluster, submission,
			new CreateRouteKey(id(19), "warehouse"), new PolicySnapshot(0, 0, false),
			GoalState.WAITING, WaitReason.RESET_REQUIRED);
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState waiting = new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(), empty.members(), empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(waiting);

			assertFalse(runtime.creditPanelGoalActualStock(cluster, epoch, 0, goalId, 1),
				"late output evidence must not erase cancel-after-drain RESET_REQUIRED");
			GoalRecord observed = runtime.snapshot(cluster).orElseThrow().goals().get(goalId);
			assertEquals(GoalState.WAITING, observed.state());
			assertEquals(WaitReason.RESET_REQUIRED, observed.waitReason());
		}
	}

	@Test
	void sealedExactUnitResumesItsOutputDeliveryWaitBeforeTheNextUnitRun() {
		UUID cluster = id(20), epoch = id(21), goalId = id(22), computer = id(23), processId = id(24);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.UNEMPLOYED, 2, 0)
			.orElseThrow();
		GoalSubmission submission = new GoalSubmission(goalId, id(25), 2, InputMode.EXACT);
		GoalRecord goal = new GoalRecord(goalId, cluster, submission,
			new CreateRouteKey(id(26), "warehouse"), new PolicySnapshot(0, 0, false),
			GoalState.WAITING, WaitReason.OUTPUT_DELIVERY);
		ProcessRecord process = new ProcessRecord(processId, goalId, computer, 0, profile,
			ProcessKind.GOAL, ProcessState.WAITING, WaitReason.OUTPUT_DELIVERY, 0, Map.of());
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState waiting = new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(processId, process), List.of(new ClusterEngineState.MemberSnapshot(computer,
				profile, true, 0, 0)), empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(waiting);

			assertTrue(ProductionPlanDispatch.resumeAfterSealedOutput(runtime,
				plan(cluster, goalId, false)));
			assertEquals(GoalState.RUNNABLE,
				runtime.snapshot(cluster).orElseThrow().goals().get(goalId).state());
			assertEquals(ProcessState.RUNNABLE,
				runtime.snapshot(cluster).orElseThrow().processes().get(processId).state());
		}
	}

	@Test
	void cancelAfterDrainPlanRejectsActualStockCompletionCreditUntilReset() {
		UUID cluster = id(27), goalId = id(28);
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, id(29), 1, InputMode.BULK),
			new CreateRouteKey(id(30), "warehouse"), new PolicySnapshot(0, 0, false),
			GoalState.RUNNABLE, null);

		assertFalse(com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal.ProductionGoalSettlement.shouldCreditGoalStock(
			plan(cluster, goalId, true), goal));
		assertEquals(WaitReason.RESET_REQUIRED,
			FactoryOrchestrationServer.outputDeliveryWaitReason(plan(cluster, goalId, true)));
	}

	@Test
	void productionManifestIsStrictAndAggregatesTheFrozenOrderedIngredients() {
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey gold = new StackKey(new ItemStack(Items.GOLD_INGOT));
		Map<StackKey, Long> manifest = DispatchManifest.decode(List.of(
			new OrderedIngredient(iron, 2),
			new OrderedIngredient(iron, 3),
			new OrderedIngredient(gold, 1)));
		assertEquals(5L, manifest.get(iron));
		assertEquals(1L, manifest.get(gold));
		assertThrows(IllegalArgumentException.class, () -> DispatchManifest.decode(
			List.of(new OrderedIngredient(
				new StackKey(new ItemStack(new net.minecraft.world.item.Item(
					new net.minecraft.world.item.Item.Properties()))), 1))));
	}

	@Test
	void componentBearingProductionManifestSurvivesIntentPersistenceAndDecode() {
		UUID cluster = id(190), epoch = id(191), process = id(192), run = id(193);
		ItemStack concrete = new ItemStack(Items.PAPER);
		concrete.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey exact = new StackKey(concrete);
		ProductionEffect.CreateDispatch dispatch = new ProductionEffect.CreateDispatch(id(194), cluster, 0,
			new ProductionEffect.TargetFence(epoch, process, 0, 0), process, run,
			new CreateRouteKey(id(195), "component-input"),
			List.of(new OrderedIngredient(exact, 2)));
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState pending = new ClusterEngineState(cluster, epoch, 0, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			empty.members(), Map.of(dispatch.intentId(), dispatch), Set.of(), 0, null);

		ClusterEngineState reloaded = RuntimeNbt.load(RuntimeNbt.save(pending)).orElseThrow();
		ProductionEffect.CreateDispatch restored = (ProductionEffect.CreateDispatch) reloaded.pendingIntents()
			.get(dispatch.intentId());
		assertEquals(exact, restored.ingredients().getFirst().stack());
		assertEquals(Map.of(exact, 2L), DispatchManifest.decode(
			restored.ingredients()));
	}

	@Test
	void durableAgentInputReplayPrecedesMutableProviderRevalidation() {
		List<String> events = new ArrayList<>();

		assertTrue(ProductionInputDispatch.replayBeforeProvider(
			() -> {
				events.add("agent-input-replay");
				return Optional.of(true);
			},
			() -> {
				events.add("provider-revalidation");
				return false;
			}));
		assertEquals(List.of("agent-input-replay"), events,
			"an exact durable input commit remains authoritative after its Agent becomes BUSY");
	}

	@Test
	void durableBareAttemptPrecedesMutableProviderRevalidation() {
		List<String> events = new ArrayList<>();

		assertFalse(ProductionInputDispatch.replayBeforeProvider(
			() -> {
				events.add("bare-attempt-replay");
				return Optional.of(false);
			},
			() -> {
				events.add("provider-revalidation");
				return true;
			}));
		assertEquals(List.of("bare-attempt-replay"), events,
			"an uncertain durable bare attempt must fence instead of consulting a changed Gauge");
	}

	@Test
	void componentBearingOutputRequestSurvivesTheCurrentIntentSchemaAndReload() {
		UUID cluster = id(196), epoch = id(197), goal = id(198), run = id(199);
		ItemStack componentOutput = new ItemStack(Items.GOLD_INGOT);
		componentOutput.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey exactOutput = new StackKey(componentOutput);
		ProductionEffect.OutputPackageRequest output = new ProductionEffect.OutputPackageRequest(id(200),
			cluster, 3, new ProductionEffect.TargetFence(epoch, goal, 4, 5), run,
			new CreateRouteKey(id(209), "warehouse"), exactOutput);
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState pending = new ClusterEngineState(cluster, epoch, 3, 0, 0, 0,
			empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
			empty.members(), Map.of(output.intentId(), output), Set.of(), 0, null);

		ClusterEngineState reloaded = RuntimeNbt.load(RuntimeNbt.save(pending)).orElseThrow();
		ProductionEffect.OutputPackageRequest restored = (ProductionEffect.OutputPackageRequest)
			reloaded.pendingIntents().get(output.intentId());
		assertEquals(exactOutput, restored.exactOutput());
	}

	@Test
	void durableRemoteRadioDispatchPreventsASecondIntentForTheSameRunAfterReload() {
		UUID runId = id(201);
		ProductionEffect.CreateDispatch remote = new ProductionEffect.CreateDispatch(id(202), id(203),
			0, new ProductionEffect.TargetFence(id(204), id(205), 0, 0), id(206), runId,
			new CreateRouteKey(id(207), "agent-input"),
			List.of(new OrderedIngredient(
				new StackKey(new ItemStack(Items.IRON_INGOT)), 1)));

		assertTrue(FactoryOrchestrationServer.durableRemoteDispatchOwnsRun(
			List.of(remote), runId));
		assertFalse(FactoryOrchestrationServer.durableRemoteDispatchOwnsRun(
			List.of(remote), id(208)));
	}

	@Test
	void nativeInputAddressIsResolvedInsideTheAcceptingPackagersNetwork() {
		UUID firstNetwork = id(21), secondNetwork = id(22);
		CreateRouteKey first = new CreateRouteKey(firstNetwork, "shared-input");
		CreateRouteKey second = new CreateRouteKey(secondNetwork, "shared-input");

		assertEquals(second, FactoryOrchestrationServer.resolveManagedInputRoute(
			"shared-input", Set.of(first, second), Set.of(secondNetwork)).orElseThrow());
		assertTrue(FactoryOrchestrationServer.resolveManagedInputRoute(
			"shared-input", Set.of(first, second), Set.of(firstNetwork, secondNetwork)).isEmpty(),
			"a physically ambiguous attachment must remain fail-closed");
	}

	@Test
	void durableGoalObservationEvidenceCannotCreditMoreThanItsAbsoluteTarget() {
		StackKey item = new StackKey(new net.minecraft.world.item.ItemStack(
			net.minecraft.world.item.Items.IRON_INGOT));
		GoalObservationState initial =
			new GoalObservationState(id(31), id(32),
				new com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope.NetworkSummary(
					id(33), item, 14), 10, 4, 0, 0, Optional.empty());
		assertEquals(0, initial.withActualCount(3).creditedCount());
		assertEquals(3, initial.withActualCount(13).creditedCount());
		assertEquals(4, initial.withActualCount(99).creditedCount());
		assertEquals(3, initial.withNewlyConfirmedOutput(id(34), 3).creditedCount());
		assertEquals(1, initial.withActualCount(13).withNewlyConfirmedOutput(id(34), 1)
			.confirmedOutputCount());
		assertEquals(3, initial.withActualCount(13).withNewlyConfirmedOutput(id(34), 1)
			.creditedCount(), "stock observation and durable receipt use one monotonic credit");
		assertEquals(4, initial.withNewlyConfirmedOutput(id(34), 4).creditedCount());
		assertThrows(IllegalArgumentException.class, () -> initial.withActualCount(-1));
		assertThrows(IllegalArgumentException.class, () ->
			initial.withNewlyConfirmedOutput(id(34), -1));
		assertThrows(IllegalArgumentException.class, () ->
			initial.withNewlyConfirmedOutput(id(34), 5));
		// Actual duplicate-receipt retirement is exercised through ProductionGoalSettlementTest.

	}

	@Test
	void durablePlanRetainsSourceAuthorityAfterRemoteRecoveryRecordRelease() {
		UUID sourceCluster = id(36), goalId = id(37);
		FrozenProductionPlan plan = plan(sourceCluster, goalId, false);

		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		journal.write(ProductionPlanRepository.journalKey(goalId), plan, RegistryAccess.EMPTY);
		ProductionPlanRepository plans = new ProductionPlanRepository(new ProductionPlanRepository.Persistence() {
			public Collection<FrozenProductionPlan> loadAll() {
				return journal.readPrefix(ProductionPlanRepository.JOURNAL_PREFIX,
					FrozenProductionPlan.class, RegistryAccess.EMPTY).values();
			}
			public Optional<FrozenProductionPlan> load(UUID goal) {
				return journal.read(ProductionPlanRepository.journalKey(goal), FrozenProductionPlan.class, RegistryAccess.EMPTY);
			}
			public void save(FrozenProductionPlan value) {
				journal.write(ProductionPlanRepository.journalKey(value.goalId()), value, RegistryAccess.EMPTY);
			}
		});
		plans.recover();
		assertEquals(sourceCluster, plans.sourceClusterForRun(
			plan.runs().currentRun().runId(), plan.runs().currentRun()).orElseThrow(),
			"OUTPUT_DISPATCHED may release remote recovery capacity before the physical output "
				+ "receipt, but that must not erase the durable source Goal authority");
	}

	@Test
	void panelPlanFailureDiagnosticRetainsBoundedExceptionTypeAndMessage() {
		assertEquals("PLAN:IllegalStateException:freeze failed",
			ProductionAdmissions.panelPlanFailureDiagnostic(
				new IllegalStateException("freeze\nfailed")));
		String bounded = ProductionAdmissions.panelPlanFailureDiagnostic(
			new IllegalArgumentException("x".repeat(ProductionEffect.MAX_TEXT_LENGTH * 2)));
		assertTrue(bounded.length() <= ProductionEffect.MAX_TEXT_LENGTH);
		assertFalse(bounded.contains("\n"));
	}

	@Test
	void productionFailureMovesTheSourceGoalAndItsProcessToAnExplicitWait() {
		UUID cluster = id(41), epoch = id(42), computer = id(43), goalId = id(44),
			processId = id(46);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.UNEMPLOYED, 2, 0)
			.orElseThrow();
		GoalSubmission submission = new GoalSubmission(goalId, id(47), 1, InputMode.BULK);
		GoalRecord goal = new GoalRecord(goalId, cluster, submission,
			new CreateRouteKey(id(45), "warehouse"), new PolicySnapshot(0, 0, false),
			GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goalId, computer, 0, profile,
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState initial = new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(processId, process), List.of(new ClusterEngineState.MemberSnapshot(computer,
				profile, true, 0, 0)), empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(initial);
			assertTrue(runtime.waitGoalFromServerObservation(cluster, epoch, 0,
				goalId, WaitReason.AGENT_UNLOADED));
			ClusterEngineState waiting = runtime.snapshot(cluster).orElseThrow();
			assertEquals(GoalState.WAITING, waiting.goals().get(goalId).state());
			assertEquals(WaitReason.AGENT_UNLOADED, waiting.goals().get(goalId).waitReason());
			assertEquals(ProcessState.WAITING, waiting.processes().get(processId).state());
			assertEquals(WaitReason.AGENT_UNLOADED, waiting.processes().get(processId).waitReason());
			assertTrue(runtime.resumePanelGoal(cluster, epoch, waiting.generation(), goalId));
			ClusterEngineState resumed = runtime.snapshot(cluster).orElseThrow();
			assertEquals(GoalState.RUNNABLE, resumed.goals().get(goalId).state());
			assertEquals(ProcessState.RUNNABLE, resumed.processes().get(processId).state(),
				"resuming a recovered route must wake the Goal's real process too");
		}
	}

	@Test
	void durableResetRequiredInputReconcilesRunnableGoalAfterRestart() {
		UUID cluster = id(51), epoch = id(52), computer = id(53), goalId = id(54),
			processId = id(55), agentId = id(56);
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.UNEMPLOYED, 2, 0)
			.orElseThrow();
		GoalSubmission submission = new GoalSubmission(goalId, id(57), 1, InputMode.BULK);
		GoalRecord goal = new GoalRecord(goalId, cluster, submission,
			new CreateRouteKey(id(58), "warehouse"), new PolicySnapshot(0, 0, false),
			GoalState.RUNNABLE, null);
		ProcessRecord process = new ProcessRecord(processId, goalId, computer, 0, profile,
			ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		ClusterEngineState empty = ClusterEngineState.empty(cluster, epoch);
		ClusterEngineState initial = new ClusterEngineState(cluster, epoch, 0, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(processId, process), List.of(new ClusterEngineState.MemberSnapshot(computer,
				profile, true, 0, 0)), empty.pendingIntents(), empty.uncertainIntentIds(), 0, null);
		FrozenProductionPlan plan = plan(cluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		AgentInputContract.State forcedInput = new AgentInputContract.State(agentId, 0, id(59),
			run, AgentInputContract.ExecutionShape.STANDARD,
			List.of(new AgentInputContract.StageInput(0, run.inputRoutes().getFirst(), run.manifest())),
			20, AgentInputPhase.RESET_REQUIRED, 0, OptionalLong.empty(), Optional.empty(),
			Optional.empty(), 0, false, 0, true, false, 0);

		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(initial);

			assertTrue(FactoryOrchestrationServer.reconcileResetRequiredGoal(
				runtime, initial, forcedInput));
			ClusterEngineState reconciled = runtime.snapshot(cluster).orElseThrow();
			assertEquals(GoalState.WAITING, reconciled.goals().get(goalId).state());
			assertEquals(WaitReason.RESET_REQUIRED, reconciled.goals().get(goalId).waitReason());
			assertEquals(ProcessState.WAITING, reconciled.processes().get(processId).state());
			assertTrue(FactoryOrchestrationServer.reconcileResetRequiredGoal(
				runtime, reconciled, forcedInput), "recovery replay must be idempotent");
		}
	}

	@Test
	void durableEndpointPreflightIncludesUnloadedMembersAndRequiresExactAuthority() {
		UUID agent = id(61), owner = id(62), epoch = id(63), network = id(64);
		SpaceAddress inputA = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(1, 2, 3));
		SpaceAddress inputB = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(2, 2, 3));
		SpaceAddress output = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(3, 2, 3));
		FactoryPanelAgentAccess.Configuration first = endpointConfiguration(agent, owner, epoch,
			7, 10, AgentEndpointRole.INPUT, "input-a", network);
		FactoryPanelAgentAccess.Configuration second = endpointConfiguration(agent, owner, epoch,
			7, 11, AgentEndpointRole.INPUT, "input-b", network);
		FactoryPanelAgentAccess.Configuration third = endpointConfiguration(agent, owner, epoch,
			7, 12, AgentEndpointRole.OUTPUT, "output", network);
		Map<SpaceAddress, FactoryOrchestrationServer.AgentEndpointConfigurationState> desired = Map.of(
			inputA, new FactoryOrchestrationServer.AgentEndpointConfigurationState(inputA,
				Optional.of(first)),
			inputB, new FactoryOrchestrationServer.AgentEndpointConfigurationState(inputB,
				Optional.of(second)),
			output, new FactoryOrchestrationServer.AgentEndpointConfigurationState(output,
				Optional.of(third)));
		Map<SpaceAddress, FactoryOrchestrationServer.AgentEndpointAuthorityState> authorities = Map.of(
			inputA, new FactoryOrchestrationServer.AgentEndpointAuthorityState(inputA, owner, epoch, 10),
			inputB, new FactoryOrchestrationServer.AgentEndpointAuthorityState(inputB, owner, epoch, 11),
			output, new FactoryOrchestrationServer.AgentEndpointAuthorityState(output, owner, epoch, 12));

		assertEquals(Set.of(inputA, inputB, output), FactoryOrchestrationServer
			.authoritativeEndpointConfigurations(desired, authorities, inputA, agent)
			.orElseThrow().keySet(), "durable unloaded members remain in structural preflight");
		assertTrue(FactoryOrchestrationServer.authoritativeEndpointConfigurations(desired,
			Map.of(inputA, authorities.get(inputA), output, authorities.get(output)), inputA, agent)
			.isEmpty(), "a missing per-endpoint authority must fail closed");
	}

	@Test
	void structuralEndpointPlanRequiresEveryExistingAgentToRemainFormable() {
		UUID agent = id(71), owner = id(72), network = id(73);
		SpaceAddress input = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(1, 4, 1));
		SpaceAddress output = new SpaceAddress(Level.OVERWORLD, null, new BlockPos(2, 4, 1));
		AgentEndpointState nextInput = AgentEndpointState.fromNative(input, owner, network,
			endpointConfiguration(agent, owner, id(74), 8, 20, AgentEndpointRole.INPUT,
				"input", network), true, true);
		AgentEndpointState nextOutput = AgentEndpointState.fromNative(output, owner, network,
			endpointConfiguration(agent, owner, id(74), 8, 21, AgentEndpointRole.OUTPUT,
				"output", network), true, true);

		Map<UUID, AgentFormation> formation = FactoryOrchestrationServer
			.plannedMembershipFormations(Map.of(input, nextInput, output, nextOutput),
				Set.of(agent), Set.of(agent)).orElseThrow();
		assertEquals(Set.of(input, output), formation.get(agent).membershipAddresses());
		assertTrue(FactoryOrchestrationServer.plannedMembershipFormations(
			Map.of(output, nextOutput), Set.of(agent), Set.of(agent)).isEmpty(),
			"a structural edit may not strand a durable formed Agent");
	}

	@Test
	void recoveryPhasePublishesBeforeItsDeliveredMessagesAreRetired() {
		UUID first = id(81), second = id(82);
		List<String> events = new ArrayList<>();

		FactoryOrchestrationServer.publishRecoveryTransition(
			() -> events.add("phase"), messageId -> events.add("retire:" + messageId),
			List.of(first, second));

		assertEquals(List.of("phase", "retire:" + first, "retire:" + second), events);
		assertEquals(0, FactoryOrchestrationServer.retiredRecoveryMessagePrefix(
			FactoryOrchestrationServer.RemoteRecoveryPhase.OFFER_PENDING));
		assertEquals(1, FactoryOrchestrationServer.retiredRecoveryMessagePrefix(
			FactoryOrchestrationServer.RemoteRecoveryPhase.TERMINAL_PENDING));
		assertEquals(2, FactoryOrchestrationServer.retiredRecoveryMessagePrefix(
			FactoryOrchestrationServer.RemoteRecoveryPhase.STATUS_QUERY_PENDING));
		assertEquals(3, FactoryOrchestrationServer.retiredRecoveryMessagePrefix(
			FactoryOrchestrationServer.RemoteRecoveryPhase.STATUS_CONFIRMED));
		assertEquals(4, FactoryOrchestrationServer.retiredRecoveryMessagePrefix(
			FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED));
	}

	@Test
	void ordinaryRadioWorldIntentRemainsReservedUntilItHasADurableRecipientInbox() {
		ProductionEffect.RadioSend reserved = new ProductionEffect.RadioSend(id(91), id(92), 3,
			new ProductionEffect.TargetFence(id(93), id(94), 5, 7), id(95), "payload");

		assertFalse(ProductionEffectPolicy.supported(reserved));
	}

	@Test
	void leaseRadioProtocolMayRetireOnlyAfterItsDurableRecipientInboxAppliesIt() {
		RemoteExpansionRequest request = remoteRequest(id(102), id(103), 8);
		RemoteProcessLease lease = new RemoteProcessLease(id(101), id(102), id(103),
			id(104), id(105), 6, 7, ProductionCapability.ALCHEMIST, request, 8);
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);

		assertTrue(FactoryOrchestrationServer.genericRadioProtocolHasDurableRecipientStore(
			new ProcessProtocolMessage.LeaseOffer(lease)));
		assertTrue(FactoryOrchestrationServer.genericRadioProtocolHasDurableRecipientStore(
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 8, revision)));
		assertTrue(FactoryOrchestrationServer.genericRadioProtocolHasDurableRecipientStore(
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 8)));
		assertTrue(FactoryOrchestrationServer.genericRadioProtocolHasDurableRecipientStore(
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 8,
				RemoteExpansionResult.compute(request, revision))));
		assertTrue(FactoryOrchestrationServer.genericRadioProtocolHasDurableRecipientStore(
			new AgentProtocolMessage.StatusQuery(new RemoteRequestKey(id(106), id(107), 8, 6, 7))));
	}

	@Test
	void sourceCreatesOnlyTheOfferAndNeverPrebuildsReceiverRevisionOrResult() {
		UUID sourceCluster = id(120), receiverCluster = id(121), goalId = id(122);
		FrozenProductionPlan plan = plan(sourceCluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		RadioEndpoint source = new RadioEndpoint(sourceCluster, id(123),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)),
			6, CommunicationFrequency.of("17"), 1, Set.of(ProductionCapability.ALCHEMIST));
		RadioEndpoint receiver = new RadioEndpoint(receiverCluster, id(124),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(8, 64, 0)),
			7, CommunicationFrequency.of("17"), 1, Set.of(ProductionCapability.ALCHEMIST));
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.CLERIC, 5, 0).orElseThrow();
		ProcessRecord sourceProcess = new ProcessRecord(id(125), goalId, source.memberId(), 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			RemoteExpansionProtocol.RemoteComputationExchange.begin(
				RemoteExpansionTestRegistries.provider(),
				plan, run, sourceProcess, 8,
				source, receiver, List.of(), List.of());

		assertEquals(sourceCluster, exchange.lease().goalOwnerClusterId());
		assertEquals(goalId, exchange.lease().sourceGoalId());
		assertFalse(exchange.materialDispatchAllowed());
		assertTrue(exchange.revisionEnvelope().isEmpty(),
			"only the receiver worker may create the revision");
		assertTrue(exchange.acknowledgementEnvelope().isEmpty(),
			"the source may create ACK only after receiving a matching revision");
		assertTrue(exchange.resultEnvelope().isEmpty(),
			"only the receiver worker may create the terminal result");
	}

	@Test
	void sameClusterPureExpansionPrefersTheLeastOccupiedEligibleComputerBeforeRadio() {
		UUID cluster = id(10_500), epoch = id(10_501), goalId = id(10_502);
		UUID sourceMember = id(10_503), busyMember = id(10_504), idleMember = id(10_505);
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		ProductionResourceProfile alchemist = ProductionResourceProfile.forProfession(
			ProductionProfession.CLERIC, 1, 0).orElseThrow();
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, id(10_506), 1, InputMode.BULK),
			new CreateRouteKey(id(10_507), "local-placement"),
			new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		ProcessRecord source = new ProcessRecord(id(10_508), goalId, sourceMember, 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 1, Map.of());
		ProcessRecord busy = new ProcessRecord(id(10_509), goalId, busyMember, 0,
			alchemist, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 1, Map.of());
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 4, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(source.processId(), source, busy.processId(), busy), List.of(
				new ClusterEngineState.MemberSnapshot(sourceMember, sourceProfile, true, 1, 1),
				new ClusterEngineState.MemberSnapshot(busyMember, alchemist, true, 2, 2),
				new ClusterEngineState.MemberSnapshot(idleMember, alchemist, true, 3, 3)),
			Map.of(), Set.of(), 0, null);

		ProductionLocalExpansion.Target target =
			ProductionLocalExpansion.selectTarget(state, source,
				ProductionCapability.ALCHEMIST).orElseThrow();
		assertEquals(idleMember, target.memberId(),
			"a fully idle same-Cluster Computer wins before a partially occupied one");
		assertEquals(3, target.generation());

		Map<UUID, ProcessRecord> full = new LinkedHashMap<>(state.processes());
		full.put(id(10_510), new ProcessRecord(id(10_510), goalId, busyMember, 1,
			alchemist, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 1, Map.of()));
		full.put(id(10_511), new ProcessRecord(id(10_511), goalId, idleMember, 0,
			alchemist, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 1, Map.of()));
		full.put(id(10_512), new ProcessRecord(id(10_512), goalId, idleMember, 1,
			alchemist, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 1, Map.of()));
		ClusterEngineState saturated = new ClusterEngineState(cluster, epoch, 5, 1, 0, 0,
			state.goals(), state.submissionSequences(), state.requestedKinds(), full,
			state.members(), Map.of(), Set.of(), 0, null);
		assertTrue(ProductionLocalExpansion.selectTarget(saturated,
			source, ProductionCapability.ALCHEMIST).isEmpty(),
			"cross-Cluster radio becomes eligible only after every local capable slot is full");
	}

	@Test
	void sameClusterPureExpansionLifecycleRoundTripsWithoutAnyRadioEnvelope() {
		UUID cluster = id(10_520), epoch = id(10_521), goalId = id(10_522);
		UUID waitingGoalId = id(10_522_1);
		UUID sourceMemberId = id(10_523), receiverMemberId = id(10_524);
		FrozenProductionPlan plan = plan(cluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.CLERIC, 1, 0).orElseThrow();
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, plan.patternId(), 1, InputMode.BULK),
			new CreateRouteKey(id(10_526), "local-lifecycle"),
			new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		GoalRecord waitingGoal = new GoalRecord(waitingGoalId, cluster,
			new GoalSubmission(waitingGoalId, id(10_525_1), 1, InputMode.BULK),
			new CreateRouteKey(id(10_526_1), "local-release-waiter"),
			new PolicySnapshot(1, 0, false), GoalState.WAITING, WaitReason.PROCESS);
		ProcessRecord source = new ProcessRecord(id(10_527), goalId, sourceMemberId, 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 4, Map.of());
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 7, 2, 0, 0,
			Map.of(goalId, goal, waitingGoalId, waitingGoal),
			Map.of(goalId, 0L, waitingGoalId, 1L),
			Map.of(goalId, ProcessKind.GOAL, waitingGoalId, ProcessKind.GOAL),
			Map.of(source.processId(), source), List.of(
				new ClusterEngineState.MemberSnapshot(sourceMemberId, sourceProfile, true, 8, 8),
				new ClusterEngineState.MemberSnapshot(receiverMemberId, receiverProfile, true, 9, 9)),
			Map.of(), Set.of(), 0, null);
		ProductionLocalExpansion.Target target =
			ProductionLocalExpansion.selectTarget(state, source,
				ProductionCapability.ALCHEMIST).orElseThrow();
		RemoteProcessLease lease = ProductionLocalExpansion.leaseFor(
			RemoteExpansionTestRegistries.provider(), plan, run, source, state, target);
		assertEquals(cluster, lease.sourceClusterId());
		assertEquals(cluster, lease.receiverClusterId());
		assertEquals(goalId, lease.sourceGoalId());
		assertEquals(source.processId(), lease.sourceProcessId());
		FactoryRuntimeService.RemoteComputationAllocation allocation;
		ClusterEngineState durable;
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start();
			runtime.restoreSnapshot(state);
			allocation = runtime.acquireRemoteComputationProcess(lease, receiverMemberId)
				.orElseThrow();
			ProcessRecord local = runtime.snapshot(cluster).orElseThrow().processes()
				.get(allocation.processId());
			assertEquals(ProcessKind.PURE_EXPANSION, local.kind());
			assertTrue(local.kind().requiresAlchemist());
			assertFalse(local.kind() == ProcessKind.REMOTE_LISTENER);
			durable = RuntimeNbt.load(RuntimeNbt.save(runtime.snapshot(cluster).orElseThrow()))
				.orElseThrow();
		}
		ProductionLocalExpansion.Job job =
			ProductionLocalExpansion.Job.begin(run.runId(), goalId, lease,
				sourceMemberId, allocation);
		ProductionLocalExpansion.Lifecycle lifecycle =
			new ProductionLocalExpansion.Lifecycle(
				Map.of(run.runId(), job));
		CompoundTag encoded = CurrentSchemaStateNbt.save(lifecycle, RegistryAccess.EMPTY);
		assertEquals(lifecycle, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			ProductionLocalExpansion.Lifecycle.class).orElseThrow());
		try (FactoryRuntimeService restored = new FactoryRuntimeService(config())) {
			restored.start();
			restored.restoreSnapshot(durable);
			assertTrue(restored.ensureRemoteComputationProcess(allocation.processId(), lease,
				receiverMemberId, allocation.owningSlot()));
			assertTrue(restored.releaseRemoteComputationProcess(allocation.processId(), lease,
				receiverMemberId, allocation.owningSlot()));
			assertFalse(restored.snapshot(cluster).orElseThrow().processes()
				.containsKey(allocation.processId()));
			assertFalse(restored.snapshot(cluster).orElseThrow().goals()
				.get(waitingGoalId).state() == GoalState.WAITING,
				"a PURE_EXPANSION slot release must atomically wake WAIT(PROCESS)");
		}
	}

	@Test
	void sameClusterPureExpansionLedgerBackpressureSurvivesReloadAndRetriesLocally() {
		UUID cluster = id(10_530), epoch = id(10_531), goalId = id(10_532);
		UUID sourceMemberId = id(10_533), receiverMemberId = id(10_534);
		FrozenProductionPlan plan = plan(cluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.CLERIC, 1, 0).orElseThrow();
		GoalRecord goal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, plan.patternId(), 1, InputMode.BULK),
			new CreateRouteKey(id(10_535), "local-ledger-backpressure"),
			new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		ProcessRecord source = new ProcessRecord(id(10_536), goalId, sourceMemberId, 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 4, Map.of());
		ClusterEngineState state = new ClusterEngineState(cluster, epoch, 7, 1, 0, 0,
			Map.of(goalId, goal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(source.processId(), source), List.of(
				new ClusterEngineState.MemberSnapshot(sourceMemberId, sourceProfile, true, 8, 8),
				new ClusterEngineState.MemberSnapshot(receiverMemberId, receiverProfile, true, 9, 9)),
			Map.of(), Set.of(), 0, null);
		ProductionLocalExpansion.Target target =
			ProductionLocalExpansion.selectTarget(state, source,
				ProductionCapability.ALCHEMIST).orElseThrow();

		UUID blockerGoal = id(10_537), blockerProcess = id(10_538);
		RemoteExpansionRequest blockerRequest = remoteRequest(blockerGoal, blockerProcess, 3);
		RemoteProcessLease blockerLease = new RemoteProcessLease(id(10_539), blockerGoal,
			blockerProcess, cluster, cluster, 1, 1, ProductionCapability.ALCHEMIST,
			blockerRequest, blockerRequest.parentStateGeneration());
		ProductionLocalExpansion.Job blocker =
			ProductionLocalExpansion.Job.begin(blockerRequest.runId(), blockerGoal,
				blockerLease, id(10_539_1), new FactoryRuntimeService.RemoteComputationAllocation(
					id(10_539_2), id(10_539_3), 0));
		LinkedHashMap<UUID, ProductionLocalExpansion.Job> activeJobs =
			new LinkedHashMap<>(Map.of(blocker.runId(), blocker));
		ClusterEngineState durableState;
		CompoundTag durableLifecycle;

		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start();
			runtime.restoreSnapshot(state);
			ProductionLocalExpansion localOwner = localOwner(runtime, activeJobs);
			localOwner.restore();
			ProductionLocalExpansion.BeginResult full =
				localOwner.begin(plan, run, source, state, target);
			assertEquals(ProductionLocalExpansion.BeginStatus.RECOVERY_LEDGER_FULL,
				full.status());
			assertTrue(full.job().isEmpty());
			assertEquals(WaitReason.RECOVERY_LEDGER_FULL,
				ProductionLocalExpansion.beginWaitReason(full.status()));
			assertTrue(runtime.snapshot(cluster).orElseThrow().processes().values().stream()
				.noneMatch(process -> process.kind() == ProcessKind.PURE_EXPANSION),
				"ledger backpressure must be reported before claiming the eligible local slot");

			ClusterEngineState current = runtime.snapshot(cluster).orElseThrow();
			assertTrue(runtime.waitGoalFromServerObservation(cluster, epoch, current.generation(),
				goalId, ProductionLocalExpansion.beginWaitReason(full.status())));
			durableState = RuntimeNbt.load(RuntimeNbt.save(runtime.snapshot(cluster).orElseThrow()))
				.orElseThrow();
			durableLifecycle = CurrentSchemaStateNbt.save(
				new ProductionLocalExpansion.Lifecycle(activeJobs),
				RegistryAccess.EMPTY);
		}

		ProductionLocalExpansion.Lifecycle reloadedLifecycle =
			CurrentSchemaStateNbt.load(durableLifecycle, RegistryAccess.EMPTY,
				ProductionLocalExpansion.Lifecycle.class).orElseThrow();
		activeJobs.clear();
		activeJobs.putAll(reloadedLifecycle.jobs());
		try (FactoryRuntimeService restored = new FactoryRuntimeService(config())) {
			restored.start();
			restored.restoreSnapshot(durableState);
			ClusterEngineState waiting = restored.snapshot(cluster).orElseThrow();
			GoalRecord reloadedGoal = waiting.goals().get(goalId);
			assertEquals(GoalState.WAITING, reloadedGoal.state());
			assertEquals(WaitReason.RECOVERY_LEDGER_FULL, reloadedGoal.waitReason());
			assertTrue(ProductionPlanDispatch.waitsForPureComputation(reloadedGoal),
				"the durable wait must remain eligible for the local-first retry path");

			ProcessRecord reloadedSource = waiting.processes().get(source.processId());
			ProductionLocalExpansion.Target reloadedTarget =
				ProductionLocalExpansion.selectTarget(waiting, reloadedSource,
					ProductionCapability.ALCHEMIST).orElseThrow();
			ProductionLocalExpansion localOwner = localOwner(restored, activeJobs);
			localOwner.restore();
			ProductionLocalExpansion.BeginResult stillFull =
				localOwner.begin(plan, run, reloadedSource, waiting, reloadedTarget);
			assertEquals(ProductionLocalExpansion.BeginStatus.RECOVERY_LEDGER_FULL,
				stillFull.status(), "reload must not erase lifecycle-ledger backpressure");

			activeJobs.clear();
			CompoundTag relieved = CurrentSchemaStateNbt.save(
				new ProductionLocalExpansion.Lifecycle(activeJobs),
				RegistryAccess.EMPTY);
			activeJobs.putAll(CurrentSchemaStateNbt.load(relieved, RegistryAccess.EMPTY,
				ProductionLocalExpansion.Lifecycle.class).orElseThrow().jobs());
			localOwner.restore();
			ProductionLocalExpansion.BeginResult retried =
				localOwner.begin(plan, run, reloadedSource, waiting, reloadedTarget);
			assertEquals(ProductionLocalExpansion.BeginStatus.STARTED,
				retried.status());
			assertEquals(run.runId(), retried.job().orElseThrow().runId());
			assertEquals(Set.of(ProcessKind.GOAL, ProcessKind.PURE_EXPANSION),
				restored.snapshot(cluster).orElseThrow().processes().values().stream()
					.map(ProcessRecord::kind).collect(java.util.stream.Collectors.toSet()));
			assertTrue(restored.snapshot(cluster).orElseThrow().processes().values().stream()
				.noneMatch(process -> process.kind() == ProcessKind.REMOTE_LISTENER),
				"relieving local ledger backpressure must retry locally, not open a radio listener");
		}
	}

	@Test
	void localAndRemotePureModesResumeEveryCapacityOrRouteWaitReason() {
		UUID cluster = id(10_540), goalId = id(10_541);
		for (WaitReason reason : List.of(WaitReason.PROCESS, WaitReason.ROUTE,
			WaitReason.RECOVERY_LEDGER_FULL)) {
			GoalRecord waiting = new GoalRecord(goalId, cluster,
				new GoalSubmission(goalId, id(10_542), 1, InputMode.BULK),
				new CreateRouteKey(id(10_543), "pure-mode-wait"),
				new PolicySnapshot(1, 0, false), GoalState.WAITING, reason);
			assertTrue(ProductionPlanDispatch.waitsForPureComputation(waiting), reason.name());
		}
		GoalRecord runnable = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, id(10_542), 1, InputMode.BULK),
			new CreateRouteKey(id(10_543), "pure-mode-wait"),
			new PolicySnapshot(1, 0, false), GoalState.RUNNABLE, null);
		assertFalse(ProductionPlanDispatch.waitsForPureComputation(runnable));
	}

	@Test
	void receiverSlotIsOwnedBeforeItsDurableJobAndOrdinarySchedulingCannotStealIt()
		throws Exception {
		UUID sourceCluster = id(126), receiverCluster = id(127), goalId = id(128);
		FrozenProductionPlan plan = plan(sourceCluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		RadioEndpoint source = new RadioEndpoint(sourceCluster, id(129),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)),
			6, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = new RadioEndpoint(receiverCluster, id(130),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(8, 64, 0)),
			7, CommunicationFrequency.of("17"), 1, Set.of(ProductionCapability.ALCHEMIST));
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		ProcessRecord sourceProcess = new ProcessRecord(id(131), goalId, source.memberId(), 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			RemoteExpansionProtocol.RemoteComputationExchange.begin(
				RemoteExpansionTestRegistries.provider(),
				plan, run, sourceProcess, 8,
				source, receiver, List.of(), List.of());
		ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.CLERIC, 1, 0).orElseThrow();
		UUID receiverEpoch = id(132);
		ClusterEngineState empty = ClusterEngineState.empty(receiverCluster, receiverEpoch);
		ClusterEngineState receiverState = new ClusterEngineState(receiverCluster, receiverEpoch,
			0, 0, 0, 0, empty.goals(), empty.submissionSequences(), empty.requestedKinds(),
			empty.processes(), List.of(new ClusterEngineState.MemberSnapshot(receiver.memberId(),
				receiverProfile, true, 0, 0)), empty.pendingIntents(), empty.uncertainIntentIds(),
			0, null);

		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(receiverState);
			FactoryRuntimeService.RemoteComputationAllocation allocation = runtime
				.acquireRemoteComputationProcess(exchange.lease(), receiver.memberId())
				.orElseThrow();
			assertTrue(runtime.remoteComputationProcessValid(allocation.processId(), exchange.lease(),
				allocation.receiverMemberId(), allocation.owningSlot()),
				"the exact receiver slot must already be installed when the allocation is returned");
			RemoteExpansionProtocol.RemoteComputationJob job =
				RemoteExpansionProtocol.RemoteComputationJob.begin(exchange, allocation);
			var encodedJob = CurrentSchemaStateNbt.save(job, RegistryAccess.EMPTY);
			assertEquals(job, CurrentSchemaStateNbt.load(encodedJob, RegistryAccess.EMPTY,
				RemoteExpansionProtocol.RemoteComputationJob.class).orElseThrow());

			ClusterEngineState reserved = runtime.snapshot(receiverCluster).orElseThrow();
			UUID ordinary = runtime.submitPanelGoal(receiverCluster, receiverEpoch,
				reserved.generation(), id(133), new CreateRouteKey(id(134), "ordinary"), 1,
				InputMode.BULK, 0).orElseThrow();
			long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
			ProcessRecord ordinaryProcess = null;
			while (System.nanoTime() < deadline) {
				ordinaryProcess = runtime.snapshot(receiverCluster).orElseThrow().processes().values()
					.stream().filter(process -> process.goalId().equals(ordinary)).findFirst()
					.orElse(null);
				if (ordinaryProcess != null) break;
				Thread.sleep(5);
			}
			assertTrue(ordinaryProcess != null,
				"the second canonical Cleric slot should remain available to ordinary scheduling");
			assertTrue(ordinaryProcess.owningSlot() != allocation.owningSlot(),
				"ordinary scheduling must observe and avoid the exact receiver-owned slot");
			assertTrue(runtime.remoteComputationProcessValid(allocation.processId(), exchange.lease(),
				allocation.receiverMemberId(), allocation.owningSlot()),
				"ordinary scheduling may not steal the durable receiver slot");
		}
	}

	@Test
	void deliveredRemoteOfferWaitsForAnExactReceiverSlotAcrossOutboxReloadWithoutDuplicates() {
		UUID sourceCluster = id(1_100), receiverCluster = id(1_101), goalId = id(1_102);
		FrozenProductionPlan plan = plan(sourceCluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		RadioEndpoint source = new RadioEndpoint(sourceCluster, id(1_103),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)),
			6, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = new RadioEndpoint(receiverCluster, id(1_104),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(8, 64, 0)),
			7, CommunicationFrequency.of("17"), 1, Set.of(ProductionCapability.ALCHEMIST));
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		ProcessRecord sourceProcess = new ProcessRecord(id(1_105), goalId, source.memberId(), 0,
			sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 0, Map.of());
		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			RemoteExpansionProtocol.RemoteComputationExchange.begin(
				RemoteExpansionTestRegistries.provider(), plan, run, sourceProcess, 8,
				source, receiver, List.of(), List.of());
		ProductionResourceProfile receiverProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.CLERIC, 1, 0).orElseThrow();
		ClusterEngineState empty = ClusterEngineState.empty(receiverCluster, id(1_106));
		ClusterEngineState receiverState = new ClusterEngineState(receiverCluster, empty.epochId(),
			0, 0, 0, 0, empty.goals(), empty.submissionSequences(), empty.requestedKinds(),
			empty.processes(), List.of(new ClusterEngineState.MemberSnapshot(receiver.memberId(),
				receiverProfile, true, 0, 0)), empty.pendingIntents(), empty.uncertainIntentIds(),
			0, null);
		List<RemoteProcessLease> blockers = new ArrayList<>();
		for (int slot = 0; slot < receiverProfile.processSlots(); slot++)
			blockers.add(remoteReceiverLease(1_200 + slot * 20L, receiverCluster,
				receiver.generation()));
		OutboxStore outboxStore = new OutboxStore();
		DurableRadioOutbox outbox = new DurableRadioOutbox(4, outboxStore);
		RadioRouter router = new RadioRouter(16, 48, 32);
		assertTrue(router.register(source)); assertTrue(router.register(receiver));
		assertTrue(outbox.offer(exchange.offerEnvelope(), List.of()));
		assertEquals(1, outbox.pump(router, 1).size());

		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(receiverState);
			List<FactoryRuntimeService.RemoteComputationAllocation> occupied = new ArrayList<>();
			for (RemoteProcessLease blocker : blockers)
				occupied.add(runtime.acquireRemoteComputationProcess(blocker,
					receiver.memberId()).orElseThrow());
			assertTrue(ProductionRemoteExpansion.prepareRemoteOfferAcceptance(runtime, exchange,
				exchange.offerEnvelope(), null, receiver).isEmpty(),
				"DELIVERED cannot be applied until the exact receiver slot is allocated");
			assertEquals(RadioRouter.RouteStatus.DELIVERED,
				outbox.status(exchange.offerEnvelope().messageId()).orElseThrow());

			DurableRadioOutbox reloaded = new DurableRadioOutbox(4, outboxStore);
			assertEquals(1, reloaded.deliveredButUnapplied(1, Optional.empty()).size());
			assertTrue(runtime.releaseRemoteComputationProcess(occupied.getFirst().processId(),
				blockers.getFirst(), occupied.getFirst().receiverMemberId(),
				occupied.getFirst().owningSlot()));
			RemoteExpansionProtocol.RemoteOfferAcceptance accepted =
				ProductionRemoteExpansion.prepareRemoteOfferAcceptance(runtime, exchange,
					exchange.offerEnvelope(), null, receiver).orElseThrow();
			Map<UUID, RemoteExpansionProtocol.RemoteComputationJob> jobs =
				new java.util.LinkedHashMap<>();
			jobs.put(accepted.job().runId(), accepted.job());
			assertEquals(accepted, ProductionRemoteExpansion.prepareRemoteOfferAcceptance(runtime,
				exchange, exchange.offerEnvelope(), accepted.job(), receiver).orElseThrow(),
				"recipient replay returns the same single receiver job");
			assertTrue(reloaded.markApplied(exchange.offerEnvelope().messageId()));
			assertEquals(1, jobs.size());
			assertEquals(RemoteExpansionProtocol.RemoteComputationPhase.REVISION_COMPUTING,
				accepted.exchange().phase());
			assertTrue(accepted.exchange().acknowledgementEnvelope().isEmpty());
			assertTrue(runtime.snapshot(receiverCluster).orElseThrow().goals().isEmpty(),
				"remote receiver allocation never creates a receiver Goal");
			assertEquals(1, router.appliedOfferCount(exchange.lease().leaseId()),
				"business retry cannot reapply the protocol OFFER");
		}
	}

	@Test
	void sourceInstallsAcknowledgedReceiverExpansionIntoFrozenExecutionBeforeMaterial() {
		UUID sourceCluster = id(140), goalId = id(141);
		FrozenProductionPlan plan = plan(sourceCluster, goalId, false);
		ProductionRun run = plan.runs().currentRun();
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, id(142), 3, 9,
			plan.patternId(), 2, run.runId(), run.fence(), run.inputMode(), run.runMultiplier(),
			plan.stages().stream().map(stage -> new RemoteExpansionRequest.StageInput(
				stage.index(), stage.route(), stage.manifest())).toList(), run.manifest());
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
		RemoteExpansionResult result = RemoteExpansionResult.compute(request, revision);

		FrozenProductionPlan revisionInstalled = plan.installRemoteRevision(request, revision);
		assertFalse(revisionInstalled.remoteExpansionMaterialReady(),
			"an accepted child revision cannot authorize material before its terminal result");
		FrozenProductionPlan resultInstalled = revisionInstalled.installRemoteResult(result);

		assertTrue(resultInstalled.remoteExpansionMaterialReady());
		assertEquals(result, resultInstalled.remoteExpansion().orElseThrow().result().orElseThrow());
		assertEquals(result.manifest(), resultInstalled.materializeCurrentRun(10).manifest(),
			"downstream Create execution must consume the installed receiver expansion");
		assertEquals(result.stages().stream().map(stage -> stage.manifest()).toList(),
			resultInstalled.expandedStages(run).stream().map(stage -> stage.manifest()).toList(),
			"Agent stage execution must consume the installed receiver expansion");
	}

	@Test
	void durableRemoteMaterialHandoffIsAReplayReceiptAndBlocksPreMaterialMigration() {
		UUID cluster = id(980), goalId = id(981);
		FrozenProductionPlan base = plan(cluster, goalId, false);
		ProductionRun run = base.runs().currentRun();
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, id(982), 0, 9,
			base.patternId(), 2, run.runId(), run.fence(), run.inputMode(),
			run.runMultiplier(), base.stages().stream().map(stage ->
				new RemoteExpansionRequest.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList(), run.manifest());
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
		RemoteExpansionResult result = RemoteExpansionResult.compute(request, revision);
		FrozenProductionPlan handedOff = base.installRemoteRevision(request, revision)
			.installRemoteResult(result).markDispatchQueued();

		assertFalse(ProductionRemoteExpansion.remoteMaterialHandoffOwnsRun(base, run.runId()));
		assertTrue(ProductionRemoteExpansion.remoteMaterialHandoffOwnsRun(handedOff, run.runId()),
			"dispatchQueued plus the exact accepted result is the durable replay receipt");
		assertFalse(ProductionRemoteExpansion.shouldMigrateRemoteComputation(false, true, false),
			"source generation changes cannot revoke an already handed-off material authority");
		assertTrue(ProductionRemoteExpansion.shouldMigrateRemoteComputation(false, false, false));
	}

	@Test
	void remoteMaterialHandoffBatchNeverBurnsAuthorizationWithoutItsReplayReceipt() {
		UUID cluster = id(990), goalId = id(991);
		FrozenProductionPlan base = plan(cluster, goalId, false);
		ProductionRun run = base.runs().currentRun();
		RadioEndpoint source = new RadioEndpoint(cluster, id(992),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)), 3, CommunicationFrequency.of("17"), 1,
			Set.of());
		RadioEndpoint receiver = new RadioEndpoint(id(993), id(994),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(8, 64, 0)), 4, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, id(995), 0, 9,
			base.patternId(), 2, run.runId(), run.fence(), run.inputMode(),
			run.runMultiplier(), base.stages().stream().map(stage ->
				new RemoteExpansionRequest.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList(), run.manifest());
		RemoteProcessLease lease = new RemoteProcessLease(id(996), goalId,
			request.parentProcessId(), cluster, receiver.clusterId(), source.generation(),
			receiver.generation(), ProductionCapability.ALCHEMIST, request,
			request.parentStateGeneration());
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
		RemoteExpansionResult result = RemoteExpansionResult.compute(request, revision);
		RadioEnvelope offer = RadioEnvelope.create(id(997), source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease));
		RadioEnvelope revised = RadioEnvelope.create(id(998), receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), lease.revisionFence(), revision));
		RadioEnvelope acknowledged = RadioEnvelope.create(id(999), source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), lease.revisionFence()));
		RadioEnvelope completed = RadioEnvelope.create(id(1_000), receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), lease.revisionFence(), result));
		RadioRouter router = new RadioRouter(16, 48, 32);
		assertTrue(router.register(source)); assertTrue(router.register(receiver));
		for (RadioEnvelope envelope : List.of(offer, revised, acknowledged, completed)) {
			router.submit(envelope, List.of());
			assertEquals(1, router.tick(envelope.messageId().getLeastSignificantBits()).deliveries().size());
		}
		RadioRouter.PreparedMaterialAuthorization authorization = router
			.prepareMaterialIntentAuthorization(receiver.key(), lease.leaseId(),
				lease.revisionFence()).orElseThrow();
		FrozenProductionPlan receipt = base.installRemoteRevision(request, revision)
			.installRemoteResult(result).markDispatchQueued();
		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			new RemoteExpansionProtocol.RemoteComputationExchange(goalId, run.runId(), lease,
				lease.revisionFence(), offer, Optional.of(revised), Optional.of(acknowledged),
				Optional.of(completed), List.of(), List.of(),
				RemoteExpansionProtocol.RemoteComputationPhase.COMPLETED);
		AtomicReference<FrozenProductionPlan> installedPlan = new AtomicReference<>(base);

		assertThrows(IllegalStateException.class, () ->
			RemoteExpansionPublication.publishRemoteMaterialHandoff((ignoredPlan, ignoredState) -> {
				throw new IllegalStateException("mailbox/journal rejected");
			}, receipt, exchange, authorization, installedPlan::set,
				router::installPersistedLeaseProtocolState));
		assertEquals(base, installedPlan.get());
		assertTrue(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(),
			lease.revisionFence()), "rejection leaves cancellation/migration pre-material safe");

		RemoteExpansionPublication.publishRemoteMaterialHandoff((ignoredPlan, ignoredState) -> {},
			receipt, exchange, authorization, installedPlan::set,
			router::installPersistedLeaseProtocolState);
		assertEquals(receipt, installedPlan.get());
		assertTrue(router.materialIntentAuthorized(receiver.key(), lease.leaseId(),
			lease.revisionFence()));
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(),
			lease.revisionFence()), "accepted handoff remains exactly once");

		FrozenProductionPlan reloadedReceipt = CurrentSchemaStateNbt.load(
			CurrentSchemaStateNbt.save(receipt, RegistryAccess.EMPTY), RegistryAccess.EMPTY,
			FrozenProductionPlan.class).orElseThrow();
		RadioRouter reloadedRouter = new RadioRouter(16, 48, 32);
		assertTrue(reloadedRouter.register(source)); assertTrue(reloadedRouter.register(receiver));
		reloadedRouter.installPersistedLeaseProtocolState(authorization.state());
		GoalRecord sourceGoal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, base.patternId(), 1, InputMode.EXACT), base.deliveryRoute(),
			new PolicySnapshot(0, 0, false), GoalState.RUNNABLE, null);
		ProductionResourceProfile sourceProfile = ProductionResourceProfile.forProfession(
			ProductionProfession.NITWIT, 1, 0).orElseThrow();
		ProcessRecord migratedParent = new ProcessRecord(request.parentProcessId(), goalId,
			source.memberId(), 0, sourceProfile, ProcessKind.GOAL, ProcessState.RUNNABLE, null,
			Math.incrementExact(request.parentProcessGeneration()), Map.of());
		ClusterEngineState emptySource = ClusterEngineState.empty(cluster, id(1_001));
		ClusterEngineState migratedSource = new ClusterEngineState(cluster,
			emptySource.epochId(), Math.incrementExact(request.parentStateGeneration()), 1, 0, 0,
			Map.of(goalId, sourceGoal), Map.of(goalId, 0L), Map.of(goalId, ProcessKind.GOAL),
			Map.of(migratedParent.processId(), migratedParent),
			List.of(new ClusterEngineState.MemberSnapshot(source.memberId(), sourceProfile,
				true, 0, 0)), Map.of(), Set.of(), 0, null);
		try (FactoryRuntimeService migratedRuntime = new FactoryRuntimeService(config())) {
			migratedRuntime.start(); migratedRuntime.restoreSnapshot(migratedSource);
			assertFalse(migratedRuntime.installRemoteChildRevision(lease, revision),
				"the old mutable Process generation is genuinely stale after migration");
			AtomicBoolean staleSourceFenceConsulted = new AtomicBoolean();
			assertTrue(ProductionRemoteExpansion.remoteMaterialHandoffMayPublish(reloadedReceipt,
				exchange, reloadedRouter, () -> {
					staleSourceFenceConsulted.set(true);
					return migratedRuntime.installRemoteChildRevision(lease, revision);
				}), "a durable handoff receipt must replay after its old source Process migrated");
			assertFalse(staleSourceFenceConsulted.get(),
				"replay authority comes from the atomic receipt, not the old Process generation");
		}
	}

	@Test
	void changingSafeRejectionDiagnosticKeepsOneExactAuthorityAcrossCallbacksAndReload() {
		UUID cluster = id(1_900), runId = id(1_901), processId = id(1_902);
		UUID intentId = id(1_903), agentId = id(1_904), outputEpoch = id(1_905);
		CreateRouteKey route = new CreateRouteKey(id(1_906), "safe-retry-input");
		long fence = 17;
		Map<StackKey, Long> manifest = Map.of(
			new StackKey(new ItemStack(Items.IRON_INGOT)), 3L);
		CreateDispatchCommand command = new CreateDispatchCommand(intentId, agentId, 5,
			fence, runId, outputEpoch, 0, route, fence, manifest);
		ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(intentId, cluster,
			fence, new ProductionEffect.TargetFence(id(1_907), id(1_908), 2, 3), processId, runId,
			route, manifest.entrySet().stream().map(entry ->
				new OrderedIngredient(entry.getKey(), entry.getValue())).toList());
		AtomicBoolean packagerLoaded = new AtomicBoolean();
		AtomicBoolean nativeAccepted = new AtomicBoolean();
		int[] nativeAttempts = { 0 };
		int[] successfulNativeOrders = { 0 };
		ProductionSafeRetryStore callbackState = new ProductionSafeRetryStore(
			new FactoryOrchestrationSavedData(), RegistryAccess.EMPTY);

		FactoryOrchestrationSavedData firstJournal = new FactoryOrchestrationSavedData();
		CreateLogisticsGateway firstGateway = safeRetrySequenceGateway(firstJournal, command,
			packagerLoaded, nativeAccepted, nativeAttempts, successfulNativeOrders);
		assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,
			firstGateway.commit(command).rejection());
		SafeCreateRetryProof unloaded = ProductionSafeRetryStore.persist(firstJournal, intent, firstJournal.inputCommitStore(
				RegistryAccess.EMPTY).load(intentId).orElseThrow(), RegistryAccess.EMPTY)
			.orElseThrow();
		callbackState.installLatest(unloaded);
		callbackState.installLatest(unloaded);
		assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,
			callbackState.get(runId).rejection());

		FactoryOrchestrationSavedData secondJournal = reloadJournal(firstJournal);
		packagerLoaded.set(true);
		CreateLogisticsGateway secondGateway = safeRetrySequenceGateway(secondJournal, command,
			packagerLoaded, nativeAccepted, nativeAttempts, successfulNativeOrders);
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED,
			secondGateway.commit(command).rejection());
		SafeCreateRetryProof nativeRejected = ProductionSafeRetryStore.persist(secondJournal, intent, secondJournal.inputCommitStore(
				RegistryAccess.EMPTY).load(intentId).orElseThrow(), RegistryAccess.EMPTY)
			.orElseThrow();
		assertTrue(unloaded.sameAuthority(nativeRejected));
		callbackState.installLatest(nativeRejected);
		callbackState.installLatest(nativeRejected);
		assertEquals(1, callbackState.size(), "duplicate callbacks retain one FIFO authority");
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED,
			callbackState.get(runId).rejection(), "the newest safe reason is diagnostic state");

		FactoryOrchestrationSavedData thirdJournal = reloadJournal(secondJournal);
		SafeCreateRetryProof reloaded = thirdJournal.read(
			ProductionSafeRetryStore.JOURNAL_PREFIX + runId,
			SafeCreateRetryProof.class, RegistryAccess.EMPTY).orElseThrow();
		assertTrue(nativeRejected.sameAuthority(reloaded));
		assertEquals(CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED,
			reloaded.rejection());
		CreateDispatchCommand changedProvider = new CreateDispatchCommand(intentId, id(1_909), 5,
			fence, runId, outputEpoch, 0, route, fence, manifest);
		assertThrows(IllegalStateException.class, () ->
			callbackState.installLatest(new SafeCreateRetryProof(intent, changedProvider,
					CreateLogisticsGateway.Rejection.NATIVE_DISPATCH_REJECTED)),
			"a changed provider/command remains an authority collision");

		nativeAccepted.set(true);
		CreateLogisticsGateway recoveredGateway = safeRetrySequenceGateway(thirdJournal, command,
			packagerLoaded, nativeAccepted, nativeAttempts, successfulNativeOrders);
		assertTrue(recoveredGateway.commit(command).committed());
		assertTrue(recoveredGateway.commit(command).committed());
		assertEquals(2, nativeAttempts[0],
			"one native safe rejection plus one successful native attempt");
		assertEquals(1, successfulNativeOrders[0], "success is physically created exactly once");
		assertTrue(SafeCreateRetryProof.from(intent,
			thirdJournal.inputCommitStore(RegistryAccess.EMPTY).load(intentId).orElseThrow()).isEmpty(),
			"durable success is never retry authority");
		thirdJournal.writeBatchAndRemoveActiveRunArtifacts(Map.of(), runId, RegistryAccess.EMPTY);
		FactoryOrchestrationSavedData retired = reloadJournal(thirdJournal);
		assertTrue(retired.read(ProductionSafeRetryStore.JOURNAL_PREFIX + runId,
			SafeCreateRetryProof.class, RegistryAccess.EMPTY).isEmpty());
		assertTrue(retired.inputCommitStore(RegistryAccess.EMPTY).load(intentId)
			.orElseThrow().provesMaterialCommit());
	}

	@Test
	void productionTickReplaysRemoteHandoffBeforeCancellationDrainReconciliation()
		throws Exception {
		String source = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/"
			+ "content/factorycluster/runtime/FactoryOrchestrationServer.java"));
		int tickStart = source.indexOf("\t\tprivate void tick() {");
		int tickEnd = source.indexOf("\n\t\tprivate void reconcileResetRequiredGoals()", tickStart);
		assertTrue(tickStart >= 0 && tickEnd > tickStart);
		String tick = source.substring(tickStart, tickEnd);
		int rebuild = tick.indexOf("rebuildAgent(agentId)");
		int rejection = tick.indexOf("reconcileRejectedCreateIntentConfirmations()");
		int replay = tick.indexOf("replayRemoteMaterialHandoffDispatches()");
		int cancellation = tick.indexOf("reconcileCancellationDrainPlans()");
		assertTrue(rebuild >= 0 && rejection > rebuild && replay > rejection
			&& cancellation > replay,
			"production tick must restore Agent state, replay an accepted handoff, then reconcile Cancel");
		int replayStart = source.indexOf(
			"\t\tprivate void replayRemoteMaterialHandoffDispatches() {");
		int replayEnd = source.indexOf("\n\t\tprivate void reconcileResetRequiredGoals()",
			replayStart);
		assertTrue(replayStart >= 0 && replayEnd > replayStart);
		String replayBody = source.substring(replayStart, replayEnd);
		int rejectionStart = source.indexOf(
			"\t\tprivate void reconcileRejectedCreateIntentConfirmations() {");
		assertTrue(rejectionStart >= 0 && rejectionStart < replayStart);
		String rejectionBody = source.substring(rejectionStart, replayStart);
		assertTrue(rejectionBody.contains("rejectedCreateConfirmationQueue.removeFirst()"));
		assertFalse(rejectionBody.contains("List.copyOf("),
			"a stuck first rejection must not starve later applied confirmations");
		assertTrue(replayBody.contains("config.maxCommittedIntentsPerTick()"));
		assertTrue(replayBody.contains("remoteMaterialHandoffOwnsRun(plan, runId)"));
		assertTrue(replayBody.contains("remoteExpansion.exchange(runId).orElse(null)"));
		assertTrue(replayBody.contains("replayRemoteMaterialHandoffDispatch(runtime,"));
		assertTrue(replayBody.contains("planDispatch.markSubmitted(runId)"));
		assertTrue(replayBody.contains("remoteMaterialHandoffReplayQueue.removeFirst()"));
		assertTrue(replayBody.contains("rejectedCreateIntentsAwaitingConfirmation.containsKey(runId)"));
		assertTrue(replayBody.contains("runtime.retryRejectedCreateDispatch(retry.intent())"));
		assertTrue(replayBody.contains("persisted.allowsRetryWithoutMaterial()"));
		assertFalse(replayBody.contains("productionPlans.values().stream()"),
			"the per-tick replay budget must not hide a whole-plan scan");
		assertFalse(replayBody.contains("planRepository.snapshot()"),
			"repository ownership must not hide a whole-plan scan behind the replay budget");
		int callbackStart = source.indexOf(
			"\t\tprivate void intentResolved(ProductionEffect intent, boolean committed) {");
		int callbackEnd = source.indexOf("\n\t\tprivate boolean loadedNativeAgentInputClaims(",
			callbackStart);
		assertTrue(callbackStart >= 0 && callbackEnd > callbackStart);
		String callback = source.substring(callbackStart, callbackEnd);
		int durableSafeRetry = callback.indexOf(
			"safeCreateRetries.persistAndInstall(dispatch, inputAttempt, exact)");
		int recordRejection = callback.indexOf(
			"rejectedCreateIntentsAwaitingConfirmation.put(dispatch.runId(), rejection)");
		int clearSubmitted = callback.indexOf("planDispatch.forgetSubmitted(dispatch.runId())");
		assertTrue(durableSafeRetry >= 0 && recordRejection > durableSafeRetry
			&& clearSubmitted > recordRejection,
			"safe no-material proof must be durable before the confirmation barrier opens");
		assertTrue(callback.contains("!inputAttempt.allowsRetryWithoutMaterial()"));
		assertTrue(callback.contains("&& !authorizedSafeNoMaterial"),
			"a provisional input contract with exact safe-false proof must not suppress replay");
		assertTrue(recordRejection >= 0 && clearSubmitted > recordRejection,
			"commit=false must install the confirmation barrier before exposing the Run to replay");
		assertFalse(callback.contains("enqueueRemoteMaterialHandoffReplay(dispatch.runId())"));
		int commitAgentStart = source.indexOf(
			"\t\tprivate boolean commitAgent(ProductionEffect.CreateDispatch intent");
		int rollbackStart = source.indexOf(
			"\n\t\tprivate Optional<WaitReason> rollbackPreDispatchAdmission(", commitAgentStart);
		assertTrue(commitAgentStart >= 0 && rollbackStart > commitAgentStart);
		String commitAgent = source.substring(commitAgentStart, rollbackStart);
		int gatewayAttempt = commitAgent.indexOf(
			"CreateLogisticsGateway.CommitResult committed = inputGateway");
		int safeBoundary = commitAgent.indexOf(
			"safeCreateRetries.persistAndInstall(intent, persisted, exact.orElseThrow())");
		int rollback = commitAgent.indexOf("rollbackPreDispatchAdmission(intent.runId()", gatewayAttempt);
		assertTrue(gatewayAttempt >= 0 && safeBoundary > gatewayAttempt && rollback > safeBoundary,
			"safe retry authority must be durable before the first rollback write");
		int rollbackEnd = source.indexOf("\n\t\tprivate void fenceReset(", rollbackStart);
		assertTrue(rollbackEnd > rollbackStart);
		String firstAttemptRollback = source.substring(rollbackStart, rollbackEnd);
		assertTrue(firstAttemptRollback.contains("ProductionSafeRetryRecovery.normalizeSteps(output,"),
			"the real first safe-false entry must use the Output-first durable normalizer");
		String inputDispatch = Files.readString(Path.of(
			"src/test/java/com/nobodiiiii/createbiotech/content/biocomputing/application/production/dispatch/ProductionInputDispatch.java"));
		int replayCommitStart = inputDispatch.indexOf("public Optional<Boolean> replay(");
		int replayCommitEnd = inputDispatch.indexOf("\n    private boolean fresh(", replayCommitStart);
		assertTrue(replayCommitStart >= 0 && replayCommitEnd > replayCommitStart);
		String replayCommit = inputDispatch.substring(replayCommitStart, replayCommitEnd);
		int installRepair = replayCommit.indexOf("proof.persist()");
		int normalizeRepair = replayCommit.indexOf("proof.normalize()");
		assertTrue(installRepair >= 0 && normalizeRepair > installRepair,
			"reload must publish exact retry authority before resuming compensation");
		assertTrue(source.contains("public void persist() { safeCreateRetries.persistAndInstall(intent, attempt, repaired); }"));
		assertTrue(source.contains("public Optional<WaitReason> normalize() { return safeRetryRecovery.normalize(intent, existing, repaired.command()); }"));
		assertTrue(source.contains("return inputDispatch.commit(request);"), "actual ordinary effect must use the new owner");
		assertTrue(source.contains("() -> inputDispatch.replay(intent, manifest, source, goal, plan,"),
			"actual remote return must share the same durable replay owner");
		String safeRecovery = Files.readString(Path.of(
			"src/test/java/com/nobodiiiii/createbiotech/content/biocomputing/application/production/dispatch/ProductionSafeRetryRecovery.java"));
		int stepsStart = safeRecovery.indexOf("public static Optional<WaitReason> normalizeSteps(");
		int stepsEnd = safeRecovery.lastIndexOf("\n}");
		assertTrue(stepsStart >= 0 && stepsEnd > stepsStart);
		String compensation = safeRecovery.substring(stepsStart, stepsEnd);
		assertTrue(source.contains("return SafeCreateRetryProof.repair(intent, persisted, input, recoveryState);"),
			"the actual adapter must retain exact typed gateway/Input/Recovery proof authority");
		assertTrue(source.contains("return safeRetryRecovery.authorize(intent, attempt, existing == null"));
		int outputRollback = compensation.indexOf("output.abortBeforeInputDispatch()");
		int recoveryRollback = compensation.indexOf("rollbackRecovery.getAsBoolean()");
		int resetRollback = compensation.indexOf("abandonReset.getAsBoolean()");
		int inputRollback = compensation.indexOf("removeInput.run()");
		assertTrue(outputRollback >= 0 && recoveryRollback > outputRollback
			&& resetRollback > recoveryRollback && inputRollback > resetRollback,
			"Output abort must become durable while Recovery/Reset authority still exists, then "
				+ "Recovery, Reset, and Input may be released");
		int advanceOutputStart = source.indexOf("\t\tprivate void advanceOutputContract(");
		int advanceOutputEnd = source.indexOf("\n\t\tprivate void", advanceOutputStart + 1);
		String advanceOutput = source.substring(advanceOutputStart, advanceOutputEnd);
		assertTrue(advanceOutput.contains("safeCreateRetries.containsKey(before.run().runId())"),
			"daily Output advancement must not reopen a safe-retry compensation boundary");
		int recoveredOutputStart = source.indexOf("\t\tprivate void reconcileRecoveredOutputs(");
		int recoveredOutputEnd = source.indexOf("\n\t\tprivate void", recoveredOutputStart + 1);
		String recoveredOutput = source.substring(recoveredOutputStart, recoveredOutputEnd);
		assertTrue(recoveredOutput.contains("safeCreateRetries.containsKey(state.run().runId())"),
			"reload reconciliation must not reopen a safe-retry compensation boundary");
	}

	@Test
	void everySafeGatewayRollbackCrashBoundaryRepairsTheSameIntentAndCommitsOnce() {
		record Boundary(String name, boolean recoveryPresent, boolean resetActive,
			boolean outputObserving, boolean inputPresent, boolean retryPresent) {}
		List<Boundary> boundaries = List.of(
			new Boundary("gateway false persisted", true, true, true, true, false),
			new Boundary("after retry publication", true, true, true, true, true),
			new Boundary("after Output abort", true, true, false, true, true),
			new Boundary("after Recovery rollback", false, true, false, true, true),
			new Boundary("after Reset rollback", false, false, false, true, true),
			new Boundary("after input-contract removal", false, false, false, false, true));

		for (int index = 0; index < boundaries.size(); index++) {
			Boundary boundary = boundaries.get(index);
			UUID cluster = id(2_000 + index * 20L), goalId = id(2_001 + index * 20L);
			UUID agentId = id(2_002 + index * 20L), intentId = id(2_003 + index * 20L);
			UUID outputEpoch = id(2_004 + index * 20L), epoch = id(2_005 + index * 20L);
			long generation = 9;
			FrozenProductionPlan plan = plan(cluster, goalId, false);
			ProductionRun run = plan.materializeCurrentRun(generation);
			List<AgentInputContract.StageInput> stages = plan.expandedStages(run).stream()
				.map(stage -> new AgentInputContract.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList();
			AgentInputContract input = new AgentInputContract(agentId, 4, outputEpoch, run,
				AgentInputContract.ExecutionShape.STANDARD, stages, 20, ignored -> {});
			assertTrue(input.markCurrentStageReady(generation, input.currentStage().manifest(),
				Set.of(input.currentStage().route()), generation), boundary.name());
			CreateDispatchCommand command = input.beginDispatch(intentId, generation).orElseThrow();
			ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(intentId, cluster,
				generation, new ProductionEffect.TargetFence(epoch, id(2_006 + index * 20L), 0, 0),
				id(2_007 + index * 20L), run.runId(), command.route(),
				command.manifest().entrySet().stream().map(entry ->
					new OrderedIngredient(entry.getKey(), entry.getValue())).toList());

			AgentPendingRunQueue queue = AgentPendingRunQueue.createServerGlobal(config(),
				new TestPriorityLeaseAllocator());
			AtomicReference<RecoveryLedger.State> recoveryState = new AtomicReference<>();
			RecoveryLedger recovery = new RecoveryLedger(config(), new RecoveryLedger.StateStore() {
				@Override public Optional<RecoveryLedger.State> load() {
					return Optional.ofNullable(recoveryState.get());
				}
				@Override public void persist(RecoveryLedger.State next) { recoveryState.set(next); }
			});
			ProductionRequestTicket ticket = new ProductionRequestTicket(intentId, run.runId(),
				generation, cluster, agentId, command.route(), run.patternId(), run.inputMode(),
				run.runMultiplier(), queue.nextSequence(), 0, generation);
			RemoteRequestKey key = new RemoteRequestKey(intentId, run.runId(), generation,
				generation, command.agentResetGeneration());
			AgentAddressRegistration registration = new AgentAddressRegistration(command.route(),
				agentId, cluster, command.agentResetGeneration(), CommunicationFrequency.of("1"),
				AgentAddressRegistration.LoadState.LOADED,
				AgentAddressRegistration.Reachability.REACHABLE);
			AgentProcess process = testAgentProcess(agentId, cluster,
				command.agentResetGeneration(), run.inputRoutes(), run.returnWarehouseRoute());
			assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
				recovery.offer(new AgentProtocolMessage.Offer(key, ticket), queue, registration)
					.disposition(), boundary.name());
			assertTrue(recovery.reserve(key, queue, process), boundary.name());
			if (!boundary.recoveryPresent())
				assertTrue(recovery.rollbackAdmission(key, queue, process), boundary.name());

			AtomicReference<AgentResetService.State> resetState = new AtomicReference<>();
			AgentResetService.StateStore resetStore = new AgentResetService.StateStore() {
				@Override public Optional<AgentResetService.State> load(UUID ignored) {
					return Optional.ofNullable(resetState.get());
				}
				@Override public boolean compareAndSet(UUID ignored, long expectedRevision,
					AgentResetService.State next) {
					AgentResetService.State current = resetState.get();
					if (current == null ? expectedRevision != -1
						: current.revision() != expectedRevision) return false;
					resetState.set(next); return true;
				}
			};
			AgentResetService reset = new AgentResetService(config(), agentId, cluster,
				command.agentResetGeneration(), key, false, resetStore);
			if (!boundary.resetActive()) assertTrue(reset.abandonFutureContract(key), boundary.name());

			ProductionOutputPolicy outputPolicy = new ProductionOutputPolicy(run.outputPolicyGeneration(),
				AgentEndpointState.OutputMode.WAREHOUSE_ALL, AgentCompletionMode.QUIET,
				Optional.empty(), run.returnWarehouseRoute(), Set.of(run.returnWarehouseRoute()),
				Map.of(), Set.of(), Optional.empty(), true, false, 4, 20, 3);
			AtomicReference<ProductionOutputContract.Snapshot> outputState = new AtomicReference<>();
			ProductionOutputContract.Store outputStore = new ProductionOutputContract.Store() {
				@Override public void persist(ProductionOutputContract.Snapshot next) {
					outputState.set(next);
				}
				@Override public void persistLedger(
					com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger ignored) {}
			};
			ProductionOutputContract output = new ProductionOutputContract(agentId,
				command.agentResetGeneration(), outputEpoch, run, outputPolicy, 0, outputStore);
			if (boundary.outputObserving()) assertTrue(output.beginAtInputReady(
				testOutputCapacity(run, index)).started(), boundary.name());

			FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
			journal.write("recovery/global", recovery.snapshot(), RegistryAccess.EMPTY);
			journal.write("reset/" + agentId, reset.snapshot(), RegistryAccess.EMPTY);
			journal.write("output_contract/" + run.runId(), output.snapshot(), RegistryAccess.EMPTY);
			if (boundary.inputPresent())
				journal.write("input_contract/" + run.runId(), input.snapshot(),
					RegistryAccess.EMPTY);
			AtomicBoolean packagerLoaded = new AtomicBoolean();
			int[] nativeDispatches = { 0 };
			CreateLogisticsGateway gateway = new CreateLogisticsGateway(ignored ->
				new CreateLogisticsGateway.CommitContext(command.agentId(),
					command.agentResetGeneration(), command.fence(), command.route(),
					packagerLoaded.get(), command.sourceInventorySnapshotGeneration(),
					AgentInputPhase.DISPATCH_INTENT, command), ignored -> {
					nativeDispatches[0]++;
					return new CreateLogisticsGateway.NativeDispatchFact(true,
						List.of(new NativeOrderLedger.NativeOrderIdentity(11, Set.of(0))));
				}, () -> true, journal.inputCommitStore(RegistryAccess.EMPTY));
			assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,
				gateway.commit(command).rejection(), boundary.name());
			CreateLogisticsGateway.PersistedCommit rejected = journal.inputCommitStore(
				RegistryAccess.EMPTY).load(intentId).orElseThrow();
			if (boundary.retryPresent())
				ProductionSafeRetryStore.persist(journal, intent, rejected,
					RegistryAccess.EMPTY).orElseThrow();

			FactoryOrchestrationSavedData crashed = reloadJournal(journal);
			Optional<AgentInputContract.State> crashedInput = crashed.read(
				"input_contract/" + run.runId(), AgentInputContract.State.class,
				RegistryAccess.EMPTY);
			RecoveryLedger.State crashedRecovery = crashed.read("recovery/global",
				RecoveryLedger.State.class, RegistryAccess.EMPTY).orElseThrow();
			SafeCreateRetryProof repaired =
				SafeCreateRetryProof.repair(intent,
					crashed.inputCommitStore(RegistryAccess.EMPTY).load(intentId).orElseThrow(),
					crashedInput, crashedRecovery).orElseThrow(() ->
						new AssertionError("unrepaired boundary: " + boundary.name()));
			ProductionSafeRetryStore.persist(crashed, intent,
				crashed.inputCommitStore(RegistryAccess.EMPTY).load(intentId).orElseThrow(),
				RegistryAccess.EMPTY).orElseThrow();

			AtomicReference<RecoveryLedger.State> repairedRecoveryState = new AtomicReference<>(
				crashedRecovery);
			RecoveryLedger repairedRecovery = new RecoveryLedger(config(),
				new RecoveryLedger.StateStore() {
					@Override public Optional<RecoveryLedger.State> load() {
						return Optional.ofNullable(repairedRecoveryState.get());
					}
					@Override public void persist(RecoveryLedger.State next) {
						repairedRecoveryState.set(next);
						crashed.write("recovery/global", next, RegistryAccess.EMPTY);
					}
				});
			AgentPendingRunQueue repairedQueue = AgentPendingRunQueue.createServerGlobal(config(),
				new TestPriorityLeaseAllocator());
			AgentProcess repairedProcess = testAgentProcess(agentId, cluster,
				command.agentResetGeneration(), run.inputRoutes(), run.returnWarehouseRoute());
			if (!repairedRecovery.snapshot().records().isEmpty()) {
				assertEquals(RecoveryLedger.ResourceReconcileResult.RESTORED_RESERVED,
					repairedRecovery.reconcileResources(key, repairedQueue, registration,
						repairedProcess), boundary.name());
			}

			AgentResetService.State crashedReset = crashed.read("reset/" + agentId,
				AgentResetService.State.class, RegistryAccess.EMPTY).orElseThrow();
			AtomicReference<AgentResetService.State> repairedResetState =
				new AtomicReference<>(crashedReset);
			AgentResetService repairedReset = new AgentResetService(config(), agentId, cluster,
				command.agentResetGeneration(), null, false, new AgentResetService.StateStore() {
					@Override public Optional<AgentResetService.State> load(UUID ignored) {
						return Optional.of(repairedResetState.get());
					}
					@Override public boolean compareAndSet(UUID ignored, long expectedRevision,
						AgentResetService.State next) {
						if (repairedResetState.get().revision() != expectedRevision) return false;
						repairedResetState.set(next);
						crashed.write("reset/" + agentId, next, RegistryAccess.EMPTY);
						return true;
					}
				});

			ProductionOutputContract.Snapshot crashedOutput = crashed.read("output_contract/"
				+ run.runId(), ProductionOutputContract.Snapshot.class, RegistryAccess.EMPTY).orElseThrow();
			AtomicReference<ProductionOutputContract.Snapshot> repairedOutputState =
				new AtomicReference<>(crashedOutput);
			ProductionOutputContract repairedOutput = ProductionOutputContract.restore(crashedOutput,
				outputPolicy, Optional.empty(), new ProductionOutputContract.Store() {
					@Override public void persist(ProductionOutputContract.Snapshot next) {
						repairedOutputState.set(next);
						crashed.write("output_contract/" + run.runId(), next,
							RegistryAccess.EMPTY);
					}
					@Override public void persistLedger(
						com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger ignored) {}
				});
			assertFalse(FactoryOrchestrationServer.prepareOutputObservation(repairedOutput,
				testOutputCapacity(run, 500 + index), true), boundary.name());
			assertEquals(crashedOutput.milestone(), repairedOutput.milestone(),
				"safe retry must hold the reloaded Output at its crash milestone: " + boundary.name());
			Optional<WaitReason> normalizedResult = ProductionSafeRetryRecovery
				.normalizeSteps(repairedOutput,
					() -> repairedRecovery.snapshot().records().isEmpty()
						|| repairedRecovery.rollbackAdmission(key, repairedQueue, repairedProcess),
					() -> repairedReset.snapshot().activeKey().isEmpty()
						|| repairedReset.abandonFutureContract(key),
					() -> crashed.remove("input_contract/" + run.runId()),
					() -> { throw new AssertionError("safe boundary lost authority: "
						+ boundary.name()); });
			assertTrue(normalizedResult.isEmpty(), boundary.name());
			assertEquals(intent, repaired.intent(), boundary.name());

			FactoryOrchestrationSavedData normalized = reloadJournal(crashed);
			assertTrue(normalized.read("recovery/global", RecoveryLedger.State.class,
				RegistryAccess.EMPTY).orElseThrow().records().isEmpty(), boundary.name());
			assertTrue(normalized.read("reset/" + agentId, AgentResetService.State.class,
				RegistryAccess.EMPTY).orElseThrow().activeKey().isEmpty(), boundary.name());
			assertEquals(ProductionOutputContract.Milestone.WAITING_INPUT_READY,
				normalized.read("output_contract/" + run.runId(), ProductionOutputContract.Snapshot.class,
					RegistryAccess.EMPTY).orElseThrow().milestone(), boundary.name());
			packagerLoaded.set(true);
			CreateLogisticsGateway recovered = new CreateLogisticsGateway(ignored ->
				new CreateLogisticsGateway.CommitContext(command.agentId(),
					command.agentResetGeneration(), command.fence(), command.route(), true,
					command.sourceInventorySnapshotGeneration(), AgentInputPhase.DISPATCH_INTENT,
					command), ignored -> {
					nativeDispatches[0]++;
					return new CreateLogisticsGateway.NativeDispatchFact(true,
						List.of(new NativeOrderLedger.NativeOrderIdentity(11, Set.of(0))));
				}, () -> true, normalized.inputCommitStore(RegistryAccess.EMPTY));
			assertTrue(recovered.commit(command).committed(), boundary.name());
			assertTrue(recovered.commit(command).committed(), boundary.name());
			assertEquals(1, nativeDispatches[0], boundary.name());
			normalized.writeBatchAndRemoveActiveRunArtifacts(Map.of(), run.runId(),
				RegistryAccess.EMPTY);
			FactoryOrchestrationSavedData retired = reloadJournal(normalized);
			assertTrue(retired.read(ProductionSafeRetryStore.JOURNAL_PREFIX
				+ run.runId(), SafeCreateRetryProof.class,
				RegistryAccess.EMPTY).isEmpty(), boundary.name());
			assertTrue(retired.inputCommitStore(RegistryAccess.EMPTY).load(intentId)
				.orElseThrow().provesMaterialCommit(), boundary.name());
			assertTrue(SafeCreateRetryProof.repair(intent,
				retired.inputCommitStore(RegistryAccess.EMPTY).load(intentId).orElseThrow(),
				Optional.empty(), new RecoveryLedger.State(Map.of(), Map.of(), Map.of(), Map.of()))
				.isEmpty(), "durable success must remain fail-closed: " + boundary.name());
			assertTrue(SafeCreateRetryProof.repair(intent,
				CreateLogisticsGateway.PersistedCommit.attempting(command), Optional.of(input.snapshot()),
				recovery.snapshot()).isEmpty(),
				"ATTEMPTING must remain fail-closed: " + boundary.name());
		}
	}

	@Test
	void lateOutputOrSignalBeforeSafeAbortRetainsReloadableRecoveryAndResetAuthority() {
		for (boolean signal : List.of(false, true)) {
			long offset = signal ? 80 : 0;
			UUID cluster = id(2_300 + offset), goalId = id(2_301 + offset);
			UUID agentId = id(2_302 + offset), intentId = id(2_303 + offset);
			UUID outputEpoch = id(2_304 + offset), epoch = id(2_305 + offset);
			long generation = 11;
			FrozenProductionPlan plan = plan(cluster, goalId, false);
			ProductionRun run = plan.materializeCurrentRun(generation);
			List<AgentInputContract.StageInput> stages = plan.expandedStages(run).stream()
				.map(stage -> new AgentInputContract.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList();
			AgentInputContract input = new AgentInputContract(agentId, 6, outputEpoch, run,
				AgentInputContract.ExecutionShape.STANDARD, stages, 20, ignored -> {});
			assertTrue(input.markCurrentStageReady(generation, input.currentStage().manifest(),
				Set.of(input.currentStage().route()), generation));
			CreateDispatchCommand command = input.beginDispatch(intentId, generation).orElseThrow();
			ProductionEffect.CreateDispatch intent = new ProductionEffect.CreateDispatch(intentId, cluster,
				generation, new ProductionEffect.TargetFence(epoch, id(2_306 + offset), 0, 0),
				id(2_307 + offset), run.runId(), command.route(), command.manifest().entrySet()
					.stream().map(entry -> new OrderedIngredient(entry.getKey(),
						entry.getValue())).toList());

			AgentPendingRunQueue queue = AgentPendingRunQueue.createServerGlobal(config(),
				new TestPriorityLeaseAllocator());
			AtomicReference<RecoveryLedger.State> recoveryState = new AtomicReference<>();
			RecoveryLedger recovery = new RecoveryLedger(config(), new RecoveryLedger.StateStore() {
				@Override public Optional<RecoveryLedger.State> load() {
					return Optional.ofNullable(recoveryState.get());
				}
				@Override public void persist(RecoveryLedger.State next) { recoveryState.set(next); }
			});
			ProductionRequestTicket ticket = new ProductionRequestTicket(intentId, run.runId(),
				generation, cluster, agentId, command.route(), run.patternId(), run.inputMode(),
				run.runMultiplier(), queue.nextSequence(), 0, generation);
			RemoteRequestKey key = new RemoteRequestKey(intentId, run.runId(), generation,
				generation, command.agentResetGeneration());
			AgentAddressRegistration registration = new AgentAddressRegistration(command.route(),
				agentId, cluster, command.agentResetGeneration(), CommunicationFrequency.of("1"),
				AgentAddressRegistration.LoadState.LOADED,
				AgentAddressRegistration.Reachability.REACHABLE);
			AgentProcess process = testAgentProcess(agentId, cluster,
				command.agentResetGeneration(), run.inputRoutes(), run.returnWarehouseRoute());
			assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
				recovery.offer(new AgentProtocolMessage.Offer(key, ticket), queue, registration)
					.disposition());
			assertTrue(recovery.reserve(key, queue, process));

			AtomicReference<AgentResetService.State> resetState = new AtomicReference<>();
			AgentResetService reset = new AgentResetService(config(), agentId, cluster,
				command.agentResetGeneration(), key, false, new AgentResetService.StateStore() {
					@Override public Optional<AgentResetService.State> load(UUID ignored) {
						return Optional.ofNullable(resetState.get());
					}
					@Override public boolean compareAndSet(UUID ignored, long expectedRevision,
						AgentResetService.State next) {
						AgentResetService.State current = resetState.get();
						if (current == null ? expectedRevision != -1
							: current.revision() != expectedRevision) return false;
						resetState.set(next);
						return true;
					}
				});
			Optional<AgentSignalSelector> selector = signal
				? Optional.of(new AgentSignalSelector(new SpaceAddress(Level.OVERWORLD, null,
					new BlockPos(8, 70, 8)), Direction.UP, 1)) : Optional.empty();
			ProductionOutputPolicy outputPolicy = new ProductionOutputPolicy(run.outputPolicyGeneration(),
				AgentEndpointState.OutputMode.WAREHOUSE_ALL,
				signal ? AgentCompletionMode.SIGNAL : AgentCompletionMode.QUIET, selector,
				run.returnWarehouseRoute(), Set.of(run.returnWarehouseRoute()), Map.of(), Set.of(),
				Optional.empty(), true, false, 4, 20, 3);
			ProductionOutputContract output = new ProductionOutputContract(agentId,
				command.agentResetGeneration(), outputEpoch, run, outputPolicy, 0, new ProductionOutputContract.Store() {
					@Override public void persist(ProductionOutputContract.Snapshot ignored) {}
					@Override public void persistLedger(
						com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger ignored) {}
				});
			assertTrue(output.beginAtInputReady(testOutputCapacity(run, (int) offset + 90)).started());
			if (signal) assertTrue(output.publishSignal(run.fence()));
			else assertEquals(ProductionOutputContract.OutputObservation.ATTRIBUTED,
				output.observeOutput(new ProductionOutputContract.OutputEvent(id(2_308 + offset),
					outputEpoch, run.returnWarehouseRoute(), run.manifest().keySet().iterator().next(), 1)));

			Optional<WaitReason> result = ProductionSafeRetryRecovery
				.normalizeSteps(output,
					() -> { throw new AssertionError("Recovery authority released before Output proof"); },
					() -> { throw new AssertionError("Reset authority released before Output proof"); },
					() -> { throw new AssertionError("Input removed despite late Output/SIGNAL"); },
					() -> {
						input.stopAfterIrreversibleBoundary(run.fence());
						assertTrue(reset.requireReset(key));
					});
			assertEquals(Optional.of(WaitReason.RESET_REQUIRED), result);
			assertTrue(recovery.snapshot().records().containsKey(key));
			assertEquals(Optional.of(key), reset.snapshot().activeKey());
			assertTrue(reset.snapshot().resetRequired());
			assertEquals(AgentInputPhase.RESET_REQUIRED, input.snapshot().phase());
			assertTrue(SafeCreateRetryProof.repair(intent,
				CreateLogisticsGateway.PersistedCommit.completed(command,
					CreateLogisticsGateway.CommitResult.rejected(command,
						CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED)),
				Optional.of(input.snapshot()), recovery.snapshot()).isEmpty(),
				"late Output/SIGNAL authority must never silently become retryable");

			RecoveryLedger.State reloadedRecovery = CurrentSchemaStateNbt.load(
				CurrentSchemaStateNbt.save(recovery.snapshot(), RegistryAccess.EMPTY),
				RegistryAccess.EMPTY, RecoveryLedger.State.class).orElseThrow();
			AgentResetService.State reloadedReset = CurrentSchemaStateNbt.load(
				CurrentSchemaStateNbt.save(reset.snapshot(), RegistryAccess.EMPTY),
				RegistryAccess.EMPTY, AgentResetService.State.class).orElseThrow();
			assertTrue(reloadedRecovery.records().containsKey(key));
			assertEquals(Optional.of(key), reloadedReset.activeKey());
			assertTrue(reloadedReset.resetRequired());
		}
	}

	@Test
	void cancelInRemoteReceiptBeforeContractPersistsDrainAcrossReloadAndFencesLateInput() {
		UUID cluster = id(1_010), goalId = id(1_011);
		FrozenProductionPlan base = sequencedPlan(cluster, goalId);
		ProductionRun run = base.runs().currentRun();
		RadioEndpoint source = new RadioEndpoint(cluster, id(1_012),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)), 3, CommunicationFrequency.of("23"), 1,
			Set.of());
		RadioEndpoint receiver = new RadioEndpoint(id(1_013), id(1_014),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(8, 64, 0)), 4, CommunicationFrequency.of("23"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, id(1_015), 0, 9,
			base.patternId(), 2, run.runId(), run.fence(), run.inputMode(),
			run.runMultiplier(), base.stages().stream().map(stage ->
				new RemoteExpansionRequest.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList(), run.manifest());
		RemoteProcessLease lease = new RemoteProcessLease(id(1_016), goalId,
			request.parentProcessId(), cluster, receiver.clusterId(), source.generation(),
			receiver.generation(), ProductionCapability.ALCHEMIST, request,
			request.parentStateGeneration());
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
		RemoteExpansionResult result = RemoteExpansionResult.compute(request, revision);
		RadioEnvelope offer = RadioEnvelope.create(id(1_017), source, receiver, CommunicationFrequency.of("23"),
			new ProcessProtocolMessage.LeaseOffer(lease));
		RadioEnvelope revised = RadioEnvelope.create(id(1_018), receiver, source, CommunicationFrequency.of("23"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), lease.revisionFence(), revision));
		RadioEnvelope acknowledged = RadioEnvelope.create(id(1_019), source, receiver, CommunicationFrequency.of("23"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), lease.revisionFence()));
		RadioEnvelope completed = RadioEnvelope.create(id(1_020), receiver, source, CommunicationFrequency.of("23"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), lease.revisionFence(), result));
		RadioRouter router = new RadioRouter(16, 48, 32);
		assertTrue(router.register(source)); assertTrue(router.register(receiver));
		for (RadioEnvelope envelope : List.of(offer, revised, acknowledged, completed)) {
			router.submit(envelope, List.of());
			assertEquals(1, router.tick(envelope.messageId().getLeastSignificantBits())
				.deliveries().size());
		}
		RadioRouter.PreparedMaterialAuthorization authorization = router
			.prepareMaterialIntentAuthorization(receiver.key(), lease.leaseId(),
				lease.revisionFence()).orElseThrow();
		FrozenProductionPlan receipt = base.installRemoteRevision(request, revision)
			.installRemoteResult(result).markDispatchQueued();
		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			new RemoteExpansionProtocol.RemoteComputationExchange(goalId, run.runId(), lease,
				lease.revisionFence(), offer, Optional.of(revised), Optional.of(acknowledged),
				Optional.of(completed), List.of(), List.of(),
				RemoteExpansionProtocol.RemoteComputationPhase.COMPLETED);
		RemoteExpansionProtocol.RemoteComputationJob job =
			new RemoteExpansionProtocol.RemoteComputationJob(run.runId(), lease,
				receiver.memberId(), 0, id(1_026),
				RemoteExpansionProtocol.RemoteComputationJobPhase.COMPLETED,
				Optional.of(revision), Optional.of(result));
		RemoteExpansionProtocol.RemoteComputationLifecycleState activeLifecycle =
			new RemoteExpansionProtocol.RemoteComputationLifecycleState(
				Map.of(run.runId(), exchange), Map.of(run.runId(), job), Map.of());
		FactoryOrchestrationSavedData handoffJournal = new FactoryOrchestrationSavedData();
		handoffJournal.write(FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			activeLifecycle, RegistryAccess.EMPTY);
		AtomicReference<FrozenProductionPlan> installedPlan = new AtomicReference<>(base);
		RemoteExpansionPublication.publishRemoteMaterialHandoff((durablePlan, durableState) ->
			handoffJournal.writeBatch(Map.of("production_plan/" + durablePlan.goalId(), durablePlan,
				FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY, durableState),
				RegistryAccess.EMPTY),
			receipt, exchange, authorization, installedPlan::set,
			router::installPersistedLeaseProtocolState);
		FactoryOrchestrationSavedData afterHandoff = reloadJournal(handoffJournal);
		FrozenProductionPlan handedOff = afterHandoff.read("production_plan/" + goalId,
			FrozenProductionPlan.class, RegistryAccess.EMPTY).orElseThrow();
		RemoteExpansionProtocol.RemoteComputationExchange reloadedExchange = afterHandoff.read(
			FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			RemoteExpansionProtocol.RemoteComputationLifecycleState.class,
			RegistryAccess.EMPTY).orElseThrow().exchanges().get(run.runId());
		RadioRouter handoffRouter = new RadioRouter(16, 48, 32);
		assertTrue(handoffRouter.register(source)); assertTrue(handoffRouter.register(receiver));
		handoffRouter.installPersistedLeaseProtocolState(afterHandoff.read(
			FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY,
			RadioRouter.LeaseProtocolState.class, RegistryAccess.EMPTY).orElseThrow());
		installedPlan.set(handedOff);

		long[] cancellationWrites = { 0 };
		assertTrue(RemoteExpansionPublication.publishRemoteMaterialCancellation(cancelled -> {
			cancellationWrites[0]++;
			afterHandoff.write("production_plan/" + cancelled.goalId(), cancelled,
				RegistryAccess.EMPTY);
		}, installedPlan.get(), reloadedExchange, handoffRouter, installedPlan::set),
			"the first Cancel must be accepted in the durable receipt-before-contract window");
		FrozenProductionPlan cancelled = installedPlan.get();
		assertTrue(cancelled.cancelAfterDrain());
		assertEquals(1, cancellationWrites[0]);
		assertTrue(RemoteExpansionPublication.publishRemoteMaterialCancellation(ignored ->
			cancellationWrites[0]++, cancelled, reloadedExchange, handoffRouter, installedPlan::set),
			"a replayed Cancel is accepted without a second durable mutation");
		assertEquals(1, cancellationWrites[0]);
		assertTrue(handoffRouter.materialIntentAuthorized(receiver.key(), lease.leaseId(),
			lease.revisionFence()));
		assertFalse(handoffRouter.canCommitMaterialIntent(receiver.key(), lease.leaseId(),
			lease.revisionFence()), "Cancel must neither retire nor regrant remote material");

		FactoryOrchestrationSavedData afterCancel = reloadJournal(afterHandoff);
		FrozenProductionPlan reloaded = afterCancel.read("production_plan/" + goalId,
			FrozenProductionPlan.class, RegistryAccess.EMPTY).orElseThrow();
		assertTrue(reloaded.cancelAfterDrain());
		ProductionResourceProfile profile = ProductionResourceProfile.forProfession(ProductionProfession.NITWIT, 1, 0)
			.orElseThrow();
		GoalRecord sourceGoal = new GoalRecord(goalId, cluster,
			new GoalSubmission(goalId, base.patternId(), 1, InputMode.EXACT), base.deliveryRoute(),
			new PolicySnapshot(0, 0, false), GoalState.RUNNABLE, null);
		ProcessRecord parent = new ProcessRecord(request.parentProcessId(), goalId,
			source.memberId(), 0, profile, ProcessKind.GOAL, ProcessState.RUNNABLE, null, 9,
			Map.of());
		ClusterEngineState empty = ClusterEngineState.empty(cluster, id(1_021));
		ClusterEngineState sourceState = new ClusterEngineState(cluster, empty.epochId(), 9,
			1, 0, 0, Map.of(goalId, sourceGoal), Map.of(goalId, 0L),
			Map.of(goalId, ProcessKind.GOAL), Map.of(parent.processId(), parent),
			List.of(new ClusterEngineState.MemberSnapshot(source.memberId(), profile, true, 0, 0)),
			Map.of(), Set.of(), 0, null);
		AtomicReference<ClusterEngineState> cancelledRuntimeState = new AtomicReference<>();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(config())) {
			runtime.start(); runtime.restoreSnapshot(sourceState);
			assertTrue(FactoryOrchestrationServer.reconcileCancellationGoalFence(runtime, reloaded));
			ClusterEngineState waiting = runtime.snapshot(cluster).orElseThrow();
			assertEquals(GoalState.WAITING, waiting.goals().get(goalId).state());
			assertEquals(WaitReason.RESET_REQUIRED, waiting.goals().get(goalId).waitReason());
			long waitingGeneration = waiting.generation();
			assertTrue(FactoryOrchestrationServer.reconcileCancellationGoalFence(runtime, reloaded));
			assertEquals(waitingGeneration, runtime.snapshot(cluster).orElseThrow().generation(),
				"reload reconciliation of the same Cancel must be idempotent");
			assertFalse(com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal.ProductionGoalSettlement.shouldCreditGoalStock(reloaded,
				waiting.goals().get(goalId)), "cancelled remote material never credits the Goal");
			cancelledRuntimeState.set(waiting);
		}

		RadioRouter cancelledRouter = new RadioRouter(16, 48, 32);
		assertTrue(cancelledRouter.register(source)); assertTrue(cancelledRouter.register(receiver));
		cancelledRouter.installPersistedLeaseProtocolState(afterCancel.read(
			FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY,
			RadioRouter.LeaseProtocolState.class, RegistryAccess.EMPTY).orElseThrow());
		AtomicReference<ClusterEngineState> pendingRuntimeState = new AtomicReference<>();
		try (FactoryRuntimeService restarted = new FactoryRuntimeService(config())) {
			restarted.start(); restarted.restoreSnapshot(RuntimeNbt.load(
				RuntimeNbt.save(cancelledRuntimeState.get())).orElseThrow());
			assertFalse(FactoryOrchestrationServer.replayRemoteMaterialHandoffDispatch(restarted,
				reloaded, reloadedExchange, cancelledRouter, RegistryAccess.EMPTY, () -> false,
				ignored -> false), "mailbox rejection must leave the receipt retryable");
			assertTrue(restarted.snapshot(cluster).orElseThrow().pendingIntents().isEmpty());
			assertTrue(FactoryOrchestrationServer.replayRemoteMaterialHandoffDispatch(restarted,
				reloaded, reloadedExchange, cancelledRouter, RegistryAccess.EMPTY, () -> false,
				restarted::publishFact));
			pendingRuntimeState.set(waitForPendingCreateIntent(restarted, cluster, run.runId()));
			assertEquals(1, pendingRuntimeState.get().pendingIntents().size());
		}

		AtomicReference<ProductionEffect.CreateDispatch> replayedIntent = new AtomicReference<>();
		AtomicReference<ClusterEngineState> rejectedRuntimeState = new AtomicReference<>();
		AtomicBoolean packagerLoaded = new AtomicBoolean();
		int[] nativeDispatches = { 0 };
		AtomicReference<CreateDispatchCommand> rejectedCommand = new AtomicReference<>();
		try (FactoryRuntimeService restartedAgain = new FactoryRuntimeService(config())) {
			restartedAgain.start(); restartedAgain.restoreSnapshot(RuntimeNbt.load(
				RuntimeNbt.save(pendingRuntimeState.get())).orElseThrow());
			AtomicBoolean newMailboxFactPublished = new AtomicBoolean();
			assertTrue(FactoryOrchestrationServer.replayRemoteMaterialHandoffDispatch(restartedAgain,
				reloaded, reloadedExchange, cancelledRouter, RegistryAccess.EMPTY,
				() -> true, ignored -> {
					newMailboxFactPublished.set(true); return false;
				}));
			assertFalse(newMailboxFactPublished.get(),
				"restart must redeliver the exact pending intent, not publish another command");
			replayedIntent.set(waitForCreateDispatch(restartedAgain, cluster, run.runId()));
			assertTrue(FactoryOrchestrationServer.replayRemoteMaterialHandoffDispatch(restartedAgain,
				reloaded, reloadedExchange, cancelledRouter, RegistryAccess.EMPTY,
				() -> true, ignored -> {
					newMailboxFactPublished.set(true); return false;
				}));
			assertTrue(restartedAgain.drainIntents(cluster, 10_000).isEmpty(),
				"one restart can redeliver the pending Create intent only once");
			FactoryOrchestrationServer.PendingCreateRejection rejection =
				FactoryOrchestrationServer.PendingCreateRejection.from(replayedIntent.get());
			Map<StackKey, Long> replayManifest = DispatchManifest.decode(
				replayedIntent.get().ingredients());
			CreateDispatchCommand command = new CreateDispatchCommand(
				replayedIntent.get().intentId(), id(1_022), 4,
				replayedIntent.get().generation(), run.runId(),
				id(1_023), 0, replayedIntent.get().route(), replayedIntent.get().generation(),
				replayManifest);
			rejectedCommand.set(command);
			CreateLogisticsGateway rejectedGateway = new CreateLogisticsGateway(ignored ->
				new CreateLogisticsGateway.CommitContext(command.agentId(),
					command.agentResetGeneration(), command.fence(), command.route(),
					packagerLoaded.get(), command.sourceInventorySnapshotGeneration(),
					AgentInputPhase.DISPATCH_INTENT, command), ignoredRequest -> {
					nativeDispatches[0]++;
					return new CreateLogisticsGateway.NativeDispatchFact(true,
						List.of(new NativeOrderLedger.NativeOrderIdentity(7, Set.of(0))));
				}, () -> true, afterCancel.inputCommitStore(RegistryAccess.EMPTY));
			CreateLogisticsGateway.CommitResult gatewayRejection =
				rejectedGateway.commit(command);
			assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,
				gatewayRejection.rejection());
			assertEquals(0, nativeDispatches[0]);
			CreateLogisticsGateway.PersistedCommit rejectionArtifact = afterCancel
				.inputCommitStore(RegistryAccess.EMPTY).load(command.intentId()).orElseThrow();
			SafeCreateRetryProof safeRetry =
				ProductionSafeRetryStore.persist(afterCancel, replayedIntent.get(),
					rejectionArtifact, RegistryAccess.EMPTY)
					.orElseThrow();
			assertEquals(replayedIntent.get(), safeRetry.intent());
			assertFalse(FactoryOrchestrationServer.pendingCreateRejectionApplied(
				restartedAgain.snapshot(cluster).orElseThrow(), rejection),
				"a retry tick before async failure confirmation must not consume the receipt queue");
			assertTrue(restartedAgain.publishFact(new WorldFact.IntentResult(cluster,
				pendingRuntimeState.get().epochId(), replayedIntent.get().generation(),
				replayedIntent.get().intentId(), false, "transient provider rejection")));
			waitForPendingIntentRetirement(restartedAgain, cluster,
				replayedIntent.get().intentId());
			rejectedRuntimeState.set(restartedAgain.snapshot(cluster).orElseThrow());
			assertTrue(FactoryOrchestrationServer.pendingCreateRejectionApplied(
				rejectedRuntimeState.get(), rejection));
		}

		ProductionEffect.CreateDispatch rejectedIntent = replayedIntent.get();
		FactoryOrchestrationSavedData retryJournal = reloadJournal(afterCancel);
		SafeCreateRetryProof reloadedSafeRetry = retryJournal.read(
			ProductionSafeRetryStore.JOURNAL_PREFIX + run.runId(),
			SafeCreateRetryProof.class,
			RegistryAccess.EMPTY).orElseThrow();
		try (FactoryRuntimeService afterFailureReload = new FactoryRuntimeService(config())) {
			afterFailureReload.start(); afterFailureReload.restoreSnapshot(RuntimeNbt.load(
				RuntimeNbt.save(rejectedRuntimeState.get())).orElseThrow());
			assertTrue(afterFailureReload.retryRejectedCreateDispatch(reloadedSafeRetry.intent()),
				"durable safe rejection must reinstate the exact previously approved intent");
			assertTrue(FactoryOrchestrationServer.replayRemoteMaterialHandoffDispatch(
				afterFailureReload, reloaded, reloadedExchange, cancelledRouter,
				RegistryAccess.EMPTY, () -> false, afterFailureReload::publishFact));
			waitForPendingCreateIntent(afterFailureReload, cluster, run.runId());
			ProductionEffect.CreateDispatch retry = waitForCreateDispatch(afterFailureReload, cluster,
				run.runId());
			assertEquals(rejectedIntent.intentId(), retry.intentId());
			assertEquals(rejectedIntent.generation(), retry.generation());
			assertEquals(rejectedIntent.processId(), retry.processId());
			assertEquals(rejectedIntent.runId(), retry.runId());
			assertEquals(rejectedIntent.route(), retry.route());
			assertEquals(rejectedIntent.ingredients(), retry.ingredients());
			packagerLoaded.set(true);
			CreateDispatchCommand exactCommand = rejectedCommand.get();
			CreateLogisticsGateway recoveredGateway = new CreateLogisticsGateway(ignored ->
				new CreateLogisticsGateway.CommitContext(exactCommand.agentId(),
					exactCommand.agentResetGeneration(), exactCommand.fence(), exactCommand.route(),
					true, exactCommand.sourceInventorySnapshotGeneration(),
					AgentInputPhase.DISPATCH_INTENT, exactCommand), ignoredRequest -> {
					nativeDispatches[0]++;
					return new CreateLogisticsGateway.NativeDispatchFact(true,
						List.of(new NativeOrderLedger.NativeOrderIdentity(7, Set.of(0))));
				}, () -> true, retryJournal.inputCommitStore(RegistryAccess.EMPTY));
			assertTrue(recoveredGateway.commit(exactCommand).committed());
			assertTrue(recoveredGateway.commit(exactCommand).committed());
			assertEquals(1, nativeDispatches[0],
				"safe rejection recovery may create exactly one successful native order");
			assertTrue(retryJournal.inputCommitStore(RegistryAccess.EMPTY)
				.load(exactCommand.intentId()).orElseThrow().provesMaterialCommit());
			assertTrue(afterFailureReload.publishFact(new WorldFact.IntentResult(cluster,
				rejectedRuntimeState.get().epochId(), retry.generation(), retry.intentId(), true,
				"transient cause cleared")));
			waitForPendingIntentRetirement(afterFailureReload, cluster, retry.intentId());
			replayedIntent.set(retry);
		}

		UUID agentId = id(1_022);
		long agentGeneration = 4;
		ProductionRun lateRun = reloaded.materializeCurrentRun(replayedIntent.get().generation());
		List<AgentInputContract.StageInput> stages = reloaded.expandedStages(lateRun).stream()
			.map(stage -> new AgentInputContract.StageInput(stage.index(), stage.route(),
				stage.manifest())).toList();
		FailingInputStateStore inputStore = new FailingInputStateStore();
		AgentInputContract lateInput = new AgentInputContract(agentId, agentGeneration, id(1_023),
			lateRun, AgentInputContract.ExecutionShape.SEQUENCED, stages, 20, inputStore);
		assertTrue(lateInput.markCurrentStageReady(lateRun.fence(),
			lateInput.currentStage().manifest(), Set.copyOf(lateRun.inputRoutes()), 1));
		assertTrue(lateInput.beginDispatch(replayedIntent.get().intentId(),
			lateRun.fence()).isPresent());

		AgentPendingRunQueue queue = AgentPendingRunQueue.createServerGlobal(config(),
			new TestPriorityLeaseAllocator());
		AtomicReference<RecoveryLedger.State> recoveryState = new AtomicReference<>();
		RecoveryLedger.StateStore recoveryStore = new RecoveryLedger.StateStore() {
			@Override public Optional<RecoveryLedger.State> load() {
				return Optional.ofNullable(recoveryState.get());
			}
			@Override public void persist(RecoveryLedger.State next) { recoveryState.set(next); }
		};
		RecoveryLedger recovery = new RecoveryLedger(config(), recoveryStore);
		ProductionRequestTicket ticket = new ProductionRequestTicket(
			replayedIntent.get().intentId(), lateRun.runId(),
			lateRun.fence(), cluster, agentId, lateInput.currentStage().route(), lateRun.patternId(),
			lateRun.inputMode(), lateRun.runMultiplier(), queue.nextSequence(), 0, 9);
		RemoteRequestKey key = new RemoteRequestKey(ticket.requestId(), ticket.runId(),
			ticket.fence(), 9, agentGeneration);
		AgentAddressRegistration registration = new AgentAddressRegistration(ticket.targetRoute(),
			agentId, receiver.clusterId(), agentGeneration, CommunicationFrequency.of("1"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
		assertEquals(RecoveryLedger.OfferDisposition.ACCEPTED,
			recovery.offer(new AgentProtocolMessage.Offer(key, ticket), queue, registration)
				.disposition());
		AgentProcess process = testAgentProcess(agentId, receiver.clusterId(), agentGeneration,
			lateRun.inputRoutes(), lateRun.returnWarehouseRoute());
		assertTrue(recovery.reserve(key, queue, process));
		assertTrue(recovery.recordInput(key, lateInput.snapshot()));
		inputStore.failNext();
		assertThrows(IllegalStateException.class, () ->
			FactoryOrchestrationServer.beginPlanCancellationDrain(reloaded, key, recovery, queue,
				process, lateInput));
		assertFalse(lateInput.cancellationDrain());
		assertEquals(RecoveryLedger.EffectKind.BEGIN_CANCEL_DRAIN,
			recovery.snapshot().pendingEffects().get(key).kind());
		RecoveryLedger reloadedRecovery = new RecoveryLedger(config(), recoveryStore);
		AgentInputContract reloadedInput = AgentInputContract.restore(inputStore.state(), inputStore);
		assertEquals(RecoveryLedger.CancelResult.CANCEL_DRAIN,
			FactoryOrchestrationServer.reconcilePlanCancellationDrain(reloaded, key,
				reloadedRecovery, queue, process, reloadedInput));
		assertTrue(reloadedInput.cancellationDrain());
		assertTrue(reloadedRecovery.snapshot().pendingEffects().isEmpty());

		AgentInputContract.State draining = reloadedInput.snapshot();
		NativeOrderLedger.NativeOrderIdentity nativeOrder =
			new NativeOrderLedger.NativeOrderIdentity(7, Set.of(0));
		NativePackageObservation accepted = new NativePackageObservation(
			reloadedInput.currentStage().route(), true, 7, 0, 0, true, true,
			new NativeOrderLedger.OrderContext(reloadedInput.currentStage().manifest()),
			reloadedInput.currentStage().manifest());
		NativeOrderLedger nativeLedger = new NativeOrderLedger(reloadedInput.currentStage().manifest(),
			List.of(nativeOrder));
		assertEquals(NativeOrderLedger.ObservationResult.ACCEPTED,
			nativeLedger.observe(accepted));
		AgentInputContract drainedInput = AgentInputContract.restore(new AgentInputContract.State(
			draining.agentId(), draining.agentResetGeneration(), draining.outputObservationEpoch(),
			draining.run(), draining.executionShape(), draining.stages(), draining.inputStallTicks(),
			AgentInputPhase.INPUT_ENDED, draining.stageIndex(), OptionalLong.empty(),
			draining.currentCommand(), Optional.of(nativeLedger.snapshot()), 0, false,
			draining.interferenceCount(), true, true, draining.cancellationDrainRevision()),
			ignored -> {});
		assertTrue(drainedInput.advanceStage().isEmpty(),
			"late first-stage input drains but cannot grant another sequenced material stage");

		UUID child = RemoteExpansionRevision.logicalChildId(request);
		RemoteExpansionProtocol.RetiredRemoteComputation retired =
			new RemoteExpansionProtocol.RetiredRemoteComputation(child, goalId, run.runId(),
				lease.leaseId(), lease.revisionFence(),
				RemoteExpansionProtocol.RemoteComputationTerminalOutcome.COMPLETED,
				request.fingerprint(), Optional.of(result.fingerprint()));
		RemoteExpansionProtocol.RemoteComputationLifecycleState retiredLifecycle =
			new RemoteExpansionProtocol.RemoteComputationLifecycleState(Map.of(), Map.of(),
				Map.of(child, retired));
		RadioRouter.CompletedLeaseRetirement routerRetirement = cancelledRouter
			.prepareCompletedLeaseRetirement(lease, result).orElseThrow();
		AtomicReference<RemoteExpansionProtocol.RemoteComputationLifecycleState>
			installedLifecycle = new AtomicReference<>(activeLifecycle);
		RemoteExpansionPublication.publishRemoteComputationRetirement((lifecycle, leases) ->
				retryJournal.writeBatch(Map.of(
				FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY, lifecycle,
				FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY, leases), RegistryAccess.EMPTY),
			retiredLifecycle, routerRetirement, installedLifecycle::set,
			cancelledRouter::completePersistedLeaseRetirement,
			cancelledRouter::retireDeliveredRoute,
			List.of(offer.messageId(), revised.messageId(), acknowledged.messageId(),
				completed.messageId()));
		FactoryOrchestrationSavedData afterRetirement = reloadJournal(retryJournal);
		RemoteExpansionProtocol.RemoteComputationLifecycleState bounded = afterRetirement.read(
			FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			RemoteExpansionProtocol.RemoteComputationLifecycleState.class,
			RegistryAccess.EMPTY).orElseThrow();
		assertTrue(bounded.exchanges().isEmpty());
		assertTrue(bounded.jobs().isEmpty());
		assertEquals(1, bounded.retiredChildren().size());
		RadioRouter retiredRouter = new RadioRouter(16, 48, 32);
		assertTrue(retiredRouter.register(source)); assertTrue(retiredRouter.register(receiver));
		retiredRouter.installPersistedLeaseProtocolState(afterRetirement.read(
			FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY,
			RadioRouter.LeaseProtocolState.class, RegistryAccess.EMPTY).orElseThrow());
		assertEquals(0, retiredRouter.activeLeaseCount());
		assertEquals(1, retiredRouter.retiredLeaseLineageCount());
		assertEquals(0, retiredRouter.activeRouteCount());
	}

	@Test
	void crashBootstrapDemotesRecoveredLoadedLibraryBeforeDirectoryPublication() {
		UUID libraryId = id(150), clusterId = id(151);
		ProductionRecipeCatalog liveCatalog = new ProductionRecipeCatalog(libraryId, 4, 7,
			true, List.of());
		FactoryOrchestrationServer.DurableProductionLibrary persisted =
			new FactoryOrchestrationServer.DurableProductionLibrary(libraryId, clusterId,
				Set.of(id(152)), 5, 9, CommunicationFrequency.of("17"), 3,
				new SpaceAddress(Level.OVERWORLD, null, new BlockPos(4, 5, 6)),
				LibraryRegistration.Reachability.REACHABLE, liveCatalog);

		FactoryOrchestrationServer.DurableProductionLibrary recovered =
			persisted.knownUnloadedAfterBootstrap();

		assertFalse(recovered.catalog().loaded(),
			"only a live physical Core publication may claim LOADED after process restart");
		assertEquals(10, recovered.directoryRevision(),
			"the crash demotion must be a new durable directory revision");
		assertEquals(liveCatalog.patterns(), recovered.catalog().patterns());
		assertEquals(recovered, recovered.knownUnloadedAfterBootstrap(),
			"replaying bootstrap demotion must be idempotent");
	}

	@Test
	void preparedAdmissionRecoveryKeepsTheSelectedSearchUntilOneShotLeaseAdoptionIsDurable()
		throws Exception {
		String source = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/"
			+ "content/factorycluster/runtime/FactoryOrchestrationServer.java"));
		int publishRecoveredLibraries = source.indexOf(
			"for (DurableProductionLibrary library : recoveredLibraries) {");
		int restoreLiveSearches = source.indexOf("productionAdmissions.restoreSearchesAfterLibraryBootstrap()");
		assertTrue(publishRecoveredLibraries >= 0 && restoreLiveSearches > publishRecoveredLibraries,
			"crash-demoted Library publication must precede selected-search restore so it cannot "
				+ "invalidate the restored cursor");
		String owner = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/"
            + "content/biocomputing/application/production/admission/ProductionAdmissions.java"));
        assertFalse(source.contains("private void advancePendingProductionAdmission("));
        assertFalse(source.contains("private void persistPendingProductionAdmission("));
        assertTrue(owner.contains("ledger.values().forEach(admission -> admission.searchId()"),
            "all live phases retain their authoritative cursor during restoration");

		int advanceStart = owner.indexOf(
			"\tprivate void advancePendingProductionAdmission(");
		int prepareStart = owner.indexOf(
			"\n\tprivate Optional<PendingProductionAdmission> preparePendingProductionAdmission(",
			advanceStart);
		String advance = owner.substring(advanceStart, prepareStart);
		int oneShotAdoption = advance.indexOf("runtime.adoptProductionSearchWinner(");
		int durableAdoptionFence = advance.indexOf(
			"admission.goalPublishedWithAdoptedSearchLeases()", oneShotAdoption);
		assertTrue(oneShotAdoption >= 0 && durableAdoptionFence > oneShotAdoption
			&& advance.indexOf("runtime.adoptProductionSearchWinner(", oneShotAdoption + 1) < 0,
			"GOAL_PUBLISHED must not replay adoption after its search cursor was retired");
		assertTrue(advance.contains("goalPublishedWithAdoptedSearchLeases"),
			"the one-shot runtime mutation needs an independently durable admission fence");

		int removeStart = owner.indexOf("\tprivate void removePendingProductionAdmission(");
		int retireStart = owner.indexOf("\n\tprivate boolean retireProductionSearch(", removeStart);
		String remove = owner.substring(removeStart, retireStart);
		assertTrue(remove.indexOf("releaseProductionGoalProcesses")
			< remove.indexOf("ledger.remove"),
			"admission authority must remain durable until adopted process release succeeds");
		int persistStart = owner.indexOf("\tprivate void persistPendingProductionAdmission(");
		String persist = owner.substring(persistStart, removeStart);
		int cleanupPending = persist.indexOf("cleanupFenceForFailure");
		int durableCleanupFence = persist.indexOf("persistence.saveLedger",
			cleanupPending);
		int destructiveSearchCleanup = persist.indexOf("retireProductionSearch(current)",
			cleanupPending);
		int destructiveGoalCleanup = persist.indexOf(
			"runtime.releaseProductionGoalProcesses(current.goalId())", cleanupPending);
		int failureTombstone = persist.indexOf("ledger.fail");
		assertTrue(cleanupPending >= 0 && durableCleanupFence > cleanupPending
			&& destructiveSearchCleanup > durableCleanupFence
			&& destructiveGoalCleanup > durableCleanupFence
			&& failureTombstone > destructiveGoalCleanup,
			"CLEANUP_PENDING must commit before either destructive release and the tombstone must "
				+ "commit only after both releases");
		assertFalse(persist.substring(destructiveSearchCleanup, destructiveGoalCleanup)
			.contains("&&"), "search cleanup must not short-circuit independent Goal cleanup");
		assertFalse(remove.contains("!retireProductionSearch(admission)\n\t\t\t||"),
			"terminal cleanup must independently attempt both release classes");
	}

	@Test
	void selectedSearchAndLocalExpansionKeepCleanupAuthorityUntilEveryProcessReleaseCommits()
		throws Exception {
		String runtime = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/"
			+ "content/factorycluster/orchestration/engine/FactoryRuntimeService.java"));
		int releaseFine = runtime.indexOf(
			"private boolean releaseFinePriorityLeasesExcept(");
		int finalizeWinner = runtime.indexOf("private boolean finalizeProductionSearchWinner(");
		int selectedFence = runtime.indexOf("ProductionSearchStatus.SELECTED", finalizeWinner);
		assertTrue(releaseFine >= 0 && finalizeWinner >= 0 && selectedFence > finalizeWinner);
		String winnerFinalization = runtime.substring(finalizeWinner, selectedFence);
		assertTrue(winnerFinalization.contains(
			"if (!releaseFinePriorityLeasesExcept(session, winner.patternId())) return false;"),
			"SELECTED must wait until every losing fine-priority lease is durably released");

		String owner = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/"
            + "content/biocomputing/application/production/expansion/ProductionLocalExpansion.java"));
        int retireLocal = owner.indexOf("public void retireCompleted()");
        String localRetirement = owner.substring(retireLocal, owner.indexOf("private boolean durableLocalOutputBoundary", retireLocal));
        int releaseProcess = localRetirement.indexOf("releaseRemoteComputationProcess(");
        int removeJob = localRetirement.indexOf("persistence.commit(");
        assertTrue(releaseProcess >= 0 && removeJob > releaseProcess
            && localRetirement.substring(releaseProcess, removeJob).contains("continue"),
            "a completed local job must retain durable cleanup authority until exact process release commits");
	}

	@Test
	void outputContractPrimaryUsesTheExactObservedComponents() {
		ItemStack configured = new ItemStack(Items.GOLD_INGOT);
		configured.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey exact = new StackKey(configured);
		StackKey componentFree = new StackKey(new ItemStack(Items.GOLD_INGOT));
		GoalObservationState evidence =
			new GoalObservationState(id(130), id(131),
				new StockObservationScope.NetworkSummary(id(132), exact, 1), 0, 1, 0, 0,
				Optional.empty());

		assertEquals(Optional.of(exact), FactoryOrchestrationServer.exactRequestedPrimary(
			evidence, Set.of(exact, componentFree)),
			"an item-id reconstruction must not replace the observed exact StackKey");
	}

	private static CreateLogisticsGateway safeRetrySequenceGateway(
		FactoryOrchestrationSavedData journal, CreateDispatchCommand command,
		AtomicBoolean packagerLoaded, AtomicBoolean nativeAccepted, int[] nativeAttempts,
		int[] successfulNativeOrders) {
		return new CreateLogisticsGateway(ignored ->
			new CreateLogisticsGateway.CommitContext(command.agentId(),
				command.agentResetGeneration(), command.fence(), command.route(),
				packagerLoaded.get(), command.sourceInventorySnapshotGeneration(),
				AgentInputPhase.DISPATCH_INTENT, command), ignored -> {
				nativeAttempts[0]++;
				boolean accepted = nativeAccepted.get();
				if (accepted) successfulNativeOrders[0]++;
				return new CreateLogisticsGateway.NativeDispatchFact(accepted, accepted
					? List.of(new NativeOrderLedger.NativeOrderIdentity(91, Set.of(0)))
					: List.of());
			}, () -> true, journal.inputCommitStore(RegistryAccess.EMPTY));
	}

	private static ClusterEngineState waitForPendingCreateIntent(FactoryRuntimeService runtime,
		UUID clusterId, UUID runId) {
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (System.nanoTime() < deadline) {
			ClusterEngineState state = runtime.snapshot(clusterId).orElseThrow();
			if (state.pendingIntents().values().stream().anyMatch(intent ->
				intent instanceof ProductionEffect.CreateDispatch dispatch
					&& dispatch.runId().equals(runId))) return state;
			sleepForTest();
		}
		throw new AssertionError("remote handoff replay never produced a pending Create intent");
	}

	private static ProductionEffect.CreateDispatch waitForCreateDispatch(FactoryRuntimeService runtime,
		UUID clusterId, UUID runId) {
		long deadline = System.nanoTime() + 3_000_000_000L;
		long tick = 1;
		while (System.nanoTime() < deadline) {
			ProductionEffect.CreateDispatch dispatch = runtime.drainIntents(clusterId, tick++).stream()
				.filter(ProductionEffect.CreateDispatch.class::isInstance)
				.map(ProductionEffect.CreateDispatch.class::cast)
				.filter(candidate -> candidate.runId().equals(runId)).findFirst().orElse(null);
			if (dispatch != null) return dispatch;
			sleepForTest();
		}
		throw new AssertionError("pending remote handoff intent was not redelivered");
	}

	private static void waitForPendingIntentRetirement(FactoryRuntimeService runtime,
		UUID clusterId, UUID intentId) {
		long deadline = System.nanoTime() + 3_000_000_000L;
		while (System.nanoTime() < deadline) {
			if (!runtime.snapshot(clusterId).orElseThrow().pendingIntents().containsKey(intentId))
				return;
			sleepForTest();
		}
		throw new AssertionError("confirmed replay intent remained pending");
	}

	private static void sleepForTest() {
		try { Thread.sleep(5); }
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	private static FactoryOrchestrationSavedData reloadJournal(
		FactoryOrchestrationSavedData journal) {
		try {
			CompoundTag root = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
			Method load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load",
				CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
			load.setAccessible(true);
			return (FactoryOrchestrationSavedData) load.invoke(null, root, RegistryAccess.EMPTY);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

	private static FactoryRuntimeConfig config() {
		return new FactoryRuntimeConfig(1, 1_000, 4, 32, 4, 4, 32, 64,
			20, 10, 100, 3, 32, 16);
	}

	private static RemoteExpansionRequest remoteRequest(UUID goalId, UUID processId,
		long parentStateGeneration) {
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		return RemoteExpansionRequest.capture(RemoteExpansionTestRegistries.provider(),
			goalId, processId, 0, parentStateGeneration,
			id(108), 2, id(109), parentStateGeneration, InputMode.EXACT, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(110), "input"), Map.of(ingredient, 1L))),
			Map.of(ingredient, 1L));
	}

	private static FrozenProductionPlan plan(UUID cluster, UUID goalId, boolean cancelAfterDrain) {
		UUID network = id(90), patternId = id(91);
		CreateRouteKey input = new CreateRouteKey(network, "input");
		CreateRouteKey delivery = new CreateRouteKey(network, "warehouse");
		StackKey output = new StackKey(new net.minecraft.world.item.ItemStack(
			net.minecraft.world.item.Items.GOLD_INGOT));
		StackKey ingredient = new StackKey(new net.minecraft.world.item.ItemStack(
			net.minecraft.world.item.Items.IRON_INGOT));
		ProductionRun run = new ProductionRun(id(92), goalId, patternId, 0, 1,
			InputMode.EXACT, List.of(input), delivery, 0, Map.of(ingredient, 1L));
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(
			new RouteSelectionScope(id(94), id(95), output, 0, 0, id(96)), id(93), patternId,
			patternId.toString(), RouteCandidate.ProviderKind.PATTERN, 0,
			RouteCandidate.Availability.AVAILABLE);
		return new FrozenProductionPlan(goalId, cluster, provider, PatternKind.STANDARD,
			output, 1, delivery, List.of(new FrozenProductionPlan.FrozenStage(0, input,
				Map.of(ingredient, 1L), List.of(new OrderedIngredient(ingredient, 1L)))),
			new ProductionRun.RunSequence(run, 1),
			new StockObservationScope.NetworkSummary(network, output, 1), 0, 1, false,
			cancelAfterDrain, Optional.empty());
	}

	private static FrozenProductionPlan sequencedPlan(UUID cluster, UUID goalId) {
		UUID network = id(1_030), patternId = id(1_031);
		CreateRouteKey first = new CreateRouteKey(network, "first-input");
		CreateRouteKey second = new CreateRouteKey(network, "second-input");
		CreateRouteKey delivery = new CreateRouteKey(network, "warehouse");
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT));
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey stone = new StackKey(new ItemStack(Items.STONE));
		Map<StackKey, Long> manifest = Map.of(iron, 1L, stone, 1L);
		ProductionRun run = new ProductionRun(id(1_032), goalId, patternId, 0, 1,
			InputMode.EXACT, List.of(first, second), delivery, 0, manifest);
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(
			new RouteSelectionScope(id(1_033), id(1_034), output, 0, 0, id(1_035)),
			id(1_036), patternId, patternId.toString(), RouteCandidate.ProviderKind.PATTERN, 0,
			RouteCandidate.Availability.AVAILABLE);
		return new FrozenProductionPlan(goalId, cluster, provider, PatternKind.SEQUENCED,
			output, 1, delivery, List.of(
				new FrozenProductionPlan.FrozenStage(0, first, Map.of(iron, 1L),
					List.of(new OrderedIngredient(iron, 1L))),
				new FrozenProductionPlan.FrozenStage(1, second, Map.of(stone, 1L),
					List.of(new OrderedIngredient(stone, 1L)))),
			new ProductionRun.RunSequence(run, 1),
			new StockObservationScope.NetworkSummary(network, output, 1), 0, 1, false, false,
			Optional.empty());
	}

	private static AgentProcess testAgentProcess(UUID agentId, UUID ownerClusterId,
		long generation, List<CreateRouteKey> inputRoutes, CreateRouteKey outputRoute) {
		ArrayList<AgentEndpointState> endpoints = new ArrayList<>();
		for (int index = 0; index < inputRoutes.size(); index++)
			endpoints.add(new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
				new BlockPos(20 + index, 64, 0)), agentId, ownerClusterId, generation,
				AgentEndpointRole.INPUT, inputRoutes.get(index), 0, CommunicationFrequency.of("23"),
				AgentCompletionMode.QUIET, Optional.empty(),
				AgentEndpointState.OutputMode.WAREHOUSE_ALL, 4, outputRoute, true, true));
		endpoints.add(new AgentEndpointState(new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(20 + inputRoutes.size(), 64, 0)),
				agentId, ownerClusterId, generation, AgentEndpointRole.OUTPUT, outputRoute, 0, CommunicationFrequency.of("23"),
				AgentCompletionMode.QUIET, Optional.empty(),
				AgentEndpointState.OutputMode.WAREHOUSE_ALL, 4, outputRoute, true, true));
		AgentFormation formation = AgentFormation.form(endpoints);
		return AgentProcess.form(formation, new AgentProcess.SlotAllocator() {
			private final AgentProcess.ResidentSlot slot = new AgentProcess.ResidentSlot(id(1_037),
				id(1_038), 0, id(1_039), ProcessKind.AGENT, true);
			@Override public Optional<AgentProcess.ResidentSlot> acquire(UUID ignoredAgent,
				UUID ignoredOwner, ProcessKind ignoredKind) { return Optional.of(slot); }
			@Override public boolean stillValid(AgentProcess.ResidentSlot candidate) {
				return slot.equals(candidate);
			}
			@Override public void release(AgentProcess.ResidentSlot ignored) {}
		});
	}

	private static ProductionOutputContract.OutputProcessCapacity testOutputCapacity(
		ProductionRun run, int identity) {
		return new ProductionOutputContract.OutputProcessCapacity() {
			@Override public Optional<ProductionOutputContract.ProcessLease> tryAcquireObservation(
				UUID runId, UUID goalId, CreateRouteKey outputAddress) {
				return run.runId().equals(runId) && run.goalId().equals(goalId)
					? Optional.of(new ProductionOutputContract.ProcessLease(id(4_000L + identity),
						outputAddress)) : Optional.empty();
			}
			@Override public Optional<ProductionOutputContract.ProcessLease> tryAcquireOutputDestination(
				UUID runId, UUID goalId, CreateRouteKey destination) {
				return tryAcquireObservation(runId, goalId, destination);
			}
			@Override public boolean isLeaseActive(ProductionOutputContract.ProcessLease lease,
				ProductionOutputContract.LeasePurpose purpose, UUID runId, UUID goalId) {
				return run.runId().equals(runId) && run.goalId().equals(goalId);
			}
			@Override public void release(ProductionOutputContract.ProcessLease ignored) {}
		};
	}

	private static final class TestPriorityLeaseAllocator
		implements AgentPendingRunQueue.PriorityLeaseAllocator {
		@Override public Optional<AgentPendingRunQueue.PriorityLease> acquire(
			ProductionRequestTicket ticket, UUID agentOwnerClusterId, ProcessKind kind,
			boolean requiresAlchemist) { return Optional.empty(); }
		@Override public boolean replace(AgentPendingRunQueue.PriorityLease lease,
			ProductionRequestTicket expectedTicket, ProductionRequestTicket updatedTicket,
			UUID agentOwnerClusterId) { return false; }
		@Override public void release(AgentPendingRunQueue.PriorityLease lease) {}
	}

	private static final class FailingInputStateStore
		implements AgentInputContract.StateStore {
		private AgentInputContract.State state;
		private boolean failNext;
		void failNext() { failNext = true; }
		AgentInputContract.State state() { return state; }
		@Override public void persist(AgentInputContract.State next) {
			if (failNext) {
				failNext = false;
				throw new IllegalStateException("simulated input cancellation crash");
			}
			state = next;
		}
	}

	private static RemoteProcessLease remoteReceiverLease(long identity, UUID receiverCluster,
		long receiverGeneration) {
		UUID goalId = id(identity), processId = id(identity + 1), sourceCluster = id(identity + 2);
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), goalId, processId, 0, 9,
			id(identity + 3), 2, id(identity + 4), 9, InputMode.EXACT, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(identity + 5), "blocked-receiver"),
				Map.of(ingredient, 1L))), Map.of(ingredient, 1L));
		return new RemoteProcessLease(id(identity + 6), goalId, processId, sourceCluster,
			receiverCluster, 1, receiverGeneration, ProductionCapability.ALCHEMIST, request, 9);
	}

	private static final class OutboxStore implements DurableRadioOutbox.StateStore {
		private DurableRadioOutbox.State state;
		@Override public Optional<DurableRadioOutbox.State> load() {
			return Optional.ofNullable(state);
		}
		@Override public void persist(DurableRadioOutbox.State next) { state = next; }
	}

	private static FactoryPanelAgentAccess.Configuration endpointConfiguration(UUID agent,
		UUID owner, UUID epoch, long generation, long revision, AgentEndpointRole role,
		String address, UUID network) {
		return new FactoryPanelAgentAccess.Configuration(agent, owner, epoch, revision, generation,
			role, address, 0, CommunicationFrequency.of("7"), AgentCompletionMode.TARGET,
			Optional.empty(), AgentEndpointState.OutputMode.WAREHOUSE_ALL, 0,
			new CreateRouteKey(network, "warehouse"));
	}

	private static UUID id(long value) { return new UUID(0, value); }
    private static ProductionLocalExpansion localOwner(FactoryRuntimeService runtime,
        Map<UUID, ProductionLocalExpansion.Job> durableJobs) {
        return new ProductionLocalExpansion(RemoteExpansionTestRegistries.provider(), runtime, 1,
            new ProductionLocalExpansion.Persistence() {
                public Optional<ProductionLocalExpansion.Lifecycle> load() {
                    return Optional.of(new ProductionLocalExpansion.Lifecycle(durableJobs));
                }
                public void commit(ProductionLocalExpansion.Lifecycle lifecycle, Optional<FrozenProductionPlan> plan) {
                    durableJobs.clear(); durableJobs.putAll(lifecycle.jobs());
                }
                public Optional<ProductionOutputContract.Snapshot> output(UUID runId) { return Optional.empty(); }
            }, new ProductionLocalExpansion.Evidence() {
                public FrozenProductionPlan plan(UUID goalId) { return null; }
                public ProductionOutputContract.Snapshot output(UUID runId) { return null; }
                public Set<UUID> remoteChildIds() { return Set.of(); }
                public void publishPlan(FrozenProductionPlan plan) {}
            });
    }

}
