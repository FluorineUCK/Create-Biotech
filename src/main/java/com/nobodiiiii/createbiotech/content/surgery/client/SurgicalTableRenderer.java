package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
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
		return table.hasSubjects();
	}

	@Override
	public boolean shouldRender(SurgicalTableBlockEntity table, Vec3 cameraPosition) {
		return table.hasSubjects();
	}

	@Override
	public void render(SurgicalTableBlockEntity table, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		if (table.getSubjects().isEmpty())
			return;
		boolean projectSourceGeometry = projectsSourceGeometry(table);
		for (SurgicalSubject subject : table.getSubjects())
			renderSubject(table, subject, poseStack, buffer, packedLight, projectSourceGeometry);
	}

	private static boolean projectsSourceGeometry(SurgicalTableBlockEntity table) {
		if (table.getLevel() == null)
			return false;
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(table.getLevel(), table.getBlockPos());
		return projectsSourceGeometry(table.getLevel(), plane);
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
		SurgicalTablePoseResolver.resolve(subject, profile, preview, subject.placementFacing()).apply(poseStack);
		int storedCount = subject.cubeCount();
		boolean collectGeometry = SurgicalTableClientHandler.needsGeometryUpdate(table, subject);
		BitSet present = storedCount > 0
			? SurgicalTableClientHandler.presentCubesFor(table, subject, storedCount) : EMPTY_CUBES;
		Map<Integer, Vec3> offsets = collectGeometry ? Map.of()
			: SurgicalTableClientHandler.offsetsFor(table, subject);
		Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(preview,
			storedCount, present, offsets, poseStack, buffer, packedLight, 0.0f, 0.0f, collectGeometry, camera,
			projectSourceGeometry);
		poseStack.popPose();
		if (collectGeometry)
			SurgicalTableClientHandler.updateGeometry(table, subject, snapshot);
	}
}
