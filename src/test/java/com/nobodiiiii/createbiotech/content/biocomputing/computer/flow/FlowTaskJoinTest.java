package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class FlowTaskJoinTest {
 static final ModuleDefinition.Key CHILD=new ModuleDefinition.Key("child",1);
 static final Map<String,DataType> TYPES=Map.of("delay",DataType.Scalar.INTEGER);
 static FlowProgram program() {
  var p=new FlowProgram(TYPES,List.of(new FlowProgram.TaskStart("launch",CHILD,Map.of("delay","delay"),"h"),
   new FlowProgram.Sleep("join","delay"),new FlowProgram.Procedure("child",CHILD,TYPES,Map.of("answer",DataType.Scalar.INTEGER),Map.of("answer","delay")),
   new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
  var encoded=(DataValue.RecordValue)FlowProgramCodec.encode(p);
  var steps=new ArrayList<>(((DataValue.ListValue)encoded.field("steps")).values());
  steps.set(1,new DataValue.RecordValue(Map.of("kind",new DataValue.TextValue("task_join"),"source",new DataValue.TextValue("join"),
   "name",new DataValue.TextValue("child"),"revision",new DataValue.IntegerValue(1),"handle",new DataValue.TextValue("h"),"outcome",new DataValue.TextValue("result"))));
  var fields=new TreeMap<>(encoded.fields()); fields.put("steps",new DataValue.ListValue(steps));
  return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
 }
 static ComputerBlockHost install(FlowProgram p,long delay) {
  var initial=new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(delay)));
  var workspace=new ProgramWorkspace(ProgramDocument.of(p.expressions())); workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(p,initial)));
  var document=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),LIMITS.archive()),LIMITS.archive());
  var prepared=PreparedProgram.prepare(document,LIMITS); var owner=new ComputerBlockHost(()->{});
  owner.installProgram(prepared,ComputerRuntime.MemoryPolicy.RESET,LIMITS,true); return owner;
 }
 @Test void documentJoinWaitsWithoutCpuWhileSiblingProgressesAndPublishesOnlyDeclaredResultWithRetirement() {
  var owner=install(program(),3);
  for(int i=0;i<10 && !owner.flowSnapshot().orElseThrow().state().status().name().equals("WAITING_TASK");i++) owner.step(LIMITS,true);
  var wait=owner.flowSnapshot().orElseThrow(); assertEquals("WAITING_TASK",wait.state().status().name());
  var sibling=owner.startFlowTask(CHILD,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(0))),LIMITS,true);
  for(int i=0;i<6;i++) { owner.step(LIMITS,true); assertEquals(wait.state().work(),owner.flowSnapshot().orElseThrow().state().work()); }
  assertEquals(FlowRuntime.Status.COMPLETE,owner.flowTask(sibling).orElseThrow().status());
  owner.setPaused(false,LIMITS,true);
  for(int tick=1;tick<=15;tick++) owner.tick(tick,LIMITS,true,Map.of());
  var saved=owner.flowSnapshot().orElseThrow(); var outcome=(DataValue.RecordValue)saved.state().locals().field("result");
  assertEquals(new DataValue.BooleanValue(true),outcome.field("ok")); assertEquals(DataValue.NullValue.INSTANCE,outcome.field("error"));
  assertEquals(new DataValue.RecordValue(Map.of("answer",new DataValue.IntegerValue(3))),outcome.field("value"));
  assertEquals(1,saved.tasks().contexts().size()); assertTrue(saved.tasks().slots().entries().getFirst().retired());
  assertTrue(saved.state().expression().isEmpty()); assertTrue(saved.state().calls().frames().isEmpty());
 }
}
