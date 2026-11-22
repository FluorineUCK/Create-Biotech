package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;

/** Cached advisory inspection of a local draft. Missing types are NOT declared dynamic ANY. */
final class NodeTypeHints {
    private final ProgramWorkspace workspace;
    private final ProgramDraft draft;
    private final boolean pure;
    private final Map<ModuleDefinition.Key, ProgramCompiler.CompiledGraph> modules;
    private final Map<String, DataType> types = new LinkedHashMap<>();

    NodeTypeHints(ProgramWorkspace workspace, ProgramDraft draft, boolean pure, ProgramCompiler.InstalledProgram preview) {
        this.workspace = workspace; this.draft = draft; this.pure = pure;
        modules = preview == null ? Map.of() : preview.modules();
        var remaining = new HashMap<String, Integer>(); var dependents = new HashMap<String, List<String>>();
        var ready = new ArrayDeque<String>();
        for (var node : draft.nodes()) {
            if (node.definition() == null || !node.connections().keySet().equals(node.ports())) continue;
            var dependencies = new HashSet<>(node.connections().values());
            remaining.put(node.id(), dependencies.size()); if (dependencies.isEmpty()) ready.add(node.id());
            dependencies.forEach(source -> dependents.computeIfAbsent(source, ignored -> new ArrayList<>()).add(node.id()));
        }
        while (!ready.isEmpty()) {
            var id = ready.removeFirst(); var node = draft.node(id);
            try {
                types.put(id, ProgramCompiler.inferNode(NodePorts.bind(node.definition(), node.connections()), types,
                    draft.inputs(), draft.cells(), modules, pure, id));
            } catch (ComputationException invalid) { continue; }
            for (var dependent : dependents.getOrDefault(id, List.of()))
                if (remaining.compute(dependent, (ignored, count) -> count - 1) == 0) ready.add(dependent);
        }
    }
    DataType output(String node) { return types.get(node); }
    DataType type(ComputerGraphPorts.Socket socket) { return socket.output() ? output(socket.node()) : input(socket.node(), socket.input()); }
    boolean mismatched(String target, String port, String source) {
        var expected = input(target, port); var actual = output(source);
        return expected != null && actual != null && !expected.compatibleWith(actual);
    }

    /** A declaration hint, not a new acceptance rule; the compiler still validates the whole operation. */
    DataType input(String id, String port) {
        var node = draft.node(id).definition();
        return switch (node) {
            case ComputationGraph.Binary n -> ScalarOperation.forLegacy(n.operation()).inputs().get(port);
            case ComputationGraph.Scalar n -> n.operation().inputs().get(port);
            case ComputationGraph.Text n -> n.operation().inputs().get(port);
            case ComputationGraph.ListEdit edit -> edit.operation().inputs().get(port);
            case ComputationGraph.ListPosition ignored -> new DataType.ListType(DataType.Scalar.ANY);
            case ComputationGraph.ListSearch ignored -> port.equals("source") ? new DataType.ListType(DataType.Scalar.ANY) : DataType.Scalar.ANY;
            case ComputationGraph.RandomRange range -> range.type();
            case ComputationGraph.Timer ignored -> DataType.Scalar.DECIMAL;
            case ComputationGraph.Answer ignored -> DataType.Scalar.TEXT;
            case ComputationGraph.KeyDown ignored -> DataType.Scalar.BOOLEAN;
            case ComputationGraph.ValueEquals ignored -> DataType.Scalar.ANY;
            case ComputationGraph.Distinct ignored -> new DataType.ListType(DataType.Scalar.ANY);
            case ComputationGraph.MakeRecord ignored -> DataType.Scalar.ANY;
            case ComputationGraph.MakeList ignored -> DataType.Scalar.ANY;
            case ComputationGraph.ConcatLists ignored -> new DataType.ListType(DataType.Scalar.ANY);
            case ComputationGraph.Index ignored -> new DataType.ListType(DataType.Scalar.ANY);
            case ComputationGraph.DynamicIndex ignored -> port.equals("index") ? DataType.Scalar.INTEGER : new DataType.ListType(DataType.Scalar.ANY);
            case ComputationGraph.DynamicField ignored -> port.equals("name") ? DataType.Scalar.TEXT : null;
            case ComputationGraph.HasField ignored -> port.equals("name") ? DataType.Scalar.TEXT : null;
            case ComputationGraph.Call n -> declaration(n.module(), port);
            case ComputationGraph.Branch n -> {
                if (port.equals("condition")) yield DataType.Scalar.BOOLEAN;
                String name = port.substring("argument:".length());
                var left = declaration(n.whenTrue(), name); var right = declaration(n.whenFalse(), name);
                yield Objects.equals(left, right) ? left : null;
            }
            case ComputationGraph.Iterate n -> collectionInput(n.module(), n.itemPort(), port);
            case ComputationGraph.Sort n -> collectionInput(n.module(), n.itemPort(), port);
            case ComputationGraph.Fold n -> port.equals("initial") ? declaration(n.module(), n.accumulatorPort())
                : collectionInput(n.module(), n.itemPort(), port);
            default -> null; // Field accepts either record or map; no fake scalar-union type.
        };
    }
    private DataType collectionInput(ModuleDefinition.Key module, String item, String port) {
        if (!port.equals("source")) return declaration(module, port.substring("capture:".length()));
        var element = declaration(module, item); return new DataType.ListType(element == null ? DataType.Scalar.ANY : element);
    }
    private DataType declaration(ModuleDefinition.Key module, String name) {
        return workspace.keys().contains(module) ? workspace.graph(module).inputs().get(name) : null;
    }

    /** Checks a proposal only; the caller commits to ProgramDraft after success. */
    void check(String target, String port, String source) {
        var pending = new ArrayDeque<String>(); var visited = new HashSet<String>(); pending.add(source);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (id.equals(target)) throw new ComputationException(ComputationException.Code.GRAPH_CYCLE, target,
                "Feedback must pass through event memory");
            if (visited.add(id)) pending.addAll(draft.node(id).connections().values());
        }
        var actual = output(source); if (actual == null) return;
        var expected = input(target, port);
        if (expected != null && !expected.compatibleWith(actual))
            throw new ComputationException(ComputationException.Code.TYPE_MISMATCH, target + "." + port,
                expected + " ← " + actual);
        var node = draft.node(target); var arguments = new LinkedHashMap<String, DataType>();
        var references = new LinkedHashMap<String, String>();
        for (String name : node.ports()) {
            references.put(name, name);
            // Placeholders are used only to check this proposal; they are never published as inferred types.
            arguments.put(name, name.equals(port) ? actual : types.getOrDefault(node.connections().get(name), DataType.Scalar.ANY));
        }
        try {
            ProgramCompiler.inferNode(NodePorts.bind(node.definition(), references), arguments,
                draft.inputs(), draft.cells(), modules, pure, target);
        } catch (ComputationException error) {
            if (error.code() == ComputationException.Code.TYPE_MISMATCH) throw error;
            // Unfinished module/field/reference diagnostics remain available in the full compiler.
        }
    }
}
