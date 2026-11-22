package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowTaskJoinTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
class FlowTaskJoinPersistenceTest {
 static DataValue.RecordValue patch(DataValue.RecordValue value,String key,DataValue replacement) { var fields=new TreeMap<>(value.fields()); fields.put(key,replacement); return new DataValue.RecordValue(fields); }
 @Test void launchAndJoinWithinSynchronousCallPreservePrivateFramesBooleanBranchAndWholeOutcomeAcrossEverySave() {
  var outer=new ModuleDefinition.Key("outer",1); var outcome=TaskOutcome.type(Map.of("answer",DataType.Scalar.INTEGER));
  var empty=new ComputationGraph(Map.of(),Map.of(),Map.of()); var extract=new ComputationGraph(Map.of("packet",outcome),Map.of("input",new ComputationGraph.Input("packet"),"ok",new ComputationGraph.Field("input","ok")),Map.of("value","ok"));
  var carry=new ComputationGraph(Map.of("packet",outcome),Map.of("input",new ComputationGraph.Input("packet")),Map.of("value","input"));
  var archive=new ProgramArchive(new ProgramDefinition(empty,Map.of("marker",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(0))),List.of(),List.of(),Map.of("ok",new ProgramDefinition.Entry(extract,List.of(),List.of()),"carry",new ProgramDefinition.Entry(carry,List.of(),List.of()))),Map.of());
  var p=new FlowProgram(TYPES,List.of(new FlowProgram.Invoke("outer_call",outer,new FlowCallBindings(Map.of("delay","delay"),Map.of("result","returned"))),
   new FlowProgram.If("if",new FlowInput.Expression("ok","value",Map.of("packet","returned"))),new FlowProgram.Shared("success","marker",new FlowInput.Literal(new DataValue.IntegerValue(7)),false),
   new FlowProgram.Otherwise("else"),new FlowProgram.Shared("failure","marker",new FlowInput.Literal(new DataValue.IntegerValue(9)),false),new FlowProgram.EndIf("end_if"),
   new FlowProgram.Evaluate("carry_result","carry",new FlowCallBindings(Map.of("packet","returned"),Map.of("value","stored"))),
   new FlowProgram.Procedure("outer",outer,TYPES,Map.of("result",outcome),Map.of("result","result")),
   new FlowProgram.TaskStart("launch",CHILD,Map.of("delay","delay"),"h"),new FlowProgram.TaskJoin("join",CHILD,"h","result"),new FlowProgram.EndProcedure("outer_return"),
   new FlowProgram.Procedure("child",CHILD,TYPES,Map.of("answer",DataType.Scalar.INTEGER),Map.of("answer","delay")),new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return")),archive).withAssembly();
  var owner=install(p,2); boolean nestedWait=false;
  for(int i=0;i<65;i++) {
   var saved=owner.flowSnapshot().orElseThrow(); if(saved.state().status()==FlowRuntime.Status.WAITING_TASK) {nestedWait=true; assertEquals(1,saved.state().assembly().orElseThrow().calls().orElseThrow().frames().size());}
   owner=FlowAssemblyTasksTest.restored(owner); owner.step(LIMITS,true);
  }
  var saved=owner.flowSnapshot().orElseThrow(); assertTrue(nestedWait); assertEquals(FlowRuntime.Status.COMPLETE,saved.state().status()); assertEquals(new DataValue.IntegerValue(7),saved.memory().cells().field("marker")); assertEquals(saved.state().locals().field("returned"),saved.state().locals().field("stored")); assertTrue(saved.tasks().contexts().isEmpty());
 }
 @Test void nativeNbtDeclaredOutputPreservesTagKindsAndRawBitsAcrossWaitClaimAndEveryOwnerBoundary() {
  var nativeValue=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("float",new DataValue.NbtValue(5,new DataValue.IntegerValue(0x7fc01234)),"array",new DataValue.NbtValue(11,new DataValue.ListValue(List.of(new DataValue.IntegerValue(7)))))));
  var types=new TreeMap<String,DataType>(TYPES); types.put("payload",DataType.Scalar.NBT); var p=new FlowProgram(types,List.of(
   new FlowProgram.TaskStart("launch",CHILD,Map.of("delay","delay","payload","payload"),"h"),new FlowProgram.TaskJoin("join",CHILD,"h","result"),
   new FlowProgram.Procedure("child",CHILD,types,Map.of("answer",DataType.Scalar.NBT),Map.of("answer","payload")),new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
  var initial=new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(2),"payload",nativeValue)); var owner=new ComputerBlockHost(()->{}); owner.installFlow(p,initial,LIMITS,true);
  for(int i=0;i<30;i++) {owner=FlowAssemblyTasksTest.restored(owner);owner.step(LIMITS,true);}
  var result=(DataValue.RecordValue)owner.flowSnapshot().orElseThrow().state().locals().field("result"); assertEquals(nativeValue,((DataValue.RecordValue)result.field("value")).field("answer")); assertTrue(owner.flowSnapshot().orElseThrow().tasks().contexts().isEmpty());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"owner_version","snapshot_version","wait_pc","wait_status","work","image","register"})
 void corruptOrDowngradedWaitRetainsOriginalSaveAndNeverExecutes(String mode) {
  var owner=install(program(),3); for(int i=0;i<10 && owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_TASK;i++) owner.step(LIMITS,true);
  var tag=(CompoundTag)owner.write(); var data=(DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("Data"),LIMITS.archive()); var flow=(DataValue.RecordValue)data.field("flow"); var state=(DataValue.RecordValue)flow.field("state"); var assembly=(DataValue.RecordValue)state.field("assembly");
  switch(mode) {
   case "owner_version" -> tag.putInt(FlowBlockNbtCodec.MARKER,30);
   case "snapshot_version" -> flow=patch(flow,"version",new DataValue.IntegerValue(31));
   case "wait_pc" -> assembly=patch(assembly,"pc",new DataValue.IntegerValue(((DataValue.IntegerValue)assembly.field("pc")).value()+1));
   case "wait_status" -> state=patch(state,"status",new DataValue.TextValue("READY"));
   case "work" -> state=patch(state,"work",new DataValue.IntegerValue(0));
   case "image" -> assembly=patch(assembly,"image",new DataValue.TextValue("stale"));
   case "register" -> assembly=patch(assembly,"registers",new DataValue.RecordValue(Map.of()));
  }
  state=patch(state,"assembly",assembly); flow=patch(flow,"state",state); data=patch(data,"flow",flow); tag.put("Data",ValueNbtCodec.encode(data,LIMITS.archive()));
  var restored=new ComputerBlockHost(()->{}); restored.read(tag); restored.tick(LIMITS,true); assertFalse(restored.loaded()); assertTrue(restored.diagnostic().isPresent()); assertEquals(tag,restored.write());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false","true,false","false,true","true,true"})
 void waitAndClaimRespectTheLastRepresentablePaidCycleAndNeverRemoveTheTargetOnExhaustion(boolean claim,boolean xbus) {
  var h=new TaskHandle(UUID.randomUUID(),0,1,0,UUID.randomUUID()).value();
  var steps=List.<FlowProgram.Step>of(new FlowProgram.Forever("loop"),new FlowProgram.TaskJoin("join",CHILD,"h","result"),new FlowProgram.EndForever("again"),
   new FlowProgram.Procedure("child",CHILD,Map.of(),Map.of(),Map.of()),xbus?new FlowProgram.Read("read","x0","v",new DataType.RecordType(Map.of("v",DataType.Scalar.INTEGER))):new FlowProgram.XBusStatus("status","x0","v"),new FlowProgram.EndProcedure("return"));
  var owner=new ComputerBlockHost(()->{}); owner.installFlow(new FlowProgram(Map.of("h",TaskHandle.TYPE),steps).withAssembly(),new DataValue.RecordValue(Map.of("h",h)),LIMITS,true); owner.step(LIMITS,true); if(claim)owner.step(LIMITS,true);
  var sibling=owner.startFlowTask(CHILD,new DataValue.RecordValue(Map.of()),LIMITS,true); long last=xbus?(Long.MAX_VALUE-steps.size()-1)/2:Long.MAX_VALUE;
  var tag=(CompoundTag)owner.write(); var data=(DataValue.RecordValue)ValueNbtCodec.decode(tag.getCompound("Data"),LIMITS.archive()); var flow=(DataValue.RecordValue)data.field("flow"); var state=(DataValue.RecordValue)flow.field("state");
  state=patch(patch(state,"work",new DataValue.IntegerValue(last-1)),"operation",new DataValue.IntegerValue(xbus?Math.addExact(Math.multiplyExact(last-1,2),steps.size()+1):last-1)); data=patch(data,"flow",patch(flow,"state",state)); tag.put("Data",ValueNbtCodec.encode(data,LIMITS.archive()));
  var restored=new ComputerBlockHost(()->{}); restored.read(tag); restored.tick(LIMITS,true); assertTrue(restored.loaded(),restored.diagnostic().toString()); var before=restored.flowSnapshot().orElseThrow(); restored.step(LIMITS,true); var failed=restored.flowSnapshot().orElseThrow();
  assertEquals(FlowRuntime.Status.FAILED,failed.state().status()); assertEquals(ComputationException.Code.ARITHMETIC_OVERFLOW,failed.state().diagnostic().orElseThrow().code()); assertEquals(last,failed.state().work()); assertEquals(before.state().assembly(),failed.state().assembly()); assertEquals(before.tasks().contexts(),failed.tasks().contexts()); assertTrue(restored.flowTask(sibling).isPresent()); FlowAssemblyTasksTest.restored(restored);
 }
 @Test void detachedJoinAndOwnerSnapshotsHaveIndependentCapabilityGatesAndLegacyInstallationRejects() {
  var owner=install(program(),0); var saved=owner.flowSnapshot().orElseThrow(); assertEquals(31,((CompoundTag)owner.write()).getInt(FlowBlockNbtCodec.MARKER));
  var snapshot=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(saved.program(),saved.state()),LIMITS.archive()); assertEquals(new DataValue.IntegerValue(32),((DataValue.RecordValue)ValueNbtCodec.decode(snapshot,LIMITS.archive())).field("version"));
  var workspace=new ProgramWorkspace(); var body=FlowDocumentBody.fromProgram(program(),FlowTaskStartTest.input(0)); var detached=new ArrayList<>(body.canvas().detached()); detached.add(new FlowBlockLayout.Stack(new ProgramDocument.Position(200,200),List.of(body.steps().get(1))));
  workspace.setFlow(Optional.of(new FlowDocumentBody(body.inputs(),List.of(body.steps().getFirst()),body.initial(),new FlowBlockLayout(body.canvas().main(),detached),body.inline(),body.execution())));
  var encoded=ProgramDocumentCodec.encode(workspace.snapshot()); assertEquals(new DataValue.IntegerValue(26),encoded.field("version")); assertThrows(ArchiveException.class,()->ProgramDocumentCodec.decode(patch(encoded,"version",new DataValue.IntegerValue(25))));
  assertThrows(ComputationException.class,()->new FlowProgram(saved.program().inputs(),saved.program().steps(),saved.program().expressions(),FlowProgram.Execution.LEGACY_GRAPH).validate(LIMITS.hardware()));
 }
}
