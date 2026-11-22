package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowCallBindings;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram;
import java.util.*;

/** An additive signature change on one Scratch call. Existing graphs are not reinterpreted. */
final class ScratchCallUpgrade {
    private ScratchCallUpgrade() {}

    static Optional<Map<String, DataValue>> addedDefaults(FlowProgram.Procedure old, FlowProgram.Procedure next) {
        if (!old.key().id().equals(next.key().id()) || old.key().revision() >= next.key().revision()
            || !old.event().equals(next.event()) || old.startup()!=next.startup() || !old.terminalKey().equals(next.terminalKey())
            || !next.inputs().entrySet().containsAll(old.inputs().entrySet())
            || !next.outputs().entrySet().containsAll(old.outputs().entrySet()))
            return Optional.empty();
        var defaults = new TreeMap<String, DataValue>();
        for (var port : next.inputs().entrySet()) {
            if (old.inputs().containsKey(port.getKey())) continue;
            var type = port.getValue();
            if (com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements.containsAny(type)
                || LocalReferences.contains(type)) return Optional.empty();
            var value = InlineGraphEdits.defaultValue(type);
            if (!type.accepts(value)) return Optional.empty();
            defaults.put(port.getKey(), value);
        }
        return Optional.of(Collections.unmodifiableMap(defaults));
    }

    static boolean supports(FlowDraft draft, FlowProgram.Invoke call, FlowProgram.Procedure current) {
        if (draft.snapshot().execution() != FlowProgram.Execution.REGISTER_V1 || !current.key().equals(call.procedure()))
            return false;
        var graph = draft.snapshot().inline().get(call.sourceId());
        return call.operands().isPresent()
            ? graph != null && graph.outputs().keySet().equals(current.inputs().keySet())
            : graph == null && call.bindings().arguments().keySet().equals(current.inputs().keySet());
    }

    static void apply(FlowDraft draft, FlowProgram.Invoke call, FlowProgram.Procedure old, FlowProgram.Procedure next) {
        if (draft.find(call.sourceId()).filter(call::equals).isEmpty() || !supports(draft, call, old))
            throw new IllegalArgumentException("The call no longer matches the original signature");
        var defaults = addedDefaults(old, next).orElseThrow(() -> new IllegalArgumentException("The new signature is not additive"));
        var replacement = new FlowProgram.Invoke(call.sourceId(), next.key(), call.bindings(), call.operands());
        if (defaults.isEmpty()) { draft.replace(call.sourceId(), replacement); return; }
        var original = draft.snapshot().inline().getOrDefault(call.sourceId(), new ComputationGraph(Map.of(), Map.of(), Map.of()));
        var inputs = new LinkedHashMap<>(original.inputs());
        var nodes = new LinkedHashMap<>(original.nodes());
        var outputs = new LinkedHashMap<>(original.outputs());
        if (replacement.operands().isEmpty()) {
            // Keep each input's declared type and its explicit source local (not the port name).
            // Multiple arguments may read the same local with different declared types.
            for (String port : new TreeSet<>(call.bindings().arguments().keySet())) {
                String id = InlineGraphEdits.next(nodes);
                inputs.put(port, old.inputs().get(port));
                nodes.put(id, new ComputationGraph.Input(port));
                outputs.put(port, id);
            }
            replacement = new FlowProgram.Invoke(call.sourceId(), next.key(), new FlowCallBindings(Map.of(), call.bindings().returns()),
                Optional.of(new FlowProgram.InvokeOperands(call.sourceId(), call.bindings().arguments())));
        }
        defaults.forEach((name, value) -> {
            String id = InlineGraphEdits.next(nodes);
            nodes.put(id, new ComputationGraph.Constant(value)); outputs.put(name, id);
        });
        draft.editInline(replacement, new ComputationGraph(inputs, nodes, outputs), draft.snapshot().canvas().values());
    }

    static Optional<Map<String, DataValue>> valueDefaults(ComputationGraph.ProcedureValue call,
            FlowProgram.Procedure old, FlowProgram.Procedure next) {
        if (!call.procedure().equals(old.key()) || !call.arguments().keySet().equals(old.inputs().keySet())
            || !old.outputs().containsKey(call.result()) || !FlowProgram.supportsValueCall(old)
            || !FlowProgram.supportsValueCall(next)) return Optional.empty();
        return addedDefaults(old, next);
    }

    static ComputationGraph value(ComputationGraph graph, String node, FlowProgram.Procedure old, FlowProgram.Procedure next) {
        if (!(graph.nodes().get(node) instanceof ComputationGraph.ProcedureValue call))
            throw new IllegalArgumentException("The slot no longer contains a value call");
        var defaults = valueDefaults(call, old, next).orElseThrow(() -> new IllegalArgumentException("The new value signature is not additive"));
        FlowReporterEdits.requireUnshared(graph, node);
        var nodes = new LinkedHashMap<>(graph.nodes());
        var arguments = new LinkedHashMap<>(call.arguments());
        defaults.forEach((port, value) -> {
            String id = InlineGraphEdits.next(nodes);
            nodes.put(id, new ComputationGraph.Constant(value));
            arguments.put(port, id);
        });
        nodes.put(node, new ComputationGraph.ProcedureValue(next.key(), call.result(), arguments));
        return new ComputationGraph(graph.inputs(), nodes, graph.outputs());
    }
}
