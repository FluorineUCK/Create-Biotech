package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;

/** Captured counts belong to one continuation, not to mutable player locals. Outer frame first. */
public record FlowRepeatState(List<Frame> frames) {
    public static final FlowRepeatState EMPTY = new FlowRepeatState(List.of());
    public record Frame(int header, long initial, long remaining) {}
    public FlowRepeatState { frames = List.copyOf(frames); }
    public DataValue.ListValue value() {
        return new DataValue.ListValue(frames.stream().<DataValue>map(frame -> new DataValue.RecordValue(Map.of(
            "header", new DataValue.IntegerValue(frame.header()), "initial", new DataValue.IntegerValue(frame.initial()),
            "remaining", new DataValue.IntegerValue(frame.remaining())))).toList());
    }
    /** Necessary path bounds, not a replay of player computations or their changing locals. */
    void validate(List<FlowProgram.Step> steps, FlowBranches branches, int cursor, long operation, long work) {
        var expected = new ArrayList<Integer>();
        for (int i = 0; i < cursor; i++)
            if (steps.get(i) instanceof FlowProgram.Repeat && cursor <= branches.end(i)) expected.add(i);
        require(frames.size() == expected.size(), "Missing or extra repeat frame");
        long completedWork = 0, offset = 0;
        try {
            for (int i = 0; i < frames.size(); i++) {
                var frame = frames.get(i);
                require(frame.header() == expected.get(i) && frame.header() < steps.size()
                    && steps.get(frame.header()) instanceof FlowProgram.Repeat, "Repeat frames do not match structured nesting");
                require(frame.initial() > 0 && frame.remaining() > 0 && frame.remaining() <= frame.initial(), "Invalid captured repeat count");
                long completed = frame.initial() - frame.remaining();
                completedWork = Math.addExact(completedWork, completed);
                offset = Math.addExact(offset, Math.multiplyExact((long)branches.end(frame.header()) - frame.header(), completed));
            }
            require(work >= Math.addExact(branches.minimumWork(cursor), completedWork), "Work underreports completed repeat iterations");
            require(operation - cursor >= offset, "Operation distance underreports repeat back-edges");
        } catch (ArithmeticException overflow) {
            throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT, "flow", "Repeat history exceeds counter range");
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT, "flow", message);
    }
    Frame top() { return frames.getLast(); }
    FlowRepeatState enter(int header, long count) {
        var next = new ArrayList<>(frames); next.add(new Frame(header, count, count)); return new FlowRepeatState(next);
    }
    FlowRepeatState completedIteration() {
        var next = new ArrayList<>(frames); var frame = next.removeLast();
        next.add(new Frame(frame.header(), frame.initial(), frame.remaining() - 1)); return new FlowRepeatState(next);
    }
    FlowRepeatState exit() { return new FlowRepeatState(frames.subList(0, frames.size() - 1)); }
}
