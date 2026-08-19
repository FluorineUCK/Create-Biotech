package com.nobodiiiii.createbiotech.content.buttercat.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nobodiiiii.createbiotech.client.ButterCatPartials;
import com.nobodiiiii.createbiotech.content.buttercat.ButterCatVariants;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModel;
import com.simibubi.create.foundation.item.render.CustomRenderedItemModelRenderer;
import com.simibubi.create.foundation.item.render.PartialItemModelRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Assembles the item out of the same models the block entity renders, so a boxed up cat keeps
 * its breed all the way into the inventory. The cat models are authored lying along the Z axis
 * with their head towards north, which is where the shaft is rendered as well.
 */
public class ButterCatItemRenderer extends CustomRenderedItemModelRenderer {
	private static final Direction.Axis SHAFT_AXIS = Direction.Axis.Z;

	@Override
	protected void render(ItemStack stack, CustomRenderedItemModel model, PartialItemModelRenderer renderer,
		ItemDisplayContext transformType, PoseStack poseStack, MultiBufferSource buffer, int light, int overlay) {
		renderer.render(getShaftModel(), light);
		renderer.render(ButterCatPartials.getCatModel(ButterCatVariants.ofBlockItemOrDefault(stack))
			.get(), light);

		if (!hasBread(stack))
			return;
		renderer.render(ButterCatPartials.BREAD.get(), light);
		renderer.render(ButterCatPartials.ROPE.get(), light);
	}

	private static boolean hasBread(ItemStack stack) {
		return stack.getItem() instanceof BlockItem blockItem
			&& blockItem.getBlock() == CBBlocks.BUTTER_CAT_ENGINE.get();
	}

	private static BakedModel getShaftModel() {
		BlockState shaft = AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, SHAFT_AXIS);
		return Minecraft.getInstance()
			.getBlockRenderer()
			.getBlockModel(shaft);
	}
}
