package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.*;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateDispatchCommand;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionInputDispatchTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null)
            type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
                .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true);field.setBoolean(null,true);Items.AIR.getDefaultInstance();
    }
    @Test void durableTrueAndFalseAreBothFinalBeforeMutableProviderAndOnlyEmptyMayAttemptFresh() {
        var calls=new ArrayList<String>();
        for(boolean committed:List.of(true,false)) {
            calls.clear();
            assertEquals(committed,ProductionInputDispatch.replayBeforeProvider(() -> {
                calls.add("durable");return Optional.of(committed);
            },() -> { throw new AssertionError("durable evidence must not repeat native movement"); }));
            assertEquals(List.of("durable"),calls);
        }
        calls.clear();
        assertTrue(ProductionInputDispatch.replayBeforeProvider(() -> {
            calls.add("durable");return Optional.empty();
        },() -> {calls.add("fresh");return true;}));
        assertEquals(List.of("durable","fresh"),calls);
    }

    @Test void actualPlanAndTypedManifestUseLivePreflightAndFiniteBareOperationWithoutEditingSource() {
        var f=new Facts();var state=f.state;var plan=f.plan;
        assertTrue(new ProductionInputDispatch(f).commit(f.intent));
        assertEquals(List.of("snapshot","plan","frequency","inputAttempt","inputContract","bareAttempt",
            "provider","resolve","claim","bare"),f.calls);
        assertSame(f.intent,f.seenIntent);assertEquals(f.manifest,f.seenManifest);
        assertSame(state,f.state);assertEquals(plan,f.plan);
        assertEquals("exact-component",f.seenManifest.keySet().iterator().next().stack().getHoverName().getString());
        assertThrows(UnsupportedOperationException.class,() -> f.seenManifest.clear());
    }

    @Test void actualSafeFalseJournalMustBeAuthorizedAndPersistedBeforeCompensationThenFresh() {
        var f=new Facts();f.rejectAtRealGateway();f.allowRetry=true;
        assertTrue(new ProductionInputDispatch(f).commit(f.intent));
        assertEquals(List.of("snapshot","plan","frequency","inputAttempt","inputContract",
            "authorize","persist","normalize","provider","resolve","claim","bare"),f.calls);
        assertEquals(f.journal.inputCommitStore(RegistryAccess.EMPTY).load(f.intent.intentId()).orElseThrow(),
            f.journal.read("test-safe-proof/"+f.intent.intentId(),CreateLogisticsGateway.PersistedCommit.class,
                RegistryAccess.EMPTY).orElseThrow());
    }

    @Test void selectedAgentNeverUsesBareAndUniqueRegistrationSelectsLocalOrRemoteWithoutMergingState() {
        for(boolean local:List.of(true,false)) {
            var f=new Facts();f.select(RouteCandidate.ProviderKind.AGENT);
            var registration=new AgentAddressRegistration(f.route,id(20),local?id(1):id(99),1,
                CommunicationFrequency.of("17"),AgentAddressRegistration.LoadState.LOADED,AgentAddressRegistration.Reachability.REACHABLE);
            f.resolution=new AgentResolution.Unique(f.route,registration);
            var before=f.state;
            assertTrue(new ProductionInputDispatch(f).commit(f.intent));
            assertEquals(List.of("snapshot","plan","frequency","inputAttempt","inputContract","bareAttempt",
                "provider","resolve",local?"agent":"remote"),f.calls);
            assertSame(before,f.state);assertFalse(f.calls.contains("bare"));assertFalse(f.calls.contains("claim"));
        }
    }

    @Test void missingAndMismatchedPreflightRejectBeforeDurableOrWorldPorts() {
        var missing=new Facts();missing.state=null;
        assertFalse(new ProductionInputDispatch(missing).commit(missing.intent));assertEquals(List.of("snapshot"),missing.calls);
        for(int variant=0;variant<5;variant++) {
            var f=new Facts();var original=f.intent;
            var epoch=variant==0?id(99):id(2);var process=variant==1?id(99):id(7);
            var run=variant==2?id(99):id(6);var route=variant==3?new CreateRouteKey(id(99),"other"):f.route;
            var ingredients=variant==4?List.of(new OrderedIngredient(new StackKey(new ItemStack(Items.IRON_INGOT)),2)):original.ingredients();
            var request=new ProductionEffect.CreateDispatch(original.intentId(),original.clusterId(),11,
                new ProductionEffect.TargetFence(epoch,id(3),0,11),process,run,route,ingredients);
            assertFalse(new ProductionInputDispatch(f).commit(request));
            assertEquals(variant<2?List.of("snapshot"):List.of("snapshot","plan"),f.calls);
            assertNull(f.seenIntent);assertEquals(original,f.intent);
        }
        var noPlan=new Facts();noPlan.plan=null;
        assertFalse(new ProductionInputDispatch(noPlan).commit(noPlan.intent));assertEquals(List.of("snapshot","plan"),noPlan.calls);
        var complete=new Facts();complete.runs(ProductionRun.RunSequence.completed());
        assertFalse(new ProductionInputDispatch(complete).commit(complete.intent));assertEquals(List.of("snapshot","plan"),complete.calls);
        var noProcess=new Facts();noProcess.state=ClusterEngineState.empty(id(1),id(2));
        assertFalse(new ProductionInputDispatch(noProcess).commit(noProcess.intent));assertEquals(List.of("snapshot"),noProcess.calls);
        var noGoal=new Facts();
        var alchemist=ProductionResourceProfile.forProfession(ProductionProfession.CLERIC,5,0).orElseThrow();
        var orphan=new ProcessRecord(id(7),id(4),id(3),0,alchemist,ProcessKind.AGENT,
            ProcessState.RUNNABLE,null,0,Map.of());
        noGoal.state=new ClusterEngineState(id(1),id(2),11,0,0,0,Map.of(),Map.of(),Map.of(),Map.of(id(7),orphan),
            List.of(new ClusterEngineState.MemberSnapshot(id(3),alchemist,true,0,0)),Map.of(),Set.of(),0,null);
        assertFalse(new ProductionInputDispatch(noGoal).commit(noGoal.intent));assertEquals(List.of("snapshot"),noGoal.calls);
    }

    @Test void emptyAirAndAggregationOverflowAreRejectedBeforeStateLookup() {
        var f=new Facts();
        assertThrows(IllegalArgumentException.class,() -> new StackKey(Items.AIR.getDefaultInstance()),
            "AIR is rejected by the public typed value boundary before a dispatch can be constructed");
        assertTrue(f.calls.isEmpty());
        assertThrows(IllegalArgumentException.class,() -> new ProductionEffect.CreateDispatch(f.intent.intentId(),id(1),11,
            f.intent.fence(),id(7),id(6),f.route,List.of()),"the finite effect boundary already rejects an empty manifest");
        var request=new ProductionEffect.CreateDispatch(f.intent.intentId(),id(1),11,f.intent.fence(),id(7),id(6),f.route,
            List.of(new OrderedIngredient(f.ingredient,Long.MAX_VALUE),new OrderedIngredient(f.ingredient,1)));
        assertFalse(new ProductionInputDispatch(f).commit(request));assertTrue(f.calls.isEmpty());
    }

    @Test void unavailableProviderUsesOriginalDistinctWaitReasonsAndNeverFreshMovement() {
        for(var kind:RouteCandidate.ProviderKind.values()) {
            var f=new Facts();f.select(kind);f.current=false;
            assertFalse(new ProductionInputDispatch(f).commit(f.intent));
            assertEquals(kind==RouteCandidate.ProviderKind.PATTERN?"wait:ROUTE":"wait:PROCESS",f.calls.getLast());
            assertFalse(f.calls.contains("bare"));assertFalse(f.calls.contains("agent"));assertFalse(f.calls.contains("remote"));
            assertEquals(kind==RouteCandidate.ProviderKind.AGENT, f.calls.contains("resolve"));
        }
    }

    @Test void registeredOrLoadedAgentRouteNeverFallsBackToBareAndAgentFailuresStayDistinct() {
        for(var kind:RouteCandidate.ProviderKind.values()) {
            var f=new Facts();f.select(kind);f.claimed=true;
            assertFalse(new ProductionInputDispatch(f).commit(f.intent));
            assertFalse(f.calls.contains("bare"));
            assertEquals(kind!=RouteCandidate.ProviderKind.AGENT,f.calls.contains("claim"));
        }
        for(boolean conflict:List.of(true,false)) {
            var f=new Facts();var first=f.registration(id(20),id(1));var second=f.registration(id(21),id(1));
            f.resolution=conflict?new AgentResolution.Conflict(f.route,List.of(first,second)):
                new AgentResolution.KnownUnavailable(f.route,List.of(first),1);
            assertFalse(new ProductionInputDispatch(f).commit(f.intent));
            assertEquals("wait:PROCESS",f.calls.getLast());assertFalse(f.calls.contains("claim"));
            f.calls.clear();f.select(RouteCandidate.ProviderKind.AGENT);
            assertFalse(new ProductionInputDispatch(f).commit(f.intent));
            String reason=conflict?"wait:AGENT_ADDRESS_CONFLICT":"wait:AGENT_UNLOADED";
            assertEquals(reason,f.calls.getLast());assertFalse(f.calls.contains("bare"));
            f.calls.clear();f.current=false;
            assertFalse(new ProductionInputDispatch(f).commit(f.intent));assertEquals(reason,f.calls.getLast());
        }
    }

    @Test void safeRetryMissingProofWaitOrPersistFailureCannotFallThroughToProvider() {
        var denied=new Facts();denied.rejectAtRealGateway();
        assertFalse(new ProductionInputDispatch(denied).commit(denied.intent));
        assertEquals(List.of("snapshot","plan","frequency","inputAttempt","inputContract","authorize","wait:RESET_REQUIRED"),denied.calls);
        var waiting=new Facts();waiting.rejectAtRealGateway();waiting.allowRetry=true;waiting.retryWait=Optional.of(WaitReason.MATERIAL);
        assertFalse(new ProductionInputDispatch(waiting).commit(waiting.intent));
        assertEquals(List.of("snapshot","plan","frequency","inputAttempt","inputContract","authorize","persist","normalize","wait:MATERIAL"),waiting.calls);
        var failed=new Facts();failed.rejectAtRealGateway();failed.allowRetry=true;
        var error=new IllegalStateException("exact proof persistence unavailable");failed.persistFailure=error;
        assertSame(error,assertThrows(IllegalStateException.class,() -> new ProductionInputDispatch(failed).commit(failed.intent)));
        assertEquals("persist",failed.calls.getLast());assertFalse(failed.calls.contains("normalize"));assertFalse(failed.calls.contains("provider"));
    }

    @Test void malformedActualInputAndBareJournalEntriesFenceInsteadOfRepeatingMovement() throws Exception {
        for(boolean input:List.of(true,false)) {
            var f=new Facts();if(input)f.rejectAtRealGateway();else f.storeBareAttempt();
            var archive=f.journal.save(new CompoundTag(),RegistryAccess.EMPTY);
            var entries=archive.getList("Entries",Tag.TAG_COMPOUND);
            for(Tag raw:entries) {
                var entry=(CompoundTag)raw;
                if(entry.getString("Key").equals((input?"input_commit/":"bare_commit/")+f.intent.intentId()))
                    entry.put("Value",new CompoundTag());
            }
            f.journal=reload(archive);
            assertFalse(new ProductionInputDispatch(f).commit(f.intent));
            assertFalse(f.journal.persistenceValid());assertEquals("wait:RESET_REQUIRED",f.calls.getLast());
            assertFalse(f.calls.contains("provider"));assertFalse(f.calls.contains("bare"));assertFalse(f.calls.contains("authorize"));
            assertEquals(archive,f.journal.save(new CompoundTag(),RegistryAccess.EMPTY));
        }
    }

    @Test void existingRealInputContractReplaysOrDurablyStopsBeforeResetAndNeverFresh() {
        for(boolean replay:List.of(true,false)) {
            var f=new Facts();f.installInputContract();f.replayResult=replay;
            var before=f.existing.snapshot();
            assertEquals(replay,new ProductionInputDispatch(f).commit(f.intent));
            assertFalse(f.calls.contains("provider"));assertFalse(f.calls.contains("bareAttempt"));
            if(replay)assertEquals(before,f.existing.snapshot());
            else {
                assertEquals(AgentInputPhase.RESET_REQUIRED,f.existing.phase());
                assertEquals(f.existing.snapshot(),f.journal.read("input_contract/"+f.run.runId(),AgentInputContract.State.class,
                    RegistryAccess.EMPTY).orElseThrow());
                assertTrue(f.calls.indexOf("reset")<f.calls.indexOf("wait:RESET_REQUIRED"));
            }
        }
    }

    @Test void coldBareAttemptIsTerminalEvenWhenFalseAndDoesNotInspectMutableProviderOrClaim() throws Exception {
        for(boolean result:List.of(true,false)) {
            var f=new Facts();f.storeBareAttempt();var archive=f.journal.save(new CompoundTag(),RegistryAccess.EMPTY);
            f.journal=reload(archive);f.result=result;f.current=false;f.claimed=true;
            assertEquals(result,new ProductionInputDispatch(f).commit(f.intent));
            assertEquals(List.of("snapshot","plan","frequency","inputAttempt","inputContract","bareAttempt","resolve","bare"),f.calls);
            assertEquals(archive,f.journal.save(new CompoundTag(),RegistryAccess.EMPTY));
        }
    }

    @Test void checkedOperationResultsExceptionsAndNonNullReplayContractRemainVisible() {
        for(var kind:List.of(RouteCandidate.ProviderKind.ORDINARY_PROVIDER,RouteCandidate.ProviderKind.AGENT)) {
            for(boolean local:List.of(true,false)) {
                var f=new Facts();f.select(kind);
                if(kind==RouteCandidate.ProviderKind.AGENT)f.resolution=new AgentResolution.Unique(f.route,f.registration(id(20),local?id(1):id(99)));
                f.result=false;assertFalse(new ProductionInputDispatch(f).commit(f.intent));
                var error=new IllegalStateException("checked native boundary unavailable");f.operationFailure=error;
                assertSame(error,assertThrows(IllegalStateException.class,() -> new ProductionInputDispatch(f).commit(f.intent)));
            }
        }
        assertThrows(NullPointerException.class,() -> new ProductionInputDispatch(null));
        assertThrows(NullPointerException.class,() -> ProductionInputDispatch.replayBeforeProvider(null,() -> true));
        assertThrows(NullPointerException.class,() -> ProductionInputDispatch.replayBeforeProvider(Optional::empty,null));
        assertThrows(NullPointerException.class,() -> ProductionInputDispatch.replayBeforeProvider(() -> null,() -> {throw new AssertionError();}));
    }

    private static UUID id(long n) {return new UUID(0,n);}
    private static final class Facts implements ProductionInputDispatch.Access {
        final List<String> calls=new ArrayList<>();
        FactoryOrchestrationSavedData journal=new FactoryOrchestrationSavedData();
        final CreateRouteKey route=new CreateRouteKey(id(9),"input");
        final StackKey ingredient;
        final Map<StackKey,Long> manifest;
        final ProductionRun run;
        final ProcessRecord process;
        final GoalRecord goal;
        final ProductionEffect.CreateDispatch intent;
        ClusterEngineState state;
        FrozenProductionPlan plan;
        AgentResolution resolution=new AgentResolution.NeverRegistered(route);
        boolean current=true,claimed=false,result=true,allowRetry=false;
        ProductionEffect.CreateDispatch seenIntent;
        Map<StackKey,Long> seenManifest;
        Optional<WaitReason> retryWait=Optional.empty();
        RuntimeException persistFailure,operationFailure;
        AgentInputContract existing;
        boolean replayResult;
        Facts() {
            ItemStack stack=new ItemStack(Items.IRON_INGOT);stack.set(DataComponents.CUSTOM_NAME,Component.literal("exact-component"));
            ingredient=new StackKey(stack);manifest=Map.of(ingredient,2L);
            var output=new StackKey(new ItemStack(Items.GOLD_INGOT));
            run=new ProductionRun(id(6),id(4),id(5),11,1,InputMode.EXACT,List.of(route),route,0,manifest);
            var profile=ProductionResourceProfile.forProfession(ProductionProfession.NITWIT,1,0).orElseThrow();
            process=new ProcessRecord(id(7),id(4),id(3),0,profile,ProcessKind.GOAL,ProcessState.RUNNABLE,null,0,Map.of());
            goal=new GoalRecord(id(4),id(1),new GoalSubmission(id(4),id(5),1,InputMode.EXACT),route,
                new PolicySnapshot(0,0,false),GoalState.RUNNABLE,null);
            state=new ClusterEngineState(id(1),id(2),11,1,0,0,Map.of(id(4),goal),Map.of(id(4),0L),
                Map.of(id(4),ProcessKind.GOAL),Map.of(id(7),process),
                List.of(new ClusterEngineState.MemberSnapshot(id(3),profile,true,0,0)),Map.of(),Set.of(),0,null);
            var selected=new SelectedProviderDescriptor(new RouteSelectionScope(id(2),id(10),output,0,0,id(12)),
                id(8),id(5),"ordinary",RouteCandidate.ProviderKind.ORDINARY_PROVIDER,0,RouteCandidate.Availability.AVAILABLE);
            plan=new FrozenProductionPlan(id(4),id(1),selected,PatternKind.STANDARD,output,1,route,
                List.of(new FrozenProductionPlan.FrozenStage(0,route,manifest,List.of(new OrderedIngredient(ingredient,2)))),
                new ProductionRun.RunSequence(run,1),new StockObservationScope.NetworkSummary(route.logisticsNetworkId(),output,1),
                0,1,false,false,Optional.empty());
            intent=new ProductionEffect.CreateDispatch(id(11),id(1),11,new ProductionEffect.TargetFence(id(2),id(3),0,11),
                id(7),id(6),route,List.of(new OrderedIngredient(ingredient,2)));
        }
        public Optional<ClusterEngineState> snapshot(UUID cluster) {calls.add("snapshot");return Optional.ofNullable(state);}
        public FrozenProductionPlan plan(UUID goal) {calls.add("plan");return plan;}
        public CommunicationFrequency frequency(UUID cluster,UUID member) {calls.add("frequency");return CommunicationFrequency.of("17");}
        public Optional<CreateLogisticsGateway.PersistedCommit> inputAttempt(UUID intent) {calls.add("inputAttempt");return journal.inputCommitStore(RegistryAccess.EMPTY).load(intent);}
        public AgentInputContract inputContract(UUID run) {calls.add("inputContract");return existing;}
        public Optional<ProductionInputDispatch.SafeRetry> authorizeRetry(ProductionEffect.CreateDispatch request,
            CreateLogisticsGateway.PersistedCommit attempt,AgentInputContract existing) {
            calls.add("authorize");assertSame(intent,request);
            assertEquals(journal.inputCommitStore(RegistryAccess.EMPTY).load(intent.intentId()).orElseThrow(),attempt);
            if(!allowRetry)return Optional.empty();
            return Optional.of(new ProductionInputDispatch.SafeRetry() {
                public void persist() {calls.add("persist");if(persistFailure!=null)throw persistFailure;journal.write("test-safe-proof/"+request.intentId(),attempt,RegistryAccess.EMPTY);}
                public Optional<WaitReason> normalize() {
                    calls.add("normalize");assertEquals(attempt,journal.read("test-safe-proof/"+request.intentId(),
                        CreateLogisticsGateway.PersistedCommit.class,RegistryAccess.EMPTY).orElseThrow());
                    return retryWait;
                }
            });
        }
        public boolean replayInput(AgentInputContract existing,ProductionEffect.CreateDispatch request) {calls.add("replayInput");return replayResult;}
        public void requireReset(UUID runId,UUID agent) {calls.add("reset");assertEquals(AgentInputPhase.RESET_REQUIRED,existing.phase());assertEquals(run.runId(),runId);assertEquals(existing.snapshot().agentId(),agent);}
        public boolean hasBareAttempt(UUID intent) {calls.add("bareAttempt");return journal.bareCommitStore(RegistryAccess.EMPTY).load(intent).isPresent();}
        public boolean providerCurrent(ProductionEffect.CreateDispatch request,SelectedProviderDescriptor selected) {calls.add("provider");return current;}
        public AgentResolution resolve(CreateRouteKey route,UUID cluster,CommunicationFrequency frequency) {calls.add("resolve");return resolution;}
        public boolean loadedInputClaims(CreateRouteKey route) {calls.add("claim");return claimed;}
        public boolean bare(ProductionEffect.CreateDispatch request,Map<StackKey,Long> decoded,AgentResolution decision,
            ClusterEngineState state,GoalRecord goal,FrozenProductionPlan plan) {calls.add("bare");return operation(request,decoded);}
        public boolean agent(ProductionEffect.CreateDispatch request,Map<StackKey,Long> decoded,ClusterEngineState state,
            GoalRecord goal,AgentAddressRegistration registration,FrozenProductionPlan plan) {calls.add("agent");return operation(request,decoded);}
        public boolean remote(ProductionEffect.CreateDispatch request,ClusterEngineState state,GoalRecord goal,
            ProcessRecord process,AgentAddressRegistration registration,CommunicationFrequency frequency) {calls.add("remote");assertSame(this.process,process);return operation(request,manifest);}
        public boolean waitSource(ClusterEngineState state,GoalRecord goal,WaitReason reason) {calls.add("wait:"+reason);return false;}
        void rejectAtRealGateway() {
            var command=new CreateDispatchCommand(intent.intentId(),id(20),1,11,run.runId(),id(21),0,route,11,manifest);
            var gateway=new CreateLogisticsGateway(ignored -> new CreateLogisticsGateway.CommitContext(id(20),1,11,route,
                false,11,AgentInputPhase.DISPATCH_INTENT,command),ignored -> {
                    throw new AssertionError("unloaded gateway must never dispatch real items");
                },() -> true,journal.inputCommitStore(RegistryAccess.EMPTY));
            assertEquals(CreateLogisticsGateway.Rejection.PACKAGER_UNLOADED,gateway.commit(command).rejection());
            assertTrue(journal.inputCommitStore(RegistryAccess.EMPTY).load(intent.intentId()).orElseThrow().allowsRetryWithoutMaterial());
        }
        void select(RouteCandidate.ProviderKind kind) {
            var selected=plan.selectedProvider();
            plan=new FrozenProductionPlan(plan.goalId(),plan.clusterId(),new SelectedProviderDescriptor(selected.selectionScope(),
                selected.libraryId(),selected.patternId(),selected.providerKey(),kind,selected.effectivePriority(),selected.availability()),
                plan.patternKind(),plan.output(),plan.outputPerOperation(),plan.deliveryRoute(),plan.stages(),plan.runs(),
                plan.observationScope(),plan.baselineCount(),plan.requestedOutputCount(),plan.dispatchQueued(),plan.cancelAfterDrain(),plan.remoteExpansion());
        }
        boolean operation(ProductionEffect.CreateDispatch request,Map<StackKey,Long> decoded) {
            seenIntent=request;seenManifest=decoded;if(operationFailure!=null)throw operationFailure;return result;
        }
        AgentAddressRegistration registration(UUID agent,UUID cluster) {
            return new AgentAddressRegistration(route,agent,cluster,1,CommunicationFrequency.of("17"),
                AgentAddressRegistration.LoadState.LOADED,AgentAddressRegistration.Reachability.REACHABLE);
        }
        void runs(ProductionRun.RunSequence sequence) {
            plan=new FrozenProductionPlan(plan.goalId(),plan.clusterId(),plan.selectedProvider(),plan.patternKind(),plan.output(),
                plan.outputPerOperation(),plan.deliveryRoute(),plan.stages(),sequence,plan.observationScope(),plan.baselineCount(),
                plan.requestedOutputCount(),plan.dispatchQueued(),plan.cancelAfterDrain(),plan.remoteExpansion());
        }
        void storeBareAttempt() {
            journal.bareCommitStore(RegistryAccess.EMPTY).persist(CreateLogisticsGateway.PersistedBareCommit.attempting(
                new CreateLogisticsGateway.BareDispatchRequest(intent.intentId(),run.runId(),route,11,0,PatternKind.STANDARD,true,manifest)));
        }
        void installInputContract() {
            existing=new AgentInputContract(id(20),1,id(21),run,AgentInputContract.ExecutionShape.STANDARD,
                List.of(new AgentInputContract.StageInput(0,route,manifest)),20,journal.inputContractStore(run.runId(),RegistryAccess.EMPTY));
            assertTrue(existing.markCurrentStageReady(11,manifest,Set.of(route),11));
            assertTrue(existing.beginDispatch(intent.intentId(),11).isPresent());
        }
    }
    private static FactoryOrchestrationSavedData reload(CompoundTag archive) throws Exception {
        var method=FactoryOrchestrationSavedData.class.getDeclaredMethod("load",CompoundTag.class,HolderLookup.Provider.class);
        method.setAccessible(true);return (FactoryOrchestrationSavedData)method.invoke(null,archive,RegistryAccess.EMPTY);
    }
}
