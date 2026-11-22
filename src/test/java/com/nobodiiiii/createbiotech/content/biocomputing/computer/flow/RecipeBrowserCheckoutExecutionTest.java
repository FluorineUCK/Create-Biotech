package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.RecipeBrowserQueryExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class RecipeBrowserCheckoutExecutionTest {
    static final DataType ANY=DataType.Scalar.ANY;
    static final DataType.RecordType PICK=new DataType.RecordType(Map.of("s",ANY,"d",ANY,"b",ANY,"o",ANY));
    static final DataType.RecordType ORDER=new DataType.RecordType(Map.of("s",ANY,"d",ANY,"n",ANY));
    static final DataValue.RecordValue PAGE=StockRecipeSupplyTest.page("ok");
    static ComputerBlockHost station(String id)throws Exception {
        var d=RecipeBrowserDocumentTest.published(id);var h=new ComputerBlockHost(()->{});
        h.installProgram(PreparedProgram.prepare(d,LIMIT),ComputerRuntime.MemoryPolicy.RESET,LIMIT,true);
        h.setPaused(false,LIMIT,true);return h;
    }
    static void healthy(ComputerBlockHost h){
        var s=h.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,s.status(),()->s.diagnostic().toString());
    }
    static DataValue.RecordValue proposal(String qty){return record("d",record("k",PAGE.field("pattern_id"),"page",PAGE.field("page"),
        "fingerprint",PAGE.field("fingerprint"),"source",PAGE.field("source")),"s","test","b",qty,"o",PAGE.field("outputs"));}
    @Test void invalidQuantityDoesNotOpenAConfirmationOrWriteAnOrder()throws Exception {
        for(String qty:List.of("","0","-1","1 ","４","2147483648")){
            var desk=station("recipe_browser_checkout");
            var writer=start(new FlowProgram(Map.of("p",PICK),List.of(new FlowProgram.Write("w","x1","p"))).withAssembly(),record("p",proposal(qty)));
            for(int t=0;t<2000;t++){
                writer.tick(LIMIT,true);desk.tick(LIMIT,true);writer.exchange("x1",LIMIT,true,desk,"x0",LIMIT,true);
                healthy(desk);assertTrue(desk.terminalQuestion().isEmpty(),qty);
                if(desk.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            var state=desk.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,state.status(),qty);
            assertEquals(new DataValue.TextValue("invalid_batches"),((DataValue.RecordValue)state.locals().field("draft")).field("c"));
        }
    }
    @Test void cancelOrExplicitlyConfirmTheSameDisplayedRowAfterReload()throws Exception {
        for(String answer:List.of("yes","YES")){
            var src=start(new FlowProgram(Map.of("e",ENVELOPE),List.of(new FlowProgram.Write("push","x0","e"))).withAssembly(),record("e",record("value",PAGE)));
            var q=RecipeBrowserDocumentTest.published();var body=q.flow().orElseThrow();
            var rig=new RecipeBrowserTerminalExecutionTest.Rig(start(body.program(q.archive()),record("poll_ticks",1,"page_count",1)),src,"recipe_browser_pick");
            var desk=station("recipe_browser_checkout");rig.prompt();assertEquals("ok",rig.submit(rig.view,"").code());rig.prompt();
            var root=TerminalProjection.project(rig.read(),Map.of(),20,200);
            var queue=new ArrayDeque<>(List.of(root));String row=null;
            while(!queue.isEmpty()){var node=queue.removeFirst();if(node.node().equals("batches"))row=node.instance();queue.addAll(node.children());}
            assertNotNull(row);var pick=rig.action(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(rig.view,row,"04").encode());
            assertEquals("ok",pick.code(),pick.detail());
            var sink=start(new FlowProgram(Map.of(),List.of(new FlowProgram.Read("receive","x0","result",ORDER))).withAssembly(),record());
            for(int t=0;t<3000&&desk.terminalQuestion().isEmpty();t++){
                rig.client.tick(LIMIT,true);desk.tick(LIMIT,true);sink.tick(LIMIT,true);healthy(desk);
                rig.client.exchange("x1",LIMIT,true,desk,"x0",LIMIT,true);
                assertNotEquals(XBusExchange.Status.EXCHANGED,desk.exchange("x1",LIMIT,true,sink,"x0",LIMIT,true));
            }
            var prompt=desk.terminalQuestion().orElseThrow();var state=desk.flowSnapshot().orElseThrow().state();
            var draft=(DataValue.RecordValue)state.locals().field("draft");var order=(DataValue.RecordValue)draft.field("request");
            assertEquals(new DataValue.IntegerValue(4),order.field("n"));assertEquals(proposal("04").field("d"),order.field("d"));
            assertFalse(((DataValue.TextValue)order.field("s")).value().isBlank());assertEquals(PAGE.field("outputs"),draft.field("o"));
            desk=restore(desk);assertEquals(prompt,desk.terminalQuestion().orElseThrow());desk.answerTerminal(prompt.token(),answer,LIMIT,true);
            int sends=0;for(int t=0;t<2048;t++){
                desk.tick(LIMIT,true);sink.tick(LIMIT,true);healthy(desk);if(desk.exchange("x1",LIMIT,true,sink,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED)sends++;
                if(desk.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            sink.tick(LIMIT,true);assertEquals(answer.equals("YES")?1:0,sends);
            assertEquals(new DataValue.TextValue(sends==1?"handed_to_validation":"cancelled"),desk.flowSnapshot().orElseThrow().state().locals().field("reply"));
            if(sends==1){var received=(DataValue.RecordValue)sink.flowSnapshot().orElseThrow().state().locals().field("result");assertEquals(order,received);StockRecipeSupplyTest.verifyBrowserShipment(received);}
        }
    }
}
