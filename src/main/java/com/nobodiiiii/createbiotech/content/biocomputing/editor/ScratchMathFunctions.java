package com.nobodiiiii.createbiotech.content.biocomputing.editor;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ScalarOperation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Scratch's selector is an editor field; the selected function is a plain typed scalar node. */
final class ScratchMathFunctions {
    static final ScalarOperation DEFAULT = ABS_DECIMAL;
    static final List<ScalarOperation> OPTIONS = List.of(ABS_DECIMAL, FLOOR_DECIMAL, CEIL_DECIMAL, SQRT_DECIMAL,
        SIN_DEGREES, COS_DEGREES, TAN_DEGREES, ASIN_DEGREES, ACOS_DEGREES, ATAN_DEGREES,
        LOG_NATURAL, LOG_BASE_TEN, EXP_NATURAL, EXP_BASE_TEN);

    private ScratchMathFunctions() {}

    static boolean hasMenu(ComputationGraph.Node node) {
        return node instanceof ComputationGraph.Scalar scalar && OPTIONS.contains(scalar.operation());
    }

    static ComputationGraph select(ComputationGraph graph, String node, ScalarOperation operation) {
        if (!(graph.nodes().get(node) instanceof ComputationGraph.Scalar old)
            || !hasMenu(old) || !OPTIONS.contains(operation) || operation == old.operation()) return graph;
        var nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put(node, new ComputationGraph.Scalar(operation, old.arguments()));
        return new ComputationGraph(graph.inputs(), nodes, graph.outputs());
    }

    static String key(ScalarOperation operation) {
        return "mathop_" + operation.name().toLowerCase(Locale.ROOT);
    }
}
