package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
class SoftwareAnyEditorTest {
 private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
 @BeforeAll static void bootstrapClient() throws Exception {ComputerProgramScreenTest.bootstrap();}
 @BeforeEach void client() throws Exception {environment.client();}
 @AfterEach void restoreClient() throws Exception {environment.restore();}
 static final ArchiveLimits ARCHIVE=new ArchiveLimits(100000,1000000,1000000);
 static ProgramDocument fixture(){
  var base=new ProgramWorkspace();var key=new ModuleDefinition.Key("widen",1);
  var definition=new FlowProgram.Procedure("definition",key,Map.of("value",DataType.Scalar.ANY,"ticks",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.TEXT),Map.of("result","value"));
  var assignment=new FlowProgram.Evaluate("assignment","assignment",new FlowCallBindings(Map.of(),Map.of("value","value")));
  var graph=new ComputationGraph(Map.of(),Map.of("value",new ComputationGraph.Constant(new DataValue.TextValue("hello"))),Map.of("value","value"));
  var initial=new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(42),"ticks",new DataValue.IntegerValue(200)));
  var layout=new FlowBlockLayout(FlowBlockLayout.DEFAULT.main(),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),List.of(definition,assignment,new FlowProgram.Sleep("sleep","ticks"),new FlowProgram.EndProcedure("end")))));
  var body=new FlowDocumentBody(Map.of("seed",DataType.Scalar.INTEGER,"ticks",DataType.Scalar.INTEGER),List.of(new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of("value","seed","ticks","ticks"),Map.of("result","out")))),initial,layout,Map.of("assignment",graph),FlowProgram.Execution.REGISTER_V1,FlowProgram.Compilation.parse("software_v1"));
  base.setFlow(Optional.of(body));return base.snapshot();
 }
 @Test void ordinaryPrivateAssignmentEditsStickyAnyAndDefaultBudgetOwnerPresentsLogicalRoot() throws Exception {
  var workspace=new ProgramWorkspace(fixture());var editor=FlowCallScreenTest.editor(workspace,640);
  var before=workspace.snapshot();set(editor,"selected","assignment");
  // The same slot edit used by a player, not authored assembly or an inferred sample type.
  var label=((FlowBlockCanvas)get(editor,"canvas")).inlineSlots().stream().filter(s->s.call().equals("assignment")&&!s.target()).findFirst().orElseThrow().label();var slot=FlowRequiredValueScreenTest.visibleSlot(editor,label,0);FlowInlineScreenTest.setLiteral(editor,slot,"hello world");
  var document=workspace.snapshot();assertNotEquals(before,document);
  var limits=NativeLibraryOwnersTest.limits("cleric",4);var body=document.flow().orElseThrow();var program=body.program(document.archive());
  assertEquals(DataType.Scalar.ANY,program.typesBefore("sleep",limits.hardware()).get("value"));
  var host=new ComputerBlockHost(()->{});host.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);host.setPaused(false,limits,true);
  long peak=0,sleepWork=-1;int sleepPc=-1;boolean slept=false;
  for(int i=0;i<2000;i++){
   host.tick(limits,true);var saved=host.flowSnapshot().orElseThrow();var state=saved.state();peak=Math.max(peak,state.controlUnits());
   if(state.sleepTicks()>0){slept=true;if(sleepWork<0){sleepWork=state.work();sleepPc=state.assembly().orElseThrow().pc();}assertFalse(ComputerStatusView.flow(Optional.of(saved),false,Optional.empty()).fields().containsKey("locals"));var observed=ComputerStatusView.flow(Optional.of(saved),true,Optional.empty());assertEquals(new DataValue.TextValue("hello world"),((DataValue.RecordValue)observed.field("locals")).field("value"));assertInstanceOf(DataValue.RecordValue.class,state.locals().field("value"));}
   assertNotEquals(FlowRuntime.Status.FAILED,state.status(),()->state.diagnostic().toString());
   if(state.status()==FlowRuntime.Status.COMPLETE){assertEquals(new DataValue.TextValue("hello world"),state.locals().field("out"));assertEquals(new DataValue.IntegerValue(42),state.locals().field("seed"));assertTrue(slept);assertEquals(133,program.usage(limits.hardware()).programUnits());assertEquals(34,state.work());
    var dir=java.nio.file.Path.of(".superpowers/cache/software-any-abi");java.nio.file.Files.createDirectories(dir);java.nio.file.Files.write(dir.resolve("player-program.bcp"),ProgramDocumentWire.encode(document,ARCHIVE));
    long low=1,high=limits.hardware().memoryUnits();while(low<high){long middle=(low+high)/2;if(admitted(document,limits,middle))high=middle;else low=middle+1;}assertTrue(admitted(document,limits,low));assertFalse(admitted(document,limits,low-1));assertEquals(649,low);
    java.nio.file.Files.writeString(dir.resolve("player-measurement.txt"),"code="+program.usage(limits.hardware()).programUnits()+" work="+state.work()+" peak_checkpoint="+peak+" minimum_owner_ram="+low+" sleep_work="+sleepWork+" sleep_pc="+sleepPc+" limits="+limits.hardware());return;}
   var cold=new ComputerBlockHost(()->{});cold.read(host.write());cold.setPaused(false,limits,true);assertEquals(saved,cold.flowSnapshot().orElseThrow());host=cold;
  }fail("not complete");
 }
 static boolean admitted(ProgramDocument document,ComputerHostLimits base,long ram){
  var limits=new ComputerHostLimits(new ResourceLimits(base.hardware().programUnits(),ram,1),base.archive(),base.mailbox(),base.pins(),base.serial(),base.taskSlots(),true,false);
  try {
   var owner=new ComputerBlockHost(()->{});owner.installProgram(PreparedProgram.prepare(document,limits),ComputerRuntime.MemoryPolicy.RESET,limits,true);owner.setPaused(false,limits,true);
   for(int i=0;i<1000;i++) {
    owner.tick(limits,true);var state=owner.flowSnapshot().orElseThrow().state();
    if(state.status()==FlowRuntime.Status.COMPLETE)return true;
    if(state.status()==FlowRuntime.Status.FAILED){assertEquals(ComputationException.Code.MEMORY_LIMIT,state.diagnostic().orElseThrow().code());
     var image=FlowXBus.image(document.flow().orElseThrow().program(document.archive()));var registers=state.assembly().orElseThrow().registers();
     image.softwareAnyRegisters().forEach(r->{if(registers.containsKey(r))assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.SoftwareAnyAbi.canonical(registers.get(r)));});return false;}
   }throw new AssertionError("owner did not finish");
  }catch(ComputationException error){assertEquals(ComputationException.Code.MEMORY_LIMIT,error.code());return false;}
   catch(ComputerBlockHost.HostException error){assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT,error.code());return false;}
 }

}
