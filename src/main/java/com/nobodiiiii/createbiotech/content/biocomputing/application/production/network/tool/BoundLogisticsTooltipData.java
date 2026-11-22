package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.NetworkNamesSavedData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Bounded, non-authoritative directory-entry snapshots synchronized with configured items. */
public record BoundLogisticsTooltipData(List<LogisticsNetworkDirectoryEntry> entries) {
	// Wire bound of already-saved item tooltip snapshots; not a production cluster membership limit.
	private static final int MAX_ENTRIES = 32;
	private static final String ROOT_KEY = "CreateBiotechBoundLogisticsTooltip";
	private static final String ENTRIES_KEY = "Entries";
	private static final String ID_KEY = "Id";
	private static final String ORDINAL_KEY = "Ordinal";
	private static final String ALIAS_KEY = "CustomAlias";
	private static final String REVISION_KEY = "Revision";

	public BoundLogisticsTooltipData {
		entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
		if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException(
			"too many tooltip entries");
		Set<UUID> ids = new HashSet<>();
		if (entries.stream().anyMatch(entry -> !ids.add(entry.logisticsNetworkId())))
			throw new IllegalArgumentException("duplicate tooltip entries");
	}

	public Optional<LogisticsNetworkDirectoryEntry> find(UUID networkId) {
		return entries.stream().filter(entry -> entry.logisticsNetworkId().equals(networkId)).findFirst();
	}

	public static Optional<BoundLogisticsTooltipData> read(ItemStack stack) {
		Objects.requireNonNull(stack, "stack");
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.empty();
		CompoundTag root = data.copyTag();
		if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) return Optional.empty();
		CompoundTag payload = root.getCompound(ROOT_KEY);
		if (!payload.getAllKeys().equals(Set.of(ENTRIES_KEY))
			|| !payload.contains(ENTRIES_KEY, Tag.TAG_LIST)) return Optional.empty();
		ListTag encoded = payload.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
		if (encoded.size() > MAX_ENTRIES) return Optional.empty();
		List<LogisticsNetworkDirectoryEntry> entries = new ArrayList<>(encoded.size());
		try {
			for (int index = 0; index < encoded.size(); index++) {
				CompoundTag entry = encoded.getCompound(index);
				Set<String> keys = entry.getAllKeys();
				if ((!keys.equals(Set.of(ID_KEY, ORDINAL_KEY, REVISION_KEY))
					&& !keys.equals(Set.of(ID_KEY, ORDINAL_KEY, ALIAS_KEY, REVISION_KEY)))
					|| !entry.hasUUID(ID_KEY) || !entry.contains(ORDINAL_KEY, Tag.TAG_LONG)
					|| !entry.contains(REVISION_KEY, Tag.TAG_LONG)) return Optional.empty();
				Optional<String> alias = entry.contains(ALIAS_KEY, Tag.TAG_STRING)
					? Optional.of(entry.getString(ALIAS_KEY)) : Optional.empty();
				entries.add(new LogisticsNetworkDirectoryEntry(entry.getUUID(ID_KEY),
					entry.getLong(ORDINAL_KEY), alias, entry.getLong(REVISION_KEY)));
			}
			return Optional.of(new BoundLogisticsTooltipData(entries));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	/** Returns true only when the synchronized display-only payload changed. */
	public static boolean refresh(ItemStack stack, List<UUID> bindings,
		LogisticsNetworkDirectory directory) {
		Objects.requireNonNull(stack, "stack");
		bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
		directory = Objects.requireNonNull(directory, "directory");
		if (bindings.size() > MAX_ENTRIES
			|| new HashSet<>(bindings).size() != bindings.size()) return clear(stack);
		List<LogisticsNetworkDirectoryEntry> entries = new ArrayList<>(bindings.size());
		for (UUID binding : bindings) {
			Optional<LogisticsNetworkDirectoryEntry> entry = directory.find(binding);
			if (entry.isEmpty()) return clear(stack);
			entries.add(entry.orElseThrow());
		}
		if (entries.isEmpty()) return clear(stack);
		BoundLogisticsTooltipData next = new BoundLogisticsTooltipData(entries);
		if (read(stack).filter(next::equals).isPresent()) return false;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(ROOT_KEY, next.save()));
		return true;
	}

	/** Only a real selection allocates a name; inspecting arbitrary stacks cannot grow world data. */
	public static void selected(net.minecraft.server.level.ServerPlayer player, ItemStack stack, UUID network) {
		if (!com.simibubi.create.Create.LOGISTICS.mayInteract(network, player)) return;
		var names = NetworkNamesSavedData.directory(player.server);
		if (names.isPresent() && names.get().tryEnsure(network).succeeded()) {
			refresh(stack, List.of(network), names.get());
		} else {
			clear(stack);
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
				"create_biotech.production_port.network_name_unavailable"), true);
		}
	}

	public static boolean refreshServer(ItemStack stack, List<UUID> bindings,
		net.minecraft.server.level.ServerLevel level) {
		if (bindings.isEmpty()) return clear(stack);
		return NetworkNamesSavedData.directory(level.getServer()).map(names -> refresh(stack, bindings, names))
			.orElseGet(() -> clear(stack));
	}

	/** Do not even load the directory for menus without a tuned item. */
	public static void refreshVisibleMenu(net.minecraft.server.level.ServerPlayer player) {
		var menu = player.containerMenu;
		java.util.function.Supplier<Optional<LogisticsNetworkDirectory>> names =
			() -> NetworkNamesSavedData.directory(player.server);
		refreshEligible(menu.getCarried(), names);
		for (var slot : menu.slots) refreshEligible(slot.getItem(), names);
	}

	static void refreshVisibleStacks(ItemStack carried, List<ItemStack> stacks, LogisticsNetworkDirectory directory) {
		java.util.function.Supplier<Optional<LogisticsNetworkDirectory>> names = () -> Optional.of(directory);
		refreshEligible(carried, names);
		for (var stack : stacks) refreshEligible(stack, names);
	}

	static void refreshEligible(ItemStack stack, java.util.function.Supplier<Optional<LogisticsNetworkDirectory>> names) {
		Optional<UUID> selected;
		if (stack.getItem() instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.production.port.MaterialRequestPortItem)
			selected = Optional.ofNullable(com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem.networkFromStack(stack));
		else if (stack.getItem() instanceof com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsConfigurationToolItem)
			selected = com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool.LogisticsToolData.read(stack).networkId();
		else return;
		if (selected.isEmpty()) { clear(stack); return; }
		var available = names.get();
		if (available.isPresent()) refresh(stack, List.of(selected.get()), available.get()); else clear(stack);
	}

	public static boolean clearProjection(ItemStack stack) {
		return clear(Objects.requireNonNull(stack, "stack"));
	}
	private static boolean clear(ItemStack stack) {
		if (read(stack).isEmpty()) return false;
		CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.remove(ROOT_KEY));
		return true;
	}

	private CompoundTag save() {
		CompoundTag payload = new CompoundTag();
		ListTag encoded = new ListTag();
		for (LogisticsNetworkDirectoryEntry entry : entries) {
			CompoundTag saved = new CompoundTag();
			saved.putUUID(ID_KEY, entry.logisticsNetworkId());
			saved.putLong(ORDINAL_KEY, entry.ordinal());
			entry.customAlias().ifPresent(alias -> saved.putString(ALIAS_KEY, alias));
			saved.putLong(REVISION_KEY, entry.revision());
			encoded.add(saved);
		}
		payload.put(ENTRIES_KEY, encoded);
		return payload;
	}
}
