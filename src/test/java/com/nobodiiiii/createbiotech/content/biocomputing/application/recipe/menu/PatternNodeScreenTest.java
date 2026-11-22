package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasViewport;
import com.nobodiiiii.createbiotech.foundation.gui.CanvasInteraction;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;

/** Real screen initialization and registered callbacks; only window/font bootstrap is replaced. */
class PatternNodeScreenTest {
    private Object oldMinecraft;
    @BeforeAll static void bootstrap() throws Exception { PatternRecipeDraftTest.bootstrap(); }
    @BeforeEach void client() throws Exception {
        oldMinecraft = field(Minecraft.class, "instance").get(null);
        var client = allocate(Minecraft.class); set(client, "font", new Metrics());
        field(Minecraft.class, "instance").set(null, client);
    }
    @AfterEach void restore() throws Exception { field(Minecraft.class, "instance").set(null, oldMinecraft); }

    @Test void existingRecipeCanvasPansWithoutMovingNodesOrOpeningConfiguration() throws Exception {
        var screen = screen(); var graph = (PatternRecipeGraph)get(screen, "graph");
        long id = graph.addNode(20, 20); var view = (CanvasViewport)get(screen, "viewport"); view.reset(); view.zoomAt(0, 0, 8);
        int x = (int)get(screen, "leftPos") + 8 + 45, y = (int)get(screen, "topPos") + 48 + 45;
        // Middle-drag also checks the same shared controller without the GLFW-dependent Shift shortcut.
        screen.mouseClicked(x, y, 2); screen.mouseDragged(x + 20, y + 10, 2, 20, 10); screen.mouseReleased(x + 20, y + 10, 2);
        var viewport = (CanvasViewport)get(screen, "viewport");
        assertEquals(20, viewport.screenX(0)); assertEquals(10, viewport.screenY(0));
        assertEquals(20, graph.get(id).x); assertEquals(-1L, get(screen, "editingNode"));
    }

    @Test void singleCreateActionPlacesAnUnconfiguredNodeBeforeAnyItemIsPicked() throws Exception {
        var screen = screen(); var graph = (PatternRecipeGraph)get(screen, "graph");
        var additions = screen.children().stream().filter(IconButton.class::isInstance).map(IconButton.class::cast)
            .filter(button -> get(button, "icon") == AllIcons.I_ADD).toList();
        assertEquals(1, additions.size(), "one node-creation entry, not separate material/output/stage creation");
        int before = graph.nodes().size(); additions.getFirst().onClick(0, 0);
        assertEquals(before + 1, graph.nodes().size());
        assertEquals("UNASSIGNED", graph.nodes().getLast().kind.name());
        var draft = (PatternRecipeDraft)get(screen, "draft");
        assertTrue(draft.inputs(-1).isEmpty()); assertTrue(draft.outputs().isEmpty()); assertTrue(draft.stages().isEmpty());
    }

    @Test void nodeOutputPickerAndNativeAmountApplyWithoutConsumingTheStack() throws Exception {
        var screen=screen();click(screen,"add_node");role(screen,1);
        var carried=new ItemStack(Items.IRON_INGOT,4);
        screen.ghostTargets().getFirst().accept().accept(carried);
        var draft=(PatternRecipeDraft)get(screen,"draft");assertTrue(draft.outputs().isEmpty());
        var amount=(ScrollInput)get(screen,"nodeAmount");amount.setState(7);amount.onChanged();
        click(screen,"apply_node");
        assertEquals(4,carried.getCount());assertEquals(7,draft.outputs().getFirst().count());
        var graph=(PatternRecipeGraph)get(screen,"graph");
        open(screen,graph.nodes().getLast().id);
        assertFalse(((ScrollInput)get(screen,"nodeRole")).active);
        screen.ghostTargets().getFirst().accept().accept(new ItemStack(Items.GOLD_NUGGET,2));
        click(screen,"cancel_node");
        assertEquals(Items.IRON_INGOT,draft.outputs().getFirst().stack().stack().getItem());
        assertEquals(7,draft.outputs().getFirst().count());
    }

    @Test void outputInputActionSelectsAnExistingMaterialAndEscapeCancelsWithoutAnotherEdge() throws Exception {
        var screen=screen();var graph=(PatternRecipeGraph)get(screen,"graph");
        var material=new PatternIngredient(ResourceLocation.parse("minecraft:iron_ingot"),null,3,DataComponentPredicate.EMPTY);
        long source=graph.addMaterial(material,30,30);
        click(screen,"add_node");role(screen,1);
        screen.ghostTargets().getFirst().accept().accept(new ItemStack(Items.GOLD_INGOT));
        click(screen,"add_input_canvas");
        ((CanvasViewport)get(screen,"viewport")).reset();
        int left=(int)get(screen,"leftPos"),top=(int)get(screen,"topPos");
        assertTrue(screen.mouseClicked(left+8+35,top+48+35,0));
        assertEquals(java.util.List.of(material),((PatternRecipeDraft)get(screen,"draft")).inputs(-1));
        long output=graph.nodes().stream().filter(n->n.kind==PatternRecipeGraph.Kind.OUTPUT).findFirst().orElseThrow().id;
        open(screen,output);click(screen,"add_input_canvas");screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE,0,0);
        assertNull(get(screen,"connectingStage"));assertEquals(1,graph.edges().size());
        assertEquals(source,graph.edges().getFirst().materialId());
    }

    @Test void stageRoleUsesNativeAddressBeforePublishingStageAndCanBeReopened() throws Exception {
        var screen=screen();click(screen,"add_node");role(screen,2);
        var draft=(PatternRecipeDraft)get(screen,"draft");
        click(screen,"apply_node");assertTrue(draft.stages().isEmpty());
        ((EditBox)get(screen,"nodeAddress")).setValue("press");click(screen,"apply_node");
        assertEquals("press",draft.address(0));
        var graph=(PatternRecipeGraph)get(screen,"graph");open(screen,graph.nodes().getLast().id);
        assertEquals("press",((EditBox)get(screen,"address")).getValue());
    }

    @Test void sequencedOutputInputActionTargetsTheLastStageRatherThanCommonInputs() throws Exception {
        var screen=screen();var draft=(PatternRecipeDraft)get(screen,"draft");
        draft.setAddress(-1,"cut");draft.addStage();draft.addStage("press");
        var graph=new PatternRecipeGraph(draft);set(screen,"graph",graph);
        click(screen,"add_node");role(screen,1);
        screen.ghostTargets().getFirst().accept().accept(new ItemStack(Items.GOLD_INGOT));
        click(screen,"add_input_canvas");
        assertEquals(1,get(screen,"connectingStage"));
    }

    @Test void removeStageButtonKeepsTheAddressTypedInTheOpenOverlay() throws Exception {
        var screen=screen();click(screen,"add_node");role(screen,2);
        ((EditBox)get(screen,"nodeAddress")).setValue("press");click(screen,"apply_node");
        var graph=(PatternRecipeGraph)get(screen,"graph");open(screen,graph.nodes().getLast().id);
        ((EditBox)get(screen,"address")).setValue("finish");click(screen,"advanced_settings");click(screen,"remove_stage");
        assertEquals("finish",((PatternRecipeDraft)get(screen,"draft")).address(-1));
    }

    private static void role(PatternRecipeEditorScreen screen,int state) {
        var control=(SelectionScrollInput)screen.children().stream().filter(SelectionScrollInput.class::isInstance).findFirst().orElseThrow();
        control.setState(state);control.onChanged();
    }
    private static void click(PatternRecipeEditorScreen screen,String key) {
        var button=screen.children().stream().filter(IconButton.class::isInstance).map(IconButton.class::cast)
            .filter(b->b.getToolTip().stream().anyMatch(c->c.getContents() instanceof TranslatableContents t&&t.getKey().equals("create_biotech.pattern_editor."+key))).findFirst().orElseThrow();
        button.onClick(0,0);
    }
    private static void open(PatternRecipeEditorScreen screen,long id) throws Exception {
        var method=PatternRecipeEditorScreen.class.getDeclaredMethod("openNode",long.class);method.setAccessible(true);method.invoke(screen,id);
    }

    static PatternRecipeEditorScreen screen() throws Exception {
        var screen = allocate(PatternRecipeEditorScreen.class);
        var menu = allocate(PatternStorageCoreMenu.class); set(menu, "carried", ItemStack.EMPTY);
        var draft = new PatternRecipeDraft();
        set(screen, "menu", menu); set(screen, "inventory", new Inventory(null));
        set(screen, "draft", draft); set(screen, "graph", new PatternRecipeGraph(draft));
        var viewport = new CanvasViewport();
        set(screen, "viewport", viewport); set(screen, "interaction", new CanvasInteraction<Long>(viewport)); set(screen, "overlayStage", -1);
        set(screen, "editingNode", -1L);
        set(screen, "selected", -1L);
        set(screen, "title", Component.literal("Pattern recipe")); set(screen, "playerInventoryTitle", Component.empty());
        set(screen, "children", new ArrayList<>()); set(screen, "renderables", new ArrayList<>()); set(screen, "narratables", new ArrayList<>());
        set(screen, "width", 640); set(screen, "height", 480);
        set(screen, "minecraft", Minecraft.getInstance()); set(screen, "font", Minecraft.getInstance().font);
        screen.init(); return screen;
    }
    static Object get(Object target, String name) {
        try { return field(target.getClass(), name).get(target); } catch (Exception failure) { throw new AssertionError(failure); }
    }
    static void set(Object target, String name, Object value) throws Exception { field(target.getClass(), name).set(target, value); }
    static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current=type; current!=null; current=current.getSuperclass()) {
            try { var f=current.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(((sun.misc.Unsafe)field(sun.misc.Unsafe.class, "theUnsafe").get(null)).allocateInstance(type));
    }
    private static final class Metrics extends Font implements com.simibubi.create.foundation.mixin.accessor.FontAccessor {
        Metrics() { super(ignored -> null, false); }
        @Override public java.util.function.Function<ResourceLocation, net.minecraft.client.gui.font.FontSet> create$getFonts() {
            return ignored -> null;
        }
        @Override public int width(String value) { return value.length(); }
        @Override public int width(FormattedText value) { return value.getString().length(); }
        @Override public String plainSubstrByWidth(String value, int width) { return value.substring(0, Math.clamp(width, 0, value.length())); }
        @Override public String plainSubstrByWidth(String value, int width, boolean reverse) {
            return reverse ? value.substring(Math.max(0, value.length()-width)) : plainSubstrByWidth(value, width);
        }
    }
}
