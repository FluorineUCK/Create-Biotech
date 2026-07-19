package com.nobodiiiii.createbiotech.content.evokerenchantingchamber;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.advancement.CBAdvancements;
import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public class EvokerEnchantingChamberConversionHandler {

	private EvokerEnchantingChamberConversionHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		Level level = event.getLevel();
		if (!level.getBlockState(event.getPos()).is(Blocks.ENCHANTING_TABLE))
			return;

		ItemStack heldItem = event.getItemStack();
		if (!CapturedEntityBoxHelper.containsEntityType(heldItem, EntityType.EVOKER))
			return;

		if (EvokerEnchantingChamberBlock.hasSpaceForUpperHalf(level, event.getPos()))
			return;

		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.FAIL);
	}

	@SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
	public static void onEnchantingTableConverted(PlayerInteractEvent.RightClickBlock event) {
		if (event.getLevel().isClientSide)
			return;
		if (!(event.getEntity() instanceof ServerPlayer player))
			return;
		if (!event.isCanceled() || event.getCancellationResult() != InteractionResult.SUCCESS)
			return;
		if (!event.getLevel().getBlockState(event.getPos()).is(CBBlocks.EVOKER_ENCHANTING_CHAMBER.get()))
			return;
		CBAdvancements.award(player, CBAdvancements.EVOKER_ENCHANTING_CHAMBER);
	}
}
