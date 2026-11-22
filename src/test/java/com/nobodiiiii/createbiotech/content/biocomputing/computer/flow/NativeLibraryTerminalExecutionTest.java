package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.*;

public class NativeLibraryTerminalExecutionTest {
    @BeforeAll static void bootstrap() throws Exception { NativeLibraryTerminalSessionTest.bootstrap(); }
    @Test void wholeImageAndTheActualPinnedTaskEntryFitIndependentDefaultBudgets() throws Exception {
        var doc=NativeLibraryTerminalSessionTest.published(); var l=NativeLibraryOwnersTest.limits("cleric",5);
        var program=doc.flow().orElseThrow().program(doc.archive());
        var main=program.compile(l.hardware()).assembly().orElseThrow();
        var task=program.compile(l.hardware(),Optional.of(NativeLibraryTerminalSessionTest.WRITER)).assembly().orElseThrow();
        assertTrue(main.codeWords()<=256); assertTrue(task.codeWords()<=256);
        var f=new NativeLibraryTerminalSessionTest.Fixture(); f.start(); f.read(); assertEquals("ok",f.submit("budget probe"));
        assertTrue(f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().assembly().isPresent());
        System.out.println("terminal A whole="+main.codeWords()+" task="+task.codeWords()+" constants="+main.constantUnits()+" limits="+l);
        for(var role:List.of("b","c","d")) {
            var member=role.equals("d")?publishedD():NativeLibraryOwnersTest.document(role);
            var limits=NativeLibraryOwnersTest.limits("cleric",role.equals("c")?4:5);
            assertEquals(Map.of("b",250,"c",185,"d",237).get(role).intValue(),member.flow().orElseThrow().program(member.archive()).compile(limits.hardware()).assembly().orElseThrow().codeWords());
        }
    }
    @Test void typedLiteralTraversesFourPublicOwnersFiniteSerialAndVersionedMediaThenExplicitClaimReleasesSlot() throws Exception { run(false,true); }
    @Test void businessRejectionIsACompletedTaskWithOriginalRawErrorNotSuccessOrInventedReadback() throws Exception { run(true,false); }

    /** In-memory copied book media: not a physical shelf, world grant, or player authorization proof. */
    static final class Media implements LibraryReadHead.Source {
        final boolean readonly;
        final String expectedEntry;
        final LibraryBookMedia media;
        ItemStack book; int commits; int releases;
        Media(boolean readonly) {this(readonly,SerialFlowHostTest.ARCHIVE,Integer.MAX_VALUE);}
        Media(boolean readonly,com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits archive,int bookBytes) {
            this(readonly,archive,bookBytes,"reading");
        }
        Media(boolean readonly,com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits archive,int bookBytes,String expectedEntry) {
            this.readonly=readonly;this.expectedEntry=expectedEntry;media=new LibraryBookMedia(DataComponentType.<CustomData>builder().persistent(CustomData.CODEC).build(),archive,bookBytes);
            book=media.put(new ItemStack(Items.WRITABLE_BOOK),"reading",new LibraryEntry(LibraryEntry.Kind.DATA,new DataValue.IntegerValue(43)));
        }
        public void validateTrack(LibraryTrackAddress t){assertEquals(new LibraryTrackAddress(1,0,0),t);}
        void at(LibraryTrackAddress t,int slot){validateTrack(t);assertEquals(2,slot);}
        public LibraryReadHead.Sample read(LibraryTrackAddress t,int slot,String entry){at(t,slot);assertEquals(expectedEntry,entry);var s=media.snapshot(book,entry);return new LibraryReadHead.Sample(s.revision(),s.value());}
        public LibraryReadHead.Sample stat(LibraryTrackAddress t,int slot,String entry){at(t,slot);var s=media.statSnapshot(book,entry);return new LibraryReadHead.Sample(s.revision(),s.value());}
        public void verify(LibraryTrackAddress t,int slot,String entry,DataValue.RecordValue fence){at(t,slot);assertEquals(media.revision(book),fence);}
        public void verifyTransfer(LibraryTrackAddress t,int slot,LibraryWriteTransfer transfer){
            at(t,slot);assertEquals("plant",transfer.volume());assertEquals(expectedEntry,transfer.entry());
            if(readonly)throw new LibraryBookAccess.Failure("readonly");
            var actual=com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation.Version.decode(media.stat(book,transfer.entry()).field("version"));
            if(!actual.medium().equals(transfer.expected().medium()))throw new LibraryBookAccess.Failure("medium_changed");
            if(!actual.equals(transfer.expected()))throw new LibraryBookAccess.Failure("stale_version");
        }
        public LibraryBookAccess.PreparedWrite prepareWrite(LibraryTrackAddress t,int slot,LibraryDeviceWrite operation){
            at(t,slot); assertEquals("plant",operation.mutation().volume()); assertEquals(expectedEntry,operation.mutation().entry());
            if(readonly)throw new LibraryBookAccess.Failure("readonly");
            var prepared=media.prepareWrite(book,operation);
            return new LibraryBookAccess.PreparedWrite(prepared.reply(),prepared.replay(),()->{book=prepared.replacement();if(!prepared.replay())commits++;});
        }
        public void releaseWrite(LibraryTrackAddress t,int slot,LibraryDeviceWrite operation){at(t,slot);book=media.releaseReceipt(book,operation);releases++;}
    }
    static ProgramDocument publishedD() throws Exception {
        try(var input=NativeLibraryTerminalExecutionTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/library_write_d.bcp")) {
            assertNotNull(input);return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(input.readAllBytes()).trim()),SerialFlowHostTest.ARCHIVE);
        }
    }
    static XBusAccess access(ComputerBlockHost h,String pin,ComputerHostLimits limits,XBusHistorySavedData history){return NativeLibraryStartupTest.access(h,pin,limits).historical(history,()->65536,()->262144);}
    static void exchange(ComputerBlockHost a,String ap,ComputerHostLimits la,ComputerBlockHost b,String bp,ComputerHostLimits lb,XBusHistorySavedData history){
        var result=access(a,ap,la,history).exchange(access(b,bp,lb,history));
        assertFalse(Set.of(XBusExchange.Status.UNAVAILABLE,XBusExchange.Status.UNSUPPORTED,XBusExchange.Status.RECOVERY_REQUIRED).contains(result),"Wire exchange "+result);
    }
    static void run(boolean readonly,boolean restore) throws Exception {
        var f=new NativeLibraryTerminalSessionTest.Fixture(); f.start();
        String typed="{\"typed\":42} literal 文本"; f.read(); assertEquals("ok",f.submit(typed));
        completeSubmitted(f,readonly,restore,true);
    }
    public static void completeSubmitted(NativeLibraryTerminalSessionTest.Fixture f,boolean readonly,boolean restore,boolean claim) throws Exception {
        var main=f.computer.flowSnapshot().orElseThrow().state();
        String typed=((DataValue.TextValue)f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().locals().field("replacement")).value();
        var la=NativeLibraryOwnersTest.limits("cleric",5);var lb=NativeLibraryOwnersTest.limits("cleric",5);var lc=NativeLibraryOwnersTest.limits("cleric",5);var ld=NativeLibraryOwnersTest.limits("cleric",5);
        var wireB=NativeLibraryOwnersTest.device(lb);wireB.configure("s0",Direction.EAST,true);var wireL=NativeLibraryOwnersTest.device(lb);wireL.configure("s0",Direction.WEST,true);
        var b=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("b"),lb,wireB);var c=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("c"),lc,null);
        var d=NativeLibraryOwnersTest.install(publishedD(),ld,null);
        b.setPaused(false,lb,true);c.setPaused(false,lc,true);d.setPaused(false,ld,true);
        var serial=new SerialNetwork();var posB=new BlockPos(0,0,0);var cable=new BlockPos(1,0,0);var posL=new BlockPos(2,0,0);var devices=Map.of(posB,wireB,posL,wireL);
        var world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(cable)?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
        var source=new Media(readonly);var head=LibraryReadHead.State.empty();var drive=NativeLibraryOwnersTest.CONFIG.libraryDrive(false);
        var history=new XBusHistorySavedData();var epoch=UUID.randomUUID();var device=UUID.randomUUID();
        var operations=new ArrayList<String>();var requests=new HashSet<DataValue>();DataValue.RecordValue rawFailure=null;boolean restored=false;long[] peakRam=new long[4];long[] peakWork=new long[4];long[] previous=new long[4];previous[0]=main.work();
        for(int tick=0;tick<14000;tick++) {
            f.owner.tick(la,true);
            b.tick(lb,true,Map.of("x0",NativeLibraryStartupTest.status(b,"x0",lb,f.owner,"x0",la)));
            c.tick(lc,true,Map.of("x0",NativeLibraryStartupTest.status(c,"x0",lc,b,"x1",lb)));
            d.tick(ld,true,Map.of("x0",NativeLibraryStartupTest.status(d,"x0",ld,f.owner,"x1",la)));
            exchange(f.owner,"x0",la,b,"x0",lb,history);exchange(b,"x1",lb,c,"x0",lc,history);exchange(f.owner,"x1",la,d,"x0",ld,history);
            serial.tick(world,devices,NativeLibraryOwnersTest.CONFIG.communication(false),NativeLibraryOwnersTest.CONFIG.serialMedium(false));
            if(head.writing().isPresent()&&head.writing().orElseThrow().admission().verdict().isEmpty())head=LibraryReadHead.withWrite(head,head.writing().orElseThrow().seal(false));
            var mechanical=LibraryReadHead.prepareTick(head,source,drive);
            if(mechanical.recovery().isPresent()) assertEquals(mechanical.recovery().orElseThrow(),LibraryReadHeadCodec.decode(LibraryReadHeadCodec.encode(mechanical.recovery().orElseThrow(),drive),drive));
            mechanical.commit().run();head=mechanical.next();
            if(wireL.status("s0").receive().frames()>0&&!Set.of("queued","transmitting").contains(wireL.status("s0").transmit())) {
                var received=wireL.prepareReceive("s0");var command=LibraryDriveCommand.decode((DataValue.RecordValue)received.result().field("value"));
                operations.add(command.operation());assertTrue(requests.add(command.request()),"No implicit replay");assertEquals(record("x",0,"y",0,"z",0),command.target());
                DataValue.RecordValue reply;
                try {
                    var transition=command.apply(head,source,drive,new LibraryDriveCommand.Admission(device,new LibraryWriteBatch(epoch,tick)));
                    if(command.operation().equals("ack")&&head.writing().isPresent())source.releaseWrite(head.track().orElseThrow(),head.slot(),head.writing().orElseThrow().operation());
                    head=transition.state();reply=transition.reply();
                }catch(LibraryBookAccess.Failure failure){reply=command.reply(head.sequence(),failure.code(),DataValue.NullValue.INSTANCE);rawFailure=reply;}
                assertTrue(received.publish());assertEquals("queued",wireL.send("s0",reply));
            }
            if(restore&&!restored&&operations.contains("status")&&f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status()==FlowRuntime.Status.WAITING_READ){
                var oldHistory=history.save(new net.minecraft.nbt.CompoundTag(),RegistryAccess.EMPTY);f.restore();b=NativeLibraryOwnersTest.reload(b,lb,wireB);c=NativeLibraryOwnersTest.reload(c,lc,null);d=NativeLibraryOwnersTest.reload(d,ld,null);
                history=XBusHistorySavedData.load(oldHistory,RegistryAccess.EMPTY);assertEquals(oldHistory,history.save(new net.minecraft.nbt.CompoundTag(),RegistryAccess.EMPTY));restored=true;
            }
            var owners=List.of(f.owner,b,c,d);var limits=List.of(la,lb,lc,ld);
            for(int i=0;i<4;i++){
                var saved=owners.get(i).flowSnapshot().orElseThrow();assertTrue(owners.get(i).loaded(),owners.get(i).diagnostic().toString());assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status());
                var runtime=FlowRuntime.restore(saved.program(),saved.state(),limits.get(i).hardware());
                long ram=runtime.retainedUnits()+saved.initialInputs().storageUnits()+saved.control().storageUnits()+saved.receipts().stream().mapToLong(r->r.value().storageUnits()).sum()+saved.pinUnits()+saved.tasks().metadataUnits();
                long work=saved.state().work();for(var task:saved.tasks().contexts()){assertNotEquals(FlowRuntime.Status.FAILED,task.status(),task.diagnostic().toString());ram+=FlowRuntime.restore(saved.program(),task,limits.get(i).hardware()).retainedUnits()-runtime.sharedUnits();work+=task.work();}
                peakRam[i]=Math.max(peakRam[i],ram);assertTrue(ram<=limits.get(i).hardware().memoryUnits(),"RAM owner "+i+" units="+ram);peakWork[i]=Math.max(peakWork[i],work-previous[i]);assertTrue(work-previous[i]<=limits.get(i).hardware().workPerSlice(),"Work owner "+i+" delta="+(work-previous[i]));previous[i]=work;
            }
            assertEquals(main,f.computer.flowSnapshot().orElseThrow().state(),"Task waits never change main continuation or configured output");
            if(f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status()==FlowRuntime.Status.COMPLETE)break;
            if(tick%1000==0)java.nio.file.Files.writeString(java.nio.file.Path.of(".superpowers/sdd/2026-10-03-biocomputing-player-delivery/terminal-library-input-progress.txt"),"tick="+tick+" readonly="+readonly+" operations="+operations+" task="+f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst().status());
            if(tick==13999)fail("Bounded terminal write did not finish: "+f.computer.flowSnapshot().orElseThrow().tasks().contexts().getFirst()+" operations="+operations);
        }
        if(restore)assertTrue(restored);
        var before=f.computer.flowSnapshot().orElseThrow();var view=f.read();f.read();assertEquals(before,f.computer.flowSnapshot().orElseThrow());
        var row=(DataValue.RecordValue)((DataValue.ListValue)view.roots().field("tasks")).values().getFirst();var result=(DataValue.RecordValue)row.field("result");
        assertEquals(new DataValue.TextValue("COMPLETE"),row.field("phase"));assertEquals(DataValue.NullValue.INSTANCE,row.field("failure"));
        assertEquals(new DataValue.BooleanValue(!readonly),result.field("success"));
        if(readonly){assertNotNull(rawFailure);assertEquals(rawFailure,result.field("error"));assertEquals(DataValue.NullValue.INSTANCE,result.field("read_result"));assertEquals(new DataValue.IntegerValue(43),source.media.read(source.book).get("reading").value());assertEquals(0,source.commits);assertEquals(1,Collections.frequency(operations,"write"));assertFalse(operations.contains("read"));}
        else {assertEquals(new DataValue.TextValue(typed),((DataValue.RecordValue)result.field("read_result")).field("value"));assertEquals(new DataValue.TextValue(typed),source.media.read(source.book).get("reading").value());assertEquals(DataValue.NullValue.INSTANCE,result.field("error"));assertEquals(1,source.commits);assertEquals(1,source.releases);assertTrue(head.result().isEmpty());assertEquals(1,Collections.frequency(operations,"write"));assertEquals(1,Collections.frequency(operations,"read"));assertEquals(5,Collections.frequency(operations,"ack"));}
        // Inspecting the value tree is read-only; only an explicit claim on the newly frozen row retires it.
        assertEquals(result,new ValueTree(record("result",result)).inputs().field("result"));assertEquals(before,f.computer.flowSnapshot().orElseThrow());
        if(claim) {
        assertEquals("ok",f.apply(ComputerControlSession.Action.TERMINAL_CLAIM,new TerminalTaskClaim(f.intent,0).encode()).code());
        var after=f.computer.flowSnapshot().orElseThrow();assertTrue(after.tasks().contexts().isEmpty());assertEquals(main,after.state());assertTrue(after.tasks().slots().entries().getFirst().retired());
        f.read();assertEquals("ok",f.submit("explicit distinct next operation, not a retry"));
        var reused=f.computer.flowSnapshot().orElseThrow();assertEquals(1,reused.tasks().contexts().size());
        assertEquals(before.tasks().slots().entries().getFirst().incarnation()+1,reused.tasks().slots().entries().getFirst().incarnation());
        }
        System.out.println("terminal four-owner readonly="+readonly+" restore="+restored+" RAM peaks="+Arrays.toString(peakRam)+" work/tick="+Arrays.toString(peakWork)+" operations="+operations);
    }
}

