package com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionRoutingTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var ready = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        ready.setAccessible(true); ready.setBoolean(null, true);
    }

    @Test void scopedSelectionUsesStableIdentityAndDurableRoundRobinWithoutMutatingItsFeed() {
        var table = new ProductionRouteTable(); var scope = scope(1);
        var first = source(scope, 10, 7); var second = source(scope, 20, 7);
        var feed = new ArrayList<>(List.of(second, first));
        table.replaceProviderKind(scope, RouteCandidate.ProviderKind.PATTERN, feed);
        var frozen = table.snapshot(scope); feed.clear();
        assertEquals(List.of(second, first), frozen);
        assertThrows(UnsupportedOperationException.class, frozen::clear);
        assertEquals(first.candidate(), table.select(scope).winner().orElseThrow());
        var restored = new ProductionRouteTable(table.cursorState());
        restored.replaceProviderKind(scope, RouteCandidate.ProviderKind.PATTERN, List.of(first, second));
        assertEquals(second.candidate(), restored.select(scope).winner().orElseThrow());
        assertTrue(restored.snapshot(scope(2)).isEmpty(), "an unrelated attempt receives no source facts");
        assertEquals(List.of(second, first), table.snapshot(scope));
    }

    static RouteSelectionScope scope(long id) {
        return new RouteSelectionScope(id(1), id(id), new StackKey(Items.BOOK.getDefaultInstance()), 3, id, id(id + 100));
    }
    static ProductionRouteTable.ProviderSource source(RouteSelectionScope scope, long identity, int priority) {
        return new ProductionRouteTable.ProviderSource(scope, id(10), id(identity), "provider/" + identity,
            RouteCandidate.ProviderKind.PATTERN, priority, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
    }
    static UUID id(long value) { return new UUID(0, value); }

    @Test void replacementCapacityFailureLeavesEveryPriorScopeAndCursorUntouched() {
        var table = new ProductionRouteTable(); var active = scope(1); var other = scope(2);
        var original = source(active, 10, 7); table.publish(original);
        for (int i = 0; i < ProductionRouteTable.MAX_SOURCES - 1; i++) table.publish(source(other, i, 3));
        assertEquals(original.candidate(), table.select(active).winner().orElseThrow());
        var before = table.snapshot(); var cursor = table.cursorState();
        assertThrows(IllegalStateException.class, () -> table.replaceProviderKind(active, RouteCandidate.ProviderKind.PATTERN,
            List.of(source(active, 20, 7), source(active, 30, 7))));
        assertEquals(before, table.snapshot(), "failed replacement must not delete the old feed or install a prefix");
        assertEquals(cursor, table.cursorState());
        assertEquals(List.of(original), table.snapshot(active));
        assertEquals(ProductionRouteTable.MAX_SOURCES - 1, table.snapshot(other).size());
    }

    @Test void exhaustedCursorCapacityRejectsBeforeSelectionAndExistingPrioritiesStillWork() {
        var entries = new LinkedHashMap<Integer, DefaultRoutePolicy.RouteIdentity>();
        for (int priority = 0; priority < 256; priority++)
            entries.put(priority, new DefaultRoutePolicy.RouteIdentity(id(10), id(priority), "old/" + priority));
        var initial = new DefaultRoutePolicy.CursorState(entries); var policy = new DefaultRoutePolicy(initial);
        assertThrows(IllegalStateException.class, () -> policy.select(List.of(source(scope(1), 300, 256).candidate())));
        assertEquals(initial, policy.cursorState(), "rejection must leave a serializable cursor snapshot");
        var existingPriority = source(scope(1), 400, 7).candidate();
        assertEquals(existingPriority, policy.select(List.of(existingPriority)).winner().orElseThrow());
        assertEquals(256, policy.cursorState().lastSelectedByPriority().size());
        assertEquals(existingPriority.patternId(), policy.cursorState().lastSelectedByPriority().get(7).patternId());
    }

    @Test void fullTableReplacementCountsFinalIdentitiesAndRejectsMixedFeedsWithoutDamage() {
        var table = new ProductionRouteTable(); var active = scope(1); var other = scope(2);
        table.publish(source(active, 10, 7));
        for (int i = 0; i < ProductionRouteTable.MAX_SOURCES - 1; i++) table.publish(source(other, i, 3));
        var otherBefore = table.snapshot(other);
        var first = source(active, 20, 8); var last = source(active, 20, 9);
        table.replaceProviderKind(active, RouteCandidate.ProviderKind.PATTERN, List.of(first, last));
        assertEquals(List.of(last), table.snapshot(active), "same identity keeps the last value without consuming an extra slot");
        assertEquals(otherBefore, table.snapshot(other)); assertEquals(ProductionRouteTable.MAX_SOURCES, table.snapshot().size());
        var before = table.snapshot();
        assertThrows(IllegalArgumentException.class, () -> table.replaceProviderKind(active, RouteCandidate.ProviderKind.PATTERN,
            List.of(source(other, 40, 9))));
        assertThrows(IllegalArgumentException.class, () -> table.replaceProviderKind(active, RouteCandidate.ProviderKind.AGENT, List.of(last)));
        assertThrows(IllegalStateException.class, () -> table.publish(source(scope(3), 50, 9)));
        assertEquals(before, table.snapshot());
        table.replaceProviderKind(active, RouteCandidate.ProviderKind.PATTERN, List.of());
        assertTrue(table.snapshot(active).isEmpty()); assertEquals(otherBefore, table.snapshot(other));
        table.publish(source(scope(3), 50, 9));
        assertEquals(ProductionRouteTable.MAX_SOURCES, table.snapshot().size(), "freed capacity can be reused");
    }
}
