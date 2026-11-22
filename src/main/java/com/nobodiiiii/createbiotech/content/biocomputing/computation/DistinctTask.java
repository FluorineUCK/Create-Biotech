package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.INVALID_CHECKPOINT;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;
import java.util.*;
import java.util.function.LongConsumer;

/** Stable typed distinct; source items are borrowed, output occurrences and indices arena-charged. */
public final class DistinctTask {
    // Package-local test observation only. No callback is retained by a continuation or archive.
    static final ThreadLocal<LongConsumer> validationObserver = new ThreadLocal<>();
    static void observeValidation(long visits) {
        var observer = validationObserver.get(); if (observer != null) observer.accept(visits);
    }
    private final DataValue.ListValue source;
    private final ArrayList<Integer> retained = new ArrayList<>();
    private final ArrayList<DataValue> output = new ArrayList<>();
    private int index, candidate;
    private boolean done;
    private TypedComparison comparison;
    private DistinctTask(DataValue.ListValue source, String location) { this.source = Objects.requireNonNull(source); Objects.requireNonNull(location); }
    public static DistinctTask start(DataValue.ListValue source, String location, LongConsumer reserve) {
        reserve.accept(2); return new DistinctTask(source, location);
    }
    public boolean complete() { return done; }
    public void advance(LongConsumer reserve) {
        if (done) return;
        if (index == source.values().size()) { done = true; return; }
        var item = source.values().get(index);
        if (comparison != null) {
            if (!comparison.complete()) comparison.advance(reserve);
            else {
                if (comparison.equal()) { index++; candidate = 0; }
                else candidate++;
                comparison = null;
            }
        } else if (candidate == retained.size()) {
            reserve.accept(Math.addExact(1, item.storageUnits()));
            retained.add(index); output.add(item); index++; candidate = 0;
        } else comparison = TypedComparison.start(item, source.values().get(retained.get(candidate)), reserve);
    }
    public long liveUnits() { long units = 2L + retained.size(); for (var item : output) units = Math.addExact(units, item.storageUnits()); return Math.addExact(units, comparison == null ? 0 : comparison.liveUnits()); }
    public DataValue.ListValue result() { if (!done) throw new IllegalStateException("Distinct is unfinished"); return new DataValue.ListValue(output); }
    public DataValue.RecordValue checkpoint() {
        return new DataValue.RecordValue(Map.of("index", number(index), "candidate", number(candidate), "done", new DataValue.BooleanValue(done),
            "retained", new DataValue.ListValue(retained.stream().map(i -> (DataValue) number(i)).toList()),
            "comparison", comparison == null ? DataValue.NullValue.INSTANCE : comparison.checkpoint()));
    }
    /** Non-serialized, identity-bound proof carrying only immutable owned controls. Constructor is private. */
    static final class Continuation {
        private final DataValue.ListValue source;
        private final DataValue.RecordValue progress;
        private final List<Integer> retained;
        private final List<DataValue> output;
        private final int index, candidate;
        private final boolean done;
        private final TypedComparison.Snapshot comparison;
        private Continuation(DistinctTask task, DataValue.RecordValue progress) {
            source = task.source; this.progress = progress;
            retained = List.copyOf(task.retained); output = List.copyOf(task.output);
            index = task.index; candidate = task.candidate; done = task.done;
            comparison = task.comparison == null ? null : task.comparison.snapshot();
        }
    }
    MachineCheckpoint.DistinctState checkpointState() {
        var progress = checkpoint(); return new MachineCheckpoint.DistinctState(progress, new Continuation(this, progress));
    }
    static DistinctTask restore(DataValue.ListValue source, MachineCheckpoint.DistinctState state, String location) {
        var saved = state.continuation();
        if (saved == null || saved.source != source || saved.progress != state.progress()) {
            var validated = restore(source, state.progress(), location);
            // Retain the successful proof even if a paused transactional publication is value-identical.
            state.rememberValidation(new Continuation(validated, state.progress()));
            return validated;
        }
        var task = new DistinctTask(source, location); task.index = saved.index; task.candidate = saved.candidate; task.done = saved.done;
        task.retained.addAll(saved.retained); task.output.addAll(saved.output);
        task.comparison = saved.comparison == null ? null : TypedComparison.copy(saved.comparison);
        return task;
    }
    public static DistinctTask restore(DataValue.ListValue source, DataValue.RecordValue state, String location) {
        try {
            observeValidation(1);
            require(state.fields().keySet().equals(Set.of("index", "candidate", "done", "retained", "comparison")));
            var task = new DistinctTask(source, location); task.index = read(state.field("index")); task.candidate = read(state.field("candidate"));
            task.done = ((DataValue.BooleanValue) state.field("done")).value();
            require(task.index >= 0 && task.index <= source.values().size());
            int last = -1;
            for (var value : ((DataValue.ListValue) state.field("retained")).values()) {
                int i = read(value); require(i > last && i < task.index); last = i;
                task.retained.add(i); task.output.add(source.values().get(i));
            }
            require(task.candidate >= 0 && task.candidate <= task.retained.size());
            require(task.index != source.values().size() || task.candidate == 0);
            require(!task.done || task.index == source.values().size());
            // Validate first-occurrence retention against the frozen prefix, bounded by source size.
            // Off-hot archive validation only; no dependence on a forged work count, and no live replay.
            int next = 0;
            for (int i = 0; i < task.index; i++) {
                boolean duplicate = false;
                for (int j = 0; j < next; j++) if (source.values().get(i).equals(source.values().get(task.retained.get(j)))) { duplicate = true; break; }
                if (!duplicate) { require(next < task.retained.size() && task.retained.get(next) == i); next++; }
            }
            require(next == task.retained.size());
            if (task.index < source.values().size()) for (int j = 0; j < task.candidate; j++)
                require(!source.values().get(task.index).equals(source.values().get(task.retained.get(j))));
            var comparison = state.field("comparison");
            if (comparison != DataValue.NullValue.INSTANCE) {
                require(!task.done && task.index < source.values().size() && task.candidate < task.retained.size());
                task.comparison = TypedComparison.restore(source.values().get(task.index), source.values().get(task.retained.get(task.candidate)), (DataValue.RecordValue) comparison);
            }
            return task;
        } catch (ComputationException invalid) {
            if (invalid.code() == INVALID_CHECKPOINT) throw invalid;
            throw failure(INVALID_CHECKPOINT, location, "Invalid distinct continuation: " + invalid.getMessage());
        } catch (IllegalArgumentException | ClassCastException | ArithmeticException | IndexOutOfBoundsException invalid) {
            throw failure(INVALID_CHECKPOINT, location, "Invalid distinct continuation: " + invalid.getMessage());
        }
    }
    private static DataValue.IntegerValue number(int n) { return new DataValue.IntegerValue(n); }
    private static int read(DataValue v) { return Math.toIntExact(((DataValue.IntegerValue) v).value()); }
    private static void require(boolean valid) { if (!valid) throw failure(INVALID_CHECKPOINT, "distinct", "Invalid distinct state"); }
}
