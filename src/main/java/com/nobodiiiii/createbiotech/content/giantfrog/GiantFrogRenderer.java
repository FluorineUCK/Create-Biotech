package com.nobodiiiii.createbiotech.content.giantfrog;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.foundation.render.EntityRenderHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class GiantFrogRenderer implements BlockEntityRenderer<GiantFrogBlockEntity> {
	@Nullable
	private Frog cachedFrog;
	@Nullable
	private ClientLevel cachedLevel;

	public GiantFrogRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public void render(GiantFrogBlockEntity blockEntity, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		Frog frog = getOrCreateFrog(blockEntity.getLevel());
		if (frog == null)
			return;

		Direction facing = getFacing(blockEntity.getBlockState());
		updateAnimationState(blockEntity, frog);
		float animationAge = blockEntity.getEatAnimationAge(partialTick);
		int animationTicks = Mth.floor(animationAge);
		float animationPartialTicks = animationAge - animationTicks;

		poseStack.pushPose();
		poseStack.translate(0.5d, 0.0d, 0.5d);
		poseStack.scale(GiantFrogBlock.FROG_SCALE, GiantFrogBlock.FROG_SCALE, GiantFrogBlock.FROG_SCALE);
		EntityRenderHelper.render(EntityRenderHelper.settings(frog)
			.packedLight(packedLight)
			.partialTicks(animationPartialTicks)
			.ticks(animationTicks)
			.face(facing)
			.dispatcherYaw(facing.toYRot())
			.flushBuffers(false), poseStack, buffer);
		poseStack.popPose();
	}

	private void updateAnimationState(GiantFrogBlockEntity blockEntity, Frog frog) {
		frog.jumpAnimationState.stop();
		frog.croakAnimationState.stop();
		frog.swimIdleAnimationState.stop();

		if (!blockEntity.isEating()) {
			frog.tongueAnimationState.stop();
			return;
		}

		frog.tongueAnimationState.start(0);
	}

	private Direction getFacing(BlockState state) {
		return state.hasProperty(GiantFrogBlock.FACING) ? state.getValue(GiantFrogBlock.FACING) : Direction.SOUTH;
	}

	@Nullable
	private Frog getOrCreateFrog(@Nullable Level level) {
		ClientLevel clientLevel = level instanceof ClientLevel cl ? cl : Minecraft.getInstance().level;
		if (clientLevel == null)
			return null;

		if (cachedFrog == null || cachedLevel != clientLevel) {
			cachedLevel = clientLevel;
			cachedFrog = EntityType.FROG.create(clientLevel);
			if (cachedFrog == null)
				return null;
			cachedFrog.setNoAi(true);
			cachedFrog.setSilent(true);
			cachedFrog.setOnGround(true);
		}

		cachedFrog.tickCount = 0;
		cachedFrog.hurtTime = 0;
		cachedFrog.deathTime = 0;
		return cachedFrog;
	}
}
