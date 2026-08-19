package com.nobodiiiii.createbiotech.content.buttercat;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.feature.CBFeature;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;

import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A cat only ever lies along a horizontal shaft, so applying a boxed cat to an upright one is
 * refused. Without this the item application would quietly turn the shaft sideways, facing
 * whichever way the player happened to stand.
 */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class CuteCatOnShaftConversionHandler {

	private CuteCatOnShaftConversionHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (!CBFeature.BUTTER_CAT.isEnabled())
			return;
		if (!CapturedEntityBoxHelper.containsEntityType(event.getItemStack(), EntityType.CAT))
			return;

		BlockState state = event.getLevel()
			.getBlockState(event.getPos());
		if (!ShaftBlock.isShaft(state) || state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y)
			return;

		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.FAIL);
	}
}
