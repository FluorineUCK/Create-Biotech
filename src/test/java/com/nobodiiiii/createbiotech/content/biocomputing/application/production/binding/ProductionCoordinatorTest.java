package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionCoordinator.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionCoordinatorTest {
    @Test void publicationRefreshesMembershipBeforeRegistrationAndWithdrawsIndexBeforeClearing() {
        var f = new Fixture(false); var before = f.states();
        f.onRegistration = host -> {
            assertTrue(host.coordinator.isPublished());
            assertTrue(f.hosts.stream().allMatch(h -> h.coordinator.isAttachedTo(f.service)));
        };
        f.service.publish(f.hosts, Set.of(id(1), id(2)));
        assertEquals(1, f.registrations); assertTrue(f.service.updateAccess(Availability.READY));
        assertEquals(Preparation.READY, f.service.prepare(f.binding(2), false));
        f.service.publish(f.hosts, Set.of(id(1), id(2)));
        assertEquals(1, f.registrations, "Republishing refreshes the set, not the registry entry");
        assertThrows(NullPointerException.class, f.service::commit, "Refreshing cancels a stale prepared transaction");
        assertEquals(Preparation.READY, f.service.prepare(f.binding(2), false));
        f.onUnregistration = host -> {
            assertTrue(host.coordinator.isPublished(), "The registry still sees the prior publication while removing it");
            assertTrue(f.hosts.stream().allMatch(h -> h.coordinator.isAttachedTo(f.service)));
        };
        f.service.withdraw();
        assertFalse(f.service.isPublished()); assertFalse(f.service.canRebind());
        assertTrue(f.hosts.stream().noneMatch(h -> h.coordinator.isAttachedTo(f.service)));
        assertThrows(NullPointerException.class, f.service::commit);
        f.service.withdraw(); assertEquals(1, f.withdrawals);
        assertEquals(before, f.states(), "Publishing does not mutate persistent topology or member profiles");
    }

    @Test void onlyTheIdentifiedElectedCoordinatorCanPublishWithoutMutatingRejectedMembers() {
        for (String missing : List.of("identity", "record", "election")) {
            var f = new Fixture(false); var original = f.a.state;
            switch (missing) {
                case "identity" -> f.edit(f.a, s -> new MemberState(null, s.profile(), s.persistenceAvailable(), s.topology()));
                case "record" -> f.topology(f.a, s -> new ProductionComputerTopology.State<>(null, s.binding(), true, null, Set.of()));
                case "election" -> f.topology(f.a, s -> new ProductionComputerTopology.State<>(
                    new ProductionStructure.Revision(id(10), 0, id(2), f.layout), s.binding(), true, null, Set.of()));
            }
            var before = f.states(); f.service.publish(f.hosts, Set.of(id(1), id(2)));
            assertFalse(f.service.isPublished(), missing); assertEquals(0, f.registrations, missing);
            assertTrue(f.hosts.stream().noneMatch(h -> h.coordinator.isAttachedTo(f.service)), missing);
            assertEquals(before, f.states(), missing);
            f.a.state = original;
            f.service.publish(f.hosts, Set.of(id(1), id(2)));
            assertTrue(f.service.isPublished()); assertEquals(1, f.registrations);
        }
    }

    @Test void switchingPublicationRetiresOldPreparationWithoutClearingTheNewAttachment() {
        for (boolean active : List.of(false, true)) {
            var f = new Fixture(); if (active) f.activate();
            assertEquals(Preparation.READY, f.service.prepare(f.binding(2), true));
            var replacement = f.host(f.a.state, 99); var next = replacement.coordinator;
            assertEquals(f.a, replacement); assertFalse(next.isPublished()); assertFalse(next.canRebind());
            var oldAttachmentsAtUnregister = new ArrayList<Boolean>();
            f.onUnregistration = host -> {
                assertSame(f.a, host); assertTrue(host.coordinator.isPublished());
                oldAttachmentsAtUnregister.add(f.b.coordinator.isAttachedTo(f.service));
            };
            next.publish(List.of(replacement, f.b), Set.of(id(1), id(2)));
            assertTrue(next.isPublished()); assertTrue(f.b.coordinator.isAttachedTo(next));
            assertFalse(f.b.coordinator.isAttachedTo(f.service));
            assertEquals(active, f.service.isPublished(), "An active follower loss does not independently retire its coordinator");
            assertThrows(NullPointerException.class, f.service::commit);
            f.service.invalidatePublication();
            assertFalse(f.service.isPublished()); assertEquals(1, f.withdrawals);
            assertEquals(List.of(!active), oldAttachmentsAtUnregister);
            assertTrue(next.isPublished()); assertTrue(f.b.coordinator.isAttachedTo(next));
            f.service.invalidatePublication(); f.service.clear();
            assertEquals(1, f.withdrawals, "Late old callbacks are idempotent");
            assertTrue(f.b.coordinator.isAttachedTo(next)); assertTrue(next.updateAccess(Availability.READY));
            assertEquals(Preparation.READY, next.prepare(f.binding(2), true));
            assertEquals(List.of(FIRST, SECOND), List.of(replacement.state.profile(), f.b.state.profile()));
            assertEquals(List.of(10L, 20L, 99L), List.of(f.a.memory, f.b.memory, replacement.memory));
        }
    }

    @Test void preparedExactReplicasCommitAllBindingsBeforeNotifyingWithoutRecaptureOrPooling() {
        var f = new Fixture(); var profiles = f.states().stream().map(MemberState::profile).toList();
        var next = f.binding(2);
        assertEquals(Preparation.READY, f.service.prepare(next, false));
        f.rejectCapture = true;
        f.service.commit();
        assertEquals(List.of("index-begin", "stage:1", "stage:2", "index-end", "publish:1", "publish:2"), f.events);
        assertEquals(List.of(List.of(next, next), List.of(next, next)), f.notifications);
        assertEquals(profiles, f.states().stream().map(MemberState::profile).toList());
        assertEquals(List.of(10L, 20L), f.hosts.stream().map(h -> h.memory).toList());
        assertThrows(NullPointerException.class, f.service::commit);
        f.rejectCapture = false; assertTrue(f.service.canRebind());
    }

    @Test void activeEpochAllowsOnlySameGroupAndAuthorityLogisticsChangesAndKeepsEachProfile() {
        var f = new Fixture(); var epoch = f.activate(); var before = f.states();
        var next = f.binding(2);
        assertFalse(f.service.canRebind());
        assertEquals(Preparation.ACTIVE, f.service.prepare(next, false));
        assertEquals(Preparation.IDENTITY, f.service.prepare(new ProductionBinding(id(99), 2, next.authority(), next.logisticsIds()), true));
        assertEquals(Preparation.IDENTITY, f.service.prepare(new ProductionBinding(id(20), 2,
            new ProductionBinding.Authority(ProductionBinding.Role.LIBRARY, id(10)), next.logisticsIds()), true));
        assertEquals(before, f.states()); assertTrue(f.events.isEmpty());
        assertEquals(Preparation.READY, f.service.prepare(next, true)); f.service.commit();
        assertEquals(List.of(FIRST, SECOND), f.states().stream().map(MemberState::profile).toList());
        f.hosts.forEach(host -> assertSame(epoch, host.state.topology().epoch()));
        assertEquals(List.of(List.of(next, next), List.of(next, next)), f.notifications);

        var historical = new Fixture();
        historical.setBinding(historical.a, new ProductionBinding(id(20), 1, null, List.of(id(501))));
        var unchanged = historical.states();
        assertEquals(Preparation.IDENTITY, historical.service.prepare(historical.binding(2), true));
        assertEquals(unchanged, historical.states()); assertTrue(historical.events.isEmpty());
    }

    @Test void rejectedPreparationClearsAnEarlierAcceptanceAndNeverMutatesMembers() {
        for (String failure : List.of("null", "authority", "unpublished", "missing-members", "duplicate-id", "storage", "profile",
            "binding-invalid", "record", "structure", "coordinator", "dimension", "address", "revision", "divergence", "offline",
            "owner-fault", "member-fault", "member-epoch")) {
            var f = new Fixture(); var next = f.binding(2); Preparation expected = Preparation.IDENTITY;
            assertEquals(Preparation.READY, f.service.prepare(next, false));
            switch (failure) {
                case "null" -> next = null;
                case "authority" -> next = new ProductionBinding(id(20), 2, null, List.of());
                case "unpublished" -> f.service.withdraw();
                case "missing-members" -> f.service.accept(List.of(f.a), Set.of(id(1), id(2)));
                case "duplicate-id" -> f.edit(f.b, s -> new MemberState(id(1), s.profile(), true, s.topology()));
                case "storage" -> f.edit(f.b, s -> new MemberState(s.computerId(), s.profile(), false, s.topology()));
                case "profile" -> f.edit(f.b, s -> new MemberState(s.computerId(), null, true, s.topology()));
                case "binding-invalid" -> f.topology(f.b, s -> new ProductionComputerTopology.State<>(s.structure(), s.binding(), false, null, Set.of()));
                case "record" -> f.topology(f.b, s -> new ProductionComputerTopology.State<>(null, s.binding(), true, null, Set.of()));
                case "structure" -> f.topology(f.b, s -> new ProductionComputerTopology.State<>(
                    new ProductionStructure.Revision(id(99), 0, id(1), f.layout), s.binding(), true, null, Set.of()));
                case "coordinator" -> f.topology(f.b, s -> new ProductionComputerTopology.State<>(
                    new ProductionStructure.Revision(id(10), 0, id(2), f.layout), s.binding(), true, null, Set.of()));
                case "dimension" -> {
                    var other = structure(f.layout.layout().members().stream().map(m -> new ProductionLayout.Member(m.computerId(),
                        new ProductionLayout.Address(Level.NETHER, null, m.address().localPos()), m.profile())).toList());
                    f.topology(f.b, s -> new ProductionComputerTopology.State<>(new ProductionStructure.Revision(id(10), 0, id(1), other), s.binding(), true, null, Set.of()));
                }
                case "address" -> f.addressUnavailable = true;
                case "revision" -> { f.setBinding(f.b, f.binding(3)); expected = Preparation.REVISION; }
                case "divergence" -> { f.setBinding(f.b, new ProductionBinding(id(20), 2, next.authority(), List.of(id(999)))); expected = Preparation.REVISION; }
                case "offline" -> { f.service.updateAccess(Availability.AUTHORITY_OFFLINE); expected = Preparation.ACTIVE; }
                case "owner-fault" -> { f.topology(f.a, s -> new ProductionComputerTopology.State<>(s.structure(), s.binding(), true, null, Set.of("width"))); expected = Preparation.ACTIVE; }
                case "member-fault" -> f.topology(f.b, s -> new ProductionComputerTopology.State<>(s.structure(), s.binding(), true, null, Set.of("width")));
                case "member-epoch" -> f.topology(f.b, s -> new ProductionComputerTopology.State<>(s.structure(), s.binding(), true,
                    ProductionEpoch.freeze(id(20), id(10), f.layout.layout()), Set.of()));
            }
            var before = f.states();
            assertEquals(expected, f.service.prepare(next, false), failure);
            assertThrows(NullPointerException.class, f.service::commit, failure);
            assertEquals(before, f.states(), failure); assertTrue(f.events.isEmpty(), failure);
        }
    }

    @Test void anUnboundPublishedGroupCanPrepareItsFirstBindingWithoutReadyAuthority() {
        var f = new Fixture(); f.hosts.forEach(h -> f.setBinding(h, null));
        f.service.updateAccess(Availability.CONFLICT);
        assertTrue(f.service.canRebind());
        assertEquals(Preparation.READY, f.service.prepare(f.initial, false)); f.service.commit();
        assertEquals(List.of(List.of(f.initial, f.initial), List.of(f.initial, f.initial)), f.notifications);
        assertTrue(f.service.canRebind());
    }

    @Test void valueEqualReplacementDetachesTheOldInstanceAndItsInvalidationCannotAffectTheNewSet() {
        var f = new Fixture(); assertEquals(Preparation.READY, f.service.prepare(f.binding(2), false));
        var replacement = f.host(f.b.state, 99);
        assertEquals(f.b, replacement); assertNotSame(f.b, replacement);
        f.service.accept(List.of(f.a, replacement), Set.of(id(1), id(2)));
        assertFalse(f.b.coordinator.isAttachedTo(f.service)); assertTrue(replacement.coordinator.isAttachedTo(f.service));
        assertThrows(NullPointerException.class, f.service::commit, "Replacing the set cancels its prepared transaction");
        f.service.invalidate(f.b);
        assertTrue(f.service.canRebind()); assertEquals(0, f.withdrawals);
        assertEquals(20, f.b.memory); assertEquals(99, replacement.memory);
    }

    @Test void acceptedCollectionsAreCopiedAndClearDetachesAllInstancesAndDropsPreparation() {
        var f = new Fixture(); var members = new ArrayList<>(f.hosts); var ids = new HashSet<>(Set.of(id(1), id(2)));
        f.service.accept(members, ids); members.clear(); ids.clear();
        assertTrue(f.service.canRebind());
        assertEquals(Preparation.READY, f.service.prepare(f.binding(2), false));
        var before = f.states(); f.service.clear();
        assertFalse(f.service.canRebind()); assertTrue(f.hosts.stream().noneMatch(h -> h.coordinator.isAttachedTo(f.service)));
        assertThrows(NullPointerException.class, f.service::commit);
        f.service.refreshLoadedReplicas(); assertTrue(f.dependencies.isEmpty()); assertEquals(before, f.states());
    }

    @Test void invalidationAlwaysDropsPreparationAndWithdrawsIdleOrOwnerButNotAnActiveFollowersCoordinator() {
        for (String mode : List.of("idle-follower", "active-follower", "active-owner")) {
            var f = new Fixture(); if (!mode.startsWith("idle")) f.activate();
            assertEquals(Preparation.READY, f.service.prepare(f.binding(2), true));
            (mode.endsWith("owner") ? f.a : f.b).coordinator.invalidatePublication();
            assertThrows(NullPointerException.class, f.service::commit, mode);
            assertEquals(mode.equals("active-follower") ? 0 : 1, f.withdrawals, mode);
            assertEquals(mode.equals("active-follower"), f.service.isPublished(), mode);
            assertEquals(Preparation.IDENTITY, f.service.prepare(f.binding(2), true), mode);
        }
    }

    @Test void dependencyRefreshTargetsOnlyCurrentlyLoadedAndExactlyAttachedMembers() {
        var f = new Fixture(); f.b.loaded = false;
        f.service.refreshLoadedReplicas(); assertEquals(List.of(f.a), f.dependencies);
        f.dependencies.clear(); f.b.loaded = true; f.b.coordinator.detachFrom(f.service);
        f.service.refreshLoadedReplicas(); assertEquals(List.of(f.a), f.dependencies);
        f.dependencies.clear(); f.service.accept(f.hosts, Set.of(id(1), id(2)));
        f.service.refreshLoadedReplicas(); assertEquals(List.of(f.a, f.b), f.dependencies);
    }

    @Test void dependencyNotificationsUseTheFullyQualifiedSnapshotBeforeCallbacksChangeLaterMembers() {
        var f = new Fixture();
        f.onDependency = host -> {
            assertEquals(List.of(f.a, f.b), f.loadedChecks, "All qualifications precede the first notification");
            f.b.loaded = false; f.b.coordinator.detachFrom(f.service);
        };
        f.service.refreshLoadedReplicas();
        assertEquals(List.of(f.a, f.b), f.dependencies, "Callbacks cannot change the already captured notification set");
        f.dependencies.clear(); f.loadedChecks.clear(); f.onDependency = host -> {};
        f.service.refreshLoadedReplicas();
        assertEquals(List.of(f.a), f.dependencies); assertEquals(List.of(f.a), f.loadedChecks);
    }

    @Test void onlyReadyAuthoritySynchronizesLowerRevisionsWithoutChangingProfilesOrPrivateMemory() {
        var f = new Fixture(); var next = f.binding(3); f.setBinding(f.a, next);
        assertFalse(f.service.updateAccess(Availability.AUTHORITY_OFFLINE));
        assertEquals(f.initial, f.b.state.topology().binding()); assertTrue(f.events.isEmpty()); assertFalse(f.service.canRebind());
        assertTrue(f.service.updateAccess(Availability.READY));
        assertEquals(List.of("stage:2", "publish:2"), f.events);
        assertEquals(List.of(List.of(next, next)), f.notifications); assertTrue(f.service.canRebind());
        assertEquals(List.of(FIRST, SECOND), f.states().stream().map(MemberState::profile).toList());
        assertEquals(List.of(10L, 20L), f.hosts.stream().map(h -> h.memory).toList());
    }

    @Test void aLaterConflictingReplicaPreventsAnyEarlierLowerRevisionFromBeingStaged() {
        for (String conflict : List.of("ahead", "different-group", "same-revision", "missing", "detached")) {
            var f = new Fixture(); var c = f.host(3, SECOND, 30); f.hosts.add(c);
            var members = new ArrayList<>(f.layout.layout().members()); members.add(new ProductionLayout.Member(id(3), address(2), SECOND));
            var record = new ProductionStructure.Revision(id(10), 0, id(1), structure(members));
            f.hosts.forEach(h -> f.topology(h, s -> new ProductionComputerTopology.State<>(record, s.binding(), true, null, Set.of())));
            f.service.accept(f.hosts, Set.of(id(1), id(2), id(3)));
            var next = f.binding(3); f.setBinding(f.a, next);
            switch (conflict) {
                case "ahead" -> f.setBinding(c, f.binding(4));
                case "different-group" -> f.setBinding(c, new ProductionBinding(id(99), 1, next.authority(), next.logisticsIds()));
                case "same-revision" -> f.setBinding(c, new ProductionBinding(id(20), 3, next.authority(), List.of(id(999))));
                case "missing" -> f.setBinding(c, null);
                case "detached" -> c.coordinator.detachFrom(f.service);
            }
            var before = f.states(); f.resetEvents();
            assertFalse(f.service.updateAccess(Availability.READY), conflict);
            assertEquals(before, f.states(), conflict); assertTrue(f.events.isEmpty(), conflict); assertFalse(f.service.canRebind(), conflict);
        }
    }

    private static final ProductionResourceProfile FIRST = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
    private static final ProductionResourceProfile SECOND = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
    private static UUID id(int n) { return new UUID(0, n); }
    private static ProductionLayout.Address address(int x) { return new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(x, 1, 1)); }
    private static ProductionStructure structure(List<ProductionLayout.Member> members) {
        var bounds = new ProductionLayout.Bounds(0, 0, 0, 4, 2, 2); var shells = new HashSet<BlockPos>();
        for (var p : BlockPos.betweenClosed(0, 0, 0, 4, 2, 2))
            if (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 2 || p.getZ() == 0 || p.getZ() == 2) shells.add(p.immutable());
        return new ProductionStructure(new ProductionLayout(bounds, members, List.of()), shells, ProductionStructure.chunksFor(bounds));
    }
    private static final class Host {
        final UUID identity; final long memory; MemberState state;
        ProductionCoordinator<Host> coordinator; boolean loaded = true;
        Host(MemberState state, long memory) { this.identity = state.computerId(); this.state = state; this.memory = memory; }
        // Value-equal replacement hosts must still be detached/accepted by exact instance identity.
        @Override public boolean equals(Object other) { return other instanceof Host host && identity.equals(host.identity); }
        @Override public int hashCode() { return identity.hashCode(); }
    }
    private static final class Fixture implements Access<Host> {
        final ProductionStructure layout = structure(List.of(new ProductionLayout.Member(id(1), ProductionCoordinatorTest.address(1), FIRST),
            new ProductionLayout.Member(id(2), ProductionCoordinatorTest.address(3), SECOND)));
        final ProductionStructure.Revision record = new ProductionStructure.Revision(id(10), 0, id(1), layout);
        final ProductionBinding initial = binding(1);
        final Host a = host(1, FIRST, 10), b = host(2, SECOND, 20);
        final List<Host> hosts = new ArrayList<>(List.of(a, b));
        final ProductionCoordinator<Host> service = a.coordinator;
        final List<String> events = new ArrayList<>();
        final List<List<ProductionBinding>> notifications = new ArrayList<>();
        final List<Host> dependencies = new ArrayList<>(), loadedChecks = new ArrayList<>();
        boolean rejectCapture, addressUnavailable; int registrations, withdrawals;
        Consumer<Host> onRegistration = host -> {}, onUnregistration = host -> {}, onDependency = host -> {};
        Fixture() { this(true); }
        Fixture(boolean publish) {
            if (publish) { service.publish(hosts, Set.of(id(1), id(2))); assertTrue(service.updateAccess(Availability.READY)); }
            resetEvents();
        }
        Host host(int id, ProductionResourceProfile profile, long memory) {
            return host(new MemberState(id(id), profile, true, new ProductionComputerTopology.State<>(record, initial, true, null, Set.of())), memory);
        }
        Host host(MemberState state, long memory) {
            var host = new Host(state, memory); host.coordinator = new ProductionCoordinator<>(host, this); return host;
        }
        ProductionBinding binding(long revision) { return new ProductionBinding(id(20), revision,
            new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR, id(10)), List.of(id(501))); }
        List<MemberState> states() { return hosts.stream().map(h -> h.state).toList(); }
        void resetEvents() { events.clear(); notifications.clear(); dependencies.clear(); }
        void edit(Host host, UnaryOperator<MemberState> change) { host.state = change.apply(host.state); }
        void topology(Host host, UnaryOperator<ProductionComputerTopology.State<?>> change) {
            var s = host.state; host.state = new MemberState(s.computerId(), s.profile(), s.persistenceAvailable(), change.apply(s.topology()));
        }
        void setBinding(Host host, ProductionBinding binding) { topology(host, s -> new ProductionComputerTopology.State<>(s.structure(), binding, true, s.epoch(), s.faults())); }
        ProductionEpoch activate() {
            var epoch = ProductionEpoch.freeze(id(20), id(10), layout.layout());
            hosts.forEach(h -> topology(h, s -> new ProductionComputerTopology.State<>(s.structure(), s.binding(), true, epoch, s.faults())));
            return epoch;
        }
        public MemberState capture(Host host) { if (rejectCapture) throw new AssertionError("Commit must use the prepared exact replicas"); return host.state; }
        public ProductionCoordinator<Host> coordinator(Host host) { return host.coordinator; }
        public ProductionLayout.Address address(Host host) { if (addressUnavailable) throw new IllegalStateException("unloaded"); return ProductionCoordinatorTest.address(1); }
        public boolean authoritativeLoaded(Host host) { loadedChecks.add(host); return host.loaded; }
        public void register(Host host) { registrations++; onRegistration.accept(host); }
        public void unregister(Host host) { withdrawals++; onUnregistration.accept(host); }
        public void dependencyChanged(Host host) { dependencies.add(host); onDependency.accept(host); }
        public void stageBinding(List<Host> hosts, ProductionBinding binding) {
            for (var host : hosts) { events.add("stage:" + host.identity.getLeastSignificantBits()); setBinding(host, binding); }
        }
        public void publishBinding(Host host) {
            events.add("publish:" + host.identity.getLeastSignificantBits());
            notifications.add(hosts.stream().map(h -> h.state.topology().binding()).toList());
        }
        public void indexedMutation(Runnable mutation) { events.add("index-begin"); mutation.run(); events.add("index-end"); }
    }
}
