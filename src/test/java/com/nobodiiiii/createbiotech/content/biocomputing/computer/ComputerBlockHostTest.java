package com.nobodiiiii.createbiotech.content.biocomputing.computer;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntimeTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class ComputerBlockHostTest {    @Test void failingDiagnosticObserverCannotFailOrReplayACommittedRendezvousOrSkipTheOtherObserver() {
        var notifications = new AtomicInteger();
        var writer = new ComputerBlockHost(() -> {}, pin -> { throw new IllegalStateException("diagnostic failure"); });
        var reader = new ComputerBlockHost(() -> {}, pin -> notifications.incrementAndGet());
        var type = new DataType.RecordType(Map.of("n", DataType.Scalar.INTEGER));
        var packet = new DataValue.RecordValue(Map.of("n", new DataValue.IntegerValue(7)));
        writer.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of("packet", type), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Write("write", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", packet)), LIMITS, true);
        reader.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("read", "x0", "packet", type))), EMPTY, LIMITS, true);
        writer.setPaused(false, LIMITS, true); reader.setPaused(false, LIMITS, true);
        writer.tick(LIMITS, true); reader.tick(LIMITS, true);
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.EXCHANGED,
            assertDoesNotThrow(() -> writer.exchange("x0", LIMITS, true, reader, "x0", LIMITS, true)));
        assertEquals(packet, reader.flowSnapshot().orElseThrow().state().locals().field("packet"));
        assertEquals(1, notifications.get());
        var writerSave = writer.write(); var readerSave = reader.write();
        writer.exchange("x0", LIMITS, true, reader, "x0", LIMITS, true);
        assertEquals(1, notifications.get()); assertEquals(writerSave, writer.write()); assertEquals(readerSave, reader.write());
    }
	static final ComputerHostLimits LIMITS = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, SAVE_LIMITS));

	@Test void emptyHostDoesNotInventAProgramOrDirtyItsOwner() {
		var changes = new AtomicInteger(); var host = new ComputerBlockHost(changes::incrementAndGet);
		host.read(null); host.tick(LIMITS, true); host.unload(); host.tick(LIMITS, true);
		assertNull(host.write()); assertFalse(host.loaded()); assertTrue(host.snapshot().isEmpty());
		assertEquals(0, changes.get());
		assertThrows(ComputerBlockHost.HostException.class, () -> host.begin(EMPTY, LIMITS, true));
		assertEquals(ComputerBlockHost.Code.NO_PROGRAM, host.diagnostic().orElseThrow().code());
	}

	@Test void unavailableSuspendsEveryEntryAndUnloadPreservesPauseAndContinuation() {
		var host = installed(); host.begin(EMPTY, LIMITS, true); host.setPaused(true, LIMITS, true);
		host.step(LIMITS, true); var saved = host.write(); var before = host.snapshot().orElseThrow();
		host.tick(LIMITS, false); assertFalse(host.loaded());
		assertThrows(ComputerBlockHost.HostException.class, () -> host.step(LIMITS, false));
		assertThrows(ComputerBlockHost.HostException.class, () -> host.install(counter(10), ComputerRuntime.MemoryPolicy.RESET, LIMITS, false));
		assertThrows(ComputerBlockHost.HostException.class, () -> host.begin(EMPTY, LIMITS, false));
		assertEquals(saved, host.write()); host.unload(); host.tick(LIMITS, true);
		assertTrue(host.loaded()); assertEquals(before, host.snapshot().orElseThrow());
		host.setPaused(false, LIMITS, true); finish(host); assertEquals(integer(1), memory(host));
		host.begin(EMPTY, LIMITS, true); finish(host); assertEquals(integer(2), memory(host));
	}

	@Test void saveLoadAfterEverySlicePreservesQueuedInstallationAndExactContinuation() {
		var host = installed(); host.begin(EMPTY, LIMITS, true);
		assertEquals(ComputerRuntime.InstallResult.QUEUED, host.install(counter(10), ComputerRuntime.MemoryPolicy.PRESERVE, LIMITS, true));
		for (int i = 0; status(host) == ComputationMachine.Status.RUNNING; i++) {
			assertTrue(i < 100); var before = host.snapshot().orElseThrow();
			var restored = new ComputerBlockHost(() -> {}); restored.read(host.write());
			assertFalse(restored.loaded()); assertTrue(restored.snapshot().isEmpty());
			restored.step(LIMITS, true); host.step(LIMITS, true);
			assertEquals(host.snapshot(), restored.snapshot()); assertEquals(before.id(), restored.snapshot().orElseThrow().id()); host = restored;
		}
		assertEquals(integer(1), memory(host)); assertTrue(host.snapshot().orElseThrow().pending().isPresent());
		host.begin(EMPTY, LIMITS, true); finish(host); assertEquals(integer(11), memory(host));
	}

	@Test void dirtyCallbackSeesOnlyCompleteArchivesAndIdleTicksAreClean() {
		var changes = new AtomicInteger(); var reference = new ComputerBlockHost[1];
		var host = reference[0] = new ComputerBlockHost(() -> {
			assertEquals(reference[0].snapshot().orElseThrow(), ComputerNbtCodec.decode((CompoundTag) reference[0].write(), SAVE_LIMITS)); changes.incrementAndGet();
		});
		host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); assertEquals(1, changes.get());
		host.tick(LIMITS, true); host.setPaused(false, LIMITS, true); host.reset(LIMITS, true); assertEquals(1, changes.get());
		host.begin(EMPTY, LIMITS, true); assertEquals(2, changes.get()); host.step(LIMITS, true); assertEquals(3, changes.get());
		host.setPaused(true, LIMITS, true); assertEquals(4, changes.get()); host.tick(LIMITS, true); assertEquals(4, changes.get());
		host.unload(); host.tick(LIMITS, true); assertEquals(4, changes.get());
		host.reset(LIMITS, true); assertEquals(5, changes.get());
	}

	@Test void malformedAndFutureArchivesStayOpaqueDefensiveAndCacheTheRestoreFailure() {
		var future = new CompoundTag(); future.putInt("Version", 42); future.putString("Unknown", "preserve me");
		for (Tag input : List.of(StringTag.valueOf("not a computer"), new CompoundTag(), future)) {
			var host = new ComputerBlockHost(() -> fail("Malformed saves must not dirty")); host.read(input);
			var expected = input.copy(); if (input instanceof CompoundTag compound) compound.putInt("caller mutation", 7);
			host.tick(LIMITS, true); var diagnostic = host.diagnostic().orElseThrow();
			assertEquals(ComputerBlockHost.Code.INVALID_SAVE, diagnostic.code()); assertFalse(host.loaded());
			for (int i = 0; i < 4; i++) { host.tick(LIMITS, true); assertSame(diagnostic, host.diagnostic().orElseThrow()); }
			assertThrows(ComputerBlockHost.HostException.class, () -> host.reset(LIMITS, true));
			assertThrows(ComputerBlockHost.HostException.class, () -> host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true));
			var write = host.write(); assertEquals(expected, write);
			if (write instanceof CompoundTag compound) compound.putBoolean("writer mutation", true);
			assertEquals(expected, host.write());
		}
	}

	@Test void hardwareReductionPreservesRamAndResumesWhenLimitsReturn() {
		var host = installed(); host.begin(EMPTY, LIMITS, true); finish(host); host.setPaused(true, LIMITS, true);
		var saved = host.write(); var before = host.snapshot();
		for (var hardware : List.of(new ResourceLimits(1, 1_000_000, 1), new ResourceLimits(1000, 1, 1))) {
			host.tick(ParserHardwareFixture.withIsa1a(new ComputerHostLimits(hardware, SAVE_LIMITS)), true);
			assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
			assertEquals(saved, host.write()); host.tick(LIMITS, true);
			assertTrue(host.loaded()); assertEquals(before, host.snapshot()); assertEquals(integer(1), memory(host));
		}
	}

	@Test void initialArchiveLimitFailureCannotPublishRuntimeOrDirtyOwner() {
		var host = new ComputerBlockHost(() -> fail("Rejected initial install cannot dirty"));
		var tiny = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, new ArchiveLimits(1, 1, 1)));
		assertThrows(ComputerBlockHost.HostException.class, () -> host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, tiny, true));
		assertNull(host.write()); assertTrue(host.snapshot().isEmpty()); assertFalse(host.loaded());
		assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
	}

	@Test void freshLoadRecognizesIdlePreservedRamExceedingHardwareAsResourceFailure() {
		var program = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of(), Map.of()),
			Map.of("text", new ProgramDefinition.Cell(DataType.Scalar.TEXT, new DataValue.TextValue(""))), List.of()), Map.of());
		var runtime = new ComputerRuntime(UUID.randomUUID(), program, HARDWARE);
		var memory = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("x".repeat(500))));
		var idle = new MachineCheckpoint(memory, ComputationMachine.Status.IDLE, true, 0, 0, Optional.empty(), Optional.empty(), List.of());
		var snapshot = new ComputerRuntime.Snapshot(runtime.id(), runtime.snapshot().program(), idle, Optional.empty(), Optional.empty());
		// This is a valid idle checkpoint after installing a smaller program while preserving larger RAM.
		assertDoesNotThrow(() -> ComputerRuntime.restore(snapshot, HARDWARE));
		var raw = ComputerNbtCodec.encode(snapshot, SAVE_LIMITS); var host = new ComputerBlockHost(() -> fail("Lazy load cannot dirty")); host.read(raw);
		host.tick(ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1000, 100, 1), SAVE_LIMITS)), true);
		assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code()); assertEquals(raw, host.write());
		host.tick(LIMITS, true); assertTrue(host.loaded()); assertEquals(memory, host.snapshot().orElseThrow().machine().memory());
	}

	@Test void unsaveableSliceRollsBackAndDoesNotRetryUntilLimitsChange() {
		var changes = new AtomicInteger(); var host = new ComputerBlockHost(changes::incrementAndGet);
		host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); host.begin(EMPTY, LIMITS, true);
		var before = host.snapshot().orElseThrow(); var saved = (CompoundTag) host.write();
		// Permit the current archive's logical value size, but not the next slice's added execution field.
		var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HARDWARE, new ArchiveLimits(100_000, 1_000_000, ValueNbtCodec.decode(saved, SAVE_LIMITS).storageUnits())));
		int dirty = changes.get(); host.tick(tight, true);
		assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
		assertFalse(host.loaded()); assertEquals(before, host.snapshot().orElseThrow()); assertEquals(saved, host.write());
		assertEquals(dirty, changes.get());
		for (int i = 0; i < 5; i++) host.tick(tight, true);
		assertEquals(saved, host.write()); assertEquals(dirty, changes.get());
		host.tick(LIMITS, true); finish(host); assertEquals(integer(1), memory(host));
	}

	@Test void corruptedProgramAndRamNeverLeakCoreFailuresFromTick() {
		var valid = computer(counter(1)).snapshot();
		var corrupt = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of("x", new ComputationGraph.Input("missing")), Map.of("out", "x")), Map.of(), List.of()), Map.of());
		var badMemory = new MachineCheckpoint(EMPTY, ComputationMachine.Status.IDLE, false, 0, 0, Optional.empty(), Optional.empty(), List.of());
		for (var snapshot : List.of(new ComputerRuntime.Snapshot(valid.id(), corrupt, valid.machine(), Optional.empty(), Optional.empty()),
			new ComputerRuntime.Snapshot(valid.id(), valid.program(), badMemory, Optional.empty(), Optional.empty()))) {
			var host = new ComputerBlockHost(() -> fail("Bad data cannot dirty")); var raw = ComputerNbtCodec.encode(snapshot, SAVE_LIMITS); host.read(raw);
			assertDoesNotThrow(() -> host.tick(LIMITS, true)); assertFalse(host.loaded());
			assertEquals(ComputerBlockHost.Code.INVALID_SAVE, host.diagnostic().orElseThrow().code()); assertEquals(raw, host.write());
		}
	}

	@Test void archiveMetadataLogicalOverflowAlsoRollsBackASuccessfulCoreSlice() {
		DataValue huge = logicalValue((Long.MAX_VALUE - 100) / 2);
		var program = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(),
			Map.of("constant", new ComputationGraph.Constant(huge)), Map.of("out", "constant")), Map.of(), List.of()), Map.of());
		var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(10_000, Long.MAX_VALUE, 1), new ArchiveLimits(100_000, 1_000_000, Long.MAX_VALUE)));
		var core = new ComputerRuntime(UUID.randomUUID(), program, limits.hardware()); core.begin(EMPTY); core.step();
		assertEquals(ComputationMachine.Status.RUNNING, core.report().status());
		assertThrows(ComputationException.class, () -> ComputerNbtCodec.encode(core.snapshot(), limits.archive()));
		var changes = new AtomicInteger(); var host = new ComputerBlockHost(changes::incrementAndGet);
		host.install(program, ComputerRuntime.MemoryPolicy.RESET, limits, true); host.begin(EMPTY, limits, true);
		var before = host.snapshot(); var raw = host.write(); int dirty = changes.get();
		assertThrows(ComputerBlockHost.HostException.class, () -> host.step(limits, true));
		assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, host.diagnostic().orElseThrow().code());
		assertEquals(before, host.snapshot()); assertEquals(raw, host.write()); assertEquals(dirty, changes.get());
		assertDoesNotThrow(() -> host.tick(limits, true)); assertEquals(before, host.snapshot());
	}

	@Test void rejectedCoreInstallationPersistsItsDiagnosticButKeepsOldProgram() {
		var changes = new AtomicInteger(); var host = new ComputerBlockHost(changes::incrementAndGet);
		host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); var program = host.snapshot().orElseThrow().program();
		var bad = new ProgramArchive(new ProgramDefinition(new ComputationGraph(Map.of(), Map.of("x", new ComputationGraph.Input("missing")), Map.of("out", "x")), Map.of(), List.of()), Map.of());
		assertThrows(ComputationException.class, () -> host.install(bad, ComputerRuntime.MemoryPolicy.RESET, LIMITS, true));
		var saved = ComputerNbtCodec.decode((CompoundTag) host.write(), SAVE_LIMITS);
		assertEquals(program, saved.program()); assertTrue(saved.installationDiagnostic().isPresent()); assertEquals(2, changes.get());
	}

	static ComputerBlockHost installed() {
		var host = new ComputerBlockHost(() -> {}); host.install(counter(1), ComputerRuntime.MemoryPolicy.RESET, LIMITS, true); return host;
	}
	static DataValue logicalValue(long units) {
		if (units == 1) return integer(0);
		if ((units & 1) == 0) return new DataValue.ListValue(List.of(logicalValue(units - 1)));
		var shared = logicalValue((units - 1) / 2); return new DataValue.ListValue(List.of(shared, shared));
	}
	static DataValue memory(ComputerBlockHost host) { return host.snapshot().orElseThrow().machine().memory().field("count"); }
	static ComputationMachine.Status status(ComputerBlockHost host) { return host.snapshot().orElseThrow().machine().status(); }
	static void finish(ComputerBlockHost host) {
		for (int i = 0; status(host) == ComputationMachine.Status.RUNNING; i++) { assertTrue(i < 100); host.tick(LIMITS, true); }
		assertEquals(ComputationMachine.Status.SUCCEEDED, status(host));
	}
}
