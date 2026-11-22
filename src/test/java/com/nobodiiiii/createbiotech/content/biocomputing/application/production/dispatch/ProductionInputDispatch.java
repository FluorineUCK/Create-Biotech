package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.DispatchManifest;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/** Finite production input decisions, separate from generic Computer execution. */
public final class ProductionInputDispatch {
    private static final Logger LOGGER=LogUtils.getLogger();
    /** Internal exact proof handle; never serialized or exposed as a program data value. */
    public interface SafeRetry {
        void persist();
        Optional<WaitReason> normalize();
    }
    public interface Access {
        Optional<ClusterEngineState> snapshot(UUID clusterId);
        FrozenProductionPlan plan(UUID goalId);
        CommunicationFrequency frequency(UUID clusterId,UUID memberId);
        Optional<CreateLogisticsGateway.PersistedCommit> inputAttempt(UUID intentId);
        AgentInputContract inputContract(UUID runId);
        Optional<SafeRetry> authorizeRetry(ProductionEffect.CreateDispatch intent,
            CreateLogisticsGateway.PersistedCommit attempt,AgentInputContract existing);
        boolean replayInput(AgentInputContract existing,ProductionEffect.CreateDispatch intent);
        void requireReset(UUID runId,UUID agentId);
        boolean hasBareAttempt(UUID intentId);
        boolean providerCurrent(ProductionEffect.CreateDispatch intent,SelectedProviderDescriptor selected);
        AgentResolution resolve(CreateRouteKey route,UUID clusterId,CommunicationFrequency frequency);
        boolean loadedInputClaims(CreateRouteKey route);
        boolean bare(ProductionEffect.CreateDispatch intent,Map<StackKey,Long> manifest,
            AgentResolution resolution,ClusterEngineState state,GoalRecord goal,FrozenProductionPlan plan);
        boolean agent(ProductionEffect.CreateDispatch intent,Map<StackKey,Long> manifest,
            ClusterEngineState state,GoalRecord goal,AgentAddressRegistration registration,FrozenProductionPlan plan);
        boolean remote(ProductionEffect.CreateDispatch intent,ClusterEngineState state,GoalRecord goal,
            ProcessRecord process,AgentAddressRegistration registration,CommunicationFrequency frequency);
        boolean waitSource(ClusterEngineState state,GoalRecord goal,WaitReason reason);
    }
    private final Access access;
    public ProductionInputDispatch(Access access) {this.access=Objects.requireNonNull(access,"access");}
    public boolean commit(ProductionEffect.CreateDispatch intent) {
        Map<StackKey,Long> manifest;
        try {manifest=DispatchManifest.decode(intent.ingredients());}
        catch(IllegalArgumentException|ArithmeticException invalid) {return false;}
        ClusterEngineState state=access.snapshot(intent.clusterId()).orElse(null);
        if(state==null || !state.epochId().equals(intent.fence().epochId()))return false;
        ProcessRecord process=state.processes().get(intent.processId());
        GoalRecord goal=process==null?null:state.goals().get(process.goalId());
        if(process==null || goal==null)return false;
        FrozenProductionPlan plan=access.plan(goal.goalId());
        if(plan==null || plan.runs().currentRun()==null
            || !plan.runs().currentRun().runId().equals(intent.runId())
            || !plan.stages().getFirst().route().equals(intent.route())
            || !plan.materializeCurrentRun(intent.generation()).manifest().equals(manifest))return false;
        CommunicationFrequency frequency=access.frequency(intent.clusterId(),process.owningComputerId());
        return replayBeforeProvider(() -> replay(intent,manifest,state,goal,plan,frequency),
            () -> fresh(intent,manifest,state,goal,process,plan,frequency));
    }

    public Optional<Boolean> replay(ProductionEffect.CreateDispatch intent,Map<StackKey,Long> manifest,
        ClusterEngineState state,GoalRecord goal,FrozenProductionPlan plan,CommunicationFrequency frequency) {
        CreateLogisticsGateway.PersistedCommit attempt;
        try {attempt=access.inputAttempt(intent.intentId()).orElse(null);}
        catch(RuntimeException unavailable) {return Optional.of(access.waitSource(state,goal,WaitReason.RESET_REQUIRED));}
        AgentInputContract existing=access.inputContract(intent.runId());
        if(attempt!=null && attempt.allowsRetryWithoutMaterial()) {
            Optional<SafeRetry> repaired=access.authorizeRetry(intent,attempt,existing);
            if(repaired.isPresent()) {
                SafeRetry proof=repaired.orElseThrow();
                proof.persist();
                Optional<WaitReason> normalization=proof.normalize();
                if(normalization.isPresent())return Optional.of(access.waitSource(state,goal,normalization.orElseThrow()));
                return Optional.empty();
            }
            return Optional.of(access.waitSource(state,goal,WaitReason.RESET_REQUIRED));
        }
        if(existing!=null) {
            if(access.replayInput(existing,intent))return Optional.of(true);
            AgentInputContract.State durable=existing.snapshot();
            LOGGER.error("Agent input replay proof mismatch for run {} in phase {}",intent.runId(),durable.phase());
            existing.stopAfterIrreversibleBoundary(durable.run().fence());
            access.requireReset(intent.runId(),durable.agentId());
            return Optional.of(access.waitSource(state,goal,WaitReason.RESET_REQUIRED));
        }
        boolean bare;
        try {bare=access.hasBareAttempt(intent.intentId());}
        catch(RuntimeException unavailable) {return Optional.of(access.waitSource(state,goal,WaitReason.RESET_REQUIRED));}
        if(!bare)return Optional.empty();
        AgentResolution resolution=access.resolve(intent.route(),intent.clusterId(),frequency);
        return Optional.of(access.bare(intent,manifest,resolution,state,goal,plan));
    }

    private boolean fresh(ProductionEffect.CreateDispatch intent,Map<StackKey,Long> manifest,
        ClusterEngineState state,GoalRecord goal,ProcessRecord process,FrozenProductionPlan plan,
        CommunicationFrequency frequency) {
        SelectedProviderDescriptor selected=plan.selectedProvider();
        if(!access.providerCurrent(intent,selected))return waitProvider(state,goal,plan,frequency);
        AgentResolution resolution=access.resolve(intent.route(),intent.clusterId(),frequency);
        return switch(selected.providerKind()) {
            case PATTERN,FACTORY_GAUGE,ORDINARY_PROVIDER ->
                resolution instanceof AgentResolution.NeverRegistered && !access.loadedInputClaims(intent.route())
                    ?access.bare(intent,manifest,resolution,state,goal,plan):waitProvider(state,goal,plan,frequency);
            case AGENT -> {
                if(resolution instanceof AgentResolution.KnownUnavailable)
                    yield access.waitSource(state,goal,WaitReason.AGENT_UNLOADED);
                if(resolution instanceof AgentResolution.Conflict)
                    yield access.waitSource(state,goal,WaitReason.AGENT_ADDRESS_CONFLICT);
                if(!(resolution instanceof AgentResolution.Unique unique))
                    yield access.waitSource(state,goal,WaitReason.ROUTE);
                yield !unique.registration().ownerClusterId().equals(intent.clusterId())
                    ?access.remote(intent,state,goal,process,unique.registration(),frequency)
                    :access.agent(intent,manifest,state,goal,unique.registration(),plan);
            }
        };
    }

    private boolean waitProvider(ClusterEngineState state,GoalRecord goal,FrozenProductionPlan plan,
        CommunicationFrequency frequency) {
        return switch(plan.selectedProvider().providerKind()) {
            case PATTERN -> access.waitSource(state,goal,WaitReason.ROUTE);
            case FACTORY_GAUGE,ORDINARY_PROVIDER -> access.waitSource(state,goal,WaitReason.PROCESS);
            case AGENT -> switch(access.resolve(plan.stages().getFirst().route(),state.clusterId(),frequency)) {
                case AgentResolution.Conflict ignored -> access.waitSource(state,goal,WaitReason.AGENT_ADDRESS_CONFLICT);
                case AgentResolution.KnownUnavailable ignored -> access.waitSource(state,goal,WaitReason.AGENT_UNLOADED);
                default -> access.waitSource(state,goal,WaitReason.PROCESS);
            };
        };
    }

    /** A durable false is just as final as a durable true; only empty permits mutable provider work. */
    public static boolean replayBeforeProvider(Supplier<Optional<Boolean>> durableReplay,
        BooleanSupplier mutableProviderAttempt) {
        durableReplay=Objects.requireNonNull(durableReplay,"durableReplay");
        mutableProviderAttempt=Objects.requireNonNull(mutableProviderAttempt,"mutableProviderAttempt");
        Optional<Boolean> replay=Objects.requireNonNull(durableReplay.get(),"durable replay result");
        return replay.isPresent()?replay.orElseThrow():mutableProviderAttempt.getAsBoolean();
    }
}
