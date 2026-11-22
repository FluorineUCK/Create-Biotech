package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.logistics.box.PackageItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

public final class PatternPackageCapacity {
	private PatternPackageCapacity() {}

	public static void requirePatternFits(List<PatternIngredient> envelopeInputs, PatternBody body) {
		if (body instanceof StandardPatternBody) {
			requireOneOperationFits(envelopeInputs);
			return;
		}
		SequencedPatternBody sequenced = (SequencedPatternBody) body;
		try {
			List<PatternIngredient> first = new ArrayList<>(Math.addExact(envelopeInputs.size(),
				sequenced.stages().getFirst().inputs().size()));
			first.addAll(envelopeInputs);
			first.addAll(sequenced.stages().getFirst().inputs());
			requireOneOperationFits(first);
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Pattern package calculation overflow", overflow);
		}
		for (int index = 1; index < sequenced.stages().size(); index++)
			requireOneOperationFits(sequenced.stages().get(index).inputs());
	}

	static void requireOneOperationFits(List<PatternIngredient> inputs) {
		if (!isExactCompatible(inputs))
			return;
		Map<StackKey, Integer> totals = new HashMap<>();
		try {
			for (PatternIngredient input : inputs) {
				ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(input.itemId()));
				stack.applyComponentsAndValidate(input.components().asPatch());
				if (!input.components().test(stack))
					throw new IllegalArgumentException("Pattern input components are not exact-compatible");
				totals.merge(new StackKey(stack), input.count(), Math::addExact);
			}
			int slots = 0;
			for (Map.Entry<StackKey, Integer> entry : totals.entrySet()) {
				int maximum = entry.getKey().stack().getMaxStackSize();
				if (maximum <= 0)
					throw new IllegalArgumentException("Pattern input cannot be packaged");
				slots = Math.addExact(slots, 1 + (entry.getValue() - 1) / maximum);
			}
			if (slots > PackageItem.SLOTS)
				throw new IllegalArgumentException("One Pattern operation exceeds Create package capacity");
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("Pattern package calculation overflow", overflow);
		}
	}

	private static boolean isExactCompatible(List<PatternIngredient> inputs) {
		return inputs.stream().allMatch(input -> input.itemId() != null);
	}
}
