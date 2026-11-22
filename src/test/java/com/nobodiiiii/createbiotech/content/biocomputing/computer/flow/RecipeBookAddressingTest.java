package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.XBusAccess;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.RecipeBrowserQueryExecutionTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public programs; only the previously tested positioning adapter and verified byte collector are fixtures. */
class RecipeBookAddressingTest {
    static final DataType.RecordType CTX=new DataType.RecordType(Map.of("r",ROW,"s",ANY,"d",ANY));
    static ComputerBlockHost installed(String id)throws Exception {
        var d=RecipeBrowserDocumentTest.published("recipe_book_"+id);var b=d.flow().orElseThrow();return start(b.program(d.archive()),b.initial());
    }
    static DataValue.RecordValue closedPage(DataValue.RecordValue page) {
        return record("ok",true,"value",record("k","recipe_page","v",null,
            "p",new DataValue.ListValue(List.of(ValueDecodingOwnerTest.wire(page)))));
    }
    @Test void advancesAddressOnlyAfterCloseAndNeverDispatchesAPageToTheDecoder()throws Exception {
        var read=installed("read");var decode=installed("decode");var gate=installed("gate");var query=installed("query");
        var first=RecipePreviewExecutionTest.patch(FoundationRecipeTest.projection(false),"pages",new DataValue.IntegerValue(2));
        first=RecipePreviewExecutionTest.patch(first,"page",new DataValue.IntegerValue(0));
        var last=RecipePreviewExecutionTest.patch(first,"page",new DataValue.IntegerValue(1));
        last=RecipePreviewExecutionTest.patch(last,"pattern_id",new DataValue.TextValue("find-second"));
        var collector=start(new FlowProgram(Map.of("a",OUTCOME,"b",OUTCOME),List.of(
            new FlowProgram.Read("read1","x0","ctx1",CTX),new FlowProgram.Write("first","x0","a"),
            new FlowProgram.Read("read2","x0","ctx2",CTX),new FlowProgram.Write("second","x0","b"))).withAssembly(),
            record("a",closedPage(first),"b",closedPage(last)));
        var ops=new ArrayList<FlowProgram.Step>();
        var expected=List.of("status","seek","result","ack","select","result","ack","recipe_page_begin","read_info","read_close",
            "recipe_page_begin","read_info","read_close");
        for(int i=0;i<expected.size();i++){
            ops.add(new FlowProgram.Read("take"+i,"x0","seen"+i,CTX));
            ops.add(new FlowProgram.Write("reply"+i,"x0","response"));
        }
        var adapter=start(new FlowProgram(Map.of("response",OUTCOME),ops).withAssembly(),
            record("response",record("ok",true,"value",record("sequence",1,"value",null))));
        var req=record("q","SECOND","k","find","i",0);
        var client=start(new FlowProgram(Map.of("req",QUERY),List.of(new FlowProgram.Write("send","x0","req"),
            new FlowProgram.Read("take","x0","answer",REPLY))).withAssembly(),record("req",req));
        boolean recovered=false;
        for(int tick=0;tick<30000;tick++){
            int signal=read.flowSnapshot().orElseThrow().state().signals().outputs().getOrDefault("p0",0);
            read.tick(LIMIT,true);decode.tick(LIMIT,true,Map.of("p0",signal));gate.tick(LIMIT,true,Map.of("p0",signal));
            query.tick(LIMIT,true);client.tick(LIMIT,true);collector.tick(LIMIT,true);adapter.tick(LIMIT,true);
            read.exchange("x0",LIMIT,true,adapter,"x0",LIMIT,true);
            var bus=XBusAccess.network(List.of(NativeLibraryStartupTest.access(read,"x1",LIMIT),
                NativeLibraryStartupTest.access(decode,"x0",LIMIT),NativeLibraryStartupTest.access(gate,"x0",LIMIT)));
            bus.exchange(NativeLibraryStartupTest.pin(read,"x1"),NativeLibraryStartupTest.pin(decode,"x0"));
            bus.exchange(NativeLibraryStartupTest.pin(read,"x1"),NativeLibraryStartupTest.pin(gate,"x0"));
            gate.exchange("x1",LIMIT,true,query,"x0",LIMIT,true);query.exchange("x1",LIMIT,true,client,"x0",LIMIT,true);
            decode.exchange("x1",LIMIT,true,collector,"x0",LIMIT,true);
            if(!recovered && adapter.flowSnapshot().orElseThrow().state().locals().fields().containsKey("seen10")){
                read=restore(read);decode=restore(decode);gate=restore(gate);query=restore(query);recovered=true;
            }
            for(var h:List.of(read,decode,gate,query,adapter,collector,client)){
                var s=h.flowSnapshot().orElseThrow();assertNotEquals(FlowRuntime.Status.FAILED,s.state().status(),()->s.state().diagnostic().toString());
                for(var t:s.tasks().contexts())assertNotEquals(FlowRuntime.Status.FAILED,t.status(),()->t.diagnostic().toString());
            }
            var answer=client.flowSnapshot().orElseThrow().state().locals();
            if(!answer.fields().containsKey("answer")||read.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE)continue;
            assertEquals(record("c","match","n",2,"r",req,"v",last),answer.field("answer"));assertTrue(recovered);
            var seen=adapter.flowSnapshot().orElseThrow().state().locals();
            for(int i=0;i<expected.size();i++){
                var ctx=(DataValue.RecordValue)seen.field("seen"+i);
                assertEquals(new DataValue.TextValue(expected.get(i)),((DataValue.RecordValue)ctx.field("r")).field("o"));
                if(i==7||i==10)assertEquals(new DataValue.IntegerValue(i==7?0:1),ctx.field("d"));
            }
            return;
        }
        fail("Positioning or shared-pin rendezvous did not finish");
    }
}
