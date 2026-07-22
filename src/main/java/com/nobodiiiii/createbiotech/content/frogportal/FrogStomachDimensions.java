package com.nobodiiiii.createbiotech.content.frogportal;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Holds the {@link ResourceKey} for the Frog Stomach (青蛙胃袋) dimension. The dimension itself is
 * defined by datapack JSON under {@code data/create_biotech/dimension[_type]/frog_stomach.json} and
 * is loaded automatically at server start; this key is used to resolve the {@code ServerLevel} at
 * runtime via {@code server.getLevel(FROG_STOMACH)}.
 */
public final class FrogStomachDimensions {

	public static final ResourceKey<Level> FROG_STOMACH =
		ResourceKey.create(Registries.DIMENSION, CreateBiotech.asResource("frog_stomach"));

	private FrogStomachDimensions() {}
}
