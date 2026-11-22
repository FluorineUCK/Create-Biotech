package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortProtocolTest.*;

/** A saved application binding is a fence, never authority to impersonate its original sender. */
class ProductionScopeProtocolTest {
    private static final UUID DEVICE=new UUID(10,1), SOURCE=new UUID(10,2), SESSION=new UUID(10,3);
    private static final DataValue.RecordValue TARGET=record(Map.of("x",integer(1),"y",integer(2),"z",integer(3)));
    static final class Store implements Journal {
        Snapshot value=Snapshot.empty();
        public Snapshot load(){return value;}
        public boolean commit(long expected,Snapshot next){if(value.revision()!=expected)return false;value=next;return true;}
    }
    static final class Adapter implements Backend {
        int preparations;
        // The physical host already proves the active sender. Both identities model explicitly authorized hosts.
        public boolean authorized(UUID source){return true;}
        public boolean supports(String extension,int version){return extension.equals("test")&&version==1;}
        public Preparation prepare(UUID source,DataValue.RecordValue data){preparations++;return Preparation.ready(new Plan("test",1,List.of(data)));}
        public Dispatch dispatch(Operation operation,DataValue.RecordValue data){throw new AssertionError("Admission must not dispatch");}
        public Observation observe(Operation operation,DataValue.RecordValue receipt){throw new AssertionError("No observation expected");}
    }
    private static DataValue.RecordValue command(DataValue.RecordValue target,String operation,DataValue.RecordValue arguments){
        return record(Map.of("kind",text("production_command"),"version",integer(1),"target",target,
            "request",text("wire-attempt"),"operation",text(operation),"arguments",arguments));
    }
    private static DataValue.RecordValue submit(UUID device,UUID session,DataValue scope){
        return record(Map.of("device",text(device.toString()),"session",text(session.toString()),"order",text("original-job"),
            "scope",scope,"data",record(Map.of("items",new DataValue.ListValue(List.of())))));
    }
    @Test void savedScopeSurvivesSessionRestoreButRejectsChangedNamespaceBeforeCreatingAnotherOrder(){
        var store=new Store();var adapter=new Adapter();var limits=new Limits(8,4,65536);
        var service=new ProductionOrderService(store,adapter,limits);
        var host=new ProductionPortProtocol(DEVICE,SESSION,7,TARGET,"workshop",service);
        var status=command(TARGET,"status",record(Map.of("scope",new DataValue.BooleanValue(true))));
        var state=(DataValue.RecordValue)host.respond(SOURCE,status).orElseThrow().field("state");
        assertTrue(state.fields().containsKey("scope"),"Public status must expose the actual dispatch namespace for durable application binding");
        var scope=state.field("scope");
        assertEquals(record(Map.of("source",text(SOURCE.toString()),"device",text(DEVICE.toString()),"revision",integer(7),
            "target",TARGET,"address",text("workshop"))),scope);
        assertEquals(text("accepted"),host.respond(SOURCE,command(TARGET,"submit",submit(DEVICE,SESSION,scope))).orElseThrow().field("code"));
        var nextSession=new UUID(10,4);var restored=new ProductionPortProtocol(DEVICE,nextSession,7,TARGET,"workshop",new ProductionOrderService(store,adapter,limits));
        assertEquals(scope,((DataValue.RecordValue)restored.respond(SOURCE,status).orElseThrow().field("state")).field("scope"));
        var query=record(Map.of("order",text("original-job"),"scope",scope));
        assertEquals(text("ok"),restored.respond(SOURCE,command(TARGET,"order",query)).orElseThrow().field("code"));
        assertEquals(text("session_changed"),restored.respond(SOURCE,command(TARGET,"submit",submit(DEVICE,SESSION,scope))).orElseThrow().field("code"));
        assertEquals(text("existing"),restored.respond(SOURCE,command(TARGET,"submit",submit(DEVICE,nextSession,scope))).orElseThrow().field("code"));
        var before=store.load();
        for(String change:List.of("source","device","revision","target","address")){
            UUID source=change.equals("source")?new UUID(20,1):SOURCE, device=change.equals("device")?new UUID(20,2):DEVICE;
            var target=change.equals("target")?record(Map.of("x",integer(4),"y",integer(5),"z",integer(6))):TARGET;
            var changed=new ProductionPortProtocol(device,nextSession,change.equals("revision")?8:7,target,
                change.equals("address")?"other":"workshop",new ProductionOrderService(store,adapter,limits));
            assertEquals(text("scope_changed"),changed.respond(source,command(target,"submit",submit(device,nextSession,scope))).orElseThrow().field("code"),change);
            assertEquals(text("scope_changed"),changed.respond(source,command(target,"order",query)).orElseThrow().field("code"),change);
            assertEquals(before,store.load(),"A mismatch must not create, advance, or query a new namespace: "+change);
        }
        assertEquals(1,adapter.preparations);assertEquals(1,store.load().orders().size());
        assertFalse(host.respond(null,status).orElseThrow().fields().containsKey("state"));
        assertEquals(text("denied"),host.respond(null,command(TARGET,"submit",submit(DEVICE,SESSION,scope))).orElseThrow().field("code"));
        var unknown=new ProductionPortProtocol(DEVICE,SESSION,7,TARGET,service);
        var unavailable=unknown.respond(SOURCE,status).orElseThrow();
        assertEquals(text("scope_unavailable"),unavailable.field("code"));assertFalse(unavailable.fields().containsKey("state"));
        assertEquals(text("scope_changed"),unknown.respond(SOURCE,command(TARGET,"submit",submit(DEVICE,SESSION,scope))).orElseThrow().field("code"));
    }
}
