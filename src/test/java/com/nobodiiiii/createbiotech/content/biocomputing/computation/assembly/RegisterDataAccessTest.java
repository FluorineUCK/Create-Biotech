package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class RegisterDataAccessTest {
 @Test void listSelectionSharesImmutableChildAndLengthIsOneBoundedInstruction() {
  var child=new DataValue.RecordValue(Map.of("large",new DataValue.TextValue("x".repeat(10000))));
  var element=DataType.typeOf(child);var list=new DataValue.ListValue(List.of(child));
  var image=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.DynamicIndex(2,0,1),0,"get"),new RegisterProgram.Code(new RegisterProgram.ListLength(3,0),0,"size")),
   List.of(new DataType.ListType(element),DataType.Scalar.INTEGER,element,DataType.Scalar.INTEGER),List.of(),Map.of("items",0,"index",1),Map.of("items",0,"index",1),1);
  var state=image.begin(new DataValue.RecordValue(Map.of("items",list,"index",new DataValue.IntegerValue(0))));
  state=image.step(state);image.validate(state,1,false);assertSame(child,state.registers().get(2));
  state=image.step(state);image.validate(state,2,false);assertEquals(new DataValue.IntegerValue(1),state.registers().get(3));assertEquals(7,image.codeWords());
  var wrongWork=state;assertThrows(ComputationException.class,()->image.validate(wrongWork,3,false));
 }
 @Test void tagPredicatesOnStructuredRegistersDoNotEnableGeneralVariableCostAlu() {
  var type=new DataType.ListType(DataType.Scalar.INTEGER);var value=new DataValue.ListValue(Collections.nCopies(10000,new DataValue.IntegerValue(1)));
  for(var operation:List.of(ScalarOperation.IS_NULL,ScalarOperation.IS_ERROR)) {
   var image=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.Alu(operation,1,List.of(0)),0,"tag")),List.of(type,DataType.Scalar.BOOLEAN),List.of(),Map.of("value",0),Map.of("value",0),1);
   var next=image.step(image.begin(new DataValue.RecordValue(Map.of("value",value))));image.validate(next,1,false);assertEquals(new DataValue.BooleanValue(false),next.registers().get(1));
  }
  assertThrows(IllegalArgumentException.class,()->new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.Alu(ScalarOperation.SELECT,2,List.of(1,0,0)),0,"select")),List.of(type,DataType.Scalar.BOOLEAN,type),List.of(),Map.of("value",0,"flag",1),Map.of("value",0,"flag",1),1));
 }
 @Test void newLogicalIndexAndLengthInstructionsHaveFullDistinctImageIdentity() {
  var types=List.<DataType>of(new DataType.ListType(DataType.Scalar.INTEGER),DataType.Scalar.INTEGER,DataType.Scalar.INTEGER,DataType.Scalar.INTEGER);
  var inputs=Map.of("items",0,"first",1,"second",2);var values=new DataValue.RecordValue(Map.of("items",new DataValue.ListValue(List.of(new DataValue.IntegerValue(1))),"first",new DataValue.IntegerValue(0),"second",new DataValue.IntegerValue(0)));
  var a=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.DynamicIndex(3,0,1),0,"select")),types,List.of(),inputs,inputs,1);
  var b=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.DynamicIndex(3,0,2),0,"select")),types,List.of(),inputs,inputs,1);
  var c=new RegisterProgram(List.of(new RegisterProgram.Code(new RegisterProgram.ListLength(3,0),0,"select")),types,List.of(),inputs,inputs,1);
  assertNotEquals(a.begin(values).image(),b.begin(values).image());assertNotEquals(a.begin(values).image(),c.begin(values).image());
 }
}
