package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;

/** Actual public E@2/F@1, explicit rendezvous and persisted receipts; no test-side policy result injection. */
class RoutedPolicyBoundaryTest {
 static final DataType.RecordType STATE=new DataType.RecordType(Map.of("address",DataType.Scalar.TEXT,"device",DataType.Scalar.TEXT,"session",DataType.Scalar.TEXT,"revision",DataType.Scalar.INTEGER));
 static final DataType.RecordType REQUEST=new DataType.RecordType(Map.of("r",DataType.Scalar.ANY,"e",DataType.Scalar.TEXT,"s",DataType.Scalar.BOOLEAN));
 static final DataType.RecordType DECISION=new DataType.RecordType(Map.of("c",DataType.Scalar.TEXT,"a",DataType.Scalar.ANY));
 static DataValue.TextValue text(String value){return new DataValue.TextValue(value);}
 static boolean oracle(String value){try{return RecipeAddressRules.requireNormalized(value).equals(value);}catch(IllegalArgumentException invalid){return false;}}
 static final class Harness {
  final ComputerHostLimits limits;final ComputerBlockHost[] h=new ComputerBlockHost[3];
  final long[] peaks=new long[3],nextPreflight=new long[3];
  XBusHistorySavedData history=new XBusHistorySavedData();int coldRestores;
  DataValue.RecordValue request,state;
  Harness(long work)throws Exception{
   var selected=NativeLibraryOwnersTest.limits("cleric",5);limits=new ComputerHostLimits(new ResourceLimits(256,65536,work),selected.archive(),selected.mailbox(),selected.pins(),selected.serial(),selected.taskSlots(),selected.isa1a(),selected.isa1b());
   for(int i=1;i<=2;i++){var d=RoutedProductionDocuments.published(i==1?"e":"f");var body=d.flow().orElseThrow();h[i]=new ComputerBlockHost(()->{});h[i].installFlow(body.program(d.archive()),body.initial(),limits,true);}
   // F is explicitly started first and waits without E, not a free remote function.
   h[2].setPaused(false,limits,true);for(int n=0;n<40;n++)h[2].tick(limits,true,Map.of("x0",0));assertNotEquals(FlowRuntime.Status.FAILED,h[2].flowSnapshot().orElseThrow().state().status());
   assertFalse(h[2].flowSnapshot().orElseThrow().state().locals().fields().containsKey("reply"));h[1].setPaused(false,limits,true);
  }
  void begin(String route){
   state=record("address",text(route),"device",text(new UUID(1,2).toString()),"session",text(new UUID(3,4).toString()),"revision",1);
   var raw=record("code",text("ok"),"state",state);request=record("r",raw,"e",text(route),"s",new DataValue.BooleanValue(false));
   var p=new FlowProgram(Map.of("request",REQUEST),List.of(new FlowProgram.Write("send","x0","request"),new FlowProgram.Read("reply","x0","decision",DECISION))).withAssembly();
   if(h[0]==null)h[0]=new ComputerBlockHost(()->{});else{h[0].setPaused(true,limits,true);h[0].reset(limits,true);}
   h[0].installFlow(p,record("request",request),limits,true);h[0].setPaused(false,limits,true);
  }
  boolean eWaiting(FlowRuntime.Status status){var saved=h[1].flowSnapshot().orElseThrow();return saved.state().status()==status&&FlowXBus.pin(saved.program(),saved.state()).equals("x1");}
  void sample(){for(int i=0;i<3;i++){if(h[i]==null||!h[i].loaded())continue;var s=h[i].flowSnapshot().orElseThrow();peaks[i]=Math.max(peaks[i],RoutedPolicyMemoryProbe.committed(s,limits));nextPreflight[i]=Math.max(nextPreflight[i],RoutedPolicyMemoryProbe.observedNextPreflight(s,limits));assertTrue(peaks[i]<=65536);}}
  // False withholds fixture rendezvous; it is NOT a physical topology-change notification.
  void step(boolean advancePolicy,boolean reload){
   sample();h[0].tick(limits,true);h[1].tick(limits,true,Map.of("x0",NativeLibraryStartupTest.status(h[1],"x0",limits,h[0],"x0",limits)));
   if(h[2].loaded())h[2].tick(limits,true,Map.of("x0",advancePolicy?NativeLibraryStartupTest.status(h[2],"x0",limits,h[1],"x1",limits):0));
   NativeLibraryServiceSerialTest.exchange(h[0],"x0",limits,h[1],"x0",limits,history);
   if(advancePolicy&&h[2].loaded())NativeLibraryServiceSerialTest.exchange(h[1],"x1",limits,h[2],"x0",limits,history);
   for(var c:h)assertNotEquals(FlowRuntime.Status.FAILED,c.flowSnapshot().orElseThrow().state().status(),c.flowSnapshot().orElseThrow().state().diagnostic().toString());sample();
   if(reload){for(int i=0;i<3;i++)if(h[i].loaded())h[i]=NativeLibraryOwnersTest.reload(h[i],limits,null);history=XBusHistorySavedData.load(history.save(new CompoundTag(),RegistryAccess.EMPTY),RegistryAccess.EMPTY);coldRestores++;}
  }
  DataValue.RecordValue finish(boolean reload){
   for(int n=0;n<3000;n++){step(true,reload&&n%3==0);var s=h[0].flowSnapshot().orElseThrow().state();if(s.status()==FlowRuntime.Status.COMPLETE){assertEquals(request,s.locals().field("request"));assertEquals(state,h[2].flowSnapshot().orElseThrow().state().locals().field("request"),"Real State crosses E.x1/F.x0 unchanged");return (DataValue.RecordValue)s.locals().field("decision");}}
   return fail("Public E/F did not finish");
  }
 }
 @Test void unicodePolicyAndFreshAlternatingResultsCrossPublicE2F1WithoutMutatingRawCopies()throws Exception{
  var cases=List.of("workshop"," bad ","\u2003x\u2003","","\u00a0","x".repeat(25),"\ud83d\ude00".repeat(13),"\u2003\u2003","a\u0085b","a\u007fb","bad\nroute","a b","\ud83d\ude00".repeat(12)+"x","\ud800x\udc00","final");
  for(long budget:new long[]{1,8}){
   var rig=new Harness(budget);long previousWork=-1;
   for(String route:cases){rig.begin(route);var decision=rig.finish(true);assertEquals(text(oracle(route)?"route_matched":"route_unavailable"),decision.field("c"),"UTF16="+route.length());assertEquals(text(route),decision.field("a"));long work=rig.h[2].flowSnapshot().orElseThrow().state().work();assertTrue(work>previousWork);previousWork=work;}
   System.out.println("ROUTED_POLICY_PUBLIC budget="+budget+" cases="+cases.size()+" sampledFullOwnerCommitted="+Arrays.toString(rig.peaks)+" observedNextPreflight="+Arrays.toString(rig.nextPreflight)+" pairedTripleRestores="+rig.coldRestores);
   if(budget==1)assertTrue(rig.nextPreflight[2]>0&&rig.nextPreflight[2]<=65536);
  }
 }
 @Test void withheldHandoverOrUnloadedOwnerWaitsForFreshPolicyWithoutStaleSuccess()throws Exception{
  for(boolean unload:List.of(false,true)){
   var rig=new Harness(1);rig.begin("workshop");assertEquals(text("route_matched"),rig.finish(true).field("c"));rig.begin(" bad ");
   for(int n=0;!rig.eWaiting(FlowRuntime.Status.WAITING_READ);n++){assertTrue(n<1000);rig.step(true,false);}
   assertFalse(rig.h[0].flowSnapshot().orElseThrow().state().locals().fields().containsKey("decision"),"Prior E/F success is not a new public reply");
   net.minecraft.nbt.Tag saved=null;if(unload){rig.h[2].setPaused(true,rig.limits,true);saved=rig.h[2].write();rig.h[2].unload();}
   long before=rig.h[1].flowSnapshot().orElseThrow().state().work();for(int n=0;n<80;n++)rig.step(false,n==30);
   assertTrue(rig.eWaiting(FlowRuntime.Status.WAITING_READ));assertEquals(before,rig.h[1].flowSnapshot().orElseThrow().state().work());assertFalse(rig.h[0].flowSnapshot().orElseThrow().state().locals().fields().containsKey("decision"));
   if(unload){var cold=new ComputerBlockHost(()->{});cold.read(saved);cold.tick(rig.limits,true);assertTrue(cold.loaded(),cold.diagnostic().toString());cold.setPaused(false,rig.limits,true);rig.h[2]=cold;}
   assertEquals(text("route_unavailable"),rig.finish(true).field("c"));
  }
 }
 @Test void admittedPausedPolicyPeerFaultPersistsWithoutAutomaticRetryOrReset()throws Exception{
  var rig=new Harness(1);rig.begin("workshop");for(int n=0;!rig.eWaiting(FlowRuntime.Status.WAITING_WRITE);n++){assertTrue(n<1000);rig.step(false,false);}
  rig.h[2].setPaused(true,rig.limits,true);NativeLibraryStartupTest.network(rig.h[1],"x1",rig.limits,rig.h[2],"x0",rig.limits).rejectPaused(NativeLibraryStartupTest.pin(rig.h[1],"x1"));var failed=rig.h[1].flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.FAILED,failed.status());assertEquals(ComputationException.Code.XBUS_PEER_PAUSED,failed.diagnostic().orElseThrow().code());var raw=rig.h[1].write();for(int n=0;n<40;n++)rig.h[1].tick(rig.limits,true);assertEquals(raw,rig.h[1].write());var cold=new ComputerBlockHost(()->{});cold.read(raw);cold.tick(rig.limits,true);assertTrue(cold.loaded());assertEquals(raw,cold.write());assertFalse(rig.h[0].flowSnapshot().orElseThrow().state().locals().fields().containsKey("decision"));
 }
}
