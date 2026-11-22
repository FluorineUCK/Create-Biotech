package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import java.util.*;

/** The scratch position field shares its parent's source; it is not a loose value reporter. */
final class ScratchListPositions {
    record Socket(String source, String normalization, boolean insertion) {}
    record Edit(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows) {}
    record ModeDefault(ComputationGraph.ListPosition.Choice choice, DataValue.IntegerValue number) {}
    private static final DataValue.IntegerValue ONE = new DataValue.IntegerValue(1);
    private ScratchListPositions() {}

    static String field(ComputationGraph graph, String normalized) {
        if (normalized != null && graph.nodes().get(normalized) instanceof ComputationGraph.Scalar offset
            && offset.operation() == ScalarOperation.SUBTRACT_INTEGER
            && graph.nodes().get(offset.arguments().get("right")) instanceof ComputationGraph.Constant constant
            && constant.value().equals(ONE)) return offset.arguments().get("left");
        return null;
    }
    static Map<String, Socket> sockets(ComputationGraph graph) {
        var sockets = new LinkedHashMap<String, Socket>();
        graph.nodes().values().forEach(node -> {
            String field;
            Socket socket;
            if (node instanceof ComputationGraph.DynamicIndex index) {
                field = field(graph, index.index()); socket = new Socket(index.source(), index.index(), false);
            } else if (node instanceof ComputationGraph.ListEdit edit && edit.operation().inputs().containsKey("index")) {
                field = field(graph, edit.arguments().get("index"));
                socket = new Socket(edit.arguments().get("source"), edit.arguments().get("index"), edit.operation() == ListOperation.INSERT);
            } else return;
            if (field != null) sockets.putIfAbsent(field, socket);
        });
        return sockets;
    }
    static boolean hasMenu(ComputationGraph.Node node) {
        return node instanceof ComputationGraph.ListPosition
            || node instanceof ComputationGraph.Constant c && c.value() instanceof DataValue.IntegerValue;
    }
    /** Editor-only shadow; never lowered as a register value. The number is retained for switching back to Number. */
    static DataValue positionShadow(ComputationGraph.ListPosition position, DataValue number) {
        return new DataValue.RecordValue(Map.of("scratch_list_position", new DataValue.TextValue(position.choice().name()),
            "number", number instanceof DataValue.IntegerValue ? number : ONE));
    }
    static Optional<ModeDefault> modeDefault(DataValue value) {
        if (value instanceof DataValue.RecordValue record && record.fields().size() == 2
                && record.fields().get("scratch_list_position") instanceof DataValue.TextValue token
                && record.fields().get("number") instanceof DataValue.IntegerValue number) {
            for (var choice : ComputationGraph.ListPosition.Choice.values())
                if (choice.name().equals(token.value())) return Optional.of(new ModeDefault(choice, number));
        }
        return Optional.empty();
    }
    static Set<String> ownedSelectors(ComputationGraph graph) {
        if (graph.nodes().values().stream().noneMatch(ComputationGraph.ListPosition.class::isInstance)) return Set.of();
        var sockets = sockets(graph);
        var selectors = new HashSet<String>();
        sockets.forEach((id, socket) -> {
            if (graph.nodes().get(id) instanceof ComputationGraph.ListPosition position
                && position.source().equals(socket.source()) && position.insertion() == socket.insertion())
                selectors.add(id);
        });
        if (selectors.isEmpty()) return selectors;
        var occurrences = new HashMap<String, Integer>();
        graph.outputs().values().forEach(id -> occurrences.merge(id, 1, Integer::sum));
        graph.nodes().values().forEach(node -> node.dependencies().forEach(id -> occurrences.merge(id, 1, Integer::sum)));
        selectors.removeIf(id -> occurrences.getOrDefault(id, 0) != 1
            || occurrences.getOrDefault(sockets.get(id).normalization(), 0) != 1 || graph.outputs().containsValue(id));
        return selectors;
    }
    static Edit choose(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows,
                       String field, ComputationGraph.ListPosition.Choice choice) {
        var socket = Objects.requireNonNull(sockets(graph).get(field), "Not a list position field");
        var before = graph.nodes().get(field);
        if (!hasMenu(before)) throw new IllegalArgumentException("Detach the value reporter before changing the position field");
        FlowReporterEdits.requireUnshared(graph, field);
        var defaults = new LinkedHashMap<>(shadows);
        ComputationGraph.Node replacement;
        if (choice == null) {
            if (before instanceof ComputationGraph.Constant) return new Edit(graph, bindings, shadows);
            var value = defaults.remove(field);
            replacement = new ComputationGraph.Constant(value instanceof DataValue.IntegerValue ? value : ONE);
        } else {
            if (before instanceof ComputationGraph.Constant constant) defaults.put(field, constant.value());
            replacement = new ComputationGraph.ListPosition(socket.source(), choice, socket.insertion());
        }
        var nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put(field, replacement);
        return new Edit(new ComputationGraph(graph.inputs(), nodes, graph.outputs()), bindings, defaults);
    }
    static InlineGraphEdits.Edit placeReporter(ComputationGraph graph, FlowCallBindings bindings, Map<String, DataValue> shadows,
                                               String field, InlineGraphEdits.Source reporter) {
        if (graph.nodes().get(field) instanceof ComputationGraph.ListPosition && sockets(graph).containsKey(field)) {
            // Wrapping an expression inherits the numeric shadow, not a selector that aliases the whole list.
            var number = choose(graph, bindings, shadows, field, null);
            graph = number.graph(); bindings = number.bindings();
        }
        return InlineGraphEdits.replace(graph, bindings, field, reporter);
    }
}
