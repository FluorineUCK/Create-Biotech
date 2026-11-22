package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import java.util.*;

/** Structural moves between single-output expressions, without evaluating a value. */
final class FlowReporterEdits {
    private FlowReporterEdits() {}
    record Edit(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows) {}

    static ScratchValueLayout.Reporter take(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows,
                                             String node, ProgramDocument.Position position) {
        var fragment = InlineGraphEdits.prune(new ComputationGraph(graph.inputs(), graph.nodes(), Map.of("value", node)), bindings);
        var nested = new LinkedHashMap<>(shadows);
        nested.keySet().retainAll(fragment.graph().nodes().keySet());
        nested.remove(node); // The outer socket belongs to the source, not to the reporter.
        return new ScratchValueLayout.Reporter(position, fragment.graph(), fragment.bindings().arguments(), nested);
    }

    static Edit restore(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows, String at, DataValue fallback) {
        requireUnshared(graph, at);
        var special = ScratchListPositions.modeDefault(shadows.get(at));
        if (special.isPresent()) {
            var socket = ScratchListPositions.sockets(graph).get(at);
            if (socket != null) {
                var mode = special.orElseThrow();
                var nodes = new LinkedHashMap<>(graph.nodes());
                nodes.put(at, new ComputationGraph.ListPosition(socket.source(), mode.choice(), socket.insertion()));
                var pruned = InlineGraphEdits.prune(new ComputationGraph(graph.inputs(), nodes, graph.outputs()), bindings);
                var defaults = new LinkedHashMap<>(shadows);
                defaults.keySet().retainAll(pruned.graph().nodes().keySet());
                defaults.put(at, mode.number());
                return new Edit(pruned.graph(), pruned.bindings(), defaults);
            }
        }
        var restored = InlineGraphEdits.replace(graph, bindings, at, new InlineGraphEdits.Literal(shadows.getOrDefault(at, fallback)));
        return new Edit(restored.graph(), restored.bindings(), shadows);
    }

    static Edit attach(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows, String at,
                       ScratchValueLayout.Reporter reporter) {
        requireUnshared(graph, at);
        var previous = graph.nodes().get(at);
        // Remove only the old subtree that is no longer used. Its bindings must not
        // prevent bringing in a reporter that uses the same input ID.
        var cleared = InlineGraphEdits.replace(graph, bindings, at, new InlineGraphEdits.Literal(DataValue.NullValue.INSTANCE));
        var nodes = new LinkedHashMap<>(cleared.graph().nodes());
        var inputs = new LinkedHashMap<>(cleared.graph().inputs());
        var args = new LinkedHashMap<>(cleared.bindings().arguments());
        var defaults = new LinkedHashMap<>(shadows);
        defaults.keySet().retainAll(nodes.keySet());
        if (previous instanceof ComputationGraph.Constant literal) defaults.put(at, literal.value());
        else if (previous instanceof ComputationGraph.ListPosition position)
            defaults.put(at, ScratchListPositions.positionShadow(position, defaults.get(at)));
        // An input ID is local to its graph. A variable rename can leave that ID
        // bound to a different local than a newly created same-named reporter.
        var importedInputs = new HashMap<String, String>();
        int suffix = 0;
        for (var input : new TreeMap<>(reporter.graph().inputs()).entrySet()) {
            String source = input.getKey(), local = reporter.arguments().get(source);
            String target = source;
            if (inputs.containsKey(source) && (!inputs.get(source).equals(input.getValue()) || !Objects.equals(args.get(source), local))) {
                do { target = "local_" + suffix++; }
                while (inputs.containsKey(target) || reporter.graph().inputs().containsKey(target));
            }
            importedInputs.put(source, target);
            inputs.put(target, input.getValue());
            args.put(target, local);
        }
        var names = new LinkedHashMap<String, String>();
        names.put(reporter.graph().outputs().get("value"), at);
        for (var id : reporter.graph().nodes().keySet()) if (!names.containsKey(id)) {
            String next = InlineGraphEdits.next(nodes);
            names.put(id, next);
            nodes.put(next, new ComputationGraph.Constant(DataValue.NullValue.INSTANCE)); // Reserve before wiring.
        }
        reporter.graph().nodes().forEach((id, node) -> {
            var ports = new LinkedHashMap<String, String>();
            NodePorts.bindings(node).forEach((port, source) -> ports.put(port, Objects.requireNonNull(names.get(source))));
            nodes.put(names.get(id), node instanceof ComputationGraph.Input input
                ? new ComputationGraph.Input(importedInputs.get(input.name())) : NodePorts.bind(node, ports));
        });
        reporter.shadows().forEach((node, value) -> defaults.put(names.get(node), value));
        return new Edit(new ComputationGraph(inputs, nodes, graph.outputs()), new FlowCallBindings(args, bindings.returns()), defaults);
    }

    static void requireUnshared(ComputationGraph graph, String at) {
        if (!graph.nodes().containsKey(at)) throw new IllegalArgumentException("Unknown value slot");
        // Imported graphs can share nodes. A gesture on one occurrence must not silently edit another.
        var shared = new HashSet<String>();
        var seen = new HashSet<String>();
        // The special field's hidden edge aliases its parent's visible list socket.
        var selectors = ScratchListPositions.ownedSelectors(graph);
        var pending = new ArrayDeque<>(graph.outputs().values());
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            if (seen.add(node)) {
                if (!selectors.contains(node)) pending.addAll(graph.nodes().get(node).dependencies());
            } else shared.add(node);
        }
        var inherited = new HashSet<String>();
        pending.addAll(shared);
        while (!pending.isEmpty()) {
            String node = pending.removeFirst();
            if (node.equals(at)) throw new IllegalArgumentException("This expression is shared by multiple value slots");
            if (inherited.add(node) && !selectors.contains(node)) pending.addAll(graph.nodes().get(node).dependencies());
        }
    }
}
