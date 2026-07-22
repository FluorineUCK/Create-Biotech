package com.yision.allay.logistics.address;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.world.item.ItemStack;

public final class AllayAddressRules {
	public static final int MAX_PORT_ADDRESS_LENGTH = 25;

	private AllayAddressRules() {}

	public static String normalize(String address) {
		return address == null ? "" : address.trim();
	}

	public static String normalizePortAddress(String address) {
		String normalized = normalize(address);
		return normalized.length() <= MAX_PORT_ADDRESS_LENGTH
			? normalized : normalized.substring(0, MAX_PORT_ADDRESS_LENGTH);
	}

	public static boolean isBlank(String address) {
		return normalize(address).isBlank();
	}

	public static boolean isExplicitPlayerAddress(String address) {
		return address != null && address.startsWith("@");
	}

	public static String explicitPlayerName(String address) {
		return isExplicitPlayerAddress(address) ? address.substring(1).trim() : "";
	}

	public static boolean matches(String left, String right) {
		return PackageItem.matchAddress(normalize(left), normalize(right));
	}

	public static boolean exact(String left, String right) {
		return normalize(left).equals(normalize(right));
	}

	public static boolean matchesPackage(ItemStack box, String address) {
		if (!PackageItem.isPackage(box)) {
			return false;
		}
		String packageAddress = PackageItem.getAddress(box);
		return !isExplicitPlayerAddress(packageAddress) && matches(packageAddress, address);
	}
}
