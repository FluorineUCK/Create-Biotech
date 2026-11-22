package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TextNextTemplateTest {
 @Test void publicTemplateIsGeneralTextAndEditableDraftOnly(){
  var entry=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"text_next").getFirst();assertEquals("text",entry.group());var template=entry.procedure();
  assertEquals(Map.of("text",DataType.Scalar.TEXT,"offset",DataType.Scalar.INTEGER),template.inputs());assertEquals(Map.of("result",RegisterProgram.TextNext.TYPE),template.outputs());assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,template.assembly().orElseThrow().version());
  var draft=new FlowDraft(FlowDocumentBody.emptyAssembly());var before=draft.snapshot();var id=draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1));var call=(FlowProgram.Invoke)draft.find(id).orElseThrow();assertTrue(call.bindings().arguments().isEmpty());assertTrue(call.bindings().returns().isEmpty());var imported=draft.snapshot();draft.undo();assertEquals(before,draft.snapshot());draft.redo();assertEquals(imported,draft.snapshot());
  var def=FlowProcedureTools.definitions(draft.snapshot()).getFirst();draft.replace(def.sourceId(),new FlowProgram.Procedure(def.sourceId(),def.key(),def.inputs(),def.outputs(),def.returns(),def.rules(),Optional.of(new AssemblySource(def.assembly().orElseThrow().text()+"\n; edited"))));assertTrue(FlowProcedureTools.definitions(draft.snapshot()).getFirst().assembly().orElseThrow().text().contains("edited"));
  var image=ProcedureAssembler.assemble(template.assembly().orElseThrow(),template.inputs(),template.outputs(),template.returns(),65536,256).program();assertTrue(image.advancedTypeLocation().isEmpty());assertTrue(image.code().stream().anyMatch(c->c.instruction() instanceof RegisterProgram.TextNext));
 }

 @Test void publishedTemplateInstallsOnlyExplicitlyAndRunsOnRealNonMasterParser(){
  var template=FlowProcedureTemplates.all().stream().filter(t->t.key().id().equals("text_next")).findFirst().orElseThrow();var in=new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("中"),"offset",new DataValue.IntegerValue(0)));var draft=new FlowDraft(new FlowDocumentBody(template.inputs(),List.of(),in,FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1));var id=draft.placeTemplate(template,new FlowDraft.Socket(null,0),new ProgramDocument.Position(1,1));var call=(FlowProgram.Invoke)draft.find(id).orElseThrow();draft.replace(id,new FlowProgram.Invoke(id,call.procedure(),new FlowCallBindings(Map.of("text","text","offset","offset"),Map.of("result","out"))));
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(draft.snapshot()));var limits=com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.NativeLibraryOwnersTest.limits("cleric",4);var doc=ProgramDocumentWire.decode(ProgramDocumentWire.encode(workspace.snapshot(),limits.archive()),limits.archive());var body=doc.flow().orElseThrow();var p=body.program(doc.archive());var owner=new com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost(()->{});owner.installFlow(p,body.initial(),limits,true);assertTrue(owner.flowSnapshot().orElseThrow().state().paused());
  for(int i=0;owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++){assertTrue(i<100);owner.step(limits,true);}
  var result=(DataValue.RecordValue)owner.flowSnapshot().orElseThrow().state().locals().field("out");assertEquals(new DataValue.IntegerValue(20013),result.field("codepoint"));assertEquals(new DataValue.IntegerValue(1),result.field("next"));System.out.println("TEXT_NEXT_TEMPLATE full="+p.usage(limits.hardware()).programUnits()+" work="+owner.flowSnapshot().orElseThrow().state().work());
 }
}
