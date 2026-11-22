package com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;

/** Production application plans, never a Computer's program, RAM or execution resource. */
public final class ProductionPlanRepository {
    public static final String JOURNAL_PREFIX = "production_plan/";

    /** The world host supplies storage only; collection ownership stays here. */
    public interface Persistence {
        Collection<FrozenProductionPlan> loadAll();
        Optional<FrozenProductionPlan> load(UUID goalId);
        void save(FrozenProductionPlan plan);
    }

    private final Persistence persistence;
    private final Map<UUID, FrozenProductionPlan> plans = new LinkedHashMap<>();

    public ProductionPlanRepository(Persistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public static String journalKey(UUID goalId) {
        return JOURNAL_PREFIX + Objects.requireNonNull(goalId, "goalId");
    }

    /** Explicit bootstrap point; a failed complete decode cannot publish a partial recovery. */
    public void recover() {
        Map<UUID, FrozenProductionPlan> restored = new LinkedHashMap<>();
        for (FrozenProductionPlan plan : persistence.loadAll())
            restored.put(plan.goalId(), plan);
        plans.putAll(restored);
    }

    @Nullable
    public FrozenProductionPlan current(@Nullable UUID goalId) { return plans.get(goalId); }

    public boolean containsGoal(@Nullable UUID goalId) { return plans.containsKey(goalId); }

    public List<FrozenProductionPlan> snapshot() { return List.copyOf(plans.values()); }

    /** Called after the admission batch commits; retries cannot rewind an advanced plan. */
    public void admissionCommitted(FrozenProductionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        plans.putIfAbsent(plan.goalId(), plan);
    }

    /**
     * Trusted local/remote application callback after a combined storage commit. Do not call
     * {@link #persist} here: splitting the plan from lifecycle/lease/outbox would break atomicity.
     * This is not a player-accessible write service or an authorization bypass.
     */
    public void publishCommitted(FrozenProductionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        plans.put(plan.goalId(), plan);
    }

    /** Singleton transition: persist first; a storage failure leaves the live value untouched. */
    public void persist(FrozenProductionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        persistence.save(plan);
        plans.put(plan.goalId(), plan);
    }

    /** Reads the durable receipt, intentionally separate from the live projection. */
    public Optional<FrozenProductionPlan> durable(UUID goalId) {
        return persistence.load(Objects.requireNonNull(goalId, "goalId"));
    }

    /** Source authority survives retirement of a bounded remote-recovery record. */
    public Optional<UUID> sourceClusterForRun(UUID runId, @Nullable ProductionRun run) {
        UUID expectedRunId = Objects.requireNonNull(runId, "runId");
        if (run == null || !run.runId().equals(expectedRunId)) return Optional.empty();
        FrozenProductionPlan plan = plans.get(run.goalId());
        return plan == null || !plan.goalId().equals(run.goalId())
            ? Optional.empty() : Optional.of(plan.clusterId());
    }
}
