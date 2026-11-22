package com.nobodiiiii.createbiotech.content.biocomputing.computer;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerFlowHostTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerXBusExchangeTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.ComputerHostLimits;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputerXBusRecoveryTest {
    @Test void olderReadableWaitMayWakeWithoutConsumingTheThirdWriterBeforeRecoveringItsRecordedRead() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(c, writer(), INPUTS);
        start(b, new FlowProgram(Map.of(), List.of(new FlowProgram.AwaitReadable("wait", "x1"),
            new FlowProgram.Read("read", "x1", "packet", PACKET))), EMPTY);
        var oldB = b.write();
        assertEquals(READABLE, exchange(a, b)); b.tick(LIMITS, true); assertEquals(EXCHANGED, exchange(a, b));
        b = restore(oldB);
        var network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        var beforeA = a.write(); var beforeC = c.write();
        assertEquals(Optional.of(RECOVERY_REQUIRED), network.inspect().rejection()); assertTrue(network.inspect().recoveries().isEmpty());
        assertEquals(READABLE, network.exchange(pin(c, "x0"), pin(b, "x1")), "Readiness is not new consumption");
        assertEquals(beforeA, a.write()); assertEquals(beforeC, c.write());
        assertTrue(b.flowSnapshot().orElseThrow().receipts().isEmpty()); assertEquals(EMPTY, b.flowSnapshot().orElseThrow().state().locals());
        b.tick(LIMITS, true);
        network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertEquals(RECOVERY_REQUIRED, network.exchange(pin(c, "x0"), pin(b, "x1")));
        assertEquals(EXCHANGED, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(INPUTS, b.flowSnapshot().orElseThrow().state().locals());
        assertEquals(beforeA, a.write()); assertEquals(beforeC, c.write());
    }

    @Test void thirdWriterCannotConsumeAReaderWhoseOriginalExchangeNeedsRecovery() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
        var oldB = b.write(); assertEquals(EXCHANGED, exchange(a, b));
        b = restore(oldB);
        var beforeA = a.write(); var beforeB = b.write(); var beforeC = c.write();
        var network = XBusAccess.network(List.of(access(c, "x0"), access(b, "x1"), access(a, "x0")));
        assertEquals(RECOVERY_REQUIRED, network.exchange(pin(c, "x0"), pin(b, "x1")));
        assertEquals(List.of(new XBusNetwork.Selection(pin(b, "x1"), pin(a, "x0"))), network.inspect().recoveries());
        assertEquals(beforeA, a.write()); assertEquals(beforeB, b.write()); assertEquals(beforeC, c.write());
        assertEquals(EXCHANGED, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(INPUTS, b.flowSnapshot().orElseThrow().state().locals());
        assertEquals(beforeA, a.write()); assertEquals(beforeC, c.write());
        assertEquals(STALE, network.exchange(pin(c, "x0"), pin(b, "x1")), "The fixed capture cannot survive recovery");
    }
    @Test void readableModeOnTheAdvancedPeerDoesNotDowngradeAnActualWriterRepairToAPeek() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(c, writer(), INPUTS);
        start(b, new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x1", "packet", PACKET),
            new FlowProgram.AwaitReadable("wait", "x1"))), EMPTY);
        var oldA = a.write(); assertEquals(EXCHANGED, exchange(a, b)); b.tick(LIMITS, true); a = restore(oldA);
        var network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        var beforeB = b.write(); var beforeC = c.write();
        assertEquals(XBusPortState.Mode.READABLE, XBusPortState.of(b.flowSnapshot().orElseThrow(), LIMITS.pins(), "x1").orElseThrow().mode());
        assertEquals(EXCHANGED, network.exchange(pin(b, "x1"), pin(a, "x0")), "Original consumption repair takes priority over peek");
        assertEquals(FlowRuntime.Status.COMPLETE, a.flowSnapshot().orElseThrow().state().status());
        assertEquals(beforeB, b.write()); assertEquals(beforeC, c.write());
    }
    @Test void advancedReaderRepairsTheOriginalWriterInsteadOfSendingToAThirdReader() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, reader(), EMPTY);
        var oldA = a.write(); assertEquals(EXCHANGED, exchange(a, b)); a = restore(oldA);
        var beforeA = a.write(); var beforeB = b.write(); var beforeC = c.write();
        var network = XBusAccess.network(List.of(access(a, "x0"), access(c, "x1"), access(b, "x1")));
        assertEquals(RECOVERY_REQUIRED, network.exchange(pin(a, "x0"), pin(c, "x1")));
        assertEquals(beforeA, a.write()); assertEquals(beforeB, b.write()); assertEquals(beforeC, c.write());
        assertEquals(EXCHANGED, network.exchange(pin(b, "x1"), pin(a, "x0")));
        assertEquals(beforeB, b.write()); assertEquals(beforeC, c.write());
    }
    @Test void multipleReceiptsRecoverInOperationOrderWithoutExecutingTheOldReadersNextInstruction() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        var next = new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(99)));
        start(a, new FlowProgram(Map.of("first", PACKET, "second", PACKET), List.of(new FlowProgram.Write("first", "x0", "first"),
            new FlowProgram.Write("second", "x0", "second"))), new DataValue.RecordValue(Map.of("first", INPUTS.field("packet"), "second", next)));
        start(b, new FlowProgram(Map.of(), List.of(new FlowProgram.Read("first", "x1", "packet", PACKET),
            new FlowProgram.Read("second", "x1", "packet", PACKET))), EMPTY);
        start(c, writer(), INPUTS); var oldB = b.write();
        assertEquals(EXCHANGED, exchange(a, b)); a.tick(LIMITS, true); b.tick(LIMITS, true);
        assertEquals(EXCHANGED, exchange(a, b)); var completed = b.flowSnapshot(); b = restore(oldB);
        var beforeA = a.write(); var beforeC = c.write();
        var network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertEquals(EXCHANGED, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(INPUTS, b.flowSnapshot().orElseThrow().state().locals());
        assertEquals(FlowRuntime.Status.READY, b.flowSnapshot().orElseThrow().state().status());
        network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertEquals(NOT_READY, network.exchange(pin(a, "x0"), pin(b, "x1")), "Recovery does not execute the next instruction");
        assertEquals(RECOVERY_REQUIRED, network.exchange(pin(c, "x0"), pin(b, "x1")));
        b.tick(LIMITS, true);
        network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertEquals(EXCHANGED, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(completed, b.flowSnapshot()); assertEquals(next, b.flowSnapshot().orElseThrow().state().locals().field("packet"));
        assertEquals(beforeA, a.write()); assertEquals(beforeC, c.write());
    }
    @Test void incompatibleConsumptionHistoriesRejectTheWholeGroupInEveryParticipantOrder() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
        var oldB = b.write(); assertEquals(EXCHANGED, exchange(a, b));
        b = restore(oldB); assertEquals(EXCHANGED, exchange(c, b)); // Two individually valid but inconsistent save branches.
        var before = List.of(a.write(), b.write(), c.write());
        var aa = access(a, "x0"); var bb = access(b, "x1"); var cc = access(c, "x0");
        for (var order : List.of(List.of(aa, bb, cc), List.of(aa, cc, bb), List.of(bb, aa, cc), List.of(bb, cc, aa), List.of(cc, aa, bb), List.of(cc, bb, aa))) {
            var network = XBusAccess.network(order);
            assertEquals(Optional.of(HISTORY_CONFLICT), network.inspect().rejection());
            assertTrue(network.inspect().recoveries().isEmpty());
            assertEquals(HISTORY_CONFLICT, network.exchange(pin(c, "x0"), pin(b, "x1")));
            assertEquals(before, List.of(a.write(), b.write(), c.write()));
        }
    }
    @Test void futureReceiptPreventsFreshConsumptionUntilLocalInstructionsReachTheRecordedWait() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(c, writer(), INPUTS);
        var inputs = new DataValue.RecordValue(Map.of("delay", new DataValue.IntegerValue(1)));
        start(b, new FlowProgram(Map.of("delay", DataType.Scalar.INTEGER), List.of(new FlowProgram.Sleep("sleep", "delay"),
            new FlowProgram.Read("read", "x1", "packet", PACKET))), inputs);
        var oldB = b.write();
        while (b.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_READ) b.tick(LIMITS, true);
        assertEquals(EXCHANGED, exchange(a, b));
        b = new ComputerBlockHost(() -> {}); b.read(oldB); assertTrue(b.synchronize(LIMITS, true));
        var network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertEquals(Optional.of(RECOVERY_REQUIRED), network.inspect().rejection());
        assertTrue(network.inspect().recoveries().isEmpty(), "Inspection must not run Sleep or the next read");
        var before = b.write(); assertEquals(RECOVERY_REQUIRED, network.exchange(pin(c, "x0"), pin(b, "x1")));
        assertEquals(before, b.write());
        while (b.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.WAITING_READ) b.tick(LIMITS, true);
        network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertEquals(EXCHANGED, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(FlowRuntime.Status.WAITING_WRITE, c.flowSnapshot().orElseThrow().state().status());
    }
    @Test void compactedRetirementStillBlocksOldImagesButDoesNotBlockANewGeneration() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
        var oldB = b.write(); assertEquals(EXCHANGED, exchange(a, b));
        a.reset(LIMITS, true); a.setPaused(false, LIMITS, true); a.tick(LIMITS, true);
        b = restore(oldB);
        var network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        var before = b.write();
        assertTrue(a.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(RETIRED, network.exchange(pin(c, "x0"), pin(b, "x1"))); assertEquals(before, b.write());
        b.reset(LIMITS, true); b.setPaused(false, LIMITS, true); b.tick(LIMITS, true);
        network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
        assertTrue(network.inspect().rejection().isEmpty());
        assertEquals(EXCHANGED, network.exchange(pin(c, "x0"), pin(b, "x1")));
    }
    @Test void everyAliasAndUnusedParticipantMustPassAuthorizationAndExactContinuationChecks() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
        var aa = access(a, "x0"); var bb = access(b, "x1"); var cc = access(c, "x0");
        var before = List.of(a.write(), b.write(), c.write());
        for (var denied : List.of(cc.guarded(() -> Optional.of(CLOSED)), cc.guarded(() -> { throw new IllegalStateException("provider"); }))) {
            var network = XBusAccess.network(List.of(aa, bb, cc, denied));
            assertTrue(Set.of(CLOSED, UNAVAILABLE).contains(network.exchange(pin(a, "x0"), pin(b, "x1"))));
            assertEquals(before, List.of(a.write(), b.write(), c.write()));
        }
        var network = XBusAccess.network(List.of(aa, bb, cc)); c.setPaused(true, LIMITS, true);
        assertEquals(STALE, network.exchange(pin(a, "x0"), pin(b, "x1")));
        c.unload(); assertEquals(UNAVAILABLE, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(before.getFirst(), a.write()); assertEquals(before.get(1), b.write());
    }
    @Test void duplicatePhysicalOwnersAreNotAliasesAndCannotHideBehindEqualSaveImages() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); var copy = restore(a.write());
        var aa = access(a, "x0"); var bb = access(b, "x1");
        var network = XBusAccess.network(List.of(aa, bb, access(copy, "x0")));
        var before = List.of(a.write(), b.write(), copy.write());
        assertEquals(STALE, network.exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(before, List.of(a.write(), b.write(), copy.write()));
        assertEquals(EXCHANGED, XBusAccess.network(List.of(aa, aa, bb, bb)).exchange(pin(a, "x0"), pin(b, "x1")));
    }
    @Test void missingRecoveryPinIsNotBypassedThroughAnotherPinOnTheSameComputer() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
        var oldB = b.write(); assertEquals(EXCHANGED, exchange(a, b)); b = restore(oldB);
        var network = XBusAccess.network(List.of(access(a, "x1"), access(b, "x1"), access(c, "x0")));
        var before = List.of(a.write(), b.write(), c.write());
        assertEquals(PORT_MISMATCH, network.exchange(pin(c, "x0"), pin(b, "x1")));
        assertEquals(before, List.of(a.write(), b.write(), c.write()));
    }
    @Test void repairStillUsesTheRecipientsActualMemoryAndArchiveBudgetWithoutPartialPublication() {
        for (boolean archive : List.of(false, true)) {
            var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
            start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
            var oldB = b.write(); assertEquals(EXCHANGED, exchange(a, b)); b = restore(oldB);
            var saved = b.flowSnapshot().orElseThrow();
            long encoded = ValueNbtCodec.decode(((CompoundTag) b.write()).getCompound("Data"), LIMITS.archive()).storageUnits();
            // Constrain capacity, not the recipient's instruction set, ports or task count.
            // The existing wait (including its protocol metadata) must still be admissible.
            long low = 0, high = LIMITS.hardware().memoryUnits();
            if (!archive) while (low < high) {
                long mid = low + (high - low) / 2;
                if (b.fitsMemory(LIMITS.withMemoryUnits(mid))) high = mid; else low = mid + 1;
            }
            var tight = archive ? new ComputerHostLimits(LIMITS.hardware(), new ArchiveLimits(100000, 1000000, encoded),
                LIMITS.mailbox(), LIMITS.pins(), LIMITS.serial(), LIMITS.taskSlots(), LIMITS.isa1a(), LIMITS.isa1b())
                : LIMITS.withMemoryUnits(high);
            assertTrue(b.synchronize(tight, true), "The older wait itself fits before attempting any repair");
            var observed = XBusPortState.of(saved, tight.pins(), "x1").orElseThrow();
            var access = new XBusAccess(b, observed, () -> tight, Optional::empty);
            var network = XBusAccess.network(List.of(access(a, "x0"), access, access(c, "x0")));
            var before = List.of(a.write(), b.write(), c.write());
            assertEquals(archive ? ARCHIVE_LIMIT : MEMORY_LIMIT, network.exchange(pin(a, "x0"), pin(b, "x1")));
            assertEquals(before, List.of(a.write(), b.write(), c.write()));
            assertEquals(RECOVERY_REQUIRED, network.exchange(pin(c, "x0"), pin(b, "x1")));
            network = XBusAccess.network(List.of(access(a, "x0"), access(b, "x1"), access(c, "x0")));
            assertEquals(EXCHANGED, network.exchange(pin(a, "x0"), pin(b, "x1")));
        }
    }
    @Test void physicalChecksFollowEveryProviderCallbackAndDirtyFailuresAreNeverReportedAsRejection() {
        var a = new ComputerBlockHost(() -> {}); var b = new ComputerBlockHost(() -> {}); var c = new ComputerBlockHost(() -> {});
        start(a, writer(), INPUTS); start(b, reader(), EMPTY); start(c, writer(), INPUTS);
        var connected = new boolean[] { true };
        var aa = access(a, "x0").withPhysicalRoute(Optional::empty, () -> connected[0] ? Optional.empty() : Optional.of(UNAVAILABLE));
        var cc = access(c, "x0").withPhysicalRoute(() -> { connected[0] = false; return Optional.empty(); }, Optional::empty);
        var before = List.of(a.write(), b.write(), c.write());
        assertEquals(UNAVAILABLE, XBusAccess.network(List.of(aa, access(b, "x1"), cc)).exchange(pin(a, "x0"), pin(b, "x1")));
        assertEquals(before, List.of(a.write(), b.write(), c.write()));
        var throwDirty = new boolean[] { false }; var error = new IllegalStateException("after publication");
        var writer = new ComputerBlockHost(() -> { if (throwDirty[0]) throw error; });
        start(writer, writer(), INPUTS); throwDirty[0] = true;
        var network = XBusAccess.network(List.of(access(writer, "x0"), access(b, "x1"), access(c, "x0")));
        assertSame(error, assertThrows(IllegalStateException.class, () -> network.exchange(pin(writer, "x0"), pin(b, "x1"))));
        assertEquals(FlowRuntime.Status.COMPLETE, writer.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.COMPLETE, b.flowSnapshot().orElseThrow().state().status());
        assertEquals(FlowRuntime.Status.COMPLETE, c.flowSnapshot().orElseThrow().state().status());
        assertEquals(writer.flowSnapshot().orElseThrow().receipts(), c.flowSnapshot().orElseThrow().receipts(),
            "Dirty callbacks run only after the entire cohort, including competing writers, is durable");
    }
    static XBusNetwork.Pin pin(ComputerBlockHost host, String pin) {
        return new XBusNetwork.Pin(host.flowSnapshot().orElseThrow().state().computer(), pin);
    }
    static XBusAccess access(ComputerBlockHost host, String pin) {
        var observed = XBusPortState.of(host.flowSnapshot().orElseThrow(), LIMITS.pins(), pin).orElseThrow();
        return new XBusAccess(host, observed, () -> LIMITS, () -> host.loaded() ? Optional.empty() : Optional.of(UNAVAILABLE));
    }
}
