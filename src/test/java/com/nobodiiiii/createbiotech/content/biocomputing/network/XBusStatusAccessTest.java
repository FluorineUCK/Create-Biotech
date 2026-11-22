package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import org.junit.jupiter.api.Test;

class XBusStatusAccessTest {
    @Test void statusCannotRepairAnOlderOwnerOrPublishAnyDimensionHistory() {
        var f = new XBusCableNetworkTest.Fixture(); var reader = f.peers.get(f.a.target().position());
        var oldReader = reader.host.write(); var before = f.snapshots();
        var network = XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow();
        var history = f.history.save(new CompoundTag(), null); f.history.setDirty(false);
        assertTrue(network.readyWriter(f.pin(f.b)).isEmpty(), "Do not choose a winner on a contended bus");
        var pair = XBusCableNetwork.capture(List.of(f.links.getFirst()), SCAN, f::resolve).network().orElseThrow();
        assertEquals(f.pin(f.a).computer(), pair.readyWriter(f.pin(f.b)).orElseThrow().computer());
        assertTrue(pair.readyWriter(f.pin(f.a)).isEmpty(), "Never notify a sender of its own offer");
        assertEquals(15, network.statusCode(f.pin(f.a))); assertEquals(before, f.snapshots());
        assertEquals(history, f.history.save(new CompoundTag(), null)); assertFalse(f.history.isDirty());
        assertEquals(XBusExchange.Status.EXCHANGED, network.exchange(f.pin(f.a), f.pin(f.b)));
        history = f.history.save(new CompoundTag(), null); f.history.setDirty(false);
        reader.host.read(oldReader); reader.host.setPaused(false, HOST, true); before = f.snapshots();
        network = XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow();
        assertEquals(0, network.statusCode(f.pin(f.a)), "Unsettled history is an error, not busy or an implicit repair");
        assertEquals(before, f.snapshots()); assertEquals(history, f.history.save(new CompoundTag(), null)); assertFalse(f.history.isDirty());
    }
    @Test void staleUnloadedOrRevokedCapturedRoutesReadZeroWithoutTouchingPayloads() {
        for (String fault : List.of("unloaded", "closed", "changed", "disconnected")) {
            var f = new XBusCableNetworkTest.Fixture();
            var network = XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().orElseThrow();
            switch (fault) {
                case "unloaded" -> f.unloaded.add(f.b.target().position());
                case "closed" -> f.peers.get(f.b.target().position()).open = false;
                case "changed" -> f.peers.get(f.b.target().position()).host.setPaused(true, HOST, true);
                case "disconnected" -> f.cables.clear();
            }
            var before = f.snapshots(); var history = f.history.save(new CompoundTag(), null); f.history.setDirty(false);
            assertTrue(network.readyWriter(f.pin(f.b)).isEmpty(), fault);
            assertEquals(0, network.statusCode(f.pin(f.a)), fault);
            assertEquals(before, f.snapshots()); assertEquals(history, f.history.save(new CompoundTag(), null)); assertFalse(f.history.isDirty());
        }
    }
}
