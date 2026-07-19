package com.nobodiiiii.createbiotech.content.ghasthotairballoon;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;

public class GhastBalloonRopeShearsInteraction extends MovingInteractionBehaviour {

	@Override
	public boolean handlePlayerInteraction(Player player, InteractionHand activeHand, BlockPos localPos,
		AbstractContraptionEntity contraptionEntity) {
		if (!(contraptionEntity instanceof GhastHotAirBalloonEntity ghastBalloon))
			return false;

		ItemStack heldItem = player.getItemInHand(activeHand);
		if (!(heldItem.getItem() instanceof ShearsItem))
			return false;

		Level level = player.level();
		if (level.isClientSide)
			return true;

		Entity vehicle = ghastBalloon.getVehicle();
		if (vehicle instanceof Ghast ghast) {
			ghast.setNoAi(false);
			CapturedEntityBoxHelper.unmarkAiDisabledByMod(ghast);
		}

		level.playSound(null, ghastBalloon.blockPosition(),
			SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 1.0f, 1.0f);

		if (!player.getAbilities().instabuild)
			heldItem.hurtAndBreak(1, player, LivingEntity.getSlotForHand(activeHand));

		ghastBalloon.disassemble();
		return true;
	}
}
