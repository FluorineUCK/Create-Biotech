package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

class ProductionAdmissionsTest {
    @BeforeAll static void bootstrap() throws Exception {
        var modList = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (modList.getMethod("get").invoke(null) == null) modList.getMethod("of", List.class, List.class,
            List.class, List.class, Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }
    @Test void validatedBatchIsAtomicAcrossPersistenceFailureCapacityRejectionAndReload() {
        var store = new Store();
        try (var service = service()) {
            var owner = owner(service, store, 2);
            store.failLedger = true;
            assertFalse(owner.admitValidatedBatch(List.of(admission(10), admission(20))));
            assertEquals(0, owner.liveSize()); assertTrue(store.loadLedger().active().isEmpty());
            assertTrue(owner.admitValidatedBatch(List.of(admission(10), admission(20))));
            var durable = store.loadLedger();
            assertFalse(owner.admitValidatedBatch(List.of(admission(30))));
            assertEquals(durable, owner.snapshot()); assertEquals(durable, store.loadLedger());
            store.reopen(); var reloaded = owner(service, store, 2);
            assertEquals(durable, reloaded.snapshot());
            assertFalse(reloaded.admitValidatedBatch(List.of(admission(10))));
        }
    }
    @Test void boundedTickRotatesAfterReloadAndRollsBackItsFairnessCursorWhenSaveFails() {
        var store = new Store();
        try (var service = service()) {
            var owner = owner(service, store, 8);
            assertTrue(owner.admitValidatedBatch(List.of(admission(10), admission(20), admission(30))));
            owner.advanceTick(1);
            assertEquals(Optional.of(id(10)), owner.snapshot().schedulingCursor());
            assertEquals("WAIT_COMPUTER", owner.get(id(10)).orElseThrow().diagnostic());
            assertEquals("PENDING", owner.get(id(20)).orElseThrow().diagnostic());
            store.reopen(); var reloaded = owner(service, store, 8); var prior = reloaded.snapshot();
            store.failLedger = true;
            assertThrows(IllegalStateException.class, () -> reloaded.advanceTick(1));
            assertEquals(prior, reloaded.snapshot()); assertEquals(prior, store.loadLedger());
            reloaded.advanceTick(1);
            assertEquals(Optional.of(id(20)), reloaded.snapshot().schedulingCursor());
            assertEquals("WAIT_COMPUTER", reloaded.get(id(20)).orElseThrow().diagnostic());
            reloaded.advanceTick(1); assertEquals(Optional.of(id(30)), reloaded.snapshot().schedulingCursor());
            reloaded.advanceTick(1); assertEquals(Optional.of(id(10)), reloaded.snapshot().schedulingCursor());
        }
    }
    @Test void epochFailurePersistsCleanupBeforeSearchDeletionAndTombstonesOnlyAfterRetry() {
        var store = new Store(); var admission = admission(10).searching(Optional.of(id(99)), "WAIT_SEARCH");
        try (var service = service()) {
            var owner = owner(service, store, 8);
            assertTrue(owner.admitValidatedBatch(List.of(admission)));
            service.restoreSnapshot(ClusterEngineState.empty(admission.clusterId(), id(999)));
            store.failDelete = true;
            assertThrows(IllegalStateException.class, () -> owner.advanceTick(1));
            assertEquals(ProductionAdmissionPhase.CLEANUP_PENDING, store.loadLedger().active().get(id(10)).phase());
            assertTrue(store.loadLedger().failures().isEmpty());
            store.reopen(); var reloaded = owner(service, store, 8); reloaded.advanceTick(1);
            assertEquals(0, reloaded.liveSize());
            assertEquals("EPOCH_FENCED", store.loadLedger().failures().get(id(10)).diagnostic());
            assertEquals(List.of(ProductionAdmissionPhase.CLEANUP_PENDING, ProductionAdmissionPhase.CLEANUP_PENDING), store.phasesAtDelete);
        }
    }
    @Test void ownerKeepsExclusiveCleanupClaimsAndNeverDeletesAPeersSearch() {
        var store = new Store(); var first = admission(10).searching(Optional.of(id(99)), "WAIT").cleanupPending("FIRST");
        var second = admission(20).searching(Optional.of(id(99)), "WAIT_SEARCH_CLAIM");
        store.saveLedger(new ProductionAdmissionLedger.State(Map.of(id(10), first, id(20), second), Map.of(), 0, Optional.of(id(10))));
        try (var service = service()) {
            service.restoreSnapshot(ClusterEngineState.empty(first.clusterId(), id(999)));
            var owner = owner(service, store, 8); owner.advanceTick(1);
            assertEquals(List.of(), store.phasesAtDelete);
            assertEquals("EPOCH_FENCED", owner.snapshot().failures().get(id(20)).diagnostic());
            assertEquals(Optional.of(id(99)), owner.get(id(10)).orElseThrow().searchId());
        }
    }
    @Test void failedCleanupFenceSaveRollsBackWithoutReleasingTheSearch() {
        var store = new Store(); var entry = admission(10).searching(Optional.of(id(99)), "WAIT");
        store.saveLedger(new ProductionAdmissionLedger.State(Map.of(id(10), entry), Map.of(), 0, Optional.of(id(10))));
        try (var service = service()) {
            service.restoreSnapshot(ClusterEngineState.empty(entry.clusterId(), id(999)));
            var owner = owner(service, store, 8); var prior = owner.snapshot(); store.failLedger = true;
            assertThrows(IllegalStateException.class, () -> owner.advanceTick(1));
            assertEquals(prior, owner.snapshot()); assertEquals(prior, store.loadLedger());
            assertTrue(store.phasesAtDelete.isEmpty());
        }
    }
    @Test void bootstrapDeletesAnOrphanByItsStoredJournalKeyEvenWhenItsPayloadIdentityDiffers() {
        var store = new Store();
        var orphan = new FactoryRuntimeService.ProductionSearchSnapshot(id(991), id(992), id(993), id(994),
            admission(10).requestedOutput(), 0, FactoryRuntimeService.ProductionSearchStatus.SEARCHING, Optional.empty());
        store.journal.write("production_search/" + id(990), orphan, RegistryAccess.EMPTY);
        try (var service = service()) {
            var owner = owner(service, store, 8); owner.restoreSearchesAfterLibraryBootstrap();
            assertTrue(store.loadSearches().isEmpty(), "cleanup must remove the actual orphan key, not a derived payload key");
        }
    }
    @Test void failedTombstoneSaveKeepsTheDurableCleanupAuthorityForAnIdempotentReloadRetry() {
        var store = new Store(); var request = admission(10).searching(Optional.of(id(99)), "WAIT");
        try (var service = service()) {
            service.restoreSnapshot(ClusterEngineState.empty(request.clusterId(), id(999)));
            var owner = owner(service, store, 8); assertTrue(owner.admitValidatedBatch(List.of(request)));
            store.failTombstone = true;
            assertThrows(IllegalStateException.class, () -> owner.advanceTick(1));
            assertEquals(ProductionAdmissionPhase.CLEANUP_PENDING, owner.get(id(10)).orElseThrow().phase());
            assertEquals(owner.snapshot(), store.loadLedger()); assertTrue(store.loadLedger().failures().isEmpty());
            store.reopen(); var reloaded = owner(service, store, 8); reloaded.advanceTick(1);
            assertEquals("EPOCH_FENCED", reloaded.snapshot().failures().get(id(10)).diagnostic());
            assertEquals(0, reloaded.liveSize());
        }
    }
    static FactoryRuntimeService service() {
        var service = new FactoryRuntimeService(new FactoryRuntimeConfig(1, 1000, 4, 32, 4, 4, 32, 64, 20, 10, 100, 3, 32, 16));
        service.start(); return service;
    }
    static ProductionAdmissions owner(FactoryRuntimeService service, Store store, int capacity) {
        return new ProductionAdmissions(capacity, service, store, new Evidence(), (plan, observation) -> {}, (id, message) -> {});
    }
    static PendingProductionAdmission admission(long value) {
        return new PendingProductionAdmission(id(value), id(1000 + value), id(2000), id(2001), 0, 0,
            ProductionAdmissionOrigin.panel(id(5000), new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), id(5001), Optional.empty()),
            id(4000), new StackKey(new ItemStack(Items.IRON_INGOT)), "delivery", 1, InputMode.BULK, 4,
            ProductionAdmissionPhase.SEARCHING, Optional.empty(), false, Optional.empty(), Optional.empty(), Optional.empty(), "PENDING");
    }
    static UUID id(long value) { return new UUID(0,value); }
    static class Evidence implements ProductionAdmissions.PlanningEvidence {
        public Optional<StockObservationScope> outputScope(UUID network, String address, StackKey output) { return Optional.empty(); }
        public StockObservationService.Observation observe(StockObservationScope scope) { throw new AssertionError("scope unavailable"); }
        public Map<StackKey, Long> availableManifest(PatternRecord pattern, UUID network) { return Map.of(); }
    }
    static class Store implements ProductionAdmissions.Persistence {
        FactoryOrchestrationSavedData journal = new FactoryOrchestrationSavedData();
        boolean failLedger, failPrepared, failDelete, failTombstone;
        List<ProductionAdmissionPhase> phasesAtDelete = new ArrayList<>();
        void reopen() {
            try {
                var load = FactoryOrchestrationSavedData.class.getDeclaredMethod("load", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class);
                load.setAccessible(true); journal = (FactoryOrchestrationSavedData) load.invoke(null, journal.save(new net.minecraft.nbt.CompoundTag(), RegistryAccess.EMPTY), RegistryAccess.EMPTY);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        public ProductionAdmissionLedger.State loadLedger() {
            return journal.read("production_admission/state", ProductionAdmissionLedger.State.class, RegistryAccess.EMPTY)
                .orElseGet(() -> new ProductionAdmissionLedger.State(Map.of(), Map.of(), 0, Optional.empty()));
        }
        public void saveLedger(ProductionAdmissionLedger.State state) {
            if (failLedger) { failLedger = false; throw new IllegalStateException("ledger unavailable"); }
            if (failTombstone && !state.failures().isEmpty()) { failTombstone = false; throw new IllegalStateException("tombstone unavailable"); }
            journal.write("production_admission/state", state, RegistryAccess.EMPTY);
        }
        public void savePrepared(ProductionAdmissionLedger.State state, FrozenProductionPlan plan, GoalObservationState observation) {
            if (failPrepared) { failPrepared = false; throw new IllegalStateException("prepared unavailable"); }
            journal.writeBatch(Map.of("production_admission/state", state, "production_plan/" + plan.goalId(), plan,
                "goal_observation/" + observation.goalId(), observation), RegistryAccess.EMPTY);
        }
        public Map<String, FactoryRuntimeService.ProductionSearchSnapshot> loadSearches() {
            return journal.readPrefix("production_search/", FactoryRuntimeService.ProductionSearchSnapshot.class, RegistryAccess.EMPTY);
        }
        public void saveSearch(FactoryRuntimeService.ProductionSearchSnapshot search) {
            journal.write("production_search/" + search.searchId(), search, RegistryAccess.EMPTY);
        }
        public void deleteSearchEntry(String key) { journal.remove(key); }
        public void deleteSearch(UUID search) {
            var entry = loadLedger().active().values().stream().filter(value -> value.searchId().equals(Optional.of(search))).findFirst().orElse(null);
            if (entry != null) phasesAtDelete.add(entry.phase());
            if (failDelete) { failDelete = false; throw new IllegalStateException("delete unavailable"); }
            journal.remove("production_search/" + search);
        }
    }
}
