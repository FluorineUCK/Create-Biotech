package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable execution data, in bottom-to-top stack order; no executable or world references. */
public record MachineCheckpoint(DataValue.RecordValue memory, ComputationMachine.Status status,
	boolean paused, long workUnits, long arenaUnits, Optional<DataValue.RecordValue> output,
	Optional<ComputationMachine.Diagnostic> diagnostic, List<FrameState> frames) {
	public MachineCheckpoint {
		Objects.requireNonNull(memory);
		Objects.requireNonNull(status);
		Objects.requireNonNull(output);
		Objects.requireNonNull(diagnostic);
		frames = List.copyOf(frames);
	}

	public sealed interface FrameState permits GraphState, LoopState, FoldState, SortState, DistinctState, ValueEqualityState, TextState, ListState, CommitState {}

	/** The pending parent owns frozen elements; only the completed prefix length is persisted. */
	public record ListState(int index) implements FrameState {}

    /** Serialized progress is public; trusted controls can only be created by a validated task. */
    public static final class ValueEqualityState implements FrameState {
        private final DataValue.RecordValue progress;
        private volatile ValueEqualityTask.Continuation continuation;
        public ValueEqualityState(DataValue.RecordValue progress) { this(progress, null); }
        ValueEqualityState(DataValue.RecordValue progress, ValueEqualityTask.Continuation continuation) {
            this.progress = Objects.requireNonNull(progress); this.continuation = continuation;
        }
        public DataValue.RecordValue progress() { return progress; }
        ValueEqualityTask.Continuation continuation() { return continuation; }
        void rememberValidation(ValueEqualityTask.Continuation validated) { continuation = validated; }
        @Override public boolean equals(Object other) { return other instanceof ValueEqualityState state && progress.equals(state.progress); }
        @Override public int hashCode() { return progress.hashCode(); }
        @Override public String toString() { return "ValueEqualityState[progress=" + progress + "]"; }
    }
    /** Value-only public construction is untrusted. Owned controls are an opaque, non-serialized optimization. */
    public static final class DistinctState implements FrameState {
        private final DataValue.RecordValue progress;
        // Logical checkpoint data stays immutable. This per-object opaque proof cache is not serialized/equalled.
        private volatile DistinctTask.Continuation continuation;
        public DistinctState(DataValue.RecordValue progress) { this(progress, null); }
        DistinctState(DataValue.RecordValue progress, DistinctTask.Continuation continuation) {
            this.progress = Objects.requireNonNull(progress); this.continuation = continuation;
        }
        public DataValue.RecordValue progress() { return progress; }
        DistinctTask.Continuation continuation() { return continuation; }
        void rememberValidation(DistinctTask.Continuation validated) { continuation = validated; }
        @Override public boolean equals(Object other) { return other instanceof DistinctState state && progress.equals(state.progress); }
        @Override public int hashCode() { return progress.hashCode(); }
        @Override public String toString() { return "DistinctState[progress=" + progress + "]"; }
    }

	/** Key extraction and merge progress are independent; source/captures are frozen in the parent. */
	public record SortState(int index, DataValue.ListValue keys, Optional<DataValue.RecordValue> returned,
		Optional<DataValue.RecordValue> progress) implements FrameState {
		public SortState {
			Objects.requireNonNull(keys);
			Objects.requireNonNull(returned);
			Objects.requireNonNull(progress);
		}
	}

	/** An empty module identifies the event root; every child uses its exact installed revision. */
	public record GraphState(Optional<ModuleDefinition.Key> module, int cursor,
		DataValue.RecordValue inputs, DataValue.RecordValue values) implements FrameState {
		public GraphState {
			Objects.requireNonNull(module);
			Objects.requireNonNull(inputs);
			Objects.requireNonNull(values);
		}
	}

	/** Operation, source and captures are derived from the parent graph's pending iteration. */
	public record LoopState(int index, DataValue.ListValue results,
		Optional<DataValue.RecordValue> returned) implements FrameState {
		public LoopState {
			Objects.requireNonNull(results);
			Objects.requireNonNull(returned);
		}
	}

	/** The frozen source, initial value and captures come from the pending parent Fold. */
	public record FoldState(int index, DataValue accumulator,
		Optional<DataValue.RecordValue> returned) implements FrameState {
		public FoldState {
			Objects.requireNonNull(accumulator);
			Objects.requireNonNull(returned);
		}
	}

	/** The pending parent Text owns the operation and frozen arguments. */
    public record TextState(DataValue.RecordValue progress) implements FrameState {
        public TextState { Objects.requireNonNull(progress); }
    }

	/** Prepared writes are derived from the output record and the unchanged old memory. */
	public record CommitState(int cursor, DataValue.RecordValue result) implements FrameState {
		public CommitState { Objects.requireNonNull(result); }
	}
}
