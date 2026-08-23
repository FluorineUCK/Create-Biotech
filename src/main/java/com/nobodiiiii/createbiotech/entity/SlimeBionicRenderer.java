package com.nobodiiiii.createbiotech.entity;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalClientTopology;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalModelRenderContext;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalSourceModelRenderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public class SlimeBionicRenderer extends EntityRenderer<SlimeBionicEntity> {
	private static final ResourceLocation SLIME_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/entity/slime/slime.png");
	private static final Map<SlimeBionicEntity, Map<Integer, Vec3>> OFFSETS = new WeakHashMap<>();

	public SlimeBionicRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.25f;
	}

	public static void clearCache() {
		OFFSETS.clear();
	}

	@Override
	public void render(SlimeBionicEntity entity, float yaw, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight) {
		SurgicalAssembly assembly = entity.getAssembly();
		if (assembly != null) {
			poseStack.pushPose();
			Map<Integer, Vec3> offsets = OFFSETS.getOrDefault(entity, Map.of());
			SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(entity,
				assembly.profile(), assembly.cubeCount(), assembly.presentCubes(), offsets, poseStack, buffer,
				packedLight, yaw, partialTick, true, null);
			poseStack.popPose();
			OFFSETS.put(entity, SurgicalClientTopology.componentOffsets(assembly.cubeCount(),
				assembly.presentCubes(), assembly.seams(), assembly.cutSeams(), snapshot.cubes()));
		} else {
			OFFSETS.remove(entity);
		}
		super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(SlimeBionicEntity entity) {
		return SLIME_TEXTURE;
	}
}
