package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Scratch write-back sugar over typed local assignment or a public-cell transaction. */
final class FlowNamedLists {
    private static final String PREFIX = "scratch_named_list_";
    static final List<DataType.Scalar> ELEMENTS = List.of(
        DataType.Scalar.INTEGER, DataType.Scalar.DECIMAL, DataType.Scalar.TEXT, DataType.Scalar.BOOLEAN);

    record Prototype(FlowProgram.Evaluate step, ComputationGraph graph) {}
    record Target(ListOperation operation, String input, String local, DataType.ListType type,
                  String itemNode, String indexNode, boolean shared) {}
    record Retargeted(Prototype block, Optional<ScratchValueLayout.Reporter> parked) {}
    record ScopedEdit(FlowProgram.Step step, ComputationGraph graph, Optional<ScratchValueLayout.Reporter> parked) {}

    static Map<String, DataType.ListType> targets(Map<String, DataType> locals) {
        var result = new TreeMap<String, DataType.ListType>();
        locals.forEach((name, type) -> {
            if (type instanceof DataType.ListType list && ELEMENTS.contains(list.element())) result.put(name, list);
        });
        return result;
    }

    static Prototype append(String source, String local, DataType.ListType type, Set<String> locals) {
        return prototype(ListOperation.APPEND, source, local, type, locals);
    }

    static Prototype prototype(ListOperation operation, String source, String local, DataType.ListType type, Set<String> locals) {
        String input = source + "_list";
        while (locals.contains(input)) input += "_";
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        var ports = new LinkedHashMap<String, String>();
        nodes.put("list", new ComputationGraph.Input(input));
        ports.put("source", "list");
        // An empty slice keeps the declared element type and uses the ordinary write-back path.
        if (operation == ListOperation.SLICE) {
            nodes.put("zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
            ports.put("start", "zero");
            ports.put("end", "zero");
        }
        if (operation.inputs().containsKey("index")) {
            var one = new ComputationGraph.Constant(new DataValue.IntegerValue(1));
            nodes.put("index", one);
            nodes.put("one", one);
            nodes.put("zero_based_index", new ComputationGraph.Scalar(ScalarOperation.SUBTRACT_INTEGER, Map.of("left", "index", "right", "one")));
            ports.put("index", "zero_based_index");
        }
        if (operation.inputs().containsKey("value")) {
            nodes.put("item", new ComputationGraph.Constant(InlineGraphEdits.defaultValue(type.element())));
            ports.put("value", "item");
        }
        nodes.put("value", new ComputationGraph.ListEdit(operation, ports));
        var graph = new ComputationGraph(Map.of(input, type), nodes, Map.of("value", "value"));
        var step = new FlowProgram.Evaluate(source, expression(operation), new FlowCallBindings(Map.of(input, local), Map.of("value", local)));
        return new Prototype(step, graph);
    }

    private static String expression(ListOperation operation) { return PREFIX + (operation == ListOperation.SLICE ? "clear" : operation.name().toLowerCase(Locale.ROOT)); }

    static Optional<ListOperation> operation(FlowBlockPalette.Kind kind) {
        if (kind == null) return Optional.empty();
        return switch (kind) {
            case LIST_APPEND_LOCAL -> Optional.of(ListOperation.APPEND);
            case LIST_INSERT_LOCAL -> Optional.of(ListOperation.INSERT);
            case LIST_REMOVE_LOCAL -> Optional.of(ListOperation.REMOVE);
            case LIST_REPLACE_LOCAL -> Optional.of(ListOperation.REPLACE);
            case LIST_CLEAR -> Optional.of(ListOperation.SLICE);
            default -> Optional.empty();
        };
    }

    static Optional<Target> target(FlowProgram.Step step, ComputationGraph graph) {
        return target(step, graph, null);
    }

    static Optional<Target> target(FlowProgram.Step step, ComputationGraph graph, DataType publicType) {
        if (graph != null && step instanceof FlowProgram.Shared shared && shared.listResult().isPresent()
            && graph.nodes().get(graph.outputs().get("value")) instanceof ComputationGraph.ListEdit edit
            && namedOperation(graph, edit)
            && graph.nodes().get(edit.arguments().get("source")) instanceof ComputationGraph.ReadMemory read
            && shared.cell().equals(read.cell())) {
            String index = itemIndex(graph, edit);
            if (edit.operation().inputs().containsKey("index") && index == null) return Optional.empty();
            return Optional.of(new Target(edit.operation(), null, shared.cell(), publicType instanceof DataType.ListType type ? type : null,
                edit.arguments().get("value"), index, true));
        }
        if (!(step instanceof FlowProgram.Evaluate call) || !call.expression().startsWith(PREFIX) || graph == null)
            return Optional.empty();
        return call.bindings().flatMap(bindings -> target(graph, bindings))
            .filter(target -> expression(target.operation()).equals(call.expression()));
    }

    static Optional<Target> target(ComputationGraph graph, FlowCallBindings bindings) {
        if (graph.nodes().get(graph.outputs().get("value")) instanceof ComputationGraph.ListEdit edit
            && namedOperation(graph, edit)
            && graph.nodes().get(edit.arguments().get("source")) instanceof ComputationGraph.Input input
            && graph.inputs().get(input.name()) instanceof DataType.ListType type) {
            String local = bindings.returns().get("value");
            if (local == null || !local.equals(bindings.arguments().get(input.name()))) return Optional.empty();
            String index = itemIndex(graph, edit);
            if (edit.operation().inputs().containsKey("index") && index == null) return Optional.empty();
            return Optional.of(new Target(edit.operation(), input.name(), local, type, edit.arguments().get("value"), index, false));
        }
        return Optional.empty();
    }

    private static boolean namedOperation(ComputationGraph graph, ComputationGraph.ListEdit edit) {
        if (edit.operation() != ListOperation.SLICE) return true;
        var zero = new ComputationGraph.Constant(new DataValue.IntegerValue(0));
        return zero.equals(graph.nodes().get(edit.arguments().get("start")))
            && zero.equals(graph.nodes().get(edit.arguments().get("end")));
    }
    static String helpKey(FlowBlockPalette.Kind kind) {
        return switch (kind) {
            case LIST_APPEND_LOCAL -> "named_list_append_hint";
            case LIST_CLEAR -> "named_list_clear_hint";
            default -> "named_list_index_hint";
        };
    }
    private static String itemIndex(ComputationGraph graph, ComputationGraph.ListEdit edit) {
        return ScratchListPositions.field(graph, edit.arguments().get("index"));
    }

    static ScopedEdit scopedPrototype(ListOperation op, String id, String name, DataType.ListType type, boolean shared, Set<String> locals) {
        var block = prototype(op, id, name, type, locals);
        if (!shared) return new ScopedEdit(block.step(), block.graph(), Optional.empty());
        return chooseScope(block.step(), block.graph(), target(block.step(), block.graph()).orElseThrow(), name, type, true, locals,
            Map.of(), new ProgramDocument.Position(0, 0));
    }

    static ScopedEdit chooseScope(FlowProgram.Step step, ComputationGraph graph, Target old, String name,
                                   DataType.ListType type, boolean shared, Set<String> locals,
                                   Map<String, DataValue> shadows, ProgramDocument.Position position) {
        if (!old.shared() && !shared) {
            var local = choose((FlowProgram.Evaluate)step, graph, old, name, type, shadows, position);
            return new ScopedEdit(local.block().step(), local.block().graph(), local.parked());
        }
        var bindings = FlowInlineValues.bindings(step);
        Optional<ScratchValueLayout.Reporter> parked = Optional.empty();
        if (old.itemNode() != null && !Objects.equals(old.type(), type)) {
            FlowReporterEdits.requireUnshared(graph, old.itemNode());
            if (old.type() == null || !new ComputationGraph.Constant(InlineGraphEdits.defaultValue(old.type().element())).equals(graph.nodes().get(old.itemNode())))
                parked = Optional.of(FlowReporterEdits.take(graph, bindings, shadows, old.itemNode(), position));
            var edit = InlineGraphEdits.replace(graph, bindings, old.itemNode(),
                new InlineGraphEdits.Literal(InlineGraphEdits.defaultValue(type.element())));
            graph = edit.graph(); bindings = edit.bindings();
        }
        var nodes = new LinkedHashMap<>(graph.nodes());
        var inputs = new LinkedHashMap<>(graph.inputs());
        var args = new LinkedHashMap<>(bindings.arguments());
        String root = graph.outputs().get("value");
        String source = ((ComputationGraph.ListEdit)nodes.get(root)).arguments().get("source");
        FlowReporterEdits.requireUnshared(graph, source);
        String id = step.sourceId();
        FlowProgram.Step replacement;
        if (shared) {
            nodes.put(source, new ComputationGraph.ReadMemory(name));
            var cleaned = InlineGraphEdits.prune(new ComputationGraph(inputs, nodes, graph.outputs()), new FlowCallBindings(args, Map.of()));
            String result = id + "_list_result";
            while (locals.contains(result)) result += "_";
            if (step instanceof FlowProgram.Shared previous) result = previous.listResult().orElseThrow();
            replacement = new FlowProgram.Shared(id, name, new FlowInput.Expression(id, "value", cleaned.bindings().arguments()), false, Optional.of(result));
            return new ScopedEdit(replacement, cleaned.graph(), parked);
        }
        String input = id + "_list";
        while (locals.contains(input) || inputs.containsKey(input)) input += "_";
        inputs.put(input, type); args.put(input, name);
        nodes.put(source, new ComputationGraph.Input(input));
        replacement = new FlowProgram.Evaluate(id, expression(old.operation()), new FlowCallBindings(args, Map.of("value", name)));
        return new ScopedEdit(replacement, new ComputationGraph(inputs, nodes, graph.outputs()), parked);
    }

    static FlowProgram.Evaluate retarget(FlowProgram.Evaluate step, Target old, String local) {
        var args = new LinkedHashMap<>(step.bindings().orElseThrow().arguments());
        args.put(old.input(), local);
        return new FlowProgram.Evaluate(step.sourceId(), step.expression(), new FlowCallBindings(args, Map.of("value", local)));
    }

    static Retargeted choose(FlowProgram.Evaluate step, ComputationGraph graph, Target old, String local,
                             DataType.ListType type, Map<String, DataValue> shadows, ProgramDocument.Position position) {
        if (old.type().equals(type)) return new Retargeted(new Prototype(retarget(step, old, local), graph), Optional.empty());

        var bindings = step.bindings().orElseThrow();
        Optional<ScratchValueLayout.Reporter> parked = Optional.empty();
        if (old.itemNode() != null) {
            FlowReporterEdits.requireUnshared(graph, old.itemNode());
            var oldDefault = new ComputationGraph.Constant(InlineGraphEdits.defaultValue(old.type().element()));
            if (!oldDefault.equals(graph.nodes().get(old.itemNode())))
                parked = Optional.of(FlowReporterEdits.take(graph, bindings, shadows, old.itemNode(), position));
            var edit = InlineGraphEdits.replace(graph, bindings, old.itemNode(),
                new InlineGraphEdits.Literal(InlineGraphEdits.defaultValue(type.element())));
            graph = edit.graph();
            bindings = edit.bindings();
        }
        var inputs = new LinkedHashMap<>(graph.inputs());
        inputs.put(old.input(), type);
        var retyped = new ComputationGraph(inputs, graph.nodes(), graph.outputs());
        var cleaned = new FlowProgram.Evaluate(step.sourceId(), step.expression(), bindings);
        return new Retargeted(new Prototype(retarget(cleaned, old, local), retyped), parked);
    }

    private FlowNamedLists() {}
}
