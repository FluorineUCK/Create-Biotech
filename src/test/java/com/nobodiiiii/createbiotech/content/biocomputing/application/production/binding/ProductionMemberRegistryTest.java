package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding.Role.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionMemberRegistry.LookupStatus.*;

import java.lang.ref.Reference;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionMemberRegistryTest {
    @Test void scopesAndRolesRemainSeparateAndDuplicateRegistrationUsesExactInstanceIdentity() {
        var registry = registry(); var firstScope = new Object(); var secondScope = new Object();
        var first = host(1, PANEL); var equalReplacement = host(1, PANEL);
        var otherRole = host(1, LIBRARY); var otherScope = host(1, PANEL);
        assertEquals(first, equalReplacement); assertNotSame(first, equalReplacement);
        registry.register(firstScope, first); registry.register(firstScope, first);
        registry.register(firstScope, equalReplacement); registry.register(firstScope, otherRole);
        registry.register(secondScope, otherScope);
        var snapshot = registry.members(firstScope, GROUP, PANEL);
        assertEquals(2, snapshot.size()); assertSame(first, snapshot.get(0)); assertSame(equalReplacement, snapshot.get(1));
        assertEquals(List.of(otherRole), registry.members(firstScope, GROUP, LIBRARY));
        assertSame(otherScope, registry.members(secondScope, GROUP, PANEL).getFirst());
        assertTrue(registry.members(firstScope, id(99), PANEL).isEmpty());
        assertTrue(registry.members(firstScope, GROUP, COORDINATOR).isEmpty());
        registry.unregister(firstScope, first);
        assertSame(equalReplacement, registry.members(firstScope, GROUP, PANEL).getFirst());
        assertSame(otherRole, registry.lookupStable(firstScope, LIBRARY, id(1)).member());
        assertSame(otherScope, registry.lookupStable(secondScope, PANEL, id(1)).member());
        assertEquals(2, snapshot.size(), "A previously captured membership list does not become a live view");
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        Reference.reachabilityFence(List.of(firstScope, secondScope, first, equalReplacement, otherRole, otherScope));
    }

    @Test void stableLookupRejectsDistinctDuplicatesButAcceptsOneInstanceCapturedInSeveralGroups() {
        var registry = registry(); var scope = new Object(); var member = host(1, PANEL);
        assertEquals(MISSING, registry.lookupStable(scope, PANEL, id(1)).status());
        assertNull(registry.lookupStable(scope, PANEL, id(1)).member());
        registry.register(scope, member);
        member.group = id(200); registry.register(scope, member);
        assertEquals(FOUND, registry.lookupStable(scope, PANEL, id(1)).status());
        assertSame(member, registry.lookupStable(scope, PANEL, id(1)).member());
        var replacement = host(1, PANEL); replacement.group = id(300); registry.register(scope, replacement);
        assertEquals(CONFLICT, registry.lookupStable(scope, PANEL, id(1)).status());
        assertNull(registry.lookupStable(scope, PANEL, id(1)).member());
        registry.rebind(scope, replacement, () -> replacement.group = GROUP);
        assertEquals(CONFLICT, registry.lookupStable(scope, PANEL, id(1)).status(), "Same-group duplicates also conflict");
        registry.unregister(scope, replacement);
        assertSame(member, registry.lookupStable(scope, PANEL, id(1)).member());
        assertEquals(MISSING, registry.lookupStable(scope, LIBRARY, id(1)).status());
        registry.unregister(scope, member);
        assertEquals(MISSING, registry.lookupStable(scope, PANEL, id(1)).status());
        assertTrue(registry.members(scope, GROUP, PANEL).isEmpty());
        assertTrue(registry.members(scope, id(200), PANEL).isEmpty());
        Reference.reachabilityFence(List.of(scope, member, replacement));
    }

    @Test void withdrawalRemovesAllFrozenKeysEvenWhenCurrentIdentityCanNoLongerBeRead() {
        var registry = registry(); var scope = new Object(); var retired = host(1, PANEL);
        var replacement = host(1, PANEL); registry.register(scope, retired); registry.register(scope, replacement);
        retired.id = id(2); retired.group = id(200); retired.role = LIBRARY;
        registry.register(scope, retired);
        retired.rejectReads = true;
        registry.unregister(scope, retired); registry.unregister(scope, retired);
        assertSame(replacement, registry.members(scope, GROUP, PANEL).getFirst());
        assertSame(replacement, registry.lookupStable(scope, PANEL, id(1)).member());
        assertTrue(registry.members(scope, id(200), LIBRARY).isEmpty());
        assertEquals(MISSING, registry.lookupStable(scope, LIBRARY, id(2)).status());
        registry.unregister(new Object(), retired);
        Reference.reachabilityFence(List.of(scope, retired, replacement));
    }

    @Test void rebindWithdrawsBeforeMutationAndIndexesFinalIdentityEvenWhenMutationThrows() {
        var registry = registry(); var scope = new Object(); var member = host(1, PANEL);
        registry.register(scope, member); var snapshot = registry.members(scope, GROUP, PANEL);
        var failure = new IllegalStateException("Interrupted mutation");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> registry.rebind(scope, member, () -> {
            assertTrue(registry.members(scope, GROUP, PANEL).isEmpty());
            assertEquals(MISSING, registry.lookupStable(scope, PANEL, id(1)).status());
            member.group = id(200); member.role = LIBRARY; member.id = id(2);
            throw failure;
        })));
        assertTrue(registry.members(scope, GROUP, PANEL).isEmpty());
        assertSame(member, registry.members(scope, id(200), LIBRARY).getFirst());
        assertEquals(MISSING, registry.lookupStable(scope, PANEL, id(1)).status());
        assertSame(member, registry.lookupStable(scope, LIBRARY, id(2)).member());
        assertSame(member, snapshot.getFirst()); assertThrows(UnsupportedOperationException.class, snapshot::clear);
        registry.rebind(scope, member, () -> { member.group = null; member.rejectIdentity = true; });
        registry.register(scope, member);
        assertTrue(registry.members(scope, id(200), LIBRARY).isEmpty());
        assertEquals(MISSING, registry.lookupStable(scope, LIBRARY, id(2)).status());
        Reference.reachabilityFence(List.of(scope, member));
    }

    @Test void coherentBindingsAreReadOncePerMemberWithoutReconciliationOrMutation() {
        var f = new Consensus(); var before = f.states();
        assertFalse(f.registry.conflicts(f.scope, GROUP).blocksNewTasks());
        assertEquals(List.of(1, 1, 1, 1), f.hosts.stream().map(host -> host.bindingReads).toList());
        assertEquals(before, f.states(), "Conflict observation cannot reconcile or mutate a binding");
        assertFalse(f.registry.conflicts(f.scope, id(999)).blocksNewTasks());
        assertFalse(f.registry.conflicts(new Object(), GROUP).blocksNewTasks());
        assertEquals(List.of(1, 1, 1, 1), f.hosts.stream().map(host -> host.bindingReads).toList());
        f.hosts.forEach(host -> f.registry.unregister(f.scope, host));
        assertFalse(f.registry.conflicts(f.scope, GROUP).blocksNewTasks());
        Reference.reachabilityFence(f);
    }

    @Test void productionCardinalityRestrictsLibrariesAndCoordinatorsButNotPanels() {
        var f = new Consensus();
        var duplicatePanel = host(2, PANEL); f.register(duplicatePanel);
        assertEquals(CONFLICT, f.registry.lookupStable(f.scope, PANEL, id(2)).status());
        assertFalse(f.registry.conflicts(f.scope, GROUP).blocksNewTasks(),
            "Stable identity ambiguity is distinct from the existing group cardinality/consensus gate");
        var secondLibrary = host(5, LIBRARY); f.register(secondLibrary);
        var libraries = f.registry.conflicts(f.scope, GROUP);
        assertTrue(libraries.libraryConflict()); assertFalse(libraries.coordinatorConflict());
        assertFalse(libraries.bindingConflict()); assertTrue(libraries.blocksNewTasks());
        var secondCoordinator = host(6, COORDINATOR); f.register(secondCoordinator);
        var both = f.registry.conflicts(f.scope, GROUP);
        assertTrue(both.libraryConflict()); assertTrue(both.coordinatorConflict());
        assertFalse(both.bindingConflict());
        f.registry.unregister(f.scope, secondLibrary);
        var coordinators = f.registry.conflicts(f.scope, GROUP);
        assertFalse(coordinators.libraryConflict()); assertTrue(coordinators.coordinatorConflict());
        assertTrue(coordinators.blocksNewTasks());
        f.registry.unregister(f.scope, secondCoordinator);
        assertFalse(f.registry.conflicts(f.scope, GROUP).blocksNewTasks());
        Reference.reachabilityFence(List.of(f, duplicatePanel, secondLibrary, secondCoordinator));
    }

    @Test void invalidOrDivergentBindingsBlockWithoutSilentlyRepairingAnyParticipant() {
        for (String problem : List.of("invalid", "missing-binding", "missing-authority", "authority-disagreement",
            "authority-absent", "authority-role", "authority-identity", "authority-unregistered",
            "group", "revision", "networks", "network-order")) {
            var f = new Consensus(); var shared = f.shared;
            switch (problem) {
                case "invalid" -> f.panel.valid = false;
                case "missing-binding" -> f.panel.binding = null;
                case "missing-authority" -> f.panel.binding = new ProductionBinding(GROUP, 2, null, shared.logisticsIds());
                case "authority-disagreement" -> f.panel.binding = new ProductionBinding(GROUP, 2,
                    new ProductionBinding.Authority(LIBRARY, f.library.id), shared.logisticsIds());
                case "authority-absent", "authority-role" -> {
                    var authority = new ProductionBinding.Authority(problem.equals("authority-absent") ? COORDINATOR : LIBRARY,
                        problem.equals("authority-absent") ? id(999) : f.coordinator.id);
                    f.hosts.forEach(host -> host.binding = new ProductionBinding(GROUP, 2, authority, shared.logisticsIds()));
                }
                case "authority-identity" -> f.coordinator.id = id(999);
                case "authority-unregistered" -> f.registry.unregister(f.scope, f.coordinator);
                case "group" -> f.panel.binding = new ProductionBinding(id(200), 2, shared.authority(), shared.logisticsIds());
                case "revision" -> f.panel.binding = new ProductionBinding(GROUP, 3, shared.authority(), shared.logisticsIds());
                case "networks" -> f.panel.binding = new ProductionBinding(GROUP, 2, shared.authority(), List.of(id(501)));
                case "network-order" -> f.panel.binding = new ProductionBinding(GROUP, 2, shared.authority(), List.of(id(502), id(501)));
                default -> throw new AssertionError(problem);
            }
            var before = f.states(); var conflicts = f.registry.conflicts(f.scope, GROUP);
            assertTrue(conflicts.bindingConflict(), problem); assertTrue(conflicts.blocksNewTasks(), problem);
            assertFalse(conflicts.libraryConflict(), problem); assertFalse(conflicts.coordinatorConflict(), problem);
            assertEquals(before, f.states(), problem);
            Reference.reachabilityFence(f);
        }
    }

    private static final UUID GROUP = id(100);
    private static final ProductionMemberRegistry.Access<Host> ACCESS = new ProductionMemberRegistry.Access<>() {
        @Override public UUID groupId(Host host) { host.readable(); return host.group; }
        @Override public ProductionBinding.Role role(Host host) { host.identityReadable(); return host.role; }
        @Override public UUID memberId(Host host) { host.identityReadable(); return host.id; }
        @Override public boolean validBinding(Host host) { host.readable(); return host.valid; }
        @Override public ProductionBinding binding(Host host) { host.readable(); host.bindingReads++; return host.binding; }
    };
    private static ProductionMemberRegistry<Object, Host> registry() { return new ProductionMemberRegistry<>(ACCESS); }
    private static UUID id(long value) { return new UUID(0, value); }
    private static Host host(long value, ProductionBinding.Role role) { return new Host(id(value), role); }

    private static final class Consensus {
        final Object scope = new Object();
        final ProductionMemberRegistry<Object, Host> registry = registry();
        final Host coordinator = host(1, COORDINATOR), panel = host(2, PANEL), library = host(3, LIBRARY);
        final List<Host> hosts = List.of(coordinator, panel, library, host(4, PANEL));
        final ProductionBinding shared = new ProductionBinding(GROUP, 2,
            new ProductionBinding.Authority(COORDINATOR, id(1)), List.of(id(501), id(502)));
        Consensus() { hosts.forEach(this::register); }
        void register(Host host) {
            host.binding = new ProductionBinding(shared.groupId(), shared.revision(), shared.authority(), shared.logisticsIds());
            registry.register(scope, host);
        }
        List<HostState> states() { return hosts.stream().map(HostState::new).toList(); }
    }

    private record HostState(UUID id, UUID group, ProductionBinding.Role role, boolean valid, ProductionBinding binding) {
        HostState(Host host) { this(host.id, host.group, host.role, host.valid, host.binding); }
    }

    /** Independent application host: deliberately value-equal for testing exact loaded-instance identity. */
    private static final class Host {
        UUID id, group = GROUP;
        ProductionBinding.Role role;
        ProductionBinding binding;
        boolean valid = true, rejectReads, rejectIdentity;
        int bindingReads;
        Host(UUID id, ProductionBinding.Role role) { this.id = id; this.role = role; }
        void readable() { if (rejectReads) throw new AssertionError("Retired host must not be queried"); }
        void identityReadable() { readable(); if (rejectIdentity) throw new AssertionError("Unbound identity must not be queried"); }
        @Override public boolean equals(Object other) { return other instanceof Host host && id.equals(host.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }
}
