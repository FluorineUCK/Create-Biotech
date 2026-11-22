package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.*;

/** Physical wait observations, never queued values or a FIFO delivery promise. One per live context. */
public record FlowPinClaims(List<Claim> entries) {
    public static final FlowPinClaims EMPTY = new FlowPinClaims(List.of());
    public record Claim(UUID flow, String pin, long operation) {
        public Claim { Objects.requireNonNull(flow); FlowProgram.name(pin); if (operation < 0) throw new IllegalArgumentException("Negative operation"); }
        public DataValue value() { return new DataValue.RecordValue(Map.of("flow", new DataValue.TextValue(flow.toString()),
            "pin", new DataValue.TextValue(pin), "operation", new DataValue.IntegerValue(operation))); }
    }
    public FlowPinClaims {
        entries = List.copyOf(entries); var ids = new HashSet<UUID>();
        for (var request : entries) if (!ids.add(request.flow())) throw new IllegalArgumentException("Task requests two physical pins");
    }
    public static boolean waiting(FlowRuntime.Checkpoint state) {
        return state.status() == FlowRuntime.Status.WAITING_READ || state.status() == FlowRuntime.Status.WAITING_WRITE
            || state.status() == FlowRuntime.Status.WAITING_READABLE;
    }
    /** Retain first-occupant identity across pause/reload; observing a later wait does not schedule a send. */
    public FlowPinClaims reconcile(FlowProgram program, FlowRuntime.Checkpoint main, FlowTasks tasks) {
        var desired = new LinkedHashMap<UUID, Claim>();
        var contexts = new ArrayList<>(tasks.contexts()); contexts.addFirst(main);
        for (var state : contexts) if (waiting(state)) desired.put(state.flow(), new Claim(state.flow(),
            FlowXBus.pin(program,state), state.operation()));
        var next = new ArrayList<Claim>();
        for (var old : entries) if (old.equals(desired.get(old.flow()))) { next.add(old); desired.remove(old.flow()); }
        next.addAll(desired.values()); return new FlowPinClaims(next);
    }
    void validate(FlowProgram program, FlowRuntime.Checkpoint main, FlowTasks tasks) {
        if (!reconcile(program, main, tasks).equals(this)) throw new IllegalArgumentException("Pin requests differ from live waits");
    }
    /** Group completion needs a shared receipt; the legacy pair path must not promote later waits. */
    public boolean shared(String pin) { return entries.stream().filter(c -> c.pin().equals(pin)).limit(2).count() > 1; }
    /** First occupancy only. This is not authority to consume a single member of a shared wait. */
    public Optional<UUID> owner(String pin) { return entries.stream().filter(c -> c.pin().equals(pin)).map(Claim::flow).findFirst(); }
    public DataValue.ListValue value() { return new DataValue.ListValue(entries.stream().map(Claim::value).toList()); }
    public long metadataUnits() { return entries.isEmpty() ? 0 : value().storageUnits(); }
}
