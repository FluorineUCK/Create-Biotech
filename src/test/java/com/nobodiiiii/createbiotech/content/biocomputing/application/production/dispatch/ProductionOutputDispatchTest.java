package com.nobodiiiii.createbiotech.content.biocomputing.application.production.dispatch;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputPolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.effect.ProductionEffect;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.*;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputGatewayTestFixture;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.*;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import net.minecraft.world.item.Items;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;

class ProductionOutputDispatchTest {
    @BeforeAll static void bootstrap() throws Exception {
        Class<?> type=Class.forName("net.neoforged.fml.loading.LoadingModList");
        if(type.getMethod("get").invoke(null)==null)
            type.getMethod("of",List.class,List.class,List.class,List.class,Map.class)
                .invoke(null,List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field=net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true);field.setBoolean(null,true);Items.AIR.getDefaultInstance();
    }
    @Test void unavailableContractsReadOriginalFactsInOrderWithoutPreparingOrMovingAnything() {
        var f=new EmptyFacts();
        assertFalse(f.owner().commit(f.request()));
        assertEquals(List.of("contract","policy","probe","source"),f.calls);
    }
    @Test void exactSealedOutputPersistsPreparationBeforeNativeCommitThenConfirmsAndRecordsRecovery() {
        var f=new Facts();
        assertTrue(f.owner().commit(f.request()));
        assertEquals(List.of("contract","policy","probe","source","plan","has-output","prepare",
            "probe-save","gateway","capture","native","contract-save","request-key","recovery-save"),f.calls);
        assertEquals(1,f.nativeMoves);
        assertEquals(0,f.contract.pendingOutputCount());
        assertTrue(f.contract.snapshot().dispatchIntent().isEmpty());
        assertEquals(f.contract.snapshot(),f.journal.read("test/recovery/"+f.run.runId(),
            ProductionOutputContract.Snapshot.class,RegistryAccess.EMPTY).orElseThrow());
        assertTrue(f.journal.outputCommitStore(RegistryAccess.EMPTY).load(f.instruction.intentId()).orElseThrow()
            .result().orElseThrow().operation().isPresent());
    }
    @Test void allRejectionsKeepPendingOutputAndOnlyOriginalFourRequireReset() {
        var resets=EnumSet.of(OutputLogisticsGateway.Rejection.NATIVE_MOVEMENT_UNCERTAIN,
            OutputLogisticsGateway.Rejection.DURABLE_STORE_UNAVAILABLE,
            OutputLogisticsGateway.Rejection.DURABLE_INTENT_MISMATCH,
            OutputLogisticsGateway.Rejection.INTENT_ID_COLLISION);
        for(var rejection:OutputLogisticsGateway.Rejection.values()) {
            if(rejection==OutputLogisticsGateway.Rejection.NONE)continue;
            var f=new Facts();var before=f.contract.snapshot();
            f.forcedResult=new OutputLogisticsGateway.CommitResult(f.instruction,Optional.empty(),rejection,Optional.empty());
            assertFalse(f.owner().commit(f.request()),rejection.toString());
            assertEquals(before,f.contract.snapshot());assertEquals(0,f.nativeMoves);
            assertEquals(resets.contains(rejection),f.calls.contains("fence:RESET_REQUIRED"));
            assertTrue(f.calls.contains("rejected:"+rejection));
            assertFalse(f.calls.contains("request-key"));assertFalse(f.calls.contains("recovery-save"));
        }
    }
    @Test void coldJournalReplayAfterMovementBeforeConfirmationDoesNotMoveTwice() throws Exception {
        var f=new Facts();var crash=new IllegalStateException("after durable gateway result, before confirmation");
        f.afterCommit=() -> {throw crash;};
        assertSame(crash,assertThrows(IllegalStateException.class,() -> f.owner().commit(f.request())));
        assertEquals(1,f.nativeMoves);assertEquals(1,f.contract.pendingOutputCount());
        assertFalse(f.calls.contains("request-key"));
        var archive=f.journal.save(new net.minecraft.nbt.CompoundTag(),RegistryAccess.EMPTY);
        var load=FactoryOrchestrationSavedData.class.getDeclaredMethod("load",net.minecraft.nbt.CompoundTag.class,
            net.minecraft.core.HolderLookup.Provider.class);load.setAccessible(true);
        f.journal=(FactoryOrchestrationSavedData)load.invoke(null,archive,RegistryAccess.EMPTY);
        assertTrue(f.journal.persistenceValid());
        assertEquals(archive,f.journal.save(new net.minecraft.nbt.CompoundTag(),RegistryAccess.EMPTY));
        var snapshot=f.journal.read("output_contract/"+f.run.runId(),ProductionOutputContract.Snapshot.class,
            RegistryAccess.EMPTY).orElseThrow();
        f.contract=ProductionOutputContract.restore(snapshot,f.policy,Optional.empty(),f.store());
        assertTrue(f.contract.reattachCapacity(f.capacity()));
        assertEquals(f.instruction,f.contract.snapshot().dispatchIntent().orElseThrow());
        f.afterCommit=() -> {};f.calls.clear();
        assertTrue(f.owner().commit(f.request()));
        assertEquals(List.of("contract","policy","probe","source","plan","has-output","prepare",
            "probe-save","gateway","contract-save","request-key","recovery-save"),f.calls);
        assertEquals(1,f.nativeMoves);assertEquals(0,f.contract.pendingOutputCount());
        f.calls.clear();assertFalse(f.owner().commit(f.request()));
        assertFalse(f.calls.contains("prepare"));assertEquals(1,f.nativeMoves);
    }
    @Test void individuallyMissingFactsAndGoalOrPlanCannotPrepare() {
        for(int missing=0;missing<6;missing++) {
            var f=new Facts();
            switch(missing) {
                case 0 -> f.contract=null;
                case 1 -> f.policy=null;
                case 2 -> f.probe=null;
                case 3 -> f.state=null;
                case 4 -> f.state=new ClusterEngineState(id(1),id(2),11,0,0,0,
                    Map.of(),Map.of(),Map.of(),Map.of(),List.of(),Map.of(),Set.of(),0,null);
                case 5 -> f.plan=null;
            }
            assertFalse(f.owner().commit(f.request()));assertFalse(f.calls.contains("prepare"));
            assertEquals(0,f.nativeMoves);assertFalse(f.calls.contains("probe-save"));
            assertEquals(List.of("contract","policy","probe","source"),f.calls.subList(0,4));
        }
    }
    @Test void exactComponentsAndOriginalRequestFencesRejectBeforeNativePreparation() {
        for(int wrong=0;wrong<8;wrong++) {
            var f=new Facts();var q=f.request();var fence=q.fence();
            var changed=new ProductionEffect.OutputPackageRequest(wrong==0?id(90):q.intentId(),
                wrong==1?id(90):q.clusterId(),q.generation(),
                new ProductionEffect.TargetFence(wrong==2?id(90):fence.epochId(),
                    wrong==3?id(90):fence.targetId(),fence.routeGeneration(),
                    wrong==4?90:fence.validationFence()),q.runId(),
                wrong==5?new CreateRouteKey(id(90),"other"):q.destination(),
                wrong==6?new StackKey(Items.GOLD_INGOT.getDefaultInstance()):q.exactOutput());
            if(wrong==7)f.probe=Set.of(new StackKey(Items.GOLD_INGOT.getDefaultInstance()));
            assertFalse(f.owner().commit(changed),"mismatch "+wrong);
            assertFalse(f.calls.contains("prepare"));assertEquals(0,f.nativeMoves);
            assertFalse(f.calls.contains("probe-save"));assertFalse(f.calls.contains("request-key"));
        }
    }
    @Test void absentNativePreparationWaitsForMaterialWithoutProbeWriteOrMovement() {
        var f=new Facts();f.available=false;
        assertFalse(f.owner().commit(f.request()));
        assertTrue(f.calls.contains("wait:MATERIAL"));assertFalse(f.calls.contains("probe-save"));
        assertFalse(f.calls.contains("gateway"));assertFalse(f.calls.contains("recovery-save"));
        assertEquals(1,f.contract.pendingOutputCount());
    }
    @Test void actualGatewayRejectsOffThreadWithoutNativeAttemptOrNewReset() {
        var f=new Facts();f.serverThread=false;
        assertFalse(f.owner().commit(f.request()));
        assertTrue(f.calls.contains("rejected:NOT_SERVER_THREAD"));
        assertFalse(f.calls.contains("capture"));assertFalse(f.calls.contains("native"));
        assertFalse(f.calls.contains("fence:RESET_REQUIRED"));assertFalse(f.calls.contains("request-key"));
        assertTrue(f.journal.outputCommitStore(RegistryAccess.EMPTY).load(f.instruction.intentId()).isEmpty());
    }
    @Test void nativeExceptionBecomesDurableUncertaintyAndIsNotRetried() {
        var f=new Facts();f.moveFailure=new IllegalStateException("unknown native outcome");
        assertFalse(f.owner().commit(f.request()));
        assertTrue(f.calls.contains("native"));assertTrue(f.calls.contains("fence:RESET_REQUIRED"));
        assertEquals(OutputLogisticsGateway.Rejection.NATIVE_MOVEMENT_UNCERTAIN,
            f.journal.outputCommitStore(RegistryAccess.EMPTY).load(f.instruction.intentId()).orElseThrow()
                .result().orElseThrow().rejection());
        f.calls.clear();f.moveFailure=null;
        assertFalse(f.owner().commit(f.request()));
        assertFalse(f.calls.contains("native"));assertFalse(f.calls.contains("capture"));
        assertTrue(f.calls.contains("rejected:NATIVE_MOVEMENT_UNCERTAIN"));
        assertFalse(f.calls.contains("recovery-save"));assertEquals(1,f.contract.pendingOutputCount());
    }
    @Test void durableAttemptingEvidenceCannotMoveAgain() {
        var f=new Facts();
        f.journal.outputCommitStore(RegistryAccess.EMPTY).persist(new OutputLogisticsGateway.PersistedCommit(
            f.instruction,OutputLogisticsGateway.AttemptState.ATTEMPTING,Optional.empty()));
        assertFalse(f.owner().commit(f.request()));
        assertTrue(f.calls.contains("rejected:NATIVE_MOVEMENT_UNCERTAIN"));
        assertTrue(f.calls.contains("fence:RESET_REQUIRED"));assertFalse(f.calls.contains("native"));
        assertFalse(f.calls.contains("capture"));assertFalse(f.calls.contains("recovery-save"));
    }
    @Test void foreignOpaqueReceiptCannotConfirmCurrentOutputOrWriteRecovery() {
        var f=new Facts();var other=new Facts();
        var real=other.commit(other.contract,other.policy,"prepared",other.instruction);
        assertTrue(real.operation().isPresent());assertNotEquals(f.instruction,real.instruction());
        f.forcedResult=real;var before=f.contract.snapshot();
        assertFalse(f.owner().commit(f.request()));
        assertEquals(before,f.contract.snapshot());assertEquals(0,f.nativeMoves);
        assertTrue(f.calls.contains("confirmation-mismatch"));assertTrue(f.calls.contains("fence:RESET_REQUIRED"));
        assertFalse(f.calls.contains("request-key"));assertFalse(f.calls.contains("recovery-save"));
    }
    @Test void successfulOutputWithoutCurrentRequestKeyDoesNotInventRecoveryAuthority() {
        var f=new Facts();f.key=null;
        assertTrue(f.owner().commit(f.request()));assertEquals(1,f.nativeMoves);
        assertEquals(0,f.contract.pendingOutputCount());assertTrue(f.calls.contains("request-key"));
        assertFalse(f.calls.contains("recovery-save"));
    }
    @Test void trustedPreparationPersistenceAndGatewayExceptionsDoNotSkipBoundaries() {
        for(int boundary=0;boundary<3;boundary++) {
            var f=new Facts();var failure=new IllegalStateException("trusted boundary "+boundary);
            if(boundary==0)f.prepareFailure=failure;
            if(boundary==1)f.persistFailure=failure;
            if(boundary==2)f.commitFailure=failure;
            assertSame(failure,assertThrows(IllegalStateException.class,() -> f.owner().commit(f.request())));
            assertEquals(0,f.nativeMoves);assertEquals(1,f.contract.pendingOutputCount());
            assertFalse(f.calls.contains("request-key"));assertFalse(f.calls.contains("recovery-save"));
            if(boundary<2)assertFalse(f.calls.contains("gateway"));
        }
    }
    @Test void actualDurableIntentCollisionFencesWithoutMovingOrConfirming() {
        var f=new Facts();var i=f.instruction;
        var different=new ProductionOutputContract.DispatchInstruction(i.intentId(),i.eventId(),i.agentId(),
            i.agentResetGeneration(),i.runId(),i.goalId(),i.fence(),i.outputObservationEpoch(),
            i.destination(),i.stack(),i.count()+1,i.credited());
        f.journal.outputCommitStore(RegistryAccess.EMPTY).persist(new OutputLogisticsGateway.PersistedCommit(
            different,OutputLogisticsGateway.AttemptState.ATTEMPTING,Optional.empty()));
        assertFalse(f.owner().commit(f.request()));
        assertTrue(f.calls.contains("rejected:INTENT_ID_COLLISION"));assertTrue(f.calls.contains("fence:RESET_REQUIRED"));
        assertFalse(f.calls.contains("native"));assertFalse(f.calls.contains("capture"));
        assertEquals(1,f.contract.pendingOutputCount());assertFalse(f.calls.contains("request-key"));
        assertEquals(different,f.journal.outputCommitStore(RegistryAccess.EMPTY).load(i.intentId()).orElseThrow().instruction());
    }
    @Test void recoveryFailurePropagatesAfterDurableConfirmationWithoutRepeatingMovement() {
        var f=new Facts();var failure=new IllegalStateException("recovery record unavailable");f.recoveryFailure=failure;
        assertSame(failure,assertThrows(IllegalStateException.class,() -> f.owner().commit(f.request())));
        assertEquals(1,f.nativeMoves);assertEquals(0,f.contract.pendingOutputCount());
        assertTrue(f.journal.outputCommitStore(RegistryAccess.EMPTY).load(f.instruction.intentId()).orElseThrow()
            .result().orElseThrow().operation().isPresent());
        assertTrue(f.journal.read("test/recovery/"+f.run.runId(),ProductionOutputContract.Snapshot.class,RegistryAccess.EMPTY).isEmpty());
        f.recoveryFailure=null;f.calls.clear();assertFalse(f.owner().commit(f.request()));
        assertEquals(1,f.nativeMoves);assertFalse(f.calls.contains("prepare"));assertFalse(f.calls.contains("recovery-save"));
    }
    /** Assembly guard only; behavioral tests above exercise contracts and the real durable gateway. */
    @Test void actualHostInstallsOneOwnerForOrdinaryAndObservedOutputWithoutOldFacade() throws Exception {
        var host=java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.java"));
        assertTrue(host.contains("private final ProductionOutputDispatch<OutputProbeState, PreparedOutput> outputDispatch;"));
        assertEquals(1,host.split("this.outputDispatch = new ProductionOutputDispatch",-1).length-1);
        assertEquals(2,host.split("outputDispatch\\.commit\\(",-1).length-1);
        assertFalse(host.contains("commitOutput("));
        assertTrue(host.contains("return prepareOutputMove(probe, instruction);"));
        assertTrue(host.contains("persistOutputProbe(probe.withDispatchPackagers(prepared.packagers()));"));
        assertTrue(host.contains("return probe.stacks().values().contains(Optional.of(exactOutput));"));
        assertTrue(host.contains("OutputLogisticsGateway.create(server::isSameThread,"));
        assertTrue(host.contains("output instruction changed after preflight"));
        assertTrue(host.indexOf("freezeOutputNativeReceipt(exact, policy, prepared);")
            <host.indexOf("LogisticsManager.performPackageRequests(prepared.requests());"));
        assertTrue(host.contains("journal.outputCommitStore(registryAccess)"));
        assertTrue(host.contains("return requestKeysByRun.get(runId);"));
        assertTrue(host.contains("recovery.recordOutput(key, snapshot);"));
    }
    private static UUID id(long n) {return new UUID(0,n);}
    private static class EmptyFacts implements ProductionOutputDispatch.Access<Set<StackKey>,String> {
        final List<String> calls=new ArrayList<>();
        final StackKey output=exactOutput();
        final CreateRouteKey destination=new CreateRouteKey(id(9),"warehouse");
        ProductionEffect.OutputPackageRequest request() {
            return new ProductionEffect.OutputPackageRequest(id(11),id(1),11,
                new ProductionEffect.TargetFence(id(2),id(4),0,11),id(6),destination,output);
        }
        ProductionOutputDispatch<Set<StackKey>,String> owner() {return new ProductionOutputDispatch<>(this);}
        public ProductionOutputContract contract(UUID run) {calls.add("contract");return null;}
        public ProductionOutputPolicy policy(UUID run) {calls.add("policy");return null;}
        public Set<StackKey> probe(UUID run) {calls.add("probe");return null;}
        public Optional<ClusterEngineState> source(UUID run) {calls.add("source");return Optional.empty();}
        public FrozenProductionPlan plan(UUID goal) {throw new AssertionError("no plan authority");}
        public boolean hasOutput(Set<StackKey> probe,StackKey output) {throw new AssertionError("no probe");}
        public Optional<String> prepare(Set<StackKey> probe,ProductionOutputContract.DispatchInstruction instruction) {throw new AssertionError("no native preparation");}
        public void persistPrepared(Set<StackKey> probe,String prepared) {throw new AssertionError("no probe writes");}
        public OutputLogisticsGateway.CommitResult commit(ProductionOutputContract contract,ProductionOutputPolicy policy,
            String prepared,ProductionOutputContract.DispatchInstruction instruction) {throw new AssertionError("no native movement");}
        public boolean waitSource(ClusterEngineState source,GoalRecord goal,WaitReason reason) {throw new AssertionError("no waiting goal");}
        public void rejected(UUID run,OutputLogisticsGateway.Rejection rejection) {throw new AssertionError("no commit");}
        public void confirmationMismatch(UUID run) {throw new AssertionError("no receipt");}
        public void fence(ProductionOutputContract contract,WaitReason reason) {throw new AssertionError("no fence");}
        public RemoteRequestKey requestKey(UUID run) {throw new AssertionError("no recovery authority");}
        public void recordOutput(RemoteRequestKey key,ProductionOutputContract.Snapshot snapshot) {throw new AssertionError("no recovery writes");}
    }
    private static StackKey exactOutput() {
        var stack=Items.GOLD_INGOT.getDefaultInstance();stack.set(DataComponents.MAX_STACK_SIZE,1);
        return new StackKey(stack);
    }
    private static final class Facts extends EmptyFacts {
        FactoryOrchestrationSavedData journal=new FactoryOrchestrationSavedData();
        final CreateRouteKey input=new CreateRouteKey(id(9),"input");
        final CreateRouteKey watched=new CreateRouteKey(id(9),"output");
        final StackKey ingredient=new StackKey(Items.IRON_INGOT.getDefaultInstance());
        final ProductionRun run=new ProductionRun(id(6),id(4),id(5),11,1,InputMode.EXACT,
            List.of(input),destination,0,Map.of(ingredient,2L));
        final GoalRecord goal=new GoalRecord(id(4),id(1),new GoalSubmission(id(4),id(5),1,InputMode.EXACT),
            destination,new PolicySnapshot(0,0,false),GoalState.RUNNABLE,null);
        ProductionOutputPolicy policy=new ProductionOutputPolicy(0,AgentEndpointState.OutputMode.WAREHOUSE_ALL,
            AgentCompletionMode.TARGET,Optional.empty(),destination,Set.of(watched),
            Map.of(output,new ProductionOutputPolicy.RequiredOutput(destination,1,new ProductionOutputPolicy.OutputReceiver.Agent(id(33)))),
            Set.of(),Optional.of(output),false,false,4,20,3);
        ProductionOutputContract contract;
        FrozenProductionPlan plan;
        ClusterEngineState state;
        Set<StackKey> probe=Set.of(output);
        ProductionOutputContract.DispatchInstruction instruction;
        RemoteRequestKey key=new RemoteRequestKey(id(11),id(6),11,11,1);
        int nativeMoves;
        boolean available=true,serverThread=true;
        RuntimeException prepareFailure,persistFailure,moveFailure,commitFailure,recoveryFailure;
        Runnable afterCommit=() -> {};
        OutputLogisticsGateway.CommitResult forcedResult;
        Facts() {
            var profile=ProductionResourceProfile.forProfession(ProductionProfession.NITWIT,1,0).orElseThrow();
            var process=new ProcessRecord(id(7),id(4),id(3),0,profile,ProcessKind.GOAL,ProcessState.RUNNABLE,null,0,Map.of());
            state=new ClusterEngineState(id(1),id(2),11,1,0,0,Map.of(id(4),goal),Map.of(id(4),0L),
                Map.of(id(4),ProcessKind.GOAL),Map.of(id(7),process),
                List.of(new ClusterEngineState.MemberSnapshot(id(3),profile,true,0,0)),Map.of(),Set.of(),0,null);
            var selected=new SelectedProviderDescriptor(new RouteSelectionScope(id(2),id(10),output,0,0,id(12)),
                id(8),id(5),"agent/"+id(20)+"/1/input",RouteCandidate.ProviderKind.AGENT,0,RouteCandidate.Availability.AVAILABLE);
            plan=new FrozenProductionPlan(id(4),id(1),selected,PatternKind.STANDARD,output,1,destination,
                List.of(new FrozenProductionPlan.FrozenStage(0,input,run.manifest(),List.of(new OrderedIngredient(ingredient,2)))),
                new ProductionRun.RunSequence(run,1),new StockObservationScope.NetworkSummary(id(9),output,1),
                0,1,false,false,Optional.empty());
            contract=new ProductionOutputContract(id(20),1,id(21),run,policy,0,store());
            assertTrue(contract.beginAtInputReady(capacity()).started());
            assertEquals(ProductionOutputContract.OutputObservation.ATTRIBUTED,
                contract.observeOutput(new ProductionOutputContract.OutputEvent(id(40),id(21),watched,output,1)));
            assertTrue(contract.markInputEnded(11));
            contract.advanceCompletionClock(OutputGatewayTestFixture.gateway(contract,true,true,true,id(21),true)
                .observeReadiness(contract.readinessRequest()).snapshot().orElseThrow());
            assertEquals(ProductionOutputContract.Milestone.OUTPUT_SEALED,contract.milestone());
            instruction=contract.nextDispatch().instruction().orElseThrow();
            calls.clear();
        }
        @Override ProductionEffect.OutputPackageRequest request() {
            return new ProductionEffect.OutputPackageRequest(instruction.intentId(),id(1),11,
                new ProductionEffect.TargetFence(id(2),id(4),0,11),id(6),destination,output);
        }
        @Override public ProductionOutputContract contract(UUID run) {calls.add("contract");return contract;}
        @Override public ProductionOutputPolicy policy(UUID run) {calls.add("policy");return policy;}
        @Override public Set<StackKey> probe(UUID run) {calls.add("probe");return probe;}
        @Override public Optional<ClusterEngineState> source(UUID run) {calls.add("source");return Optional.ofNullable(state);}
        @Override public FrozenProductionPlan plan(UUID goal) {calls.add("plan");return plan;}
        @Override public boolean hasOutput(Set<StackKey> probe,StackKey exact) {calls.add("has-output");return probe.contains(exact);}
        @Override public Optional<String> prepare(Set<StackKey> probe,ProductionOutputContract.DispatchInstruction instruction) {
            calls.add("prepare");assertEquals(this.instruction,instruction);if(prepareFailure!=null)throw prepareFailure;
            return available?Optional.of("prepared"):Optional.empty();
        }
        @Override public void persistPrepared(Set<StackKey> probe,String prepared) {
            calls.add("probe-save");assertEquals("prepared",prepared);if(persistFailure!=null)throw persistFailure;
            journal.write("test/prepared/"+run.runId(),contract.snapshot(),RegistryAccess.EMPTY);
        }
        @Override public OutputLogisticsGateway.CommitResult commit(ProductionOutputContract contract,ProductionOutputPolicy policy,
            String prepared,ProductionOutputContract.DispatchInstruction instruction) {
            calls.add("gateway");assertSame(this.contract,contract);assertSame(this.policy,policy);
            assertEquals("prepared",prepared);if(commitFailure!=null)throw commitFailure;
            if(forcedResult!=null)return forcedResult;
            var gateway=OutputLogisticsGateway.create(() -> serverThread,new OutputLogisticsGateway.Authority() {
                public ProductionOutputContract.Snapshot captureContract() {calls.add("capture");return contract.snapshot();}
                public OutputLogisticsGateway.ReadinessState captureReadiness(OutputLogisticsGateway.ReadinessRequest request) {throw new AssertionError("commit-only");}
                public OutputLogisticsGateway.DrainState captureDrain(OutputLogisticsGateway.DrainRequest request) {throw new AssertionError("commit-only");}
            },exact -> {
                calls.add("native");assertEquals(instruction,exact);if(moveFailure!=null)throw moveFailure;
                nativeMoves++;return new OutputLogisticsGateway.NativeOperation(id(60));
            },journal.outputCommitStore(RegistryAccess.EMPTY));
            var result=gateway.commit(instruction);afterCommit.run();return result;
        }
        @Override public boolean waitSource(ClusterEngineState source,GoalRecord goal,WaitReason reason) {
            calls.add("wait:"+reason);assertSame(state,source);assertEquals(this.goal,goal);return false;
        }
        @Override public void rejected(UUID run,OutputLogisticsGateway.Rejection rejection) {calls.add("rejected:"+rejection);}
        @Override public void confirmationMismatch(UUID run) {calls.add("confirmation-mismatch");}
        @Override public void fence(ProductionOutputContract contract,WaitReason reason) {calls.add("fence:"+reason);assertSame(this.contract,contract);}
        @Override public RemoteRequestKey requestKey(UUID run) {calls.add("request-key");return key;}
        @Override public void recordOutput(RemoteRequestKey key,ProductionOutputContract.Snapshot snapshot) {
            calls.add("recovery-save");assertEquals(this.key,key);assertEquals(contract.snapshot(),snapshot);
            if(recoveryFailure!=null)throw recoveryFailure;
            journal.write("test/recovery/"+run.runId(),snapshot,RegistryAccess.EMPTY);
        }
        ProductionOutputContract.Store store() {
            var store=journal.outputContractStore(run.runId(),RegistryAccess.EMPTY);
            return new ProductionOutputContract.Store() {
                public void persist(ProductionOutputContract.Snapshot snapshot) {calls.add("contract-save");store.persist(snapshot);}
                public void persistLedger(ProductionOutputLedger ledger) {store.persistLedger(ledger);}
            };
        }
        ProductionOutputContract.OutputProcessCapacity capacity() {
            return new ProductionOutputContract.OutputProcessCapacity() {
                public Optional<ProductionOutputContract.ProcessLease> tryAcquireObservation(UUID run,UUID goal,CreateRouteKey route) {
                    return Optional.of(new ProductionOutputContract.ProcessLease(id(50),route));
                }
                public Optional<ProductionOutputContract.ProcessLease> tryAcquireOutputDestination(UUID run,UUID goal,CreateRouteKey route) {
                    return Optional.of(new ProductionOutputContract.ProcessLease(id(51),route));
                }
                public boolean isLeaseActive(ProductionOutputContract.ProcessLease lease,ProductionOutputContract.LeasePurpose purpose,UUID run,UUID goal) {return true;}
                public void release(ProductionOutputContract.ProcessLease lease) {}
            };
        }
    }
}
