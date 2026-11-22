package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ComputerExecutionTicksTest {
    @Test void hostDefaultKeepsOriginalSignedWideningAndDoesNotMaskTheCycleToSixteenBits() {
        assertEquals(0x100010001L, ComputerExecutionTicks.seed(0, 0));
        assertEquals(0x200010001L, ComputerExecutionTicks.seed(65536, 0));
        assertEquals((1L << 32) + 1, ComputerExecutionTicks.seed(-1, 0));
        assertEquals((1L << 32) + ((1L + Integer.MIN_VALUE) << 16) + 1,
            ComputerExecutionTicks.seed(0x80000000L, 0));
        assertEquals(ComputerExecutionTicks.seed(0, 0), ComputerExecutionTicks.seed(1L << 32, 0),
            "An ordering seed is intentionally not a unique activity or recovery identity");
        assertEquals(0x100010001L + Integer.MAX_VALUE, ComputerExecutionTicks.seed(0, Integer.MAX_VALUE));
    }
}
