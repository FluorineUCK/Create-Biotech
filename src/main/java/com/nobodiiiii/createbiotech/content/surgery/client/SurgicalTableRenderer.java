package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLayPose;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTablePlane;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalSubject;
import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class SurgicalTableRenderer implements BlockEntityRenderer<SurgicalTableBlockEntity> {
	private static final BitSet EMPTY_CUBES = new BitSet();
	private static final Map<SurgicalSubject, CachedSubjectRender> SUBJECT_RENDER_CACHE = new WeakHashMap<>();

	public SurgicalTableRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public AABB getRenderBoundingBox(SurgicalTableBlockEntity table) {
		return table.getRenderBoundingBox();
	}

	@Override
	public boolean shouldRenderOffScreen(SurgicalTableBlockEntity table) {
		// Permit one conservative capture before exact source-model bounds are available. Once
		// measured, normal frustum culling remains enabled for the rest of this data revision.
		return table.hasSubjects() && !table.hasMeasuredClientRenderBounds();
	}

	@Override
	public boolean shouldRender(SurgicalTableBlockEntity table, Vec3 cameraPosition) {
		if (!table.hasSubjects())
			return false;
		AABB bounds = table.getRenderBoundingBox();
		double closestX = Math.max(bounds.minX, Math.min(cameraPosition.x, bounds.maxX));
		double closestY = Math.max(bounds.minY, Math.min(cameraPosition.y, bounds.maxY));
		double closestZ = Math.max(bounds.minZ, Math.min(cameraPosition.z, bounds.maxZ));
		double deltaX = cameraPosition.x - closestX;
		double deltaY = cameraPosition.y - closestY;
		double deltaZ = cameraPosition.z - closestZ;
		double viewDistance = getViewDistance();
		return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= viewDistance * viewDistance;
	}

	@Override
	public void render(SurgicalTableBlockEntity table, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		List<SurgicalSubject> subjects = table.getSubjects();
		if (subjects.isEmpty())
			return;
		boolean projectSourceGeometry = projectsSourceGeometry(table);
		for (SurgicalSubject subject : subjects)
			renderSubject(table, subject, poseStack, buffer, packedLight, projectSourceGeometry);
	}

	static boolean projectsSourceGeometry(SurgicalTableBlockEntity table) {
		return table.getLevel() != null && table.clientProjectsSourceGeometry();
	}

	static boolean projectsSourceGeometry(Level level, SurgicalTablePlane.Plane plane) {
		return plane.valid() && plane.tiles().stream()
			.anyMatch(pos -> level.getBlockState(pos).is(CBBlocks.PROJECTION_SURGICAL_TABLE.get()));
	}

	private static void renderSubject(SurgicalTableBlockEntity table, SurgicalSubject subject,
		PoseStack poseStack, MultiBufferSource buffer, int packedLight, boolean projectSourceGeometry) {
		MimicProfile profile = subject.profile();
		LivingEntity preview = SurgicalSourceModelRenderer.preview(subject, profile);
		if (preview == null)
			return;

		poseStack.pushPose();
		poseStack.translate(subject.originOffsetX(), 0.0d, subject.originOffsetZ());
		SurgicalTablePoseResolver.resolve(subject.layPose()).apply(poseStack);
		int storedCount = subject.cubeCount();
		boolean collectGeometry = SurgicalTableClientHandler.needsGeometryUpdate(table, subject);
		BitSet present = storedCount > 0
			? SurgicalTableClientHandler.presentCubesFor(table, subject, storedCount) : EMPTY_CUBES;
		Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		if (collectGeometry) {
			SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.captureGeometry(preview,
				storedCount, present, poseStack, packedLight, 0.0f, 0.0f, camera, projectSourceGeometry);
			SurgicalTableClientHandler.updateGeometry(table, subject, snapshot);
		}
		Map<Integer, Vec3> offsets = SurgicalTableClientHandler.offsetsFor(table, subject);
		CachedSubjectRender cached = SUBJECT_RENDER_CACHE.get(subject);
		if (cached == null || !cached.key().matches(preview, subject.layPose(), storedCount, present,
			offsets, packedLight, projectSourceGeometry)) {
			MeshKey key = new MeshKey(preview, subject.layPose(), storedCount, present, offsets,
				packedLight, projectSourceGeometry);
			Map<Integer, Vec3> meshOffsets = modelSpaceOffsets(subject, offsets);
			cached = bakeSubject(key, preview, storedCount, present, meshOffsets, packedLight,
				projectSourceGeometry);
			SUBJECT_RENDER_CACHE.put(subject, cached);
		}
		if (cached.mesh() != null)
			cached.mesh().render(poseStack, buffer);
		else
			SurgicalSourceModelRenderer.render(preview, storedCount, present, offsets, poseStack, buffer,
				packedLight, 0.0f, 0.0f, false, camera, projectSourceGeometry);
		poseStack.popPose();
	}

	/**
	 * Component offsets are stored and used by selection topology in the table frame.
	 * A cached mesh, however, is baked before the subject's recumbent pose and receives
	 * that pose only when replayed. Convert vectors into model space first so replaying
	 * the mesh applies the pose exactly once instead of rotating the offsets a second time.
	 */
	private static Map<Integer, Vec3> modelSpaceOffsets(SurgicalSubject subject,
		Map<Integer, Vec3> tableOffsets) {
		if (tableOffsets.isEmpty())
			return tableOffsets;
		Map<Integer, Vec3> converted = new java.util.HashMap<>(tableOffsets.size());
		tableOffsets.forEach((cube, offset) ->
			converted.put(cube, subject.layPose().inverseRotate(offset)));
		return Map.copyOf(converted);
	}

	private static CachedSubjectRender bakeSubject(MeshKey key, LivingEntity preview, int storedCount,
		BitSet present, Map<Integer, Vec3> offsets, int packedLight, boolean projectSourceGeometry) {
		SurgicalTableRenderMesh.Builder builder = SurgicalTableRenderMesh.builder();
		try {
			SurgicalSourceModelRenderer.render(preview, storedCount, present, offsets, new PoseStack(), builder,
				packedLight, 0.0f, 0.0f, false, null, projectSourceGeometry);
			return new CachedSubjectRender(key, builder.build());
		} catch (RuntimeException | LinkageError ignored) {
			// A non-standard renderer may require Minecraft's concrete buffer implementation.
			// Remember the unsupported key and retain the original direct-render path.
			return new CachedSubjectRender(key, null);
		}
	}

	public static void clearCache() {
		SUBJECT_RENDER_CACHE.clear();
	}

	private record MeshKey(LivingEntity preview, SurgicalLayPose layPose, int cubeCount, BitSet presentCubes,
		Map<Integer, Vec3> offsets, int packedLight, boolean projectSourceGeometry) {
		private MeshKey {
			presentCubes = (BitSet) presentCubes.clone();
			offsets = Map.copyOf(offsets);
		}

		private boolean matches(LivingEntity currentPreview, SurgicalLayPose currentLayPose,
			int currentCubeCount, BitSet currentPresentCubes, Map<Integer, Vec3> currentOffsets,
			int currentPackedLight, boolean currentProjectSourceGeometry) {
			return preview == currentPreview && layPose.equals(currentLayPose)
				&& cubeCount == currentCubeCount && presentCubes.equals(currentPresentCubes)
				&& offsets.equals(currentOffsets) && packedLight == currentPackedLight
				&& projectSourceGeometry == currentProjectSourceGeometry;
		}
	}

	private record CachedSubjectRender(MeshKey key, SurgicalTableRenderMesh mesh) {}
}
