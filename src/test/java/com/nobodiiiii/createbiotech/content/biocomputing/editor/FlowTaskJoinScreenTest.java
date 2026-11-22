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
import org.junit.jupiter.api.*;
class FlowTaskJoinScreenTest {
 private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
 @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
 @BeforeEach void client() throws Exception { environment.client(); }
 @AfterEach void restoreClient() throws Exception { environment.restore(); }
 @Test void legacyCatalogOmitsJoinAndBothLocalesDescribeOnceClaimAndExplicitExtraction() throws Exception {
  var workspace=new ProgramWorkspace(); var definition=FlowProcedureDocumentTest.definition(); var layout=new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),List.of(definition,new FlowProgram.EndProcedure("end")))));
  workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of(),List.of(),new DataValue.RecordValue(Map.of()),layout)));
  var editor=FlowCallScreenTest.editor(workspace,640); click(editor,"category_custom"); @SuppressWarnings("unchecked")var entries=(List<FlowBlockCatalog.Entry>)get(editor,"catalogEntries"); assertTrue(entries.stream().noneMatch(FlowBlockCatalog.Entry::taskJoin));
  for(String locale:List.of("en_us","zh_cn")) { var labels=ComputerUiTranslationsTest.load(locale); for(String key:List.of("block_task_join","add_task_join","task_join_title","task_handle_input","task_outcome","task_join_hint"))assertFalse(labels.get("create_biotech.biocomputing.flow_editor."+key).isBlank()); var hint=labels.get("create_biotech.biocomputing.flow_editor.task_join_hint"); assertTrue(hint.contains("ok")); assertTrue(locale.equals("en_us")?hint.contains("Require value"):hint.contains("取非空值")); }
 }
 @Test void taskWaitingHasAReadableDebugReasonAndRemainsPendingWithoutPrivateResultObservation() {
  var key=new ModuleDefinition.Key("child",1); var types=Map.<String,DataType>of("delay",DataType.Scalar.INTEGER);
  var p=new FlowProgram(types,List.of(new FlowProgram.TaskStart("start",key,Map.of("delay","delay"),"h"),new FlowProgram.TaskJoin("join",key,"h","result"),
   new FlowProgram.Procedure("child",key,types,Map.of(),Map.of()),new FlowProgram.Sleep("sleep","delay"),new FlowProgram.EndProcedure("return"))).withAssembly();
  var limits=new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000,100000,1),SubgraphWorkspaceTest.LIMITS,com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,com.nobodiiiii.createbiotech.content.biocomputing.hardware.HardwarePins.DEFAULT,com.nobodiiiii.createbiotech.content.biocomputing.hardware.SerialHardware.NONE,3);
  var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{}); owner.installFlow(p,new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(3))),limits,true);
  for(int i=0;i<10 && owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.WAITING_TASK;i++)owner.step(limits,true);
  var debug=ComputerStatusView.flow(owner.flowSnapshot(),owner.loaded(),owner.diagnostic()); assertEquals(new DataValue.TextValue("waiting_task"),debug.field("wait_reason"));
  var terminal=com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.decode(com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView.ofFlowOwner(Optional.empty(),owner.flowSnapshot()));
  var text=new com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition.Text(new com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalBinding.Path(new ValuePath("output",List.of()).key("result")));
  var definition=new com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalDefinition("root",Map.of("root",text));
  var projected=com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalProjection.project(new com.nobodiiiii.createbiotech.content.biocomputing.terminal.TerminalStatusView(Optional.of(definition),terminal.roots(),terminal.phase(),terminal.paused()),Map.of(),1,1);
  assertEquals("output_pending",projected.problem().orElseThrow().code()); assertEquals(DataValue.NullValue.INSTANCE,terminal.roots().field("output"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
 void realScratchJoinPrototypeAndTwoBindingsRoundTripAndUndoWithoutInstallation(int width) throws Exception {
  var workspace=new ProgramWorkspace(); var definition=FlowProcedureDocumentTest.definition();
  var initial=new DataValue.RecordValue(Map.of("delay",new DataValue.IntegerValue(0)));
  var body=new FlowDocumentBody(Map.of("delay",DataType.Scalar.INTEGER),List.of(),initial,
   new FlowBlockLayout(new ProgramDocument.Position(16,16),List.of(new FlowBlockLayout.Stack(new ProgramDocument.Position(280,16),
    List.of(definition,new FlowProgram.EndProcedure("end"))))),Map.of(),FlowProgram.Execution.REGISTER_V1);
  workspace.setFlow(Optional.of(body)); var editor=FlowCallScreenTest.editor(workspace,width); click(editor,"category_custom");
  @SuppressWarnings("unchecked") var entries=(List<FlowBlockCatalog.Entry>)get(editor,"catalogEntries");
  assertTrue(entries.stream().anyMatch(e->e.id().startsWith("join:")),"Join must sit beside custom launch/call");
  FlowPaletteScreenTest.search(editor).setValue("join:");
  var prototype=button(editor,"block_task_join"); var canvas=get(editor,"canvas");
  double fx=prototype.getX()+10,fy=prototype.getY()+10,x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
  var before=workspace.snapshot(); editor.mouseClicked(fx,fy,0); editor.mouseDragged(x,y,0,x-fx,y-fy); assertEquals(before,workspace.snapshot()); editor.mouseReleased(x,y,0);
  assertInstanceOf(FlowProgram.TaskJoin.class,workspace.flow().orElseThrow().steps().getFirst());
  var navigation=new AtomicReference<Screen>(); set(editor,"navigator",(Consumer<Screen>)navigation::set); click(editor,"edit");
  var form=navigation.get(); FlowProcedureScreenTest.initializeForm(form,width); bounds(form);
  assertEquals(4,boxes(form).size()); boxes(form).get(2).setValue("child_ticket"); boxes(form).get(3).setValue("child_result"); click(form,"procedure_apply");
  var joined=(FlowProgram.TaskJoin)workspace.flow().orElseThrow().steps().getFirst(); assertEquals("child_ticket",joined.handle()); assertEquals("child_result",joined.outcome());
  var document=workspace.snapshot(); assertEquals(new DataValue.IntegerValue(26),ProgramDocumentCodec.encode(document).field("version"));
  assertEquals(document,ProgramDocumentWire.decode(ProgramDocumentWire.encode(document,SubgraphWorkspaceTest.LIMITS),SubgraphWorkspaceTest.LIMITS));
  click(editor,"undo"); assertNotEquals(joined,workspace.flow().orElseThrow().steps().getFirst());
 }
}
