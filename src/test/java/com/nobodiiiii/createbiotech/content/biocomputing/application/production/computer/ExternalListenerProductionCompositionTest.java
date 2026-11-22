package com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.TestComputer.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionRuntimeServices;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationCommandService;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerRuntime;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InstalledNativePackageIngress;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryComputeWorker;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyLifecycle;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyTracker;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetRouteTrace;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServerTestAccess;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryRuntimeLifecycle;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;

import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;

/** Exercises the server composition with real Computer, runtime process records, and Task 5 router. */
class ExternalListenerProductionCompositionTest {
	private static final UUID COMPUTER = id(10);
	private static final UUID STRUCTURE = id(11);
	private static final UUID CLUSTER = id(12);
	private static final CommunicationFrequency PRIMARY = CommunicationFrequency.of("primary");
	private static final CommunicationFrequency EXTERNAL_A = CommunicationFrequency.of("external-a");
	private static final CommunicationFrequency EXTERNAL_B = CommunicationFrequency.of("external-b");
	private static final ProductionResourceProfile TRADER = ProductionResourceProfile.forProfession(
		ProductionProfession.WANDERING_TRADER, 1, 64).orElseThrow();
	private static final FactoryRuntimeConfig CONFIG = new FactoryRuntimeConfig(1, 500, 4,
		32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16);

	@Test
	void frozenProductionPanelSnapshotDoesNotPumpCompletedWorkerStateOrQueues() throws Exception {
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			UUID epoch = id(700);
			runtime.restoreSnapshot(ClusterEngineState.empty(CLUSTER, epoch));
			ClusterEngineState beforeFreeze = runtime.snapshot(CLUSTER).orElseThrow();
			assertTrue(runtime.publishFact(new WorldFact.Signal(CLUSTER, epoch,
				beforeFreeze.generation(), "freeze-panel-read", 1)));
			FactoryComputeWorker worker = worker(runtime);
			awaitWorkerResults(worker);
			int queuedBeforePanelRead = workerResultCount(worker);
			CompoundTag journalBeforePanelRead = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime, journal,
				Set.of())) {
				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				assertTrue(FactoryOrchestrationServer.agentDiagnostics(callback.server(), CLUSTER)
					.isEmpty());
				assertEquals(beforeFreeze, runtime.peekSnapshotForPanelView(CLUSTER).orElseThrow());
				assertEquals(queuedBeforePanelRead, workerResultCount(worker));
				assertEquals(journalBeforePanelRead, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
			}
		}
	}

	@Test
	void retiredPanelSourcesAreNotInstalledAndDoNotPumpTheWorker() throws Exception {
		CallbackServer callback = callbackServer();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			UUID epoch = id(709);
			runtime.restoreSnapshot(ClusterEngineState.empty(CLUSTER, epoch));
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime,
				new FactoryOrchestrationSavedData(), Set.of())) {
				ClusterEngineState captured = runtime.peekSnapshotForPanelView(CLUSTER).orElseThrow();
				assertTrue(runtime.publishFact(new WorldFact.Signal(CLUSTER, epoch,
					captured.generation(), "complete-between-outer-and-source-capture", 1)));
				FactoryComputeWorker worker = worker(runtime);
				awaitWorkerResults(worker);
				int pending = workerResultCount(worker);
				for (String source : List.of("panelCommunicationSnapshotSource", "panelCommunicationNetworkSource")) {
					Field field = FactoryRuntimeService.class.getDeclaredField(source);
					field.setAccessible(true);
					assertNull(field.get(runtime), "A migrated material port cannot publish an old panel directory");
				}
				assertEquals(pending, workerResultCount(worker));
				assertEquals(captured, runtime.peekSnapshotForPanelView(CLUSTER).orElseThrow());
				assertNotEquals(captured.generation(), runtime.snapshot(CLUSTER).orElseThrow().generation(),
					"the queued result still installs at the next outer synchronization boundary");
			}
		}
	}


	@Test
	void frozenPublicSubnetProjectionRejectsBeforePumpingCompletedWorkerState() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			runtime.restoreSnapshot(unoccupiedListenerState(computer.epoch().orElseThrow(), computer.id()));
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime, journal,
				Set.of())) {
				assertTrue(production.observeExternalListener(computer, PRIMARY, address(BlockPos.ZERO)));
				assertEquals(ExternalListenerService.EditResult.APPLIED,
					production.replaceExternalListeners(computer, 0, List.of(EXTERNAL_A)));
				while (runtime.workerInFlight(CLUSTER)) Thread.onSpinWait();
				SubnetBoundaryBinding binding = production.radioAuthorityState().boundaries()
					.get(externalCluster(computer.id(), 0)).binding();
				ClusterEngineState commandState = runtime.snapshot(CLUSTER).orElseThrow();
				assertTrue(runtime.publishFact(new WorldFact.Signal(CLUSTER, commandState.epochId(),
					commandState.generation(), "freeze-public-subnet", 1)));
				awaitWorkerResults(worker(runtime));
				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				FrozenRuntimeState before = frozenRuntimeState(runtime, production, journal);

				Optional<?> projection = FactoryOrchestrationServer.publicSubnetProjectionForTest(
					callback.server(), binding.targetHop().key());
				assertEquals(before, frozenRuntimeState(runtime, production, journal),
					"the exact frozen public-subnet seam must not touch engine or authority state");
				assertTrue(projection.isEmpty());
			}
		}
	}

	@Test
	void nativeUnloadUsesTheSameProductionRuntimeForRunningAndFrozenAuthority() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		UUID computerId = computer.id();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		ClusterCommunicationConfiguration configured = seedCommunication(journal, CLUSTER,
			CommunicationFrequency.of("pre-freeze"));
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			runtime.restoreSnapshot(unoccupiedListenerState(computer.epoch().orElseThrow(), computer.id()));
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime, journal,
				Set.of())) {
			assertTrue(production.observeExternalListener(computer, PRIMARY, address(BlockPos.ZERO)));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				production.replaceExternalListeners(computer, 0, List.of(EXTERNAL_A)));
			UUID listenerCluster = externalCluster(computerId, 0);
			assertTrue(production.hasBoundary(listenerCluster));

			computer.owner().setLevel(callback.level());
			FactoryOrchestrationServer.nativeComputerUnloaded(computer);
			awaitNoListenerProcess(runtime);
			assertFalse(production.hasBoundary(listenerCluster));
			assertFalse(journal.read("radio/authority", RadioRouter.AuthorityState.class,
				RegistryAccess.EMPTY).orElseThrow().boundaries().get(listenerCluster).active(),
				"RUNNING native unload must durably retire the callback Runtime's authority");

			computer.owner().setLevel(null);
			assertTrue(production.observeExternalListener(computer, PRIMARY, address(BlockPos.ZERO)));
			assertEquals(ExternalListenerService.EditResult.NO_CHANGE,
				production.replaceExternalListeners(computer, 1, List.of(EXTERNAL_A)));
			CompoundTag authorityBeforeFreeze = CurrentSchemaStateNbt.save(
				journal.read("radio/authority", RadioRouter.AuthorityState.class,
					RegistryAccess.EMPTY).orElseThrow(), RegistryAccess.EMPTY);
			ClusterCommunicationCommandService cachedCommands =
				FactoryOrchestrationServer.communicationCommandService(callback.server()).orElseThrow();

			FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
			assertEquals(FactoryRuntimeLifecycle.Phase.STOPPING_FROZEN,
				FactoryOrchestrationServer.runtimePhaseForTest(callback.server()));
			assertEquals(configured, FactoryOrchestrationServer.communicationConfiguration(
				callback.server(), CLUSTER).orElseThrow());
			assertTrue(FactoryOrchestrationServer.communicationCommandService(callback.server()).isEmpty());
			ClusterCommunicationCommandService.CommandAuthority authority =
				new ClusterCommunicationCommandService.CommandAuthority(CLUSTER, id(800),
					new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, computer.id()), id(801));
			assertEquals(ClusterCommunicationCommandService.ChangeResult.AUTHORITY_OFFLINE,
				cachedCommands.changePrimary(authority, CommunicationFrequency.of("must-not-apply"), 1));
			assertEquals(configured, FactoryOrchestrationServer.communicationConfiguration(
				callback.server(), CLUSTER).orElseThrow());

			computer.owner().setLevel(callback.level());
			FactoryOrchestrationServer.nativeComputerUnloaded(computer);
			CompoundTag authorityAfterFrozenUnload = CurrentSchemaStateNbt.save(
				journal.read("radio/authority", RadioRouter.AuthorityState.class,
					RegistryAccess.EMPTY).orElseThrow(), RegistryAccess.EMPTY);
			assertEquals(authorityBeforeFreeze, authorityAfterFrozenUnload,
				"frozen native unload must retain its production Runtime's durable authority");
			assertTrue(production.hasBoundary(listenerCluster));
			}
		}
	}

	@Test
	void freshProductionCompositionRestoresAndCommitsPersistedExactRetirementOnce()
		throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		ClusterEngineState empty = unoccupiedListenerState(computer.epoch().orElseThrow(), computer.id());
		RadioRouter.AuthorityState activeAuthority;
		ExternalListenerService.RetirementState retirementState;
		try (FactoryRuntimeService firstRuntime = new FactoryRuntimeService(CONFIG)) {
			firstRuntime.start();
			firstRuntime.restoreSnapshot(empty);
			try (FactoryOrchestrationServerTestAccess production = install(callback, firstRuntime,
				journal, Set.of())) {
				assertTrue(production.observeExternalListener(computer, PRIMARY, address(BlockPos.ZERO)));
				assertEquals(ExternalListenerService.EditResult.APPLIED,
					production.replaceExternalListeners(computer, 0, List.of(EXTERNAL_A)));
				activeAuthority = production.radioAuthorityState();
				SubnetBoundaryBinding binding = activeAuthority.boundaries()
					.get(externalCluster(computer.id(), 0)).binding();
				ExternalListenerService.Publication publication =
					new ExternalListenerService.Publication(
						activeAuthority.endpoints().get(binding.sourceHop().key()).current().orElseThrow(),
						activeAuthority.endpoints().get(binding.targetHop().key()).current().orElseThrow(),
						binding);
				retirementState = new ExternalListenerService.RetirementState(List.of(
					new ExternalListenerService.RetirementObligation(computer.listenerOwner(), EXTERNAL_A,
						publication)));
				journal.write("radio/listener_retirements", retirementState, RegistryAccess.EMPTY);
			}
		}

		try (FactoryRuntimeService restoredRuntime = new FactoryRuntimeService(CONFIG)) {
			restoredRuntime.start();
			restoredRuntime.restoreSnapshot(empty);
			try (FactoryOrchestrationServerTestAccess production = install(callback, restoredRuntime,
				journal, Set.of())) {
				assertEquals(retirementState, journal.read("radio/listener_retirements",
					ExternalListenerService.RetirementState.class, RegistryAccess.EMPTY).orElseThrow());
				assertTrue(production.radioAuthorityState().boundaries()
					.get(externalCluster(computer.id(), 0)).active());

				FactoryOrchestrationServer.tick(callback.server());
				RadioRouter.AuthorityState retired = production.radioAuthorityState();
				SubnetBoundaryBinding binding = activeAuthority.boundaries()
					.get(externalCluster(computer.id(), 0)).binding();
				assertEquals(Optional.empty(), retired.endpoints().get(binding.sourceHop().key()).current());
				assertEquals(Optional.empty(), retired.endpoints().get(binding.targetHop().key()).current());
				assertFalse(retired.boundaries().get(binding.sourceClusterId()).active());
				assertEquals(ExternalListenerService.RetirementState.empty(), journal.read(
					"radio/listener_retirements", ExternalListenerService.RetirementState.class,
					RegistryAccess.EMPTY).orElseThrow());
				FactoryOrchestrationServer.tick(callback.server());
				assertEquals(retired, production.radioAuthorityState(),
					"the restored exact retirement must commit at most once");
			}
		}
	}

	@Test
	void frozenProductionOutboxMutationSeamsFailClosedWithoutAdvancingAuthority() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			runtime.restoreSnapshot(unoccupiedListenerState(computer.epoch().orElseThrow(), computer.id()));
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime, journal,
				Set.of())) {
				assertTrue(production.observeExternalListener(computer, PRIMARY, address(BlockPos.ZERO)));
				assertEquals(ExternalListenerService.EditResult.APPLIED,
					production.replaceExternalListeners(computer, 0, List.of(EXTERNAL_A)));
				RadioRouter.AuthorityState authority = production.radioAuthorityState();
				SubnetBoundaryBinding binding = authority.boundaries().get(externalCluster(computer.id(), 0))
					.binding();
				RadioEndpoint source = authority.endpoints().get(binding.sourceHop().key()).current().orElseThrow();
				RadioEndpoint relay = authority.endpoints().get(binding.targetHop().key()).current().orElseThrow();
				RadioEnvelope frozenMark = RadioEnvelope.create(id(725), source, relay, EXTERNAL_A,
					new ProcessProtocolMessage.LeaseOffer(leaseOwnedBy(source, relay, 7_250)));
				RadioEnvelope frozenRetire = RadioEnvelope.create(id(726), source, relay, EXTERNAL_A,
					new ProcessProtocolMessage.LeaseOffer(leaseOwnedBy(source, relay, 7_260)));
				assertTrue(production.offerRadio(frozenMark, List.of()));
				assertTrue(production.offerRadio(frozenRetire, List.of()));
				List<RadioRouter.Delivery> eligible = production.pumpRadioOutbox(1);
				assertEquals(2, eligible.size());
				assertEquals(RadioRouter.RouteStatus.DELIVERED,
					production.authoritySnapshot().outbox().entries().get(frozenMark.messageId()).status());
				assertEquals(RadioRouter.RouteStatus.DELIVERED,
					production.authoritySnapshot().outbox().entries().get(frozenRetire.messageId()).status());
				assertTrue(production.markRadioApplied(frozenRetire.messageId()));
				assertFalse(production.authoritySnapshot().outbox().entries()
					.containsKey(frozenRetire.messageId()));
				assertEquals(2, production.authoritySnapshot().activeRouteCount(),
					"successfully marked route must remain eligible for volatile retirement");
				RadioEnvelope frozenPump = RadioEnvelope.create(id(727), source, relay, EXTERNAL_A,
					new ProcessProtocolMessage.LeaseOffer(leaseOwnedBy(source, relay, 7_270)));
				assertTrue(production.offerRadio(frozenPump, List.of()));
				FactoryOrchestrationServerTestAccess.RuntimeAuthoritySnapshot before =
					production.authoritySnapshot();
				RadioRouter.AuthorityState fullAuthorityBefore = production.radioAuthorityState();

				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				assertFalse(production.offerRadio(RadioEnvelope.create(id(728), source, relay, EXTERNAL_A,
					new ProcessProtocolMessage.LeaseOffer(leaseOwnedBy(source, relay, 7_280))), List.of()));
				assertTrue(production.pumpRadioOutbox(2).isEmpty());
				assertFalse(production.markRadioApplied(frozenMark.messageId()));
				assertEquals(0, production.retireAppliedRadioRoutes());
				assertEquals(before, production.authoritySnapshot(),
					"all frozen outbox mutations must leave the exact production snapshot unchanged");
				assertEquals(fullAuthorityBefore, production.radioAuthorityState());
			}
		}
	}

	@Test
	void frozenTaskControlIngressesRejectEligibleProductionStateWithoutChangingIt()
		throws Exception {
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		UUID runningDropGoal = id(810), frozenDropGoal = id(811);
		journal.write("production_plan/" + runningDropGoal, productionPlan(CLUSTER, runningDropGoal,
			812), RegistryAccess.EMPTY);
		journal.write("production_plan/" + frozenDropGoal, productionPlan(CLUSTER, frozenDropGoal,
			813), RegistryAccess.EMPTY);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime, journal,
				Set.of())) {
				UUID runningSuspend = id(814), frozenSuspend = id(815);
				UUID runningConstrain = id(816), frozenConstrain = id(817);
				UUID runningRestore = id(818), frozenRestore = id(819);
				assertTrue(FactoryOrchestrationServer.suspendNextInputDispatchAtDurableAttempt(
					callback.server(), runningSuspend));
				assertTrue(FactoryOrchestrationServer.dropNextRecoveryAcknowledgement(callback.server(),
					runningDropGoal));
				assertTrue(FactoryOrchestrationServer.constrainRecoveryAdmissionHeadroom(
					callback.server(), runningConstrain, 1));
				assertTrue(FactoryOrchestrationServer.constrainRecoveryAdmissionHeadroom(
					callback.server(), runningRestore, 1));
				assertTrue(FactoryOrchestrationServer.restoreConfiguredRecoveryAdmission(
					callback.server(), runningRestore));
				assertTrue(FactoryOrchestrationServer.constrainRecoveryAdmissionHeadroom(
					callback.server(), frozenRestore, 1));
				var beforeFreeze = production.taskControlState();

				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				assertFalse(FactoryOrchestrationServer.suspendNextInputDispatchAtDurableAttempt(
					callback.server(), frozenSuspend));
				assertFalse(FactoryOrchestrationServer.dropNextRecoveryAcknowledgement(callback.server(),
					frozenDropGoal));
				assertFalse(FactoryOrchestrationServer.constrainRecoveryAdmissionHeadroom(
					callback.server(), frozenConstrain, 1));
				assertFalse(FactoryOrchestrationServer.restoreConfiguredRecoveryAdmission(
					callback.server(), frozenRestore));
				assertEquals(beforeFreeze, production.taskControlState(),
					"each frozen public task-control ingress must preserve complete control state");
			}
		}
	}

	@Test
	void frozenRuntimeRejectsSecondTestBootstrapBeforeItCanReplaceExternalAuthorities()
		throws Exception {
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		try (FactoryRuntimeService originalRuntime = new FactoryRuntimeService(CONFIG);
			FactoryRuntimeService competingRuntime = new FactoryRuntimeService(CONFIG)) {
			originalRuntime.start();
			competingRuntime.start();
			try (FactoryOrchestrationServerTestAccess original = install(callback, originalRuntime,
				journal, Set.of())) {
				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				var controlBefore = original.taskControlState();
				assertTrue(FactoryOrchestrationServer.running(callback.server()));
				assertTrue(original.hasNativePublisher());
				assertEquals(FactoryRuntimeLifecycle.Phase.STOPPING_FROZEN,
					FactoryOrchestrationServer.runtimePhaseForTest(callback.server()));

				assertThrows(IllegalStateException.class, () -> install(callback, competingRuntime,
					new FactoryOrchestrationSavedData(), Set.of()));

				assertEquals(FactoryRuntimeLifecycle.Phase.STOPPING_FROZEN,
					FactoryOrchestrationServer.runtimePhaseForTest(callback.server()));
				assertEquals(controlBefore, original.taskControlState());
				assertTrue(FactoryOrchestrationServer.running(callback.server()),
					"a rejected duplicate bootstrap must leave the frozen Panel binding owned by its Runtime");
				assertTrue(original.hasNativePublisher(),
					"a rejected duplicate bootstrap must leave the native publisher with the frozen Runtime");
				assertEquals(FactoryRuntimeLifecycle.Phase.STOPPING_FROZEN,
					FactoryOrchestrationServer.runtimePhaseForTest(callback.server()),
					"the retained frozen Panel command adapter must remain fail-closed");
				assertTrue(FactoryOrchestrationServer.communicationCommandService(callback.server()).isEmpty());
			}
		}
	}

	@Test
	void failedNativeBindingActivationLeavesNoAuthorityAndAllowsACleanInstall() throws Exception {
		CallbackServer callback = callbackServer();
		try (FactoryRuntimeService failedRuntime = new FactoryRuntimeService(CONFIG)) {
			failedRuntime.start();
			assertThrows(IllegalStateException.class, () ->
				FactoryOrchestrationServerTestAccess.installWithFailingNativeBinding(callback.server(),
					CONFIG, failedRuntime, new FactoryOrchestrationSavedData(), ignored -> Set.of()));
			assertFalse(FactoryOrchestrationServer.running(callback.server()));
			assertFalse(FactoryOrchestrationServerTestAccess.hasNativePublisher(callback.server()));
			assertTrue(FactoryOrchestrationServer.communicationCommandService(callback.server()).isEmpty());
		}
		try (FactoryRuntimeService cleanRuntime = new FactoryRuntimeService(CONFIG)) {
			cleanRuntime.start();
			try (FactoryOrchestrationServerTestAccess clean = install(callback, cleanRuntime,
				new FactoryOrchestrationSavedData(), Set.of())) {
				assertTrue(FactoryOrchestrationServer.running(callback.server()));
				assertTrue(FactoryOrchestrationServer.running(callback.server()));
				assertTrue(clean.hasNativePublisher());
			}
		}
	}

	@Test
	void reentrantInstallerCannotPublishACompetingRuntimeOrUseUnpublishedIngress() throws Exception {
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData outerJournal = new FactoryOrchestrationSavedData();
		FactoryOrchestrationSavedData competingJournal = new FactoryOrchestrationSavedData();
		CompoundTag before = outerJournal.save(new CompoundTag(), RegistryAccess.EMPTY);
		AtomicBoolean reentrantRejected = new AtomicBoolean();
		FactoryOrchestrationServerTestAccess installed = null;
		try (FactoryRuntimeService outerRuntime = new FactoryRuntimeService(CONFIG);
			FactoryRuntimeService competingRuntime = new FactoryRuntimeService(CONFIG)) {
			outerRuntime.start();
			competingRuntime.start();
			try {
				installed = FactoryOrchestrationServerTestAccess.installWithReentrantNativeBinding(
					callback.server(), CONFIG, outerRuntime, outerJournal, competingRuntime,
					competingJournal, ignored -> Set.of(), reentrantRejected);
				assertTrue(reentrantRejected.get(),
					"the per-server INSTALLING reservation must reject same-thread reentry");
				assertTrue(FactoryOrchestrationServer.running(callback.server()));
				assertTrue(installed.hasNativePublisher());
				assertEquals(before, outerJournal.save(new CompoundTag(), RegistryAccess.EMPTY),
					"a Runtime must reject native ingress until reservation-owner publication completes");
			} finally {
				if (installed != null) installed.close();
				else FactoryOrchestrationServer.stop(callback.server());
			}
		}
	}

	@Test
	void nativeInstallThenThrowRollsBackExactPublisherAndAllowsCleanRetry() throws Exception {
		assertNativeInstallFailureRollsBackAndRetries(false);
	}

	@Test
	void nativeInstallThenNullRollsBackExactPublisherAndAllowsCleanRetry() throws Exception {
		assertNativeInstallFailureRollsBackAndRetries(true);
	}

	@Test
	void frozenInstalledNativeIngressCannotMutateAResolvedContractReceiptOrDispatch() throws Exception {
		CallbackServer callback = callbackServer();
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		UUID runId = id(820);
		CreateRouteKey route = new CreateRouteKey(id(821), "frozen-native-input");
		CreateRouteKey nextRoute = new CreateRouteKey(route.logisticsNetworkId(), "frozen-next-input");
		AgentInputContract.State contract = sequencedClosedContract(runId, id(822), route, nextRoute);
		journal.write("input_contract/" + runId, contract, RegistryAccess.EMPTY);
		PackagerBlockEntity packager = acceptedPackager(callback.level());
		NativePackageObservation accepted = acceptedObservation(route,
			contract.stages().getFirst().manifest());
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			runtime.restoreSnapshot(ClusterEngineState.empty(CLUSTER, id(823)));
			try (FactoryOrchestrationServerTestAccess production = install(callback, runtime, journal,
				Set.of(route.logisticsNetworkId()))) {
				AgentInputContract.State beforeContract = journal.read("input_contract/" + runId,
					AgentInputContract.State.class, RegistryAccess.EMPTY).orElseThrow();
				CompoundTag journalBefore = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
				ClusterEngineState queueBefore = runtime.peekSnapshotForPanelView(CLUSTER).orElseThrow();
				var authorityBefore = production.authoritySnapshot();

				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				assertEquals(0, InstalledNativePackageIngress.throughPublisherLookup(callback.server(),
					packager, route.address(), accepted),
					"the installed ingress must not copy an accepted package after managed-route freeze");
				InstalledNativePackageIngress.throughResolvedRoute(callback.server(), packager,
					route.address(), route, accepted);
				InstalledNativePackageIngress.failAfterResolvedRoute(callback.server(), packager,
					route.address(), route);

				assertEquals(beforeContract, journal.read("input_contract/" + runId,
					AgentInputContract.State.class, RegistryAccess.EMPTY).orElseThrow(),
					"the resolved accepted package must not advance its next dispatch stage while frozen");
				assertEquals(journalBefore, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
				assertEquals(queueBefore, runtime.peekSnapshotForPanelView(CLUSTER).orElseThrow());
				assertEquals(authorityBefore, production.authoritySnapshot(),
					"frozen production ingress must preserve Router and outbox authority");
			}
		}
	}

	@Test
	void primaryFrequencyOnlyRefreshReplacesExactPublicationAndRoutesTheNewBinding() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		UUID computerId = computer.id();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 8);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router);
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A)));
			var oldBinding = router.boundaryBinding(externalCluster(computerId, 0)).orElseThrow();
			RadioEndpoint oldSource = router.registeredEndpoint(oldBinding.sourceHop().key()).orElseThrow();
			RadioEndpoint oldRelay = router.registeredEndpoint(oldBinding.targetHop().key()).orElseThrow();

			CommunicationFrequency changedPrimary = CommunicationFrequency.of("primary-changed");
			listeners.observe(computer, changedPrimary, address(BlockPos.ZERO));

			var current = router.boundaryBinding(externalCluster(computerId, 0)).orElseThrow();
			assertNotEquals(oldBinding, current);
			RadioEndpoint currentSource = router.registeredEndpoint(current.sourceHop().key()).orElseThrow();
			RadioEndpoint currentRelay = router.registeredEndpoint(current.targetHop().key()).orElseThrow();
			assertTrue(currentSource.generation() > oldSource.generation());
			assertTrue(currentRelay.generation() > oldRelay.generation());
			RadioEnvelope incoming = RadioEnvelope.create(id(51), currentSource, currentRelay, EXTERNAL_A,
				new ProcessProtocolMessage.Data("new-primary"),
				SubnetRouteTrace.start(current.sourceNetworkId(), current.sourceClusterId()));
			assertTrue(router.forwardBoundary(incoming).isPresent());
		}
	}

	@Test
	void productionListenerRuntimeBindingIsOneTimeAndCannotBeReplaced() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			new ExternalListenerRuntime(runtime, router);
			assertThrows(IllegalStateException.class,
				() -> new ExternalListenerRuntime(runtime, new RadioRouter(8, 32, 8)));
		}
	}

	@Test
	void prematureTraderObservationDefersUntilTheExactLoadedMemberIsPublished() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 8);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router);

			assertDoesNotThrow(() -> listeners.observe(computer, PRIMARY, address(BlockPos.ZERO)));
			assertTrue(listeners.projection(computer.id()).isEmpty());
			assertTrue(router.authorityStateForTest().endpoints().isEmpty());

			runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A)));
			assertEquals(ExternalListenerService.ListenerState.ACTIVE,
				listeners.projection(computer.id()).getFirst().state());
		}
	}

	@Test
	void activeListenerSourceAndRelayPublishIntoTheSharedFrozenTopologyFrame() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 8);
		RadioTopologyLifecycle topology = new RadioTopologyLifecycle(true);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start();
			ProcessRecord occupied = new ProcessRecord(id(501), id(502), computer.id(), 0, TRADER,
				ProcessKind.REMOTE_LISTENER, ProcessState.RUNNABLE, null, 0, Map.of());
			runtime.restoreSnapshot(new ClusterEngineState(CLUSTER, epoch.epochId(), 0,
				0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(occupied.processId(), occupied),
				List.of(new ClusterEngineState.MemberSnapshot(computer.id(), TRADER, true, 1, 1)),
				Map.of(), Set.of(), 0, null));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router,
				new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {
						topology.observe(new RadioTopologyLifecycle.Registration(owner.ownerToken(),
							endpoint.memberId()), endpoint, topologyPose(endpoint), 1);
					}
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {
						topology.retire(new RadioTopologyLifecycle.Registration(owner.ownerToken(),
							endpoint.memberId()));
					}
				});
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A, EXTERNAL_B)));
			assertEquals(ExternalListenerService.ListenerState.WAITING_PROCESS,
				listeners.projection(computer.id()).get(1).state());

			var binding = router.boundaryBinding(externalCluster(computer.id(), 1)).orElseThrow();
			assertEquals(List.of(router.registeredEndpoint(binding.targetHop().key()).orElseThrow()),
				listeners.relayCandidates().stream().map(ExternalListenerRuntime.RelayCandidate::endpoint)
					.toList(), "only the exact ACTIVE listener relay enters automatic route discovery");
			topology.refresh(20, (endpoint, ignored) -> new RadioTopologyTracker.ResolvedPose(
				Vec3.atCenterOf(endpoint.address().localPos()),
				java.util.Optional.ofNullable(endpoint.address().subLevelId())));
			assertTrue(topology.frame().sample(binding.sourceHop().key()).loaded());
			assertTrue(topology.frame().sample(binding.targetHop().key()).loaded());
			assertRoutes(router, binding, EXTERNAL_A, 500);
			listeners.unload(computer);
			assertTrue(listeners.relayCandidates().isEmpty(),
				"unloading the exact listener owner must withdraw its automatic relay candidate");
		}
	}

	@Test
	void rejectedListenerAuthorityCasKeepsTheExactPublicationAndTopologyPredecessor()
		throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
		List<RadioEndpoint> observed = new ArrayList<>();
		List<RadioEndpoint> retired = new ArrayList<>();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router,
				new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) { observed.add(endpoint); }
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) { retired.add(endpoint); }
				});
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A)));
			var binding = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			RadioRouter.AuthorityState predecessor = router.authorityStateForTest();
			RadioEndpoint source = router.registeredEndpoint(binding.sourceHop().key()).orElseThrow();
			RadioEndpoint relay = router.registeredEndpoint(binding.targetHop().key()).orElseThrow();
			assertEquals(2, observed.size());

			store.reject = true;
			listeners.observe(computer, CommunicationFrequency.of("primary-cas-rejected"),
				address(BlockPos.ZERO));

			assertEquals(predecessor, router.authorityStateForTest());
			assertEquals(source, router.registeredEndpoint(source.key()).orElseThrow());
			assertEquals(relay, router.registeredEndpoint(relay.key()).orElseThrow());
			assertEquals(binding, router.boundaryBinding(binding.sourceClusterId()).orElseThrow());
			assertEquals(2, observed.size());
			assertTrue(retired.isEmpty(), "topology retirement must follow only a committed authority CAS");
			assertTrue(listeners.relayCandidates().isEmpty(),
				"the unreserved failed predecessor must be withdrawn from production admission");
			assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
				listeners.projection(computer.id()).getFirst().state());
		}
	}

	@Test
	void rejectedReplacementRecoversDurablePredecessorAfterSaveAndFreshComposition()
		throws Exception {
		assertFailedReplacementRecoversAfterSave(false);
	}

	@Test
	void throwingReplacementRecoversDurablePredecessorAfterSaveAndFreshComposition()
		throws Exception {
		assertFailedReplacementRecoversAfterSave(true);
	}

	@Test
	void aSiblingListenerCommitDoesNotRetireARejectedPredecessorTopology() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
		List<RadioEndpoint> observed = new ArrayList<>();
		List<RadioEndpoint> retired = new ArrayList<>();
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router,
				new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) { observed.add(endpoint); }
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) { retired.add(endpoint); }
				});
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A, EXTERNAL_B)));
			var first = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			var second = router.boundaryBinding(externalCluster(computer.id(), 1)).orElseThrow();
			RadioEndpoint oldFirstSource = router.registeredEndpoint(first.sourceHop().key()).orElseThrow();
			RadioEndpoint oldFirstRelay = router.registeredEndpoint(first.targetHop().key()).orElseThrow();
			RadioEndpoint oldSecondSource = router.registeredEndpoint(second.sourceHop().key()).orElseThrow();
			RadioEndpoint oldSecondRelay = router.registeredEndpoint(second.targetHop().key()).orElseThrow();
			observed.clear();
			retired.clear();
			store.rejectAtCall = store.casCalls + 2;

			listeners.observe(computer, CommunicationFrequency.of("primary-mixed-cas"),
				address(BlockPos.ZERO));

			var currentFirst = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			RadioEndpoint currentFirstSource = router.registeredEndpoint(currentFirst.sourceHop().key())
				.orElseThrow();
			RadioEndpoint currentFirstRelay = router.registeredEndpoint(currentFirst.targetHop().key())
				.orElseThrow();
			assertTrue(currentFirstSource.generation() > oldFirstSource.generation());
			assertTrue(currentFirstRelay.generation() > oldFirstRelay.generation());
			assertEquals(oldSecondSource, router.registeredEndpoint(oldSecondSource.key()).orElseThrow());
			assertEquals(oldSecondRelay, router.registeredEndpoint(oldSecondRelay.key()).orElseThrow());
			assertTrue(retired.contains(oldFirstSource));
			assertTrue(retired.contains(oldFirstRelay));
			assertFalse(retired.contains(oldSecondSource));
			assertFalse(retired.contains(oldSecondRelay));
			assertTrue(observed.contains(currentFirstSource));
			assertTrue(observed.contains(currentFirstRelay));
			assertEquals(List.of(currentFirstRelay), listeners.relayCandidates().stream()
				.map(ExternalListenerRuntime.RelayCandidate::endpoint).toList());
		}
	}

	@Test
	void listenerExactReactivationPublishesTopologyAfterAnOrdinaryEndpointReactivation()
		throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioEndpoint ordinary = new RadioEndpoint(CLUSTER, id(990), address(new BlockPos(8, 64, 0)),
			0, CommunicationFrequency.of("ordinary-reactivation"), 1, Set.of());
		RadioRouter firstRouter = new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
		assertTrue(firstRouter.register(ordinary));
		try (FactoryRuntimeService firstRuntime = new FactoryRuntimeService(CONFIG)) {
			firstRuntime.start(); firstRuntime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime firstListeners = new ExternalListenerRuntime(firstRuntime, firstRouter);
			firstListeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				firstListeners.replace(computer, 0, List.of(EXTERNAL_A)));
		}

		RadioRouter restoredRouter = new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
		assertTrue(restoredRouter.register(ordinary),
			"the ordinary durable endpoint must exact-reactivate before the listener");
		List<RadioEndpoint> observed = new ArrayList<>();
		try (FactoryRuntimeService restoredRuntime = new FactoryRuntimeService(CONFIG)) {
			restoredRuntime.start();
			restoredRuntime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime restoredListeners = new ExternalListenerRuntime(restoredRuntime,
				restoredRouter, new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) { observed.add(endpoint); }
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {}
				});
			restoredListeners.observe(computer, PRIMARY, address(BlockPos.ZERO));

			var binding = restoredRouter.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			assertTrue(observed.contains(restoredRouter.registeredEndpoint(binding.sourceHop().key())
				.orElseThrow()));
			assertTrue(observed.contains(restoredRouter.registeredEndpoint(binding.targetHop().key())
				.orElseThrow()));
		}
	}

	@Test
	void exactLeaseOwnedByAnotherClusterCannotAuthorizeListenerIngress() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 8);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router);
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A)));
			var binding = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			RadioEndpoint source = router.registeredEndpoint(binding.sourceHop().key()).orElseThrow();
			RadioEndpoint relay = router.registeredEndpoint(binding.targetHop().key()).orElseThrow();
			RemoteProcessLease foreignLease = leaseOwnedBy(source, relay);
			assertEquals(RadioRouter.RouteStatus.QUEUED, router.submit(RadioEnvelope.create(id(52), source,
				relay, EXTERNAL_A, new ProcessProtocolMessage.LeaseOffer(foreignLease)), List.of()));
			router.tick(1);
			assertTrue(router.hasExactLease(foreignLease.leaseId(), foreignLease.revisionFence()));
			assertNotEquals(CLUSTER, router.goalOwner(foreignLease.leaseId()).orElseThrow());
			RadioEnvelope ingress = RadioEnvelope.create(id(53), source, relay, EXTERNAL_A,
				new ProcessProtocolMessage.Data("unrelated-lease"),
				SubnetRouteTrace.start(binding.sourceNetworkId(), binding.sourceClusterId()));
			assertTrue(listeners.forwardIngress(computer, EXTERNAL_A, ingress,
				new ExternalListenerService.IngressAuthority.ExistingLease(foreignLease.leaseId(),
					foreignLease.revisionFence())).isEmpty());
		}
	}

	@Test
	void repeatedPrimaryAndAddressRefreshesKeepEndpointKeysBoundedAndAdvanceGenerations() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 4);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router);
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A)));
			var initial = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			RadioEndpoint.Key sourceKey = initial.sourceHop().key(), relayKey = initial.targetHop().key();
			long sourceGeneration = initial.sourceHop().generation(), relayGeneration = initial.targetHop().generation();
			for (int revision = 1; revision <= 8; revision++) {
				CommunicationFrequency primary = CommunicationFrequency.of("primary-" + revision);
				listeners.observe(computer, primary, address(new BlockPos(revision, 64, revision)));
				var current = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
				assertEquals(sourceKey, current.sourceHop().key());
				assertEquals(relayKey, current.targetHop().key());
				assertTrue(current.sourceHop().generation() > sourceGeneration);
				assertTrue(current.targetHop().generation() > relayGeneration);
				RadioEndpoint source = router.registeredEndpoint(current.sourceHop().key()).orElseThrow();
				RadioEndpoint relay = router.registeredEndpoint(current.targetHop().key()).orElseThrow();
				RadioEnvelope ingress = RadioEnvelope.create(id(70 + revision), source, relay, EXTERNAL_A,
					new ProcessProtocolMessage.Data("refresh-" + revision),
					SubnetRouteTrace.start(current.sourceNetworkId(), current.sourceClusterId()));
				assertTrue(router.forwardBoundary(ingress).isPresent());
				sourceGeneration = current.sourceHop().generation();
				relayGeneration = current.targetHop().generation();
			}
		}
	}

	@Test
	void savedTraderReloadKeepsTheRestoredExactPublicationWhenTheOriginalObservesLate() throws Exception {
		bootstrap();
		ProductionComputer original = formedTrader();
		ProductionEpoch epoch = original.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 8);
		RadioTopologyLifecycle topology = new RadioTopologyLifecycle(true);
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, original.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router,
				new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {
						topology.observe(new RadioTopologyLifecycle.Registration(owner.ownerToken(),
							endpoint.memberId()), endpoint, topologyPose(endpoint), 1);
					}
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {
						topology.retire(new RadioTopologyLifecycle.Registration(owner.ownerToken(),
							endpoint.memberId()));
					}
				});
			listeners.observe(original, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(original, 0, List.of(EXTERNAL_A, EXTERNAL_B)));
			var oldA = router.boundaryBinding(externalCluster(original.id(), 0)).orElseThrow();
			var oldB = router.boundaryBinding(externalCluster(original.id(), 1)).orElseThrow();
			CompoundTag saved = save(original);
			UUID oldOwner = original.listenerOwnerToken();
			listeners.unload(original);

			ProductionComputer restored = TestComputer.create(BlockEntityType.FURNACE, BlockPos.ZERO,
				Blocks.FURNACE.defaultBlockState());
			physical(restored).read(saved, RegistryAccess.EMPTY, false);
			field("availabilityReason").set(restored, ComputerAvailabilityReason.NONE);
			field("persistenceAvailable").setBoolean(restored, true);
			assertNotEquals(oldOwner, restored.listenerOwnerToken());
			assertTrue(restored.canConfigureExternalListeners());
			listeners.observe(restored, PRIMARY, address(BlockPos.ZERO));

			assertEquals(List.of(ExternalListenerService.ListenerState.ACTIVE,
				ExternalListenerService.ListenerState.ACTIVE), listeners.projection(restored.id()).stream()
				.map(ExternalListenerService.ListenerProjection::state).toList());
			var restoredA = router.boundaryBinding(externalCluster(restored.id(), 0)).orElseThrow();
			var restoredB = router.boundaryBinding(externalCluster(restored.id(), 1)).orElseThrow();
			assertTrue(restoredA.sourceHop().generation() > oldA.sourceHop().generation());
			assertTrue(restoredB.targetHop().generation() > oldB.targetHop().generation());
			RadioEndpoint restoredSource = router.registeredEndpoint(restoredA.sourceHop().key())
				.orElseThrow();
			RadioEndpoint restoredRelay = router.registeredEndpoint(restoredA.targetHop().key())
				.orElseThrow();
			List<ExternalListenerRuntime.RelayCandidate> restoredCandidates = listeners.relayCandidates();
			topology.refresh(20, (endpoint, ignored) -> new RadioTopologyTracker.ResolvedPose(
				Vec3.atCenterOf(endpoint.address().localPos()),
				java.util.Optional.ofNullable(endpoint.address().subLevelId())));
			var restoredTopology = topology.frame();
			listeners.unload(original);
			assertEquals(restoredA, router.boundaryBinding(restoredA.sourceClusterId()).orElseThrow());

			original.owner().setRemoved();
			listeners.observe(original, CommunicationFrequency.of("stale-original"),
				address(new BlockPos(9, 64, 9)));

			assertEquals(restoredA, router.boundaryBinding(restoredA.sourceClusterId()).orElseThrow(),
				"a stale original observation must not replace the restored exact binding");
			assertEquals(restoredSource, router.registeredEndpoint(restoredA.sourceHop().key()).orElseThrow(),
				"a stale original observation must not replace the restored source router owner");
			assertEquals(restoredRelay, router.registeredEndpoint(restoredA.targetHop().key()).orElseThrow(),
				"a stale original observation must not replace the restored relay router owner");
			assertEquals(restoredCandidates, listeners.relayCandidates(),
				"a stale original observation must not replace the restored automatic relay candidate");
			assertEquals(restoredTopology, topology.frame(),
				"a stale original observation must not replace the restored exact topology frame");
		}
	}

	@Test
	void routerAuthorityRevisionRetriesCollisionAndTwoFrequenciesReturnSlotsToOrdinaryGoals() throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RadioRouter router = new RadioRouter(8, 32, 8);
		RadioEndpoint foreignSource = new RadioEndpoint(externalCluster(computer.id(), 0),
			sourceMember(computer.id(), 0), address(new BlockPos(9, 64, 9)), 0, EXTERNAL_A, 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		assertTrue(router.register(foreignSource));
		try (FactoryRuntimeService runtime = new FactoryRuntimeService(CONFIG)) {
			runtime.start(); runtime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			ExternalListenerRuntime listeners = new ExternalListenerRuntime(runtime, router);
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 0, List.of(EXTERNAL_A)));
			assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
				listeners.projection(computer.id()).getFirst().state());
			assertEquals(0, listenerProcesses(runtime));
			assertTrue(router.unregisterExactEndpoint(foreignSource));
			listeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.ListenerState.ACTIVE,
				listeners.projection(computer.id()).getFirst().state());

			assertEquals(ExternalListenerService.EditResult.APPLIED,
				listeners.replace(computer, 1, List.of(EXTERNAL_A, EXTERNAL_B)));
			assertEquals(2, listenerProcesses(runtime));
			var first = router.boundaryBinding(externalCluster(computer.id(), 0)).orElseThrow();
			var second = router.boundaryBinding(externalCluster(computer.id(), 1)).orElseThrow();
			assertNotEquals(first.sourceHop(), second.sourceHop());
			assertNotEquals(first.targetHop(), second.targetHop());
			assertNotEquals(first.listenerFrequency(), second.listenerFrequency());
			assertRoutes(router, first, EXTERNAL_A, 81);
			assertRoutes(router, second, EXTERNAL_B, 82);

			listeners.unload(computer);
			awaitNoListenerProcess(runtime);
			assertTrue(submitOrdinaryGoal(runtime, epoch.epochId(), 90).isPresent());
			assertTrue(submitOrdinaryGoal(runtime, epoch.epochId(), 91).isPresent());
			awaitOrdinaryGoalProcesses(runtime, 2);
		}
	}

	@Test
	void orderlyShutdownReconstructionRestoresOnlyExactListenerAuthority() throws Exception {
		bootstrap();
		ProductionComputer firstComputer = formedTrader();
		ClusterEngineState emptyRuntime = unoccupiedListenerState(firstComputer.epoch().orElseThrow(),
			firstComputer.id());
		CompoundTag runtimeNbt = RuntimeNbt.save(emptyRuntime);
		FactoryOrchestrationSavedData firstJournal = new FactoryOrchestrationSavedData();
		CallbackServer callback = callbackServer();
		CompoundTag computerBeforeStop;
		CompoundTag finalComputerNbt;
		CompoundTag finalJournalNbt;
		SubnetBoundaryBinding originalBinding;
		RadioEnvelope ordinaryExact;
		RadioEnvelope originalForwarded;
		try (FactoryRuntimeService firstRuntime = new FactoryRuntimeService(CONFIG)) {
			firstRuntime.start();
			firstRuntime.restoreSnapshot(RuntimeNbt.load(runtimeNbt).orElseThrow());
			try (FactoryOrchestrationServerTestAccess production = install(callback, firstRuntime,
				firstJournal, Set.of())) {
				assertTrue(production.observeExternalListener(firstComputer, PRIMARY,
					address(BlockPos.ZERO)), "the installed production root must own the listener host");
				assertEquals(ExternalListenerService.EditResult.APPLIED,
					production.replaceExternalListeners(firstComputer, 0, List.of(EXTERNAL_A)));
				assertTrue(production.hasBoundary(externalCluster(firstComputer.id(), 0)),
					"the installed production root must own an active listener boundary before freeze");
				RadioRouter.AuthorityState rootAuthorityState = production.radioAuthorityState();
				originalBinding = rootAuthorityState.boundaries()
					.get(externalCluster(firstComputer.id(), 0)).binding();
				RadioEndpoint source = rootAuthorityState.endpoints().get(originalBinding.sourceHop().key())
					.current().orElseThrow();
				RadioEndpoint relay = rootAuthorityState.endpoints().get(originalBinding.targetHop().key())
					.current().orElseThrow();
				RadioRouter envelopeBuilder = new RadioRouter(8, 32, 8);
				RadioRouter.ListenerAuthority listenerAuthority = new RadioRouter.ListenerAuthority(source,
					relay, originalBinding);
				assertTrue(envelopeBuilder.compareAndSetListenerAuthority(envelopeBuilder
					.prepareListenerPublication(Optional.empty(), listenerAuthority).orElseThrow()));
				ordinaryExact = RadioEnvelope.create(id(699), source, relay, EXTERNAL_A,
					new ProcessProtocolMessage.LeaseOffer(leaseOwnedBy(source, relay, 6_990)));
				originalForwarded = envelopeBuilder.forwardBoundary(RadioEnvelope.create(id(700), source, relay,
					EXTERNAL_A, new ProcessProtocolMessage.LeaseOffer(leaseOwnedBy(source, relay, 7_000)),
					SubnetRouteTrace.start(originalBinding.sourceNetworkId(),
						originalBinding.sourceClusterId()))).orElseThrow();
				assertTrue(production.offerRadio(ordinaryExact, List.of()));
				assertTrue(production.offerRadio(originalForwarded, List.of()));
				RadioRouter.AuthorityState committedAuthority = production.radioAuthorityState();
				computerBeforeStop = save(firstComputer);
				ProductionComputer unowned = TestComputer.create(BlockEntityType.FURNACE,
					BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
				unowned.owner().setLevel(callback.level());
				physical(unowned).read(computerBeforeStop, RegistryAccess.EMPTY, false);
				assertEquals(committedAuthority, production.radioAuthorityState(),
					"initial deserialization without the registered owner must not retire a live predecessor");
				firstComputer.owner().setLevel(callback.level());
				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				assertEquals(FactoryRuntimeLifecycle.Phase.STOPPING_FROZEN,
					FactoryOrchestrationServer.runtimePhaseForTest(callback.server()));
				FactoryOrchestrationServer.nativeComputerUnloaded(firstComputer);
				physical(firstComputer).read(computerBeforeStop, RegistryAccess.EMPTY, false);
				assertEquals(committedAuthority, production.radioAuthorityState(),
					"production technical unload and NBT read after orderly freeze must retain exact authority");
				FactoryOrchestrationServer.stop(callback.server());
				assertEquals(FactoryRuntimeLifecycle.Phase.STOPPED,
					FactoryOrchestrationServer.runtimePhaseForTest(callback.server()));
			}
			finalJournalNbt = firstJournal.save(new CompoundTag(), RegistryAccess.EMPTY);
			finalComputerNbt = save(firstComputer);
		}

		assertEquals(computerBeforeStop, finalComputerNbt,
			"orderly stop must not mutate the persisted Computer listener configuration");
		FactoryOrchestrationSavedData restoredJournal = reloadJournal(finalJournalNbt);
		ProductionComputer restoredComputer = restoreComputer(finalComputerNbt);
		CompoundTag changedComputerNbt;
		CompoundTag changedJournalNbt;
		RadioEnvelope retiredForwarded;
		SubnetBoundaryBinding changedBinding;
		try (FactoryRuntimeService restoredRuntime = new FactoryRuntimeService(CONFIG)) {
			restoredRuntime.start();
			restoredRuntime.restoreSnapshot(RuntimeNbt.load(runtimeNbt).orElseThrow());
			try (FactoryOrchestrationServerTestAccess production = install(callback, restoredRuntime,
				restoredJournal, Set.of())) {
				assertTrue(production.pumpRadioOutbox(1).isEmpty());
				assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
					FactoryOrchestrationServer.radioStatus(callback.server(), ordinaryExact.messageId()).orElseThrow());
				assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
					FactoryOrchestrationServer.radioStatus(callback.server(), originalForwarded.messageId()).orElseThrow());
				assertTrue(production.observeExternalListener(restoredComputer, PRIMARY,
					address(BlockPos.ZERO)));
				assertEquals(originalBinding, production.radioAuthorityState().boundaries()
					.get(externalCluster(restoredComputer.id(), 0)).binding(),
					"an unchanged persisted listener must reactivate its exact durable generations");
				List<RadioRouter.Delivery> deliveries = production.pumpRadioOutbox(2);
				assertEquals(2, deliveries.size(),
					"exact Computer re-observation must emit exactly two final actions");
				assertEquals(1L, deliveries.stream().filter(delivery -> delivery.envelope().messageId()
					.equals(ordinaryExact.messageId())).count());
				assertEquals(1L, deliveries.stream().filter(delivery -> delivery.envelope().messageId()
					.equals(originalForwarded.messageId())).count());
				assertTrue(deliveries.stream().allMatch(RadioRouter.Delivery::finalRecipient));
				assertTrue(deliveries.stream().noneMatch(RadioRouter.Delivery::duplicateProtocolMessage),
					"independent ordinary and boundary actions must not collapse into protocol duplicates");
				assertTrue(production.markRadioApplied(ordinaryExact.messageId()));
				assertTrue(production.markRadioApplied(originalForwarded.messageId()));
				assertEquals(2, production.retireAppliedRadioRoutes());
				FactoryOrchestrationSavedData appliedJournal = reloadJournal(
					restoredJournal.save(new CompoundTag(), RegistryAccess.EMPTY));
				DurableRadioOutbox replayedOutbox = productionDurableOutbox(appliedJournal);
				assertTrue(replayedOutbox.entry(ordinaryExact.messageId()).isEmpty()
					&& replayedOutbox.entry(originalForwarded.messageId()).isEmpty(),
					"a second reconstruction of exact applied authority cannot replay either final action");

				RadioRouter.AuthorityState restoredAuthority = production.radioAuthorityState();
				RadioEndpoint oldSource = restoredAuthority.endpoints().get(originalBinding.sourceHop().key())
					.current().orElseThrow();
				RadioEndpoint oldRelay = restoredAuthority.endpoints().get(originalBinding.targetHop().key())
					.current().orElseThrow();
				RadioRouter retiredEnvelopeBuilder = listenerEnvelopeBuilder(oldSource, oldRelay, originalBinding);
				retiredForwarded = retiredEnvelopeBuilder.forwardBoundary(RadioEnvelope.create(id(701), oldSource,
					oldRelay, EXTERNAL_A, new ProcessProtocolMessage.LeaseOffer(
						leaseOwnedBy(oldSource, oldRelay, 7_010)),
					SubnetRouteTrace.start(originalBinding.sourceNetworkId(),
						originalBinding.sourceClusterId()))).orElseThrow();
				assertTrue(production.offerRadio(retiredForwarded, List.of()));
				assertEquals(ExternalListenerService.EditResult.APPLIED,
					production.replaceExternalListeners(restoredComputer, 1, List.of(EXTERNAL_B)));
				changedBinding = production.radioAuthorityState().boundaries()
					.get(externalCluster(restoredComputer.id(), 0)).binding();
				assertNotEquals(originalBinding, changedBinding);
				assertTrue(changedBinding.sourceHop().generation() > originalBinding.sourceHop().generation()
					&& changedBinding.targetHop().generation() > originalBinding.targetHop().generation());
				assertTrue(production.pumpRadioOutbox(3).isEmpty());
				assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
					FactoryOrchestrationServer.radioStatus(callback.server(), retiredForwarded.messageId()).orElseThrow());
				RadioRouter.AuthorityState reconfiguredAuthority = production.radioAuthorityState();
				FactoryOrchestrationServer.beginOrderlyShutdown(callback.server());
				restoredComputer.owner().setLevel(callback.level());
				FactoryOrchestrationServer.nativeComputerUnloaded(restoredComputer);
				assertEquals(reconfiguredAuthority, production.radioAuthorityState(),
					"frozen unload must retain the RUNNING-reconfigured listener authority");
				FactoryOrchestrationServer.stop(callback.server());
				changedComputerNbt = save(restoredComputer);
				changedJournalNbt = restoredJournal.save(new CompoundTag(), RegistryAccess.EMPTY);
			}
		}

		FactoryOrchestrationSavedData changedJournal = reloadJournal(changedJournalNbt);
		RadioRouter changedRouter = productionDurableRouter(changedJournal);
		ProductionComputer changedComputer = restoreComputer(changedComputerNbt);
		try (FactoryRuntimeService changedRuntime = new FactoryRuntimeService(CONFIG)) {
			changedRuntime.start();
			changedRuntime.restoreSnapshot(RuntimeNbt.load(runtimeNbt).orElseThrow());
			try (FactoryOrchestrationServerTestAccess production = install(callback, changedRuntime,
				changedJournal, Set.of())) {
				assertTrue(production.pumpRadioOutbox(4).isEmpty());
				assertTrue(production.observeExternalListener(changedComputer, PRIMARY,
					address(BlockPos.ZERO)));
				assertEquals(changedBinding, production.radioAuthorityState().boundaries()
					.get(externalCluster(changedComputer.id(), 0)).binding());
				assertFalse(changedRouter.register(new RadioEndpoint(
					originalBinding.sourceHop().key().clusterId(),
					originalBinding.sourceHop().key().memberId(), address(BlockPos.ZERO),
					originalBinding.sourceHop().generation(), EXTERNAL_A, 1,
					Set.of(ProductionCapability.EXTENDED_RADIO))));
				assertFalse(changedRouter.registerBoundaryBinding(originalBinding));
				assertTrue(production.pumpRadioOutbox(5).isEmpty());
				assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
					FactoryOrchestrationServer.radioStatus(callback.server(), retiredForwarded.messageId())
						.orElseThrow(),
					"a reconfigured listener must not replay an envelope fenced to its retired binding");
			}
		}
	}

	private static FactoryOrchestrationServerTestAccess install(CallbackServer callback,
		FactoryRuntimeService runtime, FactoryOrchestrationSavedData journal,
		Set<UUID> attachedNetworks) {
		return FactoryOrchestrationServerTestAccess.install(callback.server(), CONFIG, runtime, journal,
			ignored -> attachedNetworks);
	}

	private static void assertFailedReplacementRecoversAfterSave(boolean throwFailure)
		throws Exception {
		bootstrap();
		ProductionComputer computer = formedTrader();
		ProductionEpoch epoch = computer.epoch().orElseThrow();
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		SubnetBoundaryBinding predecessorBinding;
		RadioEndpoint predecessorSource;
		RadioEndpoint predecessorRelay;
		CompoundTag savedComputer;
		int callsBeforeRecovery;
		SpaceAddress replacementAddress = address(new BlockPos(1, 0, 0));
		try (FactoryRuntimeService firstRuntime = new FactoryRuntimeService(CONFIG)) {
			firstRuntime.start();
			firstRuntime.restoreSnapshot(unoccupiedListenerState(epoch, computer.id()));
			RadioRouter firstRouter = new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
			ExternalListenerRuntime firstListeners = new ExternalListenerRuntime(firstRuntime, firstRouter);
			firstListeners.observe(computer, PRIMARY, address(BlockPos.ZERO));
			assertEquals(ExternalListenerService.EditResult.APPLIED,
				firstListeners.replace(computer, 0, List.of(EXTERNAL_A)));
			predecessorBinding = firstRouter.boundaryBinding(externalCluster(computer.id(), 0))
				.orElseThrow();
			predecessorSource = firstRouter.registeredEndpoint(predecessorBinding.sourceHop().key())
				.orElseThrow();
			predecessorRelay = firstRouter.registeredEndpoint(predecessorBinding.targetHop().key())
				.orElseThrow();

			var installed = computer.externalListenerConfiguration();
			assertTrue(computer.compareAndSetExternalListenerConfiguration(installed,
				new com.nobodiiiii.createbiotech.content.factorycluster.communication
					.ExternalListenerConfiguration(installed.revision() + 1, installed.frequencies())),
				"the new listener configuration revision must be durable before authority replacement");
			store.reject = !throwFailure;
			store.throwFailure = throwFailure;
			firstListeners.observe(computer, PRIMARY, replacementAddress);
			assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
				firstListeners.projection(computer.id()).getFirst().state());
			assertEquals(predecessorSource,
				firstRouter.registeredEndpoint(predecessorSource.key()).orElseThrow());
			assertEquals(predecessorRelay,
				firstRouter.registeredEndpoint(predecessorRelay.key()).orElseThrow());
			savedComputer = save(computer);
			callsBeforeRecovery = store.casCalls;
		}

		store.reject = false;
		store.throwFailure = false;
		ProductionComputer restoredComputer = restoreComputer(savedComputer);
		List<RadioEndpoint> observed = new ArrayList<>();
		RadioRouter restoredRouter = new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
		try (FactoryRuntimeService restoredRuntime = new FactoryRuntimeService(CONFIG)) {
			restoredRuntime.start();
			restoredRuntime.restoreSnapshot(unoccupiedListenerState(
				restoredComputer.epoch().orElseThrow(), restoredComputer.id()));
			ExternalListenerRuntime restoredListeners = new ExternalListenerRuntime(restoredRuntime,
				restoredRouter, new ExternalListenerRuntime.TopologySink() {
					@Override public void observe(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) { observed.add(endpoint); }
					@Override public void retire(ExternalListenerService.ListenerOwner owner,
						RadioEndpoint endpoint) {}
				});

			restoredListeners.observe(restoredComputer, PRIMARY, replacementAddress);
			assertEquals(ExternalListenerService.ListenerState.ACTIVE,
				restoredListeners.projection(restoredComputer.id()).getFirst().state());
			SubnetBoundaryBinding replacementBinding = restoredRouter.boundaryBinding(
				externalCluster(restoredComputer.id(), 0)).orElseThrow();
			RadioEndpoint replacementSource = restoredRouter.registeredEndpoint(
				replacementBinding.sourceHop().key()).orElseThrow();
			RadioEndpoint replacementRelay = restoredRouter.registeredEndpoint(
				replacementBinding.targetHop().key()).orElseThrow();
			assertTrue(replacementSource.generation() > predecessorSource.generation());
			assertTrue(replacementRelay.generation() > predecessorRelay.generation());
			assertEquals(2, observed.size(),
				"the recovered source and relay must each enter topology exactly once");
			assertEquals(Set.of(replacementSource, replacementRelay), Set.copyOf(observed));
			assertEquals(callsBeforeRecovery + 1, store.casCalls,
				"fresh observation must commit the recovered predecessor replacement once");
			assertFalse(restoredRouter.register(predecessorSource),
				"the durable high-water mark must tombstone the old source generation");
			RadioEnvelope stale = RadioEnvelope.create(id(7_410), predecessorSource,
				predecessorRelay, EXTERNAL_A, new ProcessProtocolMessage.Data("stale-replacement"),
				SubnetRouteTrace.start(predecessorBinding.sourceNetworkId(),
					predecessorBinding.sourceClusterId()));
			assertTrue(restoredRouter.forwardBoundary(stale).isEmpty());
			RadioEnvelope current = RadioEnvelope.create(id(7_411), replacementSource,
				replacementRelay, EXTERNAL_A, new ProcessProtocolMessage.Data("current-replacement"),
				SubnetRouteTrace.start(replacementBinding.sourceNetworkId(),
					replacementBinding.sourceClusterId()));
			assertTrue(restoredRouter.forwardBoundary(current).isPresent());

			restoredListeners.observe(restoredComputer, PRIMARY, replacementAddress);
			assertEquals(callsBeforeRecovery + 1, store.casCalls);
			assertEquals(2, observed.size());
			assertEquals(Set.of(replacementSource, replacementRelay), Set.copyOf(observed));
		}
	}

	private static void assertNativeInstallFailureRollsBackAndRetries(boolean returnNull)
		throws Exception {
		CallbackServer callback = callbackServer();
		AtomicReference<AutoCloseable> failedBinding = new AtomicReference<>();
		FactoryOrchestrationServerTestAccess clean = null;
		try {
			try (FactoryRuntimeService failedRuntime = new FactoryRuntimeService(CONFIG)) {
				failedRuntime.start();
				assertThrows(RuntimeException.class, () ->
					FactoryOrchestrationServerTestAccess.installWithNativeBindingFailureAfterInstall(
						callback.server(), CONFIG, failedRuntime,
						new FactoryOrchestrationSavedData(), ignored -> Set.of(), returnNull,
						failedBinding));
				assertFalse(FactoryOrchestrationServer.running(callback.server()));
				assertFalse(FactoryOrchestrationServerTestAccess.hasNativePublisher(callback.server()),
					"a failed installer must roll back its publisher even when no token was returned");
			}
			try (FactoryRuntimeService cleanRuntime = new FactoryRuntimeService(CONFIG)) {
				cleanRuntime.start();
				clean = install(callback, cleanRuntime, new FactoryOrchestrationSavedData(), Set.of());
				AutoCloseable stale = failedBinding.get();
				if (stale != null) stale.close();
				assertTrue(clean.hasNativePublisher(),
					"the failed owner's stale token must not remove the clean retry publisher");
				clean.close();
				clean = null;
			}
		} finally {
			if (clean != null) clean.close();
			FactoryOrchestrationServer.stop(callback.server());
			AutoCloseable stale = failedBinding.get();
			if (stale != null) stale.close();
		}
	}

	private static ClusterCommunicationConfiguration seedCommunication(
		FactoryOrchestrationSavedData journal, UUID clusterId, CommunicationFrequency frequency) {
		ClusterCommunicationDirectory directory = new ClusterCommunicationDirectory(
			journal.clusterCommunicationStore(RegistryAccess.EMPTY));
		ClusterCommunicationConfiguration initial = directory.ensure(clusterId);
		ClusterCommunicationConfiguration configured = new ClusterCommunicationConfiguration(clusterId,
			frequency, initial.role(), initial.revision() + 1);
		assertTrue(directory.compareAndSet(clusterId, initial.revision(), configured));
		return configured;
	}

	private static FrozenProductionPlan productionPlan(UUID clusterId, UUID goalId, long identity) {
		UUID network = id(identity);
		UUID patternId = id(identity + 1);
		CreateRouteKey input = new CreateRouteKey(network, "eligible-input-" + identity);
		CreateRouteKey delivery = new CreateRouteKey(network, "eligible-delivery-" + identity);
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey output = new StackKey(new ItemStack(Items.GOLD_INGOT));
		ProductionRun run = new ProductionRun(id(identity + 2), goalId, patternId, 0, 1,
			InputMode.EXACT, List.of(input), delivery, 0, Map.of(ingredient, 1L));
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(
			new RouteSelectionScope(id(identity + 3), id(identity + 4), output, 0, 0,
				id(identity + 5)), id(identity + 6), patternId, patternId.toString(),
			RouteCandidate.ProviderKind.PATTERN, 0, RouteCandidate.Availability.AVAILABLE);
		return new FrozenProductionPlan(goalId, clusterId, provider, PatternKind.STANDARD, output, 1,
			delivery, List.of(new FrozenProductionPlan.FrozenStage(0, input, Map.of(ingredient, 1L),
				List.of(new com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient(
					ingredient, 1L)))), new ProductionRun.RunSequence(run, 1),
			new StockObservationScope.NetworkSummary(network, output, 1), 0, 1, false, false,
			Optional.empty());
	}

	private static AgentInputContract.State sequencedClosedContract(UUID runId, UUID agentId,
		CreateRouteKey route, CreateRouteKey nextRoute) {
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey stone = new StackKey(new ItemStack(Items.STONE));
		Map<StackKey, Long> firstManifest = Map.of(iron, 1L);
		ProductionRun run = new ProductionRun(runId, id(824), id(825), 7, 1, InputMode.EXACT,
			List.of(route, nextRoute), nextRoute, 0, Map.of(iron, 1L, stone, 1L));
		NativeOrderLedger ledger = new NativeOrderLedger(firstManifest,
			List.of(new NativeOrderLedger.NativeOrderIdentity(42, Set.of(0))));
		CreateDispatchCommand command = new CreateDispatchCommand(id(826), agentId, 0, run.fence(),
			run.runId(), id(827), 0, route, run.fence(), firstManifest);
		return new AgentInputContract.State(agentId, 0, id(827), run,
			AgentInputContract.ExecutionShape.SEQUENCED,
			List.of(new AgentInputContract.StageInput(0, route, firstManifest),
				new AgentInputContract.StageInput(1, nextRoute, Map.of(stone, 1L))),
			20, AgentInputPhase.INPUT_CLOSED, 0, java.util.OptionalLong.empty(),
			Optional.of(command), Optional.of(ledger.snapshot()), 0, false, 0, true, false, 0);
	}

	private static NativePackageObservation acceptedObservation(CreateRouteKey route,
		Map<StackKey, Long> manifest) {
		return new NativePackageObservation(route, true, 42, 0, 0, true, true,
			new NativeOrderLedger.OrderContext(manifest), manifest);
	}

	private static PackagerBlockEntity acceptedPackager(ServerLevel level)
		throws ReflectiveOperationException {
		PackagerBlockEntity packager = allocate(PackagerBlockEntity.class);
		setInstanceField(packager, "level", level);
		setInstanceField(packager, "worldPosition", BlockPos.ZERO);
		return packager;
	}

	private static FrozenRuntimeState frozenRuntimeState(FactoryRuntimeService runtime,
		FactoryOrchestrationServerTestAccess production, FactoryOrchestrationSavedData journal)
		throws ReflectiveOperationException {
		synchronized (runtime) {
			LinkedHashMap<String, Object> service = new LinkedHashMap<>();
			for (Field candidate : FactoryRuntimeService.class.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(candidate.getModifiers())) continue;
				candidate.setAccessible(true);
				Object value = candidate.get(runtime);
				if (value instanceof com.nobodiiiii.createbiotech.content.factorycluster.agent
					.AgentPendingRunQueue queue) service.put(candidate.getName(), queue.snapshot());
				else if (value instanceof Map<?, ?> || value instanceof Collection<?>
					|| candidate.getType().isPrimitive() || value instanceof UUID
					|| value instanceof String || value instanceof Enum<?> || value == null)
					service.put(candidate.getName(), freezeValue(value));
			}
			FactoryComputeWorker worker = worker(runtime);
			LinkedHashMap<String, Object> workerState = new LinkedHashMap<>();
			for (String name : List.of("input", "output", "outstanding", "started",
				"accepting", "closed", "closing")) {
				Field candidate = FactoryComputeWorker.class.getDeclaredField(name);
				candidate.setAccessible(true);
				Object value = candidate.get(worker);
				workerState.put(name, value instanceof java.util.concurrent.Semaphore semaphore
					? semaphore.availablePermits() : freezeValue(value));
			}
			return new FrozenRuntimeState(java.util.Collections.unmodifiableMap(service),
				java.util.Collections.unmodifiableMap(workerState),
				production.radioAuthorityState(), production.authoritySnapshot(),
				journal.save(new CompoundTag(), RegistryAccess.EMPTY).copy());
		}
	}

	@SuppressWarnings("unchecked")
	private static AutoCloseable bindComputerRuntime(MinecraftServer server,
		FactoryRuntimeService runtime) throws ReflectiveOperationException {
		Field field = ProductionRuntimeServices.class.getDeclaredField("SERVICES");
		field.setAccessible(true);
		Map<MinecraftServer, FactoryRuntimeService> services =
			(Map<MinecraftServer, FactoryRuntimeService>) field.get(null);
		synchronized (services) {
			if (services.putIfAbsent(server, runtime) != null)
				throw new IllegalStateException("test server already owns a Computer runtime");
		}
		return () -> {
			synchronized (services) { services.remove(server, runtime); }
		};
	}

	private static Object freezeValue(Object value) {
		if (value instanceof Map<?, ?> map) {
			LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
			map.forEach((key, entry) -> result.put(freezeValue(key), freezeValue(entry)));
			return java.util.Collections.unmodifiableMap(result);
		}
		if (value instanceof Set<?> set) {
			java.util.LinkedHashSet<Object> result = new java.util.LinkedHashSet<>();
			set.forEach(entry -> result.add(freezeValue(entry)));
			return java.util.Collections.unmodifiableSet(result);
		}
		if (value instanceof Collection<?> collection) {
			ArrayList<Object> result = new ArrayList<>(collection.size());
			collection.forEach(entry -> result.add(freezeValue(entry)));
			return java.util.Collections.unmodifiableList(result);
		}
		return value;
	}


	private static ProductionComputer formedTrader() throws ReflectiveOperationException {
		ProductionComputer computer = TestComputer.create(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
		physical(computer).installResidentSnapshot(capturedTrader(), TRADER);
		ProductionStructure snapshot = new ProductionStructure(new ProductionLayout(productionBounds(new BoundingBox(0, 0, 0, 2, 2, 2)), List.of(new ProductionLayout.Member(COMPUTER, productionAddress(address(BlockPos.ZERO)), TRADER)), List.of()), casing(), Set.of(new ChunkPos(0, 0)));
		ProductionEpoch epoch = ProductionEpoch.freeze(CLUSTER, STRUCTURE, snapshot.layout());
		computer.applyTopologyState(new ProductionStructure.Revision(STRUCTURE, 1, COMPUTER, snapshot),
			new ClusterBinding(CLUSTER, 1, null, List.of()), true, epoch, Set.of());
		field("availabilityReason").set(computer, ComputerAvailabilityReason.NONE);
		field("persistenceAvailable").setBoolean(computer, true);
		assertTrue(computer.canConfigureExternalListeners());
		return computer;
	}
	private static ProductionComputer restoreComputer(CompoundTag saved)
		throws ReflectiveOperationException {
		ProductionComputer restored = TestComputer.create(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
		physical(restored).read(saved, RegistryAccess.EMPTY, false);
		field("availabilityReason").set(restored, ComputerAvailabilityReason.NONE);
		field("persistenceAvailable").setBoolean(restored, true);
		assertTrue(restored.canConfigureExternalListeners());
		return restored;
	}
	private static FactoryOrchestrationSavedData reloadJournal(CompoundTag saved)
		throws ReflectiveOperationException {
		Method load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load", CompoundTag.class,
			net.minecraft.core.HolderLookup.Provider.class);
		load.setAccessible(true);
		return (FactoryOrchestrationSavedData) load.invoke(null, saved.copy(), RegistryAccess.EMPTY);
	}
	private static RadioRouter durableRouter(FactoryOrchestrationSavedData journal) {
		return new RadioRouter(8, 32, 8, 8, state -> {}, state -> {},
			new JournalAuthorityStore(journal));
	}
	private static DurableRadioOutbox durableOutbox(FactoryOrchestrationSavedData journal) {
		return new DurableRadioOutbox(8, new JournalOutboxStore(journal));
	}
	private static RadioRouter productionDurableRouter(FactoryOrchestrationSavedData journal) {
		return new RadioRouter(CONFIG.ordinaryListenerRange(), CONFIG.wanderingTraderListenerRange(),
			CONFIG.recoveryLedgerCapacity(), CONFIG.recoveryLedgerCapacity(), state -> {}, state -> {},
			new JournalAuthorityStore(journal, "radio/authority"));
	}
	private static RadioRouter listenerEnvelopeBuilder(RadioEndpoint source, RadioEndpoint relay,
		SubnetBoundaryBinding binding) {
		RadioRouter result = new RadioRouter(CONFIG.ordinaryListenerRange(),
			CONFIG.wanderingTraderListenerRange(), CONFIG.recoveryLedgerCapacity());
		RadioRouter.ListenerAuthority authority = new RadioRouter.ListenerAuthority(source, relay, binding);
		assertTrue(result.compareAndSetListenerAuthority(result.prepareListenerPublication(
			Optional.empty(), authority).orElseThrow()));
		return result;
	}
	private static DurableRadioOutbox productionDurableOutbox(FactoryOrchestrationSavedData journal) {
		return new DurableRadioOutbox(8, new JournalOutboxStore(journal, "radio/outbox"));
	}

	private static FactoryComputeWorker worker(FactoryRuntimeService runtime)
		throws ReflectiveOperationException {
		Field worker = FactoryRuntimeService.class.getDeclaredField("worker");
		worker.setAccessible(true);
		return (FactoryComputeWorker) worker.get(runtime);
	}

	private static void awaitWorkerResults(FactoryComputeWorker worker) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (workerResultCount(worker) == 0 && System.nanoTime() < deadline) Thread.sleep(5);
		assertTrue(workerResultCount(worker) > 0, "the worker must complete before the frozen Panel read");
	}

	@SuppressWarnings("unchecked")
	private static int workerResultCount(FactoryComputeWorker worker) throws ReflectiveOperationException {
		Field output = FactoryComputeWorker.class.getDeclaredField("output");
		output.setAccessible(true);
		return ((BlockingQueue<FactoryComputeWorker.ComputeResult>) output.get(worker)).size();
	}

	private static void awaitNoListenerProcess(FactoryRuntimeService runtime) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
		while (runtime.snapshot(CLUSTER).orElseThrow().processes().values().stream()
			.anyMatch(process -> process.kind() == ProcessKind.RADIO_LISTENER)
			&& System.nanoTime() < deadline) Thread.sleep(5);
		assertEquals(0, runtime.snapshot(CLUSTER).orElseThrow().processes().values().stream()
			.filter(process -> process.kind() == ProcessKind.RADIO_LISTENER).count());
	}
	private static ClusterEngineState unoccupiedListenerState(ProductionEpoch epoch, UUID computerId) {
		return new ClusterEngineState(CLUSTER, epoch.epochId(), 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), Map.of(), List.of(new ClusterEngineState.MemberSnapshot(
				computerId, TRADER, true, 1, 1)), Map.of(), Set.of(), 0, null);
	}

	private static Field field(String name) throws ReflectiveOperationException {
		Field field = ProductionComputer.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
	private static CallbackServer callbackServer() throws ReflectiveOperationException {
		bootstrap();
		MinecraftServer server = allocate(DedicatedServer.class);
		setInstanceField(server, "serverThread", Thread.currentThread());
		ServerLevel level = allocate(ServerLevel.class);
		setInstanceField(level, "server", server);
		ServerLevelData levelData = (ServerLevelData) Proxy.newProxyInstance(
			ServerLevelData.class.getClassLoader(), new Class<?>[] { ServerLevelData.class, WritableLevelData.class },
			(proxy, method, ignored) -> method.getName().equals("getGameTime") ? 0L
				: primitiveDefault(method.getReturnType()));
		setInstanceField(level, "levelData", levelData);
		setInstanceField(level, "serverLevelData", levelData);
		setInstanceField(server, "levels", Map.of(Level.OVERWORLD, level));
		return new CallbackServer(server, level);
	}
	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
		Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		return (T) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(type);
	}
	private static void setInstanceField(Object target, String name, Object value)
		throws ReflectiveOperationException {
		for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException ignored) {}
		}
		throw new NoSuchFieldException(name);
	}
	private static Object primitiveDefault(Class<?> type) {
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == char.class) return '\0';
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0F;
		if (type == double.class) return 0D;
		return null;
	}
	private record CallbackServer(MinecraftServer server, ServerLevel level) {}
	private record FrozenRuntimeState(Map<String, Object> runtime,
		Map<String, Object> worker, RadioRouter.AuthorityState radioAuthority,
		FactoryOrchestrationServerTestAccess.RuntimeAuthoritySnapshot authority,
		CompoundTag journal) {}

	private static int listenerProcesses(FactoryRuntimeService runtime) {
		return (int) runtime.snapshot(CLUSTER).orElseThrow().processes().values().stream()
			.filter(process -> process.kind() == ProcessKind.RADIO_LISTENER).count();
	}
	private static UUID externalCluster(UUID computerId, int slot) {
		return named("external-listener-cluster", computerId, "slot:" + slot + ":source");
	}
	private static UUID sourceMember(UUID computerId, int slot) {
		return named("external-listener-member", computerId, "slot:" + slot + ":source");
	}
	private static UUID named(String prefix, UUID computerId, String value) {
		return UUID.nameUUIDFromBytes((prefix + ":" + computerId + ":" + value)
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
	private static void assertRoutes(RadioRouter router,
		com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding binding,
		CommunicationFrequency frequency, long message) {
		RadioEndpoint source = router.registeredEndpoint(binding.sourceHop().key()).orElseThrow();
		RadioEndpoint relay = router.registeredEndpoint(binding.targetHop().key()).orElseThrow();
		assertTrue(router.forwardBoundary(RadioEnvelope.create(id(message), source, relay, frequency,
			new ProcessProtocolMessage.Data("two-frequency"),
			SubnetRouteTrace.start(binding.sourceNetworkId(), binding.sourceClusterId()))).isPresent());
	}
	private static java.util.Optional<UUID> submitOrdinaryGoal(FactoryRuntimeService runtime, UUID epoch,
		long identity) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
		java.util.Optional<UUID> result = java.util.Optional.empty();
		while (result.isEmpty() && System.nanoTime() < deadline) {
			var state = runtime.snapshot(CLUSTER).orElseThrow();
			result = runtime.submitPanelGoal(CLUSTER, epoch, state.generation(), id(identity),
				new CreateRouteKey(id(identity + 100), "ordinary"), 1, InputMode.BULK, 0);
			if (result.isEmpty()) Thread.sleep(5);
		}
		return result;
	}
	private static void awaitOrdinaryGoalProcesses(FactoryRuntimeService runtime, int expected)
		throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
		while (runtime.snapshot(CLUSTER).orElseThrow().processes().values().stream()
			.filter(process -> process.kind() == ProcessKind.GOAL).count() < expected
			&& System.nanoTime() < deadline) Thread.sleep(5);
		assertEquals(expected, runtime.snapshot(CLUSTER).orElseThrow().processes().values().stream()
			.filter(process -> process.kind() == ProcessKind.GOAL).count());
	}
	private static CompoundTag save(ProductionComputer computer) {
		CompoundTag result = new CompoundTag(); physical(computer).write(result, RegistryAccess.EMPTY, false); return result;
	}
	private static RemoteProcessLease leaseOwnedBy(RadioEndpoint source, RadioEndpoint receiver) {
		return leaseOwnedBy(source, receiver, 60);
	}
	private static RemoteProcessLease leaseOwnedBy(RadioEndpoint source, RadioEndpoint receiver,
		long identity) {
		StackKey ingredient = new StackKey(new ItemStack(Items.IRON_INGOT));
		UUID sourceGoalId = id(identity);
		UUID sourceProcessId = id(identity + 1);
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(RemoteExpansionTestRegistries.provider(),
			sourceGoalId, sourceProcessId, 0, 4, id(identity + 2), 2, id(identity + 3), 4,
			InputMode.BULK, 1,
			List.of(new RemoteExpansionRequest.StageInput(0, new CreateRouteKey(id(identity + 4), "foreign"),
				Map.of(ingredient, 1L))), Map.of(ingredient, 1L));
		return new RemoteProcessLease(id(identity + 5), sourceGoalId, sourceProcessId,
			source.clusterId(), receiver.clusterId(),
			source.generation(), receiver.generation(), ProductionCapability.EXTENDED_RADIO, request, 4);
	}
	private static SpaceAddress address(BlockPos pos) { return new SpaceAddress(Level.OVERWORLD, null, pos); }
	private static RadioTopologyTracker.Pose topologyPose(RadioEndpoint endpoint) {
		return new RadioTopologyTracker.Pose(java.util.Optional.ofNullable(endpoint.address().subLevelId()),
			Vec3.atCenterOf(endpoint.address().localPos()));
	}
	private static UUID id(long value) { return new UUID(0, value); }
	private static Set<BlockPos> casing() {
		java.util.LinkedHashSet<BlockPos> result = new java.util.LinkedHashSet<>();
		for (int x = 0; x <= 2; x++) for (int y = 0; y <= 2; y++) for (int z = 0; z <= 2; z++)
			if (x == 0 || x == 2 || y == 0 || y == 2 || z == 0 || z == 2) result.add(new BlockPos(x, y, z));
		return result;
	}
	private static ItemStack capturedTrader() {
		ItemStack stack = new ItemStack(Items.PAPER);
		CompoundTag captured = new CompoundTag();
		captured.putString("id", "minecraft:wandering_trader");
		captured.putUUID("UUID", COMPUTER);
		captured.putString("CustomName", "Trader");
		CompoundTag data = new CompoundTag(); data.put("CapturedEntity", captured);
		CBItemData.set(stack, data);
		return stack;
	}
	private static void bootstrap() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			Field field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}

	private static final class JournalAuthorityStore implements RadioRouter.AuthorityStateStore {
		private final FactoryOrchestrationSavedData journal;
		private final String key;
		private JournalAuthorityStore(FactoryOrchestrationSavedData journal) {
			this(journal, "test/radio_authority");
		}
		private JournalAuthorityStore(FactoryOrchestrationSavedData journal, String key) {
			this.journal = journal;
			this.key = key;
		}
		@Override public Optional<RadioRouter.AuthorityState> load() {
			return journal.read(key, RadioRouter.AuthorityState.class,
				RegistryAccess.EMPTY);
		}
		@Override public boolean compareAndSet(RadioRouter.AuthorityState expected,
			RadioRouter.AuthorityState replacement) {
			RadioRouter.AuthorityState actual = load().orElseGet(() ->
				new RadioRouter.AuthorityState(0, Map.of(), Map.of()));
			if (!actual.equals(expected)) return false;
			journal.write(key, replacement, RegistryAccess.EMPTY);
			return true;
		}
	}

	private static final class RecordingAuthorityStore implements RadioRouter.AuthorityStateStore {
		private RadioRouter.AuthorityState state = new RadioRouter.AuthorityState(0, Map.of(), Map.of());
		private boolean reject;
		private boolean throwFailure;
		private int casCalls;
		private int rejectAtCall = -1;
		@Override public Optional<RadioRouter.AuthorityState> load() { return Optional.of(state); }
		@Override public boolean compareAndSet(RadioRouter.AuthorityState expected,
			RadioRouter.AuthorityState replacement) {
			casCalls++;
			if (throwFailure) throw new IllegalStateException("injected authority store failure");
			if (reject || casCalls == rejectAtCall || !state.equals(expected)) return false;
			state = replacement;
			return true;
		}
	}

	private static final class JournalOutboxStore implements DurableRadioOutbox.StateStore {
		private final FactoryOrchestrationSavedData journal;
		private final String key;
		private JournalOutboxStore(FactoryOrchestrationSavedData journal) {
			this(journal, "test/radio_outbox");
		}
		private JournalOutboxStore(FactoryOrchestrationSavedData journal, String key) {
			this.journal = journal;
			this.key = key;
		}
		@Override public Optional<DurableRadioOutbox.State> load() {
			return journal.read(key, DurableRadioOutbox.State.class,
				RegistryAccess.EMPTY);
		}
		@Override public void persist(DurableRadioOutbox.State state) {
			journal.write(key, state, RegistryAccess.EMPTY);
		}
	}
	private static ProductionLayout.Address productionAddress(SpaceAddress address) {
		return new ProductionLayout.Address(address.dimension(), address.subLevelId(), address.localPos());
	}
	private static ProductionLayout.Bounds productionBounds(BoundingBox bounds) {
		return new ProductionLayout.Bounds(bounds.minX(), bounds.minY(), bounds.minZ(),
			bounds.maxX(), bounds.maxY(), bounds.maxZ());
	}
}
