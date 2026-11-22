package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;

/** Matching core only. The physical host must validate the actual connection before calling it. */
public final class XBusExchange {
    private XBusExchange() {}
    public enum Status { NOT_READY, PREPARED, EXCHANGED, READABLE, STALE, RETIRED, UNAVAILABLE, CLOSED, PAUSED, PORT_MISMATCH, TYPE_MISMATCH, MEMORY_LIMIT, ARCHIVE_LIMIT, HISTORY_CONFLICT, UNSUPPORTED, NOT_FOUND, ARITHMETIC_OVERFLOW, RECOVERY_REQUIRED }
    public record Transition(FlowRuntime.Checkpoint leftBefore, FlowRuntime.Checkpoint leftAfter,
        FlowRuntime.Checkpoint rightBefore, FlowRuntime.Checkpoint rightAfter) {}
    public static final class Preparation {
        private final Status status;
        private final FlowRuntime left, right;
        private final Transition transition;
        private Preparation(Status status, FlowRuntime left, FlowRuntime right, Transition transition) {
            this.status = status; this.left = left; this.right = right; this.transition = transition;
        }
        public Status status() { return status; }
        public Optional<Transition> transition() { return Optional.ofNullable(transition); }
        /** In-memory publication only, NOT an atomic save of two independently persisted blocks. */
        public Status commit() {
            if (transition == null) return status;
            if (!left.loaded() || !right.loaded()) return Status.UNAVAILABLE;
            if (left.checkpoint() != transition.leftBefore() || right.checkpoint() != transition.rightBefore()) return Status.STALE;
            left.publish(transition.leftAfter()); right.publish(transition.rightAfter());
            return transition.leftBefore() == transition.leftAfter() || transition.rightBefore() == transition.rightAfter()
                ? Status.READABLE : Status.EXCHANGED;
        }
    }
    public static Preparation prepare(FlowRuntime left, String leftPort, FlowRuntime right, String rightPort) {
        try { return prepareChecked(left, leftPort, right, rightPort); }
        catch (ComputationException error) {
            if (error.code() == ComputationException.Code.MEMORY_LIMIT) return rejected(Status.MEMORY_LIMIT);
            if (error.code() == ComputationException.Code.ARITHMETIC_OVERFLOW) return rejected(Status.ARITHMETIC_OVERFLOW);
            throw error;
        } catch (ArithmeticException overflow) { return rejected(Status.MEMORY_LIMIT); }
    }
    private static Preparation prepareChecked(FlowRuntime left, String leftPort, FlowRuntime right, String rightPort) {
        Objects.requireNonNull(left); Objects.requireNonNull(right); FlowProgram.name(leftPort); FlowProgram.name(rightPort);
        if (!left.loaded() || !right.loaded()) return rejected(Status.UNAVAILABLE);
        if (left.checkpoint().paused() || right.checkpoint().paused()) return rejected(Status.PAUSED);
        if (left == right || left.checkpoint().computer().equals(right.checkpoint().computer())
            && left.checkpoint().flow().equals(right.checkpoint().flow())) return rejected(Status.NOT_READY);
        boolean forward = left.checkpoint().status() == FlowRuntime.Status.WAITING_WRITE
            && isReading(right);
        boolean backward = right.checkpoint().status() == FlowRuntime.Status.WAITING_WRITE
            && isReading(left);
        if (!forward && !backward) return rejected(Status.NOT_READY);
        var writer = forward ? left : right; var reader = forward ? right : left;
        var write = FlowXBus.at(writer.program(),writer.checkpoint());
        String readerPort=FlowXBus.pin(reader.program(),reader.checkpoint());
        if (!write.pin().equals(forward ? leftPort : rightPort) || !readerPort.equals(forward ? rightPort : leftPort))
            return rejected(Status.PORT_MISMATCH);
        if (reader.checkpoint().status() == FlowRuntime.Status.WAITING_READABLE) {
            var wake = reader.completedXBus(null);
            return new Preparation(Status.PREPARED, left, right, new Transition(left.checkpoint(), forward ? left.checkpoint() : wake,
                right.checkpoint(), forward ? wake : right.checkpoint()));
        }
        var read = FlowXBus.at(reader.program(),reader.checkpoint());
        var payload = FlowXBus.payload(writer.program(),writer.checkpoint());
        com.nobodiiiii.createbiotech.content.biocomputing.computation.LocalReferences.portable(payload);
        if (!read.payload().accepts(payload)) return rejected(Status.TYPE_MISMATCH);
        var received = reader.completedXBus((DataValue.RecordValue)payload);
        var sent = writer.completedXBus(null);
        return new Preparation(Status.PREPARED, left, right, new Transition(left.checkpoint(), forward ? sent : received,
            right.checkpoint(), forward ? received : sent));
    }
    private static boolean isReading(FlowRuntime runtime) {
        return runtime.checkpoint().status() == FlowRuntime.Status.WAITING_READ || runtime.checkpoint().status() == FlowRuntime.Status.WAITING_READABLE;
    }
    private static Preparation rejected(Status status) { return new Preparation(status, null, null, null); }
}
