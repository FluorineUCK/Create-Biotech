package com.nobodiiiii.createbiotech.client.render;

import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.foundation.block.CBBeltPlacementSegment;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/** Buffered-renderer counterpart of {@link CBBeltVisual}. */
public final class CBBeltRenderHelper {

	private CBBeltRenderHelper() {}

	public static <T extends KineticBlockEntity & CBBeltPlacementSegment> void renderSurface(T blockEntity,
		BlockState blockState, PoseStack poseStack, MultiBufferSource buffer, int light,
		CBBeltVisual.SpriteProvider sprites, float verticalSurfaceOffset) {
		CBBeltRenderState state = CBBeltRenderState.of(blockState);
		PoseStack localTransforms = new PoseStack();
		localTransforms.translate(0, state.surfaceYOffset(verticalSurfaceOffset), 0);
		state.transformBufferedModel(TransformStack.of(localTransforms));
		VertexConsumer consumer = buffer.getBuffer(RenderType.solid());
		float renderTick = AnimationTickHolder.getRenderTime(blockEntity.getLevel());

		for (boolean bottom : Iterate.trueAndFalse) {
			PartialModel partial = BeltRenderer.getBeltPartial(state.diagonal(), state.bufferedStart(),
				state.bufferedEnd(), bottom);
			SuperByteBuffer beltBuffer = CachedBuffers.partial(partial, blockState).light(light);
			SpriteShiftEntry spriteShift = sprites.get(state.diagonal(), bottom);
			TextureAtlasSprite target = spriteShift.getTarget();
			if (target != null) {
				double scroll = state.bufferedScroll(blockEntity.getSpeed(), renderTick, bottom);
				scroll -= Math.floor(scroll);
				float scrollFactor = state.diagonal() ? 3f / 8f : .5f;
				scroll *= (target.getV1() - target.getV0()) * scrollFactor;
				beltBuffer.shiftUVScrolling(spriteShift, (float) scroll);
			}
			beltBuffer.transform(localTransforms).renderInto(poseStack, consumer);
			if (state.diagonal())
				break;
		}

		if (blockEntity.createBiotech$hasPulley())
			renderPulley(blockEntity, blockState, poseStack, consumer, light, state.pulleyOrientation());
	}

	private static void renderPulley(KineticBlockEntity blockEntity, BlockState blockState, PoseStack poseStack,
		VertexConsumer consumer, int light, Direction direction) {
		Supplier<PoseStack> matrices = () -> {
			PoseStack stack = new PoseStack();
			var transform = TransformStack.of(stack);
			transform.center();
			if (direction.getAxis() == Direction.Axis.X)
				transform.rotateYDegrees(90);
			if (direction.getAxis() == Direction.Axis.Y)
				transform.rotateXDegrees(90);
			transform.rotateXDegrees(90);
			transform.uncenter();
			return stack;
		};
		SuperByteBuffer pulley =
			CachedBuffers.partialDirectional(AllPartialModels.BELT_PULLEY, blockState, direction, matrices);
		KineticBlockEntityRenderer.standardKineticRotationTransform(pulley, blockEntity, light)
			.renderInto(poseStack, consumer);
	}
}
