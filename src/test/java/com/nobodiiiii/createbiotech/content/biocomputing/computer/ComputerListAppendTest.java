package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerAssemblyConstructionTest.patch;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerAssemblyConstructionTest.record;
class ComputerListAppendTest {
 static final ComputerHostLimits LIMITS=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(256,1000000,1),ComputerFlowHostTest.LIMITS.archive(),com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,HardwarePins.DEFAULT,SerialHardware.NONE,2));
 static FlowProgram program(DataType element,boolean task){
  var type=new DataType.ListType(element);var in=Map.of("source",type,"element",element);
  var key=new ModuleDefinition.Key("append",1);var steps=new ArrayList<FlowProgram.Step>();
  if(task){steps.add(new FlowProgram.TaskStart("start",key,Map.of("source","source","element","element"),"handle"));steps.add(new FlowProgram.TaskJoin("join",key,"handle","joined"));}
  else steps.add(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("source","source","element","element"),Map.of("result","out"))));
  steps.add(new FlowProgram.Procedure("def",key,in,Map.of("result",type),Map.of("result","source"),FlowBlockRules.NONE,Optional.of(new AssemblySource("LIST.APPEND source source element\nRET"))));steps.add(new FlowProgram.EndProcedure("end"));
  return new FlowProgram(in,steps).withAssembly();
 }
 static DataValue.RecordValue input(int size,DataValue element){return new DataValue.RecordValue(Map.of("source",new DataValue.ListValue(Collections.nCopies(size,element)),"element",element));}
 @Test void emptyNestedNativeAndRecipeListsRoundtripAtEveryBudgetBoundaryAndTaskCall(){
  var nativeValue=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("zero",new DataValue.NbtValue(6,new DataValue.IntegerValue(Long.MIN_VALUE)))));
  var recipe=new DataValue.RecipeValue("recipe",1,FoundationRecipeTest.projection(false));
  for(var element:List.<DataValue>of(new DataValue.IntegerValue(5),new DataValue.ListValue(List.of(nativeValue)),nativeValue,recipe))for(DataType declared:List.of(DataType.Scalar.ANY,element instanceof DataValue.IntegerValue?DataType.Scalar.INTEGER:element instanceof DataValue.NbtValue?DataType.Scalar.NBT:element instanceof DataValue.RecipeValue?DataType.Semantic.RECIPE:new DataType.ListType(DataType.Scalar.NBT)))for(int n:List.of(0,1,8))for(boolean task:List.of(false,true)){
   var p=program(declared,task);var initial=input(n,element);var host=new ComputerBlockHost(()->{});host.installFlow(p,initial,LIMITS,true);var seen=new HashSet<Integer>();
   for(int i=0;;i++){
    assertTrue(i<300);var state=host.flowSnapshot().orElseThrow().state();assertNotEquals(FlowRuntime.Status.FAILED,state.status(),state.diagnostic().toString());
    state.assembly().orElseThrow().build().ifPresent(b->seen.add(b.cursor()));
    var save=host.write();var next=new ComputerBlockHost(()->{});next.read(save);next.tick(LIMITS,true);assertTrue(next.loaded(),next.diagnostic().toString());assertEquals(save,next.write());host=next;
    if(state.status()==FlowRuntime.Status.COMPLETE)break;
    if(task){host.setPaused(false,LIMITS,true);host.tick(LIMITS,true);host.setPaused(true,LIMITS,true);}else host.step(LIMITS,true);
   }
   var locals=host.flowSnapshot().orElseThrow().state().locals();assertEquals(initial.field("source"),locals.field("source"));
   if(!task){assertEquals(new DataValue.ListValue(Collections.nCopies(n+1,element)),locals.field("out"));assertEquals(n+2,seen.size());}
  }
 }
 @Test void oldVersionsRejectAndHardwareCannotAcquireEitherExtensionImplicitly(){
  var type=new DataType.ListType(DataType.Scalar.INTEGER);
  for(int v=2;v<=5;v++){int version=v;assertTrue(assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(version,"LIST.APPEND source source element"),Map.of(),Map.of(),Map.of(),65536,256)).getMessage().contains("Unsupported assembler version"));}
  var basic=new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive());var parser=ParserHardwareFixture.withIsa1a(basic);
  assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(program(DataType.Scalar.INTEGER,false),basic));assertDoesNotThrow(()->FlowPins.validate(program(DataType.Scalar.INTEGER,false),parser));
  assertThrows(FlowPins.Unavailable.class,()->FlowPins.validate(program(DataType.Scalar.ANY,false),parser));
 }
 @Test void ramFailureKeepsSourceDestinationAndPrivatePrefix(){
  var p=program(DataType.Scalar.INTEGER,false);var initial=input(8,new DataValue.IntegerValue(7));var host=new ComputerBlockHost(()->{});host.installFlow(p,initial,LIMITS,true);
  while(host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().build().filter(b->b.cursor()==2).isEmpty())host.step(LIMITS,true);
  var before=host.flowSnapshot().orElseThrow();long memory=before.state().controlUnits()+before.initialInputs().storageUnits()+before.control().storageUnits()+p.usage(LIMITS.hardware()).constantUnits();
  var tight=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(256,memory+2,1),LIMITS.archive()));host.step(tight,true);var failed=host.flowSnapshot().orElseThrow();
  assertEquals(ComputationException.Code.MEMORY_LIMIT,failed.state().diagnostic().orElseThrow().code());assertEquals(before.state().assembly(),failed.state().assembly());assertEquals(before.state().locals(),failed.state().locals());
  var next=new ComputerBlockHost(()->{});next.read(host.write());next.tick(LIMITS,true);assertTrue(next.loaded());assertEquals(failed,next.flowSnapshot().orElseThrow());
 }
 @Test void coldOwnerRejectsForgedCursorWorkAndWrongOpcodeWithoutChangingRawSave(){
  for(String mutation:List.of("cursor","work","pc","source")){
   var p=program(DataType.Scalar.INTEGER,false);var host=new ComputerBlockHost(()->{});host.installFlow(p,input(3,new DataValue.IntegerValue(7)),LIMITS,true);
   while(host.flowSnapshot().orElseThrow().state().assembly().orElseThrow().build().filter(b->b.cursor()==1).isEmpty())host.step(LIMITS,true);
   var raw=(CompoundTag)host.write();var owner=(DataValue.RecordValue)ValueNbtCodec.decode(raw.getCompound("Data"),LIMITS.archive());var flow=record(owner,"flow");var state=record(flow,"state");var a=record(state,"assembly");
   switch(mutation){
    case "cursor" -> a=patch(a,"build",new DataValue.RecordValue(Map.of("cursor",new DataValue.IntegerValue(999))));
    case "work" -> a=patch(a,"build",new DataValue.RecordValue(Map.of("cursor",new DataValue.IntegerValue(3))));
    case "pc" -> a=patch(a,"pc",new DataValue.IntegerValue(((DataValue.IntegerValue)a.field("pc")).value()+1));
    case "source" -> {var regs=record(a,"registers");String key=regs.fields().entrySet().stream().filter(e->e.getValue() instanceof DataValue.ListValue).findFirst().orElseThrow().getKey();a=patch(a,"registers",patch(regs,key,new DataValue.TextValue("wrong")));}
   }
   raw.put("Data",ValueNbtCodec.encode(patch(owner,"flow",patch(flow,"state",patch(state,"assembly",a))),LIMITS.archive()));var next=new ComputerBlockHost(()->{});next.read(raw);next.tick(LIMITS,true);assertFalse(next.loaded(),mutation);assertEquals(raw,next.write());
  }
 }
}
