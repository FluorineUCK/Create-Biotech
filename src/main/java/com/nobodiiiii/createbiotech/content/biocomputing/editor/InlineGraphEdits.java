package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import java.util.*;

/** Immutable graph edits. Type checking and execution belong to the ordinary compiler/machine. */
final class InlineGraphEdits {
    sealed interface Source {}
    record Timer() implements Source {}
    record Answer() implements Source {}
    record KeyDown() implements Source {}
    /** Editor drag source, not a new IR node. Resolve against the owning definition before insertion. */
    record ProcedureParameter(ScratchValueLayout.ProcedureScope owner, String name, DataType type) implements Source {
        Variable variable() { return new Variable(name, List.of(), type); }
    }
    record ValueEquality() implements Source {}
    record Operator(ScalarOperation operation) implements Source {}
    record RandomRange(boolean decimal) implements Source {
        DataType.Scalar type() { return decimal ? DataType.Scalar.DECIMAL : DataType.Scalar.INTEGER; }
        DataValue bound(long n) { return decimal ? new DataValue.DecimalValue(n) : new DataValue.IntegerValue(n); }
    }
    record TextOperator(TextOperation operation) implements Source {}
    record ProcedureResult(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Procedure procedure,
                           String result) implements Source {}
    /** Scratch slot uses 1-based numbering; stored DynamicIndex is ordinary 0-based IR. */
    record ListConstruction() implements Source {
    }
    record RecordConstruction() implements Source {
    }
    record ListItem() implements Source {}
    record ListAppend() implements Source {}
    record ListSearch(boolean contains) implements Source {}
    record Literal(DataValue value) implements Source {}
    record Variable(String name, List<String> path, DataType type) implements Source {
        Variable { path = List.copyOf(path); }
        String label() { return name + (path.isEmpty() ? "" : "." + String.join(".", path)); }
    }
    record PublicVariable(String name, List<String> path, DataType type) implements Source {
        PublicVariable { path = List.copyOf(path); }
        String label() { return name + (path.isEmpty() ? "" : "." + String.join(".", path)); }
    }
    /** wrapsPrevious describes whether replace() kept the old root as a child of the incoming reporter. */
    record Edit(ComputationGraph graph, FlowCallBindings bindings, boolean wrapsPrevious) {
        Edit(ComputationGraph graph, FlowCallBindings bindings) { this(graph, bindings, false); }
    }
    static Edit replace(ComputationGraph graph, FlowCallBindings bindings, String at, Source source) {
        var nodes = new LinkedHashMap<>(graph.nodes()); var inputs = new LinkedHashMap<>(graph.inputs());
        var arguments = new LinkedHashMap<>(bindings.arguments());
        var previous = Objects.requireNonNull(nodes.get(at), "Unknown value slot");
        String inherited = null;
        if (source instanceof Timer) {
            nodes.put(at, new ComputationGraph.Timer());
        } else if (source instanceof Answer) {
            nodes.put(at,new ComputationGraph.Answer());
        } else if (source instanceof KeyDown) {
            nodes.put(at,new ComputationGraph.KeyDown("space"));
        } else if (source instanceof ListConstruction || source instanceof RecordConstruction) {
            String child = next(nodes);
            nodes.put(child, previous);
            inherited = child;
            nodes.put(at, source instanceof ListConstruction?new ComputationGraph.MakeList(List.of(child)):new ComputationGraph.MakeRecord(Map.of("field", child)));
        } else if (source instanceof ListAppend) {
            String list = next(nodes); nodes.put(list, previous instanceof ComputationGraph.Constant c && !(c.value() instanceof DataValue.ListValue)
                ? new ComputationGraph.Constant(new DataValue.ListValue(List.of())) : previous);
            String item = next(nodes); nodes.put(item, new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
            nodes.put(at, new ComputationGraph.ListEdit(ListOperation.APPEND, Map.of("source", list, "value", item)));
            inherited = list;
        } else if (source instanceof ListSearch search) {
            String list = next(nodes);
            nodes.put(list, new ComputationGraph.Constant(new DataValue.ListValue(List.of())));
            String item = next(nodes);
            nodes.put(item, previous instanceof ComputationGraph.Constant constant && !(constant.value() instanceof DataValue.ListValue)
                ? previous : new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
            nodes.put(at, new ComputationGraph.ListSearch(list, item, search.contains()));
            inherited = item;
        } else if (source instanceof ListItem) {
            String list = next(nodes); nodes.put(list, previous instanceof ComputationGraph.Constant c && !(c.value() instanceof DataValue.ListValue)
                ? new ComputationGraph.Constant(new DataValue.ListValue(List.of())) : previous);
            String slot = next(nodes); nodes.put(slot, new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
            String one = next(nodes); nodes.put(one, new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
            String index = next(nodes); nodes.put(index, new ComputationGraph.Scalar(ScalarOperation.SUBTRACT_INTEGER, Map.of("left", slot, "right", one)));
            nodes.put(at, new ComputationGraph.DynamicIndex(list, index));
            inherited = list;
        } else if (source instanceof RandomRange range) {
            String from = next(nodes); nodes.put(from, new ComputationGraph.Constant(range.bound(1)));
            String through = next(nodes); nodes.put(through, new ComputationGraph.Constant(range.bound(10)));
            nodes.put(at, new ComputationGraph.RandomRange(from, through, range.decimal()));
        } else if (source instanceof Operator || source instanceof TextOperator || source instanceof ValueEquality) {
            var signature = source instanceof ValueEquality ? Map.of("left", DataType.Scalar.ANY, "right", DataType.Scalar.ANY) : source instanceof Operator operator ? operator.operation().inputs() : ((TextOperator)source).operation().inputs();
            String child = next(nodes); nodes.put(child, previous);
            var ports = new LinkedHashMap<String, String>(); boolean first = true;
            for (var port : signature.entrySet()) {
                String id = child;
                if (first && previous instanceof ComputationGraph.Constant constant && !port.getValue().accepts(constant.value()))
                    nodes.put(child,new ComputationGraph.Constant(defaultValue(port.getValue())));
                if (!first) {
                    id = next(nodes);
                    var value = source instanceof TextOperator text && text.operation() == TextOperation.LETTER_OF && port.getKey().equals("index")
                        ? new DataValue.IntegerValue(1) : defaultValue(port.getValue());
                    nodes.put(id, new ComputationGraph.Constant(value));
                }
                ports.put(port.getKey(), id); first = false;
            }
            inherited = child;
            nodes.put(at, source instanceof ValueEquality ? new ComputationGraph.ValueEquals(ports.get("left"), ports.get("right")) : source instanceof Operator operator ? new ComputationGraph.Scalar(operator.operation(), ports)
                : new ComputationGraph.Text(((TextOperator)source).operation(), ports));
        } else if (source instanceof ProcedureResult call) {
            var ports = new LinkedHashMap<String, String>();
            new TreeMap<>(call.procedure().inputs()).forEach((name, type) -> {
                String id = next(nodes);
                var value = com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.reserved(call.procedure().key())
                    ? com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions.binding(call.procedure().key()).orElseThrow().command().defaults().get(name)
                    : defaultValue(type);
                nodes.put(id, new ComputationGraph.Constant(value));
                ports.put(name, id);
            });
            nodes.put(at, new ComputationGraph.ProcedureValue(call.procedure().key(), call.result(), ports));
        } else if (source instanceof Literal literal) nodes.put(at, new ComputationGraph.Constant(literal.value()));
        else if (source instanceof Variable variable) {
            // A graph input may already use this name as an alias for a different local.
            String alias = variable.name();
            for (int suffix = 0; inputs.containsKey(alias) && !(variable.type().equals(inputs.get(alias))
                    && variable.name().equals(arguments.get(alias))); suffix++) alias = "local_" + suffix;
            inputs.put(alias, variable.type()); arguments.put(alias, variable.name());
            ComputationGraph.Node node = new ComputationGraph.Input(alias);
            for (String field : variable.path()) { String id = next(nodes); nodes.put(id, node); node = new ComputationGraph.Field(id, field); }
            nodes.put(at, node);
        }
        else if (source instanceof PublicVariable variable) {
            // This is a compiler expression, not a same-name task input or a graph-runtime read.
            ComputationGraph.Node node = new ComputationGraph.ReadMemory(variable.name());
            for (String field : variable.path()) { String id = next(nodes); nodes.put(id, node); node = new ComputationGraph.Field(id, field); }
            nodes.put(at, node);
        }
        var reachable = new HashSet<String>(); var pending = new ArrayDeque<>(graph.outputs().values());
        while (!pending.isEmpty()) { String id = pending.removeFirst(); if (reachable.add(id)) pending.addAll(Objects.requireNonNull(nodes.get(id)).dependencies()); }
        nodes.keySet().retainAll(reachable);
        var usedInputs = new HashSet<String>(); nodes.values().forEach(node -> { if (node instanceof ComputationGraph.Input input) usedInputs.add(input.name()); });
        inputs.keySet().retainAll(usedInputs); arguments.keySet().retainAll(usedInputs);
        return new Edit(new ComputationGraph(inputs, nodes, graph.outputs()), new FlowCallBindings(arguments, bindings.returns()),
            inherited != null && nodes.get(inherited) == previous);
    }
    /** Temporary row form; only finish() may turn data keys into a map. */
    static final class ConstructorDraft {
        record Row(String key, String child) {
        }
        private ComputationGraph graph;
        private FlowCallBindings bindings;
        private final String at;
        private final boolean record;
        private final List<Row> rows = new ArrayList<>();
        ConstructorDraft(ComputationGraph graph, FlowCallBindings bindings, String at) {
            this.graph = graph;
            this.bindings = bindings;
            this.at = at;
            var node = graph.nodes().get(at);
            record = node instanceof ComputationGraph.MakeRecord;
            if (record)((ComputationGraph.MakeRecord)node).fields().forEach((key, child)->rows.add(new Row(key, child)));
            else if (node instanceof ComputationGraph.MakeList list)list.elements().forEach(child->rows.add(new Row("", child)));
            else throw new IllegalArgumentException("Not a constructor");
        }
        boolean record() {
            return record;
        }
        List<Row> rows() {
            return List.copyOf(rows);
        }
        FlowCallBindings bindings() {
            return bindings;
        }
        ComputationGraph editableGraph() {
            var nodes = new LinkedHashMap<>(graph.nodes());
            nodes.put(at, new ComputationGraph.MakeList(rows.stream().map(Row::child).toList()));
            return new ComputationGraph(graph.inputs(), nodes, graph.outputs());
        }
        void accept(Edit edit) {
            graph = edit.graph();
            bindings = edit.bindings();
        }
        ComputationGraph preview(String root) {
            return new ComputationGraph(graph.inputs(), graph.nodes(), Map.of("value", root));
        }
        ComputationGraph.Node node(String id) {
            return graph.nodes().get(id);
        }
        ComputationGraph.Node node(int row) {
            return graph.nodes().get(rows.get(row).child());
        }
        void rename(int row, String key) {
            var old = rows.get(row);
            rows.set(row, new Row(key, old.child()));
        }
        void add() {
            var nodes = new LinkedHashMap<>(graph.nodes());
            String child = next(nodes);
            nodes.put(child, new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
            graph = new ComputationGraph(graph.inputs(), nodes, graph.outputs());
            String key = "field";
            int n = 1;
            var keys = rows.stream().map(Row::key).toList();
            while (keys.contains(key))key = "field" + n++;
            rows.add(new Row(record?key:"", child));
        }
        void remove(int row) {
            rows.remove(row);
        }
        void move(int row, int delta) {
            int other = row + delta;
            if (other >= 0 && other<rows.size())Collections.swap(rows, row, other);
        }
        void replace(int row, Source source) {
            replaceNode(rows.get(row).child(), source);
        }
        void replaceNode(String node, Source source) {
            var nodes = new LinkedHashMap<>(graph.nodes());
            nodes.put(at, new ComputationGraph.MakeList(rows.stream().map(Row::child).toList()));
            var edit = InlineGraphEdits.replace(new ComputationGraph(graph.inputs(), nodes, graph.outputs()), bindings, node, source);
            graph = edit.graph();
            bindings = edit.bindings();
        }
        Edit finish() {
            var nodes = new LinkedHashMap<>(graph.nodes());
            ComputationGraph.Node result;
            if (record) {
                var fields = new LinkedHashMap<String, String>();
                for (var row:rows)if (fields.putIfAbsent(row.key(), row.child()) != null) throw new IllegalArgumentException("Duplicate record field: " + row.key());
                result = new ComputationGraph.MakeRecord(fields);
            }
            else result = new ComputationGraph.MakeList(rows.stream().map(Row::child).toList());
            nodes.put(at, result);
            // Reuse the ordinary reachability and binding cleanup, without altering a child ID.
            return prune(new ComputationGraph(graph.inputs(), nodes, graph.outputs()), bindings);
        }
    }
    static Edit prune(ComputationGraph graph, FlowCallBindings bindings) {
        var nodes = new LinkedHashMap<>(graph.nodes());
        var inputs = new LinkedHashMap<>(graph.inputs());
        var arguments = new LinkedHashMap<>(bindings.arguments());
        var reachable = new HashSet<String>();
        var pending = new ArrayDeque<>(graph.outputs().values());
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (reachable.add(id))pending.addAll(nodes.get(id).dependencies());
        }
        nodes.keySet().retainAll(reachable);
        var used = new HashSet<String>();
        nodes.values().forEach(n-> {
            if (n instanceof ComputationGraph.Input i)used.add(i.name());
        });
        inputs.keySet().retainAll(used);
        arguments.keySet().retainAll(used);
        return new Edit(new ComputationGraph(inputs, nodes, graph.outputs()), new FlowCallBindings(arguments, bindings.returns()));
    }
    static String next(Map<String, ?> nodes) { int n=1; while(nodes.containsKey("value_"+n)) n++; return "value_"+n; }
    static DataValue defaultValue(DataType type) {
        if (type == DataType.Scalar.INTEGER) return new DataValue.IntegerValue(0);
        if (type == DataType.Scalar.DECIMAL) return new DataValue.DecimalValue(0);
        if (type == DataType.Scalar.BOOLEAN) return new DataValue.BooleanValue(false);
        if (type == DataType.Scalar.TEXT) return new DataValue.TextValue("");
        if (type instanceof DataType.ListType) return new DataValue.ListValue(List.of());
        return DataValue.NullValue.INSTANCE;
    }
    static List<Source> palette(Map<String, DataType> locals) {
        return palette(locals, Map.of());
    }
    static boolean numeric(ScalarOperation operation) {
        return switch (operation.category()) {
            case INTEGER, DECIMAL, CONVERSION -> true;
            default -> false;
        };
    }
    static List<Source> palette(Map<String, DataType> locals, Map<String, DataType> publicVariables) {
        var result = new ArrayList<Source>();
        for (var operation : List.of(ScalarOperation.ADD_INTEGER, ScalarOperation.SUBTRACT_INTEGER, ScalarOperation.MULTIPLY_INTEGER,
                ScalarOperation.LESS_INTEGER, ScalarOperation.EQUAL_INTEGER, ScalarOperation.AND_BOOLEAN, ScalarOperation.OR_BOOLEAN, ScalarOperation.NOT_BOOLEAN, ScalarOperation.IS_NULL, ScalarOperation.IS_ERROR, ScalarOperation.LIST_LENGTH, ScalarOperation.REQUIRE_VALUE)) result.add(new Operator(operation));
        result.add(new TextOperator(TextOperation.CONCAT));
        result.add(new TextOperator(TextOperation.LETTER_OF));
        result.add(new TextOperator(TextOperation.LENGTH));
        result.add(new TextOperator(TextOperation.CONTAINS_IGNORE_CASE));
        result.add(new RandomRange(false));
        result.add(new RandomRange(true));
        for (var operation : ScalarOperation.values()) {
            if (!numeric(operation) || ScratchMathFunctions.OPTIONS.contains(operation) && operation != ScratchMathFunctions.DEFAULT) continue;
            var block = new Operator(operation);
            if (!result.contains(block)) result.add(block);
        }
        result.add(new Timer());
        result.add(new Answer());
        result.add(new KeyDown());
        result.add(new ValueEquality());
        result.add(new Operator(ScalarOperation.SAMPLE_RISING_INTEGER));
        result.add(new Operator(ScalarOperation.SAMPLE_RISING_DECIMAL));
        result.add(new Operator(ScalarOperation.EDGE_RISING_INTEGER));
        result.add(new Operator(ScalarOperation.EDGE_RISING_DECIMAL));
        result.add(new ListItem());
        result.add(new ListAppend());
        result.add(new ListSearch(false));
        result.add(new ListSearch(true));
        result.add(new ListConstruction());
        result.add(new RecordConstruction());
        for (var type : List.of(DataType.Scalar.INTEGER, DataType.Scalar.DECIMAL, DataType.Scalar.BOOLEAN, DataType.Scalar.TEXT)) result.add(new Literal(defaultValue(type)));
        locals.forEach((name, type) -> { result.add(new Variable(name, List.of(), type)); fields(result, name, List.of(), type, type, 0, false); });
        publicVariables.forEach((name, type) -> { result.add(new PublicVariable(name, List.of(), type)); fields(result, name, List.of(), type, type, 0, true); });
        return List.copyOf(result);
    }
    private static void fields(List<Source> result, String name, List<String> path, DataType root, DataType type, int depth, boolean shared) {
        // This is a convenience directory, not a limit on graph data or field access.
        if (depth >= 8 || !(type instanceof DataType.RecordType record)) return;
        record.fields().forEach((field, child) -> { var next = new ArrayList<>(path); next.add(field); result.add(shared ? new PublicVariable(name, next, root) : new Variable(name, next, root)); fields(result, name, next, root, child, depth+1, shared); });
    }
}
