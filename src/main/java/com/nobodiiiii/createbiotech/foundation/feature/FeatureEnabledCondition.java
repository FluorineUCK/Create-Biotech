package com.nobodiiiii.createbiotech.foundation.feature;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.neoforged.neoforge.common.conditions.ICondition;

public record FeatureEnabledCondition(CBFeature feature) implements ICondition {
	public static final MapCodec<FeatureEnabledCondition> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		CBFeature.CODEC.fieldOf("feature").forGetter(FeatureEnabledCondition::feature)
	).apply(instance, FeatureEnabledCondition::new));

	@Override
	public boolean test(IContext context) {
		return feature.isEnabled();
	}

	@Override
	public MapCodec<? extends ICondition> codec() {
		return CODEC;
	}
}
