package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlock;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public class SurgicalTableRenderer implements BlockEntityRenderer<SurgicalTableBlockEntity> {
	public SurgicalTableRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public void render(SurgicalTableBlockEntity table, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		MimicProfile profile = table.getProfile();
		if (profile == null)
			return;
		LivingEntity preview = SurgicalSourceModelRenderer.preview(table, profile);
		if (preview == null)
			return;

		Direction facing = table.getBlockState().getValue(SurgicalTableBlock.FACING);
		poseStack.pushPose();
		poseStack.translate(0.5d, 1.01d, 0.5d);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - facing.toYRot()));
		poseStack.translate(0.0d, 0.0d, -preview.getBbHeight() * 0.5d);
		poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));

		int storedCount = table.getCubeCount();
		int selectionCount = storedCount > 0 ? storedCount : com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly.MAX_CUBES;
		BitSet present = table.getPresentCubesForRender(selectionCount);
		Map<Integer, Vec3> offsets = SurgicalTableClientHandler.offsetsFor(table);
		Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(table, profile,
			storedCount, present, offsets, poseStack, buffer, packedLight, 0.0f, 0.0f, true, camera);
		poseStack.popPose();

		SurgicalTableClientHandler.updateGeometry(table, snapshot);
	}
}
