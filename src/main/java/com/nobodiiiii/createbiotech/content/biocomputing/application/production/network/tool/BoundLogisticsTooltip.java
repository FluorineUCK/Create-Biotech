package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Pure client-safe composition for configured logistics item tooltips. */
public final class BoundLogisticsTooltip {
	private static final String ROOT = "create_biotech.factory_cluster.tooltip.logistics.";
	private final Function<UUID, Optional<LogisticsNetworkDirectoryEntry>> entries;
	private final Optional<List<UUID>> projectedBindings;

	public BoundLogisticsTooltip(Function<UUID, Optional<LogisticsNetworkDirectoryEntry>> entries) {
		this(entries, Optional.empty());
	}
	private BoundLogisticsTooltip(Function<UUID, Optional<LogisticsNetworkDirectoryEntry>> entries,
		Optional<List<UUID>> projectedBindings) {
		this.entries = Objects.requireNonNull(entries, "entries");
		this.projectedBindings = Objects.requireNonNull(projectedBindings, "projectedBindings").map(List::copyOf);
	}

	/** Uses only the synchronized display projection stored on this stack. */
	public static List<Component> forNativePort(ItemStack stack, boolean shift) {
		var selected = com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBlockItem.networkFromStack(stack);
		return fromStack(stack).build(Optional.ofNullable(selected).stream().toList(), shift, true);
	}

	public static BoundLogisticsTooltip fromStack(ItemStack stack) {
		return BoundLogisticsTooltipData.read(stack).map(data -> new BoundLogisticsTooltip(data::find,
			Optional.of(data.entries().stream().map(LogisticsNetworkDirectoryEntry::logisticsNetworkId).toList())))
			.orElseGet(() -> new BoundLogisticsTooltip(ignored -> Optional.empty()));
	}

	public List<Component> build(List<UUID> bindings, boolean shiftDown, boolean panel) {
		bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
		if (bindings.isEmpty()) return List.of(line("unbound", ChatFormatting.GRAY));
		if (projectedBindings.isPresent()
			&& !projectedBindings.orElseThrow().equals(bindings))
			return List.of(line("synchronizing", ChatFormatting.GRAY));
		List<Component> names = resolvedNames(bindings);
		if (names.isEmpty()) return List.of(line("synchronizing", ChatFormatting.GRAY));
		if (!shiftDown) {
			var summary = Component.empty().append(names.getFirst().copy()
				.withStyle(ChatFormatting.GRAY));
			if (names.size() > 1) summary.append(Component.literal(" +" + (names.size() - 1))
				.withStyle(ChatFormatting.GRAY));
			return List.of(line("configured", ChatFormatting.GOLD), summary,
				line("hold_shift", ChatFormatting.GRAY));
		}
		List<Component> lines = new ArrayList<>(names.size() + 1);
		for (Component name : names)
			lines.add(Component.literal("• ").withStyle(ChatFormatting.GRAY)
				.append(name.copy().withStyle(ChatFormatting.GOLD)));
		lines.add(line(panel ? "panel_hint" : "tool_hint", ChatFormatting.GRAY));
		return List.copyOf(lines);
	}

	private List<Component> resolvedNames(List<UUID> bindings) {
		List<Component> names = new ArrayList<>(bindings.size());
		for (UUID binding : bindings) {
			Optional<LogisticsNetworkDirectoryEntry> entry = entries.apply(binding);
			if (entry.isEmpty()) return List.of();
			names.add(LogisticsNetworkDirectory.displayName(entry.orElseThrow()));
		}
		return List.copyOf(names);
	}

	private static Component line(String suffix, ChatFormatting style) {
		return Component.translatable(ROOT + suffix).withStyle(style);
	}
}
