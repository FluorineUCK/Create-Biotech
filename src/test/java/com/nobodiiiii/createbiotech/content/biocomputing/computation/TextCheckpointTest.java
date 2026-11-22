package com.nobodiiiii.createbiotech.content.biocomputing.computation;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.TextProgramFixtures.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import org.junit.jupiter.api.Test;
class TextCheckpointTest {
    @Test void firstReplacementEmissionWithCopiedCharacterCannotInventExtraBufferUnits(){
        var args=Map.<String,DataValue>of("text",text("a"),"needle",text("a"),"replacement",text("xy"));
        var task=TextTask.start(TextOperation.REPLACE,args,"x",n->{});task.advance(n->{});task.advance(n->{});task.advance(n->{});
        var checkpoint=task.checkpoint();assertEquals(text("EMIT"),checkpoint.field("phase"));assertEquals(integer(1),checkpoint.field("copy"));
        assertEquals(text("x"),checkpoint.field("buffer"));
        var legal=TextTask.restore(TextOperation.REPLACE,args,checkpoint,"x");
        for(int i=0;!legal.complete();i++){assertTrue(i<20);legal.advance(n->{});}
        assertEquals(text("xy"),legal.result());
        var fields=new LinkedHashMap<>(checkpoint.fields());fields.put("buffer",text("junkx"));
        invalidTask(TextOperation.REPLACE,args,fields);
    }
    @Test void emptyNeedleSearchCannotMoveItsFirstMatchAwayFromZero(){
        var args=Map.<String,DataValue>of("text",text("abc"),"needle",text(""));
        var task=TextTask.start(TextOperation.INDEX_OF,args,"x",n->{});
        var legal=TextTask.restore(TextOperation.INDEX_OF,args,task.checkpoint(),"x");legal.advance(n->{});
        assertEquals(integer(0),legal.result());
        var fields=new LinkedHashMap<>(task.checkpoint().fields());
        fields.put("candidate",integer(1));fields.put("offset",integer(1));fields.put("index",integer(1));
        invalidTask(TextOperation.INDEX_OF,args,fields);
    }
    @Test void completedEmptyNeedleSearchCannotClaimANonzeroMatchIndex(){
        var args=Map.<String,DataValue>of("text",text("abc"),"needle",text(""));
        var task=TextTask.start(TextOperation.INDEX_OF,args,"x",n->{});task.advance(n->{});
        assertTrue(task.complete());
        assertEquals(integer(0),TextTask.restore(TextOperation.INDEX_OF,args,task.checkpoint(),"x").result());
        var fields=new LinkedHashMap<>(task.checkpoint().fields());
        fields.put("candidate",integer(1));fields.put("offset",integer(1));fields.put("index",integer(1));
        invalidTask(TextOperation.INDEX_OF,args,fields);
    }
    @Test void firstReplacementEmissionCannotInventAnOutputPrefix(){
        var args=Map.<String,DataValue>of("text",text("a"),"needle",text("a"),"replacement",text("x"));
        var task=TextTask.start(TextOperation.REPLACE,args,"x",n->{});task.advance(n->{});task.advance(n->{});
        var initial=task.checkpoint();assertEquals(text("EMIT"),initial.field("phase"));assertEquals(integer(0),initial.field("copy"));
        assertEquals(text(""),initial.field("buffer"));
        var legal=TextTask.restore(TextOperation.REPLACE,args,initial,"x");legal.advance(n->{});
        assertEquals(integer(1),legal.checkpoint().field("copy"));assertEquals(text("x"),legal.checkpoint().field("buffer"));
        legal=TextTask.restore(TextOperation.REPLACE,args,legal.checkpoint(),"x");
        for(int i=0;!legal.complete();i++){assertTrue(i<20);legal.advance(n->{});}
        assertEquals(text("x"),legal.result());
        var fields=new LinkedHashMap<>(initial.fields());fields.put("buffer",text("junk"));
        invalidTask(TextOperation.REPLACE,args,fields);
    }
    @Test void exactRuntimeCapacitySucceedsAndOneUnitLessPreservesRamAndOutbox(){
        var archive=budgetedArchive();var baseline=new ComputerRuntime(UUID.randomUUID(),archive,ROOMY);
        baseline.begin(new DataValue.RecordValue(Map.of()));finish(baseline);
        assertEquals(ComputationMachine.Status.SUCCEEDED,baseline.report().status());
        long capacity=baseline.report().peakMemoryUnits()+overhead(baseline.snapshot());
        var exact=new ComputerRuntime(UUID.randomUUID(),archive,new ResourceLimits(10000,capacity,1));
        exact.begin(new DataValue.RecordValue(Map.of()));finish(exact);
        assertEquals(ComputationMachine.Status.SUCCEEDED,exact.report().status());
        assertEquals(text("😀".repeat(16)+"中".repeat(8)),exact.memory().field("value"));
        assertTrue(exact.outbound().isPresent());
        var shortByOne=new ComputerRuntime(UUID.randomUUID(),archive,new ResourceLimits(10000,capacity-1,1));
        shortByOne.begin(new DataValue.RecordValue(Map.of()));finish(shortByOne);assertMemoryFailure(shortByOne);
    }
    @Test void supplementaryCodePointIsChargedBeforeTextFrameAppendAtCapacityBoundary(){
        var runtime=new ComputerRuntime(UUID.randomUUID(),budgetedArchive(),ROOMY);
        runtime.begin(new DataValue.RecordValue(Map.of()));
        for(int i=0;runtime.snapshot().machine().frames().stream().noneMatch(MachineCheckpoint.TextState.class::isInstance);i++){assertTrue(i<30);runtime.step();}
        var before=runtime.snapshot();long capacity=before.machine().arenaUnits()+2+overhead(before);
        var exact=ComputerRuntime.restore(before,new ResourceLimits(10000,capacity,1));exact.load();exact.step();
        assertEquals(ComputationMachine.Status.RUNNING,exact.report().status());
        assertEquals(before.machine().arenaUnits()+2,exact.report().peakMemoryUnits());
        var state=(MachineCheckpoint.TextState)exact.snapshot().machine().frames().getLast();
        assertEquals(text("😀"),state.progress().field("buffer"));
        var shortByOne=ComputerRuntime.restore(before,new ResourceLimits(10000,capacity-1,1));shortByOne.load();shortByOne.step();
        assertMemoryFailure(shortByOne);assertEquals(before.machine().arenaUnits(),shortByOne.report().peakMemoryUnits());
    }
    private static long overhead(ComputerRuntime.Snapshot snapshot){return snapshot.mailbox().storageUnits()+snapshot.rpc().storageUnits();}
    private static void finish(ComputerRuntime runtime){for(int i=0;runtime.report().status()==ComputationMachine.Status.RUNNING;i++){assertTrue(i<1000);runtime.step();}}
    private static void assertMemoryFailure(ComputerRuntime runtime){
        assertEquals(ComputationMachine.Status.FAILED,runtime.report().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT,runtime.report().diagnostic().orElseThrow().code());
        assertEquals(text("old"),runtime.memory().field("value"));assertTrue(runtime.outbound().isEmpty());
        assertTrue(runtime.report().output().isEmpty());
    }
    private static ProgramArchive budgetedArchive(){
        var nodes=new LinkedHashMap<String,ComputationGraph.Node>();
        nodes.put("left",new ComputationGraph.Constant(text("😀".repeat(16))));nodes.put("right",new ComputationGraph.Constant(text("中".repeat(8))));
        nodes.put("join",new ComputationGraph.Text(TextOperation.CONCAT,Map.of("left","left","right","right")));
        nodes.put("endpoint",new ComputationGraph.Constant(text("factory:test")));nodes.put("payload",new ComputationGraph.MakeRecord(Map.of("value","join")));
        var graph=new ComputationGraph(Map.of(),nodes,Map.of("value","join","payload","payload","endpoint","endpoint"));
        return new ProgramArchive(new ProgramDefinition(graph,Map.of("value",new ProgramDefinition.Cell(DataType.Scalar.TEXT,text("old"))),
            List.of(new ProgramDefinition.Write("value","value")),List.of(new ProgramDefinition.Send("endpoint","payload"))),Map.of());
    }
    @Test void publicStationFilterComposesInsideNestedCallBranchMapAndFold(){
        var archive=stationArchive();var installed=archive.compile(ROOMY);
        var source=((ComputationGraph.Constant)archive.program().graph().nodes().get("stations")).value();
        var machine=new ComputationMachine(installed);machine.begin(new DataValue.RecordValue(Map.of()));int steps=0;
        while(machine.report().status()==ComputationMachine.Status.RUNNING){
            assertTrue(++steps<2000);machine=ComputationMachine.restore(installed,machine.checkpoint());machine.step();
        }
        assertEquals(ComputationMachine.Status.SUCCEEDED,machine.report().status());
        var output=machine.report().output().orElseThrow();
        assertEquals(new DataValue.ListValue(List.of(station("Sensor-A"),station("SENSOR-C"))),output.field("filtered"));
        assertEquals(new DataValue.ListValue(List.of(text("Sensor-A"),text("SENSOR-C"))),output.field("names"));
        assertEquals(text("Sensor-ASENSOR-C"),output.field("joined"));
        assertEquals(new DataValue.ListValue(List.of(station("Sensor-A"),station("Pump-B"),station("SENSOR-C"))),source);
        assertTrue(steps>100);
    }
    @Test void splitThenJoinUsesOnlyPublicNamedPorts(){
        var nodes=new LinkedHashMap<String,ComputationGraph.Node>();
        nodes.put("source",new ComputationGraph.Constant(text(",a,,")));nodes.put("delimiter",new ComputationGraph.Constant(text(",")));
        nodes.put("separator",new ComputationGraph.Constant(text("|")));
        nodes.put("split",new ComputationGraph.Text(TextOperation.SPLIT,Map.of("text","source","delimiter","delimiter")));
        nodes.put("join",new ComputationGraph.Text(TextOperation.JOIN,Map.of("items","split","separator","separator")));
        var archive=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(
            new ProgramDefinition(new ComputationGraph(Map.of(),nodes,Map.of("result","join")),Map.of(),List.of()),Map.of());
        var installed=archive.compile(ROOMY);var machine=new ComputationMachine(installed);machine.begin(new DataValue.RecordValue(Map.of()));
        while(machine.report().status()==ComputationMachine.Status.RUNNING){machine=ComputationMachine.restore(installed,machine.checkpoint());machine.step();}
        assertEquals(text("|a||"),machine.report().output().orElseThrow().field("result"));
    }
    @Test void lateAnyJoinFailureDoesNotCommitMemoryOrPublishMessages(){
        var nodes=new LinkedHashMap<String,ComputationGraph.Node>();
        nodes.put("items",new ComputationGraph.Input("items"));nodes.put("separator",new ComputationGraph.Constant(text("|")));
        nodes.put("join",new ComputationGraph.Text(TextOperation.JOIN,Map.of("items","items","separator","separator")));
        nodes.put("payload",new ComputationGraph.MakeRecord(Map.of("value","join")));
        nodes.put("endpoint",new ComputationGraph.Constant(text("factory:test")));
        var graph=new ComputationGraph(Map.of("items",DataType.Scalar.ANY),nodes,Map.of("value","join","payload","payload","endpoint","endpoint"));
        var program=new ProgramDefinition(graph,Map.of("value",new ProgramDefinition.Cell(DataType.Scalar.TEXT,text("old"))),List.of(new ProgramDefinition.Write("value","value")),List.of(new ProgramDefinition.Send("endpoint","payload")));
        var archive=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(program,Map.of());
        var runtime=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime(UUID.randomUUID(),archive,ROOMY);
        runtime.begin(new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(text("abc"),integer(9))))));
        for(int i=0;runtime.snapshot().machine().status()==ComputationMachine.Status.RUNNING;i++){assertTrue(i<100);runtime.step();}
        assertEquals(ComputationException.Code.TYPE_MISMATCH,runtime.snapshot().machine().diagnostic().orElseThrow().code());
        assertEquals(text("old"),runtime.snapshot().machine().memory().field("value"));
        assertTrue(runtime.snapshot().machine().output().isEmpty());assertTrue(runtime.snapshot().delivery().isEmpty());
    }
    @Test void emptyPatternCannotRestoreAFalseResultAndMoveRequiresActualMismatch(){
        for(var op:List.of(TextOperation.STARTS_WITH,TextOperation.CONTAINS,TextOperation.INDEX_OF,TextOperation.CONTAINS_IGNORE_CASE)){
            var args=Map.<String,DataValue>of("text",text("abc"),"needle",text(""));
            var fields=new LinkedHashMap<>(TextTask.start(op,args,"x",n->{}).checkpoint().fields());
            fields.put("phase",text("SEARCH_DONE"));
            if(op!=TextOperation.STARTS_WITH){fields.put("candidate",integer(3));fields.put("offset",integer(3));fields.put("index",integer(3));}
            invalidTask(op,args,fields);
        }
        var args=Map.<String,DataValue>of("text",text("abc"),"needle",text("ab"));
        var fields=new LinkedHashMap<>(TextTask.start(TextOperation.CONTAINS,args,"x",n->{}).checkpoint().fields());
        fields.put("phase",text("MOVE"));invalidTask(TextOperation.CONTAINS,args,fields);
    }
    @Test void characterBufferMustContainExactlyOneCodePoint(){
        var args=Map.<String,DataValue>of("text",text("😀"),"index",integer(0));
        var task=TextTask.start(TextOperation.CHARACTER_AT,args,"x",n->{});task.advance(n->{});
        var fields=new LinkedHashMap<>(task.checkpoint().fields());fields.put("buffer",text("ab"));
        invalidTask(TextOperation.CHARACTER_AT,args,fields);
    }
    @Test void exactLiveChargeIsLegalButOmittingOnlyTextOutputAndAddingChildrenAreNot(){
        for(var op:List.of(TextOperation.CONCAT,TextOperation.SPLIT)){
            var example=examples().stream().filter(e->e.operation()==op).findFirst().orElseThrow();
            var installed=archive(op,example.arguments()).compile(ROOMY);
            var machine=new ComputationMachine(installed);machine.begin(new DataValue.RecordValue(Map.of()));int inspected=0;
            while(machine.report().status()==ComputationMachine.Status.RUNNING){
                var cp=machine.checkpoint();
                if(cp.frames().getLast() instanceof MachineCheckpoint.TextState state){
                    inspected++;long other=installed.usage().constantUnits()+installed.usage().initialMemoryUnits()+cp.memory().storageUnits();
                    for(var f:cp.frames())if(f instanceof MachineCheckpoint.GraphState graph){other+=graph.inputs().storageUnits();for(var v:graph.values().fields().values())other+=v.storageUnits();}
                    var task=TextTask.restore(op,example.arguments(),state.progress(),"x");
                    var exact=with(cp,cp.frames(),other+task.liveUnits());assertDoesNotThrow(()->ComputationMachine.restore(installed,exact));
                    invalidMachine(installed,with(cp,cp.frames(),other));
                    var frames=new ArrayList<>(cp.frames());frames.add(cp.frames().getFirst());invalidMachine(installed,with(cp,frames,cp.arenaUnits()));
                    invalidMachine(installed,with(cp,List.of(state),cp.arenaUnits()));
                    var fields=new LinkedHashMap<>(state.progress().fields());fields.put("phase",text("JOIN_ITEM"));
                    frames=new ArrayList<>(cp.frames());frames.set(frames.size()-1,new MachineCheckpoint.TextState(new DataValue.RecordValue(fields)));
                    invalidMachine(installed,with(cp,frames,cp.arenaUnits()));
                }
                machine.step();
            }
            assertTrue(inspected>2);
        }
    }
    @Test void cursorsCannotSplitSurrogatesOrExceedTheirFrozenSource(){
        var args=Map.<String,DataValue>of("text",text("😀中"));
        var task=TextTask.start(TextOperation.LENGTH,args,"x",n->{});
        for(long offset:List.of(-1L,1L,4L)){
            var fields=new LinkedHashMap<>(task.checkpoint().fields());fields.put("offset",integer(offset));fields.put("index",integer(1));invalidTask(TextOperation.LENGTH,args,fields);
        }
        var fields=new LinkedHashMap<>(task.checkpoint().fields());fields.put("operation",text("CONCAT"));invalidTask(TextOperation.LENGTH,args,fields);
    }
    private static void invalidTask(TextOperation op,Map<String,DataValue> args,Map<String,DataValue> fields){
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->TextTask.restore(op,args,new DataValue.RecordValue(fields),"x")).code());
    }
    private static MachineCheckpoint with(MachineCheckpoint cp,List<MachineCheckpoint.FrameState> frames,long arena){return new MachineCheckpoint(cp.memory(),cp.status(),cp.paused(),cp.workUnits(),arena,cp.output(),cp.diagnostic(),frames);}
    private static void invalidMachine(ProgramCompiler.InstalledProgram installed,MachineCheckpoint cp){assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->ComputationMachine.restore(installed,cp)).code());}
    static final ResourceLimits ROOMY=new ResourceLimits(10000,1000000,1000);
    @Test void everyOperationRunsThroughGraphAndRestoresEveryMachineStep() {
        for(var example:examples()){
            var installed=archive(example.operation(),example.arguments()).compile(ROOMY);
            var machine=new ComputationMachine(installed);machine.begin(new DataValue.RecordValue(Map.of()));boolean saw=false;
            for(int i=0;machine.report().status()==ComputationMachine.Status.RUNNING;i++){
                assertTrue(i<2000);var cp=machine.checkpoint();
                saw|=cp.frames().stream().anyMatch(MachineCheckpoint.TextState.class::isInstance);
                machine=ComputationMachine.restore(installed,cp);long before=machine.report().workUnits();machine.step();
                assertEquals(before+1,machine.report().workUnits());
            }
            assertTrue(saw);assertEquals(ComputationMachine.Status.SUCCEEDED,machine.report().status(),example.operation().name());
            assertEquals(example.expected(),machine.report().output().orElseThrow().field("result"));
        }
    }
    @Test void progressHasClosedFieldSetAndCannotChangeOperation() {
        var args=Map.<String,DataValue>of("text",text("abc"));var task=TextTask.start(TextOperation.LENGTH,args,"x",n->{});
        var fields=new LinkedHashMap<>(task.checkpoint().fields());fields.put("extra",integer(0));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->TextTask.restore(TextOperation.LENGTH,args,new DataValue.RecordValue(fields),"x")).code());
        fields.remove("extra");fields.remove("phase");
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->TextTask.restore(TextOperation.LENGTH,args,new DataValue.RecordValue(fields),"x")).code());
    }
}
