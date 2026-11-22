package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType.Scalar.INTEGER;
import static org.junit.jupiter.api.Assertions.*;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

class PeriodicRuntimeTest {
	private static final DataValue.RecordValue THREE = record("increment", integer(3));

	@Test void periodicCountdownStartsOnceAndWaitsWithoutBacklogWhileBusy() {
		var runtime = runtime(1000, 1);
		runtime.configurePeriodic(2, THREE);
		runtime.tick(); assertEquals(integer(0), runtime.memory().field("count"));
		runtime.tick(); assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
		finish(runtime); assertEquals(integer(3), runtime.memory().field("count"));
		assertEquals(0, runtime.snapshot().periodic().orElseThrow().remainingTicks());
		runtime.tick();
		assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
		assertEquals(2, runtime.snapshot().periodic().orElseThrow().remainingTicks());
	}

	@Test void fastPeriodicCounterRunsOnEverySecondTick() {
		var runtime = runtime(1000, 100); runtime.configurePeriodic(2, THREE);
		runtime.tick(); assertEquals(integer(0), runtime.memory().field("count"));
		runtime.tick(); assertEquals(integer(3), runtime.memory().field("count"));
		runtime.tick(); assertEquals(integer(3), runtime.memory().field("count"));
		runtime.tick(); assertEquals(integer(6), runtime.memory().field("count"));
	}

	@Test void pauseUnloadAndStepFreezeTimerAndResetRestartsFullInterval() {
		var runtime = runtime(1000, 1); runtime.configurePeriodic(2, THREE);
		runtime.setPaused(true); runtime.tick(); runtime.step();
		assertEquals(2, runtime.snapshot().periodic().orElseThrow().remainingTicks());
		runtime.setPaused(false); runtime.unload(); runtime.tick();
		assertEquals(2, runtime.snapshot().periodic().orElseThrow().remainingTicks());
		runtime.load(); runtime.tick(); assertEquals(1, runtime.snapshot().periodic().orElseThrow().remainingTicks());
		runtime.reset(); assertEquals(2, runtime.snapshot().periodic().orElseThrow().remainingTicks());
	}

	@Test void disableRetainsInputsClearReleasesThemAndInstallDisablesOnlyWhenAccepted() {
		var runtime = runtime(1000, 10); runtime.configurePeriodic(7, THREE);
		var invalid = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of("x", "missing")), Map.of(), List.of()), Map.of());
		assertThrows(ComputationException.class, () -> runtime.requestInstall(invalid, ComputerRuntime.MemoryPolicy.RESET));
		assertTrue(runtime.snapshot().periodic().orElseThrow().enabled());
		runtime.requestInstall(counterProgram(), ComputerRuntime.MemoryPolicy.RESET);
		assertFalse(runtime.snapshot().periodic().orElseThrow().enabled());
		assertEquals(THREE, runtime.snapshot().periodic().orElseThrow().inputs());
		runtime.clearPeriodic(); assertTrue(runtime.snapshot().periodic().isEmpty());
	}

	@Test void runningOrPendingBoundaryRejectsConfigurationAndFailureDisablesStarts() {
		var runtime = runtime(1000, 1); runtime.begin(THREE);
		assertEquals(ComputationException.Code.BUSY,
			assertThrows(ComputationException.class, () -> runtime.configurePeriodic(2, THREE)).code());
		finish(runtime);
		var failingGraph = new ComputationGraph(Map.of("increment", DataType.Scalar.ANY), Map.of(
			"increment", new ComputationGraph.Input("increment"),
			"bad", new ComputationGraph.Field("increment", "missing")), Map.of("next", "bad"));
		var failing = new ProgramArchive(new ProgramDefinition(failingGraph,
			Map.of("count", new ProgramDefinition.Cell(INTEGER, integer(0))), List.of()), Map.of());
		var broken = new ComputerRuntime(UUID.randomUUID(), failing, new ResourceLimits(1000, 1000, 100));
		broken.configurePeriodic(1, THREE); broken.tick();
		assertEquals(ComputationMachine.Status.FAILED, broken.report().status());
		assertFalse(broken.snapshot().periodic().orElseThrow().enabled());
	}

	@Test void manualEventFailureImmediatelyDisablesScheduleAndDiagnosticCannotBeRestarted() {
		var runtime = new ComputerRuntime(UUID.randomUUID(), counterProgram(), new ResourceLimits(1000, 1000, 100));
		runtime.configurePeriodic(1, THREE);
		runtime.begin(record("increment", new DataValue.TextValue("wrong")));
		assertEquals(ComputationMachine.Status.FAILED, runtime.report().status());
		assertFalse(runtime.snapshot().periodic().orElseThrow().enabled());
		var diagnostic = runtime.report().diagnostic();
		runtime.tick();
		assertEquals(ComputationMachine.Status.FAILED, runtime.report().status());
		assertEquals(diagnostic, runtime.report().diagnostic());
	}

	@Test void configureReservesPersistedAndActiveCopiesAtomically() {
		var program = counterProgram();
		var compiled = program.compile(new ResourceLimits(1000, 1000, 10));
		long base = compiled.usage().constantUnits() + compiled.usage().initialMemoryUnits()
			+ new ComputationMachine(compiled).memory().storageUnits();
		var runtime = new ComputerRuntime(UUID.randomUUID(), program,
			new ResourceLimits(1000, base + 2 * THREE.storageUnits() - 1, 10));
		var before = runtime.snapshot();
		assertEquals(ComputationException.Code.MEMORY_LIMIT,
			assertThrows(ComputationException.class, () -> runtime.configurePeriodic(2, THREE)).code());
		assertEquals(before, runtime.snapshot());
	}

	@Test void enabledRestoreChecksInputSchemaButDisabledRetainedDataMayOutliveProgram() {
		var emptyProgram = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()), Map.of(), List.of()), Map.of());
		var base = new ComputerRuntime(UUID.randomUUID(), emptyProgram, new ResourceLimits(1000, 1000, 10)).snapshot();
		var enabled = new ComputerRuntime.Snapshot(base.id(), base.program(), base.machine(), base.pending(), base.installationDiagnostic(),
			Optional.of(new PeriodicSchedule(2, 2, true, THREE)));
		assertEquals(ComputationException.Code.TYPE_MISMATCH,
			assertThrows(ComputationException.class, () -> ComputerRuntime.restore(enabled, new ResourceLimits(1000, 1000, 10))).code());
		var disabled = new ComputerRuntime.Snapshot(base.id(), base.program(), base.machine(), base.pending(), base.installationDiagnostic(),
			Optional.of(new PeriodicSchedule(2, 2, false, THREE)));
		assertEquals(THREE, ComputerRuntime.restore(disabled, new ResourceLimits(1000, 1000, 10)).snapshot().periodic().orElseThrow().inputs());
	}

	@Test void inFlightSaveLoadPauseUnloadDisableAndClearPreserveOneExactExecution() throws IOException {
		var hardware = new ResourceLimits(1000, 1000, 1);
		var runtime = new ComputerRuntime(UUID.randomUUID(), counterProgram(), hardware);
		runtime.configurePeriodic(2, THREE); runtime.tick(); runtime.tick();
		assertEquals(ComputationMachine.Status.RUNNING, runtime.report().status());
		runtime.setPaused(true);
		long remaining = runtime.snapshot().periodic().orElseThrow().remainingTicks();
		long work = runtime.report().workUnits(); runtime.step();
		assertEquals(remaining, runtime.snapshot().periodic().orElseThrow().remainingTicks());
		assertEquals(work + 1, runtime.report().workUnits());
		var restored = ComputerRuntime.restore(roundTrip(runtime.snapshot()), hardware);
		assertEquals(runtime.snapshot(), restored.snapshot());
		var frozen = restored.snapshot(); restored.tick(); assertEquals(frozen, restored.snapshot());
		restored.load(); restored.disablePeriodic(); var checkpoint = restored.snapshot().machine();
		restored.clearPeriodic(); assertEquals(checkpoint, restored.snapshot().machine());
		restored.setPaused(false); finish(restored);
		assertEquals(integer(3), restored.memory().field("count"));
	}

	@Test void queuedInstallDisablesImmediatelyAndPendingBoundarySurvivesReducedBudgetRestore() throws IOException {
		var hardware = new ResourceLimits(1000, 1000, 1);
		var runtime = new ComputerRuntime(UUID.randomUUID(), counterProgram(), hardware);
		runtime.configurePeriodic(1, THREE); runtime.tick();
		assertEquals(ComputerRuntime.InstallResult.QUEUED,
			runtime.requestInstall(counterProgram(), ComputerRuntime.MemoryPolicy.PRESERVE));
		assertFalse(runtime.snapshot().periodic().orElseThrow().enabled());
		finish(runtime); assertTrue(runtime.snapshot().pending().isPresent());
		assertEquals(ComputationException.Code.BUSY,
			assertThrows(ComputationException.class, () -> runtime.configurePeriodic(2, THREE)).code());
		var restored = ComputerRuntime.restore(roundTrip(runtime.snapshot()), hardware);
		assertTrue(restored.snapshot().pending().isPresent());
		assertEquals(THREE, restored.snapshot().periodic().orElseThrow().inputs());
	}

	private static ComputerRuntime.Snapshot roundTrip(ComputerRuntime.Snapshot snapshot) throws IOException {
		var limits = new ArchiveLimits(100_000, 1_000_000, 10_000_000);
		var bytes = new ByteArrayOutputStream();
		NbtIo.write(ComputerNbtCodec.encode(snapshot, limits), new DataOutputStream(bytes));
		return ComputerNbtCodec.decode(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))), limits);
	}

	@Test void configurationIsAtomicAndReservedInputsReduceCapacityEvenWhenDisabled() {
		var program = counterProgram();
		var compiled = program.compile(new ResourceLimits(1000, 1000, 10));
		var initialMachine = new ComputationMachine(compiled);
		long base = compiled.usage().constantUnits() + compiled.usage().initialMemoryUnits() + initialMachine.memory().storageUnits();
		long inputUnits = THREE.storageUnits();
		var runtime = new ComputerRuntime(UUID.randomUUID(), program, new ResourceLimits(1000, base + 2 * inputUnits, 10));
		runtime.configurePeriodic(2, THREE); var saved = runtime.snapshot();
		var wrong = record("increment", new DataValue.TextValue("bad"));
		assertEquals(ComputationException.Code.TYPE_MISMATCH,
			assertThrows(ComputationException.class, () -> runtime.configurePeriodic(2, wrong)).code());
		assertEquals(saved, runtime.snapshot());
		runtime.disablePeriodic();
		var tight = new ResourceLimits(1000, base + inputUnits - 1, 10);
		assertEquals(ComputationException.Code.MEMORY_LIMIT,
			assertThrows(ComputationException.class, () -> ComputerRuntime.restore(runtime.snapshot(), tight)).code());
		runtime.clearPeriodic();
		assertDoesNotThrow(() -> ComputerRuntime.restore(runtime.snapshot(), tight));
	}

	private static ComputerRuntime runtime(long memory, long work) {
		return new ComputerRuntime(UUID.randomUUID(), counterProgram(), new ResourceLimits(1000, memory, work));
	}
	private static ProgramArchive counterProgram() {
		var graph = new ComputationGraph(Map.of("increment", INTEGER), Map.of(
			"count", new ComputationGraph.ReadMemory("count"), "increment", new ComputationGraph.Input("increment"),
			"sum", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "count", "increment")), Map.of("next", "sum"));
		return new ProgramArchive(new ProgramDefinition(graph, Map.of("count", new ProgramDefinition.Cell(INTEGER, integer(0))),
			List.of(new ProgramDefinition.Write("count", "next"))), Map.of());
	}
	private static DataValue.RecordValue record(String key, DataValue value) { return new DataValue.RecordValue(Map.of(key, value)); }
	private static DataValue.IntegerValue integer(long value) { return new DataValue.IntegerValue(value); }
	private static void finish(ComputerRuntime runtime) {
		for (int i = 0; runtime.report().status() == ComputationMachine.Status.RUNNING && i < 100; i++) runtime.tick();
		assertEquals(ComputationMachine.Status.SUCCEEDED, runtime.report().status());
	}
}
