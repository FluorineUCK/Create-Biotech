package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AppendReservationRegressionTest {
 static final ComputerHostLimits LIMITS=ComputerListAppendTest.LIMITS;
 static ComputerHostLimits limits(long ram){return new ComputerHostLimits(new ResourceLimits(256,ram,1),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),2,true,true);}
 static ComputerBlockHost midway(int size,int cursor){
  var p=ComputerListAppendTest.program(DataType.Scalar.TEXT,false);var h=new ComputerBlockHost(()->{});h.installFlow(p,ComputerListAppendTest.input(size,new DataValue.TextValue("x".repeat(64))),LIMITS,true);
  for(int i=0;;i++){assertTrue(i<100);if(h.flowSnapshot().orElseThrow().state().assembly().orElseThrow().build().filter(b->b.cursor()==cursor).isPresent())return h;h.step(LIMITS,true);}
 }
 @Test void liveAppendAndPublicationReserveCandidateInAdditionToStillOwnedPrefix(){
  for(int cursor:List.of(2,5)){
   var host=midway(4,cursor);var saved=host.flowSnapshot().orElseThrow();var state=saved.state();var prefix=state.assembly().orElseThrow().build().orElseThrow();
   long candidate=prefix.ownedUnits()+(cursor<=4?65:0);long retained=state.controlUnits()+saved.program().usage(LIMITS.hardware()).constantUnits();
   var ample=FlowRuntime.restore(saved.program(),state,LIMITS.hardware());ample.load();ample.setPaused(false);ample.advance(1);assertEquals(FlowRuntime.Status.READY,ample.checkpoint().status());
   long nextOwned=ample.checkpoint().controlUnits()+saved.program().usage(LIMITS.hardware()).constantUnits();long ceiling=retained+candidate-1;
   assertTrue(nextOwned<=ceiling,"Committed candidate must fit: this targets transient ownership only");
   var runtime=FlowRuntime.restore(saved.program(),state,new ResourceLimits(256,ceiling,1));runtime.load();runtime.setPaused(false);runtime.advance(1);
   assertEquals(FlowRuntime.Status.FAILED,runtime.checkpoint().status());assertEquals(ComputationException.Code.MEMORY_LIMIT,runtime.checkpoint().diagnostic().orElseThrow().code());assertEquals(state.assembly(),runtime.checkpoint().assembly());assertEquals(state.locals(),runtime.checkpoint().locals());
  }
 }
 @Test void appendOnlyColdPrefixFitsCommittedButNotReconstructionTransientPair(){
  var host=midway(5,4);var warm=host.flowSnapshot().orElseThrow();var raw=host.write();var cold=FlowBlockNbtCodec.decode((net.minecraft.nbt.CompoundTag)raw,LIMITS.archive());var state=cold.state();var build=state.assembly().orElseThrow().build().orElseThrow();assertTrue(build.materializedUnits().isEmpty());
  long committed=warm.state().controlUnits()+warm.program().usage(LIMITS.hardware()).constantUnits();
  assertEquals(ComputationException.Code.MEMORY_LIMIT,assertThrows(ComputationException.class,()->FlowRuntime.restore(cold.program(),state,new ResourceLimits(256,committed,1))).code());
  assertTrue(build.materializedUnits().isEmpty(),"Reject before publishing a reconstructed witness");assertEquals(raw,host.write());
  assertEquals(warm.state(),FlowRuntime.restore(cold.program(),state,LIMITS.hardware()).checkpoint());
 }
 static long total(FlowBlockHost.Saved s){long n=s.initialInputs().storageUnits()+s.control().storageUnits()+s.memory().storageUnits()+s.pinUnits()+s.tasks().metadataUnits()+s.program().usage(LIMITS.hardware()).constantUnits()+s.state().controlUnits();for(var c:s.tasks().contexts())n+=c.controlUnits();return n;}
 @Test void appendOnlyColdMultiTaskReconstructionCannotBorrowRetainedSiblingRam(){
  var p=ComputerListAppendTest.program(DataType.Scalar.TEXT,false);var input=ComputerListAppendTest.input(10,new DataValue.TextValue("sibling".repeat(40)));var host=new ComputerBlockHost(()->{});host.installFlow(p,input,LIMITS,true);host.startFlowTask(new ModuleDefinition.Key("append",1),input,LIMITS,true);
  for(int i=0;;i++){
   assertTrue(i<100);var saved=host.flowSnapshot().orElseThrow();boolean root=saved.state().assembly().orElseThrow().build().filter(b->b.cursor()>=4).isPresent();boolean child=saved.tasks().contexts().getFirst().assembly().orElseThrow().build().filter(b->b.cursor()>=4).isPresent();if(root&&child)break;host.step(LIMITS,true);
  }
  var before=host.flowSnapshot().orElseThrow();var raw=host.write();long committed=total(before);assertTrue(committed<LIMITS.hardware().memoryUnits());
  var cold=new ComputerBlockHost(()->{});cold.read(raw);cold.tick(limits(committed),true);assertFalse(cold.loaded(),"Shared retained contexts fit, but rebuilding the second prefix needs additional transient space");assertEquals(raw,cold.write());assertEquals(before,host.flowSnapshot().orElseThrow());
  cold.tick(LIMITS,true);assertTrue(cold.loaded(),cold.diagnostic().toString());assertEquals(before,cold.flowSnapshot().orElseThrow());assertEquals(raw,cold.write());
 }
}
