package com.nobodiiiii.createbiotech.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.beltsurface.BeltSurface;
import com.nobodiiiii.createbiotech.content.beltsurface.BeltFunnelStateExtensions;
import com.simibubi.create.foundation.model.BakedModelWrapperWithData;
import com.simibubi.create.foundation.model.BakedQuadHelper;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelData.Builder;
import net.neoforged.neoforge.client.model.data.ModelProperty;

public class SlimeBeltFunnelModel extends BakedModelWrapperWithData {

	private static final ModelProperty<Direction> OUTWARD_NORMAL_PROPERTY = new ModelProperty<>();

	public SlimeBeltFunnelModel(BakedModel originalModel) {
		super(originalModel);
	}

	@Override
	protected Builder gatherModelData(Builder builder, BlockAndTintGetter world, BlockPos pos, BlockState state,
		ModelData blockEntityData) {
		Direction outwardNormal = BeltFunnelStateExtensions.tiltedOutwardNormal(state);
		return outwardNormal == null ? builder : builder.with(OUTWARD_NORMAL_PROPERTY, outwardNormal);
	}

	@Override
	public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
		@NotNull RandomSource rand, @NotNull ModelData extraData, @Nullable RenderType renderType) {
		Direction outwardNormal = extraData.get(OUTWARD_NORMAL_PROPERTY);
		if (outwardNormal == null)
			return super.getQuads(state, side, rand, extraData, renderType);
		if (state == null)
			return Collections.emptyList();

		List<BakedQuad> templateQuads = super.getQuads(state, side, rand, extraData, renderType);
		if (templateQuads.isEmpty())
			return templateQuads;

		List<BakedQuad> quads = new ArrayList<>(templateQuads.size());
		for (BakedQuad templateQuad : templateQuads) {
			int[] transformedVertices = templateQuad.getVertices().clone();
			for (int vertex = 0; vertex < 4; vertex++) {
				Vec3 transformedPosition = BeltSurface.transformPosition(
					BakedQuadHelper.getXYZ(transformedVertices, vertex), outwardNormal);
				Vec3 transformedNormal = BeltSurface.transformDirection(
					BakedQuadHelper.getNormalXYZ(transformedVertices, vertex), outwardNormal)
					.normalize();
				BakedQuadHelper.setXYZ(transformedVertices, vertex, transformedPosition);
				BakedQuadHelper.setNormalXYZ(transformedVertices, vertex, transformedNormal);
			}

			Vec3 quadNormal = BeltSurface.transformDirection(
				Vec3.atLowerCornerOf(templateQuad.getDirection().getNormal()), outwardNormal)
				.normalize();
			quads.add(new BakedQuad(transformedVertices, templateQuad.getTintIndex(),
				BeltSurface.nearestDirection(quadNormal), templateQuad.getSprite(), templateQuad.isShade()));
		}

		return quads;
	}
}
