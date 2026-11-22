package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowTaskStartTest {
    static final ModuleDefinition.Key CHILD = new ModuleDefinition.Key("child", 1);
    static final Map<String, DataType> TYPES = Map.of("delay", DataType.Scalar.INTEGER);
    static FlowProgram program() {
        var source = new FlowProgram(TYPES, List.of(
            new FlowProgram.Invoke("launch", CHILD, new FlowCallBindings(Map.of("delay", "delay"), Map.of())),
            new FlowProgram.Sleep("parent", "delay"),
            new FlowProgram.Procedure("child", CHILD, TYPES, Map.of(), Map.of()),
            new FlowProgram.Sleep("child_sleep", "delay"), new FlowProgram.EndProcedure("return"))).withAssembly();
        var encoded = (DataValue.RecordValue) FlowProgramCodec.encode(source);
        var steps = new ArrayList<>(((DataValue.ListValue)encoded.field("steps")).values());
        var launch = new TreeMap<>(((DataValue.RecordValue)steps.getFirst()).fields());
        launch.put("kind", new DataValue.TextValue("task_start")); launch.remove("returns");
        launch.put("handle", new DataValue.TextValue("child_handle")); steps.set(0, new DataValue.RecordValue(launch));
        var fields = new TreeMap<>(encoded.fields()); fields.put("steps", new DataValue.ListValue(steps));
        return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
    }
    @Test void explicitLaunchPublishesChildAndParentHandleTogetherWithoutExecutingChild() {
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(program(), new DataValue.RecordValue(Map.of("delay", new DataValue.IntegerValue(3))), FlowAssemblyTasksTest.LIMITS, true);
        owner.step(FlowAssemblyTasksTest.LIMITS, true); // one metered argument capture
        assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        owner.step(FlowAssemblyTasksTest.LIMITS, true); // one admission, never a synchronous child call
        var saved = owner.flowSnapshot().orElseThrow();
        assertEquals(1, saved.tasks().contexts().size()); assertEquals(0, saved.tasks().contexts().getFirst().work());
        assertInstanceOf(DataValue.RecordValue.class, saved.state().locals().field("child_handle"));
        assertEquals(2, saved.state().work());
        for (int i = 0; i < 4; i++) owner.step(FlowAssemblyTasksTest.LIMITS, true);
        assertEquals(FlowRuntime.Status.SLEEPING, owner.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.SLEEPING, owner.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status());
    }

    static ComputerBlockHost install(FlowProgram program, DataValue.RecordValue input) {
        var owner = new ComputerBlockHost(() -> {}); owner.installFlow(program, input, FlowAssemblyTasksTest.LIMITS, true); return owner;
    }
    static DataValue.RecordValue input(long delay) { return new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(delay))); }
    @Test void restoresBeforeAndAfterEveryLaunchInstructionWithoutDuplicateAdmissionAndSharesOneBudget() {
        var owner = install(program(),input(2));
        for (int i=0;i<2;i++) { owner = FlowAssemblyTasksTest.restored(owner); owner.step(FlowAssemblyTasksTest.LIMITS,true); }
        owner = FlowAssemblyTasksTest.restored(owner); var child = owner.flowSnapshot().orElseThrow().tasks().contexts().getFirst().flow();
        owner.setPaused(false,FlowAssemblyTasksTest.LIMITS,true); long previous=2;
        for (int tick=1;tick<=12;tick++) {
            owner.tick(tick,FlowAssemblyTasksTest.LIMITS,true,Map.of());
            var saved=owner.flowSnapshot().orElseThrow(); long work=saved.state().work()+saved.tasks().contexts().stream().mapToLong(FlowRuntime.Checkpoint::work).sum();
            assertTrue(work-previous>=0 && work-previous<=1); previous=work;
            owner.tick(tick,FlowAssemblyTasksTest.LIMITS,true,Map.of());
            assertEquals(saved,owner.flowSnapshot().orElseThrow()); assertEquals(1,saved.tasks().contexts().size());
        }
        assertEquals(FlowRuntime.Status.COMPLETE,owner.flowTask(child).orElseThrow().status());
        assertEquals(FlowRuntime.Status.COMPLETE,owner.flowSnapshot().orElseThrow().state().status());
    }
    @Test void selfLaunchSwapsArgumentsWithoutOverwritingOrDroppingTheParentsPrivateInputs() {
        var pair=Map.<String,DataType>of("a",DataType.Scalar.INTEGER,"b",DataType.Scalar.INTEGER);
        var p=new FlowProgram(pair,List.of(new FlowProgram.TaskStart("main_start",CHILD,Map.of("a","a","b","b"),"h"),
            new FlowProgram.Sleep("main_sleep","a"),new FlowProgram.Procedure("child",CHILD,pair,Map.of(),Map.of()),
            new FlowProgram.TaskStart("self_start",CHILD,Map.of("a","b","b","a"),"h"),
            new FlowProgram.Sleep("self_sleep","a"),new FlowProgram.EndProcedure("return"))).withAssembly();
        var owner=install(p,new DataValue.RecordValue(Map.of("a",new DataValue.IntegerValue(7),"b",new DataValue.IntegerValue(11))));
        for(int i=0;i<20;i++) owner.step(FlowAssemblyTasksTest.LIMITS,true);
        var saved=owner.flowSnapshot().orElseThrow(); assertEquals(2,saved.tasks().contexts().size());
        var first=saved.tasks().contexts().get(0); var second=saved.tasks().contexts().get(1);
        assertEquals(new DataValue.IntegerValue(7),first.locals().field("a")); assertEquals(new DataValue.IntegerValue(11),first.locals().field("b"));
        assertEquals(new DataValue.IntegerValue(11),second.locals().field("a")); assertEquals(new DataValue.IntegerValue(7),second.locals().field("b"));
        assertEquals(FlowRuntime.Status.FAILED,second.status()); assertEquals(ComputationException.Code.BUSY,second.diagnostic().orElseThrow().code());
        assertTrue(owner.loaded()); FlowAssemblyTasksTest.restored(owner);
    }
    @Test void launchInsideASynchronousProcedureKeepsTheCallerFrameAndReturnsOnlyTheHandle() {
        var outer=new ModuleDefinition.Key("outer",1); var handle=com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle.TYPE;
        var p=new FlowProgram(TYPES,List.of(new FlowProgram.Invoke("call",outer,new FlowCallBindings(Map.of("delay","delay"),Map.of("ticket","h"))),
            new FlowProgram.Procedure("outer",outer,TYPES,Map.of("ticket",handle),Map.of("ticket","h")),
            new FlowProgram.TaskStart("launch",CHILD,Map.of("delay","delay"),"h"),new FlowProgram.EndProcedure("outer_return"),
            new FlowProgram.Procedure("child",CHILD,TYPES,Map.of(),Map.of()),new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
        var owner=install(p,input(2)); boolean frame=false;
        for(int i=0;i<25;i++) {
            var state=owner.flowSnapshot().orElseThrow().state();
            if(state.locals().fields().containsKey("h") && !state.assembly().orElseThrow().calls().orElseThrow().frames().isEmpty()) frame=true;
            owner=FlowAssemblyTasksTest.restored(owner); owner.step(FlowAssemblyTasksTest.LIMITS,true);
        }
        assertTrue(frame); var saved=owner.flowSnapshot().orElseThrow(); assertEquals(FlowRuntime.Status.COMPLETE,saved.state().status());
        assertTrue(handle.accepts(saved.state().locals().field("h"))); assertEquals(1,saved.tasks().contexts().size());
    }
    @Test void fullSlotsFailOnlyLaunchingParentAndNeverReplaceItsHandleOrAllocateAnotherSlot() {
        var h=new com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskHandle(UUID.randomUUID(),0,1,0,UUID.randomUUID()).value();
        var types=new TreeMap<>(TYPES); types.put("child_handle",DataType.typeOf(h));
        var p=new FlowProgram(types,program().steps()).withAssembly(); var values=new TreeMap<>(input(0).fields()); values.put("child_handle",h);
        var owner=install(p,new DataValue.RecordValue(values));
        var a=owner.startFlowTask(CHILD,input(0),FlowAssemblyTasksTest.LIMITS,true); var b=owner.startFlowTask(CHILD,input(0),FlowAssemblyTasksTest.LIMITS,true);
        for(int i=0;i<12;i++) owner.step(FlowAssemblyTasksTest.LIMITS,true);
        var saved=owner.flowSnapshot().orElseThrow(); assertTrue(owner.loaded()); assertEquals(FlowRuntime.Status.FAILED,saved.state().status());
        assertEquals(ComputationException.Code.BUSY,saved.state().diagnostic().orElseThrow().code()); assertEquals(h,saved.state().locals().field("child_handle"));
        assertEquals(1,saved.state().assembly().orElseThrow().pc()); assertEquals(2,saved.tasks().slots().entries().size());
        assertEquals(FlowRuntime.Status.COMPLETE,owner.flowTask(a).orElseThrow().status()); assertEquals(FlowRuntime.Status.COMPLETE,owner.flowTask(b).orElseThrow().status());
        FlowAssemblyTasksTest.restored(owner);
    }
    @Test void targetDeclarationContextBanPropagatesThroughSynchronousCallsButNotAsynchronousBodies() {
        var outer=new ModuleDefinition.Key("outer",1);
        var steps=List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"),new FlowProgram.Invoke("outer_call",outer,new FlowCallBindings(Map.of(),Map.of())),new FlowProgram.EndForever("again"),
            new FlowProgram.Procedure("outer",outer,Map.of(),Map.of(),Map.of()),new FlowProgram.TaskStart("launch",CHILD,Map.of(),"h"),new FlowProgram.EndProcedure("outer_end"),
            new FlowProgram.Procedure("child",CHILD,Map.of(),Map.of(),Map.of(),new FlowBlockRules(Set.of(),Set.of("loop"))),new FlowProgram.EndProcedure("child_end"));
        assertEquals(ComputationException.Code.INVALID_GRAPH,assertThrows(ComputationException.class,()->new FlowProgram(Map.of(),steps).withAssembly().validate(FlowAssemblyTasksTest.LIMITS.hardware())).code());
    }
}
