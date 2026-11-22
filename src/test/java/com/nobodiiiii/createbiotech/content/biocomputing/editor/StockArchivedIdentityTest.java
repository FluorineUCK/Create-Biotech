package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Tests the public archive handshake, not a substitute for the actual book/serial integration. */
class StockArchivedIdentityTest {
    @Test void noNewBusinessIdentityEscapesBeforeArchiveCompletionAndFailureNeverPublishesIt() throws Exception {
        for (boolean committed : List.of(false,true)) {
            var rig=new Rig(List.of(),committed);var reply=rig.run(true);
            assertEquals(new DataValue.TextValue(committed?"bound":"archive_failed"),reply.field("c"));
            var stored=rig.archiver.flowSnapshot().orElseThrow().state().locals().field("request");
            var archive=(DataValue.RecordValue)stored;assertEquals(rig.version,archive.field("expected"));
            var candidate=(DataValue.RecordValue)((DataValue.ListValue)archive.field("v")).values().getFirst();
            assertEquals(rig.source,candidate.field("s"));
            assertEquals(committed?candidate.field("j"):new DataValue.TextValue(""),reply.field("j"));
        }
    }
    @Test void restoredMatchingBindingIsReusedButConflictingSourceOrEmptyIdentityIsNot() throws Exception {
        var source=StockNativeIdentityDocuments.input("stock-output",7,"workshop");
        var prior=List.<DataValue>of(record("s",StockNativeIdentityDocuments.input("other",1,"a"),"j","other"),
            record("s",StockNativeIdentityDocuments.input("stock-output",3,"a"),"j","earlier"),record("s",source,"j","original-business"));
        assertEquals(record("c","existing","j","original-business"),new Rig(prior,null).run(false));
        assertEquals(record("c","source_conflict","j",""),new Rig(List.of(record("s",StockNativeIdentityDocuments.input("stock-output",7,"changed"),"j","old")),null).run(false));
        assertEquals(record("c","source_conflict","j",""),new Rig(List.of(record("s",source,"j","")),null).run(false));
    }
    @Test void invalidSourceOrArchiveMetadataNeverReachesIdentityAllocation() throws Exception {
        var source=StockNativeIdentityDocuments.input("stock-output",7,"workshop");
        var version=record("medium",UUID.randomUUID().toString(),"entry","reading","revision",3);
        var empty=new DataValue.ListValue(List.of());
        for(var request:List.of(
            record("s",StockNativeIdentityDocuments.input("stock-output",0,"workshop"),"book",record("kind","DATA","value",empty,"version",version)),
            record("s",source,"book",record("kind","PROGRAM","value",empty,"version",version)),
            record("s",source,"book",record("kind","DATA","value",empty,"version",record("medium","","entry","reading","revision",3))),
            record("s",source,"book",record("kind","DATA","value",empty,"version",record("medium",UUID.randomUUID().toString(),"entry","reading","revision",-1))))) {
            var rig=new Rig(List.of(),null,request);var reply=rig.run(false);
            assertEquals(new DataValue.TextValue(""),reply.field("j"));
            assertTrue(Set.of(new DataValue.TextValue("invalid_source"),new DataValue.TextValue("invalid_archive")).contains(reply.field("c")));
        }
    }
    private static final class Rig {
        final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits limits=StockApplicationDriverDocuments.hardware();
        final DataValue.RecordValue source=StockNativeIdentityDocuments.input("stock-output",7,"workshop");
        final DataValue.RecordValue version=record("medium",UUID.randomUUID().toString(),"entry","reading","revision",3);
        ComputerBlockHost gate=new ComputerBlockHost(()->{}), binder=new ComputerBlockHost(()->{});
        final ComputerBlockHost caller=new ComputerBlockHost(()->{}),archiver=new ComputerBlockHost(()->{});
        final boolean archive;
        Rig(List<DataValue> entries,Boolean committed) throws Exception {
            this(entries,committed,null);
        }
        Rig(List<DataValue> entries,Boolean committed,DataValue.RecordValue supplied) throws Exception {
            archive=committed!=null;var d=StockNativeArchiveDocuments.ledger();
            System.out.println("ARCHIVED_IDENTITY "+d.flow().orElseThrow().program(d.archive()).usage(new ResourceLimits(4096,65536,8)));
            binder.installProgram(PreparedProgram.prepare(d,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);binder.setPaused(false,limits,true);
            gate.installProgram(PreparedProgram.prepare(StockNativeArchiveDocuments.ledger("check"),limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);gate.setPaused(false,limits,true);
            var snapshot=record("kind","DATA","value",new DataValue.ListValue(entries),"version",version);
            caller.installFlow(new FlowProgram(Map.of("request",StockNativeArchiveDocuments.LEDGER_REQUEST),List.of(new FlowProgram.Write("send","x0","request"),
                new FlowProgram.Read("reply","x0","reply",StockNativeIdentityDocuments.REPLY))).withAssembly(),record("request",supplied==null?record("s",source,"book",snapshot):supplied),limits,true);
            caller.setPaused(false,limits,true);
            if(archive) {archiver.installFlow(new FlowProgram(Map.of("reply",NativeLibraryReadDocuments.OUTCOME),List.of(
                new FlowProgram.Read("accept","x0","request",StockNativeArchiveDocuments.REQUEST),new FlowProgram.Write("finish","x0","reply"))).withAssembly(),
                record("reply",record("ok",committed,"value",committed?"verified":"version_conflict")),limits,true);archiver.setPaused(false,limits,true);}
        }
        DataValue.RecordValue run(boolean restoreBeforeArchive) {
            boolean restored=false;boolean enabled=!restoreBeforeArchive;
            for(int tick=1;tick<=10000;tick++) {
                gate.tick(tick,limits,true,Map.of());binder.tick(tick,limits,true,Map.of());caller.tick(tick,limits,true,Map.of());
                if(archive)archiver.tick(tick,limits,true,Map.of());
                gate.exchange("x0",limits,true,caller,"x0",limits,true);
                gate.exchange("x1",limits,true,binder,"x0",limits,true);
                var state=binder.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
                if(restoreBeforeArchive&&!restored&&state.status()==FlowRuntime.Status.WAITING_WRITE&&FlowXBus.pin(binder.flowSnapshot().orElseThrow().program(),state).equals("x1")) {
                    assertNotEquals(FlowRuntime.Status.COMPLETE,caller.flowSnapshot().orElseThrow().state().status());
                    assertFalse(caller.flowSnapshot().orElseThrow().state().locals().fields().containsKey("reply"));
                    binder.setPaused(true,limits,true);var saved=binder.flowSnapshot().orElseThrow();var raw=binder.write();binder.unload();binder=new ComputerBlockHost(()->{});binder.read(raw);
                    binder.tick(limits,true);assertEquals(saved,binder.flowSnapshot().orElseThrow());binder.setPaused(false,limits,true);restored=true;enabled=true;
                }
                if(archive&&enabled)binder.exchange("x1",limits,true,archiver,"x0",limits,true);
                if(caller.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
            }
            assertEquals(restoreBeforeArchive,restored);var done=caller.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
            return (DataValue.RecordValue)done.locals().field("reply");
        }
    }
}
