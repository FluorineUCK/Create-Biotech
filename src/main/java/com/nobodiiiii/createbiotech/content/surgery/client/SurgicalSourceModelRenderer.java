package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class SurgicalSourceModelRenderer {
	private static final Map<Object, CachedPreview> PREVIEWS = new WeakHashMap<>();

	private SurgicalSourceModelRenderer() {}

	@Nullable
	public static LivingEntity preview(Object owner, MimicProfile profile) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null)
			return null;

		CachedPreview cached = PREVIEWS.get(owner);
		if (cached != null && cached.level == level && cached.profile.equals(profile))
			return cached.entity;

		LivingEntity entity = profile.createPreviewEntity(level);
		if (entity == null)
			return null;
		PREVIEWS.put(owner, new CachedPreview(level, profile, entity));
		return entity;
	}

	public static SurgicalModelRenderContext.Snapshot render(Object owner, MimicProfile profile, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		LivingEntity preview = preview(owner, profile);
		if (preview == null)
			return new SurgicalModelRenderContext.Snapshot(0, java.util.List.of());
		return render(preview, cubeCount, presentCubes, cubeOffsets, poseStack, buffer, packedLight, yaw, partialTick,
			collectGeometry, cameraPosition);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		preview.setYRot(yaw);
		preview.yRotO = yaw;
		preview.yBodyRot = yaw;
		preview.yBodyRotO = yaw;
		preview.yHeadRot = yaw;
		preview.yHeadRotO = yaw;
		preview.tickCount = 0;

		@SuppressWarnings("unchecked")
		EntityRenderer<LivingEntity> renderer = (EntityRenderer<LivingEntity>) Minecraft.getInstance()
			.getEntityRenderDispatcher().getRenderer(preview);

		SurgicalModelRenderContext.begin(poseStack, cubeCount, presentCubes, cubeOffsets,
			collectGeometry, cameraPosition);
		SurgicalModelRenderContext.Snapshot snapshot;
		try {
			renderer.render(preview, yaw, partialTick, poseStack, buffer, packedLight);
		} finally {
			snapshot = SurgicalModelRenderContext.end();
		}
		return snapshot;
	}

	public static void clear() {
		PREVIEWS.clear();
	}

	private record CachedPreview(ClientLevel level, MimicProfile profile, LivingEntity entity) {}
}
