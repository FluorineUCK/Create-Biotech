package com.nobodiiiii.createbiotech.content.biocomputing.computation;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationException.Code.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachineTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramCompilerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MachineCheckpointTest {
	private static final DataValue.RecordValue EMPTY = record(Map.of());

	@Test
	void everyNestedMapFilterAndCallTransitionResumesWithoutReplayOrExtraCharges() {
		var group = new ModuleDefinition.Key("group", 1);
		var predicate = new ModuleDefinition.Key("predicate", 1);
		var predicateModule = new ModuleDefinition(predicate, new ComputationGraph(Map.of("item", INTEGER),
			Map.of("input", new ComputationGraph.Input("item"),
				"call", new ComputationGraph.Call(DOUBLE, Map.of("value", "input")),
				"doubled", new ComputationGraph.Field("call", "result"),
				"limit", new ComputationGraph.Constant(integer(5)),
				"keep", new ComputationGraph.Binary(ComputationGraph.BinaryOp.LESS_INTEGER, "doubled", "limit")),
			Map.of("keep", "keep")));
		var groupModule = new ModuleDefinition(group, new ComputationGraph(Map.of("items", new DataType.ListType(INTEGER)),
			Map.of("input", new ComputationGraph.Input("items"),
				"filter", new ComputationGraph.Iterate(ComputationGraph.Iteration.FILTER, "input", predicate,
					"item", Map.of(), "keep"),
				"map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "filter", DOUBLE,
					"value", Map.of(), "result")), Map.of("result", "map")));
		var graph = new ComputationGraph(Map.of("groups", new DataType.ListType(new DataType.ListType(INTEGER))),
			Map.of("input", new ComputationGraph.Input("groups"),
				"map", new ComputationGraph.Iterate(ComputationGraph.Iteration.MAP, "input", group,
					"items", Map.of(), "result")), Map.of("result", "map"));
		var installation = ProgramCompiler.compile(program(graph),
			Map.of(group, groupModule, predicate, predicateModule, DOUBLE, doubleModule()), ROOMY);
		var input = record(Map.of("groups", new DataValue.ListValue(List.of(numbers(1, 3, 2), numbers(), numbers(4, 1)))));
		var terminal = assertEveryTransition(installation, input);
		assertEquals(new DataValue.ListValue(List.of(numbers(2, 4), numbers(), numbers(2))),
			terminal.report().output().orElseThrow().field("result"));
	}

	@Test
	void partialMultiCellCommitResumesAtomicallyAndPreservesTheSavedMemory() {
		var installation = ProgramCompiler.compile(counterProgram(), Map.of(), ROOMY);
		var terminal = assertEveryTransition(installation, EMPTY);
		assertEquals(record(Map.of("count", integer(1), "previous", integer(0))), terminal.memory());
		var machine = new ComputationMachine(installation);
		machine.begin(EMPTY);
		var checkpoint = find(machine, state -> state.frames().size() == 1
			&& state.frames().getFirst() instanceof MachineCheckpoint.CommitState commit && commit.cursor() == 1);
		var restored = ComputationMachine.restore(installation, checkpoint);
		assertEquals(record(Map.of("count", integer(0), "previous", integer(-1))), restored.memory());
		restored.step();
		assertEquals(checkpoint.memory(), restored.memory(), "preparing final write must not publish partial RAM");
		restored.step();
		assertEquals(terminal.memory(), restored.memory());
		assertEquals(checkpoint.memory(), machine.memory());
	}

	@Test
	void dynamicMemoryGrowthAndShrinkageRoundTripOnBothSidesOfCommit() {
		var graph = new ComputationGraph(Map.of("value", ANY), Map.of("v", new ComputationGraph.Input("value")), Map.of("out", "v"));
		var definition = new ProgramDefinition(graph, Map.of("cell", new ProgramDefinition.Cell(ANY, integer(0))),
			List.of(new ProgramDefinition.Write("cell", "out")));
		var installation = ProgramCompiler.compile(definition, Map.of(), ROOMY);
		var large = new DataValue.TextValue("x".repeat(200));
		assertEveryTransition(installation, record(Map.of("value", large)));
		var machine = ComputationMachine.withMemory(installation, record(Map.of("cell", large)));
		machine.begin(record(Map.of("value", integer(1))));
		for (int steps = 0; steps < 20; steps++) {
			machine = ComputationMachine.restore(installation, machine.checkpoint());
			if (machine.report().status() != ComputationMachine.Status.RUNNING) break;
			machine.step();
		}
		assertEquals(ComputationMachine.Status.SUCCEEDED, machine.report().status());
		assertEquals(integer(1), machine.memory().field("cell"));
	}

	@Test
	void everyTransitionBeforeArenaExhaustionRestoresTheSameFailure() {
		var installation = ProgramCompiler.compile(program(mapGraph()), Map.of(DOUBLE, doubleModule()), new ResourceLimits(100, 90, 1));
		var failed = assertEveryTransition(installation, record(Map.of("samples", numbers(1, 2, 3, 4, 5, 6))));
		assertEquals(ComputationMachine.Status.FAILED, failed.report().status());
		assertEquals(MEMORY_LIMIT, failed.report().diagnostic().orElseThrow().code());
	}

	@Test
	void idlePausedAndFailedStatesRoundTripAndStepDoesNotUnpause() {
		var installation = ProgramCompiler.compile(program(mapGraph()), Map.of(DOUBLE, doubleModule()), ROOMY);
		var machine = new ComputationMachine(installation);
		assertEquals(machine.checkpoint(), ComputationMachine.restore(installation, machine.checkpoint()).checkpoint());
		machine.begin(record(Map.of("samples", numbers(1, 2))));
		machine.setPaused(true);
		var restored = ComputationMachine.restore(installation, machine.checkpoint());
		assertEquals(machine.report(), restored.tick());
		restored.step();
		assertTrue(restored.paused());
		assertEquals(1, restored.report().workUnits());
		assertEquals(restored.checkpoint(), ComputationMachine.restore(installation, restored.checkpoint()).checkpoint());
		machine.reset();
		machine.begin(EMPTY);
		assertEquals(ComputationMachine.Status.FAILED, machine.report().status());
		assertEquals(machine.checkpoint(), ComputationMachine.restore(installation, machine.checkpoint()).checkpoint());
	}

	@Test
	void failedBeginCanHaveArenaBelowTheFullBaseWithoutBeingAnInvalidCheckpoint() {
		var graph = new ComputationGraph(Map.of("value", ANY), Map.of("input", new ComputationGraph.Input("value")),
			Map.of("out", "input"));
		var definition = new ProgramDefinition(graph, Map.of("cell", new ProgramDefinition.Cell(ANY, integer(0))),
			List.of(new ProgramDefinition.Write("cell", "out")));
		var installation = ProgramCompiler.compile(definition, Map.of(), new ResourceLimits(100, 20, 1));
		// A failed snapshot may retain an oversized old image, e.g. loaded into lower-capacity hardware.
		var failed = new MachineCheckpoint(record(Map.of("cell", new DataValue.TextValue("x".repeat(40)))),
			ComputationMachine.Status.FAILED, false, 0, installation.usage().initialMemoryUnits(), Optional.empty(),
			Optional.of(new ComputationMachine.Diagnostic(MEMORY_LIMIT, "event", "No room")), List.of());
		assertEquals(failed, ComputationMachine.restore(installation, failed).checkpoint());
	}

	@Test
	void persistedMemoryRequiresExactCellSchemaAndCapacityAndStartsIdle() {
		var installation = ProgramCompiler.compile(counterProgram(), Map.of(), ROOMY);
		var memory = record(Map.of("count", integer(7), "previous", integer(6)));
		var machine = ComputationMachine.withMemory(installation, memory);
		assertEquals(memory, machine.memory());
		assertEquals(ComputationMachine.Status.IDLE, machine.report().status());
		assertEquals(0, machine.report().workUnits());
		assertEquals(0, machine.report().peakMemoryUnits());
		run(machine, EMPTY);
		assertEquals(integer(8), machine.memory().field("count"));
		assertCode(INVALID_CHECKPOINT, () -> ComputationMachine.withMemory(installation, EMPTY));
		assertCode(INVALID_CHECKPOINT, () -> ComputationMachine.withMemory(installation,
			record(Map.of("count", new DataValue.TextValue("wrong"), "previous", integer(0)))));
		var graph = new ComputationGraph(Map.of(), Map.of("v", new ComputationGraph.Constant(integer(0))), Map.of("v", "v"));
		var dynamic = ProgramCompiler.compile(new ProgramDefinition(graph,
			Map.of("cell", new ProgramDefinition.Cell(ANY, integer(0))), List.of()), Map.of(), new ResourceLimits(100, 30, 1));
		assertCode(INVALID_CHECKPOINT, () -> ComputationMachine.withMemory(dynamic,
			record(Map.of("cell", new DataValue.TextValue("x".repeat(40))))));
	}

	@Test
	void rejectsMalformedGraphCursorsPrefixesTypesAndStackRoots() {
		var installation = ProgramCompiler.compile(counterProgram(), Map.of(), ROOMY);
		var machine = new ComputationMachine(installation);
		machine.begin(EMPTY);
		machine.step();
		var saved = machine.checkpoint();
		var graph = (MachineCheckpoint.GraphState) saved.frames().getFirst();
		for (int cursor : new int[] {-1, installation.root().order().size() + 1})
			assertInvalid(installation, frames(saved, List.of(new MachineCheckpoint.GraphState(Optional.empty(), cursor, EMPTY, graph.values()))));
		assertInvalid(installation, frames(saved, List.of(new MachineCheckpoint.GraphState(Optional.empty(), graph.cursor(), EMPTY, EMPTY))));
		assertInvalid(installation, frames(saved, List.of(new MachineCheckpoint.GraphState(Optional.empty(), graph.cursor(), EMPTY,
			record(Map.of(installation.root().order().getFirst(), new DataValue.TextValue("bad")))))));
		assertInvalid(installation, frames(saved, List.of(graph, graph)));
		assertInvalid(installation, frames(saved, List.of(new MachineCheckpoint.LoopState(0, numbers(), Optional.empty()))));
		assertInvalid(installation, frames(saved, List.of()));
		assertInvalid(installation, copy(saved, EMPTY, saved.status(), saved.workUnits(), saved.arenaUnits(), saved.output(), saved.diagnostic(), saved.frames()));
	}

	@Test
	void rejectsWrongPinnedModuleArgumentsLoopProgressAndReturnedChildConflicts() {
		var installation = ProgramCompiler.compile(program(mapGraph()), Map.of(DOUBLE, doubleModule()), ROOMY);
		var machine = new ComputationMachine(installation);
		machine.begin(record(Map.of("samples", numbers(1, 2))));
		var saved = find(machine, state -> state.frames().size() == 3);
		var graph = (MachineCheckpoint.GraphState) saved.frames().get(2);
		var list = new ArrayList<>(saved.frames());
		list.set(2, new MachineCheckpoint.GraphState(Optional.of(new ModuleDefinition.Key("double", 999)), graph.cursor(), graph.inputs(), graph.values()));
		assertInvalid(installation, frames(saved, list));
		list.set(2, new MachineCheckpoint.GraphState(graph.module(), graph.cursor(), record(Map.of("value", integer(99))), graph.values()));
		assertInvalid(installation, frames(saved, list));
		list.set(2, graph);
		list.set(1, new MachineCheckpoint.LoopState(3, numbers(), Optional.empty()));
		assertInvalid(installation, frames(saved, list));
		list.set(1, new MachineCheckpoint.LoopState(1, numbers(), Optional.empty()));
		assertInvalid(installation, frames(saved, list));
		list.set(1, new MachineCheckpoint.LoopState(0, numbers(), Optional.of(record(Map.of("result", integer(2))))));
		assertInvalid(installation, frames(saved, list));
		list.removeLast();
		list.set(1, new MachineCheckpoint.LoopState(0, numbers(), Optional.of(record(Map.of("wrong", integer(2))))));
		assertInvalid(installation, frames(saved, list));
	}

	@Test
	void rejectsInvalidStatusCountersArenaAndPreparedWrites() {
		var installation = ProgramCompiler.compile(counterProgram(), Map.of(), ROOMY);
		var machine = new ComputationMachine(installation);
		machine.begin(EMPTY);
		var saved = machine.checkpoint();
		assertInvalid(installation, copy(saved, saved.memory(), saved.status(), -1, saved.arenaUnits(), saved.output(), saved.diagnostic(), saved.frames()));
		for (long arena : new long[] {-1, 0, ROOMY.memoryUnits() + 1})
			assertInvalid(installation, copy(saved, saved.memory(), saved.status(), saved.workUnits(), arena, saved.output(), saved.diagnostic(), saved.frames()));
		assertInvalid(installation, copy(saved, saved.memory(), ComputationMachine.Status.IDLE, 1, 0, Optional.empty(), Optional.empty(), List.of()));
		assertInvalid(installation, copy(saved, saved.memory(), ComputationMachine.Status.SUCCEEDED, 1, saved.arenaUnits(), Optional.empty(), Optional.empty(), List.of()));
		assertInvalid(installation, copy(saved, saved.memory(), ComputationMachine.Status.FAILED, 1, saved.arenaUnits(), Optional.empty(), Optional.empty(), List.of()));
		assertInvalid(installation, copy(saved, saved.memory(), saved.status(), 0, saved.arenaUnits(), Optional.of(EMPTY), Optional.empty(), saved.frames()));
		var commit = find(machine, state -> state.frames().size() == 1 && state.frames().getFirst() instanceof MachineCheckpoint.CommitState);
		var result = ((MachineCheckpoint.CommitState) commit.frames().getFirst()).result();
		assertInvalid(installation, frames(commit, List.of(new MachineCheckpoint.CommitState(3, result))));
		assertInvalid(installation, frames(commit, List.of(new MachineCheckpoint.CommitState(1, EMPTY))));
		assertInvalid(installation, copy(commit, commit.memory(), commit.status(), commit.workUnits(),
			installation.usage().constantUnits() + installation.usage().initialMemoryUnits() + commit.memory().storageUnits(),
			Optional.empty(), Optional.empty(), commit.frames()));
	}

	@Test
	void pendingDynamicWriteMayFailButAnAlreadyPreparedWriteMustBeWellTyped() {
		var graph = new ComputationGraph(Map.of("value", ANY), Map.of("input", new ComputationGraph.Input("value")),
			Map.of("out", "input"));
		var installation = ProgramCompiler.compile(new ProgramDefinition(graph,
			Map.of("cell", new ProgramDefinition.Cell(INTEGER, integer(0))),
			List.of(new ProgramDefinition.Write("cell", "out"))), Map.of(), ROOMY);
		var machine = new ComputationMachine(installation);
		machine.begin(record(Map.of("value", new DataValue.TextValue("wrong"))));
		var saved = find(machine, state -> state.frames().getFirst() instanceof MachineCheckpoint.CommitState);
		var restored = ComputationMachine.restore(installation, saved);
		restored.step();
		assertEquals(TYPE_MISMATCH, restored.report().diagnostic().orElseThrow().code());
		var commit = (MachineCheckpoint.CommitState) saved.frames().getFirst();
		assertInvalid(installation, copy(saved, saved.memory(), saved.status(), saved.workUnits() + 1,
			ROOMY.memoryUnits(), Optional.empty(), Optional.empty(), List.of(new MachineCheckpoint.CommitState(1, commit.result()))));
	}

	@Test
	void arenaLowerBoundIncludesGraphValuesLoopBuilderReturnAndEachPreparedWrite() {
		var installation = ProgramCompiler.compile(program(mapGraph()), Map.of(DOUBLE, doubleModule()), ROOMY);
		var machine = new ComputationMachine(installation);
		machine.begin(record(Map.of("samples", numbers(1, 2))));
		machine.step();
		var saved = machine.checkpoint();
		// No discarded data yet: subtracting the charged computed list alone must be rejected.
		assertInvalid(installation, copy(saved, saved.memory(), saved.status(), saved.workUnits(), saved.arenaUnits() - 1,
			Optional.empty(), Optional.empty(), saved.frames()));
		saved = find(machine, state -> state.frames().size() == 2
			&& state.frames().get(1) instanceof MachineCheckpoint.LoopState loop && loop.returned().isPresent());
		// Hand count: two empty RAM images (2), root inputs (12), root computed list (3),
		// loop builder header (1), returned {result: 2} record (9) = 27 live charged units.
		assertInvalid(installation, copy(saved, saved.memory(), saved.status(), saved.workUnits(), 26,
			Optional.empty(), Optional.empty(), saved.frames()));
		var counter = ProgramCompiler.compile(counterProgram(), Map.of(), ROOMY);
		machine = new ComputationMachine(counter);
		machine.begin(EMPTY);
		saved = find(machine, state -> state.frames().getFirst() instanceof MachineCheckpoint.CommitState commit && commit.cursor() == 1);
		// Counter RAM {count, previous} costs 18, reset image 18, constant 1, output {next, old} 12,
		// copied old RAM 18, prepared count write 7: 74 live units, irrespective of discarded graph data.
		assertInvalid(counter, copy(saved, saved.memory(), saved.status(), saved.workUnits(), 73,
			Optional.empty(), Optional.empty(), saved.frames()));
	}

	@Test
	void snapshotFrameListCannotBeMutatedThroughTheConstructorOrAccessor() {
		var installation = ProgramCompiler.compile(counterProgram(), Map.of(), ROOMY);
		var machine = new ComputationMachine(installation);
		machine.begin(EMPTY);
		var source = new ArrayList<>(machine.checkpoint().frames());
		var saved = frames(machine.checkpoint(), source);
		source.clear();
		assertEquals(1, saved.frames().size());
		assertThrows(UnsupportedOperationException.class, () -> saved.frames().clear());
		assertEquals(saved, ComputationMachine.restore(installation, saved).checkpoint());
	}

	private static ComputationMachine assertEveryTransition(ProgramCompiler.InstalledProgram installation, DataValue.RecordValue input) {
		var baseline = run(new ComputationMachine(installation), input);
		var machine = new ComputationMachine(installation);
		machine.begin(input);
		for (int steps = 0; steps < 2000; steps++) {
			var snapshot = machine.checkpoint();
			var restored = ComputationMachine.restore(installation, snapshot);
			assertEquals(snapshot, restored.checkpoint(), "restoration itself must not execute or charge anything");
			for (int finish = 0; restored.report().status() == ComputationMachine.Status.RUNNING && finish < 2000; finish++) restored.step();
			assertEquals(baseline.report(), restored.report());
			assertEquals(baseline.memory(), restored.memory());
			if (machine.report().status() != ComputationMachine.Status.RUNNING) return machine;
			machine.step();
		}
		throw new AssertionError("execution must terminate");
	}

	private static MachineCheckpoint find(ComputationMachine machine, java.util.function.Predicate<MachineCheckpoint> match) {
		for (int steps = 0; steps < 2000; steps++) {
			var state = machine.checkpoint();
			if (match.test(state)) return state;
			assertEquals(ComputationMachine.Status.RUNNING, state.status(), "transition must exist");
			machine.step();
		}
		throw new AssertionError("transition not found");
	}

	private static MachineCheckpoint frames(MachineCheckpoint source, List<MachineCheckpoint.FrameState> frames) {
		return copy(source, source.memory(), source.status(), source.workUnits(), source.arenaUnits(), source.output(), source.diagnostic(), frames);
	}
	private static MachineCheckpoint copy(MachineCheckpoint source, DataValue.RecordValue memory, ComputationMachine.Status status,
		long work, long arena, Optional<DataValue.RecordValue> output, Optional<ComputationMachine.Diagnostic> diagnostic,
		List<MachineCheckpoint.FrameState> frames) {
		return new MachineCheckpoint(memory, status, source.paused(), work, arena, output, diagnostic, frames);
	}
	private static void assertInvalid(ProgramCompiler.InstalledProgram installation, MachineCheckpoint checkpoint) {
		assertCode(INVALID_CHECKPOINT, () -> ComputationMachine.restore(installation, checkpoint));
	}
}
