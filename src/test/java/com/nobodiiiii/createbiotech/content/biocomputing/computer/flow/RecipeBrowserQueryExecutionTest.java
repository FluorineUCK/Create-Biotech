package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static org.junit.jupiter.api.Assertions.*;

class RecipeBrowserQueryExecutionTest {
    static final ComputerHostLimits LIMIT = NativeLibraryOwnersTest.limits("cleric",5);
    static final DataType.RecordType ENVELOPE = new DataType.RecordType(Map.of("value",ANY));
    static final DataType.RecordType QUERY = new DataType.RecordType(Map.of("k",TEXT,"i",INTEGER,"q",TEXT));
    static final DataType.RecordType REPLY = new DataType.RecordType(Map.of("r",QUERY,"n",INTEGER,"c",TEXT,"v",ANY));

    static ComputerBlockHost start(FlowProgram p, DataValue.RecordValue inputs) {
        var h = new ComputerBlockHost(()->{});
        h.installFlow(p,inputs,LIMIT,true);
        h.setPaused(false,LIMIT,true);
        return h;
    }
    static ComputerBlockHost restore(ComputerBlockHost h) {
        h.setPaused(true,LIMIT,true);
        var save=h.write();
        var next=new ComputerBlockHost(()->{});
        next.read(save); next.tick(LIMIT,true);
        assertTrue(next.loaded(),next.diagnostic().toString());
        assertEquals(save,next.write());
        next.setPaused(false,LIMIT,true);
        return next;
    }

    @Test void zeroPagesAnswersEndWithoutAReaderAndNegativeCountDoesNotOpenTheService() throws Exception {
        var doc=RecipeBrowserDocumentTest.published();
        var body=doc.flow().orElseThrow();
        for(int count:new int[]{-1,0}){
            var worker=start(body.program(doc.archive()),record("poll_ticks",1,"page_count",count));
            var request=record("q","","k","empty","i",0);
            var client=start(new FlowProgram(Map.of("request",QUERY),List.of(
                new FlowProgram.Write("ask","x0","request"),new FlowProgram.Read("read","x0","reply",REPLY))).withAssembly(),
                record("request",request));
            int crossings=0;
            for(int tick=0;tick<2000;tick++){
                client.tick(LIMIT,true);
                worker.tick(LIMIT,true,Map.of("x1",NativeLibraryStartupTest.status(worker,"x1",LIMIT,client,"x0",LIMIT)));
                if(worker.exchange("x1",LIMIT,true,client,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED)crossings++;
                if(count<0 && worker.flowSnapshot().orElseThrow().state().locals().fields().containsKey("error")
                    || client.flowSnapshot().orElseThrow().state().locals().fields().containsKey("reply")) break;
            }
            if(count<0){
                assertEquals(0,crossings);
                assertEquals(new DataValue.TextValue("invalid_page_count"),worker.flowSnapshot().orElseThrow().state().locals().field("error"));
            } else {
                assertEquals(2,crossings);
                assertEquals(record("c","end","n",0,"r",request,"v",null),client.flowSnapshot().orElseThrow().state().locals().field("reply"));
            }
        }
    }

    @Test void twoRomPagesCanBeSearchedRepeatedlyOverTheSamePinsAfterReload() throws Exception {
        var first=FoundationRecipeTest.projection(false);
        var second=FoundationRecipeTest.projection(true);
        var outputs=new ArrayList<>(((DataValue.ListValue)second.field("outputs")).values());
        outputs.add(record("item","minecraft:diamond","count",1,"components",((DataValue.RecordValue)outputs.getFirst()).field("components")));
        second=RecipePreviewExecutionTest.patch(second,"outputs",new DataValue.ListValue(outputs));
        second=RecipePreviewExecutionTest.patch(second,"pattern_id",new DataValue.TextValue("11111111-0000-0000-0000-000000000001"));
        second=RecipePreviewExecutionTest.patch(second,"page",new DataValue.IntegerValue(1));
        second=RecipePreviewExecutionTest.patch(second,"pages",new DataValue.IntegerValue(2));
        first=RecipePreviewExecutionTest.patch(first,"pages",new DataValue.IntegerValue(2));
        var source=start(new FlowProgram(Map.of("a",ENVELOPE,"b",ENVELOPE),List.of(
            new FlowProgram.Write("first","x0","a"),new FlowProgram.Write("second","x0","b"))).withAssembly(),
            record("a",record("value",first),"b",record("value",second)));

        var doc=RecipeBrowserDocumentTest.published();
        var body=doc.flow().orElseThrow();
        var worker=start(body.program(doc.archive()),body.initial());
        String[] words={"dIaMoNd","","IRON","","unmatched","11111111","x","x"};
        int[] cursor={0,0,1,2,0,0,-1,3};
        var steps=new ArrayList<FlowProgram.Step>(); var args=new LinkedHashMap<String,DataType>();
        var input=new LinkedHashMap<String,DataValue>();
        for(int i=0;i<words.length;i++){
            var k="q"+i; args.put(k,QUERY);
            input.put(k,record("q",words[i],"k",k,"i",cursor[i]));
            steps.add(new FlowProgram.Write("ask"+i,"x0",k)); steps.add(new FlowProgram.Read("take"+i,"x0","r"+i,REPLY));
        }
        var client=start(new FlowProgram(args,steps).withAssembly(),new DataValue.RecordValue(input));
        int pages=0, crossings=0; boolean reloaded=false;
        for (int tick=0;tick<16000;tick++){
            source.tick(LIMIT,true);client.tick(LIMIT,true);
            worker.tick(LIMIT,true,Map.of("x0",NativeLibraryStartupTest.status(worker,"x0",LIMIT,source,"x0",LIMIT),
                "x1",NativeLibraryStartupTest.status(worker,"x1",LIMIT,client,"x0",LIMIT)));
            if(source.exchange("x0",LIMIT,true,worker,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED) pages++;
            if(worker.exchange("x1",LIMIT,true,client,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED) crossings++;
            for(var h:List.of(source,worker,client)){
                var state=h.flowSnapshot().orElseThrow().state();
                assertNotEquals(FlowRuntime.Status.FAILED,state.status(),()->state.diagnostic().toString());
            }
            if(!reloaded && crossings==3){worker=restore(worker);client=restore(client);reloaded=true;}
            if(client.flowSnapshot().orElseThrow().state().locals().fields().containsKey("r7")) break;
        }
        assertTrue(reloaded);assertEquals(2,pages);assertEquals(words.length*2,crossings);
        var results=client.flowSnapshot().orElseThrow().state().locals();
        var expected=Arrays.asList(second,first,second,null,null,second,null,null);
        int[] next={2,1,2,2,2,2,-1,3};
        for(int i=0;i<words.length;i++)assertEquals(record("c",i>=6?"invalid_cursor":expected.get(i)==null?"end":"match",
            "n",next[i],"r",input.get("q"+i),"v",expected.get(i)),results.field("r"+i));
    }
}
