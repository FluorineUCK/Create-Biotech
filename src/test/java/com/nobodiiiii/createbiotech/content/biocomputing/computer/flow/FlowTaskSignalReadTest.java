package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Input-only tasks share physical samples and CPU, not an output latch or extra budget. */
class FlowTaskSignalReadTest {
    @Test void indirectChildReadCannotChangeRootOutputMode() {
        var base=FlowAuthoredDevicesTest.HOST_LIMITS;
        var limits=new ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),base.serial(),3);
        var wrapper=new ModuleDefinition.Key("wrapper",1);var reader=new ModuleDefinition.Key("reader",1);
        var integer=DataType.Scalar.INTEGER;
        var program=new FlowProgram(Map.of("value",integer),List.of(new FlowProgram.SignalWrite("drive","p0","value"),
            new FlowProgram.Procedure("wrapper",wrapper,Map.of(),Map.of(),Map.of()),
            new FlowProgram.Invoke("indirect",reader,new FlowCallBindings(Map.of(),Map.of())),new FlowProgram.EndProcedure("wrapper_end"),
            new FlowProgram.Procedure("reader",reader,Map.of(),Map.of(),Map.of(),FlowBlockRules.NONE,
                Optional.of(new AssemblySource("REG value INTEGER\nSIO.READ value p0\nRET"))),new FlowProgram.EndProcedure("reader_end"))).withAssembly();
        var host=new ComputerBlockHost(()->{});host.installFlow(program,record("value",15),limits,true);host.step(limits,true);
        var before=host.write();
        assertEquals(ComputationException.Code.INVALID_ARGUMENT,assertThrows(ComputationException.class,
            ()->host.startFlowTask(wrapper,new DataValue.RecordValue(Map.of()),limits,true)).code());
        assertEquals(before,host.write());assertEquals(Map.of("p0",15),host.flowSnapshot().orElseThrow().state().signals().outputs());
        assertThrows(ComputationException.class,()->program.compile(limits.hardware()).assembly().orElseThrow().forTask("wrapper",1));
    }
    @Test void inputOnlyTasksRetainTheirSamplesAcrossPauseAndReloadWithoutDrivingPins() {
        var base=FlowAuthoredDevicesTest.HOST_LIMITS;
        var limits=new ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),base.serial(),3);
        var key=new ModuleDefinition.Key("sample",1);
        var integer=DataType.Scalar.INTEGER;
        var program=new FlowProgram(Map.of("delay",integer),List.of(new FlowProgram.Sleep("main", "delay"),
            new FlowProgram.Procedure("reader",key,Map.of("wait",integer),Map.of("first",integer,"second",integer),
                Map.of("first","first","second","second"),FlowBlockRules.NONE,Optional.of(new AssemblySource(                    "REG first INTEGER\nREG second INTEGER\nSIO.READ first p0\nSLEEP wait\nSIO.READ second p1\nRET"))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var host=new ComputerBlockHost(()->{});
        host.installFlow(program,record("delay",100),limits,true);
        var a=host.startFlowTask(key,record("wait",2),limits,true);
        var b=host.startFlowTask(key,record("wait",2),limits,true);
        var taskImage=program.compile(limits.hardware()).assembly().orElseThrow().forTask("sample",1);
        var initialTask=host.flowTask(a).orElseThrow();
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,
            ()->taskImage.validateSignals(initialTask.assembly().orElseThrow(),initialTask.work(),false,Map.of("p0",15))).code());
        host.setPaused(false,limits,true);
        boolean sleeping=false;long tick=0;
        for(;tick<40;tick++) {
            host.tick(tick,limits,true,Map.of("p0",11,"p1",7));
            var saved=host.flowSnapshot().orElseThrow();
            assertTrue(saved.state().signals().outputs().isEmpty());
            for(var child:saved.tasks().contexts())assertTrue(child.signals().outputs().isEmpty());
            if(saved.tasks().contexts().stream().allMatch(s->s.status()==FlowRuntime.Status.SLEEPING)){sleeping=true;break;}
        }
        assertTrue(sleeping,"Both tasks must sample before the reload boundary");
        host.setPaused(true,limits,true);var disk=host.write();host.tick(++tick,limits,true,Map.of("p0",3,"p1",4));
        assertEquals(disk,host.write());host.unload();
        host=new ComputerBlockHost(()->{});host.read(disk);host.tick(++tick,limits,true,Map.of());assertEquals(disk,host.write());
        host.setPaused(false,limits,true);
        for(int i=0;i<40;i++) {
            host.tick(++tick,limits,true,Map.of("p0",3,"p1",7));
            if(host.flowSnapshot().orElseThrow().tasks().contexts().stream().allMatch(s->s.status()==FlowRuntime.Status.COMPLETE))break;
        }
        for(var id:List.of(a,b)) {
            var result=host.claimFlowTask(id,limits,true);
            assertEquals(FlowRuntime.Status.COMPLETE,result.status());
            assertEquals(new DataValue.IntegerValue(11),result.locals().field("first"));
            assertEquals(new DataValue.IntegerValue(7),result.locals().field("second"));
            assertTrue(result.signals().outputs().isEmpty());
        }
        var saved=host.flowSnapshot().orElseThrow();assertTrue(saved.tasks().contexts().isEmpty());
        assertTrue(saved.state().signals().outputs().isEmpty());assertTrue(saved.receipts().isEmpty());
        assertEquals(0,saved.control().generation(),"Claiming tasks does not reset the Computer");
    }
    private static DataValue.RecordValue record(String key,long value){return new DataValue.RecordValue(Map.of(key,new DataValue.IntegerValue(value)));}
}
