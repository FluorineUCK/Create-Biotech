package com.nobodiiiii.createbiotech.entity;

import java.util.BitSet;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalClientTopology;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalModelRenderContext;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalSourceModelRenderer;
import com.nobodiiiii.createbiotech.foundation.render.EntityGeometry;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class SlimeBionicRenderer extends EntityRenderer<SlimeBionicEntity> {
	private static final ResourceLocation SLIME_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/entity/slime/slime.png");
	private static final Map<SlimeBionicEntity, CachedGeometry> GEOMETRY = new WeakHashMap<>();

	public SlimeBionicRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.25f;
	}

	public static void clearCache() {
		GEOMETRY.clear();
	}

	@Override
	public void render(SlimeBionicEntity entity, float yaw, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight) {
		SurgicalAssembly assembly = entity.getAssembly();
		if (assembly != null) {
			poseStack.pushPose();
			LivingEntity preview = SurgicalSourceModelRenderer.preview(entity, assembly.profile());
			boolean slimeForm = SlimeMimicHandler.isSlimeMimic(entity);
			if (preview != null)
				((SlimeMimicAccess) (Object) preview).createBiotech$setSlimeMimic(true);
			CachedGeometry cached = GEOMETRY.get(entity);
			boolean rebuildGeometry = cached == null || cached.assembly != assembly
				|| Float.floatToIntBits(cached.yaw) != Float.floatToIntBits(yaw)
				|| cached.slimeForm != slimeForm;
			if (rebuildGeometry) {
				cached = rebuildGeometry(preview, assembly, yaw, partialTick, packedLight, slimeForm);
				if (cached != null) {
					GEOMETRY.put(entity, cached);
				} else {
					GEOMETRY.remove(entity);
				}
			}

			BitSet presentCubes = cached == null ? assembly.presentCubes() : cached.presentCubes;
			Map<Integer, Vec3> offsets = cached == null ? Map.of() : cached.offsets;
			if (cached != null)
				poseStack.translate(cached.modelOffset.x, cached.modelOffset.y, cached.modelOffset.z);
			if (preview != null) {
				SurgicalSourceModelRenderer.render(preview, assembly.cubeCount(), presentCubes, offsets,
					poseStack, buffer, packedLight, yaw, partialTick, false, null, !slimeForm);
			}
			poseStack.popPose();
		} else {
			GEOMETRY.remove(entity);
		}
		super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
	}

	private static CachedGeometry rebuildGeometry(LivingEntity preview, SurgicalAssembly assembly,
		float yaw, float partialTick, int packedLight, boolean slimeForm) {
		if (preview == null)
			return null;

		BitSet presentCubes = assembly.presentCubes();
		Map<Integer, Vec3> offsets = componentOffsets(preview, assembly, presentCubes, yaw, partialTick,
			packedLight);

		// Measure the active visual body's geometry, not the source creature's model origin.
		// EntityGeometry suppresses optional RenderLayers here, so clothes, armor and held items
		// follow the resulting translation without affecting where the body is centered or grounded.
		EntityGeometry.Collector bodyGeometry = EntityGeometry.Collector.boundsOnly();
		MultiBufferSource measuringBuffer = renderType -> bodyGeometry;
		EntityGeometry.measureBaseModelWithFallback(preview, bodyGeometry, () ->
			SurgicalSourceModelRenderer.render(preview, assembly.cubeCount(), presentCubes, offsets,
				new PoseStack(), measuringBuffer, packedLight, yaw, partialTick, false, null, !slimeForm));
		EntityGeometry.Bounds bounds = bodyGeometry.bounds();
		Vec3 modelOffset = new Vec3(-bounds.centerX(), -bounds.minY(), -bounds.centerZ());
		return new CachedGeometry(assembly, yaw, slimeForm, presentCubes, offsets, modelOffset);
	}

	private static Map<Integer, Vec3> componentOffsets(LivingEntity preview, SurgicalAssembly assembly,
		BitSet presentCubes, float yaw, float partialTick, int packedLight) {
		EntityGeometry.Collector discardedVertices = EntityGeometry.Collector.boundsOnly();
		MultiBufferSource discardedBuffer = renderType -> discardedVertices;
		SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(preview,
			assembly.cubeCount(), presentCubes, Map.of(), new PoseStack(), discardedBuffer, packedLight,
			yaw, partialTick, true, null);
		return SurgicalClientTopology.componentOffsets(assembly.cubeCount(), presentCubes,
			assembly.seams(), assembly.cutSeams(), snapshot.cubes());
	}

	@Override
	public ResourceLocation getTextureLocation(SlimeBionicEntity entity) {
		return SLIME_TEXTURE;
	}

	private record CachedGeometry(SurgicalAssembly assembly, float yaw, boolean slimeForm, BitSet presentCubes,
		Map<Integer, Vec3> offsets, Vec3 modelOffset) {
		private CachedGeometry {
			presentCubes = (BitSet) presentCubes.clone();
			offsets = Map.copyOf(offsets);
		}
	}
}
