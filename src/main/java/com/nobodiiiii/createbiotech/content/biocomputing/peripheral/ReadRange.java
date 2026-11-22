package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Explicit absolute component indices, not a persistent cursor or snapshot lease. */
public record ReadRange(int offset, int count) {
    private static final Set<String> PAGE_FIELDS = Set.of("offset", "count", "total", "next", "items");

    public ReadRange {
        if (offset < 0 || count <= 0) throw new IllegalArgumentException("invalid_range");
        Math.addExact(offset, count);
    }
    public boolean fits(ReadLimits limits) { return count <= limits.maxEntries(); }

    /** Provider result, validated by the registry before exposing its items and metadata. */
    public DataValue.RecordValue page(int total, DataValue.ListValue items) {
        return new DataValue.RecordValue(Map.of("offset", integer(offset), "count", integer(items.values().size()),
            "total", integer(total), "next", integer(Math.addExact(offset, items.values().size())), "items", items));
    }
    /** Exact requested extent: budget pressure may never silently shrink a page. */
    public boolean accepts(DataValue.RecordValue page) {
        if (!page.fields().keySet().equals(PAGE_FIELDS) || !(page.field("items") instanceof DataValue.ListValue items)) return false;
        for (String key : List.of("offset", "count", "total", "next"))
            if (!(page.field(key) instanceof DataValue.IntegerValue n) || n.value() < 0 || n.value() > Integer.MAX_VALUE) return false;
        long total = ((DataValue.IntegerValue)page.field("total")).value();
        return total >= offset && page.field("offset").equals(integer(offset))
            && page.field("count").equals(integer(items.values().size()))
            && items.values().size() == Math.min(count, total - offset)
            && page.field("next").equals(integer((long)offset + items.values().size()));
    }
    public static DataValue.RecordValue metadata(DataValue.RecordValue page) {
        var fields = new LinkedHashMap<>(page.fields());
        fields.remove("items");
        return new DataValue.RecordValue(fields);
    }
    private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
}
