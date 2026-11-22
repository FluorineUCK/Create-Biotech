package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class XBusWiringObservationTest {
    private static XBusWiringSavedData enabled(XBusCableNetworkTest.Fixture f) {
        var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {}); return store;
    }
    @Test void closedUnloadedRemovedAndReplacedEndpointsAreNotShownAsUsable() {
        for (String fault : List.of("closed", "unloaded", "missing", "identity", "cable", "provider")) {
            var f = new XBusCableNetworkTest.Fixture(); var store = enabled(f); var original = f.snapshots();
            var a = f.peers.get(f.a.target().position());
            switch (fault) {
                case "closed" -> a.open = false;
                case "unloaded" -> f.unloaded.add(f.a.target().position());
                case "missing" -> f.peers.remove(f.a.target().position());
                case "identity" -> a.device = UUID.randomUUID();
                case "cable" -> f.cables.clear();
                case "provider" -> a.throwing = true;
            }
            var observed = XBusWiringObservation.inspect(store, store.enabledConnections().getFirst(), f::resolve);
            assertEquals(switch (fault) {
                case "unloaded" -> "target_unloaded";
                case "missing" -> "not_found";
                case "identity" -> "identity_changed";
                case "cable" -> "unreachable";
                case "provider" -> "provider_failure";
                default -> fault;
            }, observed.code(), fault);
            assertTrue(observed.modes().isEmpty(), fault); assertEquals(original.getFirst(), a.host.write());
        }
    }
    @Test void disablingDuringLateCallbacksRevokesFeedbackAndOldTicketsNeverCallProviders() {
        var f = new XBusCableNetworkTest.Fixture(); var store = enabled(f);
        var ticket = store.enabledConnections().getFirst(); var before = f.snapshots();
        f.peers.get(f.b.target().position()).onLimits = () -> {
            var current = store.view(f.a.target().device());
            if (current.entries().getFirst().enabled()) store.setEnabled(current, "wire", false, ignored -> {});
        };
        assertEquals("stale", XBusWiringObservation.inspect(store, ticket, f::resolve).code());
        assertEquals("stale", XBusWiringObservation.inspect(store, ticket, (a, b) -> {
            throw new AssertionError("Revoked declarations must not inspect peers");
        }).code());
        assertEquals(before, f.snapshots());
    }
    @Test void callbackCannotHideABrokenEarlierPhysicalRouteAndMalformedRepliesStayPrivate() {
        var f = new XBusCableNetworkTest.Fixture(); var store = enabled(f); var ticket = store.enabledConnections().getFirst();
        f.peers.get(f.b.target().position()).onLimits = () -> f.cables.clear();
        var before = f.snapshots();
        assertNotEquals("ready", XBusWiringObservation.inspect(store, ticket, f::resolve).code());
        for (String fault : List.of("throw", "empty", "identity")) {
            var observed = XBusWiringObservation.inspect(store, ticket, (a, b) -> switch (fault) {
                case "throw" -> throw new IllegalStateException("private data");
                case "empty" -> new XBusCableRoute.Result("ready", Optional.empty());
                default -> null;
            });
            assertEquals("provider_failure", observed.code()); assertTrue(observed.modes().isEmpty());
        }
        assertEquals(before, f.snapshots());
    }
    @Test void observingARealWaitingPairReturnsOnlyModesWithoutExchangingOrUpdatingHistory() {
        var f = new XBusCableNetworkTest.Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {});
        var before = f.snapshots(); var history = f.history.save(new CompoundTag(), null);
        var observed = XBusWiringObservation.inspect(store, store.enabledConnections().getFirst(), f::resolve);
        assertEquals("ready", observed.code());
        assertEquals(XBusPortState.Mode.WRITE, observed.modes().orElseThrow().source());
        assertEquals(XBusPortState.Mode.READ, observed.modes().orElseThrow().target());
        assertEquals(before, f.snapshots());
        assertEquals(history, f.history.save(new CompoundTag(), null), "Cosmetics cannot register or retire history");
        assertTrue(f.peers.values().stream().allMatch(peer -> peer.inboxCalls == 0));
    }
}
