package com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissionLedger;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionPlanRepositoryTest {
    @BeforeAll
    static void bootstrap() throws Exception { PlanFixture.bootstrap(); }

    @Test
    void actualSavedJournalRestoresPlanAndSourceAuthorityAtExplicitRecovery() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        var journal = new FactoryOrchestrationSavedData();
        journal.write("production_plan/" + plan.goalId(), plan, RegistryAccess.EMPTY);
        var restoredJournal = reload(journal);
        var owner = new ProductionPlanRepository(new JournalPort(restoredJournal));
        assertNull(owner.current(plan.goalId()), "construction must not move recovery earlier");
        owner.recover();
        assertEquals(plan, owner.current(plan.goalId()));
        var run = plan.runs().currentRun();
        assertEquals(plan.clusterId(), owner.sourceClusterForRun(run.runId(), run).orElseThrow());
        assertEquals(plan, owner.durable(plan.goalId()).orElseThrow());
        assertEquals(journal.save(new CompoundTag(), RegistryAccess.EMPTY),
            restoredJournal.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    @Test
    void malformedNamespaceFencesWholeRestoreAndKeepsTheExistingLiveProjection() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        var journal = new FactoryOrchestrationSavedData();
        var owner = new ProductionPlanRepository(new JournalPort(journal));
        owner.persist(plan);
        var before = owner.snapshot();
        var next = plan.markDispatchQueued();
        journal.write(ProductionPlanRepository.journalKey(plan.goalId()), next, RegistryAccess.EMPTY);
        journal.write(ProductionPlanRepository.journalKey(PlanFixture.id(200)), InputMode.EXACT, RegistryAccess.EMPTY);
        assertThrows(IllegalStateException.class, owner::recover);
        assertEquals(before, owner.snapshot());
        assertEquals(plan, owner.current(plan.goalId()));
        assertEquals(next, owner.durable(plan.goalId()).orElseThrow());
        assertFalse(owner.containsGoal(PlanFixture.id(200)));
    }

    @Test
    void restoredPlansRemainIndependentAndSourceAuthorityRequiresExactRunAndKnownGoal() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        var second = withGoal(plan, PlanFixture.id(201));
        var journal = new FactoryOrchestrationSavedData();
        journal.writeBatch(Map.of(ProductionPlanRepository.journalKey(plan.goalId()), plan,
            ProductionPlanRepository.journalKey(second.goalId()), second), RegistryAccess.EMPTY);
        var owner = new ProductionPlanRepository(new JournalPort(reload(journal)));
        var otherOwner = new ProductionPlanRepository(new JournalPort(reload(journal)));
        owner.recover(); otherOwner.recover();
        assertEquals(2, owner.snapshot().size());
        owner.persist(plan.cancelAfterDrainNow());
        assertEquals(plan, otherOwner.current(plan.goalId()), "separate repository, no shared mutable map");
        assertEquals(second, owner.current(second.goalId()));
        var run = plan.runs().currentRun();
        assertTrue(owner.sourceClusterForRun(PlanFixture.id(999), run).isEmpty());
        assertTrue(owner.sourceClusterForRun(run.runId(), null).isEmpty());
        assertTrue(owner.sourceClusterForRun(run.runId(), withGoal(plan, PlanFixture.id(202))
            .runs().currentRun()).isEmpty());
        assertEquals(plan.clusterId(), owner.sourceClusterForRun(run.runId(), run).orElseThrow());
        assertThrows(NullPointerException.class, () -> owner.sourceClusterForRun(null, run));
        assertEquals("production_plan/" + plan.goalId(), ProductionPlanRepository.journalKey(plan.goalId()));
    }

    @Test
    void actualWorldHostHasTransferredTheOnlyPlanCollectionAndRecoveryPoint() throws Exception {
        String source = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/"
            + "factorycluster/runtime/FactoryOrchestrationServer.java"));
        assertTrue(source.contains("private final ProductionPlanRepository planRepository;"),
            "the actual host must use the new repository, not keep a shadow collection");
        assertFalse(source.contains("productionPlans"));
        assertFalse(source.contains("PRODUCTION_PLAN_PREFIX"));
        assertFalse(source.contains("persistProductionPlan("));
        assertFalse(source.contains("durablePlanSourceCluster("));
        assertTrue(source.contains("planRepository.admissionCommitted(plan)"));
        assertTrue(source.contains("planRepository.publishCommitted(plan)"));
        assertTrue(source.contains("planRepository.sourceClusterForRun(runId, run)"));
        assertTrue(source.contains("ProductionPlanRepository.journalKey(plan.goalId())"));
        int dispatch = source.indexOf("this.planDispatch = new ProductionPlanDispatch(");
        int recover = source.indexOf("planRepository.recover();");
        int retryRestore = source.indexOf("safeCreateRetries.recover();");
        assertTrue(dispatch >= 0 && recover > dispatch && retryRestore > recover,
            "restore remains after dispatch/settlement construction and before retry recovery");
    }

    private static FrozenProductionPlan withGoal(FrozenProductionPlan plan, UUID goalId) {
        var run = plan.runs().currentRun();
        var sibling = new ProductionRun(run.runId(), goalId, run.patternId(), run.fence(),
            run.runMultiplier(), run.inputMode(), run.inputRoutes(), run.returnWarehouseRoute(),
            run.outputPolicyGeneration(), run.manifest());
        return new FrozenProductionPlan(goalId, plan.clusterId(), plan.selectedProvider(),
            plan.patternKind(), plan.output(), plan.outputPerOperation(), plan.deliveryRoute(), plan.stages(),
            new ProductionRun.RunSequence(sibling, plan.runs().remainingRuns()), plan.observationScope(),
            plan.baselineCount(), plan.requestedOutputCount(), plan.dispatchQueued(), plan.cancelAfterDrain(),
            plan.remoteExpansion());
    }

    @Test
    void expansionPublishesOnlyAfterItsCombinedJournalBatchAndFailureChangesNeitherSide() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        var journal = new FactoryOrchestrationSavedData();
        var owner = new ProductionPlanRepository(new JournalPort(journal));
        owner.persist(plan);
        var snapshot = owner.snapshot();
        var next = plan.markDispatchQueued();
        var lifecycle = new ProductionLocalExpansion.Lifecycle(Map.of());
        var before = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertThrows(IllegalArgumentException.class, () -> {
            journal.writeBatch(Map.of(ProductionPlanRepository.journalKey(plan.goalId()), next,
                "local_computation/lifecycle", lifecycle, "unsupported", new Object()), RegistryAccess.EMPTY);
            owner.publishCommitted(next);
        });
        assertEquals(before, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertEquals(plan, owner.current(plan.goalId()));
        journal.writeBatch(Map.of(ProductionPlanRepository.journalKey(plan.goalId()), next,
            "local_computation/lifecycle", lifecycle), RegistryAccess.EMPTY);
        assertEquals(next, owner.durable(plan.goalId()).orElseThrow());
        assertEquals(plan, owner.current(plan.goalId()), "live projection waits for committed callback");
        var committed = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        owner.publishCommitted(next);
        assertEquals(next, owner.current(plan.goalId()));
        assertEquals(committed, journal.save(new CompoundTag(), RegistryAccess.EMPTY));
        assertEquals(List.of(plan), snapshot, "previous snapshot cannot be a live mutable view");
        var recovered = new ProductionPlanRepository(new JournalPort(reload(journal)));
        recovered.recover();
        assertEquals(owner.snapshot(), recovered.snapshot());
    }

    @Test
    void committedAdmissionRetryNeverRollsBackAnInstalledQueuedCancelledPlan() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        var journal = new FactoryOrchestrationSavedData();
        var owner = new ProductionPlanRepository(new JournalPort(journal));
        var ledger = new ProductionAdmissionLedger.State(Map.of(), Map.of(), 0, Optional.empty());
        var observation = new GoalObservationState(plan.goalId(), plan.clusterId(),
            plan.observationScope(), plan.baselineCount(), plan.requestedOutputCount(),
            0, 0, Optional.empty());
        journal.writeBatch(Map.of("production_admission/global", ledger,
            ProductionPlanRepository.journalKey(plan.goalId()), plan,
            "goal_observation/" + plan.goalId(), observation), RegistryAccess.EMPTY);
        var durableBeforeCallback = journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        owner.admissionCommitted(plan);
        assertEquals(durableBeforeCallback, journal.save(new CompoundTag(), RegistryAccess.EMPTY),
            "publication must not split or rewrite the already committed multi-owner batch");
        var installed = PlanFixture.installed(plan);
        owner.persist(installed);
        var snapshot = owner.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        owner.admissionCommitted(plan);
        assertEquals(installed, owner.current(plan.goalId()));
        assertEquals(installed, owner.durable(plan.goalId()).orElseThrow());
        assertEquals(List.of(installed), snapshot);
        assertTrue(owner.containsGoal(plan.goalId()));
        assertFalse(owner.containsGoal(null));
        assertNull(owner.current(null));
    }

    @Test
    void singletonUpdateIsDurableBeforeLiveAndInvalidJournalCannotPublish() throws Exception {
        var plan = PlanFixture.stableRun(PlanFixture.freeze(ProductionPlanFreezer.class.getName()));
        var port = new JournalPort(new FactoryOrchestrationSavedData());
        var owner = new ProductionPlanRepository(port);
        owner.persist(plan);
        assertEquals(plan, owner.current(plan.goalId()));
        assertEquals(plan, owner.durable(plan.goalId()).orElseThrow());
        var next = plan.markDispatchQueued().cancelAfterDrainNow();
        owner.persist(next);
        assertEquals(next, owner.current(plan.goalId()));
        assertEquals(next, owner.durable(plan.goalId()).orElseThrow());
        var damaged = port.journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        damaged.putInt("Version", -1);
        port.journal = reload(damaged);
        assertFalse(port.journal.persistenceValid());
        var before = port.journal.save(new CompoundTag(), RegistryAccess.EMPTY);
        assertThrows(IllegalStateException.class, () -> owner.persist(plan));
        assertEquals(next, owner.current(plan.goalId()), "failed save must not publish rollback");
        assertEquals(before, port.journal.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    private static FactoryOrchestrationSavedData reload(FactoryOrchestrationSavedData journal)
        throws Exception {
        return reload(journal.save(new CompoundTag(), RegistryAccess.EMPTY));
    }

    private static FactoryOrchestrationSavedData reload(CompoundTag archive) throws Exception {
        var method = FactoryOrchestrationSavedData.class.getDeclaredMethod("load",
            CompoundTag.class, HolderLookup.Provider.class);
        method.setAccessible(true);
        return (FactoryOrchestrationSavedData) method.invoke(null, archive, RegistryAccess.EMPTY);
    }

    private static final class JournalPort implements ProductionPlanRepository.Persistence {
        private FactoryOrchestrationSavedData journal;
        JournalPort(FactoryOrchestrationSavedData journal) { this.journal = journal; }
        public Collection<FrozenProductionPlan> loadAll() {
            return journal.readPrefix(ProductionPlanRepository.JOURNAL_PREFIX,
                FrozenProductionPlan.class, RegistryAccess.EMPTY).values();
        }
        public Optional<FrozenProductionPlan> load(UUID goalId) {
            return journal.read(ProductionPlanRepository.journalKey(goalId),
                FrozenProductionPlan.class, RegistryAccess.EMPTY);
        }
        public void save(FrozenProductionPlan plan) {
            journal.write(ProductionPlanRepository.journalKey(plan.goalId()), plan, RegistryAccess.EMPTY);
        }
    }
}
