package com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;

class ProductionRemoteExpansionTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }
    @Test void productionHostUsesTheSingleRemoteLifecycleOwner() throws Exception {
        Class<?> owner = Class.forName("com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion");
        Class<?> host = Class.forName("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$Runtime");
        assertTrue(Arrays.stream(host.getDeclaredFields()).anyMatch(field -> field.getType() == owner));
        assertFalse(Arrays.stream(host.getDeclaredFields()).anyMatch(field -> Set.of("remoteComputationExchanges", "remoteComputationJobs", "retiredRemoteComputations").contains(field.getName())));
        var oldTypes = Class.forName("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer").getDeclaredClasses();
        assertFalse(Arrays.stream(oldTypes).anyMatch(type -> type.getSimpleName().startsWith("RemoteComputation") || type.getSimpleName().equals("RetiredRemoteComputation") || type.getSimpleName().equals("RemoteOfferAcceptance")));
    }
    @Test void failedOfferSaveRetainsExactRecoverableSlotWithoutPublishingLifecycleOrConsumingDelivery() throws Exception {
        try (RemoteExpansionFixture f = new RemoteExpansionFixture()) {
            var before = f.begin();
            var delivered = f.deliverActive();
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, () -> f.owner.receive(before, delivered));
            assertEquals(before, f.exchange());
            assertNull(f.job());
            assertEquals(f.owner.snapshot(), f.durable().orElseThrow());
            assertTrue(f.outbox.entry(delivered.messageId()).isPresent());
            var actual = f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow();
            var allocated = actual.processes().values().stream().filter(process -> process.kind() == ProcessKind.REMOTE_LISTENER).findFirst().orElseThrow();
            f.fail = false; f.reload();
            f.owner.receive(f.exchange(), delivered);
            assertEquals(allocated.processId(), f.job().processId());
            assertEquals(allocated.owningSlot(), f.job().owningSlot());
            assertEquals(RemoteComputationPhase.REVISION_COMPUTING, f.exchange().phase());
            assertTrue(f.outbox.entry(delivered.messageId()).isEmpty());
            int commits = f.commits;
            f.owner.receive(f.exchange(), delivered);
            assertEquals(commits, f.commits, "duplicate OFFER cannot republish receiver work");
            assertEquals(1, f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow().processes().size());
            assertEquals(0, f.planPublications);
        }
    }

    @Test void revisionAndResultSaveFailuresRestoreEachPhaseAndOnlyAtomicHandoffGrantsMaterial() throws Exception {
        try (RemoteExpansionFixture f = new RemoteExpansionFixture()) {
            f.begin(); f.receiveActive();
            f.awaitPhase(RemoteComputationPhase.REVISION_PENDING);
            var revisionPending = f.exchange();
            var revisionDelivery = f.deliverActive();
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, () -> f.owner.receive(revisionPending, revisionDelivery));
            assertEquals(revisionPending, f.exchange());
            assertEquals(f.initialPlan, f.livePlan);
            assertEquals(0, f.planPublications);
            assertTrue(f.outbox.entry(revisionDelivery.messageId()).isPresent());
            f.fail = false; f.reload();
            assertEquals(revisionPending, f.exchange());
            f.owner.receive(f.exchange(), revisionDelivery);
            assertEquals(RemoteComputationPhase.ACK_PENDING, f.exchange().phase());
            assertEquals(1, f.planPublications);
            assertTrue(f.owner.materialReady(f.livePlan, f.exchange()).isEmpty());
            f.reload();
            f.receiveActive();
            assertEquals(RemoteComputationJobPhase.ACK_DURABLE, f.job().phase());
            f.reload(); f.awaitPhase(RemoteComputationPhase.RESULT_PENDING);
            var resultPending = f.exchange();
            var resultDelivery = f.deliverActive();
            var planWithRevision = f.livePlan;
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, () -> f.owner.receive(resultPending, resultDelivery));
            assertEquals(resultPending, f.exchange());
            assertEquals(planWithRevision, f.livePlan);
            assertTrue(f.runtime.ensureRemoteComputationProcess(f.job().processId(), f.job().lease(), f.job().receiverMemberId(), f.job().owningSlot()));
            f.fail = false; f.reload();
            f.owner.receive(f.exchange(), resultDelivery);
            assertEquals(RemoteComputationJobPhase.COMPLETED, f.job().phase());
            assertEquals(f.job().result(), f.livePlan.remoteExpansion().orElseThrow().result());
            assertTrue(f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow().processes().isEmpty());
            int commits = f.commits, publications = f.planPublications;
            f.owner.receive(f.exchange(), resultDelivery);
            assertEquals(commits, f.commits);
            assertEquals(publications, f.planPublications);
            var completed = f.exchange();
            assertTrue(f.router.canCommitMaterialIntent(f.receiverEndpoint.key(), completed.lease().leaseId(), completed.revision()));
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, () -> f.owner.materialReady(f.livePlan, completed));
            assertFalse(f.livePlan.dispatchQueued());
            assertEquals(publications, f.planPublications);
            assertEquals(0, f.replays);
            assertTrue(f.router.canCommitMaterialIntent(f.receiverEndpoint.key(), completed.lease().leaseId(), completed.revision()));
            f.fail = false;
            assertTrue(f.owner.materialReady(f.livePlan, completed).isPresent());
            assertTrue(f.livePlan.dispatchQueued());
            assertEquals(1, f.replays);
            assertFalse(f.router.canCommitMaterialIntent(f.receiverEndpoint.key(), completed.lease().leaseId(), completed.revision()));
            commits = f.commits;
            assertTrue(f.owner.materialReady(f.livePlan, completed).isPresent());
            assertEquals(commits, f.commits, "replay must not consume another material fence");
            f.reload();
            assertTrue(f.owner.materialReady(f.livePlan, f.exchange()).isPresent());
            assertEquals(commits, f.commits);
        }
    }

    @Test void completedRetirementNeedsExactDurableOutputAndFailedBatchKeepsBothOwnersRecoverable() throws Exception {
        try (RemoteExpansionFixture f = new RemoteExpansionFixture()) {
            f.begin(); f.receiveActive(); f.awaitPhase(RemoteComputationPhase.REVISION_PENDING);
            f.receiveActive(); f.receiveActive(); f.awaitPhase(RemoteComputationPhase.RESULT_PENDING); f.receiveActive();
            f.owner.materialReady(f.livePlan, f.exchange()).orElseThrow();
            var completed = f.owner.snapshot();
            f.livePlan = f.livePlan.cancelAfterDrainNow();
            f.liveOutput = f.receivedOutput();
            f.owner.retireCompleted();
            assertEquals(completed, f.owner.snapshot(), "live-only output cannot retire the receiver evidence");
            assertEquals(1, f.router.activeLeaseCount());
            f.journal.write("output_contract/" + f.exchange().runId(), f.liveOutput,
                com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries.provider());
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, f.owner::retireCompleted);
            assertEquals(completed, f.owner.snapshot());
            assertEquals(completed, f.durable().orElseThrow());
            assertEquals(1, f.router.activeLeaseCount());
            assertEquals(4, f.router.activeLeaseRouteCount());
            f.fail = false; f.owner.retireCompleted();
            assertTrue(f.owner.snapshot().exchanges().isEmpty());
            assertTrue(f.owner.snapshot().jobs().isEmpty());
            assertEquals(1, f.owner.retiredCount());
            assertEquals(0, f.router.activeLeaseCount());
            assertEquals(0, f.router.activeLeaseRouteCount());
            f.reload();
            assertEquals(1, f.owner.retiredCount());
            assertTrue(f.owner.exchanges().isEmpty());
            assertEquals(0, f.router.activeLeaseCount());
        }
    }
    @Test void terminationBatchFailureRetainsCleanupAndReacquiresExactReceiverBeforeRetry() throws Exception {
        try (RemoteExpansionFixture f = new RemoteExpansionFixture()) {
            f.begin(); f.receiveActive();
            var job = f.job();
            var active = f.owner.snapshot();
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, () -> f.owner.terminate(f.exchange(), RemoteComputationTerminalOutcome.MIGRATED));
            assertEquals(active, f.owner.snapshot());
            assertEquals(active, f.durable().orElseThrow());
            assertEquals(1, f.router.activeLeaseCount());
            assertTrue(f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow().processes().isEmpty());
            f.fail = false; f.reload(); f.owner.advanceJobs();
            assertEquals(job.processId(), f.job().processId());
            assertEquals(job.owningSlot(), f.job().owningSlot());
            assertTrue(f.runtime.ensureRemoteComputationProcess(f.job().processId(), f.job().lease(), f.job().receiverMemberId(), f.job().owningSlot()));
            assertTrue(f.owner.terminate(f.exchange(), RemoteComputationTerminalOutcome.MIGRATED));
            assertTrue(f.owner.exchanges().isEmpty());
            assertEquals(RemoteComputationTerminalOutcome.MIGRATED, f.owner.snapshot().retiredChildren().values().iterator().next().outcome());
            assertTrue(f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow().processes().isEmpty());
            assertEquals(0, f.router.activeLeaseCount());
            assertEquals(0, f.router.activeLeaseRouteCount());
            f.reload();
            assertEquals(1, f.owner.retiredCount());
            assertTrue(f.owner.exchanges().isEmpty());
        }
    }

    @Test void completedRetirementKeepsItsOnlyCleanupOwnerWhenExactReleaseFails() throws Exception {
        try (RemoteExpansionFixture f = new RemoteExpansionFixture()) {
            f.begin(); f.receiveActive(); f.awaitPhase(RemoteComputationPhase.REVISION_PENDING);
            f.receiveActive(); f.receiveActive(); f.awaitPhase(RemoteComputationPhase.RESULT_PENDING); f.receiveActive();
            f.owner.materialReady(f.livePlan, f.exchange()).orElseThrow();
            var completed = f.owner.snapshot();
            var job = f.job();
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!f.runtime.ensureRemoteComputationProcess(job.processId(), job.lease(), job.receiverMemberId(), job.owningSlot())
                && System.nanoTime() < deadline) Thread.sleep(5);
            var receiver = f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow();
            assertTrue(receiver.processes().containsKey(job.processId()), "the cleanup witness owns an actual exact receiver process");
            f.runtime.restoreSnapshot(RemoteExpansionFixture.withGeneration(receiver, Long.MAX_VALUE));
            assertFalse(f.runtime.releaseRemoteComputationProcess(job.processId(), job.lease(), job.receiverMemberId(), job.owningSlot()),
                "real exhausted receiver state must reject the exact release, without deleting it");
            f.livePlan = f.livePlan.cancelAfterDrainNow();
            f.liveOutput = f.receivedOutput();
            f.journal.write("output_contract/" + job.runId(), f.liveOutput,
                com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries.provider());
            var materialPlan = f.livePlan;
            int commits = f.commits;
            f.owner.retireCompleted();
            assertEquals(completed, f.owner.snapshot(), "failed exact release cannot discard the sole cleanup authority");
            assertEquals(completed, f.durable().orElseThrow());
            assertEquals(commits, f.commits, "failed exact release must not reach the retirement batch");
            assertEquals(materialPlan, f.livePlan);
            assertEquals(job.result(), f.livePlan.remoteExpansion().orElseThrow().result());
            assertTrue(f.router.materialIntentAuthorized(f.receiverEndpoint.key(), job.lease().leaseId(), job.lease().revisionFence()));
            assertEquals(1, f.router.activeLeaseCount());
            f.runtime.restoreSnapshot(receiver);
            f.fail = true;
            assertThrows(RemoteExpansionFixture.SaveFailure.class, f.owner::retireCompleted,
                "successful exact release may precede a failed journal batch, but cleanup evidence must remain");
            assertTrue(f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow().processes().isEmpty());
            assertEquals(completed, f.owner.snapshot());
            assertEquals(completed, f.durable().orElseThrow());
            assertEquals(materialPlan, f.livePlan);
            f.fail = false; f.reload();
            f.owner.retireCompleted();
            assertTrue(f.owner.exchanges().isEmpty());
            assertEquals(1, f.owner.retiredCount());
            assertEquals(materialPlan, f.livePlan);
            assertEquals(job.result(), f.livePlan.remoteExpansion().orElseThrow().result());
            assertEquals(0, f.router.activeLeaseCount());
            f.reload(); assertEquals(1, f.owner.retiredCount());
        }
    }
}
