package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;

/** Shared immutable layout and layout schema; executable graphs never contain canvas positions. */
final class GraphLayouts {
    private GraphLayouts() {}
    static Map<String, Map<String, ProgramDocument.Position>> copy(Map<String, Set<String>> known,
            Map<String, Map<String, ProgramDocument.Position>> positions) {
        var copy = new LinkedHashMap<String, Map<String, ProgramDocument.Position>>();
        positions.forEach((graph, nodes) -> {
            Set<String> knownNodes = known.get(graph);
            if (knownNodes == null) throw new IllegalArgumentException("Unknown graph layout: " + graph);
            var nodeCopy = new LinkedHashMap<String, ProgramDocument.Position>();
            Objects.requireNonNull(nodes).forEach((node, position) -> {
                if (!knownNodes.contains(node)) throw new IllegalArgumentException("Unknown graph node layout: " + node);
                nodeCopy.put(node, Objects.requireNonNull(position));
            });
            copy.put(graph, Collections.unmodifiableMap(nodeCopy));
        });
        return Collections.unmodifiableMap(copy);
    }
    static DataValue.ListValue encode(Map<String, Map<String, ProgramDocument.Position>> positions) {
        var layouts = new ArrayList<DataValue>();
        positions.forEach((graph, nodes) -> {
            var values = new ArrayList<DataValue>();
            nodes.forEach((node, position) -> values.add(new DataValue.RecordValue(Map.of(
                "node", new DataValue.TextValue(node), "x", new DataValue.DecimalValue(position.x()), "y", new DataValue.DecimalValue(position.y())))));
            layouts.add(new DataValue.RecordValue(Map.of("graph", new DataValue.TextValue(graph), "positions", new DataValue.ListValue(values))));
        });
        return new DataValue.ListValue(layouts);
    }
    static Map<String, Map<String, ProgramDocument.Position>> decode(DataValue value) {
        var layouts = new LinkedHashMap<String, Map<String, ProgramDocument.Position>>();
        for (var item : list(value)) {
            var layout = exactRecord(item, Set.of("graph", "positions"));
            var positions = new LinkedHashMap<String, ProgramDocument.Position>();
            for (var entry : list(layout.get("positions"))) {
                var position = exactRecord(entry, Set.of("node", "x", "y"));
                if (positions.putIfAbsent(string(position.get("node")), new ProgramDocument.Position(decimal(position.get("x")), decimal(position.get("y")))) != null)
                    throw invalid("Duplicate node layout");
            }
            if (layouts.putIfAbsent(string(layout.get("graph")), positions) != null) throw invalid("Duplicate graph layout");
        }
        return layouts;
    }
    static Map<String, DataValue> exactRecord(DataValue value, Set<String> names) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(names)) throw invalid("Wrong document fields");
        return record.fields();
    }
    static List<DataValue> list(DataValue value) {
        if (value instanceof DataValue.ListValue list) return list.values(); throw invalid("Expected list");
    }
    static String string(DataValue value) {
        if (value instanceof DataValue.TextValue text) return text.value(); throw invalid("Expected text");
    }
    static long integer(DataValue value) {
        if (value instanceof DataValue.IntegerValue integer) return integer.value(); throw invalid("Expected integer");
    }
    static double decimal(DataValue value) {
        if (value instanceof DataValue.DecimalValue decimal) return decimal.value(); throw invalid("Expected decimal");
    }
    static ArchiveException invalid(String message) { return new ArchiveException(ArchiveException.Code.INVALID_FORMAT, message); }
}
