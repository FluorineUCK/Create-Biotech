package com.nobodiiiii.createbiotech.content.slimearmor;

import java.util.Locale;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.equipment.armor.CardboardArmorItem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

/**
 * A slime counterpart to Create's cardboard armor. It extends {@link CardboardArmorItem} so it
 * shares every cardboard behaviour handled by Create's server-side handler (mob stealth, shrunken
 * hitbox, the helmet blur overlay and the advancement) because those handlers key off
 * {@code instanceof CardboardArmorItem}. The only differences are the slime-tinted armour texture
 * and, when the full set is worn while crouching, rendering as a small slime instead of a package
 * (see {@code SlimeArmorRenderHandler}).
 */
public class SlimeArmorItem extends CardboardArmorItem {

	public SlimeArmorItem(Type type, Properties properties) {
		super(type, properties);
	}

	@Override
	public @Nullable ResourceLocation getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot,
		ArmorMaterial.Layer layer, boolean innerModel) {
		return CreateBiotech.asResource(String.format(Locale.ROOT, "textures/models/armor/slime_layer_%d.png",
			slot == EquipmentSlot.LEGS ? 2 : 1));
	}
}
