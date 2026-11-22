package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
class LocalReferenceEditorTest {
 final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
 @BeforeAll static void bootstrap()throws Exception{ComputerProgramScreenTest.bootstrap();}
 @BeforeEach void client()throws Exception{environment.client();}
 @AfterEach void restore()throws Exception{environment.restore();}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={320,426,640})
 void playerCreatesPublicCellReferenceReadsAndReadonlyAliasUsingVisibleForms(int width)throws Exception{
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));
  var screen=FlowCallScreenTest.editor(workspace,width);
  click(screen,"category_storage");click(screen,"declare_cell");boxes(screen).get(0).setValue("counter");boxes(screen).get(1).setValue("7");click(screen,"save_initial");click(screen,"back");
  click(screen,"category_variables");FlowPaletteScreenTest.search(screen).setValue("ref_address");click(screen,"add_ref_address");boxes(screen).get(0).setValue("counter");boxes(screen).get(1).setValue("a");bounds(screen);click(screen,"apply");
  FlowPaletteScreenTest.search(screen).setValue("ref_readonly");click(screen,"add_ref_readonly");boxes(screen).get(0).setValue("a");boxes(screen).get(1).setValue("ro");click(screen,"apply");
  FlowPaletteScreenTest.search(screen).setValue("ref_read");click(screen,"add_ref_read");boxes(screen).get(0).setValue("ro");boxes(screen).get(1).setValue("snapshot");click(screen,"apply");
  FlowPaletteScreenTest.search(screen).setValue("ref_write");click(screen,"add_ref_write");boxes(screen).get(0).setValue("a");boxes(screen).get(1).setValue("snapshot");bounds(screen);click(screen,"apply");
  FlowPaletteScreenTest.search(screen).setValue("ref_same");click(screen,"add_ref_same");boxes(screen).get(0).setValue("a");boxes(screen).get(1).setValue("same");boxes(screen).get(2).setValue("ro");bounds(screen);click(screen,"apply");
  var document=workspace.snapshot();assertEquals(document,ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(document)));
  var body=document.flow().orElseThrow();var program=body.program(document.archive());
  var owner=new ComputerBlockHost(()->{});var limits=new ComputerHostLimits(new ResourceLimits(1000,100000,1),new ArchiveLimits(100000,1000000,1000000));owner.installFlow(program,body.initial(),limits,true);
  for(int i=0;i<8&&owner.flowSnapshot().orElseThrow().state().status()!=FlowRuntime.Status.COMPLETE;i++)owner.step(limits,true);
  assertEquals(FlowRuntime.Status.COMPLETE,owner.flowSnapshot().orElseThrow().state().status());assertEquals(new DataValue.IntegerValue(7),owner.flowSnapshot().orElseThrow().state().locals().field("snapshot"));
  assertEquals(new DataValue.BooleanValue(true),owner.flowSnapshot().orElseThrow().state().locals().field("same"));
  assertEquals("Ref<"+ComputerGraphPorts.typeLabel(DataType.Scalar.INTEGER)+",RO>",ComputerGraphPorts.typeLabel(new DataType.RefType(DataType.Scalar.INTEGER,false)));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void publicSignatureChoicesExposeNominalPermissionAndLocalScope(String locale)throws Exception{
  var old=net.minecraft.locale.Language.getInstance();try{
   var translations=ComputerUiTranslationsTest.load(locale);net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(translations));
   for(var choice:ProcedureSignatureTypes.Choice.values())if(choice.name().startsWith("REF_"))assertTrue(translations.containsKey("create_biotech.biocomputing.flow_editor.procedure_type_"+choice.name().toLowerCase(Locale.ROOT)),choice.name());
   var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptySoftwareAssembly()));var editor=FlowCallScreenTest.editor(workspace,320);
   var navigation=new java.util.concurrent.atomic.AtomicReference<net.minecraft.client.gui.screens.Screen>();set(editor,"navigator",(java.util.function.Consumer<net.minecraft.client.gui.screens.Screen>)navigation::set);
   click(editor,"category_custom");click(editor,"define_procedure");var form=navigation.get();FlowProcedureScreenTest.initializeForm(form,320);
   for(var choice:ProcedureSignatureTypes.Choice.values()){if(choice==ProcedureSignatureTypes.Choice.REF_INTEGER_RW)break;click(form,"procedure_type_"+choice.name().toLowerCase(Locale.ROOT));}
   var type=button(form,"procedure_type_ref_integer_rw");var tooltip=((ComputerFlowProcedureScreen)form).typeTooltip(type.getX()+1,type.getY()+1,true).getString();
   assertTrue(tooltip.contains("Ref<"));assertTrue(tooltip.contains(translations.get("create_biotech.biocomputing.flow_editor.reference_hint")));bounds(form);
   click(form,"procedure_outputs");for(var choice:ProcedureSignatureTypes.Choice.values()){if(choice==ProcedureSignatureTypes.Choice.REF_INTEGER_RW)break;click(form,"procedure_type_"+choice.name().toLowerCase(Locale.ROOT));}click(form,"procedure_apply");
   var definition=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();assertEquals(new DataType.RefType(DataType.Scalar.INTEGER,true),definition.inputs().get("value"));assertEquals(definition.inputs().get("value"),definition.outputs().get("result"));assertEquals(workspace.snapshot(),ProgramDocumentCodec.decode(ProgramDocumentCodec.encode(workspace.snapshot())));
  }finally{net.minecraft.locale.Language.inject(old);}
 }
}
