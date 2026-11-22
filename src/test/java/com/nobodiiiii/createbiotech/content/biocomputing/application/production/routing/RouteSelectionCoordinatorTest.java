package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRoutingTest.*;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RouteSelectionCoordinatorTest {
    @BeforeAll static void bootstrapRegistry() throws ReflectiveOperationException { bootstrap(); }

    @Test void explicitCollectorsSelectOnceAndTheSequenceFencesTerminalReplays() {
        var coordinator = new RouteSelectionCoordinator(id(1), 0, new DefaultRoutePolicy.CursorState(Map.of()), 2);
        var scope = scope(0); var request = RouteSelectionRequest.patternOnly(scope); var source = source(scope, 10, 7);
        assertTrue(coordinator.begin(request)); assertEquals(1, coordinator.nextAttemptSequence());
        assertTrue(coordinator.begin(request), "identical active admission is idempotent");
        assertEquals(1, coordinator.nextAttemptSequence());
        assertFalse(coordinator.complete(scope, RouteCandidate.ProviderKind.PATTERN));
        assertTrue(coordinator.replaceSources(scope, RouteCandidate.ProviderKind.PATTERN, List.of(source)));
        for (var kind : RouteCandidate.ProviderKind.values()) {
            assertTrue(coordinator.select(scope).isEmpty());
            assertTrue(coordinator.complete(scope, kind));
        }
        assertEquals(source.candidate(), coordinator.select(scope).orElseThrow().winner().orElseThrow());
        assertTrue(coordinator.sourceSnapshot(scope).isEmpty());
        assertTrue(coordinator.select(scope).isEmpty());
        assertFalse(coordinator.begin(request), "retired scope cannot reopen using its old sequence");
        assertFalse(coordinator.replaceSources(scope, RouteCandidate.ProviderKind.PATTERN, List.of(source)));
        assertFalse(coordinator.complete(scope, RouteCandidate.ProviderKind.PATTERN));
        assertEquals(source.providerKey(), coordinator.cursorState().lastSelectedByPriority().get(7).providerKey());
    }

    @Test void supersedeIsExactAndCompletedFeedsAreFrozenWithoutAffectingAnotherQuery() {
        var coordinator = fresh(2); var old = scope(0); var other = scope(1);
        var oldSource = source(old, 10, 7); var otherSource = source(other, 20, 7);
        assertTrue(coordinator.begin(RouteSelectionRequest.patternOnly(old)));
        assertFalse(coordinator.begin(RouteSelectionRequest.allRequired(old)), "active collector participation cannot change implicitly");
        assertTrue(coordinator.replaceSources(old, RouteCandidate.ProviderKind.PATTERN, List.of(oldSource)));
        assertTrue(coordinator.complete(old, RouteCandidate.ProviderKind.PATTERN));
        assertFalse(coordinator.replaceSources(old, RouteCandidate.ProviderKind.PATTERN, List.of(source(old, 30, 7))));
        assertTrue(coordinator.replaceSources(old, RouteCandidate.ProviderKind.PATTERN, List.of(oldSource)), "exact completed feed replay is idempotent");
        assertTrue(coordinator.begin(RouteSelectionRequest.patternOnly(other)));
        assertTrue(coordinator.replaceSources(other, RouteCandidate.ProviderKind.PATTERN, List.of(otherSource)));
        var dirt = new StackKey(Items.DIRT.getDefaultInstance());
        var changedWithoutGeneration = attempt(old, 2, old.searchGeneration(), dirt);
        assertFalse(coordinator.supersede(old, RouteSelectionRequest.patternOnly(changedWithoutGeneration)));
        assertEquals(2, coordinator.nextAttemptSequence()); assertEquals(List.of(oldSource), coordinator.sourceSnapshot(old));
        var next = attempt(old, 2, old.searchGeneration() + 1, dirt);
        assertTrue(coordinator.supersede(old, RouteSelectionRequest.patternOnly(next)));
        assertEquals(3, coordinator.nextAttemptSequence()); assertTrue(coordinator.sourceSnapshot(old).isEmpty());
        assertFalse(coordinator.begin(RouteSelectionRequest.patternOnly(old)));
        assertFalse(coordinator.supersede(old, RouteSelectionRequest.patternOnly(next)));
        assertFalse(coordinator.complete(old, RouteCandidate.ProviderKind.PATTERN));
        assertFalse(coordinator.replaceSources(old, RouteCandidate.ProviderKind.PATTERN, List.of(oldSource)));
        assertTrue(coordinator.select(old).isEmpty());
        for (var invalid : List.of(
                attempt(next, 3, next.searchGeneration() - 1, dirt),
                attempt(next, 3, next.searchGeneration(), old.requestedStackKey()),
                attempt(next, 4, next.searchGeneration() + 1, dirt),
                new RouteSelectionScope(id(9), next.queryId(), dirt, 5, 3, id(999)),
                new RouteSelectionScope(id(1), id(999), dirt, 5, 3, id(999))))
            assertFalse(coordinator.supersede(next, RouteSelectionRequest.patternOnly(invalid)));
        assertEquals(3, coordinator.nextAttemptSequence());
        var winner = source(next, 30, 8); ready(coordinator, next, winner);
        assertEquals(winner.candidate(), coordinator.select(next).orElseThrow().winner().orElseThrow());
        assertEquals(List.of(otherSource), coordinator.sourceSnapshot(other));
    }

    @Test void retiringFreesCapacityWithoutRewindingSequenceAndBothHistoricalLimitsRemain() {
        var coordinator = fresh(2);
        assertTrue(coordinator.begin(RouteSelectionRequest.patternOnly(scope(0))));
        assertTrue(coordinator.begin(RouteSelectionRequest.patternOnly(scope(1))));
        assertFalse(coordinator.begin(RouteSelectionRequest.patternOnly(scope(2))));
        assertEquals(2, coordinator.nextAttemptSequence());
        var firstSource = source(scope(0), 10, 7);
        assertTrue(coordinator.replaceSources(scope(0), RouteCandidate.ProviderKind.PATTERN, List.of(firstSource)));
        coordinator.retireQuery(scope(0).queryId());
        assertTrue(coordinator.sourceSnapshot(scope(0)).isEmpty());
        assertFalse(coordinator.begin(RouteSelectionRequest.patternOnly(scope(0))));
        assertTrue(coordinator.begin(RouteSelectionRequest.patternOnly(scope(2))));
        assertEquals(3, coordinator.nextAttemptSequence());
        var wide = fresh(RouteSelectionCoordinator.MAX_SCOPE_CAPACITY);
        for (int i = 0; i < 256; i++) assertTrue(wide.begin(RouteSelectionRequest.patternOnly(scope(i))));
        assertFalse(wide.begin(RouteSelectionRequest.patternOnly(scope(256))));
        wide.retireQuery(scope(0).queryId()); assertTrue(wide.begin(RouteSelectionRequest.patternOnly(scope(256))));
        var cursor = new DefaultRoutePolicy.CursorState(Map.of());
        assertThrows(IllegalArgumentException.class, () -> new RouteSelectionCoordinator(id(1), -1, cursor, 2));
        assertThrows(IllegalArgumentException.class, () -> fresh(0));
        assertThrows(IllegalArgumentException.class, () -> fresh(RouteSelectionCoordinator.MAX_SCOPE_CAPACITY + 1));
        var exhausted = new RouteSelectionCoordinator(id(1), Long.MAX_VALUE, cursor, 2);
        var last = new RouteSelectionScope(id(1), id(20), scope(0).requestedStackKey(), 3, Long.MAX_VALUE, id(30));
        assertFalse(exhausted.begin(RouteSelectionRequest.patternOnly(last)));
        assertEquals(Long.MAX_VALUE, exhausted.nextAttemptSequence());
    }

    @Test void restoreRetainsProgressButDoesNotShareOrReplayTransientCollection() {
        var original = fresh(2); var scope = scope(0); var source = source(scope, 10, 7);
        assertTrue(original.begin(RouteSelectionRequest.patternOnly(scope)));
        assertTrue(original.replaceSources(scope, RouteCandidate.ProviderKind.PATTERN, List.of(source)));
        var restored = new RouteSelectionCoordinator(id(1), original.nextAttemptSequence(), original.cursorState(), 2);
        assertFalse(restored.begin(RouteSelectionRequest.patternOnly(scope)));
        assertFalse(restored.complete(scope, RouteCandidate.ProviderKind.PATTERN));
        assertTrue(restored.sourceSnapshot(scope).isEmpty());
        assertTrue(restored.begin(RouteSelectionRequest.patternOnly(scope(1))));
        assertEquals(1, original.nextAttemptSequence()); assertEquals(2, restored.nextAttemptSequence());
        assertEquals(List.of(source), original.sourceSnapshot(scope));
        var alienEpoch = new RouteSelectionScope(id(2), id(2), scope.requestedStackKey(), 3, 2, id(102));
        assertFalse(restored.begin(RouteSelectionRequest.patternOnly(alienEpoch)));
        assertEquals(2, restored.nextAttemptSequence());
    }

    @Test void cursorFailureRetainsTheReadyBarrierUntilAnExplicitValidSupersede() {
        var entries = new LinkedHashMap<Integer, DefaultRoutePolicy.RouteIdentity>();
        for (int i = 0; i < DefaultRoutePolicy.CursorState.MAX_PRIORITIES; i++)
            entries.put(i, new DefaultRoutePolicy.RouteIdentity(id(10), id(i), "previous/" + i));
        var full = new DefaultRoutePolicy.CursorState(entries);
        var coordinator = new RouteSelectionCoordinator(id(1), 0, full, 2);
        var scope = scope(0); var rejected = source(scope, 10, 1000);
        assertTrue(coordinator.begin(RouteSelectionRequest.patternOnly(scope))); ready(coordinator, scope, rejected);
        assertThrows(IllegalStateException.class, () -> coordinator.select(scope));
        assertEquals(full, coordinator.cursorState()); assertEquals(List.of(rejected), coordinator.sourceSnapshot(scope));
        assertTrue(coordinator.complete(scope, RouteCandidate.ProviderKind.PATTERN));
        var next = attempt(scope, 1, scope.searchGeneration(), scope.requestedStackKey());
        assertTrue(coordinator.supersede(scope, RouteSelectionRequest.patternOnly(next)));
        var valid = source(next, 20, 7); ready(coordinator, next, valid);
        assertEquals(valid.candidate(), coordinator.select(next).orElseThrow().winner().orElseThrow());
        assertEquals(2, coordinator.nextAttemptSequence()); assertEquals(256, coordinator.cursorState().lastSelectedByPriority().size());
    }

    private static RouteSelectionCoordinator fresh(int capacity) {
        return new RouteSelectionCoordinator(id(1), 0, new DefaultRoutePolicy.CursorState(Map.of()), capacity);
    }
    private static RouteSelectionScope attempt(RouteSelectionScope prior, long sequence, long generation, StackKey output) {
        return new RouteSelectionScope(prior.epochId(), prior.queryId(), output, generation, sequence, id(100 + sequence));
    }
    private static void ready(RouteSelectionCoordinator coordinator, RouteSelectionScope scope, ProductionRouteTable.ProviderSource source) {
        assertTrue(coordinator.replaceSources(scope, RouteCandidate.ProviderKind.PATTERN, List.of(source)));
        for (var kind : RouteCandidate.ProviderKind.values()) assertTrue(coordinator.complete(scope, kind));
    }
}
