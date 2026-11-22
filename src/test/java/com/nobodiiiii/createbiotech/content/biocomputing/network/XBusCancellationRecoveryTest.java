package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.HOST;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusOccupancyHistoryTest.packet;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.hardware.*;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XBusCancellationRecoveryTest {
    @Test void assemblyCancellationPreservesWaitingRegistersAndOwner24EvidenceWithoutResendingDiscardedInput() {
        var f = new XBusOccupancyHistoryTest.Fixture(3, i -> () -> {}, true);
        assertEquals(Optional.of(f.pin(0)), f.network().selectWriter(f.pins()).writer());
        var waiting = f.saved().get(2).state(); var oldDiscarded = f.hosts.get(2).write();
        f.hosts.getFirst().reset(HOST, true); assertEquals(RETIRED, f.network().settleRetired());
        var failed = f.saved().get(2);
        assertEquals(FlowRuntime.Status.FAILED, failed.state().status());
        assertEquals(ComputationException.Code.XBUS_OPERATION_RETIRED, failed.state().diagnostic().orElseThrow().code());
        assertEquals(waiting.assembly(), failed.state().assembly()); assertEquals(waiting.operation(), failed.state().operation());
        assertEquals(waiting.work(), failed.state().work()); assertTrue(failed.receipts().isEmpty());
        assertFalse(failed.cancellations().isEmpty()); assertEquals(24, ((CompoundTag)f.hosts.get(2).write()).getInt(FlowBlockNbtCodec.MARKER));
        var fresh = f.hosts.get(2).write(); f.hosts.get(2).read(fresh); f.hosts.get(2).tick(HOST, true);
        assertEquals(failed, f.saved().get(2));
        f.hosts.get(2).read(oldDiscarded); f.hosts.get(2).tick(HOST, true);
        f.history = XBusHistorySavedData.load(f.disk(), null);
        assertEquals(RETIRED, f.network().settleRetired()); assertEquals(failed, f.saved().get(2));
        assertFalse(f.saved().get(1).state().locals().fields().containsKey("received"));
    }
    @Test void installingSignalAssemblyPreservesUnretiredRemoteCancellationEvidenceAcrossOwner23Recovery() {
        var f = new XBusOccupancyHistoryTest.Fixture(4); f.network().selectWriter(f.pins());
        f.hosts.getFirst().reset(HOST, true); assertEquals(RETIRED, f.network().settleRetired());
        var host = f.hosts.get(2); var witness = host.flowSnapshot().orElseThrow().cancellations(); assertFalse(witness.isEmpty());
        host.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.XBusStatus("status", "x0", "busy"))).withAssembly(),
            new DataValue.RecordValue(Map.of()), HOST, true);
        var installed = host.flowSnapshot().orElseThrow(); assertEquals(witness, installed.cancellations());
        var raw = (CompoundTag)host.write(); assertEquals(23, raw.getInt(FlowBlockNbtCodec.MARKER));
        var restored = new ComputerBlockHost(() -> {}); restored.read(raw); restored.tick(HOST, true);
        assertTrue(restored.loaded(), () -> restored.diagnostic().toString()); assertEquals(installed, restored.flowSnapshot().orElseThrow());
        assertEquals(raw, restored.write()); restored.step(HOST, true, Map.of());
        assertEquals(FlowRuntime.Status.COMPLETE, restored.flowSnapshot().orElseThrow().state().status());
        assertEquals(new DataValue.IntegerValue(0), restored.flowSnapshot().orElseThrow().state().locals().field("busy"));
        assertEquals(witness, restored.flowSnapshot().orElseThrow().cancellations());
    }
    @Test void aDirtyFailureAfterCancellationLeavesEveryOwnerPublishedAndTheJournalFenceRecoverable() {
        var armed = new java.util.concurrent.atomic.AtomicBoolean(); var notified = new java.util.concurrent.atomic.AtomicInteger();
        var f = new XBusOccupancyHistoryTest.Fixture(4, i -> () -> {
            if (!armed.get()) return;
            if (i == 2) throw new IllegalStateException("after publication");
            if (i == 3) notified.incrementAndGet();
        });
        f.network().selectWriter(f.pins()); var oldC = f.hosts.get(2).write();
        f.hosts.getFirst().reset(HOST, true); armed.set(true);
        assertThrows(IllegalStateException.class, () -> f.network().settleRetired());
        assertEquals(1, notified.get());
        for (int i : List.of(2, 3)) {
            assertEquals(FlowRuntime.Status.FAILED, f.saved().get(i).state().status());
            assertTrue(f.saved().get(i).receipts().isEmpty());
        }
        assertTrue(f.disk().getList("Occupancies", 10).isEmpty()); assertFalse(f.disk().getList("Cancellations", 10).isEmpty());
        var expected = f.saved(); armed.set(false); f.hosts.get(2).read(oldC); f.hosts.get(2).setPaused(false, HOST, true);
        f.history = XBusHistorySavedData.load(f.disk(), null);
        assertEquals(RETIRED, f.network().settleRetired()); assertEquals(expected, f.saved());
        assertEquals(NOT_READY, f.network().settleRetired());
    }
    @Test void missingOrDowngradedCancellationProofIsRetainedAsInvalidInsteadOfReplayingAnOldWrite() {
        var f = new XBusOccupancyHistoryTest.Fixture(); f.network().selectWriter(f.pins());
        f.hosts.getFirst().reset(HOST, true); assertEquals(RETIRED, f.network().settleRetired());
        var good = (CompoundTag)f.hosts.get(2).write(); assertEquals(22, good.getInt(FlowBlockNbtCodec.MARKER));
        var downgrade = good.copy(); downgrade.putInt(FlowBlockNbtCodec.MARKER, 18);
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(good.getCompound("Data"), HOST.archive())).fields());
        fields.remove("cancellations"); var missing = downgrade.copy(); missing.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), HOST.archive()));
        fields.put("cancellations", new DataValue.ListValue(List.of())); var empty = good.copy(); empty.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), HOST.archive()));
        for (var bad : List.of(downgrade, missing, empty)) {
            var host = new ComputerBlockHost(() -> {}); host.read(bad); host.tick(0, HOST, true, Map.of());
            assertFalse(host.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, host.diagnostic().orElseThrow().code());
            assertEquals(bad, host.write());
        }
    }
    @Test void splittingTwoAlreadyCancelledSiblingPinsSettlesOnlyTheAuthorizedPinWithoutBlockingTheOther() {
        var limits = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(HOST.hardware(), HOST.archive(), HOST.mailbox(), HOST.pins(), HOST.serial(), 3));
        var key = new ModuleDefinition.Key("send", 1); var type = XBusOccupancyHistoryTest.PACKET;
        var c = new ComputerBlockHost(() -> {});
        var program = new FlowProgram(Map.of("packet", type), List.of(new FlowProgram.Write("main", "x0", "packet"),
            new FlowProgram.Procedure("child", key, Map.of("packet", type), Map.of(), Map.of()),
            new FlowProgram.Write("write", "x1", "packet"), new FlowProgram.EndProcedure("return")));
        c.installFlow(program, new DataValue.RecordValue(Map.of("packet", packet(99))), limits, true);
        var child = c.startFlowTask(key, new DataValue.RecordValue(Map.of("packet", packet(100))), limits, true);
        c.setPaused(false, limits, true);
        for (int i = 0; i < 4; i++) c.tick(i, limits, true, Map.of());
        var f = new XBusOccupancyHistoryTest.Fixture(); var a = f.hosts.getFirst();
        var together = XBusAccess.network(List.of(access(a, "x0", HOST, f.history), access(c, "x0", limits, f.history), access(c, "x1", limits, f.history)));
        var aPin = f.pin(0); var cId = c.flowSnapshot().orElseThrow().state().computer();
        assertEquals(Optional.of(aPin), together.selectWriter(List.of(aPin, new XBusNetwork.Pin(cId, "x0"), new XBusNetwork.Pin(cId, "x1"))).writer());
        c.setPaused(true, limits, true); a.reset(HOST, true);
        together = XBusAccess.network(List.of(access(a, "x0", HOST, f.history), access(c, "x0", limits, f.history), access(c, "x1", limits, f.history)));
        assertEquals(RETIRED, together.settleRetired());
        c.setPaused(false, limits, true); var beforeChild = c.flowTask(child).orElseThrow();
        var first = XBusAccess.network(List.of(access(a, "x0", HOST, f.history), access(c, "x0", limits, f.history)));
        assertEquals(RETIRED, first.settleRetired());
        assertEquals(FlowRuntime.Status.FAILED, c.flowSnapshot().orElseThrow().state().status());
        assertEquals(beforeChild, c.flowTask(child).orElseThrow(), "An unrelated pin is not authorized by this component");
        first = XBusAccess.network(List.of(access(a, "x0", HOST, f.history), access(c, "x0", limits, f.history)));
        assertEquals(NOT_READY, first.settleRetired());
        assertTrue(first.selectWriter(List.of(aPin, new XBusNetwork.Pin(cId, "x0"))).rejection().isEmpty());
        var second = XBusAccess.network(List.of(access(f.hosts.get(1), "x0", HOST, f.history), access(c, "x1", limits, f.history)));
        assertEquals(RETIRED, second.settleRetired());
        assertEquals(FlowRuntime.Status.FAILED, c.flowTask(child).orElseThrow().status());
        assertTrue(c.flowSnapshot().orElseThrow().receipts().isEmpty());
        var failedMain = c.flowSnapshot().orElseThrow().state();
        c.claimFlowTask(child, limits, true);
        var next = c.startFlowTask(key, new DataValue.RecordValue(Map.of("packet", packet(101))), limits, true);
        for (int i = 4; i < 8; i++) c.tick(i, limits, true, Map.of());
        second = XBusAccess.network(List.of(access(f.hosts.get(1), "x0", HOST, f.history), access(c, "x1", limits, f.history)));
        var cPin = new XBusNetwork.Pin(cId, "x1");
        assertEquals(Optional.of(cPin), second.selectWriter(List.of(f.pin(1), cPin)).writer());
        assertEquals(EXCHANGED, second.exchange(f.pin(1), cPin));
        assertEquals(packet(101), f.saved().get(1).state().locals().field("received"), "A reused slot sends only its fresh input");
        assertEquals(FlowRuntime.Status.READY, c.flowTask(next).orElseThrow().status());
        assertEquals(failedMain, c.flowSnapshot().orElseThrow().state(), "Retiring a child does not reset its failed sibling");
    }
    private static XBusAccess access(ComputerBlockHost host, String pin, ComputerHostLimits limits, XBusHistorySavedData history) {
        return new XBusAccess(host, XBusPortState.of(host.flowSnapshot().orElseThrow(), limits.pins(), pin).orElseThrow(), () -> limits, Optional::empty)
            .historical(history, () -> 10, () -> 100);
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3})
    void aPausedOwnerRejectsAndRetainsInvalidLocalCancellationPositions(int mutation) {
        var f = new XBusOccupancyHistoryTest.Fixture(); f.network().selectWriter(f.pins());
        f.hosts.get(2).setPaused(true, HOST, true); f.hosts.getFirst().reset(HOST, true);
        assertEquals(RETIRED, f.network().settleRetired());
        var saved = f.saved().get(2); var witness = saved.cancellations().getFirst();
        var self = witness.writers().stream().filter(p -> p.computer().equals(saved.state().computer())).findFirst().orElseThrow();
        var future = new XBusReceipt.Position(self.computer(), mutation == 1 ? UUID.randomUUID() : self.flow(),
            mutation == 3 ? saved.program().steps().size() : self.cursor(), mutation == 2 ? "x1" : self.pin(), self.generation() + (mutation == 0 ? 1 : 0),
            mutation == 3 ? saved.program().steps().size() : self.operation(), self.slot(), self.incarnation());
        var writers = new LinkedHashSet<>(witness.writers()); writers.remove(self); writers.add(future);
        var bad = new XBusCancellation(witness.holder(), writers, witness.proof());
        assertThrows(IllegalArgumentException.class, () -> new FlowBlockHost.Saved(saved.program(), saved.initialInputs(), saved.state(),
            saved.receipts(), saved.control(), saved.tasks(), saved.pinClaims(), List.of(bad)));
        var encoded = (CompoundTag)f.hosts.get(2).write();
        var fields = new LinkedHashMap<>(((DataValue.RecordValue)ValueNbtCodec.decode(encoded.getCompound("Data"), HOST.archive())).fields());
        fields.put("cancellations", new DataValue.ListValue(List.of(bad.value())));
        encoded.put("Data", ValueNbtCodec.encode(new DataValue.RecordValue(fields), HOST.archive()));
        var restored = new ComputerBlockHost(() -> {}); restored.read(encoded); restored.tick(0, HOST, true, Map.of());
        assertFalse(restored.loaded()); assertEquals(ComputerBlockHost.Code.INVALID_SAVE, restored.diagnostic().orElseThrow().code());
        assertEquals(encoded, restored.write(), "Bad proof is retained, never discarded to make the old send runnable");
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void mixedOwnerAndDimensionSavesKeepCancellationAndNeverPromoteTheDiscardedValue(int fresh) {
        var f = new XBusOccupancyHistoryTest.Fixture(); f.network().selectWriter(f.pins());
        var oldA = f.hosts.getFirst().write(); var oldC = f.hosts.get(2).write(); var oldHistory = f.disk();
        var a = f.hosts.getFirst(); a.reset(HOST, true); a.setPaused(false, HOST, true); a.step(HOST, true);
        assertEquals(RETIRED, f.network().settleRetired());
        var newA = a.write(); var newC = f.hosts.get(2).write(); var newHistory = f.disk();
        a.read((fresh & 1) != 0 ? newA : oldA); a.setPaused(false, HOST, true);
        f.hosts.get(2).read((fresh & 2) != 0 ? newC : oldC); f.hosts.get(2).setPaused(false, HOST, true);
        f.history = XBusHistorySavedData.load((fresh & 4) != 0 ? newHistory : oldHistory, null);
        if ((fresh & 1) == 0) {
            assertEquals(Optional.of(RETIRED), f.network().selectWriter(f.pins()).rejection());
            assertFalse(f.saved().get(1).state().locals().fields().containsKey("received"));
            a.read(newA); a.setPaused(false, HOST, true);
        }
        var selected = f.network().selectWriter(f.pins().reversed());
        if (selected.rejection().equals(Optional.of(RETIRED))) selected = f.network().selectWriter(f.pins().reversed());
        assertEquals(Optional.of(f.pin(0)), selected.writer());
        assertEquals(FlowRuntime.Status.FAILED, f.saved().get(2).state().status());
        assertEquals(packet(99), f.saved().get(2).state().locals().field("packet"));
        assertTrue(f.saved().get(2).receipts().isEmpty());
        assertEquals(EXCHANGED, f.network().exchange(f.pin(1), f.pin(0)));
        assertEquals(packet(12), f.saved().get(1).state().locals().field("received"));
        var after = f.saved(); assertEquals(NOT_READY, f.network().exchange(f.pin(1), f.pin(2)));
        assertEquals(after, f.saved());
    }

    @Test void rebuildingAllCancellationIdentitiesRespectsBothDimensionBudgetsAtomically() {
        var f = new XBusOccupancyHistoryTest.Fixture(4); f.network().selectWriter(f.pins());
        f.hosts.getFirst().reset(HOST, true); assertEquals(RETIRED, f.network().settleRetired());
        var owner = f.saved().get(2); var history = new XBusHistorySavedData(); var before = history.save(new CompoundTag(), null);
        assertEquals(Optional.of(ARCHIVE_LIMIT), history.observe(List.of(owner), 2, 100));
        assertEquals(before, history.save(new CompoundTag(), null));
        assertEquals(Optional.of(ARCHIVE_LIMIT), history.observe(List.of(owner), 10, 2));
        assertEquals(before, history.save(new CompoundTag(), null));
        assertTrue(history.observe(List.of(owner), 3, 4).isEmpty());
        var saved = history.save(new CompoundTag(), null); var loaded = XBusHistorySavedData.load(saved, null);
        assertTrue(loaded.tracks(f.pin(3).computer())); assertEquals(saved, loaded.save(new CompoundTag(), null));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eachCompetitorMustAffordItsOwnCompleteCancellationSaveBeforeAnyOwnerChanges(boolean archive) {
        var f = new XBusOccupancyHistoryTest.Fixture(4); f.network().selectWriter(f.pins());
        f.hosts.getFirst().reset(HOST, true); f.history.observe(f.saved(), 10, 100);
        var before = f.hosts.stream().map(ComputerBlockHost::write).toList(); var journal = f.disk();
        assertEquals(RETIRED, f.network().settleRetired());
        var complete = f.saved().get(3);
        long units = ValueNbtCodec.decode(((CompoundTag)f.hosts.get(3).write()).getCompound("Data"), HOST.archive()).storageUnits();
        long memory = complete.cancellations().stream().mapToLong(c -> c.value().storageUnits()).sum();
        var tight = ParserHardwareFixture.withIsa1a(new ComputerHostLimits(archive ? HOST.hardware() : new ResourceLimits(1000, memory - 1, 1),
            archive ? new ArchiveLimits(HOST.archive().maxNodes(), HOST.archive().maxStringChars(), units - 1) : HOST.archive(), HOST.mailbox(), HOST.pins()));
        for (int i = 0; i < f.hosts.size(); i++) { f.hosts.get(i).read(before.get(i)); f.hosts.get(i).setPaused(i == 0, HOST, true); }
        f.history = XBusHistorySavedData.load(journal, null);
        var accesses = new ArrayList<XBusAccess>();
        for (int i = 0; i < f.hosts.size(); i++) {
            var limits = i == 3 ? tight : HOST;
            accesses.add(new XBusAccess(f.hosts.get(i), XBusPortState.of(f.saved().get(i), limits.pins(), "x0").orElseThrow(), () -> limits, Optional::empty)
                .historical(f.history, () -> 10, () -> 100));
        }
        assertEquals(archive ? ARCHIVE_LIMIT : MEMORY_LIMIT, XBusAccess.network(accesses).settleRetired());
        assertEquals(before, f.hosts.stream().map(ComputerBlockHost::write).toList());
        assertEquals(journal, f.disk()); assertTrue(f.hosts.stream().allMatch(ComputerBlockHost::loaded));
        assertEquals(RETIRED, f.network().settleRetired());
    }
}
