package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.*;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PatternNodeAuthoringTest {
    @BeforeAll static void bootstrap() throws Exception { PatternRecipeDraftTest.bootstrap(); }

    @Test void nodeExistsBeforeChoosingItemAndOnlyConnectedMaterialBecomesAnInput() throws Exception {
        var draft = new PatternRecipeDraft(); var graph = new PatternRecipeGraph(draft);
        long id = add(graph, 30, 40);
        assertSame(graph.get(id), graph.nodeAt(31, 41));
        assertFalse(graph.connect(id, -1));
        assertTrue(draft.inputs(-1).isEmpty());
        var iron = ingredient("c:ingots/iron", 3, DataComponentPredicate.EMPTY);
        configure(graph, "configureMaterial", id, PatternIngredient.class, iron);
        assertEquals(30, graph.get(id).x); assertEquals(40, graph.get(id).y);
        assertTrue(draft.inputs(-1).isEmpty());
        assertTrue(graph.connect(id, -1)); assertFalse(graph.connect(id, -1));
        assertEquals(List.of(iron), draft.inputs(-1));
    }

    @Test void editingMaterialSelectorKeepsEachConnectedOccurrenceAmountAndOtherNodes() throws Exception {
        var draft = new PatternRecipeDraft(); draft.setAddress(-1, "press"); draft.addStage();
        var graph = new PatternRecipeGraph(draft);
        var iron = ingredient("c:ingots/iron", 3, DataComponentPredicate.EMPTY);
        long id = add(graph, 30, 40), duplicate = graph.addMaterial(iron, 70, 40);
        configure(graph, "configureMaterial", id, PatternIngredient.class, iron);
        graph.connect(id, -1); graph.connect(id, 0); graph.connect(duplicate, -1);
        graph.setInputCount(new PatternRecipeGraph.Edge(id, 0), 7);
        var predicate = DataComponentPredicate.builder().expect(DataComponents.CUSTOM_NAME, Component.literal("Batch A")).build();
        var copper = ingredient("c:ingots/copper", 5, predicate);
        configure(graph, "configureMaterial", id, PatternIngredient.class, copper);
        assertEquals(ingredient("c:ingots/copper", 3, predicate), draft.inputs(-1).getFirst());
        assertEquals(ingredient("c:ingots/copper", 7, predicate), draft.inputs(0).getFirst());
        assertEquals(iron, draft.inputs(-1).get(1));
        assertEquals(copper, graph.get(id).material());
    }

    @Test void outputAndStageAreConfiguredInExistingNodesWithoutMovingThemOrFlatteningBook() throws Exception {
        var draft = new PatternRecipeDraft(); var graph = new PatternRecipeGraph(draft);
        long output = add(graph, 370, 80), stage = add(graph, 220, 150);
        var result = new PatternOutput(new StackKey(Items.IRON_INGOT.getDefaultInstance()), 4);
        configure(graph, "configureOutput", output, PatternOutput.class, result);
        configure(graph, "configureStage", stage, String.class, "press");
        assertEquals(370, graph.get(output).x); assertEquals(150, graph.get(stage).y);
        assertEquals(List.of(result), draft.snapshot().outputs());
        assertEquals(List.of(new PatternStage(0, "press", List.of())), ((SequencedPatternBody)draft.snapshot().body()).stages());
        long second = add(graph, 480, 80);
        configure(graph, "configureOutput", second, PatternOutput.class, new PatternOutput(new StackKey(Items.GOLD_NUGGET.getDefaultInstance()), 2));
        graph.removeOutput(0);
        assertEquals(0, graph.get(second).index); assertNull(graph.get(output));
        assertEquals(2, draft.snapshot().outputs().getFirst().count());
    }

    @Test void invalidStageAndRoleChangeLeaveExistingGraphAndDraftUntouched() throws Exception {
        var draft = new PatternRecipeDraft(); var graph = new PatternRecipeGraph(draft);
        long id = add(graph, 20, 20);
        assertThrows(IllegalArgumentException.class, () -> configure(graph, "configureStage", id, String.class, ""));
        assertEquals("UNASSIGNED", graph.get(id).kind.name()); assertTrue(draft.stages().isEmpty());
        var iron = ingredient("c:ingots/iron", 2, DataComponentPredicate.EMPTY);
        configure(graph, "configureMaterial", id, PatternIngredient.class, iron); graph.connect(id, -1);
        assertThrows(IllegalArgumentException.class, () -> configure(graph, "configureStage", id, String.class, "press"));
        assertEquals(List.of(iron), draft.inputs(-1)); assertTrue(draft.stages().isEmpty());
    }

    @Test void removingFirstAuthoredStageRetainsItsAddressForTheStandardRecipe() throws Exception {
        var draft=new PatternRecipeDraft();var graph=new PatternRecipeGraph(draft);
        long stage=add(graph,20,20),output=add(graph,180,20);
        configure(graph,"configureStage",stage,String.class,"press");
        configure(graph,"configureOutput",output,PatternOutput.class,new PatternOutput(new StackKey(Items.IRON_INGOT.getDefaultInstance()),2));
        graph.removeStage(0);
        assertEquals("press",((StandardPatternBody)draft.snapshot().body()).address());
        assertEquals(2,draft.snapshot().outputs().getFirst().count());
    }

    @Test void removingLastStageUsesItsEditedAddressRatherThanTheStaleFallback() throws Exception {
        var draft=new PatternRecipeDraft();var graph=new PatternRecipeGraph(draft);
        long stage=add(graph,20,20),output=add(graph,180,20);
        configure(graph,"configureStage",stage,String.class,"press");
        configure(graph,"configureOutput",output,PatternOutput.class,new PatternOutput(new StackKey(Items.IRON_INGOT.getDefaultInstance()),2));
        draft.setAddress(0,"finish");graph.removeStage(0);
        assertEquals("finish",((StandardPatternBody)draft.snapshot().body()).address());
    }

    private static PatternIngredient ingredient(String tag, int count, DataComponentPredicate predicate) {
        return new PatternIngredient(null, ResourceLocation.parse(tag), count, predicate);
    }
    private static long add(PatternRecipeGraph graph, double x, double y) throws Exception {
        return (long) PatternRecipeGraph.class.getMethod("addNode", double.class, double.class).invoke(graph, x, y);
    }
    private static void configure(PatternRecipeGraph graph, String method, long id, Class<?> type, Object value) throws Exception {
        try { PatternRecipeGraph.class.getMethod(method, long.class, type).invoke(graph, id, value); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }
}
