package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.RecipeBrowserQueryExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeBookStreamExecutionTest {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void onlyFinishedScanCanReturnRowsEvenWhenTheTerminalAsksEarly(boolean complete)throws Exception {
        var first=RecipePreviewExecutionTest.patch(FoundationRecipeTest.projection(false),"pages",new DataValue.IntegerValue(2));
        first=RecipePreviewExecutionTest.patch(first,"page",new DataValue.IntegerValue(0));
        var second=RecipePreviewExecutionTest.patch(first,"pattern_id",new DataValue.TextValue("second"));
        second=RecipePreviewExecutionTest.patch(second,"page",new DataValue.IntegerValue(1));
        var result=new DataType.RecordType(Map.of("ok",BOOLEAN,"value",ANY));
        var command=new DataType.RecordType(Map.of("n",INTEGER,"c",TEXT));
        var steps=new ArrayList<FlowProgram.Step>();
        for(String id:List.of("a","b")){
            steps.add(new FlowProgram.SignalWrite("arm"+id,"p0","high"));
            steps.add(new FlowProgram.Write("pub"+id,"x0",id));
            steps.add(new FlowProgram.SignalWrite("release"+id,"p0","zero"));
            steps.add(new FlowProgram.Read("confirm"+id,"x0","ack"+id,command));
        }
        var source=start(new FlowProgram(Map.of("a",result,"b",result,"high",INTEGER,"zero",INTEGER),steps).withAssembly(),
            record("a",record("ok",true,"value",first),"b",record("ok",complete,"value",complete?second:record("code","changed")),"high",15,"zero",0));
        var relayDoc=RecipeBrowserDocumentTest.published("recipe_book_gate");var relayBody=relayDoc.flow().orElseThrow();
        var relay=start(relayBody.program(relayDoc.archive()),relayBody.initial());
        var doc=RecipeBrowserDocumentTest.published("recipe_book_query");
        var body=doc.flow().orElseThrow();var worker=start(body.program(doc.archive()),body.initial());
        var req=record("q",complete?"SECOND":"","k","same-search","i",0);
        var client=start(new FlowProgram(Map.of("req",QUERY),List.of(new FlowProgram.Write("ask","x0","req"),
            new FlowProgram.Read("reply","x0","reply",REPLY))).withAssembly(),record("req",req));
        int crossed=0;boolean restored=false;
        for(int tick=0;tick<8000;tick++){
            int signal=source.flowSnapshot().orElseThrow().state().signals().outputs().getOrDefault("p0",0);
            source.tick(LIMIT,true);client.tick(LIMIT,true);
            relay.tick(LIMIT,true,Map.of("p0",signal));
            worker.tick(LIMIT,true);
            if(source.exchange("x0",LIMIT,true,relay,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED)crossed++;
            relay.exchange("x1",LIMIT,true,worker,"x0",LIMIT,true);
            var transfer=worker.exchange("x1",LIMIT,true,client,"x0",LIMIT,true);
            if(crossed<3)assertNotEquals(XBusExchange.Status.EXCHANGED,transfer,"No partial query before final release");
            if(!restored && crossed==1){relay=restore(relay);worker=restore(worker);restored=true;}
            var state=worker.flowSnapshot().orElseThrow().state();
            assertNotEquals(FlowRuntime.Status.FAILED,state.status(),()->state.diagnostic().toString());
            var locals=client.flowSnapshot().orElseThrow().state().locals();
            if(locals.fields().containsKey("reply") && source.flowSnapshot().orElseThrow().state().locals().fields().containsKey("ackb")){
                var pub=source.flowSnapshot().orElseThrow().state().locals();
                assertEquals(record("n",1,"c",""),pub.field("acka"));
                assertEquals(record("n",-1,"c",complete?"":"changed"),pub.field("ackb"));
                assertEquals(record("c",complete?"match":"changed","n",complete?2:0,"r",req,"v",complete?second:null),locals.field("reply"));
                assertTrue(restored);return;
            }
        }
        fail("Did not finish both rendezvous: "+crossed);
    }
}
