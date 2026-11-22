package com.nobodiiiii.createbiotech.content.biocomputing.editor;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerProgramScreenTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.editor.ComputerFlowScreenTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class RecipeTemplateScreenTest {
    final ComputerProgramScreenTest environment=new ComputerProgramScreenTest();
    @BeforeAll static void bootstrap()throws Exception{ComputerProgramScreenTest.bootstrap();}
    @BeforeEach void client()throws Exception{
        environment.client();
        set(net.minecraft.client.Minecraft.getInstance(),"font",new Metrics(){@Override public net.minecraft.client.StringSplitter getSplitter(){return new net.minecraft.client.StringSplitter((codepoint,style)->1f);}});
    }
    @AfterEach void restore()throws Exception{environment.restore();}
    @ParameterizedTest @ValueSource(ints={320,426,640})
    void nominalSignaturesAreLabelledFixedEditableAndImmuneToSampleRefresh(int width)throws Exception{
        for(var template:FlowProcedureTemplates.all().stream().filter(t->t.key().id().startsWith("recipe_")).toList()){
            var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
            var editor=FlowCallScreenTest.editor(workspace,width);var menu=(ComputerProgramMenu)get(editor,"menu");
            var result=new AtomicReference<FlowProgram.Step>();
            var form=new ComputerFlowProcedureScreen(menu,(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),
                net.minecraft.network.chat.Component.empty(),template.sourceId(),template,null,result::set,()->{});
            FlowProcedureScreenTest.initializeForm(form,width);bounds(form);
            assertNotNull(button(form,template.key().id().equals("recipe_project")?"procedure_type_recipe":"procedure_type_any"));
            var samples=boxes(form).stream().filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t&&t.getKey().endsWith("procedure_type_sample")).toList();
            assertEquals(1,samples.size());assertFalse(samples.getFirst().active);samples.getFirst().setValue("{}");
            click(form,"procedure_outputs");bounds(form);
            assertNotNull(button(form,template.key().id().equals("recipe_validate")?"procedure_type_recipe_check":"procedure_type_any"));
            click(form,"procedure_apply");assertEquals(template,result.get(),"No-op apply retains exact resource signature and source");
        }
    }
    @ParameterizedTest @ValueSource(strings={"en_us","zh_cn"})
    void dataTemplateControlsAndSearchAreLocalized(String locale)throws Exception{
        var old=net.minecraft.locale.Language.getInstance();
        try{
            var values=ComputerUiTranslationsTest.load(locale);net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
            for(var key:List.of("template_recipe_validate","template_recipe_project","template_scale_materials","recipe_template_hint","recipe_template_legacy","group_recipes","keywords_recipes","procedure_type_any","procedure_type_recipe","procedure_type_optional_recipe","procedure_type_recipe_check","procedure_type_retained","recipe_signature_hint","procedure_fixed_signature_hint"))
                assertNotNull(values.get("create_biotech.biocomputing.flow_editor."+key));
            var matches=FlowBlockCatalog.search(Map.of(),FlowBlockPalette.Category.DATA,locale.equals("zh_cn")?"配方":"recipe");
            assertEquals(4,matches.stream().filter(FlowBlockCatalog.Entry::template).count());
            for(var entry:matches)if(entry.template())assertFalse(entry.help().getString().contains("create_biotech."));
        }finally{net.minecraft.locale.Language.inject(old);}
    }
    @Test void actualDataPaletteDragCancelsAndPublishesDefinitionWithCallInOneUndo()throws Exception{
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var before=workspace.snapshot();var editor=FlowCallScreenTest.editor(workspace,640);click(editor,"category_data");
        ((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("recipe_validate");
        var prototype=button(editor,"template_recipe_validate");var canvas=get(editor,"canvas");
        double x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0);editor.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);editor.mouseReleased(x,y,0);
        assertEquals(before,workspace.snapshot());click(editor,"category_data");((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("recipe_validate");prototype=button(editor,"template_recipe_validate");
        editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0);editor.mouseReleased(x,y,0);
        var imported=workspace.snapshot();assertEquals(1,imported.flow().orElseThrow().steps().size());assertEquals(1,FlowProcedureTools.definitions(imported.flow().orElseThrow()).size());
        click(editor,"undo");assertEquals(before,workspace.snapshot());click(editor,"redo");assertEquals(imported,workspace.snapshot());
        assertFalse((boolean)get(editor,"waiting"));
    }
    @Test void actualSelectionPaletteDragAndSourceEditRemainOrdinaryExplicitControls() throws Exception {
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,640);click(editor,"category_data");
        ((net.minecraft.client.gui.components.EditBox)get(editor,"catalogSearch")).setValue("select_material");
        var prototype=button(editor,"template_select_material");var canvas=get(editor,"canvas");
        double x=(int)get(canvas,"x")+24,y=(int)get(canvas,"y")+50;
        editor.mouseClicked(prototype.getX()+10,prototype.getY()+10,0);editor.mouseReleased(x,y,0);
        var template=FlowProcedureTools.definitions(workspace.flow().orElseThrow()).getFirst();
        assertEquals("select_material",template.key().id());assertEquals(5,template.inputs().size());
        var result=new AtomicReference<FlowProgram.Step>();
        var form=new ComputerFlowProcedureScreen((ComputerProgramMenu)get(editor,"menu"),(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),net.minecraft.network.chat.Component.empty(),template.sourceId(),template,null,result::set,()->{});
        FlowProcedureScreenTest.initializeForm(form,426);click(form,"procedure_implementation");
        var source=form.children().stream().filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).map(net.minecraft.client.gui.components.MultiLineEditBox.class::cast).findFirst().orElseThrow();
        assertEquals(template.assembly().orElseThrow().text(),source.getValue());source.setValue(source.getValue()+"\n; explicit local selection edit");click(form,"procedure_apply");
        var modified=(FlowProgram.Procedure)result.get();assertEquals(template.inputs(),modified.inputs());assertEquals(template.outputs(),modified.outputs());assertTrue(modified.assembly().orElseThrow().text().contains("local selection edit"));assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,modified.assembly().orElseThrow().version());
    }
    @ParameterizedTest @ValueSource(strings={"en_us","zh_cn"})
    void nativeNbtFieldBrowserAndSelectionComponentLabelsResolve(String locale) throws Exception {
        var old=net.minecraft.locale.Language.getInstance();
        try {
            var values=ComputerUiTranslationsTest.load(locale);net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(values));
            var nbt=new DataValue.NbtValue(10,new DataValue.RecordValue(Map.of()));
            String key="create_biotech.biocomputing.tree.type."+ValueTree.kind(nbt);
            assertTrue(values.containsKey(key));assertNotEquals(key,net.minecraft.network.chat.Component.translatable(key).getString());
            for(String suffix:List.of("component.inventory_item_tags","flow_editor.template_select_material")) {
                String label="create_biotech.biocomputing."+suffix;assertTrue(values.containsKey(label));assertNotEquals(label,net.minecraft.network.chat.Component.translatable(label).getString());
            }
        } finally { net.minecraft.locale.Language.inject(old); }
    }
    @Test void actualReaderComponentSelectionStartsUngrantingAndOnlyTogglesTheNewMetadataDraft() throws Exception {
        var old=net.minecraft.locale.Language.getInstance();
        var input=get(net.minecraft.client.Minecraft.getInstance(),"lastInputType");
        set(net.minecraft.client.Minecraft.getInstance(),"lastInputType",net.minecraft.client.InputType.MOUSE);
        try {
            var labels=ComputerUiTranslationsTest.load("en_us");net.minecraft.locale.Language.inject(ComputerUiTranslationsTest.language(labels));
            var registry=com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReadComponents.registry();
            var ids=registry.ids().stream().sorted().toList();var descriptions=registry.describe();
            var entries=new ArrayList<DataValue>();
            for(String id:ids){var descriptor=(DataValue.RecordValue)descriptions.field(id);entries.add(new DataValue.RecordValue(Map.of("id",new DataValue.TextValue(id),"label",descriptor.field("label"),"additional",descriptor.field("additional"))));}
            var menu=allocate(com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderMenu.class);
            set(menu,"view",new DataValue.RecordValue(Map.of("current",new DataValue.IntegerValue(-1),"components",new DataValue.ListValue(List.of(new DataValue.TextValue("create_biotech:inventory"))),"catalog",new DataValue.ListValue(entries),"choices",new DataValue.ListValue(List.of()),"binding",DataValue.NullValue.INSTANCE)));
            var editor=FlowCallScreenTest.editor(new ProgramWorkspace(),640);
            var screen=new com.nobodiiiii.createbiotech.content.biocomputing.peripheral.ReaderScreen(menu,(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),net.minecraft.network.chat.Component.empty());
            FlowProcedureScreenTest.initializeForm(screen,640);
            assertEquals(Set.of(ids.indexOf("create_biotech:inventory")),get(screen,"enabled"));
            screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t&&t.getKey().equals("create_biotech.reader.components")).findFirst().orElseThrow().onClick(0,0);
            String label=labels.get("create_biotech.biocomputing.component.inventory_item_tags");
            com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton target=null;
            for(int page=0;page<ids.size();page++){
                target=screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElse(null);
                if(target!=null)break;
                var next=screen.children().stream().filter(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::isInstance).map(com.nobodiiiii.createbiotech.foundation.gui.CreateTextButton.class::cast).filter(b->b.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t&&t.getKey().equals("create_biotech.reader.next")).findFirst().orElseThrow();assertTrue(next.active);next.onClick(0,0);
            }
            assertNotNull(target);target.onClick(0,0);
            assertEquals(Set.of(ids.indexOf("create_biotech:inventory"),ids.indexOf("create_biotech:inventory_item_tags")),get(screen,"enabled"));
            assertEquals(new DataValue.ListValue(List.of(new DataValue.TextValue("create_biotech:inventory"))),menu.view().field("components"),"Toggling a local draft does not authorize the component");
            assertFalse((boolean)get(screen,"waiting"));
        } finally {net.minecraft.locale.Language.inject(old);set(net.minecraft.client.Minecraft.getInstance(),"lastInputType",input);}
    }
    @Test void optionalSignatureAndFullResourceImplementationAreNormallyEditable()throws Exception{
        var workspace=new ProgramWorkspace();workspace.setFlow(Optional.of(FlowDocumentBody.emptyAssembly()));
        var editor=FlowCallScreenTest.editor(workspace,426);var template=FlowProcedureTemplates.all().get(1);
        var result=new AtomicReference<FlowProgram.Step>();
        var form=new ComputerFlowProcedureScreen((ComputerProgramMenu)get(editor,"menu"),(net.minecraft.world.entity.player.Inventory)get(editor,"editorInventory"),
            net.minecraft.network.chat.Component.empty(),template.sourceId(),template,null,result::set,()->{});
        FlowProcedureScreenTest.initializeForm(form,426);click(form,"procedure_type_recipe");assertNotNull(button(form,"procedure_type_optional_recipe"));
        click(form,"procedure_implementation");
        var source=form.children().stream().filter(net.minecraft.client.gui.components.MultiLineEditBox.class::isInstance).map(net.minecraft.client.gui.components.MultiLineEditBox.class::cast).findFirst().orElseThrow();
        assertEquals(template.assembly().orElseThrow().text(),source.getValue());source.setValue(source.getValue()+"\n; local editable implementation");
        click(form,"procedure_apply");
        var modified=(FlowProgram.Procedure)result.get();assertNotNull(modified);
        assertEquals(ProcedureSignatureTypes.OPTIONAL_RECIPE,modified.inputs().get("recipe"));
        assertTrue(modified.assembly().orElseThrow().text().contains("; local editable implementation"));assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblySource.VERSION,modified.assembly().orElseThrow().version());
    }
}
