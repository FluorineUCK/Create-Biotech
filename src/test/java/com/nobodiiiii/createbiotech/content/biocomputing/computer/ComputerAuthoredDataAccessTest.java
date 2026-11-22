package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerAuthoredDataAccessTest {
 static final com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits LIMITS=ParserHardwareFixture.withMasterParser(ComputerFlowHostTest.LIMITS);
 static FlowProgram program(Map<String,DataType> inputs, Map<String,DataType> outputs, Map<String,String> returns,String source) {
  var key=new ModuleDefinition.Key("select",1);var args=new TreeMap<String,String>();inputs.keySet().forEach(n->args.put(n,n));
  var results=new TreeMap<String,String>();outputs.keySet().forEach(n->results.put(n,n));
  return new FlowProgram(inputs,List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(args,results)),
   new FlowProgram.Procedure("select",key,inputs,outputs,returns,FlowBlockRules.NONE,Optional.of(new AssemblySource(source))),new FlowProgram.EndProcedure("end"))).withAssembly();
 }
 static ComputerBlockHost run(FlowProgram p,DataValue.RecordValue input) {
  var host=new ComputerBlockHost(()->{});host.installFlow(p,input,LIMITS,true);
  for(int i=0;host.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.READY;i++) {
   assertTrue(i<100);var before=host.flowSnapshot().orElseThrow();var saved=host.write();
   var restored=new ComputerBlockHost(()->{});restored.read(saved);restored.tick(LIMITS,true);
   assertTrue(restored.loaded());assertEquals(before,restored.flowSnapshot().orElseThrow());assertEquals(saved,restored.write());
   host=restored;host.step(LIMITS,true);assertEquals(before.state().work()+1,host.flowSnapshot().orElseThrow().state().work());
   assertTrue(host.flowSnapshot().orElseThrow().state().expression().isEmpty());
  }
  return host;
 }
 @Test void typedListAndRuntimeIndexReturnThroughAuthoredScratchCallAndReloadEveryInstruction() {
  var p=program(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER),"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","selected"),"REG selected ELEMENT items\nLIST.GET selected items index\nRET");
  assertEquals(29,p.usage(LIMITS.hardware()).programUnits());
  var input=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(0),new DataValue.IntegerValue(42))),"index",new DataValue.IntegerValue(1)));
  var done=run(p,input).flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,done.state().status());
  assertEquals(new DataValue.IntegerValue(42),done.state().locals().field("result"));assertEquals(input,done.initialInputs());
 }
 @Test void recordFieldAndTagPredicatesAcceptOptionalStructuredAndAnyWithoutConversion() {
  var nullable=new DataType.OptionalType(new DataType.ListType(DataType.Scalar.INTEGER));
  var record=new DataType.RecordType(Map.of("value",nullable,"outcome",DataType.Scalar.ANY));
  var p=program(Map.of("record",record,"nullable",nullable),Map.of("nil",DataType.Scalar.BOOLEAN,"error",DataType.Scalar.BOOLEAN),Map.of("nil","nil","error","error"),
   "REG field OUTPUT nil\nREG optional INPUT record\nREG optionalField INPUT nullable\nREG outcome ANY\nREG nil BOOLEAN\nREG error BOOLEAN\nFIELD optionalField record value\nFIELD outcome record outcome\nIS_NULL nil optionalField\nIS_ERROR error outcome\nRET");
  var input=new DataValue.RecordValue(Map.of("nullable",DataValue.NullValue.INSTANCE,"record",new DataValue.RecordValue(Map.of("value",DataValue.NullValue.INSTANCE,"outcome",new DataValue.ErrorValue("fixture","failed")))));
  var done=run(p,input).flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
  assertEquals(new DataValue.BooleanValue(true),done.locals().field("nil"));assertEquals(new DataValue.BooleanValue(true),done.locals().field("error"));
 }
 @Test void listLengthAndAuthoredTextScanKeepCachedSizeAndOwnerContinuation() {
  var p=program(Map.of("items",new DataType.ListType(DataType.Scalar.TEXT),"text",DataType.Scalar.TEXT),Map.of("size",DataType.Scalar.INTEGER,"points",DataType.Scalar.INTEGER),Map.of("size","size","points","points"),
   "REG size INTEGER\nREG points INTEGER\nLIST.LENGTH size items\nTEXT.LENGTH points text\nRET");
  var input=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.TextValue("large"),new DataValue.TextValue("ignored"))),"text",new DataValue.TextValue("测😀abc")));
  var done=run(p,input).flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());
  assertEquals(new DataValue.IntegerValue(2),done.locals().field("size"));assertEquals(new DataValue.IntegerValue(5),done.locals().field("points"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(longs={-1,2,2147483648L,4294967296L,Long.MIN_VALUE,Long.MAX_VALUE})
 void invalidDynamicIndicesFailDurablyRatherThanNarrowingOrReturningZero(long index) {
  var p=program(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER),"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","selected"),"REG selected ELEMENT items\nLIST.GET selected items index\nRET");
  var input=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(0),new DataValue.IntegerValue(42))),"index",new DataValue.IntegerValue(index)));
  var host=run(p,input);var failed=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.FAILED,failed.state().status());
  assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS,failed.state().diagnostic().orElseThrow().code());assertFalse(failed.state().locals().fields().containsKey("result"));
  var restored=new ComputerBlockHost(()->{});restored.read(host.write());restored.tick(LIMITS,true);assertTrue(restored.loaded());assertEquals(failed,restored.flowSnapshot().orElseThrow());
 }
 @Test void zeroElementIsNotAnIndexErrorAndStructuredLocalAliasesAreCompileTimeOnly() {
  var p=program(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER),"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","selected"),"REG spare INPUT items\nREG output OUTPUT result\nREG selected ELEMENT items\nLIST.GET selected items index\nRET");
  var input=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(0))),"index",new DataValue.IntegerValue(0)));
  var done=run(p,input).flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());assertEquals(new DataValue.IntegerValue(0),done.locals().field("result"));
 }
 @Test void selectingLargeImmutableChildChargesLogicalRamAndPublishesNothingOnFailure() {
  var p=program(Map.of("items",new DataType.ListType(DataType.Scalar.TEXT),"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.TEXT),Map.of("result","selected"),"REG selected ELEMENT items\nLIST.GET selected items index\nRET");
  var child=new DataValue.TextValue("x".repeat(2000));var input=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(child)),"index",new DataValue.IntegerValue(0)));
  var host=new ComputerBlockHost(()->{});host.installFlow(p,input,LIMITS,true);for(int i=0;i<3;i++)host.step(LIMITS,true);
  var before=host.flowSnapshot().orElseThrow();assertFalse(before.state().locals().fields().containsKey("selected"));
  long memory=before.state().controlUnits()+before.initialInputs().storageUnits()+before.control().storageUnits()+p.usage(LIMITS.hardware()).constantUnits()+64;
  var small=ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000,memory,1),LIMITS.archive()));
  host.step(small,true);var failed=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.FAILED,failed.state().status());
  assertEquals(ComputationException.Code.MEMORY_LIMIT,failed.state().diagnostic().orElseThrow().code());assertEquals(before.state().assembly(),failed.state().assembly());assertEquals(before.state().work()+1,failed.state().work());
  var restored=new ComputerBlockHost(()->{});restored.read(host.write());restored.tick(LIMITS,true);assertEquals(failed,restored.flowSnapshot().orElseThrow());
  var done=run(p,input).flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,done.status());assertEquals(child,done.locals().field("result"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={
  "REG selected ELEMENT absent\nRET", "REG selected ELEMENT index\nRET", "REG selected ELEMENT later\nREG later INPUT items\nRET", "REG selected INPUT absent\nRET", "REG selected OUTPUT absent\nRET", "REG selected TEXT\nCONST selected true\nRET", "REG selected ELEMENT items\nMOV selected uninitialized\nRET"})
 void typeAliasesAndNonScalarConstantsHaveDirectSourceDiagnostics(String source) {
  var p=program(Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER),"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","selected"),source);
  var invalid=assertThrows(ComputationException.class,()->p.validate(LIMITS.hardware()));assertTrue(invalid.getMessage().contains("line "),invalid.getMessage());
 }
 @Test void typedVersionsAndUnsupportedVariableCostAluRemainFenced() {
  for(int version:List.of(23,2)){
   assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(version,"REG selected INTEGER\nLIST.GET selected items index\nRET"),Map.of("items",new DataType.ListType(DataType.Scalar.INTEGER),"index",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","selected"),10000,1000));
   assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(version,"REG nil BOOLEAN\nIS_NULL nil value\nRET"),Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.BOOLEAN),Map.of("result","nil"),10000,1000));
  }
  for(String source:List.of("REG selected TEXT\nERROR_MESSAGE selected value\nRET","REG selected ANY\nREG flag BOOLEAN\nCONST flag true\nSELECT selected flag value value\nRET"))
   assertThrows(ComputationException.class,()->program(Map.of("value",DataType.Scalar.ANY),Map.of("result",DataType.Scalar.ANY),Map.of("result","selected"),source).validate(LIMITS.hardware()));
 }
 @Test void anyAndNullMisuseOrMissingMapKeysStayExplicit() {
  var any=Map.<String,DataType>of("value",DataType.Scalar.ANY,"index",DataType.Scalar.INTEGER);
  for(String source:List.of("REG selected ANY\nLIST.GET selected value index\nRET","REG selected INTEGER\nLIST.LENGTH selected value\nRET","REG selected ANY\nFIELD selected value missing\nRET")) {
   var p=program(any,Map.of("result",source.contains("LIST.LENGTH")?DataType.Scalar.INTEGER:DataType.Scalar.ANY),Map.of("result","selected"),source);
   var done=run(p,new DataValue.RecordValue(Map.of("value",DataValue.NullValue.INSTANCE,"index",new DataValue.IntegerValue(0)))).flowSnapshot().orElseThrow().state();
   assertEquals(FlowRuntime.Status.FAILED,done.status());assertEquals(ComputationException.Code.TYPE_MISMATCH,done.diagnostic().orElseThrow().code());
  }
  var p=program(Map.of("value",new DataType.MapType(DataType.Scalar.INTEGER)),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","selected"),"REG selected INTEGER\nFIELD selected value missing\nRET");
  var done=run(p,new DataValue.RecordValue(Map.of("value",new DataValue.MapValue(Map.of())))).flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.FAILED,done.status());assertEquals(ComputationException.Code.MISSING_FIELD,done.diagnostic().orElseThrow().code());
 }
 static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> immutableSignatures() {
  var scalar=new DataValue.IntegerValue(7);var list=new DataValue.ListValue(List.of(scalar));var record=new DataValue.RecordValue(Map.of("value",list));
  var map=new DataValue.MapValue(Map.of("value",list));var nbt=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("value",new DataValue.NbtValue(3,scalar))));
  return java.util.stream.Stream.of(
   org.junit.jupiter.params.provider.Arguments.of(DataType.typeOf(list),list),org.junit.jupiter.params.provider.Arguments.of(DataType.typeOf(record),record),
   org.junit.jupiter.params.provider.Arguments.of(DataType.typeOf(map),map),org.junit.jupiter.params.provider.Arguments.of(new DataType.OptionalType(DataType.typeOf(list)),DataValue.NullValue.INSTANCE),
   org.junit.jupiter.params.provider.Arguments.of(DataType.Scalar.TEXT,new DataValue.TextValue("immutable")),org.junit.jupiter.params.provider.Arguments.of(DataType.Scalar.NBT,nbt),
   org.junit.jupiter.params.provider.Arguments.of(DataType.Scalar.ERROR,new DataValue.ErrorValue("fixture","failed")),org.junit.jupiter.params.provider.Arguments.of(DataType.Scalar.NULL,DataValue.NullValue.INSTANCE),
   org.junit.jupiter.params.provider.Arguments.of(DataType.Scalar.ANY,record));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("immutableSignatures")
 void existingImmutableStructuredSignaturesReturnWithoutConstructingSamplesOrCasting(DataType type,DataValue value) {
  var p=program(Map.of("value",type),Map.of("result",type),Map.of("result","selected"),"REG selected OUTPUT result\nMOV selected value\nRET");
  var input=new DataValue.RecordValue(Map.of("value",value));var done=run(p,input).flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,done.state().status());
  assertEquals(value,done.state().locals().field("result"));assertEquals(input,done.initialInputs());
 }
}
