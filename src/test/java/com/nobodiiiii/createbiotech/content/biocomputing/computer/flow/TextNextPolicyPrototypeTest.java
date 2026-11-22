package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TextNextPolicyPrototypeTest {
 static final DataType.RecordType REPLY=new DataType.RecordType(Map.of("ok",DataType.Scalar.BOOLEAN));
 static final DataType.RecordType REQUEST=new DataType.RecordType(Map.of("text",DataType.Scalar.TEXT));
 static String source(){try(var in=TextNextPolicyPrototypeTest.class.getResourceAsStream("/assets/create_biotech/biocomputing/text_next_policy_service.asm")){return new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new RuntimeException(e);}}
 static FlowProgram service(){return service(source());}
 static FlowProgram service(String source){Map<String,DataType> in=Map.of("poll_ticks",DataType.Scalar.INTEGER);var key=new ModuleDefinition.Key("canonical_policy_test",1);return new FlowProgram(in,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("poll_ticks","poll_ticks"),Map.of())),new FlowProgram.Procedure("def",key,in,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),new FlowProgram.EndProcedure("end"))).withAssembly();}
 @Test void completeServiceFitsRealDefaultsBeforeAnyRoleIsPublished(){
  var limits=NativeLibraryOwnersTest.limits("cleric",5);assertEquals(new ResourceLimits(256,65536,64),limits.hardware());Map<String,DataType> in=Map.of("poll_ticks",DataType.Scalar.INTEGER);
  long body=ProcedureAssembler.assemble(new AssemblySource(source()),in,Map.of(),Map.of(),65536,10000).program().codeWords();
  long full=service().usage(limits.hardware()).programUnits();System.out.println("TEXT_NEXT_POLICY body="+body+" full="+full+" hardware="+limits.hardware());assertTrue(full<=256);
 }

 @Test void proposedUnpublishedECallIsMeasuredWithItsRealRecordEnvelope() throws Exception {
  String old;try(var in=getClass().getResourceAsStream("/assets/create_biotech/biocomputing/history/routed_production_e_v1.asm")){old=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
  var next=old.replace("REG zero INTEGER\n", "").replace("REG bound INTEGER\n", "").replace("REG length INTEGER\n", "").replace("CONST zero 0\n", "").replace("CONST bound 26\n", "").replace("REG checked_text CheckedText", "TYPE Policy RECORD ok BOOLEAN\nREG policy Policy\nREG checked_text CheckedText");
  next=next.replace("TEXT.LENGTH length address\nGREATER_INTEGER flag length zero\nJFALSE flag finish\nLESS_INTEGER flag length bound\nJFALSE flag finish", "XBUS.WRITE x1 confirmed\npolicy_wait:\nXBUS.STATUS status x1\nEQUAL_INTEGER flag status fifteen\nJFALSE flag policy_pause\nXBUS.READ policy x1\nFIELD flag policy ok\nJFALSE flag finish")+"\npolicy_pause:\nSLEEP poll_ticks\nJMP policy_wait\n";
  assertNotEquals(old,next);Map<String,DataType> in=Map.of("poll_ticks",DataType.Scalar.INTEGER);
  long body=ProcedureAssembler.assemble(new AssemblySource(next),in,Map.of(),Map.of(),65536,10000).program().codeWords();
  var key=new ModuleDefinition.Key("e_test",1);var p=new FlowProgram(in,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("poll_ticks","poll_ticks"),Map.of())),new FlowProgram.Procedure("def",key,in,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(next))),new FlowProgram.EndProcedure("end"))).withAssembly();
  long full=p.usage(new ResourceLimits(10000,65536,8)).programUnits();
  System.out.println("TEXT_NEXT_PROPOSED_E body="+body+" full="+full+" (TEST ONLY, record reply requires FIELD; historical E@1=254)");assertEquals(271,full);
  String blocking=next.replace("policy_wait:\nXBUS.STATUS status x1\nEQUAL_INTEGER flag status fifteen\nJFALSE flag policy_pause\n", "").replace("\npolicy_pause:\nSLEEP poll_ticks\nJMP policy_wait\n", "");
  var blockingProgram=new FlowProgram(in,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("poll_ticks","poll_ticks"),Map.of())),new FlowProgram.Procedure("def",key,in,Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource(blocking))),new FlowProgram.EndProcedure("end"))).withAssembly();
  long blockingFull=blockingProgram.usage(NativeLibraryOwnersTest.limits("cleric",5).hardware()).programUnits();System.out.println("TEXT_NEXT_PROPOSED_E direct blocking READ full="+blockingFull+" (TEST ONLY; real client below uses same WRITE/READ rendezvous)");assertEquals(254,blockingFull);
  String stateWorker=source().replace("TYPE Request RECORD text TEXT", "TYPE Request RECORD address TEXT device TEXT session TEXT revision INTEGER").replace("FIELD text request text", "FIELD text request address");long stateBody=ProcedureAssembler.assemble(new AssemblySource(stateWorker),in,Map.of(),Map.of(),65536,256).program().codeWords();long stateFull=service(stateWorker).usage(NativeLibraryOwnersTest.limits("cleric",5).hardware()).programUnits();System.out.println("TEXT_NEXT_POLICY proposed State-envelope body="+stateBody+" full="+stateFull);assertEquals(208,stateBody);assertEquals(217,stateFull);
 }

 static boolean oracle(String text){try{return com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules.requireNormalized(text).equals(text);}catch(IllegalArgumentException e){return false;}}
 static long owned(ComputerBlockHost owner,ComputerHostLimits limits){var s=owner.flowSnapshot().orElseThrow();return s.initialInputs().storageUnits()+s.control().storageUnits()+s.memory().storageUnits()+s.pinUnits()+s.tasks().metadataUnits()+s.state().controlUnits()+s.program().usage(limits.hardware()).constantUnits();}
 @Test void completeOrdinaryServiceMatchesJavaPolicyAcrossUnicodeAndColdOwnerBoundaries(){
  var values=new ArrayList<String>(List.of(""," "," bad ","bad\nroute","workshop","a b","a\u0000b","a\u007fb","a\u0085b","a\u009fb","\u2003\u2003","\u2003x\u2003","\u00a0","\u00a0x\u00a0","\ud83d\ude00".repeat(13),"\ud83d\ude00".repeat(12)+"x","x".repeat(25),"x".repeat(26),"\ud800x\udc00","\tbad","bad\t","\u001fx","x\u001f"));
  var random=new Random(7);int[] points={0,9,31,32,65,160,0x85,0x2003,0x2007,0x202f,0x3000,0x1f600,0xd800,0xdc00};for(int i=0;i<32;i++){var v=new StringBuilder();for(int j=0,n=random.nextInt(28);j<n;j++)v.appendCodePoint(points[random.nextInt(points.length)]);values.add(v.toString());}
  var limits=NativeLibraryOwnersTest.limits("cleric",5);long maxCommitted=0,maxTextReserve=0,maxWork=0;int restores=0;
  for(long budget:new long[]{1,8})for(String text:values){
   var selected=NativeLibraryOwnersTest.limits("cleric",5);limits=new ComputerHostLimits(new ResourceLimits(256,65536,budget),selected.archive(),selected.mailbox(),selected.pins(),selected.serial(),selected.taskSlots(),selected.isa1a(),selected.isa1b());
   var p=service();var worker=new ComputerBlockHost(()->{});worker.installFlow(p,new DataValue.RecordValue(Map.of("poll_ticks",new DataValue.IntegerValue(1))),limits,true);worker.setPaused(false,limits,true);
   // Before a peer starts, the ordinary status/sleep branch remains live, with no phantom reply.
   for(int i=0;i<30;i++)worker.tick(limits,true);assertNotEquals(FlowRuntime.Status.FAILED,worker.flowSnapshot().orElseThrow().state().status());
   var clientProgram=new FlowProgram(Map.of("request",REQUEST),List.of(new FlowProgram.Write("send","x0","request"),new FlowProgram.Read("receive","x0","reply",REPLY))).withAssembly();
   var request=new DataValue.RecordValue(Map.of("text",new DataValue.TextValue(text)));var input=new DataValue.RecordValue(Map.of("request",request));var client=new ComputerBlockHost(()->{});client.installFlow(clientProgram,input,limits,true);client.setPaused(false,limits,true);
   for(int tick=0;;tick++){
    assertTrue(tick<2000,"service made no progress at budget="+budget+", UTF16="+text.length()+" worker="+worker.flowSnapshot().orElseThrow().state().status()+"/"+worker.flowSnapshot().orElseThrow().state().work()+"/pc"+worker.flowSnapshot().orElseThrow().state().assembly().orElseThrow().pc()+" client="+client.flowSnapshot().orElseThrow().state().status()+"/"+client.flowSnapshot().orElseThrow().state().work()+"/"+client.flowSnapshot().orElseThrow().state().diagnostic());var state=worker.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());maxCommitted=Math.max(maxCommitted,owned(worker,limits));var a=state.assembly().orElseThrow();if(FlowXBus.image(p).code().get(a.pc()).instruction() instanceof RegisterProgram.TextNext)maxTextReserve=Math.max(maxTextReserve,owned(worker,limits)+45);
    worker.tick(limits,true,Map.of("x0",NativeLibraryStartupTest.status(worker,"x0",limits,client,"x0",limits)));client.tick(limits,true);client.exchange("x0",limits,true,worker,"x0",limits,true);
    if(client.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
    if(tick%7==0){worker.setPaused(true,limits,true);var saved=worker.write();var next=new ComputerBlockHost(()->{});next.read(saved);next.tick(limits,true);assertTrue(next.loaded(),next.diagnostic().toString());assertTrue(saved.equals(next.write()),"Paused cold service must retain its exact saved continuation");worker=next;worker.setPaused(false,limits,true);
     client.setPaused(true,limits,true);var clientSaved=client.write();var clientCold=new ComputerBlockHost(()->{});clientCold.read(clientSaved);clientCold.tick(limits,true);assertTrue(clientCold.loaded(),clientCold.diagnostic().toString());assertTrue(clientSaved.equals(clientCold.write()),"Client WRITE/READ receipt and continuation must survive cold pause");client=clientCold;client.setPaused(false,limits,true);restores++;}
   }
   var state=client.flowSnapshot().orElseThrow().state();assertEquals(request,state.locals().field("request"));var reply=(DataValue.RecordValue)state.locals().field("reply");assertEquals(new DataValue.BooleanValue(oracle(text)),reply.field("ok"),"UTF16 length="+text.length()+" points="+text.codePointCount(0,text.length()));maxWork=Math.max(maxWork,worker.flowSnapshot().orElseThrow().state().work());
  }
  System.out.println("TEXT_NEXT_POLICY cases="+(values.size()*2)+" peakCommitted="+maxCommitted+" maxTextInstructionPreflight="+maxTextReserve+" maxWork="+maxWork+" coldRestores="+restores);assertTrue(maxTextReserve>0&&maxTextReserve<=65536);
 }
}
