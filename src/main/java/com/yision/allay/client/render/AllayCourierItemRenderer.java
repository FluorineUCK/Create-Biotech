package com.yision.allay.client.render;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;
import com.yision.allay.entity.courier.AllayCourierEntity;
import com.yision.allay.item.allaycourier.AllayCourierItem;
import com.nobodiiiii.createbiotech.foundation.render.EntityRenderHelper;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

@OnlyIn(Dist.CLIENT)
public class AllayCourierItemRenderer extends CustomRenderedItemModelRenderer {
	private static final Vector3f GUI_TOP_LIGHT_0 = new Vector3f(0.15f, 1.0f, -0.35f).normalize();
	private static final Vector3f GUI_TOP_LIGHT_1 = new Vector3f(-0.2f, 0.8f, 0.35f).normalize();

	@Nullable
	private AllayCourierEntity cachedCourier;
	@Nullable
	private ClientLevel cachedLevel;
	private final boolean renderLogisticsHat;

	public AllayCourierItemRenderer() {
		this(true);
	}

	public AllayCourierItemRenderer(boolean renderLogisticsHat) {
		this.renderLogisticsHat = renderLogisticsHat;
	}

	@Override
	protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
		ItemDisplayContext transformType, PoseStack ms, MultiBufferSource buffer, int light, int overlay) {
		AllayCourierEntity courier = getOrCreateCourier(Minecraft.getInstance().level);
		if (courier == null) {
			ms.pushPose();
			applyHeadingRotation(stack, transformType, ms);
			renderer.render(model.getOriginalModel(), light);
			ms.popPose();
			return;
		}

		configureCourier(courier, stack, transformType);

		ms.pushPose();
		applyHeadingRotation(stack, transformType, ms);
		boolean guiLighting = transformType == ItemDisplayContext.GUI;
		if (guiLighting) {
			setupGuiTopLighting();
		}
		int entityLight = guiLighting ? LightTexture.FULL_BRIGHT : light;
		try {
			EntityRenderHelper.render(EntityRenderHelper.settings(courier)
				.packedLight(entityLight)
				.partialTicks(AnimationTickHolder.getPartialTicks())
				.ticks((int) AnimationTickHolder.getRenderTime())
				.dispatcherYaw(0.0f)
				.preserveOrientation()
				.flushBuffers(guiLighting), ms, buffer);
		} finally {
			if (guiLighting) {
				Lighting.setupFor3DItems();
			}
		}
		ms.popPose();
	}

	private void configureCourier(AllayCourierEntity courier, ItemStack stack, ItemDisplayContext transformType) {
		courier.setPackage(AllayCourierItem.copyCargoPackage(stack));
		courier.setPhase(AllayCourierEntity.Phase.WAITING);
		courier.setRenderLogisticsHat(renderLogisticsHat);
		courier.setNoGravity(true);
		courier.setDeltaMovement(Vec3.ZERO);
		courier.setPos(0, 0, 0);
		courier.setYRot(0.0f);
		courier.yRotO = 0.0f;
		courier.setXRot(0.0f);
		courier.xRotO = 0.0f;
	}

	private @Nullable AllayCourierEntity getOrCreateCourier(@Nullable Level level) {
		if (!(level instanceof ClientLevel clientLevel)) {
			return null;
		}

		if (cachedCourier == null || cachedLevel != clientLevel) {
			cachedLevel = clientLevel;
			cachedCourier = AllayCourierEntity.createWaiting(clientLevel, ItemStack.EMPTY, new Vec3(0, 0, 1));
		}

		return cachedCourier;
	}

	private static void applyHeadingRotation(ItemStack stack, ItemDisplayContext transformType, PoseStack ms) {
		if (transformType != ItemDisplayContext.FIXED && transformType != ItemDisplayContext.GROUND) {
			return;
		}
		if (!AllayCourierItem.hasHeadingAngle(stack)) {
			return;
		}
		ms.mulPose(Axis.YP.rotationDegrees(AllayCourierItem.getHeadingAngle(stack)));
	}

	private static void setupGuiTopLighting() {
		RenderSystem.setShaderLights(GUI_TOP_LIGHT_0, GUI_TOP_LIGHT_1);
	}
}
