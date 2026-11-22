package com.nobodiiiii.createbiotech.content.biocomputing.cluster.menu;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ClusterControlSessionTest {    @Test void logObservationRetainsNonceAndReplayGuardsWithoutRequiringAConfigurationPayloadOrCurrentRevision() {
        var nonce = UUID.randomUUID(); var session = new ClusterControlSession(nonce);
        var action = ClusterControlSession.Action.READ_LOG;
        assertFalse(action.requiresValue()); assertFalse(action.wiring());
        assertTrue(session.apply(UUID.randomUUID(), 1, 0, action, () -> 7L, () -> { throw new AssertionError("wrong nonce"); }).isEmpty());
        assertEquals("ok", session.apply(nonce, 1, 0, action, () -> 7L, () -> "ok").orElseThrow().code());
        assertTrue(session.apply(nonce, 1, 0, action, () -> 7L, () -> { throw new AssertionError("replayed"); }).isEmpty());
        assertDoesNotThrow(() -> new ClusterControlRequest(1, nonce, 2, 0, action, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new ClusterControlRequest(1, nonce, 2, 0, action, new byte[] {1}));
    }
    @Test void openingComputerUsesTheSameNonceIntentAndRevisionFence() {
        var nonce = UUID.randomUUID(); var session = new ClusterControlSession(nonce); var opens = new AtomicInteger();
        java.util.function.Supplier<String> open = () -> { opens.incrementAndGet(); return "opened"; };
        assertTrue(session.apply(UUID.randomUUID(), 1, 4, ClusterControlSession.Action.OPEN_COMPUTER, () -> 4, open).isEmpty());
        assertEquals("stale", session.apply(nonce, 1, 3, ClusterControlSession.Action.OPEN_COMPUTER, () -> 4, open).orElseThrow().code());
        assertEquals(0, opens.get());
        assertEquals("opened", session.apply(nonce, 2, 4, ClusterControlSession.Action.OPEN_COMPUTER, () -> 4, open).orElseThrow().code());
        assertTrue(session.apply(nonce, 2, 4, ClusterControlSession.Action.OPEN_COMPUTER, () -> 4, open).isEmpty());
        assertEquals(1, opens.get());
    }
    @Test void wrongNonceAndRepeatedOrOlderIntentsCannotApplyConfigurationTwice() {
        UUID nonce = UUID.randomUUID();
        var session = new ClusterControlSession(nonce);
        var applies = new AtomicInteger();
        var revision = new AtomicLong(4);
        assertTrue(session.apply(UUID.randomUUID(), 1, 4, ClusterControlSession.Action.CONFIGURE,
            () -> 4, () -> { applies.incrementAndGet(); return "configured"; }).isEmpty());
        var first = session.apply(nonce, 2, 4, ClusterControlSession.Action.CONFIGURE,
            revision::get, () -> { applies.incrementAndGet(); revision.incrementAndGet(); return "configured"; }).orElseThrow();
        assertEquals("configured", first.code());
        assertEquals(5, first.revision());
        assertTrue(session.apply(nonce, 2, 5, ClusterControlSession.Action.CONFIGURE,
            () -> 5, () -> { applies.incrementAndGet(); return "configured"; }).isEmpty());
        assertTrue(session.apply(nonce, 1, 5, ClusterControlSession.Action.CONFIGURE,
            () -> 5, () -> { applies.incrementAndGet(); return "configured"; }).isEmpty());
        assertEquals(1, applies.get());
    }

    @Test void configurationUsesRevisionFenceButRefreshObservesCurrentRevision() {
        UUID nonce = UUID.randomUUID();
        var session = new ClusterControlSession(nonce);
        var applies = new AtomicInteger();
        var stale = session.apply(nonce, 1, 3, ClusterControlSession.Action.CONFIGURE,
            () -> 4, () -> { applies.incrementAndGet(); return "configured"; }).orElseThrow();
        assertEquals(new ClusterControlSession.Result("stale", 4), stale);
        var refresh = session.apply(nonce, 2, 0, ClusterControlSession.Action.REFRESH,
            () -> 7, () -> "ready").orElseThrow();
        assertEquals(new ClusterControlSession.Result("ready", 7), refresh);
        assertEquals(0, applies.get());
    }
}
