package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListSearchBoundaryTest {
    private static DataValue.IntegerValue integer(long n) { return new DataValue.IntegerValue(n); }
    private static DataValue.TextValue text(String s) { return new DataValue.TextValue(s); }
    private static FlowProgram program(ComputationGraph graph,boolean isa) {
        var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),
            List.of(),List.of(),Map.of("search",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
        var result=new FlowProgram(Map.of(),List.of(new FlowProgram.Evaluate("search","search",
            new FlowCallBindings(Map.of(),Map.of("value","answer")))),archive);
        return isa?result.withAssembly():result;
    }
    @Test void emptyMissAndNativeTypedComparisonsDoNotCoerceOrMutateTheSource() {
        var tag=new DataValue.NbtValue(3,integer(1));
        var record=new DataValue.RecordValue(Map.of("tag",tag,"quantity",integer(2)));
        var items=new DataValue.ListValue(List.of(integer(1),text("1"),tag,record,record));
        for(var example:List.of(Map.entry(integer(1),1),Map.entry(text("1"),2),Map.entry(tag,3),
                Map.entry(record,4),Map.entry(new DataValue.NbtValue(4,integer(1)),0),Map.entry(text("absent"),0))) {
            assertEquals(integer(example.getValue()),execute(items,example.getKey(),false));
            assertEquals(new DataValue.BooleanValue(example.getValue()!=0),execute(items,example.getKey(),true));
        }
        assertEquals(integer(0),execute(new DataValue.ListValue(List.of()),integer(1),false));
        assertEquals(new DataValue.BooleanValue(false),execute(new DataValue.ListValue(List.of()),integer(1),true));
        assertEquals(List.of(integer(1),text("1"),tag,record,record),items.values());
    }
    private static DataValue execute(DataValue.ListValue list,DataValue value,boolean contains) {
        var p=program(ScratchListSearchRecoveryTest.graph(list,value,contains),true);
        var limits=StockApplicationDriverDocuments.hardware();
        var host=new ComputerBlockHost(()->{});
        host.installFlow(p,new DataValue.RecordValue(Map.of()),limits,true);
        for(int step=0;step<700 && host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;step++)
            host.step(limits,true);
        var state=host.flowSnapshot().orElseThrow().state();
        assertEquals(FlowRuntime.Status.COMPLETE,state.status(),state.diagnostic().toString());
        return state.locals().field("answer");
    }
    @Test void searchCannotFallBackToTheGraphInterpreterOrBypassItsArchiveSchema() {
        var graph=ScratchListSearchRecoveryTest.graph(new DataValue.ListValue(List.of(integer(1))),integer(1),false);
        var limits=new ResourceLimits(512,100000,1);
        assertEquals(ComputationException.Code.INVALID_GRAPH,assertThrows(ComputationException.class,
            ()->ProgramCompiler.compile(new ProgramDefinition(graph,Map.of(),List.of()),Map.of(),limits)).code());
        assertEquals(ComputationException.Code.INVALID_GRAPH,assertThrows(ComputationException.class,
            ()->new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),program(graph,false),
                new DataValue.RecordValue(Map.of()),limits)).code());
        var archive=program(graph,true).expressions();
        var encoded=ProgramArchiveCodec.encode(archive);
        assertEquals(integer(26),encoded.field("version"));
        assertEquals(archive,ProgramArchiveCodec.decode(encoded));
        var older=new LinkedHashMap<>(encoded.fields());older.put("version",integer(25));
        assertThrows(ArchiveException.class,()->ProgramArchiveCodec.decode(new DataValue.RecordValue(older)));
    }
    @Test void booleanShapeTypeHintsAndPaletteAvailabilityMatchTheResultContract() {
        for(boolean contains:List.of(false,true)) {
            var source=new InlineGraphEdits.ListSearch(contains);
            assertTrue(InlineGraphEdits.palette(Map.of()).contains(source));
            var hints=FlowTypeDetails.source(source);
            assertEquals(contains?DataType.Scalar.BOOLEAN:DataType.Scalar.INTEGER,hints.getFirst().type());
            var graph=ScratchListSearchRecoveryTest.graph(new DataValue.ListValue(List.of(integer(1))),integer(1),contains);
            assertEquals(contains,InlineGraphView.layout(graph,String::length).bool());
            var rebound=NodePorts.bind(graph.nodes().get("find"),Map.of("source","items","value","needle"));
            assertEquals(graph.nodes().get("find"),rebound);
        }
    }
}
