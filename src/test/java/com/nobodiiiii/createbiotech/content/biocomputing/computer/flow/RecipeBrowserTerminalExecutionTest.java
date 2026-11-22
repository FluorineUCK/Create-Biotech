package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.RecipeBrowserQueryExecutionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real terminal intents, published program documents and independent bounded ISA hosts. */
class RecipeBrowserTerminalExecutionTest {
    static class Rig {
        ReaderSelectionTerminalFixture terminal;
        ComputerBlockHost source, server, client;
        ComputerControlSession session;
        UUID nonce;
        long intent, view;
        int crossings, pages;
        Rig(ComputerBlockHost server,ComputerBlockHost source) throws Exception {
            this(server,source,RecipeBrowserDocumentTest.TERMINAL);
        }
        Rig(ComputerBlockHost server,ComputerBlockHost source,String document) throws Exception {
            this.server=server;this.source=source;
            terminal=new ReaderSelectionTerminalFixture(LIMIT);
            terminal.install(RecipeBrowserDocumentTest.published(document));
            client=terminal.host();client.setPaused(false,LIMIT,true);
            newSession();
        }
        void newSession(){nonce=UUID.randomUUID();session=new ComputerControlSession(nonce);intent=0;view=0;}
        ComputerControlSession.Answer action(ComputerControlSession.Action act,DataValue.RecordValue in){
            return session.apply(nonce,++intent,terminal.computer.programRevision(),act,()->in,terminal.computer).orElseThrow();
        }
        TerminalStatusView read(){
            var answer=action(ComputerControlSession.Action.READ_TERMINAL,record());
            assertEquals("ok",answer.code(),answer.detail());view=intent;
            return TerminalStatusView.decode(answer.dedicatedStatus().orElseThrow());
        }
        ComputerControlSession.Answer submit(long publication,String q){
            return action(ComputerControlSession.Action.TERMINAL_SUBMIT,new TerminalSubmit(publication,TerminalSubmit.QUESTION,q).encode());
        }
        void until(BooleanSupplier done){
            for(int i=0;i<16000&&!done.getAsBoolean();i++){
                if(source!=null)source.tick(LIMIT,true);
                server.tick(LIMIT,true,Map.of("x1",NativeLibraryStartupTest.status(server,"x1",LIMIT,client,"x0",LIMIT)));
                client.tick(LIMIT,true);
                if(source!=null && source.exchange("x0",LIMIT,true,server,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED)pages++;
                if(server.exchange("x1",LIMIT,true,client,"x0",LIMIT,true)==XBusExchange.Status.EXCHANGED)crossings++;
                for(var h:List.of(server,client)){
                    var state=h.flowSnapshot().orElseThrow().state();
                    assertNotEquals(FlowRuntime.Status.FAILED,state.status(),()->state.diagnostic().toString());
                }
            }
            assertTrue(done.getAsBoolean(),"Wire crossings="+crossings);
        }
        void prompt(){until(()->client.terminalQuestion().isPresent());assertTrue(read().question().isPresent());}
        void cold()throws Exception{
            server=restore(server);
            terminal=terminal.cold();client=terminal.host();client.setPaused(false,LIMIT,true);newSession();
        }
        DataValue.RecordValue panel(TerminalStatusView s){return (DataValue.RecordValue)((DataValue.RecordValue)s.roots().field("memory")).field("last_search");}
        List<DataValue> rows(TerminalStatusView s,String node){
            var found=new ArrayList<DataValue>();var pending=new ArrayDeque<TerminalProjection.View>();
            pending.add(TerminalProjection.project(s,Map.of(),20,200));
            while(!pending.isEmpty()){
                var v=pending.removeFirst();assertTrue(v.problem().isEmpty(),()->v.problem().toString());
                if(v.node().equals(node))found.add(v.value().orElseThrow());
                pending.addAll(v.children());
            }
            return found;
        }
    }
    @Test void blankListsBothPagesThenSubmitASecondSearchWithoutCreatingOrClaimingTasks() throws Exception {
        var first=FoundationRecipeTest.shapedProjection();
        var second=RecipePreviewExecutionTest.patch(FoundationRecipeTest.projection(true),"pattern_id",new DataValue.TextValue("11111111-0000-0000-0000-000000000001"));
        var source=start(new FlowProgram(Map.of("a",ENVELOPE,"b",ENVELOPE),List.of(
            new FlowProgram.Write("p0","x0","a"),new FlowProgram.Write("p1","x0","b"))).withAssembly(),
            record("a",record("value",first),"b",record("value",second)));
        var b=RecipeBrowserDocumentTest.published();
        var body=b.flow().orElseThrow();
        var rig=new Rig(start(body.program(b.archive()),body.initial()),source);
        rig.prompt();long firstView=rig.view;
        assertEquals("ok",rig.submit(firstView,"").code());
        rig.until(()->rig.crossings==3);
        assertFalse(rig.read().question().isPresent());
        assertEquals(new DataValue.TextValue("idle"),rig.panel(rig.read()).field("c"),"A partial first page is not published");
        assertNotEquals("ok",rig.submit(firstView,"overwrite").code(),"Stale prompt cannot enqueue a search");
        rig.cold();rig.prompt();
        var all=rig.read();assertEquals(List.of(first,second),rig.rows(all,"details"));
        assertEquals(List.of(first.field("pattern_id"),second.field("pattern_id")),rig.rows(all,"id"));
        assertEquals(2,rig.rows(all,"icon").size());assertEquals(2,rig.pages);assertEquals(6,rig.crossings);
        assertEquals("ok",rig.submit(rig.view,"11111111").code());
        rig.prompt();var one=rig.read();
        assertEquals(List.of(second),rig.rows(one,"details"));
        assertEquals(new DataValue.TextValue("11111111"),rig.panel(one).field("q"));
        assertEquals("ok",rig.submit(rig.view,"no_such_item").code());
        rig.prompt();var none=rig.read();assertTrue(rig.rows(none,"details").isEmpty());
        assertEquals(new DataValue.TextValue("end"),rig.panel(none).field("c"));
        assertEquals(12,rig.crossings);
        assertTrue(rig.client.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
    }
    @Test void anOldReplyAfterOneGoodPageCannotPublishThePrefixOrRunAnotherRequest() throws Exception {
        var key=new ModuleDefinition.Key("bad_peer",1);
        var src="""
            TYPE Q RECORD q TEXT i INTEGER k TEXT
            TYPE R RECORD c TEXT n INTEGER r Q v ANY
            REG req Q
            REG old Q
            REG reply R
            REG next INTEGER
            REG match TEXT
            CONST next 1
            CONST match "match"
            XBUS.READ old x1
            RECORD.MAKE reply c match n next r old v page
            XBUS.WRITE x1 reply
            XBUS.READ req x1
            XBUS.WRITE x1 reply
            """;
        var args=Map.<String,DataType>of("page",DataType.Scalar.ANY);
        var peer=new FlowProgram(args,List.of(new FlowProgram.Invoke("run",key,new FlowCallBindings(Map.of("page","page"),Map.of())),
            new FlowProgram.Procedure("peer",key,args,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(src))),
            new FlowProgram.EndProcedure("end"))).withAssembly();
        var rig=new Rig(start(peer,record("page",FoundationRecipeTest.projection(false))),null);
        rig.prompt();assertEquals("ok",rig.submit(rig.view,"iron").code());rig.prompt();
        var result=rig.read();assertTrue(rig.rows(result,"details").isEmpty());
        assertEquals(new DataValue.TextValue("unmatched_reply"),rig.panel(result).field("c"));
        assertEquals(4,rig.crossings);
    }
}
