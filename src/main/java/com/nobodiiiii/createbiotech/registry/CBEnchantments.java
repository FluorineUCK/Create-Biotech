package com.nobodiiiii.createbiotech.registry;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;

public final class CBEnchantments {

	public static final ResourceKey<Enchantment> SONIC_BOOM = ResourceKey.create(
		Registries.ENCHANTMENT, CreateBiotech.asResource("sonic_boom"));

	private CBEnchantments() {}
}
