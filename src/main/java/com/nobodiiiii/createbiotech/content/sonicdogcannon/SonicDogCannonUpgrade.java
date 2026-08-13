package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;

public enum SonicDogCannonUpgrade implements StringRepresentable {
	VOICE_PACK("voice_pack"),
	SCOPE("scope"),
	SHRIEK_SONIC_BOOM("shriek_sonic_boom");

	public static final Codec<SonicDogCannonUpgrade> CODEC =
		StringRepresentable.fromEnum(SonicDogCannonUpgrade::values);
	private static final int ORDER_ENTRY_BITS = 2;
	private static final int ORDER_ENTRY_MASK = (1 << ORDER_ENTRY_BITS) - 1;

	private final String serializedName;

	SonicDogCannonUpgrade(String serializedName) {
		this.serializedName = serializedName;
	}

	public boolean isInstalled(ItemStack stack) {
		return getInstallationOrder(stack).contains(this);
	}

	public void install(ItemStack stack) {
		if (isInstalled(stack))
			return;

		List<SonicDogCannonUpgrade> order = getInstallationOrder(stack);
		order.add(this);
		stack.set(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get(), encodeOrder(order));
	}

	public static boolean hasInstalledUpgrade(ItemStack stack) {
		return !getInstallationOrder(stack).isEmpty();
	}

	public static boolean removeLastInstalled(ItemStack stack) {
		List<SonicDogCannonUpgrade> order = getInstallationOrder(stack);
		if (order.isEmpty())
			return false;

		order.removeLast();
		if (order.isEmpty()) {
			stack.remove(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get());
		} else {
			stack.set(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get(), encodeOrder(order));
		}
		return true;
	}

	private static List<SonicDogCannonUpgrade> getInstallationOrder(ItemStack stack) {
		List<SonicDogCannonUpgrade> order = new ArrayList<>();
		int encodedOrder = stack.getOrDefault(CBDataComponents.SONIC_DOG_CANNON_UPGRADES.get(), 0);
		while (encodedOrder != 0) {
			int encodedUpgrade = encodedOrder & ORDER_ENTRY_MASK;
			encodedOrder >>>= ORDER_ENTRY_BITS;
			if (encodedUpgrade == 0 || encodedUpgrade > values().length)
				continue;

			SonicDogCannonUpgrade upgrade = values()[encodedUpgrade - 1];
			if (!order.contains(upgrade))
				order.add(upgrade);
		}
		return order;
	}

	private static int encodeOrder(List<SonicDogCannonUpgrade> order) {
		int encodedOrder = 0;
		for (int i = order.size() - 1; i >= 0; i--)
			encodedOrder = encodedOrder << ORDER_ENTRY_BITS | order.get(i).ordinal() + 1;
		return encodedOrder;
	}

	public String tooltipKey() {
		return "item.create_biotech.sonic_dog_cannon.upgrade." + serializedName;
	}

	@Override
	public String getSerializedName() {
		return serializedName;
	}
}
