package com.nobodiiiii.createbiotech.content.biocomputing.network;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveLimits;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class XBusWiringSessionTest {
    @Test void enableRechecksBothFixedEndpointsAndFailureDoesNotChangeTheDeclarationOrEitherComputer() {
        for (String fault : List.of("replacement", "closed", "unloaded", "disconnected", "denied", "provider_failure")) {
            var f = new Fixture(); f.session.read(1); f.session.add(2, 1, "wire", "x0", 0);
            switch (fault) {
                case "replacement" -> f.world.providers.put(f.right.position(), new XBusCableRouteTest.Peer(false));
                case "closed" -> f.reader.open = false;
                case "unloaded" -> f.world.unloaded = f.right.position();
                case "disconnected" -> f.world.connected = false;
                case "denied" -> f.world.denied = new BlockPos(1, 0, 0);
                case "provider_failure" -> f.reader.throwing = true;
            }
            var before = f.store.view(f.left.device()); var a = f.writer.host.write(); var b = f.reader.host.write();
            var failure = assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.setEnabled(3, 2, "wire", true), fault);
            String expected = switch (fault) {
                case "replacement" -> "identity_changed";
                case "unloaded" -> "target_unloaded";
                case "disconnected" -> "unreachable";
                case "denied" -> "access_denied";
                default -> fault;
            };
            assertEquals(expected, failure.getMessage(), fault);
            assertEquals(before, f.store.view(f.left.device()));
            assertEquals(a, f.writer.host.write()); assertEquals(b, f.reader.host.write());
            assertEquals("stale", assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.setEnabled(4, 2, "wire", true)).getMessage());
        }
    }
    @Test void enablingCannotOutliveMenuRevocationSourceReconfigurationOrStoreReplacementDuringProviderCallbacks() {
        for (String fault : List.of("authorization", "revision", "store", "invalidation")) {
            var f = new Fixture(); f.session.read(1); f.session.add(2, 1, "wire", "x0", 0);
            var original = f.store; var before = original.view(f.left.device());
            var a = f.writer.host.write(); var b = f.reader.host.write();
            f.reader.onLimits = () -> {
                switch (fault) {
                    case "authorization" -> f.authorized = false;
                    case "revision" -> f.sourceRevision++;
                    case "store" -> f.store = new XBusWiringSavedData();
                    case "invalidation" -> f.session.invalidate();
                }
            };
            assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.setEnabled(3, 2, "wire", true), fault);
            assertEquals(before, original.view(f.left.device()));
            assertEquals(a, f.writer.host.write()); assertEquals(b, f.reader.host.write());
        }
    }
    @Test void togglingHonorsAliasScopeAuthorizationAndExactReplyBudgetForBothDirections() {
        var f = new Fixture();
        var hidden = new XBusCableTarget(f.left.position(), f.left.domain(), "other", f.left.pin(), f.left.device(), f.left.computer());
        f.store.add(f.store.view(f.left.device()), "hidden", hidden, f.right, 8, ignored -> {});
        var scoped = new XBusWiringSession(f, () -> f.store, () -> XBusCableRouteTest.SCAN, () -> f.archive, "io");
        scoped.read(1); var before = f.store.view(f.left.device());
        assertEquals("invalid_selection", assertThrows(PeerDiscovery.DiscoveryException.class, () -> scoped.setEnabled(2, 1, "hidden", true)).getMessage());
        assertEquals(before, f.store.view(f.left.device()));
        f.session.read(1); f.session.add(2, 1, "wire", "x0", 0);
        long token = 3;
        for (boolean enabled : List.of(true, false)) {
            long read = token++, write = token++; f.session.read(read);
            var original = f.store.view(f.left.device());
            var failure = assertThrows(PeerDiscovery.DiscoveryException.class,
                () -> f.session.setEnabled(write, read, "wire", enabled, new ArchiveLimits(1, 0, 1)));
            assertEquals("too_large", failure.code()); assertEquals(original, f.store.view(f.left.device()));
            long next = token++, denied = token++; f.session.read(next); f.authorized = false;
            assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.setEnabled(denied, next, "wire", enabled));
            assertEquals(original, f.store.view(f.left.device())); f.authorized = true;
        }
    }
    @Test void explicitEnablePublishesItsChoiceWithoutConsumingDataAndDisableWorksWithABrokenPeer() {
        var f = new Fixture(); f.session.read(1); f.session.add(2, 1, "wire", "x0", 0);
        var a = f.writer.host.write(); var b = f.reader.host.write();
        var reply = f.session.setEnabled(3, 2, "wire", true);
        assertTrue(f.store.view(f.left.device()).entries().getFirst().enabled());
        var entry = (DataValue.RecordValue)((DataValue.ListValue)((DataValue.RecordValue)reply.field("wiring")).field("entries")).values().getFirst();
        assertEquals(new DataValue.BooleanValue(true), entry.field("enabled"));
        assertEquals(a, f.writer.host.write()); assertEquals(b, f.reader.host.write());
        assertEquals(0, f.writer.inboxCalls + f.reader.inboxCalls);
        f.world.connected = false;
        f.session.setEnabled(4, 3, "wire", false);
        assertFalse(f.store.view(f.left.device()).entries().getFirst().enabled());
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.setEnabled(5, 3, "wire", true));
        assertEquals(a, f.writer.host.write()); assertEquals(b, f.reader.host.write());
    }
    @Test void aSourceRevisionChangeDuringDiscoveryCannotPublishAMixedConfigurationView() {
        var f = new Fixture(); f.onDiscovery = () -> f.sourceRevision++;
        assertEquals("stale", assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.read(1)).getMessage());
        assertEquals("stale", assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(2, 1, "wire", "x0", 0)).getMessage());
        f.onDiscovery = () -> {}; f.session.read(3); f.session.add(4, 3, "fresh", "x0", 0);
        assertEquals("fresh", f.store.view(f.left.device()).entries().getFirst().name());
    }
    @Test void scopedViewsDoNotBypassWholeDeviceCapacityOrReserveNamesPerAlias() {
        var f = new Fixture();
        var hidden = new XBusCableTarget(f.left.position(), f.left.domain(), "other", f.left.pin(), f.left.device(), f.left.computer());
        f.store.add(f.store.view(f.left.device()), "reserved", hidden, f.right, 8, ignored -> {});
        var limited = new XBusWiringSession(f, () -> f.store, () -> new CommunicationNetworkLimits(64, 2), () -> f.archive, "io");
        var view = com.nobodiiiii.createbiotech.content.biocomputing.editor.WiringStatusView.decode(limited.read(1));
        assertTrue(view.entries().isEmpty()); assertEquals(1, view.revision());
        var before = f.store.view(f.left.device());
        assertEquals("capacity", assertThrows(IllegalArgumentException.class, () -> limited.add(2, 1, "new", "x0", 0)).getMessage());
        assertEquals(before, f.store.view(f.left.device()));
        var normal = new XBusWiringSession(f, () -> f.store, () -> XBusCableRouteTest.SCAN, () -> f.archive, "io");
        normal.read(1);
        assertEquals("duplicate_name", assertThrows(IllegalArgumentException.class, () -> normal.add(2, 1, "reserved", "x0", 0)).getMessage());
        assertEquals(before, f.store.view(f.left.device()));
    }

    @Test void scopedRemovalIsOneShotAndCannotReachAnotherAliasesHiddenEntry() {
        var f = new Fixture();
        var hidden = new XBusCableTarget(f.left.position(), f.left.domain(), "other", f.left.pin(), f.left.device(), f.left.computer());
        f.store.add(f.store.view(f.left.device()), "hidden", hidden, f.right, 8, ignored -> {});
        var scoped = new XBusWiringSession(f, () -> f.store, () -> XBusCableRouteTest.SCAN, () -> f.archive, "io");
        scoped.read(1);
        assertEquals("invalid_selection", assertThrows(PeerDiscovery.DiscoveryException.class, () -> scoped.remove(2, 1, "hidden")).getMessage());
        assertEquals("stale", assertThrows(PeerDiscovery.DiscoveryException.class, () -> scoped.add(3, 1, "wire", "x0", 0)).getMessage());
        assertEquals(1, f.store.view(f.left.device()).revision());
    }

    @Test void scopedPublicationRejectsAnOutOfScopeLocalProviderBeforeOfferingIt() {
        var f = new Fixture();
        var scoped = new XBusWiringSession(f, () -> f.store, () -> XBusCableRouteTest.SCAN, () -> f.archive, "not_io");
        assertEquals("invalid_selection", assertThrows(PeerDiscovery.DiscoveryException.class, () -> scoped.read(1)).getMessage());
        assertTrue(f.store.view(f.left.device()).entries().isEmpty());
    }

    @Test void explicitChoiceSavesTheDiscoveredIdentitiesWithoutConsumingAPendingRecord() {
        var f = new Fixture(); var beforeA = f.writer.host.flowSnapshot(); var beforeB = f.reader.host.flowSnapshot();
        var view = f.session.read(1);
        assertEquals(0, f.store.view(f.left.device()).revision());
        var result = f.session.add(2, 1, "wire", "x0", 0);
        assertEquals(1, f.store.view(f.left.device()).revision());
        assertEquals(new XBusWiringSavedData.Entry("wire", f.left, f.right), f.store.view(f.left.device()).entries().getFirst());
        assertEquals(beforeA, f.writer.host.flowSnapshot()); assertEquals(beforeB, f.reader.host.flowSnapshot());
        assertEquals(0, f.writer.inboxCalls + f.reader.inboxCalls);
        assertEquals(new DataValue.TextValue("declarations_only"), ((DataValue.RecordValue)result.field("wiring")).field("mode"));
        assertFalse(view.toString().contains(f.right.device().toString()), "No identity handles in the player selection protocol");
    }

    @Test void savingInactiveMetadataDoesNotAttemptRecoveryOrRetireAnOlderParticipantsHistory() {
        var f = new Fixture(); var oldReader = f.reader.host.write();
        var writerAccess = f.writer.xbusAccess("io", f.writer.xbusPort("io", "x0").orElseThrow()).orElseThrow();
        var readerAccess = f.reader.xbusAccess("io", f.reader.xbusPort("io", "x1").orElseThrow()).orElseThrow();
        assertEquals(com.nobodiiiii.createbiotech.content.biocomputing.computer.flow.XBusExchange.Status.EXCHANGED, writerAccess.exchange(readerAccess));
        f.reader.host.read(oldReader); f.reader.host.tick(XBusCableRouteTest.HOST, true);
        // A new inactive declaration chooses B.x0, whereas the old exchange must recover through B.x1.
        f.right = new XBusCableTarget(f.right.position(), f.reader.domain(), "io", "x0", f.reader.device, f.reader.computer());
        var before = f.writer.host.write(); var oldHistory = f.reader.host.write();
        f.session.read(1); f.session.add(2, 1, "inactive", "x0", 0);
        assertEquals(1, f.store.view(f.left.device()).entries().size());
        assertEquals(before, f.writer.host.write()); assertEquals(oldHistory, f.reader.host.write());
        var a = f.resolve(f.right, f.left).pin().orElseThrow().access(); var c = f.resolve(f.left, f.right).pin().orElseThrow().access();
        assertTrue(XBusAccess.network(List.of(a, c)).inspect().rejection().isPresent(), "The unchanged execution API still requires the original recovery pin");
    }

    @Test void replyBudgetIsCapturedBeforeProviderCallbacksAndOversizedReplyNeverCommits() {
        var f = new Fixture(); f.session.read(1);
        f.archive = new ArchiveLimits(1, 0, 1);
        f.reader.onAccess = () -> f.archive = XBusCableRouteTest.HOST.archive();
        var failure = assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(2, 1, "wire", "x0", 0));
        assertEquals("too_large", failure.code());
        assertEquals(0, f.store.view(f.left.device()).revision());
        assertTrue(f.store.view(f.left.device()).entries().isEmpty());
    }

    @Test void peerChangesNeverRebindAnAlreadyOfferedChoiceOrConsumeEitherFlow() {
        for (String fault : List.of("replacement", "closed", "unloaded", "disconnected", "denied", "provider_failure")) {
            var f = new Fixture(); f.session.read(1);
            switch (fault) {
                case "replacement" -> f.world.providers.put(f.right.position(), new XBusCableRouteTest.Peer(false));
                case "closed" -> f.reader.open = false;
                case "unloaded" -> f.world.unloaded = f.right.position();
                case "disconnected" -> f.world.connected = false;
                case "denied" -> f.world.denied = new BlockPos(1, 0, 0);
                case "provider_failure" -> f.reader.throwing = true;
            }
            var a = f.writer.host.write(); var b = f.reader.host.write();
            var failure = assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(2, 1, "wire", "x0", 0), fault);
            assertFalse(failure.getMessage().contains("private"));
            assertTrue(f.store.view(f.left.device()).entries().isEmpty(), fault);
            assertEquals(a, f.writer.host.write(), fault); assertEquals(b, f.reader.host.write(), fault);
            assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(3, 1, "retry", "x0", 0), "Failed choice is consumed");
        }
    }

    @Test void oneShotViewsRejectReplayInvalidSelectionsAndConcurrentNamespaceEdits() {
        var f = new Fixture(); f.session.read(1);
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(2, 1, "wire", "not_a_pin", 0));
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(3, 1, "wire", "x0", 0));
        f.session.read(4);
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(5, 4, "wire", "x0", Integer.MAX_VALUE));
        f.session.read(6);
        f.store.add(f.store.view(f.left.device()), "other_player", f.left, f.right, 8, ignored -> {});
        assertThrows(IllegalArgumentException.class, () -> f.session.add(7, 6, "wire", "x0", 0));
        assertEquals(List.of("other_player"), f.store.view(f.left.device()).entries().stream().map(XBusWiringSavedData.Entry::name).toList());
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.read(7));
        f.session.read(8); f.session.invalidate();
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.remove(9, 8, "other_player"));
    }

    @Test void deleteRequiresCurrentAuthorizationAndPreflightsTheExactOuterReplyBudgetButNotPeerAvailability() {
        var f = new Fixture(); f.session.read(1); f.session.add(2, 1, "wire", "x0", 0);
        f.world.connected = false; f.session.read(3);
        var before = f.store.view(f.left.device());
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.remove(4, 3, "wire", new ArchiveLimits(1, 0, 1)));
        assertEquals(before, f.store.view(f.left.device()));
        f.session.read(5); f.authorized = false;
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.remove(6, 5, "wire"));
        assertEquals(before, f.store.view(f.left.device()));
        f.authorized = true; f.session.read(7); f.session.remove(8, 7, "wire");
        assertEquals(2, f.store.view(f.left.device()).revision());
        assertTrue(f.store.view(f.left.device()).entries().isEmpty());
    }

    @Test void lateProviderRevocationsAndReentryCannotPublishAPreviouslyCheckedBinding() {
        for (String fault : List.of("closed", "identity", "disconnect", "invalidation")) {
            var f = new Fixture(); f.session.read(1);
            f.reader.onLimits = () -> {
                switch (fault) {
                    case "closed" -> f.writer.open = false;
                    case "identity" -> f.writer.device = UUID.randomUUID();
                    case "disconnect" -> f.world.connected = false;
                    case "invalidation" -> f.session.invalidate();
                }
            };
            var a = f.writer.host.write(); var b = f.reader.host.write();
            assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(2, 1, "wire", "x0", 0), fault);
            assertTrue(f.store.view(f.left.device()).entries().isEmpty(), fault);
            assertEquals(a, f.writer.host.write(), fault); assertEquals(b, f.reader.host.write(), fault);
        }
    }

    @Test void dimensionStoreReplacementInTheLastProviderCallbackMustNotPublishIntoDetachedData() {
        var f = new Fixture(); f.session.read(1); var original = f.store;
        f.reader.onLimits = () -> f.store = new XBusWiringSavedData();
        assertThrows(PeerDiscovery.DiscoveryException.class, () -> f.session.add(2, 1, "wire", "x0", 0));
        assertTrue(original.view(f.left.device()).entries().isEmpty());
        assertTrue(f.store.view(f.left.device()).entries().isEmpty());
    }

    static final class Fixture implements XBusWiringSession.Access {
        final XBusCableRouteTest.Peer writer = new XBusCableRouteTest.Peer(true), reader = new XBusCableRouteTest.Peer(false);
        final XBusCableRouteTest.World world = new XBusCableRouteTest.World();
        XBusWiringSavedData store = new XBusWiringSavedData();
        final XBusCableTarget left = new XBusCableTarget(BlockPos.ZERO, writer.domain(), "io", "x0", writer.device, writer.computer());
        XBusCableTarget right = new XBusCableTarget(XBusCableRouteTest.DESTINATION, reader.domain(), "io", "x1", reader.device, reader.computer());
        ArchiveLimits archive = XBusCableRouteTest.HOST.archive();
        boolean authorized = true;
        long sourceRevision;
        Runnable onDiscovery = () -> {};
        final XBusWiringSession session = new XBusWiringSession(this, () -> store, () -> XBusCableRouteTest.SCAN, () -> archive);
        Fixture() { world.providers.put(left.position(), writer); world.providers.put(right.position(), reader); }
        public void authorize() { if (!authorized) throw new PeerDiscovery.DiscoveryException("unavailable", "menu_unavailable"); }
        public UUID owner() { return writer.device; }
        public long sourceRevision() { return sourceRevision; }
        public XBusWiringSession.Discovery discover() { onDiscovery.run(); return new XBusWiringSession.Discovery(List.of(left), List.of(right), "ready"); }
        public XBusCableRoute.Result resolve(XBusCableTarget origin, XBusCableTarget target) {
            return XBusCableRoute.resolve(world, origin.position(), XBusCableRouteTest.SCAN, target);
        }
    }
}
