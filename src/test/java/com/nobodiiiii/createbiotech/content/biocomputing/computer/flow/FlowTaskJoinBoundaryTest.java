package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowTaskJoinTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class FlowTaskJoinBoundaryTest {
 static final ModuleDefinition.Key WAITER=new ModuleDefinition.Key("waiter",1),OTHER=new ModuleDefinition.Key("other",1);
 static final Map<String,DataType> OUTPUTS=Map.of("answer",DataType.Scalar.INTEGER);
 static final ComputerHostLimits MANY=new ComputerHostLimits(LIMITS.hardware(),LIMITS.archive(),LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),5,LIMITS.isa1a(),LIMITS.isa1b());
 static ComputerBlockHost taskOwner() {
  var p=new FlowProgram(TYPES,List.of(new FlowProgram.Sleep("main","delay"),
   new FlowProgram.Procedure("child",CHILD,TYPES,OUTPUTS,Map.of("answer","delay")),new FlowProgram.Sleep("child_sleep","delay"),new FlowProgram.EndProcedure("child_return"),
   new FlowProgram.Procedure("other",OTHER,TYPES,OUTPUTS,Map.of("answer","delay")),new FlowProgram.EndProcedure("other_return"),
   new FlowProgram.Procedure("waiter",WAITER,Map.of("h",TaskHandle.TYPE),Map.of("result",TaskOutcome.type(OUTPUTS)),Map.of("result","result")),
   new FlowProgram.TaskJoin("join",CHILD,"h","result"),new FlowProgram.EndProcedure("waiter_return"))).withAssembly();
  var owner=new ComputerBlockHost(()->{}); owner.installFlow(p,FlowTaskStartTest.input(100),MANY,true); owner.step(MANY,true); return owner;
 }
 static TaskHandle handle(ComputerBlockHost owner,UUID id) {
  var saved=owner.flowSnapshot().orElseThrow(); var entries=saved.tasks().slots().entries();
  for(int i=0;i<entries.size();i++) if(entries.get(i).flow().equals(id)) return new TaskHandle(saved.state().computer(),saved.control().generation(),i+1,entries.get(i).incarnation(),id);
  throw new AssertionError("Missing task slot");
 }
 static UUID waiter(ComputerBlockHost owner,DataValue h) { return owner.startFlowTask(WAITER,new DataValue.RecordValue(Map.of("h",h)),MANY,true); }
 static void finish(ComputerBlockHost owner) { owner.setPaused(false,MANY,true); for(int i=1;i<=35;i++) owner.tick(i,MANY,true,Map.of()); }
 static DataValue.RecordValue result(ComputerBlockHost owner,UUID id) { return (DataValue.RecordValue)owner.flowTask(id).orElseThrow().locals().field("result"); }
 static String error(DataValue.RecordValue result) { assertEquals(new DataValue.BooleanValue(false),result.field("ok")); assertEquals(DataValue.NullValue.INSTANCE,result.field("value")); return ((DataValue.ErrorValue)result.field("error")).code(); }
 @Test void legacyGraphCannotRestoreTheNewTaskWaitStatus() {
  var p=new FlowProgram(TYPES,List.of(new FlowProgram.Sleep("sleep","delay"))); var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,FlowTaskStartTest.input(2),LIMITS.hardware()); var s=r.checkpoint();
  var invalid=new FlowRuntime.Checkpoint(s.computer(),s.flow(),0,FlowRuntime.Status.WAITING_TASK,s.locals(),1,s.paused(),s.expression(),s.diagnostic(),0,s.signals(),1,s.repeats(),s.inputInvocation(),s.calls(),s.entry(),s.assembly());
  assertThrows(ComputationException.class,()->FlowRuntime.restore(p,invalid,LIMITS.hardware()));
 }
 @Test void pausedDebuggerStepsWakeAndClaimWithoutResumingTheComputer() {
  var owner=install(program(),2);
  for(int i=0;i<24;i++) owner.step(LIMITS,true);
  var saved=owner.flowSnapshot().orElseThrow(); assertTrue(saved.state().paused()); assertEquals(FlowRuntime.Status.COMPLETE,saved.state().status());
  assertEquals(new DataValue.BooleanValue(true),((DataValue.RecordValue)saved.state().locals().field("result")).field("ok")); assertTrue(saved.tasks().contexts().isEmpty());
 }
 @Test void twoConsumersGetExactlyOneDeclaredResultAndOneAlreadyClaimedOutcome() {
  var owner=taskOwner(); var child=owner.startFlowTask(CHILD,FlowTaskStartTest.input(3),MANY,true); var h=handle(owner,child); var a=waiter(owner,h.value()); var b=waiter(owner,h.value()); finish(owner);
  var outcomes=List.of(result(owner,a),result(owner,b)); assertEquals(1,outcomes.stream().filter(o->o.field("ok").equals(new DataValue.BooleanValue(true))).count());
  assertEquals("task_result_claimed",error(outcomes.stream().filter(o->o.field("ok").equals(new DataValue.BooleanValue(false))).findFirst().orElseThrow()));
  assertTrue(owner.flowTask(child).isEmpty()); assertTrue(owner.flowSnapshot().orElseThrow().tasks().slots().entries().getFirst().retired());
 }
 @Test void failedChildExportsItsDiagnosticButNeverPrivateLocalsOrRegisters() {
  var owner=taskOwner(); var child=owner.startFlowTask(CHILD,FlowTaskStartTest.input(-1),MANY,true); var a=waiter(owner,handle(owner,child).value()); finish(owner);
  var result=result(owner,a); assertEquals("task_failed_invalid_argument",error(result)); assertEquals(Set.of("ok","error","value"),result.fields().keySet()); assertTrue(owner.flowTask(child).isEmpty());
 }
 @Test void allMalformedAndForeignOrStaleHandlesReturnBranchableStableErrorsWithoutOwnerSuspension() {
  for(String mode:List.of("foreign","generation","slot","incarnation","flow","uuid","negative","overflow","procedure")) {
   var owner=taskOwner(); var child=owner.startFlowTask(mode.equals("procedure")?OTHER:CHILD,FlowTaskStartTest.input(0),MANY,true); var h=handle(owner,child); var fields=new TreeMap<>(h.value().fields()); String expected;
   switch(mode) {
    case "foreign" -> {fields.put("computer",new DataValue.TextValue(UUID.randomUUID().toString()));expected="task_foreign_computer";}
    case "generation" -> {fields.put("generation",new DataValue.IntegerValue(h.generation()+1));expected="task_generation_stale";}
    case "slot" -> {fields.put("slot",new DataValue.IntegerValue(99));expected="task_unknown";}
    case "incarnation" -> {fields.put("incarnation",new DataValue.IntegerValue(1));expected="task_incarnation_stale";}
    case "flow" -> {fields.put("flow",new DataValue.TextValue(UUID.randomUUID().toString()));expected="task_identity_stale";}
    case "uuid" -> {fields.put("flow",new DataValue.TextValue("not-uuid"));expected="task_handle_invalid";}
    case "negative" -> {fields.put("generation",new DataValue.IntegerValue(-1));expected="task_handle_invalid";}
    case "overflow" -> {fields.put("slot",new DataValue.IntegerValue(Long.MAX_VALUE));expected="task_handle_invalid";}
    default -> expected="task_procedure_mismatch";
   }
   var a=waiter(owner,new DataValue.RecordValue(fields)); finish(owner); assertEquals(expected,error(result(owner,a)),mode); assertTrue(owner.loaded()); assertTrue(owner.flowTask(child).isPresent());
  }
 }
 @Test void atomicProgramClaimRetiresDeviceReceiptsAndFrontiersWithoutChangingTheOriginalTransferredPayload() {
  var packet=new DataType.RecordType(Map.of("number",DataType.Scalar.INTEGER)); var payload=new DataValue.RecordValue(Map.of("number",new DataValue.IntegerValue(42)));
  var p=new FlowProgram(Map.of("packet",packet),List.of(new FlowProgram.TaskStart("start",CHILD,Map.of("packet","packet"),"h"),new FlowProgram.TaskJoin("join",CHILD,"h","result"),
   new FlowProgram.Procedure("child",CHILD,Map.of("packet",packet),Map.of("answer",packet),Map.of("answer","packet")),new FlowProgram.Write("write","x0","packet"),new FlowProgram.EndProcedure("return"))).withAssembly();
  var owner=new ComputerBlockHost(()->{}); owner.installFlow(p,new DataValue.RecordValue(Map.of("packet",payload)),LIMITS,true); owner.setPaused(false,LIMITS,true);
  for(int i=0;i<8 && owner.flowSnapshot().orElseThrow().tasks().contexts().stream().noneMatch(c->c.status()==FlowRuntime.Status.WAITING_WRITE);i++)owner.step(LIMITS,true);
  var reader=new ComputerBlockHost(()->{}); reader.installFlow(new FlowProgram(Map.of(),List.of(new FlowProgram.Read("read","x1","packet",packet))).withAssembly(),new DataValue.RecordValue(Map.of()),LIMITS,true); reader.setPaused(false,LIMITS,true); reader.step(LIMITS,true);
  assertEquals(XBusExchange.Status.EXCHANGED,owner.exchange("x0",LIMITS,true,reader,"x1",LIMITS,true)); var receipt=owner.flowSnapshot().orElseThrow().receipts().getFirst(); assertEquals(payload,receipt.payload());
  for(int i=0;i<12;i++)owner.step(LIMITS,true); var saved=owner.flowSnapshot().orElseThrow(); assertTrue(saved.tasks().contexts().isEmpty()); assertTrue(saved.receipts().isEmpty()); assertTrue(FlowControl.Peer.of(saved).supersedes(receipt.writer()));
  assertEquals(payload,((DataValue.RecordValue)((DataValue.RecordValue)saved.state().locals().field("result")).field("value")).field("answer")); FlowAssemblyTasksTest.restored(owner);
 }
 @Test void typeInvalidHandleLocalsFailInstallationInsteadOfBecomingNumericalFallbackOutcomes() {
  var p=new FlowProgram(Map.of("h",DataType.Scalar.INTEGER),List.of(new FlowProgram.TaskJoin("join",CHILD,"h","result"),new FlowProgram.Procedure("child",CHILD,Map.of(),Map.of(),Map.of()),new FlowProgram.EndProcedure("return"))).withAssembly();
  assertEquals(ComputationException.Code.TYPE_MISMATCH,assertThrows(ComputationException.class,()->p.validate(LIMITS.hardware())).code());
 }
 @Test void reusedSlotsRejectTheOldIncarnationInsteadOfMistakingItForAlreadyClaimedOrTheNewResult() {
  var owner=taskOwner(); var first=owner.startFlowTask(CHILD,FlowTaskStartTest.input(0),MANY,true); var old=handle(owner,first);
  while(owner.flowTask(first).orElseThrow().status()!=FlowRuntime.Status.COMPLETE) owner.step(MANY,true);
  owner.claimFlowTask(first,MANY,true); var next=owner.startFlowTask(CHILD,FlowTaskStartTest.input(0),MANY,true); var fresh=handle(owner,next);
  assertEquals(old.slot(),fresh.slot()); assertEquals(old.incarnation()+1,fresh.incarnation()); var bad=waiter(owner,old.value()); var good=waiter(owner,fresh.value()); finish(owner);
  assertEquals("task_incarnation_stale",error(result(owner,bad))); assertEquals(new DataValue.BooleanValue(true),result(owner,good).field("ok"));
 }
 @Test void pausedHumanClaimThenDebuggerStepReturnsAlreadyClaimedWithoutUnpausing() {
  var owner=taskOwner(); var child=owner.startFlowTask(CHILD,FlowTaskStartTest.input(2),MANY,true); var a=waiter(owner,handle(owner,child).value());
  while(owner.flowTask(child).orElseThrow().status()!=FlowRuntime.Status.COMPLETE) owner.step(MANY,true);
  assertEquals(FlowRuntime.Status.WAITING_TASK,owner.flowTask(a).orElseThrow().status()); owner.claimFlowTask(child,MANY,true);
  for(int i=0;i<5;i++) owner.step(MANY,true); assertTrue(owner.flowSnapshot().orElseThrow().state().paused()); assertEquals("task_result_claimed",error(result(owner,a)));
 }
 @Test void selfWaitIsAStableOutcomeEvenWhenTheInputHandleIsExchangedAsOrdinaryLocalData() {
  var owner=taskOwner(); var child=owner.startFlowTask(CHILD,FlowTaskStartTest.input(0),MANY,true); var a=waiter(owner,handle(owner,child).value()); var self=handle(owner,a).value();
  var tag=(net.minecraft.nbt.CompoundTag)owner.write(); var data=(DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("Data"),MANY.archive()); var tasks=(DataValue.RecordValue)data.field("tasks"); var contexts=new ArrayList<>(((DataValue.ListValue)tasks.field("contexts")).values());
  for(int i=0;i<contexts.size();i++) { var c=(DataValue.RecordValue)contexts.get(i); if(!c.field("flow").equals(new DataValue.TextValue(a.toString())))continue;
   var registers=(DataValue.RecordValue)c.field("assembly"); var values=(DataValue.RecordValue)registers.field("registers"); var copy=new TreeMap<>(values.fields()); copy.replaceAll((k,v)->TaskHandle.TYPE.accepts(v)?self:v);
   registers=FlowTaskJoinPersistenceTest.patch(registers,"registers",new DataValue.RecordValue(copy)); c=FlowTaskJoinPersistenceTest.patch(c,"assembly",registers); c=FlowTaskJoinPersistenceTest.patch(c,"locals",new DataValue.RecordValue(Map.of("h",self))); contexts.set(i,c);
  }
  tasks=FlowTaskJoinPersistenceTest.patch(tasks,"contexts",new DataValue.ListValue(contexts)); data=FlowTaskJoinPersistenceTest.patch(data,"tasks",tasks); tag.put("Data",ValueNbtCodec.encode(data,MANY.archive()));
  var restored=new ComputerBlockHost(()->{}); restored.read(tag); restored.tick(MANY,true); assertTrue(restored.loaded()); finish(restored); assertEquals("task_self_wait",error(result(restored,a))); assertTrue(restored.flowTask(child).isPresent());
 }
 @Test void atomicDeclarationBansJoinDirectlyOrThroughSynchronousWrapperAndExplicitTransactionFaultsOnlyCaller() {
  var joinProgram=program(); var steps=new ArrayList<FlowProgram.Step>(joinProgram.steps()); steps.add(1,new FlowProgram.Atomic("atomic","commit")); steps.add(3,new FlowProgram.EndAtomic("end_atomic"));
  assertThrows(ComputationException.class,()->new FlowProgram(TYPES,steps).withAssembly().validate(LIMITS.hardware()));
  var outer=new ModuleDefinition.Key("outer",1); var indirect=List.<FlowProgram.Step>of(new FlowProgram.Atomic("atomic","commit"),new FlowProgram.Invoke("call",outer,new FlowCallBindings(Map.of("h","h"),Map.of())),new FlowProgram.EndAtomic("end_atomic"),
   new FlowProgram.Procedure("outer",outer,Map.of("h",TaskHandle.TYPE),Map.of(),Map.of()),new FlowProgram.TaskJoin("join",CHILD,"h","result"),new FlowProgram.EndProcedure("outer_return"),
   new FlowProgram.Procedure("child",CHILD,TYPES,OUTPUTS,Map.of("answer","delay")),new FlowProgram.EndProcedure("child_return"));
  assertThrows(ComputationException.class,()->new FlowProgram(Map.of("h",TaskHandle.TYPE),indirect).withAssembly().validate(LIMITS.hardware()));
  var transaction=new ArrayList<FlowProgram.Step>(joinProgram.steps()); transaction.add(1,new FlowProgram.Memory("begin",FlowProgram.MemoryKind.BEGIN,"","")); var owner=install(new FlowProgram(TYPES,transaction).withAssembly(),0);
  for(int i=0;i<15;i++) owner.step(LIMITS,true); var saved=owner.flowSnapshot().orElseThrow(); assertEquals(FlowRuntime.Status.FAILED,saved.state().status()); assertEquals(ComputationException.Code.INVALID_ARGUMENT,saved.state().diagnostic().orElseThrow().code()); assertFalse(saved.memory().active(saved.state().flow())); assertEquals(FlowRuntime.Status.COMPLETE,saved.tasks().contexts().getFirst().status()); assertTrue(owner.loaded());
 }
 @Test void aHumanClaimWhilePausedDoesNotAdvanceTheWaiterUntilResumeAndUnloadNeverCatchesUp() {
  var owner=taskOwner(); var child=owner.startFlowTask(CHILD,FlowTaskStartTest.input(2),MANY,true); var a=waiter(owner,handle(owner,child).value());
  owner.setPaused(false,MANY,true); owner.tick(1,MANY,true,Map.of()); owner.tick(2,MANY,true,Map.of()); owner.tick(3,MANY,true,Map.of()); owner.setPaused(true,MANY,true);
  while(owner.flowTask(child).orElseThrow().status()!=FlowRuntime.Status.COMPLETE) owner.step(MANY,true);
  assertEquals(FlowRuntime.Status.WAITING_TASK,owner.flowTask(a).orElseThrow().status()); var waiting=owner.flowTask(a).orElseThrow(); owner.claimFlowTask(child,MANY,true);
  assertEquals(waiting,owner.flowTask(a).orElseThrow()); var raw=owner.write(); owner.tick(100,MANY,true,Map.of()); assertEquals(raw,owner.write());
  var restored=new ComputerBlockHost(()->{}); restored.read(raw); restored.tick(MANY,true); assertEquals(raw,restored.write()); restored.setPaused(false,MANY,true);
  assertEquals(FlowRuntime.Status.READY,restored.flowTask(a).orElseThrow().status()); assertEquals(waiting.work(),restored.flowTask(a).orElseThrow().work());
  for(int i=101;i<=110;i++) restored.tick(i,MANY,true,Map.of()); assertEquals("task_result_claimed",error(result(restored,a)));
 }
}
