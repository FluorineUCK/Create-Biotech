package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalSubject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class SurgicalTableRenderer implements BlockEntityRenderer<SurgicalTableBlockEntity> {
	private static final BitSet EMPTY_CUBES = new BitSet();

	public SurgicalTableRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public void render(SurgicalTableBlockEntity table, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		for (SurgicalSubject subject : table.getSubjects())
			renderSubject(table, subject, poseStack, buffer, packedLight);
	}

	private static void renderSubject(SurgicalTableBlockEntity table, SurgicalSubject subject,
		PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
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
			storedCount, present, offsets, poseStack, buffer, packedLight, 0.0f, 0.0f, collectGeometry, camera);
		poseStack.popPose();
		if (collectGeometry)
			SurgicalTableClientHandler.updateGeometry(table, subject, snapshot);
	}
}
