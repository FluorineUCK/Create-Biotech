package com.nobodiiiii.createbiotech.content.biocomputing.application.production.material;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Registered exact-item validation for checked production dispatch, not general data reading. */
public final class DispatchManifest {
	private DispatchManifest() {}

	public static Map<StackKey, Long> decode(List<OrderedIngredient> ingredients) {
		LinkedHashMap<StackKey, Long> manifest = new LinkedHashMap<>();
		for (OrderedIngredient ingredient : List.copyOf(
			Objects.requireNonNull(ingredients, "ingredients"))) {
			StackKey exact = ingredient.stack();
			Item item = exact.stack().getItem();
			ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
			if (!BuiltInRegistries.ITEM.containsKey(key)
				|| BuiltInRegistries.ITEM.get(key) != item || item == Items.AIR)
				throw new IllegalArgumentException("Air is not a dispatch item");
			manifest.merge(exact, ingredient.amount(), Math::addExact);
		}
		if (manifest.isEmpty()) throw new IllegalArgumentException("Dispatch manifest is empty");
		return Map.copyOf(manifest);
	}
}
