package com.nobodiiiii.createbiotech.mixin;

import com.nobodiiiii.createbiotech.content.smartglue.SmartSuperGlueItem;
import com.simibubi.create.content.contraptions.glue.SuperGlueItem;
import com.simibubi.create.content.contraptions.glue.SuperGlueSelectionHelper;

import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SuperGlueSelectionHelper.class)
public abstract class MixinSuperGlueSelectionHelper {

	@Inject(method = "collectGlueFromInventory(Lnet/minecraft/world/entity/player/Player;IZ)Z", at = @At("HEAD"),
		cancellable = true)
	private static void createBiotech$excludeSmartGlue(Player player, int requiredAmount, boolean simulate,
		CallbackInfoReturnable<Boolean> cir) {
		if (player.getAbilities().instabuild || requiredAmount == 0) {
			cir.setReturnValue(true);
			return;
		}

		NonNullList<ItemStack> items = player.getInventory().items;
		for (int i = -1; i < items.size(); i++) {
			int slot = i == -1 ? player.getInventory().selected : i;
			ItemStack stack = items.get(slot);
			if (stack.isEmpty() || !(stack.getItem() instanceof SuperGlueItem)
				|| stack.getItem() instanceof SmartSuperGlueItem)
				continue;

			int charges = Math.min(requiredAmount, stack.getMaxDamage() - stack.getDamageValue());
			if (!simulate)
				stack.hurtAndBreak(charges, player, LivingEntity.getSlotForHand(InteractionHand.MAIN_HAND));

			requiredAmount -= charges;
			if (requiredAmount <= 0) {
				cir.setReturnValue(true);
				return;
			}
		}

		cir.setReturnValue(false);
	}
}
