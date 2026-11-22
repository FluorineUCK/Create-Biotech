package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NodeTypeHintsTest {
    @Test void incompleteAndInvalidIslandsDoNotHideIndependentTypesOrBecomeAny() {
        var workspace=new ProgramWorkspace(); var draft=workspace.root();
        String number=draft.add(ProgramDraftTest.number(3),0,0);
        String unfinished=draft.add(null,0,0);
        String invalid=draft.add(new ComputationGraph.Field("source","missing"),0,0); draft.connect(invalid,"source",number);
        String downstream=draft.add(new ComputationGraph.Index("source",0),0,0); draft.connect(downstream,"source",invalid);
        draft.putInput("dynamic",DataType.Scalar.ANY); String dynamic=draft.add(new ComputationGraph.Input("dynamic"),0,0);
        draft.putCell("count",DataType.Scalar.INTEGER,new DataValue.IntegerValue(0)); String memory=draft.add(new ComputationGraph.ReadMemory("count"),0,0);
        var types=new NodeTypeHints(workspace,draft,false,null);
        assertEquals(DataType.Scalar.INTEGER,types.output(number)); assertEquals(DataType.Scalar.INTEGER,types.output(memory));
        assertNull(types.output(unfinished)); assertNull(types.output(invalid)); assertNull(types.output(downstream));
        assertEquals(DataType.Scalar.ANY,types.output(dynamic));
        assertNull(new NodeTypeHints(workspace,draft,true,null).output(memory));
        assertNotEquals(ComputerGraphPorts.color(null),ComputerGraphPorts.color(DataType.Scalar.ANY));
        assertEquals("?",ComputerGraphPorts.typeLabel(null)); assertEquals("ANY",ComputerGraphPorts.typeLabel(DataType.Scalar.ANY));
    }

    @Test void compositeConnectionsUseCompilerRulesWithoutEvaluatingOrInstalling() {
        var workspace=new ProgramWorkspace(); var draft=workspace.root();
        var value=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(9)))));
        String record=draft.add(new ComputationGraph.Constant(value),0,0);
        String field=draft.add(new ComputationGraph.Field("source","items"),0,0); draft.connect(field,"source",record);
        String index=draft.add(new ComputationGraph.Index("source",0),0,0); draft.connect(index,"source",field); draft.output("answer",index);
        var types=new NodeTypeHints(workspace,draft,false,null);
        var installed=draft.snapshot().compile(ProgramDraftTest.ROOM);
        for(var node:draft.nodes())assertEquals(installed.root().nodeTypes().get(node.id()),types.output(node.id()));
        assertEquals(ComputationException.Code.TYPE_MISMATCH,assertThrows(ComputationException.class,()->types.check(index,"source",record)).code());
        assertEquals(ComputationException.Code.GRAPH_CYCLE,assertThrows(ComputationException.class,()->types.check(field,"source",index)).code());
        assertEquals(Map.of("source",record),draft.node(field).connections());
        assertEquals(value,((ComputationGraph.Constant)draft.node(record).definition()).value());
    }

    @Test void exactRevisionModuleSignaturesRemainUsefulBeforeOutputInferenceIsAvailable() {
        var workspace=new ProgramWorkspace(); var key=workspace.createModule("leaf"); var child=workspace.graph(key);
        child.putInput("item",DataType.Scalar.INTEGER); child.putInput("limit",DataType.Scalar.INTEGER);
        String result=child.add(new ComputationGraph.Input("item"),0,0); child.output("value",result);
        var draft=workspace.root();
        String list=draft.add(new ComputationGraph.Constant(new DataValue.ListValue(List.of(new DataValue.IntegerValue(8)))),0,0);
        String number=draft.add(ProgramDraftTest.number(5),0,0);
        String bad=draft.add(new ComputationGraph.Constant(new DataValue.TextValue("wrong")),0,0);
        String map=draft.add(new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP,"source",key,"item",Map.of("limit","limit"),"value"),0,0);
        draft.connect(map,"source",list); draft.connect(map,"capture:limit",number); draft.output("answer",map);
        var unfinished=new NodeTypeHints(workspace,draft,false,null);
        assertNull(unfinished.output(map)); assertEquals(DataType.Scalar.INTEGER,unfinished.input(map,"capture:limit"));
        assertEquals(new DataType.ListType(DataType.Scalar.INTEGER),unfinished.input(map,"source"));
        assertEquals(ComputationException.Code.TYPE_MISMATCH,assertThrows(ComputationException.class,()->unfinished.check(map,"capture:limit",bad)).code());
        var compiled=workspace.snapshot().archive().compile(ProgramDraftTest.ROOM);
        var known=new NodeTypeHints(workspace,draft,false,compiled);
        assertEquals(compiled.root().nodeTypes().get(map),known.output(map));
        assertEquals(DataType.Scalar.INTEGER,new NodeTypeHints(workspace,child,true,compiled).output(result));
        var absent=new ModuleDefinition.Key(key.id(),key.revision()+1);
        String call=draft.add(new ComputationGraph.Call(absent,Map.of("item","item","limit","limit")),0,0);
        assertNull(new NodeTypeHints(workspace,draft,false,compiled).input(call,"item"));
    }

    @Test void allOperationHintsComeFromTheCanonicalSignaturesAndAnyRemainsDynamic() {
        var workspace=new ProgramWorkspace(); var draft=workspace.root();
        draft.putInput("dynamic",DataType.Scalar.ANY); String dynamic=draft.add(new ComputationGraph.Input("dynamic"),0,0);
        for(var operation:ScalarOperation.values()) {
            var arguments=new LinkedHashMap<String,String>(); operation.inputs().keySet().forEach(name->arguments.put(name,name));
            String id=draft.add(new ComputationGraph.Scalar(operation,arguments),0,0);
            var types=new NodeTypeHints(workspace,draft,false,null);
            operation.inputs().forEach((name,type)->{assertEquals(type,types.input(id,name));assertDoesNotThrow(()->types.check(id,name,dynamic));});
        }
        for(var operation:TextOperation.values()) {
            var arguments=new LinkedHashMap<String,String>(); operation.inputs().keySet().forEach(name->arguments.put(name,name));
            String id=draft.add(new ComputationGraph.Text(operation,arguments),0,0); var types=new NodeTypeHints(workspace,draft,false,null);
            operation.inputs().forEach((name,type)->assertEquals(type,types.input(id,name)));
        }
    }

    @Test void deepReadyChainsUseAnIterativePassRatherThanJavaRecursion() {
        var workspace=new ProgramWorkspace(); var draft=workspace.root(); String previous=draft.add(ProgramDraftTest.number(1),0,0);
        for(int i=0;i<2000;i++) {
            String next=draft.add(new ComputationGraph.Scalar(ScalarOperation.NEGATE_INTEGER,Map.of("value","value")),0,0);
            draft.connect(next,"value",previous); previous=next;
        }
        assertEquals(DataType.Scalar.INTEGER,new NodeTypeHints(workspace,draft,false,null).output(previous));
    }
}
