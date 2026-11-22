package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LocalReferenceBoundaryTest {
 static DataValue.LocalReference handle(){return new DataValue.LocalReference(UUID.randomUUID(),0,"0".repeat(64),UUID.randomUUID(),0,new DataType.RefType(DataType.Scalar.INTEGER,true));}
 @Test void nestedCapabilityNeverBecomesLibraryDataOrPortableConstant(){
  var ref=handle();var nested=new DataValue.RecordValue(Map.of("hidden",new DataValue.ListValue(List.of(ref))));
  assertEquals(1,nested.localReferences());
  assertAll(()->assertThrows(RuntimeException.class,()->new LibraryEntry(LibraryEntry.Kind.DATA,nested)),
   ()->assertThrows(RuntimeException.class,()->ValueNbtCodec.encode(nested,SoftwareAnyAbiTest.ARCHIVE)),
   ()->assertThrows(RuntimeException.class,()->new ComputationGraph.Constant(nested)),
   ()->assertThrows(RuntimeException.class,()->new ProgramDefinition.Cell(new DataType.RecordType(Map.of("hidden",new DataType.ListType(ref.type()))),nested)),
   ()->assertFalse(DataType.Scalar.ANY.accepts(nested)),
   ()->assertFalse(DataType.Scalar.ANY.compatibleWith(DataType.typeOf(nested))));
 }
 @Test void legacyPureMachineCannotAcceptNominalExternalInputs(){var ref=handle();var graph=new ComputationGraph(Map.of("value",ref.type()),Map.of("in",new ComputationGraph.Input("value")),Map.of("out","in"));var image=ProgramCompiler.compile(new ProgramDefinition(graph,Map.of(),List.of()),Map.of(),new ResourceLimits(1000,100000,1));var machine=new ComputationMachine(image);machine.begin(new DataValue.RecordValue(Map.of("value",ref)));assertEquals(ComputationMachine.Status.FAILED,machine.report().status());assertEquals(ComputationException.Code.TYPE_MISMATCH,machine.report().diagnostic().orElseThrow().code());}
 @Test void sharedTypeDagIsVisitedByIdentity(){assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->{DataType type=DataType.Scalar.INTEGER;for(int i=0;i<100;i++)type=new DataType.RecordType(Map.of("left",type,"right",type));assertFalse(LocalReferences.contains(type));});}
 @Test void referenceSignatureCannotRunThroughLegacyGraphEvenWithoutReferenceInstruction(){
  var type=new DataType.RefType(DataType.Scalar.INTEGER,true);
  var source=new FlowProgram(Map.of(),List.of(new FlowProgram.Procedure("definition",new ModuleDefinition.Key("identity",1),Map.of("value",type),Map.of("result",type),Map.of("result","value")),new FlowProgram.EndProcedure("end")));
  assertThrows(ComputationException.class,()->source.compile(new ResourceLimits(2000,100000,1)));
 }
}
