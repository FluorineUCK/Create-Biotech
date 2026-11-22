package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListPositionBoundaryTest {
    private static FlowProgram program(ComputationGraph graph,boolean isa) {
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),
            List.of(),List.of(),Map.of("search",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var result=new FlowProgram(Map.of(),List.of(new FlowProgram.Evaluate("search","search",
            new FlowCallBindings(Map.of(),Map.of("value","answer")))),archive);
        return isa?result.withAssembly():result;
    }

    private static ComputationGraph graph(boolean insertion,ComputationGraph.ListPosition.Choice choice) {
        return new ComputationGraph(Map.of(),Map.of("items",new ComputationGraph.Constant(new DataValue.ListValue(List.of())),
            "position",new ComputationGraph.ListPosition("items",choice,insertion)),Map.of("value","position"));
    }
    @Test void emptyListInsertionHasOnePositionButEmptyRandomReadFailsExplicitly() {
        for(var choice:ComputationGraph.ListPosition.Choice.values())for(boolean insertion:List.of(false,true)) {
            var host=new ComputerBlockHost(()->{});var limits=StockApplicationDriverDocuments.hardware();
            host.installFlow(program(graph(insertion,choice),true),new DataValue.RecordValue(Map.of()),limits,true);
            for(int i=0;i<100 && host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;i++)host.step(limits,true);
            var state=host.flowSnapshot().orElseThrow().state();
            if(!insertion && choice==ComputationGraph.ListPosition.Choice.RANDOM) {
                assertEquals(FlowRuntime.Status.FAILED,state.status());
                assertEquals(ComputationException.Code.INVALID_ARGUMENT,state.diagnostic().orElseThrow().code());
            } else {
                assertEquals(FlowRuntime.Status.COMPLETE,state.status(),state.diagnostic().toString());
                assertEquals(new DataValue.IntegerValue(insertion?1:0),state.locals().field("answer"));
            }
        }
    }
    @Test void specialPositionsHaveAnArchiveBoundaryAndCannotFallBackToGraphExecution() {
        var graph=graph(true,ComputationGraph.ListPosition.Choice.RANDOM);var limits=new ResourceLimits(512,100000,1);
        assertEquals(ComputationException.Code.INVALID_GRAPH,assertThrows(ComputationException.class,
            ()->ProgramCompiler.compile(new ProgramDefinition(graph,Map.of(),List.of()),Map.of(),limits)).code());
        assertEquals(ComputationException.Code.INVALID_GRAPH,assertThrows(ComputationException.class,
            ()->new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),program(graph,false),new DataValue.RecordValue(Map.of()),limits)).code());
        var archive=program(graph,true).expressions();var encoded=ProgramArchiveCodec.encode(archive);
        assertEquals(new DataValue.IntegerValue(27),encoded.field("version"));assertEquals(archive,ProgramArchiveCodec.decode(encoded));
        var older=new LinkedHashMap<>(encoded.fields());older.put("version",new DataValue.IntegerValue(26));
        assertThrows(ArchiveException.class,()->ProgramArchiveCodec.decode(new DataValue.RecordValue(older)));
    }
    @Test void selectorSwitchingKeepsNumericShadowAndDoesNotCreateACycleWhenWrapped() {
        var bindings=new FlowCallBindings(Map.of(),Map.of());
        var list=new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.ListValue(List.of(
            new DataValue.IntegerValue(4),new DataValue.IntegerValue(8))))),Map.of("value","value"));
        var get=InlineGraphEdits.replace(list,bindings,"value",new InlineGraphEdits.ListItem());
        String field=ScratchListPositions.sockets(get.graph()).keySet().iterator().next();
        var chosen=ScratchListPositions.choose(get.graph(),get.bindings(),Map.of(),field,ComputationGraph.ListPosition.Choice.RANDOM);
        assertDoesNotThrow(()->FlowReporterEdits.requireUnshared(chosen.graph(),field));
        var number=ScratchListPositions.choose(chosen.graph(),chosen.bindings(),chosen.shadows(),field,null);
        assertEquals(get.graph(),number.graph());assertTrue(number.shadows().isEmpty());
        var wrapped=ScratchListPositions.placeReporter(chosen.graph(),chosen.bindings(),chosen.shadows(),field,
            new InlineGraphEdits.Operator(ScalarOperation.ADD_INTEGER));
        assertTrue(wrapped.graph().nodes().values().stream().noneMatch(ComputationGraph.ListPosition.class::isInstance));
        assertDoesNotThrow(()->ProgramCompiler.compileFlow(program(wrapped.graph(),true).expressions().program(),Map.of(),
            new ResourceLimits(512,100000,1),Map.of()));
    }
}
