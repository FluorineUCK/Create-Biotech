package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompilerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComputationMachineTest {
	@Test
	void nestedModulesMapNamedCapturesAndSelectOneOfSeveralOutputs() {
		var shifted = new ModuleDefinition.Key("shifted-double", 1);
		var module = new ModuleDefinition(shifted, new ComputationGraph(Map.of("sample", INTEGER, "offset", INTEGER),
			Map.of("x", new ComputationGraph.Input("sample"), "offset", new ComputationGraph.Input("offset"),
				"call", new ComputationGraph.Call(DOUBLE, Map.of("value", "x")),
				"doubled", new ComputationGraph.Field("call", "result"),
				"sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "doubled", "offset")),
			Map.of("scaled", "sum", "original", "x")));
		var graph = new ComputationGraph(Map.of("samples", new DataType.ListType(INTEGER), "offset", INTEGER),
			Map.of("data", new ComputationGraph.Input("samples"), "offset", new ComputationGraph.Input("offset"),
				"map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "data", shifted,
					"sample", Map.of("offset", "offset"), "scaled")), Map.of("results", "map"));
		var installation = ProgramCompiler.compile(program(graph), Map.of(DOUBLE, doubleModule(), shifted, module), ROOMY);
		assertEquals(10, installation.usage().programUnits()); // 3 root + 5 outer + 2 inner, independent of item count.
		assertEquals(2, installation.modules().size());
		var machine = run(new ComputationMachine(installation), record(Map.of("samples", numbers(1, 2), "offset", integer(3))));
		assertEquals(numbers(5, 7), machine.report().output().orElseThrow().field("results"));
	}

	@Test
	void reconstructingARecordCanChangeDataWithoutChangingTheSourceEvenForEmptyDataKeys() {
		var original = record(Map.of("", integer(3)));
		var graph = new ComputationGraph(Map.of("source", ANY), Map.of(
			"input", new ComputationGraph.Input("source"), "field", new ComputationGraph.Field("input", ""),
			"one", new ComputationGraph.Constant(integer(1)),
			"next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "field", "one"),
			"copy", new ComputationGraph.MakeRecord(Map.of("", "next"))), Map.of("modified", "copy"));
		var machine = run(new ComputationMachine(compile(graph)), record(Map.of("source", original)));
		assertEquals(record(Map.of("", integer(4))), machine.report().output().orElseThrow().field("modified"));
		assertEquals(integer(3), original.field(""));
	}

	static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
	static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
	static DataValue.ListValue numbers(long... values) {
		var result = new ArrayList<DataValue>();
		for (long value : values) result.add(integer(value));
		return new DataValue.ListValue(result);
	}

	static ComputationMachine run(ComputationMachine machine, DataValue.RecordValue input) {
		machine.begin(input);
		for (int slices = 0; machine.report().status() == ComputationMachine.Status.RUNNING; slices++) {
			assertTrue(slices < 10_000, "execution must make progress");
			machine.tick();
		}
		return machine;
	}

	static ComputationGraph mapGraph() {
		return new ComputationGraph(Map.of("samples", new DataType.ListType(INTEGER)),
			Map.of("input", new ComputationGraph.Input("samples"),
				"map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "input", DOUBLE,
					"value", Map.of(), "result")), Map.of("samples", "map"));
	}

	static ComputationMachine mapMachine(long throughput, long memory) {
		return new ComputationMachine(ProgramCompiler.compile(program(mapGraph()), Map.of(DOUBLE, doubleModule()),
			new ResourceLimits(100, memory, throughput)));
	}

	@Test
	void iterationsYieldWithoutTruncationAndThroughputChangesLatencyNotAnswers() {
		var slow = mapMachine(1, 100_000);
		var input = record(Map.of("samples", numbers(1, 2, 3, 4)));
		slow.begin(input);
		slow.tick();
		assertEquals(ComputationMachine.Status.RUNNING, slow.report().status());
		assertEquals(1, slow.report().workUnits());
		assertTrue(slow.report().output().isEmpty());
		while (slow.report().status() == ComputationMachine.Status.RUNNING) slow.tick();
		var fast = run(mapMachine(1000, 100_000), input);
		assertEquals(record(Map.of("samples", numbers(2, 4, 6, 8))), slow.report().output().orElseThrow());
		assertEquals(slow.report().output(), fast.report().output());
		assertEquals(slow.report().workUnits(), fast.report().workUnits());
		assertTrue(slow.report().workUnits() > 4, "each module invocation consumes work");
	}

	@Test
	void filtersEnvironmentalRecordsThroughNamedItemAndCapturedThreshold() {
		var key = new ModuleDefinition.Key("below-temperature", 1);
		var sampleType = new DataType.RecordType(Map.of("name", TEXT, "temperature", INTEGER));
		var module = new ModuleDefinition(key, new ComputationGraph(Map.of("sample", sampleType, "threshold", INTEGER),
			Map.of("s", new ComputationGraph.Input("sample"), "limit", new ComputationGraph.Input("threshold"),
				"temperature", new ComputationGraph.Field("s", "temperature"),
				"test", new ComputationGraph.Binary(ComputationGraph.BinaryOp.LESS_INTEGER, "temperature", "limit")),
			Map.of("keep", "test")));
		var graph = new ComputationGraph(Map.of("measurements", new DataType.ListType(sampleType), "limit", INTEGER),
			Map.of("data", new ComputationGraph.Input("measurements"), "limit", new ComputationGraph.Input("limit"),
				"filter", new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER, "data", key,
					"sample", Map.of("threshold", "limit"), "keep")),
			Map.of("coldLocations", "filter"));
		DataValue cold = record(Map.of("name", new DataValue.TextValue("forest"), "temperature", integer(4)));
		DataValue hot = record(Map.of("name", new DataValue.TextValue("desert"), "temperature", integer(30)));
		var source = new ArrayList<>(List.of(cold, hot));
		var frozen = new DataValue.ListValue(source);
		var machine = new ComputationMachine(ProgramCompiler.compile(program(graph), Map.of(key, module), ROOMY));
		machine.begin(record(Map.of("measurements", frozen, "limit", integer(10))));
		source.clear();
		while (machine.report().status() == ComputationMachine.Status.RUNNING) machine.tick();
		assertEquals(record(Map.of("coldLocations", new DataValue.ListValue(List.of(cold)))),
			machine.report().output().orElseThrow());
		assertEquals(2, frozen.values().size());
	}

	static ProgramDefinition counterProgram() {
		var graph = new ComputationGraph(Map.of(),
			Map.of("old", new ComputationGraph.ReadMemory("count"), "again", new ComputationGraph.ReadMemory("count"),
				"one", new ComputationGraph.Constant(integer(1)),
				"next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "one")),
			Map.of("next", "next", "old", "again"));
		return new ProgramDefinition(graph,
			Map.of("count", new ProgramDefinition.Cell(INTEGER, integer(0)),
				"previous", new ProgramDefinition.Cell(INTEGER, integer(-1))),
			List.of(new ProgramDefinition.Write("count", "next"), new ProgramDefinition.Write("previous", "old")));
	}

	@Test
	void readsOldMemoryUntilAtomicCommitAndMachinesNeverShareRam() {
		var installation = ProgramCompiler.compile(counterProgram(), Map.of(), new ResourceLimits(100, 1000, 1));
		var a = new ComputationMachine(installation);
		var b = new ComputationMachine(installation);
		var initial = a.memory();
		a.begin(record(Map.of()));
		while (a.report().status() == ComputationMachine.Status.RUNNING) {
			assertEquals(initial, a.memory(), "no partial commit between work slices");
			a.tick();
		}
		assertEquals(record(Map.of("count", integer(1), "previous", integer(0))), a.memory());
		assertEquals(initial, b.memory());
		run(a, record(Map.of()));
		assertEquals(record(Map.of("count", integer(2), "previous", integer(1))), a.memory());
		a.reset();
		assertEquals(initial, a.memory());
	}

	@Test
	void pauseAndStepOnlyAffectThisMachineAndResetCancelsPendingEvent() {
		var machine = mapMachine(100, 100_000);
		machine.begin(record(Map.of("samples", numbers(2, 4))));
		machine.setPaused(true);
		machine.tick();
		assertEquals(0, machine.report().workUnits());
		machine.step();
		assertEquals(1, machine.report().workUnits());
		assertTrue(machine.paused());
		assertCode(BUSY, () -> machine.begin(record(Map.of("samples", numbers(9)))));
		machine.reset();
		assertEquals(ComputationMachine.Status.IDLE, machine.report().status());
		assertTrue(machine.report().output().isEmpty());
		machine.setPaused(false);
		run(machine, record(Map.of("samples", numbers(9))));
		assertEquals(numbers(18), machine.report().output().orElseThrow().field("samples"));
	}

	@Test
	void aLateDynamicWriteTypeErrorRollsBackAllWrites() {
		var graph = new ComputationGraph(Map.of("first", ANY, "second", ANY),
			Map.of("a", new ComputationGraph.Input("first"), "b", new ComputationGraph.Input("second")),
			Map.of("first", "a", "second", "b"));
		var program = new ProgramDefinition(graph,
			Map.of("a", new ProgramDefinition.Cell(INTEGER, integer(0)), "b", new ProgramDefinition.Cell(INTEGER, integer(0))),
			List.of(new ProgramDefinition.Write("a", "first"), new ProgramDefinition.Write("b", "second")));
		var machine = new ComputationMachine(ProgramCompiler.compile(program, Map.of(), ROOMY));
		var initial = machine.memory();
		run(machine, record(Map.of("first", integer(5), "second", new DataValue.TextValue("wrong"))));
		assertEquals(ComputationMachine.Status.FAILED, machine.report().status());
		assertEquals(TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
		assertEquals(initial, machine.memory());
		assertTrue(machine.report().output().isEmpty());
		run(machine, record(Map.of("first", integer(5), "second", integer(6))));
		assertEquals(record(Map.of("a", integer(5), "b", integer(6))), machine.memory());
	}

	@Test
	void missingFieldsAndArithmeticOverflowAreExplicitFailures() {
		var graph = new ComputationGraph(Map.of("data", ANY),
			Map.of("input", new ComputationGraph.Input("data"), "field", new ComputationGraph.Field("input", "missing")),
			Map.of("value", "field"));
		var machine = run(new ComputationMachine(compile(graph)), record(Map.of("data", record(Map.of()))));
		assertEquals(MISSING_FIELD, machine.report().diagnostic().orElseThrow().code());
		graph = new ComputationGraph(Map.of(),
			Map.of("large", new ComputationGraph.Constant(integer(Long.MAX_VALUE)),
				"sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "large", "large")),
			Map.of("value", "sum"));
		machine = run(new ComputationMachine(compile(graph)), record(Map.of()));
		assertEquals(ARITHMETIC_OVERFLOW, machine.report().diagnostic().orElseThrow().code());
	}

	@Test
	void memoryExhaustionFailsInsteadOfTruncatingAndLeavesOldState() {
		var machine = mapMachine(1, 90);
		var initial = machine.memory();
		run(machine, record(Map.of("samples", numbers(1, 2, 3, 4, 5, 6))));
		assertEquals(ComputationMachine.Status.FAILED, machine.report().status());
		assertEquals(MEMORY_LIMIT, machine.report().diagnostic().orElseThrow().code());
		assertEquals(initial, machine.memory());
		assertTrue(machine.report().output().isEmpty());
		assertTrue(machine.report().workUnits() > 1, "intermediates, not only initial inputs, exhaust memory");
		run(machine, record(Map.of("samples", numbers())));
		assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
	}

	@Test
	void frozenInputDataSizeIsCheckedBeforeExecutingAndNoExtraPortsAreIgnored() {
		var machine = mapMachine(1, 90);
		machine.begin(record(Map.of("samples", numbers(1), "unexpected", integer(0))));
		assertEquals(TYPE_MISMATCH, machine.report().diagnostic().orElseThrow().code());
		var many = new ArrayList<DataValue>();
		for (int n = 0; n < 100; n++) many.add(integer(n));
		machine.begin(record(Map.of("samples", new DataValue.ListValue(many))));
		assertEquals(MEMORY_LIMIT, machine.report().diagnostic().orElseThrow().code());
		assertEquals(0, machine.report().workUnits());
	}

	@Test
	void installedCallsKeepOldDefinitionAfterLibraryReplacement() {
		var library = new LinkedHashMap<ModuleDefinition.Key, ModuleDefinition>();
		library.put(DOUBLE, doubleModule());
		var installed = ProgramCompiler.compile(program(mapGraph()), library, ROOMY);
		library.put(DOUBLE, new ModuleDefinition(DOUBLE, new ComputationGraph(Map.of("value", INTEGER),
			Map.of("n", new ComputationGraph.Constant(integer(999))), Map.of("result", "n"))));
		var machine = run(new ComputationMachine(installed), record(Map.of("samples", numbers(3))));
		assertEquals(numbers(6), machine.report().output().orElseThrow().field("samples"));
	}
}
