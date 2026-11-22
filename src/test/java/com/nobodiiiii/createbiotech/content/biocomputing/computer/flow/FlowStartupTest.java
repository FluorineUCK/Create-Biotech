package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;

class FlowStartupTest {
    private static final DataValue.RecordValue EMPTY=new DataValue.RecordValue(Map.of());
    private static final ModuleDefinition.Key FIRST=new ModuleDefinition.Key("first",1), SECOND=new ModuleDefinition.Key("second",1);
    private static ComputerHostLimits limits(int slots){
        return ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(4096,64000,1),FlowArchiveTest.ARCHIVE,
            MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,slots));
    }
    private static FlowInput value(long n){return new FlowInput.Literal(new DataValue.IntegerValue(n));}
    private static FlowProgram program(FlowProgram.Compilation policy){
        var cells=Map.of("left",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0)),
            "right",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0)));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),cells,List.of()),Map.of());
        return new FlowProgram(Map.of(),List.of(new FlowProgram.Sleep("main_sleep",value(100)),
            FlowProgram.Procedure.startupHat("first_hat",FIRST),new FlowProgram.Shared("left_add","left",value(1),true),
            new FlowProgram.Sleep("child_sleep",value(40)),new FlowProgram.EndProcedure("first_end"),
            FlowProgram.Procedure.startupHat("second_hat",SECOND),new FlowProgram.Shared("right_add","right",value(1),true),
            new FlowProgram.EndProcedure("second_end")),archive,FlowProgram.Execution.REGISTER_V1,policy);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void startupScriptsAreFinitePausedInitiallyAndNotReplayedOnReload(FlowProgram.Compilation policy){
        var program=program(policy);var limits=limits(3);var owner=new ComputerBlockHost(()->{});
        var document=new ProgramDocument(program.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(program,EMPTY)));
        assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
        owner.installFlow(program,EMPTY,limits,true);
        assertEquals(2,owner.flowSnapshot().orElseThrow().tasks().contexts().size());
        var initial=owner.write();owner.tick(limits,true);assertEquals(initial,owner.write());
        owner.setPaused(false,limits,true);
        for(int i=0;i<16;i++)owner.tick(limits,true);
        var state=owner.flowSnapshot().orElseThrow();
        assertEquals(FlowRuntime.Status.SLEEPING,state.state().status());
        assertEquals(List.of(FIRST),state.tasks().contexts().stream().map(s->s.entry().orElseThrow()).toList());
        assertEquals(new DataValue.IntegerValue(1),state.memory().cells().field("left"));
        assertEquals(new DataValue.IntegerValue(1),state.memory().cells().field("right"));
        owner.setPaused(true,limits,true);var saved=owner.write();
        var restored=new ComputerBlockHost(()->{});restored.read(saved);restored.tick(limits,true);
        assertEquals(owner.flowSnapshot(),restored.flowSnapshot(),()->restored.diagnostic().toString());
        restored.setPaused(false,limits,true);for(int i=0;i<150;i++)restored.tick(limits,true);
        assertTrue(restored.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
        assertEquals(new DataValue.IntegerValue(1),restored.flowSnapshot().orElseThrow().memory().cells().field("left"));
        assertEquals(new DataValue.IntegerValue(1),restored.flowSnapshot().orElseThrow().memory().cells().field("right"));
    }

    @org.junit.jupiter.api.Test void installationIsAllOrNothingAndPristineStartupTasksDoNotBlockReplacement(){
        var program=program(FlowProgram.Compilation.ORIGINAL);var owner=new ComputerBlockHost(()->{});
        var before=owner.write();
        var error=assertThrows(ComputationException.class,()->owner.installFlow(program,EMPTY,limits(2),true));
        assertEquals(ComputationException.Code.MEMORY_LIMIT,error.code());assertEquals(before,owner.write());
        owner.installFlow(program,EMPTY,limits(3),true);
        assertDoesNotThrow(()->owner.installFlow(program,EMPTY,limits(3),true));
        var installed=owner.write();
        assertEquals(ComputationException.Code.INVALID_ARGUMENT,assertThrows(ComputationException.class,
            ()->owner.startFlowTask(FIRST,EMPTY,limits(3),true)).code());
        assertEquals(installed,owner.write());
        var smallRam=new ComputerHostLimits(new ResourceLimits(4096,1,1),FlowArchiveTest.ARCHIVE,
            MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,3,true);
        assertThrows(ComputationException.class,()->new ComputerBlockHost(()->{}).installFlow(program,EMPTY,smallRam,true));
    }
    @org.junit.jupiter.api.Test void resetExplicitlyCreatesNewPausedRootsButReloadAndLostCapacityDoNot(){
        var limits=limits(3);var owner=new ComputerBlockHost(()->{});
        owner.installFlow(program(FlowProgram.Compilation.ORIGINAL),EMPTY,limits,true);
        owner.setPaused(false,limits,true);for(int i=0;i<16;i++)owner.tick(limits,true);
        var oldRoot=owner.flowSnapshot().orElseThrow().tasks().contexts().getFirst().flow();
        owner.reset(limits,true);var reset=owner.flowSnapshot().orElseThrow();
        assertTrue(reset.state().paused());assertEquals(2,reset.tasks().contexts().size());
        assertTrue(reset.tasks().contexts().stream().noneMatch(t->t.flow().equals(oldRoot)));
        assertEquals(new DataValue.IntegerValue(0),reset.memory().cells().field("left"));
        var saved=owner.write();var restored=new ComputerBlockHost(()->{});restored.read(saved);
        restored.tick(limits(2),true);assertFalse(restored.loaded());assertEquals(saved,restored.write());
        restored.tick(limits,true);assertEquals(reset,restored.flowSnapshot().orElseThrow());
        restored.setPaused(false,limits,true);for(int i=0;i<160;i++)restored.tick(limits,true);
        assertEquals(new DataValue.IntegerValue(1),restored.flowSnapshot().orElseThrow().memory().cells().field("left"));
    }

    @org.junit.jupiter.api.Test void startupRolesCannotBeDowngradedInArchivesOrUsedAsOrdinaryCalls(){
        var program=program(FlowProgram.Compilation.ORIGINAL);var owner=new ComputerBlockHost(()->{});owner.installFlow(program,EMPTY,limits(3),true);
        var state=owner.flowSnapshot().orElseThrow();var raw=FlowBlockNbtCodec.encode(state,FlowArchiveTest.ARCHIVE);
        assertEquals(54,raw.getInt(FlowBlockNbtCodec.MARKER));
        raw.putInt(FlowBlockNbtCodec.MARKER,40);assertThrows(ArchiveException.class,()->FlowBlockNbtCodec.decode(raw,FlowArchiveTest.ARCHIVE));
        var doc=new ProgramDocument(program.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(program,EMPTY)));
        var fields=new LinkedHashMap<>(ProgramDocumentCodec.encode(doc).fields());fields.put("version",new DataValue.IntegerValue(39));
        assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
        for(boolean launch:List.of(false,true)){
            var steps=new ArrayList<>(program.steps());steps.set(0,launch?new FlowProgram.TaskStart("bad_launch",FIRST,Map.of(),"handle"):
                new FlowProgram.Invoke("bad_call",FIRST,new FlowCallBindings(Map.of(),Map.of())));
            var invalid=new FlowProgram(Map.of(),steps,program.expressions()).withAssembly();
            assertThrows(ComputationException.class,()->new ComputerBlockHost(()->{}).installFlow(invalid,EMPTY,limits(3),true));
        }
    }
    @org.junit.jupiter.api.Test void completedMainDoesNotStopSleepingRootAndFailedRootRemainsInspectable(){
        var base=program(FlowProgram.Compilation.ORIGINAL);var steps=new ArrayList<>(base.steps());steps.removeFirst();
        steps.set(steps.indexOf(new FlowProgram.Sleep("child_sleep",value(40))),new FlowProgram.Sleep("child_sleep",value(-1)));
        steps.add(steps.size()-1,new FlowProgram.Sleep("second_sleep",value(8)));
        var owner=new ComputerBlockHost(()->{});var limits=limits(3);
        owner.installFlow(new FlowProgram(Map.of(),steps,base.expressions()).withAssembly(),EMPTY,limits,true);
        owner.setPaused(false,limits,true);for(int i=0;i<30;i++)owner.tick(limits,true);
        var state=owner.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,state.state().status());
        assertEquals(1,state.tasks().contexts().size());assertEquals(FlowRuntime.Status.FAILED,state.tasks().contexts().getFirst().status());
        assertEquals(Optional.of(FIRST),state.tasks().contexts().getFirst().entry());
        assertEquals(new DataValue.IntegerValue(1),state.memory().cells().field("right"));
        var restored=new ComputerBlockHost(()->{});restored.read(owner.write());restored.tick(limits,true);
        assertEquals(owner.flowSnapshot(),restored.flowSnapshot());
    }
}
