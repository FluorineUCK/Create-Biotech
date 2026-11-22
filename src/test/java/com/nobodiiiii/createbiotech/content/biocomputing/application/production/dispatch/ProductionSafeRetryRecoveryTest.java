package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol.RemoteComputationExchange;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.*;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionSafeRetryRecoveryTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null)
            type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
                .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true);field.setBoolean(null,true);Items.AIR.getDefaultInstance();
    }
    @Test void noLiveExchangeOrPlanCannotConsultRecoveryOrGrantProof() {
        var f=new Facts();
        assertTrue(f.owner().authorize(f.intent,f.persisted,Optional.empty()).isEmpty());
        assertEquals(List.of("exchange","snapshot"),f.calls);
        assertFalse(f.calls.contains("proof"));assertFalse(f.calls.contains("recovery"));
    }
    @Test void exactLiveAuthorityUsesActualPlanExchangePendingAndJournalBeforeProof() {
        var f=new Facts();f.enableExchange();
        assertSame(f.command,f.owner().authorize(f.intent,f.persisted,Optional.empty()).orElseThrow());
        assertEquals(List.of("exchange","plan","snapshot","agent","generation","handoff","recovery","proof"),f.calls);
        assertTrue(f.persisted.allowsRetryWithoutMaterial());
        assertSame(f.intent,f.state.pendingIntents().get(f.intent.intentId()));
    }
    @Test void unavailableResetWaitsExplicitlyWithoutDroppingInputOrWorldReads() {
        var f=new Facts();
        assertEquals(Optional.of(WaitReason.AGENT_UNLOADED),f.owner().normalize(f.intent,null,f.command));
        assertEquals(List.of("recovery","reset"),f.calls);
    }
    @Test void missingPlanStateProcessAndGoalRejectWithoutRecoveryOrProof() {
        for(int variant=0;variant<4;variant++) {
            var f=new Facts();f.enableExchange();
            if(variant==0)f.plan=null;
            if(variant==1)f.state=null;
            if(variant==2)f.state=ClusterEngineState.empty(id(1),id(2));
            if(variant==3) {
                var profile=ProductionResourceProfile.forProfession(ProductionProfession.CLERIC,5,0).orElseThrow();
                var orphan=new ProcessRecord(id(7),id(4),id(3),0,profile,ProcessKind.AGENT,ProcessState.RUNNABLE,null,0,Map.of());
                f.state=new ClusterEngineState(id(1),id(2),11,0,0,0,Map.of(),Map.of(),Map.of(),Map.of(id(7),orphan),
                    List.of(new ClusterEngineState.MemberSnapshot(id(3),profile,true,0,0)),Map.of(),Set.of(),0,null);
            }
            assertTrue(f.owner().authorize(f.intent,f.persisted,Optional.empty()).isEmpty());
            assertFalse(f.calls.contains("handoff"));assertFalse(f.calls.contains("recovery"));assertFalse(f.calls.contains("proof"));
            assertTrue(f.calls.contains("snapshot"));
        }
    }
    @Test void changedEpochPendingRunRouteAgentGenerationAndHandoffNeverGrantProof() {
        for(int variant=0;variant<10;variant++) {
            var f=new Facts();f.enableExchange();var request=f.intent;
            if(variant==0)f.state=f.state(id(2),Map.of());
            if(variant==1)request=new ProductionEffect.CreateDispatch(id(11),id(1),11,
                new ProductionEffect.TargetFence(id(99),id(3),0,11),id(7),id(6),f.route,request.ingredients());
            if(variant==2)request=f.request(id(99),f.route,request.ingredients());
            if(variant==3)request=f.request(id(6),new CreateRouteKey(id(9),"changed"),request.ingredients());
            if(variant==2 || variant==3)f.state=f.state(id(2),Map.of(id(11),request));
            if(variant==4)f.agent=id(99);
            if(variant==5)f.agentGeneration=OptionalLong.empty();
            if(variant==6)f.handoff=false;
            if(variant==7)f.agentGeneration=OptionalLong.of(2);
            if(variant==8)f.agent=null;
            if(variant==9) {
                var plan=f.plan;
                f.plan=new FrozenProductionPlan(plan.goalId(),plan.clusterId(),plan.selectedProvider(),plan.patternKind(),plan.output(),
                    plan.outputPerOperation(),plan.deliveryRoute(),plan.stages(),ProductionRun.RunSequence.completed(),plan.observationScope(),
                    plan.baselineCount(),plan.requestedOutputCount(),plan.dispatchQueued(),plan.cancelAfterDrain(),plan.remoteExpansion());
            }
            assertTrue(f.owner().authorize(request,f.persisted,Optional.empty()).isEmpty());
            assertFalse(f.calls.contains("recovery"));assertFalse(f.calls.contains("proof"));
        }
    }
    @Test void exactTypedManifestAndAggregationOverflowAreRejectedAfterHandoffBeforeProof() {
        for(boolean overflow:List.of(false,true)) {
            var f=new Facts();f.enableExchange();
            var changed=new ItemStack(Items.IRON_INGOT);changed.set(DataComponents.CUSTOM_NAME,Component.literal("not-source"));
            var ingredients=overflow?List.of(new OrderedIngredient(f.manifest.keySet().iterator().next(),Long.MAX_VALUE),
                new OrderedIngredient(f.manifest.keySet().iterator().next(),1))
                :List.of(new OrderedIngredient(new StackKey(changed),2));
            var request=f.request(id(6),f.route,ingredients);f.state=f.state(id(2),Map.of(id(11),request));
            assertTrue(f.owner().authorize(request,f.persisted,Optional.empty()).isEmpty());
            assertTrue(f.calls.contains("handoff"));assertFalse(f.calls.contains("recovery"));assertFalse(f.calls.contains("proof"));
            assertEquals(2L,f.manifest.values().iterator().next());
        }
    }
    @Test void proofPortReceivesExactInputAndItsRejectionOrExceptionCannotBecomeAuthority() {
        var f=new Facts();f.enableExchange();f.installContracts(false,f.key());f.calls.clear();f.grant=false;
        var input=Optional.of(f.input.snapshot());f.expectedInput=input;
        assertTrue(f.owner().authorize(f.intent,f.persisted,input).isEmpty());
        assertEquals("proof",f.calls.getLast());
        f.proofFailure=new IllegalStateException("proof unavailable");
        assertSame(f.proofFailure,assertThrows(IllegalStateException.class,() -> f.owner().authorize(f.intent,f.persisted,input)));
        assertEquals(AgentInputPhase.DISPATCH_INTENT,f.input.phase());
    }
    @Test void exactResetAndOutputAbortBeforeInputRemovalWithRealDurableContracts() {
        var f=new Facts();f.installContracts(true,f.key());f.calls.clear();
        assertEquals(Optional.empty(),f.owner().normalize(f.intent,f.input,f.command));
        assertTrue(f.calls.indexOf("output-persist")<f.calls.indexOf("remove"));
        assertFalse(f.calls.contains("rollback"));assertFalse(f.calls.contains("coordinator"));
        assertEquals(ProductionOutputContract.Milestone.WAITING_INPUT_READY,f.output.milestone());
        assertTrue(f.reset.snapshot().activeKey().isEmpty());
        assertTrue(f.journal.read("input_contract/"+id(6),AgentInputContract.State.class,RegistryAccess.EMPTY).isEmpty());
        assertEquals(f.reset.snapshot(),f.journal.resetStore(RegistryAccess.EMPTY).load(id(20)).orElseThrow());
        assertEquals(f.output.snapshot(),f.journal.read("output_contract/"+id(6),ProductionOutputContract.Snapshot.class,RegistryAccess.EMPTY).orElseThrow());
        assertEquals(Optional.empty(),f.owner().normalize(f.intent,null,f.command),"compensation remains restart-idempotent");
        assertTrue(f.journal.inputCommitStore(RegistryAccess.EMPTY).load(id(11)).orElseThrow().allowsRetryWithoutMaterial());
    }
    @Test void absentOutputStopsInputAndRequiresExactResetWithoutDroppingItsStoredContract() {
        var f=new Facts();f.installContracts(false,f.key());f.output=null;f.calls.clear();
        assertEquals(Optional.of(WaitReason.RESET_REQUIRED),f.owner().normalize(f.intent,f.input,f.command));
        assertEquals(AgentInputPhase.RESET_REQUIRED,f.input.phase());assertTrue(f.reset.snapshot().resetRequired());
        assertEquals(f.input.snapshot(),f.journal.read("input_contract/"+id(6),AgentInputContract.State.class,RegistryAccess.EMPTY).orElseThrow());
        assertTrue(f.reset.snapshot().activeKey().isPresent());assertFalse(f.calls.contains("remove"));
    }
    @Test void foreignResetKeysAndAlreadyRequiredResetCannotReleaseAnyAuthority() {
        for(int variant=0;variant<3;variant++) {
            var f=new Facts();var key=variant==0?new RemoteRequestKey(id(99),id(6),11,11,1)
                :variant==1?new RemoteRequestKey(id(11),id(99),11,11,1):f.key();
            f.installContracts(false,key);if(variant==2)f.reset.requireReset(key);f.calls.clear();
            assertEquals(Optional.of(WaitReason.RESET_REQUIRED),f.owner().normalize(f.intent,f.input,f.command));
            assertEquals(Optional.of(key),f.reset.snapshot().activeKey());
            assertEquals(variant==2,f.reset.snapshot().resetRequired(),"unmatched key must not gain reset authority");
            assertEquals(AgentInputPhase.RESET_REQUIRED,f.input.phase());
            assertFalse(f.calls.contains("output"));assertFalse(f.calls.contains("remove"));
        }
    }
    @Test void loadedRecoveryWithoutCoordinatorIsUnavailableAndDoesNotAbortOutput() {
        var f=new Facts();f.installContracts(true,f.key());f.addRecovery(f.key());f.calls.clear();
        assertEquals(Optional.of(WaitReason.AGENT_UNLOADED),f.owner().normalize(f.intent,f.input,f.command));
        assertEquals(List.of("recovery","reset","output","coordinator"),f.calls);
        assertEquals(ProductionOutputContract.Milestone.OBSERVING,f.output.milestone());
        assertEquals(AgentInputPhase.DISPATCH_INTENT,f.input.phase());assertEquals(Optional.of(f.key()),f.reset.snapshot().activeKey());
    }
    @Test void outputSignalCannotBeRolledBackAndKeepsRecoveryResetInputClosed() {
        var f=new Facts();f.completion=AgentCompletionMode.SIGNAL;f.installContracts(true,f.key());assertTrue(f.output.publishSignal(11));f.calls.clear();
        assertEquals(Optional.of(WaitReason.RESET_REQUIRED),f.owner().normalize(f.intent,f.input,f.command));
        assertTrue(f.reset.snapshot().resetRequired());assertEquals(AgentInputPhase.RESET_REQUIRED,f.input.phase());
        assertEquals(ProductionOutputContract.Milestone.OBSERVING,f.output.milestone());assertFalse(f.calls.contains("remove"));
    }
    @Test void publicNormalizerStopsAtRecoveryOrResetFailureAndKeepsOrderAndExceptions() {
        var f=new Facts();f.installContracts(true,f.key());var calls=new ArrayList<String>();
        assertEquals(Optional.of(WaitReason.PROCESS),ProductionSafeRetryRecovery.normalizeSteps(f.output,
            () -> {assertEquals(ProductionOutputContract.Milestone.WAITING_INPUT_READY,f.output.milestone());calls.add("recovery");return false;},
            () -> {throw new AssertionError("Reset must stay owned");},() -> {throw new AssertionError("Input must stay owned");},
            () -> {throw new AssertionError("PROCESS is not RESET_REQUIRED");}));
        assertEquals(List.of("recovery"),calls);calls.clear();
        assertEquals(Optional.of(WaitReason.RESET_REQUIRED),ProductionSafeRetryRecovery.normalizeSteps(f.output,
            () -> {calls.add("recovery");return true;},() -> {calls.add("reset");return false;},
            () -> {throw new AssertionError("Input must stay owned");},() -> calls.add("retain")));
        assertEquals(List.of("recovery","reset","retain"),calls);
        var failure=new IllegalStateException("rollback unavailable");
        assertSame(failure,assertThrows(IllegalStateException.class,() -> ProductionSafeRetryRecovery.normalizeSteps(f.output,
            () -> {throw failure;},() -> {throw new AssertionError();},() -> {throw new AssertionError();},() -> {throw new AssertionError();})));
    }
    @Test void failedOutputPersistenceCannotContinueToRecoveryResetOrInputRemoval() {
        var f=new Facts();f.installContracts(true,f.key());f.calls.clear();f.outputFailure=new IllegalStateException("store failed");
        assertSame(f.outputFailure,assertThrows(IllegalStateException.class,() -> f.owner().normalize(f.intent,f.input,f.command)));
        assertFalse(f.calls.contains("rollback"));assertFalse(f.calls.contains("remove"));
        assertEquals(Optional.of(f.key()),f.reset.snapshot().activeKey());assertFalse(f.reset.snapshot().resetRequired());
        assertEquals(AgentInputPhase.DISPATCH_INTENT,f.input.phase());
    }
    private static UUID id(long n) {return new UUID(0,n);}
    private static FactoryRuntimeConfig config() {
        return new FactoryRuntimeConfig(1,1_000,4,32,4,4,32,64,20,10,100,3,32,16);
    }
    private static final class Facts implements ProductionSafeRetryRecovery.Access<CreateDispatchCommand> {
        final List<String> calls=new ArrayList<>();
        final FactoryOrchestrationSavedData journal=new FactoryOrchestrationSavedData();
        final CreateRouteKey route=new CreateRouteKey(id(9),"input");
        final Map<StackKey,Long> manifest=Map.of(new StackKey(new ItemStack(Items.IRON_INGOT)),2L);
        final ProductionRun run=new ProductionRun(id(6),id(4),id(5),11,1,InputMode.EXACT,List.of(route),route,0,manifest);
        final ProductionResourceProfile profile=ProductionResourceProfile.forProfession(ProductionProfession.NITWIT,1,0).orElseThrow();
        final ProcessRecord process=new ProcessRecord(id(7),id(4),id(3),0,profile,ProcessKind.GOAL,ProcessState.RUNNABLE,null,0,Map.of());
        final GoalRecord goal=new GoalRecord(id(4),id(1),new GoalSubmission(id(4),id(5),1,InputMode.EXACT),route,
            new PolicySnapshot(0,0,false),GoalState.RUNNABLE,null);
        final ProductionEffect.CreateDispatch intent=new ProductionEffect.CreateDispatch(id(11),id(1),11,
            new ProductionEffect.TargetFence(id(2),id(3),0,11),id(7),id(6),route,
            List.of(new OrderedIngredient(manifest.keySet().iterator().next(),2)));
        final CreateDispatchCommand command=new CreateDispatchCommand(id(11),id(20),1,11,id(6),id(21),0,route,11,manifest);
        final CreateLogisticsGateway.PersistedCommit persisted;
        ClusterEngineState state=new ClusterEngineState(id(1),id(2),11,1,0,0,Map.of(id(4),goal),Map.of(id(4),0L),
            Map.of(id(4),ProcessKind.GOAL),Map.of(id(7),process),
            List.of(new ClusterEngineState.MemberSnapshot(id(3),profile,true,0,0)),Map.of(id(11),intent),Set.of(),0,null);
        FrozenProductionPlan plan;
        RemoteComputationExchange exchange;
        RecoveryLedger.State recovery=new RecoveryLedger.State(Map.of(),Map.of(),Map.of(),Map.of());
        UUID agent=id(20);OptionalLong agentGeneration=OptionalLong.of(1);
        boolean handoff=true,grant=true;
        RuntimeException proofFailure,outputFailure;
        Optional<AgentInputContract.State> expectedInput;
        AgentCompletionMode completion=AgentCompletionMode.QUIET;
        AgentInputContract input;ProductionOutputContract output;AgentResetService reset;
        Facts() {
            var key=manifest.keySet().iterator().next();var outputKey=new StackKey(new ItemStack(Items.GOLD_INGOT));
            var selected=new SelectedProviderDescriptor(new RouteSelectionScope(id(2),id(10),outputKey,0,0,id(12)),
                id(8),id(5),"agent/"+id(20)+"/1/input",RouteCandidate.ProviderKind.AGENT,0,RouteCandidate.Availability.AVAILABLE);
            plan=new FrozenProductionPlan(id(4),id(1),selected,PatternKind.STANDARD,outputKey,1,route,
                List.of(new FrozenProductionPlan.FrozenStage(0,route,manifest,List.of(new OrderedIngredient(key,2)))),
                new ProductionRun.RunSequence(run,1),new StockObservationScope.NetworkSummary(route.logisticsNetworkId(),outputKey,1),
                0,1,false,false,Optional.empty());
            var gateway=new CreateLogisticsGateway(ignored -> new CreateLogisticsGateway.CommitContext(id(20),1,11,route,
                false,11,AgentInputPhase.DISPATCH_INTENT,command),ignored -> {throw new AssertionError("no items may move");},
                () -> true,journal.inputCommitStore(RegistryAccess.EMPTY));
            assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,gateway.commit(command).rejection());
            persisted=journal.inputCommitStore(RegistryAccess.EMPTY).load(id(11)).orElseThrow();
        }
        void enableExchange() {
            var frequency=CommunicationFrequency.of("17");
            var source=new RadioEndpoint(id(1),id(3),new SpaceAddress(Level.OVERWORLD,null,new BlockPos(0,64,0)),
                0,frequency,1,Set.of());
            var receiver=new RadioEndpoint(id(99),id(33),new SpaceAddress(Level.OVERWORLD,null,new BlockPos(8,64,0)),
                1,frequency,1,Set.of(ProductionCapability.ALCHEMIST));
            exchange=RemoteComputationExchange.begin(RemoteExpansionTestRegistries.provider(),plan,run,process,11,
                source,receiver,List.of(),List.of());
        }
        ProductionEffect.CreateDispatch request(UUID run,CreateRouteKey route,List<OrderedIngredient> ingredients) {
            return new ProductionEffect.CreateDispatch(id(11),id(1),11,intent.fence(),id(7),run,route,ingredients);
        }
        ClusterEngineState state(UUID epoch,Map<UUID,ProductionEffect> pending) {
            return new ClusterEngineState(id(1),epoch,11,1,0,0,state.goals(),state.submissionSequences(),state.requestedKinds(),
                state.processes(),state.members(),pending,Set.of(),0,null);
        }
        RemoteRequestKey key() {return new RemoteRequestKey(id(11),id(6),11,11,1);}
        void installContracts(boolean observing,RemoteRequestKey key) {
            input=new AgentInputContract(id(20),1,id(21),run,AgentInputContract.ExecutionShape.STANDARD,
                List.of(new AgentInputContract.StageInput(0,route,manifest)),20,journal.inputContractStore(id(6),RegistryAccess.EMPTY));
            assertTrue(input.markCurrentStageReady(11,manifest,Set.of(route),11));assertEquals(command,input.beginDispatch(id(11),11).orElseThrow());
            reset=new AgentResetService(config(),id(20),id(1),1,key,false,journal.resetStore(RegistryAccess.EMPTY));
            var policy=new ProductionOutputPolicy(run.outputPolicyGeneration(),AgentEndpointState.OutputMode.WAREHOUSE_ALL,
                completion,completion==AgentCompletionMode.SIGNAL?Optional.of(new AgentSignalSelector(
                    new SpaceAddress(Level.OVERWORLD,null,new BlockPos(0,64,0)),net.minecraft.core.Direction.UP,1)):Optional.empty(),
                route,Set.of(route),Map.of(),Set.of(),Optional.empty(),true,false,4,20,3);
            var realStore=journal.outputContractStore(id(6),RegistryAccess.EMPTY);
            output=new ProductionOutputContract(id(20),1,id(21),run,policy,0,new ProductionOutputContract.Store() {
                public void persist(ProductionOutputContract.Snapshot snapshot) {
                    calls.add("output-persist");if(outputFailure!=null)throw outputFailure;realStore.persist(snapshot);
                }
                public void persistLedger(ProductionOutputLedger ledger) {realStore.persistLedger(ledger);}
            });
            if(observing)assertTrue(output.beginAtInputReady(new ProductionOutputContract.OutputProcessCapacity() {
                public Optional<ProductionOutputContract.ProcessLease> tryAcquireObservation(UUID run,UUID goal,CreateRouteKey route) {
                    return Optional.of(new ProductionOutputContract.ProcessLease(id(50),route));
                }
                public Optional<ProductionOutputContract.ProcessLease> tryAcquireOutputDestination(UUID run,UUID goal,CreateRouteKey route) {
                    return Optional.of(new ProductionOutputContract.ProcessLease(id(51),route));
                }
                public boolean isLeaseActive(ProductionOutputContract.ProcessLease lease,ProductionOutputContract.LeasePurpose purpose,UUID run,UUID goal) {return true;}
                public void release(ProductionOutputContract.ProcessLease lease) {calls.add("release-output");}
            }).started());
        }
        void addRecovery(RemoteRequestKey key) {
            var ticket=new ProductionRequestTicket(key.requestId(),key.runId(),key.fence(),id(1),id(20),route,id(5),InputMode.EXACT,1,0,0,11);
            var record=new RemoteAgentRecord(key,id(1),ticket,RemoteAgentPhase.QUEUED,RemoteAgentRecord.RecoveryCondition.NORMAL,
                false,false,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),0);
            var lineage=new RecoveryLedger.LineageId(key.requestId(),key.runId());
            recovery=new RecoveryLedger.State(Map.of(key,record),Map.of(),Map.of(),Map.of(lineage,
                new RecoveryLedger.LineageHighWatermark(lineage,key,id(1),id(20),route,false)));
        }
        ProductionSafeRetryRecovery<CreateDispatchCommand> owner() {return new ProductionSafeRetryRecovery<>(this);}
        public RemoteComputationExchange exchange(UUID run) {calls.add("exchange");return exchange;}
        public FrozenProductionPlan plan(UUID goal) {calls.add("plan");return plan;}
        public Optional<ClusterEngineState> snapshot(UUID cluster) {calls.add("snapshot");return Optional.ofNullable(state);}
        public UUID selectedAgent(SelectedProviderDescriptor provider) {calls.add("agent");return agent;}
        public OptionalLong selectedAgentGeneration(SelectedProviderDescriptor provider) {calls.add("generation");return agentGeneration;}
        public boolean handoffOwnsRun(FrozenProductionPlan plan,UUID run) {calls.add("handoff");return handoff;}
        public RecoveryLedger.State recovery() {calls.add("recovery");return recovery;}
        public Optional<CreateDispatchCommand> proof(ProductionEffect.CreateDispatch intent,
            CreateLogisticsGateway.PersistedCommit persisted,Optional<AgentInputContract.State> input,RecoveryLedger.State recovery) {
            calls.add("proof");assertSame(this.persisted,persisted);assertSame(this.recovery,recovery);
            if(expectedInput!=null)assertSame(expectedInput,input);
            if(proofFailure!=null)throw proofFailure;
            return grant?Optional.of(command):Optional.empty();
        }
        public AgentResetService reset(UUID agent) {calls.add("reset");return reset;}
        public ProductionOutputContract output(UUID run) {calls.add("output");return output;}
        public AgentCoordinator coordinator(UUID agent) {calls.add("coordinator");return null;}
        public boolean rollbackAdmission(RemoteRequestKey key,AgentCoordinator coordinator) {calls.add("rollback");return true;}
        public void removeInput(UUID run,AgentInputContract existing) {calls.add("remove");journal.remove("input_contract/"+run);}
    }
}
