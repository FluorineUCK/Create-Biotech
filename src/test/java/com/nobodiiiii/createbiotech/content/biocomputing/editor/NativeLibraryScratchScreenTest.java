package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.*;
class NativeLibraryScratchScreenTest {
 static com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton button(Screen screen,String key){return screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t && t.getKey().endsWith("."+key)).findFirst().orElseThrow(()->new AssertionError("Missing visible control "+key));}
 static void click(Screen screen,String key){var b=button(screen,key);assertTrue(b.active,key);b.onClick(0,0);}

 net.neoforged.api.distmarker.Dist oldDist;
 net.minecraft.client.player.LocalPlayer oldPlayer;
 List<net.minecraft.network.protocol.Packet<?>> sent;
 // Reuse the established test-only packet sink; no production injection point.
 void capturePackets()throws Exception {
  oldPlayer=Minecraft.getInstance().player;oldDist=net.neoforged.fml.loading.FMLEnvironment.dist;setDist(net.neoforged.api.distmarker.Dist.CLIENT);
  var type=Class.forName(ComputerWiringScreenTest.class.getName()+"$CaptureListener");var connection=(net.minecraft.client.multiplayer.ClientPacketListener)allocate(type);sent=new ArrayList<>();set(connection,"sent",sent);
  var player=allocate(net.minecraft.client.player.LocalPlayer.class);set(player,"connection",connection);Minecraft.getInstance().player=player;
 }
 static void setDist(net.neoforged.api.distmarker.Dist dist)throws Exception {var method=ComputerWiringScreenTest.class.getDeclaredMethod("setDist",net.neoforged.api.distmarker.Dist.class);method.setAccessible(true);method.invoke(null,dist);}
 List<ProgramEditorRequest> installs(){return sent.stream().filter(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.class::isInstance).map(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket.class::cast).map(net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket::payload).filter(ProgramEditorRequest.class::isInstance).map(ProgramEditorRequest.class::cast).filter(ProgramEditorRequest::install).toList();}
 final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
 @BeforeAll static void bootstrapClient()throws Exception{ComputerProgramScreenTest.bootstrap();}
 @BeforeEach void client()throws Exception{environment.client();set(Minecraft.getInstance(),"font",new Metrics(){@Override public net.minecraft.client.StringSplitter getSplitter(){return new net.minecraft.client.StringSplitter((cp,style)->1f);}@Override public List<net.minecraft.util.FormattedCharSequence> split(net.minecraft.network.chat.FormattedText t,int w){return List.of(net.minecraft.util.FormattedCharSequence.forward(t.getString(),net.minecraft.network.chat.Style.EMPTY));}});}
 void restorePackets()throws Exception{Minecraft.getInstance().player=oldPlayer;setDist(oldDist);}
 @AfterEach void restoreClient()throws Exception{try{restorePackets();}finally{environment.restore();}}
 @Test void packetCaptureRestoresUninitializedDistributionInsteadOfLeakingClientMode()throws Exception {
  var entryDist=net.neoforged.fml.loading.FMLEnvironment.dist;
  try {
   setDist(null);capturePackets();restorePackets();
   assertNull(net.neoforged.fml.loading.FMLEnvironment.dist);
  } finally {oldDist=entryDist;setDist(entryDist);}
 }
 @Test void readerRangeCatalogPreviewExplicitImportEditInputsAndInstall()throws Exception{defaultScratchImportsRevealsEditsExportsReimportsAndExplicitlyInstallsPublicDefinition("reader_sample_range_a");}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"read_a","read_b","read_c","write_a","write_d","inspect_a","ack_a","terminal_a","service_b","service_c","service_d","preview_v","preview_p","preview_s","material_selection_service","material_selection_coordinator","routed_production_a","routed_production_e","routed_production_d","routed_production_f"})
 void defaultScratchImportsRevealsEditsExportsReimportsAndExplicitlyInstallsPublicDefinition(String role)throws Exception {
  String example=(role.startsWith("reader_sample_range_")||role.startsWith("material_selection_")||role.startsWith("routed_production_"))?role:role.startsWith("preview_")?"recipe_"+role:"library_"+role;
  capturePackets();var fixture=screen();var menu=ComputerFlowScreenTest.readyMenu(fixture);var authority=menu.response().orElseThrow();set(menu,"nonce",authority.nonce());set(menu,"latestIntent",authority.intent());var hub=new ComputerScratchScreen(menu,(Inventory)get(fixture,"editorInventory"),Component.empty());
  Screen[] shown={hub};set(hub,"navigator",(Consumer<Screen>)(s->shown[0]=s));ComputerScratchNavigationTest.initialize(hub);set(hub,"requested",true);set(menu,"responseVersion",1L);hub.containerTick();assertInstanceOf(ComputerFlowScreen.class,shown[0]);
  click(hub,"program_package");var page=assertInstanceOf(ComputerProgramDocumentScreen.class,shown[0]);FlowProcedureScreenTest.initializeForm(page,640);
  set(page,"exampleResourceOpener",(Function<net.minecraft.resources.ResourceLocation,java.io.InputStream>)(id->getClass().getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath())));
  click(page,"examples");for(int i=0;i<ProgramExampleCatalog.load(id->getClass().getClassLoader().getResourceAsStream("assets/"+id.getNamespace()+"/"+id.getPath())).size();i++){try{button(page,"example."+example+".title");break;}catch(AssertionError absent){click(page,"examples_next");}}
  var before=(ProgramWorkspace)get(hub,"workspace");click(page,"example."+example+".title");assertSame(before,get(hub,"workspace"));assertTrue(installs().isEmpty(),"Preview must not install");click(page,"example_replace");var workspace=(ProgramWorkspace)get(hub,"workspace");assertNotSame(before,workspace);assertTrue(installs().isEmpty(),"Replacing the draft must not install");assertFalse(workspace.flow().orElseThrow().canvas().detached().isEmpty());page.onClose();click(hub,"flow");
  var editor=assertInstanceOf(ComputerFlowScreen.class,shown[0]);set(editor,"navigator",(Consumer<Screen>)(s->shown[0]=s));initialize(editor,640);
  if(role.equals("reader_sample_range_a")){
   var originalInputs=workspace.flow().orElseThrow().initial();click(editor,"initials");
   for(var changed:Map.of("offset","7","count","2").entrySet()){
    while(button(editor,"previous").active)click(editor,"previous");
    com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton selected=null;
    for(int i=0;i<4&&selected==null;i++){
     selected=editor.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).filter(b->b.getMessage().getString().equals(changed.getKey())).findFirst().orElse(null);
     if(selected==null)click(editor,"next");
    }
    assertNotNull(selected);selected.onClick(0,0);
    var valueField=editor.children().stream().filter(net.minecraft.client.gui.components.EditBox.class::isInstance).map(net.minecraft.client.gui.components.EditBox.class::cast).filter(f->f.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t&&t.getKey().endsWith(".initial_value")).findFirst().orElseThrow();
    valueField.setValue(changed.getValue());click(editor,"save_initial");
   }
   click(editor,"back");var edited=workspace.flow().orElseThrow().initial();assertEquals(new DataValue.IntegerValue(7),edited.field("offset"));assertEquals(new DataValue.IntegerValue(2),edited.field("count"));assertEquals(originalInputs.field("target"),edited.field("target"));assertEquals(originalInputs.field("component"),edited.field("component"));assertTrue(installs().isEmpty());
  }
  var definition=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();
  var canvas=(FlowBlockCanvas)get(editor,"canvas");canvas.focus(definition.sourceId());int cx=(int)get(canvas,"x")+24,cy=(int)get(canvas,"y")+30;assertTrue(editor.mouseClicked(cx,cy,0));editor.mouseReleased(cx,cy,0);click(editor,"edit");var form=shown[0];assertInstanceOf(ComputerFlowProcedureScreen.class,form);FlowProcedureScreenTest.initializeForm(form,640);click(form,"procedure_implementation");
  var source=form.children().stream().filter(MultiLineEditBox.class::isInstance).map(MultiLineEditBox.class::cast).findFirst().orElseThrow();assertEquals(definition.assembly().orElseThrow().text(),source.getValue());source.setValue(source.getValue()+"\n; player edited this visible implementation");click(form,"procedure_apply");
  assertTrue(FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst().assembly().orElseThrow().text().contains("player edited"));assertFalse((boolean)get(editor,"waiting"));assertTrue(installs().isEmpty(),"Editing visible source must not install");
  var doc=workspace.snapshot();var archive=menu.response().orElseThrow().archiveLimits();click(editor,"documents");page=assertInstanceOf(ComputerProgramDocumentScreen.class,shown[0]);FlowProcedureScreenTest.initializeForm(page,640);String[] clipboard={""};set(page,"clipboardWrite",(Consumer<String>)(s->clipboard[0]=s));click(page,"export_program");click(page,"copy_package");assertFalse(clipboard[0].isBlank());assertTrue(installs().isEmpty(),"Export must not install");
  set(page,"clipboardRead",(Supplier<String>)(()->clipboard[0]));click(page,"paste_package");click(page,"import_program");var imported=((ProgramWorkspace)get(hub,"workspace")).snapshot();assertEquals(doc,imported);assertTrue(installs().isEmpty(),"Reimport must only replace the draft");
  page.onClose();editor=assertInstanceOf(ComputerFlowScreen.class,shown[0]);initialize(editor,640);assertTrue(installs().isEmpty());long previousIntent=(long)get(menu,"latestIntent");click(editor,"install");
  assertEquals(1,installs().size(),"Only the explicit install click emits an installation request");var request=installs().getFirst();assertEquals(menu.containerId,request.containerId());assertEquals(authority.nonce(),request.nonce());assertEquals(authority.revision(),request.revision());assertEquals(previousIntent+1,request.intent());assertTrue(request.install());assertTrue(request.resetMemory());assertArrayEquals(ProgramDocumentWire.encode(imported,archive),request.archive());var received=ProgramDocumentWire.decode(request.archive(),archive);assertEquals(imported,received);assertTrue(FlowProcedureTools.definitions(received.flow().orElseThrow()).getFirst().assembly().orElseThrow().text().contains("player edited"));assertTrue((boolean)get(editor,"waiting"));
  // Packet capture proves the client submission; this independent host install is not a live server/menu/world claim.
  var owner=new ComputerBlockHost(()->{});var hardware=ParserHardwareFixture.withMasterParser(new ComputerHostLimits(new ResourceLimits(256,65536,8),archive,com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MailboxLimits.DEFAULT,HardwarePins.DEFAULT,new SerialHardware(Map.of("s0",new SerialHardware.Port(128,4096,2,8192)))));var body=received.flow().orElseThrow();owner.installFlow(body.program(received.archive()),body.initial(),hardware,true);assertTrue(owner.flowSnapshot().orElseThrow().state().paused());assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().isPresent());assertEquals(1,installs().size());
 }
}
