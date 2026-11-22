package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegisterComparisonReservationTest {
    private static ComputerHostLimits parser(ResourceLimits resources, ArchiveLimits archive) {
        return new ComputerHostLimits(resources, archive,
            com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,
            HardwarePins.DEFAULT, SerialHardware.NONE, 1, true);
    }
    static final ComputerHostLimits LIMITS=parser(new ResourceLimits(1000,100000,1),new ArchiveLimits(100000,1000000,1000000));
    static FlowProgram program() {
        var graph=new ComputationGraph(Map.of("left",DataType.Scalar.TEXT,"right",DataType.Scalar.ANY),Map.of(
            "left",new ComputationGraph.Input("left"),"right",new ComputationGraph.Input("right"),"eq",new ComputationGraph.ValueEquals("left","right")),Map.of("value","eq"));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),
            Map.of("compare",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        return new FlowProgram(graph.inputs(),List.of(new FlowProgram.Evaluate("compare","compare",new FlowCallBindings(Map.of("left","left","right","right"),Map.of("value","match")))),archive).withAssembly();
    }
    static ComputerBlockHost midway() {
        var text=new DataValue.TextValue("x".repeat(64));var host=new ComputerBlockHost(() -> {});
        host.installFlow(program(),new DataValue.RecordValue(Map.of("left",text,"right",text)),LIMITS,true);
        for(int i=0;i<12;i++)host.step(LIMITS,true);return host;
    }
    static RegisterState coldState() {
        var saved=midway().flowSnapshot().orElseThrow();
        return FlowNbtCodec.decode(FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(saved.program(),saved.state()),LIMITS.archive()),LIMITS.archive()).state().assembly().orElseThrow();
    }
    @Test void coldValidationReservesSnapshotBeforeMaterializingItAndMaterializesOnlyOnceWhenAdmitted() {
        var state=coldState();var before=state.value();var progress=state.comparison().orElseThrow();
        var op=new RegisterProgram.ValueEquals(4,2,3);var materializations=new AtomicInteger();
        assertEquals(9,progress.ownedUnits());
        assertEquals(ComputationException.Code.MEMORY_LIMIT,assertThrows(ComputationException.class,()->progress.validate(state,op,units->{
            if(units>9)throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"assembly","cursor fits but snapshot does not");
        },task->{materializations.incrementAndGet();return task.checkpoint();})).code());
        assertEquals(0,materializations.get(),"Failed snapshot reservation must not first allocate a progress snapshot");assertEquals(before,state.value());
        progress.validate(state,op,units->assertTrue(units<=59),task->{materializations.incrementAndGet();return task.checkpoint();});
        assertEquals(1,materializations.get(),"Successful validation materializes the genuine checkpoint once");assertEquals(before,state.value());
    }
    @Test void realOwnerWithCursorOnlyColdHeadroomRejectsAndRetainsOriginalNbtAndPaidProgress() {
        var host=midway();var saved=host.flowSnapshot().orElseThrow();var raw=host.write();
        long retained=saved.initialInputs().storageUnits()+saved.control().storageUnits()+saved.memory().storageUnits()+saved.state().controlUnits()+saved.program().usage(LIMITS.hardware()).constantUnits();
        var tight=parser(new ResourceLimits(1000,retained+9,1),LIMITS.archive());
        var cold=new ComputerBlockHost(() -> {});cold.read(raw);cold.tick(tight,true);
        assertFalse(cold.loaded());assertEquals(raw,cold.write());
        var adequate=parser(new ResourceLimits(1000,retained+80,1),LIMITS.archive());
        var restored=new ComputerBlockHost(() -> {});restored.read(raw);restored.tick(adequate,true);
        assertTrue(restored.loaded(),()->restored.diagnostic().toString());assertEquals(saved,restored.flowSnapshot().orElseThrow());assertEquals(raw,restored.write());
    }
    @Test void allocationFreeFootprintMatchesRealCheckpointAcrossEveryFrameMode() {
        var fields=new LinkedHashMap<String,DataValue>();fields.put("alpha",new DataValue.ListValue(List.of(new DataValue.ErrorValue("code","message"),new DataValue.NbtValue(8,new DataValue.TextValue("native")))));
        fields.put("beta",new DataValue.MapValue(Map.of("key",new DataValue.TextValue("value"))));
        var reversed=new LinkedHashMap<String,DataValue>();reversed.put("beta",fields.get("beta"));reversed.put("alpha",fields.get("alpha"));
        var pairs=List.of(List.<DataValue>of(new DataValue.RecordValue(fields),new DataValue.RecordValue(reversed)),
            List.<DataValue>of(new DataValue.ListValue(List.of(new DataValue.ListValue(List.of(new DataValue.IntegerValue(1))))),new DataValue.ListValue(List.of(new DataValue.ListValue(List.of(new DataValue.IntegerValue(2)))))),
            List.<DataValue>of(DataValue.NullValue.INSTANCE,new DataValue.BooleanValue(false)));
        var modes=new HashSet<String>();
        for(var pair:pairs) {
            var task=ValueEqualityTask.start(pair.getFirst(),pair.getLast(),"test",ignored->{});
            for(int work=0;;work++) {
                assertTrue(work<500);var progress=task.checkpoint();
                assertEquals(progress.storageUnits(),task.checkpointStorageUnits());
                for(var frame:((DataValue.ListValue)progress.field("frames")).values())modes.add(((DataValue.TextValue)((DataValue.RecordValue)frame).field("mode")).value());
                if(task.complete())break;task.advance(ignored->{});
            }
        }
        assertEquals(Set.of("ENTER","TEXT","ERROR_CODE","ERROR_MESSAGE","LIST","WAIT_LIST","NAMED_LEFT","NAMED_RIGHT","KEY","WAIT_NAMED","WAIT_NBT","NBT_DONE"),modes);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void nativeStartAndHotAdvanceAlsoReserveTheirSnapshotAndNewWitnessBeforePublication(boolean advancing) {
        var text=new DataValue.TextValue("x".repeat(64));var host=new ComputerBlockHost(() -> {});
        host.installFlow(program(),new DataValue.RecordValue(Map.of("left",text,"right",text)),LIMITS,true);
        for(int i=0;i<(advancing?12:2);i++)host.step(LIMITS,true);
        var saved=host.flowSnapshot().orElseThrow();long retained=saved.state().controlUnits()+saved.program().usage(LIMITS.hardware()).constantUnits();
        var tight=new ResourceLimits(1000,retained+9,1);var runtime=FlowRuntime.restore(saved.program(),saved.state(),tight);
        runtime.load();runtime.setPaused(false);runtime.advance(1);
        assertEquals(FlowRuntime.Status.FAILED,runtime.checkpoint().status());
        assertEquals(ComputationException.Code.MEMORY_LIMIT,runtime.checkpoint().diagnostic().orElseThrow().code());
        assertEquals(saved.state().assembly(),runtime.checkpoint().assembly());assertEquals(saved.state().locals(),runtime.checkpoint().locals());
    }
}
