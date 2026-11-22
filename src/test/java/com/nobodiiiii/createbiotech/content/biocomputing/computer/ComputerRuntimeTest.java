package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerRuntimeTest {
	static final ResourceLimits HARDWARE = new ResourceLimits(1000, 1_000_000, 1);
	static final ArchiveLimits SAVE_LIMITS = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
	static final DataValue.RecordValue EMPTY = new DataValue.RecordValue(Map.of());

	@Test void computersOwnIndependentMemoryAndUnloadDoesNotMeanReset() {
		var a = computer(counter(1)); var b = computer(counter(1));
		a.begin(EMPTY); b.begin(EMPTY); a.tick();
		a.unload(); var frozen = a.snapshot(); a.tick(); a.step();
		assertEquals(frozen, a.snapshot());
		assertThrows(ComputationException.class, () -> a.begin(EMPTY));
		finish(b); assertEquals(integer(1), b.memory().field("count"));
		assertEquals(integer(0), a.memory().field("count"));
		a.load(); finish(a); assertEquals(b.memory(), a.memory());
		a.begin(EMPTY); finish(a);
		assertEquals(integer(2), a.memory().field("count"));
		assertEquals(integer(1), b.memory().field("count"));
	}

	@Test void replacementWaitsForAnEventBoundaryAndPreservesTheFinalOldMemory() {
		var computer = computer(counter(1)); computer.begin(EMPTY); computer.step();
		assertEquals(ComputerRuntime.InstallResult.QUEUED, computer.requestInstall(counter(10), ComputerRuntime.MemoryPolicy.PRESERVE));
		assertThrows(ComputationException.class, () -> computer.requestInstall(counter(3), ComputerRuntime.MemoryPolicy.RESET));
		finish(computer);
		assertEquals(integer(1), computer.memory().field("count"));
		assertEquals(integer(1), computer.report().output().orElseThrow().field("next"));
		assertTrue(computer.snapshot().pending().isPresent());
		computer.begin(EMPTY); finish(computer);
		assertEquals(integer(11), computer.memory().field("count"));
		assertTrue(computer.snapshot().pending().isEmpty());
	}

	@Test void invalidInstallKeepsOldProgramAndResetPolicyIsExplicit() {
		var computer = computer(counter(1)); computer.begin(EMPTY); finish(computer);
		var before = computer.snapshot().program();
		var text = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
			Map.of("count", new ProgramDefinition.Cell(TEXT, new DataValue.TextValue("initial"))), List.of()), Map.of());
		var mismatch = assertThrows(ComputationException.class, () -> computer.requestInstall(text, ComputerRuntime.MemoryPolicy.PRESERVE));
		assertEquals(ComputationException.Code.TYPE_MISMATCH, mismatch.code());
		assertEquals(before, computer.snapshot().program()); assertEquals(integer(1), computer.memory().field("count"));
		assertEquals(ComputerRuntime.InstallResult.APPLIED, computer.requestInstall(text, ComputerRuntime.MemoryPolicy.RESET));
		assertEquals(new DataValue.TextValue("initial"), computer.memory().field("count"));
	}

	@Test void failedQueuedInstallationDoesNotSilentlyStartOldCode() {
		var computer = computer(counter(1)); computer.begin(EMPTY);
		var badMemory = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
			Map.of("count", new ProgramDefinition.Cell(TEXT, new DataValue.TextValue(""))), List.of()), Map.of());
		computer.requestInstall(badMemory, ComputerRuntime.MemoryPolicy.PRESERVE); finish(computer);
		assertThrows(ComputationException.class, () -> computer.begin(EMPTY));
		assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
		assertTrue(computer.installationDiagnostic().isPresent());
		assertEquals(integer(1), computer.memory().field("count"));
	}

	@Test void saveLoadAtEveryStepPreservesExactProgressAndQueuedInstallation() {
		var computer = computer(counter(1)); computer.begin(EMPTY);
		computer.requestInstall(counter(10), ComputerRuntime.MemoryPolicy.PRESERVE);
		for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 100);
			var tag = ComputerNbtCodec.encode(computer.snapshot(), SAVE_LIMITS);
			var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(tag, SAVE_LIMITS), HARDWARE);
			assertEquals(computer.id(), restored.id());
			assertEquals(computer.snapshot(), restored.snapshot());
			assertFalse(restored.loaded());
			var frozen = restored.report(); restored.tick(); restored.step(); assertEquals(frozen, restored.report());
			restored.load(); computer.step(); restored.step();
			assertEquals(computer.snapshot(), restored.snapshot()); computer = restored;
		}
		computer.begin(EMPTY); finish(computer); assertEquals(integer(11), computer.memory().field("count"));
	}

	@Test void debugPauseSurvivesLoadAndResetRestoresInitialValuesAndCancelsPendingInstall() {
		var computer = computer(counter(1)); computer.begin(EMPTY); computer.setPaused(true); computer.step();
		computer.requestInstall(counter(10), ComputerRuntime.MemoryPolicy.RESET);
		var restored = ComputerRuntime.restore(ComputerNbtCodec.decode(ComputerNbtCodec.encode(computer.snapshot(), SAVE_LIMITS), SAVE_LIMITS), HARDWARE);
		restored.load(); var before = restored.report(); restored.tick(); assertEquals(before, restored.report());
		restored.step(); assertEquals(before.workUnits() + 1, restored.report().workUnits());
		restored.reset(); assertTrue(restored.paused()); assertTrue(restored.snapshot().pending().isEmpty());
		assertEquals(integer(0), restored.memory().field("count"));
		assertEquals(ComputationMachine.Status.IDLE, restored.report().status());
	}

	@Test void saveCannotUpgradeHardwareAndInvalidProgramCannotReplaceTheInstallation() {
		var computer = computer(counter(1)); var snapshot = computer.snapshot();
		assertThrows(ComputationException.class, () -> ComputerRuntime.restore(snapshot, new ResourceLimits(1, 1_000_000, 1)));
		var invalid = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of("x", new ComputationGraph.Input("missing")), Map.of("out", "x")), Map.of(), List.of()), Map.of());
		assertThrows(ComputationException.class, () -> computer.requestInstall(invalid, ComputerRuntime.MemoryPolicy.RESET));
		assertEquals(snapshot.program(), computer.snapshot().program());
		assertEquals(snapshot.machine(), computer.snapshot().machine());
	}

	static ComputerRuntime computer(ProgramArchive program) { return new ComputerRuntime(UUID.randomUUID(), program, HARDWARE); }
	static DataValue.IntegerValue integer(long n) { return new DataValue.IntegerValue(n); }
	static ProgramArchive counter(long increment) {
		var graph = new ComputationGraph(Map.of(), Map.of("read", new ComputationGraph.ReadMemory("count"),
			"increment", new ComputationGraph.Constant(integer(increment)), "sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "read", "increment")), Map.of("next", "sum"));
		return new ProgramArchive(new ProgramDefinition(graph, Map.of("count", new ProgramDefinition.Cell(INTEGER, integer(0))), List.of(new ProgramDefinition.Write("count", "next"))), Map.of());
	}
	static void finish(ComputerRuntime computer) {
		for (int i = 0; computer.report().status() == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 10000); computer.tick();
		}
		assertEquals(ComputationMachine.Status.SUCCEEDED, computer.report().status());
	}
}
