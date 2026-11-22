package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtension.Shape;
import com.nobodiiiii.createbiotech.content.biocomputing.extension.ScratchExtensions;
import java.util.*;

/** Fixed control predicates paired with player-authored bodies, lowered to ordinary Flow control. */
final class ScratchExtensionMouth {
    private ScratchExtensionMouth() {}

    static boolean isMouth(FlowProgram.Step step, ComputationGraph graph) {
        return predicate(step, graph) != null;
    }

    /** A debugger/source hint can name the actual painted control block, not its hidden procedure. */
    static Optional<ScratchExtensions.Binding> binding(FlowProgram.Step step, ComputationGraph graph) {
        var node = predicate(step, graph);
        return node == null ? Optional.empty() : ScratchExtensions.binding(((ComputationGraph.ProcedureValue) graph.nodes().get(node)).procedure());
    }

    static Optional<ModuleDefinition.Key> missingWatch(FlowProgram.Watch watch, ComputationGraph graph) {
        var node = predicate(watch, graph);
        if (node == null) return Optional.empty();
        var key = ((ComputationGraph.ProcedureValue) graph.nodes().get(node)).procedure();
        return ScratchExtensions.binding(key).isEmpty() ? Optional.of(key) : Optional.empty();
    }

    static String predicate(FlowProgram.Step step, ComputationGraph graph) {
        if (graph == null) return null;
        var rootId = graph.outputs().get("value");
        boolean loop = step instanceof FlowProgram.Until;
        boolean hat = step instanceof FlowProgram.Watch;
        if (loop) rootId = ScratchExtensions.loopPredicate(graph, rootId);
        else if (hat) rootId = ScratchExtensions.hatPredicate(graph, rootId);
        else if (!(step instanceof FlowProgram.If)) return null;
        if (rootId == null) return null;
        var root = graph.nodes().get(rootId);
        if (!(root instanceof ComputationGraph.ProcedureValue call) || !ScratchExtensions.reserved(call.procedure())) return null;
        // When an addon is absent the canonical edge adapter still identifies a fixed-predicate
        // header to display. Do not infer C shapes: a normal If or Until can use a boolean reporter.
        return ScratchExtensions.binding(call.procedure()).map(b -> loop ? b.command().shape() == Shape.LOOP
                : hat ? b.command().shape() == Shape.HAT : b.command().shape().conditional()).orElse(hat) ? rootId : null;
    }

    record Prototype(List<FlowProgram.Step> steps, Map<String, ComputationGraph> graphs) {}

    static Prototype prototype(FlowDraft draft, ScratchExtensions.Binding binding) {
        var id = draft.nextId();
        var close = id + "_end";
        while (draft.find(close).isPresent()) close += "_";
        var result = binding.command().outputs().keySet().iterator().next();
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        var args = new LinkedHashMap<String, String>();
        new TreeMap<>(binding.command().defaults()).forEach((name, value) -> {
            String port = "arg_" + nodes.size();
            nodes.put(port, new ComputationGraph.Constant(value)); args.put(name, port);
        });
        boolean loop = binding.command().shape() == Shape.LOOP;
        boolean hat = binding.command().shape() == Shape.HAT;
        if (!hat && binding.command().shape().bodyCount() == 0) throw new IllegalArgumentException("Expected a control header");
        nodes.put(loop || hat ? "predicate" : "value", new ComputationGraph.ProcedureValue(binding.key(), result, args));
        if (loop) nodes.put("value", new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", "predicate")));
        if (hat) {
            String watchId = id + "_condition";
            while (draft.find(watchId).isPresent()) watchId += "_";
            nodes.put("zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
            nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
            nodes.put("bit", new ComputationGraph.Scalar(ScalarOperation.SELECT,
                Map.of("condition", "predicate", "when_true", "one", "when_false", "zero")));
            nodes.put("value", new ComputationGraph.Scalar(ScalarOperation.SAMPLE_RISING_INTEGER,
                Map.of("current", "bit", "threshold", "zero")));
            var procedure = new FlowProgram.Procedure(id, new ModuleDefinition.Key("monitor_" + UUID.randomUUID(), 1),
                Map.of(), Map.of(), Map.of());
            var watch = new FlowProgram.Watch(watchId, Optional.empty(), new FlowInput.Expression(watchId, "value", Map.of()));
            return new Prototype(List.of(procedure, watch, new FlowProgram.EndProcedure(close)),
                Map.of(watchId, new ComputationGraph(Map.of(), nodes, Map.of("value", "value"))));
        }
        var graph = new ComputationGraph(Map.of(), nodes, Map.of("value", "value"));
        var condition = new FlowInput.Expression(id, "value", Map.of());
        if (loop) return new Prototype(List.of(new FlowProgram.Until(id, condition), new FlowProgram.EndUntil(close)), Map.of(id, graph));
        var steps = new ArrayList<FlowProgram.Step>();
        steps.add(new FlowProgram.If(id, condition));
        if (binding.command().shape().bodyCount() == 2) {
            String alternate = id + "_else";
            while (draft.find(alternate).isPresent()) alternate += "_";
            steps.add(new FlowProgram.Otherwise(alternate));
        }
        steps.add(new FlowProgram.EndIf(close));
        return new Prototype(List.copyOf(steps), Map.of(id, graph));
    }
}
