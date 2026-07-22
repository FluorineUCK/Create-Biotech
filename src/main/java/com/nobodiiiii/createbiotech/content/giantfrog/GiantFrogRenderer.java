package com.nobodiiiii.createbiotech.content.giantfrog;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.foundation.render.BlockEntityModelElement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.FrogModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class GiantFrogRenderer implements BlockEntityRenderer<GiantFrogBlockEntity> {
	private static final float LIVING_ENTITY_MODEL_Y_OFFSET = -1.501f;
	private static final float BELT_OPEN_HEAD_X_ROT = (float) Math.toRadians(-10.0d);

	private final FrogModel<Frog> frogModel;
	@Nullable
	private Frog cachedFrog;
	@Nullable
	private ClientLevel cachedLevel;

	public GiantFrogRenderer(BlockEntityRendererProvider.Context context) {
		frogModel = new FrogModel<>(context.bakeLayer(ModelLayers.FROG));
	}

	@Override
	public void render(GiantFrogBlockEntity blockEntity, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		if (!GiantFrogBlock.isMain(blockEntity.getBlockState()))
			return;

		Frog frog = getOrCreateFrog(blockEntity.getLevel());
		if (frog == null)
			return;

		Direction facing = getFacing(blockEntity.getBlockState());
		boolean tongue = blockEntity.isTongueAnimating();
		boolean mouthHeldOpen = blockEntity.isMouthHeldOpenByBelt();
		updateAnimationState(frog, tongue);
		float animationAge = tongue ? blockEntity.getTongueAnimationAge(partialTick) : 0.0f;
		prepareFrogModel(frog, animationAge, mouthHeldOpen && !tongue);

		BlockEntityModelElement.builder()
			.atLocal(0.5d, 0.0d, 0.5d)
			.rotateY(180.0f - facing.toYRot())
			.scale(-GiantFrogBlock.FROG_SCALE, -GiantFrogBlock.FROG_SCALE, GiantFrogBlock.FROG_SCALE)
			.packedLight(packedLight)
			.render(poseStack, buffer, (modelPose, modelBuffer, light) -> {
				modelPose.translate(0.0f, LIVING_ENTITY_MODEL_Y_OFFSET, 0.0f);
				renderFrogModel(frog, modelPose, modelBuffer, light);
			});
	}

	private void updateAnimationState(Frog frog, boolean tongue) {
		frog.jumpAnimationState.stop();
		frog.swimIdleAnimationState.stop();
		frog.croakAnimationState.stop();

		if (!tongue) {
			frog.tongueAnimationState.stop();
			return;
		}

		frog.tongueAnimationState.start(0);
	}

	private void prepareFrogModel(Frog frog, float ageInTicks, boolean openMouth) {
		frog.tickCount = (int) ageInTicks;
		frogModel.attackTime = 0.0f;
		frogModel.riding = false;
		frogModel.young = false;
		frogModel.prepareMobModel(frog, 0.0f, 0.0f, 0.0f);
		frogModel.setupAnim(frog, 0.0f, 0.0f, ageInTicks, 0.0f, 0.0f);
		if (openMouth)
			frogHead().xRot += BELT_OPEN_HEAD_X_ROT;
	}

	private void renderFrogModel(Frog frog, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		VertexConsumer consumer = buffer.getBuffer(frogModel.renderType(frog.getVariant().value().texture()));
		frogModel.renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
	}

	private ModelPart frogHead() {
		return frogModel.root()
			.getChild("body")
			.getChild("head");
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
