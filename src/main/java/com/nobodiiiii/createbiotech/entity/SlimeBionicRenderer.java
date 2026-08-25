package com.nobodiiiii.createbiotech.entity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalCubeRotation;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLayPose;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalClientTopology;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalModelRenderContext;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalSourceModelRenderer;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalTablePoseResolver;
import com.nobodiiiii.createbiotech.entity.client.SlimeBionicAnimator;
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
	private static final Map<SlimeBionicEntity, CompositeCachedGeometry> COMPOSITE_GEOMETRY = new WeakHashMap<>();
	private static final Map<SurgicalAssembly, Map<SurgicalAssembly.Source, Map<Integer, Vec3>>>
		UPRIGHT_OFFSETS = new WeakHashMap<>();
	private static final Map<SurgicalAssembly, Map<SurgicalAssembly.Source, Map<Integer, SurgicalCubeRotation>>>
		UPRIGHT_ROTATIONS = new WeakHashMap<>();

	public SlimeBionicRenderer(EntityRendererProvider.Context context) {
		super(context);
		shadowRadius = 0.25f;
	}

	public static void clearCache() {
		GEOMETRY.clear();
		COMPOSITE_GEOMETRY.clear();
		UPRIGHT_OFFSETS.clear();
		UPRIGHT_ROTATIONS.clear();
		SlimeBionicAnimator.clearCache();
	}

	@Override
	public void render(SlimeBionicEntity entity, float yaw, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight) {
		SurgicalAssembly assembly = entity.getAssembly();
		if (assembly != null) {
			if (assembly.preservesLayout() || assembly.sources().size() > 1) {
				renderComposite(entity, assembly, yaw, partialTick, poseStack, buffer, packedLight);
				super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
				return;
			}
			COMPOSITE_GEOMETRY.remove(entity);
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
			Map<Integer, SurgicalCubeRotation> rotations = assembly.cubeRotations();
			if (cached != null) {
				poseStack.translate(cached.modelOffset.x, cached.modelOffset.y, cached.modelOffset.z);
				SlimeBionicAnimator.Frame frame = SlimeBionicAnimator.resolve(entity, assembly,
					List.of(new SlimeBionicAnimator.SourceState(cached.restBoxes, offsets, rotations)),
					yaw, partialTick).getFirst();
				offsets = frame.mergeOffsets(offsets);
				rotations = frame.mergeRotations(rotations);
			}
			if (preview != null) {
				SurgicalSourceModelRenderer.render(preview, assembly.cubeCount(), presentCubes, offsets,
					rotations,
					poseStack, buffer, packedLight, yaw, partialTick, false, null, !slimeForm);
			}
			poseStack.popPose();
		} else {
			GEOMETRY.remove(entity);
			COMPOSITE_GEOMETRY.remove(entity);
		}
		super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
	}

	private static void renderComposite(SlimeBionicEntity entity, SurgicalAssembly assembly,
		float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		GEOMETRY.remove(entity);
		boolean slimeForm = SlimeMimicHandler.isSlimeMimic(entity);
		CompositeCachedGeometry cached = COMPOSITE_GEOMETRY.get(entity);
		if (cached == null || cached.assembly != assembly
			|| Float.floatToIntBits(cached.yaw) != Float.floatToIntBits(yaw)
			|| cached.slimeForm != slimeForm) {
			cached = measureComposite(entity, assembly, yaw, partialTick, packedLight, slimeForm);
			if (cached == null)
				COMPOSITE_GEOMETRY.remove(entity);
			else
				COMPOSITE_GEOMETRY.put(entity, cached);
		}

		List<SlimeBionicAnimator.Frame> frames = cached == null ? List.of()
			: SlimeBionicAnimator.resolve(entity, assembly, cached.sources, yaw, partialTick);
		poseStack.pushPose();
		if (cached != null)
			poseStack.translate(cached.modelOffset.x, cached.modelOffset.y, cached.modelOffset.z);
		renderCompositeSources(entity, assembly, yaw, partialTick, poseStack, buffer, packedLight,
			!slimeForm, frames, null);
		poseStack.popPose();
	}

	/**
	 * Measures the rest pose once per cached configuration.
	 *
	 * <p>The model offset that grounds and centres the body has to come from the still rest pose:
	 * remeasuring an animated frame would make a walking body bob as its own bounds shift.</p>
	 */
	@Nullable
	private static CompositeCachedGeometry measureComposite(SlimeBionicEntity entity,
		SurgicalAssembly assembly, float yaw, float partialTick, int packedLight, boolean slimeForm) {
		EntityGeometry.Collector geometry = EntityGeometry.Collector.boundsOnly();
		MultiBufferSource measuringBuffer = renderType -> geometry;
		List<SlimeBionicAnimator.SourceState> sources = new ArrayList<>(assembly.sources().size());
		renderCompositeSources(entity, assembly, yaw, partialTick, new PoseStack(), measuringBuffer,
			packedLight, !slimeForm, List.of(), sources);
		if (!geometry.hasVertices())
			return null;
		EntityGeometry.Bounds bounds = geometry.bounds();
		return new CompositeCachedGeometry(assembly, yaw, slimeForm,
			new Vec3(-bounds.centerX(), -bounds.minY(), -bounds.centerZ()), List.copyOf(sources));
	}

	/**
	 * Renders or measures every source of a composite body.
	 *
	 * <p>When {@code restStates} is supplied each source's rest geometry is collected instead of an
	 * animation frame being applied, which is how the still reference pose is captured.</p>
	 */
	private static void renderCompositeSources(SlimeBionicEntity entity, SurgicalAssembly assembly,
		float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
		boolean renderSourceGeometry, List<SlimeBionicAnimator.Frame> frames,
		@Nullable List<SlimeBionicAnimator.SourceState> restStates) {
		poseStack.pushPose();
		SurgicalTablePoseResolver.applyInverseRotation(poseStack, assembly.layoutLayPose());
		List<SurgicalAssembly.Source> sources = assembly.sources();
		for (int index = 0; index < sources.size(); index++) {
			SurgicalAssembly.Source source = sources.get(index);
			LivingEntity preview = SurgicalSourceModelRenderer.preview(entity, source.profile());
			if (preview == null) {
				if (restStates != null)
					restStates.add(new SlimeBionicAnimator.SourceState(Map.of(), Map.of(), Map.of()));
				continue;
			}
			((SlimeMimicAccess) (Object) preview).createBiotech$setSlimeMimic(true);
			Map<Integer, Vec3> offsets = uprightOffsets(assembly, source);
			Map<Integer, SurgicalCubeRotation> rotations = uprightRotations(assembly, source);
			SlimeBionicAnimator.Frame frame = index < frames.size() ? frames.get(index)
				: SlimeBionicAnimator.Frame.EMPTY;
			poseStack.pushPose();
			poseStack.translate(source.originOffset().x, source.originOffset().y, source.originOffset().z);
			if (assembly.preservesLayout())
				SurgicalTablePoseResolver.resolve(source.layPose()).apply(poseStack);
			SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(preview,
				source.cubeCount(), source.presentCubes(), frame.mergeOffsets(offsets),
				frame.mergeRotations(rotations),
				poseStack, buffer, packedLight, yaw, partialTick, restStates != null, null,
				renderSourceGeometry);
			if (restStates != null)
				restStates.add(new SlimeBionicAnimator.SourceState(
					SlimeBionicAnimator.measure(snapshot, yaw), offsets, rotations));
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	private static Map<Integer, Vec3> uprightOffsets(SurgicalAssembly assembly,
		SurgicalAssembly.Source source) {
		Map<SurgicalAssembly.Source, Map<Integer, Vec3>> assemblyOffsets = UPRIGHT_OFFSETS
			.computeIfAbsent(assembly, ignored -> new IdentityHashMap<>());
		Map<Integer, Vec3> cached = assemblyOffsets.get(source);
		if (cached != null)
			return cached;
		SurgicalLayPose pose = assembly.layoutLayPose();
		Map<Integer, Vec3> offsets = source.cubeOffsets();
		if (offsets.isEmpty())
			return offsets;
		Map<Integer, Vec3> transformed = new java.util.HashMap<>();
		offsets.forEach((cube, offset) -> transformed.put(cube, pose.inverseRotate(offset)));
		Map<Integer, Vec3> result = Map.copyOf(transformed);
		assemblyOffsets.put(source, result);
		return result;
	}

	private static Map<Integer, SurgicalCubeRotation> uprightRotations(SurgicalAssembly assembly,
		SurgicalAssembly.Source source) {
		Map<SurgicalAssembly.Source, Map<Integer, SurgicalCubeRotation>> assemblyRotations = UPRIGHT_ROTATIONS
			.computeIfAbsent(assembly, ignored -> new IdentityHashMap<>());
		Map<Integer, SurgicalCubeRotation> cached = assemblyRotations.get(source);
		if (cached != null)
			return cached;
		SurgicalLayPose pose = assembly.layoutLayPose();
		Map<Integer, SurgicalCubeRotation> rotations = source.cubeRotations();
		if (rotations.isEmpty())
			return rotations;
		Map<Integer, SurgicalCubeRotation> transformed = new java.util.HashMap<>();
		rotations.forEach((cube, rotation) -> transformed.put(cube, rotation.inverseRotate(pose)));
		Map<Integer, SurgicalCubeRotation> result = Map.copyOf(transformed);
		assemblyRotations.put(source, result);
		return result;
	}

	@Nullable
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
		SurgicalModelRenderContext.Snapshot[] restPose = new SurgicalModelRenderContext.Snapshot[1];
		EntityGeometry.measureBaseModelWithFallback(preview, bodyGeometry, () ->
			restPose[0] = SurgicalSourceModelRenderer.render(preview, assembly.cubeCount(), presentCubes,
				offsets, assembly.cubeRotations(),
				new PoseStack(), measuringBuffer, packedLight, yaw, partialTick, true, null, !slimeForm));
		EntityGeometry.Bounds bounds = bodyGeometry.bounds();
		Vec3 modelOffset = new Vec3(-bounds.centerX(), -bounds.minY(), -bounds.centerZ());
		Map<Integer, SlimeBionicAnimator.CubeBox> restBoxes = restPose[0] == null ? Map.of()
			: SlimeBionicAnimator.measure(restPose[0], yaw);
		return new CachedGeometry(assembly, yaw, slimeForm, presentCubes, offsets, modelOffset, restBoxes);
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
		Map<Integer, Vec3> offsets, Vec3 modelOffset,
		Map<Integer, SlimeBionicAnimator.CubeBox> restBoxes) {
		private CachedGeometry {
			presentCubes = (BitSet) presentCubes.clone();
			offsets = Map.copyOf(offsets);
		}
	}

	private record CompositeCachedGeometry(SurgicalAssembly assembly, float yaw, boolean slimeForm,
		Vec3 modelOffset, List<SlimeBionicAnimator.SourceState> sources) {}
}
