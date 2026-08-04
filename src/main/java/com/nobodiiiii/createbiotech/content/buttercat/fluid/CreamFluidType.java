package com.nobodiiiii.createbiotech.content.buttercat.fluid;

import com.simibubi.create.AllFluids;

import net.createmod.catnip.theme.Color;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.FluidStack;

import org.joml.Vector3f;

public class CreamFluidType extends AllFluids.TintedFluidType {
	private final Vector3f fogColor;

	public CreamFluidType(Properties properties, ResourceLocation stillTexture, ResourceLocation flowingTexture) {
		super(properties, stillTexture, flowingTexture);
		fogColor = new Color(14147267, false).asVectorF();
	}

	@Override
	protected int getTintColor(FluidStack stack) {
		return NO_TINT;
	}

	@Override
	protected int getTintColor(FluidState state, BlockAndTintGetter world, BlockPos pos) {
		return NO_TINT;
	}

	@Override
	protected Vector3f getCustomFogColor() {
		return fogColor;
	}

	@Override
	protected float getFogDistanceModifier() {
		return 1F / 16F;
	}
}
