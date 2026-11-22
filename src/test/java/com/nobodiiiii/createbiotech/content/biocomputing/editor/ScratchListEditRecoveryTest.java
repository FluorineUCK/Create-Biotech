package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScratchListEditRecoveryTest {
    private static final DataType LIST = new DataType.ListType(DataType.Scalar.INTEGER);
    private static DataValue.ListValue list(long... values) {
        return new DataValue.ListValue(Arrays.stream(values).mapToObj(n -> (DataValue)new DataValue.IntegerValue(n)).toList());
    }
    private static ComputationGraph graph(ListOperation operation) {
        var nodes = new LinkedHashMap<String,ComputationGraph.Node>();
        var arguments = new LinkedHashMap<String,String>();
        operation.inputs().keySet().forEach(name -> {
            DataValue value = switch (name) {
                case "source" -> list(3, 5, 9);
                case "value" -> new DataValue.IntegerValue(7);
                case "end" -> new DataValue.IntegerValue(3);
                default -> new DataValue.IntegerValue(1);
            };
            nodes.put(name, new ComputationGraph.Constant(value)); arguments.put(name, name);
        });
        nodes.put("edit", new ComputationGraph.ListEdit(operation, arguments));
        return new ComputationGraph(Map.of(), nodes, Map.of("value", "edit"));
    }
    @ParameterizedTest @EnumSource(FlowProgram.Compilation.class)
    void directCallArgumentRestoresEveryPaidStepWithoutPublishingAPartialList(FlowProgram.Compilation policy) {
        var expected = Map.of(ListOperation.APPEND, list(3,5,9,7), ListOperation.INSERT, list(3,7,5,9),
            ListOperation.REPLACE, list(3,7,9), ListOperation.REMOVE, list(3,9), ListOperation.SLICE, list(5,9));
        for (var operation : ListOperation.values()) {
            var graph = graph(operation);
            if (operation == ListOperation.APPEND) {
                assertEquals(23, GraphArchiveCodec.encodingVersion(List.of(graph)));
                assertThrows(ArchiveException.class, () -> GraphArchiveCodec.decode(GraphArchiveCodec.encode(graph), 22));
            } else assertEquals(13, GraphArchiveCodec.encodingVersion(List.of(graph)));
            var key = new ModuleDefinition.Key("copy_list",1);
            var call = FlowInlineValues.operands(new FlowProgram.Invoke("call", key,
                new FlowCallBindings(Map.of(),Map.of("result","answer"))),"call",Map.of());
            var procedure = new FlowProgram.Procedure("body",key,Map.of("value",LIST),Map.of("result",LIST),Map.of("result","value"));
            var canvas = new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),List.of(new FlowBlockLayout.Stack(
                new ProgramDocument.Position(400,16), List.of(procedure,new FlowProgram.EndProcedure("end")))));
            var workspace = new ProgramWorkspace();
            workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(call),new DataValue.RecordValue(Map.of()),
                canvas,Map.of("call",graph),FlowProgram.Execution.REGISTER_V1,policy)));
            var document = workspace.snapshot();
            assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
            var program = document.flow().orElseThrow().program(document.archive());
            assertTrue(program.usesConstructionAssembly());
            assertTrue(program.usesBoundedAssemblyRestore());
            var limits = StockApplicationDriverDocuments.hardware();
            var host = new ComputerBlockHost(()->{});
            host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            boolean completed = false, sawConstruction = false;
            for (int step=0;step<200;step++) {
                var before = host.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,before.state().status(),before.state().diagnostic().toString());
                if (before.state().status()==FlowRuntime.Status.COMPLETE) {
                    assertEquals(expected.get(operation),program.displayLocals(before.state().assembly().orElseThrow()).field("answer"));
                    completed=true;break;
                }
                if (before.state().assembly().orElseThrow().build().isPresent()) sawConstruction=true;
                host.tick(limits,true);
                assertEquals(before,host.flowSnapshot().orElseThrow(),"Paused ticks cannot advance copying");
                host.step(limits,true);
                var checkpoint=host.flowSnapshot().orElseThrow();var saved=host.write();
                host=new ComputerBlockHost(()->{});host.read(saved);host.tick(limits,true);
                assertTrue(host.loaded(),host.diagnostic().toString());
                assertEquals(checkpoint,host.flowSnapshot().orElseThrow(),operation+" "+policy+" step "+step);
            }
            assertTrue(completed,operation+" "+policy);
            assertTrue(sawConstruction,operation+" must use bounded copying");
        }
    }
}
