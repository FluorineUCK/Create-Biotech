package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProgramCompilerTest {
	@Test
	void rebindingLimitsPreservesSelectedEntryAndRejectsCurrentCapacityShortfalls() {
		var graph = new ComputationGraph(Map.of("endpoint", TEXT, "payload", new DataType.RecordType(Map.of())),
			Map.of("endpoint", new ComputationGraph.Input("endpoint"), "payload", new ComputationGraph.Input("payload"),
				"n", new ComputationGraph.Constant(new DataValue.IntegerValue(7))),
			Map.of("destination", "endpoint", "message", "payload", "value", "n"));
		var writes = List.of(new ProgramDefinition.Write("count", "value"));
		var sends = List.of(new ProgramDefinition.Send("destination", "message"));
		var definition = new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
			Map.of("count", new ProgramDefinition.Cell(INTEGER, new DataValue.IntegerValue(0))), List.of(), List.of(),
			Map.of("event", new ProgramDefinition.Entry(graph, writes, sends)));
		var installed = ProgramCompiler.compile(definition, Map.of(), ROOMY);
		var selected = installed.select("event");
		var usage = selected.usage();
		var exact = new ResourceLimits(usage.programUnits(), usage.constantUnits() + 2 * usage.initialMemoryUnits(), 1);
		var rebound = selected.withLimits(exact);
		assertSame(selected.root(), rebound.root()); assertEquals(writes, rebound.writes()); assertEquals(sends, rebound.sends());
		assertEquals(exact, rebound.limits()); assertEquals(ROOMY, selected.limits());
		assertEquals(installed.root(), rebound.select(null).root());
		assertTrue(rebound.select(null).writes().isEmpty()); assertTrue(rebound.select(null).sends().isEmpty());
		for (var limited : List.of(new ResourceLimits(exact.programUnits() - 1, exact.memoryUnits(), 1),
			new ResourceLimits(exact.programUnits(), exact.memoryUnits() - 1, 1))) {
			var cold = assertThrows(ComputationException.class, () -> ProgramCompiler.compile(definition, Map.of(), limited));
			var warm = assertThrows(ComputationException.class, () -> selected.withLimits(limited));
			assertEquals(cold.code(), warm.code()); assertEquals(cold.location(), warm.location());
			assertEquals(cold.getMessage(), warm.getMessage());
		}
	}
	@Test
	void rejectsBlankPortAndNodeNamesAndShadowedIterationInputs() {
		assertCode(INVALID_GRAPH, () -> new ComputationGraph.Input(" "));
		assertCode(INVALID_GRAPH, () -> new ComputationGraph(Map.of(),
			Map.of("", new ComputationGraph.Constant(new DataValue.IntegerValue(1))), Map.of()));
		assertCode(INVALID_GRAPH, () -> new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP,
			"list", DOUBLE, "value", Map.of("value", "other"), "result"));
	}

	static final ResourceLimits ROOMY = new ResourceLimits(1_000, 100_000, 10);
	static final ModuleDefinition.Key DOUBLE = new ModuleDefinition.Key("double", 1);

	static ModuleDefinition doubleModule() {
		return new ModuleDefinition(DOUBLE, new ComputationGraph(Map.of("value", INTEGER),
			Map.of("x", new ComputationGraph.Input("value"),
				"twice", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "x", "x")),
			Map.of("result", "twice")));
	}

	static ProgramDefinition program(ComputationGraph graph) {
		return new ProgramDefinition(graph, Map.of(), List.of());
	}

	static ProgramCompiler.InstalledProgram compile(ComputationGraph graph) {
		return ProgramCompiler.compile(program(graph), Map.of(), ROOMY);
	}

	@Test
	void graphUsesDependenciesNotInsertionOrCanvasOrder() {
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		nodes.put("sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "one", "one"));
		nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
		var installed = compile(new ComputationGraph(Map.of(), nodes, Map.of("answer", "sum")));
		assertEquals(List.of("one", "sum"), installed.root().order());
	}

	@Test
	void moduleBodyCountsOnceAndEveryCallSiteCounts() {
		var nodes = new LinkedHashMap<String, ComputationGraph.Node>();
		nodes.put("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)));
		nodes.put("a", new ComputationGraph.Call(DOUBLE, Map.of("value", "one")));
		nodes.put("b", new ComputationGraph.Call(DOUBLE, Map.of("value", "one")));
		var graph = new ComputationGraph(Map.of(), nodes, Map.of("first", "a", "second", "b"));
		var library = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
		library.put(DOUBLE, doubleModule());
		var installed = ProgramCompiler.compile(program(graph), library, ROOMY);
		library.clear();
		assertEquals(5, installed.usage().programUnits());
		assertEquals(1, installed.usage().constantUnits());
		assertEquals(1, installed.modules().size());
		assertThrows(UnsupportedOperationException.class, () -> installed.modules().clear());
		assertCode(PROGRAM_LIMIT, () -> ProgramCompiler.compile(program(graph), Map.of(DOUBLE, doubleModule()),
			new ResourceLimits(4, 1000, 1)));
	}

	@Test
	void pinsExactRevisionInsteadOfSilentlyChoosingLatest() {
		var graph = new ComputationGraph(Map.of("value", INTEGER),
			Map.of("x", new ComputationGraph.Input("value"),
				"call", new ComputationGraph.Call(new ModuleDefinition.Key("double", 2), Map.of("value", "x"))),
			Map.of("answer", "call"));
		assertCode(UNKNOWN_MODULE, () -> ProgramCompiler.compile(program(graph), Map.of(DOUBLE, doubleModule()), ROOMY));
	}

	@Test
	void rejectsMissingLinksPureCyclesAndRecursiveModules() {
		assertCode(MISSING_REFERENCE, () -> compile(new ComputationGraph(Map.of(),
			Map.of("a", new ComputationGraph.Field("missing", "value")), Map.of("x", "a"))));
		assertCode(GRAPH_CYCLE, () -> compile(new ComputationGraph(Map.of(),
			Map.of("a", new ComputationGraph.Field("b", "value"), "b", new ComputationGraph.Field("a", "value")),
			Map.of("x", "a"))));
		var recursive = new ComputationGraph(Map.of(),
			Map.of("self", new ComputationGraph.Call(DOUBLE, Map.of())), Map.of("x", "self"));
		assertCode(RECURSIVE_MODULE, () -> ProgramCompiler.compile(program(recursive),
			Map.of(DOUBLE, new ModuleDefinition(DOUBLE, recursive)), ROOMY));
	}

	@Test
	void pureModulesCannotReadComputerMemory() {
		var impure = new ModuleDefinition(DOUBLE, new ComputationGraph(Map.of(),
			Map.of("ram", new ComputationGraph.ReadMemory("count")), Map.of("result", "ram")));
		var graph = new ComputationGraph(Map.of(),
			Map.of("call", new ComputationGraph.Call(DOUBLE, Map.of())), Map.of("answer", "call"));
		assertCode(INVALID_GRAPH, () -> ProgramCompiler.compile(program(graph), Map.of(DOUBLE, impure), ROOMY));
	}

	@Test
	void checksNamedArgumentsTypesAndMemoryWritesBeforeInstallation() {
		var graph = new ComputationGraph(Map.of(),
			Map.of("text", new ComputationGraph.Constant(new DataValue.TextValue("bad")),
				"call", new ComputationGraph.Call(DOUBLE, Map.of("value", "text"))), Map.of("answer", "call"));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(program(graph), Map.of(DOUBLE, doubleModule()), ROOMY));
		var result = literal(new DataValue.IntegerValue(1));
		var cells = Map.of("count", new ProgramDefinition.Cell(INTEGER, new DataValue.IntegerValue(0)));
		assertCode(WRITE_CONFLICT, () -> ProgramCompiler.compile(new ProgramDefinition(result, cells,
			List.of(new ProgramDefinition.Write("count", "value"), new ProgramDefinition.Write("count", "value"))), Map.of(), ROOMY));
		assertCode(MISSING_REFERENCE, () -> ProgramCompiler.compile(new ProgramDefinition(result, cells,
			List.of(new ProgramDefinition.Write("other", "value"))), Map.of(), ROOMY));
	}

	@Test
	void dataSizeIsSeparateFromCodeSizeAndCannotHideInOneLiteral() {
		var small = literal(new DataValue.TextValue("a"));
		var large = literal(new DataValue.TextValue("a".repeat(100)));
		assertEquals(compile(small).usage().programUnits(), compile(large).usage().programUnits());
		assertEquals(101, compile(large).usage().constantUnits());
		assertCode(MEMORY_LIMIT, () -> ProgramCompiler.compile(program(large), Map.of(), new ResourceLimits(10, 20, 1)));
	}

	@Test
	void disconnectedDraftNodesAreNotInstalledOrExecuted() {
		var graph = new ComputationGraph(Map.of(),
			Map.of("one", new ComputationGraph.Constant(new DataValue.IntegerValue(1)),
				"unused", new ComputationGraph.Call(DOUBLE, Map.of())), Map.of("n", "one"));
		assertEquals(1, compile(graph).usage().programUnits());
		assertTrue(compile(graph).modules().isEmpty());
	}

	@Test
	void sendBindingsAreTypedPreservedAndChargedAsProgramUnits() {
		var payloadType = new DataType.RecordType(Map.of("amount", INTEGER));
		var graph = new ComputationGraph(Map.of("endpoint", TEXT, "payload", payloadType),
			Map.of("endpointNode", new ComputationGraph.Input("endpoint"),
				"payloadNode", new ComputationGraph.Input("payload")),
			Map.of("destination", "endpointNode", "message", "payloadNode"));
		var sends = new java.util.ArrayList<>(List.of(new ProgramDefinition.Send("destination", "message")));
		var installed = ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of(), sends), Map.of(), ROOMY);
		sends.clear();
		assertEquals(List.of(new ProgramDefinition.Send("destination", "message")), installed.sends());
		assertEquals(3, installed.usage().programUnits());
		assertThrows(UnsupportedOperationException.class,
			() -> installed.sends().add(new ProgramDefinition.Send("destination", "message")));
	}

	@Test
	void sendBindingsRejectMissingOrIncorrectlyTypedOutputs() {
		var record = new DataValue.RecordValue(Map.of("amount", new DataValue.IntegerValue(1)));
		var graph = new ComputationGraph(Map.of(),
			Map.of("text", new ComputationGraph.Constant(new DataValue.TextValue("factory:input")),
				"record", new ComputationGraph.Constant(record),
				"number", new ComputationGraph.Constant(new DataValue.IntegerValue(1))),
			Map.of("endpoint", "text", "payload", "record", "number", "number"));
		assertCode(MISSING_REFERENCE, () -> ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of(),
			List.of(new ProgramDefinition.Send("missing", "payload"))), Map.of(), ROOMY));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of(),
			List.of(new ProgramDefinition.Send("number", "payload"))), Map.of(), ROOMY));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(new ProgramDefinition(graph, Map.of(), List.of(),
			List.of(new ProgramDefinition.Send("endpoint", "number"))), Map.of(), ROOMY));
	}

	static ComputationGraph literal(DataValue value) {
		return new ComputationGraph(Map.of(), Map.of("literal", new ComputationGraph.Constant(value)), Map.of("value", "literal"));
	}

	static void assertCode(ComputationException.Code code, org.junit.jupiter.api.function.Executable body) {
		assertEquals(code, assertThrows(ComputationException.class, body).code());
	}
}
