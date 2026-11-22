package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowAssemblyTasksTest.LIMITS;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.tasks.TaskOutcome;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;
import org.junit.jupiter.api.Test;
class FlowRequiredValueTest {
 static final ModuleDefinition.Key CHILD=new ModuleDefinition.Key("child",1);
 static ComputationGraph extract() {
  return new ComputationGraph(Map.of("packet",TaskOutcome.type(Map.of("answer",DataType.Scalar.INTEGER))),
   Map.of("input",new ComputationGraph.Input("packet"),"optional",new ComputationGraph.Field("input","value"),
    "required",new ComputationGraph.Scalar(ScalarOperation.valueOf("REQUIRE_VALUE"),Map.of("value","optional"))),Map.of("value","required"));
 }
 static FlowProgram program() {
  var arithmetic=new ComputationGraph(Map.of("payload",new DataType.RecordType(Map.of("answer",DataType.Scalar.INTEGER))),Map.of(
   "input",new ComputationGraph.Input("payload"),"answer",new ComputationGraph.Field("input","answer"),
   "one",new ComputationGraph.Constant(new DataValue.IntegerValue(1)),"sum",new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER,Map.of("left","answer","right","one"))),Map.of("value","sum"));
  var empty=new ComputationGraph(Map.of(),Map.of(),Map.of());
  var archive=new ProgramArchive(new ProgramDefinition(empty,Map.of(),List.of(),List.of(),Map.of("extract",new ProgramDefinition.Entry(extract(),List.of(),List.of()),"arithmetic",new ProgramDefinition.Entry(arithmetic,List.of(),List.of()))),Map.of());
  return new FlowProgram(FlowTaskJoinTest.TYPES,List.of(
   new FlowProgram.TaskStart("launch",CHILD,Map.of("delay","delay"),"h"),new FlowProgram.TaskJoin("join",CHILD,"h","outcome"),
   new FlowProgram.Evaluate("extract_block","extract",new FlowCallBindings(Map.of("packet","outcome"),Map.of("value","payload"))),
   new FlowProgram.Evaluate("arithmetic_block","arithmetic",new FlowCallBindings(Map.of("payload","payload"),Map.of("value","sum"))),
   new FlowProgram.Procedure("child",CHILD,FlowTaskJoinTest.TYPES,Map.of("answer",DataType.Scalar.INTEGER),Map.of("answer","delay")),
   new FlowProgram.EndProcedure("return")),archive).withAssembly();
 }
 @Test void publicTaskResultContinuesAsPrivateRecordFieldArithmeticAcrossEveryPausedOwnerReload() {
  var p=program();var owner=FlowTaskJoinTest.install(p,41); boolean beforeExtract=false,afterExtract=false;
  for(int i=0;i<80;i++) {
   var saved=owner.flowSnapshot().orElseThrow();beforeExtract|=saved.state().locals().fields().containsKey("outcome")&&!saved.state().locals().fields().containsKey("payload");
   afterExtract|=saved.state().locals().fields().containsKey("payload");
   owner=FlowAssemblyTasksTest.restored(owner);owner.step(LIMITS,true);
   if(owner.flowSnapshot().orElseThrow().state().status()==FlowRuntime.Status.COMPLETE)break;
  }
  var saved=owner.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,saved.state().status(),saved.state().diagnostic().toString());
  assertTrue(beforeExtract);assertTrue(afterExtract);assertEquals(new DataValue.IntegerValue(42),saved.state().locals().field("sum"));
  assertEquals(new DataValue.IntegerValue(41),saved.initialInputs().field("delay"));assertTrue(saved.tasks().contexts().isEmpty());
  assertEquals(((DataValue.RecordValue)saved.state().locals().field("outcome")).field("value"),saved.state().locals().field("payload"));
  System.out.println("REQUIRE_VALUE tracer words="+p.compile(LIMITS.hardware()).assembly().orElseThrow().codeWords()+" work="+saved.state().work());
 }
 @Test void requiredValueCapabilitiesCannotBeDowngradedAtOwnerSnapshotOrDocumentBoundaries() {
  var p=program();var owner=FlowTaskJoinTest.install(p,41);
  var tag=(net.minecraft.nbt.CompoundTag)owner.write();assertEquals(33,tag.getInt(FlowBlockNbtCodec.MARKER));
  tag.putInt(FlowBlockNbtCodec.MARKER,32);var rejected=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});rejected.read(tag);rejected.tick(LIMITS,true);assertFalse(rejected.loaded());assertEquals(tag,rejected.write());
  var saved=owner.flowSnapshot().orElseThrow();var snapshot=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p,saved.state()),LIMITS.archive());
  var data=(DataValue.RecordValue)ValueNbtCodec.decode(snapshot,LIMITS.archive());assertEquals(new DataValue.IntegerValue(35),data.field("version"));
  assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(ValueNbtCodec.encode(FlowTaskJoinPersistenceTest.patch(data,"version",new DataValue.IntegerValue(34)),LIMITS.archive()),LIMITS.archive()));
  var workspace=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace(com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocument.of(p.expressions()));
  workspace.setFlow(Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.fromProgram(p,saved.initialInputs())));
  var doc=com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(workspace.snapshot());assertEquals(new DataValue.IntegerValue(28),doc.field("version"));
  assertThrows(ArchiveException.class,()->com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(FlowTaskJoinPersistenceTest.patch(doc,"version",new DataValue.IntegerValue(27))));
 }
 static FlowProgram guardedProgram() {
  var base=program();var packet=TaskOutcome.type(Map.of("answer",DataType.Scalar.INTEGER));
  var ok=new ComputationGraph(Map.of("packet",packet),Map.of("input",new ComputationGraph.Input("packet"),"ok",new ComputationGraph.Field("input","ok")),Map.of("value","ok"));
  var definition=base.expressions().program();var entries=new TreeMap<>(definition.entries());entries.put("ok",new ProgramDefinition.Entry(ok,List.of(),List.of()));
  var archive=new ProgramArchive(new ProgramDefinition(definition.graph(),Map.of("marker",new ProgramDefinition.Cell(DataType.Scalar.INTEGER,new DataValue.IntegerValue(9))),List.of(),List.of(),entries),Map.of());
  var b=base.steps();return new FlowProgram(base.inputs(),List.of(b.get(0),b.get(1),new FlowProgram.If("guard",new FlowInput.Expression("ok","value",Map.of("packet","outcome"))),b.get(2),b.get(3),
   new FlowProgram.Shared("publish","marker",new FlowInput.Local("sum"),false),new FlowProgram.EndIf("end_guard"),b.get(4),new FlowProgram.Sleep("child_failure","delay"),b.get(5)),archive).withAssembly();
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(longs={0,41,-1})
 void guardedSuccessRetainsZeroAndFailedOutcomeIsBranchableWithoutInvokingExtraction(long value) {
  var p=guardedProgram();var owner=FlowTaskJoinTest.install(p,value);boolean ran=false;
  var image=p.compile(LIMITS.hardware()).assembly().orElseThrow();
  for(int i=0;i<100;i++) {var state=owner.flowSnapshot().orElseThrow().state();if(state.status()==FlowRuntime.Status.COMPLETE)break;
   var registers=state.assembly().orElseThrow();if(registers.pc()<image.code().size()&&image.code().get(registers.pc()).instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Alu a&&a.operation()==ScalarOperation.REQUIRE_VALUE)ran=true;
   owner=FlowAssemblyTasksTest.restored(owner);owner.step(LIMITS,true);
  }
  var saved=owner.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.COMPLETE,saved.state().status(),saved.state().diagnostic().toString());assertEquals(value>=0,ran);
  var outcome=(DataValue.RecordValue)saved.state().locals().field("outcome");assertEquals(new DataValue.BooleanValue(value>=0),outcome.field("ok"));assertEquals(new DataValue.IntegerValue(value<0?9:value+1),saved.memory().cells().field("marker"));assertTrue(saved.tasks().contexts().isEmpty());
  if(value<0)assertSame(DataValue.NullValue.INSTANCE,outcome.field("value"));
  System.out.println("REQUIRE_VALUE guarded words="+image.codeWords()+" work="+saved.state().work());
 }
 static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost beforeRequired(FlowProgram p,long value) {
  var owner=FlowTaskJoinTest.install(p,value);var image=p.compile(LIMITS.hardware()).assembly().orElseThrow();
  for(int i=0;i<100;i++) {var state=owner.flowSnapshot().orElseThrow().state();var registers=state.assembly().orElseThrow();
   if(registers.pc()<image.code().size()&&image.code().get(registers.pc()).instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Alu a&&a.operation()==ScalarOperation.REQUIRE_VALUE)return owner;
   owner.step(LIMITS,true);
  }throw new AssertionError("extraction not reached");
 }
 @Test void unguardedFailedTaskNullFaultKeepsPcDestinationTaskRetirementAndUnrelatedMemory() {
  var base=program();var steps=new ArrayList<>(base.steps());steps.add(5,new FlowProgram.Sleep("fail_child","delay"));var p=new FlowProgram(base.inputs(),steps,base.expressions()).withAssembly();
  var owner=beforeRequired(p,-1);var before=owner.flowSnapshot().orElseThrow();owner.step(LIMITS,true);var after=owner.flowSnapshot().orElseThrow();
  assertEquals(FlowRuntime.Status.FAILED,after.state().status());assertEquals(ComputationException.Code.INVALID_ARGUMENT,after.state().diagnostic().orElseThrow().code());assertEquals("extract_block",after.state().diagnostic().orElseThrow().location());
  assertEquals(before.state().assembly(),after.state().assembly());assertEquals(before.state().locals(),after.state().locals());assertEquals(before.tasks(),after.tasks());assertEquals(before.memory(),after.memory());FlowAssemblyTasksTest.restored(owner);
 }
 @Test void extractionLogicalRamAdmissionAndArchiveFailureCannotPublishNewRegisterOrLoseClaimedOutcome() {
  var owner=beforeRequired(program(),41);var before=owner.flowSnapshot().orElseThrow();long ram=FlowTaskJoinResourcesTest.ram(owner),disk=FlowTaskJoinResourcesTest.disk(owner);
  var unrestricted=FlowTaskJoinResourcesTest.restore(owner,LIMITS);unrestricted.step(LIMITS,true);assertTrue(FlowTaskJoinResourcesTest.ram(unrestricted)>ram);assertTrue(FlowTaskJoinResourcesTest.disk(unrestricted)>disk);
  var tight=FlowTaskJoinResourcesTest.limits(ram,LIMITS.archive().maxValueUnits());var constrained=FlowTaskJoinResourcesTest.restore(owner,tight);constrained.step(tight,true);var failed=constrained.flowSnapshot().orElseThrow();
  assertEquals(FlowRuntime.Status.FAILED,failed.state().status());assertEquals(before.state().assembly(),failed.state().assembly());assertEquals(before.state().locals(),failed.state().locals());assertEquals(before.tasks(),failed.tasks());
  var archive=FlowTaskJoinResourcesTest.limits(LIMITS.hardware().memoryUnits(),disk);var full=FlowTaskJoinResourcesTest.restore(owner,archive);var raw=full.write();assertThrows(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost.HostException.class,()->full.step(archive,true));assertEquals(raw,full.write());
 }
 @Test void extractionInsideSynchronousCallAndTaskPreservesNativeFramesAcrossEveryOwnerSave() {
  var base=program();var outer=new ModuleDefinition.Key("outer",1);var steps=new ArrayList<FlowProgram.Step>();
  steps.add(new FlowProgram.Invoke("outer_call",outer,new FlowCallBindings(Map.of("delay","delay"),Map.of("sum","sum"))));
  steps.add(new FlowProgram.Procedure("outer_definition",outer,base.inputs(),Map.of("sum",DataType.Scalar.INTEGER),Map.of("sum","sum")));steps.addAll(base.steps().subList(0,4));steps.add(new FlowProgram.EndProcedure("outer_return"));steps.addAll(base.steps().subList(4,6));
  var p=new FlowProgram(base.inputs(),steps,base.expressions()).withAssembly();var owner=FlowTaskJoinTest.install(p,41);boolean frames=false;
  for(int i=0;i<100&&owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){frames|=!owner.flowSnapshot().orElseThrow().state().assembly().orElseThrow().calls().orElseThrow().frames().isEmpty();owner=FlowAssemblyTasksTest.restored(owner);owner.step(LIMITS,true);}
  assertTrue(frames);assertEquals(new DataValue.IntegerValue(42),owner.flowSnapshot().orElseThrow().state().locals().field("sum"));
  // Run the same outer as an independently admitted task, keeping the main forever loop private.
  steps.set(0,new FlowProgram.Forever("loop"));steps.add(1,new FlowProgram.EndForever("again"));p=new FlowProgram(Map.of(),steps,base.expressions()).withAssembly();
  owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});owner.installFlow(p,new DataValue.RecordValue(Map.of()),LIMITS,true);var task=owner.startFlowTask(outer,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(41))),LIMITS,true);
  for(int i=0;i<150&&owner.flowTask(task).orElseThrow().status()!=FlowRuntime.Status.COMPLETE;i++){owner=FlowAssemblyTasksTest.restored(owner);owner.step(LIMITS,true);}
  assertEquals(new DataValue.RecordValue(Map.of("sum",new DataValue.IntegerValue(42))),owner.flowTask(task).orElseThrow().locals());assertTrue(owner.flowSnapshot().orElseThrow().state().locals().fields().isEmpty());
 }
}
