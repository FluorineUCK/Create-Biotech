package com.nobodiiiii.createbiotech.content.biocomputing.diagnostics;

import java.util.*;

/** Keep a wrapped-row anchor, not a stale pixel offset, when new events arrive or old ones expire. */
public final class RecentEventScroll {
    public record Anchor(long event, int part) {}
    private List<Anchor> rows = List.of();
    private int offset, visible = 1;
    private boolean following = true;
    public void update(List<Anchor> next, int visible) {
        var anchor = rows.isEmpty() ? null : rows.get(Math.min(offset, rows.size() - 1));
        rows = List.copyOf(next); this.visible = Math.max(1, visible);
        offset = following ? maximum() : Math.max(0, Math.min(maximum(), rows.indexOf(anchor)));
    }
    public void scroll(int delta) { offset = (int)Math.max(0, Math.min(maximum(), (long)offset + delta)); following = offset == maximum(); }
    public int offset() { return offset; }
    public boolean following() { return following; }
    public int maximum() { return Math.max(0, rows.size() - visible); }
}
