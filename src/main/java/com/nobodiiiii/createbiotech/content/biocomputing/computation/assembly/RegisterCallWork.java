package com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException;
import java.util.*;
import java.util.function.IntToLongFunction;

/** Intraprocedural path bounds with revision-pinned child costs. No body inlining or runtime replay.
 * A call's excluded cycles must be possible on this path, not an arbitrary untrusted offset. */
final class RegisterCallWork {
    private record Bounds(long[] min, long[] max) {}
    private final List<RegisterProgram.Code> code;
    private final List<Set<Integer>> edges;
    private final int[] owners;
    private final Map<Integer, RegisterProcedure> procedures;
    private final Bounds totals, children;

    RegisterCallWork(List<RegisterProgram.Code> code, List<Set<Integer>> edges, int[] owners,
            Map<Integer, RegisterProcedure> procedures) {
        this.code = code; this.edges = edges; this.owners = owners; this.procedures = procedures;
        totals = emptyBounds(code.size() + 1); children = emptyBounds(code.size() + 1);
        var scopes = new HashMap<Integer, List<Integer>>();
        for (int pc = 0; pc <= code.size(); pc++) scopes.computeIfAbsent(owners[pc], ignored -> new ArrayList<>()).add(pc);
        var compiled = new HashSet<Integer>();
        var pending = new HashSet<>(procedures.keySet()); pending.add(-1);
        while (!pending.isEmpty()) {
            var ready = new ArrayList<Integer>();
            for (int owner : pending) {
                boolean waiting = false;
                for (int pc : scopes.get(owner)) if (pc < code.size()
                        && code.get(pc).instruction() instanceof RegisterProgram.Call c && !compiled.contains(c.target())) { waiting = true; break; }
                if (!waiting) ready.add(owner);
            }
            if (ready.isEmpty()) throw new IllegalArgumentException("Recursive call costs");
            for (int owner : ready) {
                int start = owner < 0 ? 0 : owner;
                var nodes = scopes.get(owner);
                // A standalone authored image can start directly in its procedure at PC 0.
                // Its synthetic main scope must not reset that procedure's cost bounds.
                if (owners[start] == owner) {
                    paths(start, nodes, pc -> add(RegisterProgram.work(code.get(pc).instruction()), child(pc, false)), pc ->
                        RegisterProgram.variableWork(code.get(pc).instruction()) ? Long.MAX_VALUE : add(RegisterProgram.work(code.get(pc).instruction()), child(pc, true)), totals);
                    for (int pc : nodes) if (pc < code.size() && totals.min()[pc] != Long.MAX_VALUE
                            && RegisterProgram.retriesRandom(code.get(pc).instruction())) totals.max()[pc] = Long.MAX_VALUE;
                    paths(start, nodes, pc -> child(pc, false), pc -> child(pc, true), children);
                }
                compiled.add(owner);
                pending.remove(owner);
            }
        }
    }
    private long child(int pc, boolean maximum) {
        if (!(code.get(pc).instruction() instanceof RegisterProgram.Call c)) return 0;
        int end = procedures.get(c.target()).end();
        return add(maximum ? totals.max()[end] : totals.min()[end], 1); // Include the callee's RET.
    }
    private static Bounds emptyBounds(int size) {
        long[] min = new long[size], max = new long[size]; Arrays.fill(min, Long.MAX_VALUE); Arrays.fill(max, -1);
        return new Bounds(min, max);
    }
    private void paths(int start, List<Integer> nodes, IntToLongFunction low, IntToLongFunction high, Bounds target) {
        long[] min = target.min(), max = target.max(); min[start] = 0; max[start] = 0;
        record Visit(int pc, long cost) {}
        var queue = new PriorityQueue<Visit>(Comparator.comparingLong(Visit::cost)); queue.add(new Visit(start, 0));
        while (!queue.isEmpty()) {
            var visit = queue.remove(); int pc = visit.pc();
            if (min[pc] != visit.cost() || edges.get(pc).isEmpty()) continue;
            long nextCost = add(visit.cost(), low.applyAsLong(pc));
            for (int next : edges.get(pc)) if (nextCost < min[next]) { min[next] = nextCost; queue.add(new Visit(next, nextCost)); }
        }
        // Zero-cost control cycles do not invent child work. Positive cycles (or scans)
        // produce an unbounded upper estimate, then propagate only along reachable edges.
        var growing = new HashSet<Integer>();
        for (int pass = 0; pass < nodes.size(); pass++) {
            boolean changed = false; growing.clear();
            for (int pc : nodes) {
                if (max[pc] < 0 || edges.get(pc).isEmpty()) continue;
                long cost = add(max[pc], high.applyAsLong(pc));
                for (int next : edges.get(pc)) if (cost > max[next]) { max[next] = cost; changed = true; growing.add(next); }
            }
            if (!changed) break;
        }
        var infinite = new ArrayDeque<>(growing); var seen = new HashSet<Integer>();
        while (!infinite.isEmpty()) {
            int pc = infinite.removeFirst(); if (!seen.add(pc)) continue;
            max[pc] = Long.MAX_VALUE; infinite.addAll(edges.get(pc));
        }
    }
    void validate(int pc, long entryWork, long excludedWork, long cycles, boolean attempted) {
        if (pc < 0 || pc >= owners.length || cycles < entryWork || excludedWork < entryWork) invalid();
        long completed = cycles - entryWork - (attempted ? 1 : 0), nested = excludedWork - entryWork;
        if (totals.min()[pc] == Long.MAX_VALUE || completed < totals.min()[pc] || completed > totals.max()[pc]
                || children.min()[pc] == Long.MAX_VALUE || nested < children.min()[pc] || nested > children.max()[pc]) invalid();
    }
    private static long add(long left, long right) {
        return left == Long.MAX_VALUE || right == Long.MAX_VALUE || left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
    private static void invalid() {
        throw new ComputationException(ComputationException.Code.INVALID_CHECKPOINT, "assembly", "Call work differs from its instruction path");
    }
}
