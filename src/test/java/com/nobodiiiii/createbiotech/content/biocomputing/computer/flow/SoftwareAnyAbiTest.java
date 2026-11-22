package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.editor.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SoftwareAnyAbiTest {
 static final ArchiveLimits ARCHIVE=new ArchiveLimits(100000,1000000,1000000);
 static final ComputerHostLimits LIMITS=new ComputerHostLimits(new ResourceLimits(8192,200000,1),ARCHIVE,NativeLibraryOwnersTest.limits("cleric",4).mailbox(),NativeLibraryOwnersTest.limits("cleric",4).pins(),NativeLibraryOwnersTest.limits("cleric",4).serial(),4,true,false);
 static ModuleDefinition.Key key(String name){return new ModuleDefinition.Key(name,1);}
 static final Map<String,DataType> INPUTS=Map.of("number",DataType.Scalar.INTEGER,"text",DataType.Scalar.TEXT,"native",DataType.Scalar.NBT,"integer",DataType.Scalar.BOOLEAN,"native_mode",DataType.Scalar.BOOLEAN,"ticks",DataType.Scalar.INTEGER);
 static FlowProgram.Invoke call(String id,String proc,Map<String,String> args,Map<String,String> returns){return new FlowProgram.Invoke(id,key(proc),new FlowCallBindings(args,returns));}
 static FlowProgram.Evaluate assign(String id,String input,String target){return new FlowProgram.Evaluate(id,input,new FlowCallBindings(Map.of("input",input),Map.of("value",target)));}
 static FlowProgram source(){
  var entries=new LinkedHashMap<String,ProgramDefinition.Entry>();for(String name:List.of("text","native")){var graph=new ComputationGraph(Map.of("input",INPUTS.get(name)),Map.of("input",new ComputationGraph.Input("input")),Map.of("value","input"));entries.put(name,new ProgramDefinition.Entry(graph,List.of(),List.of()));}
  var steps=new ArrayList<FlowProgram.Step>();steps.add(call("call_choose","choose",Map.of("value","number","text","text","native","native","integer","integer","native_mode","native_mode","ticks","ticks"),Map.of("result","dynamic")));
  steps.add(new FlowProgram.If("consume_integer","integer"));steps.add(call("take_integer","integer_consumer",Map.of("value","dynamic"),Map.of("result","out_integer")));steps.add(new FlowProgram.Otherwise("consume_other"));
  steps.add(new FlowProgram.If("consume_native","native_mode"));steps.add(call("take_native","native_consumer",Map.of("value","dynamic"),Map.of("result","out_native")));steps.add(new FlowProgram.Otherwise("consume_text"));steps.add(call("take_text","text_consumer",Map.of("value","dynamic"),Map.of("result","out_text")));steps.add(new FlowProgram.EndIf("consume_inner_end"));steps.add(new FlowProgram.EndIf("consume_end"));
  var params=new LinkedHashMap<>(INPUTS);params.remove("number");params.put("value",DataType.Scalar.ANY);
  steps.add(new FlowProgram.Procedure("choose_def",key("choose"),params,Map.of("result",DataType.Scalar.ANY),Map.of("result","value")));
  steps.add(new FlowProgram.If("choose_integer","integer"));steps.add(new FlowProgram.Otherwise("choose_other"));steps.add(new FlowProgram.If("choose_native","native_mode"));steps.add(assign("assign_native","native","value"));steps.add(new FlowProgram.Otherwise("choose_text"));steps.add(assign("assign_text","text","value"));steps.add(new FlowProgram.EndIf("choose_inner_end"));steps.add(new FlowProgram.EndIf("choose_end"));
  steps.add(call("relay_call","relay",Map.of("value","value"),Map.of("result","value")));steps.add(new FlowProgram.Sleep("sleep","ticks"));steps.add(new FlowProgram.EndProcedure("choose_return"));
  for(var entry:Map.of("relay",DataType.Scalar.ANY,"integer_consumer",DataType.Scalar.INTEGER,"text_consumer",DataType.Scalar.TEXT,"native_consumer",DataType.Scalar.NBT).entrySet()){
   steps.add(new FlowProgram.Procedure(entry.getKey()+"_def",key(entry.getKey()),Map.of("value",DataType.Scalar.ANY),Map.of("result",entry.getValue()),Map.of("result","value")));steps.add(new FlowProgram.EndProcedure(entry.getKey()+"_end"));
  }
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),entries),Map.of());
  return new FlowProgram(INPUTS,steps,archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.parse("software_v1"));
 }
 static DataValue.RecordValue initial(int mode){return new DataValue.RecordValue(Map.of("number",new DataValue.IntegerValue(42),"text",new DataValue.TextValue("runtime text"),"native",new DataValue.NbtValue(8,new DataValue.TextValue("native text")),"integer",new DataValue.BooleanValue(mode==0),"native_mode",new DataValue.BooleanValue(mode==2),"ticks",new DataValue.IntegerValue(2)));}
 static FlowRuntime.Checkpoint run(FlowProgram p,DataValue.RecordValue initial,boolean reload){
  var workspace=new ProgramWorkspace(new ProgramDocument(p.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(p,initial))));
  var document=workspace.snapshot();var wire=ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,ARCHIVE),ARCHIVE);assertEquals(document,wire);
  var host=new ComputerBlockHost(()->{});host.installProgram(PreparedProgram.prepare(wire,LIMITS),ComputerRuntime.MemoryPolicy.RESET,LIMITS,true);host.setPaused(false,LIMITS,true);
  for(int i=0;i<5000;i++){host.tick(LIMITS,true);var saved=host.flowSnapshot().orElseThrow();var state=saved.state();if(state.status()==FlowRuntime.Status.COMPLETE)return state;if(state.status()==FlowRuntime.Status.FAILED){var error=state.diagnostic().orElseThrow();throw new ComputationException(error.code(),error.location(),error.message());}
   if(reload){var cold=new ComputerBlockHost(()->{});cold.read(host.write());cold.setPaused(false,LIMITS,true);assertEquals(saved,cold.flowSnapshot().orElseThrow());host=cold;}
  }throw new AssertionError("not complete");
 }
 @Test void visualAnyBranchesReturnToTypedConsumersInOneImageAndColdNestedCall(){
  var source=source();String image=null;
  for(int mode=0;mode<3;mode++){
   var normal=run(source,initial(mode),false);var cold=run(source,initial(mode),true);assertEquals(normal.locals(),cold.locals());assertEquals(normal.work(),cold.work());
   String current=normal.assembly().orElseThrow().image();if(image==null)image=current;else assertEquals(image,current);
   String port=mode==0?"integer":mode==1?"text":"native";assertEquals(initial(mode).field(mode==0?"number":port),normal.locals().field("out_"+port));
  }
  var code=source.compile(LIMITS.hardware()).assembly().orElseThrow();assertTrue(code.registerTypes().stream().noneMatch(AssemblyTypeRequirements::containsAny));assertFalse(code.instructionFamilies().contains(InstructionFamily.ISA_1B));assertEquals(5,code.procedures().size());
 }
 static FlowProgram simple(DataType input,DataType output,boolean before){
  var base=source();var steps=new ArrayList<FlowProgram.Step>();
  var definition=List.<FlowProgram.Step>of(new FlowProgram.Procedure("relay_def",key("relay"),Map.of("value",DataType.Scalar.ANY,"ticks",DataType.Scalar.INTEGER),Map.of("result",output),Map.of("result","value")),new FlowProgram.Sleep("pause","ticks"),new FlowProgram.EndProcedure("return"));
  if(before)steps.addAll(definition);
  steps.add(call("call","relay",Map.of("value","input","ticks","ticks"),Map.of("result","result")));
  if(!before)steps.addAll(definition);
  return new FlowProgram(Map.of("input",input,"ticks",DataType.Scalar.INTEGER),steps,base.expressions(),FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
 }
 @Test void booleanNullAndTypedMismatchUseActualOwnerPath(){
  for(var entry:Map.of(DataType.Scalar.BOOLEAN,new DataValue.BooleanValue(true),DataType.Scalar.NULL,DataValue.NullValue.INSTANCE).entrySet()){
   var initial=new DataValue.RecordValue(Map.of("input",entry.getValue(),"ticks",new DataValue.IntegerValue(1)));
   for(boolean before:List.of(false,true))assertEquals(entry.getValue(),run(simple(entry.getKey(),entry.getKey(),before),initial,true).locals().field("result"));
  }
  var wrong=new DataValue.RecordValue(Map.of("input",new DataValue.TextValue("wrong"),"ticks",new DataValue.IntegerValue(1)));
  var error=assertThrows(ComputationException.class,()->run(simple(DataType.Scalar.TEXT,DataType.Scalar.INTEGER,false),wrong,true));
  assertEquals(ComputationException.Code.INVALID_ARGUMENT,error.code());assertTrue(error.location().contains("return"));
 }
 @Test void provableScalarKeepsClosedImageAndNoParser(){
  var p=simple(DataType.Scalar.INTEGER,DataType.Scalar.ANY,false);
  var closed=new FlowProgram(p.inputs(),p.steps(),p.expressions(),p.execution(),FlowProgram.Compilation.CLOSED_V1);
  var old=closed.compile(LIMITS.hardware()).assembly().orElseThrow();var next=p.compile(LIMITS.hardware()).assembly().orElseThrow();
  assertEquals(old.code(),next.code());assertEquals(old.registerTypes(),next.registerTypes());assertEquals(old.codeWords(),next.codeWords());
 }
 @Test void forgedCarrierTagRejectedButUserLookalikeIsOrdinaryRecord(){
  var done=run(source(),initial(1),false);var original=done.assembly().orElseThrow();var image=source().compile(LIMITS.hardware()).assembly().orElseThrow();
  var registers=new TreeMap<>(original.registers());
  int carrier=registers.entrySet().stream().filter(e->e.getValue() instanceof DataValue.RecordValue r&&r.fields().keySet().equals(Set.of("kind","payload"))).findFirst().orElseThrow().getKey();
  var value=(DataValue.RecordValue)registers.get(carrier);
  registers.put(carrier,new DataValue.RecordValue(Map.of("kind",new DataValue.IntegerValue(99),"payload",value.field("payload"))));
  var forged=new RegisterState(original.image(),original.pc(),registers,original.structured(),original.scan(),original.calls(),original.transaction(),original.constructionCapable(),original.build(),original.serialCapable(),original.serial(),original.equalityCapable(),original.comparison());
  assertEquals(ComputationException.Code.INVALID_CHECKPOINT,assertThrows(ComputationException.class,()->image.validate(forged,done.work(),false)).code());
 }

 static FlowProgram choice(DataType first,DataType second,ScalarOperation operation){
  var inputs=Map.<String,DataType>of("first",first,"second",second,"flag",DataType.Scalar.BOOLEAN,"ticks",DataType.Scalar.INTEGER);
  var assignment=new ComputationGraph(Map.of("input",second),Map.of("input",new ComputationGraph.Input("input")),Map.of("value","input"));
  var observation=new ComputationGraph(Map.of("value",DataType.Scalar.ANY),Map.of("input",new ComputationGraph.Input("value"),"observe",new ComputationGraph.Scalar(operation,Map.of("value","input"))),Map.of("value","observe"));
  var entries=Map.of("assignment",new ProgramDefinition.Entry(assignment,List.of(),List.of()),"observation",new ProgramDefinition.Entry(observation,List.of(),List.of()));
  var steps=List.<FlowProgram.Step>of(call("choose","choice",Map.of("value","first","second","second","flag","flag","ticks","ticks"),Map.of("result","dynamic")),new FlowProgram.Evaluate("observe","observation",new FlowCallBindings(Map.of("value","dynamic"),Map.of("value","observed"))),
   new FlowProgram.Procedure("definition",key("choice"),Map.of("value",DataType.Scalar.ANY,"second",second,"flag",DataType.Scalar.BOOLEAN,"ticks",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.ANY),Map.of("result","value")),new FlowProgram.Repeat("repeat","ticks"),new FlowProgram.If("branch","flag"),new FlowProgram.Evaluate("assign","assignment",new FlowCallBindings(Map.of("input","second"),Map.of("value","value"))),new FlowProgram.EndIf("end_branch"),new FlowProgram.EndRepeat("end_repeat"),new FlowProgram.Sleep("sleep","ticks"),new FlowProgram.EndProcedure("end"));
  return new FlowProgram(inputs,steps,new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),entries),Map.of()),FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
 }
 @Test void booleanNullRuntimeChoiceLoopsAndSourcePredicates(){
  String image=null;
  for(boolean flag:List.of(false,true)){
   var initial=new DataValue.RecordValue(Map.of("first",new DataValue.BooleanValue(false),"second",DataValue.NullValue.INSTANCE,"flag",new DataValue.BooleanValue(flag),"ticks",new DataValue.IntegerValue(2)));
   var p=choice(DataType.Scalar.BOOLEAN,DataType.Scalar.NULL,ScalarOperation.IS_NULL);var done=run(p,initial,true);
   assertEquals(new DataValue.BooleanValue(flag),done.locals().field("observed"));
   String next=done.assembly().orElseThrow().image();if(image==null)image=next;else assertEquals(image,next);
   if(flag){var failure=assertThrows(ComputationException.class,()->run(choice(DataType.Scalar.BOOLEAN,DataType.Scalar.NULL,ScalarOperation.REQUIRE_VALUE),initial,true));assertEquals(ComputationException.Code.INVALID_ARGUMENT,failure.code());assertTrue(failure.location().contains("observe"));}
  }
 }

 @Test void unsupportedOpenBoundariesAreSourceErrorsAndLookalikeRecordIsNotDecoded(){
  for(DataType variant:List.of(DataType.Scalar.DECIMAL,DataType.Scalar.ERROR,new DataType.ListType(DataType.Scalar.INTEGER),SoftwareAnyAbi.TYPE)){
   var error=assertThrows(ComputationException.class,()->simple(variant,DataType.Scalar.ANY,false).compile(LIMITS.hardware()));assertTrue(error.getMessage().contains("Unsupported software ANY variant"));
  }
  var p=source();var steps=new ArrayList<>(p.steps());steps.add(1,new FlowProgram.SerialSend("leak","serial","dynamic","sent"));
  var error=assertThrows(ComputationException.class,()->new FlowProgram(p.inputs(),steps,p.expressions(),p.execution(),p.compilation()).compile(LIMITS.hardware()));assertTrue(error.getMessage().contains("boundary"));assertEquals("leak",error.location());
  var user=new DataValue.RecordValue(Map.of("kind",new DataValue.IntegerValue(99),"payload",new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE)));
  var image=new RegisterProgram(List.of(),List.of(SoftwareAnyAbi.TYPE),List.of(),Map.of("user",0),Map.of("user",0),0);var state=image.begin(new DataValue.RecordValue(Map.of("user",user)));image.validate(state,0,false);assertEquals(user,image.locals(state).field("user"));assertEquals(user,image.displayLocals(state).field("user"));
 }
 @Test void sourceSelectAndValueEqualityRespectLogicalKinds(){
  var inputs=Map.<String,DataType>of("flag",DataType.Scalar.BOOLEAN,"text",DataType.Scalar.TEXT,"native",DataType.Scalar.NBT);
  var nodes=new LinkedHashMap<String,ComputationGraph.Node>();for(String name:inputs.keySet())nodes.put(name,new ComputationGraph.Input(name));
  nodes.put("select",new ComputationGraph.Scalar(ScalarOperation.SELECT,Map.of("condition","flag","when_true","text","when_false","native")));nodes.put("equal",new ComputationGraph.ValueEquals("select","text"));
  var graph=new ComputationGraph(inputs,nodes,Map.of("selected","select","same","equal"));
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of("select",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
  var p=new FlowProgram(inputs,List.of(new FlowProgram.Evaluate("select","select")),archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);String image=null;
  for(boolean flag:List.of(false,true)){
   var initial=new DataValue.RecordValue(Map.of("flag",new DataValue.BooleanValue(flag),"text",new DataValue.TextValue("same"),"native",new DataValue.NbtValue(8,new DataValue.TextValue("same"))));
   var done=run(p,initial,true);assertEquals(new DataValue.BooleanValue(flag),done.locals().field("same"));String next=done.assembly().orElseThrow().image();if(image==null)image=next;else assertEquals(image,next);
  }
 }
 @Test void exactCodeCapacityAndLongPayloadHaveNoUnchargedDynamicRegister(){
  var p=simple(DataType.Scalar.TEXT,DataType.Scalar.TEXT,false);long words=p.usage(LIMITS.hardware()).programUnits();
  p.validate(new ResourceLimits(words,200000,1));assertEquals(ComputationException.Code.PROGRAM_LIMIT,assertThrows(ComputationException.class,()->p.validate(new ResourceLimits(words-1,200000,1))).code());
  var shortInput=new DataValue.RecordValue(Map.of("input",new DataValue.TextValue("x"),"ticks",new DataValue.IntegerValue(1)));
  var longInput=new DataValue.RecordValue(Map.of("input",new DataValue.TextValue("x".repeat(10000)),"ticks",new DataValue.IntegerValue(1)));
  var shortRun=run(p,shortInput,true);var longRun=run(p,longInput,true);assertEquals(shortRun.work(),longRun.work());assertTrue(longRun.controlUnits()>shortRun.controlUnits()+10000);
  assertEquals(longInput.field("input"),longRun.locals().field("input"));assertEquals(longInput.field("input"),longRun.locals().field("result"));
 }

 @Test void externalAndSharedAnyNeverReachSoftwareCarrierTransport(){
  var p=source();var inputs=new TreeMap<>(p.inputs());inputs.put("unused_open",DataType.Scalar.ANY);
  assertThrows(ComputationException.class,()->new FlowProgram(inputs,p.steps(),p.expressions(),p.execution(),p.compilation()).compile(LIMITS.hardware()));
  var definition=p.expressions().program();var cells=Map.of("shared",new ProgramDefinition.Cell(DataType.Scalar.ANY,new DataValue.IntegerValue(0)));
  var archive=new ProgramArchive(new ProgramDefinition(definition.graph(),cells,definition.writes(),definition.sends(),definition.entries()),Map.of());
  var steps=new ArrayList<>(p.steps());steps.add(1,new FlowProgram.Shared("public_leak","shared",new FlowInput.Local("dynamic"),false));
  var failure=assertThrows(ComputationException.class,()->new FlowProgram(p.inputs(),steps,archive,p.execution(),p.compilation()).compile(LIMITS.hardware()));assertTrue(failure.getMessage().contains("boundary"));
 }
 @Test void nativeEnvelopeValueExtractionIsDiagnosedNotReinterpreted(){
  var receive=com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.RECEIVE;
  var graph=new ComputationGraph(Map.of("packet",receive),Map.of("packet",new ComputationGraph.Input("packet"),"raw",new ComputationGraph.Field("packet","value")),Map.of("value","raw"));
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of("extract",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
  var p=new FlowProgram(Map.of(),List.of(new FlowProgram.SerialReceive("receive","serial","packet",false),new FlowProgram.Evaluate("extract","extract")),archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
  var error=assertThrows(ComputationException.class,()->p.compile(LIMITS.hardware()));assertEquals("extract",error.location());assertTrue(error.getMessage().contains("software ANY")||error.getMessage().contains("Software ANY"));
 }
 @Test void canonicalMarkerIdentityIsOrderedAndChecksPayloadKindAndBooleanRange(){
  var values=List.<DataValue>of(new DataValue.RecordValue(Map.of("kind",new DataValue.IntegerValue(1),"payload",new DataValue.NbtValue(8,new DataValue.TextValue("not integer")))),new DataValue.RecordValue(Map.of("kind",new DataValue.IntegerValue(2),"payload",new DataValue.NbtValue(1,new DataValue.IntegerValue(2)))));
  for(var value:values){var image=new RegisterProgram(List.of(),List.of(SoftwareAnyAbi.TYPE),List.of(),Map.of("value",0),Map.of("value",0),0,List.of(),Map.of(),Optional.empty(),Set.of(0));assertThrows(ComputationException.class,()->image.begin(new DataValue.RecordValue(Map.of("value",value))));}
  var valid=new DataValue.RecordValue(Map.of("kind",new DataValue.IntegerValue(0),"payload",new DataValue.NbtValue(0,DataValue.NullValue.INSTANCE)));
  var roots=new DataValue.RecordValue(Map.of("a",valid,"b",valid));String previous=null;
  for(var order:List.of(List.of(0,1),List.of(1,0))){var image=new RegisterProgram(List.of(),List.of(SoftwareAnyAbi.TYPE,SoftwareAnyAbi.TYPE),List.of(),Map.of("a",0,"b",1),Map.of("a",0,"b",1),0,List.of(),Map.of(),Optional.empty(),new LinkedHashSet<>(order));String identity=image.begin(roots).image();if(previous==null)previous=identity;else assertEquals(previous,identity);}
 }

 @Test void typedMultipleResultsPublishOnlyAfterEveryExtractionSucceeds(){
  var input=new DataValue.RecordValue(Map.of("a",new DataValue.IntegerValue(7),"b",new DataValue.IntegerValue(8),"text",new DataValue.TextValue("wrong"),"flag",new DataValue.BooleanValue(false)));
  var types=((DataType.RecordType)DataType.typeOf(input)).fields();
  var graph=new ComputationGraph(Map.of("text",DataType.Scalar.TEXT,"flag",DataType.Scalar.BOOLEAN),Map.of("text",new ComputationGraph.Input("text"),"flag",new ComputationGraph.Input("flag"),"nine",new ComputationGraph.Constant(new DataValue.IntegerValue(9)),"choice",new ComputationGraph.Scalar(ScalarOperation.SELECT,Map.of("condition","flag","when_true","nine","when_false","text"))),Map.of("a","nine","b","choice"));
  var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of("results",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
  var p=new FlowProgram(types,List.of(new FlowProgram.Evaluate("consume","results")),archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);
  var host=new ComputerBlockHost(()->{});host.installFlow(p,input,LIMITS,true);host.setPaused(false,LIMITS,true);
  for(int tick=0;tick<1000;tick++) {host.tick(LIMITS,true);var state=host.flowSnapshot().orElseThrow().state();if(state.status()==FlowRuntime.Status.FAILED){assertEquals(ComputationException.Code.INVALID_ARGUMENT,state.diagnostic().orElseThrow().code());assertEquals(input.field("a"),state.locals().field("a"));assertEquals(input.field("b"),state.locals().field("b"));return;}}
  fail("expected checked consumer failure");
 }

 @Test void directAnyTaskArgumentIsAnUnsupportedBoundaryEvenWithConcreteTarget(){
  var p=source();var steps=new ArrayList<>(p.steps());steps.add(1,new FlowProgram.TaskStart("task_leak",key("task"),Map.of("value","dynamic"),"handle"));
  steps.add(new FlowProgram.Procedure("task_definition",key("task"),Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value")));steps.add(new FlowProgram.EndProcedure("task_end"));
  var error=assertThrows(ComputationException.class,()->new FlowProgram(p.inputs(),steps,p.expressions(),p.execution(),p.compilation()).compile(LIMITS.hardware()));assertEquals("task_leak",error.location());assertTrue(error.getMessage().contains("task"));
 }
 @Test void actualSoftwareBooleanExtractionAndDifferentNativePayloadShapesRemainLossless(){
  var base=choice(DataType.Scalar.BOOLEAN,DataType.Scalar.NULL,ScalarOperation.IS_NULL);var steps=new ArrayList<>(base.steps());steps.add(2,call("consume_bool","boolean_consumer",Map.of("value","dynamic"),Map.of("result","boolean_result")));
  steps.add(new FlowProgram.Procedure("boolean_definition",key("boolean_consumer"),Map.of("value",DataType.Scalar.ANY),Map.of("result",DataType.Scalar.BOOLEAN),Map.of("result","value")));steps.add(new FlowProgram.EndProcedure("boolean_end"));
  var p=new FlowProgram(base.inputs(),steps,base.expressions(),base.execution(),base.compilation());String image=null;
  for(boolean bit:List.of(false,true)){
   var initial=new DataValue.RecordValue(Map.of("first",new DataValue.BooleanValue(bit),"second",DataValue.NullValue.INSTANCE,"flag",new DataValue.BooleanValue(false),"ticks",new DataValue.IntegerValue(1)));
   var done=run(p,initial,true);assertEquals(new DataValue.BooleanValue(bit),done.locals().field("boolean_result"));String next=done.assembly().orElseThrow().image();if(image==null)image=next;else assertEquals(image,next);
  }
  var nativeProgram=simple(DataType.Scalar.NBT,DataType.Scalar.NBT,false);image=null;
  for(var nativeValue:List.of(new DataValue.NbtValue(5,new DataValue.IntegerValue(0x7fc01234)),new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of("unknown",new DataValue.NbtValue(9,new DataValue.ListValue(List.of(new DataValue.NbtValue(8,new DataValue.TextValue("variable")))))))))){
   var initial=new DataValue.RecordValue(Map.of("input",nativeValue,"ticks",new DataValue.IntegerValue(1)));var done=run(nativeProgram,initial,true);assertEquals(nativeValue,done.locals().field("result"));assertEquals(nativeValue,done.locals().field("input"));String next=done.assembly().orElseThrow().image();if(image==null)image=next;else assertEquals(image,next);
  }
 }
 @Test void parserAndNativeGatesRemainDistinctAndNoSourcePolicyUpgradeOccurs(){
  var software=simple(DataType.Scalar.TEXT,DataType.Scalar.TEXT,false);var input=new DataValue.RecordValue(Map.of("input",new DataValue.TextValue("text"),"ticks",new DataValue.IntegerValue(1)));
  var noParser=new ComputerHostLimits(LIMITS.hardware(),ARCHIVE);assertThrows(FlowPins.Unavailable.class,()->new ComputerBlockHost(()->{}).installFlow(software,input,noParser,true));
  var originalBase=simple(DataType.Scalar.ANY,DataType.Scalar.ANY,false);var nativeDefinition=(FlowProgram.Procedure)originalBase.steps().get(1);
  var nativeSteps=List.<FlowProgram.Step>of(originalBase.steps().getFirst(),new FlowProgram.Procedure(nativeDefinition.sourceId(),nativeDefinition.key(),nativeDefinition.inputs(),nativeDefinition.outputs(),nativeDefinition.returns(),nativeDefinition.rules(),Optional.of(new AssemblySource("MOV value value"))),new FlowProgram.EndProcedure("return"));
  var original=new FlowProgram(originalBase.inputs(),nativeSteps,originalBase.expressions(),originalBase.execution(),FlowProgram.Compilation.ORIGINAL);
  assertThrows(FlowPins.Unavailable.class,()->new ComputerBlockHost(()->{}).installFlow(original,input,LIMITS,true));
  var master=new ComputerHostLimits(LIMITS.hardware(),ARCHIVE,LIMITS.mailbox(),LIMITS.pins(),LIMITS.serial(),4,true,true);var owner=new ComputerBlockHost(()->{});owner.installFlow(original,input,master,true);assertEquals(FlowProgram.Compilation.ORIGINAL,owner.flowSnapshot().orElseThrow().program().compilation());
  var doc=new ProgramDocument(software.expressions(),Map.of(),Optional.empty(),Optional.of(FlowDocumentBody.fromProgram(software,input)));var encoded=(DataValue.RecordValue)ProgramDocumentCodec.encode(doc);assertEquals(new DataValue.IntegerValue(33),encoded.field("version"));
  var fields=new TreeMap<>(encoded.fields());fields.put("version",new DataValue.IntegerValue(32));assertThrows(RuntimeException.class,()->ProgramDocumentCodec.decode(new DataValue.RecordValue(fields)));
 }

 @Test void independentClosedIdentityCannotHideOpenMainSelectOrIntermediateNode(){
  for(boolean exposeAny:List.of(false,true)){
   var inputs=Map.<String,DataType>of("number",DataType.Scalar.INTEGER,"flag",DataType.Scalar.BOOLEAN,"text",DataType.Scalar.TEXT,"native",DataType.Scalar.NBT);
   var nodes=Map.<String,ComputationGraph.Node>of("flag",new ComputationGraph.Input("flag"),"text",new ComputationGraph.Input("text"),"native",new ComputationGraph.Input("native"),"select",new ComputationGraph.Scalar(ScalarOperation.SELECT,Map.of("condition","flag","when_true","text","when_false","native")),"same",new ComputationGraph.ValueEquals("select","text"));
   var graph=new ComputationGraph(Map.of("flag",DataType.Scalar.BOOLEAN,"text",DataType.Scalar.TEXT,"native",DataType.Scalar.NBT),nodes,exposeAny?Map.of("selected","select","same","same"):Map.of("same","same"));
   var archive=new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),Map.of(),Map.of()),Map.of(),List.of(),List.of(),Map.of("select",new ProgramDefinition.Entry(graph,List.of(),List.of()))),Map.of());
   var steps=List.<FlowProgram.Step>of(new FlowProgram.Evaluate("select","select"),call("identity_call","identity",Map.of("value","number"),Map.of("result","identity_result")),new FlowProgram.Procedure("identity_definition",key("identity"),Map.of("value",DataType.Scalar.ANY),Map.of("result",DataType.Scalar.ANY),Map.of("result","value")),new FlowProgram.EndProcedure("identity_end"));
   var p=new FlowProgram(inputs,steps,archive,FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.SOFTWARE_V1);String image=null;
   for(boolean flag:List.of(false,true)) {
    var input=new DataValue.RecordValue(Map.of("number",new DataValue.IntegerValue(42),"flag",new DataValue.BooleanValue(flag),"text",new DataValue.TextValue("same"),"native",new DataValue.NbtValue(8,new DataValue.TextValue("same"))));
    var done=run(p,input,true);assertEquals(new DataValue.BooleanValue(flag),done.locals().field("same"));assertEquals(new DataValue.IntegerValue(42),p.displayLocals(done.assembly().orElseThrow()).field("identity_result"));
    String next=done.assembly().orElseThrow().image();if(image==null)image=next;else assertEquals(image,next);
   }
  }
 }

}
