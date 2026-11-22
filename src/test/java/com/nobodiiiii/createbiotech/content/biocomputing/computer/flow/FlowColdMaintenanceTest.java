package com.nobodiiiii.createbiotech.content.biocomputing.computer.flow;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ParserHardwareFixture;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ResourceLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockHost;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.FlowBlockNbtCodec;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class FlowColdMaintenanceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void provenReceiptRetirementCanRestoreAnOverBudgetOwnerWithoutResettingItsProgram(boolean receiving) {
        var limits = FlowAssemblyMixedRestoreTest.SERIAL_LIMITS;
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var packet = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("historical".repeat(512))));
        var type = (DataType.RecordType) DataType.typeOf(packet);
        var writer = new ComputerBlockHost(() -> {});
        writer.installFlow(new FlowProgram(Map.of("packet", type), List.of(new FlowProgram.Write("send", "x0", "packet"))).withAssembly(),
            new DataValue.RecordValue(Map.of("packet", packet)), limits, true);
        var reader = new ComputerBlockHost(() -> {}, pin -> {}, device);
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Read("read", "x0", "packet", type));
        if (receiving) steps.add(new FlowProgram.SerialReceive("receive", "s0", "received", false));
        steps.add(new FlowProgram.Sleep("wait", "ticks"));
        reader.installFlow(new FlowProgram(Map.of("ticks", DataType.Scalar.INTEGER), steps).withAssembly(),
            new DataValue.RecordValue(Map.of("ticks", new DataValue.IntegerValue(100))), limits, true);
        writer.setPaused(false, limits, true); reader.setPaused(false, limits, true);
        for (int i = 0; i < 8; i++) { writer.tick(limits, true); reader.tick(limits, true); }
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0", limits, true, reader, "x0", limits, true));
        if (receiving) {
            var frame = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("incoming".repeat(32))));
            device.attachments().getFirst().receiver().offer(
                com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(frame, device.encodingLimits("s0")));
            for (int i = 0; i < 8 && reader.flowSnapshot().orElseThrow().state().assembly().flatMap(r -> r.serial()).isEmpty(); i++) reader.step(limits, true);
            assertTrue(reader.flowSnapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().receiving());
        }
        reader.setPaused(true, limits, true);
        var queue = device.write();
        var before = reader.flowSnapshot().orElseThrow(); var receipt = before.receipts().getFirst();
        var cleaned = new FlowBlockHost.Saved(before.program(), before.initialInputs(), before.state(), List.of(),
            before.control().retireOperations(before.receipts(), before.state().computer()), before.tasks(), before.pinClaims(),
            before.cancellations(), before.memory(), before.prompts(), before.monitors(), before.privateMonitors());
        var tight = withRam(limits, FlowAssemblyMixedRestoreTest.totalRam(cleaned) + 32);
        var raw = reader.write(); var dirty = new AtomicInteger();
        var cold = new ComputerBlockHost(dirty::incrementAndGet, pin -> {}, device);
        cold.read(raw); cold.tick(tight, true);
        assertFalse(cold.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, cold.diagnostic().orElseThrow().code());
        assertEquals(0, cold.reclaimCompletedXBus(r -> false));
        assertEquals(raw, cold.write()); assertEquals(0, dirty.get());
        cold.tick(tight, false);
        assertEquals(0, cold.reclaimCompletedXBus(receipt::equals), "Unavailable hardware never becomes executable through maintenance");
        assertEquals(raw, cold.write()); cold.tick(tight, true);
        var tooSmall = withRam(limits, 1);
        var rejected = new ComputerBlockHost(() -> { fail("A failed cleanup must not publish"); }, pin -> {}, device);
        rejected.read(raw); rejected.tick(tooSmall, true); var diagnostic = rejected.diagnostic();
        var checks = new AtomicInteger(); var witness = new Object();
        java.util.function.Predicate<XBusReceipt> proven = r -> { checks.incrementAndGet(); return r.equals(receipt); };
        assertEquals(0, rejected.maintainXBusReceipts(witness, proven, tooSmall, true));
        assertEquals(1, checks.get());
        assertEquals(0, rejected.maintainXBusReceipts(witness, proven, tooSmall, true));
        assertEquals(1, checks.get(), "Unchanged failed owners must not reconstruct the archive on every tick");
        assertEquals(raw, rejected.write()); assertEquals(diagnostic, rejected.diagnostic()); assertFalse(rejected.loaded());
        assertEquals(0, rejected.maintainXBusReceipts(new Object(), proven, tooSmall, true));
        assertEquals(2, checks.get(), "A newly durable proof permits another bounded maintenance attempt");
        var state = before.state(); var badLocals = new LinkedHashMap<>(state.locals().fields());
        badLocals.put("not_a_local", new DataValue.IntegerValue(1));
        var invalidState = new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(),
            new DataValue.RecordValue(badLocals), state.work(), state.paused(), state.expression(), state.diagnostic(),
            state.sleepTicks(), state.signals(), state.operation(), state.repeats(), state.inputInvocation(), state.calls(), state.entry(), state.assembly());
        var invalidOwner = new FlowBlockHost.Saved(before.program(), before.initialInputs(), invalidState, before.receipts(),
            before.control(), before.tasks(), before.pinClaims(), before.cancellations(), before.memory(), before.prompts(), before.monitors(), before.privateMonitors());
        var invalidBytes = FlowBlockNbtCodec.encode(invalidOwner, limits.archive());
        var corrupt = new ComputerBlockHost(() -> { fail("Maintenance must not publish an invalid continuation"); }, pin -> {}, device);
        corrupt.read(invalidBytes); corrupt.tick(tight, true);
        assertEquals(0, corrupt.reclaimCompletedXBus(receipt::equals));
        assertFalse(corrupt.loaded()); assertEquals(invalidBytes, corrupt.write());
        if (receiving) {
            var changedDevice = SerialFlowHostTest.device(); changedDevice.read(queue);
            var receiver = changedDevice.attachments().getFirst().receiver();
            var raced = new ComputerBlockHost(() -> { fail("A stale device inspection must not publish"); }, pin -> {}, changedDevice);
            raced.read(raw); raced.tick(tight, true);
            assertEquals(0, raced.reclaimCompletedXBus(r -> { receiver.offer(new byte[0]); return r.equals(receipt); }));
            assertFalse(raced.loaded()); assertEquals(raw, raced.write());
            assertEquals(1, receiver.status().frames(), "Invalidation must not consume the frame");
            var retry = new ComputerBlockHost(() -> {}, pin -> {}, changedDevice); retry.read(raw); retry.tick(tight, true);
            var retryWitness = new Object(); var attempts = new AtomicInteger();
            java.util.function.Predicate<XBusReceipt> attempt = r -> { attempts.incrementAndGet(); return r.equals(receipt); };
            changedDevice.read(queue); // Opaque until the ordinary device lifecycle restores it.
            assertEquals(0, retry.maintainXBusReceipts(retryWitness, attempt, tight, true));
            assertEquals(0, attempts.get()); assertEquals(queue, changedDevice.write());
            changedDevice.beginReceive("s0");
            assertEquals(1, retry.maintainXBusReceipts(retryWitness, attempt, tight, true), "Device readiness invalidates a failed maintenance cache");
            assertEquals(1, attempts.get()); assertTrue(retry.loaded());
        }
        assertEquals(1, cold.reclaimCompletedXBus(receipt::equals), "Durably retired data must not require execution RAM just to be released");
        assertTrue(cold.loaded()); assertTrue(cold.diagnostic().isEmpty());
        assertEquals(cleaned, cold.flowSnapshot().orElseThrow()); assertEquals(1, dirty.get());
        assertEquals(before.state(), cold.flowSnapshot().orElseThrow().state());
        var reloaded = new ComputerBlockHost(() -> {}, pin -> {}, device); reloaded.read(cold.write()); reloaded.tick(tight, true);
        assertTrue(reloaded.loaded()); assertEquals(cleaned, reloaded.flowSnapshot().orElseThrow());
        assertEquals(queue, device.write(), "Neither maintenance nor cold validation consumes or modifies the receive queue");
        if (receiving) {
            // Resume under the original execution budget; the artificially tight restore probe
            // above does not grant room for future allocations or make decoding free.
            for (int i = 0; !reloaded.flowSnapshot().orElseThrow().state().locals().fields().containsKey("received"); i++) {
                assertTrue(i < 5000, reloaded.diagnostic().toString());
                assertEquals(1, device.status("s0").receive().frames());
                long work = reloaded.flowSnapshot().orElseThrow().state().work();
                reloaded.step(limits, true);
                assertEquals(work + 1, reloaded.flowSnapshot().orElseThrow().state().work());
            }
            var frame = new DataValue.RecordValue(Map.of("text", new DataValue.TextValue("incoming".repeat(32))));
            assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialResult.receive("received", frame),
                reloaded.flowSnapshot().orElseThrow().state().locals().field("received"));
            assertEquals(0, device.status("s0").receive().frames());
            device.attachments().getFirst().receiver().offer(
                com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(frame, device.encodingLimits("s0")));
            var finished = reloaded.flowSnapshot().orElseThrow();
            var afterClaim = new ComputerBlockHost(() -> {}, pin -> {}, device);
            afterClaim.read(reloaded.write()); afterClaim.tick(limits, true);
            assertEquals(finished, afterClaim.flowSnapshot().orElseThrow());
            afterClaim.step(limits, true);
            assertEquals(1, device.status("s0").receive().frames(), "A restored completed receive must not claim the next frame");
        }
    }

    @Test void resourceMaintenanceDoesNotBroadenCodeTaskOrArchiveLimits() {
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var owner = FlowAssemblyMixedRestoreTest.serialSender(device); var saved = owner.flowSnapshot().orElseThrow();
        var base = FlowAssemblyMixedRestoreTest.SERIAL_LIMITS; var raw = owner.write(); var queue = device.write();
        var tight = withRam(base, FlowAssemblyMixedRestoreTest.totalRam(saved));
        for (var limits : List.of(
            ParserHardwareFixture.withIsa1a(new ComputerHostLimits(new ResourceLimits(1, tight.hardware().memoryUnits(), 1), base.archive(), base.mailbox(), base.pins(), base.serial(), base.taskSlots())),
            ParserHardwareFixture.withIsa1a(new ComputerHostLimits(tight.hardware(), base.archive(), base.mailbox(), base.pins(), base.serial(), 1)),
            ParserHardwareFixture.withIsa1a(new ComputerHostLimits(tight.hardware(), new com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits(1, 1, 1), base.mailbox(), base.pins(), base.serial(), base.taskSlots())))) {
            var dirty = new AtomicInteger(); var cold = new ComputerBlockHost(dirty::incrementAndGet, pin -> {}, device);
            cold.read(raw); cold.tick(limits, true); assertFalse(cold.loaded()); assertFalse(cold.canResetResourceFailure());
            assertThrows(ComputerBlockHost.HostException.class, () -> cold.reset(limits, true));
            assertEquals(raw, cold.write()); assertEquals(queue, device.write()); assertEquals(0, dirty.get());
        }
    }

    @Test void activeReceiveNeverUsesTheTxOnlyMaintenanceProbe() {
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var seed = FlowAssemblyMixedRestoreTest.serialSender(device).flowSnapshot().orElseThrow();
        var base = FlowAssemblyMixedRestoreTest.SERIAL_LIMITS;
        var steps = new ArrayList<>(seed.program().steps()); steps.set(0, new FlowProgram.SerialReceive("main_receive", "s0", "received", false));
        var program = new FlowProgram(seed.program().inputs(), steps, seed.program().expressions()).withAssembly();
        assertTrue(program.usesValueEqualityAssembly());
        device.attachments().getFirst().receiver().offer(com.nobodiiiii.createbiotech.content.biocomputing.network.serial.SerialRecordCodec.encode(
            (DataValue.RecordValue) seed.initialInputs().field("packet"), device.encodingLimits("s0")));
        var owner = new ComputerBlockHost(() -> {}, pin -> {}, device); owner.installFlow(program, seed.initialInputs(), base, true); owner.step(base, true);
        assertTrue(owner.flowSnapshot().orElseThrow().state().assembly().orElseThrow().serial().orElseThrow().receiving());
        var raw = owner.write(); var queue = device.write(); var dirty = new AtomicInteger();
        var cold = new ComputerBlockHost(dirty::incrementAndGet, pin -> {}, device); cold.read(raw); var tight = withRam(base, 1); cold.tick(tight, true);
        assertFalse(cold.loaded()); assertFalse(cold.canResetResourceFailure());
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.reset(tight, true));
        assertEquals(raw, cold.write()); assertEquals(queue, device.write()); assertEquals(0, dirty.get());
    }

    @Test void resetRetiresAnActualHandoffAndRejectsTheOldRemoteWaitWithoutChangingSerialFrames() {
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var seed = FlowAssemblyMixedRestoreTest.serialSender(device).flowSnapshot().orElseThrow();
        var limits = FlowAssemblyMixedRestoreTest.SERIAL_LIMITS;
        var steps = new ArrayList<FlowProgram.Step>(); steps.add(new FlowProgram.Write("history", "x0", "packet")); steps.addAll(seed.program().steps());
        var program = new FlowProgram(seed.program().inputs(), steps, seed.program().expressions()).withAssembly();
        var writer = new ComputerBlockHost(() -> {}, pin -> {}, device); writer.installFlow(program, seed.initialInputs(), limits, true);
        writer.setPaused(false, limits, true);
        var reader = new ComputerBlockHost(() -> {});
        reader.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received",
            (DataType.RecordType) DataType.typeOf(seed.initialInputs().field("packet"))))).withAssembly(), new DataValue.RecordValue(Map.of()), limits, true);
        reader.setPaused(false, limits, true);
        for (int i = 0; i < 20 && writer.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; i++) writer.tick(limits, true);
        for (int i = 0; i < 20 && reader.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_READ; i++) reader.tick(limits, true);
        var oldRead = reader.write();
        assertEquals(XBusExchange.Status.EXCHANGED, writer.exchange("x0", limits, true, reader, "x0", limits, true));
        writer.setPaused(true, limits, true); writer.startFlowTask(FlowAssemblyMixedRestoreTest.KEY, FlowAssemblyMixedRestoreTest.inputs(), limits, true);
        for (int i = 0; i < 12; i++) writer.step(limits, true);
        var before = writer.flowSnapshot().orElseThrow(); assertEquals(1, before.receipts().size());
        assertTrue(before.state().assembly().orElseThrow().serial().isPresent());
        var tight = withRam(limits, FlowAssemblyMixedRestoreTest.totalRam(before)); var raw = writer.write(); var queue = device.write();
        var cold = new ComputerBlockHost(() -> {}, pin -> {}, device); cold.read(raw); cold.tick(tight, true);
        assertFalse(cold.loaded()); assertTrue(cold.canResetResourceFailure());
        cold.reset(tight, true); var reset = cold.flowSnapshot().orElseThrow();
        assertEquals(before.control().retire(before.receipts(), before.state()), reset.control()); assertTrue(reset.receipts().isEmpty());
        assertEquals(queue, device.write());
        var oldPeer = new ComputerBlockHost(() -> {}); oldPeer.read(oldRead); oldPeer.tick(limits, true);
        cold.setPaused(false, tight, true);
        for (int i = 0; i < 20 && cold.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_WRITE; i++) cold.tick(tight, true);
        var writerBefore = cold.write(); var readerBefore = oldPeer.write();
        assertEquals(XBusExchange.Status.RETIRED, cold.exchange("x0", tight, true, oldPeer, "x0", limits, true));
        assertEquals(writerBefore, cold.write()); assertEquals(readerBefore, oldPeer.write()); assertEquals(queue, device.write());
    }

    @Test void resourceGateCannotHideAnInvalidContinuationFromMaintenanceValidation() {
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var owner = FlowAssemblyMixedRestoreTest.serialSender(device);
        var saved = owner.flowSnapshot().orElseThrow(); var state = saved.state();
        var locals = new LinkedHashMap<>(state.locals().fields()); locals.put("not_a_local", new DataValue.IntegerValue(1));
        var bad = new FlowRuntime.Checkpoint(state.computer(), state.flow(), state.cursor(), state.status(), new DataValue.RecordValue(locals),
            state.work(), state.paused(), state.expression(), state.diagnostic(), state.sleepTicks(), state.signals(), state.operation(),
            state.repeats(), state.inputInvocation(), state.calls(), state.entry(), state.assembly());
        var invalid = new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), bad, saved.receipts(), saved.control(),
            saved.tasks(), saved.pinClaims(), saved.cancellations(), saved.memory());
        var limits = FlowAssemblyMixedRestoreTest.SERIAL_LIMITS;
        var raw = FlowBlockNbtCodec.encode(invalid, limits.archive());
        var tight = withRam(limits, FlowAssemblyMixedRestoreTest.totalRam(saved));
        var dirty = new AtomicInteger(); var cold = new ComputerBlockHost(dirty::incrementAndGet, pin -> {}, device);
        var queue = device.write(); cold.read(raw); cold.tick(tight, true);
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, cold.diagnostic().orElseThrow().code());
        assertFalse(cold.canResetResourceFailure());
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.reset(tight, true));
        assertEquals(raw, cold.write()); assertEquals(queue, device.write()); assertEquals(0, dirty.get());
        cold.tick(limits, true);
        assertEquals(ComputerBlockHost.Code.INVALID_SAVE, cold.diagnostic().orElseThrow().code(), "The hidden defect is a real validation failure");
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.reset(limits, true));
        assertEquals(raw, cold.write()); assertEquals(queue, device.write()); assertEquals(0, dirty.get());
    }

    @Test void rejectedResetCandidateAndUnavailableHardwareKeepTheOriginalBytesAndDiagnostic() {
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var owner = FlowAssemblyMixedRestoreTest.serialSender(device); var raw = owner.write(); var queue = device.write();
        var limits = withRam(FlowAssemblyMixedRestoreTest.SERIAL_LIMITS, 1);
        var dirty = new AtomicInteger(); var cold = new ComputerBlockHost(dirty::incrementAndGet, pin -> {}, device);
        cold.read(raw); cold.tick(limits, true); var diagnostic = cold.diagnostic();
        assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, diagnostic.orElseThrow().code());
        assertTrue(cold.canResetResourceFailure(), "Probe passes; the actual initial-state candidate must still fail atomically");
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.reset(limits, true));
        assertEquals(diagnostic, cold.diagnostic()); assertEquals(raw, cold.write()); assertEquals(0, dirty.get());
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.reset(limits, false));
        assertEquals(raw, cold.write()); assertEquals(queue, device.write()); assertFalse(cold.loaded()); assertEquals(0, dirty.get());
        assertFalse(cold.canResetResourceFailure());
    }

    private static ComputerHostLimits withRam(ComputerHostLimits original, long ram) {
        return new ComputerHostLimits(new ResourceLimits(original.hardware().programUnits(), ram, original.hardware().workPerSlice()),
            original.archive(), original.mailbox(), original.pins(), original.serial(), original.taskSlots(), original.isa1a(), original.isa1b());
    }
    @Test void explicitResetCanAbandonAValidColdTxCheckpointWithoutGrantingExecutionRam() {
        var device = SerialFlowHostTest.device(); device.configure("s0", Direction.EAST, true);
        var owner = FlowAssemblyMixedRestoreTest.serialSender(device);
        var before = owner.flowSnapshot().orElseThrow(); var raw = owner.write(); var queue = device.write();
        var original = FlowAssemblyMixedRestoreTest.SERIAL_LIMITS;
        var tight = new ComputerHostLimits(new ResourceLimits(original.hardware().programUnits(),
            FlowAssemblyMixedRestoreTest.totalRam(before), original.hardware().workPerSlice()), original.archive(),
            original.mailbox(), original.pins(), original.serial(), original.taskSlots(), original.isa1a(), original.isa1b());
        var changed = new AtomicInteger(); var cold = new ComputerBlockHost(changed::incrementAndGet, pin -> {}, device);
        cold.read(raw); cold.tick(tight, true);
        assertFalse(cold.loaded()); assertEquals(ComputerBlockHost.Code.RESOURCE_LIMIT, cold.diagnostic().orElseThrow().code());
        assertTrue(cold.canResetResourceFailure());
        assertEquals(raw, cold.write()); assertEquals(queue, device.write()); assertEquals(0, changed.get());
        assertTrue(cold.flowSnapshot().isEmpty(), "The probe's materialized state must not become a public execution snapshot");
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.step(tight, true));
        assertThrows(ComputerBlockHost.HostException.class, () -> cold.installFlow(before.program(), before.initialInputs(), tight, true));
        assertEquals(raw, cold.write()); assertEquals(queue, device.write());

        assertDoesNotThrow(() -> cold.reset(tight, true), "Explicit confirmed maintenance is not permission to resume the old continuation");
        var reset = cold.flowSnapshot().orElseThrow();
        assertTrue(cold.loaded()); assertTrue(cold.diagnostic().isEmpty());
        assertFalse(cold.canResetResourceFailure());
        assertTrue(reset.state().paused()); assertEquals(0, reset.state().work()); assertTrue(reset.tasks().contexts().isEmpty());
        assertEquals(before.state().computer(), reset.state().computer()); assertNotEquals(before.state().flow(), reset.state().flow());
        assertEquals(before.control().generation() + 1, reset.control().generation());
        assertEquals(before.initialInputs(), reset.initialInputs()); assertEquals(before.program(), reset.program());
        assertEquals(queue, device.write(), "Maintenance neither sends nor consumes physical frames");
        assertEquals(1, changed.get());
        var checkpoint = cold.write(); cold.tick(tight, true); assertEquals(checkpoint, cold.write());
        var reloaded = new ComputerBlockHost(() -> {}, pin -> {}, device); reloaded.read(checkpoint); reloaded.tick(tight, true);
        assertTrue(reloaded.loaded()); assertEquals(reset, reloaded.flowSnapshot().orElseThrow()); assertEquals(queue, device.write());
    }
}
