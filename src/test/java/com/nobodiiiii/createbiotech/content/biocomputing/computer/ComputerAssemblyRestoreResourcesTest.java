package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ComputerAssemblyRestoreResourcesTest {
    static final ModuleDefinition.Key KEY = new ModuleDefinition.Key("compare",1);
    static final ComputerHostLimits TASK_LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),3));
    static DataValue.RecordValue inputs() {
        var text=new DataValue.TextValue("x".repeat(64));return new DataValue.RecordValue(Map.of("left",text,"right",text));
    }
    static ComputerBlockHost mainComparison() {
        var host=new ComputerBlockHost(() -> {});
        host.installFlow(ComputerAssemblyCorrelationMatchTest.equality(DataType.Scalar.TEXT,DataType.Scalar.ANY),inputs(),LIMITS,true);
        for(int i=0;i<12;i++)host.step(LIMITS,true);return host;
    }
    static ComputerBlockHost taskComparison() {
        var base=ComputerAssemblyCorrelationMatchTest.equality(DataType.Scalar.TEXT,DataType.Scalar.ANY);
        var types=new LinkedHashMap<>(base.inputs());types.put("ticks",DataType.Scalar.INTEGER);
        var flow=new FlowProgram(types,List.of(new FlowProgram.Sleep("idle","ticks"),
            new FlowProgram.Procedure("definition",KEY,base.inputs(),Map.of("match",DataType.Scalar.BOOLEAN),Map.of("match","match")),
            base.steps().getFirst(),new FlowProgram.EndProcedure("return")),base.expressions()).withAssembly();
        var args=new LinkedHashMap<>(inputs().fields());args.put("ticks",new DataValue.IntegerValue(1000));
        var host=new ComputerBlockHost(() -> {});host.installFlow(flow,new DataValue.RecordValue(args),TASK_LIMITS,true);host.step(TASK_LIMITS,true);
        var task=host.startFlowTask(KEY,inputs(),TASK_LIMITS,true);
        for(int i=0;i<12;i++)host.step(TASK_LIMITS,true);
        assertEquals(FlowRuntime.Status.SLEEPING,host.flowSnapshot().orElseThrow().state().status());
        assertTrue(host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().comparison().isEmpty());
        assertTrue(host.flowTask(task).orElseThrow().assembly().orElseThrow().comparison().isPresent());return host;
    }
    @Test void standaloneColdDecodeRejectsWireOnlyArchiveThatCannotEncodeItsOwnedFrames() {
        var before=mainComparison().flowSnapshot().orElseThrow();
        var snapshot=new FlowNbtCodec.Snapshot(before.program(),before.state());var raw=FlowNbtCodec.encode(snapshot,LIMITS.archive());
        long wire=ValueNbtCodec.decode(raw,LIMITS.archive()).storageUnits();var tight=new ArchiveLimits(100000,1000000,wire);
        assertThrows(ArchiveException.class,()->FlowNbtCodec.encode(snapshot,tight));
        assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(raw,tight));
        assertEquals(snapshot,FlowNbtCodec.decode(raw,LIMITS.archive()));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void ownerColdLoadRejectsMainOrTaskOnlyComparisonWithWireOnlyArchiveAndKeepsRawNbt(boolean taskOnly) {
        var host=taskOnly?taskComparison():mainComparison();var before=host.flowSnapshot().orElseThrow();var raw=(CompoundTag)host.write();var copy=raw.copy();
        long wire=ValueNbtCodec.decode(raw.getCompound("Data"),LIMITS.archive()).storageUnits();
        var limits=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(TASK_LIMITS.hardware(),new ArchiveLimits(100000,1000000,wire),TASK_LIMITS.mailbox(),TASK_LIMITS.pins(),TASK_LIMITS.serial(),3));
        var cold=new ComputerBlockHost(() -> {});cold.read(raw);cold.tick(limits,true);
        assertFalse(cold.loaded());assertEquals(copy,cold.write());assertEquals(raw,copy);assertTrue(cold.diagnostic().isPresent());
        var adequate=new ComputerBlockHost(() -> {});adequate.read(copy);adequate.tick(TASK_LIMITS,true);
        assertTrue(adequate.loaded());assertEquals(before,adequate.flowSnapshot().orElseThrow());assertEquals(copy,adequate.write());
    }
    @Test void taskColdValidationCannotBorrowMainOrSiblingRetainedRamAndAdequateLoadPreservesPaidWork() {
        var host=taskComparison();var saved=host.flowSnapshot().orElseThrow();var raw=host.write();
        long fixed=saved.initialInputs().storageUnits()+saved.control().storageUnits()+saved.memory().storageUnits();
        for(var receipt:saved.receipts())fixed+=receipt.value().storageUnits();
        for(var cancellation:saved.cancellations())fixed+=cancellation.value().storageUnits();
        long retained=saved.program().usage(TASK_LIMITS.hardware()).constantUnits()+saved.tasks().metadataUnits()+saved.pinUnits()+saved.state().controlUnits();
        for(var child:saved.tasks().contexts())retained+=child.controlUnits();
        var tight=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000,fixed+retained,1),TASK_LIMITS.archive(),TASK_LIMITS.mailbox(),TASK_LIMITS.pins(),TASK_LIMITS.serial(),3));
        var cold=new ComputerBlockHost(() -> {});cold.read(raw);cold.tick(tight,true);
        assertFalse(cold.loaded(),"B=T leaves no real Computer RAM for a task's cold comparison validation");assertEquals(raw,cold.write());assertTrue(cold.diagnostic().isPresent());
        var adequate=ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000,fixed+retained+100,1),TASK_LIMITS.archive(),TASK_LIMITS.mailbox(),TASK_LIMITS.pins(),TASK_LIMITS.serial(),3));
        var restored=new ComputerBlockHost(() -> {});restored.read(raw);restored.tick(adequate,true);
        assertTrue(restored.loaded(),()->restored.diagnostic().toString());assertEquals(saved,restored.flowSnapshot().orElseThrow());assertEquals(raw,restored.write());
        var child=saved.tasks().contexts().getFirst();restored.step(adequate,true);
        var next=restored.flowTask(child.flow()).orElseThrow();
        assertEquals(child.work()+1,next.work());assertEquals(child.assembly().orElseThrow().comparison().orElseThrow().paidWork()+1,next.assembly().orElseThrow().comparison().orElseThrow().paidWork());
    }
}
