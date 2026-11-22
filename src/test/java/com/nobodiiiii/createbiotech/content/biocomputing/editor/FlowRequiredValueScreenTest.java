package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.*;
class FlowRequiredValueScreenTest {
 private final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
 @BeforeAll static void bootstrapClient() throws Exception { ComputerProgramScreenTest.bootstrap(); }
 @BeforeEach void client() throws Exception { environment.client(); }
 @AfterEach void restoreClient() throws Exception { environment.restore(); }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
 void ordinaryDataReporterNestedDropInfersPrivateRecordFieldsAndUndoRestoresDraft(int width) throws Exception {
  var record=new DataType.RecordType(Map.of("answer",DataType.Scalar.INTEGER));var workspace=new ProgramWorkspace();
  workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("packet",new DataType.OptionalType(record)),List.of(),new DataValue.RecordValue(Map.of("packet",new DataValue.RecordValue(Map.of("answer",new DataValue.IntegerValue(41))))),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
  var editor=FlowCallScreenTest.editor(workspace,width);click(editor,"category_variables");click(editor,"add_assign");var before=workspace.snapshot();
  var entries=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"require_value");assertEquals(1,entries.size());assertEquals(FlowBlockPalette.Category.DATA,entries.getFirst().category());
  ((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("require_value");
  var reporter=button(editor,"inline_require_value");var slot=visibleSlot(editor,"0",0);editor.mouseClicked(reporter.getX()+4,reporter.getY()+4,0);editor.mouseDragged(slot.x()+4,slot.y()+4,0,20,20);editor.mouseReleased(slot.x()+4,slot.y()+4,0);
  ((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("");FlowInlineScreenTest.dropPrivateVariable(editor,"packet",visibleSlot(editor,"0",0));
  var doc=workspace.snapshot();assertEquals(doc,ProgramDocumentWire.decode(ProgramDocumentWire.encode(doc,SubgraphWorkspaceTest.LIMITS),SubgraphWorkspaceTest.LIMITS));
  var steps=new ArrayList<FlowProgram.Step>(doc.flow().orElseThrow().program(doc.archive()).steps());steps.add(new FlowProgram.Evaluate("probe",((FlowProgram.Evaluate)steps.getFirst()).expression(),new FlowCallBindings(((FlowProgram.Evaluate)steps.getFirst()).bindings().orElseThrow().arguments(),Map.of("value","probe_value"))));var probe=new FlowProgram(doc.flow().orElseThrow().inputs(),steps,doc.flow().orElseThrow().program(doc.archive()).expressions()).withAssembly();var types=probe.typesBefore("probe",new ResourceLimits(1000,100000,1));assertEquals(record,types.get("block_1_value"));
  assertTrue(FlowBlockCatalog.entries(types).stream().anyMatch(e->e.value() instanceof InlineGraphEdits.Variable v&&v.name().equals("block_1_value")&&v.path().equals(List.of("answer"))));
  assertEquals(new DataValue.RecordValue(Map.of("answer",new DataValue.IntegerValue(41))),FlowInlineScreenTest.run(doc).field("block_1_value"));
  click(editor,"undo");click(editor,"undo");assertEquals(before,workspace.snapshot());click(editor,"redo");click(editor,"redo");
  click(editor,"category_variables");click(editor,"add_assign");FlowInlineScreenTest.dropReporter(editor,"inline_add_integer",visibleSlot(editor,"0",0));
  ((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("answer");
  @SuppressWarnings("unchecked")var reporters=(Map<com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton,InlineGraphEdits.Source>)get(editor,"reporters");
  var field=reporters.entrySet().stream().filter(e->e.getValue() instanceof InlineGraphEdits.Variable v&&v.path().equals(List.of("answer"))).findFirst().orElseThrow().getKey();
  var left=visibleSlot(editor,"0",0);editor.mouseClicked(field.getX()+8,field.getY()+8,0);editor.mouseDragged(left.x()+8,left.y()+left.height()/2,0,40,0);editor.mouseReleased(left.x()+8,left.y()+left.height()/2,0);
  ((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("");FlowInlineScreenTest.setLiteral(editor,visibleSlot(editor,"0",0),"1");
  var calculated=workspace.snapshot();assertEquals(new DataValue.IntegerValue(42),FlowInlineScreenTest.run(calculated).field("block_2_value"));
  var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});var limits=ParserHardwareFixture.withIsa1a(new com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits(new ResourceLimits(1000,100000,1),SubgraphWorkspaceTest.LIMITS));
  var body=calculated.flow().orElseThrow();owner.installFlow(body.program(calculated.archive()),body.initial(),limits,true);var installed=owner.write();
  // Existing Apply/Cancel literal responder paths remain independent of the installed program.
  var one=visibleSlot(editor,"1",0);editor.mouseClicked(one.x()+8,one.y()+one.height()/2,0);boxes(editor).getFirst().setValue("7");editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);assertEquals(calculated,workspace.snapshot());
  FlowInlineScreenTest.setLiteral(editor,visibleSlot(editor,"1",0),"2");assertNotEquals(calculated,workspace.snapshot());assertEquals(installed,owner.write());click(editor,"undo");assertEquals(calculated,workspace.snapshot());
 }
 static FlowBlockCanvas.Slot visibleSlot(ComputerFlowScreen editor,String label,int index) throws Exception {
  var slot=FlowInlineScreenTest.slot(editor,label,index);var canvas=(FlowBlockCanvas)get(editor,"canvas");
  var viewport=(com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport)get(canvas,"viewport");
  if(!canvas.contains(slot.x()+8,slot.y()+slot.height()/2)) {
   viewport.pan((int)get(canvas,"x")+24-slot.x(),(int)get(canvas,"y")+50-slot.y());
   slot=FlowInlineScreenTest.slot(editor,label,index);
  }
  return slot;
 }
}
