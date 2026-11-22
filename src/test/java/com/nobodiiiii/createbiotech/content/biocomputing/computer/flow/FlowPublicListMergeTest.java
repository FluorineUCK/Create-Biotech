package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyMemoryTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowPublicListMergeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(FlowProgram.Compilation.class)
    void competingPublicListAppendsResumeWithoutLosingTheCommittedEdit(FlowProgram.Compilation policy) {
        var limits = ParserHardwareFixture.withIsa1a(FlowAssemblyTasksTest.LIMITS);
        var number = DataType.Scalar.INTEGER;
        var result = com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterMemory.RESULT_TYPE;
        var key = new ModuleDefinition.Key("append", 1);
        var element = Map.<String, DataType>of("value", number);
        var edit = new ComputationGraph(element, Map.of(
            "original", new ComputationGraph.ReadMemory("items"),
            "item", new ComputationGraph.Input("value"),
            "updated", new ComputationGraph.ListEdit(ListOperation.APPEND, Map.of("source", "original", "value", "item"))),
            Map.of("value", "updated"));
        var archive = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
            Map.of("items", new ProgramDefinition.Cell(new DataType.ListType(number),
                new DataValue.ListValue(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(2))))),
            List.of(), List.of(), Map.of("edit", new ProgramDefinition.Entry(edit, List.of(), List.of()))), Map.of());
        var source = new FlowProgram(element, List.of(new FlowProgram.Sleep("idle", "value"),
            new FlowProgram.Procedure("definition", key, element, Map.of("result", result), Map.of("result", "result")),
            new FlowProgram.Shared("append_item", "items", new FlowInput.Expression("edit", "value", Map.of("value", "value")),
                false),
            new FlowProgram.EndProcedure("return")), archive).withAssembly();
        var encoded=(DataValue.RecordValue)FlowProgramCodec.encode(source);
        var fields=new LinkedHashMap<>(encoded.fields());
        var steps=new ArrayList<>(((DataValue.ListValue)fields.get("steps")).values());
        var editFields=new LinkedHashMap<>(((DataValue.RecordValue)steps.get(2)).fields());
        editFields.put("kind",new DataValue.TextValue("public_list_edit"));editFields.remove("add");
        editFields.put("result",new DataValue.TextValue("result"));steps.set(2,new DataValue.RecordValue(editFields));
        fields.put("steps",new DataValue.ListValue(steps));source=FlowProgramCodec.decode(new DataValue.RecordValue(fields));
        source=new FlowProgram(source.inputs(),source.steps(),source.expressions(),FlowProgram.Execution.REGISTER_V1,policy);
        var owner = new ComputerBlockHost(() -> {});
        owner.installFlow(source, input(), limits, true);
        owner.step(limits, true);
        UUID a = owner.startFlowTask(key, FlowAssemblyTasksTest.value(11), limits, true);
        UUID b = owner.startFlowTask(key, FlowAssemblyTasksTest.value(22), limits, true);
        boolean concurrent = false;
        for (int i = 0; i < 300; i++) {
            owner.step(limits, true);
            var checkpoint = owner.flowSnapshot().orElseThrow();
            if (checkpoint.memory().owners().size() == 2) {
                concurrent = true;
                assertEquals(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(2)),
                    ((DataValue.ListValue)checkpoint.memory().cells().field("items")).values());
            }
            var restored = new ComputerBlockHost(() -> {});
            restored.read(owner.write()); restored.tick(limits, true);
            assertTrue(restored.loaded(), restored.diagnostic().toString());
            assertEquals(checkpoint, restored.flowSnapshot().orElseThrow());
            owner = restored;
            if(owner.flowTask(a).orElseThrow().status()==FlowRuntime.Status.COMPLETE && owner.flowTask(b).orElseThrow().status()==FlowRuntime.Status.COMPLETE)break;
        }
        assertTrue(concurrent, "Both tasks must overlap at a real persisted transaction boundary");
        assertEquals(obj("ok", true, "code", "committed"), owner.flowTask(a).orElseThrow().locals().field("result"));
        assertEquals(obj("ok", false, "code", "write_conflict"), owner.flowTask(b).orElseThrow().locals().field("result"));
        assertEquals(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(2), new DataValue.IntegerValue(11)),
            ((DataValue.ListValue)owner.flowSnapshot().orElseThrow().memory().cells().field("items")).values());
        assertTrue(owner.flowSnapshot().orElseThrow().memory().owners().isEmpty());
    }
    @Test void operandMaySleepBeforeTheShortTransactionBegins() {
        var number=DataType.Scalar.INTEGER;var types=Map.<String,DataType>of("value",number,"ticks",number);
        var key=new ModuleDefinition.Key("prepare_item",1);
        var graph=new ComputationGraph(types,Map.of("original",new ComputationGraph.ReadMemory("items"),
            "value",new ComputationGraph.Input("value"),"ticks",new ComputationGraph.Input("ticks"),
            "call",new ComputationGraph.ProcedureValue(key,"result",Map.of("value","value","ticks","ticks")),
            "edit",new ComputationGraph.ListEdit(ListOperation.APPEND,Map.of("source","original","value","call"))),Map.of("value","edit"));
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),
            Map.of("items",new ProgramDefinition.Cell(new DataType.ListType(number),new DataValue.ListValue(List.of()))),List.of(),List.of(),
            Map.of("edit",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var source=new FlowProgram(types,List.of(
            new FlowProgram.Shared("append","items",new FlowInput.Expression("edit","value",Map.of("value","value","ticks","ticks")),false,Optional.of("result")),
            new FlowProgram.Procedure("prepare",key,types,Map.of("result",number),Map.of("result","value")),
            new FlowProgram.Sleep("wait","ticks"),new FlowProgram.EndProcedure("return")),archive).withAssembly();
        var limits=ParserHardwareFixture.withIsa1a(FlowAssemblyTasksTest.LIMITS);var owner=new ComputerBlockHost(()->{});
        owner.installFlow(source,new DataValue.RecordValue(Map.of("value",new DataValue.IntegerValue(7),"ticks",new DataValue.IntegerValue(2))),limits,true);
        boolean slept=false,transaction=false;
        for(int i=0;i<100;i++) {
            var saved=owner.flowSnapshot().orElseThrow();
            assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),saved.state().diagnostic().toString());
            if(saved.state().status()==FlowRuntime.Status.SLEEPING){slept=true;assertTrue(saved.memory().owners().isEmpty());}
            transaction|=!saved.memory().owners().isEmpty();
            if(saved.state().status()==FlowRuntime.Status.COMPLETE)break;
            var restored=new ComputerBlockHost(()->{});restored.read(owner.write());restored.tick(limits,true);
            assertTrue(restored.loaded(),restored.diagnostic().toString());assertEquals(saved,restored.flowSnapshot().orElseThrow());
            owner=restored;owner.step(limits,true);
        }
        assertTrue(slept);assertTrue(transaction);
        var done=owner.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,done.state().status());
        assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(7))),done.memory().cells().field("items"));
    }
}
