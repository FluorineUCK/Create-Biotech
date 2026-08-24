package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
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
		SurgicalSourceModelRenderer.render(preview, storedCount, present, offsets, poseStack, buffer,
			packedLight, 0.0f, 0.0f, false, camera, projectSourceGeometry);
		poseStack.popPose();
	}
}
