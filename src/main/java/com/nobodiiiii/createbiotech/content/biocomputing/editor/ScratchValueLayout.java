package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.editor.GraphLayouts.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.GraphArchiveCodec;
import java.util.*;

/** Non-executable socket shadows and unattached reporters. Not part of a ProgramArchive. */
record ScratchValueLayout(Map<String, Map<String, DataValue>> shadows, List<Reporter> parked) {
    static final ScratchValueLayout EMPTY = new ScratchValueLayout(Map.of(), List.of());

    /** Editor-only identity: a parked argument must not capture a same-name local in another stack. */
    record ProcedureScope(String source, ModuleDefinition.Key key) {
        ProcedureScope {
            if (source.isBlank()) throw invalid("Missing reporter procedure source");
            Objects.requireNonNull(key);
        }
    }

    record Reporter(ProgramDocument.Position position, ComputationGraph graph, Map<String, String> arguments,
                    Map<String, DataValue> shadows, Optional<ProcedureScope> procedureScope) {
        Reporter(ProgramDocument.Position position, ComputationGraph graph, Map<String, String> arguments, Map<String, DataValue> shadows) {
            this(position, graph, arguments, shadows, Optional.empty());
        }
        Reporter {
            Objects.requireNonNull(position);
            Objects.requireNonNull(graph);
            Objects.requireNonNull(procedureScope);
            arguments = DataValue.immutableMap(arguments);
            shadows.values().forEach(LocalReferences::portable);
            graph.nodes().values().forEach(node -> {
                if (node instanceof ComputationGraph.Constant constant) LocalReferences.portable(constant.value());
            });
            shadows = DataValue.immutableMap(shadows);
            if (!arguments.keySet().equals(graph.inputs().keySet()) || !graph.outputs().keySet().equals(Set.of("value"))
                    || !graph.nodes().containsKey(graph.outputs().get("value")) || !shadows.keySet().stream().allMatch(graph.nodes()::containsKey))
                throw new IllegalArgumentException("Invalid parked reporter");
        }
        Reporter at(ProgramDocument.Position next) { return new Reporter(next, graph, arguments, shadows, procedureScope); }
        Reporter scoped(ProcedureScope scope) { return new Reporter(position, graph, arguments, shadows, Optional.of(scope)); }
        boolean isRoot(String node) { return node.equals(graph.outputs().get("value")); }
        Reporter subtree(String node) {
            if (isRoot(node)) return this;
            var fragment = FlowReporterEdits.take(graph, new FlowCallBindings(arguments, Map.of()), shadows, node, position);
            return procedureScope.filter(scope -> !fragment.arguments().isEmpty()).map(fragment::scoped).orElse(fragment);
        }
        Reporter restoreChild(String node, DataValue fallback) {
            var edit = FlowReporterEdits.restore(graph, new FlowCallBindings(arguments, Map.of()), shadows, node, fallback);
            return withEdit(edit, procedureScope);
        }
        Reporter attachChild(String node, Reporter carried) {
            if (isRoot(node)) throw new IllegalArgumentException("The root of a parked expression is not a socket");
            var otherScope = carried.procedureScope();
            if (otherScope.isPresent() && (procedureScope.isPresent() && !procedureScope.equals(otherScope)
                    || procedureScope.isEmpty() && !arguments.isEmpty())
                || procedureScope.isPresent() && otherScope.isEmpty() && !carried.arguments().isEmpty())
                throw new IllegalArgumentException("Reporter inputs belong to a different procedure scope");
            var edit = FlowReporterEdits.attach(graph, new FlowCallBindings(arguments, Map.of()), shadows, node, carried);
            return withEdit(edit, procedureScope.or(() -> otherScope));
        }
        private Reporter withEdit(FlowReporterEdits.Edit edit, Optional<ProcedureScope> scope) {
            var keptShadows = new LinkedHashMap<>(edit.shadows());
            keptShadows.keySet().retainAll(edit.graph().nodes().keySet());
            var used = edit.bindings().arguments();
            return new Reporter(position, edit.graph(), used, keptShadows, scope.filter(unused -> !used.isEmpty()));
        }
        Reporter withLiteral(String node, DataValue value) {
            if (!(graph.nodes().get(node) instanceof ComputationGraph.Constant))
                throw new IllegalArgumentException("Not an editable literal");
            var nodes = new LinkedHashMap<>(graph.nodes());
            nodes.put(node, new ComputationGraph.Constant(value));
            return new Reporter(position, new ComputationGraph(graph.inputs(), nodes, graph.outputs()), arguments, shadows, procedureScope);
        }
        Reporter withConstructor(InlineGraphEdits.Edit edit) {
            var defaults = new LinkedHashMap<>(shadows);
            defaults.keySet().removeIf(node -> !edit.graph().nodes().containsKey(node)
                || edit.graph().nodes().get(node) instanceof ComputationGraph.Constant);
            return withEdit(new FlowReporterEdits.Edit(edit.graph(), edit.bindings(), defaults), procedureScope);
        }
    }

    ScratchValueLayout {
        var immutable = new LinkedHashMap<String, Map<String, DataValue>>();
        shadows.forEach((id, values) -> {
            values.values().forEach(LocalReferences::portable);
            if (!values.isEmpty()) immutable.put(id, DataValue.immutableMap(values));
        });
        shadows = DataValue.immutableMap(immutable);
        parked = List.copyOf(parked);
    }
    ScratchValueLayout withParked(List<Reporter> reporters) { return new ScratchValueLayout(shadows, reporters); }
    Map<String, DataValue> forBlock(String id) { return shadows.getOrDefault(id, Map.of()); }
    ScratchValueLayout with(String id, Map<String, DataValue> defaults, List<Reporter> reporters) {
        var next = new LinkedHashMap<>(shadows);
        next.put(id, defaults);
        return new ScratchValueLayout(next, reporters);
    }
    ScratchValueLayout retain(Map<String, ComputationGraph> graphs) {
        var next = new LinkedHashMap<String, Map<String, DataValue>>();
        shadows.forEach((id, defaults) -> {
            var graph = graphs.get(id);
            if (graph == null) return;
            var kept = new LinkedHashMap<String, DataValue>();
            defaults.forEach((node, value) -> {
                var current = graph.nodes().get(node);
                if (current != null && !(current instanceof ComputationGraph.Constant)) kept.put(node, value);
            });
            next.put(id, kept);
        });
        return new ScratchValueLayout(next, parked);
    }

    DataValue encode() {
        var values = new LinkedHashMap<String, DataValue>();
        shadows.forEach((block, slots) -> values.put(block, new DataValue.RecordValue(slots)));
        var reporters = new ArrayList<DataValue>();
        boolean scoped = parked.stream().anyMatch(r -> r.procedureScope().isPresent());
        for (var reporter : parked) {
            var arguments = new LinkedHashMap<String, DataValue>();
            reporter.arguments().forEach((name, local) -> arguments.put(name, new DataValue.TextValue(local)));
            var fields = new LinkedHashMap<String, DataValue>();
            fields.put("position", FlowBlockLayout.position(reporter.position()));
            fields.put("graph", GraphArchiveCodec.encode(reporter.graph()));
            fields.put("arguments", new DataValue.RecordValue(arguments));
            fields.put("shadows", new DataValue.RecordValue(reporter.shadows()));
            if (scoped) fields.put("procedure", reporter.procedureScope().<DataValue>map(scope -> new DataValue.RecordValue(Map.of(
                "source", new DataValue.TextValue(scope.source()), "key", new DataValue.TextValue(scope.key().id()),
                "revision", new DataValue.IntegerValue(scope.key().revision())))).orElse(DataValue.NullValue.INSTANCE));
            reporters.add(new DataValue.RecordValue(fields));
        }
        return new DataValue.RecordValue(Map.of("version", new DataValue.IntegerValue(scoped ? 2 : 1),
            "graph_version", new DataValue.IntegerValue(GraphArchiveCodec.encodingVersion(parked.stream().map(Reporter::graph).toList())),
            "shadows", new DataValue.RecordValue(values), "parked", new DataValue.ListValue(reporters)));
    }

    static ScratchValueLayout decode(DataValue value) {
        var fields = exactRecord(value, Set.of("version", "graph_version", "shadows", "parked"));
        long version = integer(fields.get("version"));
        if (version != 1 && version != 2) throw invalid("Unknown Scratch value layout version");
        long graphVersion = integer(fields.get("graph_version"));
        if (graphVersion < 1 || graphVersion > GraphArchiveCodec.VERSION) throw invalid("Unknown reporter graph version");
        var shadows = new LinkedHashMap<String, Map<String, DataValue>>();
        record(fields.get("shadows")).forEach((id, defaults) -> shadows.put(id, record(defaults)));
        var parked = new ArrayList<Reporter>();
        for (var item : list(fields.get("parked"))) {
            var reporter = exactRecord(item, version == 1 ? Set.of("position", "graph", "arguments", "shadows")
                : Set.of("position", "graph", "arguments", "shadows", "procedure"));
            var arguments = new LinkedHashMap<String, String>();
            record(reporter.get("arguments")).forEach((name, local) -> arguments.put(name, string(local)));
            var scope = Optional.<ProcedureScope>empty();
            if (version == 2 && reporter.get("procedure") != DataValue.NullValue.INSTANCE) {
                var owner = exactRecord(reporter.get("procedure"), Set.of("source", "key", "revision"));
                scope = Optional.of(new ProcedureScope(string(owner.get("source")),
                    new ModuleDefinition.Key(string(owner.get("key")), integer(owner.get("revision")))));
            }
            parked.add(new Reporter(FlowBlockLayout.readPosition(reporter.get("position")),
                GraphArchiveCodec.decode(reporter.get("graph"), graphVersion), arguments, record(reporter.get("shadows")), scope));
        }
        return new ScratchValueLayout(shadows, parked);
    }

    private static Map<String, DataValue> record(DataValue value) {
        if (value instanceof DataValue.RecordValue record) return record.fields();
        throw invalid("Expected Scratch value layout record");
    }
}
