package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DistinctIndexedValueTest {
    @Test void indexedNamedRepresentationIsImmutableOrderedAndKeepsEqualityHashAndLogicalUnits() {
        var source = new LinkedHashMap<String, DataValue>(); source.put("first", new DataValue.IntegerValue(1)); source.put("", new DataValue.TextValue("two"));
        var record = new DataValue.RecordValue(source); var map = new DataValue.MapValue(source);
        long expected = 1; for (var entry : source.entrySet()) expected += 1L + entry.getKey().length() + entry.getValue().storageUnits();
        assertEquals(expected, record.storageUnits()); assertEquals(expected, map.storageUnits());
        for (var entries : List.of(record.indexedFields(), map.indexedEntries())) {
            assertEquals(List.of("first", ""), entries.stream().map(Map.Entry::getKey).toList());
            assertThrows(UnsupportedOperationException.class, () -> entries.add(Map.entry("new", DataValue.NullValue.INSTANCE)));
            assertThrows(UnsupportedOperationException.class, () -> entries.set(0, Map.entry("new", DataValue.NullValue.INSTANCE)));
            assertThrows(UnsupportedOperationException.class, () -> entries.getFirst().setValue(DataValue.NullValue.INSTANCE));
        }
        source.clear(); assertEquals(2, record.fields().size()); assertEquals(2, map.entries().size());
        assertEquals(new DataValue.IntegerValue(1), record.field("first")); assertEquals(new DataValue.IntegerValue(1), map.field("first"));
        var reordered = new LinkedHashMap<String, DataValue>(); reordered.put("", new DataValue.TextValue("two")); reordered.put("first", new DataValue.IntegerValue(1));
        assertEquals(record, new DataValue.RecordValue(reordered)); assertEquals(record.hashCode(), new DataValue.RecordValue(reordered).hashCode());
        assertEquals(map, new DataValue.MapValue(reordered)); assertEquals(map.hashCode(), new DataValue.MapValue(reordered).hashCode()); assertNotEquals(record, map);
    }
}
