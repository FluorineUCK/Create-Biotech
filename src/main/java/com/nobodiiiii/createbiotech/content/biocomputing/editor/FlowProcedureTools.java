package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import java.util.*;

/** Editor-only procedure views; the Flow compiler remains the sole authority for executable calls. */
final class FlowProcedureTools {
    static Optional<FlowProgram.Watch> watch(FlowDocumentBody body, FlowProgram.Procedure definition) {
        return body.canvas().detached().stream().filter(stack -> stack.steps().getFirst().equals(definition))
            .flatMap(stack -> stack.steps().stream().skip(1).limit(1))
            .filter(FlowProgram.Watch.class::isInstance).map(FlowProgram.Watch.class::cast).findFirst();
    }
    static List<FlowProgram.Procedure> definitions(FlowDocumentBody body) {
        return body.canvas().detached().stream().filter(stack -> stack.steps().size() < 2 || !(stack.steps().get(1) instanceof FlowProgram.Watch)).map(stack -> stack.steps().getFirst()).filter(FlowProgram.Procedure.class::isInstance)
            .map(FlowProgram.Procedure.class::cast).toList();
    }
    static Optional<FlowProgram.Procedure> owner(FlowDocumentBody body, String selected) {
        return body.canvas().detached().stream().filter(stack -> stack.steps().stream().anyMatch(step -> step.sourceId().equals(selected)))
            .map(stack -> stack.steps().getFirst()).filter(FlowProgram.Procedure.class::isInstance).map(FlowProgram.Procedure.class::cast).findFirst();
    }
    static Map<String, DataType> locals(FlowDocumentBody body, String selected, ProgramArchive archive, ResourceLimits limits) {
        var owner = owner(body,selected);
        if (selected != null && owner.filter(p -> p.sourceId().equals(selected)).isEmpty()) {
            try { return body.program(archive).typesBefore(selected,limits); }
            catch (ComputationException | IllegalArgumentException incomplete) { /* A draft can be edited before it is installable. */ }
        }
        return owner.map(FlowProgram.Procedure::inputs).orElse(body.inputs());
    }
    static FlowProgram.Invoke call(FlowDraft draft, FlowProgram.Procedure definition) {
        // Missing arguments are visible unresolved bindings, not implicit same-name capture.
        return new FlowProgram.Invoke(draft.nextId(), definition.key(), new FlowCallBindings(Map.of(),Map.of()));
    }
    static boolean supportsInlineOperands(FlowProgram.Procedure definition) {
        return !definition.inputs().isEmpty() && definition.inputs().values().stream().allMatch(type ->
            !com.nobodiiiii.createbiotech.content.biocomputing.computation.assembly.AssemblyTypeRequirements.containsAny(type)
                && type.accepts(InlineGraphEdits.defaultValue(type)));
    }
    record CallPrototype(FlowProgram.Invoke call, Map<String, ComputationGraph> graphs) {}
    static CallPrototype prototype(FlowDraft draft, FlowProgram.Procedure definition) {
        if (draft.snapshot().execution() != FlowProgram.Execution.REGISTER_V1 || !supportsInlineOperands(definition))
            return new CallPrototype(call(draft, definition), Map.of());
        var defaults = new TreeMap<String, DataValue>();
        definition.inputs().forEach((port, type) -> defaults.put(port, InlineGraphEdits.defaultValue(type)));
        return prototype(draft, definition, defaults);
    }
    static CallPrototype prototype(FlowDraft draft, FlowProgram.Procedure definition, Map<String, DataValue> defaults) {
        var call = call(draft, definition);
        if (defaults.isEmpty()) return new CallPrototype(call, Map.of());
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        var outputs = new LinkedHashMap<String, String>();
        for (var port : new TreeMap<>(definition.inputs()).entrySet()) {
            var value = defaults.get(port.getKey());
            String node = "arg_" + nodes.size();
            nodes.put(node, new ComputationGraph.Constant(value));
            outputs.put(port.getKey(), node);
        }
        return new CallPrototype(FlowInlineValues.operands(call, call.sourceId(), Map.of()),
            Map.of(call.sourceId(), new ComputationGraph(Map.of(), nodes, outputs)));
    }
    static void define(FlowDraft draft, FlowProgram.Procedure definition, boolean replace) {
        if (definitions(draft.snapshot()).stream().anyMatch(p -> p.key().equals(definition.key()) && !p.sourceId().equals(definition.sourceId())))
            throw new IllegalArgumentException("This fixed procedure version already exists");
        if (replace) draft.replace(definition.sourceId(),definition);
        else draft.place(List.of(definition,new FlowProgram.EndProcedure(definition.sourceId()+"_end")),null,
            new ProgramDocument.Position(280,16 + draft.snapshot().canvas().detached().size()*80));
    }
}
