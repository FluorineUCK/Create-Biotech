package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class SurgicalSourceModelRenderer {
	private static final Map<Object, Map<MimicProfile, CachedPreview>> PREVIEWS = new WeakHashMap<>();

	private SurgicalSourceModelRenderer() {}

	private static final class DiscardingVertexConsumer implements VertexConsumer {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int red, int green, int blue, int alpha) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}
	}

	private static final class DiscardingBuffer implements MultiBufferSource {
		private final Map<RenderType, VertexConsumer> consumers = new IdentityHashMap<>();

		@Override
		public VertexConsumer getBuffer(RenderType renderType) {
			return consumers.computeIfAbsent(renderType, ignored -> new DiscardingVertexConsumer());
		}
	}

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
		EntityRenderer<LivingEntity> renderer = renderer(preview);

		SurgicalModelRenderContext.begin(poseStack, cubeCount, presentCubes, cubeOffsets,
			collectGeometry, cameraPosition, renderSourceGeometry);
		SurgicalModelRenderContext.Snapshot snapshot;
		try {
			renderer.render(preview, yaw, partialTick, poseStack, buffer, packedLight);
		} finally {
			snapshot = SurgicalModelRenderContext.end();
		}
		return snapshot;
	}

	/** Captures unoffset source geometry without submitting the temporary model to the visible buffer. */
	public static SurgicalModelRenderContext.Snapshot captureGeometry(LivingEntity preview, int cubeCount,
		BitSet presentCubes, PoseStack poseStack, int packedLight, float yaw, float partialTick,
		@Nullable Vec3 cameraPosition, boolean renderSourceGeometry) {
		return render(preview, cubeCount, presentCubes, Map.of(), poseStack, new DiscardingBuffer(),
			packedLight, yaw, partialTick, true, cameraPosition, renderSourceGeometry);
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
	}

	private record CachedPreview(ClientLevel level, MimicProfile profile, LivingEntity entity) {}
}
