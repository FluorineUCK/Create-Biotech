package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalCellReferenceTest {
 static DataValue.RecordValue obj(Object... args){var fields=new LinkedHashMap<String,DataValue>();for(int i=0;i<args.length;i+=2){Object v=args[i+1];fields.put((String)args[i],v instanceof DataValue d?d:v instanceof Boolean b?new DataValue.BooleanValue(b):v instanceof Number n?new DataValue.IntegerValue(n.longValue()):new DataValue.TextValue((String)v));}return new DataValue.RecordValue(fields);}
 static final ComputerHostLimits LIMITS=SoftwareAnyAbiTest.LIMITS;
 static DataValue reference(String id,String op,String target,String local,String value){
  var fields=new LinkedHashMap<>(obj("kind","reference","source",id,"operation",op,"target",target,"local",local).fields());
  if(value!=null)fields.put("value",obj("kind","local","name",value));return new DataValue.RecordValue(fields);
 }
 static DataValue signature(boolean output){
  var nodes=new ArrayList<DataValue>();nodes.add(obj("kind","scalar","name","INTEGER"));nodes.add(obj("kind","ref","child",0,"writable",true));
  var fields=new LinkedHashMap<String,DataValue>();fields.put(output?"result":"value",new DataValue.IntegerValue(1));if(!output)fields.put("next",new DataValue.IntegerValue(0));
  nodes.add(obj("kind","record","fields",new DataValue.RecordValue(fields)));
  return obj("nodes",new DataValue.ListValue(nodes),"root",2);
 }
 static FlowProgram source(){
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of("counter",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(7))),List.of()),Map.of());
  var base=(DataValue.RecordValue)FlowProgramCodec.encode(new FlowProgram(Map.of("next",DataType.Scalar.INTEGER,"ticks",DataType.Scalar.INTEGER),List.of(),archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1));
  var steps=List.of(reference("address_a","address","counter","a",null),reference("address_b","address","counter","b",null),reference("snapshot","read","a","before",null),reference("readonly","readonly","b","ro",null),
   obj("kind","invoke","source","call","name","replace","revision",1,"arguments",obj("value","b","next","next"),"returns",obj("result","returned")),
   reference("identity","same","a","same","returned"),obj("kind","sleep","source","pause","ticks","ticks"),reference("after","read","ro","after",null),
   obj("kind","procedure","source","definition","name","replace","revision",1,"inputs",signature(false),"outputs",signature(true),"returns",obj("result","value")),
   reference("update","write","value","","next"),obj("kind","end_procedure","source","end"));
  var fields=new LinkedHashMap<>(base.fields());fields.put("steps",new DataValue.ListValue(steps));return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
 }
 @Test void completeAuthoredReferenceInstructionsLinkRealInstalledCell(){
  var key=new ModuleDefinition.Key("authored",1);var inputs=Map.<String,DataType>of("next",DataType.Scalar.INTEGER,"ticks",DataType.Scalar.INTEGER);
  var outputs=Map.<String,DataType>of("before",DataType.Scalar.INTEGER,"after",DataType.Scalar.INTEGER,"same",DataType.Scalar.BOOLEAN);
  var assembly=new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("""
   TYPE RW REF INTEGER RW
   TYPE RO REF INTEGER RO
   REG a RW
   REG b RO
   REG before INTEGER
   REG after INTEGER
   REG same BOOLEAN
   REF.ADDRESS a counter
   REF.READ before a
   REF.READONLY b a
   REF.WRITE a next
   REF.SAME same a b
   SLEEP ticks
   REF.READ after b
   RET
   """);
  var program=new FlowProgram(inputs,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("next","next","ticks","ticks"),Map.of("before","before","after","after","same","same"))),new FlowProgram.Procedure("definition",key,inputs,outputs,Map.of("before","before","after","after","same","same"),FlowBlockRules.NONE,Optional.of(assembly)),new FlowProgram.EndProcedure("end")),source().expressions(),FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
  var done=SoftwareAnyAbiTest.run(program,new DataValue.RecordValue(Map.of("next",new DataValue.IntegerValue(42),"ticks",new DataValue.IntegerValue(2))),true);
  assertEquals(new DataValue.IntegerValue(7),done.locals().field("before"));assertEquals(new DataValue.IntegerValue(42),done.locals().field("after"));assertEquals(new DataValue.BooleanValue(true),done.locals().field("same"));
 }
 @Test void scratchPublicCellAliasesSurviveSynchronousUpdateAndEveryColdOwnerBoundary(){
  var source=source();var initial=new DataValue.RecordValue(Map.of("next",new DataValue.IntegerValue(42),"ticks",new DataValue.IntegerValue(2)));
  var document=new ProgramDocument(source.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(source,initial)));
  var wire=ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,LIMITS.archive()),LIMITS.archive());assertEquals(document,wire);
  var host=new ComputerBlockHost(()->{});host.installProgram(PreparedProgram.prepare(wire,LIMITS),ComputerRuntime.MemoryPolicy.RESET,LIMITS,true);host.setPaused(false,LIMITS,true);
  boolean slept=false;
  for(int i=0;i<200;i++){
   host.tick(LIMITS,true);var before=host.flowSnapshot().orElseThrow();assertNotEquals(FlowRuntime.Status.FAILED,before.state().status(),()->before.state().diagnostic().toString());
   slept|=before.state().status()==FlowRuntime.Status.SLEEPING;
   var cold=new ComputerBlockHost(()->{});cold.read(host.write());cold.setPaused(false,LIMITS,true);assertEquals(before,cold.flowSnapshot().orElseThrow());host=cold;
   if(before.state().status()==FlowRuntime.Status.COMPLETE)break;
  }
  var done=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,done.state().status());assertTrue(slept);
  assertEquals(new DataValue.IntegerValue(7),done.state().locals().field("before"));assertEquals(new DataValue.IntegerValue(42),done.state().locals().field("after"));assertEquals(new DataValue.BooleanValue(true),done.state().locals().field("same"));
  assertEquals(new DataValue.IntegerValue(42),done.memory().publicLoad(done.state().flow(),0));
 }
}
