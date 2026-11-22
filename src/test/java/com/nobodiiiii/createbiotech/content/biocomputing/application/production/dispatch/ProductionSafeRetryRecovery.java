package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;

import java.util.*;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.DispatchManifest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.*;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;

/** Current production authority and bounded pre-dispatch compensation, never generic Computer RAM. */
public final class ProductionSafeRetryRecovery<T> {
    /** Trusted internal proof type T is neither serialized here nor exposed to player programs. */
    public interface Access<T> {
        @Nullable RemoteComputationExchange exchange(UUID runId);
        @Nullable FrozenProductionPlan plan(UUID goalId);
        Optional<ClusterEngineState> snapshot(UUID clusterId);
        @Nullable UUID selectedAgent(SelectedProviderDescriptor provider);
        OptionalLong selectedAgentGeneration(SelectedProviderDescriptor provider);
        boolean handoffOwnsRun(FrozenProductionPlan plan,UUID runId);
        RecoveryLedger.State recovery();
        Optional<T> proof(ProductionEffect.CreateDispatch intent,CreateLogisticsGateway.PersistedCommit persisted,
            Optional<AgentInputContract.State> input,RecoveryLedger.State recovery);
        @Nullable AgentResetService reset(UUID agentId);
        @Nullable ProductionOutputContract output(UUID runId);
        @Nullable AgentCoordinator coordinator(UUID agentId);
        boolean rollbackAdmission(RemoteRequestKey key,AgentCoordinator coordinator);
        void removeInput(UUID runId,@Nullable AgentInputContract existing);
    }
    private final Access<T> access;
    public ProductionSafeRetryRecovery(Access<T> access) {this.access=Objects.requireNonNull(access,"access");}
    public Optional<T> authorize(ProductionEffect.CreateDispatch intent,
        CreateLogisticsGateway.PersistedCommit persisted,Optional<AgentInputContract.State> inputState) {
        RemoteComputationExchange exchange=access.exchange(intent.runId());
        FrozenProductionPlan plan=exchange==null?null:access.plan(exchange.goalId());
        ProductionRun run=plan==null?null:plan.runs().currentRun();
        ClusterEngineState state=access.snapshot(intent.clusterId()).orElse(null);
        ProcessRecord process=state==null?null:state.processes().get(intent.processId());
        GoalRecord goal=process==null || state==null?null:state.goals().get(process.goalId());
        ProductionEffect pending=state==null?null:state.pendingIntents().get(intent.intentId());
        UUID selectedAgent=plan==null?null:access.selectedAgent(plan.selectedProvider());
        OptionalLong selectedAgentGeneration=plan==null?OptionalLong.empty()
            :access.selectedAgentGeneration(plan.selectedProvider());
        if(plan==null || run==null || state==null || process==null || goal==null
            || !state.clusterId().equals(intent.clusterId())
            || !state.epochId().equals(intent.fence().epochId())
            || !intent.equals(pending)
            || !process.goalId().equals(plan.goalId())
            || !goal.goalId().equals(plan.goalId())
            || !run.runId().equals(intent.runId())
            || !exchange.runId().equals(intent.runId())
            || !exchange.goalId().equals(plan.goalId())
            || !exchange.lease().expansionRequest().parentProcessId().equals(intent.processId())
            || !plan.stages().getFirst().route().equals(intent.route())
            || !Objects.equals(selectedAgent,persisted.command().agentId())
            || selectedAgentGeneration.isEmpty()
            || selectedAgentGeneration.getAsLong()!=persisted.command().agentResetGeneration()
            || !access.handoffOwnsRun(plan,intent.runId()))return Optional.empty();
        try {
            if(!plan.materializeCurrentRun(intent.generation()).manifest()
                .equals(DispatchManifest.decode(intent.ingredients())))return Optional.empty();
        } catch(IllegalArgumentException|ArithmeticException invalid) {return Optional.empty();}
        return access.proof(intent,persisted,inputState,access.recovery());
    }
    public Optional<WaitReason> normalize(ProductionEffect.CreateDispatch intent,
        @Nullable AgentInputContract existing,CreateDispatchCommand command) {
        RecoveryLedger.State recoveryState=access.recovery();
        List<Map.Entry<RemoteRequestKey,RemoteAgentRecord>> records=recoveryState.records()
            .entrySet().stream().filter(entry -> entry.getKey().runId().equals(intent.runId())).toList();
        if(records.size()>1)return fail(existing,null,null);
        RemoteRequestKey key=records.isEmpty()?null:records.getFirst().getKey();
        AgentResetService reset=access.reset(command.agentId());
        if(reset==null)return Optional.of(WaitReason.AGENT_UNLOADED);
        if(key==null)key=reset.snapshot().activeKey()
            .filter(candidate -> candidate.runId().equals(intent.runId())).orElse(null);
        if(key!=null && (!key.requestId().equals(intent.intentId())
            || key.fence()!=intent.generation()
            || key.agentGeneration()!=command.agentResetGeneration()))return fail(existing,reset,null);
        AgentResetService.State resetState=reset.snapshot();
        if(resetState.resetRequired())return fail(existing,reset,key);
        if(resetState.activeKey().isPresent()) {
            if(key==null || resetState.activeKey().filter(key::equals).isEmpty())return fail(existing,reset,key);
        }
        ProductionOutputContract output=access.output(intent.runId());
        AgentCoordinator coordinator=records.isEmpty()?null:access.coordinator(command.agentId());
        if(!records.isEmpty() && coordinator==null)return Optional.of(WaitReason.AGENT_UNLOADED);
        RemoteRequestKey exactKey=key;
        AgentCoordinator exactCoordinator=coordinator;
        return normalizeSteps(output,
            () -> records.isEmpty() || access.rollbackAdmission(exactKey,exactCoordinator),
            () -> reset.snapshot().activeKey().isEmpty() || reset.abandonFutureContract(exactKey),
            () -> access.removeInput(intent.runId(),existing),
            () -> fail(existing,reset,exactKey));
    }

    private static Optional<WaitReason> fail(@Nullable AgentInputContract input,
        @Nullable AgentResetService reset,@Nullable RemoteRequestKey key) {
        if(input!=null)input.stopAfterIrreversibleBoundary(input.snapshot().run().fence());
        if(reset!=null && key!=null && reset.snapshot().activeKey().filter(key::equals).isPresent())
            reset.requireReset(key);
        return Optional.of(WaitReason.RESET_REQUIRED);
    }

    /** Output rollback must be durable while Recovery/Reset authority still exists. */
    public static Optional<WaitReason> normalizeSteps(@Nullable ProductionOutputContract output,
        BooleanSupplier rollbackRecovery,BooleanSupplier abandonReset,Runnable removeInput,Runnable retainResetAuthority) {
        rollbackRecovery=Objects.requireNonNull(rollbackRecovery,"rollbackRecovery");
        abandonReset=Objects.requireNonNull(abandonReset,"abandonReset");
        removeInput=Objects.requireNonNull(removeInput,"removeInput");
        retainResetAuthority=Objects.requireNonNull(retainResetAuthority,"retainResetAuthority");
        if(output==null) {
            retainResetAuthority.run();return Optional.of(WaitReason.RESET_REQUIRED);
        }
        if(output.milestone()==ProductionOutputContract.Milestone.OBSERVING && !output.abortBeforeInputDispatch()) {
            retainResetAuthority.run();return Optional.of(WaitReason.RESET_REQUIRED);
        }
        if(output.milestone()!=ProductionOutputContract.Milestone.WAITING_INPUT_READY) {
            retainResetAuthority.run();return Optional.of(WaitReason.RESET_REQUIRED);
        }
        if(!rollbackRecovery.getAsBoolean())return Optional.of(WaitReason.PROCESS);
        if(!abandonReset.getAsBoolean()) {
            retainResetAuthority.run();return Optional.of(WaitReason.RESET_REQUIRED);
        }
        removeInput.run();return Optional.empty();
    }
}
