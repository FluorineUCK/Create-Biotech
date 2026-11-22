package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TextNextOwnerTest {
 static final ComputerHostLimits PARSER=NativeLibraryOwnersTest.limits("cleric",4);
 static final Map<String,DataType> INPUTS=Map.of("text",DataType.Scalar.TEXT,"offset",DataType.Scalar.INTEGER);
 static final ModuleDefinition.Key KEY=new ModuleDefinition.Key("text_iteration",1);
 static final String LOOP="REG result TEXT_NEXT\nREG done BOOLEAN\nloop:\nTEXT.NEXT result text offset\nFIELD done result done\nJFALSE done advance\nRET\nadvance:\nFIELD offset result next\nJMP loop";
 static FlowProgram program(String body,boolean task){
  var steps=new ArrayList<FlowProgram.Step>();
  if(task){steps.add(new FlowProgram.TaskStart("start",KEY,Map.of("text","text","offset","offset"),"handle"));steps.add(new FlowProgram.TaskJoin("join",KEY,"handle","joined"));}
  else steps.add(new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("text","text","offset","offset"),Map.of("result","out"))));
  steps.add(new FlowProgram.Procedure("definition",KEY,INPUTS,Map.of("result",RegisterProgram.TextNext.TYPE),Map.of("result","result"),FlowBlockRules.NONE,Optional.of(new AssemblySource(body))));steps.add(new FlowProgram.EndProcedure("end"));return new FlowProgram(INPUTS,steps).withAssembly();
 }
 static DataValue.RecordValue input(String s){return new DataValue.RecordValue(Map.of("text",new DataValue.TextValue(s),"offset",new DataValue.IntegerValue(0)));}
 static ComputerHostLimits limits(long ram,long work){return new ComputerHostLimits(new ResourceLimits(PARSER.hardware().programUnits(),Math.min(ram,PARSER.hardware().memoryUnits()),Math.min(work,PARSER.hardware().workPerSlice())),PARSER.archive(),PARSER.mailbox(),PARSER.pins(),PARSER.serial(),PARSER.taskSlots(),true,false);}
 static long owned(FlowBlockHost.Saved s){long n=s.initialInputs().storageUnits()+s.control().storageUnits()+s.memory().storageUnits()+s.pinUnits()+s.tasks().metadataUnits()+s.program().usage(PARSER.hardware()).constantUnits()+s.state().controlUnits();for(var c:s.tasks().contexts())n+=c.controlUnits();return n;}
 @Test void actualNonMasterParserCanPauseColdReloadAndFinishBothCallsAndTasksAtTinyBudgets(){
  assertTrue(PARSER.isa1a());assertFalse(PARSER.isa1b());
  for(boolean task:List.of(false,true))for(long budget:new long[]{1,8}){
   var l=limits(65536,budget);var p=program(LOOP,task);var initial=input("a中\ud83d\ude00\ud800x\udc00");var owner=new ComputerBlockHost(()->{});owner.installFlow(p,initial,l,true);long peak=0;var offsets=new HashSet<Long>();
   for(int i=0;;i++){
    assertTrue(i<700);var state=owner.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());peak=Math.max(peak,owned(owner.flowSnapshot().orElseThrow()));
    if(state.status()==FlowRuntime.Status.COMPLETE)break;
    if(state.locals().fields().get("offset") instanceof DataValue.IntegerValue n)offsets.add(n.value());
    var paused=owner.write();owner.tick(l,true);assertEquals(paused,owner.write(),"Paused owner may not advance UTF16 cursor");
    var cold=new ComputerBlockHost(()->{});cold.read(paused);cold.tick(l,true);assertTrue(cold.loaded(),cold.diagnostic().toString());assertEquals(paused,cold.write());owner=cold;
    owner.setPaused(false,l,true);owner.tick(l,true);owner.setPaused(true,l,true);
   }
   var state=owner.flowSnapshot().orElseThrow().state();assertEquals(initial.field("text"),state.locals().field("text"));
   var result=(DataValue.RecordValue)(task?((DataValue.RecordValue)state.locals().field("joined")).field("value"):state.locals().field("out"));
   if(task)result=(DataValue.RecordValue)result.field("result");
   assertEquals(new DataValue.BooleanValue(true),result.field("done"));assertEquals(new DataValue.IntegerValue(7),result.field("next"));
   if(!task&&budget==1)assertTrue(offsets.containsAll(List.of(0L,1L,2L,4L,5L,6L,7L)),offsets.toString());
   System.out.println("TEXT_NEXT_OWNER task="+task+" budget="+l.hardware().workPerSlice()+" words="+p.usage(l.hardware()).programUnits()+" work="+state.work()+" peakCommitted="+peak);
  }
 }
 @Test void masterWithoutParserDeniedAndAnyStillNeedsMaster(){
  var p=program(LOOP,false);var master=NativeLibraryOwnersTest.limits("farmer",5);assertFalse(master.isa1a());assertTrue(master.isa1b());var e=assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(p,master));assertTrue(e.getMessage().contains("ISA-1a"), e.getMessage());assertTrue(e.getMessage().contains("definition/assembly/output result"), e.getMessage());assertDoesNotThrow(()->FlowPins.validate(p,PARSER));
  var key=new ModuleDefinition.Key("any",1);var any=new FlowProgram(Map.of("a",DataType.Scalar.ANY),List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("a","a"),Map.of())),new FlowProgram.Procedure("def",key,Map.of("a",DataType.Scalar.ANY),Map.of(),Map.of(),FlowBlockRules.NONE,Optional.of(new AssemblySource("RET"))),new FlowProgram.EndProcedure("end"))).withAssembly();assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(any,PARSER));
 }
 @Test void realOwnerTransientRamRejectionKeepsPreviousResultAndExactPc(){
  var p=program("REG result TEXT_NEXT\nTEXT.NEXT result text offset\nTEXT.NEXT result text offset\nRET",false);var owner=new ComputerBlockHost(()->{});owner.installFlow(p,input("x"),limits(65536,1),true);
  for(int i=0;;i++){assertTrue(i<100);var s=owner.flowSnapshot().orElseThrow().state();if(s.locals().fields().containsKey("result"))break;owner.step(limits(65536,1),true);}
  var before=owner.flowSnapshot().orElseThrow();long retained=owned(before);var raw=owner.write();owner.step(limits(retained+44,1),true);var failed=owner.flowSnapshot().orElseThrow();assertEquals(ComputationException.Code.MEMORY_LIMIT,failed.state().diagnostic().orElseThrow().code());assertEquals(before.state().assembly(),failed.state().assembly());assertEquals(before.state().locals(),failed.state().locals());assertNotEquals(raw,owner.write());
  var cold=new ComputerBlockHost(()->{});cold.read(owner.write());cold.tick(limits(65536,1),true);assertTrue(cold.loaded(),cold.diagnostic().toString());assertEquals(owner.write(),cold.write());
 }

 @Test void sharedOwnerCountsRetainedSiblingBeforeTextResultReservationAndColdAdmission(){
  var l=limits(65536,1);var p=program("REG result TEXT_NEXT\nTEXT.NEXT result text offset\nTEXT.NEXT result text offset\nRET",false);var owner=new ComputerBlockHost(()->{});owner.installFlow(p,input("shared"),l,true);owner.startFlowTask(KEY,input("sibling"),l,true);
  for(int i=0;;i++){assertTrue(i<100);if(owner.flowSnapshot().orElseThrow().state().locals().fields().containsKey("result"))break;owner.step(l,true);}
  var before=owner.flowSnapshot().orElseThrow();assertFalse(before.tasks().contexts().isEmpty());long retained=owned(before);var raw=owner.write();
  var cold=new ComputerBlockHost(()->{});cold.read(raw);cold.tick(limits(retained-1,1),true);assertFalse(cold.loaded());assertEquals(raw,cold.write());
  var contexts=new ArrayList<FlowRuntime.Checkpoint>();contexts.add(before.state());contexts.addAll(before.tasks().contexts());
  FlowRuntime.Checkpoint failed=null;
  for(int i=0;i<3&&failed==null;i++){owner.step(limits(retained+44,1),true);var current=owner.flowSnapshot().orElseThrow();var all=new ArrayList<FlowRuntime.Checkpoint>();all.add(current.state());all.addAll(current.tasks().contexts());failed=all.stream().filter(c->c.diagnostic().isPresent()).findFirst().orElse(null);}
  assertNotNull(failed,"One selected context must reject the shared-owner result reserve");assertEquals(ComputationException.Code.MEMORY_LIMIT,failed.diagnostic().orElseThrow().code());var selected=failed;var prior=contexts.stream().filter(c->c.flow().equals(selected.flow())).findFirst().orElseThrow();assertEquals(prior.assembly(),failed.assembly());assertEquals(prior.locals(),failed.locals());
 }

 @Test void coldHardwareQualificationAndCorruptCursorCannotReplaceSavedSource(){
  var l=limits(65536,1);var p=program(LOOP,false);var owner=new ComputerBlockHost(()->{});owner.installFlow(p,input("x"),l,true);owner.step(l,true);var raw=owner.write();
  var wrongHardware=new ComputerBlockHost(()->{});wrongHardware.read(raw);wrongHardware.tick(NativeLibraryOwnersTest.limits("farmer",5),true);assertFalse(wrongHardware.loaded());assertEquals(raw,wrongHardware.write());wrongHardware.tick(l,true);assertTrue(wrongHardware.loaded(),wrongHardware.diagnostic().toString());assertEquals(raw,wrongHardware.write());
  // Corrupt the real serialized register at the TEXT.NEXT boundary, not an editor sample.
  while(!FlowXBus.image(p).code().get(owner.flowSnapshot().orElseThrow().state().assembly().orElseThrow().pc()).instruction().getClass().equals(RegisterProgram.TextNext.class))owner.step(l,true);
  var tag=(net.minecraft.nbt.CompoundTag)owner.write();var root=(DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("Data"),l.archive());var flow=(DataValue.RecordValue)root.field("flow");var state=(DataValue.RecordValue)flow.field("state");var assembly=(DataValue.RecordValue)state.field("assembly");var regs=(DataValue.RecordValue)assembly.field("registers");var cursor=regs.fields().entrySet().stream().filter(e->e.getValue().equals(new DataValue.IntegerValue(0))).findFirst().orElseThrow().getKey();assembly=patch(assembly,"registers",patch(regs,cursor,new DataValue.TextValue("bad cursor")));tag.put("Data",ValueNbtCodec.encode(patch(root,"flow",patch(flow,"state",patch(state,"assembly",assembly))),l.archive()));
  var corrupt=new ComputerBlockHost(()->{});corrupt.read(tag);corrupt.tick(l,true);assertFalse(corrupt.loaded());assertEquals(tag,corrupt.write());assertTrue(corrupt.diagnostic().toString().contains("line 4"),corrupt.diagnostic().toString());
 }
 static DataValue.RecordValue patch(DataValue.RecordValue r,String k,DataValue v){var f=new TreeMap<>(r.fields());f.put(k,v);return new DataValue.RecordValue(f);}
}
