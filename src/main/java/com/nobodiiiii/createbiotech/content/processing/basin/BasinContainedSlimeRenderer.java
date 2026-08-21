package com.nobodiiiii.createbiotech.content.processing.basin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.foundation.render.BoundedRenderEntityCache;
import com.nobodiiiii.createbiotech.foundation.render.EntityRenderHelper;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.Level;

/** Draws basin contents using off-world client-only slime proxies. */
public final class BasinContainedSlimeRenderer {
	private static final int MAX_VISIBLE_SLIMES = 4;
	private static final BoundedRenderEntityCache<Integer, Slime> CACHE =
		new BoundedRenderEntityCache<>(MAX_VISIBLE_SLIMES, (level, index) -> {
			Slime slime = EntityType.SLIME.create(level);
			if (slime != null)
				slime.setSize(1, false);
			return slime;
		});

	private BasinContainedSlimeRenderer() {}

	public static void render(BasinBlockEntity basin, float partialTicks, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight) {
		Level level = basin.getLevel();
		int count = BasinEntityProcessing.getCapturedSmallSlimeItemCount(basin);
		if (level == null || count <= 0)
			return;

		int visible = Math.min(count, MAX_VISIBLE_SLIMES);
		BlockPos pos = basin.getBlockPos();
		float time = AnimationTickHolder.getRenderTime(level);
		float densityScale = 1f + .045f * Math.min(4, Math.max(0, count - MAX_VISIBLE_SLIMES));
		for (int index = 0; index < visible; index++) {
			Slime slime = CACHE.get(level, index);
			if (slime == null)
				continue;

			float phase = BasinEntityProcessing.getContainedSlimeAnimationPhase(level, pos, index, partialTicks);
			float squish = Mth.sin(phase) * .22f;
			float oldSquish = slime.oSquish;
			float currentSquish = slime.squish;
			float targetSquish = slime.targetSquish;
			slime.oSquish = squish;
			slime.squish = squish;
			slime.targetSquish = squish;

			double angle = Math.PI * 2d * index / Math.max(1, visible)
				+ Math.floorMod(pos.getX() * 31 + pos.getZ() * 17, 360) * Mth.DEG_TO_RAD;
			double radius = visible == 1 ? 0 : .19d;
			slime.setPos(pos.getX() + .5d + Math.cos(angle) * radius, pos.getY() + .2d,
				pos.getZ() + .5d + Math.sin(angle) * radius);
			poseStack.pushPose();
			poseStack.translate(.5d + Math.cos(angle) * radius, .2d, .5d + Math.sin(angle) * radius);
			poseStack.scale(densityScale, densityScale, densityScale);
			EntityRenderHelper.render(EntityRenderHelper.settings(slime)
				.packedLight(packedLight)
				.partialTicks(partialTicks)
				.ticks(Mth.floor(time + index * 3f))
				.renderShadow(false)
				.flushBuffers(false), poseStack, buffer);
			poseStack.popPose();

			slime.oSquish = oldSquish;
			slime.squish = currentSquish;
			slime.targetSquish = targetSquish;
		}
	}
}
