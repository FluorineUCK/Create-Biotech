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
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;
class TextNextScreenTest {
 final NativeLibraryScratchScreenTest environment=new NativeLibraryScratchScreenTest();
 @BeforeAll static void bootstrap() throws Exception{ComputerProgramScreenTest.bootstrap();}
 @BeforeEach void client() throws Exception{environment.client();environment.capturePackets();}
 @AfterEach void restore() throws Exception{environment.restoreClient();}
 @Test void actualTextPaletteDragSourceEditAndExplicitPacketInstall() throws Exception {
  var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(new FlowDocumentBody(Map.of("text",DataType.Scalar.TEXT,"offset",DataType.Scalar.INTEGER),List.of(),new DataValue.RecordValue(Map.of("text",new DataValue.TextValue("中"),"offset",new DataValue.IntegerValue(0))),FlowBlockLayout.DEFAULT,Map.of(),FlowProgram.Execution.REGISTER_V1)));
  var editor=FlowCallScreenTest.editor(workspace,640);click(editor,"category_data");((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("text_next");
  var prototype=button(editor,"template_text_next");var canvas=get(editor,"canvas");double x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0);editor.mouseReleased(x,y,0);
  var template=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();assertEquals("text_next",template.key().id());assertTrue(environment.installs().isEmpty());var result=new AtomicReference<FlowProgram.Step>();var form=new ComputerFlowProcedureScreen((ComputerProgramMenu)get(editor,"menu"),(Inventory)get(editor,"editorInventory"),Component.empty(),template.sourceId(),template,null,result::set,()->{});FlowProcedureScreenTest.initializeForm(form,426);click(form,"procedure_implementation");
  var source=form.children().stream().filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).map(net.minecraft.client.gui.components.MultiLineEditBox.class::cast).findFirst().orElseThrow();assertEquals(template.assembly().orElseThrow().text(),source.getValue());source.setValue(source.getValue()+"\n; player text edit");click(form,"procedure_apply");var modified=(FlowProgram.Procedure)result.get();assertEquals(template.inputs(),modified.inputs());assertEquals(template.outputs(),modified.outputs());assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,modified.assembly().orElseThrow().version());assertTrue(environment.installs().isEmpty());
  var draft=new FlowDraft(workspace.flow().orElseThrow());draft.replace(template.sourceId(),modified);var call=(FlowProgram.Invoke)draft.snapshot().steps().getFirst();draft.replace(call.sourceId(),new FlowProgram.Invoke(call.sourceId(),call.procedure(),new FlowCallBindings(Map.of("text","text","offset","offset"),Map.of("result","next_point"))));workspace.setFlow(Optional.of(draft.snapshot()));
  var archive=((ComputerProgramMenu)get(editor,"menu")).response().orElseThrow().archiveLimits();var doc=workspace.snapshot();var imported=ProgramDocumentWire.decode(ProgramDocumentWire.encode(doc,archive),archive);assertEquals(doc,imported);assertTrue(environment.installs().isEmpty(),"Editing and serializing a draft cannot install");
  var fixture=screen();var menu=ComputerFlowScreenTest.readyMenu(fixture);var authority=menu.response().orElseThrow();set(menu,"nonce",authority.nonce());set(menu,"latestIntent",authority.intent());var hub=new ComputerScratchScreen(menu,(Inventory)get(fixture,"editorInventory"),Component.empty());Screen[] shown={hub};set(hub,"navigator",(Consumer<Screen>)(s->shown[0]=s));ComputerScratchNavigationTest.initialize(hub);set(hub,"requested",true);set(menu,"responseVersion",1L);hub.containerTick();
  set(hub,"workspace",new ProgramWorkspace(imported));NativeLibraryScratchScreenTest.click(hub,"flow");var installScreen=assertInstanceOf(ComputerFlowScreen.class,shown[0]);initialize(installScreen,640);assertTrue(environment.installs().isEmpty());click(installScreen,"install");assertEquals(1,environment.installs().size());assertArrayEquals(ProgramDocumentWire.encode(imported,archive),environment.installs().getFirst().archive());
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"en_us","zh_cn"})
 void textSearchAndHelpAreLocalizedWithoutRecipeGrouping(String locale) throws Exception{
  var previous=net.minecraft.locale.Language.getInstance();try{var values=ComputerUiTranslationsTest.load(locale);net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));var entry=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,"text_next").getFirst();assertEquals("text",entry.group());assertFalse(entry.label().getString().contains("create_biotech."));assertFalse(entry.help().getString().contains("create_biotech."));}finally{net.minecraft.locale.Language.inject(previous);}
 }
 @Test void sixtyOnePreV7PublishedArchivesAndHistoricalE1RetainExactBytes() throws Exception {
  DataValue.RecordValue hashes;try(var in=getClass().getResourceAsStream("/assets/create_biotech/biocomputing/text_next_original_bcp_sha256.json")){hashes=(DataValue.RecordValue)EditorValueFormat.parse(EditorValueFormat.Kind.JSON,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}assertEquals(62,hashes.fields().size());
  for(var entry:hashes.fields().entrySet()){String path=entry.getKey().substring("src/main/resources".length());if(path.endsWith("/routed_production_e.bcp"))path="/assets/create_biotech/biocomputing/history/routed_production_e_v1.bcp";if(path.endsWith("/redstone_output_b.bcp"))path="/assets/create_biotech/biocomputing/history/redstone_output_b_before_reply_timeout.bcp";try(var in=getClass().getResourceAsStream(path)){assertNotNull(in,path);assertEquals(((DataValue.TextValue)entry.getValue()).value(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())),path);}}
 }
}
