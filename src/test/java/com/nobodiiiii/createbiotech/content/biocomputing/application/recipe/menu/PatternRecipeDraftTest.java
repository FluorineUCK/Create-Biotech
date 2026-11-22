package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeDraftPreview;
import net.minecraft.world.item.crafting.*;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternRecipeDraftTest {
	private static SpaceAddress worldAddress(RecipeSourceLocation source) {
		return new SpaceAddress(source.dimension(), source.subLevelId(), source.localPos());
	}

    @BeforeAll static void bootstrap() throws Exception {
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }

    @Test void stonecuttingImportKeepsTheSelectorAndTheActualOutputPatch() {
        var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, ResourceLocation.parse("c:stones"));
        var output = new ItemStack(Items.STONE_SLAB, 2);
        output.set(DataComponents.CUSTOM_NAME, Component.literal("cut"));
        output.remove(DataComponents.RARITY);
        var recipe = new net.minecraft.world.item.crafting.StonecutterRecipe("", net.minecraft.world.item.crafting.Ingredient.of(tag), output);
        var registries = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var source = RecipeDraftPreview.from(recipe, registries);
        var preview = RecipeDraftPreview.decode(source.encode(registries), registries);
        assertEquals(tag.location(), preview.inputs().getFirst().tagId());
        assertEquals(DataComponentPredicate.EMPTY, preview.inputs().getFirst().components());
        assertEquals(2, preview.output().count());
        assertTrue(ItemStack.isSameItemSameComponents(output, preview.output().stack().stack()));
    }

    @Test void previewAndBookPreserveRequiredComponentsWithoutRequiringAnExactStack() {
        var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var name = Component.literal("calibrated");
        var ingredient = net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(false,
            DataComponents.CUSTOM_NAME, name, Items.PAPER);
        var recipe = new StonecutterRecipe("", ingredient, new ItemStack(Items.MAP));
        var preview = RecipeDraftPreview.from(recipe, access);
        var draft = new PatternRecipeDraft();
        draft.initialize(RecipeDraftPreview.decode(preview.encode(access), access));
        draft.setAddress(-1, "print");
        var json = com.google.gson.JsonParser.parseString(draft.snapshot().toJson(access)).getAsJsonObject();
        var material = json.getAsJsonArray("inputs").get(0).getAsJsonObject();
        assertTrue(material.getAsJsonObject("components").has("minecraft:custom_name"));
        var selector = draft.inputs(-1).getFirst();
        var paper = new ItemStack(Items.PAPER);
        assertFalse(selector.matches(paper));
        paper.set(DataComponents.CUSTOM_NAME, name);
        paper.set(DataComponents.RARITY, net.minecraft.world.item.Rarity.EPIC);
        assertTrue(ingredient.test(paper) && selector.matches(paper));
        var other = new ItemStack(Items.STICK);
        other.set(DataComponents.CUSTOM_NAME, name);
        assertFalse(selector.matches(other));
    }

    @Test void stonecuttingImportNeverPicksOneOfSeveralAlternatives() {
        var alternatives = net.minecraft.world.item.crafting.Ingredient.of(Items.STONE, Items.ANDESITE);
        var recipe = new net.minecraft.world.item.crafting.StonecutterRecipe("", alternatives, new ItemStack(Items.STONE_SLAB));
        assertRefusal(RecipeDraftPreview.Refusal.UNSUPPORTED_SELECTOR, recipe);
    }

    @Test void componentIngredientCannotHideStrictEqualityAlternativesOrRemainders() {
        var predicate = DataComponentPredicate.builder().expect(DataComponents.CUSTOM_NAME, Component.literal("reserved")).build();
        for (var unsupported : List.of(
                net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(true, predicate, Items.PAPER),
                net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(false, predicate, Items.PAPER, Items.MAP))) {
            assertRefusal(RecipeDraftPreview.Refusal.UNSUPPORTED_SELECTOR, new StonecutterRecipe("", unsupported, new ItemStack(Items.MAP)));
        }
        var inputs = net.minecraft.core.NonNullList.<Ingredient>create();
        inputs.add(net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(false, predicate, Items.HONEY_BOTTLE));
        assertRefusal(RecipeDraftPreview.Refusal.REMAINDER,
            new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.SUGAR), inputs));
    }

    @Test void aTransientComponentMayNotDisappearInTheWirePreview() {
        var transientType = net.minecraft.core.component.DataComponentType.<Integer>builder()
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.INT).build();
        var ingredient = net.neoforged.neoforge.common.crafting.DataComponentIngredient.of(false, transientType, 9, Items.PAPER);
        var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var preview = RecipeDraftPreview.from(new StonecutterRecipe("", ingredient, new ItemStack(Items.MAP)), access);
        assertEquals(RecipeDraftPreview.Refusal.UNSUPPORTED_SELECTOR,
            assertThrows(RecipeDraftPreview.Refused.class, () -> preview.encode(access)).reason());
    }

    @Test void workbenchImportKeepsRepeatedIngredientsWithoutConsumingOrMergingThem() {
        var recipe = shapeless(Items.SUGAR, Items.COCOA_BEANS, Items.SUGAR);
        var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var source = RecipeDraftPreview.from(recipe, access);
        var draft = new PatternRecipeDraft();
        draft.initialize(RecipeDraftPreview.decode(source.encode(access), access));
        assertEquals(List.of("minecraft:sugar", "minecraft:cocoa_beans", "minecraft:sugar"),
            draft.inputs(-1).stream().map(i -> i.itemId().toString()).toList());
        assertEquals(List.of(1,1,1), draft.inputs(-1).stream().map(PatternIngredient::count).toList());
        assertEquals(2, draft.outputs().getFirst().count());
        assertThrows(IllegalStateException.class, () -> draft.initialize(source));
    }
    @Test void shapedWorkbenchRetainsHolesDuplicateIngredientsAndBookCoordinates() {
        var access = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var key = Map.of('A', Ingredient.of(Items.STONE), 'B', Ingredient.of(Items.STICK));
        var shaped = new ShapedRecipe("", CraftingBookCategory.MISC, ShapedRecipePattern.of(key, " A", "BA"), new ItemStack(Items.LEVER));
        var original = RecipeDraftPreview.from(shaped, access);
        var draft = new PatternRecipeDraft();
        draft.initialize(RecipeDraftPreview.decode(original.encode(access), access));
        draft.setAddress(-1, "bench");
        var raw = draft.snapshot().toJson(access);
        var json = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
        assertEquals("[1,3,4]", json.get("crafting_slots").toString());
        var parser = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser(access);
        var restored = assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult.Valid.class, parser.parse(source(), raw)).pattern();
        assertEquals(raw, new PatternRecipeDraft(restored).snapshot().toJson(access));
        var cached = com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs.saveRecord(restored,access);
        assertEquals(restored,com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs.loadRecord(cached,access).orElseThrow());
        cached.getCompound("Body").putIntArray("CraftingSlots",new int[]{1,1,4});
        assertTrue(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs.loadRecord(cached,access).isEmpty());
        for(String slots: List.of("[]","[1,3]","[1,1,4]","[1,3,9]","[1,3,4.5]")) {
            json.add("crafting_slots",com.google.gson.JsonParser.parseString(slots));
            assertInstanceOf(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult.Invalid.class,parser.parse(source(),json.toString()));
        }
        draft.removeInput(-1,1);
        assertEquals(1,draft.inputAtCell(4));
        assertEquals(-1,draft.inputAtCell(3));
        assertThrows(IllegalArgumentException.class,()->draft.addStage("other"));
        assertThrows(IllegalArgumentException.class,()->draft.setInputCount(-1,0,2));
        draft.detachGrid(); draft.setInputCount(-1,0,2);
        var unordered = com.google.gson.JsonParser.parseString(draft.snapshot().toJson(access)).getAsJsonObject();
        assertFalse(unordered.has("crafting_slots"));
        assertEquals(2,unordered.get("version").getAsInt());
    }

    @Test void workbenchImportRefusesLostContainersAndDynamicResults() {
        assertRefusal(RecipeDraftPreview.Refusal.REMAINDER, shapeless(Items.WATER_BUCKET, Items.SUGAR));
        var key = Map.of('A', Ingredient.of(Items.STONE));
        var shaped = new ShapedRecipe("", CraftingBookCategory.MISC, ShapedRecipePattern.of(key, "AA"), new ItemStack(Items.STONE_SLAB));
        assertEquals(2, RecipeDraftPreview.from(shaped, net.minecraft.core.RegistryAccess.EMPTY).inputs().size());
        assertRefusal(RecipeDraftPreview.Refusal.UNSUPPORTED_RECIPE, new MapCloningRecipe(CraftingBookCategory.MISC));
    }
    private static void assertRefusal(RecipeDraftPreview.Refusal expected, Recipe<?> recipe) {
        assertEquals(expected, assertThrows(RecipeDraftPreview.Refused.class,
            () -> RecipeDraftPreview.from(recipe, net.minecraft.core.RegistryAccess.EMPTY)).reason());
    }
    private static net.minecraft.world.item.crafting.ShapelessRecipe shapeless(net.minecraft.world.item.Item... items) {
        var inputs = net.minecraft.core.NonNullList.<net.minecraft.world.item.crafting.Ingredient>create();
        for (var item : items) inputs.add(net.minecraft.world.item.crafting.Ingredient.of(item));
        return new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
            new ItemStack(Items.COOKIE,2), inputs);
    }

    @Test void quantityEditPreservesTagPredicateAndOriginalRecipe() {
        var predicate = DataComponentPredicate.builder().expect(DataComponents.CUSTOM_NAME, Component.literal("A")).build();
        var ingredient = new PatternIngredient(null, ResourceLocation.parse("c:ingots/iron"), 2, predicate);
        PatternRecord original = recipe(List.of(ingredient), new StandardPatternBody("smelter"));
        var draft = new PatternRecipeDraft(original);
        draft.setInputCount(-1, 0, 5);
        PatternIngredient changed = draft.inputs(-1).getFirst();
        assertEquals(5, changed.count());
        assertEquals(ResourceLocation.parse("c:ingots/iron"), changed.tagId());
        assertEquals(predicate, changed.components());
        assertEquals(2, original.inputs().getFirst().count());
    }

    @Test void outputQuantityEditPreservesExactComponentsAndOtherOutputs() {
        ItemStack named = new ItemStack(Items.IRON_INGOT);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Refined"));
        var original = new PatternRecord(UUID.randomUUID(), source(), List.of(),
            List.of(new PatternOutput(new StackKey(named), 1), new PatternOutput(new StackKey(new ItemStack(Items.GOLD_NUGGET)), 2)),
            new StandardPatternBody("press"), OptionalInt.of(7));
        var draft = new PatternRecipeDraft(original);
        draft.setOutputCount(0, 4);
        assertEquals(Component.literal("Refined"), draft.outputs().getFirst().stack().stack().get(DataComponents.CUSTOM_NAME));
        assertEquals(4, draft.outputs().getFirst().count());
        assertEquals(original.outputs().get(1), draft.outputs().get(1));
        assertEquals(OptionalInt.of(7), draft.snapshot().priorityOverride());
    }

    @Test void movingAndRemovingStagesKeepsContentsAndContiguousIndexes() {
        var one = new PatternIngredient(ResourceLocation.parse("minecraft:iron_ingot"), null, 1, DataComponentPredicate.EMPTY);
        var original = recipe(List.of(), new SequencedPatternBody(List.of(
            new PatternStage(0, "cut", List.of(one)), new PatternStage(1, "press", List.of()))));
        var draft = new PatternRecipeDraft(original);
        draft.moveStage(0, 1);
        assertEquals(new PatternStage(0, "press", List.of()), draft.stages().getFirst());
        assertEquals(new PatternStage(1, "cut", List.of(one)), draft.stages().get(1));
        draft.removeStage(0);
        assertEquals(List.of(new PatternStage(0, "cut", List.of(one))), draft.stages());
        assertEquals("cut", ((SequencedPatternBody) original.body()).stages().getFirst().address());
    }

    @Test void saveRefusesNoOutputsAndKeepsDraftEditable() {
        var draft = new PatternRecipeDraft(recipe(List.of(), new StandardPatternBody("press")));
        draft.removeOutput(0);
        assertThrows(IllegalArgumentException.class, draft::snapshot);
        assertEquals(List.of(), draft.outputs());
    }

    @Test void emptyDraftCanBeAuthoredWithoutSyntheticPatternRecord() throws Exception {
        var draft = PatternRecipeDraft.class.getConstructor().newInstance();
        assertTrue(draft.inputs(-1).isEmpty());
        assertTrue(draft.outputs().isEmpty());
        assertThrows(IllegalArgumentException.class, draft::snapshot);
        draft.setAddress(-1, "press");
        draft.setOutput(0, new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT)), 2));
        assertEquals("press", ((StandardPatternBody) draft.snapshot().body()).address());
    }
    @Test void graphStageEditsPreserveDuplicateTagPredicatesAndUnrelatedOutputs() {
        var predicate = DataComponentPredicate.builder().expect(DataComponents.CUSTOM_NAME, Component.literal("Batch A")).build();
        var first = new PatternIngredient(null, ResourceLocation.parse("c:ingots/iron"), 2, predicate);
        var second = new PatternIngredient(null, ResourceLocation.parse("c:ingots/iron"), 4, DataComponentPredicate.EMPTY);
        var original = recipe(List.of(), new SequencedPatternBody(List.of(
            new PatternStage(0, "cut", List.of(first, second)), new PatternStage(1, "press", List.of(second)))));
        var draft = new PatternRecipeDraft(original);
        draft.setOutput(1, new PatternOutput(new StackKey(new ItemStack(Items.GOLD_NUGGET)), 3));
        var graph = new PatternRecipeGraph(draft);
        var edge = graph.edges().getFirst();
        graph.setInputCount(edge, 9);
        graph.moveStage(0, 1);
        assertEquals("cut", draft.address(1));
        assertEquals(9, draft.inputs(1).getFirst().count());
        assertEquals(predicate, draft.inputs(1).getFirst().components());
        assertEquals(second, draft.inputs(1).get(1));
        graph.disconnect(edge.materialId(), 1);
        assertEquals(List.of(second), draft.inputs(1));
        assertEquals(List.of(second), draft.inputs(0));
        assertEquals(2, draft.snapshot().outputs().size());
        assertEquals(2, ((SequencedPatternBody)original.body()).stages().getFirst().inputs().getFirst().count());
    }
    @Test void lastStageRemovalReturnsToEditableCommonRecipeWithoutLosingOutputs() {
        var original = recipe(List.of(), new SequencedPatternBody(List.of(new PatternStage(0, "cut", List.of()))));
        var draft = new PatternRecipeDraft(original);var graph = new PatternRecipeGraph(draft);
        graph.removeStage(0);
        assertFalse(draft.sequenced());assertEquals("cut", draft.address(-1));
        assertEquals(1, draft.snapshot().outputs().size());
        assertEquals("cut", ((StandardPatternBody)draft.snapshot().body()).address());
    }

    @Test void readRefreshAdvancesFenceAndRetainsSameUnsavedRecipeWithoutSubmittingIt() {
        var original = recipe(List.of(), new StandardPatternBody("press"));
        var draft = new PatternRecipeDraft(original);draft.setOutputCount(0, 7);
        var fence = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence(
            UUID.randomUUID(), source().shelf(), UUID.randomUUID(), 2);
        var browser = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserClientSession(1, fence);
        var request = browser.request(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter.EMPTY, 0, Optional.empty());
        var latest = fence.withGeneration(3);
        assertTrue(browser.accept(new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket(1,request.intentId(),
            com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage.empty(
                com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage.Status.STALE,latest,0,false),
            com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserDiagnostics.empty())));
        assertTrue(browser.needsRefresh());
        var retry=browser.request(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter.EMPTY,0,Optional.empty());
        assertEquals(latest,retry.fence());
        assertTrue(browser.accept(new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket(1,retry.intentId(),
            com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage.empty(
                com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage.Status.OK,latest,0,false),
            com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserDiagnostics.empty())));
        assertFalse(browser.needsRefresh());
        assertEquals(7,draft.snapshot().outputs().getFirst().count());
        assertEquals(1,original.outputs().getFirst().count());
    }
    private static PatternRecord recipe(List<PatternIngredient> inputs, PatternBody body) {
        return new PatternRecord(UUID.randomUUID(), source(), inputs,
            List.of(new PatternOutput(new StackKey(new ItemStack(Items.IRON_INGOT)), 1)), body, OptionalInt.empty());
    }
    private static PatternPageKey source() { return new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 0, 0); }
}



