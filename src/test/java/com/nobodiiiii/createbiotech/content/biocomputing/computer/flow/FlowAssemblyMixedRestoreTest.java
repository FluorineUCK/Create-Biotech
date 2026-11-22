package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowAssemblyMixedRestoreTest {
    static final ModuleDefinition.Key KEY=new ModuleDefinition.Key("worker",1);
    static final ComputerHostLimits LIMITS=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(1000,200000,1),new ArchiveLimits(100000,1000000,1000000),
        com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,3));
    static final Map<String,DataType> TYPES=Map.of("left",DataType.Scalar.TEXT,"right",DataType.Scalar.ANY,"x",DataType.Scalar.TEXT,"ticks",DataType.Scalar.INTEGER);
    static DataValue.RecordValue inputs() {var text=new DataValue.TextValue("x".repeat(64));return new DataValue.RecordValue(Map.of("left",text,"right",text,"x",new DataValue.TextValue("payload".repeat(30)),"ticks",new DataValue.IntegerValue(1000)));}
    static ProgramArchive archive(boolean record) {
        var build=new ComputationGraph(Map.of("x",DataType.Scalar.TEXT),Map.of("x",new ComputationGraph.Input("x"),"build",record?
            new ComputationGraph.MakeRecord(Map.of("a","x","b","x","c","x")):new ComputationGraph.MakeList(List.of("x","x","x"))),Map.of("value","build"));
        var compare=new ComputationGraph(Map.of("left",DataType.Scalar.TEXT,"right",DataType.Scalar.ANY),Map.of("left",new ComputationGraph.Input("left"),
            "right",new ComputationGraph.Input("right"),"compare",new ComputationGraph.ValueEquals("left","right")),Map.of("value","compare"));
        return new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of(
            "build",new ProgramDefinition.Entry(build,List.of(),List.of()),"compare",new ProgramDefinition.Entry(compare,List.of(),List.of()))),Map.of());
    }
    static FlowProgram.Evaluate build(String source){return new FlowProgram.Evaluate(source,"build",new FlowCallBindings(Map.of("x","x"),Map.of("value","built")));}
    static FlowProgram.Evaluate compare(String source){return new FlowProgram.Evaluate(source,"compare",new FlowCallBindings(Map.of("left","left","right","right"),Map.of("value","match")));}
    static ComputerBlockHost mixed(boolean record,boolean mainBuild) {
        var steps=List.<FlowProgram.Step>of(mainBuild?build("main_build"):compare("main_compare"),new FlowProgram.Sleep("idle","ticks"),
            new FlowProgram.Procedure("definition",KEY,TYPES,Map.of("value",DataType.Scalar.ANY),Map.of("value",mainBuild?"match":"built")),
            mainBuild?compare("task_compare"):build("task_build"),new FlowProgram.EndProcedure("return"));
        var flow=new FlowProgram(TYPES,steps,archive(record)).withAssembly();var host=new ComputerBlockHost(()->{});host.installFlow(flow,inputs(),LIMITS,true);
        var task=host.startFlowTask(KEY,inputs(),LIMITS,true);
        for(int i=0;i<30;i++) {
            var saved=host.flowSnapshot().orElseThrow();var a=saved.state().assembly().orElseThrow();var b=host.flowTask(task).orElseThrow().assembly().orElseThrow();
            if((mainBuild?a.build():b.build()).filter(p->p.cursor()==1).isPresent()&&(mainBuild?b.comparison():a.comparison()).isPresent())return host;
            host.step(LIMITS,true);
        }
        throw new AssertionError("mixed intermediate states not reached");
    }
    static long totalRam(FlowBlockHost.Saved s) {
        long total=s.initialInputs().storageUnits()+s.control().storageUnits()+s.memory().storageUnits()+s.pinUnits()+s.tasks().metadataUnits()+s.program().usage(LIMITS.hardware()).constantUnits()+s.state().controlUnits();
        for(var r:s.receipts())total+=r.value().storageUnits();for(var c:s.cancellations())total+=c.value().storageUnits();for(var child:s.tasks().contexts())total+=child.controlUnits();return total;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false","false,true","true,false","true,true"})
    void mixedConstructionAndComparisonColdRestoreBothDirectionsAndKeepPcPaidAndSource(boolean record,boolean mainBuild) {
        var host=mixed(record,mainBuild);var before=host.flowSnapshot().orElseThrow();var raw=host.write();
        var cold=new ComputerBlockHost(()->{});cold.read(raw);assertDoesNotThrow(()->cold.tick(LIMITS,true));
        assertTrue(cold.loaded(),()->cold.diagnostic().toString());assertEquals(before,cold.flowSnapshot().orElseThrow());assertEquals(raw,cold.write());
        long work=before.state().work();for(var child:before.tasks().contexts())work+=child.work();cold.step(LIMITS,true);
        var next=cold.flowSnapshot().orElseThrow();long nextWork=next.state().work();for(var child:next.tasks().contexts())nextWork+=child.work();assertEquals(work+1,nextWork);
        var tight=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(1000,totalRam(before),1),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),3));
        var rejected=new ComputerBlockHost(()->{});rejected.read(raw);assertDoesNotThrow(()->rejected.tick(tight,true));
        assertFalse(rejected.loaded());assertEquals(raw,rejected.write());assertTrue(rejected.diagnostic().isPresent());
        rejected.tick(LIMITS,true);assertTrue(rejected.loaded(),()->rejected.diagnostic().toString());
        assertEquals(before,rejected.flowSnapshot().orElseThrow());assertEquals(raw,rejected.write());
    }
    static final ComputerHostLimits SERIAL_LIMITS = ParserHardwareFixture.withMasterParser(new ComputerHostLimits(LIMITS.hardware(), LIMITS.archive(), LIMITS.mailbox(), LIMITS.pins(), SerialFlowHostTest.SERIAL, 3));
    static ComputerBlockHost serialSender(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialDevice device) {
        var packet = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("encoding".repeat(30))));
        var types = new LinkedHashMap<>(TYPES); types.put("packet", DataType.typeOf(packet));
        var values = new LinkedHashMap<>(inputs().fields()); values.put("packet", packet);
        var program = new FlowProgram(types, List.of(new FlowProgram.SerialSend("main_send", "s0", "packet", "sent"),
            new FlowProgram.Sleep("idle", "ticks"), new FlowProgram.Procedure("definition", KEY, TYPES,
            Map.of("value", DataType.Scalar.ANY), Map.of("value", "match")), compare("task_compare"), new FlowProgram.EndProcedure("return")), archive(false)).withAssembly();
        var owner = new ComputerBlockHost(() -> {}, pin -> {}, device); owner.installFlow(program, new DataValue.RecordValue(values), SERIAL_LIMITS, true);
        owner.startFlowTask(KEY, inputs(), SERIAL_LIMITS, true);
        for (int i=0; i<12; i++) owner.step(SERIAL_LIMITS, true);
        assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().paidWork()>1);
        assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().getFirst().assembly().orElseThrow().comparison().isPresent());
        return owner;
    }
    @Test void serialTxColdRestoreAndRetryPreservePcPaidSourcesAndQueueAndNextStepCostsOne() {
        var device=SerialFlowHostTest.device(); device.configure("s0", net.minecraft.core.Direction.EAST, true);
        var owner=serialSender(device); var before=owner.flowSnapshot().orElseThrow(); var raw=owner.write(); var queue=device.write();
        var tight=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(1000,totalRam(before),1),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),SERIAL_LIMITS.serial(),3));
        var cold=new ComputerBlockHost(()->{},pin->{},device); cold.read(raw); assertDoesNotThrow(()->cold.tick(tight,true));
        assertFalse(cold.loaded()); assertEquals(raw,cold.write()); assertEquals(queue,device.write()); assertTrue(cold.diagnostic().isPresent());
        assertDoesNotThrow(()->cold.tick(SERIAL_LIMITS,true)); assertTrue(cold.loaded(),()->cold.diagnostic().toString());
        assertEquals(before,cold.flowSnapshot().orElseThrow()); assertEquals(raw,cold.write()); assertEquals(queue,device.write());
        long work=before.state().work()+before.tasks().contexts().getFirst().work(); cold.step(SERIAL_LIMITS,true);
        var next=cold.flowSnapshot().orElseThrow(); assertEquals(work+1,next.state().work()+next.tasks().contexts().getFirst().work());
        assertEquals(queue,device.write());
    }
    @Test void coldTxRejectsBeforeAllocatingOutputBufferWithoutWholeOwnerHeadroom() {
        var device=SerialFlowHostTest.device(); device.configure("s0",net.minecraft.core.Direction.EAST,true);
        var owner=serialSender(device); var before=owner.flowSnapshot().orElseThrow();
        var decoded=FlowBlockNbtCodec.decode((net.minecraft.nbt.CompoundTag)owner.write(),SERIAL_LIMITS.archive());
        var state=decoded.state(); var progress=state.assembly().orElseThrow().serial().orElseThrow();
        assertTrue(progress.materializedUnits().isEmpty());
        long wire=state.restoreControlUnits()+before.program().usage(SERIAL_LIMITS.hardware()).constantUnits();
        assertThrows(ComputationException.class,()->FlowRuntime.restore(before.program(),state,new ResourceLimits(1000,Math.max(4096,wire),1)));
        assertTrue(progress.materializedUnits().isEmpty(),"Resource rejection must precede buffer reconstruction, not only reject the retained result");
        assertEquals(before.state(),state); assertEquals(before.program(),decoded.program());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"list","record","tx"})
    void partiallyFailedValidationKeepsItsWarmWitnessFullyChargedOnRetry(String kind) {
        var device=SerialFlowHostTest.device();device.configure("s0",net.minecraft.core.Direction.EAST,true);
        var owner=kind.equals("tx")?serialSender(device):mixed(kind.equals("record"),true);
        var saved=owner.flowSnapshot().orElseThrow();var raw=owner.write();
        var decoded=FlowBlockNbtCodec.decode((net.minecraft.nbt.CompoundTag)raw,LIMITS.archive());var state=decoded.state();
        long cold=state.restoreControlUnits(),shared=saved.program().usage(LIMITS.hardware()).constantUnits();
        var badLocals=new LinkedHashMap<>(state.locals().fields());badLocals.put("not_a_local",new DataValue.IntegerValue(1));
        var bad=new FlowRuntime.Checkpoint(state.computer(),state.flow(),state.cursor(),state.status(),new DataValue.RecordValue(badLocals),
            state.work(),state.paused(),state.expression(),state.diagnostic(),state.sleepTicks(),state.signals(),state.operation(),state.repeats(),
            state.inputInvocation(),state.calls(),state.entry(),state.assembly());
        var failure=assertThrows(ComputationException.class,()->FlowRuntime.restore(saved.program(),bad,LIMITS.hardware()));
        assertEquals(ComputationException.Code.INVALID_CHECKPOINT,failure.code());
        var registers=state.assembly().orElseThrow();
        long owned=kind.equals("tx")?registers.serial().orElseThrow().materializedUnits().orElseThrow():registers.build().orElseThrow().materializedUnits().orElseThrow();
        assertTrue(owned>0);assertEquals(cold+owned,state.restoreControlUnits());assertEquals(state.controlUnits(),state.restoreControlUnits());
        var tight=new ResourceLimits(1000,cold+shared+owned-1,1);
        var rejected=assertThrows(ComputationException.class,()->FlowRuntime.restore(saved.program(),state,tight));
        assertEquals(ComputationException.Code.MEMORY_LIMIT,rejected.code());
        assertEquals(cold+owned,state.restoreControlUnits(),"A previously materialized witness cannot disappear from the retry ledger");
        assertEquals(state,FlowRuntime.restore(saved.program(),state,LIMITS.hardware()).checkpoint());assertEquals(raw,owner.write());
    }

}
