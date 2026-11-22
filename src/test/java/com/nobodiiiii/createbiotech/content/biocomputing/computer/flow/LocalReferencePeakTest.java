package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class LocalReferencePeakTest {
 static ComputerHostLimits limits(long ram){var base=NativeLibraryOwnersTest.limits("farmer",4);return new ComputerHostLimits(new ResourceLimits(base.hardware().programUnits(),ram,base.hardware().workPerSlice()),base.archive(),base.mailbox(),base.pins(),base.serial(),base.taskSlots(),base.isa1a(),base.isa1b());}
 static ComputerBlockHost before(FlowProgram source,DataValue value,long ram){var owner=new ComputerBlockHost(()->{});owner.installFlow(source,new DataValue.RecordValue(Map.of("next",value)),limits(ram),true);owner.step(limits(ram),true);return owner;}
 @ParameterizedTest @ValueSource(booleans={false,true}) void variableReplacementReservesOldAndNewWithoutAnyOldSnapshot(boolean nbt){
  DataValue old=new DataValue.TextValue("a".repeat(4000)),next=new DataValue.TextValue("b".repeat(5000));if(nbt){old=new DataValue.NbtValue(8,old);next=new DataValue.NbtValue(8,next);}
  var type=nbt?DataType.Scalar.NBT:DataType.Scalar.TEXT;
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of("cell",new ProgramDefinition.Cell(type,old)),List.of()),Map.of());
  var source=new FlowProgram(Map.of("next",type),List.of(new FlowProgram.Reference("address",FlowProgram.ReferenceKind.ADDRESS,"cell","a",Optional.empty()),new FlowProgram.Reference("update",FlowProgram.ReferenceKind.WRITE,"a","",Optional.of(new FlowInput.Local("next")))),archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
  var snapshot=before(source,next,98304).flowSnapshot().orElseThrow();assertEquals(1,snapshot.state().work());assertFalse(snapshot.state().locals().fields().containsValue(old));
  long retained=snapshot.initialInputs().storageUnits()+snapshot.control().storageUnits()+snapshot.memory().storageUnits()+snapshot.state().controlUnits()+source.compile(limits(98304).hardware()).assembly().orElseThrow().constantUnits()+snapshot.tasks().metadataUnits()+snapshot.pinUnits();
  long temporary=2+next.storageUnits(),peak=Math.addExact(retained,temporary);
  var exact=before(source,next,peak);exact.step(limits(peak),true);assertEquals(FlowRuntime.Status.COMPLETE,exact.flowSnapshot().orElseThrow().state().status());assertEquals(next,exact.flowSnapshot().orElseThrow().memory().cells().field("cell"));
  var low=before(source,next,peak-1);var bank=low.flowSnapshot().orElseThrow().memory();low.step(limits(peak-1),true);var rejected=low.flowSnapshot().orElseThrow();
  assertEquals(FlowRuntime.Status.FAILED,rejected.state().status(),"Both target versions must fit at publication, even without an old READ snapshot");assertEquals(ComputationException.Code.MEMORY_LIMIT,rejected.state().diagnostic().orElseThrow().code());assertEquals(bank,rejected.memory());assertEquals(old,rejected.memory().cells().field("cell"));assertEquals(2,rejected.state().work());assertEquals("update",source.steps().get(rejected.state().cursor()).sourceId());
  System.out.println("REF_WRITE_PEAK "+type+" before="+retained+" temporary="+temporary+" required="+peak+" candidate_bank="+exact.flowSnapshot().orElseThrow().memory().storageUnits());
 }
}
