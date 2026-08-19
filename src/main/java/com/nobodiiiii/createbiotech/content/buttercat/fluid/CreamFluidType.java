package com.nobodiiiii.createbiotech.content.buttercat.fluid;

import com.nobodiiiii.createbiotech.foundation.fluid.CBFluidType;

import net.createmod.catnip.theme.Color;
import net.minecraft.resources.ResourceLocation;

import org.joml.Vector3f;

public class CreamFluidType extends CBFluidType {
	private final Vector3f fogColor;

	public CreamFluidType(Properties properties, ResourceLocation stillTexture, ResourceLocation flowingTexture) {
		super(properties, stillTexture, flowingTexture);
		fogColor = new Color(14147267, false).asVectorF();
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
