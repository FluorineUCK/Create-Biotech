package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.MultiLineEditBox;
import org.junit.jupiter.api.Test;
class FlowAuthoredProcedureScreenTest {
 private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
 @org.junit.jupiter.api.BeforeAll static void bootstrapClient()throws Exception{ComputerProgramScreenTest.bootstrap();}
 @org.junit.jupiter.api.BeforeEach void client()throws Exception{environment.client();set(net.minecraft.client.Minecraft.getInstance(),"font",new Metrics(){@Override public net.minecraft.client.StringSplitter getSplitter(){return new net.minecraft.client.StringSplitter((codepoint,style)->1f);}});}
 @org.junit.jupiter.api.AfterEach void restoreClient()throws Exception{environment.restore();}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
 void playerExplicitlyAppliesMultilineAssemblyOrCancelsWithoutInstalling(int width)throws Exception{
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
  var editor=FlowCallScreenTest.editor(workspace,width);var navigation=new AtomicReference<Screen>();set(editor,"navigator",(Consumer<Screen>)navigation::set);
  click(editor,"category_custom");click(editor,"define_procedure");var form=navigation.get();FlowProcedureScreenTest.initializeForm(form,width);
  boxes(form).getFirst().setValue("c");click(form,"procedure_implementation");bounds(form);click(form,"implementation_scratch");
  var input=source(form);input.setValue("REG one INTEGER\nCONST one 1\nADD_INTEGER value value one\nRET");
  var before=workspace.snapshot();assertTrue(before.flow().orElseThrow().canvas().detached().isEmpty());click(form,"procedure_apply");
  var declaration=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();assertTrue(declaration.assembly().isPresent());
  assertEquals(input.getValue(),declaration.assembly().orElseThrow().text());assertEquals(Map.of("result","value"),declaration.returns());
  assertFalse((boolean)get(editor,"waiting"));assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
  initialize(editor,width);click(editor,"edit");form=navigation.get();FlowProcedureScreenTest.initializeForm(form,width);click(form,"procedure_implementation");
  var saved=workspace.snapshot();source(form).setValue("MOV value caller_secret\nRET");click(form,"procedure_apply");assertEquals(saved,workspace.snapshot());assertSame(form,navigation.get());
  click(form,"cancel");assertEquals(saved,workspace.snapshot());assertSame(editor,navigation.get());
 }
 static MultiLineEditBox source(Screen form){return form.children().stream().filter(MultiLineEditBox.class::isInstance).map(MultiLineEditBox.class::cast).findFirst().orElseThrow();}
 @Test void explicitV8ImplementationSurvivesSourceInstallAndTwoSleepingReloads() throws Exception {
  var assembly = """
      REG dynamic ANY INTEGER BOOLEAN
      REG value INTEGER
      REG flag BOOLEAN
      REG saved INTEGER
      REG result BOOLEAN
      REG one INTEGER
      CONST value 73
      CONST flag true
      CONST saved -1
      CONST result false
      CONST one 1
      ANY.SET dynamic value
      SLEEP one
      ANY.IS result dynamic INTEGER
      JFALSE result failed
      ANY.GET saved dynamic failed
      ANY.SET dynamic flag
      SLEEP one
      ANY.GET result dynamic failed
      RET
      failed:
      CONST result false
      RET""";
  var key = new ModuleDefinition.Key("software_any",1);
  var def = new FlowProgram.Procedure("definition",key,Map.of(),
      Map.of("value",DataType.Scalar.INTEGER,"ok",DataType.Scalar.BOOLEAN),Map.of("value","saved","ok","result"),
      FlowBlockRules.NONE,Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("RET")));
  var p = new FlowProgram(Map.of(),List.of(
      new FlowProgram.Invoke("call",key,new FlowCallBindings(Map.of(),Map.of("value","integer_result","ok","boolean_result"))),
      def,new FlowProgram.EndProcedure("end"))).withAssembly();
  var workspace = new ProgramWorkspace(); workspace.setFlow(Optional.of(FlowDocumentBody.fromProgram(p,new DataValue.RecordValue(Map.of()))));
  var editor = FlowCallScreenTest.editor(workspace,640);
  var draft = new FlowDraft(workspace.flow().orElseThrow());
  var form = new ComputerFlowProcedureScreen((ComputerProgramMenu)get(editor,"menu"),
      (net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),net.minecraft.network.chat.Component.empty(),
      def.sourceId(),def,null,step->{
       FlowProcedureTools.define(draft,(FlowProgram.Procedure)step,true);
       workspace.setFlow(Optional.of(draft.snapshot()));
      },()->{});
  FlowProcedureScreenTest.initializeForm(form,640); click(form,"procedure_implementation");
  source(form).setValue(assembly); click(form,"procedure_apply");
  var saved = workspace.snapshot();
  var savedSource = FlowProcedureTools.definitions(saved.flow().orElseThrow()).getFirst().assembly().orElseThrow();
  assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,savedSource.version()); assertEquals(assembly,savedSource.text());
  var archive = new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,1000000);
  var wire = ProgramDocumentWire.decode(ProgramDocumentWire.encode(saved,archive),archive);
  assertEquals(saved,wire); assertEquals(saved,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(wire)));
  var limits = new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(4096,100000,1),archive);
  assertFalse(limits.isa1a()); assertFalse(limits.isa1b());
  var host = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
  var prepared = com.nobodiiiii.createbiotech.content.biocomputing.computer.PreparedProgram.prepare(wire,limits);
  host.installProgram(prepared,com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime.MemoryPolicy.RESET,limits,true);
  host.setPaused(false,limits,true);
  int restores = 0;
  for (int i=0;i<150;i++) {
   host.tick(limits,true);
   var snapshot = host.flowSnapshot().orElseThrow();
   if (snapshot.state().status()==FlowRuntime.Status.SLEEPING && snapshot.state().sleepTicks()==1) {
    var restored = new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});
    restored.read(host.write()); restored.setPaused(false,limits,true);
    assertEquals(snapshot,restored.flowSnapshot().orElseThrow());
    host=restored; restores++;
   }
   if (snapshot.state().status()==FlowRuntime.Status.COMPLETE) break;
  }
  var done = host.flowSnapshot().orElseThrow().state();
  assertEquals(2,restores); assertEquals(FlowRuntime.Status.COMPLETE,done.status());
  assertEquals(new DataValue.IntegerValue(73),done.locals().field("integer_result"));
  assertEquals(new DataValue.BooleanValue(true),done.locals().field("boolean_result"));
 }

 @Test void nativeBodiesRejectVisualInsertionAndOptInCannotDiscardAVisualBody() {
  var key=new ModuleDefinition.Key("c",1);var declaration=new FlowProgram.Procedure("c",key,Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value"),FlowBlockRules.NONE,Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("RET")));
  var nativeBody=new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),List.of(declaration,new FlowProgram.EndProcedure("end"))))),Map.of(),FlowProgram.Execution.REGISTER_V1);
  var draft=new FlowDraft(nativeBody);var nativeDraft=draft;assertThrows(IllegalArgumentException.class,()->nativeDraft.insertAfter("c",new FlowProgram.Sleep("wait","value")));assertEquals(nativeBody,draft.snapshot());
  var visual=new FlowProgram.Procedure("c",key,declaration.inputs(),declaration.outputs(),declaration.returns());
  var visualBody=new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),List.of(visual,new FlowProgram.Sleep("wait","value"),new FlowProgram.EndProcedure("end"))))),Map.of(),FlowProgram.Execution.REGISTER_V1);
  draft=new FlowDraft(visualBody);var target=draft;assertThrows(IllegalArgumentException.class,()->FlowProcedureTools.define(target,declaration,true));assertEquals(visualBody,draft.snapshot());
 }

 @Test void sourceSurvivesSignatureRulesMoveUndoAndDocumentWireExport()throws Exception{
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));var editor=FlowCallScreenTest.editor(workspace,640);var nav=new AtomicReference<Screen>();set(editor,"navigator",(Consumer<Screen>)nav::set);
  click(editor,"category_custom");click(editor,"define_procedure");var form=nav.get();FlowProcedureScreenTest.initializeForm(form,640);click(form,"procedure_implementation");click(form,"implementation_scratch");var source=source(form).getValue();click(form,"procedure_apply");initialize(editor,640);
  click(editor,"edit");form=nav.get();FlowProcedureScreenTest.initializeForm(form,640);click(form,"procedure_rules");click(form,"rule_allowed");click(form,"procedure_apply");
  var def=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();assertEquals(source,def.assembly().orElseThrow().text());assertEquals(Set.of("device_wait"),def.rules().forbidInside());
  var draft=new FlowDraft(workspace.flow().orElseThrow());var before=draft.snapshot();draft.drop(def.sourceId(),null,new ProgramDocument.Position(390,180));assertEquals(def,FlowProcedureTools.definitions(draft.snapshot()).getFirst());draft.undo();assertEquals(before,draft.snapshot());
  var limits=new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(100000,1000000,1000000);var document=workspace.snapshot();assertEquals(document,ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,limits),limits));
  assertFalse((boolean)get(editor,"waiting"));
 }

 @Test void aPlayerDragsAndExplicitlyBindsTheAuthoredCustomBlockThenGets42()throws Exception{
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("seed",DataType.Scalar.INTEGER),List.of(),new DataValue.RecordValue(Map.of("seed",new DataValue.IntegerValue(41))),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
  var editor=FlowCallScreenTest.editor(workspace,640);var nav=new AtomicReference<Screen>();set(editor,"navigator",(Consumer<Screen>)nav::set);click(editor,"category_custom");click(editor,"define_procedure");var form=nav.get();FlowProcedureScreenTest.initializeForm(form,640);boxes(form).getFirst().setValue("c");click(form,"procedure_implementation");click(form,"implementation_scratch");click(form,"procedure_apply");initialize(editor,640);
  click(editor,"reset_view");FlowPaletteScreenTest.search(editor).setValue("bind:");var prototype=button(editor,"block_bind_invoke");var canvas=get(editor,"canvas");double x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
  editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0);editor.mouseDragged(x,y,0,10,10);editor.mouseReleased(x,y,0);
  var call=assertInstanceOf(FlowProgram.Invoke.class,workspace.flow().orElseThrow().steps().getFirst());assertTrue(call.bindings().arguments().isEmpty());
  click(editor,"edit");form=nav.get();FlowProcedureScreenTest.initializeForm(form,640);boxes(form).getLast().setValue("seed");click(form,"procedure_outputs");boxes(form).getLast().setValue("answer");click(form,"procedure_apply");
  var document=workspace.snapshot();var p=document.flow().orElseThrow().program(document.archive());var limits=new ResourceLimits(32,100000,1);assertEquals(27,p.usage(limits).programUnits());
  var r=new FlowRuntime(UUID.randomUUID(),UUID.randomUUID(),p,document.flow().orElseThrow().initial(),limits);for(int i=0;r.checkpoint().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<30);r.advance(1);}assertEquals(new DataValue.IntegerValue(42),r.checkpoint().locals().field("answer"));assertFalse((boolean)get(editor,"waiting"));
 }


 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
 void existingDefinitionUsesCurrentDeviceSyntaxWithoutAnUpgradeStep(int width)throws Exception{
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));var editor=FlowCallScreenTest.editor(workspace,width);
  var def=new FlowProgram.Procedure("old",new ModuleDefinition.Key("old",1),Map.of("value",DataType.Scalar.INTEGER),Map.of("result",DataType.Scalar.INTEGER),Map.of("result","value"),FlowBlockRules.NONE,Optional.of(new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource("RET ; unchanged bytes")));
  var applied=new AtomicReference<FlowProgram.Step>();var form=new ComputerFlowProcedureScreen((ComputerProgramMenu)get(editor,"menu"),(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),net.minecraft.network.chat.Component.empty(),"old",def,null,applied::set,()->{});
  FlowProcedureScreenTest.initializeForm(form,width);click(form,"procedure_implementation");bounds(form);click(form,"procedure_apply");assertEquals(def,applied.get(),"Opening/applying preserves source and identity");
  applied.set(null);source(form).setValue("SIO.READ value p0\nRET");click(form,"procedure_apply");var upgraded=assertInstanceOf(FlowProgram.Procedure.class,applied.get());
  assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,upgraded.assembly().orElseThrow().version());assertEquals("SIO.READ value p0\nRET",upgraded.assembly().orElseThrow().text());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={23,2,3,4,5,6,7})
 void obsoleteSourcesCannotEnterTheEditor(int version){
  assertThrows(IllegalArgumentException.class,()->new com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource(version,"RET"));
 }
}
