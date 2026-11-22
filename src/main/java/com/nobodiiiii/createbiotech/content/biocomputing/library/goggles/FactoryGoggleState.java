package com.nobodiiiii.createbiotech.content.biocomputing.library.goggles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Current-schema bounded display-only BE packet; strings only, never executable Components. */
public record FactoryGoggleState(List<Row> rows) {
	public static final String ROOT = "NormalGoggles";
	/** Enough for every bounded Panel binding or all four native Gauge slots, not a catalog. */
	public static final int MAX_ROWS = 48;
	public static final int SYNC_INTERVAL_TICKS = 20;
	private static final int MAX_TEXT = 128;
	private static final Pattern UUID_TEXT = Pattern.compile(
			"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final Set<String> KEYS = Set.of("cluster", "role", "formation", "profession",
			"slots", "primary", "listeners", "state", "frequency", "priority", "cache", "index", "queries", "queued_pages",
			"face", "base", "agent", "alias", "tasks", "waits", "address", "queue", "run", "point",
			"dynamic", "age", "distance", "link", "unavailable");
	public static final FactoryGoggleState EMPTY = new FactoryGoggleState(List.of());

	public FactoryGoggleState {
		rows = List.copyOf(rows);
		if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("too many normal Goggles rows");
	}
	public record Row(String key, List<String> values) {
		public Row {
			if (!KEYS.contains(key)) throw new IllegalArgumentException("unknown row");
			values = List.copyOf(values);
			if (values.size() > 2 || values.stream().anyMatch(value -> value.length() > MAX_TEXT
					|| UUID_TEXT.matcher(value).find() || value.codePoints().anyMatch(Character::isISOControl)))
				throw new IllegalArgumentException("invalid normal Goggles value");
		}
	}
	public static String text(String value) {
		value = UUID_TEXT.matcher(Objects.requireNonNull(value))
				.replaceAll(match -> match.group().substring(0, 8));
		value = value.codePoints().filter(codePoint -> !Character.isISOControl(codePoint))
				.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
		return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT - 1) + "…" : value;
	}
	public CompoundTag save() {
		var tag = new CompoundTag();
		var list = new ListTag();
		for (var row : rows) {
			var child = new CompoundTag();
			child.putString("Key", row.key());
			var values = new ListTag();
			row.values().forEach(value -> values.add(StringTag.valueOf(value)));
			child.put("Values", values);
			list.add(child);
		}
		tag.put("Rows", list);
		return tag;
	}
	public static Optional<FactoryGoggleState> load(CompoundTag tag) {
		if (!tag.getAllKeys().equals(Set.of("Rows")) || !(tag.get("Rows") instanceof ListTag list)
				|| list.size() > MAX_ROWS || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
			return Optional.empty();
		var rows = new ArrayList<Row>();
		try {
			for (var raw : list) {
				var row = (CompoundTag) raw;
				if (!row.getAllKeys().equals(Set.of("Key", "Values")) || !row.contains("Key", Tag.TAG_STRING)
						|| !(row.get("Values") instanceof ListTag values) || values.size() > 2
						|| !values.isEmpty() && values.getElementType() != Tag.TAG_STRING) return Optional.empty();
				rows.add(new Row(row.getString("Key"), values.stream().map(Tag::getAsString).toList()));
			}
			return Optional.of(new FactoryGoggleState(rows));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}
	public FactoryGoggleState append(FactoryGoggleState other) {
		var next = new ArrayList<>(rows);
		next.addAll(other.rows);
		return new FactoryGoggleState(next);
	}
}
