package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.editor.NodePorts;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScalarGraphTest {
	private static final ResourceLimits ROOMY = new ResourceLimits(10_000, 100_000, 1);

	@Test void scalarValidatesAndExposesStableNamedDependenciesAndEditorPorts() {
		var arguments = new LinkedHashMap<String, String>();
		arguments.put("when_false", "no"); arguments.put("when_true", "yes"); arguments.put("condition", "c");
		var node = new ComputationGraph.Scalar(ScalarOperation.SELECT, arguments);
		assertEquals(List.of("c", "yes", "no"), node.dependencies());
		assertEquals(List.of("condition", "when_true", "when_false"), List.copyOf(NodePorts.bindings(node).keySet()));
		assertEquals(node, NodePorts.bind(node, NodePorts.bindings(node)));
		arguments.clear();
		assertEquals(List.of("c", "yes", "no"), node.dependencies());
		assertCode(INVALID_GRAPH, () -> new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("wrong", "x")));
		assertCode(INVALID_GRAPH, () -> new ComputationGraph.Scalar(ScalarOperation.NOT_BOOLEAN, Map.of("value", " ")));
	}

	@Test void everyOperationCompilesExecutesAndRestoresAtEveryStep() {
		for (var operation : ScalarOperation.values()) {
			var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
			var refs = new LinkedHashMap<String, String>();
			operation.inputs().forEach((port, type) -> {
				String id = "arg_" + port;
				nodes.put(id, new ComputationGraph.Constant(sample(type, port)));
				refs.put(port, id);
			});
			nodes.put("result", new ComputationGraph.Scalar(operation, refs));
			var installed = ProgramCompiler.compile(new ProgramDefinition(new ComputationGraph(Map.of(), nodes, Map.of("result", "result")), Map.of(), List.of()), Map.of(), ROOMY);
			var uninterrupted = run(installed);
			assertEquals(ComputationMachine.Status.SUCCEEDED, uninterrupted.report().status(), operation.name());
			var restored = new ComputationMachine(installed); restored.begin(new DataValue.RecordValue(Map.of()));
			while (restored.report().status() == ComputationMachine.Status.RUNNING) {
				restored = ComputationMachine.restore(installed, restored.checkpoint());
				long beforeStep = restored.report().workUnits();
				restored.step();
				assertEquals(beforeStep + 1, restored.report().workUnits(), operation.name() + " step cost");
			}
			assertEquals(uninterrupted.report(), restored.report(), operation.name());
			assertEquals(ComputationMachine.Status.SUCCEEDED, restored.report().status(), operation.name());
			assertEquals(expectedWorkUnits(operation), restored.report().workUnits(), operation.name());
			assertEquals(uninterrupted.memory(), restored.memory(), operation.name());
		}
	}

	@Test void allLegacyOperationsDelegateWithExactParity() {
		for (var legacy : ComputationGraph.BinaryOp.values()) {
			var scalar = graph(new ComputationGraph.Scalar(ScalarOperation.forLegacy(legacy), Map.of("left", "a", "right", "b")));
			var binary = graph(new ComputationGraph.Binary(legacy, "a", "b"));
			var a = ProgramCompiler.compile(new ProgramDefinition(scalar, Map.of(), List.of()), Map.of(), ROOMY);
			var b = ProgramCompiler.compile(new ProgramDefinition(binary, Map.of(), List.of()), Map.of(), ROOMY);
			assertEquals(b.usage(), a.usage(), legacy.name());
			assertEquals(ComputationMachine.Status.SUCCEEDED, run(a).report().status(), legacy.name());
			assertEquals(run(b).report(), run(a).report(), legacy.name());
		}
	}

	@Test void actualHostPersistsAndRestoresEveryScalarStep() {
		var host = new ComputerBlockHost(() -> {});
		assertEquals(ComputerRuntime.InstallResult.APPLIED, host.install(ScalarProgramFixtures.intervalArchive(), ComputerRuntime.MemoryPolicy.RESET, EventEntryFixtures.LIMITS, true));
		host.begin(ScalarProgramFixtures.intervalInputs(5, 2, 8), EventEntryFixtures.LIMITS, true);
		while (host.observe(EventEntryFixtures.LIMITS, true).machine().status() == ComputationMachine.Status.RUNNING) {
			var before = host.observe(EventEntryFixtures.LIMITS, true);
			var restored = new ComputerBlockHost(() -> {}); restored.read(host.write());
			assertEquals(before, restored.observe(EventEntryFixtures.LIMITS, true));
			host = restored; host.step(EventEntryFixtures.LIMITS, true);
		}
		assertEquals(ComputationMachine.Status.SUCCEEDED, host.observe(EventEntryFixtures.LIMITS, true).machine().status());
		assertEquals(new DataValue.BooleanValue(true), host.observe(EventEntryFixtures.LIMITS, true).machine().memory().field("accepted"));
	}

	@Test void scalarRunsInsidePureModulesMapAndFilterWithCapturesWithoutMutatingSource() {
		var negateKey = new ModuleDefinition.Key("negate", 1);
		var keepKey = new ModuleDefinition.Key("keep", 1);
		var negate = new ModuleDefinition(negateKey, new ComputationGraph(Map.of("item", INTEGER), Map.of(
			"item", new ComputationGraph.Input("item"),
			"result", new ComputationGraph.Scalar(ScalarOperation.NEGATE_INTEGER, Map.of("value", "item"))), Map.of("result", "result")));
		var keep = new ModuleDefinition(keepKey, new ComputationGraph(Map.of("item", INTEGER, "limit", INTEGER), Map.of(
			"item", new ComputationGraph.Input("item"), "limit", new ComputationGraph.Input("limit"),
			"result", new ComputationGraph.Scalar(ScalarOperation.LESS_INTEGER, Map.of("left", "item", "right", "limit"))), Map.of("result", "result")));
		var sourceList = new DataValue.ListValue(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(3), new DataValue.IntegerValue(2)));
		var root = new ComputationGraph(Map.of(), Map.of(
			"source", new ComputationGraph.Constant(sourceList), "limit", new ComputationGraph.Constant(new DataValue.IntegerValue(3)),
			"filtered", new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER, "source", keepKey, "item", Map.of("limit", "limit"), "result"),
			"mapped", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "filtered", negateKey, "item", Map.of(), "result")), Map.of("result", "mapped"));
		var result = run(ProgramCompiler.compile(new ProgramDefinition(root, Map.of(), List.of()), Map.of(negateKey, negate, keepKey, keep), ROOMY));
		assertEquals(new DataValue.ListValue(List.of(new DataValue.IntegerValue(-1), new DataValue.IntegerValue(-2))), result.report().output().orElseThrow().field("result"));
		assertEquals(List.of(new DataValue.IntegerValue(1), new DataValue.IntegerValue(3), new DataValue.IntegerValue(2)), sourceList.values());
	}

	@Test void scalarFailurePublishesNeitherRamNorOutboundSend() {
		var graph = new ComputationGraph(Map.of("value", ANY), Map.of(
			"value", new ComputationGraph.Input("value"), "zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
			"bad", new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER, Map.of("left", "value", "right", "zero")),
			"endpoint", new ComputationGraph.Constant(new DataValue.TextValue("factory:test")),
			"payload", new ComputationGraph.MakeRecord(Map.of("value", "bad"))),
			Map.of("bad", "bad", "endpoint", "endpoint", "payload", "payload"));
		var program = new ProgramDefinition(graph,
			Map.of("cell", new ProgramDefinition.Cell(INTEGER, new DataValue.IntegerValue(99))),
			List.of(new ProgramDefinition.Write("cell", "bad")), List.of(new ProgramDefinition.Send("endpoint", "payload")));
		var runtime = new ComputerRuntime(java.util.UUID.randomUUID(), new com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive(program, Map.of()),
			EventEntryFixtures.HARDWARE, EventEntryFixtures.LIMITS.mailbox());
		runtime.begin(new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(7))));
		while (runtime.report().status() == ComputationMachine.Status.RUNNING) runtime.step();
		assertEquals(DIVISION_BY_ZERO, runtime.report().diagnostic().orElseThrow().code());
		assertEquals(new DataValue.IntegerValue(99), runtime.memory().field("cell"));
		assertTrue(runtime.outbound().isEmpty());
	}

	@Test void intervalProgramCompilesRunsWritesAndRestoresEveryStep() {
		var installed = ScalarProgramFixtures.intervalArchive().compile(ROOMY);
		var machine = new ComputationMachine(installed);
		machine.begin(ScalarProgramFixtures.intervalInputs(5, 2, 8));
		while (machine.report().status() == ComputationMachine.Status.RUNNING) {
			var restored = ComputationMachine.restore(installed, machine.checkpoint());
			assertEquals(machine.report(), restored.report());
			assertEquals(machine.memory(), restored.memory());
			machine = restored;
			machine.step();
		}
		assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
		assertEquals(new DataValue.BooleanValue(true), machine.memory().field("accepted"));
		assertEquals(new DataValue.TextValue("in_range"), ((DataValue.RecordValue) machine.memory().field("selected")).field("state"));
	}

	@Test void scalarAndLegacyBinaryHaveIdenticalResourceAndWorkCosts() {
		var scalar = graph(new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "a", "right", "b")));
		var legacy = graph(new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "a", "b"));
		var si = ProgramCompiler.compile(new ProgramDefinition(scalar, Map.of(), List.of()), Map.of(), ROOMY);
		var li = ProgramCompiler.compile(new ProgramDefinition(legacy, Map.of(), List.of()), Map.of(), ROOMY);
		assertEquals(li.usage(), si.usage());
		assertEquals(li.root().nodeTypes(), si.root().nodeTypes());
		assertEquals(run(li).report().workUnits(), run(si).report().workUnits());
		assertEquals(run(li).report().output(), run(si).report().output());
	}

	@Test void staticAndDynamicTypeFailuresAndDivisionAreAtomic() {
		var staticallyWrong = new ComputationGraph(Map.of(), Map.of(
			"a", new ComputationGraph.Constant(new DataValue.TextValue("x")),
			"b", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
			"op", new ComputationGraph.Scalar(ScalarOperation.ADD_INTEGER, Map.of("left", "a", "right", "b"))), Map.of("out", "op"));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(new ProgramDefinition(staticallyWrong, Map.of(), List.of()), Map.of(), ROOMY));

		var dynamic = operationGraph(ScalarOperation.ADD_INTEGER, ANY);
		var installed = ProgramCompiler.compile(programWriting(dynamic), Map.of(), ROOMY);
		var failed = run(installed, new DataValue.RecordValue(Map.of("left", new DataValue.TextValue("bad"), "right", new DataValue.IntegerValue(2))));
		assertEquals(TYPE_MISMATCH, failed.report().diagnostic().orElseThrow().code());
		assertEquals(new DataValue.IntegerValue(99), failed.memory().field("cell"));

		installed = ProgramCompiler.compile(programWriting(operationGraph(ScalarOperation.DIVIDE_INTEGER, INTEGER)), Map.of(), ROOMY);
		failed = run(installed, new DataValue.RecordValue(Map.of("left", new DataValue.IntegerValue(7), "right", new DataValue.IntegerValue(0))));
		assertEquals(DIVISION_BY_ZERO, failed.report().diagnostic().orElseThrow().code());
		assertEquals(new DataValue.IntegerValue(99), failed.memory().field("cell"));
	}

	@Test void selectIsEagerBecauseAllDependenciesExecuteBeforeIt() {
		var graph = new ComputationGraph(Map.of(), Map.of(
			"condition", new ComputationGraph.Constant(new DataValue.BooleanValue(true)),
			"one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
			"zero", new ComputationGraph.Constant(new DataValue.IntegerValue(0)),
			"bad", new ComputationGraph.Scalar(ScalarOperation.DIVIDE_INTEGER, Map.of("left", "one", "right", "zero")),
			"selected", new ComputationGraph.Scalar(ScalarOperation.SELECT, Map.of("condition", "condition", "when_true", "one", "when_false", "bad"))),
			Map.of("out", "selected"));
		var failed = run(ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of()), Map.of(), ROOMY), new DataValue.RecordValue(Map.of()));
		assertEquals(DIVISION_BY_ZERO, failed.report().diagnostic().orElseThrow().code());
	}

	private static ComputationGraph graph(ComputationGraph.Node operation) {
		return new ComputationGraph(Map.of(), Map.of("a", new ComputationGraph.Constant(new DataValue.IntegerValue(2)),
			"b", new ComputationGraph.Constant(new DataValue.IntegerValue(3)), "op", operation), Map.of("out", "op"));
	}
	private static ComputationGraph operationGraph(ScalarOperation operation, DataType inputType) {
		return new ComputationGraph(Map.of("left", inputType, "right", inputType), Map.of(
			"left", new ComputationGraph.Input("left"), "right", new ComputationGraph.Input("right"),
			"op", new ComputationGraph.Scalar(operation, Map.of("left", "left", "right", "right"))), Map.of("out", "op"));
	}
	private static ProgramDefinition programWriting(ComputationGraph graph) {
		return new ProgramDefinition(graph, Map.of("cell", new ProgramDefinition.Cell(INTEGER, new DataValue.IntegerValue(99))), List.of(new ProgramDefinition.Write("cell", "out")));
	}
	private static ComputationMachine run(ProgramCompiler.InstalledProgram installed) { return run(installed, new DataValue.RecordValue(Map.of())); }
	private static ComputationMachine run(ProgramCompiler.InstalledProgram installed, DataValue.RecordValue inputs) {
		var machine = new ComputationMachine(installed); machine.begin(inputs);
		while (machine.report().status() == ComputationMachine.Status.RUNNING) machine.step();
		return machine;
	}
	private static DataValue sample(DataType type, String port) {
		if (type == INTEGER) return new DataValue.IntegerValue("right".equals(port) ? 2 : 6);
		if (type == DECIMAL) return new DataValue.DecimalValue("right".equals(port) ? 2.0 : 6.0);
		if (type == BOOLEAN) return new DataValue.BooleanValue(!"when_false".equals(port));
		if (type == ERROR) return new DataValue.ErrorValue("E", "m");
		if (type instanceof DataType.ListType) return new DataValue.ListValue(List.of(new DataValue.IntegerValue(1)));
		return "condition".equals(port) ? new DataValue.BooleanValue(true) : new DataValue.TextValue(port);
	}
	private static long expectedWorkUnits(ScalarOperation operation) {
		return switch (operation) {
			case REQUIRE_VALUE, LIST_LENGTH, NOT_BOOLEAN, NEGATE_INTEGER, ABS_INTEGER, NEGATE_DECIMAL, ABS_DECIMAL,
				INTEGER_TO_DECIMAL, DECIMAL_TO_INTEGER, IS_NULL, IS_ERROR, ERROR_CODE, ERROR_MESSAGE, SQRT_DECIMAL, ROUND_DECIMAL_TO_INTEGER, CEIL_DECIMAL_TO_INTEGER, FLOOR_DECIMAL_TO_INTEGER -> 4;
			case SELECT -> 6;
			default -> 5;
		};
	}
	private static void assertCode(ComputationException.Code code, Runnable action) {
		assertEquals(code, assertThrows(ComputationException.class, action::run).code());
	}
}
