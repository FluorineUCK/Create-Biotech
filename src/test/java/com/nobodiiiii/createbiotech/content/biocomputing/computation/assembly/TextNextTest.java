package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TextNextTest {
 static final DataType.RecordType TYPE=new DataType.RecordType(Map.of("done",DataType.Scalar.BOOLEAN,"next",DataType.Scalar.INTEGER,"codepoint",DataType.Scalar.INTEGER,"whitespace",DataType.Scalar.BOOLEAN,"control",DataType.Scalar.BOOLEAN));
 @Test void v7ExposesConcreteOneStepTextIterator(){
  var image=ProcedureAssembler.assemble(new AssemblySource("REG result TEXT_NEXT\nTEXT.NEXT result text offset\nRET"),Map.of("text",DataType.Scalar.TEXT,"offset",DataType.Scalar.INTEGER),Map.of("result",TYPE),Map.of("result","result"),65536,256).program();
  assertEquals(4,image.code().getFirst().instruction().words());
  assertEquals(1,RegisterProgram.work(image.code().getFirst().instruction()));
  assertFalse(RegisterProgram.variableWork(image.code().getFirst().instruction()));
  assertEquals(InstructionFamily.ISA_1A,image.code().getFirst().instruction().family());
  assertTrue(image.advancedTypeLocation().isEmpty());
 }

 static RegisterProgram direct(){
  return new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.TextNext(2,0,1),0,"iterator/line 1")),List.of(DataType.Scalar.TEXT,DataType.Scalar.INTEGER,TYPE),List.of(),Map.of("text",0,"offset",1,"result",2),Map.of("text",0,"offset",1,"result",2),1);
 }
 static DataValue.RecordValue expected(boolean done,long next,long cp,boolean whitespace,boolean control){return new DataValue.RecordValue(Map.of("done",new DataValue.BooleanValue(done),"next",new DataValue.IntegerValue(next),"codepoint",new DataValue.IntegerValue(cp),"whitespace",new DataValue.BooleanValue(whitespace),"control",new DataValue.BooleanValue(control)));}
 static RegisterState start(RegisterProgram image,String text,long offset){return image.begin(new DataValue.RecordValue(Map.of("text",new DataValue.TextValue(text),"offset",new DataValue.IntegerValue(offset),"result",expected(false,99,99,true,true))));}
 @Test void exactJavaCodepointsAndPropertiesWithFixedWorkAndReserve(){
  var p=direct();
  for(String text:List.of("","a","中","\ud83d\ude00","\ud800x","\udc00x","a\ud800","\u0000","\u001f","\u007f","\u0085","\u009f","\u2003","\u00a0","z".repeat(100000))){
   for(int offset=0;;){
    var before=start(p,text,offset);var reserved=new ArrayList<Long>();var after=p.step(before,Map.of(),Map.of(),1,reserved::add).registers();
    boolean end=offset==text.length();int cp=end?-1:text.codePointAt(offset);int next=end?offset:offset+Character.charCount(cp);
    assertEquals(expected(end,next,cp,!end&&Character.isWhitespace(cp),!end&&Character.isISOControl(cp)),after.registers().get(2));
    assertEquals(List.of(45L),reserved);assertEquals(45,after.registers().get(2).storageUnits());assertEquals(1,after.pc());assertTrue(after.scan().isEmpty());assertEquals(new DataValue.TextValue(text),after.registers().get(0));assertEquals(expected(false,99,99,true,true),before.registers().get(2));p.validate(after,1,false);
    if(end || text.length()>1000)break;offset=next;
   }
  }
  for(int offset:new int[]{99999,100000}){var before=start(p,"z".repeat(100000),offset);var after=p.step(before);assertEquals(expected(offset==100000,100000,offset==100000?-1:122,false,false),after.registers().get(2));p.validate(after,1,false);}
  System.out.println("TEXT_NEXT fixed words=4 work=1 reserve=45 small/100000 UTF16 units");
 }
 @Test void badCursorsAndAdmissionRetainDestinationAndContinuation(){
  var p=direct();for(long offset:new long[]{-1,3,Long.MAX_VALUE}){
   var before=start(p,"ab",offset);var error=assertThrows(ComputationException.class,()->p.step(before,Map.of(),Map.of(),1,x->fail("must validate cursor before reserving")));assertEquals(ComputationException.Code.INDEX_OUT_OF_BOUNDS,error.code());assertEquals("iterator/line 1",error.location());assertEquals(0,before.pc());assertEquals(expected(false,99,99,true,true),before.registers().get(2));
  }
  var middle=start(p,"\ud83d\ude00",1);assertEquals(ComputationException.Code.INVALID_ARGUMENT,assertThrows(ComputationException.class,()->p.step(middle)).code());
  var before=start(p,"x",0);assertEquals(ComputationException.Code.MEMORY_LIMIT,assertThrows(ComputationException.class,()->p.step(before,Map.of(),Map.of(),1,n->{throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"owner","full");})).code());assertEquals(0,before.pc());assertEquals(expected(false,99,99,true,true),before.registers().get(2));
 }
 @Test void oldDialectsAndWrongTypesKeepSourceDiagnostics(){
  for(int v=2;v<=6;v++){ final int old=v; assertThrows(IllegalArgumentException.class,()->new AssemblySource(old,"RET")); }
  for(String operand:List.of("offset offset","text text")){
   var error=assertThrows(ComputationException.class,()->ProcedureAssembler.assemble(new AssemblySource("REG result TEXT_NEXT\nTEXT.NEXT result "+operand),Map.of("text",DataType.Scalar.TEXT,"offset",DataType.Scalar.INTEGER),Map.of("result",TYPE),Map.of("result","result"),65536,256));assertTrue(error.getMessage().contains("TEXT.NEXT type mismatch"));assertTrue(error.getMessage().contains("line 2"));
  }
 }

 @Test void forgedTextContinuationRetainsInstructionSourceDiagnostic(){
  var p=direct();var before=start(p,"x",0);var wrong=new TreeMap<>(before.registers());wrong.put(1,new DataValue.TextValue("not a cursor"));var forged=new RegisterState(before.image(),0,wrong,true,Optional.empty());var error=assertThrows(ComputationException.class,()->p.validate(forged,0,false));assertEquals(ComputationException.Code.INVALID_CHECKPOINT,error.code());assertEquals("iterator/line 1",error.location());
 }

 @Test void reservedTextNextAliasCannotBeShadowed(){
  var custom=new DataType.RecordType(Map.of("custom",DataType.Scalar.INTEGER));assertThrows(ComputationException.class,()->ProcedureAssembler.assemble(new AssemblySource("TYPE TEXT_NEXT RECORD custom INTEGER\nREG result TEXT_NEXT\nRECORD.MAKE result custom offset\nRET"),Map.of("offset",DataType.Scalar.INTEGER),Map.of("result",custom),Map.of("result","result"),65536,256).program());
 }
}
