package com.nobodiiiii.createbiotech.content.powerbelt;

import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.client.render.CBBeltRenderHelper;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.block.state.BlockState;

public class PowerBeltRenderer extends SafeBlockEntityRenderer<PowerBeltBlockEntity> {

	public PowerBeltRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	protected void renderSafe(PowerBeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		BlockState blockState = be.getBlockState();
		if (!blockState.is(CBBlocks.POWER_BELT.get()))
			return;
		CBBeltRenderHelper.renderSurface(be, blockState, ms, buffer, light,
			PowerBeltRenderer::getSpriteShiftEntry, 0);
	}

	public static SpriteShiftEntry getSpriteShiftEntry(boolean diagonal, boolean bottom) {
		return diagonal ? PowerBeltSpriteShifts.BELT_DIAGONAL
			: bottom ? PowerBeltSpriteShifts.BELT_OFFSET : PowerBeltSpriteShifts.BELT;
	}
}
