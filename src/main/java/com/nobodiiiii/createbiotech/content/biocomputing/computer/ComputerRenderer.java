package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentRenderHost;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentRenderHelper;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Client-only resident rendering for the lower, state-owning computer half. */
public class ComputerRenderer<T extends BlockEntity & ComputerResidentRenderHost> implements BlockEntityRenderer<T> {
	public static final ResidentRenderHelper.ResidentRenderLayout RESIDENT_LAYOUT =
		new ResidentRenderHelper.ResidentRenderLayout(-0.125d, 1.0f,
			ResidentRenderHelper.ResidentPose.STANDING);
	private final ResidentRenderHelper residents;

	public ComputerRenderer(BlockEntityRendererProvider.Context context) {
		residents = new ResidentRenderHelper(context);
	}

	@Override
	public void render(T computer, float partialTick, PoseStack poseStack,
		MultiBufferSource buffer, int packedLight, int packedOverlay) {
		if (computer.getBlockState().getValue(ComputerBlock.HALF) != DoubleBlockHalf.LOWER)
			return;
		ComputerResidentView state = computer.residentView();
		if (!state.renderResident() || state.resident() == null)
			return;
		residents.render(computer, state.resident(), computer.getBlockState().getValue(ComputerBlock.FACING),
			RESIDENT_LAYOUT, partialTick, poseStack, buffer);
	}

	@Override
	public boolean shouldRenderOffScreen(T computer) {
		return computer.getBlockState().getValue(ComputerBlock.HALF) == DoubleBlockHalf.LOWER;
	}

	@Override
	public AABB getRenderBoundingBox(T computer) {
		return new AABB(computer.getBlockPos()).expandTowards(0, 1, 0);
	}
}
