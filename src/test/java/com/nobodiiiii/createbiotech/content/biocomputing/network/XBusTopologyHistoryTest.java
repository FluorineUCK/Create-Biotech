package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class XBusTopologyHistoryTest {
    @Test void retiringALaterPairDoesNotDestroyAnotherComputersStillHeldEarlierRepairWitness() {
        var f = new XBusCableNetworkTest.Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        var c = f.peers.get(f.c.target().position());
        b.host.reset(HOST, true);
        b.host.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("first", "x1", "packet", PACKET),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("second", "x1", "packet", PACKET),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("next", "x1", "packet", PACKET))), EMPTY, HOST, true);
        b.host.setPaused(false, HOST, true); b.host.tick(HOST, true); var oldC = c.host.write();
        a.host.setPaused(true, HOST, true); // Not a participant in C's first cohort.
        // Leave the first publication unconfirmed: a later completed pair must not
        // compact away this older, still necessary recovery payload.
        c.onDirty = () -> { throw new IllegalStateException("unconfirmed first publication"); };
        assertThrows(IllegalStateException.class, () -> XBusCableNetwork.capture(f.links, SCAN, f::resolve)
            .network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
        c.onDirty = () -> {};
        b.host.tick(HOST, true);
        a.host.setPaused(false, HOST, true);
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        b.host.tick(HOST, true); var readerBefore = b.host.write();
        a.host.reset(HOST, true);
        assertTrue(f.history.observe(List.of(a.host.flowSnapshot().orElseThrow()), 4096, 65536).isEmpty());
        f.history = XBusHistorySavedData.load(f.history.save(new net.minecraft.nbt.CompoundTag(), null), null);
        f.peers.values().forEach(p -> p.history = f.history);
        f.unloaded.add(f.a.target().position()); f.cables.remove(new BlockPos(1, 0, 0));
        c.host.read(oldC); c.host.tick(HOST, true);
        var group = XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve);
        assertEquals("recovery_required", group.code(), "B still owns C's receipt; A's later reset did not erase it");
        assertEquals(EXCHANGED, group.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
        assertEquals(readerBefore, b.host.write());
    }
    @Test void repairingAnOldWriterDoesNotReserveOrConsumeTheReadersNextWaitingRead() {
        var f = new XBusCableNetworkTest.Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        b.host.reset(HOST, true);
        b.host.installFlow(new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram(Map.of(), List.of(
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("first", "x1", "packet", PACKET),
            new com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowProgram.Read("next", "x1", "packet", PACKET))), EMPTY, HOST, true);
        b.host.setPaused(false, HOST, true); b.host.tick(HOST, true);
        var oldWriter = a.host.write();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        b.host.tick(HOST, true);
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.WAITING_READ,
            b.host.flowSnapshot().orElseThrow().state().status());
        var readerBefore = b.host.write(); var competitorBefore = f.peers.get(f.c.target().position()).host.write();
        a.host.read(oldWriter); a.host.tick(HOST, true);
        var group = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals("recovery_required", group.code());
        assertEquals(EXCHANGED, group.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        assertEquals(readerBefore, b.host.write(), "Acknowledge the first receipt, never consume the next Read");
        assertEquals(competitorBefore, f.peers.get(f.c.target().position()).host.write());
    }
    @Test void postPublicationDirtyFailureRetainsTheFenceInsteadOfRetryingWithADifferentWriter() {
        var f = new XBusCableNetworkTest.Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        var oldReader = b.host.write();
        var group = XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow();
        a.onDirty = () -> { throw new IllegalStateException("post_publication_failure"); };
        assertEquals("post_publication_failure", assertThrows(IllegalStateException.class, () -> group.exchange(f.pin(f.a), f.pin(f.b))).getMessage());
        assertEquals(INPUTS, b.host.flowSnapshot().orElseThrow().state().locals(), "Both owners were published before notifications");
        b.host.read(oldReader); b.host.tick(HOST, true);
        f.unloaded.add(f.a.target().position()); f.cables.remove(new BlockPos(1, 0, 0));
        var before = f.snapshots();
        var reduced = XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve);
        assertEquals("recovery_required", reduced.code());
        assertEquals(RECOVERY_REQUIRED, reduced.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
        assertEquals(before, f.snapshots());
    }

    @Test void losingAllCohortReceiptsDoesNotLetTheIdentityOnlyServiceInventRepairData() {
        var f = new XBusCableNetworkTest.Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        var oldWriter = a.host.write(); var oldReader = b.host.write();
        var c = f.peers.get(f.c.target().position()); var oldCompetitor = c.host.write();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        a.host.read(oldWriter); b.host.read(oldReader); c.host.read(oldCompetitor);
        a.host.tick(HOST, true); b.host.tick(HOST, true); c.host.tick(HOST, true);
        var before = f.snapshots();
        var capture = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals("recovery_required", capture.code());
        assertTrue(capture.network().orElseThrow().inspect().recoveries().isEmpty());
        assertEquals(RECOVERY_REQUIRED, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        assertEquals(before, f.snapshots());
    }

    @Test void persistedIdentityHistoryStillProtectsADeletedBranchAfterAllTransientAccessesAreDiscarded() {
        var f = new XBusCableNetworkTest.Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        var oldReader = b.host.write();
        assertEquals(EXCHANGED, XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var encoded = f.history.save(new net.minecraft.nbt.CompoundTag(), null);
        assertFalse(encoded.toString().contains("payload")); assertFalse(encoded.toString().contains("packet"));
        f.history = XBusHistorySavedData.load(encoded, null); f.peers.values().forEach(p -> p.history = f.history);
        b.host.read(oldReader); b.host.tick(HOST, true);
        f.unloaded.add(f.a.target().position()); f.cables.remove(new BlockPos(1, 0, 0));
        var capture = XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve);
        assertEquals("retired", capture.code());
        assertTrue(capture.network().isEmpty(),
            "A persisted completion proof refuses access, even though the old read has reappeared");
    }
    @Test void aCapacityRejectionBeforePublicationCannotBeOverriddenByTheOtherAccess() {
        var f = new XBusCableNetworkTest.Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        var left = a.xbusAccess("io", a.xbusPort("io", "x0").orElseThrow()).orElseThrow().historical(f.history, () -> 4096, () -> 0);
        var right = b.xbusAccess("io", b.xbusPort("io", "x1").orElseThrow()).orElseThrow();
        var before = f.snapshots();
        assertEquals(ARCHIVE_LIMIT, left.exchange(right));
        assertEquals(before, f.snapshots());
        var accepted = a.xbusAccess("io", a.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        assertEquals(EXCHANGED, accepted.exchange(right), "Increasing the explicit capacity admits the same pending pair");
    }
    @Test void deletingAnOldWireCannotLetAThirdComputerConsumeTheRestoredRead() {
        var f = new XBusCableNetworkTest.Fixture();
        var a = f.peers.get(f.a.target().position());
        var b = f.peers.get(f.b.target().position());
        var c = f.peers.get(f.c.target().position());
        var oldReader = b.host.write();
        var full = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals(EXCHANGED, full.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var witness = a.host.write();
        b.host.read(oldReader); b.host.tick(HOST, true);
        var readerBefore = b.host.write(); var competitorBefore = c.host.write();
        f.unloaded.add(f.a.target().position()); f.cables.remove(new BlockPos(1, 0, 0));

        // The active topology now contains only B--C, but A's consumption is still authoritative.
        var reduced = XBusCableNetwork.capture(List.of(f.links.get(1)), SCAN, f::resolve);
        assertEquals("recovery_required", reduced.code());
        assertEquals(RECOVERY_REQUIRED, reduced.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
        assertEquals(readerBefore, b.host.write()); assertEquals(competitorBefore, c.host.write());
        assertEquals(witness, a.host.write());

        // Restoring an explicit, physically reachable original route permits the original repair.
        f.unloaded.clear(); f.cables.add(new BlockPos(1, 0, 0));
        var joined = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals(EXCHANGED, joined.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        assertEquals(INPUTS, b.host.flowSnapshot().orElseThrow().state().locals());
        assertEquals(0, a.inboxCalls + b.inboxCalls + c.inboxCalls);
    }
}
