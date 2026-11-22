package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.StockRecipeOrderPreviewTest.*;

public class StockRecipeSupplyTest {
    static final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits HW=StockApplicationDriverDocuments.hardware();
    static final DataValue nil=DataValue.NullValue.INSTANCE;
    static final DataValue.RecordValue POS=record("x",2,"y",0,"z",0);
    static final UUID stock=UUID.randomUUID(), device=UUID.randomUUID(), sender=UUID.randomUUID();
    static final long sequence=9223372036854775806L;
    static DataValue.ListValue list(DataValue... values){return new DataValue.ListValue(List.of(values));}
    static DataValue.TextValue text(String v){return new DataValue.TextValue(v);}
    static DataValue.RecordValue scope(){return record("source",sender.toString(),"device",device.toString(),"revision",2,
        "target",POS,"address","press");}
    static ProgramDocument settings(ProgramDocument doc,DataValue.RecordValue inputs) {
        var w=new ProgramWorkspace(doc);var b=w.flow().orElseThrow();
        w.setFlow(Optional.of(FlowDocumentBody.fromProgram(b.program(doc.archive()),inputs)));
        return w.snapshot();
    }
    @Test void checkedCatalogueSelectionAndRealWireProduceOneFencedIngredientOrder() throws Exception {
        var rig=new Rig();
        var result=rig.run("ok",4);
        assertEquals(text("ok"),result.field("code")); assertEquals(text("queued"),result.field("phase"));
        var order=rig.saved.orders().values().iterator().next();
        var exact=(DataValue.RecordValue)((DataValue.ListValue)order.request().field("items")).values().getFirst();
        assertEquals(text("minecraft:stone"),exact.field("item"));assertEquals(new DataValue.IntegerValue(8),exact.field("count"));
        assertEquals(FoundationRecipeTest.projection(false).field("source"),exact.field("components"));
        assertEquals(text(stock+"/materials/"+sequence),result.field("order"));
        assertEquals(text("ok"),rig.run("ok",4).field("code")); // fresh CPUs and a fresh device session
        assertEquals(text("request_conflict"),rig.run("ok",5).field("code"));
        assertEquals(1,rig.saved.orders().size());assertEquals(1,rig.preparations);
    }
    @Test void stalePageTagsAndRouteErrorsDoNotCreateOrAcknowledgeOrders() throws Exception {
        for(var mode:List.of("stale","sequenced","no_choice","wrong_address","unapproved_scope")){
            var rig=new Rig(); var result=rig.run(mode,4);
            assertEquals(text(switch(mode){case "stale"->"recipe_changed";case "sequenced"->"stage_selection_required";
                case "no_choice"->"selection_required";case "wrong_address"->"route_mismatch";default->"scope_not_approved";}), result.field("code"), mode);
            assertEquals(0,rig.preparations);assertTrue(rig.saved.orders().isEmpty());
        }
    }
    public static DataValue.RecordValue page(String mode) {
        var pageFields=new LinkedHashMap<>(FoundationRecipeTest.projection(mode.equals("sequenced")).fields());
        var components=pageFields.get("source");
        var rows=list(record("item",nil,"tag","c:stone","count",2,"components",components));
        pageFields.put("inputs",rows);
        if(!mode.equals("sequenced"))pageFields.put("body",record("address",mode.equals("wrong_address")?"shop":"press"));
        if(mode.equals("foreign_book"))pageFields.put("source",new DataValue.NbtValue(10,record()));
        return new DataValue.RecordValue(pageFields);
    }
    /** Follow the actual packet captured after confirmation, without inventing a stockkeeper queue head. */
    public static void verifyBrowserShipment(DataValue.RecordValue confirmed) throws Exception {
        var rig=new Rig();
        assertEquals(text("recipe_changed"),rig.run("foreign_book",4,confirmed).field("code"));
        assertEquals(text("scope_not_approved"),rig.run("unapproved_scope",4,confirmed).field("code"));
        assertEquals(0,rig.preparations);assertTrue(rig.saved.orders().isEmpty());
        var receipt=rig.run("ok",4,confirmed);
        assertEquals(text("queued"),receipt.field("phase"));assertEquals(confirmed.field("s"),receipt.field("order"));
        assertEquals(confirmed.field("s"),rig.run("ok",4,confirmed).field("order"));
        assertEquals(1,rig.saved.orders().size());assertEquals(1,rig.preparations);
        var line=(DataValue.RecordValue)((DataValue.ListValue)rig.saved.orders().values().iterator().next().request().field("items")).values().getFirst();
        assertEquals(text("minecraft:stone"),line.field("item"));assertEquals(new DataValue.IntegerValue(8),line.field("count"));
        assertEquals(page("ok").field("source"),line.field("components"));
    }
    static class Rig {
        ProductionOrderService.Snapshot saved=ProductionOrderService.Snapshot.empty();int preparations;
        final ProductionOrderService orders=new ProductionOrderService(new ProductionOrderService.Journal(){
            public ProductionOrderService.Snapshot load(){return saved;}
            public boolean commit(long v,ProductionOrderService.Snapshot n){if(v!=saved.revision())return false;saved=n;return true;}
        },new ProductionOrderService.Backend(){
            public boolean authorized(UUID s){return sender.equals(s);}
            public boolean supports(String ext,int v){return ext.equals("test")&&v==1;}
            public ProductionOrderService.Preparation prepare(UUID s,DataValue.RecordValue req){preparations++;return ProductionOrderService.Preparation.ready(new ProductionOrderService.Plan("test",1,List.of(req)));}
            public ProductionOrderService.Dispatch dispatch(ProductionOrderService.Operation op,DataValue.RecordValue cmd){throw new AssertionError();}
            public ProductionOrderService.Observation observe(ProductionOrderService.Operation op,DataValue.RecordValue r){throw new AssertionError();}
        },new ProductionOrderService.Limits(8,4,65536));
        DataValue.RecordValue run(String mode,long batches) throws Exception {return run(mode,batches,null);}
        DataValue.RecordValue run(String mode,long batches,DataValue.RecordValue confirmed) throws Exception {
            var hw=StockApplicationDriverDocuments.hardware();
            var port=new ProductionPortProtocol(device,UUID.randomUUID(),2,POS,"press",orders);
            var page=page(mode);
            ComputerBlockHost sample=null,input;
            if(confirmed!=null) {
                var type=new DataType.RecordType(Map.of("s",DataType.Scalar.ANY,"n",DataType.Scalar.ANY,"d",DataType.Scalar.ANY));
                input=new ComputerBlockHost(()->{});input.installFlow(new FlowProgram(Map.of("order",type),
                    List.of(new FlowProgram.Write("send_confirmed","x1","order"))).withAssembly(),record("order",confirmed),hw,true);
            } else {
                var app=new StockApplication.Application(UUID.randomUUID(),UUID.randomUUID());
                var access=new StockApplication.Access(app,true,false);
                var limits=new StockApplication.Limits(8,8,65536,256,25);
                var id=((DataValue.TextValue)page.field("pattern_id")).value();
                var entry=new StockApplication.Entry(id,"recipe","batch",(DataValue.RecordValue)((DataValue.ListValue)page.field("outputs")).values().getFirst(),
                    record("page",page.field("page"),"fingerprint",mode.equals("stale")?text("changed"):page.field("fingerprint")));
                var state=new StockApplication.State(stock,UUID.randomUUID(),1,Optional.of(app),true,sequence,List.of(entry),List.of());
                var placed=StockApplication.confirm(state,access,sender,new StockApplication.Request(state.fence(),sequence,List.of(new StockApplication.Selection(id,batches)),"shop"),limits);
                sample=StockRecipeOrderPreviewTest.sender("v",StockApplicationConnectorAdmissionTest.readerFrame(placed.state(),placed.input().orElseThrow()));
                input=installed(StockApplicationDriverDocuments.load("recipe_selection"));
            }
            var v=installed(RecipePreviewDocumentsTest.published("v"));
            var book=StockRecipeOrderPreviewTest.sender("value",page);
            var q=installed(confirmed==null?StockApplicationDriverDocuments.load("recipe_dispatch"):RecipeBrowserDocumentTest.published("recipe_browser_dispatch"));
            var choices=mode.equals("no_choice")?list():list(record("slot",2,"item","minecraft:stone","tags",list(text("c:stone"))));
            var materials=installed(settings(StockApplicationDriverDocuments.load("recipe_materials"),record("choices",choices)));
            var chooser=installed(MaterialSelectionDocuments.build("service"));
            var route=installed(confirmed==null?StockApplicationDriverDocuments.load("recipe_route"):RecipeBrowserDocumentTest.published("recipe_browser_route"));
            var submit=installed(settings(StockApplicationDriverDocuments.load("recipe_submit"),record("target",POS,"approved_scope",mode.equals("unapproved_scope")?nil:scope())));
            var packet=installed(StockNativeSupplyDocuments.build("packet_scoped"));
            var wire=new SerialExchangeDriverDocuments.Wire();
            var exchange=new ComputerBlockHost(()->{},p->{},wire.source);
            exchange.installProgram(PreparedProgram.prepare(SerialExchangeDriverDocuments.build(),hw),ComputerRuntime.MemoryPolicy.RESET,hw,true);
            var all=new ArrayList<>(List.of(input,book,v,q,materials,chooser,route,submit,packet,exchange));if(sample!=null)all.add(sample);
            all.forEach(h->h.setPaused(false,hw,true));
            var watch=switch(mode){case "stale","sequenced","foreign_book"->q;case "no_choice"->materials;default->submit;};
            boolean reading=false; 
            for(int t=1;t<=5000&&!complete(watch);t++){
                if(level(q)==15)reading=true;
                if(sample!=null)sample.tick(t,hw,true,Map.of());input.tick(t,hw,true,Map.of());if(reading){book.tick(t,hw,true,Map.of());v.tick(t,hw,true,Map.of("x0",SerialExchangeDriverDocuments.signal(book,"x0")));}
                q.tick(t,hw,true,Map.of());materials.tick(t,hw,true,Map.of());chooser.tick(t,hw,true,Map.of("x0",SerialExchangeDriverDocuments.signal(materials,"x1")));
                route.tick(t,hw,true,Map.of("p0",level(materials)));submit.tick(t,hw,true,Map.of());
                packet.tick(t,hw,true,Map.of());exchange.tick(t,hw,true,Map.of("x0",SerialExchangeDriverDocuments.signal(packet,"x1")));
                if(sample!=null)swap(sample,"x0",input,"x0");swap(input,"x1",q,"x0");if(reading){swap(book,"x0",v,"x0");swap(v,"x1",q,"x0");}
                swap(q,"x1",materials,"x0");swap(materials,"x1",chooser,"x0");swap(materials,"x0",route,"x0");
                swap(route,"x1",submit,"x0");swap(submit,"x1",packet,"x0");swap(packet,"x1",exchange,"x0");
                wire.tick(); var frame=wire.peer.poll("s0");
                if(frame.isPresent()){
                    var reply=port.respond(sender,(DataValue.RecordValue)frame.orElseThrow()).orElseThrow();
                    assertEquals("queued",wire.peer.send("s0",reply));
                }
                for(var h:all)assertNotEquals(FlowRuntime.Status.FAILED,h.flowSnapshot().orElseThrow().state().status(),()->h.flowSnapshot().orElseThrow().state().diagnostic().toString());
            }
            assertTrue(complete(watch),()->"Stalled "+mode+" "+all.stream().map(h->h.flowSnapshot().orElseThrow().state().status()).toList());
            return (DataValue.RecordValue)watch.flowSnapshot().orElseThrow().state().locals().field("reply");
        }
    }
    static int level(ComputerBlockHost h){return h.flowSnapshot().orElseThrow().state().signals().outputs().getOrDefault("p0",0);}
    static void swap(ComputerBlockHost a,String l,ComputerBlockHost b,String r){a.exchange(l,HW,true,b,r,HW,true);}
}
