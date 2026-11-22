package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Bounded owner identities, not physical-pin arbitration or a message queue. Main is implicit slot 0.
 * Reusing a retired child slot advances its incarnation; only a new program generation clears it. */
public record FlowTaskSlots(List<Slot> entries) {
    public static final FlowTaskSlots EMPTY = new FlowTaskSlots(List.of());
    /** List index + 1 is the stable child slot. A retired slot contains no result or continuation. */
    public record Slot(UUID flow, long incarnation, boolean retired, boolean event, Optional<UUID> waiter) {
        public Slot(UUID flow, long incarnation, boolean retired) { this(flow, incarnation, retired, false, Optional.empty()); }
        public Slot {
            Objects.requireNonNull(flow); Objects.requireNonNull(waiter);
            if (incarnation < 0) throw new IllegalArgumentException("Negative task incarnation");
            if (event && retired || waiter.isPresent() && (!event || waiter.orElseThrow().equals(flow)))
                throw new IllegalArgumentException("Invalid event task linkage");
        }
        DataValue value() {
            var values = new LinkedHashMap<String, DataValue>(Map.of("flow", new DataValue.TextValue(flow.toString()),
                "incarnation", new DataValue.IntegerValue(incarnation), "retired", new DataValue.BooleanValue(retired)));
            if (event) values.put("event_waiter", waiter.<DataValue>map(id -> new DataValue.TextValue(id.toString())).orElse(DataValue.NullValue.INSTANCE));
            return new DataValue.RecordValue(values);
        }
    }
    public FlowTaskSlots {
        entries = List.copyOf(entries);
        var ids = new HashSet<UUID>();
        for (var slot : entries) if (!ids.add(slot.flow())) throw new IllegalArgumentException("Duplicate task slot identity");
    }
    /** Schema-15 tasks never acquired XBus pins, so their current order is a safe initial allocation. */
    public static FlowTaskSlots legacy(List<FlowRuntime.Checkpoint> contexts) {
        return new FlowTaskSlots(contexts.stream().map(s -> new Slot(s.flow(), 0, false)).toList());
    }
    boolean supersedes(XBusReceipt.Position p) {
        if (p.slot() <= 0 || p.slot() > entries.size()) return false;
        var slot = entries.get(p.slot() - 1);
        return slot.incarnation() > p.incarnation()
            || slot.incarnation() == p.incarnation() && slot.flow().equals(p.flow()) && slot.retired();
    }
    FlowTaskSlots admit(UUID flow) {
        return admit(flow, false, Optional.empty());
    }
    FlowTaskSlots admitEvent(UUID flow, Optional<UUID> waiter) { return admit(flow, true, waiter); }
    private FlowTaskSlots admit(UUID flow, boolean event, Optional<UUID> waiter) {
        var next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            var previous = next.get(i);
            if (!previous.retired()) continue;
            if (previous.incarnation() == Long.MAX_VALUE)
                throw FlowProgram.error(ComputationException.Code.ARITHMETIC_OVERFLOW, "tasks", "Task slot incarnation exhausted");
            next.set(i, new Slot(flow, previous.incarnation() + 1, false, event, waiter));
            return new FlowTaskSlots(next);
        }
        next.add(new Slot(flow, 0, false, event, waiter)); return new FlowTaskSlots(next);
    }
    FlowTaskSlots retire(UUID flow) {
        var next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            var slot = next.get(i);
            if (!slot.flow().equals(flow) || slot.retired()) continue;
            next.set(i, new Slot(flow, slot.incarnation(), true)); return new FlowTaskSlots(next);
        }
        throw new IllegalArgumentException("No active task slot to retire");
    }
    FlowTaskSlots detachStoppedWaiters(Set<UUID> stopped) {
        return new FlowTaskSlots(entries.stream().map(slot -> slot.waiter().filter(stopped::contains).isPresent()
            ? new Slot(slot.flow(), slot.incarnation(), false, true, Optional.empty()) : slot).toList());
    }
    void validate(List<FlowRuntime.Checkpoint> contexts) {
        var active = new HashSet<UUID>();
        entries.stream().filter(s -> !s.retired()).forEach(s -> active.add(s.flow()));
        if (active.size() != contexts.size() || !active.equals(new HashSet<>(contexts.stream().map(FlowRuntime.Checkpoint::flow).toList())))
            throw new IllegalArgumentException("Task slots differ from retained contexts");
    }
    public DataValue.ListValue value() { return new DataValue.ListValue(entries.stream().map(Slot::value).toList()); }
}
