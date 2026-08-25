package com.nobodiiiii.createbiotech.entity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicAccess;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalBodyBounds;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalCubeRotation;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLayPose;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLimbType;
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
import net.minecraft.util.Mth;
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
			float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
			BodyFrame bodyFrame = BodyFrame.of(bodyYaw);
			if (assembly.preservesLayout() || assembly.sources().size() > 1) {
				renderComposite(entity, assembly, bodyFrame, partialTick, poseStack, buffer, packedLight);
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
				|| cached.slimeForm != slimeForm;
			if (rebuildGeometry) {
				cached = rebuildGeometry(entity, preview, assembly, partialTick, packedLight, slimeForm);
				if (cached != null) {
					GEOMETRY.put(entity, cached);
				} else {
					GEOMETRY.remove(entity);
				}
			}

			BitSet presentCubes = cached == null ? assembly.presentCubes() : cached.presentCubes;
			Map<Integer, Vec3> localOffsets = cached == null ? Map.of() : cached.offsets;
			Map<Integer, SurgicalCubeRotation> localRotations = assembly.cubeRotations();
			bodyFrame.apply(poseStack);
			if (cached != null) {
				poseStack.translate(cached.modelOffset.x, cached.modelOffset.y, cached.modelOffset.z);
				SlimeBionicAnimator.Frame frame = SlimeBionicAnimator.resolve(entity, assembly,
					List.of(new SlimeBionicAnimator.SourceState(cached.restBoxes, localOffsets, localRotations)),
					partialTick).getFirst();
				localOffsets = frame.mergeOffsets(localOffsets);
				localRotations = frame.mergeRotations(localRotations);
			}
			if (preview != null) {
				SurgicalSourceModelRenderer.render(preview, assembly.cubeCount(), presentCubes,
					bodyFrame.rotateOffsets(localOffsets), bodyFrame.rotateRotations(localRotations),
					poseStack, buffer, packedLight, 0.0f, partialTick, false, null, !slimeForm);
			}
			poseStack.popPose();
		} else {
			GEOMETRY.remove(entity);
			COMPOSITE_GEOMETRY.remove(entity);
		}
		super.render(entity, yaw, partialTick, poseStack, buffer, packedLight);
	}

	private static void renderComposite(SlimeBionicEntity entity, SurgicalAssembly assembly,
		BodyFrame bodyFrame, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
		GEOMETRY.remove(entity);
		boolean slimeForm = SlimeMimicHandler.isSlimeMimic(entity);
		CompositeCachedGeometry cached = COMPOSITE_GEOMETRY.get(entity);
		if (cached == null || cached.assembly != assembly || cached.slimeForm != slimeForm) {
			cached = measureComposite(entity, assembly, partialTick, packedLight, slimeForm);
			if (cached == null)
				COMPOSITE_GEOMETRY.remove(entity);
			else
				COMPOSITE_GEOMETRY.put(entity, cached);
		}

		List<SlimeBionicAnimator.Frame> frames = cached == null ? List.of()
			: SlimeBionicAnimator.resolve(entity, assembly, cached.sources, partialTick);
		poseStack.pushPose();
		bodyFrame.apply(poseStack);
		if (cached != null)
			poseStack.translate(cached.modelOffset.x, cached.modelOffset.y, cached.modelOffset.z);
		renderCompositeSources(entity, assembly, bodyFrame, partialTick, poseStack, buffer, packedLight,
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
		SurgicalAssembly assembly, float partialTick, int packedLight, boolean slimeForm) {
		EntityGeometry.Collector geometry = EntityGeometry.Collector.boundsOnly();
		MultiBufferSource measuringBuffer = renderType -> geometry;
		List<SlimeBionicAnimator.SourceState> sources = new ArrayList<>(assembly.sources().size());
		renderCompositeSources(entity, assembly, BodyFrame.IDENTITY, partialTick, new PoseStack(),
			measuringBuffer, packedLight, !slimeForm, List.of(), sources);
		if (!geometry.hasVertices())
			return null;
		EntityGeometry.Bounds bounds = geometry.bounds();
		updateClientBodyBounds(entity, assembly, bounds, sources);
		return new CompositeCachedGeometry(assembly, slimeForm,
			new Vec3(-bounds.centerX(), -bounds.minY(), -bounds.centerZ()), List.copyOf(sources));
	}

	private static void updateClientBodyBounds(SlimeBionicEntity entity, SurgicalAssembly assembly,
		EntityGeometry.Bounds bounds, List<SlimeBionicAnimator.SourceState> sources) {
		Set<SurgicalAssembly.CombinationMember> armCubes = new HashSet<>();
		for (SurgicalAssembly.Limb limb : assembly.limbs())
			if (limb.type() == SurgicalLimbType.SHOULDER)
				armCubes.addAll(assembly.rotatingGroup(limb.childSource(), limb.childCube()));
		List<List<Vec3>> allCubes = new ArrayList<>();
		List<List<Vec3>> bodyCubes = new ArrayList<>();
		for (int source = 0; source < sources.size(); source++)
			for (Map.Entry<Integer, SlimeBionicAnimator.CubeBox> entry
				: sources.get(source).boxes().entrySet()) {
				List<Vec3> points = entry.getValue().points();
				allCubes.add(points);
				if (!armCubes.contains(new SurgicalAssembly.CombinationMember(source, entry.getKey())))
					bodyCubes.add(points);
			}
		SurgicalBodyBounds.Envelope visible = new SurgicalBodyBounds.Envelope(
			bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
		SurgicalAssembly.BodyBounds bodyBounds = SurgicalBodyBounds.measure(bodyCubes, allCubes, visible);
		if (bodyBounds != null)
			entity.setClientBodyBounds(assembly, bodyBounds);
	}

	/**
	 * Renders or measures every source of a composite body.
	 *
	 * <p>When {@code restStates} is supplied each source's rest geometry is collected instead of an
	 * animation frame being applied, which is how the still reference pose is captured.</p>
	 */
	private static void renderCompositeSources(SlimeBionicEntity entity, SurgicalAssembly assembly,
		BodyFrame bodyFrame, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight,
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
			Map<Integer, Vec3> renderOffsets = restStates == null
				? bodyFrame.rotateOffsets(frame.mergeOffsets(offsets)) : offsets;
			Map<Integer, SurgicalCubeRotation> renderRotations = restStates == null
				? bodyFrame.rotateRotations(frame.mergeRotations(rotations)) : rotations;
			poseStack.pushPose();
			poseStack.translate(source.originOffset().x, source.originOffset().y, source.originOffset().z);
			if (assembly.preservesLayout())
				SurgicalTablePoseResolver.resolve(source.layPose()).apply(poseStack);
			SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(preview,
				source.cubeCount(), source.presentCubes(), renderOffsets, renderRotations,
				poseStack, buffer, packedLight, 0.0f, partialTick, restStates != null, null,
				renderSourceGeometry);
			if (restStates != null)
				restStates.add(new SlimeBionicAnimator.SourceState(
					SlimeBionicAnimator.measure(snapshot), offsets, rotations));
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
	private static CachedGeometry rebuildGeometry(SlimeBionicEntity entity, LivingEntity preview,
		SurgicalAssembly assembly,
		float partialTick, int packedLight, boolean slimeForm) {
		if (preview == null)
			return null;

		BitSet presentCubes = assembly.presentCubes();
		Map<Integer, Vec3> offsets = componentOffsets(preview, assembly, presentCubes, partialTick,
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
				new PoseStack(), measuringBuffer, packedLight, 0.0f, partialTick, true, null, !slimeForm));
		EntityGeometry.Bounds bounds = bodyGeometry.bounds();
		Map<Integer, SlimeBionicAnimator.CubeBox> restBoxes = restPose[0] == null ? Map.of()
			: SlimeBionicAnimator.measure(restPose[0]);
		updateClientBodyBounds(entity, assembly, bounds,
			List.of(new SlimeBionicAnimator.SourceState(restBoxes, offsets, assembly.cubeRotations())));
		Vec3 modelOffset = new Vec3(-bounds.centerX(), -bounds.minY(), -bounds.centerZ());
		return new CachedGeometry(assembly, slimeForm, presentCubes, offsets, modelOffset, restBoxes);
	}

	private static Map<Integer, Vec3> componentOffsets(LivingEntity preview, SurgicalAssembly assembly,
		BitSet presentCubes, float partialTick, int packedLight) {
		EntityGeometry.Collector discardedVertices = EntityGeometry.Collector.boundsOnly();
		MultiBufferSource discardedBuffer = renderType -> discardedVertices;
		SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(preview,
			assembly.cubeCount(), presentCubes, Map.of(), new PoseStack(), discardedBuffer, packedLight,
			0.0f, partialTick, true, null);
		return SurgicalClientTopology.componentOffsets(assembly.cubeCount(), presentCubes,
			assembly.seams(), assembly.cutSeams(), snapshot.cubes());
	}

	@Override
	public ResourceLocation getTextureLocation(SlimeBionicEntity entity) {
		return SLIME_TEXTURE;
	}

	private record CachedGeometry(SurgicalAssembly assembly, boolean slimeForm, BitSet presentCubes,
		Map<Integer, Vec3> offsets, Vec3 modelOffset,
		Map<Integer, SlimeBionicAnimator.CubeBox> restBoxes) {
		private CachedGeometry {
			presentCubes = (BitSet) presentCubes.clone();
			offsets = Map.copyOf(offsets);
		}
	}

	private record CompositeCachedGeometry(SurgicalAssembly assembly, boolean slimeForm,
		Vec3 modelOffset, List<SlimeBionicAnimator.SourceState> sources) {}

	/**
	 * Converts cached yaw-zero component transforms into the final render axes. Base geometry and
	 * source origins are rotated by the pose stack; component offsets and quaternions are applied
	 * after that pose, so they must be reframed explicitly by the same body rotation.
	 */
	private record BodyFrame(float yaw, SurgicalCubeRotation rotation) {
		private static final BodyFrame IDENTITY = new BodyFrame(0.0f, SurgicalCubeRotation.IDENTITY);

		private static BodyFrame of(float yaw) {
			float wrapped = Mth.wrapDegrees(yaw);
			if (Math.abs(wrapped) <= 1.0e-6f)
				return IDENTITY;
			return new BodyFrame(wrapped,
				SurgicalCubeRotation.around(new Vec3(0.0d, 1.0d, 0.0d), -wrapped));
		}

		private void apply(PoseStack poseStack) {
			if (this != IDENTITY)
				poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
		}

		private Map<Integer, Vec3> rotateOffsets(Map<Integer, Vec3> offsets) {
			if (this == IDENTITY || offsets.isEmpty())
				return offsets;
			Map<Integer, Vec3> rotated = new java.util.HashMap<>(offsets.size());
			offsets.forEach((cube, offset) -> rotated.put(cube, rotation.rotate(offset)));
			return Map.copyOf(rotated);
		}

		private Map<Integer, SurgicalCubeRotation> rotateRotations(
			Map<Integer, SurgicalCubeRotation> rotations) {
			if (this == IDENTITY || rotations.isEmpty())
				return rotations;
			Quaternionf frame = quaternion(rotation);
			Quaternionf inverse = new Quaternionf(frame).conjugate();
			Map<Integer, SurgicalCubeRotation> rotated = new java.util.HashMap<>(rotations.size());
			rotations.forEach((cube, local) -> {
				Quaternionf reframed = new Quaternionf(frame).mul(quaternion(local)).mul(inverse);
				rotated.put(cube, new SurgicalCubeRotation(reframed.x(), reframed.y(), reframed.z(),
					reframed.w()));
			});
			return Map.copyOf(rotated);
		}

		private static Quaternionf quaternion(SurgicalCubeRotation rotation) {
			return new Quaternionf((float) rotation.x(), (float) rotation.y(), (float) rotation.z(),
				(float) rotation.w());
		}
	}
}
