package com.nobodiiiii.createbiotech.content.biocomputing.application.production.create;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class DeliveryLedgerTest {
    private static DeliveryLedger.Fragment<String> fragment(int index, boolean last, Optional<Map<String,Long>> context, long count) {
        return new DeliveryLedger.Fragment<>(7, 0, index, true, last, context, Map.of("stone", count));
    }
    @Test void outOfOrderCompletesAndReplaysExactly() {
        var ledger = new DeliveryLedger<>(Map.of("stone", 3L), Map.of(7, Set.of(0)), 8);
        var finalPart = fragment(1, true, Optional.of(Map.of("stone", 3L)), 2);
        assertEquals(DeliveryLedger.Outcome.ACCEPTED, ledger.accept(finalPart));
        assertFalse(ledger.complete());
        assertEquals(DeliveryLedger.Outcome.ACCEPTED, ledger.accept(fragment(0, false, Optional.empty(), 1)));
        assertTrue(ledger.complete());
        assertEquals(DeliveryLedger.Outcome.DUPLICATE, ledger.accept(finalPart));
        var restored = new DeliveryLedger<>(Map.of("stone", 3L), Map.of(7, Set.of(0)), 8);
        ledger.fragments().forEach(f -> assertEquals(DeliveryLedger.Outcome.ACCEPTED, restored.accept(f)));
        assertEquals(ledger.fragments(), restored.fragments());
        assertEquals(ledger.accepted(), restored.accepted());
        assertTrue(restored.complete());
    }
    @Test void rejectedEvidenceIsAtomicAndTerminalOrderIndependent() {
        var ledger = new DeliveryLedger<>(Map.of("stone", 10L), Map.of(7, Set.of(0)), 4);
        assertEquals(DeliveryLedger.Outcome.ACCEPTED, ledger.accept(fragment(2, false, Optional.empty(), 1)));
        var before = ledger.fragments(); var totals = ledger.accepted();
        var rejected = List.of(fragment(1, true, Optional.empty(), 1), fragment(2, false, Optional.empty(), 2),
            fragment(3, false, Optional.of(Map.of("stone", 1L)), 1), fragment(3, false, Optional.empty(), 10));
        for (var f : rejected) {
            assertEquals(DeliveryLedger.Outcome.CONFLICT, ledger.accept(f));
            assertEquals(before, ledger.fragments()); assertEquals(totals, ledger.accepted());
        }
        assertEquals(DeliveryLedger.Outcome.FOREIGN, ledger.accept(new DeliveryLedger.Fragment<>(8, 0, 0, true, true, Optional.empty(), Map.of("stone", 1L))));
        assertEquals(DeliveryLedger.Outcome.FOREIGN, ledger.accept(new DeliveryLedger.Fragment<>(7, 1, 0, true, true, Optional.empty(), Map.of("stone", 1L))));
        assertEquals(DeliveryLedger.Outcome.CONFLICT, ledger.accept(new DeliveryLedger.Fragment<>(7, 0, 0, false, false, Optional.empty(), Map.of("stone", 1L))));
        assertEquals(DeliveryLedger.Outcome.CAPACITY, ledger.accept(fragment(Integer.MAX_VALUE, true, Optional.empty(), 1)));
        assertEquals(before, ledger.fragments()); assertEquals(totals, ledger.accepted());
    }
    @Test void missingContextAndGapsNeverCompleteAndAfterFinalIsRejected() {
        var missing = new DeliveryLedger<>(Map.of("stone", 1L), Map.of(7, Set.of(0)), 4);
        missing.accept(fragment(0, true, Optional.empty(), 1)); assertFalse(missing.complete());
        var gap = new DeliveryLedger<>(Map.of("stone", 1L), Map.of(7, Set.of(0)), 4);
        gap.accept(fragment(1, true, Optional.of(Map.of("stone", 1L)), 1)); assertFalse(gap.complete());
        assertEquals(DeliveryLedger.Outcome.CONFLICT, gap.accept(fragment(2, false, Optional.empty(), 1)));
    }
    @Test void overflowAndCapacityDoNotMutate() {
        var overflow = new DeliveryLedger<>(Map.of("stone", Long.MAX_VALUE), Map.of(7, Set.of(0)), 4);
        overflow.accept(fragment(0, false, Optional.empty(), Long.MAX_VALUE));
        assertEquals(DeliveryLedger.Outcome.CONFLICT, overflow.accept(fragment(1, true, Optional.empty(), 1)));
        assertEquals(Map.of("stone", Long.MAX_VALUE), overflow.accepted());
        var capacity = new DeliveryLedger<>(Map.of("stone", 3L), Map.of(7, Set.of(0, 1)), 1);
        var first = new DeliveryLedger.Fragment<>(7, 0, 0, false, true, Optional.empty(), Map.of("stone", 1L));
        assertEquals(DeliveryLedger.Outcome.ACCEPTED, capacity.accept(first));
        assertEquals(DeliveryLedger.Outcome.CAPACITY, capacity.accept(new DeliveryLedger.Fragment<>(7, 1, 0, true, true, Optional.empty(), Map.of("stone", 1L))));
        assertEquals(List.of(first), capacity.fragments());
        assertEquals(DeliveryLedger.Outcome.DUPLICATE, capacity.accept(first));
    }
    @Test void validatesAndCopiesInputsAndExposesImmutableSnapshots() {
        var expected = new HashMap<>(Map.of("stone", 1L)); var links = new HashSet<>(Set.of(0));
        var orders = new HashMap<Integer, Set<Integer>>(Map.of(7, links));
        var ledger = new DeliveryLedger<>(expected, orders, Integer.MAX_VALUE);
        expected.clear(); links.clear(); orders.clear();
        var items = new HashMap<>(Map.of("stone", 1L)); var context = new HashMap<>(items);
        var f = new DeliveryLedger.Fragment<>(7, 0, 0, true, true, Optional.of(context), items);
        items.clear(); context.clear();
        var old = ledger.accepted(); assertEquals(DeliveryLedger.Outcome.ACCEPTED, ledger.accept(f));
        assertTrue(old.isEmpty()); assertTrue(ledger.complete());
        assertThrows(UnsupportedOperationException.class, () -> ledger.fragments().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.accepted().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> f.context().orElseThrow().clear());
        assertThrows(IllegalArgumentException.class, () -> new DeliveryLedger<>(Map.of("stone", 0L), Map.of(7, Set.of(0)), 2));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryLedger<>(Map.of("stone", 1L), Map.of(), 2));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryLedger<>(Map.of("stone", 1L), Map.of(7, Set.of(0, Integer.MAX_VALUE)), 2));
        assertThrows(IllegalArgumentException.class, () -> fragment(-1, true, Optional.empty(), 1));
        assertThrows(IllegalArgumentException.class, () -> fragment(0, true, Optional.empty(), -1));
    }
}


