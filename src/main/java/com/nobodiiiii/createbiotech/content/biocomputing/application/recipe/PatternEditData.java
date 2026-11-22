package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import com.google.gson.*;
import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;

/** Editable Pattern v2 facts. Source identity deliberately is not client-editable. */
public record PatternEditData(List<PatternIngredient> inputs, List<PatternOutput> outputs,
	PatternBody body, OptionalInt priorityOverride) {
	public PatternEditData {
		inputs = List.copyOf(inputs); outputs = List.copyOf(outputs);
		if (outputs.isEmpty()) throw new IllegalArgumentException("Pattern must have output");
		body = Objects.requireNonNull(body); priorityOverride = Objects.requireNonNull(priorityOverride);
		PatternRepresentationBounds.requireRecordFits(inputs, outputs, body);
		PatternPackageCapacity.requirePatternFits(inputs, body);
	}
	public String toJson(HolderLookup.Provider registries) {
		JsonObject root = new JsonObject();
		if (body instanceof StandardPatternBody s && s.grid().shaped()) {
			root.addProperty("version", 3); root.add("crafting_slots", s.grid().json());
		} else root.addProperty("version", 2);
		root.addProperty("type", body instanceof StandardPatternBody ? "standard" : "sequenced");
		root.add("inputs", ingredients(inputs, registries));
		JsonArray outs = new JsonArray();
		for (PatternOutput output : outputs) {
			JsonObject value = new JsonObject(); var stack = output.stack().stack();
			value.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			value.addProperty("count", output.count());
			DataComponentPatch patch = stack.getComponentsPatch();
			if (!patch.isEmpty()) value.add("components", encode(DataComponentPatch.CODEC, patch, registries));
			outs.add(value);
		}
		root.add("outputs", outs);
		if (body instanceof StandardPatternBody standard) root.addProperty("address", standard.address());
		else {
			JsonArray stages = new JsonArray();
			for (PatternStage stage : ((SequencedPatternBody) body).stages()) {
				JsonObject value = new JsonObject(); value.addProperty("address", stage.address());
				value.add("inputs", ingredients(stage.inputs(), registries)); stages.add(value);
			}
			root.add("stages", stages);
		}
		priorityOverride.ifPresent(value -> root.addProperty("priority", value));
		return root.toString();
	}
	private static JsonArray ingredients(List<PatternIngredient> ingredients, HolderLookup.Provider registries) {
		JsonArray values = new JsonArray();
		for (PatternIngredient ingredient : ingredients) {
			JsonObject value = new JsonObject();
			value.addProperty(ingredient.itemId() != null ? "item" : "tag",
				(ingredient.itemId() != null ? ingredient.itemId() : ingredient.tagId()).toString());
			value.addProperty("count", ingredient.count());
			if (!ingredient.components().equals(DataComponentPredicate.EMPTY))
				value.add("components", encode(DataComponentPredicate.CODEC, ingredient.components(), registries));
			values.add(value);
		}
		return values;
	}
	private static <T> JsonElement encode(Codec<T> codec, T value, HolderLookup.Provider registries) {
		return codec.encodeStart(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), value)
			.getOrThrow(error -> new IllegalArgumentException("Cannot encode Pattern components: " + error));
	}
}
