package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternNodeScreenTest.*;
import java.nio.file.Files;
import java.nio.file.Path;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.*;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;

/** Native recipe composition exercised through the real screen, not a replacement form model. */
class PatternGaugeScreenTest {
    private final PatternNodeScreenTest bootstrap=new PatternNodeScreenTest();
    @BeforeAll static void registry() throws Exception { PatternRecipeDraftTest.bootstrap(); }
    @BeforeEach void client() throws Exception { bootstrap.client(); }
    @AfterEach void restore() throws Exception { bootstrap.restore(); }

    @Test void primaryPageUsesNativeAddressStripAndOnlyThePrimaryOutputTarget() throws Exception {
        var s=screen();openCommon(s);
        int x=(int)get(s,"leftPos")+((int)get(s,"canvasWidth")-254)/2;
        int y=(int)get(s,"topPos")+((int)get(s,"canvasHeight")-160)/2;
        var address=(EditBox)get(s,"address");
        assertEquals(x+36,address.getX());assertEquals(y+109,address.getY());
        assertFalse(address.isBordered());assertEquals(1,s.ghostTargets().size());
        Rect2i output=s.ghostTargets().getFirst().area();
        assertEquals(x+159,output.getX());assertEquals(y+47,output.getY());
        assertTrue(s.children().stream().noneMatch(c->c==get(s,"priority")));
        assertNotNull(button(s,"advanced_settings"));assertNotNull(button(s,"relocate_node"));
    }
    @Test void extensionPreservesPendingAddressPriorityAndSecondaryOutputs() throws Exception {
        var s=screen();var d=(PatternRecipeDraft)get(s,"draft");
        d.setOutput(0,new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT)),4));
        d.setOutput(1,new PatternOutput(new StackKey(new ItemStack(Items.GOLD_INGOT)),2));
        openCommon(s);((EditBox)get(s,"address")).setValue("press");
        click(s,"advanced_settings");((EditBox)get(s,"priority")).setValue("-8");
        click(s,"recipe_settings");assertEquals("press",((EditBox)get(s,"address")).getValue());
        s.ghostTargets().getFirst().accept().accept(new ItemStack(Items.DIAMOND,3));
        click(s,"advanced_settings");assertEquals("-8",((EditBox)get(s,"priority")).getValue());
        click(s,"recipe_settings");click(s,"back_canvas");
        assertEquals("press",d.address(-1));assertEquals(-8,d.priority().orElseThrow());
        assertEquals(2,d.outputs().size());assertEquals(Items.GOLD_INGOT,d.outputs().get(1).stack().stack().getItem());
        assertEquals(3,d.outputs().getFirst().count());
    }
    @Test void inputGridPagesKeepDuplicateMaterialsAndEditTheCorrectOccurrence() throws Exception {
        var s=screen();var graph=(PatternRecipeGraph)get(s,"graph");var d=(PatternRecipeDraft)get(s,"draft");
        for(int i=1;i<=11;i++)assertTrue(graph.connect(graph.addMaterial(new PatternIngredient(
            ResourceLocation.parse("minecraft:iron_ingot"),null,i,DataComponentPredicate.EMPTY),i*110,0),-1));
        openCommon(s);click(s,"next_inputs");assertEquals(9,get(s,"inputOffset"));
        var first=slot(s,false,0);
        var hit=PatternRecipeEditorScreen.class.getDeclaredMethod("slotAt",boolean.class,double.class,double.class);
        hit.setAccessible(true);assertEquals(9,hit.invoke(s,false,(double)first.getX()+2,(double)first.getY()+2));
        assertEquals(10,d.inputs(-1).get(9).count());assertEquals(1,d.inputs(-1).getFirst().count());
        var second=slot(s,false,1);s.mouseClicked(second.getX()+2,second.getY()+2,1);
        assertEquals(10,d.inputs(-1).size());assertEquals(10,graph.edges().size());
    }
    @Test void emptyDraftCanOpenExtensionsAndResetInputsDoesNotDeleteOutputs() throws Exception {
        var s=screen();var d=(PatternRecipeDraft)get(s,"draft");var graph=(PatternRecipeGraph)get(s,"graph");
        d.setOutput(0,new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT)),1));
        assertTrue(graph.connect(graph.addMaterial(new PatternIngredient(ResourceLocation.parse("minecraft:iron_ingot"),null,1,DataComponentPredicate.EMPTY),0,0),-1));
        openCommon(s);click(s,"advanced_settings");click(s,"recipe_settings");
        click(s,"clear_inputs");assertTrue(d.inputs(-1).isEmpty());assertEquals(1,d.outputs().size());
        assertEquals("",((EditBox)get(s,"address")).getValue());
    }
    @Test void extensionCanSelectAndReorderStagesWithoutLosingTheirAddressOrSharedPriority() throws Exception {
        var s=screen();var d=(PatternRecipeDraft)get(s,"draft");
        d.setAddress(-1,"cut");d.addStage();d.addStage("press");
        set(s,"graph",new PatternRecipeGraph(d));openCommon(s);click(s,"advanced_settings");
        selectStage(s,1);assertEquals(0,get(s,"overlayStage"));assertTrue((boolean)get(s,"advanced"));
        click(s,"recipe_settings");((EditBox)get(s,"address")).setValue("polish");
        click(s,"advanced_settings");((EditBox)get(s,"priority")).setValue("-9");click(s,"stage_down");
        assertEquals(1,get(s,"overlayStage"));assertEquals("polish",d.address(1));assertEquals("press",d.address(0));
        assertEquals(-9,d.priority().orElseThrow());assertTrue((boolean)get(s,"advanced"));
        selectStage(s,0);assertEquals(-1,get(s,"overlayStage"));
        click(s,"recipe_settings");assertFalse(((EditBox)get(s,"address")).active);
        click(s,"advanced_settings");selectStage(s,2);click(s,"remove_stage");
        assertFalse((boolean)get(s,"overlay"));assertEquals(1,d.stages().size());assertEquals("press",d.address(0));
    }
    @Test void rejectedStageSelectionRestoresTheLiveSelectorAndCanBeRetried() throws Exception {
        var s=screen();var d=(PatternRecipeDraft)get(s,"draft");
        d.setAddress(-1,"cut");d.addStage("press");
        set(s,"graph",new PatternRecipeGraph(d));openCommon(s);click(s,"advanced_settings");

        ((EditBox)get(s,"priority")).setValue("bad");selectStage(s,1);
        assertEquals(-1,get(s,"overlayStage"));assertTrue((boolean)get(s,"overlay"));
        assertEquals("bad",((EditBox)get(s,"priority")).getValue());
        assertEquals(0,stageSelector(s).getState(),"rejected callback must restore the live widget state");
        assertTrue(d.priority().isEmpty());

        ((EditBox)get(s,"priority")).setValue("4");selectStage(s,1);
        assertEquals(0,get(s,"overlayStage"));assertEquals(4,d.priority().orElseThrow());

        click(s,"recipe_settings");((EditBox)get(s,"address")).setValue("");
        click(s,"advanced_settings");selectStage(s,0);
        assertEquals(0,get(s,"overlayStage"));assertTrue((boolean)get(s,"overlay"));
        assertEquals("",((EditBox)get(s,"address")).getValue());
        assertEquals(1,stageSelector(s).getState(),"invalid address must not leave the selector detached from the overlay");
        assertEquals("press",d.address(0));

        click(s,"recipe_settings");((EditBox)get(s,"address")).setValue("finish");
        click(s,"advanced_settings");selectStage(s,0);
        assertEquals(-1,get(s,"overlayStage"));assertEquals("finish",d.address(0));
    }
    @Test void overlayMaskIsRenderedBeforeTheStatusFeedback() throws Exception {
        String code=Files.readString(Path.of("src/main/java/com/nobodiiiii/createbiotech/content/biocomputing/application/recipe/menu/PatternRecipeEditorScreen.java"));
        String render=code.substring(code.indexOf("@Override protected void renderBg"),code.indexOf("private void renderNode("));
        assertTrue(render.indexOf("if(overlay)renderOverlay(g)")<render.indexOf("g.drawString(font,font.plainSubstrByWidth(status.getString()"),
            "status and errors must be drawn after the full-window overlay mask");
    }
    @Test void extensionOutputPagesReplaceAppendAndDeleteOnlyTheirActualIndices() throws Exception {
        var s=screen();var d=(PatternRecipeDraft)get(s,"draft");
        for(int i=0;i<9;i++)d.setOutput(i,new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT)),i+1));
        ((PatternRecipeGraph)get(s,"graph")).syncOutputs();
        openCommon(s);click(s,"advanced_settings");click(s,"next_outputs");
        assertEquals(8,get(s,"outputOffset"));assertEquals(2,s.ghostTargets().size());
        s.ghostTargets().getFirst().accept().accept(new ItemStack(Items.DIAMOND,3));
        s.ghostTargets().get(1).accept().accept(new ItemStack(Items.EMERALD,2));
        assertEquals(10,d.outputs().size());assertEquals(Items.DIAMOND,d.outputs().get(8).stack().stack().getItem());
        assertEquals(Items.EMERALD,d.outputs().get(9).stack().stack().getItem());
        var first=slot(s,true,0);s.mouseClicked(first.getX()+2,first.getY()+2,1);
        assertEquals(Items.EMERALD,d.outputs().get(8).stack().stack().getItem());
        s.mouseClicked(first.getX()+2,first.getY()+2,1);
        assertEquals(8,d.outputs().size());assertEquals(1,s.ghostTargets().size(),"last page retains the append slot");
        click(s,"recipe_settings");assertEquals(1,s.ghostTargets().size());
        assertEquals(Items.IRON_INGOT,d.outputs().getFirst().stack().stack().getItem());assertEquals(1,d.outputs().getFirst().count());
    }
    private static void selectStage(PatternRecipeEditorScreen s,int value) {
        var control=stageSelector(s);
        control.setState(value);control.onChanged();
    }
    private static SelectionScrollInput stageSelector(PatternRecipeEditorScreen s) {
        return (SelectionScrollInput)s.children().stream().filter(SelectionScrollInput.class::isInstance).findFirst().orElseThrow();
    }
    private static void openCommon(PatternRecipeEditorScreen s) throws Exception {
        var m=PatternRecipeEditorScreen.class.getDeclaredMethod("openOverlay",int.class);m.setAccessible(true);m.invoke(s,-1);
    }
    private static Rect2i slot(PatternRecipeEditorScreen s,boolean out,int i) throws Exception {
        var m=PatternRecipeEditorScreen.class.getDeclaredMethod("slotArea",boolean.class,int.class);m.setAccessible(true);return (Rect2i)m.invoke(s,out,i);
    }
    private static IconButton button(PatternRecipeEditorScreen s,String key) {
        return s.children().stream().filter(IconButton.class::isInstance).map(IconButton.class::cast)
            .filter(b->b.getToolTip().stream().anyMatch(c->c.getContents() instanceof TranslatableContents t&&t.getKey().equals("create_biotech.pattern_editor."+key))).findFirst().orElseThrow();
    }
    private static void click(PatternRecipeEditorScreen s,String key) {button(s,key).onClick(0,0);}
}
