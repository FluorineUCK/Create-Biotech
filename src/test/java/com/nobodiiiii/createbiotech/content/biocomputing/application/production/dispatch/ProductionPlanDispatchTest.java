package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

class ProductionPlanDispatchTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        SharedConstants.tryDetectVersion();
        var field = Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }
    @Test void readyPlanPersistsBeforeOneActualDispatchAndNeverPublishesTwice() throws Exception {
        try (DispatchFixture f = new DispatchFixture()) {
            ProductionPlanDispatch dispatch = dispatch(f, new Facts(f));
            dispatch.advance();
            assertTrue(dispatch.isSubmitted(runId(f)));
            assertTrue(f.livePlan.dispatchQueued());
            assertTrue(f.journal.read("production_plan/" + f.goal, FrozenProductionPlan.class, RemoteExpansionTestRegistries.provider()).orElseThrow().dispatchQueued());
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (f.runtime.snapshot(f.cluster).orElseThrow().pendingIntents().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            dispatch.advance();
            assertEquals(1, f.runtime.snapshot(f.cluster).orElseThrow().pendingIntents().values().stream().filter(ProductionEffect.CreateDispatch.class::isInstance).count());
        }
    }

    @Test void cancelledPlanDoesNotDispatch() {
        try (DispatchFixture f = new DispatchFixture()) {
            f.livePlan = f.livePlan.cancelAfterDrainNow();
            ProductionPlanDispatch dispatch = dispatch(f, new Facts(f));
            dispatch.advance();
            assertFalse(dispatch.isSubmitted(runId(f)));
            assertFalse(f.livePlan.dispatchQueued());
            assertEquals(0, createCount(f));
        }
    }
    @Test void fullyCreditedPlanDoesNotDispatch() {
        try (DispatchFixture f = new DispatchFixture()) {
            Facts facts = new Facts(f);
            facts.observation = new GoalObservationState(f.goal, f.cluster, f.livePlan.observationScope(), 0, 1, 1, 0, Optional.empty());
            ProductionPlanDispatch dispatch = dispatch(f, facts);
            dispatch.advance();
            assertFalse(dispatch.isSubmitted(runId(f)));
            assertFalse(f.livePlan.dispatchQueued());
            assertEquals(0, createCount(f));
        }
    }
    @Test void failedPlanPersistencePublishesNothingAndRetryCommitsBeforePublication() throws Exception {
        try (DispatchFixture f = new DispatchFixture()) {
            ProductionPlanDispatch dispatch = dispatch(f, new Facts(f));
            f.fail = true;
            assertThrows(DispatchFixture.SaveFailure.class, dispatch::advance);
            assertFalse(dispatch.isSubmitted(runId(f)));
            assertFalse(f.livePlan.dispatchQueued());
            assertTrue(f.journal.read("production_plan/" + f.goal, FrozenProductionPlan.class, RemoteExpansionTestRegistries.provider()).isEmpty());
            assertEquals(0, createCount(f));
            f.fail = false;
            dispatch.advance();
            assertTrue(dispatch.isSubmitted(runId(f)));
            assertTrue(f.livePlan.dispatchQueued());
        }
    }
    @Test void durableMaterialEvidencePreventsDuplicateDispatchAndStaleSourceMigration() {
        try (DispatchFixture f = new DispatchFixture(true, false, 1)) {
            var exchange = f.begin();
            // Real remote exchange, but current endpoint is unloaded: its source fence is now stale.
            f.sourceEndpointLoaded = false;
            Facts facts = new Facts(f); facts.durableMaterial = true;
            ProductionPlanDispatch dispatch = dispatch(f, facts);
            dispatch.advance();
            assertEquals(exchange, f.owner.exchange(runId(f)).orElseThrow());
            assertFalse(dispatch.isSubmitted(runId(f)));
            assertFalse(f.livePlan.dispatchQueued());
            assertEquals(0, createCount(f));
        }
    }
    @Test void submittedRunsAreServiceLocalImmutableAndForgettable() {
        try (DispatchFixture first = new DispatchFixture(); DispatchFixture second = new DispatchFixture()) {
            ProductionPlanDispatch a = dispatch(first, new Facts(first));
            ProductionPlanDispatch b = dispatch(second, new Facts(second));
            a.markSubmitted(runId(first));
            Set<UUID> frozen = a.submittedRunIds();
            assertThrows(UnsupportedOperationException.class, () -> frozen.clear());
            assertFalse(b.isSubmitted(runId(second)), "same Run UUID on a separate service must not share an ephemeral fence");
            b.advance();
            assertTrue(b.isSubmitted(runId(second)));
            a.forgetSubmitted(runId(first));
            assertFalse(a.isSubmitted(runId(first)));
            assertEquals(Set.of(runId(first)), frozen, "returned submitted snapshot is immutable, not a live view");
            a.advance();
            assertTrue(a.isSubmitted(runId(first)));
        }
    }
    @Test void fullMailboxKeepsDurablePlanRetryableWithoutMarkingSubmitted() throws Exception {
        try (DispatchFixture f = new DispatchFixture(false, false, 1000)) {
            Facts facts = new Facts(f) {
                @Override public void persistPlan(FrozenProductionPlan plan) {
                    super.persistPlan(plan);
                    // Saturate the real runtime mailbox at the journal/world publication boundary.
                    WorldFact signal = new WorldFact.Signal(f.cluster, f.sourceState.epochId(), 0, "dispatch-mailbox-load", 0);
                    long beforeGeneration = f.runtime.peekSnapshotForPanelView(f.cluster).orElseThrow().generation();
                    assertTrue(f.runtime.publishFact(signal));
                    // Observe the first fact's completed state through the real runtime, without
                    // dispatching its continuation. Delay only paces bounded polling, never proves completion.
                    long completionDeadline = System.nanoTime() + 5_000_000_000L;
                    boolean completed = false;
                    while (!completed && System.nanoTime() < completionDeadline) {
                        completed = f.runtime.snapshotForServerMutation(f.cluster).orElseThrow().generation() > beforeGeneration;
                        if (!completed) {
                            try { Thread.sleep(5); } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                            }
                        }
                    }
                    assertTrue(completed, "first real worker fact must complete before mailbox saturation");
                    assertFalse(f.runtime.workerInFlight(f.cluster), "completion barrier leaves no surprise first result to pump");
                    // Start the next actual, cadence-gated worker cycle and fill its bounded mailbox.
                    assertTrue(f.runtime.publishFact(signal));
                    boolean rejected = false;
                    for (int n = 0; n < 10000 && !rejected; n++) rejected = !f.runtime.publishFact(signal);
                    assertTrue(rejected, "fixture must observe actual bounded mailbox rejection");
                }
            };
            ProductionPlanDispatch dispatch = dispatch(f, facts);
            dispatch.advance();
            assertTrue(f.livePlan.dispatchQueued());
            assertFalse(dispatch.isSubmitted(runId(f)), "a mailbox refusal must not install the ephemeral submitted fence");
            assertEquals(0, createCount(f));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!dispatch.isSubmitted(runId(f)) && System.nanoTime() < deadline) { Thread.sleep(20); dispatch.advance(); }
            assertTrue(dispatch.isSubmitted(runId(f)), "the same durable queued plan retries once mailbox capacity returns");
        }
    }
    @Test void exhaustedSourceAllocatesLocalFirstAndDoesNotExceedOneExactJob() throws Exception {
        try (DispatchFixture f = new DispatchFixture(true, true, 1)) {
            ProductionPlanDispatch dispatch = dispatch(f, new Facts(f));
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (f.local.count() == 0 && System.nanoTime() < deadline) { dispatch.advance(); Thread.sleep(5); }
            assertEquals(1, f.local.count());
            assertTrue(f.owner.exchanges().isEmpty(), "real available local alchemist must win before remote allocation");
            assertFalse(dispatch.isSubmitted(runId(f)), "material publication waits for actual finite pure work completion");
            dispatch.advance();
            assertEquals(1, f.local.count());
            assertTrue(f.owner.exchanges().isEmpty());
        }
    }
    @Test void noLocalTargetAllocatesOnlyOneRemoteJobAndFencesMaterialUntilCompletion() throws Exception {
        try (DispatchFixture f = new DispatchFixture(true, false, 1)) {
            ProductionPlanDispatch dispatch = dispatch(f, new Facts(f));
            dispatch.advance();
            assertEquals(1, f.owner.exchanges().size());
            assertEquals(0, f.local.count());
            assertFalse(dispatch.isSubmitted(runId(f)));
            assertFalse(f.livePlan.dispatchQueued());
            dispatch.advance();
            assertEquals(1, f.owner.exchanges().size());
            assertEquals(0, createCount(f));
        }
    }
    @Test void occupiedFiniteReceiverSlotsRejectExpansionWithoutDispatch() throws Exception {
        try (DispatchFixture f = new DispatchFixture(true, false, 1)) {
            UUID receiverEpoch = f.runtime.snapshotForServerMutation(f.receiverCluster).orElseThrow().epochId();
            for (int slot = 0; slot < f.receiverProfile.processSlots(); slot++) {
                UUID lease = new UUID(10, slot);
                long deadline = System.nanoTime() + 3_000_000_000L;
                boolean reserved = false;
                while (!reserved && System.nanoTime() < deadline) {
                    f.runtime.snapshotForServerMutation(f.receiverCluster);
                    reserved = f.runtime.reserveExternalListenerProcess(f.receiverCluster, receiverEpoch, f.receiverId, lease).isPresent();
                    if (!reserved) Thread.sleep(5);
                }
                assertTrue(reserved, "real receiver slot must actually be occupied before testing capacity");
            }
            assertTrue(f.runtime.reserveExternalListenerProcess(f.receiverCluster, receiverEpoch, f.receiverId, new UUID(10, 99)).isEmpty());
            ProductionPlanDispatch dispatch = dispatch(f, new Facts(f));
            dispatch.advance();
            assertTrue(f.owner.exchanges().isEmpty());
            assertEquals(0, f.local.count());
            assertFalse(dispatch.isSubmitted(runId(f)));
            assertFalse(f.livePlan.dispatchQueued());
            assertEquals(0, createCount(f));
        }
    }
    private static long createCount(DispatchFixture f) {
        return f.runtime.snapshot(f.cluster).orElseThrow().pendingIntents().values().stream()
            .filter(ProductionEffect.CreateDispatch.class::isInstance).count();
    }
    private static UUID runId(DispatchFixture f) { return f.initialPlan.runs().currentRun().runId(); }
    private static ProductionPlanDispatch dispatch(DispatchFixture f, Facts facts) {
        return new ProductionPlanDispatch(f.runtime, RemoteExpansionTestRegistries.provider(), f.local, f.owner, facts);
    }
    private static class Facts implements ProductionPlanDispatch.Evidence {
        final DispatchFixture f;
        GoalObservationState observation;
        boolean durableMaterial;
        Facts(DispatchFixture f) { this.f = f; }
        public Collection<FrozenProductionPlan> plans() { return List.of(f.livePlan); }
        public FrozenProductionPlan plan(UUID goalId) { return f.livePlan; }
        public GoalObservationState observation(UUID goalId) { return observation; }
        public boolean runHasDurableMaterialEvidence(ClusterEngineState state, UUID runId) {
            return durableMaterial || state.pendingIntents().values().stream().anyMatch(intent ->
                intent instanceof ProductionEffect.CreateDispatch create && create.runId().equals(runId));
        }
        public boolean loadedAgentOwns(CreateRouteKey route, UUID clusterId) { return true; }
        public void persistPlan(FrozenProductionPlan plan) {
            if (f.fail) throw new DispatchFixture.SaveFailure();
            f.journal.write("production_plan/" + f.goal, plan, RemoteExpansionTestRegistries.provider());
            f.livePlan = plan;
        }
        public boolean waitSource(ClusterEngineState state, GoalRecord goal, WaitReason reason) {
            ClusterEngineState refreshed = f.runtime.snapshot(state.clusterId()).orElse(state);
            f.runtime.waitGoalFromServerObservation(refreshed.clusterId(), refreshed.epochId(), refreshed.generation(), goal.goalId(), reason);
            return false;
        }
    }
}
