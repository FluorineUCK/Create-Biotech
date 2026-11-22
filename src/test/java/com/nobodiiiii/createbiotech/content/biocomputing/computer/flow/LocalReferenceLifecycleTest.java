package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class LocalReferenceLifecycleTest {
 static FlowProgram.Reference op(String id,FlowProgram.ReferenceKind kind,String target,String local){return new FlowProgram.Reference(id,kind,target,local,Optional.empty());}
 @ParameterizedTest @ValueSource(strings={"BOOLEAN","TEXT","NBT"}) void ordinaryAssignmentCopiesAliasWhileReadSnapshotRemainsImmutable(String target){
  var type=DataType.Scalar.valueOf(target);DataValue old=target.equals("BOOLEAN")?new DataValue.BooleanValue(false):new DataValue.TextValue("old".repeat(20)),next=target.equals("BOOLEAN")?new DataValue.BooleanValue(true):new DataValue.TextValue("new".repeat(30));if(type==DataType.Scalar.NBT){old=new DataValue.NbtValue(8,old);next=new DataValue.NbtValue(8,next);}
  var ref=new DataType.RefType(type,true);var graph=new ComputationGraph(Map.of("value",ref),Map.of("in",new ComputationGraph.Input("value")),Map.of("result","in"));
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of("cell",new ProgramDefinition.Cell(type,old)),List.of(),List.of(),Map.of("copy",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
  var p=new FlowProgram(Map.of("next",type,"ticks",DataType.Scalar.INTEGER),List.of(op("address",FlowProgram.ReferenceKind.ADDRESS,"cell","a"),new FlowProgram.Evaluate("copy","copy",Optional.of(new FlowCallBindings(Map.of("value","a"),Map.of("result","b")))),new FlowProgram.Evaluate("self","copy",Optional.of(new FlowCallBindings(Map.of("value","b"),Map.of("result","b")))),op("snapshot",FlowProgram.ReferenceKind.READ,"a","before"),op("readonly",FlowProgram.ReferenceKind.READONLY,"a","ro"),new FlowProgram.Reference("write",FlowProgram.ReferenceKind.WRITE,"b","",Optional.of(new FlowInput.Local("next"))),new FlowProgram.Sleep("sleep","ticks"),op("read",FlowProgram.ReferenceKind.READ,"ro","after")),archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
  var initial=new DataValue.RecordValue(Map.of("next",next,"ticks",new DataValue.IntegerValue(2)));var warm=SoftwareAnyAbiTest.run(p,initial,false);var cold=SoftwareAnyAbiTest.run(p,initial,true);
  assertEquals(warm.work(),cold.work());assertEquals(old,cold.locals().field("before"));assertEquals(next,cold.locals().field("after"));assertEquals(initial,new DataValue.RecordValue(Map.of("next",next,"ticks",new DataValue.IntegerValue(2))));
  var a=(DataValue.LocalReference)cold.locals().field("a");assertEquals(a,cold.locals().field("b"));assertTrue(a.sameTarget((DataValue.LocalReference)cold.locals().field("ro")));assertFalse(((DataValue.LocalReference)cold.locals().field("ro")).type().writable());
 }
 static RegisterProgram drops(){var type=new DataType.RefType(DataType.Scalar.INTEGER,true);var ops=List.<RegisterProgram.Instruction>of(new RegisterReference(RegisterReference.Kind.ADDRESS,0,-1,0),new RegisterProgram.Move(1,0),new RegisterProgram.Move(1,1),new RegisterProgram.Drop(0),new RegisterProgram.Drop(1));var code=new ArrayList<RegisterProgram.Code>();for(int i=0;i<ops.size();i++)code.add(new RegisterProgram.Code(ops.get(i),i,"ref/"+i));return new RegisterProgram(code,List.of(type,type),List.of(),Map.of(),Map.of(),ops.size(),List.of(),Map.of("cell",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(7))));}
 @Test void droppingLastAliasReleasesOnlyHandleRootsNotBankCell(){
  var image=drops();var memory=RegisterMemory.initial(Map.of("cell",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(7))));var empty=image.begin(new DataValue.RecordValue(Map.of()));var one=image.stepReference(empty,memory,UUID.randomUUID(),0,UUID.randomUUID()).registers();var ref=(DataValue.LocalReference)one.registers().get(0);assertEquals(206,ref.storageUnits());
  var two=image.step(one);assertEquals(ref,two.registers().get(1));assertEquals(ref.storageUnits()+2,two.value().storageUnits()-one.value().storageUnits());var self=image.step(two);assertEquals(two.registers(),self.registers());var last=image.step(self);assertEquals(ref.storageUnits()+2,self.value().storageUnits()-last.value().storageUnits());var none=image.step(last);assertTrue(none.registers().isEmpty());assertEquals(new DataValue.IntegerValue(7),memory.cells().field("cell"));assertEquals(empty.value().storageUnits(),none.value().storageUnits());
 }
 @Test void staleInstructionImageCannotMintNewHandle(){var image=drops();var empty=image.begin(new DataValue.RecordValue(Map.of()));var stale=new RegisterState("f".repeat(64),empty.pc(),empty.registers(),empty.structured(),empty.scan(),empty.calls(),empty.transaction());var memory=RegisterMemory.initial(Map.of("cell",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(7))));assertThrows(ComputationException.class,()->image.stepReference(stale,memory,UUID.randomUUID(),0,UUID.randomUUID()));}
 @Test void portableDocumentInitialValuesCannotContainEvenExplicitlyTypedReference(){var ref=LocalReferenceBoundaryTest.handle();assertThrows(RuntimeException.class,()->new FlowDocumentBody(Map.of("value",ref.type()),List.of(),new DataValue.RecordValue(Map.of("value",ref))));}
}
