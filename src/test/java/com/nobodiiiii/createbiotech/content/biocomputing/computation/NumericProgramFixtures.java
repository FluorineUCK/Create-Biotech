package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import java.util.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;

/** Ordinary public nodes, shared by menu, archive and runtime acceptance tests. */
public final class NumericProgramFixtures {
    private NumericProgramFixtures() {}
    public static final List<ScalarOperation> OPERATIONS = List.of(ScalarOperation.SQRT_DECIMAL, ScalarOperation.POWER_DECIMAL,
        ScalarOperation.REMAINDER_DECIMAL, ScalarOperation.ROUND_DECIMAL_TO_INTEGER,
        ScalarOperation.CEIL_DECIMAL_TO_INTEGER, ScalarOperation.FLOOR_DECIMAL_TO_INTEGER);
    public static ComputationGraph graph(ScalarOperation operation) {
        var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
        var bindings = new LinkedHashMap<String, String>();
        operation.inputs().forEach((name, type) -> { nodes.put(name, new ComputationGraph.Input(name)); bindings.put(name, name); });
        nodes.put("numeric", new ComputationGraph.Scalar(operation, bindings));
        return new ComputationGraph(operation.inputs(), nodes, Map.of("result", "numeric"));
    }
    public static ProgramArchive archive(ScalarOperation operation) {
        return new ProgramArchive(new ProgramDefinition(graph(operation),
            Map.of("saved", new ProgramDefinition.Cell(DataType.Scalar.ANY, DataValue.NullValue.INSTANCE)),
            List.of(new ProgramDefinition.Write("saved", "result"))), Map.of());
    }
    public static DataValue.RecordValue inputs(ScalarOperation operation) {
        var values = new LinkedHashMap<String, DataValue>();
        operation.inputs().keySet().forEach(name -> values.put(name, new DataValue.DecimalValue(name.equals("right") ? 0.5 : 9)));
        return new DataValue.RecordValue(values);
    }
    public static DataValue expected(ScalarOperation operation) {
        return switch (operation) {
            case SQRT_DECIMAL, POWER_DECIMAL -> new DataValue.DecimalValue(3);
            case REMAINDER_DECIMAL -> new DataValue.DecimalValue(0);
            case ROUND_DECIMAL_TO_INTEGER, CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER -> new DataValue.IntegerValue(9);
            default -> throw new IllegalArgumentException(operation.name());
        };
    }
}
