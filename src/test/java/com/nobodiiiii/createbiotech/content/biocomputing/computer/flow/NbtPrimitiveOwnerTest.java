package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NbtPrimitiveOwnerTest {
 static final ArchiveLimits ARCHIVE=new ArchiveLimits(100000,1000000,1000000);
 static final ComputerHostLimits BASE=NativeLibraryOwnersTest.limits("cleric",4);
 static final ComputerHostLimits LIMITS=new ComputerHostLimits(new ResourceLimits(4096,100000,1),ARCHIVE,BASE.mailbox(),BASE.pins(),BASE.serial(),BASE.taskSlots(),true,false);
 static final Map<String,DataType> IN=Map.of("input",DataType.Scalar.NBT);
 static final ModuleDefinition.Key KEY=new ModuleDefinition.Key("native_walk",1);
 static final String SOURCE="REG result INTEGER\nNBT.TAG result input\nRET";
 static FlowProgram program(String body,DataType output) {return new FlowProgram(IN,List.of(
  new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("input","input"),Map.of("result","out"))),
  new FlowProgram.Procedure("definition",KEY,IN,Map.of("result",output),Map.of("result","result"),FlowBlockRules.NONE,Optional.of(new AssemblySource(body))),
  new FlowProgram.EndProcedure("end"))).withAssembly();}
 static FlowRuntime.Checkpoint run(String body,DataType output,DataValue.NbtValue input,boolean reload,ComputerHostLimits limits){
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(program(body,output),new DataValue.RecordValue(Map.of("input",input)))));
  var document=workspace.snapshot();var wire=ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,ARCHIVE),ARCHIVE);assertEquals(document,wire);
  var host=new ComputerBlockHost(()->{});host.installProgram(PreparedProgram.prepare(wire,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);host.setPaused(false,limits,true);
  for(int i=0;i<10000;i++){host.tick(limits,true);var saved=host.flowSnapshot().orElseThrow();var state=saved.state();
   if(state.status()==FlowRuntime.Status.COMPLETE)return state;
   if(state.status()==FlowRuntime.Status.FAILED){var e=state.diagnostic().orElseThrow();throw new ComputationException(e.code(),e.location(),e.message());}
   if(reload){var cold=new ComputerBlockHost(()->{});cold.read(host.write());cold.setPaused(false,limits,true);assertEquals(saved,cold.flowSnapshot().orElseThrow());host=cold;}
  }throw new AssertionError("not complete");
 }
 @Test void authoredPublicInstallReadsRuntimeNativeTagWithoutAny(){
  var compiled=ProcedureAssembler.assemble(new AssemblySource(SOURCE),IN,Map.of("result",DataType.Scalar.INTEGER),Map.of("result","result"),65536,4096).program();
  assertTrue(compiled.instructionFamilies().contains(InstructionFamily.ISA_1A));assertFalse(compiled.instructionFamilies().contains(InstructionFamily.ISA_1B));
  for(var input:List.of(new DataValue.NbtValue(4,new DataValue.IntegerValue(42)),new DataValue.NbtValue(8,new DataValue.TextValue("runtime")))){
   var done=run(SOURCE,DataType.Scalar.INTEGER,input,false,LIMITS);var cold=run(SOURCE,DataType.Scalar.INTEGER,input,true,LIMITS);
   assertEquals(new DataValue.IntegerValue(input.tagId()),done.locals().field("out"));assertEquals(done.locals(),cold.locals());assertEquals(done.work(),cold.work());
  }
 }
 static final String REBUILD="""
 TYPE TextMaybe OPTIONAL TEXT
 TYPE Checked RECORD ok BOOLEAN value TextMaybe
 TYPE Pair RECORD key TEXT value NBT
 TYPE Pairs LIST Pair
 REG n INTEGER
 REG one INTEGER
 REG key TEXT
 REG child NBT
 REG checked Checked
 REG ok BOOLEAN
 REG textMaybe TextMaybe
 REG text TEXT
 REG pair Pair
 REG pairs Pairs
 REG result NBT
 CONST one 1
 NBT.LENGTH n input
 SUBTRACT_INTEGER n n one
 NBT.KEY key input n
 NBT.CHILD child input n
 NBT.READ.TEXT checked child
 FIELD ok checked ok
 JFALSE ok wrap
 FIELD textMaybe checked value
 REQUIRE_VALUE text textMaybe
 NBT.PACK child 8 text
 wrap:
 RECORD.MAKE pair key key value child
 LIST.MAKE pairs pair
 NBT.PACK result 10 pairs
 SLEEP one
 RET
 """;
 @Test void runtimeCompoundKeysAndVariableTextRebuildThroughPublicDocumentAndColdSleep(){
  String image=null;
  for(var fields:List.of(Map.<String,DataValue>of("integer_runtime",new DataValue.NbtValue(4,new DataValue.IntegerValue(42))),Map.<String,DataValue>of("alpha",new DataValue.NbtValue(8,new DataValue.TextValue("hello"))),new LinkedHashMap<String,DataValue>() {{put("a",new DataValue.NbtValue(4,new DataValue.IntegerValue(23)));put("long_runtime_key",new DataValue.NbtValue(8,new DataValue.TextValue("x".repeat(75))));}})){
   var input=new DataValue.NbtValue(10,new DataValue.RecordValue(fields));var before=ValueNbtCodec.decode(ValueNbtCodec.encode(input,ARCHIVE),ARCHIVE);
   var done=run(REBUILD,DataType.Scalar.NBT,input,false,LIMITS);var cold=run(REBUILD,DataType.Scalar.NBT,input,true,LIMITS);
   var last=((DataValue.RecordValue)input.value()).indexedFields().getLast();var expected=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of(last.getKey(),last.getValue())));
   if(image==null)image=done.assembly().orElseThrow().image();else assertEquals(image,done.assembly().orElseThrow().image());
   assertEquals(expected,done.locals().field("out"));assertEquals(done.locals(),cold.locals());assertEquals(done.work(),cold.work());assertEquals(before,input);
   System.out.printf("NBT rebuild work=%d units=%d%n",done.work(),expected.storageUnits());
  }
 }
 @Test void nativeNumericPayloadReadsPreserveExactFloatAndDoubleBits(){
  String body="TYPE Maybe OPTIONAL INTEGER\nTYPE Checked RECORD ok BOOLEAN value Maybe\nREG checked Checked\nREG optional Maybe\nREG result INTEGER\nNBT.READ.INTEGER checked input\nFIELD optional checked value\nREQUIRE_VALUE result optional\nRET";
  long[][] samples={{1,-128},{2,32767},{3,Integer.MIN_VALUE},{4,Long.MIN_VALUE},{5,Integer.MIN_VALUE},{5,0x7fc01234},{5,0x7f800000},{6,Long.MIN_VALUE},{6,0x7ff8000000001234L},{6,0x7ff0000000000000L}};
  for(var sample:samples){var input=new DataValue.NbtValue((int)sample[0],new DataValue.IntegerValue(sample[1]));var done=run(body,DataType.Scalar.INTEGER,input,true,LIMITS);assertEquals(new DataValue.IntegerValue(sample[1]),done.locals().field("out"));}
 }
 static ComputerHostLimits budget(long code,long ram){return new ComputerHostLimits(new ResourceLimits(code,ram,1),ARCHIVE,BASE.mailbox(),BASE.pins(),BASE.serial(),BASE.taskSlots(),true,false);}
 static boolean fits(DataValue.NbtValue input,long code,long ram,boolean cold){try{run(REBUILD,DataType.Scalar.NBT,input,cold,budget(code,ram));return true;}catch(ComputationException e){assertEquals(ComputationException.Code.MEMORY_LIMIT,e.code());return false;}}
 @Test void exactPublicCapacityHardwareAndOriginalVersionsRemainEnforced(){
  var input=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("alpha",new DataValue.NbtValue(8,new DataValue.TextValue("hello")))));
  var p=program(REBUILD,DataType.Scalar.NBT);var usage=p.usage(LIMITS.hardware());long code=usage.programUnits();assertEquals(91,code);
  assertEquals(ComputationException.Code.PROGRAM_LIMIT,assertThrows(ComputationException.class,()->p.validate(new ResourceLimits(code-1,100000,1))).code());
  long low=0,high=100000;while(low+1<high){long mid=(low+high)/2;if(fits(input,code,mid,false))high=mid;else low=mid;}
  assertEquals(1026,high);assertTrue(fits(input,code,high,false));assertFalse(fits(input,code,high-1,false));assertTrue(fits(input,code,high,true));
  var done=run(REBUILD,DataType.Scalar.NBT,input,true,budget(code,high));assertEquals(35,done.work());
  var actual=run(REBUILD,DataType.Scalar.NBT,input,true,BASE);assertEquals(done.locals(),actual.locals());assertFalse(BASE.isa1b());
  System.out.printf("NBT exact code=%d constants=%d registers=%d RAM=%d work=%d; cleric4 code=%d RAM=%d%n",code,usage.constantUnits(),usage.registers(),high,done.work(),BASE.hardware().programUnits(),BASE.hardware().memoryUnits());
  var scalar=new ComputerHostLimits(LIMITS.hardware(),ARCHIVE);assertThrows(IllegalArgumentException.class,()->run(SOURCE,DataType.Scalar.INTEGER,input,false,scalar));
  assertThrows(IllegalArgumentException.class,()->run("REG result ANY\nMOV result input\nRET",DataType.Scalar.ANY,input,false,LIMITS));
  for(int v=2;v<=8;v++){int version=v;assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(version,SOURCE),IN,Map.of(),Map.of(),65536,4096));}
  assertEquals(7,new AssemblySource("RET").version());
  assertFalse(ProcedureAssembler.assemble(new AssemblySource("REG v ANY INTEGER BOOLEAN\nRET"),Map.of(),Map.of(),Map.of(),65536,4096).program().instructionFamilies().contains(InstructionFamily.ISA_1A));
 }
 @Test void authoredNbtCannotEscapeNestedEvaluateExclusion(){
  var steps=new ArrayList<>(program(SOURCE,DataType.Scalar.INTEGER).steps());
  var old=(FlowProgram.Procedure)steps.get(1);steps.set(1,new FlowProgram.Procedure(old.sourceId(),old.key(),old.inputs(),old.outputs(),old.returns(),new FlowBlockRules(Set.of("evaluate"),Set.of()),old.assembly()));
  var p=new FlowProgram(IN,steps).withAssembly();assertTrue(assertThrows(ComputationException.class,()->p.validate(LIMITS.hardware())).getMessage().contains("evaluate"));
 }
 static DataValue replace(DataValue value,DataValue replacement,String... path){return replaceAt(value,replacement,path,0);}
 static DataValue replaceAt(DataValue value,DataValue replacement,String[] path,int offset){if(offset==path.length)return replacement;var fields=new LinkedHashMap<>(((DataValue.RecordValue)value).fields());fields.put(path[offset],replaceAt(fields.get(path[offset]),replacement,path,offset+1));return new DataValue.RecordValue(fields);}
 @Test void savedNativeConstructionHasStrictVersionAndCannotImportTrustedPrefix(){
  var input=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("alpha",new DataValue.NbtValue(8,new DataValue.TextValue("hello")))));
  var host=new ComputerBlockHost(()->{});host.installFlow(program(REBUILD,DataType.Scalar.NBT),new DataValue.RecordValue(Map.of("input",input)),LIMITS,true);host.setPaused(false,LIMITS,true);
  for(int i=0;i<100;i++){host.tick(LIMITS,true);var saved=host.flowSnapshot().orElseThrow();
   if(saved.state().assembly().orElseThrow().build().filter(RegisterConstruction::nativePack).isEmpty())continue;
   var snapshot=new FlowNbtCodec.Snapshot(saved.program(),saved.state());var encoded=FlowNbtCodec.encode(snapshot,ARCHIVE);assertEquals(snapshot,FlowNbtCodec.decode(encoded,ARCHIVE));
   var wire=ValueNbtCodec.decode(encoded,ARCHIVE);
   for(var change:Map.<String,DataValue>of("version",new DataValue.IntegerValue(2),"kind",new DataValue.TextValue("unknown"),"tag",new DataValue.IntegerValue(8),"paid",new DataValue.IntegerValue(0)).entrySet()){
    var invalid=replace(wire,change.getValue(),"state","assembly","build",change.getKey());assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(ValueNbtCodec.encode(invalid,ARCHIVE),ARCHIVE));
   }
   var build=saved.state().assembly().orElseThrow().build().orElseThrow().value();var forged=new LinkedHashMap<>(build.fields());forged.put("trusted_prefix",input);
   var invalid=replace(wire,new DataValue.RecordValue(forged),"state","assembly","build");assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(ValueNbtCodec.encode(invalid,ARCHIVE),ARCHIVE));return;
  }fail("no native construction checkpoint");
 }
 static final String EMPTY_PACK="TYPE Pair RECORD key TEXT value NBT\nTYPE Pairs LIST Pair\nREG empty Pairs\nREG result NBT\nLIST.MAKE empty\nNBT.PACK result 10 empty\nRET";
 @Test void emptyCompoundMinimumRejectsUnderpaidActiveAuthoredCall(){
  var p=program(EMPTY_PACK,DataType.Scalar.NBT);var image=p.compile(LIMITS.hardware()).assembly().orElseThrow();
  var host=new ComputerBlockHost(()->{});host.installFlow(p,new DataValue.RecordValue(Map.of("input",new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE))),LIMITS,true);host.setPaused(false,LIMITS,true);
  for(int i=0;i<100;i++){
   host.tick(LIMITS,true);var checkpoint=host.flowSnapshot().orElseThrow().state();var state=checkpoint.assembly().orElseThrow();
   if(state.pc()<image.code().size() && image.code().get(state.pc()).instruction() instanceof RegisterProgram.Return && !state.calls().orElseThrow().frames().isEmpty()){
    assertTrue(state.build().isEmpty());image.validate(state,checkpoint.work(),false);
    assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->image.validate(state,checkpoint.work()-1,false)).code());return;
   }
  }fail("no authored return checkpoint");
 }
 @Test void emptyCompoundMinimumPropagatesToReturnedChildWorkProof(){
  var done=run(EMPTY_PACK,DataType.Scalar.NBT,new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE),true,LIMITS);
  var image=program(EMPTY_PACK,DataType.Scalar.NBT).compile(LIMITS.hardware()).assembly().orElseThrow();var state=done.assembly().orElseThrow();image.validate(state,done.work(),false);
  var calls=state.calls().orElseThrow();assertTrue(calls.frames().isEmpty());assertTrue(calls.excludedWork()>0);
  var forgedCalls=new RegisterCalls(calls.excludedWork()-1,calls.frames());
  var forged=new RegisterState(state.image(),state.pc(),state.registers(),state.structured(),state.scan(),Optional.of(forgedCalls),state.transaction(),state.constructionCapable(),state.build(),state.serialCapable(),state.serial(),state.equalityCapable(),state.comparison());
  assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->image.validate(forged,done.work()-1,false)).code());
 }
}
