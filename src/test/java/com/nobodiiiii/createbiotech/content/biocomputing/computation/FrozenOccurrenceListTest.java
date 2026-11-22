package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FrozenOccurrenceListTest {
    @Test void everyAppendCopiesExactly32NodesAndNeverReplaysPriorOccurrences() throws Exception {
        var root = FrozenOccurrenceList.class.getDeclaredField("root"); root.setAccessible(true);
        var vector = FrozenOccurrenceList.EMPTY;
        for (int i = 0; i < 4096; i++) {
            var previous = vector;
            vector = vector.append(new DataValue.IntegerValue(i));
            assertEquals(32, changed(root.get(previous), root.get(vector)));
            assertEquals(i, previous.size());
            if (i > 0) assertEquals(new DataValue.IntegerValue(i - 1), previous.get(i - 1));
        }
        assertEquals(4096, vector.stream().count());
        assertThrows(UnsupportedOperationException.class, () -> FrozenOccurrenceList.EMPTY.add(new DataValue.IntegerValue(1)));
    }

    private static int changed(Object before, Object after) throws Exception {
        if (before == after) return 0;
        var left = after.getClass().getDeclaredMethod("left"); left.setAccessible(true);
        var right = after.getClass().getDeclaredMethod("right"); right.setAccessible(true);
        return 1 + changed(before == null ? null : left.invoke(before), left.invoke(after))
            + changed(before == null ? null : right.invoke(before), right.invoke(after));
    }

    @Test void checkedLogicalOverflowDoesNotMutateAnAcceptedImmutablePrefix() {
        DataValue value = new DataValue.IntegerValue(1);
        for (int i = 0; i < 61; i++) value = new DataValue.ListValue(List.of(value, value));
        var first = DataValue.Construction.list().append(value);
        var full = first.append(value);
        assertEquals(Long.MAX_VALUE, full.storageUnits());
        var child = value;
        assertThrows(ComputationException.class, () -> full.append(child));
        assertEquals(1L << 62, first.storageUnits()); assertEquals(1, first.size());
        assertEquals(2, ((DataValue.ListValue)full.finish()).values().size());
    }
}
