package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.LinkedHashMap;
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
	private static final int MAX_RENDER_PLANS = 512;
	private static final Map<Object, Map<MimicProfile, CachedPreview>> PREVIEWS = new WeakHashMap<>();
	private static final Map<LivingEntity, MimicProfile> PREVIEW_PROFILES = new WeakHashMap<>();
	private static final Map<RenderPlanKey, SurgicalCapturedRenderPlan> RENDER_PLANS =
		new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<RenderPlanKey, SurgicalCapturedRenderPlan> eldest) {
				return size() > MAX_RENDER_PLANS;
			}
		};
	private static final Map<LivingEntity, CachedRenderPlan> FALLBACK_RENDER_PLANS = new WeakHashMap<>();

	private SurgicalSourceModelRenderer() {}

	@Nullable
	public static LivingEntity preview(Object owner, MimicProfile profile) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null)
			return null;

		Map<MimicProfile, CachedPreview> ownerPreviews = PREVIEWS.computeIfAbsent(owner,
			ignored -> new java.util.HashMap<>());
		CachedPreview cached = ownerPreviews.get(profile);
		if (cached != null && cached.level == level && cached.profile.equals(profile))
			return cached.entity;

		LivingEntity entity = profile.createPreviewEntity(level);
		if (entity == null)
			return null;
		ownerPreviews.put(profile, new CachedPreview(level, profile, entity));
		PREVIEW_PROFILES.put(entity, profile);
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
		return render(preview, cubeCount, presentCubes, cubeOffsets, poseStack, buffer, packedLight, yaw,
			partialTick, collectGeometry, cameraPosition, false);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition,
		boolean renderSourceGeometry) {
		preparePreview(preview, yaw);
		SurgicalCapturedRenderPlan plan = plan(preview, yaw, partialTick, packedLight);
		return plan.render(poseStack, buffer, packedLight, cubeCount, presentCubes, cubeOffsets,
			collectGeometry, cameraPosition, renderSourceGeometry);
	}

	private static SurgicalCapturedRenderPlan plan(LivingEntity preview, float yaw, float partialTick,
		int packedLight) {
		EntityRenderer<LivingEntity> renderer = renderer(preview);
		MimicProfile profile = PREVIEW_PROFILES.get(preview);
		if (profile != null) {
			RenderPlanKey key = new RenderPlanKey(profile, renderer, Float.floatToIntBits(yaw));
			SurgicalCapturedRenderPlan plan = RENDER_PLANS.get(key);
			if (plan == null) {
				plan = SurgicalCapturedRenderPlan.capture(renderer, preview, yaw, partialTick, packedLight);
				RENDER_PLANS.put(key, plan);
			}
			return plan;
		}

		CachedRenderPlan cached = FALLBACK_RENDER_PLANS.get(preview);
		if (cached == null || cached.renderer != renderer
			|| Float.floatToIntBits(cached.yaw) != Float.floatToIntBits(yaw)) {
			SurgicalCapturedRenderPlan plan =
				SurgicalCapturedRenderPlan.capture(renderer, preview, yaw, partialTick, packedLight);
			cached = new CachedRenderPlan(renderer, yaw, plan);
			FALLBACK_RENDER_PLANS.put(preview, cached);
		}
		return cached.plan;
	}

	/** Captures unoffset source geometry without submitting the temporary model to the visible buffer. */
	public static SurgicalModelRenderContext.Snapshot captureGeometry(LivingEntity preview, int cubeCount,
		BitSet presentCubes, PoseStack poseStack, int packedLight, float yaw, float partialTick,
		@Nullable Vec3 cameraPosition, boolean renderSourceGeometry) {
		preparePreview(preview, yaw);
		return plan(preview, yaw, partialTick, packedLight)
			.snapshot(poseStack, cubeCount, presentCubes, Map.of(), cameraPosition);
	}

	private static void preparePreview(LivingEntity preview, float yaw) {
		preview.setYRot(yaw);
		preview.yRotO = yaw;
		preview.yBodyRot = yaw;
		preview.yBodyRotO = yaw;
		preview.yHeadRot = yaw;
		preview.yHeadRotO = yaw;
		preview.tickCount = 0;
	}

	@SuppressWarnings("unchecked")
	private static EntityRenderer<LivingEntity> renderer(LivingEntity preview) {
		EntityRenderer<LivingEntity> renderer = (EntityRenderer<LivingEntity>) Minecraft.getInstance()
			.getEntityRenderDispatcher().getRenderer(preview);
		return renderer;
	}

	public static void clear() {
		PREVIEWS.clear();
		PREVIEW_PROFILES.clear();
		RENDER_PLANS.clear();
		FALLBACK_RENDER_PLANS.clear();
		SurgicalCapturedRenderPlan.clearResources();
	}

	private record CachedPreview(ClientLevel level, MimicProfile profile, LivingEntity entity) {}

	private record RenderPlanKey(MimicProfile profile, EntityRenderer<LivingEntity> renderer, int yawBits) {}

	private record CachedRenderPlan(EntityRenderer<LivingEntity> renderer, float yaw,
		SurgicalCapturedRenderPlan plan) {}
}
