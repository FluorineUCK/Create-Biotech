package com.nobodiiiii.createbiotech.content.magmabelt;

import java.util.Random;
import java.util.function.Supplier;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.nobodiiiii.createbiotech.content.magmabelt.transport.MagmaBeltInventory;
import com.nobodiiiii.createbiotech.client.render.CBBeltRenderHelper;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.render.ShadowRenderHelper;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.levelWrappers.WrappedLevel;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class MagmaBeltRenderer extends SafeBlockEntityRenderer<MagmaBeltBlockEntity> {

	public MagmaBeltRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public boolean shouldRenderOffScreen(MagmaBeltBlockEntity be) {
		return be.isController();
	}

	@Override
	protected void renderSafe(MagmaBeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay) {

		if (!VisualizationManager.supportsVisualization(be.getLevel())) {

			BlockState blockState = be.getBlockState();
			if (!MagmaBeltBlock.isMagmaBelt(blockState))
				return;
			CBBeltRenderHelper.renderSurface(be, blockState, ms, buffer, light,
				MagmaBeltRenderer::getSpriteShiftEntry, 0);
		}

		renderItems(be, partialTicks, ms, buffer, light, overlay);
	}

	public static SpriteShiftEntry getSpriteShiftEntry(boolean diagonal, boolean bottom) {
		return diagonal ? MagmaBeltSpriteShifts.BELT_DIAGONAL
			: bottom ? MagmaBeltSpriteShifts.BELT_OFFSET : MagmaBeltSpriteShifts.BELT;
	}

	public static PartialModel getBeltPartial(boolean diagonal, boolean start, boolean end, boolean bottom) {
		if (diagonal) {
			if (start)
				return AllPartialModels.BELT_DIAGONAL_START;
			if (end)
				return AllPartialModels.BELT_DIAGONAL_END;
			return AllPartialModels.BELT_DIAGONAL_MIDDLE;
		} else if (bottom) {
			if (start)
				return AllPartialModels.BELT_START_BOTTOM;
			if (end)
				return AllPartialModels.BELT_END_BOTTOM;
			return AllPartialModels.BELT_MIDDLE_BOTTOM;
		} else {
			if (start)
				return AllPartialModels.BELT_START;
			if (end)
				return AllPartialModels.BELT_END;
			return AllPartialModels.BELT_MIDDLE;
		}
	}

	protected void renderItems(MagmaBeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
		int light, int overlay) {
		if (!be.isController())
			return;
		if (be.beltLength == 0)
			return;

		ms.pushPose();

		Direction beltFacing = be.getBeltFacing();
		Vec3i directionVec = beltFacing.getNormal();
		Vec3 beltStartOffset = Vec3.atLowerCornerOf(directionVec)
			.scale(-.5)
			.add(.5, 15 / 16f, .5);
		ms.translate(beltStartOffset.x, beltStartOffset.y, beltStartOffset.z);
		BeltSlope slope = be.getBlockState()
			.getValue(MagmaBeltBlock.SLOPE);
		int verticality = slope == BeltSlope.DOWNWARD ? -1 : slope == BeltSlope.UPWARD ? 1 : 0;
		boolean slopeAlongX = beltFacing.getAxis() == Direction.Axis.X;
		boolean onContraption = be.getLevel() instanceof WrappedLevel;

		MagmaBeltInventory inventory = be.getInventory();
		for (TransportedItemStack transported : inventory.getTransportedItems())
			renderItem(be, partialTicks, ms, buffer, light, overlay, beltFacing, directionVec, slope, verticality,
				slopeAlongX, onContraption, transported, beltStartOffset);
		if (inventory.getLazyClientItem() != null)
			renderItem(be, partialTicks, ms, buffer, light, overlay, beltFacing, directionVec, slope, verticality,
				slopeAlongX, onContraption, inventory.getLazyClientItem(), beltStartOffset);

		ms.popPose();
	}

	private void renderItem(MagmaBeltBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
		int overlay, Direction beltFacing, Vec3i directionVec, BeltSlope slope, int verticality, boolean slopeAlongX,
		boolean onContraption, TransportedItemStack transported, Vec3 beltStartOffset) {
		Minecraft mc = Minecraft.getInstance();
		ItemRenderer itemRenderer = mc.getItemRenderer();
		MutableBlockPos mutablePos = new MutableBlockPos();

		float offset = Mth.lerp(partialTicks, transported.prevBeltPosition, transported.beltPosition);
		float sideOffset = Mth.lerp(partialTicks, transported.prevSideOffset, transported.sideOffset);
		float verticalMovement = verticality;

		if (be.getSpeed() == 0) {
			offset = transported.beltPosition;
			sideOffset = transported.sideOffset;
		}

		if (offset < .5)
			verticalMovement = 0;
		else
			verticalMovement = verticality * (Math.min(offset, be.beltLength - .5f) - .5f);
		Vec3 offsetVec = Vec3.atLowerCornerOf(directionVec)
			.scale(offset);
		if (verticalMovement != 0)
			offsetVec = offsetVec.add(0, verticalMovement, 0);
		boolean onSlope = slope != BeltSlope.HORIZONTAL && Mth.clamp(offset, .5f, be.beltLength - .5f) == offset;
		boolean tiltForward = (slope == BeltSlope.DOWNWARD
			^ beltFacing.getAxisDirection() == AxisDirection.POSITIVE) == (beltFacing.getAxis() == Direction.Axis.Z);
		float slopeAngle = onSlope ? tiltForward ? -45 : 45 : 0;

		Vec3 itemPos = beltStartOffset.add(
				be.getBlockPos().getX(),
				be.getBlockPos().getY(),
				be.getBlockPos().getZ())
			.add(offsetVec);

		if (this.shouldCullItem(itemPos, be.getLevel())) {
			return;
		}

		ms.pushPose();
		TransformStack.of(ms).nudge(transported.angle);
		ms.translate(offsetVec.x, offsetVec.y, offsetVec.z);

		boolean alongX = beltFacing.getClockWise()
			.getAxis() == Direction.Axis.X;
		if (!alongX)
			sideOffset *= -1;
		ms.translate(alongX ? sideOffset : 0, 0, alongX ? 0 : sideOffset);

		int stackLight;
		if (onContraption) {
			stackLight = light;
		} else {
			int segment = (int) Math.floor(offset);
			mutablePos.set(be.getBlockPos()).move(directionVec.getX() * segment, verticality * segment, directionVec.getZ() * segment);
			stackLight = LevelRenderer.getLightColor(be.getLevel(), mutablePos);
		}

		boolean renderUpright = MagmaBeltHelper.isItemUpright(transported.stack);
		BakedModel bakedModel = itemRenderer.getModel(transported.stack, be.getLevel(), null, 0);
		boolean blockItem = bakedModel.isGui3d();

		int count = 0;
		if (be.getLevel() instanceof PonderLevel || mc.player.getEyePosition(1.0F).distanceTo(itemPos) < 16)
			count = (int) (Mth.log2((int) (transported.stack.getCount()))) / 2;

		Random r = new Random(transported.angle);

		boolean slopeShadowOnly = renderUpright && onSlope;
		float slopeOffset = 1 / 8f;
		if (slopeShadowOnly)
			ms.pushPose();
		if (!renderUpright || slopeShadowOnly)
			ms.mulPose((slopeAlongX ? Axis.ZP : Axis.XP).rotationDegrees(slopeAngle));
		if (onSlope)
			ms.translate(0, slopeOffset, 0);
		ms.pushPose();
		ms.translate(0, -1 / 8f + 0.005f, 0);
		ShadowRenderHelper.renderShadow(ms, buffer, .75f, .2f);
		ms.popPose();
		if (slopeShadowOnly) {
			ms.popPose();
			ms.translate(0, slopeOffset, 0);
		}

		if (renderUpright) {
			Vec3 cameraPosition = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			Vec3 vectorForOffset = MagmaBeltHelper.getVectorForOffset(be, offset);
			Vec3 diff = vectorForOffset.subtract(cameraPosition);
			float yRot = (float) (Mth.atan2(diff.x, diff.z) + Math.PI);
			ms.mulPose(Axis.YP.rotation(yRot));
			ms.translate(0, 3 / 32d, 1 / 16f);
		}

		for (int i = 0; i <= count; i++) {
			ms.pushPose();

			boolean box = PackageItem.isPackage(transported.stack);
			ms.mulPose(Axis.YP.rotationDegrees(transported.angle));
			if (!blockItem && !renderUpright) {
				ms.translate(0, -.09375, 0);
				ms.mulPose(Axis.XP.rotationDegrees(90));
			}

			if (blockItem && !box)
				ms.translate(r.nextFloat() * .0625f * i, 0, r.nextFloat() * .0625f * i);

			if (box) {
				ms.translate(0, 4 / 16f, 0);
				ms.scale(1.5f, 1.5f, 1.5f);
			} else {
				ms.scale(.5f, .5f, .5f);
			}

			itemRenderer.render(transported.stack, ItemDisplayContext.FIXED, false, ms, buffer, stackLight, overlay, bakedModel);
			ms.popPose();

			if (!renderUpright) {
				if (!blockItem)
					ms.mulPose(Axis.YP.rotationDegrees(10));
				ms.translate(0, blockItem ? 1 / 64d : 1 / 16d, 0);
			} else
				ms.translate(0, 0, -1 / 16f);

		}

		ms.popPose();
	}
}
