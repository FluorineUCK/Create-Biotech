package com.nobodiiiii.createbiotech.content.factorycluster.diagnostics;

import com.nobodiiiii.createbiotech.content.biocomputing.library.goggles.FactoryGoggleState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Client-safe localized composition. No world reads and no gameplay actions. */
public final class FactoryGoggleProjectionFormatter {
	public static final String PREFIX = "create_biotech.factory_cluster.goggles.";
	private FactoryGoggleProjectionFormatter() {}

	public static FactoryGoggleState project(FactoryGoggleProjection projection) {
		var rows = new ArrayList<FactoryGoggleState.Row>();
		switch (projection) {
			case FactoryGoggleProjection.Computer p -> {
				add(rows, "cluster", literal(p.cluster()));
				add(rows, "role", token(p.role()));
				add(rows, "formation", token(p.formation()));
				add(rows, "profession", token(p.profession()), p.level());
				add(rows, "slots", p.usedSlots() + "/" + p.totalSlots());
				add(rows, "primary", literal(p.primary()));
				for (var frequency : p.listeners()) add(rows, "listeners", literal(frequency));
				if (p.listeners().isEmpty()) add(rows, "listeners", token("NONE"));
				add(rows, "state", token(p.state()));
			}
			case FactoryGoggleProjection.Core p -> {
				add(rows, "formation", token(p.formation()));
				add(rows, "frequency", literal(p.frequency()));
				add(rows, "priority", p.priority());
				add(rows, "cache", p.validCache());
				add(rows, "index", p.indexedPages() + "/" + p.pages());
				add(rows, "queued_pages", p.queuedPages());
			}
			case FactoryGoggleProjection.Panel p -> {
				add(rows, "formation", token(p.cluster().isEmpty() ? "NOT_PUBLISHED" : "FORMED"));
				add(rows, "cluster", literal(p.cluster()));
				add(rows, "face", token(p.face()));
				add(rows, "base", token(p.base()));
				add(rows, "agent", token(p.agentState()));
				for (var alias : p.aliases()) add(rows, "alias", alias);
				if (p.aliases().isEmpty()) add(rows, "alias", token("NONE"));
				add(rows, "tasks", p.tasks());
				add(rows, "waits", p.waits());
			}
			case FactoryGoggleProjection.Endpoint p -> {
				add(rows, "role", token(p.role()));
				add(rows, "address", literal(p.address()));
				add(rows, "alias", p.alias());
				add(rows, "priority", p.priority() == null ? token("NOT_APPLICABLE") : p.priority());
				add(rows, "queue", p.queue() < 0
						? token(p.run().equals("NOT_APPLICABLE") ? "NOT_APPLICABLE" : "UNAVAILABLE") : p.queue());
				add(rows, "run", token(p.run()));
			}
			case FactoryGoggleProjection.Radio p -> {
				add(rows, "point", p.projectedPoint());
				add(rows, "dynamic", token(p.dynamic() ? "ON" : "OFF"));
				add(rows, "age", p.frameAge());
				add(rows, "distance", p.distance());
				add(rows, "link", token(p.linkType()));
			}
		}
		return new FactoryGoggleState(rows);
	}

	public static List<Component> normal(FactoryGoggleProjection projection) {
		return normal(project(projection));
	}
	public static List<Component> normal(FactoryGoggleState state) {
		return state.rows().stream().map(row -> (Component) Component.literal("  ")
				.append(Component.translatable(PREFIX + row.key(), row.values().stream()
						.map(FactoryGoggleProjectionFormatter::value).toArray()))
				.withStyle(ChatFormatting.GRAY)).toList();
	}
	private static Component value(String value) {
		if (value.startsWith("=")) return Component.literal(value.length() == 1 ? "—" : value.substring(1));
		if (value.startsWith("@network:")) return Component.translatable(
				"create_biotech.factory_cluster.logistics.default_name", value.substring(9));
		return value.startsWith("@") ? Component.translatable(PREFIX + "value." + value.substring(1))
				: Component.literal(value.isEmpty() ? "—" : value);
	}
	public static String token(String value) { return "@" + value; }
	private static String literal(String value) { return "=" + value; }
	private static void add(List<FactoryGoggleState.Row> rows, String key, Object... values) {
		rows.add(new FactoryGoggleState.Row(key, Arrays.stream(values).map(Object::toString)
				.map(FactoryGoggleState::text).toList()));
	}
}
