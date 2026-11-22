package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ModuleDefinition;
import java.util.*;

/** Matched structured control, never player-supplied jump addresses. Loops have explicit back-edges. */
public final class FlowBranches {
    private final int[] end, start, otherwise, depth;
    private final List<FlowProgram.Step> steps;
    private final long[] minimumWork;
    private final long[] repeatedWork, repeatedOffset;
    private final Map<ModuleDefinition.Key, Integer> procedures = new LinkedHashMap<>();
    private final Map<Integer, List<Integer>> callers = new HashMap<>();
    private final int[] owner;
    private final boolean resolveCalls;
    private final int entry, entryOwner;
    private FlowBranches(List<FlowProgram.Step> steps, boolean resolveCalls) {
        this(steps, resolveCalls, Optional.empty(), Map.of());
    }
    private FlowBranches(List<FlowProgram.Step> steps, boolean resolveCalls, Optional<ModuleDefinition.Key> taskEntry,
                         Map<Integer, Set<ModuleDefinition.Key>> reporterCalls) {
        this.resolveCalls = resolveCalls;
        this.steps = List.copyOf(steps);
        int size = steps.size(); end = new int[size]; start = new int[size]; otherwise = new int[size]; depth = new int[size];
        Arrays.fill(end, -1); Arrays.fill(start, -1); Arrays.fill(otherwise, -1);
        owner = new int[size + 1]; Arrays.fill(owner, -1);
        var open = new ArrayDeque<Integer>();
        int procedureOwner = -1;
        for (int i = 0; i < size; i++) {
            var step = steps.get(i);
            var declaration = FlowBlockDeclarations.of(step);
            owner[i] = procedureOwner;
            if (step instanceof FlowProgram.Procedure procedure) {
                if (!open.isEmpty()) throw invalid(step, "Procedure definitions must be at document scope");
                if (procedures.putIfAbsent(procedure.key(), i) != null) throw invalid(step, "Duplicate fixed procedure version");
                procedureOwner = i;
            }
            if (declaration.delimiter()) {
                if (open.isEmpty()) throw invalid(step, "Control delimiter has no matching header");
                int start = open.peek();
                if (!declaration.closes().equals(FlowBlockDeclarations.of(steps.get(start)).id()))
                    throw invalid(step, "Control delimiter crosses a different group");
                if (step instanceof FlowProgram.Otherwise) {
                    if (otherwise[start] >= 0) throw invalid(step, "A branch has more than one otherwise");
                    otherwise[start] = i;
                } else {
                    open.pop(); end[start] = i;
                    this.start[i] = start;
                    if (otherwise[start] >= 0) end[otherwise[start]] = i;
                }
                depth[i] = step instanceof FlowProgram.Otherwise ? open.size() - 1 : open.size();
                if (step instanceof FlowProgram.EndProcedure) procedureOwner = -1;
            } else {
                depth[i] = open.size();
                if (declaration.header()) open.push(i);
            }
        }
        if (!open.isEmpty()) throw invalid(steps.get(open.peek()), "Control header has no matching end");
        for (int i = 0; resolveCalls && i < size; i++) if (steps.get(i) instanceof FlowProgram.Invoke call) {
            int target = procedure(call.procedure()); callers.computeIfAbsent(target, ignored -> new ArrayList<>()).add(i);
        }
        if (resolveCalls) rejectRecursion(reporterCalls);
        entryOwner = taskEntry.map(this::procedure).orElse(-1);
        entry = entryOwner + 1;
        minimumWork = new long[size + 1]; Arrays.fill(minimumWork, Long.MAX_VALUE); minimumWork[entry] = 0;
        var pending = new ArrayDeque<Integer>(); pending.add(entry);
        while (!pending.isEmpty()) {
            int at = pending.removeFirst();
            if (at == size) continue;
            for (int next : successors(at)) if (minimumWork[next] == Long.MAX_VALUE) {
                minimumWork[next] = minimumWork[at] + 1; pending.addLast(next);
            }
        }
        boolean backEdges = FlowProgram.hasLoops(steps) || FlowProgram.hasProcedures(steps);
        repeatedWork = backEdges ? repeatedDistance(true) : null;
        repeatedOffset = backEdges ? repeatedDistance(false) : null;
    }
    private void rejectRecursion(Map<Integer, Set<ModuleDefinition.Key>> reporterCalls) {
        var edges = new HashMap<Integer, Set<Integer>>(); var incoming = new HashMap<Integer, Integer>();
        procedures.values().forEach(at -> { edges.put(at, new HashSet<>()); incoming.put(at, 0); });
        callers.forEach((target, sites) -> sites.forEach(at -> {
            if (owner[at] >= 0 && edges.get(owner[at]).add(target)) incoming.merge(target, 1, Integer::sum);
        }));
        reporterCalls.forEach((site, targets) -> targets.forEach(key -> {
            int target = procedure(key);
            if (owner[site] >= 0 && edges.get(owner[site]).add(target)) incoming.merge(target, 1, Integer::sum);
        }));
        var ready = new ArrayDeque<Integer>(); incoming.forEach((at, count) -> { if (count == 0) ready.add(at); });
        int visited = 0;
        while (!ready.isEmpty()) { int at = ready.remove(); visited++;
            for (int next : edges.get(at)) if (incoming.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        if (visited != procedures.size()) throw FlowProgram.error(ComputationException.Code.INVALID_GRAPH, "flow", "Recursive flow procedures are not supported");
    }
    int procedure(ModuleDefinition.Key key) {
        var at = procedures.get(key);
        if (at == null) throw FlowProgram.error(ComputationException.Code.MISSING_REFERENCE, "flow", "Unknown procedure version: " + key);
        return at;
    }
    int owner(int cursor) { return owner[cursor]; }
    private record Visit(int cursor, boolean repeated, long distance) {}
    /** Lower bounds for a path that has actually crossed a back-edge, not an execution replay. */
    private long[] repeatedDistance(boolean work) {
        var distance = new long[2][steps.size() + 1];
        for (var row : distance) Arrays.fill(row, Long.MAX_VALUE);
        distance[0][entry] = 0;
        var pending = new PriorityQueue<Visit>(Comparator.comparingLong(Visit::distance));
        pending.add(new Visit(entry, false, 0));
        while (!pending.isEmpty()) {
            var at = pending.remove(); int phase = at.repeated() ? 1 : 0;
            if (at.distance() != distance[phase][at.cursor()] || at.cursor() == steps.size()) continue;
            for (int next : successors(at.cursor())) {
                boolean back = next <= at.cursor(), repeated = at.repeated() || back;
                long cost = at.distance() + (work ? 1 : back ? (long)at.cursor() - next + 1 : 0);
                int targetPhase = repeated ? 1 : 0;
                if (cost < distance[targetPhase][next]) {
                    distance[targetPhase][next] = cost; pending.add(new Visit(next, repeated, cost));
                }
            }
        }
        return distance[1];
    }
    public static FlowBranches compile(List<FlowProgram.Step> steps) { return new FlowBranches(steps, true); }
    static FlowBranches compile(List<FlowProgram.Step> steps, Optional<ModuleDefinition.Key> entry) { return compile(steps, entry, Map.of()); }
    static FlowBranches compile(List<FlowProgram.Step> steps, Optional<ModuleDefinition.Key> entry, Map<Integer, Set<ModuleDefinition.Key>> reporterCalls) {
        return new FlowBranches(steps, true, entry, reporterCalls);
    }
    int entry() { return entry; }
    int entryOwner() { return entryOwner; }
    /** Editing one stack validates its structure; only installation resolves the complete definition set. */
    public static FlowBranches structure(List<FlowProgram.Step> steps) { return new FlowBranches(steps, false); }
    public int end(int start) { return end[start]; }
    public int start(int end) { return start[end]; }
    int[] successors(int at) {
        if (!resolveCalls && (steps.get(at) instanceof FlowProgram.Invoke || steps.get(at) instanceof FlowProgram.EndProcedure)) return new int[] {at + 1};
        if (steps.get(at) instanceof FlowProgram.Procedure) return new int[] {end[at] + 1};
        if (steps.get(at) instanceof FlowProgram.Invoke call) return new int[] {procedure(call.procedure()) + 1};
        if (steps.get(at) instanceof FlowProgram.StopScript stop && stop.cap())
            return new int[] {stop.mode() == FlowProgram.StopMode.ALL || owner[at] < 0 ? steps.size() : end[owner[at]]};
        if (steps.get(at) instanceof FlowProgram.EndProcedure && start[at] == entryOwner) return new int[] {steps.size()};
        if (steps.get(at) instanceof FlowProgram.EndProcedure)
            return callers.getOrDefault(start[at], List.of()).stream().mapToInt(call -> call + 1).toArray();
        return steps.get(at) instanceof FlowProgram.If ? new int[] {at + 1, alternate(at)}
            : steps.get(at) instanceof FlowProgram.Repeat || steps.get(at) instanceof FlowProgram.Until ? new int[] {at + 1, end[at] + 1}
            : steps.get(at) instanceof FlowProgram.EndRepeat ? new int[] {start[at] + 1, at + 1}
            : new int[] {steps.get(at) instanceof FlowProgram.Otherwise ? end[at] + 1
                : steps.get(at) instanceof FlowProgram.EndForever || steps.get(at) instanceof FlowProgram.EndUntil ? start[at] : at + 1};
    }
    public boolean hasOtherwise(int start) { return otherwise[start] >= 0; }
    public int depth(int index) { return depth[index]; }
    int alternate(int start) { return (otherwise[start] < 0 ? end[start] : otherwise[start]) + 1; }
    long minimumWork(int cursor) { return minimumWork[cursor]; }
    long repeatedWork(int cursor) { return repeatedWork == null ? Long.MAX_VALUE : repeatedWork[cursor]; }
    long repeatedOffset(int cursor) { return repeatedOffset == null ? Long.MAX_VALUE : repeatedOffset[cursor]; }
    private static ComputationException invalid(FlowProgram.Step step, String message) {
        return FlowProgram.error(ComputationException.Code.INVALID_GRAPH, step.sourceId(), message);
    }
}
