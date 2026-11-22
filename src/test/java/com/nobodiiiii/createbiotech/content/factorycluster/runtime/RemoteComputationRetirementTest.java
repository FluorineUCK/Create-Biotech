package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionPublication;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.DurableRadioOutbox;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class RemoteComputationRetirementTest {
	@BeforeAll
	static void bootstrapMinecraft() {
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
	void failureBeforeTheCompositionRootBatchPreservesEveryActiveFenceAndMaterialConsumption() {
		RetirementFixture fixture = retirementFixture();
		AtomicReference<RemoteExpansionProtocol.RemoteComputationLifecycleState> installed =
			new AtomicReference<>(fixture.activeLifecycle());

		assertThrows(SimulatedCrash.class, () ->
			RemoteExpansionPublication.publishRemoteComputationRetirement(
				(lifecycle, leases) -> { throw new SimulatedCrash(); },
				fixture.retiredLifecycle(), fixture.routerRetirement(), installed::set,
				fixture.router()::completePersistedLeaseRetirement,
				fixture.router()::retireDeliveredRoute, fixture.messageIds()));

		assertEquals(fixture.activeLifecycle(), installed.get());
		assertEquals(fixture.activeLifecycle(), fixture.journal().read(
			FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			RemoteExpansionProtocol.RemoteComputationLifecycleState.class,
			RegistryAccess.EMPTY).orElseThrow());
		assertEquals(1, fixture.router().activeLeaseCount());
		assertEquals(4, fixture.router().activeLeaseRouteCount());

		RadioRouter reloaded = reloadRouter(fixture.journal(), fixture.source(), fixture.receiver());
		assertEquals(1, reloaded.activeLeaseCount());
		assertEquals(0, reloaded.retiredLeaseLineageCount());
		assertFalse(reloaded.canCommitMaterialIntent(fixture.receiver().key(),
			fixture.lease().leaseId(), fixture.lease().revisionFence()),
			"the pre-retirement reload must retain the already-consumed one-shot material fence");
	}

	@Test
	void mismatchedLifecycleAndRouterRetirementCannotCrossTheAtomicBatchBoundary() {
		RetirementFixture fixture = retirementFixture();
		AtomicBoolean durableWriterCalled = new AtomicBoolean();

		assertThrows(IllegalArgumentException.class, () ->
			RemoteExpansionPublication.publishRemoteComputationRetirement(
				(lifecycle, leases) -> durableWriterCalled.set(true),
				fixture.activeLifecycle(), fixture.routerRetirement(), ignored -> {},
				ignored -> {}, ignored -> true, fixture.messageIds()));

		assertFalse(durableWriterCalled.get(),
			"an active exchange/job cannot be paired with a retired router lease");
		assertEquals(1, fixture.router().activeLeaseCount());
		assertEquals(0, fixture.router().retiredLeaseLineageCount());
	}

	@Test
	void crashAfterTheCompositionRootBatchReloadsOnlyBoundedTombstonesAndIsIdempotent() {
		RetirementFixture fixture = retirementFixture();
		AtomicReference<RemoteExpansionProtocol.RemoteComputationLifecycleState> installed =
			new AtomicReference<>(fixture.activeLifecycle());

		assertThrows(SimulatedCrash.class, () ->
			RemoteExpansionPublication.publishRemoteComputationRetirement(
				(lifecycle, leases) -> {
					fixture.journal().writeBatch(Map.of(
						FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY, lifecycle,
						FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY, leases),
						RegistryAccess.EMPTY);
					throw new SimulatedCrash();
				}, fixture.retiredLifecycle(), fixture.routerRetirement(), installed::set,
				fixture.router()::completePersistedLeaseRetirement,
				fixture.router()::retireDeliveredRoute, fixture.messageIds()));

		assertEquals(fixture.activeLifecycle(), installed.get(),
			"a process crash may happen before volatile state installation");
		assertEquals(1, fixture.router().activeLeaseCount());
		assertEquals(4, fixture.router().activeLeaseRouteCount());
		RemoteExpansionProtocol.RemoteComputationLifecycleState reloadedLifecycle =
			fixture.journal().read(
				FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
				RemoteExpansionProtocol.RemoteComputationLifecycleState.class,
				RegistryAccess.EMPTY).orElseThrow();
		assertEquals(fixture.retiredLifecycle(), reloadedLifecycle);
		assertTrue(reloadedLifecycle.exchanges().isEmpty());
		assertTrue(reloadedLifecycle.jobs().isEmpty());
		assertEquals(1, reloadedLifecycle.retiredChildren().size());

		RadioRouter reloaded = reloadRouter(fixture.journal(), fixture.source(), fixture.receiver());
		assertEquals(0, reloaded.activeLeaseCount());
		assertEquals(1, reloaded.retiredLeaseLineageCount());
		assertEquals(0, reloaded.activeRouteCount(),
			"ephemeral tick scans are empty after composition-root reload");
		assertTrue(reloaded.issueMaterialIntentPermit(fixture.receiver().key(),
			fixture.lease().leaseId(), fixture.lease().revisionFence()).isEmpty(),
			"a durable tombstone cannot recreate material authority");

		RadioRouter.CompletedLeaseRetirement replay = reloaded.prepareCompletedLeaseRetirement(
			fixture.lease(), fixture.result()).orElseThrow();
		AtomicReference<RemoteExpansionProtocol.RemoteComputationLifecycleState> replayInstalled =
			new AtomicReference<>(reloadedLifecycle);
		RemoteExpansionPublication.publishRemoteComputationRetirement(
			(lifecycle, leases) -> fixture.journal().writeBatch(Map.of(
				FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY, lifecycle,
				FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY, leases),
				RegistryAccess.EMPTY), reloadedLifecycle, replay, replayInstalled::set,
			reloaded::completePersistedLeaseRetirement, reloaded::retireDeliveredRoute,
			fixture.messageIds());
		assertEquals(reloadedLifecycle, replayInstalled.get());
		assertEquals(0, reloaded.activeLeaseCount());
		assertEquals(1, reloaded.retiredLeaseLineageCount());
		assertEquals(0, reloaded.activeRouteCount());
	}

	@Test
	void cancelledPreResultComputationAtomicallyRetiresLifecycleRouterAndOutboxWithoutMaterial() {
		TerminalFixture fixture = cancellationFixture();
		AtomicReference<RemoteExpansionProtocol.RemoteComputationLifecycleState> installed =
			new AtomicReference<>(fixture.activeLifecycle());

		assertThrows(SimulatedCrash.class, () ->
			RemoteExpansionPublication.publishRemoteComputationTermination(
				(lifecycle, leases, outbox) -> {
					fixture.journal().writeBatch(Map.of(
						FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY, lifecycle,
						FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY, leases,
						FactoryOrchestrationServer.RADIO_OUTBOX_KEY, outbox), RegistryAccess.EMPTY);
					throw new SimulatedCrash();
				}, fixture.terminalLifecycle(), fixture.routerRetirement(),
				fixture.terminalOutbox(), installed::set,
				fixture.router()::completePersistedLeaseRetirement, ignored -> {},
				fixture.router()::retireTerminalRoute, fixture.messageIds()));

		assertEquals(fixture.activeLifecycle(), installed.get(),
			"a crash immediately after the durable batch may precede volatile installation");
		RemoteExpansionProtocol.RemoteComputationLifecycleState reloadedLifecycle =
			fixture.journal().read(FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
				RemoteExpansionProtocol.RemoteComputationLifecycleState.class,
				RegistryAccess.EMPTY).orElseThrow();
		assertEquals(fixture.terminalLifecycle(), reloadedLifecycle);
		assertTrue(reloadedLifecycle.exchanges().isEmpty());
		assertTrue(reloadedLifecycle.jobs().isEmpty());
		assertEquals(RemoteExpansionProtocol.RemoteComputationTerminalOutcome.CANCELLED,
			reloadedLifecycle.retiredChildren().values().iterator().next().outcome());

		RadioRouter reloaded = reloadRouter(fixture.journal(), fixture.source(), fixture.receiver());
		assertEquals(0, reloaded.activeLeaseCount());
		assertEquals(1, reloaded.retiredLeaseLineageCount());
		assertTrue(reloaded.issueMaterialIntentPermit(fixture.receiver().key(),
			fixture.lease().leaseId(), fixture.lease().revisionFence()).isEmpty());
		DurableRadioOutbox reloadedOutbox = new DurableRadioOutbox(8,
			new JournalOutboxStore(fixture.journal()));
		assertTrue(reloadedOutbox.snapshot().entries().isEmpty(),
			"the obsolete OFFER cannot replay after the terminal batch");
	}

	@Test
	void sourceProcessGenerationMigrationReissuesOnlyAHighFencedLeaseWithoutDoubleMaterial() {
		TerminalFixture fixture = migrationFixture();
		RemoteExpansionRequest oldRequest = fixture.lease().expansionRequest();
		assertEquals(InputMode.BULK, oldRequest.inputMode(),
			"the production migration regression uses the same multi-unit mode as the GameTest");
		GoalRecord sourceGoal = new GoalRecord(oldRequest.sourceGoalId(),
			fixture.source().clusterId(), new GoalSubmission(oldRequest.sourceGoalId(),
				oldRequest.patternId(), oldRequest.runMultiplier(), oldRequest.inputMode()),
			new CreateRouteKey(id(965), "migration_output"), new PolicySnapshot(8, 3, false),
			GoalState.RUNNABLE, null);
		String goalKey = "migration_goal/" + sourceGoal.goalId();
		fixture.journal().write(goalKey, sourceGoal, RegistryAccess.EMPTY);
		AtomicReference<RemoteExpansionProtocol.RemoteComputationLifecycleState>
			installedLifecycle = new AtomicReference<>(fixture.activeLifecycle());
		AtomicReference<DurableRadioOutbox.State> installedOutbox =
			new AtomicReference<>(fixture.activeOutbox());

		RemoteExpansionPublication.publishRemoteComputationTermination(
			(lifecycle, leases, outbox) -> fixture.journal().writeBatch(Map.of(
				FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY, lifecycle,
				FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY, leases,
				FactoryOrchestrationServer.RADIO_OUTBOX_KEY, outbox), RegistryAccess.EMPTY),
			fixture.terminalLifecycle(), fixture.routerRetirement(), fixture.terminalOutbox(),
			installedLifecycle::set, fixture.router()::completePersistedLeaseRetirement,
			installedOutbox::set, fixture.router()::retireTerminalRoute,
			fixture.messageIds());

		assertEquals(fixture.terminalLifecycle(), installedLifecycle.get());
		assertEquals(fixture.terminalOutbox(), installedOutbox.get());
		assertEquals(0, fixture.router().activeLeaseCount());
		assertEquals(0, fixture.router().activeRouteCount());
		RemoteExpansionProtocol.RemoteComputationLifecycleState migrated = fixture.journal()
			.read(FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
				RemoteExpansionProtocol.RemoteComputationLifecycleState.class,
				RegistryAccess.EMPTY).orElseThrow();
		assertTrue(migrated.exchanges().isEmpty(), "the old exchange must clear atomically");
		assertTrue(migrated.jobs().isEmpty(), "the old receiver job must clear atomically");
		assertEquals(1, migrated.retiredChildren().size());
		assertEquals(RemoteExpansionProtocol.RemoteComputationTerminalOutcome.MIGRATED,
			migrated.retiredChildren().values().iterator().next().outcome());
		assertEquals(sourceGoal, fixture.journal().read(goalKey, GoalRecord.class,
			RegistryAccess.EMPTY).orElseThrow(), "migration must preserve the runnable Goal");
		DurableRadioOutbox emptyAfterMigration = new DurableRadioOutbox(8,
			new JournalOutboxStore(fixture.journal()));
		assertTrue(emptyAfterMigration.snapshot().entries().isEmpty(),
			"the old OFFER must not survive the migration batch");

		RadioRouter reloaded = reloadRouter(fixture.journal(), fixture.source(),
			fixture.receiver());
		assertEquals(0, reloaded.activeLeaseCount());
		assertEquals(1, reloaded.retiredLeaseLineageCount());
		RadioEnvelope staleOffer = envelope(fixture.messageIds().getFirst(), fixture.source(),
			fixture.receiver(), new ProcessProtocolMessage.LeaseOffer(fixture.lease()));
		assertThrows(IllegalArgumentException.class,
			() -> reloaded.submit(staleOffer, List.of()),
			"reload must reject replay at the migrated high-watermark");
		assertTrue(reloaded.issueMaterialIntentPermit(fixture.receiver().key(),
			fixture.lease().leaseId(), fixture.lease().revisionFence()).isEmpty());

		RemoteExpansionRequest replacementRequest = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), oldRequest.sourceGoalId(),
			oldRequest.parentProcessId(), oldRequest.parentProcessGeneration() + 1,
			oldRequest.parentStateGeneration() + 1, oldRequest.patternId(),
			oldRequest.patternVersion(), oldRequest.runId(), oldRequest.runFence() + 1,
			oldRequest.inputMode(), oldRequest.runMultiplier(), oldRequest.stages(),
			oldRequest.dependencyManifest());
		assertEquals(RemoteExpansionRevision.logicalChildId(oldRequest),
			RemoteExpansionRevision.logicalChildId(replacementRequest),
			"one Run migration must retain one logical child lineage");
		assertNotEquals(oldRequest.fingerprint(), replacementRequest.fingerprint());
		assertEquals(oldRequest.parentProcessGeneration() + 1,
			replacementRequest.parentProcessGeneration());
		RemoteProcessLease replacementLease = new RemoteProcessLease(fixture.lease().leaseId(),
			replacementRequest.sourceGoalId(), replacementRequest.parentProcessId(),
			fixture.source().clusterId(), fixture.receiver().clusterId(),
			fixture.source().generation(), fixture.receiver().generation(),
			ProductionCapability.ALCHEMIST, replacementRequest,
			replacementRequest.parentStateGeneration());
		assertTrue(replacementLease.revisionFence() > fixture.lease().revisionFence());
		assertEquals(fixture.lease().revisionFence() + 1,
			ProductionRemoteExpansion.nextRemoteComputationRevision(
				fixture.lease().revisionFence(), oldRequest.sourceGoalId(), oldRequest.runId(),
				fixture.terminalLifecycle().retiredChildren().values()),
			"a reissued Run must cross the durable retired high-watermark even when the live "
				+ "cluster generation did not advance");
		RadioEnvelope replacementOffer = envelope(fixture.messageIds().getFirst(),
			fixture.source(), fixture.receiver(),
			new ProcessProtocolMessage.LeaseOffer(replacementLease));
		RemoteExpansionProtocol.RemoteComputationExchange replacementExchange =
			new RemoteExpansionProtocol.RemoteComputationExchange(
				replacementRequest.sourceGoalId(), replacementRequest.runId(), replacementLease,
				replacementLease.revisionFence(), replacementOffer, Optional.empty(),
				Optional.empty(), Optional.empty(), List.of(), List.of(),
				RemoteExpansionProtocol.RemoteComputationPhase.OFFER_PENDING);
		RemoteExpansionProtocol.RemoteComputationLifecycleState replacementLifecycle =
			new RemoteExpansionProtocol.RemoteComputationLifecycleState(
				Map.of(replacementRequest.runId(), replacementExchange), Map.of(), Map.of());
		fixture.journal().write(FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			replacementLifecycle, RegistryAccess.EMPTY);

		DurableRadioOutbox replacementOutbox = new DurableRadioOutbox(8,
			new JournalOutboxStore(fixture.journal()));
		assertTrue(replacementOutbox.offer(replacementOffer, List.of()),
			"the cleared durable message id must admit the higher-fenced reissue");
		assertEquals(1, replacementOutbox.pump(reloaded, 1).size());
		assertTrue(replacementOutbox.markApplied(replacementOffer.messageId()));
		assertTrue(reloaded.retireDeliveredRoute(replacementOffer.messageId()));
		assertEquals(1, reloaded.activeLeaseCount());
		assertEquals(0, reloaded.retiredLeaseLineageCount(),
			"the accepted higher fence replaces the migrated high-watermark");

		RemoteExpansionRevision replacementRevision =
			RemoteExpansionRevision.compute(replacementRequest);
		RadioEnvelope revised = envelope(id(963), fixture.receiver(), fixture.source(),
			new ProcessProtocolMessage.LeaseRevision(replacementLease.leaseId(),
				replacementLease.revisionFence(), replacementRevision));
		RadioEnvelope acknowledged = envelope(id(964), fixture.source(), fixture.receiver(),
			new ProcessProtocolMessage.RevisionAcknowledgement(replacementLease.leaseId(),
				replacementLease.revisionFence()));
		deliver(reloaded, revised, 2);
		deliver(reloaded, acknowledged, 3);
		RadioRouter.MaterialIntentPermit permit = reloaded.issueMaterialIntentPermit(
			fixture.receiver().key(), replacementLease.leaseId(),
			replacementLease.revisionFence()).orElseThrow();
		assertTrue(reloaded.consumeMaterialIntentPermit(permit));
		assertFalse(reloaded.consumeMaterialIntentPermit(permit),
			"one material permit cannot be consumed twice");
		assertTrue(reloaded.issueMaterialIntentPermit(fixture.receiver().key(),
			replacementLease.leaseId(), replacementLease.revisionFence()).isEmpty());

		RadioRouter afterMaterialReload = reloadRouter(fixture.journal(), fixture.source(),
			fixture.receiver());
		assertEquals(1, afterMaterialReload.activeLeaseCount());
		assertTrue(afterMaterialReload.issueMaterialIntentPermit(fixture.receiver().key(),
			replacementLease.leaseId(), replacementLease.revisionFence()).isEmpty(),
			"reload must retain the consumed material fence");
		DurableRadioOutbox replayOutbox = new DurableRadioOutbox(8,
			new JournalOutboxStore(fixture.journal()));
		assertTrue(replayOutbox.offer(replacementOffer, List.of()));
		assertEquals(1, replayOutbox.pump(afterMaterialReload, 1).size());
		assertTrue(replayOutbox.markApplied(replacementOffer.messageId()));
		assertTrue(afterMaterialReload.retireDeliveredRoute(replacementOffer.messageId()));
		assertTrue(afterMaterialReload.issueMaterialIntentPermit(fixture.receiver().key(),
			replacementLease.leaseId(), replacementLease.revisionFence()).isEmpty(),
			"replayed higher-fenced OFFER must not recreate material authority");
		assertThrows(IllegalStateException.class,
			() -> afterMaterialReload.submit(staleOffer, List.of()),
			"the old process-generation payload must conflict with the active replacement");
		assertEquals(sourceGoal, fixture.journal().read(goalKey, GoalRecord.class,
			RegistryAccess.EMPTY).orElseThrow());
	}

	private static RetirementFixture retirementFixture() {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		RadioEndpoint source = endpoint(id(801), id(802), 0, 4, Set.of());
		RadioEndpoint receiver = endpoint(id(803), id(804), 8, 5,
			Set.of(ProductionCapability.ALCHEMIST));
		JournalLeaseStore store = new JournalLeaseStore(journal);
		RadioRouter router = new RadioRouter(16, 48, 32, 32,
			state -> {}, store);
		router.register(source); router.register(receiver);
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(805), id(806), 0, 8,
			id(807), 2, id(808), 8, InputMode.BULK, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(809), "retire_input"), Map.of(iron, 1L))),
			Map.of(iron, 1L));
		RemoteProcessLease lease = new RemoteProcessLease(id(810), request.sourceGoalId(),
			request.parentProcessId(), source.clusterId(), receiver.clusterId(),
			source.generation(), receiver.generation(), ProductionCapability.ALCHEMIST,
			request, request.parentStateGeneration());
		RemoteExpansionRevision revision = RemoteExpansionRevision.compute(request);
		RemoteExpansionResult result = RemoteExpansionResult.compute(request, revision);
		RadioEnvelope offer = envelope(id(811), source, receiver,
			new ProcessProtocolMessage.LeaseOffer(lease));
		RadioEnvelope revised = envelope(id(812), receiver, source,
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), lease.revisionFence(),
				revision));
		RadioEnvelope acknowledged = envelope(id(813), source, receiver,
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(),
				lease.revisionFence()));
		RadioEnvelope completed = envelope(id(814), receiver, source,
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), lease.revisionFence(),
				result));
		deliver(router, offer, 1);
		deliver(router, revised, 2);
		deliver(router, acknowledged, 3);
		RadioRouter.MaterialIntentPermit permit = router.issueMaterialIntentPermit(
			receiver.key(), lease.leaseId(), lease.revisionFence()).orElseThrow();
		assertTrue(router.consumeMaterialIntentPermit(permit));
		deliver(router, completed, 4);

		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			new RemoteExpansionProtocol.RemoteComputationExchange(request.sourceGoalId(),
				request.runId(), lease, lease.revisionFence(), offer, Optional.of(revised),
				Optional.of(acknowledged), Optional.of(completed), List.of(), List.of(),
				RemoteExpansionProtocol.RemoteComputationPhase.COMPLETED);
		RemoteExpansionProtocol.RemoteComputationJob job =
			new RemoteExpansionProtocol.RemoteComputationJob(request.runId(), lease,
				receiver.memberId(), 0, id(815),
				RemoteExpansionProtocol.RemoteComputationJobPhase.COMPLETED,
				Optional.of(revision), Optional.of(result));
		RemoteExpansionProtocol.RemoteComputationLifecycleState active =
			new RemoteExpansionProtocol.RemoteComputationLifecycleState(
				Map.of(request.runId(), exchange), Map.of(request.runId(), job), Map.of());
		journal.write(FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			active, RegistryAccess.EMPTY);
		UUID child = RemoteExpansionRevision.logicalChildId(request);
		RemoteExpansionProtocol.RetiredRemoteComputation tombstone =
			new RemoteExpansionProtocol.RetiredRemoteComputation(child,
				request.sourceGoalId(), request.runId(), lease.leaseId(), lease.revisionFence(),
				RemoteExpansionProtocol.RemoteComputationTerminalOutcome.COMPLETED,
				request.fingerprint(), Optional.of(result.fingerprint()));
		RemoteExpansionProtocol.RemoteComputationLifecycleState retired =
			new RemoteExpansionProtocol.RemoteComputationLifecycleState(Map.of(), Map.of(),
				Map.of(child, tombstone));
		RadioRouter.CompletedLeaseRetirement routerRetirement = router
			.prepareCompletedLeaseRetirement(lease, result).orElseThrow();
		return new RetirementFixture(journal, router, source, receiver, lease, result,
			active, retired, routerRetirement,
			List.of(offer.messageId(), revised.messageId(), acknowledged.messageId(),
				completed.messageId()));
	}

	private static TerminalFixture cancellationFixture() {
		return terminalFixture(900,
			RemoteExpansionProtocol.RemoteComputationTerminalOutcome.CANCELLED,
			RadioRouter.LeaseTerminalOutcome.CANCELLED, InputMode.EXACT);
	}

	private static TerminalFixture migrationFixture() {
		return terminalFixture(950,
			RemoteExpansionProtocol.RemoteComputationTerminalOutcome.MIGRATED,
			RadioRouter.LeaseTerminalOutcome.MIGRATED, InputMode.BULK);
	}

	private static TerminalFixture terminalFixture(long base,
		RemoteExpansionProtocol.RemoteComputationTerminalOutcome lifecycleOutcome,
		RadioRouter.LeaseTerminalOutcome routerOutcome, InputMode inputMode) {
		FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
		RadioEndpoint source = endpoint(id(base + 1), id(base + 2), 0, 4, Set.of());
		RadioEndpoint receiver = endpoint(id(base + 3), id(base + 4), 8, 5,
			Set.of(ProductionCapability.ALCHEMIST));
		JournalLeaseStore store = new JournalLeaseStore(journal);
		RadioRouter router = new RadioRouter(16, 48, 32, 32, state -> {}, store);
		router.register(source); router.register(receiver);
		StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(base + 5), id(base + 6), 0, 8,
			id(base + 7), 2, id(base + 8), 8, inputMode, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(base + 9), "terminal_input"), Map.of(iron, 1L))),
			Map.of(iron, 1L));
		RemoteProcessLease lease = new RemoteProcessLease(id(base + 10), request.sourceGoalId(),
			request.parentProcessId(), source.clusterId(), receiver.clusterId(), source.generation(),
			receiver.generation(), ProductionCapability.ALCHEMIST, request,
			request.parentStateGeneration());
		RadioEnvelope offer = envelope(id(base + 11), source, receiver,
			new ProcessProtocolMessage.LeaseOffer(lease));
		deliver(router, offer, 1);
		RemoteExpansionProtocol.RemoteComputationExchange exchange =
			new RemoteExpansionProtocol.RemoteComputationExchange(request.sourceGoalId(),
				request.runId(), lease, lease.revisionFence(), offer, Optional.empty(),
				Optional.empty(), Optional.empty(), List.of(), List.of(),
				RemoteExpansionProtocol.RemoteComputationPhase.REVISION_COMPUTING);
		RemoteExpansionProtocol.RemoteComputationJob job =
			new RemoteExpansionProtocol.RemoteComputationJob(request.runId(), lease,
				receiver.memberId(), 0, id(base + 12),
				RemoteExpansionProtocol.RemoteComputationJobPhase.REVISION_WORK_PENDING,
				Optional.empty(), Optional.empty());
		var active = new RemoteExpansionProtocol.RemoteComputationLifecycleState(
			Map.of(request.runId(), exchange), Map.of(request.runId(), job), Map.of());
		journal.write(FactoryOrchestrationServer.REMOTE_COMPUTATION_LIFECYCLE_KEY,
			active, RegistryAccess.EMPTY);
		DurableRadioOutbox.State activeOutbox = new DurableRadioOutbox.State(Map.of(
			offer.messageId(), new DurableRadioOutbox.Entry(offer, List.of(),
				RadioRouter.RouteStatus.DELIVERED)));
		journal.write(FactoryOrchestrationServer.RADIO_OUTBOX_KEY, activeOutbox,
			RegistryAccess.EMPTY);
		UUID child = RemoteExpansionRevision.logicalChildId(request);
		var tombstone = new RemoteExpansionProtocol.RetiredRemoteComputation(child,
			request.sourceGoalId(), request.runId(), lease.leaseId(), lease.revisionFence(),
			lifecycleOutcome,
			request.fingerprint(), Optional.empty());
		var terminal = new RemoteExpansionProtocol.RemoteComputationLifecycleState(
			Map.of(), Map.of(), Map.of(child, tombstone));
		RadioRouter.TerminalLeaseRetirement routerRetirement = router
			.prepareTerminalLeaseRetirement(lease, routerOutcome)
			.orElseThrow();
		return new TerminalFixture(journal, router, source, receiver, lease, active,
			terminal, routerRetirement, activeOutbox, new DurableRadioOutbox.State(Map.of()),
			List.of(offer.messageId()));
	}

	private static RadioRouter reloadRouter(FactoryOrchestrationSavedData journal,
		RadioEndpoint source, RadioEndpoint receiver) {
		RadioRouter reloaded = new RadioRouter(16, 48, 32, 32, state -> {},
			new JournalLeaseStore(journal));
		reloaded.register(source); reloaded.register(receiver);
		return reloaded;
	}

	private static void deliver(RadioRouter router, RadioEnvelope envelope, long tick) {
		router.submit(envelope, List.of());
		assertEquals(1, router.tick(tick).deliveries().size());
	}

	private static RadioEndpoint endpoint(UUID member, UUID cluster, int x, long generation,
		Set<ProductionCapability> capabilities) {
		return new RadioEndpoint(cluster, member, new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 64, 0)), generation, CommunicationFrequency.of("17"), 1, capabilities);
	}

	private static RadioEnvelope envelope(UUID messageId, RadioEndpoint source,
		RadioEndpoint target, ProcessProtocolMessage payload) {
		return RadioEnvelope.create(messageId, source, target, CommunicationFrequency.of("17"), payload);
	}

	private record RetirementFixture(FactoryOrchestrationSavedData journal, RadioRouter router,
		RadioEndpoint source, RadioEndpoint receiver, RemoteProcessLease lease,
		RemoteExpansionResult result,
		RemoteExpansionProtocol.RemoteComputationLifecycleState activeLifecycle,
		RemoteExpansionProtocol.RemoteComputationLifecycleState retiredLifecycle,
		RadioRouter.CompletedLeaseRetirement routerRetirement, List<UUID> messageIds) {}

	private record TerminalFixture(FactoryOrchestrationSavedData journal, RadioRouter router,
		RadioEndpoint source, RadioEndpoint receiver, RemoteProcessLease lease,
		RemoteExpansionProtocol.RemoteComputationLifecycleState activeLifecycle,
		RemoteExpansionProtocol.RemoteComputationLifecycleState terminalLifecycle,
		RadioRouter.TerminalLeaseRetirement routerRetirement,
		DurableRadioOutbox.State activeOutbox, DurableRadioOutbox.State terminalOutbox,
		List<UUID> messageIds) {}

	private static final class JournalLeaseStore implements RadioRouter.LeaseProtocolStateStore {
		private final FactoryOrchestrationSavedData journal;
		private JournalLeaseStore(FactoryOrchestrationSavedData journal) {
			this.journal = journal;
		}
		@Override public Optional<RadioRouter.LeaseProtocolState> load() {
			return journal.read(FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY,
				RadioRouter.LeaseProtocolState.class, RegistryAccess.EMPTY);
		}
		@Override public void persist(RadioRouter.LeaseProtocolState state) {
			journal.write(FactoryOrchestrationServer.RADIO_LEASE_PROTOCOL_KEY,
				state, RegistryAccess.EMPTY);
		}
	}

	private static final class JournalOutboxStore implements DurableRadioOutbox.StateStore {
		private final FactoryOrchestrationSavedData journal;
		private JournalOutboxStore(FactoryOrchestrationSavedData journal) {
			this.journal = journal;
		}
		@Override public Optional<DurableRadioOutbox.State> load() {
			return journal.read(FactoryOrchestrationServer.RADIO_OUTBOX_KEY,
				DurableRadioOutbox.State.class, RegistryAccess.EMPTY);
		}
		@Override public void persist(DurableRadioOutbox.State state) {
			journal.write(FactoryOrchestrationServer.RADIO_OUTBOX_KEY, state,
				RegistryAccess.EMPTY);
		}
	}

	private static final class SimulatedCrash extends RuntimeException {}

	private static UUID id(long value) { return new UUID(0, value); }
}
