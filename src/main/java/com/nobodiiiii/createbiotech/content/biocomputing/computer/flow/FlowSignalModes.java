package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;

/** Conservative drive-mode fixed points; first-pass facts remain separate from loop re-entry. */
final class FlowSignalModes {
    private record Modes(Set<String> required, Set<String> allowed) {
        Modes { required = Set.copyOf(required); allowed = Set.copyOf(allowed); }
        Modes after(FlowProgram.Step step) {
            var must = new HashSet<>(required); var may = new HashSet<>(allowed);
            if (step instanceof FlowProgram.SignalWrite write) { must.add(write.port()); may.add(write.port()); }
            if (step instanceof FlowProgram.SignalRead read) { must.remove(read.port()); may.remove(read.port()); }
            return new Modes(must, may);
        }
        Modes merge(Modes other) {
            var must = new HashSet<>(required); must.retainAll(other.required);
            var may = new HashSet<>(allowed); may.addAll(other.allowed);
            return new Modes(must, may);
        }
    }
    private final List<Modes> before, first;
    private FlowSignalModes(List<Modes> before, List<Modes> first) { this.before = before; this.first = first; }
    static FlowSignalModes compile(List<FlowProgram.Step> steps, FlowBranches branches) {
        var first = analyze(steps, branches, false);
        return new FlowSignalModes(FlowProgram.hasLoops(steps) || FlowProgram.hasProcedures(steps) ? analyze(steps, branches, true) : first, first);
    }
    private static List<Modes> analyze(List<FlowProgram.Step> steps, FlowBranches branches, boolean backEdges) {
        var before = new ArrayList<Modes>(Collections.nCopies(steps.size() + 1, null));
        before.set(branches.entry(), new Modes(Set.of(), Set.of()));
        var pending = new ArrayDeque<Integer>(); var queued = new BitSet(); pending.add(branches.entry()); queued.set(branches.entry());
        while (!pending.isEmpty()) {
            int i = pending.removeFirst(); queued.clear(i);
            if (i == steps.size()) continue;
            var step = steps.get(i); var after = before.get(i).after(step);
            for (int next : branches.successors(i)) {
                if (!backEdges && next <= i) continue;
                if (merge(before, next, after) && !queued.get(next)) { pending.addLast(next); queued.set(next); }
            }
        }
        return Collections.unmodifiableList(before);
    }
    private static boolean merge(List<Modes> states, int at, Modes incoming) {
        var previous = states.get(at); var next = previous == null ? incoming : previous.merge(incoming);
        if (Objects.equals(previous, next)) return false;
        states.set(at, next); return true;
    }
    void validate(int cursor, long operation, SimpleIoState actual) {
        var mode = (operation == cursor ? first : before).get(cursor); var pins = actual.outputs().keySet();
        if (mode == null || !pins.containsAll(mode.required) || !mode.allowed.containsAll(pins))
            throw FlowProgram.error(ComputationException.Code.INVALID_CHECKPOINT, "flow", "Saved signal drive modes differ from control position");
    }
}
