package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared test fixture for the real editor/archive/host interval-predicate path. */
public final class ScalarProgramFixtures {
	private ScalarProgramFixtures() {}

	public static ProgramArchive intervalArchive() {
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		nodes.put("value", new ComputationGraph.Input("value"));
		nodes.put("lower", new ComputationGraph.Input("lower"));
		nodes.put("upper", new ComputationGraph.Input("upper"));
		nodes.put("at_least", scalar(ScalarOperation.GREATER_EQUAL_INTEGER, "left", "value", "right", "lower"));
		nodes.put("below", scalar(ScalarOperation.LESS_INTEGER, "left", "value", "right", "upper"));
		nodes.put("accepted", scalar(ScalarOperation.AND_BOOLEAN, "left", "at_least", "right", "below"));
		nodes.put("yes", new ComputationGraph.Constant(record("state", new DataValue.TextValue("in_range"))));
		nodes.put("no", new ComputationGraph.Constant(record("state", new DataValue.TextValue("out_of_range"))));
		nodes.put("selected", scalar(ScalarOperation.SELECT, "condition", "accepted", "when_true", "yes", "when_false", "no"));
		var graph = new ComputationGraph(Map.of("value", INTEGER, "lower", INTEGER, "upper", INTEGER), nodes,
			Map.of("accepted", "accepted", "selected", "selected"));
		var program = new ProgramDefinition(graph,
			Map.of("accepted", new ProgramDefinition.Cell(BOOLEAN, new DataValue.BooleanValue(false)),
				"selected", new ProgramDefinition.Cell(new DataType.RecordType(Map.of("state", TEXT)), record("state", new DataValue.TextValue("out_of_range")))),
			List.of(new ProgramDefinition.Write("accepted", "accepted"), new ProgramDefinition.Write("selected", "selected")));
		return new ProgramArchive(program, Map.of());
	}

	public static DataValue.RecordValue intervalInputs(long value, long lower, long upper) {
		return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value),
			"lower", new DataValue.IntegerValue(lower), "upper", new DataValue.IntegerValue(upper)));
	}

	private static ComputationGraph.Scalar scalar(ScalarOperation operation, String... pairs) {
		var arguments = new LinkedHashMap<String, String>();
		for (int index = 0; index < pairs.length; index += 2) arguments.put(pairs[index], pairs[index + 1]);
		return new ComputationGraph.Scalar(operation, arguments);
	}

	private static DataValue.RecordValue record(String name, DataValue value) {
		return new DataValue.RecordValue(Map.of(name, value));
	}
}
