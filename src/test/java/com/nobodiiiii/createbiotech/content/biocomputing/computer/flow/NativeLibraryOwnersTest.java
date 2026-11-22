package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
public class NativeLibraryOwnersTest {
 static final BiocomputingConfig CONFIG=new BiocomputingConfig(new net.neoforged.neoforge.common.ModConfigSpec.Builder());
 public static ComputerHostLimits limits(String profession,int level){return CONFIG.select(new ResidentHardwareIdentity(ResourceLocation.parse("minecraft:villager"),ResourceLocation.parse("minecraft:"+profession),level),false).limits();}
 static ProgramDocument document(String role){var path="/assets/create_biotech/biocomputing/examples/library_read_"+role+".bcp";try(var in=NativeLibraryOwnersTest.class.getResourceAsStream(path)){if(in==null)throw new IllegalStateException("Missing public resource "+path);return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim()),SerialFlowHostTest.ARCHIVE);}catch(java.io.IOException e){throw new RuntimeException(e);}}
 static ComputerBlockHost install(ProgramDocument d,ComputerHostLimits l,SerialDevice serial){var h=new ComputerBlockHost(()->{},p->{},serial);var body=d.flow().orElseThrow();h.installFlow(body.program(d.archive()),body.initial(),l,true);return h;}
 static SerialDevice device(ComputerHostLimits l){return new SerialDevice(l::serial,()->true,()->CONFIG.serialCodec(false),()->{});}
 @Test void lowerResidentRefusesExplicitInstallAndPreservesItsPreviousImage(){var l=limits("cleric",5);var h=new ComputerBlockHost(()->{});h.installFlow(new FlowProgram(Map.of(),List.of()).withAssembly(),record(),l,true);var before=h.write();var d=document("a");assertThrows(FlowPins.Unavailable.class,()->h.installFlow(d.flow().orElseThrow().program(d.archive()),d.flow().orElseThrow().initial(),limits("cleric",4),true));assertEquals(before,h.write());}
 @Test void realOwnersReadOverFiniteSerialAndExplicitReversingXBusWithoutPooledResources() throws Exception {run(false);}
 @Test void mixedAuthoredCallsResumeAtPeriodicTickSamplesWithoutDuplicateMechanicalAdmissions() throws Exception {run(true);}
 static void run(boolean resume) throws Exception {
  var la=limits("cleric",5);var lb=limits("cleric",5);var lc=limits("cleric",5);
  assertEquals(new ResourceLimits(256,65536,64),la.hardware());assertEquals(la.hardware(),lb.hardware());assertEquals(256,lc.hardware().programUnits());
  var wireB=device(lb);wireB.configure("s0",Direction.EAST,true);var wireL=device(lb);wireL.configure("s0",Direction.WEST,true);
  var a=install(document("a"),la,null);var b=install(document("b"),lb,wireB);var c=install(document("c"),lc,null);
  a.setPaused(false,la,true);b.setPaused(false,lb,true);c.setPaused(false,lc,true);
  var network=new SerialNetwork();var posB=new BlockPos(0,0,0);var cable=new BlockPos(1,0,0);var posL=new BlockPos(2,0,0);var devices=Map.of(posB,wireB,posL,wireL);
  var world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(cable)?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
  var source=new LibraryReadHead.Source(){public void validateTrack(LibraryTrackAddress t){assertEquals(new LibraryTrackAddress(1,0,0),t);}public LibraryReadHead.Sample read(LibraryTrackAddress t,int slot,String entry){assertEquals(2,slot);assertEquals("reading",entry);return new LibraryReadHead.Sample(record("book",17),record("value",43));}public void verify(LibraryTrackAddress t,int slot,String entry,DataValue.RecordValue fence){assertEquals(record("book",17),fence);}};
  var driveLimits=CONFIG.libraryDrive(false);var head=LibraryReadHead.State.empty();var admissions=new HashMap<String,Integer>();var requests=new HashSet<DataValue>();
  long[] peaks={0,0,0};boolean retainedBeforeAck=false;int restores=0;
  for(int tick=0;tick<10000;tick++) {
   var bs=Map.of("x0",NativeLibraryStartupTest.status(b,"x0",lb,a,"x0",la));var cs=Map.of("x0",NativeLibraryStartupTest.status(c,"x0",lc,b,"x1",lb));a.tick(la,true);b.tick(lb,true,bs);c.tick(lc,true,cs);
   a.exchange("x0",la,true,b,"x0",lb,true);b.exchange("x1",lb,true,c,"x0",lc,true);
   network.tick(world,devices,CONFIG.communication(false),CONFIG.serialMedium(false));
   head=LibraryReadHead.advance(head,source,driveLimits);
   if(wireL.status("s0").receive().frames()>0 && !Set.of("queued","transmitting").contains(wireL.status("s0").transmit())) {
    var received=wireL.prepareReceive("s0");var packet=(DataValue.RecordValue)received.result().field("value");var cmd=LibraryDriveCommand.decode(packet);assertTrue(requests.add(cmd.request()),"Every outgoing command is fresh, including busy polls");admissions.merge(cmd.operation(),1,Integer::sum);
    if(cmd.operation().equals("ack")&&head.sequence()==3){assertTrue(head.result().isPresent());var locals=a.flowSnapshot().orElseThrow().state().locals();assertEquals(record("value",43),locals.field("read_result"));retainedBeforeAck=true;}
    DataValue.RecordValue reply;
    try{var transition=cmd.apply(head,source,driveLimits);head=transition.state();reply=transition.reply();}catch(LibraryBookAccess.Failure f){reply=cmd.reply(head.sequence(),f.code(),DataValue.NullValue.INSTANCE);}
    assertTrue(received.publish());assertEquals("queued",wireL.send("s0",reply));
   }
   var owners=List.of(a,b,c);var capacities=List.of(la,lb,lc);
   for(int i=0;i<3;i++){var saved=owners.get(i).flowSnapshot().orElseThrow();assertNotEquals(FlowRuntime.Status.FAILED,saved.state().status(),saved.state().diagnostic().toString());assertTrue(owners.get(i).loaded(),owners.get(i).diagnostic().toString());var state=saved.state();var units=FlowRuntime.restore(saved.program(),state,capacities.get(i).hardware()).retainedUnits()+saved.initialInputs().storageUnits()+saved.control().storageUnits()+saved.receipts().stream().mapToLong(r->r.value().storageUnits()).sum()+saved.pinUnits();peaks[i]=Math.max(peaks[i],units);assertTrue(units<=capacities.get(i).hardware().memoryUnits());}
   if(resume&&tick%113==37){a=reload(a,la,null);b=reload(b,lb,wireB);c=reload(c,lc,null);restores++;}
   if(a.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
   if(tick%500==0)java.nio.file.Files.writeString(java.nio.file.Path.of(".superpowers/cache/assembly-library-read/owner-progress.txt"),"tick="+tick+" admissions="+admissions+"\n"+summary(a)+"\n"+summary(b)+"\n"+summary(c));
   if(tick==9999)fail("Query did not finish: "+summary(a)+" b="+summary(b)+" c="+summary(c));
  }
  var result=a.flowSnapshot().orElseThrow().state();assertEquals(new DataValue.BooleanValue(true),result.locals().field("success"));assertEquals(record("value",43),result.locals().field("read_result"));assertEquals(DataValue.NullValue.INSTANCE,result.locals().field("error"));assertTrue(retainedBeforeAck);assertEquals(1,admissions.get("seek"));assertEquals(1,admissions.get("select"));assertEquals(1,admissions.get("read"));assertEquals(3,admissions.get("ack"));assertEquals(3,head.sequence());assertTrue(head.result().isEmpty());if(resume)assertTrue(restores>0);
  var file=java.nio.file.Path.of(".superpowers/cache/assembly-library-read/owner-measurements-"+resume+".txt");java.nio.file.Files.writeString(file,"RAM peaks="+Arrays.toString(peaks)+" work="+result.work()+","+b.flowSnapshot().orElseThrow().state().work()+","+c.flowSnapshot().orElseThrow().state().work()+" admissions="+admissions+" restores="+restores);
 }
 static String summary(ComputerBlockHost h){var s=h.flowSnapshot().orElseThrow().state();return s.status()+" work="+s.work()+" pc="+s.assembly().orElseThrow().pc()+" locals="+EditorValueFormat.format(s.locals());}
 static ComputerBlockHost reload(ComputerBlockHost h,ComputerHostLimits l,SerialDevice serial){h.setPaused(true,l,true);var before=h.flowSnapshot().orElseThrow();var raw=h.write();h.unload();var next=new ComputerBlockHost(()->{},p->{},serial);next.read(raw);next.tick(l,true);assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(before,next.flowSnapshot().orElseThrow());next.setPaused(false,l,true);return next;}
}
