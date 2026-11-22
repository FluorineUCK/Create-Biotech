package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** One Computer's FIFO. Questions stay in their admitted task registers, not in a second text buffer. */
public record FlowPrompts(DataValue.TextValue answer, List<UUID> pending) {
    public static final int MAX_CHARS = 1024;
    public static final FlowPrompts EMPTY = new FlowPrompts(new DataValue.TextValue(""), List.of());
    public FlowPrompts {
        requireText(answer);
        pending = List.copyOf(pending);
        if (new HashSet<>(pending).size() != pending.size()) throw new IllegalArgumentException("Duplicate prompt owner");
    }
    public static void requireText(DataValue.TextValue text) {
        if (text.value().length() > MAX_CHARS) throw new IllegalArgumentException("Terminal question or answer is too long");
    }
    public FlowPrompts enqueue(UUID flow) {
        var next = new ArrayList<>(pending); next.add(flow);
        return new FlowPrompts(answer, next);
    }
    public FlowPrompts reply(DataValue.TextValue text) {
        return new FlowPrompts(text, pending.subList(1, pending.size()));
    }
    FlowPrompts retain(Set<UUID> waiting) {
        var next = pending.stream().filter(waiting::contains).toList();
        return next.equals(pending) ? this : new FlowPrompts(answer, next);
    }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("answer", answer, "pending", new DataValue.ListValue(pending.stream()
            .<DataValue>map(id -> new DataValue.TextValue(id.toString())).toList())));
    }
    public static String token(FlowRuntime.Checkpoint checkpoint) { return checkpoint.flow() + ":" + checkpoint.work(); }
}
