package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.*;

public final class FoldProgramFixtures {
    public static final ModuleDefinition.Key SUM = new ModuleDefinition.Key("sum", 1);
    private FoldProgramFixtures() {}
    public static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
    public static DataValue.ListValue numbers(long... values) {
        return new DataValue.ListValue(Arrays.stream(values).mapToObj(FoldProgramFixtures::integer).map(DataValue.class::cast).toList());
    }
    public static ComputationGraph sum() {
        return new ComputationGraph(Map.of("item", INTEGER, "total", INTEGER), Map.of(
            "item", new ComputationGraph.Input("item"), "total", new ComputationGraph.Input("total"),
            "next", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "total", "right", "item"))), Map.of("next", "next"));
    }
    public static ProgramArchive archive(DataValue items, DataValue seed) { return archive(items, seed, sum()); }
    public static ProgramArchive archive(DataValue items, DataValue seed, ComputationGraph module) {
        var root = new ComputationGraph(Map.of(), Map.of("items", new ComputationGraph.Constant(items),
            "seed", new ComputationGraph.Constant(seed), "fold", fold()), Map.of("result", "fold"));
        return new ProgramArchive(new ProgramDefinition(root, Map.of(), List.of()), Map.of(SUM, new ModuleDefinition(SUM, module)));
    }
    public static ComputationGraph.Fold fold() {
        return new ComputationGraph.Fold("items", "seed", SUM, "item", "total", Map.of(), "next");
    }
}
