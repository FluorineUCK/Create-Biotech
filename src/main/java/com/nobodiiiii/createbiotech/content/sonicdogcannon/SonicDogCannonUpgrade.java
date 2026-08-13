package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;

public enum SonicDogCannonUpgrade implements StringRepresentable {
	VOICE_PACK("voice_pack", 1),
	SCOPE("scope", 1 << 1),
	SHRIEK_SONIC_BOOM("shriek_sonic_boom", 1 << 2);

	public static final Codec<SonicDogCannonUpgrade> CODEC =
		StringRepresentable.fromEnum(SonicDogCannonUpgrade::values);

	private final String serializedName;
	private final int bit;

	SonicDogCannonUpgrade(String serializedName, int bit) {
		this.serializedName = serializedName;
		this.bit = bit;
	}

	public boolean isInstalled(ItemStack stack) {
		return (stack.getOrDefault(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get(), 0) & bit) != 0;
	}

	public void install(ItemStack stack) {
		int upgrades = stack.getOrDefault(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get(), 0);
		stack.set(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get(), upgrades | bit);
	}

	public String tooltipKey() {
		return "item.create_biotech.sonic_dog_cannon.upgrade." + serializedName;
	}

	@Override
	public String getSerializedName() {
		return serializedName;
	}
}
