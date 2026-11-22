package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Private no-payload proof that one admitted operation was explicitly retired, not delivered. */
public record XBusCancellation(XBusReceipt.Position holder, Set<XBusReceipt.Position> writers, FlowControl.Peer proof) {
    public XBusCancellation {
        Objects.requireNonNull(holder); writers = Set.copyOf(writers); Objects.requireNonNull(proof);
        if (!writers.contains(holder) || !proof.supersedes(holder)) throw new IllegalArgumentException("Unproven XBus cancellation");
        var operations = new HashSet<List<Object>>();
        for (var p : writers) if (!operations.add(List.of(p.computer(), p.flow(), p.generation(), p.operation())))
            throw new IllegalArgumentException("Duplicate cancelled operation");
    }
    public List<XBusReceipt.Position> ordered() {
        return writers.stream().sorted(Comparator.comparing(XBusReceipt.Position::computer).thenComparing(XBusReceipt.Position::flow)
            .thenComparingLong(XBusReceipt.Position::operation)).toList();
    }
    public DataValue.RecordValue value() {
        return new DataValue.RecordValue(Map.of("holder", holder.value(),
            "writers", new DataValue.ListValue(ordered().stream().<DataValue>map(XBusReceipt.Position::value).toList()),
            "proof", new FlowControl(0, Map.of(holder.computer(), proof)).value()));
    }
    public long units() { return writers.size() + (long)proof.slots().size(); }
    public boolean matches(FlowBlockHost.Saved saved, FlowRuntime.Checkpoint state) {
        return writers.stream().anyMatch(p -> matches(p, saved, state));
    }
    private static boolean matches(XBusReceipt.Position p, FlowBlockHost.Saved saved, FlowRuntime.Checkpoint state) {
        return saved.context(p).filter(state::equals).isPresent()
            && FlowXBus.matches(p,state) && state.operation() == p.operation()
            && state.cursor() < saved.program().steps().size()
            && FlowXBus.at(saved.program(),state).kind()==com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Kind.WRITE && FlowXBus.pin(saved.program(),state).equals(p.pin());
    }
    public boolean pending(FlowBlockHost.Saved saved, String pin) {
        return writers.stream().anyMatch(p -> p.pin().equals(pin) && saved.context(p)
            .filter(s -> s.status() == FlowRuntime.Status.WAITING_WRITE && matches(p, saved, s)).isPresent());
    }
    public boolean activePending(FlowBlockHost.Saved saved, String pin) {
        return !saved.state().paused() && writers.stream().anyMatch(p -> p.pin().equals(pin) && saved.context(p)
            .filter(s -> !s.paused() && s.status() == FlowRuntime.Status.WAITING_WRITE && matches(p, saved, s)).isPresent());
    }
}
