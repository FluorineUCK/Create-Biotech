package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterCalls;
import java.util.*;

/** Program-selected variable names for a single task activation, never copies of private values. */
public record FlowPrivateMonitors(Set<Watch> watches) {
    public static final FlowPrivateMonitors EMPTY = new FlowPrivateMonitors(Set.of());

    public record Watch(UUID task, long entered, String name) {
        public Watch {
            Objects.requireNonNull(task);
            if (entered < 0 || name.isBlank()) throw new IllegalArgumentException("Invalid private monitor");
        }
    }

    public FlowPrivateMonitors { watches = Set.copyOf(watches); }

    private static List<RegisterCalls.Frame> frames(FlowRuntime.Checkpoint state) {
        return state.assembly().flatMap(registers -> registers.calls()).map(RegisterCalls::frames).orElse(List.of());
    }
    private static long activation(FlowRuntime.Checkpoint state) {
        var frames = frames(state);
        return frames.isEmpty() ? 0 : frames.getLast().enteredWork();
    }
    private static boolean usable(FlowRuntime.Checkpoint state) {
        return state.assembly().isPresent() && !state.cancelled() &&
            (state.entry().isEmpty() || state.status() != FlowRuntime.Status.COMPLETE);
    }
    private static boolean owns(FlowRuntime.Checkpoint state, Watch watch) {
        return usable(state) && watch.task().equals(state.flow()) &&
            (watch.entered() == 0 || frames(state).stream().anyMatch(frame -> frame.enteredWork() == watch.entered()));
    }

    public Set<String> visible(FlowRuntime.Checkpoint state) {
        if (!usable(state) || watches.isEmpty()) return Set.of();
        long entered = activation(state);
        var names = new TreeSet<String>();
        for (var watch : watches)
            if (watch.task().equals(state.flow()) && watch.entered() == entered) names.add(watch.name());
        return Set.copyOf(names);
    }

    FlowPrivateMonitors change(FlowRuntime.Checkpoint state, String name, boolean visible) {
        var watch = new Watch(state.flow(), activation(state), name);
        var next = new HashSet<>(watches);
        boolean changed = visible ? next.add(watch) : next.remove(watch);
        return changed ? new FlowPrivateMonitors(next) : this;
    }

    FlowPrivateMonitors retain(FlowRuntime.Checkpoint main, FlowTasks tasks) {
        if (watches.isEmpty()) return this;
        var next = new HashSet<>(watches);
        next.removeIf(watch -> !owns(main, watch) && tasks.contexts().stream().noneMatch(state -> owns(state, watch)));
        return next.size() == watches.size() ? this : new FlowPrivateMonitors(next);
    }

    void validate(FlowProgram program, List<FlowRuntime.Checkpoint> contexts) {
        for (var watch : watches)
            if (!program.canShowPrivateMonitor(watch.name()) || contexts.stream().noneMatch(state -> owns(state, watch)))
                throw new IllegalArgumentException("Unavailable private monitor in saved state");
    }

    public DataValue.ListValue value() {
        return new DataValue.ListValue(watches.stream()
            .sorted(Comparator.comparing(Watch::task).thenComparingLong(Watch::entered).thenComparing(Watch::name))
            .<DataValue>map(watch -> new DataValue.RecordValue(Map.of("task", new DataValue.TextValue(watch.task().toString()),
                "entered", new DataValue.IntegerValue(watch.entered()), "name", new DataValue.TextValue(watch.name())))).toList());
    }

    public static FlowPrivateMonitors decode(DataValue value) {
        if (!(value instanceof DataValue.ListValue list)) throw new IllegalArgumentException("Invalid private monitor list");
        var result = new HashSet<Watch>();
        for (var item : list.values()) {
            if (!(item instanceof DataValue.RecordValue fields) || !fields.fields().keySet().equals(Set.of("task", "entered", "name"))
                || !(fields.field("task") instanceof DataValue.TextValue task) || !(fields.field("entered") instanceof DataValue.IntegerValue entered)
                || !(fields.field("name") instanceof DataValue.TextValue name) ||
                !result.add(new Watch(UUID.fromString(task.value()), entered.value(), name.value())))
                throw new IllegalArgumentException("Invalid or duplicate private monitor");
        }
        return new FlowPrivateMonitors(result);
    }
}
