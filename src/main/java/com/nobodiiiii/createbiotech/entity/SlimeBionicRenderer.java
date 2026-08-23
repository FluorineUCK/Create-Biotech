package com.nobodiiiii.createbiotech.entity;

import java.util.BitSet;
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
			CachedGeometry cached = GEOMETRY.get(entity);
			boolean collectGeometry = cached == null || cached.assembly != assembly
				|| Float.floatToIntBits(cached.yaw) != Float.floatToIntBits(yaw);
			BitSet presentCubes = cached != null && cached.assembly == assembly
				? cached.presentCubes : assembly.presentCubes();
			Map<Integer, Vec3> offsets = collectGeometry ? Map.of() : cached.offsets;
			SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(entity,
				assembly.profile(), assembly.cubeCount(), presentCubes, offsets, poseStack, buffer,
				packedLight, yaw, partialTick, collectGeometry, null);
			poseStack.popPose();
			if (collectGeometry) {
				Map<Integer, Vec3> computedOffsets = SurgicalClientTopology.componentOffsets(assembly.cubeCount(),
					presentCubes, assembly.seams(), assembly.cutSeams(), snapshot.cubes());
				GEOMETRY.put(entity, new CachedGeometry(assembly, yaw, presentCubes, computedOffsets));
			}
		} else {
			GEOMETRY.remove(entity);
		}
		super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
	}

	@Override
	public ResourceLocation getTextureLocation(SlimeBionicEntity entity) {
		return SLIME_TEXTURE;
	}

	private record CachedGeometry(SurgicalAssembly assembly, float yaw, BitSet presentCubes,
		Map<Integer, Vec3> offsets) {
		private CachedGeometry {
			presentCubes = (BitSet) presentCubes.clone();
			offsets = Map.copyOf(offsets);
		}
	}
}
