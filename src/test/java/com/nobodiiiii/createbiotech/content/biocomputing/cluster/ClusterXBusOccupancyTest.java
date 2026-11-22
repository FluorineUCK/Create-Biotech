package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.ClusterXBusBroadcastTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;

class ClusterXBusOccupancyTest {
    @BeforeAll static void setup() throws Exception { ClusterControllerRuntimeTest.bootstrap(); }

    @Test void holderResetSettlesTheCompetingOperationInTheActualRoundWithoutTransferFeedbackForCancellation() throws Exception {
        var f = waiting(); var a = f.computers().getFirst(); var b = f.computers().get(1); var c = f.third();
        round(f, 0); write(c, 99); round(f, 1);
        var before = c.flowSnapshot().orElseThrow().state();
        write(a, 42); read(b);
        var result = round(f, 0);
        assertEquals(1, result.failedOperations(), "Explicit retirement is progress, not a perpetually unhandled wait");
        assertEquals(FlowRuntime.Status.FAILED, c.flowSnapshot().orElseThrow().state().status());
        assertEquals(ComputationException.Code.XBUS_OPERATION_RETIRED, c.flowSnapshot().orElseThrow().state().diagnostic().orElseThrow().code());
        assertEquals(before.locals(), c.flowSnapshot().orElseThrow().state().locals());
        assertTrue(c.flowSnapshot().orElseThrow().receipts().isEmpty());
        assertEquals(result.exchanges(), f.world().particles, "Cancellation is not shown as a data transfer");
        for (int i = 0; i < 3 && b.flowSnapshot().orElseThrow().state().status() != FlowRuntime.Status.COMPLETE; i++) round(f, i);
        assertEquals(packet(42), b.flowSnapshot().orElseThrow().state().locals().field("received"));
    }

    @Test void pausingTheAdmittedWriterRejectsActiveWaitersWithoutPromotingTheCompetingInput() throws Exception {
        var f = waiting(); var a = f.computers().getFirst(); var b = f.computers().get(1); var c = f.third();
        round(f, 0); write(c, 99); round(f, 1); a.setPaused(true); read(b);
        var paused = a.flowSnapshot(); var disk = XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null);
        assertEquals(0, XBusConnectedNetwork.capture(f.world(), b, "x0").connected().orElseThrow().network().statusCode(pin(b)));
        assertEquals(disk, XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null), "Status sampling cannot change occupancy");
        var result = round(f, 0);
        assertEquals(0, result.exchanges()); assertEquals(2, result.failedOperations());
        for (var host : List.of(b, c)) {
            var state = host.flowSnapshot().orElseThrow().state();
            assertEquals(FlowRuntime.Status.FAILED, state.status());
            assertEquals(ComputationException.Code.XBUS_PEER_PAUSED, state.diagnostic().orElseThrow().code());
        }
        assertEquals(paused, a.flowSnapshot());
        assertFalse(b.flowSnapshot().orElseThrow().state().locals().fields().containsKey("received"));
        a.setPaused(false); read(b); assertEquals(1, round(f, 0).exchanges());
        assertEquals(packet(12), b.flowSnapshot().orElseThrow().state().locals().field("received"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void newerOwnerReceiptsReplaceAnOlderUnconsumedJournalWithoutLeavingAnInvalidOrPermanentOccupancy(int oldMask) throws Exception {
        var f = waiting(); var a = f.computers().getFirst(); var b = f.computers().get(1); var c = f.third();
        round(f, 0); write(c, 99); round(f, 1); read(b);
        var before = f.computers().stream().map(ClusterXBusOccupancyTest::save).toList();
        var held = XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null);
        assertEquals(1, round(f, 0).exchanges());
        var complete = f.computers().stream().map(ComputerBehaviour::flowSnapshot).toList();
        for (int i = 0; i < before.size(); i++) if ((oldMask & 1 << i) != 0) restore(f.computers().get(i), before.get(i));
        f.world().getDataStorage().set(XBusHistorySavedData.DATA_NAME, XBusHistorySavedData.load(held, null));
        var connected = XBusConnectedNetwork.capture(f.world(), b, "x0").connected().orElseThrow();
        var inspection = connected.network().inspect();
        if (oldMask != 0) {
            assertEquals(Optional.of(XBusExchange.Status.RECOVERY_REQUIRED), inspection.rejection());
            assertEquals(XBusExchange.Status.EXCHANGED, connected.network().exchange(pin(b), pin(a)));
        } else assertTrue(inspection.rejection().isEmpty());
        assertEquals(complete, f.computers().stream().map(ComputerBehaviour::flowSnapshot).toList());
        var journal = XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null);
        assertTrue(journal.getList("Occupancies", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),
            "A retained consumed receipt supersedes the unconsumed occupancy");
        var reloaded = XBusHistorySavedData.load(journal, null);
        assertTrue(reloaded.observe(f.computers().stream().map(h -> h.flowSnapshot().orElseThrow()).toList(), 100, 100).isEmpty());
        assertEquals(journal, reloaded.save(new CompoundTag(), null));
    }

    @Test void firstConnectedWriterKeepsTheBusAcrossRoundsAndReloadInsteadOfTheNextSeedPromotingItsCompetitor() throws Exception {
        var f = waiting(); var a = f.computers().getFirst(); var b = f.computers().get(1); var c = f.third();
        assertEquals(0, round(f, 0).exchanges()); // A is the only sender; nobody is reading yet.
        write(c, 99);
        assertEquals(0, round(f, 1).exchanges()); // A later sender must not replace A, even before a reader exists.
        var snapshots = f.computers().stream().map(ClusterXBusOccupancyTest::save).toList();
        var journal = XBusHistorySavedData.get(f.world()).save(new CompoundTag(), null);
        for (int i = 0; i < snapshots.size(); i++) restore(f.computers().get(i), snapshots.get(i));
        f.world().getDataStorage().set(XBusHistorySavedData.DATA_NAME, XBusHistorySavedData.load(journal, null));
        read(b);
        var connected = XBusConnectedNetwork.capture(f.world(), b, "x0").connected().orElseThrow();
        long adversarialSeed = 0;
        while (!connected.topology().round(adversarialSeed).peek(pin(b), Set.of(pin(a), pin(c))).orElseThrow().writer().equals(pin(c))) adversarialSeed++;
        var result = round(f, adversarialSeed);
        assertEquals(1, result.exchanges(), result.diagnostics().toString());
        assertEquals(packet(12), b.flowSnapshot().orElseThrow().state().locals().field("received"));
        assertEquals(FlowRuntime.Status.COMPLETE, c.flowSnapshot().orElseThrow().state().status());
        assertEquals(1, f.world().particles, "Discarded competitor input is not another data delivery");
        read(b);
        assertEquals(0, round(f, 0).exchanges(), "The competitor must never become a queued second send");
    }

    static Three waiting() throws Exception {
        var f = three();
        for (var host : List.of(f.computers().get(1), f.third())) {
            host.reset(); host.installFlow(new FlowProgram(Map.of("delay", DataType.Scalar.INTEGER),
                List.of(new FlowProgram.Sleep("sleep", "delay"))), new DataValue.RecordValue(Map.of("delay", new DataValue.IntegerValue(100))));
            host.setPaused(false); host.step();
        }
        return f;
    }
    static ComputerExecutionCycle.Result round(Three f, long seed) {
        f.world().clock++;
        return new ComputerExecutionCycle(f.world(), f.computers(), f.world().clock).advanceRound(seed);
    }
    static void write(ComputerBehaviour host, int value) {
        host.reset(); host.installFlow(new FlowProgram(Map.of("packet", PACKET), List.of(new FlowProgram.Write("send", "x0", "packet"))),
            new DataValue.RecordValue(Map.of("packet", packet(value)))); host.setPaused(false); host.step();
    }
    static void read(ComputerBehaviour host) {
        host.reset(); host.installFlow(new FlowProgram(Map.of(), List.of(new FlowProgram.Read("read", "x0", "received", PACKET))), EMPTY);
        host.setPaused(false); host.step();
    }
    static DataValue.RecordValue packet(int value) { return new DataValue.RecordValue(Map.of("value", new DataValue.IntegerValue(value))); }
    static XBusNetwork.Pin pin(ComputerBehaviour host) { return new XBusNetwork.Pin(host.communicationIdentity().orElseThrow(), "x0"); }
    static CompoundTag save(ComputerBehaviour host) { var tag = new CompoundTag(); host.write(tag, RegistryAccess.EMPTY, false); return tag; }
    static void restore(ComputerBehaviour host, CompoundTag tag) { host.read(tag, RegistryAccess.EMPTY, false); host.communicationIdentity(); }
}
