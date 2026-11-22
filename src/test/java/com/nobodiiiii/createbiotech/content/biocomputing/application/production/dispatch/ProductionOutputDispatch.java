package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;

/**
 * Checked production output decisions, separate from generic Computer execution.
 * B/P are trusted native facts and prepared moves, never player values, shared RAM or world-write handles.
 * The physical adapter retains fixed native preflight and durable gateway semantics.
 */
public final class ProductionOutputDispatch<B, P> {
    public interface Access<B, P> {
        ProductionOutputContract contract(UUID runId);
        ProductionOutputPolicy policy(UUID runId);
        B probe(UUID runId);
        Optional<ClusterEngineState> source(UUID runId);
        FrozenProductionPlan plan(UUID goalId);
        boolean hasOutput(B probe, StackKey exactOutput);
        Optional<P> prepare(B probe, ProductionOutputContract.DispatchInstruction instruction);
        void persistPrepared(B probe, P prepared);
        OutputLogisticsGateway.CommitResult commit(ProductionOutputContract contract, ProductionOutputPolicy policy,
            P prepared, ProductionOutputContract.DispatchInstruction instruction);
        boolean waitSource(ClusterEngineState source, GoalRecord goal, WaitReason reason);
        void rejected(UUID runId, OutputLogisticsGateway.Rejection rejection);
        void confirmationMismatch(UUID runId);
        void fence(ProductionOutputContract contract, WaitReason reason);
        RemoteRequestKey requestKey(UUID runId);
        void recordOutput(RemoteRequestKey key, ProductionOutputContract.Snapshot snapshot);
    }
    private final Access<B, P> access;

    public ProductionOutputDispatch(Access<B, P> access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    public boolean commit(ProductionEffect.OutputPackageRequest intent) {
        ProductionOutputContract contract = access.contract(intent.runId());
        ProductionOutputPolicy policy = access.policy(intent.runId());
        B probe = access.probe(intent.runId());
        ClusterEngineState source = access.source(intent.runId()).orElse(null);
        GoalRecord goal = source == null || contract == null ? null
            : source.goals().get(contract.snapshot().run().goalId());
        FrozenProductionPlan plan = goal == null ? null : access.plan(goal.goalId());
        if (contract == null || policy == null || probe == null || source == null || goal == null
            || plan == null || !plan.output().equals(intent.exactOutput())
            || !access.hasOutput(probe, intent.exactOutput())
            || !source.clusterId().equals(intent.clusterId())
            || !source.epochId().equals(intent.fence().epochId())) return false;
        ProductionOutputContract.DispatchInstruction instruction = contract.snapshot().dispatchIntent()
            .orElse(null);
        if (instruction == null || !instruction.intentId().equals(intent.intentId())
            || !instruction.destination().equals(intent.destination())
            || intent.fence().validationFence() != instruction.fence()
            || !intent.fence().targetId().equals(instruction.goalId())) return false;
        P prepared = access.prepare(probe, instruction).orElse(null);
        if (prepared == null) return access.waitSource(source, goal, WaitReason.MATERIAL);
        access.persistPrepared(probe, prepared);
        OutputLogisticsGateway.CommitResult result = access.commit(contract, policy, prepared, instruction);
        if (!result.operation().isPresent()) {
            access.rejected(intent.runId(), result.rejection());
            if (result.rejection() == OutputLogisticsGateway.Rejection.NATIVE_MOVEMENT_UNCERTAIN
                || result.rejection() == OutputLogisticsGateway.Rejection.DURABLE_STORE_UNAVAILABLE
                || result.rejection() == OutputLogisticsGateway.Rejection.DURABLE_INTENT_MISMATCH
                || result.rejection() == OutputLogisticsGateway.Rejection.INTENT_ID_COLLISION)
                access.fence(contract, WaitReason.RESET_REQUIRED);
            return false;
        }
        if (!contract.confirmDispatch(result.confirmationReceipt().orElseThrow())) {
            access.confirmationMismatch(intent.runId());
            access.fence(contract, WaitReason.RESET_REQUIRED);
            return false;
        }
        RemoteRequestKey key = access.requestKey(intent.runId());
        if (key != null) access.recordOutput(key, contract.snapshot());
        return true;
    }
}
