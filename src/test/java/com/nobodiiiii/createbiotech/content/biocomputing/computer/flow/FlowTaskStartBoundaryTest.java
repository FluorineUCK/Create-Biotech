package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowTaskStartTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class FlowTaskStartBoundaryTest {
    static ComputerBlockHost large() {
        var types=Map.<String,DataType>of("payload",DataType.Scalar.TEXT,"delay",DataType.Scalar.INTEGER);
        var p=new FlowProgram(types,List.of(new FlowProgram.TaskStart("launch",CHILD,Map.of("payload","payload","delay","delay"),"h"),
            new FlowProgram.Sleep("parent","delay"),new FlowProgram.Procedure("child",CHILD,types,Map.of(),Map.of()),
            new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
        var owner=install(p,new DataValue.RecordValue(Map.of("payload",new DataValue.TextValue("x".repeat(4000)),"delay",new DataValue.IntegerValue(0))));
        owner.step(LIMITS,true); owner.step(LIMITS,true); return owner;
    }
    static long units(FlowBlockHost.Saved saved) {
        long total=saved.initialInputs().storageUnits()+saved.control().storageUnits()+saved.tasks().metadataUnits()+saved.memory().storageUnits();
        var states=new ArrayList<>(saved.tasks().contexts()); states.add(saved.state()); long shared=0;
        for(var state:states) { var runtime=FlowRuntime.restore(saved.program(),state,LIMITS.hardware()); shared=runtime.sharedUnits(); total+=runtime.retainedUnits()-shared; }
        return total+shared;
    }
    static long archive(ComputerBlockHost owner) { return ValueNbtCodec.decode(((CompoundTag)owner.write()).getCompound("Data"),LIMITS.archive()).storageUnits(); }
    static ComputerHostLimits limits(long ram,ArchiveLimits archive) { return new ComputerHostLimits(new ResourceLimits(1000,ram,1),archive,LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),3); }
    @Test void ramAdmissionRejectionKeepsParentAtStartWithoutAChildOrSlotAndFitsItsOwnFailure() {
        var owner=large(); var before=owner.flowSnapshot().orElseThrow(); var tight=limits(units(before)+800,LIMITS.archive());
        owner.step(tight,true); var failed=owner.flowSnapshot().orElseThrow();
        assertTrue(owner.loaded()); assertEquals(FlowRuntime.Status.FAILED,failed.state().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT,failed.state().diagnostic().orElseThrow().code());
        assertEquals(before.state().assembly().orElseThrow().pc(),failed.state().assembly().orElseThrow().pc());
        assertEquals(before.state().assembly().orElseThrow().registers(),failed.state().assembly().orElseThrow().registers());
        assertTrue(failed.tasks().slots().entries().isEmpty()); FlowAssemblyTasksTest.restored(owner);
    }
    @Test void archiveAdmissionRejectionPublishesOnlyTheSmallerParentFailure() {
        var owner=large(); long size=archive(owner); var archive=new ArchiveLimits(100000,1000000,size+800);
        owner.step(limits(LIMITS.hardware().memoryUnits(),archive),true); var failed=owner.flowSnapshot().orElseThrow();
        assertTrue(owner.loaded()); assertEquals(FlowRuntime.Status.FAILED,failed.state().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT,failed.state().diagnostic().orElseThrow().code());
        assertTrue(failed.tasks().contexts().isEmpty()); assertTrue(failed.tasks().slots().entries().isEmpty());
        FlowAssemblyTasksTest.restored(owner);
    }
    @Test void ifEvenTheFailureCannotFitThePreviousDurableOwnerIsRetainedAndSuspended() {
        var owner=large(); var before=owner.write(); var tight=limits(LIMITS.hardware().memoryUnits(),new ArchiveLimits(100000,1000000,archive(owner)));
        assertThrows(ComputerBlockHost.HostException.class,()->owner.step(tight,true)); assertFalse(owner.loaded()); assertEquals(before,owner.write());
        assertTrue(owner.flowSnapshot().orElseThrow().tasks().slots().entries().isEmpty());
    }
    @Test void childLaunchInsideAnExplicitTransactionFailsOnlyItsOwnerAndRollsBackThatTransaction() {
        var steps=new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Memory("begin",FlowProgram.MemoryKind.BEGIN,"","")); steps.addAll(program().steps());
        var owner=install(new FlowProgram(TYPES,steps).withAssembly(),input(0)); var sibling=owner.startFlowTask(CHILD,input(0),LIMITS,true);
        for(int i=0;i<10;i++) owner.step(LIMITS,true);
        var saved=owner.flowSnapshot().orElseThrow(); assertEquals(FlowRuntime.Status.FAILED,saved.state().status());
        assertEquals(ComputationException.Code.INVALID_ARGUMENT,saved.state().diagnostic().orElseThrow().code());
        assertFalse(saved.memory().active(saved.state().flow())); assertEquals(1,saved.tasks().contexts().size());
        assertEquals(FlowRuntime.Status.COMPLETE,owner.flowTask(sibling).orElseThrow().status()); assertTrue(owner.loaded()); FlowAssemblyTasksTest.restored(owner);
    }
    @Test void atomicBansDirectAndIndirectLaunchButDoesNotMergeAnAsynchronousTargetsBodyEffects() {
        var steps=new ArrayList<FlowProgram.Step>(program().steps()); steps.add(0,new FlowProgram.Atomic("atomic","result")); steps.add(2,new FlowProgram.EndAtomic("end_atomic"));
        assertThrows(ComputationException.class,()->new FlowProgram(TYPES,steps).withAssembly().validate(LIMITS.hardware()));
        var outer=new ModuleDefinition.Key("outer",1);
        var nested=List.<FlowProgram.Step>of(new FlowProgram.Atomic("atomic","result"),new FlowProgram.Invoke("call",outer,new FlowCallBindings(Map.of(),Map.of())),new FlowProgram.EndAtomic("end_atomic"),
            new FlowProgram.Procedure("outer",outer,Map.of(),Map.of(),Map.of()),new FlowProgram.TaskStart("start",CHILD,Map.of(),"h"),new FlowProgram.EndProcedure("outer_end"),
            new FlowProgram.Procedure("child",CHILD,Map.of(),Map.of(),Map.of()),new FlowProgram.EndProcedure("child_end"));
        assertThrows(ComputationException.class,()->new FlowProgram(Map.of(),nested).withAssembly().validate(LIMITS.hardware()));
        var safe=List.<FlowProgram.Step>of(new FlowProgram.Procedure("outer",outer,TYPES,Map.of(),Map.of(),new FlowBlockRules(Set.of("device_wait"),Set.of())),
            new FlowProgram.TaskStart("start",CHILD,Map.of("delay","delay"),"h"),new FlowProgram.EndProcedure("outer_end"),
            new FlowProgram.Procedure("child",CHILD,TYPES,Map.of(),Map.of()),new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("child_end"));
        assertDoesNotThrow(()->new FlowProgram(Map.of(),safe).withAssembly().validate(LIMITS.hardware()));
    }
    @Test void publicBankTasksUseTheSameOwnerPathAndExchangeOnlyExplicitPublicValues() {
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of("counter",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0))),List.of()),Map.of());
        var steps=new ArrayList<FlowProgram.Step>(program().steps()); steps.add(4,new FlowProgram.Shared("publish","counter",new FlowInput.Literal(new DataValue.IntegerValue(7)),false));
        var owner=install(new FlowProgram(TYPES,steps,archive).withAssembly(),input(1));
        for(int i=0;i<14;i++) owner.step(LIMITS,true);
        var saved=owner.flowSnapshot().orElseThrow(); assertEquals(new DataValue.IntegerValue(7),saved.memory().cells().field("counter"));
        assertEquals(1,saved.tasks().contexts().size()); assertEquals(FlowRuntime.Status.COMPLETE,saved.tasks().contexts().getFirst().status());
        FlowAssemblyTasksTest.restored(owner);
    }
    @Test void retainedHandleIsBoundToExactComputerGenerationSlotIncarnationAndTaskAcrossReuse() {
        var steps=new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Forever("loop")); steps.add(program().steps().getFirst()); steps.add(new FlowProgram.EndForever("again")); steps.addAll(program().steps().subList(2,5));
        var owner=install(new FlowProgram(TYPES,steps).withAssembly(),input(0));
        while(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty()) owner.step(LIMITS,true);
        var saved=owner.flowSnapshot().orElseThrow(); var first=TaskHandle.from(saved.state().locals().field("child_handle"));
        assertEquals(saved.state().computer(),first.computer()); assertEquals(saved.control().generation(),first.generation());
        assertEquals(1,first.slot()); assertEquals(0,first.incarnation()); assertEquals(saved.tasks().contexts().getFirst().flow(),first.flow());
        // Finish the child before the parent reaches another START, then explicitly release the terminal result.
        while(owner.flowTask(first.flow()).orElseThrow().status()!=FlowRuntime.Status.COMPLETE) owner.step(LIMITS,true);
        owner.claimFlowTask(first.flow(),LIMITS,true);
        while(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty()) owner.step(LIMITS,true);
        saved=owner.flowSnapshot().orElseThrow(); var second=TaskHandle.from(saved.state().locals().field("child_handle"));
        assertEquals(first.slot(),second.slot()); assertEquals(first.incarnation()+1,second.incarnation()); assertNotEquals(first.flow(),second.flow());
        assertEquals(1,saved.tasks().slots().entries().size()); assertNotEquals(first,second); FlowAssemblyTasksTest.restored(owner);
    }
    @Test void legacyAndDowngradedOwnerOrSnapshotFormatsNeverExecuteTaskStart() {
        assertThrows(ComputationException.class,()->new FlowProgram(TYPES,program().steps()).validate(LIMITS.hardware()));
        var owner=install(program(),input(0)); owner.step(LIMITS,true); owner.step(LIMITS,true);
        var raw=(CompoundTag)owner.write(); assertEquals(30,raw.getInt(FlowBlockNbtCodec.MARKER));
        raw.putInt(FlowBlockNbtCodec.MARKER,29); assertThrows(ArchiveException.class,()->FlowBlockNbtCodec.decode(raw,LIMITS.archive()));
        var ownerTag=(CompoundTag)owner.write(); var data=(DataValue.RecordValue)ValueNbtCodec.decode(ownerTag.getCompound("Data"),LIMITS.archive()); var snapshot=(DataValue.RecordValue)data.field("flow");
        assertEquals(new DataValue.IntegerValue(31),snapshot.field("version")); var fields=new TreeMap<>(snapshot.fields()); fields.put("version",new DataValue.IntegerValue(30));
        var ownerFields=new TreeMap<>(data.fields()); ownerFields.put("flow",new DataValue.RecordValue(fields)); ownerTag.put("Data",ValueNbtCodec.encode(new DataValue.RecordValue(ownerFields),LIMITS.archive()));
        assertThrows(ArchiveException.class,()->FlowBlockNbtCodec.decode(ownerTag,LIMITS.archive()));
    }

    @Test void firstLaunchInAnUnpausedTickCannotBypassOwnerAdmissionOrMintAChildBudget() {
        var owner=install(program(),input(0)); owner.setPaused(false,LIMITS,true); long previous=0;
        for(int tick=1;tick<=10;tick++) {
            owner.tick(tick,LIMITS,true,Map.of()); var saved=owner.flowSnapshot().orElseThrow();
            long work=saved.state().work()+saved.tasks().contexts().stream().mapToLong(FlowRuntime.Checkpoint::work).sum();
            assertTrue(work-previous>=0 && work-previous<=1); previous=work;
            if(tick==1) assertTrue(saved.tasks().contexts().isEmpty());
            if(tick==2) { assertEquals(1,saved.tasks().contexts().size()); assertEquals(0,saved.tasks().contexts().getFirst().work()); }
            owner.tick(tick,LIMITS,true,Map.of()); assertEquals(saved,owner.flowSnapshot().orElseThrow());
        }
        assertEquals(1,owner.flowSnapshot().orElseThrow().tasks().contexts().size());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"handle_type","parent_pc","task_slot"})
    void tamperedJointAdmissionRetainsRawOwnerWithoutExecuting(String mutation) {
        var owner=install(program(),input(0)); owner.step(LIMITS,true); owner.step(LIMITS,true);
        var tag=(CompoundTag)owner.write(); var data=(DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("Data"),LIMITS.archive());
        var flow=(DataValue.RecordValue)data.field("flow"); var state=(DataValue.RecordValue)flow.field("state");
        var registers=(DataValue.RecordValue)state.field("assembly");
        if(mutation.equals("handle_type")) {
            var values=(DataValue.RecordValue)registers.field("registers");
            var key=values.fields().entrySet().stream().filter(e->TaskHandle.TYPE.accepts(e.getValue())).findFirst().orElseThrow().getKey();
            var handle=(DataValue.RecordValue)values.field(key); handle=patch(handle,"slot",new DataValue.TextValue("not_an_integer"));
            registers=patch(registers,"registers",patch(values,key,handle));
            state=patch(state,"locals",patch((DataValue.RecordValue)state.field("locals"),"child_handle",handle));
        } else if(mutation.equals("parent_pc")) registers=patch(registers,"pc",new DataValue.IntegerValue(0));
        else {
            var tasks=(DataValue.RecordValue)data.field("tasks"); var slots=(DataValue.ListValue)tasks.field("slots");
            var slot=patch((DataValue.RecordValue)slots.values().getFirst(),"flow",new DataValue.TextValue(UUID.randomUUID().toString()));
            data=patch(data,"tasks",patch(tasks,"slots",new DataValue.ListValue(List.of(slot))));
        }
        state=patch(state,"assembly",registers); data=patch(data,"flow",patch(flow,"state",state)); tag.put("Data",ValueNbtCodec.encode(data,LIMITS.archive()));
        var restored=new ComputerBlockHost(()->{}); restored.read(tag); restored.tick(LIMITS,true);
        assertFalse(restored.loaded()); assertTrue(restored.diagnostic().isPresent()); assertEquals(tag,restored.write());
    }
    private static DataValue.RecordValue patch(DataValue.RecordValue value,String key,DataValue replacement) {
        var fields=new TreeMap<>(value.fields()); fields.put(key,replacement); return new DataValue.RecordValue(fields);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void exhaustedLaunchReservesItsFailureCycleWithoutAdmittingAChildOrStoppingItsSibling(boolean xbus) {
        var oldHandle=new TaskHandle(UUID.randomUUID(),0,1,0,UUID.randomUUID()).value();
        var steps=List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"),new FlowProgram.TaskStart("launch",CHILD,Map.of(),"h"),
            new FlowProgram.EndForever("again"),new FlowProgram.Procedure("child",CHILD,Map.of(),Map.of(),Map.of()),
            xbus?new FlowProgram.Read("read","x0","v",new DataType.RecordType(Map.of("v",DataType.Scalar.INTEGER)))
                :new FlowProgram.XBusStatus("status","x0","v"),new FlowProgram.EndProcedure("return"));
        var owner=install(new FlowProgram(Map.of("h",TaskHandle.TYPE),steps).withAssembly(),new DataValue.RecordValue(Map.of("h",oldHandle)));
        owner.step(LIMITS,true); // A real loop-head instruction leaves the parent immediately before TASK.START.
        var sibling=owner.startFlowTask(CHILD,new DataValue.RecordValue(Map.of()),LIMITS,true);
        long lastCycle=xbus?(Long.MAX_VALUE-steps.size()-1)/2:Long.MAX_VALUE;
        var tag=(CompoundTag)owner.write(); var data=(DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("Data"),LIMITS.archive());
        var flow=(DataValue.RecordValue)data.field("flow"); var state=(DataValue.RecordValue)flow.field("state");
        state=patch(patch(state,"work",new DataValue.IntegerValue(lastCycle-1)),"operation",
            new DataValue.IntegerValue(xbus?Math.addExact(Math.multiplyExact(lastCycle-1,2),steps.size()+1):lastCycle-1));
        data=patch(data,"flow",patch(flow,"state",state)); tag.put("Data",ValueNbtCodec.encode(data,LIMITS.archive()));
        var restored=new ComputerBlockHost(()->{}); restored.read(tag); restored.tick(LIMITS,true);
        assertTrue(restored.loaded(),restored.diagnostic().toString()); // The near-limit continuation is valid, not a malformed-save rejection.
        var before=restored.flowSnapshot().orElseThrow(); assertEquals(1,before.state().cursor());
        restored.step(LIMITS,true); var failed=restored.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.FAILED,failed.state().status());
        assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW,failed.state().diagnostic().orElseThrow().code());
        assertEquals(lastCycle,failed.state().work()); assertEquals(before.state().assembly(),failed.state().assembly());
        assertEquals(oldHandle,failed.state().locals().field("h")); assertEquals(before.tasks().slots(),failed.tasks().slots());
        assertEquals(before.tasks().contexts(),failed.tasks().contexts()); assertTrue(restored.loaded());
        restored=FlowAssemblyTasksTest.restored(restored); var failedParent=restored.flowSnapshot().orElseThrow().state();
        restored.step(LIMITS,true); assertEquals(1,restored.flowTask(sibling).orElseThrow().work());
        assertEquals(failedParent,restored.flowSnapshot().orElseThrow().state());
        assertEquals(xbus?FlowRuntime.Status.WAITING_READ:FlowRuntime.Status.READY,restored.flowTask(sibling).orElseThrow().status());
        FlowAssemblyTasksTest.restored(restored);
    }
}
