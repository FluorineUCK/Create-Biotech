package com.nobodiiiii.createbiotech.content.giantfrog;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.foundation.render.RenderedLivingEntityItemRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

public class GiantFrogItemRenderer extends BlockEntityWithoutLevelRenderer {
	private static final float ITEM_ENTITY_SCALE = 1.25f;

	@Nullable
	private Frog cachedFrog;
	@Nullable
	private ClientLevel cachedLevel;

	public GiantFrogItemRenderer() {
		super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
	}

	@Override
	public void renderByItem(ItemStack stack, ItemDisplayContext transformType, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int overlay) {
		Frog frog = getOrCreateFrog();
		if (frog == null)
			return;

		RenderedLivingEntityItemRenderer.renderEntity(frog, ITEM_ENTITY_SCALE, poseStack, buffer, packedLight);
	}

	@Nullable
	private Frog getOrCreateFrog() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null)
			return null;

		if (cachedFrog == null || cachedLevel != level) {
			cachedLevel = level;
			cachedFrog = EntityType.FROG.create(level);
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
