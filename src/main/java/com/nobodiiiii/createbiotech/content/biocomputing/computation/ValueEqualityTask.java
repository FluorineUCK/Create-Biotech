package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.INVALID_CHECKPOINT;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompiler.failure;
import java.util.Objects;
import java.util.function.LongConsumer;

/** Borrowed immutable values and the same budgeted comparison cursor used by DISTINCT. */
public final class ValueEqualityTask {
    private final DataValue left, right;
    private final TypedComparison comparison;
    private ValueEqualityTask(DataValue left, DataValue right, TypedComparison comparison) {
        this.left = Objects.requireNonNull(left); this.right = Objects.requireNonNull(right); this.comparison = comparison;
    }
    public static ValueEqualityTask start(DataValue left, DataValue right, String location, LongConsumer reserve) {
        Objects.requireNonNull(location);
        return new ValueEqualityTask(left, right, TypedComparison.start(left, right, reserve));
    }
    public boolean complete() { return comparison.complete(); }
    public void advance(LongConsumer reserve) { comparison.advance(reserve); }
    public static long checkpointUnits(DataValue.RecordValue progress) { return TypedComparison.checkpointUnits(progress); }
    public long liveUnits() { return comparison.liveUnits(); }
    public DataValue.BooleanValue result() { return new DataValue.BooleanValue(comparison.equal()); }
    public long checkpointStorageUnits() { return comparison.checkpointStorageUnits(); }
    public DataValue.RecordValue checkpoint() { return comparison.checkpoint(); }

    /** Generic immutable live cursor. No graph machine, owner or execution-checkpoint dependency. */
    public static final class Frozen {
        private final DataValue left, right;
        private final TypedComparison.Snapshot snapshot;
        private Frozen(ValueEqualityTask task) { left = task.left; right = task.right; snapshot = task.comparison.snapshot(); }
    }
    public Frozen freeze() { return new Frozen(this); }
    public static ValueEqualityTask resume(DataValue left, DataValue right, Frozen frozen) {
        if (frozen.left != left || frozen.right != right) throw failure(INVALID_CHECKPOINT, "equality", "Comparison sources changed");
        return new ValueEqualityTask(left, right, TypedComparison.copy(frozen.snapshot));
    }

    /** Private proof binds both borrowed sources and the immutable serialized progress by identity. */
    static final class Continuation {
        private final DataValue left, right;
        private final DataValue.RecordValue progress;
        private final TypedComparison.Snapshot comparison;
        private Continuation(ValueEqualityTask task, DataValue.RecordValue progress) {
            left = task.left; right = task.right; this.progress = progress; comparison = task.comparison.snapshot();
        }
    }
    MachineCheckpoint.ValueEqualityState checkpointState() {
        var progress = checkpoint(); return new MachineCheckpoint.ValueEqualityState(progress, new Continuation(this, progress));
    }
    static ValueEqualityTask restore(DataValue left, DataValue right, MachineCheckpoint.ValueEqualityState state, String location) {
        var saved = state.continuation();
        if (saved == null || saved.left != left || saved.right != right || saved.progress != state.progress()) {
            var validated = restore(left, right, state.progress(), location);
            state.rememberValidation(new Continuation(validated, state.progress())); return validated;
        }
        return new ValueEqualityTask(left, right, TypedComparison.copy(saved.comparison));
    }
    public static ValueEqualityTask restore(DataValue left, DataValue right, DataValue.RecordValue progress, String location) {
        try { return new ValueEqualityTask(left, right, TypedComparison.restore(left, right, progress)); }
        catch (ComputationException invalid) {
            if (invalid.code() == INVALID_CHECKPOINT) throw invalid;
            throw failure(INVALID_CHECKPOINT, location, "Invalid value equality continuation: " + invalid.getMessage());
        } catch (IllegalArgumentException | ClassCastException | ArithmeticException | IndexOutOfBoundsException invalid) {
            throw failure(INVALID_CHECKPOINT, location, "Invalid value equality continuation: " + invalid.getMessage());
        }
    }
}
