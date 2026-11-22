package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchListSearchRecoveryTest {
    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
    private static DataValue.RecordValue record(Map<String,DataValue> values) { return new DataValue.RecordValue(values); }
    static ComputationGraph graph(DataValue.ListValue items,DataValue needle,boolean contains) {
        // Read the source worktree's public archive shape, not a test-only execution path.
        var base=new ComputationGraph(Map.of(),Map.of("items",new ComputationGraph.Constant(items),
            "needle",new ComputationGraph.Constant(needle)),Map.of("value","items"));
        var encoded=(DataValue.RecordValue)GraphArchiveCodec.encode(base);
        var fields=new LinkedHashMap<>(encoded.fields());
        var nodes=new LinkedHashMap<>(((DataValue.RecordValue)fields.get("nodes")).fields());
        nodes.put("find",record(Map.of("kind",text("list_search"),"source",text("items"),"value",text("needle"),
            "contains",new DataValue.BooleanValue(contains))));
        fields.put("nodes",record(nodes));fields.put("outputs",record(Map.of("value",text("find"))));
        assertThrows(ArchiveException.class,()->GraphArchiveCodec.decode(record(fields),23));
        return GraphArchiveCodec.decode(record(fields),24);
    }
    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void directArgumentSearchRestoresDuringComparisonAndReturnsFirstExactMatch(FlowProgram.Compilation policy) {
        var items=new DataValue.ListValue(List.of(text("other"),text("job-12345678"),text("job-12345678")));
        for(boolean contains:List.of(false,true)) for(String needle:List.of("job-12345678","Job-12345678")) {
            boolean match=needle.equals("job-12345678");
            DataType resultType=contains?DataType.Scalar.BOOLEAN:DataType.Scalar.INTEGER;
            DataValue expected=contains?new DataValue.BooleanValue(match):new DataValue.IntegerValue(match?2:0);
            var graph=graph(items,text(needle),contains);
            assertEquals(24,GraphArchiveCodec.encodingVersion(List.of(graph)));
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
            assertTrue(program.usesValueEqualityAssembly());
            assertTrue(program.usesBoundedAssemblyRestore());
            var limits=StockApplicationDriverDocuments.hardware();
            var host=new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            boolean completed=false,sawComparison=false;
            for(int step=0;step<400;step++) {
                var before=host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,before.state().status(),before.state().diagnostic().toString());
                if(before.state().status()==FlowRuntime.Status.COMPLETE) {
                    assertEquals(expected,program.displayLocals(before.state().assembly().orElseThrow()).field("answer"));
                    completed=true;break;
                }
                if(before.state().assembly().orElseThrow().comparison().isPresent()) sawComparison=true;
                host.tick(limits,true);assertEquals(before,host.flowSnapshot().orElseThrow());
                host.step(limits,true);
                var checkpoint=host.flowSnapshot().orElseThrow();var raw=host.write();
                host=new ComputerBlockHost(()->{});host.read(raw);host.tick(limits,true);
                assertTrue(host.loaded(),host.diagnostic().toString());assertEquals(checkpoint,host.flowSnapshot().orElseThrow());
            }
            assertTrue(completed);assertTrue(sawComparison,"Search must use metered typed equality");
            assertEquals(List.of(text("other"),text("job-12345678"),text("job-12345678")),items.values());
        }
    }
}
