package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hand-checkable lazy-branch fixture shared by runtime, archive and editor tests. */
public final class BranchProgramFixtures {
	public static final ModuleDefinition.Key DIVIDE = new ModuleDefinition.Key("divide", 1);
	public static final ModuleDefinition.Key ZERO = new ModuleDefinition.Key("zero", 1);

	private BranchProgramFixtures() {}

	public static ProgramArchive archive() {
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		nodes.put("x", new ComputationGraph.Input("x"));
		nodes.put("d", new ComputationGraph.Input("d"));
		nodes.put("zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)));
		nodes.put("is_zero", new ComputationGraph.Scalar(ScalarOperation.EQUAL_INTEGER,
			Map.of("left", "d", "right", "zero")));
		nodes.put("nonzero", new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", "is_zero")));
		nodes.put("choice", new ComputationGraph.Branch("nonzero", DIVIDE, ZERO, Map.of("x", "x", "d", "d")));
		var graph = new ComputationGraph(Map.of("x", INTEGER, "d", INTEGER), nodes, Map.of("result", "choice"));
		return new ProgramArchive(new ProgramDefinition(graph, Map.of(), List.of()), modules());
	}

	public static Map<ModuleDefinition.Key, ModuleDefinition> modules() {
		var inputs = Map.<String, DataType>of("x", INTEGER, "d", INTEGER);
		var divide = new ComputationGraph(inputs, Map.of(
			"x", new ComputationGraph.Input("x"), "d", new ComputationGraph.Input("d"),
			"value", new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER, Map.of("left", "x", "right", "d"))),
			Map.of("value", "value"));
		var zero = new ComputationGraph(inputs, Map.of("value", new ComputationGraph.Constant(new DataValue.IntegerValue(0))),
			Map.of("value", "value"));
		return Map.of(DIVIDE, new ModuleDefinition(DIVIDE, divide), ZERO, new ModuleDefinition(ZERO, zero));
	}

	public static DataValue.RecordValue inputs(long x, long d) {
		return new DataValue.RecordValue(Map.of("x", new DataValue.IntegerValue(x), "d", new DataValue.IntegerValue(d)));
	}
}
