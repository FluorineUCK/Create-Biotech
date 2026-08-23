package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.BitSet;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
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
	private static final BitSet EMPTY_CUBES = new BitSet();

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
		poseStack.translate(table.getOriginOffsetX(), 0.0d, table.getOriginOffsetZ());
		SurgicalTablePoseResolver.resolve(table, profile, preview, facing).apply(poseStack);

		int storedCount = table.getCubeCount();
		boolean collectGeometry = SurgicalTableClientHandler.needsGeometryUpdate(table);
		BitSet present = storedCount > 0
			? SurgicalTableClientHandler.presentCubesFor(table, storedCount) : EMPTY_CUBES;
		// Geometry is always captured in the unseparated base pose. Derived, offset
		// interaction geometry is rebuilt only when a cut/present state revision arrives.
		Map<Integer, Vec3> offsets = collectGeometry ? Map.of() : SurgicalTableClientHandler.offsetsFor(table);
		Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
		SurgicalModelRenderContext.Snapshot snapshot = SurgicalSourceModelRenderer.render(preview,
			storedCount, present, offsets, poseStack, buffer, packedLight, 0.0f, 0.0f, collectGeometry, camera);
		poseStack.popPose();

		if (collectGeometry)
			SurgicalTableClientHandler.updateGeometry(table, snapshot);
	}
}
