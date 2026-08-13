package com.nobodiiiii.createbiotech.content.sonicdogcannon;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.nobodiiiii.createbiotech.registry.CBDataComponents;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

public enum SonicDogCannonUpgrade implements StringRepresentable {
	VOICE_PACK("voice_pack"),
	SCOPE("scope"),
	SHRIEK_SONIC_BOOM("shriek_sonic_boom"),
	DOG_COLLAR("dog_collar");

	public static final Codec<SonicDogCannonUpgrade> CODEC =
		StringRepresentable.fromEnum(SonicDogCannonUpgrade::values);
	private static final int LEGACY_ORDER_ENTRY_BITS = 2;
	private static final int ORDER_ENTRY_BITS = 3;
	private static final int VERSIONED_ORDER_MARKER = Integer.MIN_VALUE;

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

		SonicDogCannonUpgrade removed = order.removeLast();
		if (removed == DOG_COLLAR)
			stack.remove(CBDataComponents.SONIC_DOG_CANNON_COLLAR_COLOR.get());
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
		if ((encodedOrder & VERSIONED_ORDER_MARKER) == 0)
			return decodeOrder(encodedOrder, LEGACY_ORDER_ENTRY_BITS, order);

		return decodeOrder(encodedOrder & ~VERSIONED_ORDER_MARKER, ORDER_ENTRY_BITS, order);
	}

	private static List<SonicDogCannonUpgrade> decodeOrder(int encodedOrder, int entryBits,
		List<SonicDogCannonUpgrade> order) {
		int entryMask = (1 << entryBits) - 1;
		while (encodedOrder != 0) {
			int encodedUpgrade = encodedOrder & entryMask;
			encodedOrder >>>= entryBits;
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
		return encodedOrder | VERSIONED_ORDER_MARKER;
	}

	public static DyeColor getCollarColor(ItemStack stack) {
		return stack.getOrDefault(CBDataComponents.SONIC_DOG_CANNON_COLLAR_COLOR.get(), DyeColor.RED);
	}

	public static void setCollarColor(ItemStack stack, DyeColor color) {
		DOG_COLLAR.install(stack);
		stack.set(CBDataComponents.SONIC_DOG_CANNON_COLLAR_COLOR.get(), color);
	}

	public String tooltipKey() {
		return "item.create_biotech.sonic_dog_cannon.upgrade." + serializedName;
	}

	@Override
	public String getSerializedName() {
		return serializedName;
	}
}
