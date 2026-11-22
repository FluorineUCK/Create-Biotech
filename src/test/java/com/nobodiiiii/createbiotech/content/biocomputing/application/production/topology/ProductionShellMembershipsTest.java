package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionShellMemberships.Refresh.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionShellMembershipsTest {
    @Test void lateOldInstanceRemovalDoesNotRemoveAnEqualIdentityReplacement() {
        var index = new ProductionShellMemberships<Object, Host>(); var scope = new Object();
        var old = new Host(new UUID(0, 1)); var replacement = new Host(old.id());
        var oldRegistration = index.registration(old); var newRegistration = index.registration(replacement);
        var shell = address(0);
        oldRegistration.refresh(scope, 1, () -> List.of(shell));
        newRegistration.refresh(scope, 1, () -> List.of(shell));
        var both = index.members(scope, shell);
        assertEquals(old, replacement); assertNotSame(old, replacement);
        assertEquals(2, both.size()); assertSame(old, both.get(0)); assertSame(replacement, both.get(1));
        oldRegistration.clear();
        assertFalse(oldRegistration.indexed()); assertTrue(newRegistration.indexed());
        assertEquals(1, index.members(scope, shell).size()); assertSame(replacement, index.members(scope, shell).getFirst());
    }

    @Test void scopeDimensionAndSublevelSeparateTheSameCoordinates() {
        var index = new ProductionShellMemberships<Object, Host>();
        var scope = new Object(); var otherScope = new Object();
        var addresses = List.of(address(0), new ProductionLayout.Address(Level.NETHER, null, BlockPos.ZERO),
            new ProductionLayout.Address(Level.OVERWORLD, new UUID(0, 1), BlockPos.ZERO),
            new ProductionLayout.Address(Level.OVERWORLD, new UUID(0, 2), BlockPos.ZERO));
        var hosts = new ArrayList<Host>();
        var registrations = new ArrayList<ProductionShellMemberships<Object, Host>.Registration>();
        for (int i = 0; i < addresses.size(); i++) {
            var h = new Host(new UUID(0, i)); hosts.add(h);
            var r = index.registration(h); registrations.add(r); var a = addresses.get(i);
            r.refresh(scope, 1, () -> List.of(a));
        }
        var foreignHost = new Host(new UUID(0, 5)); var foreign = index.registration(foreignHost);
        foreign.refresh(otherScope, 1, () -> List.of(addresses.getFirst()));
        for (int i = 0; i < addresses.size(); i++) {
            assertEquals(1, index.members(scope, addresses.get(i)).size());
            assertSame(hosts.get(i), index.members(scope, addresses.get(i)).getFirst());
            if (i > 0) assertTrue(index.members(otherScope, addresses.get(i)).isEmpty());
        }
        assertSame(foreignHost, index.members(otherScope, addresses.getFirst()).getFirst());
        registrations.forEach(ProductionShellMemberships.Registration::clear);
        assertTrue(index.members(scope, address(0)).isEmpty()); assertTrue(foreign.indexed());
    }

    @Test void refreshIsLazyAndPublishesADetachedDeduplicatedAddressSnapshot() {
        var index = new ProductionShellMemberships<Object, Host>(); var scope = new Object();
        var host = new Host(new UUID(0, 1)); var r = index.registration(host); var calls = new AtomicInteger();
        var source = new ArrayList<>(List.of(address(0), address(0), address(1)));
        assertEquals(UPDATED, r.refresh(scope, 1, () -> { calls.incrementAndGet(); return source; }));
        source.clear(); source.add(address(2));
        var retained = index.members(scope, address(0));
        assertEquals(1, retained.size()); assertSame(host, retained.getFirst());
        assertThrows(UnsupportedOperationException.class, retained::clear);
        assertSame(host, index.members(scope, address(1)).getFirst());
        assertTrue(index.members(scope, address(2)).isEmpty());
        assertEquals(UNCHANGED, r.refresh(scope, 1, () -> { calls.incrementAndGet(); return source; }));
        assertEquals(1, calls.get());
        assertEquals(UPDATED, r.refresh(scope, 2, () -> source));
        assertTrue(index.members(scope, address(0)).isEmpty()); assertTrue(index.members(scope, address(1)).isEmpty());
        assertSame(host, index.members(scope, address(2)).getFirst()); assertSame(host, retained.getFirst());
        assertEquals(UPDATED, r.refresh(scope, 3, List::of));
        assertFalse(r.indexed()); assertTrue(index.members(scope, address(2)).isEmpty());
    }

    @Test void aScopeChangeAtTheSameVersionReindexesAndClearDoesNotNeedTheCurrentScope() {
        var index = new ProductionShellMemberships<Object, Host>(); var a = new Object(); var b = new Object();
        var host = new Host(new UUID(0, 1)); var r = index.registration(host);
        r.refresh(a, 1, () -> List.of(address(0)));
        assertEquals(UPDATED, r.refresh(b, 1, () -> List.of(address(1))));
        assertTrue(index.members(a, address(0)).isEmpty()); assertSame(host, index.members(b, address(1)).getFirst());
        r.clear(); r.clear(); assertFalse(r.indexed()); assertTrue(index.members(b, address(1)).isEmpty());
        assertEquals(UPDATED, r.refresh(a, 1, () -> List.of(address(0))));
        assertSame(host, index.members(a, address(0)).getFirst());
    }

    @Test void failedPreparationNeverPartiallyReplacesThePublishedSet() {
        var index = new ProductionShellMemberships<Object, Host>(); var scope = new Object();
        var host = new Host(new UUID(0, 1)); var r = index.registration(host);
        r.refresh(scope, 1, () -> List.of(address(0)));
        var failure = new IllegalStateException("capture failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> r.refresh(scope, 2, () -> { throw failure; })));
        assertThrows(NullPointerException.class, () -> r.refresh(scope, 2, () -> null));
        assertThrows(NullPointerException.class, () -> r.refresh(scope, 2, () -> Arrays.asList(address(1), null)));
        assertTrue(r.indexed()); assertSame(host, index.members(scope, address(0)).getFirst());
        assertTrue(index.members(scope, address(1)).isEmpty());
        assertEquals(UNCHANGED, r.refresh(scope, 1, () -> { throw new AssertionError("published version changed"); }));
        assertEquals(UPDATED, r.refresh(scope, 2, () -> List.of(address(1))));
        assertTrue(index.members(scope, address(0)).isEmpty()); assertSame(host, index.members(scope, address(1)).getFirst());
    }

    @Test void reentrantClearOrRefreshWinsOverAnOlderPreparation() {
        var index = new ProductionShellMemberships<Object, Host>(); var scope = new Object();
        var host = new Host(new UUID(0, 1)); var r = index.registration(host);
        assertEquals(STALE, r.refresh(scope, 1, () -> { r.clear(); return List.of(address(0)); }));
        assertFalse(r.indexed()); assertTrue(index.members(scope, address(0)).isEmpty());
        r.refresh(scope, 1, () -> List.of(address(0)));
        assertEquals(STALE, r.refresh(scope, 2, () -> {
            assertEquals(UPDATED, r.refresh(scope, 3, () -> List.of(address(2))));
            return List.of(address(1));
        }));
        assertTrue(index.members(scope, address(0)).isEmpty()); assertTrue(index.members(scope, address(1)).isEmpty());
        assertSame(host, index.members(scope, address(2)).getFirst());
        assertEquals(UNCHANGED, r.refresh(scope, 3, () -> { throw new AssertionError("newer publication lost"); }));
        assertEquals(STALE, r.refresh(scope, 4, () -> { r.clear(); return List.of(address(3)); }));
        assertFalse(r.indexed()); assertTrue(index.members(scope, address(2)).isEmpty());
        assertTrue(index.members(scope, address(3)).isEmpty());
    }

    @Test void multipleHandlesForOneHostDoNotDuplicateCallbacksOrRemoveEachOther() {
        var index = new ProductionShellMemberships<Object, Host>(); var scope = new Object();
        var host = new Host(new UUID(0, 1)); var first = index.registration(host); var second = index.registration(host);
        first.refresh(scope, 1, () -> List.of(address(0))); second.refresh(scope, 1, () -> List.of(address(0)));
        assertEquals(1, index.members(scope, address(0)).size());
        first.clear(); assertFalse(first.indexed()); assertTrue(second.indexed());
        assertEquals(1, index.members(scope, address(0)).size()); assertSame(host, index.members(scope, address(0)).getFirst());
        second.clear(); assertTrue(index.members(scope, address(0)).isEmpty());
    }

    private static ProductionLayout.Address address(int x) {
        return new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(x, 0, 0));
    }
    // Equal persisted identities still represent distinct loaded hosts.
    private record Host(UUID id) {}
}
