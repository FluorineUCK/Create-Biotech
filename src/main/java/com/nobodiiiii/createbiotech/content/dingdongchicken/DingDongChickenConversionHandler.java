package com.nobodiiiii.createbiotech.content.dingdongchicken;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.registry.CBEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllSoundEvents;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class DingDongChickenConversionHandler {

	private DingDongChickenConversionHandler() {}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void convertChicken(PlayerInteractEvent.EntityInteract event) {
		Player player = event.getEntity();
		ItemStack heldItem = player.getItemInHand(event.getHand());
		if (player.isSpectator() || !AllBlocks.DESK_BELL.isIn(heldItem)
			|| !(event.getTarget() instanceof Chicken chicken)
			|| chicken instanceof DingDongChickenEntity)
			return;

		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
		AllSoundEvents.DESK_BELL_USE.play(event.getLevel(), player, chicken.blockPosition());
		if (event.getLevel().isClientSide)
			return;

		int age = chicken.getAge();
		int loveTime = chicken.getInLoveTime();
		int eggTime = chicken.eggTime;
		float health = chicken.getHealth();
		boolean chickenJockey = chicken.isChickenJockey;
		var movement = chicken.getDeltaMovement();

		DingDongChickenEntity converted = chicken.convertTo(CBEntityTypes.DING_DONG_CHICKEN.get(), true);
		if (converted == null)
			return;

		converted.setAge(age);
		converted.setInLoveTime(loveTime);
		converted.eggTime = eggTime;
		converted.setHealth(Math.min(health, converted.getMaxHealth()));
		converted.isChickenJockey = chickenJockey;
		converted.setDeltaMovement(movement);
		if (!player.hasInfiniteMaterials())
			heldItem.shrink(1);
	}
}
