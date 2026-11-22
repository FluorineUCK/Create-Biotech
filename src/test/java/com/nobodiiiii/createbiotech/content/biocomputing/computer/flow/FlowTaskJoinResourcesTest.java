package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowTaskJoinTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
class FlowTaskJoinResourcesTest {
 static ComputerBlockHost readyClaim(String output,int payloadSize) {
  var types=Map.<String,DataType>of("delay",DataType.Scalar.INTEGER,"payload",DataType.Scalar.TEXT);
  var p=new FlowProgram(types,List.of(new FlowProgram.TaskStart("launch",CHILD,Map.of("delay","delay","payload","payload"),"h"),
   new FlowProgram.TaskJoin("join",CHILD,"h","result"),new FlowProgram.Procedure("child",CHILD,types,Map.of(output,DataType.Scalar.TEXT),Map.of(output,"payload")),
   new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
  var owner=new ComputerBlockHost(()->{}); owner.installFlow(p,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(0),"payload",new DataValue.TextValue("x".repeat(payloadSize)))),LIMITS,true);
  for(int i=0;i<25;i++) {
   var tasks=owner.flowSnapshot().orElseThrow().tasks().contexts();
   if(!tasks.isEmpty() && tasks.getFirst().status()==FlowRuntime.Status.COMPLETE) break;
   owner.step(LIMITS,true);
  }
  owner.setPaused(false,LIMITS,true); owner.setPaused(true,LIMITS,true);
  for(int i=0;i<10;i++) { var saved=owner.flowSnapshot().orElseThrow(); var main=FlowRuntime.restore(saved.program(),saved.state(),LIMITS.hardware()); if(main.taskJoinReady() && !main.atTaskWait()) break; owner.step(LIMITS,true); }
  assertEquals(FlowRuntime.Status.READY,owner.flowSnapshot().orElseThrow().state().status()); assertEquals(1,owner.flowSnapshot().orElseThrow().tasks().contexts().size()); return owner;
 }
 static ComputerBlockHost restore(ComputerBlockHost owner,ComputerHostLimits limits) { var result=new ComputerBlockHost(()->{}); result.read(owner.write()); result.tick(limits,true); assertTrue(result.loaded(),result.diagnostic().toString()); return result; }
 static long ram(ComputerBlockHost owner) { return FlowTaskStartBoundaryTest.units(owner.flowSnapshot().orElseThrow()); }
 static long disk(ComputerBlockHost owner) { return FlowTaskStartBoundaryTest.archive(owner); }
 static ComputerHostLimits limits(long memory,long archive) {
  var base=FlowTaskStartBoundaryTest.limits(memory,new ArchiveLimits(100000,1000000,archive));
  return new ComputerHostLimits(base.hardware(),base.archive(),base.mailbox(),base.pins(),base.serial(),base.taskSlots(),LIMITS.isa1a());
 }
 @Test void postPublicationDirtyFailureCannotEraseAnAlreadyClaimedResult() {
  var staged=readyClaim("answer",0); var fail=new java.util.concurrent.atomic.AtomicBoolean();
  var owner=new ComputerBlockHost(()->{if(fail.get())throw new ComputationException(ComputationException.Code.MEMORY_LIMIT,"dirty","Notification failed after publication");});
  owner.read(staged.write()); owner.tick(LIMITS,true);
  var before=owner.flowSnapshot().orElseThrow(); var main=FlowRuntime.restore(before.program(),before.state(),LIMITS.hardware());
  assertTrue(main.taskJoinReady()); assertFalse(main.atTaskWait()); assertEquals(FlowRuntime.Status.COMPLETE,before.tasks().contexts().getFirst().status());
  fail.set(true);
  try {owner.step(LIMITS,true);} catch(RuntimeException notification) { /* Observe the published owner, not notification success. */ }
  var saved=owner.flowSnapshot().orElseThrow(); assertTrue(saved.tasks().contexts().isEmpty()); assertTrue(saved.tasks().slots().entries().getFirst().retired());
  assertTrue(saved.state().locals().fields().containsKey("result"),"Published caller outcome must survive a dirty callback failure after target retirement");
  assertEquals(new DataValue.BooleanValue(true),((DataValue.RecordValue)saved.state().locals().field("result")).field("ok"));
  var restored=new ComputerBlockHost(()->{}); restored.read(owner.write()); restored.tick(LIMITS,true); assertEquals(saved,restored.flowSnapshot().orElseThrow());
 }
 @Test void finalAggregateAdmissionDoesNotTemporarilyKeepChildAndNewCallerEnvelope() {
  var owner=readyClaim("answer",4000); long beforeRam=ram(owner),beforeDisk=disk(owner); var tight=limits(beforeRam,beforeDisk);
  var constrained=restore(owner,tight); constrained.step(tight,true); assertTrue(constrained.loaded());
  assertTrue(constrained.flowSnapshot().orElseThrow().tasks().contexts().isEmpty()); assertTrue(ram(constrained)<=beforeRam); assertTrue(disk(constrained)<=beforeDisk);
  assertEquals(new DataValue.BooleanValue(true),((DataValue.RecordValue)constrained.flowSnapshot().orElseThrow().state().locals().field("result")).field("ok"));
 }
 @Test void ramRejectionKeepsTheTargetAndClaimantsPreviousPcAndRegisters() {
  var owner=readyClaim("output_"+"a".repeat(1200),0); long before=ram(owner); var unrestricted=restore(owner,LIMITS); unrestricted.step(LIMITS,true); assertTrue(ram(unrestricted)>before,"Wide output names must force envelope growth");
  var tight=limits(before,LIMITS.archive().maxValueUnits()); var constrained=restore(owner,tight); var saved=constrained.flowSnapshot().orElseThrow(); constrained.step(tight,true); var rejected=constrained.flowSnapshot().orElseThrow();
  assertTrue(constrained.loaded()); assertEquals(FlowRuntime.Status.FAILED,rejected.state().status()); assertEquals(ComputationException.Code.MEMORY_LIMIT,rejected.state().diagnostic().orElseThrow().code());
  assertEquals(saved.state().assembly(),rejected.state().assembly()); assertEquals(saved.state().locals(),rejected.state().locals()); assertEquals(saved.tasks().contexts(),rejected.tasks().contexts()); assertEquals(saved.tasks().slots(),rejected.tasks().slots());
 }
 @Test void archiveRejectionKeepsTargetAndPublishesOnlyClaimantFailureOrRetainsOriginalIfFailureCannotFit() {
  var owner=readyClaim("output_"+"a".repeat(1200),0); long before=disk(owner); var unrestricted=restore(owner,LIMITS); unrestricted.step(LIMITS,true); assertTrue(disk(unrestricted)>before+200);
  var tight=limits(LIMITS.hardware().memoryUnits(),before+200); var constrained=restore(owner,tight); var saved=constrained.flowSnapshot().orElseThrow(); constrained.step(tight,true);
  assertTrue(constrained.loaded()); var rejected=constrained.flowSnapshot().orElseThrow(); assertEquals(FlowRuntime.Status.FAILED,rejected.state().status()); assertEquals(saved.state().assembly(),rejected.state().assembly()); assertEquals(saved.tasks().contexts(),rejected.tasks().contexts()); assertEquals(saved.tasks().slots(),rejected.tasks().slots());
  var noRoom=limits(LIMITS.hardware().memoryUnits(),before); var suspended=restore(owner,noRoom); var raw=suspended.write(); assertThrows(ComputerBlockHost.HostException.class,()->suspended.step(noRoom,true)); assertFalse(suspended.loaded()); assertEquals(raw,suspended.write());
 }
}
