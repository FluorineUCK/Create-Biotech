package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

class StockScopedIdentityTest {
    @Test void scopeIsPartOfTheDurableBindingAndChangingItNeverAllocatesAnotherIdentity() throws Exception {
        var source=source("original-sender");var rig=new Rig(source,List.of(),true);var first=rig.run();
        assertEquals(new DataValue.TextValue("bound"),first.field("c"));
        var stored=(DataValue.RecordValue)rig.store.flowSnapshot().orElseThrow().state().locals().field("candidate");
        var rows=(DataValue.ListValue)stored.field("v");var row=(DataValue.RecordValue)rows.values().getFirst();
        assertEquals(source,row.field("s"));assertEquals(first.field("j"),row.field("j"));assertEquals(rig.version,stored.field("expected"));
        assertEquals(record("c","existing","j",first.field("j")),new Rig(source,rows.values(),false).run());
        assertEquals(record("c","source_conflict","j",""),new Rig(source("new-sender"),rows.values(),false).run());
    }
    static DataValue.RecordValue source(String sender) {
        var original=StockNativeIdentityDocuments.input("stock-device",7,"workshop");
        return record("d",original.field("d"),"h",original.field("h"),"r",record("source",sender,"device","dispatch-device",
            "revision",3,"target",record("x",2,"y",0,"z",4),"address","workshop"));
    }
    private static final class Rig {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits=StockApplicationDriverDocuments.hardware();
        final ComputerBlockHost check=new ComputerBlockHost(()->{}),identity=new ComputerBlockHost(()->{}),caller=new ComputerBlockHost(()->{}),store=new ComputerBlockHost(()->{});
        final DataValue.RecordValue version=record("medium",UUID.randomUUID().toString(),"entry","reading","revision",3);
        final boolean write;
        Rig(DataValue.RecordValue source,List<DataValue> entries,boolean write) throws Exception {
            this.write=write;
            for(var entry:Map.of("check",check,"identity",identity).entrySet()) {
                entry.getValue().installProgram(PreparedProgram.prepare(StockScopedArchiveDocuments.build(entry.getKey()),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);
                entry.getValue().setPaused(false,limits,true);
            }
            caller.installFlow(new FlowProgram(Map.of("request",StockScopedArchiveDocuments.REQUEST),List.of(new FlowProgram.Write("send","x0","request"),
                new FlowProgram.Read("reply","x0","reply",StockNativeIdentityDocuments.REPLY))).withAssembly(),
                record("request",record("s",source,"book",record("kind","DATA","value",new DataValue.ListValue(entries),"version",version))),limits,true);
            caller.setPaused(false,limits,true);
            if(write){store.installFlow(new FlowProgram(Map.of("result",NativeLibraryReadDocuments.OUTCOME),List.of(
                new FlowProgram.Read("candidate","x0","candidate",StockNativeArchiveDocuments.REQUEST),new FlowProgram.Write("verified","x0","result"))).withAssembly(),
                record("result",record("ok",true,"value","verified")),limits,true);store.setPaused(false,limits,true);}
        }
        DataValue.RecordValue run() {
            for(int tick=1;tick<10000;tick++) {
                check.tick(tick,limits,true,Map.of("p0",2));identity.tick(tick,limits,true,Map.of());caller.tick(tick,limits,true,Map.of());
                if(write)store.tick(tick,limits,true,Map.of());
                caller.exchange("x0",limits,true,check,"x0",limits,true);check.exchange("x1",limits,true,identity,"x0",limits,true);
                if(write)identity.exchange("x1",limits,true,store,"x0",limits,true);
                for(var h:List.of(check,identity,caller))assertNotEquals(FlowRuntime.Status.FAILED,h.flowSnapshot().orElseThrow().state().status(),h.flowSnapshot().orElseThrow().state().diagnostic().toString());
                if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            var done=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
            return (DataValue.RecordValue)done.locals().field("reply");
        }
    }
}
