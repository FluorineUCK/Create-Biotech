package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchTextReportersRecoveryTest {
    record Example(TextOperation operation, Map<String,DataValue> inputs, DataValue expected, DataType output) {}
    static Example letter(long index, String expected) {
        return new Example(TextOperation.LETTER_OF,Map.of("text",new DataValue.TextValue("A😀中"),
            "index",new DataValue.IntegerValue(index)),new DataValue.TextValue(expected),DataType.Scalar.TEXT);
    }
    static Example contains(String text,String needle,boolean expected) {
        return new Example(TextOperation.CONTAINS_IGNORE_CASE,Map.of("text",new DataValue.TextValue(text),
            "needle",new DataValue.TextValue(needle)),new DataValue.BooleanValue(expected),DataType.Scalar.BOOLEAN);
    }

    @org.junit.jupiter.api.Test void paletteOffersTextReportersAndLetterDefaultsToFirstCharacter() {
        var palette=InlineGraphEdits.palette(Map.of());
        for(var operation:List.of(TextOperation.CONCAT,TextOperation.LETTER_OF,TextOperation.CONTAINS_IGNORE_CASE))
            assertEquals(1,palette.stream().filter(value->value.equals(new InlineGraphEdits.TextOperator(operation))).count());
        var graph=new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.TextValue("abc"))),Map.of("value","value"));
        var edited=InlineGraphEdits.replace(graph,new FlowCallBindings(Map.of(),Map.of()),"value",new InlineGraphEdits.TextOperator(TextOperation.LETTER_OF)).graph();
        var letter=(ComputationGraph.Text)edited.nodes().get("value");
        assertEquals(new ComputationGraph.Constant(new DataValue.IntegerValue(1)),edited.nodes().get(letter.arguments().get("index")));
        assertEquals(new ComputationGraph.Constant(new DataValue.TextValue("abc")),edited.nodes().get(letter.arguments().get("text")));
    }

    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void directCallArgumentsSurviveEveryInstructionSaveAndPausedTick(FlowProgram.Compilation policy) {
        for(var example:List.of(letter(2,"😀"),letter(-1,""),letter(99,""),
                contains("a😀B中","😀b",true),contains("aaaaab","aaac",false),contains("","",true),
                contains("ß","ss",false),contains("Σ","ς",true))) {
            var key=new ModuleDefinition.Key("copy_text_result",1);
            var nodes=new LinkedHashMap<String,ComputationGraph.Node>();
            var arguments=new LinkedHashMap<String,String>();
            example.inputs().forEach((name,value)->{nodes.put(name,new ComputationGraph.Constant(value));arguments.put(name,name);});
            nodes.put("reporter",new ComputationGraph.Text(example.operation(),arguments));
            var graph=new ComputationGraph(Map.of(),nodes,Map.of("value","reporter"));
            if(example.operation()==TextOperation.LETTER_OF) {
                assertEquals(26,GraphArchiveCodec.encodingVersion(List.of(graph)));
                assertThrows(ArchiveException.class,()->GraphArchiveCodec.decode(GraphArchiveCodec.encode(graph),25));
            }
            var call=FlowInlineValues.operands(new FlowProgram.Invoke("copy",key,
                new FlowCallBindings(Map.of(),Map.of("result","answer"))),"copy",Map.of());
            var procedure=new FlowProgram.Procedure("copy_body",key,Map.of("value",example.output()),
                Map.of("result",example.output()),Map.of("result","value"));
            var canvas=new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),List.of(new FlowBlockLayout.Stack(
                new ProgramDocument.Position(400,16),List.of(procedure,new FlowProgram.EndProcedure("end_copy")))));
            var workspace=new ProgramWorkspace();
            workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(call),new DataValue.RecordValue(Map.of()),canvas,
                Map.of("copy",graph),FlowProgram.Execution.REGISTER_V1,policy)));
            var document=workspace.snapshot();
            assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
            var program=document.flow().orElseThrow().program(document.archive());
            assertTrue(program.usesBoundedAssemblyRestore());
            var limits=StockApplicationDriverDocuments.hardware();
            var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            boolean completed=false;
            for(int step=0;step<200;step++) {
                var before=host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,before.state().status(),before.state().diagnostic().toString());
                if(before.state().status()==FlowRuntime.Status.COMPLETE) {
                    assertEquals(example.expected(),program.displayLocals(before.state().assembly().orElseThrow()).field("answer"));
                    completed=true;break;
                }
                host.tick(limits,true);
                assertEquals(before,host.flowSnapshot().orElseThrow(),"Paused tick must not advance the reporter");
                host.step(limits,true);
                var expected=host.flowSnapshot().orElseThrow();var saved=host.write();
                host=new ComputerBlockHost(()->{});host.read(saved);host.tick(limits,true);
                assertTrue(host.loaded(),host.diagnostic().toString());
                assertEquals(expected,host.flowSnapshot().orElseThrow());
            }
            assertTrue(completed,example.toString());
        }
    }
}
