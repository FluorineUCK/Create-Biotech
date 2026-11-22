package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

class StockRecipeCatalogueTest {
    private static final String ROLE = "recipe_catalog";
    private static final DataType.RecordType ENVELOPE = new DataType.RecordType(Map.of("value",ANY));
    private static final DataType.RecordType RESPONSE = new DataType.RecordType(Map.of("r",ANY));
    private static final DataType.RecordType TARGET = new DataType.RecordType(Map.of("x",INTEGER,"y",INTEGER,"z",INTEGER));
    private static final DataType.RecordType CALL = new DataType.RecordType(Map.of("k",BOOLEAN,"t",TARGET,"o",TEXT,"a",ANY));
    private static final StockApplication.Limits LIMITS = new StockApplication.Limits(16,8,65536,256,25);
    private static final DataValue.RecordValue TARGET_POS = record("x",0,"y",0,"z",0);
    private static DataValue.RecordValue page(boolean second) {
        var f=new LinkedHashMap<>(FoundationRecipeTest.projection(second).fields());
        if(second) f.put("pattern_id",new DataValue.TextValue("00000000-0000-0000-0000-000000000002"));
        var patch=new DataValue.NbtValue(10,record("!minecraft:rarity",new DataValue.NbtValue(10,record())));
        // Physical page keys contain nested NBT even when the book has no custom data.
        var shelf = new DataValue.NbtValue(10,record("Pos",new DataValue.NbtValue(4,new DataValue.IntegerValue(42)),
            "Dimension",new DataValue.NbtValue(8,new DataValue.TextValue("minecraft:overworld"))));
        var slot = new DataValue.NbtValue(1,new DataValue.IntegerValue(0));
        f.put("source",new DataValue.NbtValue(10,record("Shelf",shelf,"Slot",slot,"Page",slot)));
        f.put("outputs",new DataValue.ListValue(List.of(record("item","minecraft:paper","count",3,"components",patch))));
        return new DataValue.RecordValue(f);
    }

    @Test void publishedDocumentsAreTheEditableProgramsForDefaultMasterClerics() throws Exception {
        var hw=StockApplicationDriverDocuments.hardware();
        var supply=List.of("recipe_dispatch","recipe_materials","recipe_route","recipe_submit");
        for(var role:java.util.stream.Stream.concat(List.of("recipe_row",ROLE,"recipe_sample","recipe_selection","recipe_order").stream(),supply.stream()).toList()){
            var doc=StockApplicationDriverDocuments.build(role);
            System.out.println("STOCK_RECIPE_WORDS "+role+" "+doc.flow().orElseThrow().program(doc.archive()).usage(new ResourceLimits(4096,65536,8)).programUnits());
            PreparedProgram.prepare(doc,hw);
            var name="stock_application_"+role+".bcp";
            var game=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
            var editable=Path.of("examples/biocomputing",name);
            var generate=System.getenv("GENERATE_STOCK_RECIPE_CATALOG");
            if(("1".equals(generate) && !supply.contains(role)) || role.equals(generate) || ("supply".equals(generate) && supply.contains(role))){
                var bytes=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(doc,hw.archive()))+"\n";
                Files.writeString(game,bytes); Files.writeString(editable,bytes);
            } else {
                assertEquals(doc,StockApplicationDriverDocuments.load(role)); assertArrayEquals(Files.readAllBytes(game),Files.readAllBytes(editable));
            }
        }
    }

    @Test void publishesSelectedPagesInOrderAndFreezesRecipeDescriptorInPlayerOrder() throws Exception {
        var run=run(List.of(page(false),page(true)),false);
        assertEquals("accepted",run.code());
        assertEquals(2,run.state().catalog().size());
        for(int i=0;i<2;i++) {
            var recipe=page(i==1); var entry=run.state().catalog().get(i);
            assertEquals(((DataValue.TextValue)recipe.field("pattern_id")).value(),entry.key());
            var out=(DataValue.RecordValue)((DataValue.ListValue)recipe.field("outputs")).values().getFirst();
            assertEquals(out.field("components"),entry.icon().field("components"));
            assertEquals(out,entry.icon(),"Icon is the output of one batch; it is not the ordered quantity");
            assertEquals(recipe.field("page"),entry.data().field("page"));
            assertFalse(entry.data().fields().containsKey("source"));
            assertEquals(recipe.field("fingerprint"),entry.data().field("fingerprint"));
        }
        var queued=StockApplication.confirm(run.state(),run.access(),UUID.randomUUID(),new StockApplication.Request(
            run.state().fence(),run.state().nextSequence(),List.of(new StockApplication.Selection(run.state().catalog().get(1).key(),2)),"shop"),LIMITS);
        var item=queued.input().orElseThrow().items().getFirst();
        int bytes=StockApplicationConnectorAdmissionTest.readerFrameBytes(queued.state(),queued.input().orElseThrow());
        assertTrue(bytes <= 4096,"Stock reader sample, including the actual order/outer frame: "+bytes);
        assertEquals(2,item.quantity()); assertEquals(run.state().catalog().get(1),item.entry());
    }
    @Test void malformedSecondPageNeverPublishesAPartialCatalogue() throws Exception {
        var run=run(List.of(page(false),record("type","standard")),false);
        assertEquals("invalid_recipe",run.code()); assertFalse(run.sentReplace());
        assertEquals(run.initial(),run.state());
    }
    @Test void interveningEditIsNotOverwritten() throws Exception {
        var run=run(List.of(page(false)),true);
        assertEquals("stale_revision",run.code()); assertTrue(run.sentReplace());
        assertEquals(run.initial().revision()+1,run.state().revision());
        assertEquals(run.initial().catalog(),run.state().catalog());
    }
    @Test void repeatedRecipeIsRejectedWithoutChangingExistingCatalogue() throws Exception {
        var run=run(List.of(page(false),page(false)),false);
        assertEquals("invalid_catalog",run.code()); assertEquals(run.initial(),run.state());
    }
    @Test void zeroPagesCannotClearTheCatalogue() throws Exception {
        var run=run(List.of(),false);
        assertEquals("invalid_page_count",run.code()); assertFalse(run.sentReplace()); assertEquals(run.initial(),run.state());
    }

    private record Result(String code,boolean sentReplace,StockApplication.State initial,StockApplication.State state,StockApplication.Access access) {}
    // Run the editable program at stock hardware limits. The two test peers expose the
    // actual page-reader and command-builder XBus shapes; catalogue admission uses the real service.
    private Result run(List<DataValue.RecordValue> pages,boolean changeRevision) throws Exception {
        var hw=StockApplicationDriverDocuments.hardware(); var doc=StockApplicationDriverDocuments.build(ROLE);
        var draft=new ProgramWorkspace(doc); var body=draft.flow().orElseThrow();
        System.out.println("STOCK_ROM "+body.program(doc.archive()).usage(new ResourceLimits(4096,65536,8)));
        var fields=new LinkedHashMap<>(body.initial().fields()); fields.put("pages",new DataValue.IntegerValue(pages.size()));
        draft.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),new DataValue.RecordValue(fields),body.canvas(),body.inline(),body.execution(),body.compilation())));
        var publisher=new ComputerBlockHost(()->{}); publisher.installProgram(PreparedProgram.prepare(draft.snapshot(),hw),ComputerRuntime.MemoryPolicy.RESET,hw,true);
        var rowDoc=StockApplicationDriverDocuments.build("recipe_row");
        System.out.println("STOCK_ROW "+rowDoc.flow().orElseThrow().program(rowDoc.archive()).usage(new ResourceLimits(4096,65536,8)));
        var row=new ComputerBlockHost(()->{}); row.installProgram(PreparedProgram.prepare(rowDoc,hw),ComputerRuntime.MemoryPolicy.RESET,hw,true);
        var sender=new ComputerBlockHost(()->{}); var parameters=new LinkedHashMap<String,DataType>(); var values=new LinkedHashMap<String,DataValue>(); var sends=new ArrayList<FlowProgram.Step>();
        for(int i=0;i<pages.size();i++){ var name="page"+i; parameters.put(name,ENVELOPE);values.put(name,record("value",pages.get(i)));sends.add(new FlowProgram.Write("send"+i,"x0",name)); }
        sender.installFlow(new FlowProgram(parameters,sends),new DataValue.RecordValue(values),hw,true);
        var app=new StockApplication.Application(UUID.randomUUID(),UUID.randomUUID()); var access=new StockApplication.Access(app,true,false);
        var initial=StockApplication.bind(StockApplication.closed(UUID.randomUUID(),UUID.randomUUID()),app,UUID.randomUUID()).state();
        var previous=new StockApplication.Entry("previous","Previous","unit",record("item","minecraft:paper","count",1,
            "components",new DataValue.NbtValue(10,record())),record());
        initial=StockApplication.replace(initial,access,initial.fence(),List.of(previous),LIMITS).state();
        var state=initial;
        var status=plan(state,access,"status",record()); var stock=new ComputerBlockHost(()->{});
        stock.installFlow(new FlowProgram(Map.of("status",RESPONSE),List.of(new FlowProgram.Read("s","x0","first",CALL),
            new FlowProgram.Write("r","x0","status"),new FlowProgram.Read("c","x0","replace",CALL),
            new FlowProgram.Read("device","x1","receipt",RESPONSE),new FlowProgram.Write("done","x0","receipt"))),
            record("status",record("r",status.reply())),hw,true);
        for(var host:List.of(publisher,row,sender,stock))host.setPaused(false,hw,true);
        ComputerBlockHost receipt=null;
        for(int t=1;t<=2500;t++){
            publisher.tick(t,hw,true,Map.of());
            row.tick(t,hw,true,Map.of("x0",busy(sender)));
            for(var host:List.of(sender,stock))host.tick(t,hw,true,Map.of());
            assertTrue(row.flowSnapshot().orElseThrow().state().diagnostic().isEmpty(),row.flowSnapshot().orElseThrow().state().diagnostic().toString());
            row.exchange("x0",hw,true,sender,"x0",hw,true); publisher.exchange("x1",hw,true,row,"x1",hw,true);
            publisher.exchange("x0",hw,true,stock,"x0",hw,true);
            var calls=stock.flowSnapshot().orElseThrow().state().locals();
            if(receipt==null && calls.fields().get("replace") instanceof DataValue.RecordValue call){
                assertEquals(new DataValue.TextValue("status"),((DataValue.RecordValue)calls.field("first")).field("o"));
                assertEquals(new DataValue.TextValue("replace"),call.field("o"));
                if(changeRevision) state=StockApplication.replace(state,access,state.fence(),state.catalog(),LIMITS).state();
                var applied=plan(state,access,"replace",(DataValue.RecordValue)call.field("a")); state=applied.next();
                receipt=new ComputerBlockHost(()->{});
                receipt.installFlow(new FlowProgram(Map.of("ack",RESPONSE),List.of(new FlowProgram.Write("reply","x0","ack"))),record("ack",record("r",applied.reply())),hw,true);
                receipt.setPaused(false,hw,true);
            }
            if(receipt!=null){receipt.tick(t,hw,true,Map.of());stock.exchange("x1",hw,true,receipt,"x0",hw,true);}
            var result=publisher.flowSnapshot().orElseThrow().state();
            assertTrue(result.diagnostic().isEmpty(),result.diagnostic().toString());
            if(result.status()==FlowRuntime.Status.COMPLETE){
                var reply=(DataValue.RecordValue)result.locals().field("reply");
                return new Result(((DataValue.TextValue)reply.field("code")).value(),receipt!=null,initial,state,access);
            }
        }
        throw new AssertionError("Publisher did not finish: "+publisher.flowSnapshot().orElseThrow().state().status());
    }
    private static int busy(ComputerBlockHost host) {
        return host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.WAITING_WRITE ? 15 : 0;
    }
    private static StockApplicationProtocol.Plan plan(StockApplication.State state,StockApplication.Access access,String op,DataValue.RecordValue args) {
        var frame=record("kind","stock_application_command","version",1,"target",TARGET_POS,"operation",op,"request",UUID.randomUUID().toString(),"arguments",args);
        SerialRecordCodec.encode(frame,new SerialRecordCodec.Limits(4096,262144,StockApplicationDriverDocuments.hardware().archive()));
        return StockApplicationProtocol.plan(state,access,TARGET_POS,frame,LIMITS).orElseThrow();
    }
}
