package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.NativeLibraryReadDocuments.record;
public class RecipePreviewExecutionTest {
 static final ComputerHostLimits LIMITS=NativeLibraryOwnersTest.limits("cleric",5);
 static DataValue.RecordValue patch(DataValue.RecordValue r,String key,DataValue value){var m=new LinkedHashMap<>(r.fields());m.put(key,value);return new DataValue.RecordValue(m);}
 public static DataValue.RecordValue page(boolean sequence,boolean empty){
  var base=FoundationRecipeTest.projection(sequence);var row=(DataValue.RecordValue)((DataValue.ListValue)base.field("inputs")).values().getFirst();
  row=patch(row,"components",new DataValue.NbtValue(10,record("negative_zero",new DataValue.NbtValue(6,new DataValue.IntegerValue(Long.MIN_VALUE)),"bytes",new DataValue.NbtValue(7,new DataValue.ListValue(List.of(new DataValue.IntegerValue(-128),new DataValue.IntegerValue(0),new DataValue.IntegerValue(127)))))));
  var item=patch(patch(row,"tag",DataValue.NullValue.INSTANCE),"item",new DataValue.TextValue("minecraft:stone"));var rows=new DataValue.ListValue(List.of(row,item,row));
  base=patch(base,"inputs",empty?new DataValue.ListValue(List.of()):rows);
  if(sequence)base=patch(base,"body",record("stages",new DataValue.ListValue(List.of(record("index",0,"address","first","inputs",rows),record("index",1,"address","empty","inputs",new DataValue.ListValue(List.of())),record("index",2,"address","last","inputs",rows)))));
  return base;
 }
 static class Rig {
  final DataValue.RecordValue raw;final long batches;ComputerBlockHost[] h=new ComputerBlockHost[4];long[] peak=new long[4];com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition.Key extraKey;UUID background;int tick;int[] exchanges=new int[3];
  Rig(DataValue.RecordValue raw,long batches)throws Exception{
   this.raw=raw;this.batches=batches;
   h[0]=new ComputerBlockHost(()->{});var envelope=new DataType.RecordType(Map.of("value",DataType.Scalar.ANY));h[0].installFlow(new FlowProgram(Map.of("message",envelope),List.of(new FlowProgram.Write("send","x0","message"))).withAssembly(),record("message",record("value",raw)),LIMITS,true);
   for(int i=1;i<4;i++){String role=List.of("v","p","s").get(i-1);var d=RecipePreviewDocumentsTest.published(role);var body=d.flow().orElseThrow();var initial=body.initial();if(i==1)initial=patch(initial,"batches",new DataValue.IntegerValue(batches));h[i]=new ComputerBlockHost(()->{});h[i].installFlow(body.program(d.archive()),initial,LIMITS,true);h[i].setPaused(false,LIMITS,true);}
  }
  int status(int left,String lp,int right,String rp){return NativeLibraryStartupTest.status(h[left],lp,LIMITS,h[right],rp,LIMITS);}
  void step(boolean reload){
   h[0].tick(LIMITS,true);
   h[1].tick(LIMITS,true,Map.of("x0",status(1,"x0",0,"x0")));
   h[2].tick(LIMITS,true,Map.of("x0",status(2,"x0",1,"x1"),"x1",status(2,"x1",3,"x0")));
   h[3].tick(LIMITS,true,Map.of("x0",status(3,"x0",2,"x1")));
   for(int i=0;i<3;i++){int l=i==0?0:i==1?1:2,r=i==0?1:i==1?2:3;String lp=i==0?"x0":"x1";if(h[l].exchange(lp,LIMITS,true,h[r],"x0",LIMITS,true)==XBusExchange.Status.EXCHANGED)exchanges[i]++;}
   if(extraKey!=null&&background==null&&exchanges[2]>0)background=h[3].startFlowTask(extraKey,record("value",7),LIMITS,true);
   tick++;
   for(int i=0;i<4;i++){var s=h[i].flowSnapshot().orElseThrow();long owned=s.state().controlUnits()+s.initialInputs().storageUnits()+s.control().storageUnits()+s.program().usage(LIMITS.hardware()).constantUnits()+s.tasks().metadataUnits();for(var c:s.tasks().contexts())owned+=c.controlUnits();peak[i]=Math.max(peak[i],owned);if(reload){boolean paused=s.state().paused();h[i].setPaused(true,LIMITS,true);var save=h[i].write();var next=new ComputerBlockHost(()->{});next.read(save);next.tick(LIMITS,true);assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(save,next.write());h[i]=next;h[i].setPaused(paused,LIMITS,true);}}
  }
  FlowRuntime.Checkpoint result(){return h[2].flowSnapshot().orElseThrow().state();}
  DataValue.RecordValue finish(boolean everyBoundary){
   for(int n=0;n<60;n++){step(n==20);for(int i=1;i<4;i++)assertNotEquals(FlowRuntime.Status.WAITING_READ,h[i].flowSnapshot().orElseThrow().state().status(),"No READ against paused sender");}assertEquals(0,h[0].flowSnapshot().orElseThrow().state().work());
   h[0].setPaused(false,LIMITS,true);
   for(int n=0;n<20000;n++){step(everyBoundary||n%31==0);for(int i=0;i<4;i++){var s=h[i].flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,s.status(),"role="+i+" "+s.diagnostic());}if(result().status()==FlowRuntime.Status.COMPLETE){System.out.println("PREVIEW_RUN seq="+raw.fields().getOrDefault("type",DataValue.NullValue.INSTANCE)+" batches="+batches+" ticks="+tick+" work="+Arrays.stream(h).map(x->x.flowSnapshot().orElseThrow().state().work()).toList()+" peak="+Arrays.toString(peak)+" exchanges="+Arrays.toString(exchanges));return (DataValue.RecordValue)result().locals().field("result");}}
   return fail("Preview did not complete");
  }
 }
 static void terminalProblem(ComputerBlockHost host,String role,String code)throws Exception{
  var d=RecipePreviewDocumentsTest.published(role);var observation=com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.decode(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(d.terminal(),host.flowSnapshot()));var root=com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection.project(observation,Map.of(),8,100);var pending=new ArrayDeque<com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection.View>();pending.add(root);boolean found=false;while(!pending.isEmpty()){var view=pending.removeFirst();if(view.problem().filter(p->p.code().equals(code)).isPresent())found=true;pending.addAll(view.children());}assertTrue(found,"Existing terminal distinguishes "+code);
 }
 static void assertScaled(DataValue.ListValue source,DataValue output,long batches){var rows=((DataValue.ListValue)output).values();assertEquals(source.values().size(),rows.size());for(int i=0;i<rows.size();i++){var row=(DataValue.RecordValue)source.values().get(i);assertEquals(patch(row,"count",new DataValue.IntegerValue(Math.multiplyExact(((DataValue.IntegerValue)row.field("count")).value(),batches))),rows.get(i));}}
 @Test void standardAndEverySequencedStagePreserveAddressOrderDuplicatesNativeAndSourceAcrossIndependentOwners()throws Exception{
  var costs=new HashMap<String,Long>();
  for(boolean seq:List.of(false,true))for(boolean empty:List.of(false,true)){var raw=page(seq,empty);var rig=new Rig(raw,3);terminalProblem(rig.h[2],"p","output_pending");var result=rig.finish(seq&&empty);assertEquals(new DataValue.BooleanValue(true),result.field("ok"));var terminal=RecipePreviewDocumentsTest.published("p").terminal();var observation=com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.decode(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(terminal,rig.h[2].flowSnapshot()));var projected=com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection.project(observation,Map.of(),8,100);assertEquals(result,((DataValue.RecordValue)observation.roots().field("output")).field("result"));assertTrue(terminal.orElseThrow().nodes().values().stream().filter(n->n instanceof com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text).map(n->((com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text)n).value()).filter(b->b instanceof com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding.Literal).anyMatch(b->((com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding.Literal)b).value() instanceof DataValue.TextValue t&&t.value().contains("PREVIEW ONLY")));var tree=new ValueTree((DataValue.RecordValue)observation.roots().field("output"));var path=new ValuePath("result",List.of()).key("value");tree.toggle(new ValuePath("result",List.of()));tree.toggle(path);assertEquals(result.field("value"),path.resolve(tree.inputs()));
   var preview=(DataValue.RecordValue)result.field("value");assertEquals(raw,((DataValue.RecipeValue)preview.field("r")).projection());assertEquals(new DataValue.IntegerValue(3),preview.field("n"));assertScaled((DataValue.ListValue)raw.field("inputs"),preview.field("c"),3);
   var body=(DataValue.RecordValue)raw.field("body");var stages=((DataValue.ListValue)preview.field("s")).values();if(!seq){assertEquals(body.field("address"),preview.field("a"));assertTrue(stages.isEmpty());}else{assertEquals(DataValue.NullValue.INSTANCE,preview.field("a"));var original=((DataValue.ListValue)body.field("stages")).values();assertEquals(original.size(),stages.size());for(int i=0;i<stages.size();i++){var a=(DataValue.RecordValue)original.get(i);var b=(DataValue.RecordValue)stages.get(i);assertEquals(a.field("index"),b.field("i"));assertEquals(a.field("address"),b.field("a"));assertScaled((DataValue.ListValue)a.field("inputs"),b.field("m"),3);}}
   assertEquals(record("message",record("value",raw)),rig.h[0].flowSnapshot().orElseThrow().initialInputs());assertEquals(1,rig.exchanges[0]);assertEquals(1,rig.exchanges[1]);assertEquals(seq?8:2,rig.exchanges[2]);costs.put(seq+":"+empty,rig.h[3].flowSnapshot().orElseThrow().state().work());
  }
  assertTrue(costs.get("false:false")>costs.get("false:true"));assertTrue(costs.get("true:false")>costs.get("true:true"));assertTrue(costs.get("true:false")>costs.get("false:false"));
 }
 @Test void malformedMissingStageAndNonpositiveBatchesRejectWithoutPartialPreview()throws Exception{
  var malformed=record("inputs",new DataValue.ListValue(List.of()));var missing=patch(page(true,false),"body",record("stages",new DataValue.ListValue(List.of(record("index",1,"address","missing0","inputs",new DataValue.ListValue(List.of()))))));
  for(var raw:List.of(page(false,false),malformed,missing))for(long batches:new long[]{0,-2,3}){var rig=new Rig(raw,batches);boolean valid=raw.fields().containsKey("pattern_id")&&raw!=missing&&batches>0;if(valid)continue;var result=rig.finish(false);assertEquals(record("ok",false,"value",null),result);assertEquals(0,rig.exchanges[2]);}
 }
 @Test void overflowIsOrdinaryWorkerFaultAndCannotPublishSuccessfulPreview()throws Exception{
  var source=page(true,true);var body=(DataValue.RecordValue)source.field("body");var stages=new ArrayList<>(((DataValue.ListValue)body.field("stages")).values());var first=(DataValue.RecordValue)stages.getFirst();var row=(DataValue.RecordValue)((DataValue.ListValue)first.field("inputs")).values().getFirst();stages.set(0,patch(first,"inputs",new DataValue.ListValue(List.of(patch(row,"count",new DataValue.IntegerValue(1))))));source=patch(source,"body",record("stages",new DataValue.ListValue(stages)));
  var rig=new Rig(source,Long.MAX_VALUE);rig.h[0].setPaused(false,LIMITS,true);for(int i=0;i<20000;i++){rig.step(i%19==0);if(rig.h[3].flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.FAILED){assertTrue(rig.exchanges[2]>=5,"Overflow occurs only after earlier common/stage work succeeded");assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW,rig.h[3].flowSnapshot().orElseThrow().state().diagnostic().orElseThrow().code());assertFalse(rig.result().locals().fields().containsKey("result"));terminalProblem(rig.h[3],"s","output_failed");terminalProblem(rig.h[2],"p","output_pending");var saved=rig.h[3].write();rig.step(true);assertEquals(saved,rig.h[3].write());return;}}fail("No explicit overflow");
 }
 @Test void interleavedIndependentLocalTaskSharesUnchangedWorkerCapacityAndSurvivesEveryBudgetReload()throws Exception{
  var rig=new Rig(page(true,false),3);var before=rig.h[3].flowSnapshot().orElseThrow();var p=before.program();var steps=new ArrayList<>(p.steps());rig.extraKey=new ModuleDefinition.Key("background",1);
  steps.add(new FlowProgram.Procedure("background",rig.extraKey,Map.of("value",DataType.Scalar.INTEGER),Map.of("value",DataType.Scalar.INTEGER),Map.of("value","value"),FlowBlockRules.NONE,Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("SLEEP value\nRET"))));steps.add(new FlowProgram.EndProcedure("background_end"));
  var variant=new FlowProgram(p.inputs(),steps,p.expressions()).withAssembly();assertTrue(variant.usage(LIMITS.hardware()).programUnits()<=256);rig.h[3].installFlow(variant,before.initialInputs(),LIMITS,true);rig.h[3].setPaused(false,LIMITS,true);
  var result=rig.finish(true);assertEquals(new DataValue.BooleanValue(true),result.field("ok"));assertNotNull(rig.background);var task=rig.h[3].flowTask(rig.background).orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,task.status());assertEquals(new DataValue.IntegerValue(7),task.locals().field("value"));
 }
 @Test void pausedSenderBeforeAdmissionPollsButAfterAdmittedReadStillFaultsWithoutAutomaticRetry()throws Exception{
  var rig=new Rig(page(false,false),3);rig.h[0].setPaused(false,LIMITS,true);
  for(int n=0;n<200;n++){rig.h[0].tick(LIMITS,true);rig.h[1].tick(LIMITS,true,Map.of("x0",rig.status(1,"x0",0,"x0")));if(rig.h[1].flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.WAITING_READ)break;}
  assertEquals(FlowRuntime.Status.WAITING_READ,rig.h[1].flowSnapshot().orElseThrow().state().status());rig.h[0].setPaused(true,LIMITS,true);
  NativeLibraryStartupTest.network(rig.h[0],"x0",LIMITS,rig.h[1],"x0",LIMITS).rejectPaused(NativeLibraryStartupTest.pin(rig.h[1],"x0"));
  var state=rig.h[1].flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.FAILED,state.status());assertEquals(ComputationException.Code.XBUS_PEER_PAUSED,state.diagnostic().orElseThrow().code());terminalProblem(rig.h[1],"v","output_failed");assertFalse(rig.result().locals().fields().containsKey("result"));var raw=rig.h[1].write();
  for(int n=0;n<50;n++)rig.h[1].tick(LIMITS,true);assertEquals(raw,rig.h[1].write());var cold=new ComputerBlockHost(()->{});cold.read(raw);cold.tick(LIMITS,true);assertTrue(cold.loaded());assertEquals(raw,cold.write());
 }
}
