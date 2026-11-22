package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** A bounded player-software ledger; no new host-side order identity service. */
public class StockNativeIdentityDocuments {
    static final DataType.RecordType HEAD=new DataType.RecordType(Map.of("kind",TEXT,"sequence",INTEGER,"player",TEXT,"address",TEXT,"order",NBT));
    static final DataType.RecordType INPUT=new DataType.RecordType(Map.of("d",TEXT,"h",HEAD));
    static final DataType.RecordType REPLY=new DataType.RecordType(Map.of("c",TEXT,"j",TEXT));
    public static ProgramDocument build() throws Exception {
        var inputs=Map.<String,DataType>of("capacity",INTEGER);
        var key=new ModuleDefinition.Key("bind",1);
        var p=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("serve",key,new FlowCallBindings(Map.of("capacity","capacity"),Map.of())),
            new FlowProgram.Procedure("implementation",key,inputs,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(                Files.readString(Path.of("examples/biocomputing/stock_native_identity.asm"))))),new FlowProgram.EndProcedure("end"))).withAssembly();
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(p,record("capacity",8))));return workspace.snapshot();
    }
    @Test void bindingIsDurableBeforeReplyAndDoesNotRebindChangedOrAdditionalOrdersWhenFull() throws Exception {
        var r=new Rig();var source=input("library-output",1,"workshop");
        var first=r.call(source,true);assertEquals(new DataValue.TextValue("bound"),first.field("c"));
        var job=(DataValue.TextValue)first.field("j");assertFalse(job.value().isEmpty());
        assertEquals(record("c","existing","j",job),r.call(source,false));
        assertEquals(record("c","source_conflict","j",""),r.call(input("library-output",1,"changed"),false));
        var second=r.call(input("other-output",1,"workshop"),false);
        assertEquals(new DataValue.TextValue("bound"),second.field("c"));assertNotEquals(job,second.field("j"));
        assertEquals(record("c","ledger_full","j",""),r.call(input("library-output",2,"workshop"),false));
        assertEquals(record("c","existing","j",job),r.call(source,true));
    }
    @Test void selectedLedgerSleepsWhileIdleAndHeldSelectionNeverRebinds() throws Exception {
        var r=new Rig(true);r.waitSelection(0);
        var input=input("stock-output",1,"workshop");var first=r.call(input,true);
        r.waitSelection(1); // A held selector must not consume the next differently shaped bus operation.
        r.waitSelection(0);
        assertEquals(record("c","existing","j",first.field("j")),r.call(input,true));
    }
    @Test void publishedLedgerIsAnEditableProgramOnUnmodifiedHardware() throws Exception {
        var limits=StockApplicationDriverDocuments.hardware();var document=build();PreparedProgram.prepare(document,limits);
        var name="stock_native_identity.bcp";
        var asset=Path.of("src/main/resources/assets/create_biotech/biocomputing/examples",name);
        var doc=Path.of("examples/biocomputing",name);
        if("1".equals(System.getenv("GENERATE_STOCK_NATIVE_IDENTITY"))) {
            var encoded=Base64.getEncoder().encodeToString(ProgramDocumentWire.encode(document,limits.archive()))+"\n";
            Files.writeString(asset,encoded);Files.writeString(doc,encoded);
        } else {
            assertEquals(Files.readString(doc),Files.readString(asset));
            assertEquals(document,ProgramDocumentWire.decode(Base64.getDecoder().decode(Files.readString(asset).strip()),limits.archive()));
        }
    }
    static DataValue.RecordValue input(String device,int sequence,String address) {
        return record("d",device,"h",record("kind","native_order","sequence",sequence,"player","player-id","address",address,
            "order",new DataValue.NbtValue(10,record("typed",new DataValue.NbtValue(1,new DataValue.IntegerValue(3))))));
    }
    static final class Rig {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits=StockApplicationDriverDocuments.hardware();
        ComputerBlockHost ledger=new ComputerBlockHost(()->{}), caller=new ComputerBlockHost(()->{}); long tick; final boolean selected;
        Rig() throws Exception {this(false);}
        Rig(boolean selected) throws Exception {this.selected=selected;var workspace=new ProgramWorkspace(selected?StockNativeSupplyDocuments.build("identity_selected"):build());var b=workspace.flow().orElseThrow();
            workspace.setFlow(Optional.of(new FlowDocumentBody(b.inputs(),b.steps(),record("capacity",2),b.canvas(),b.inline(),b.execution(),b.compilation())));
            var d=workspace.snapshot();System.out.println("NATIVE_IDENTITY "+d.flow().orElseThrow().program(d.archive()).usage(new ResourceLimits(4096,65536,8)));
            ledger.installProgram(PreparedProgram.prepare(d,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);ledger.setPaused(false,limits,true);}
        void waitSelection(int level) {
            int sleeps=0;
            for(int n=0;n<20;n++) {
                ledger.tick(++tick,limits,true,Map.of("p0",level,"x0",0));
                var state=ledger.flowSnapshot().orElseThrow().state();
                assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
                assertNotEquals(FlowRuntime.Status.WAITING_READ,state.status(),"Unselected/held selection must not consume another bus input");
                if(state.status()==FlowRuntime.Status.SLEEPING)sleeps++;
            }
            assertTrue(sleeps>0,"Idle selection loops must yield with explicit sleep, not burn the whole CPU budget");
        }
        DataValue.RecordValue call(DataValue.RecordValue input,boolean restoreBeforeReply) {
            if(caller.flowSnapshot().isPresent()){caller.setPaused(true,limits,true);caller.reset(limits,true);}
            caller.installFlow(new FlowProgram(Map.of("input",INPUT),List.of(new FlowProgram.Write("send","x0","input"),
                new FlowProgram.Read("receive","x0","reply",REPLY))).withAssembly(),record("input",input),limits,true);
            caller.setPaused(false,limits,true);boolean restored=false;
            for(int n=0;n<10000;n++) {
                ledger.tick(++tick,limits,true,Map.of("x0",SerialExchangeDriverDocuments.signal(caller,"x0"),"p0",selected?1:0));caller.tick(tick,limits,true,Map.of());
                var saved=ledger.flowSnapshot().orElseThrow();
                assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),saved.state().diagnostic().toString());
                if(restoreBeforeReply&&!restored&&saved.state().status()==FlowRuntime.Status.WAITING_WRITE) {
                    var disk=ledger.write();ledger=new ComputerBlockHost(()->{});ledger.read(disk);ledger.setPaused(false,limits,true);
                    disk=caller.write();caller=new ComputerBlockHost(()->{});caller.read(disk);caller.setPaused(false,limits,true);restored=true;
                }
                ledger.exchange("x0",limits,true,caller,"x0",limits,true);
                if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE) {
                    assertEquals(restoreBeforeReply,restored);return (DataValue.RecordValue)caller.flowSnapshot().orElseThrow().state().locals().field("reply");
                }
            }
            throw new AssertionError("Binding never returned: "+ledger.flowSnapshot().orElseThrow().state().diagnostic());
        }
    }
}
