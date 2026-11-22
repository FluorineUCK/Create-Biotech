package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.terminal.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LocalReferenceContractTest {
 static DataValue.RecordValue initial(){return new DataValue.RecordValue(Map.of("next",new DataValue.IntegerValue(42),"ticks",new DataValue.IntegerValue(2)));}
 static ComputerBlockHost run(ComputerHostLimits limits){
  var host=new ComputerBlockHost(()->{});host.installFlow(LocalCellReferenceTest.source(),initial(),limits,true);host.setPaused(false,limits,true);
  for(int i=0;i<200;i++){host.tick(limits,true);var state=host.flowSnapshot().orElseThrow().state();if(state.status()==FlowRuntime.Status.COMPLETE||state.status()==FlowRuntime.Status.FAILED)return host;}
  throw new AssertionError("not completed");
 }
 @Test void referenceDocumentsAndOwnerSnapshotsHaveExplicitNewVersionsAndPortableObservations(){
  var p=LocalCellReferenceTest.source();var document=new ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(p,initial())));
  assertEquals(new DataValue.IntegerValue(34),((DataValue.RecordValue)ProgramDocumentCodec.encode(document)).field("version"));
  var host=run(LocalCellReferenceTest.LIMITS);var saved=host.flowSnapshot().orElseThrow();var owner=FlowBlockNbtCodec.encode(saved,SoftwareAnyAbiTest.ARCHIVE);assertEquals(38,owner.getInt(FlowBlockNbtCodec.MARKER));
  assertThrows(ArchiveException.class,()->ValueNbtCodec.decode(owner.getCompound("Data"),SoftwareAnyAbiTest.ARCHIVE));
  assertEquals(0,ComputerStatusView.flow(Optional.of(saved),true,Optional.empty()).localReferences());
  var terminal=TerminalStatusView.ofFlowOwner(Optional.empty(),Optional.of(saved));assertEquals(0,terminal.localReferences());ValueNbtCodec.encode(terminal,SoftwareAnyAbiTest.ARCHIVE);
 }
 @Test void realFarmerFourNeedsNeitherParserNorMasterAndExactWholeOwnerBoundary(){
  var limits=NativeLibraryOwnersTest.limits("farmer",4);assertFalse(limits.isa1a());assertFalse(limits.isa1b());
  var p=LocalCellReferenceTest.source();var image=p.compile(limits.hardware()).assembly().orElseThrow();assertEquals(Set.of(InstructionFamily.ISA_1),image.instructionFamilies());
  var complete=run(limits).flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,complete.state().status());
  long lo=0,hi=limits.hardware().memoryUnits();while(lo+1<hi){long mid=(lo+hi)/2;if(fits(limits,mid))hi=mid;else lo=mid;}
  assertTrue(fits(limits,hi));assertFalse(fits(limits,hi-1));assertEquals(47,image.codeWords());assertEquals(18,complete.state().work());assertEquals(2438,hi);assertEquals(2015,complete.state().controlUnits());
  var tooSmall=new ComputerHostLimits(new ResourceLimits(46,limits.hardware().memoryUnits(),limits.hardware().workPerSlice()),limits.archive(),limits.mailbox(),limits.pins(),limits.serial(),limits.taskSlots(),false,false);assertThrows(RuntimeException.class,()->run(tooSmall));
  System.out.println("REF_FIXTURE code="+image.codeWords()+" registers="+image.registerTypes().size()+" work="+complete.state().work()+" exact_owner_ram="+hi+" checkpoint="+complete.state().controlUnits()+" hardware="+limits.hardware());
 }
 static boolean fits(ComputerHostLimits base,long ram){try{var limits=new ComputerHostLimits(new ResourceLimits(base.hardware().programUnits(),ram,base.hardware().workPerSlice()),base.archive(),base.mailbox(),base.pins(),base.serial(),base.taskSlots(),base.isa1a(),base.isa1b());return run(limits).flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE;}catch(ComputationException e){assertEquals(ComputationException.Code.MEMORY_LIMIT,e.code());return false;}catch(ComputerBlockHost.HostException e){assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT,e.code());return false;}}
 @Test void explicitTaskEntryCannotExportAReferenceResult(){
  var key=new ModuleDefinition.Key("bad_task",1);var ref=new DataType.RefType(DataType.Scalar.INTEGER,true);
  var p=new FlowProgram(Map.of("ticks",DataType.Scalar.INTEGER),List.of(new FlowProgram.Sleep("sleep","ticks"),new FlowProgram.Procedure("definition",key,Map.of(),Map.of("result",ref),Map.of("result","a")),new FlowProgram.Reference("address",FlowProgram.ReferenceKind.ADDRESS,"counter","a",Optional.empty()),new FlowProgram.EndProcedure("end")),LocalCellReferenceTest.source().expressions(),FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
  assertThrows(ComputationException.class,()->FlowRuntime.startProcedure(UUID.randomUUID(),UUID.randomUUID(),p,key,new DataValue.RecordValue(Map.of()),LocalCellReferenceTest.LIMITS.hardware()));
 }
}
