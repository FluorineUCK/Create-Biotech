package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class RegisterRequiredValueTest {
 static RegisterProgram image(DataType source,DataType target,boolean defined) {
  var inputs=defined?Map.of("value",0):Map.<String,Integer>of();
  return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.Alu(ScalarOperation.REQUIRE_VALUE,1,List.of(0)),0,"extract_block")),List.of(source,target),List.of(),inputs,inputs,1);
 }
 @Test void everyNonNullKindPreservesImmutableIdentityAndPaysOneCycleThreeWordsRegardlessOfPayloadSize() {
  for(var value:List.<DataValue>of(new DataValue.IntegerValue(0),new DataValue.DecimalValue(0),new DataValue.BooleanValue(false),new DataValue.TextValue(""),
   new DataValue.ErrorValue("E","ordinary data"),new DataValue.NbtValue(3,new DataValue.IntegerValue(0)),
   new DataValue.ListValue(Collections.nCopies(10000,new DataValue.IntegerValue(7))),new DataValue.RecordValue(Map.of("payload",new DataValue.TextValue("x".repeat(10000)))))) {
   var type=DataType.typeOf(value);var p=image(new DataType.OptionalType(new DataType.OptionalType(type)),type,true);var old=p.begin(new DataValue.RecordValue(Map.of("value",value)));var next=p.step(old);
   assertSame(value,next.registers().get(1));assertFalse(old.registers().containsKey(1));assertEquals(3,p.codeWords());p.validate(next,1,false);assertEquals(1,RegisterProgram.work(p.code().getFirst().instruction()));
   assertTrue(next.registers().get(1).storageUnits()>0);
  }
 }
 @Test void anyAndKnownNullDoNotStrengthenAndNullFaultNeverPublishesDestinationOrPc() {
  for(var type:List.<DataType>of(DataType.Scalar.ANY,DataType.Scalar.NULL,new DataType.OptionalType(DataType.Scalar.INTEGER))) {
   var result=ScalarOperation.REQUIRE_VALUE.outputType(Map.of("value",type),"source");assertEquals(type instanceof DataType.OptionalType?DataType.Scalar.INTEGER:DataType.Scalar.ANY,result);
   var p=image(type,result,true);var old=p.begin(new DataValue.RecordValue(Map.of("value",DataValue.NullValue.INSTANCE)));var ex=assertThrows(ComputationException.class,()->p.step(old));
   assertEquals(ComputationException.Code.INVALID_ARGUMENT,ex.code());assertEquals("extract_block",ex.location());assertEquals(0,old.pc());assertFalse(old.registers().containsKey(1));
  }
  assertThrows(IllegalArgumentException.class,()->image(DataType.Scalar.ANY,DataType.Scalar.INTEGER,true));
  assertThrows(IllegalArgumentException.class,()->image(new DataType.OptionalType(DataType.Scalar.TEXT),DataType.Scalar.INTEGER,true));
  assertThrows(IllegalArgumentException.class,()->image(DataType.Scalar.INTEGER,DataType.Scalar.INTEGER,false));
 }
 @Test void authoredV3UsesExistingSignatureAliasesAndRejectsOldDialectUndefinedAndMalformedTypes() {
  var record=new DataType.RecordType(Map.of("answer",DataType.Scalar.INTEGER));var optional=new DataType.OptionalType(record);
  var src="REG payload OUTPUT payload\nREQUIRE_VALUE payload value\nRET";
  var p=ProcedureAssembler.assemble(new AssemblySource(src),Map.of("value",optional),Map.of("payload",record),Map.of("payload","payload"),10000,1000).program();assertEquals(9,p.codeWords());
  for(int v:List.of(23,2)) assertThrows(IllegalArgumentException.class,()->ProcedureAssembler.assemble(new AssemblySource(v,"REG payload INTEGER\nREQUIRE_VALUE payload value\nRET"),Map.of("value",DataType.Scalar.INTEGER),Map.of("payload",DataType.Scalar.INTEGER),Map.of("payload","payload"),10000,1000));
  for(String bad:List.of(src.replace("payload value","payload missing"),src.replace("OUTPUT payload","INTEGER"))) assertThrows(ComputationException.class,()->ProcedureAssembler.assemble(new AssemblySource(bad),Map.of("value",optional),Map.of("payload",record),Map.of("payload","payload"),10000,1000));
 }
}
