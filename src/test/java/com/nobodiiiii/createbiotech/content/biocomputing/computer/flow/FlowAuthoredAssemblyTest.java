package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram;
import java.util.*;
import org.junit.jupiter.api.Test;
class FlowAuthoredAssemblyTest {
 static final ResourceLimits LIMITS=new ResourceLimits(2000,100000,1);
 static final ModuleDefinition.Key KEY=new ModuleDefinition.Key("c",1);
 static final String SOURCE="REG one INTEGER\nREG answer INTEGER\nCONST one 1\nADD_INTEGER answer value one\nRET";
 static FlowProgram program(String source) {
  return program(source,AssemblySource.VERSION);
 }
 static FlowProgram program(String source,int version) {
  var p=new FlowProgram(Map.of("seed",DataType.Scalar.INTEGER),List.of(
   new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("value","seed"),Map.of("result","answer"))),
   new FlowProgram.Procedure("c",KEY,Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","answer")),new FlowProgram.EndProcedure("end"))).withAssembly();
  var data=(DataValue.RecordValue)FlowProgramCodec.encode(p); var fields=new TreeMap<>(data.fields());
  var steps=new ArrayList<>(((DataValue.ListValue)data.field("steps")).values());
  var declaration=new TreeMap<>(((DataValue.RecordValue)steps.get(1)).fields());
  declaration.put("assembly",new DataValue.RecordValue(Map.of("assembly_version",new DataValue.IntegerValue(version),"source",new DataValue.TextValue(source))));
  steps.set(1,new DataValue.RecordValue(declaration));fields.put("steps",new DataValue.ListValue(steps));
  return FlowProgramCodec.decode(new DataValue.RecordValue(fields));
 }
 @Test void experimentalV1HeaderIsNotStandardAssemblyV1(){
  var encoded=(DataValue.RecordValue)FlowProgramCodec.encode(program(SOURCE));
  var fields=new TreeMap<>(encoded.fields());
  var steps=new ArrayList<>(((DataValue.ListValue)encoded.field("steps")).values());
  var decl=new TreeMap<>(((DataValue.RecordValue)steps.get(1)).fields());
  assertEquals(new DataValue.IntegerValue(1),((DataValue.RecordValue)decl.get("assembly")).field("assembly_version"));
  decl.put("assembly",new DataValue.RecordValue(Map.of("version",new DataValue.IntegerValue(1),"source",new DataValue.TextValue(SOURCE))));
  steps.set(1,new DataValue.RecordValue(decl));fields.put("steps",new DataValue.ListValue(steps));
  assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,assertThrows(ArchiveException.class,()->FlowProgramCodec.decode(new DataValue.RecordValue(fields))).code());
 }
 @Test void explicitDropReleasesOnlyTheNamedRegisterAndCannotBeReadBeforeReassignment() {
  String source="REG answer INTEGER\nMOV answer value\nDROP value\nRET";
  assertThrows(ArchiveException.class,()->program(source,12).validate(LIMITS));
  assertThrows(ComputationException.class,()->program("REG answer INTEGER\nDROP value\nMOV answer value\nRET",AssemblySource.VERSION).validate(LIMITS));
  var p=program(source,AssemblySource.VERSION);
  var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),LIMITS);
  for(int i=0;runtime.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<30);runtime.advance(1);}
  assertEquals(new DataValue.IntegerValue(41),runtime.checkpoint().locals().field("answer"));
  assertEquals(new DataValue.IntegerValue(41),runtime.checkpoint().locals().field("seed"));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.CsvSource({
   "RISING, 10, 11, 10, true", "RISING, 9, 10, 10, false",
   "FALLING, 11, 10, 10, true", "FALLING, 12, 11, 10, false",
   "RISING, 9007199254740992, 9007199254740993, 9007199254740992, true"})
 void authoredNumericEdgeReadsExplicitSamplesWithoutLosingIntegerPrecision(String direction, long before, long now,
       long threshold, boolean expected) {
  var type=Map.<String,DataType>of("previous",DataType.Scalar.INTEGER,"current",DataType.Scalar.INTEGER,"threshold",DataType.Scalar.INTEGER);
  var returns=Map.<String,DataType>of("fired",DataType.Scalar.BOOLEAN);
  var p=new FlowProgram(type,List.of(
   new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("previous","previous","current","current","threshold","threshold"),Map.of("fired","fired"))),
   new FlowProgram.Procedure("edge",KEY,type,returns,Map.of("fired","hit"),FlowBlockRules.NONE,
      Optional.of(new AssemblySource(AssemblySource.VERSION,"REG hit BOOLEAN\nEDGE."+direction+" hit previous current threshold\nRET"))),
   new FlowProgram.EndProcedure("end"))).withAssembly();
  var values=new DataValue.RecordValue(Map.of("previous",new DataValue.IntegerValue(before),
      "current",new DataValue.IntegerValue(now),"threshold",new DataValue.IntegerValue(threshold)));
  var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,values,LIMITS);
  for(int i=0;r.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<30);r.advance(1);}
  assertEquals(new DataValue.BooleanValue(expected),r.checkpoint().locals().field("fired"));
 }

 @Test void sampledEdgeStartsColdAndCarriesItsPreviousSampleAcrossCheckpoint() {
  var key = new ModuleDefinition.Key("monitor", 1);
  var intType = DataType.Scalar.INTEGER;
  var inputs = Map.<String, DataType>of("first", intType, "next", intType, "limit", intType);
  var outputs = Map.<String, DataType>of("count", intType);
  var source = new AssemblySource(AssemblySource.VERSION, """
      REG current INTEGER
      REG count INTEGER
      REG one INTEGER
      REG flag BOOLEAN
      REG hit BOOLEAN
      REG previous INTEGER
      MOV current first
      CONST count 0
      CONST one 1
      CONST flag false
      again:
      EDGE.SAMPLE.RISING hit previous current limit
      JFALSE hit skip
      ADD_INTEGER count count one
      skip:
      JFALSE flag second
      RET
      second:
      CONST flag true
      MOV current next
      JMP again
      """);
  var flow = new FlowProgram(inputs, List.of(
      new FlowProgram.Invoke("call", key, new FlowCallBindings(Map.of("first","first","next","next","limit","limit"), Map.of("count","count"))),
      new FlowProgram.Procedure("definition", key, inputs, outputs, Map.of("count", "count"),
          FlowBlockRules.NONE, Optional.of(source)), new FlowProgram.EndProcedure("end"))).withAssembly();
  for (var first : List.of(10L, 11L)) {
   var initial = new DataValue.RecordValue(Map.of("first", new DataValue.IntegerValue(first), "next", new DataValue.IntegerValue(11),
       "limit", new DataValue.IntegerValue(10)));
   var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(() -> {});
   var limits = FlowAssemblyTasksTest.LIMITS;
   host.installFlow(flow, initial, limits, true);
   for(int i = 0; i < 13; i++) host.step(limits, true);
   assertEquals(new DataValue.IntegerValue(first), host.flowSnapshot().orElseThrow().state().locals().field("previous"));
   host = FlowAssemblyTasksTest.restored(host);
   for(int i = 0; i < 30 && host.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) host.step(limits, true);
   var result = host.flowSnapshot().orElseThrow().state();
   assertEquals(FlowRuntime.Status.COMPLETE, result.status());
   assertEquals(new DataValue.IntegerValue(first == 10 ? 1 : 0), result.locals().field("count"));
  }
 }

 @Test void decimalEdgeUsesIsa1WithFixedSizeAndRejectsOldDialectOrMixedInputs() {
  var args = Map.<String,DataType>of("prior", DataType.Scalar.DECIMAL, "now", DataType.Scalar.DECIMAL, "limit", DataType.Scalar.DECIMAL);
  var returns = Map.<String,DataType>of("fired", DataType.Scalar.BOOLEAN);
  var bindings = Map.of("fired", "hit");
  var asm = "REG hit BOOLEAN\nEDGE.FALLING hit prior now limit\nRET";
  var image = ProcedureAssembler.assemble(new AssemblySource(asm), args, returns, bindings, 1000, 1000).program();
  var edge = image.code().getFirst().instruction();
  assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterProgram.Alu.class, edge,
      "A numeric edge is software, not a dedicated ISA opcode");
  assertTrue(image.code().stream().filter(c -> c.location().equals("line 2")).count() >= 3);
  assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.InstructionFamily.ISA_1,
      com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.InstructionFamily.of(edge));
  assertThrows(IllegalArgumentException.class, () -> new AssemblySource(21,asm));
  var mixed = new HashMap<>(args); mixed.put("limit", DataType.Scalar.INTEGER);
  var bad = assertThrows(ComputationException.class, () ->
      ProcedureAssembler.assemble(new AssemblySource(asm), mixed, returns, bindings, 1000, 1000));
  assertEquals("assembly/line 2", bad.location());
 }

 @Test void typedScratchCallExecutesAuthoredInstructionsAndChargesRealIsa() {
  var p=program(SOURCE); var runtime=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),LIMITS);
  for(int i=0;runtime.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<30);runtime.advance(1);}
  assertEquals(new DataValue.IntegerValue(42),runtime.checkpoint().locals().field("answer"));
  assertEquals(11,runtime.checkpoint().work()); assertEquals(27,p.usage(LIMITS).programUnits());
 }
 @Test void assemblyEffectsCannotHideWaitsOrLoopsInsideAWrapper() {
  for(String source:List.of("MOV answer value\nSLEEP value\nRET","MOV answer value\nagain:\nJMP again")) {
   var base=program("REG answer INTEGER\n"+source); var steps=new ArrayList<FlowProgram.Step>();
   var wrapperKey=new ModuleDefinition.Key("wrapper",1);
   steps.add(new FlowProgram.Invoke("entry",wrapperKey,new FlowCallBindings(Map.of("value","seed"),Map.of("result","answer"))));
   steps.add(new FlowProgram.Procedure("wrapper",wrapperKey,Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","answer"),new FlowBlockRules(Set.of("device_wait","loop"),Set.of())));
   steps.add(new FlowProgram.Invoke("indirect",KEY,new FlowCallBindings(Map.of("value","value"),Map.of("result","answer"))));
   steps.add(new FlowProgram.EndProcedure("wrapper_end"));steps.addAll(base.steps().subList(1,3));
   var p=new FlowProgram(base.inputs(),steps,base.expressions()).withAssembly();
   assertThrows(ComputationException.class,()->p.validate(LIMITS),source);
  }
 }

 @Test void changingOnlySourceTextStillInvalidatesAnInFlightImage() {
  var p=program(SOURCE);var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),LIMITS);r.advance(1);r.advance(1);
  assertThrows(ComputationException.class,()->FlowRuntime.restore(program(SOURCE+" ; a new fixed implementation"),r.checkpoint(),LIMITS));
 }

 @Test void authoredDocumentsAndCheckpointsRequireTheirCapabilityVersions() {
  var p=program(SOURCE);var initial=new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41)));
  var workspace=new com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramWorkspace();workspace.setFlow(Optional.of(com.nobodiiiii.createbiotech.content.biocomputing.editor.FlowDocumentBody.fromProgram(p,initial)));
  var data=com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.encode(workspace.snapshot());
  assertEquals(new DataValue.IntegerValue(31),data.field("version"));
  assertEquals(workspace.snapshot(),com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(data));
  var fields=new TreeMap<>(data.fields());fields.put("version",new DataValue.IntegerValue(26));
  assertThrows(ArchiveException.class,()->com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
 }

 @Test void diagnosticsIdentifyTheActualBadInstructionAndHardwareCapsBeforeIsaAnalysis() {
  var failure=assertThrows(ComputationException.class,()->program("REG answer INTEGER\nREG unknown INTEGER\nMOV answer unknown\nRET").validate(LIMITS));
  assertTrue(failure.getMessage().contains("line 3"),failure.getMessage());
  var huge=program("REG answer INTEGER\n"+"MOV answer value\n".repeat(300)+"RET");
  var limited=assertThrows(ComputationException.class,()->huge.validate(new ResourceLimits(32,100000,1)));
  assertEquals(ComputationException.Code.PROGRAM_LIMIT,limited.code());
 }

 @Test void explicitTaskStartAndJoinUseTheSameAuthoredTypedProcedure() {
  var base=program(SOURCE);var steps=new ArrayList<FlowProgram.Step>();
  steps.add(new FlowProgram.TaskStart("launch",KEY,Map.of("value","seed"),"h"));steps.add(new FlowProgram.TaskJoin("join",KEY,"h","joined"));steps.addAll(base.steps().subList(1,3));
  var p=new FlowProgram(base.inputs(),steps,base.expressions()).withAssembly();var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
  host.installFlow(p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),FlowAssemblyTasksTest.LIMITS,true);
  host.setPaused(false,FlowAssemblyTasksTest.LIMITS,true);for(int i=0;i<30;i++)host.tick(FlowAssemblyTasksTest.LIMITS,true);
  var state=host.flowSnapshot().orElseThrow().state();assertEquals(FlowRuntime.Status.COMPLETE,state.status());
  var outcome=(DataValue.RecordValue)state.locals().field("joined");assertEquals(new DataValue.BooleanValue(true),outcome.field("ok"));
  assertEquals(new DataValue.RecordValue(Map.of("result",new DataValue.IntegerValue(42))),outcome.field("value"));
 }

 @Test void authoredSleepRetainsSourcePcCallStackAndReturnsOnceAfterPausedOwnerReload() {
  var p=program(SOURCE.replace("ADD_INTEGER","SLEEP one\nADD_INTEGER"));var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
  var limits=FlowAssemblyTasksTest.LIMITS;host.installFlow(p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),limits,true);
  for(int i=0;i<10&&host.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.SLEEPING;i++)host.step(limits,true);
  var before=host.flowSnapshot().orElseThrow();assertEquals(FlowRuntime.Status.SLEEPING,before.state().status());
  assertEquals("c",p.steps().get(before.state().cursor()).sourceId());assertEquals(1,before.state().assembly().orElseThrow().calls().orElseThrow().frames().size());
  var raw=(net.minecraft.nbt.CompoundTag)host.write();assertEquals(37,raw.getInt(FlowBlockNbtCodec.MARKER));
  var flow=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p,before.state()),limits.archive());
  assertEquals(new DataValue.IntegerValue(39),((DataValue.RecordValue)ValueNbtCodec.decode(flow,limits.archive())).field("version"));
  host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});host.read(raw);host.tick(limits,true);assertTrue(host.loaded());
  assertEquals(before,host.flowSnapshot().orElseThrow());for(int i=0;i<4;i++)host.tick(limits,true);assertEquals(before.state().work(),host.flowSnapshot().orElseThrow().state().work());
  host.setPaused(false,limits,true);for(int i=0;i<20;i++)host.tick(limits,true);var completed=host.flowSnapshot().orElseThrow();
  assertEquals(FlowRuntime.Status.COMPLETE,completed.state().status());assertEquals(new DataValue.IntegerValue(42),completed.state().locals().field("answer"));
  assertTrue(completed.state().assembly().orElseThrow().calls().orElseThrow().frames().isEmpty());host.tick(limits,true);assertEquals(completed,host.flowSnapshot().orElseThrow());
 }

 @Test void invalidDecimalConstantsKeepTheirSourceLineDiagnostic() {
  var failure=assertThrows(ComputationException.class,()->program("REG bad DECIMAL\nCONST bad NaN\nREG answer INTEGER\nCONST answer 1\nRET").validate(LIMITS));
  assertEquals("assembly/line 2",failure.location());
 }

 @Test void returnAbiMovesAreNotMisrepresentedAsAuthoredExpressionEffects() {
  var p=new FlowProgram(Map.of("seed",DataType.Scalar.INTEGER),List.of(new FlowProgram.Invoke("call",KEY,new FlowCallBindings(Map.of("value","seed"),Map.of("result","answer"))),
   new FlowProgram.Procedure("c",KEY,Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value"),new FlowBlockRules(Set.of("evaluate"),Set.of()),Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("RET"))),new FlowProgram.EndProcedure("end"))).withAssembly();
  assertDoesNotThrow(()->p.validate(LIMITS));
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={
  "REG answer INTEGER\nMOV answer seed\nRET", "REG answer INTEGER\nJMP caller", "REG answer INTEGER\nCONST answer 1\nJMP -1",
  "REG value INTEGER\nRET", "REG answer INTEGER\nlabel:\nlabel:\nCONST answer 1\nRET", "REG answer TEXT\nRET",
  "REG answer INTEGER\nADD answer value value\nRET", "REG answer INTEGER\nCALL c\nRET", "REG answer INTEGER\nTASK_START c\nRET",
  "REG answer INTEGER\nCONST answer 1\nSLEEP answer extra\nRET", "REG answer INTEGER\nREG flag BOOLEAN\nCONST flag maybe\nRET",
  "REG answer INTEGER\nREG flag BOOLEAN\nCONST flag true\nMOV answer flag\nRET",
  "REG answer INTEGER\nREG flag BOOLEAN\nCONST flag true\nJFALSE flag done\nCONST answer 1\ndone:\nRET",
  "REG answer INTEGER\nRET", "REG answer INTEGER\nADD_INTEGER answer value absent\nRET"})
 void malformedSourcesCannotEscapeLocalSymbolsTypesOrInitialization(String source){assertThrows(ComputationException.class,()->program(source).validate(LIMITS));}
 @Test void typedForwardBranchesAndDecimalAluUseExistingIsaAndAreNotLoops(){
  for(String source:List.of("REG answer INTEGER\nREG flag BOOLEAN\nCONST flag false\nJFALSE flag otherwise\nCONST answer 999\nJMP done\notherwise:\nCONST answer 42\ndone:\nRET", "REG decimal DECIMAL\nREG answer INTEGER\nCONST decimal 41.6\nROUND_DECIMAL_TO_INTEGER answer decimal\nRET")){
   var p=program(source);var steps=new ArrayList<>(p.steps());var original=(FlowProgram.Procedure)steps.get(1);
   steps.set(1,new FlowProgram.Procedure(original.sourceId(),original.key(),original.inputs(),original.outputs(),original.returns(),new FlowBlockRules(Set.of("loop"),Set.of()),original.assembly()));
   p=new FlowProgram(p.inputs(),steps,p.expressions()).withAssembly();var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),LIMITS);
   for(int i=0;r.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<30);r.advance(1);}assertEquals(new DataValue.IntegerValue(42),r.checkpoint().locals().field("answer"));
  }
 }
 @Test void localInfiniteBranchYieldsToEachAdmittedBudget(){
  var p=program("REG answer INTEGER\nMOV answer value\nagain:\nJMP again");var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),LIMITS);
  for(int i=1;i<=30;i++){r.advance(1);assertEquals(i,r.checkpoint().work());assertEquals(FlowRuntime.Status.READY,r.checkpoint().status());}
 }
 @Test void futureOrDowngradedSourcesAndOwnerSnapshotsRejectWithoutExecution(){
  var p=program(SOURCE);var encoded=(DataValue.RecordValue)FlowProgramCodec.encode(p);var fields=new TreeMap<>(encoded.fields());var steps=new ArrayList<>(((DataValue.ListValue)encoded.field("steps")).values());var definition=(DataValue.RecordValue)steps.get(1);
  var assembly=(DataValue.RecordValue)definition.field("assembly");var af=new TreeMap<>(assembly.fields());af.put("assembly_version",new DataValue.IntegerValue(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION+1));var df=new TreeMap<>(definition.fields());df.put("assembly",new DataValue.RecordValue(af));steps.set(1,new DataValue.RecordValue(df));fields.put("steps",new DataValue.ListValue(steps));
  assertEquals(ArchiveException.Code.UNSUPPORTED_VERSION,assertThrows(ArchiveException.class,()->FlowProgramCodec.decode(new DataValue.RecordValue(fields))).code());
  assertThrows(ComputationException.class,()->new FlowProgram(p.inputs(),p.steps(),p.expressions()).validate(LIMITS));
  var host=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});var limits=FlowAssemblyTasksTest.LIMITS;host.installFlow(p,new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),limits,true);var owner=(net.minecraft.nbt.CompoundTag)host.write();
  owner.putInt(FlowBlockNbtCodec.MARKER,31);assertThrows(ArchiveException.class,()->FlowBlockNbtCodec.decode(owner,limits.archive()));
  var snapshot=FlowNbtCodec.encode(new FlowNbtCodec.Snapshot(p,host.flowSnapshot().orElseThrow().state()),limits.archive());var saved=(DataValue.RecordValue)ValueNbtCodec.decode(snapshot,limits.archive());
  for(long version:List.of(32L,34L)){var sf=new TreeMap<>(saved.fields());sf.put("version",new DataValue.IntegerValue(version));var forged=ValueNbtCodec.encode(new DataValue.RecordValue(sf),limits.archive());assertThrows(ArchiveException.class,()->FlowNbtCodec.decode(forged,limits.archive()));}
 }
 @Test void sourceBytesAndMixedBodiesHaveNoInstallationEscape(){
  assertThrows(ComputationException.class,()->program(SOURCE+";".repeat(com.nobodiiiii.createbiotech.content.biocomputing.editor.ProgramEditorWire.MAX_BYTES)).validate(LIMITS));
  var p=program(SOURCE);var steps=new ArrayList<>(p.steps());steps.add(2,new FlowProgram.Sleep("visual","value"));assertThrows(ComputationException.class,()->new FlowProgram(p.inputs(),steps,p.expressions()).withAssembly().validate(LIMITS));
 }

 @Test void floorModuloRequiresExplicitDialectUpgradeAndDoesNotChangeTheOldRemainder() {
  var remainder = "REG divisor INTEGER\nREG answer INTEGER\nCONST divisor 3\nREMAINDER_INTEGER answer value divisor\nRET";
  var modulo = remainder.replace("REMAINDER_INTEGER", "MOD_INTEGER");
  for (var version : List.of(15, 16)) {
   var original = program(version == 15 ? remainder : modulo);
   var steps = new ArrayList<>(original.steps());
   var procedure = (FlowProgram.Procedure)steps.get(1);
   if (version == 15) assertThrows(IllegalArgumentException.class,
    () -> com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.ProcedureAssembler.assemble(
     new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(15,modulo),
     procedure.inputs(), procedure.outputs(), procedure.returns(), 4096, 64));
   steps.set(1, new FlowProgram.Procedure(procedure.sourceId(), procedure.key(), procedure.inputs(), procedure.outputs(),
    procedure.returns(), procedure.rules(), Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(
     version == 15 ? remainder : modulo))));
   var p = new FlowProgram(original.inputs(), steps, original.expressions()).withAssembly();
   var runtime = new FlowRuntime(UUID.randomUUID(), UUID.randomUUID(), p,
    new DataValue.RecordValue(Map.of("seed", new DataValue.IntegerValue(-7))), LIMITS);
   for (int i=0; runtime.checkpoint().status()==FlowRuntime.Status.READY; i++) {
    assertTrue(i<30); assertEquals(1, runtime.advance(1));
   }
   assertEquals(FlowRuntime.Status.COMPLETE, runtime.checkpoint().status());
   assertEquals(new DataValue.IntegerValue(version == 15 ? -1 : 2), runtime.checkpoint().locals().field("answer"));
  }
 }
 @Test void extendedDecimalMathUsesVersionedScalarAlu() {
  var signature = Map.<String,DataType>of("value", DataType.Scalar.DECIMAL);
  var output = Map.<String,DataType>of("result", DataType.Scalar.DECIMAL);
  var returns = Map.of("result", "answer");
  var text = "REG answer DECIMAL\nSIN_DEGREES answer value\nRET";
  assertThrows(IllegalArgumentException.class,
   () -> ProcedureAssembler.assemble(new AssemblySource(17,text), signature, output, returns, 4096, 64));
  var compiled = ProcedureAssembler.assemble(new AssemblySource(text), signature, output, returns, 4096, 64).program();
  var instruction = assertInstanceOf(RegisterProgram.Alu.class, compiled.code().getFirst().instruction());
  assertEquals(ScalarOperation.SIN_DEGREES, instruction.operation());
  assertEquals(3, instruction.words());
 }


}
