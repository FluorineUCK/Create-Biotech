package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.ProductionPortProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.order.ProductionOrderService.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Actual public ISA packet builder + finite wire + order journal. No native extraction/player claim. */
class StockScopedPacketTest {
    @Test void publicAssemblyKeepsArchivedScopeOnTheWireAcrossSessionRestoreAndDeviceReplacement() throws Exception {
        var rig=new Rig();
        var initial=rig.call("status",DataValue.NullValue.INSTANCE,DataValue.NullValue.INSTANCE,false);
        var oldState=(DataValue.RecordValue)initial.field("state");var scope=oldState.field("scope");
        assertEquals(new DataValue.TextValue("accepted"),rig.call("submit",oldState,scope,false).field("code"));
        rig.session=UUID.randomUUID();
        var fresh=(DataValue.RecordValue)rig.call("status",DataValue.NullValue.INSTANCE,DataValue.NullValue.INSTANCE,false).field("state");
        assertEquals(scope,fresh.field("scope"));
        assertEquals(new DataValue.TextValue("existing"),rig.call("submit",fresh,scope,true).field("code"));
        rig.device=UUID.randomUUID();
        var replaced=(DataValue.RecordValue)rig.call("status",DataValue.NullValue.INSTANCE,DataValue.NullValue.INSTANCE,false).field("state");
        assertNotEquals(scope,replaced.field("scope"));
        assertEquals(new DataValue.TextValue("scope_changed"),rig.call("submit",replaced,scope,true).field("code"));
        assertEquals(new DataValue.TextValue("scope_changed"),rig.call("order",DataValue.NullValue.INSTANCE,scope,false).field("code"));
        assertEquals(1,rig.preparations);assertEquals(1,rig.saved.orders().size());
    }
    private static final class Rig {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits=StockApplicationDriverDocuments.hardware();
        final SerialExchangeDriverDocuments.Wire wire=new SerialExchangeDriverDocuments.Wire();
        final DataValue.RecordValue target=record("x",2,"y",0,"z",0);
        final UUID source=UUID.randomUUID();UUID device=UUID.randomUUID(),session=UUID.randomUUID();
        Snapshot saved=Snapshot.empty();int preparations;long tick;
        ComputerBlockHost packet=new ComputerBlockHost(()->{}),gateway=new ComputerBlockHost(()->{},pin->{},wire.source);
        final ProductionOrderService orders=new ProductionOrderService(new Journal(){
            public Snapshot load(){return saved;}
            public boolean commit(long expected,Snapshot next){if(saved.revision()!=expected)return false;saved=next;return true;}
        },new Backend(){
            public boolean authorized(UUID sender){return source.equals(sender);}
            public boolean supports(String extension,int version){return extension.equals("test")&&version==1;}
            public Preparation prepare(UUID sender,DataValue.RecordValue data){preparations++;return Preparation.ready(new Plan("test",1,List.of(data)));}
            public Dispatch dispatch(Operation operation,DataValue.RecordValue data){throw new AssertionError("No native extraction in this test");}
            public Observation observe(Operation operation,DataValue.RecordValue receipt){throw new AssertionError("No delivery observation");}
        },new Limits(8,4,65536));
        Rig() throws Exception {
            packet.installProgram(PreparedProgram.prepare(StockNativeSupplyDocuments.build("packet_scoped"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            gateway.installProgram(PreparedProgram.prepare(SerialExchangeDriverDocuments.build(),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
            packet.setPaused(false,limits,true);gateway.setPaused(false,limits,true);
        }
        DataValue.RecordValue call(String operation,DataValue state,DataValue scope,boolean restore) {
            var caller=new ComputerBlockHost(()->{});
            // The calling block also uses ordinary ISA for data.items, not Java request packaging.
            var inputs=Map.<String,DataType>of("target",SerialExchangeDriverDocuments.TARGET,"job",TEXT,"items",ANY,"state",ANY,"operation",TEXT,"expected",ANY);
            var outputs=Map.<String,DataType>of("reply",new DataType.RecordType(Map.of("r",ANY)));
            var bindings=new LinkedHashMap<String,String>();inputs.keySet().forEach(k->bindings.put(k,k));
            var returns=Map.of("reply","reply");var key=new ModuleDefinition.Key("request",1);
            var sourceCode="""
                TYPE Target RECORD x INTEGER y INTEGER z INTEGER
                TYPE Data RECORD items ANY
                TYPE Input RECORD t Target j TEXT i Data s ANY o TEXT e ANY
                TYPE Reply RECORD r ANY
                REG data Data
                REG input Input
                REG reply Reply
                RECORD.MAKE data items items
                RECORD.MAKE input t target j job i data s state o operation e expected
                XBUS.WRITE x0 input
                XBUS.READ reply x0
                """;
            caller.installFlow(new FlowProgram(inputs,List.of(new FlowProgram.Invoke("send",key,new FlowCallBindings(bindings,returns)),
                new FlowProgram.Procedure("implementation",key,inputs,outputs,returns,FlowBlockRules.NONE,
                    Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(sourceCode))),new FlowProgram.EndProcedure("end"))).withAssembly(),
                record("target",target,"job","original-job","items",new DataValue.ListValue(List.of()),"state",state,"operation",operation,"expected",scope),limits,true);
            caller.setPaused(false,limits,true);int sends=0;boolean restored=false;
            for(int step=0;step<15000;step++) {
                caller.tick(++tick,limits,true,Map.of());packet.tick(tick,limits,true,Map.of());
                gateway.tick(tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(packet,"x1")));
                caller.exchange("x0",limits,true,packet,"x0",limits,true);
                packet.exchange("x1",limits,true,gateway,"x0",limits,true);wire.tick();
                var frame=wire.peer.poll("s0");
                if(frame.isPresent()) {
                    sends++;var command=(DataValue.RecordValue)frame.orElseThrow();
                    var arguments=(DataValue.RecordValue)command.field("arguments");
                    if(!operation.equals("status"))assertEquals(scope,arguments.field("scope"),"The ISA builder must keep the original fence, not current status.scope");
                    var reply=new ProductionPortProtocol(device,session,7,target,"workshop",orders).respond(source,command).orElseThrow();
                    assertEquals("queued",wire.peer.send("s0",reply));
                    if(restore&&!restored){packet=reload(packet,false);gateway=reload(gateway,true);restored=true;}
                }
                for(var host:List.of(caller,packet,gateway))assertNotEquals(FlowRuntime.Status.FAILED,host.flowSnapshot().orElseThrow().state().status(),host.flowSnapshot().orElseThrow().state().diagnostic().toString());
                if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            assertEquals(1,sends,"No implicit mutation retry during restore or rejection");assertEquals(restore,restored);
            var done=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
            return (DataValue.RecordValue)((DataValue.RecordValue)done.locals().field("reply")).field("r");
        }
        ComputerBlockHost reload(ComputerBlockHost host,boolean serial) {
            host.setPaused(true,limits,true);var before=host.flowSnapshot().orElseThrow();var raw=host.write();host.unload();
            var next=serial?new ComputerBlockHost(()->{},pin->{},wire.source):new ComputerBlockHost(()->{});
            next.read(raw);next.tick(limits,true);assertEquals(before,next.flowSnapshot().orElseThrow());next.setPaused(false,limits,true);return next;
        }
    }
}
