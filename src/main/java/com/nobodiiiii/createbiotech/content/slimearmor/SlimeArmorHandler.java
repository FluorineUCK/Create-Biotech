package com.nobodiiiii.createbiotech.content.slimearmor;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Shared helpers for the slime armour set. All server-side stealth behaviour (hitbox, visibility,
 * mob targeting, advancement) is inherited from Create's {@code CardboardArmorHandler} because
 * {@link SlimeArmorItem} extends {@code CardboardArmorItem}; this class only adds the "full slime
 * set" test used by the client to render the player as a small slime instead of a package.
 */
public final class SlimeArmorHandler {

	private SlimeArmorHandler() {}

	public static boolean isSlimeArmor(ItemStack stack) {
		return stack.getItem() instanceof SlimeArmorItem;
	}

	/**
	 * Mirrors {@code CardboardArmorHandler.testForStealth} but requires every slot to be a slime
	 * armour piece, so a full slime set renders as a slime while a mixed/cardboard set keeps
	 * Create's package rendering.
	 */
	public static boolean testForSlimeStealth(Entity entityIn) {
		if (!(entityIn instanceof LivingEntity entity))
			return false;
		if (entity.getPose() != Pose.CROUCHING)
			return false;
		if (entity instanceof Player player && player.getAbilities().flying)
			return false;
		return isSlimeArmor(entity.getItemBySlot(EquipmentSlot.HEAD))
			&& isSlimeArmor(entity.getItemBySlot(EquipmentSlot.CHEST))
			&& isSlimeArmor(entity.getItemBySlot(EquipmentSlot.LEGS))
			&& isSlimeArmor(entity.getItemBySlot(EquipmentSlot.FEET));
	}
}
