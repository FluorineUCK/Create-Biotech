package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

class StockRecipeOrderPreviewTest {
    @Test void frozenOrderOverridesPreviewDefaultButCannotUseAChangedPage() throws Exception {
        for (String change : List.of("none","fingerprint","page","pattern_id")) {
            var hw = StockApplicationDriverDocuments.hardware();
            var projection = new LinkedHashMap<>(FoundationRecipeTest.projection(true).fields());
            projection.put("pages",new DataValue.IntegerValue(2));
            var page = new DataValue.RecordValue(projection);
            var app = new StockApplication.Application(UUID.randomUUID(),UUID.randomUUID());
            var access = new StockApplication.Access(app,true,false);
            var limits = new StockApplication.Limits(8,8,65536,256,25);
            var state = StockApplication.bind(StockApplication.closed(UUID.randomUUID(),UUID.randomUUID()),app,UUID.randomUUID()).state();
            var id = ((DataValue.TextValue)page.field("pattern_id")).value();
            var entry = new StockApplication.Entry(id,"title","batch",
                (DataValue.RecordValue)((DataValue.ListValue)page.field("outputs")).values().getFirst(),record("page",page.field("page"),"fingerprint",page.field("fingerprint")));
            state = StockApplication.replace(state,access,state.fence(),List.of(entry),limits).state();
            var queued = StockApplication.confirm(state,access,UUID.randomUUID(), new StockApplication.Request(state.fence(),
                state.nextSequence(),List.of(new StockApplication.Selection(id,4)),"shop"),limits);
            var reply = StockApplicationConnectorAdmissionTest.readerFrame(queued.state(),queued.input().orElseThrow());

            var document = StockApplicationDriverDocuments.build("recipe_order");
            if (change.equals("none")) System.out.println("Matched order hardware " + document.flow().orElseThrow().program(document.archive()).usage(new ResourceLimits(4096,65536,8)));
            var selectorDoc = StockApplicationDriverDocuments.build("recipe_selection");
            if (change.equals("none")) System.out.println("Frozen selection hardware " + selectorDoc.flow().orElseThrow().program(selectorDoc.archive()).usage(new ResourceLimits(4096,65536,8)));
            var selector=installed(selectorDoc); var join = installed(document);
            var v = installed(RecipePreviewDocumentsTest.published("v"));
            var p = installed(RecipePreviewDocumentsTest.published("p"));
            var s = installed(RecipePreviewDocumentsTest.published("s"));
            var sample = installed(StockApplicationDriverDocuments.build("recipe_sample"));
            var stock = stockBus(reply);
            var fields = new LinkedHashMap<>(page.fields());
            if (!change.equals("none")) fields.put(change,switch(change){
                case "page" -> new DataValue.IntegerValue(1);
                case "pattern_id" -> new DataValue.TextValue("00000000-0000-0000-0000-000000000001");
                default -> new DataValue.TextValue("b".repeat(64));
            });
            var book = sender("value",new DataValue.RecordValue(fields));
            var all = List.of(join,v,p,s,sample,book,selector,stock);
            for (var host : all) host.setPaused(false,hw,true);
            boolean pageStarted = false;
            for (int tick=1; tick<2500; tick++) {
                if (join.flowSnapshot().orElseThrow().state().signals().outputs().getOrDefault("p0",0)==15) pageStarted=true;
                if (pageStarted) {
                    book.tick(tick,hw,true,Map.of()); v.tick(tick,hw,true,Map.of("x0",busy(book)));
                    book.exchange("x0",hw,true,v,"x0",hw,true);
                    join.exchange("x0",hw,true,v,"x1",hw,true);
                }
                stock.tick(tick,hw,true,Map.of());
                sample.tick(tick,hw,true,Map.of());sample.exchange("x0",hw,true,stock,"x0",hw,true);
                join.tick(tick,hw,true,Map.of());
                p.tick(tick,hw,true,Map.of("x0",busy(join),"x1",busy(s)));
                s.tick(tick,hw,true,Map.of("x0",busy(p)));
                selector.tick(tick,hw,true,Map.of()); selector.exchange("x0",hw,true,sample,"x1",hw,true);
                join.exchange("x0",hw,true,selector,"x1",hw,true);
                join.exchange("x1",hw,true,p,"x0",hw,true); p.exchange("x1",hw,true,s,"x0",hw,true);
                for (var h:all) assertNotEquals(FlowRuntime.Status.FAILED,h.flowSnapshot().orElseThrow().state().status(),
                    ()->h.flowSnapshot().orElseThrow().state().diagnostic().toString());
                if (change.equals("none") ? complete(p) : complete(join)) break;
            }
            var requests=stock.flowSnapshot().orElseThrow().state().locals();
            for (var op:List.of("status","sample")){
                var call=(DataValue.RecordValue)requests.field(op);
                assertEquals(new DataValue.TextValue(op),call.field("o"));
                assertEquals(new DataValue.BooleanValue(true),call.field("k"),"Only the Reader is addressed");
            }
            assertTrue(complete(join));
            var result=(DataValue.RecordValue)join.flowSnapshot().orElseThrow().state().locals().field("reply");
            if (!change.equals("none")) {
                assertEquals(new DataValue.TextValue("recipe_changed"),result.field("code"));
                assertFalse(complete(p)); continue;
            }
            assertEquals(new DataValue.TextValue("matched"),result.field("code"));
            var source=(DataValue.RecordValue)result.field("source");
            var head=(DataValue.RecordValue)source.field("head");
            assertEquals(new DataValue.TextValue("shop"),head.field("address"));
            assertEquals(new DataValue.IntegerValue(queued.input().orElseThrow().sequence()),head.field("sequence"));
            assertTrue(complete(p));
            var preview=(DataValue.RecordValue)((DataValue.RecordValue)p.flowSnapshot().orElseThrow().state().locals().field("result")).field("value");
            assertEquals(new DataValue.IntegerValue(4),preview.field("n"));
            var first=(DataValue.RecordValue)((DataValue.ListValue)preview.field("c")).values().getFirst();
            assertEquals(new DataValue.IntegerValue(8),first.field("count"));
            assertEquals(((DataValue.RecordValue)((DataValue.ListValue)page.field("inputs")).values().getFirst()).field("components"),first.field("components"));
            assertEquals(1,((DataValue.ListValue)preview.field("s")).values().size());
        }
    }
    @Test void emptyNativeAndMultipleSelectionsStopBeforeAnyRecipeReadOrAck() throws Exception {
        var hw=StockApplicationDriverDocuments.hardware();
        for (var code:List.of("empty","not_catalog_order","select_one_recipe")) {
            var stock=switch(code) {
                case "empty" -> record("state",record());
                case "not_catalog_order" -> record("head",record("kind","native_order","order",record()));
                default -> record("head",record("items",new DataValue.ListValue(List.of(record(),record()))));
            };
            var selector=installed(StockApplicationDriverDocuments.build("recipe_selection"));
            var sample=sender("v",record("value",record("components",record("create_biotech:stock",stock))));
            selector.setPaused(false,hw,true);sample.setPaused(false,hw,true);
            for(int t=1;t<160 && !complete(selector);t++) {
                selector.tick(t,hw,true,Map.of());sample.tick(t,hw,true,Map.of());
                selector.exchange("x0",hw,true,sample,"x0",hw,true);
            }
            assertTrue(complete(selector),"No matcher or dispatcher is connected: "+code);
            assertEquals(record("code",code),selector.flowSnapshot().orElseThrow().state().locals().field("reply"));
        }
    }
    static boolean complete(ComputerBlockHost h){return h.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE;}
    static int busy(ComputerBlockHost h){return h.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.WAITING_WRITE?15:0;}
    static ComputerBlockHost installed(ProgramDocument d) {
        var hw=StockApplicationDriverDocuments.hardware();var h=new ComputerBlockHost(()->{});
        h.installProgram(PreparedProgram.prepare(d,hw),ComputerRuntime.MemoryPolicy.RESET,hw,true);return h;
    }
    private static ComputerBlockHost stockBus(DataValue reply) {
        var hw=StockApplicationDriverDocuments.hardware();var h=new ComputerBlockHost(()->{});
        var any=DataType.Scalar.ANY;var i=DataType.Scalar.INTEGER;
        var target=new DataType.RecordType(Map.of("x",i,"y",i,"z",i));
        var call=new DataType.RecordType(Map.of("t",target,"k",DataType.Scalar.BOOLEAN,"o",DataType.Scalar.TEXT,"a",any));
        var response=new DataType.RecordType(Map.of("r",any));
        h.installFlow(new FlowProgram(Map.of("one",response,"two",response),List.of(
            new FlowProgram.Read("s","x0","status",call),new FlowProgram.Write("r","x0","one"),
            new FlowProgram.Read("q","x0","sample",call),new FlowProgram.Write("t","x0","two"))),
            record("one",record("r",record("state",record("device","reader","session","current"))),"two",record("r",reply)),hw,true);
        return h;
    }
    static ComputerBlockHost sender(String key,DataValue value) {
        var hw=StockApplicationDriverDocuments.hardware();var h=new ComputerBlockHost(()->{});
        h.installFlow(new FlowProgram(Map.of("m",new DataType.RecordType(Map.of(key,DataType.Scalar.ANY))),
            List.of(new FlowProgram.Write("send","x0","m"))),record("m",record(key,value)),hw,true);return h;
    }
}
