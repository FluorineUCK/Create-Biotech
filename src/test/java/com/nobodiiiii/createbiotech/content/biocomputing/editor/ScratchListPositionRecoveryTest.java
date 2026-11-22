package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchListPositionRecoveryTest {
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.RecordValue record(Map<String,DataValue> values) { return new DataValue.RecordValue(values); }
    static ComputationGraph graph(boolean insertion, String choice) {
        var items=new DataValue.ListValue(List.of(new DataValue.IntegerValue(10),new DataValue.IntegerValue(20)));
        var base=new ComputationGraph(Map.of(),Map.of("items",new ComputationGraph.Constant(items)),Map.of("value","items"));
        var encoded=(DataValue.RecordValue)GraphArchiveCodec.encode(base);
        var fields=new LinkedHashMap<>(encoded.fields());
        var nodes=new LinkedHashMap<>(((DataValue.RecordValue)fields.get("nodes")).fields());
        nodes.put("position",record(Map.of("kind",text("list_position"),"source",text("items"),"choice",text(choice),
            "insertion",new DataValue.BooleanValue(insertion))));
        fields.put("nodes",record(nodes));fields.put("outputs",record(Map.of("value",text("position"))));
        assertThrows(ArchiveException.class,()->GraphArchiveCodec.decode(record(fields),24));
        return GraphArchiveCodec.decode(record(fields),25);
    }
    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void directArgumentPositionSurvivesEveryPaidStepAndPausedTick(FlowProgram.Compilation policy) {
        for(boolean insertion:List.of(false,true)) for(String choice:List.of("LAST","RANDOM")) {
            DataType resultType=DataType.Scalar.INTEGER;
            var graph=graph(insertion,choice);
            assertEquals(25,GraphArchiveCodec.encodingVersion(List.of(graph)));
            var key=new ModuleDefinition.Key("identity",1);
            var call=FlowInlineValues.operands(new FlowProgram.Invoke("call",key,
                new FlowCallBindings(Map.of(),Map.of("result","answer"))),"call",Map.of());
            var procedure=new FlowProgram.Procedure("body",key,Map.of("value",resultType),Map.of("result",resultType),Map.of("result","value"));
            var canvas=new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),List.of(new FlowBlockLayout.Stack(
                new ProgramDocument.Position(400,16),List.of(procedure,new FlowProgram.EndProcedure("end")))));
            var workspace=new ProgramWorkspace();
            workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(call),record(Map.of()),canvas,
                Map.of("call",graph),FlowProgram.Execution.REGISTER_V1,policy)));
            var document=workspace.snapshot();
            assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
            var program=document.flow().orElseThrow().program(document.archive());
            assertTrue(program.usesBoundedAssemblyRestore());
            var limits=StockApplicationDriverDocuments.hardware();
            var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            boolean completed=false;
            for(int step=0;step<400;step++) {
                var before=host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,before.state().status(),before.state().diagnostic().toString());
                if(before.state().status()==FlowRuntime.Status.COMPLETE) {
                    long position=((DataValue.IntegerValue)program.displayLocals(before.state().assembly().orElseThrow()).field("answer")).value();
                    assertTrue(position>=1 && position <= (insertion?3:2));
                    if(choice.equals("LAST")) assertEquals(insertion?3:2,position);
                    completed=true;break;
                }
                host.tick(limits,true);assertEquals(before,host.flowSnapshot().orElseThrow());
                var fork=new ComputerBlockHost(()->{});fork.read(host.write());fork.tick(limits,true);
                assertTrue(fork.loaded(),fork.diagnostic().toString());fork.step(limits,true);
                host.step(limits,true);
                assertEquals(host.flowSnapshot().orElseThrow(),fork.flowSnapshot().orElseThrow(),"Reload must not reroll the choice");
                var checkpoint=host.flowSnapshot().orElseThrow();var raw=host.write();
                host=new ComputerBlockHost(()->{});host.read(raw);host.tick(limits,true);
                assertTrue(host.loaded(),host.diagnostic().toString());assertEquals(checkpoint,host.flowSnapshot().orElseThrow());
            }
            assertTrue(completed);
        }
    }
}
