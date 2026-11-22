package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.DynamicOps;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

/** Editable facts from a registered recipe, not a ROM page or a manufacturing instruction. */
public record RecipeDraftPreview(List<PatternIngredient> inputs, PatternOutput output, CraftingSlots grid) {
    public RecipeDraftPreview(List<PatternIngredient> inputs, PatternOutput output) { this(inputs, output, CraftingSlots.NONE); }
    private static final int WORKBENCH_SLOTS = 3 * 3;
    public enum Refusal { UNSUPPORTED_RECIPE, UNSUPPORTED_SELECTOR, GRID_REQUIRED, REMAINDER, CUSTOM_REMAINDER, TOO_LARGE }
    public static final class Refused extends IllegalArgumentException {
        private final Refusal reason;
        private Refused(Refusal reason) { super(reason.name()); this.reason = reason; }
        public Refusal reason() { return reason; }
    }

    public RecipeDraftPreview {
        inputs = List.copyOf(inputs);
        Objects.requireNonNull(output);
        grid.validate(inputs);
        if (inputs.isEmpty() || inputs.size() > WORKBENCH_SLOTS) throw new Refused(Refusal.TOO_LARGE);
        for (var input : inputs) if (input.count() != 1)
            throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
    }

    public static RecipeDraftPreview from(Recipe<?> recipe, HolderLookup.Provider registries) {
        boolean crafting = recipe.getClass() != StonecutterRecipe.class;
        int width = 0, height = 0;
        if (recipe.getClass() == ShapedRecipe.class) {
            var shaped = (ShapedRecipe)recipe;
            width = shaped.getWidth(); height = shaped.getHeight();
            if (width < 1 || height < 1 || width > 3 || height > 3 || shaped.getIngredients().size() != width * height)
                throw new Refused(Refusal.GRID_REQUIRED);
        } else if (crafting && recipe.getClass() != ShapelessRecipe.class) throw new Refused(Refusal.UNSUPPORTED_RECIPE);
        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() || ingredients.size() > WORKBENCH_SLOTS) throw new Refused(Refusal.TOO_LARGE);
        var selectors = new ArrayList<PatternIngredient>(ingredients.size());
        var slots = new ArrayList<Integer>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (width != 0 && width * height > 1) {
                if (ingredient == Ingredient.EMPTY) continue;
                slots.add(i % width + CraftingSlots.SIDE * (i / width));
            }
            if (ingredient.isCustom()) {
                selectors.add(componentSelector(ingredient, registries, crafting));
                continue;
            }
            var values = ingredient.getValues();
            if (values.length != 1) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
            switch (values[0]) {
                case Ingredient.ItemValue value -> {
                    var item = value.item().getItem();
                    if (crafting) requireNoRemainder(item);
                    selectors.add(new PatternIngredient(BuiltInRegistries.ITEM.getKey(item), null, 1, DataComponentPredicate.EMPTY));
                }
                case Ingredient.TagValue value -> {
                    if (crafting) {
                        var members = registries.lookupOrThrow(Registries.ITEM).get(value.tag())
                            .orElseThrow(() -> new Refused(Refusal.UNSUPPORTED_SELECTOR));
                        checkTagRemainders(members);
                    }
                    selectors.add(new PatternIngredient(null, value.tag().location(), 1, DataComponentPredicate.EMPTY));
                }
                default -> throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
            }
        }
        ItemStack result = recipe.getResultItem(registries);
        // Real shaped matching trims the player's inputs. An untrimmed custom construction is not equivalent.
        if (width * height > 1) {
            int cols = 0, rows = 0;
            for (int slot : slots) { cols |= 1 << (slot % 3); rows |= 1 << (slot / 3); }
            if ((cols & 1) == 0 || (rows & 1) == 0 || (cols >> (width-1)) == 0 || (rows >> (height-1)) == 0)
                throw new Refused(Refusal.GRID_REQUIRED);
        }
        return new RecipeDraftPreview(selectors, new PatternOutput(new StackKey(result), result.getCount()), new CraftingSlots(slots));
    }

    private static PatternIngredient componentSelector(Ingredient ingredient, HolderLookup.Provider registries, boolean crafting) {
        var custom = ingredient.getCustomIngredient();
        if (custom.getClass() != DataComponentIngredient.class) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
        var data = (DataComponentIngredient) custom;
        // ROM predicates require a subset. They cannot express "no other components", OR, or custom holder sets.
        if (data.isStrict()) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
        var items = data.items();
        if (items.getClass() == HolderSet.Named.class) {
            var key = items.unwrapKey().orElseThrow();
            if (registries.lookupOrThrow(Registries.ITEM).get(key).orElse(null) != items) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
            if (crafting) checkTagRemainders(items);
            return new PatternIngredient(null, key.location(), 1, data.components());
        }
        if (!(items instanceof HolderSet.Direct<Item>) || items.size() != 1) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
        var holder = items.get(0);
        var item = holder.value();
        if (holder != item.builtInRegistryHolder()) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
        if (crafting) requireNoRemainder(item);
        return new PatternIngredient(BuiltInRegistries.ITEM.getKey(item), null, 1, data.components());
    }

    private static void checkTagRemainders(HolderSet<Item> members) {
        if (members.size() == 0) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
        if (members.size() > PatternRepresentationBounds.MAX_ENTRIES) throw new Refused(Refusal.TOO_LARGE);
        for (var member : members) requireNoRemainder(member.value());
    }

    // Never call a modded hook on a sample stack and assume all component variants behave the same.
    // Inspect only the two known presence checks used by Recipe#getRemainingItems, never a player-supplied method.
    private static final ClassValue<Boolean> STATIC_PRESENCE = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("hasCraftingRemainingItem", ItemStack.class).getDeclaringClass() == IItemExtension.class
                    && type.getMethod("hasCraftingRemainingItem").getDeclaringClass() == Item.class;
            } catch (ReflectiveOperationException | SecurityException unknown) { return false; }
        }
    };
    private static void requireNoRemainder(Item item) {
        if (!STATIC_PRESENCE.get(item.getClass())) throw new Refused(Refusal.CUSTOM_REMAINDER);
        if (item.hasCraftingRemainingItem()) throw new Refused(Refusal.REMAINDER);
    }

    public String encode(HolderLookup.Provider registries) {
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        ItemStack stack = output.stack().stack().copyWithCount(output.count());
        var encoded = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
        if (!ItemStack.matches(stack, ItemStack.CODEC.parse(ops, encoded).getOrThrow()))
            throw new IllegalArgumentException("unsupported_output");
        JsonObject json;
        if (!grid.shaped() && inputs.size() == 1 && inputs.getFirst().components().equals(DataComponentPredicate.EMPTY))
            json = selector(inputs.getFirst(), ops); // C100 single-input format
        else {
            json = new JsonObject();
            var list = new JsonArray(inputs.size());
            for (var input : inputs) list.add(selector(input, ops));
            json.add("inputs", list);
        }
        json.add("output", encoded);
        if (grid.shaped()) json.add("crafting_slots", grid.json());
        return json.toString();
    }
    private static JsonObject selector(PatternIngredient input, DynamicOps<JsonElement> ops) {
        var json = new JsonObject();
        if (input.tagId() != null) json.addProperty("tag", input.tagId().toString());
        else json.addProperty("item", input.itemId().toString());
        if (!input.components().equals(DataComponentPredicate.EMPTY)) {
            var codec = DataComponentPredicate.CODEC;
            var encoded = codec.encodeStart(ops, input.components()).result().orElseThrow(() -> new Refused(Refusal.UNSUPPORTED_SELECTOR));
            var decoded = codec.parse(ops, encoded).result().orElseThrow(() -> new Refused(Refusal.UNSUPPORTED_SELECTOR));
            // The vanilla codec can silently omit transient fields. Compare by type, not iteration order.
            if (!decoded.asPatch().equals(input.components().asPatch())) throw new Refused(Refusal.UNSUPPORTED_SELECTOR);
            json.add("components", encoded);
        }
        return json;
    }
    private static PatternIngredient selector(JsonObject json, DynamicOps<JsonElement> ops) {
        boolean tag = json.has("tag");
        boolean components = json.has("components");
        if (json.size() != (components ? 2 : 1) || tag == json.has("item")) throw new IllegalArgumentException("invalid selector");
        ResourceLocation id = ResourceLocation.parse(json.get(tag ? "tag" : "item").getAsString());
        if (!tag && !BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("missing item");
        var predicate = components ? DataComponentPredicate.CODEC.parse(ops, json.get("components")).getOrThrow() : DataComponentPredicate.EMPTY;
        return new PatternIngredient(tag ? null : id, tag ? id : null, 1, predicate);
    }
    public static RecipeDraftPreview decode(String text, HolderLookup.Provider registries) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        var grid = json.has("crafting_slots") ? CraftingSlots.read(json.remove("crafting_slots")) : CraftingSlots.NONE;
        if (!json.has("output") || json.size() != 2 || grid.shaped() && !json.has("inputs")) throw new IllegalArgumentException("invalid preview");
        var ops = registries.createSerializationContext(JsonOps.INSTANCE);
        ItemStack output = ItemStack.CODEC.parse(ops, json.remove("output")).getOrThrow();
        var inputs = new ArrayList<PatternIngredient>();
        if (json.has("inputs")) {
            var list = json.getAsJsonArray("inputs");
            if (list.size() > WORKBENCH_SLOTS) throw new Refused(Refusal.TOO_LARGE);
            for (var entry : list) inputs.add(selector(entry.getAsJsonObject(), ops));
        } else inputs.add(selector(json, ops));
        return new RecipeDraftPreview(inputs, new PatternOutput(new StackKey(output), output.getCount()), grid);
    }
}
