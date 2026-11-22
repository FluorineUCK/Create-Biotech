package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NativeNbtComparisonTest {
    @Test void comparisonsChargeEachArrayElementAndResumeAtEveryCursor() {
        for (boolean differs : List.of(false, true)) {
            var left = new DataValue.NbtValue(11, new DataValue.ListValue(java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> (DataValue)new DataValue.IntegerValue(i)).toList()));
            var right = new DataValue.NbtValue(11, new DataValue.ListValue(java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> (DataValue)new DataValue.IntegerValue(differs && i == 63 ? -1 : i)).toList()));
            var compare = TypedComparison.start(left, right, units -> {}); int work = 0;
            while (!compare.complete()) {
                compare.advance(units -> assertTrue(units > 0)); work++;
                compare = TypedComparison.restore(left, right, compare.checkpoint());
                assertTrue(work < 500);
            }
            assertTrue(work > 64, "A native array cannot bypass budgeted equality");
            assertEquals(!differs, compare.equal());
            assertEquals(!differs, left.equals(right));
            if (!differs) assertEquals(left.hashCode(), right.hashCode());
        }
    }
}
