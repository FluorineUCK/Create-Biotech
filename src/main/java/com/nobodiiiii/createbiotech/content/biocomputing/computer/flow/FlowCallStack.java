package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Suspended synchronous callers. No separate executor, hardware budget, or mutable shared locals. */
public record FlowCallStack(List<Frame> frames) {
    public static final FlowCallStack EMPTY = new FlowCallStack(List.of());
    public record Frame(int cursor, DataValue.RecordValue locals, FlowRepeatState repeats, long work, long operation) {
        public Frame { Objects.requireNonNull(locals); Objects.requireNonNull(repeats); }
    }
    public FlowCallStack { frames = List.copyOf(frames); }
    public DataValue.ListValue value() {
        return new DataValue.ListValue(frames.stream().<DataValue>map(frame -> new DataValue.RecordValue(Map.of(
            "cursor", new DataValue.IntegerValue(frame.cursor()), "locals", frame.locals(), "repeats", frame.repeats().value(),
            "work", new DataValue.IntegerValue(frame.work()), "operation", new DataValue.IntegerValue(frame.operation())))).toList());
    }
    Frame top() { return frames.getLast(); }
    FlowCallStack enter(FlowRuntime.Checkpoint state) {
        var next = new ArrayList<>(frames); next.add(new Frame(state.cursor(), state.locals(), state.repeats(), state.work(), state.operation()));
        return new FlowCallStack(next);
    }
    FlowCallStack exit() { return new FlowCallStack(frames.subList(0, frames.size() - 1)); }
    void validate(FlowProgram program, FlowProgram.Installation installed, FlowRuntime.Checkpoint state) {
        int expectedOwner = installed.branches().entryOwner(); long previousWork = -1, previousOperation = -1;
        for (var frame : frames) {
            require(frame.cursor() >= 0 && frame.cursor() < program.steps().size(), "Caller cursor outside program");
            require(program.steps().get(frame.cursor()) instanceof FlowProgram.Invoke, "Caller is not at a procedure call");
            require(installed.branches().owner(frame.cursor()) == expectedOwner, "Caller chain crosses procedure scope");
            require(installed.locals().get(frame.cursor()).accepts(frame.locals()), "Caller locals differ from call schema");
            require(frame.work() > previousWork && frame.work() <= state.work()
                && frame.work() > installed.branches().minimumWork(frame.cursor()), "Caller work is invalid");
            require(frame.operation() >= frame.cursor() && frame.operation() > previousOperation && frame.operation() < state.operation(), "Caller operation identity is invalid");
            frame.repeats().validate(program.steps(), installed.branches(), frame.cursor(), frame.operation(), frame.work());
            var call = (FlowProgram.Invoke)program.steps().get(frame.cursor());
            expectedOwner = installed.branches().procedure(call.procedure());
            previousWork = frame.work(); previousOperation = frame.operation();
        }
        require(state.status() == FlowRuntime.Status.COMPLETE && frames.isEmpty()
            || installed.branches().owner(state.cursor()) == expectedOwner, "Missing or extra procedure call frame");
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT, "flow", message);
    }
}
