package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** The bounded held-stack payload of the logistics configuration tool. */
public record LogisticsToolData(Optional<UUID> networkId) {
	private static final String ROOT_KEY = "CreateBiotechLogisticsTool";
	private static final String NETWORK_KEY = "NetworkId";
	// The non-placeable tool carries a native Create stock-link selection. Minecraft persists
	// BLOCK_ENTITY_DATA only if it names the source block entity, even for an item in player inventory.
	private static final String STOCK_LINK_TYPE = "create:packager_link";
	private static final String FIRST_ALIAS_KEY = "FirstAliasPending";

	public LogisticsToolData {
		if (networkId == null) throw new IllegalArgumentException("network id is required");
	}

	public static LogisticsToolData read(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		UUID nativeNetwork = LogisticallyLinkedBlockItem.networkFromStack(stack);
		if (nativeNetwork != null) {
			var data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
			if (data != null && !data.contains("id")) writeNativeNetwork(stack, nativeNetwork);
			else removeLegacyNetwork(stack, nativeNetwork);
			return new LogisticsToolData(Optional.of(nativeNetwork));
		}
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) return new LogisticsToolData(Optional.empty());
		CompoundTag root = customData.copyTag();
		if (!root.contains(ROOT_KEY)) return new LogisticsToolData(Optional.empty());
		if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND))
			return new LogisticsToolData(Optional.empty());
		CompoundTag payload = root.getCompound(ROOT_KEY);
		if (!payload.hasUUID(NETWORK_KEY))
			return new LogisticsToolData(Optional.empty());
		UUID legacyNetwork = payload.getUUID(NETWORK_KEY);
		writeNativeNetwork(stack, legacyNetwork);
		return new LogisticsToolData(Optional.of(legacyNetwork));
	}

	public static void write(ItemStack stack, LogisticsToolData data) {
		Objects.requireNonNull(stack, "stack");
		data = Objects.requireNonNull(data, "data");
		if (data.networkId().isEmpty()) {
			clear(stack);
			return;
		}
		UUID network = data.networkId().orElseThrow();
		boolean pending = firstAliasPending(stack, network);
		writeNativeNetwork(stack, network);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag payload = root.getCompound(ROOT_KEY);
			payload.remove(NETWORK_KEY);
			if (pending) payload.putBoolean(FIRST_ALIAS_KEY, true);
			else payload.remove(FIRST_ALIAS_KEY);
			if (payload.isEmpty()) root.remove(ROOT_KEY);
			else root.put(ROOT_KEY, payload);
		});
	}

	public static void writeNativeNetwork(ItemStack stack, UUID network) {
		Objects.requireNonNull(stack, "stack");
		Objects.requireNonNull(network, "network");
		UUID previous = LogisticallyLinkedBlockItem.networkFromStack(stack);
		CompoundTag tag = stack.getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
		if (!tag.contains("id", Tag.TAG_STRING)) tag.putString("id", STOCK_LINK_TYPE);
		tag.putUUID("Freq", network);
		stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag));
		if (previous != null && !previous.equals(network)) clearFirstAliasPending(stack);
		removeLegacyNetwork(stack, network);
	}

	/** UI intent only: authority and revision are still validated by the alias menu. */
	static boolean firstAliasPending(ItemStack stack, UUID network) {
		if (!read(stack).networkId().filter(network::equals).isPresent()) return false;
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		return customData != null && customData.copyTag().getCompound(ROOT_KEY)
			.getBoolean(FIRST_ALIAS_KEY);
	}

	static void setFirstAliasPending(ItemStack stack, UUID network, boolean pending) {
		if (!read(stack).networkId().filter(network::equals).isPresent()) return;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag payload = root.getCompound(ROOT_KEY);
			if (pending) payload.putBoolean(FIRST_ALIAS_KEY, true);
			else payload.remove(FIRST_ALIAS_KEY);
			if (payload.isEmpty()) root.remove(ROOT_KEY);
			else root.put(ROOT_KEY, payload);
		});
	}

	public static void clear(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		stack.remove(DataComponents.BLOCK_ENTITY_DATA);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(ROOT_KEY));
	}

	private static void clearFirstAliasPending(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag payload = root.getCompound(ROOT_KEY);
			payload.remove(FIRST_ALIAS_KEY);
			if (payload.isEmpty()) root.remove(ROOT_KEY);
			else root.put(ROOT_KEY, payload);
		});
	}

	private static void removeLegacyNetwork(ItemStack stack) {
		removeLegacyNetwork(stack, null);
	}

	private static void removeLegacyNetwork(ItemStack stack, UUID replacement) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> {
			CompoundTag payload = root.getCompound(ROOT_KEY);
			if (replacement != null && payload.hasUUID(NETWORK_KEY)
				&& !replacement.equals(payload.getUUID(NETWORK_KEY)))
				payload.remove(FIRST_ALIAS_KEY);
			payload.remove(NETWORK_KEY);
			if (payload.isEmpty()) root.remove(ROOT_KEY);
			else root.put(ROOT_KEY, payload);
		});
	}

	/** A bounded transport fence; the server also retains and compares the exact stack snapshot. */
	public static String fingerprint(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		String material = net.minecraft.core.registries.BuiltInRegistries.ITEM
			.getKey(stack.getItem()) + "|" + stack.getCount() + "|"
			+ ItemStack.hashItemAndComponents(stack);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(material.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(CreateBiotech.MOD_ID + " requires SHA-256", impossible);
		}
	}
}
