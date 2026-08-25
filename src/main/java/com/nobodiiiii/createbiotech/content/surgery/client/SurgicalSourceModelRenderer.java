package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalCubeRotation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
		return render(owner, profile, cubeCount, presentCubes, cubeOffsets, Map.of(), poseStack, buffer, packedLight,
			yaw, partialTick, collectGeometry, cameraPosition);
	}

	public static SurgicalModelRenderContext.Snapshot render(Object owner, MimicProfile profile, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
		Map<Integer, SurgicalCubeRotation> cubeRotations, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		LivingEntity preview = preview(owner, profile);
		if (preview == null)
			return new SurgicalModelRenderContext.Snapshot(0, java.util.List.of());
		return render(preview, cubeCount, presentCubes, cubeOffsets, cubeRotations, poseStack, buffer, packedLight,
			yaw, partialTick, collectGeometry, cameraPosition);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		return render(preview, cubeCount, presentCubes, cubeOffsets, Map.of(), poseStack, buffer, packedLight,
			yaw, partialTick, collectGeometry, cameraPosition, false);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
		Map<Integer, SurgicalCubeRotation> cubeRotations, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition) {
		return render(preview, cubeCount, presentCubes, cubeOffsets, cubeRotations, poseStack, buffer, packedLight,
			yaw, partialTick, collectGeometry, cameraPosition, false);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition,
		boolean renderSourceGeometry) {
		return render(preview, cubeCount, presentCubes, cubeOffsets, Map.of(), poseStack, buffer, packedLight,
			yaw, partialTick, collectGeometry, cameraPosition, renderSourceGeometry);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
		Map<Integer, SurgicalCubeRotation> cubeRotations, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition,
		boolean renderSourceGeometry) {
		return render(preview, cubeCount, presentCubes, cubeOffsets, cubeRotations, poseStack, buffer, packedLight,
			yaw, partialTick, collectGeometry, cameraPosition, renderSourceGeometry, 1.0f);
	}

	public static SurgicalModelRenderContext.Snapshot render(LivingEntity preview, int cubeCount,
		BitSet presentCubes, Map<Integer, Vec3> cubeOffsets,
		Map<Integer, SurgicalCubeRotation> cubeRotations, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		float yaw, float partialTick, boolean collectGeometry, @Nullable Vec3 cameraPosition,
		boolean renderSourceGeometry, float alpha) {
		preparePreview(preview, yaw);
		SurgicalCapturedRenderPlan plan = plan(preview, yaw, partialTick);
		float clampedAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
		MultiBufferSource renderBuffer = clampedAlpha < 1.0f
			? new AlphaBufferSource(buffer, clampedAlpha) : buffer;
		return plan.render(poseStack, renderBuffer, packedLight, cubeCount, presentCubes, cubeOffsets, cubeRotations,
			collectGeometry, cameraPosition, renderSourceGeometry);
	}

	private static SurgicalCapturedRenderPlan plan(LivingEntity preview, float yaw, float partialTick) {
		EntityRenderer<LivingEntity> renderer = renderer(preview);
		MimicProfile profile = PREVIEW_PROFILES.get(preview);
		if (profile != null) {
			RenderPlanKey key = new RenderPlanKey(profile, renderer, Float.floatToIntBits(yaw));
			SurgicalCapturedRenderPlan plan = RENDER_PLANS.get(key);
			if (plan == null) {
				plan = SurgicalCapturedRenderPlan.capture(renderer, preview, yaw, partialTick);
				RENDER_PLANS.put(key, plan);
			}
			return plan;
		}

		CachedRenderPlan cached = FALLBACK_RENDER_PLANS.get(preview);
		if (cached == null || cached.renderer != renderer
			|| Float.floatToIntBits(cached.yaw) != Float.floatToIntBits(yaw)) {
			SurgicalCapturedRenderPlan plan =
				SurgicalCapturedRenderPlan.capture(renderer, preview, yaw, partialTick);
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
		return plan(preview, yaw, partialTick)
			.snapshot(poseStack, cubeCount, presentCubes, Map.of(), Map.of(), cameraPosition);
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

	private record AlphaBufferSource(MultiBufferSource delegate, float alpha) implements MultiBufferSource {
		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			RenderType translucent = SurgicalCapturedRenderPlan.translucentPreviewType(renderType);
			return new AlphaVertexConsumer(delegate.getBuffer(translucent), alpha);
		}
	}

	private record AlphaVertexConsumer(VertexConsumer delegate, float alpha) implements VertexConsumer {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			delegate.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int sourceAlpha) {
			int multipliedAlpha = Math.max(0, Math.min(255, Math.round(sourceAlpha * alpha)));
			delegate.setColor(red, green, blue, multipliedAlpha);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			delegate.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			delegate.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			delegate.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			delegate.setNormal(x, y, z);
			return this;
		}
	}
}
