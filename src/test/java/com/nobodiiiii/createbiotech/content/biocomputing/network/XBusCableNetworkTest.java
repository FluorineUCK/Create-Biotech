package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.network.XBusCableRouteTest.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange;
import static com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.*;
import java.util.*;
import net.minecraft.core.*;
import org.junit.jupiter.api.Test;

class XBusCableNetworkTest {
    @Test void aCapturedSubsetCannotSilentlyOmitAnotherOwnersAlreadyEnabledBranch() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {});
        var subset = store.enabledConnections();
        var branch = store.add(store.view(f.c.target().device()), "branch", f.c.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(branch, "branch", true, ignored -> {});
        var calls = new java.util.concurrent.atomic.AtomicInteger(); var before = f.snapshots();
        var capture = XBusCableNetwork.captureEnabled(store, subset, SCAN, (source, target) -> {
            calls.incrementAndGet(); return f.resolve(source, target);
        });
        assertEquals("incomplete_configuration", capture.code());
        assertTrue(capture.network().isEmpty());
        assertEquals(0, calls.get(), "Omission must be rejected before any provider callbacks");
        assertEquals(before, f.snapshots());
    }
    @Test void anotherOwnersNewBranchRevokesAnOlderCaptureBeforeEitherHostConsumes() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {});
        var capture = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
        assertEquals("ready", capture.code());
        var before = f.snapshots();
        var branch = store.add(store.view(f.c.target().device()), "branch", f.c.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(branch, "branch", true, ignored -> {});

        assertEquals(STALE, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)),
            "A new branch owned by another device changes this physical network's arbitration");
        assertEquals(before, f.snapshots());
        var fresh = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
        assertEquals("ready", fresh.code());
        assertEquals(4, fresh.diagnostics().size());
        assertEquals(EXCHANGED, fresh.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
    }
    @Test void aBranchAddedByAProviderCallbackIsCheckedAfterEveryParticipantCallback() {
        for (boolean duringCapture : List.of(false, true)) {
            var f = new Fixture(); var store = new XBusWiringSavedData();
            var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
            store.setEnabled(added, "wire", true, ignored -> {});
            var before = f.snapshots(); var once = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable connect = () -> {
                if (!once.compareAndSet(false, true)) return;
                var branch = store.add(store.view(f.c.target().device()), "branch", f.c.target(), f.b.target(), 8, ignored -> {});
                store.setEnabled(branch, "branch", true, ignored -> {});
            };
            if (duringCapture) f.peers.get(f.b.target().position()).onLimits = connect;
            var capture = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
            if (duringCapture) {
                assertEquals("stale", capture.code()); assertTrue(capture.network().isEmpty());
            } else {
                assertEquals("ready", capture.code());
                f.peers.get(f.b.target().position()).onLimits = connect;
                assertEquals(STALE, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
            }
            assertTrue(once.get()); assertEquals(before, f.snapshots());
        }
    }
    @Test void unrelatedNetworksDisabledBranchesAndRejectedEditsDoNotInvalidateThisComponent() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {});
        var capture = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
        var before = f.snapshots();
        var branch = store.add(store.view(f.c.target().device()), "disabled", f.c.target(), f.b.target(), 8, ignored -> {});
        assertThrows(IllegalStateException.class, () -> store.setEnabled(branch, "disabled", true,
            ignored -> { throw new IllegalStateException("reply capacity"); }));
        assertEquals(branch, store.view(f.c.target().device()));
        // This is a separate physical pin even though the Computer identity and cable position are shared.
        var c = f.c.target();
        var otherPin = new XBusCableTarget(c.position(), c.domain(), c.endpoint(), "x1", c.device(), c.computer());
        var unrelated = store.add(branch, "other_pin", otherPin, XBusWiringSavedDataTest.target(30), 8, ignored -> {});
        store.setEnabled(unrelated, "other_pin", true, ignored -> {});
        assertTrue(capture.network().orElseThrow().inspect().rejection().isEmpty());
        assertEquals(before, f.snapshots());
        assertEquals(EXCHANGED, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
    }
    @Test void removingANewBranchDoesNotResurrectAnOlderCaptureEvenWithTheOriginalTopology() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {});
        var original = store.enabledConnections();
        var capture = XBusCableNetwork.captureEnabled(store, original, SCAN, f::resolve);
        var before = f.snapshots();
        var branch = store.add(store.view(f.c.target().device()), "branch", f.c.target(), f.b.target(), 8, ignored -> {});
        var enabled = store.setEnabled(branch, "branch", true, ignored -> {});
        store.remove(enabled, "branch", ignored -> {});
        assertEquals(original, store.enabledConnections());
        assertEquals(STALE, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        assertEquals(before, f.snapshots());
        assertEquals(EXCHANGED, XBusCableNetwork.captureEnabled(store, original, SCAN, f::resolve)
            .network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
    }
    @Test void restoredDeclarationsRebuildTheClosureAndAliasesCannotHideAnOmittedPhysicalPin() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(added, "wire", true, ignored -> {});
        var subset = store.enabledConnections();
        var b = f.b.target();
        var alias = new XBusCableTarget(b.position().above(), b.domain(), "gateway_alias", b.pin(), UUID.randomUUID(), b.computer());
        var branch = store.add(store.view(f.c.target().device()), "branch", f.c.target(), alias, 8, ignored -> {});
        store.setEnabled(branch, "branch", true, ignored -> {});
        var tag = store.save(new net.minecraft.nbt.CompoundTag(), null);
        var restored = XBusWiringSavedData.load(tag, null); var before = f.snapshots();
        assertEquals(tag, restored.save(new net.minecraft.nbt.CompoundTag(), null), "Derived neighborhoods add no save fields");
        assertEquals("incomplete_configuration", XBusCableNetwork.captureEnabled(restored, subset, SCAN,
            (source, target) -> { throw new AssertionError("Do not resolve an incomplete component"); }).code());
        assertEquals(before, f.snapshots());
        restored.remove(restored.view(f.c.target().device()), "branch", ignored -> {});
        var fresh = XBusCableNetwork.captureEnabled(restored, restored.enabledConnections(), SCAN, f::resolve);
        assertEquals("ready", fresh.code());
        assertEquals(EXCHANGED, fresh.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
    }
    @Test void aLateThirdPartyCallbackCannotUseARevokedDeclarationAndDisabledInputsNeverResolve() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        for (var link : f.links) {
            var owner = link.left().target().device();
            var added = store.add(store.view(owner), "wire", link.left().target(), link.right().target(), 8, ignored -> {});
            store.setEnabled(added, "wire", true, ignored -> {});
        }
        var enabled = store.enabledConnections(); var before = f.snapshots();
        var capture = XBusCableNetwork.captureEnabled(store, enabled, SCAN, f::resolve);
        assertEquals("ready", capture.code());
        f.peers.get(f.c.target().position()).onLimits = () -> {
            var current = store.view(f.a.target().device());
            if (current.entries().getFirst().enabled()) store.setEnabled(current, "wire", false, ignored -> {});
        };
        assertEquals(STALE, capture.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
        assertEquals(before, f.snapshots());
        java.util.function.BiFunction<XBusCableNetwork.End, XBusCableNetwork.End, XBusCableRoute.Result> forbidden =
            (source, target) -> { throw new AssertionError("Invalid declarations must not invoke providers"); };
        assertEquals("stale", XBusCableNetwork.captureEnabled(store, enabled, SCAN, forbidden).code());
        assertEquals("endpoint_limit", XBusCableNetwork.captureEnabled(store, enabled, new CommunicationNetworkLimits(100, 3), forbidden).code());
        assertEquals("invalid_configuration", XBusCableNetwork.captureEnabled(store, List.of(), SCAN, forbidden).code());
    }
    @Test void removingAnEnabledLineRetainsConsumedHistoryAndCannotLetAThirdPartyTakeTheOldRead() {
        var f = new Fixture(); var store = new XBusWiringSavedData();
        var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
        var enabled = store.setEnabled(added, "wire", true, ignored -> {});
        var reader = f.peers.get(f.b.target().position()); var oldRead = reader.host.write();
        var capture = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
        assertEquals(EXCHANGED, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        var history = f.history.save(new net.minecraft.nbt.CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY);
        store.remove(enabled, "wire", ignored -> {});
        assertEquals(history, f.history.save(new net.minecraft.nbt.CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY));
        reader.host.read(oldRead); reader.host.tick(HOST, true);
        var next = store.add(store.view(f.c.target().device()), "replacement", f.c.target(), f.b.target(), 8, ignored -> {});
        store.setEnabled(next, "replacement", true, ignored -> {});
        var before = f.snapshots();
        var denied = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
        assertEquals("recovery_required", denied.code());
        assertEquals(RECOVERY_REQUIRED, denied.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
        assertEquals(before, f.snapshots());
        var retained = f.history.save(new net.minecraft.nbt.CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY);
        assertEquals(history.get("Claims"), retained.get("Claims"));
        // Capture may register C's identity frontier; it must not retire A/B or create a competing claim.
        assertTrue(retained.getList("Frontiers", net.minecraft.nbt.Tag.TAG_COMPOUND)
            .containsAll(history.getList("Frontiers", net.minecraft.nbt.Tag.TAG_COMPOUND)));
    }
    @Test void disablingOrReplacingADeclarationStopsAnAlreadyCapturedNetworkBeforeItConsumes() {
        for (boolean delete : List.of(false, true)) {
            var f = new Fixture(); var store = new XBusWiringSavedData();
            var added = store.add(store.view(f.a.target().device()), "wire", f.a.target(), f.b.target(), 8, ignored -> {});
            var enabled = store.setEnabled(added, "wire", true, ignored -> {});
            var connections = store.enabledConnections();
            var capture = XBusCableNetwork.captureEnabled(store, connections, SCAN, f::resolve);
            assertEquals("ready", capture.code()); var before = f.snapshots();
            if (delete) {
                var removed = store.remove(enabled, "wire", ignored -> {});
                var recreated = store.add(removed, "wire", f.a.target(), f.b.target(), 8, ignored -> {});
                store.setEnabled(recreated, "wire", true, ignored -> {});
            } else {
                var disabled = store.setEnabled(enabled, "wire", false, ignored -> {});
                store.setEnabled(disabled, "wire", true, ignored -> {});
            }
            assertEquals(STALE, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
            assertEquals(before, f.snapshots());
            assertEquals("stale", XBusCableNetwork.captureEnabled(store, connections, SCAN, f::resolve).code());
            var current = XBusCableNetwork.captureEnabled(store, store.enabledConnections(), SCAN, f::resolve);
            assertEquals(EXCHANGED, current.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        }
    }
    @Test void declaredForkCapturesBothDirectionsOfEveryEdgeWithoutConsumingOrAddingUndeclaredPeers() {
        var f = new Fixture(); var before = f.snapshots();
        var capture = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals("ready", capture.code()); assertEquals(4, capture.diagnostics().size());
        assertTrue(capture.diagnostics().stream().allMatch(d -> d.code().equals("ready")));
        assertEquals(before, f.snapshots());
        assertEquals(EXCHANGED, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        assertEquals(INPUTS, f.peers.get(f.b.target().position()).host.flowSnapshot().orElseThrow().state().locals());
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.FlowRuntime.Status.COMPLETE,
            f.peers.get(f.c.target().position()).host.flowSnapshot().orElseThrow().state().status(), "A competing writer finishes this cohort, never queues a second send");
        assertEquals(3, f.peers.get(f.c.target().position()).host.flowSnapshot().orElseThrow().receipts().getFirst().positions().size());
        assertEquals(0, f.peers.values().stream().mapToInt(p -> p.inboxCalls).sum());
    }
    @Test void oneUnloadedConfiguredParticipantNeverBecomesAPartialGroupOfTheOtherTwo() {
        var f = new Fixture(); var before = f.snapshots(); f.unloaded.add(f.a.target().position());
        var capture = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertTrue(capture.network().isEmpty(), "A missing declared participant must not be filtered out");
        assertEquals("target_unloaded", capture.code());
        assertTrue(capture.diagnostics().stream().anyMatch(d -> d.side() == XBusCableNetwork.Side.LEFT && d.code().equals("target_unloaded")));
        assertEquals(before, f.snapshots());
    }
    @Test void survivingCompetingWriterCanRepairOnlyTheOriginalCohortAfterARejoin() {
        var f = new Fixture(); var a = f.peers.get(f.a.target().position()); var b = f.peers.get(f.b.target().position());
        var oldRead = b.host.write(); var original = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals(EXCHANGED, original.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        b.host.read(oldRead); b.host.tick(HOST, true); var before = f.snapshots();
        f.unloaded.add(f.a.target().position());
        assertTrue(XBusCableNetwork.capture(f.links, SCAN, f::resolve).network().isEmpty());
        f.unloaded.clear();
        var capture = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
        assertEquals("recovery_required", capture.code());
        assertEquals(EXCHANGED, capture.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)),
            "C holds the same full cohort receipt, not a new competing transmission");
        assertEquals(INPUTS, b.host.flowSnapshot().orElseThrow().state().locals());
        assertEquals(before.getFirst(), a.host.write()); assertEquals(before.get(2), f.snapshots().get(2));
    }
    @Test void configuredParticipantFailuresAreClassifiedWithoutReturningTheRemainingAuthorities() {
        for (String fault : List.of("closed", "identity", "missing", "disconnected", "throwing")) {
            var f = new Fixture(); var a = f.peers.get(f.a.target().position()); var before = f.snapshots();
            switch (fault) {
                case "closed" -> a.open = false;
                case "identity" -> a.device = UUID.randomUUID();
                case "missing" -> f.peers.remove(f.a.target().position());
                case "disconnected" -> f.cables.remove(new BlockPos(1, 0, 0));
                case "throwing" -> a.throwing = true;
            }
            var capture = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
            var expected = switch (fault) {
                case "identity" -> "identity_changed";
                case "missing" -> "not_found";
                case "disconnected" -> "unreachable";
                case "throwing" -> "provider_failure";
                default -> fault;
            };
            assertEquals(expected, capture.code(), fault); assertTrue(capture.network().isEmpty(), fault);
            assertEquals(before.getFirst(), a.host.write());
            assertEquals(before.get(1), f.peers.get(f.b.target().position()).host.write());
            assertEquals(before.get(2), f.peers.get(f.c.target().position()).host.write());
        }
    }
    @Test void lateThirdPartyCallbacksAndChangesAfterCaptureCannotInvalidateEarlierRoutesSilently() {
        for (boolean afterCapture : List.of(false, true)) for (boolean close : List.of(false, true)) {
            var f = new Fixture(); var before = f.snapshots(); var third = f.peers.get(f.c.target().position());
            Runnable invalidate = () -> { if (close) f.peers.get(f.a.target().position()).open = false; else f.cables.remove(new BlockPos(1, 0, 0)); };
            if (!afterCapture) third.onGuard = invalidate;
            var capture = XBusCableNetwork.capture(f.links, SCAN, f::resolve);
            if (afterCapture) {
                assertEquals("ready", capture.code()); invalidate.run();
                assertEquals(close ? CLOSED : UNAVAILABLE, capture.network().orElseThrow().exchange(f.pin(f.c), f.pin(f.b)));
            } else {
                assertEquals(close ? "closed" : "unavailable", capture.code()); assertTrue(capture.network().isEmpty());
            }
            assertEquals(before, f.snapshots());
        }
    }
    @Test void emptyDisconnectedAndOverBudgetDeclarationsNeverCallAnyProvider() {
        var f = new Fixture(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiFunction<XBusCableNetwork.End, XBusCableNetwork.End, XBusCableRoute.Result> resolver = (a, b) -> { calls.incrementAndGet(); return f.resolve(a, b); };
        assertEquals("invalid_configuration", XBusCableNetwork.capture(List.of(), SCAN, resolver).code());
        assertEquals("endpoint_limit", XBusCableNetwork.capture(f.links, new CommunicationNetworkLimits(100, 3), resolver).code());
        var other = new XBusCableNetwork.End(new XBusCableTarget(new BlockPos(10, 0, 0), "primary", "io", "x0", UUID.randomUUID(), UUID.randomUUID()), Set.of(Direction.EAST));
        var isolated = new XBusCableNetwork.Link(other, other);
        assertEquals("disconnected_configuration", XBusCableNetwork.capture(List.of(f.links.getFirst(), isolated), SCAN, resolver).code());
        assertEquals(0, calls.get());
    }
    @Test void duplicateEdgesRetainAllGuardsAndMalformedResolverRepliesNeverBecomeAuthority() {
        var f = new Fixture(); var copies = List.of(f.links.getFirst(), f.links.getFirst(), f.links.get(1));
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var capture = XBusCableNetwork.capture(copies, SCAN, (a, b) -> { count.incrementAndGet(); return f.resolve(a, b); });
        assertEquals(6, count.get()); assertEquals(6, capture.diagnostics().size());
        assertEquals(EXCHANGED, capture.network().orElseThrow().exchange(f.pin(f.a), f.pin(f.b)));
        for (String fault : List.of("null", "throw", "empty_ready", "different_target")) {
            var original = f.snapshots();
            var denied = XBusCableNetwork.capture(f.links, SCAN, (a, b) -> switch (fault) {
                case "null" -> null;
                case "throw" -> throw new IllegalStateException("private provider data");
                case "empty_ready" -> new XBusCableRoute.Result("ready", Optional.empty());
                default -> f.resolve(f.a, f.c);
            });
            assertTrue(denied.network().isEmpty()); assertEquals(fault.equals("different_target") ? "identity_changed" : "provider_failure", denied.code());
            assertEquals(original, f.snapshots());
        }
    }
    static final class Fixture {
        XBusHistorySavedData history = new XBusHistorySavedData();
        final Map<BlockPos, Peer> peers = new LinkedHashMap<>(); final Set<BlockPos> unloaded = new HashSet<>();
        final Set<BlockPos> cables = new HashSet<>(List.of(new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0), new BlockPos(2, 0, 1)));
        final XBusCableNetwork.End a = end(new BlockPos(0, 0, 0), new Peer(true), "x0");
        final XBusCableNetwork.End b = end(new BlockPos(4, 0, 0), new Peer(false), "x1");
        final XBusCableNetwork.End c = end(new BlockPos(2, 0, 2), new Peer(true), "x0");
        final List<XBusCableNetwork.Link> links = List.of(new XBusCableNetwork.Link(a, b), new XBusCableNetwork.Link(b, c));
        XBusCableNetwork.End end(BlockPos pos, Peer peer, String pin) {
            peer.history = history;
            peers.put(pos, peer);
            return new XBusCableNetwork.End(new XBusCableTarget(pos, "primary", "io", pin, peer.device, peer.computer()), EnumSet.allOf(Direction.class));
        }
        List<Object> snapshots() { return peers.values().stream().map(p -> (Object)p.host.write()).toList(); }
        XBusCableRoute.Result resolve(XBusCableNetwork.End source, XBusCableNetwork.End target) {
            var origin = source.target().position();
            XBusCableRoute.World world = new XBusCableRoute.World() {
                public boolean loaded(BlockPos pos) { return !unloaded.contains(pos); }
                public CableDiscovery.Cell cell(BlockPos pos) {
                    assertTrue(loaded(pos));
                    return new CableDiscovery.Cell(cables.contains(pos) ? CableDiscovery.Kind.CABLE
                        : peers.containsKey(pos) ? CableDiscovery.Kind.ENDPOINT : CableDiscovery.Kind.EMPTY, null);
                }
                public boolean acceptsCable(BlockPos pos, Direction side) { return !pos.equals(origin) || source.faces().contains(side); }
                public CommunicationEndpoint endpoint(BlockPos pos) { assertTrue(loaded(pos)); return peers.get(pos); }
            };
            return XBusCableRoute.resolve(world, origin, SCAN, target.target());
        }
        XBusNetwork.Pin pin(XBusCableNetwork.End end) { return new XBusNetwork.Pin(end.target().computer(), end.target().pin()); }
    }
}
