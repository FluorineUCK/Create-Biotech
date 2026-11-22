package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FlowTaskFrontierTest {
    @Test void slotRetirementAndReuseDoNotRetireOtherSlotsOrAcceptAnOlderIncarnation() {
        UUID main = UUID.randomUUID(), first = UUID.randomUUID(), other = UUID.randomUUID(), later = UUID.randomUUID();
        var live = new FlowControl.Peer(7, Map.of(0, new FlowControl.Frontier(main, 0, -1, false),
            1, new FlowControl.Frontier(first, 0, 12, false), 2, new FlowControl.Frontier(other, 3, 21, false)));
        var retirement = new FlowControl.Peer(7, Map.of(1, new FlowControl.Frontier(first, 0, -1, true)));
        var retired = live.merge(retirement);
        assertEquals(retired, retirement.merge(live)); assertEquals(retired, retired.merge(live));
        assertEquals(Optional.of(RETIRED), retired.check(1, first, 0, 100, 7));
        assertTrue(retired.check(0, main, 0, 0, 7).isEmpty()); assertTrue(retired.check(2, other, 3, 22, 7).isEmpty());
        assertEquals(Optional.of(RETIRED), retired.check(2, other, 3, 21, 7));
        var replacement = new FlowControl.Peer(7, Map.of(1, new FlowControl.Frontier(later, 1, -1, false)));
        var current = retired.merge(replacement);
        assertEquals(current, replacement.merge(retired)); assertEquals(current, current.merge(retirement));
        assertEquals(3, current.slots().size()); assertEquals(Optional.of(RETIRED), current.check(1, first, 0, Long.MAX_VALUE, 7));
        assertTrue(current.check(1, later, 1, 0, 7).isEmpty());
        assertEquals(Optional.of(HISTORY_CONFLICT), current.check(1, first, 1, 0, 7));
        assertThrows(IllegalArgumentException.class, () -> current.merge(new FlowControl.Peer(7,
            Map.of(1, new FlowControl.Frontier(UUID.randomUUID(), 1, -1, false)))));
        var reset = new FlowControl.Peer(8, UUID.randomUUID(), -1);
        assertEquals(reset, current.merge(reset)); assertEquals(reset, reset.merge(current));
        assertEquals(Optional.of(RETIRED), reset.check(1, later, 1, Long.MAX_VALUE, 7));
    }
}
