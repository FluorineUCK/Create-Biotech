package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionsTest.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.*;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

class ProductionAdmissionsPreparationTest {
    @BeforeAll static void bootstrap() throws Exception { ProductionAdmissionsTest.bootstrap(); }
    @Test void preparedBatchCommitsLedgerPlanAndObservationBeforeAnyPublicationAndRollsBackOnFailure() throws Exception {
        var store = new Store(); var evidence = new StockEvidence(); var request = admission(10);
        List<FrozenProductionPlan> publications = new ArrayList<>();
        try (var service = selectedService()) {
            var owner = new ProductionAdmissions(8, service, store, evidence, (plan, observation) -> {
                assertEquals(plan, store.journal.read("production_plan/" + plan.goalId(), FrozenProductionPlan.class, RegistryAccess.EMPTY).orElseThrow());
                assertEquals(observation, store.journal.read("goal_observation/" + observation.goalId(), GoalObservationState.class, RegistryAccess.EMPTY).orElseThrow());
                assertTrue(store.loadLedger().active().containsKey(request.admissionId()));
                publications.add(plan);
            }, (id, message) -> {});
            assertTrue(owner.admitValidatedBatch(List.of(request))); store.failPrepared = true;
            assertThrows(IllegalStateException.class, () -> owner.advanceTick(1));
            assertEquals(ProductionAdmissionPhase.SEARCHING, owner.get(id(10)).orElseThrow().phase());
            assertEquals(owner.snapshot(), store.loadLedger()); assertTrue(publications.isEmpty());
            assertTrue(service.snapshot(request.clusterId()).orElseThrow().goals().isEmpty());
            owner.advanceTick(1);
            assertFalse(publications.isEmpty());
            assertEquals(4, publications.getFirst().requestedOutputCount());
            assertEquals(14, publications.getFirst().absoluteTarget());
            await(() -> { owner.advanceTick(1); return owner.get(id(10)).map(value -> value.phase() == ProductionAdmissionPhase.GOAL_PUBLISHED && value.searchId().isEmpty()).orElse(false); });
            var goal = service.snapshot(request.clusterId()).orElseThrow().goals().get(request.goalId());
            assertEquals(1, goal.submission().multiplier()); assertEquals(request.mode(), goal.submission().inputMode());
            assertEquals(request.priority(), goal.policy().priority());
            assertTrue(store.loadSearches().isEmpty(), "adopted search retires durably after publication");
            assertFalse(service.snapshot(request.clusterId()).orElseThrow().processes().values().stream().anyMatch(process -> process.goalId().equals(publications.getFirst().selectedProvider().selectionScope().queryId())));
        }
    }
    @Test void worldEvidenceWaitsStayLiveButFatalManifestFailuresBecomeTombstones() throws Exception {
        var store = new Store(); var evidence = new StockEvidence(); evidence.scopeAvailable = false;
        try (var service = selectedService()) {
            var owner = new ProductionAdmissions(8, service, store, evidence, (plan, observation) -> fail("must not publish while waiting"), (id, message) -> {});
            assertTrue(owner.admitValidatedBatch(List.of(admission(10)))); owner.advanceTick(1);
            assertEquals("WAIT_OUTPUT_SCOPE", owner.get(id(10)).orElseThrow().diagnostic());
            evidence.scopeAvailable = true; evidence.loaded = false; owner.advanceTick(1);
            assertEquals("WAIT_STOCK_OBSERVATION", owner.get(id(10)).orElseThrow().diagnostic());
            evidence.loaded = true; evidence.available = Map.of(); owner.advanceTick(1);
            assertEquals("WAIT_INPUT_MANIFEST", owner.get(id(10)).orElseThrow().diagnostic());
            evidence.manifestFailure = true; owner.advanceTick(1);
            assertTrue(owner.values().isEmpty()); assertEquals("PLAN:IllegalStateException:manifest broken", owner.snapshot().failures().get(id(10)).diagnostic());
        }
    }
    @Test void quantityOverflowFailsWithoutFreezingOrPublishing() throws Exception {
        var store = new Store(); var evidence = new StockEvidence(); evidence.baseline = Long.MAX_VALUE;
        try (var service = selectedService()) {
            var owner = new ProductionAdmissions(8, service, store, evidence, (plan, observation) -> fail("overflow must not publish"), (id, message) -> {});
            assertTrue(owner.admitValidatedBatch(List.of(admission(10)))); owner.advanceTick(1);
            assertEquals("COUNT_OVERFLOW", owner.snapshot().failures().get(id(10)).diagnostic());
            assertTrue(owner.values().isEmpty());
        }
    }
    @Test void stockKeeperItemsBecomeBatchesOnlyWhenTheWinningRecipeIsFrozen() throws Exception {
        var store = new Store(); var evidence = new StockEvidence(); var panel = admission(10);
        var order = new PendingProductionAdmission(panel.admissionId(), panel.goalId(), panel.clusterId(), panel.epochId(),
            panel.snapshotGeneration(), panel.viewRevision(), ProductionAdmissionOrigin.stockKeeper(id(888), new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), id(889), 9),
            panel.logisticsId(), panel.requestedOutput(), panel.deliveryAddress(), 1, panel.mode(), panel.priority(), panel.phase(), panel.searchId(),
            false, Optional.empty(), Optional.empty(), Optional.empty(), panel.diagnostic());
        List<FrozenProductionPlan> publications = new ArrayList<>();
        try (var service = selectedService()) {
            var owner = new ProductionAdmissions(8, service, store, evidence, (plan, observation) -> publications.add(plan), (id, message) -> {});
            assertTrue(owner.admitValidatedBatch(List.of(order))); owner.advanceTick(1);
            assertEquals(12, publications.getFirst().requestedOutputCount()); assertEquals(22, publications.getFirst().absoluteTarget());
            assertEquals(3, owner.get(id(10)).orElseThrow().multiplier());
        }
    }
    @Test void libraryCrashDemotionThenOwnerRestoreRetainsExactlyTheLiveSelectedAndCleanupCursors() throws Exception {
        var store = new Store(); var request = admission(10); UUID searchId;
        try (var service = selectedService()) {
            var search = service.productionSearchSnapshot(request.clusterId(), request.logisticsId(), request.requestedOutput()).orElseThrow();
            searchId = search.searchId(); store.saveSearch(search);
            store.saveLedger(new ProductionAdmissionLedger.State(Map.of(id(10), request.searching(Optional.of(searchId), "WAIT").cleanupPending("FAIL")), Map.of(), 0, Optional.empty()));
            store.saveSearch(new FactoryRuntimeService.ProductionSearchSnapshot(id(99999), id(99998), id(99997), id(99996), request.requestedOutput(), 0, FactoryRuntimeService.ProductionSearchStatus.SEARCHING, Optional.empty()));
        }
        try (var reloaded = service()) {
            installMembers(reloaded);
            publishCatalog(reloaded, false); // Actual bootstrap Library crash-demotion precedes admission restoration.
            var owner = owner(reloaded, store, 8); owner.restoreSearchesAfterLibraryBootstrap();
            assertEquals(FactoryRuntimeService.ProductionSearchStatus.SELECTED, reloaded.productionSearchSnapshot(request.clusterId(), request.logisticsId(), request.requestedOutput()).orElseThrow().status());
            assertEquals(Set.of(searchId), store.loadSearches().values().stream().map(FactoryRuntimeService.ProductionSearchSnapshot::searchId).collect(java.util.stream.Collectors.toSet()));
            assertEquals(owner.snapshot(), store.loadLedger());
        }
    }
    @Test void crashAfterPreparedCommitRestoresAndPublishesWithoutReAdoptingARetiredCursor() throws Exception {
        var store = new Store(); var request = admission(10); ClusterEngineState durableRuntime;
        try (var service = selectedService()) {
            var owner = new ProductionAdmissions(8, service, store, new StockEvidence(),
                (plan, observation) -> { throw new IllegalStateException("crash after prepared commit"); }, (id, message) -> {});
            assertTrue(owner.admitValidatedBatch(List.of(request)));
            assertThrows(IllegalStateException.class, () -> owner.advanceTick(1));
            assertEquals(ProductionAdmissionPhase.PREPARED, store.loadLedger().active().get(id(10)).phase());
            assertTrue(service.snapshot(request.clusterId()).orElseThrow().goals().isEmpty());
            durableRuntime = service.snapshot(request.clusterId()).orElseThrow();
        }
        store.reopen();
        try (var service = service()) {
            service.restoreSnapshot(durableRuntime); publishCatalog(service, false);
            var owner = new ProductionAdmissions(8, service, store, new StockEvidence(), (plan, observation) -> {}, (id, message) -> {});
            owner.restoreSearchesAfterLibraryBootstrap();
            await(() -> { owner.advanceTick(1); return owner.get(id(10)).map(value -> value.phase() == ProductionAdmissionPhase.GOAL_PUBLISHED && value.searchId().isEmpty()).orElse(false); });
            assertEquals(1, service.snapshot(request.clusterId()).orElseThrow().goals().size());
            store.reopen(); var resumed = owner(service, store, 8);
            resumed.advanceTick(1); // The deleted cursor is no longer needed for one-shot lease adoption.
            assertEquals(1, service.snapshot(request.clusterId()).orElseThrow().goals().size());
            assertEquals(ProductionAdmissionPhase.GOAL_PUBLISHED, resumed.get(id(10)).orElseThrow().phase());
        }
    }
    @Test void ambiguousConcreteMaterialIdentityIsFatalRatherThanAStockWait() throws Exception {
        var store = new Store(); var evidence = new StockEvidence();
        var componentStone = new ItemStack(Items.STONE);
        componentStone.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1);
        evidence.available = Map.of(new StackKey(new ItemStack(Items.STONE)), 32L, new StackKey(componentStone), 32L);
        try (var service = selectedService()) {
            var owner = new ProductionAdmissions(8, service, store, evidence, (plan, observation) -> fail("ambiguous material must not publish"), (id, message) -> {});
            assertTrue(owner.admitValidatedBatch(List.of(admission(10)))); owner.advanceTick(1);
            assertEquals("AMBIGUOUS_INPUT", owner.snapshot().failures().get(id(10)).diagnostic());
        }
    }
    static FactoryRuntimeService selectedService() throws Exception {
        var service = service(); installMembers(service); publishCatalog(service, true);
        var request = admission(10);
        try { await(() -> service.selectProductionPattern(request.clusterId(), request.logisticsId(), request.requestedOutput()).isPresent()); return service; }
        catch (Throwable failure) { service.close(); throw failure; }
    }
    static void installMembers(FactoryRuntimeService service) {
        var request = admission(10); var empty = ClusterEngineState.empty(request.clusterId(), request.epochId());
        var profile = ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 3, 0).orElseThrow();
        service.restoreSnapshot(new ClusterEngineState(empty.clusterId(), empty.epochId(), 0, 0, 0, 0,
            empty.goals(), empty.submissionSequences(), empty.requestedKinds(), empty.processes(),
            List.of(new ClusterEngineState.MemberSnapshot(id(8000), profile, true, 0, 0)), empty.pendingIntents(), empty.uncertainIntentIds(), 0, null));
    }
    static void publishCatalog(FactoryRuntimeService service, boolean loaded) {
        var request = admission(10); var source = new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO);
        var pattern = new PatternRecord(id(8001), new PatternPageKey(source, 0, 0),
            List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"), null, 1, DataComponentPredicate.EMPTY)),
            List.of(new PatternOutput(request.requestedOutput(), 4)), new StandardPatternBody("input"), OptionalInt.empty());
        service.publishProductionLibraryAccess(request.clusterId(), Set.of(request.logisticsId()), 1, CommunicationFrequency.of("0"), 0,
            new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), LibraryRegistration.Reachability.REACHABLE,
            new ProductionRecipeCatalog(id(8002), 1, 1, loaded, List.of(pattern)));
    }
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(condition.getAsBoolean(), "production service must converge within a bounded retry window");
    }
    static class StockEvidence extends Evidence {
        boolean scopeAvailable = true, loaded = true, manifestFailure;
        long baseline = 10;
        Map<StackKey, Long> available = Map.of(new StackKey(new ItemStack(Items.STONE)), 32L);
        public Optional<StockObservationScope> outputScope(UUID network, String address, StackKey output) {
            return scopeAvailable ? Optional.of(new StockObservationScope.NetworkSummary(network, output, 0)) : Optional.empty();
        }
        public StockObservationService.Observation observe(StockObservationScope scope) {
            return new StockObservationService.Observation(scope, baseline, 0, loaded ? StockObservationService.Status.TARGET_REACHED : StockObservationService.Status.WAIT_STOCK_OBSERVATION);
        }
        public Map<StackKey, Long> availableManifest(PatternRecord pattern, UUID network) {
            if (manifestFailure) throw new IllegalStateException("manifest broken"); return available;
        }
    }
}
