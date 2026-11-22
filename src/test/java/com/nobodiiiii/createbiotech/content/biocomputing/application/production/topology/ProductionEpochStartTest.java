package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochStart.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionEpochStartTest {
    @Test void successfulStartFreezesEachMemberProfileAndRepeatStartHasNoWorldEffects() {
        var f = new Fixture();
        assertEquals(Result.STARTED, f.start());
        var epoch = f.a.state.value.epoch();
        assertEquals(f.structure.layout(), epoch.layout());
        assertEquals(List.of(FIRST, SECOND), epoch.layout().members().stream().map(ProductionLayout.Member::profile).toList());
        assertEquals(epoch, f.b.state.value.epoch()); assertEquals(f.a.id, epoch.coordinatorId());
        assertEquals(List.of(List.of(true, true), List.of(true, true)), f.notifications);
        var before = f.states(); int probes = f.probes;
        assertEquals(Result.ALREADY_ACTIVE, f.start()); assertEquals(probes, f.probes); assertEquals(before, f.states());
    }

    @Test void initialRejectionsKeepTheirPriorityAndDoNotProbeTheWorld() {
        for (String failure : List.of("active", "fault", "not_ready", "persistence", "identity", "record",
            "coordinator", "offline", "binding_invalid", "binding_missing", "group")) {
            var f = new Fixture(); var s = f.a.state.value;
            var record = failure.equals("record") ? null : failure.equals("coordinator")
                ? new ProductionStructure.Revision(s.record().structureId(), 0, f.b.id, f.structure) : s.record();
            var binding = failure.equals("binding_missing") ? null : failure.equals("group")
                ? new ProductionBinding(id(999), 0, null, List.of()) : s.binding();
            f.edit(f.a, ignored -> new Replica<>(failure.equals("identity") ? null : s.computerId(),
                !failure.equals("persistence"), !failure.equals("binding_invalid"), record, binding,
                failure.equals("active") ? f.epoch() : null, failure.equals("fault") ? Set.of("depth") : Set.of()));
            f.notReady = failure.equals("not_ready"); f.online = !failure.equals("offline");
            var expected = switch (failure) {
                case "active" -> Result.ALREADY_ACTIVE;
                case "fault" -> Result.REQUIRES_REFORM;
                case "coordinator", "offline" -> Result.NOT_COORDINATOR;
                case "binding_invalid", "binding_missing", "group" -> Result.BINDING_UNAVAILABLE;
                default -> Result.NOT_READY;
            };
            var before = f.states(); assertEquals(expected, f.start(), failure);
            assertEquals(0, f.probes, failure); assertEquals(before, f.states(), failure);
        }
    }

    @Test void incompleteOrChangedObservationAndAnEqualIdentityReplacementCannotStart() {
        for (String failure : List.of("chunks", "scan", "structure", "observed_id", "resolve", "replacement", "profile")) {
            var f = new Fixture(failure.equals("profile"));
            switch (failure) {
                case "chunks" -> f.loaded = false;
                case "scan" -> f.scan = new ProductionStructureScanner.ScanResult(ProductionStructureScanner.State.PARTIAL_UNLOADED, null, id(10));
                case "structure" -> f.scan = new ProductionStructureScanner.ScanResult(ProductionStructureScanner.State.VALID, structure(true), id(10));
                case "observed_id" -> f.scan = new ProductionStructureScanner.ScanResult(ProductionStructureScanner.State.VALID, f.structure, id(11));
                case "resolve" -> f.resolved = false;
                case "replacement" -> f.callerReplacement = true;
            }
            var before = f.states(); assertEquals(Result.NOT_READY, f.start(), failure);
            assertEquals(before, f.states(), failure); assertEquals(0, f.preparations, failure);
            assertEquals(0, f.authorityCalls, failure);
        }
    }

    @Test void incompatibleMemberBindingsOrFaultsAreRejectedBeforeRefreshingAuthority() {
        for (String failure : List.of("persistence", "record", "structure_id", "binding", "epoch", "fault")) {
            var f = new Fixture(); var s = f.b.state.value;
            f.edit(f.b, ignored -> new Replica<>(s.computerId(), !failure.equals("persistence"), true,
                failure.equals("record") ? null : failure.equals("structure_id")
                    ? new ProductionStructure.Revision(id(11), 0, f.a.id, f.structure) : s.record(),
                failure.equals("binding") ? new ProductionBinding(GROUP, 1, null, List.of()) : s.binding(),
                failure.equals("epoch") ? f.epoch() : null, failure.equals("fault") ? Set.of("width") : Set.of()));
            var before = f.states(); assertEquals(Result.NOT_READY, f.start(), failure);
            assertEquals(0, f.authorityCalls, failure); assertEquals(before, f.states(), failure);
        }
    }

    @Test void permissionReconciliationIsFollowedByFreshAuthorityAndReplicaCapture() {
        var f = new Fixture(); var revised = new ProductionBinding(GROUP, 1, null, List.of(id(70)));
        f.authorityEffect = () -> f.hosts.forEach(h -> f.edit(h, s -> new Replica<>(s.computerId(), true, true,
            s.record(), revised, null, Set.of())));
        assertEquals(Result.STARTED, f.start()); assertEquals(1, f.authorityCalls);
        for (var h : f.hosts) assertEquals(revised, h.state.value.binding());
    }

    @Test void permissionFailureAndChangesDuringItsCallbackCannotSlipPastFinalAdmission() {
        for (String failure : List.of("publication", "permission", "authority_record", "authority_group", "replica_invalid", "replica_fault")) {
            var f = new Fixture();
            f.a.published = !failure.equals("publication"); f.authorized = !failure.equals("permission");
            f.authorityEffect = () -> {
                if (failure.equals("authority_record")) f.edit(f.a, s -> new Replica<>(s.computerId(), true, true,
                    new ProductionStructure.Revision(id(10), 1, f.a.id, f.structure), s.binding(), null, Set.of()));
                if (failure.equals("authority_group")) f.edit(f.a, s -> new Replica<>(s.computerId(), true, true,
                    s.record(), new ProductionBinding(id(999), 1, null, List.of()), null, Set.of()));
                if (failure.equals("replica_invalid") || failure.equals("replica_fault")) f.edit(f.b, s -> new Replica<>(s.computerId(), true,
                    !failure.equals("replica_invalid"), s.record(), s.binding(), null,
                    failure.equals("replica_fault") ? Set.of("depth") : Set.of()));
            };
            assertEquals(failure.equals("publication") || failure.equals("permission") ? Result.BINDING_UNAVAILABLE : Result.NOT_READY,
                f.start(), failure);
            assertEquals(0, f.preparations, failure); assertTrue(f.hosts.stream().allMatch(h -> h.state.value.epoch() == null));
            if (failure.equals("publication")) assertEquals(0, f.authorityCalls);
        }
    }

    @Test void stalePreparedWitnessRejectsAndPublicationFailureRestoresAllMembersBeforeNotification() {
        var stale = new Fixture();
        stale.prepareEffect = h -> { if (h == stale.b) stale.edit(stale.a, UnaryOperator.identity()); };
        assertEquals(Result.NOT_READY, stale.start()); assertEquals(0, stale.publications);
        assertTrue(stale.notifications.isEmpty()); assertTrue(stale.hosts.stream().allMatch(h -> h.state.value.epoch() == null));
        var failed = new Fixture(); failed.publishSuccess = false; var before = failed.states();
        assertEquals(Result.NOT_READY, failed.start()); assertEquals(before, failed.states());
        assertEquals(1, failed.publications);
        assertEquals(List.of(List.of(true, true), List.of(true, true), List.of(false, false), List.of(false, false)), failed.notifications);
    }

    private static final UUID GROUP = id(50);
    private static final ProductionResourceProfile FIRST = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
    private static final ProductionResourceProfile SECOND = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
    private static UUID id(int n) { return new UUID(0, n); }
    private record State(Replica<String> value, long version) {}
    private static final class Host {
        final UUID id; State state; boolean published;
        Host(UUID id, State state) { this.id = id; this.state = state; }
    }
    private static ProductionStructure structure(boolean missingProfile) {
        var bounds = new ProductionLayout.Bounds(0, 0, 0, 4, 2, 2);
        var members = List.of(new ProductionLayout.Member(id(1), new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(1, 1, 1)), FIRST),
            new ProductionLayout.Member(id(2), new ProductionLayout.Address(Level.OVERWORLD, null, new BlockPos(3, 1, 1)), missingProfile ? null : SECOND));
        var shells = new LinkedHashSet<BlockPos>();
        for (var p : BlockPos.betweenClosed(0, 0, 0, 4, 2, 2))
            if (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 2 || p.getZ() == 0 || p.getZ() == 2) shells.add(p.immutable());
        return new ProductionStructure(new ProductionLayout(bounds, members, List.of()), shells, ProductionStructure.chunksFor(bounds));
    }
    private static final class Fixture implements Access<Host, State, String> {
        final ProductionStructure structure; final Host a, b; final List<Host> hosts;
        boolean notReady, online = true, loaded = true, resolved = true, callerReplacement, authorized = true, publishSuccess = true;
        ProductionStructureScanner.ScanResult scan;
        int probes, authorityCalls, preparations, publications;
        Runnable authorityEffect = () -> {}; Consumer<Host> prepareEffect = h -> {};
        final List<List<Boolean>> notifications = new ArrayList<>();
        Fixture() { this(false); }
        Fixture(boolean missingProfile) {
            structure = structure(missingProfile); var record = new ProductionStructure.Revision(id(10), 0, id(1), structure);
            var binding = new ProductionBinding(GROUP, 0, null, List.of());
            a = new Host(id(1), new State(new Replica<>(id(1), true, true, record, binding, null, Set.of()), 0));
            b = new Host(id(2), new State(new Replica<>(id(2), true, true, record, binding, null, Set.of()), 0));
            a.published = true; hosts = List.of(a, b);
            scan = new ProductionStructureScanner.ScanResult(ProductionStructureScanner.State.VALID, structure, id(10));
        }
        Result start() { return ProductionEpochStart.start(a, GROUP, this); }
        List<State> states() { return hosts.stream().map(h -> h.state).toList(); }
        ProductionEpoch epoch() { return ProductionEpoch.freeze(GROUP, id(10), structure.layout()); }
        void edit(Host host, UnaryOperator<Replica<String>> edit) { host.state = new State(edit.apply(host.state.value), host.state.version + 1); }
        public Capture<State, String> capture(Host host) { return new Capture<>(host.state, host.state.value); }
        public boolean notReady(Host host) { return notReady; }
        public boolean coordinatorOnline(Host host) { return online; }
        public boolean chunksLoaded(ProductionStructure value) { probes++; return loaded; }
        public ProductionStructureScanner.ScanResult scan(Host host) { probes++; return scan; }
        public Resolved<Host> resolve(Host host, ProductionStructure value) {
            probes++; return new Resolved<>(hosts, Map.of(a.id, callerReplacement ? new Host(a.id, a.state) : a, b.id, b), resolved);
        }
        public boolean enforcePublication() { return true; }
        public boolean published(Host host) { return host.published; }
        public boolean refreshBindingAuthority(Host host) { authorityCalls++; authorityEffect.run(); return authorized; }
        public ProductionTopologyCommit.Replica prepare(Host h, Capture<State, String> before,
            ProductionStructure.Revision record, ProductionBinding binding, ProductionEpoch epoch) {
            preparations++; prepareEffect.accept(h);
            return new ProductionTopologyCommit.Replica() {
                public boolean matchesExpected() { return h.state.equals(before.witness()); }
                public boolean published() { return h.published; }
                public boolean withdrawBeforeCommit() { return false; }
                public void withdraw() { h.published = false; }
                public void stage() { edit(h, s -> new Replica<>(s.computerId(), true, true, record, binding, epoch, Set.of())); }
                public void restore() { h.state = before.witness(); }
                public void changed() { notifications.add(hosts.stream().map(host -> host.state.value.epoch() != null).toList()); }
                public void republish() { h.published = true; }
                public void markUnavailable() { throw new AssertionError("Inactive start has no old panels to restore"); }
            };
        }
        public ProductionTopologyCommit.Publication publication(ProductionStructure value, ProductionBinding binding, ProductionEpoch epoch) {
            return new ProductionTopologyCommit.Publication() {
                public boolean publish() { publications++; return publishSuccess; }
                public boolean restore() { return true; }
                public void withdraw() { throw new AssertionError("Inactive start has no old panels to withdraw"); }
            };
        }
    }
}
