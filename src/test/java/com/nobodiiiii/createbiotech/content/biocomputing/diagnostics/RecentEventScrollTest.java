package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecentEventScrollTest {
    @Test void newEventsFollowTheTailUnlessThePlayerScrolledUpAndEvictionKeepsTheVisibleAnchor() {
        var scroll = new RecentEventScroll();
        var first = rows(1, 4); scroll.update(first, 2); assertEquals(2, scroll.offset());
        scroll.scroll(-1); assertEquals(1, scroll.offset()); assertFalse(scroll.following());
        scroll.update(rows(1, 5), 2); assertEquals(1, scroll.offset());
        scroll.update(rows(2, 5), 2); assertEquals(0, scroll.offset()); assertFalse(scroll.following());
        scroll.scroll(100); assertTrue(scroll.following());
        scroll.update(rows(3, 6), 2); assertEquals(2, scroll.offset());
    }
    private static List<RecentEventScroll.Anchor> rows(int start, int end) {
        var rows = new ArrayList<RecentEventScroll.Anchor>();
        for (int i = start; i <= end; i++) rows.add(new RecentEventScroll.Anchor(i, 0)); return rows;
    }
}