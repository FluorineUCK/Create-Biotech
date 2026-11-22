package com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RpcStateTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static RequestCorrelation key(long event, int ordinal) { return new RequestCorrelation(OWNER, new UUID(0, event), ordinal); }
    private static RpcState.Pending pending(long event) { return new RpcState.Pending(key(event, 0), "on_result"); }

    @Test void stagedRequestsBecomeVisibleOnlyAfterCommitAndLatestGenerationWins() {
        var first = pending(2); var second = pending(3);
        var empty = RpcState.empty();
        var staging = empty.begin(Optional.empty(), Optional.empty(), Map.of("search", first));
        assertTrue(staging.matching(first.correlation()).isEmpty());
        var old = staging.commit();
        assertEquals(Optional.of(new ReplyContext("search", first)), old.matching(first.correlation()));
        var replacing = old.begin(Optional.empty(), Optional.empty(), Map.of("search", second));
        assertTrue(replacing.matching(first.correlation()).isPresent());
        assertTrue(replacing.matching(second.correlation()).isEmpty());
        var latest = replacing.commit();
        assertTrue(latest.matching(first.correlation()).isEmpty());
        assertEquals(Optional.of(new ReplyContext("search", second)), latest.matching(second.correlation()));
        assertEquals(Map.of("search", first), old.pending());
        assertEquals(Map.of(), empty.pending()); assertEquals(Map.of(), latest.staged());
    }

    @Test void failedEventDiscardsOnlyStagedCorrelations() {
        var old = RpcState.empty().begin(Optional.empty(), Optional.empty(), Map.of("search", pending(2))).commit();
        var staged = old.begin(Optional.empty(), Optional.empty(), Map.of("search", pending(3), "details", pending(4)));
        var failed = staged.discardStaged();
        assertEquals(old.pending(), failed.pending()); assertTrue(failed.staged().isEmpty());
        assertTrue(failed.matching(key(3, 0)).isEmpty()); assertEquals(2, staged.staged().size());
    }

    @Test void replyConsumesOnlyMatchingWaitAndCanAtomicallyStartNewRequestInSameSlot() {
        var first = pending(2); var second = pending(3);
        var waiting = RpcState.empty().begin(Optional.empty(), Optional.empty(), Map.of("search", first)).commit();
        var reply = waiting.matching(first.correlation()).orElseThrow();
        var handling = waiting.begin(Optional.empty(), Optional.of(reply), Map.of());
        assertTrue(handling.matching(first.correlation()).isPresent());
        var completed = handling.commit();
        assertTrue(completed.pending().isEmpty()); assertEquals(Optional.of(reply), completed.reply());
        assertEquals(completed, completed.commit());
        var chained = waiting.begin(Optional.empty(), Optional.of(reply), Map.of("search", second)).commit();
        assertEquals(Map.of("search", second), chained.pending());
        assertThrows(IllegalArgumentException.class, () -> chained.begin(Optional.empty(), Optional.of(reply), Map.of()));
    }

    @Test void requestContextSurvivesCommitForReplyOutboxWithoutRetainingRequestPayload() {
        var context = new RequestContext(key(2, 0), "return literal endpoint");
        var running = RpcState.empty().begin(Optional.of(context), Optional.empty(), Map.of("nested", pending(3)));
        var committed = running.commit();
        assertEquals(Optional.of(context), committed.request()); assertTrue(committed.reply().isEmpty());
        assertEquals(Map.of("nested", pending(3)), committed.pending());
        var next = committed.begin(Optional.empty(), Optional.empty(), Map.of());
        assertTrue(next.request().isEmpty()); assertEquals(committed.pending(), next.pending());
    }

    @Test void namedMapsAreDefensiveAndImpossibleContextsAreRejected() {
        var map = new LinkedHashMap<String, RpcState.Pending>(); map.put("search", pending(2));
        var saved = new RpcState(map, Map.of(), Optional.empty(), Optional.empty()); map.clear();
        assertEquals(1, saved.pending().size());
        assertThrows(UnsupportedOperationException.class, () -> saved.pending().clear());
        assertThrows(IllegalArgumentException.class, () -> new RpcState(Map.of(" ", pending(2)), Map.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RpcState(Map.of("a", pending(2), "b", pending(2)), Map.of(), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RpcState(Map.of(), Map.of("a", pending(2), "b", pending(2)), Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RpcState(Map.of(), Map.of(),
            Optional.of(new RequestContext(key(2, 0), "source")), Optional.of(new ReplyContext("search", pending(3)))));
        assertThrows(IllegalArgumentException.class, () -> new RequestCorrelation(OWNER, new UUID(0, 2), -1));
        assertThrows(IllegalArgumentException.class, () -> new RequestContext(key(2, 0), ""));
        assertThrows(IllegalArgumentException.class, () -> new RpcState.Pending(key(2, 0), ""));
    }

    @Test void projectedCommitRejectsCrossMapCollisionBeforeExecutionButAllowsSafeReplacement() {
        var first = pending(2); var second = pending(3);
        var waiting = new RpcState(Map.of("a", first), Map.of(), Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> waiting.begin(Optional.empty(), Optional.empty(), Map.of("b", first)));
        assertThrows(IllegalArgumentException.class, () -> new RpcState(Map.of("a", first), Map.of("b", first), Optional.empty(), Optional.empty()));
        // Replacing the original slot, or consuming its matching reply, removes the collision.
        var replacement = waiting.begin(Optional.empty(), Optional.empty(), Map.of("a", second, "b", first));
        assertEquals(Map.of("a", second, "b", first), replacement.commit().pending());
        var consuming = waiting.begin(Optional.empty(), Optional.of(new ReplyContext("a", first)), Map.of("b", first));
        assertEquals(Map.of("b", first), consuming.commit().pending());
    }

    @Test void emptyLegacyStateCostsNothingButPendingMetadataHasLiteralStorageCost() {
        assertEquals(0, RpcState.empty().storageUnits());
        var state = new RpcState(Map.of("s", new RpcState.Pending(key(2, 0), "r")), Map.of(), Optional.empty(), Optional.empty());
        // v2 adds the explicit null continuation field (14 units) to v1's 175.
        assertEquals(189, state.storageUnits());
        assertEquals(189, state.value().storageUnits());
    }
}
