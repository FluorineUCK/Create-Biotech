package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.drive.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.serial.*;
import net.minecraft.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class NativeLibraryRecoveryTest {
 static ProgramDocument document(String role)throws Exception {try(var in=NativeLibraryRecoveryTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/examples/library_"+role+"_a.bcp")){assertNotNull(in,"Missing public native recovery example");return ProgramDocumentWire.decode(Base64.getDecoder().decode(new String(in.readAllBytes()).trim()),SerialFlowHostTest.ARCHIVE);}}
 @Test void publicInspectionInstallsAsNativeCompleteIndependentImage()throws Exception {for(var role:List.of("inspect","ack")){var d=document(role);var limits=NativeLibraryOwnersTest.limits("cleric",5);var image=d.flow().orElseThrow().program(d.archive()).compile(limits.hardware()).assembly().orElseThrow();assertTrue(image.codeWords()<=256);System.out.println(role+" codeWords="+image.codeWords()+" profile="+limits.hardware());var h=NativeLibraryOwnersTest.install(d,limits,null);assertTrue(h.flowSnapshot().orElseThrow().state().assembly().isPresent());}}
 static final LibraryTrackAddress TRACK=new LibraryTrackAddress(1,0,0);
 static final LibraryReadHead.Source SOURCE=new LibraryReadHead.Source(){public void validateTrack(LibraryTrackAddress t){fail("Recovery must not mutate mechanics");}public LibraryReadHead.Sample read(LibraryTrackAddress t,int s,String e){throw new AssertionError("Recovery must not read media");}public void verify(LibraryTrackAddress t,int s,String e,DataValue.RecordValue f){fail("Recovery must not verify media");}};
 static LibraryReadHead.State retained(String code){return new LibraryReadHead.State(7,Optional.of(TRACK),2,Optional.empty(),Optional.of(new LibraryReadHead.Result(code,code.equals("read")?record("value",43):DataValue.NullValue.INSTANCE)));}
 @Test void inspectionReadsRetainedResultWithoutConsumptionAndRestoresDuringXBusWait()throws Exception {var before=retained("read");var run=run("inspect",before,-1,true,false);assertEquals(before,run.head);assertEquals(List.of("status","result"),run.operations);assertEquals(new DataValue.IntegerValue(7),run.locals.field("observed_sequence"));assertEquals("result_pending",text(((DataValue.RecordValue)run.locals.field("status_reply")).field("code")));assertEquals("read",text(((DataValue.RecordValue)run.locals.field("result_reply")).field("code")));assertEquals(DataValue.NullValue.INSTANCE,run.locals.field("error"));}
 @Test void inspectionPreservesRawDeviceResultFailure()throws Exception {var before=retained("grant_revoked");var run=run("inspect",before,-1,false,false);assertEquals(before,run.head);assertEquals("grant_revoked",text(((DataValue.RecordValue)run.locals.field("result_reply")).field("code")));}
 @Test void idleAndBusyAreSingleObservationsNotPollingOrAcknowledgement()throws Exception {var pending=new LibraryReadHead.Pending(LibraryReadHead.Action.SEEK,TRACK,-1,"",1000,1000,Optional.empty());for(var head:List.of(LibraryReadHead.State.empty(),new LibraryReadHead.State(7,Optional.empty(),-1,Optional.of(pending),Optional.empty()))){var run=run("inspect",head,-1,false,false);assertEquals(head,run.head);assertEquals(List.of("status"),run.operations);assertEquals(DataValue.NullValue.INSTANCE,run.locals.field("result_reply"));assertEquals(head.sequence()==0?"idle":"busy",text(((DataValue.RecordValue)run.locals.field("status_reply")).field("code")));}}
 @Test void explicitAckConsumesOnlyExactSequenceNeverAdoptsLatest()throws Exception {for(int seq:List.of(6,7)){var before=retained("read");var run=run("ack",before,seq,true,false);assertEquals(List.of("ack"),run.operations);assertEquals(new DataValue.BooleanValue(seq==7),run.locals.field("acknowledged"));assertEquals(seq==7?Optional.empty():before.result(),run.head.result());assertEquals(""+(seq==7?"acknowledged":"stale_operation"),text(((DataValue.RecordValue)run.locals.field("ack_reply")).field("code")));}}
 @Test void invalidDefaultRejectsBeforeAnyTransmit()throws Exception {var before=retained("read");var run=run("ack",before,-1,false,false);assertEquals(List.of(),run.operations);assertEquals(before,run.head);assertEquals(new DataValue.TextValue("invalid_sequence"),run.locals.field("error"));assertEquals(new DataValue.BooleanValue(false),run.locals.field("acknowledged"));assertEquals(DataValue.NullValue.INSTANCE,run.locals.field("ack_reply"));}
 @Test void rawStatusDeviceFailureIsNotBusinessSuccess()throws Exception {var before=retained("read");var run=run("inspect",before,-1,false,true);assertEquals(before,run.head);assertEquals(List.of("status"),run.operations);assertEquals("grant_revoked",text(((DataValue.RecordValue)run.locals.field("status_reply")).field("code")));assertEquals(DataValue.NullValue.INSTANCE,run.locals.field("result_reply"));}
 // Scripted B scope: coordinator output handling only; no Library/device authorization claim.
 @Test void rawTransportFailuresSurviveWithoutAcknowledgementOrInventedObservation()throws Exception {
  var raw=record("status","disconnected","detail",record("evidence",17));
  for(var role:List.of("inspect","ack")){var locals=scripted(role,record("ok",false,"value",raw),true);assertEquals(raw,locals.field("error"));assertEquals(DataValue.NullValue.INSTANCE,locals.field(role.equals("inspect")?"status_reply":"ack_reply"));if(role.equals("ack"))assertEquals(new DataValue.BooleanValue(false),locals.field("acknowledged"));}
 }
 @Test void ackNeverInfersSuccessFromBusyOrWrongSequenceAcknowledged()throws Exception {
  for(var reply:List.of(record("code","busy","sequence",7,"value",null),record("code","acknowledged","sequence",8,"value",null))){var locals=scripted("ack",record("ok",true,"value",reply),false);assertEquals(reply,locals.field("ack_reply"));assertEquals(new DataValue.BooleanValue(false),locals.field("acknowledged"));}
 }
 @Test void lostAckReplyStaysWaitingAfterRestoreWithoutSuccessOrRetry()throws Exception {
  var limits=NativeLibraryOwnersTest.limits("cleric",5);var a=NativeLibraryOwnersTest.install(withSequence(document("ack"),7),limits,null);
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of(),List.of(new FlowProgram.Read("command","x0","command",PACKET))).withAssembly(),record())));
  var b=NativeLibraryOwnersTest.install(workspace.snapshot(),limits,null);a.setPaused(false,limits,true);b.setPaused(false,limits,true);
  for(int tick=0;tick<500;tick++){a.tick(limits,true);b.tick(limits,true);a.exchange("x0",limits,true,b,"x0",limits,true);if(tick==100)a=NativeLibraryOwnersTest.reload(a,limits,null);}
  assertEquals(FlowRuntime.Status.WAITING_READ,a.flowSnapshot().orElseThrow().state().status());assertEquals(FlowRuntime.Status.COMPLETE,b.flowSnapshot().orElseThrow().state().status());var cmd=LibraryDriveCommand.decode((DataValue.RecordValue)b.flowSnapshot().orElseThrow().state().locals().field("command"));assertEquals("ack",cmd.operation());assertEquals(7,cmd.sequence());assertFalse(a.flowSnapshot().orElseThrow().state().locals().fields().containsValue(new DataValue.BooleanValue(true)));
 }
 static ProgramDocument withSequence(ProgramDocument doc,int seq){var body=doc.flow().orElseThrow();var workspace=new ProgramWorkspace(doc);workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),record("target",record("x",0,"y",0,"z",0),"sequence",seq),body.canvas(),body.inline(),body.execution())));return workspace.snapshot();}
 static DataValue.RecordValue scripted(String role,DataValue.RecordValue outcome,boolean restore)throws Exception {
  var limits=NativeLibraryOwnersTest.limits("cleric",5);var doc=document(role);if(role.equals("ack"))doc=withSequence(doc,7);
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(new FlowProgram(Map.of("response",OUTCOME,"delay",DataType.Scalar.INTEGER),List.of(new FlowProgram.Read("command","x0","command",PACKET),new FlowProgram.Sleep("delay","delay"),new FlowProgram.Write("response","x0","response"))).withAssembly(),record("response",outcome,"delay",200))));
  var a=NativeLibraryOwnersTest.install(doc,limits,null);var b=NativeLibraryOwnersTest.install(workspace.snapshot(),limits,null);a.setPaused(false,limits,true);b.setPaused(false,limits,true);
  for(int tick=0;tick<2000;tick++){a.tick(limits,true);b.tick(limits,true);a.exchange("x0",limits,true,b,"x0",limits,true);if(tick==60&&restore){assertNotEquals(FlowRuntime.Status.COMPLETE,a.flowSnapshot().orElseThrow().state().status());a=NativeLibraryOwnersTest.reload(a,limits,null);b=NativeLibraryOwnersTest.reload(b,limits,null);}assertNotEquals(FlowRuntime.Status.FAILED,a.flowSnapshot().orElseThrow().state().status(),a.diagnostic().toString());if(a.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)return a.flowSnapshot().orElseThrow().state().locals();}throw new AssertionError("Scripted transport outcome not returned");
 }
 static String text(DataValue v){return ((DataValue.TextValue)v).value();}
 record Run(LibraryReadHead.State head,List<String> operations,DataValue.RecordValue locals){}
 // Real independently installed A/B/C images, finite serial cable and device semantic transitions.
 // Mechanical clock is deliberately held fixed: inspection cannot itself advance or cancel mechanics.
 static Run run(String role,LibraryReadHead.State head,int seq,boolean restore,boolean rejectStatus)throws Exception {
  var la=NativeLibraryOwnersTest.limits("cleric",5);var lb=NativeLibraryOwnersTest.limits("cleric",5);var lc=NativeLibraryOwnersTest.limits("cleric",5);
  var wireB=NativeLibraryOwnersTest.device(lb);wireB.configure("s0",Direction.EAST,true);var wireL=NativeLibraryOwnersTest.device(lb);wireL.configure("s0",Direction.WEST,true);
  var doc=document(role);if(role.equals("ack")&&seq!=-1){var body=doc.flow().orElseThrow();var workspace=new ProgramWorkspace(doc);workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),body.steps(),record("target",record("x",0,"y",0,"z",0),"sequence",seq),body.canvas(),body.inline(),body.execution())));doc=workspace.snapshot();}
  var a=NativeLibraryOwnersTest.install(doc,la,null);var b=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("b"),lb,wireB);var c=NativeLibraryOwnersTest.install(NativeLibraryOwnersTest.document("c"),lc,null);
  for(var owner:List.of(a,b,c))owner.setPaused(false,owner==a?la:owner==b?lb:lc,true);
  var network=new SerialNetwork();var posB=new BlockPos(0,0,0);var cable=new BlockPos(1,0,0);var posL=new BlockPos(2,0,0);var devices=Map.of(posB,wireB,posL,wireL);
  var world=new CableDiscovery.World(){public boolean loaded(BlockPos p){return true;}public CableDiscovery.Cell cell(BlockPos p){return new CableDiscovery.Cell(p.equals(cable)?CableDiscovery.Kind.CABLE:devices.containsKey(p)?CableDiscovery.Kind.ENDPOINT:CableDiscovery.Kind.EMPTY,null);}};
  var operations=new ArrayList<String>();boolean restored=false;
  for(int tick=0;tick<6000;tick++){
   a.tick(la,true);b.tick(lb,true,Map.of("x0",NativeLibraryStartupTest.status(b,"x0",lb,a,"x0",la)));c.tick(lc,true,Map.of("x0",NativeLibraryStartupTest.status(c,"x0",lc,b,"x1",lb)));
   a.exchange("x0",la,true,b,"x0",lb,true);b.exchange("x1",lb,true,c,"x0",lc,true);network.tick(world,devices,NativeLibraryOwnersTest.CONFIG.communication(false),NativeLibraryOwnersTest.CONFIG.serialMedium(false));
   if(tick==50&&restore){assertEquals(FlowRuntime.Status.WAITING_READ,a.flowSnapshot().orElseThrow().state().status());a=NativeLibraryOwnersTest.reload(a,la,null);b=NativeLibraryOwnersTest.reload(b,lb,wireB);c=NativeLibraryOwnersTest.reload(c,lc,null);restored=true;}
   if(tick>150&&wireL.status("s0").receive().frames()>0&&!Set.of("queued","transmitting").contains(wireL.status("s0").transmit())){
    var received=wireL.prepareReceive("s0");var cmd=LibraryDriveCommand.decode((DataValue.RecordValue)received.result().field("value"));operations.add(cmd.operation());assertEquals(record("x",0,"y",0,"z",0),cmd.target());if(!cmd.operation().equals("status"))assertEquals(role.equals("ack")?seq:7,cmd.sequence());DataValue.RecordValue reply;
    try{if(rejectStatus)throw new LibraryBookAccess.Failure("grant_revoked");var transition=cmd.apply(head,SOURCE,NativeLibraryOwnersTest.CONFIG.libraryDrive(false));head=transition.state();reply=transition.reply();}catch(LibraryBookAccess.Failure f){reply=cmd.reply(head.sequence(),f.code(),DataValue.NullValue.INSTANCE);}
    assertTrue(received.publish());assertEquals("queued",wireL.send("s0",reply));
   }
   for(var owner:List.of(a,b,c))assertNotEquals(FlowRuntime.Status.FAILED,owner.flowSnapshot().orElseThrow().state().status(),owner.diagnostic().toString());
   if(a.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE){if(restore)assertTrue(restored);return new Run(head,List.copyOf(operations),a.flowSnapshot().orElseThrow().state().locals());}
  }
  throw new AssertionError("Recovery did not complete: "+NativeLibraryOwnersTest.summary(a));
 }
}
