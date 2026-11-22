package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventEntryCompilerTest {
	private static final ResourceLimits ROOMY = new ResourceLimits(100, 1_000, 100);
	private static final ModuleDefinition.Key IDENTITY = new ModuleDefinition.Key("identity", 1);

	@Test
	void independentlyTypedEntriesShareMemoryAndRetainCanonicalDefinition() {
		var root = literalGraph(1);
		var message = new ComputationGraph(Map.of("amount", INTEGER),
			Map.of("amount", new ComputationGraph.Input("amount")), Map.of("out", "amount"));
		var definition = definition(root, List.of(write()), Map.of("message",
			new ProgramDefinition.Entry(message, List.of(write()), List.of())));
		var installed = ProgramCompiler.compile(definition, Map.of(), ROOMY);

		var rootMachine = run(new ComputationMachine(installed), record(Map.of()));
		assertEquals(integer(1), rootMachine.memory().field("n"));
		var selected = installed.select("message");
		var messageMachine = run(ComputationMachine.withMemory(selected, rootMachine.memory()),
			record(Map.of("amount", integer(7))));
		assertEquals(integer(7), messageMachine.memory().field("n"));
		assertEquals(4, selected.usage().programUnits());
		assertEquals(1, selected.usage().constantUnits());
		assertEquals(definition, selected.definition());
		assertEquals(root, selected.definition().graph());
		assertThrows(IllegalArgumentException.class, () -> installed.select("missing"));
	}

	@Test
	void writeConflictsAreScopedToOneEntryAndUnusedEntriesAreValidated() {
		var valid = definition(literalGraph(1), List.of(write()), Map.of("message",
			new ProgramDefinition.Entry(literalGraph(2), List.of(write()), List.of())));
		assertDoesNotThrow(() -> ProgramCompiler.compile(valid, Map.of(), ROOMY));
		var duplicate = definition(literalGraph(1), List.of(), Map.of("message",
			new ProgramDefinition.Entry(literalGraph(2), List.of(write(), write()), List.of())));
		assertCode(WRITE_CONFLICT, () -> ProgramCompiler.compile(duplicate, Map.of(), ROOMY));
		var invalidUnused = definition(literalGraph(1), List.of(), Map.of("message",
			new ProgramDefinition.Entry(new ComputationGraph(Map.of("amount", INTEGER),
				Map.of("amount", new ComputationGraph.Input("amount")), Map.of("out", "amount")),
				List.of(), List.of(new ProgramDefinition.Send("out", "out")))));
		assertCode(TYPE_MISMATCH, () -> ProgramCompiler.compile(invalidUnused, Map.of(), ROOMY));
	}

	@Test
	void allEntryCostsAreGlobalAndSharedModulesCountOnce() {
		var module = new ModuleDefinition(IDENTITY, new ComputationGraph(Map.of("value", INTEGER),
			Map.of("value", new ComputationGraph.Input("value")), Map.of("result", "value")));
		var root = callGraph(new ComputationGraph.Constant(integer(1)));
		var message = callGraph(new ComputationGraph.Constant(integer(22)));
		var installed = ProgramCompiler.compile(definition(root, List.of(write()), Map.of("message",
			new ProgramDefinition.Entry(message, List.of(write()), List.of()))), Map.of(IDENTITY, module), ROOMY);
		assertEquals(9, installed.usage().programUnits()); // 3 nodes + write per entry, one shared module node.
		assertEquals(2, installed.usage().constantUnits()); // Integer literals 1 and 22.
		assertEquals(4, installed.usage().initialMemoryUnits()); // record + field header + "n" + integer.
		assertEquals(1, installed.modules().size());
		var selected = installed.select("message");
		assertEquals(9, selected.usage().programUnits());
		assertEquals(2, selected.usage().constantUnits());
		assertCode(PROGRAM_LIMIT, () -> ProgramCompiler.compile(installed.definition(), Map.of(IDENTITY, module),
			new ResourceLimits(8, 1_000, 1)));
		assertCode(MEMORY_LIMIT, () -> ProgramCompiler.compile(installed.definition(), Map.of(IDENTITY, module),
			new ResourceLimits(100, 9, 1)));
	}

	private static ProgramDefinition definition(ComputationGraph root, List<ProgramDefinition.Write> writes,
		Map<String, ProgramDefinition.Entry> entries) {
		return new ProgramDefinition(root, Map.of("n", new ProgramDefinition.Cell(INTEGER, integer(0))),
			writes, List.of(), entries);
	}
	private static ProgramDefinition.Write write() { return new ProgramDefinition.Write("n", "out"); }
	private static ComputationGraph literalGraph(long value) {
		return new ComputationGraph(Map.of(), Map.of("value", new ComputationGraph.Constant(integer(value))), Map.of("out", "value"));
	}
	private static ComputationGraph callGraph(ComputationGraph.Node source) {
		return new ComputationGraph(Map.of(), Map.of("source", source,
			"call", new ComputationGraph.Call(IDENTITY, Map.of("value", "source")),
			"result", new ComputationGraph.Field("call", "result")), Map.of("out", "result"));
	}
	private static ComputationMachine run(ComputationMachine machine, DataValue.RecordValue input) {
		machine.begin(input);
		while (machine.report().status() == ComputationMachine.Status.RUNNING) machine.tick();
		assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
		return machine;
	}
	private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
	private static DataValue.RecordValue record(Map<String, DataValue> fields) { return new DataValue.RecordValue(fields); }
	private static void assertCode(ComputationException.Code code, org.junit.jupiter.api.function.Executable body) {
		assertEquals(code, assertThrows(ComputationException.class, body).code());
	}
}
