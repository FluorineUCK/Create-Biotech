package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterDevices;
import java.util.*;

/** Additional continuations, not additional machines. Main always occupies the first task slot. */
public record FlowTasks(List<FlowRuntime.Checkpoint> contexts, Optional<UUID> after, FlowTaskSlots slots) {
    public static final FlowTasks EMPTY = new FlowTasks(List.of(), Optional.empty());
    public FlowTasks {
        contexts = List.copyOf(contexts); Objects.requireNonNull(after); Objects.requireNonNull(slots).validate(contexts);
        if (contexts.isEmpty() && after.isPresent()) throw new IllegalArgumentException("Empty task group has no cursor");
    }
    public FlowTasks(List<FlowRuntime.Checkpoint> contexts, Optional<UUID> after) {
        this(contexts, after, FlowTaskSlots.legacy(contexts));
    }
    void validate(FlowProgram program, FlowRuntime.Checkpoint main) {
        if (slots.entries().stream().anyMatch(s -> s.flow().equals(main.flow()))) throw new IllegalArgumentException("Main cannot occupy a child task slot");
        for (var watch : program.watches().keySet()) {
            var matching = contexts.stream().filter(s -> s.entry().filter(watch::equals).isPresent()).toList();
            if (matching.size() != 1 || slots.entries().stream().noneMatch(s -> !s.event() && !s.retired() && s.flow().equals(matching.getFirst().flow())))
                throw new IllegalArgumentException("Expected one retained context for each installed monitor");
        }
        if (contexts.isEmpty()) return;
        var ids = new HashSet<UUID>(); ids.add(main.flow());
        for (var state : contexts) {
            if (state.entry().isEmpty() || !state.computer().equals(main.computer()) || state.paused() != main.paused()
                    || !ids.add(state.flow())) throw new IllegalArgumentException("Invalid managed task ownership");
            requireSupported(program, state.entry().orElseThrow());
        }
        var startup = new HashSet<>(program.startupEntries());
        for (var root : startup) {
            var matching=contexts.stream().filter(state->state.entry().filter(root::equals).isPresent()).toList();
            if (matching.size()>1 || !matching.isEmpty() && slots.entries().stream().noneMatch(slot->
                slot.event() && slot.waiter().isEmpty() && !slot.retired() && slot.flow().equals(matching.getFirst().flow())))
                throw new IllegalArgumentException("Invalid startup task ownership");
        }
        var eventSlots = slots.entries().stream().filter(FlowTaskSlots.Slot::event).toList();
        if (!eventSlots.isEmpty()) {
            if (!program.usesEvents() && !program.usesTerminalKeys() && !program.usesTryLaunch() && startup.isEmpty()) throw new IllegalArgumentException("Event slots require installed event hats");
            var receivers = new HashSet<ModuleDefinition.Key>();
            var unlinked = new HashSet<>(startup);
            for (var step : program.steps()) if (step instanceof FlowProgram.Procedure p) {
                if (p.event().isPresent()) receivers.add(p.key());
                if (p.terminalKey().isPresent()) unlinked.add(p.key());
            }
            for (var step : program.steps()) if (step instanceof FlowProgram.TryLaunch launch) unlinked.add(launch.procedure());
            for (var step : program.steps()) if (step instanceof FlowProgram.Watch watch) watch.handler().ifPresent(unlinked::add);
            var byId = new HashMap<UUID, FlowRuntime.Checkpoint>();
            byId.put(main.flow(), main);
            contexts.forEach(state -> byId.put(state.flow(), state));
            var waits = new HashMap<UUID, UUID>();
            for (var slot : eventSlots) {
                var child = byId.get(slot.flow());
                var entry = child.entry().orElseThrow();
                if (!receivers.contains(entry) && !(unlinked.contains(entry) && slot.waiter().isEmpty()))
                    throw new IllegalArgumentException("Invalid event task receiver");
                if (!terminal(child)) slot.waiter().ifPresent(waiter -> {
                    var parent = byId.get(waiter);
                    if (parent == null || parent.status() != FlowRuntime.Status.WAITING_TASK)
                        throw new IllegalArgumentException("Event waiter is no longer waiting");
                    waits.put(slot.flow(), waiter);
                });
            }
            for (var child : waits.keySet()) {
                var path = new HashSet<UUID>();
                for (var id = child; id != null; id = waits.get(id))
                    if (!path.add(id)) throw new IllegalArgumentException("Circular event wait");
            }
        }
        if (after.isPresent() && !ids.contains(after.orElseThrow())) throw new IllegalArgumentException("Unknown task cursor");
    }
    static void requireStartupCapacity(FlowProgram program,int slots) {
        var startup=program.startupEntries();
        int monitors=program.watches().size();
        if (monitors>0 && monitors>slots-2) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT,"watch","Configured monitors need room for a handler");
        if (startup.size()>slots-1-monitors) throw FlowProgram.error(ComputationException.Code.MEMORY_LIMIT,"startup",
            "Startup scripts and monitors need "+(1L+startup.size()+monitors)+" task slots; hardware has "+slots);
        startup.forEach(entry->requireSupported(program,entry));
    }
    /** Root-owned outputs may coexist with tasks that never touch SIO. Input-only programs may
     * also sample in children. No child may drive a latch or change a root-owned mode. */
    static void requireSupported(FlowProgram program, ModuleDefinition.Key entry) {
        if (!FlowProgram.hasSignals(program.steps())) return;
        if (FlowDevices.uses(program.steps(), program.expressions().program().cells()).stream()
                .anyMatch(use -> use.kind() == RegisterDevices.Kind.SIO_WRITE)
                && taskUsesSignals(program, entry))
            throw FlowProgram.error(ComputationException.Code.INVALID_ARGUMENT, "tasks",
                "Tasks touching Simple I/O in a program with outputs require physical output arbitration; not yet available");
    }
    private static boolean taskUsesSignals(FlowProgram program, ModuleDefinition.Key entry) {
        var branches = FlowBranches.compile(program.steps());
        var pending = new ArrayDeque<ModuleDefinition.Key>(); pending.add(entry);
        var visited = new HashSet<ModuleDefinition.Key>();
        while (!pending.isEmpty()) {
            var key = pending.removeFirst(); if (!visited.add(key)) continue;
            int start = branches.procedure(key);
            var declaration = (FlowProgram.Procedure)program.steps().get(start);
            if (FlowDevices.authored(declaration, program.expressions().program().cells()).stream()
                    .anyMatch(use -> use.kind() != RegisterDevices.Kind.XBUS_STATUS)) return true;
            for (int at = start + 1; at < branches.end(start); at++) {
                var step = program.steps().get(at);
                if (step instanceof FlowProgram.SignalRead || step instanceof FlowProgram.SignalWrite) return true;
                if (step instanceof FlowProgram.Invoke call) pending.addLast(call.procedure());
                // Reporter calls execute in this task too. A Watch only lowers its selected output.
                var expression = FlowProgram.expressionEntry(step);
                var graphEntry = expression == null ? null : program.expressions().program().entries().get(expression);
                if (graphEntry != null) {
                    var graph = graphEntry.graph();
                    var nodes = new ArrayDeque<String>();
                    if (step instanceof FlowProgram.Watch w && w.condition() instanceof FlowInput.Expression ref) {
                        var root = graph.outputs().get(ref.output());
                        if (root != null) nodes.add(root);
                    } else nodes.addAll(graph.outputs().values());
                    var seen = new HashSet<String>();
                    while (!nodes.isEmpty()) {
                        var id = nodes.removeFirst();
                        if (!seen.add(id)) continue;
                        var node = graph.nodes().get(id);
                        if (node instanceof ComputationGraph.ProcedureValue value) pending.addLast(value.procedure());
                        if (node != null) nodes.addAll(node.dependencies());
                    }
                }
            }
        }
        return false;
    }
    /** Definitions and explicit calls only; a caller's continuation is not part of a callee. */
    public static Set<String> xbusPins(FlowProgram program, ModuleDefinition.Key entry) {
        var pins = new HashSet<String>();
        var branches = FlowBranches.compile(program.steps());
        var pending = new ArrayDeque<ModuleDefinition.Key>(); pending.add(entry);
        var visited = new HashSet<ModuleDefinition.Key>();
        while (!pending.isEmpty()) {
            var key = pending.removeFirst(); if (!visited.add(key)) continue;
            int start = branches.procedure(key);
            var declaration=(FlowProgram.Procedure)program.steps().get(start);
            for(var c:FlowXBus.authored(declaration,program.expressions().program().cells()))if(c.instruction() instanceof com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.RegisterXBus.Instruction d)pins.add(d.pin());
            for (int at = start + 1; at < branches.end(start); at++) {
                var step = program.steps().get(at);
                if (FlowPins.pin(step) != null) pins.add(FlowPins.pin(step));
                if (step instanceof FlowProgram.Invoke call) pending.addLast(call.procedure());
            }
        }
        return Set.copyOf(pins);
    }
    public long metadataUnits() {
        if (slots.entries().isEmpty()) return 0;
        return new DataValue.RecordValue(Map.of("after", after.<DataValue>map(id -> new DataValue.TextValue(id.toString())).orElse(DataValue.NullValue.INSTANCE),
            "slots", slots.value())).storageUnits();
    }
    /** The world scheduler must not classify a Computer as dormant just because its main sleeps. */
    public boolean hasReadyTask(FlowRuntime.Checkpoint main) {
        return !main.paused() && (ready(main) || contexts.stream().anyMatch(FlowTasks::ready));
    }
    static boolean ready(FlowRuntime.Checkpoint state) {
        return state.status() == FlowRuntime.Status.READY || state.status() == FlowRuntime.Status.COMPUTING;
    }
    FlowTasks replace(FlowRuntime.Checkpoint next, UUID served) {
        return new FlowTasks(contexts.stream().map(s -> s.flow().equals(next.flow()) ? next : s).toList(), Optional.of(served), slots);
    }
    static boolean terminal(FlowRuntime.Checkpoint state) {
        return state.status() == FlowRuntime.Status.COMPLETE || state.status() == FlowRuntime.Status.FAILED;
    }
    boolean hasEventWork(UUID waiter) {
        var matching = new HashSet<UUID>();
        for (var slot : slots.entries()) if (slot.waiter().filter(waiter::equals).isPresent()) matching.add(slot.flow());
        return contexts.stream().anyMatch(state -> matching.contains(state.flow()) && !terminal(state));
    }
}
