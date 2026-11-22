package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochClose.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochAccess.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Capture;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Failure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionEpochCloseTest {
    @Test void followerClosesAnIdleHeterogeneousEpochWithoutChangingMemberProfilesOrBindings() {
        var f = new Fixture(); var record = f.a.state.value.record();
        assertEquals(Result.CLOSED, f.close());
        assertEquals(List.of("roots", "idle:1", "root:1", "mailbox:1", "idle:2", "root:2", "mailbox:2"), f.quiescenceCalls);
        assertEquals(List.of(List.of(false, false), List.of(false, false)), f.notifications);
        for (var host : f.hosts) {
            assertNull(host.state.value.epoch()); assertTrue(host.state.value.faults().isEmpty());
            assertEquals(record, host.state.value.record()); assertEquals(f.binding, host.state.value.binding());
        }
        assertEquals(List.of(FIRST, SECOND), record.structure().layout().members().stream().map(ProductionLayout.Member::profile).toList());
        assertTrue(f.a.published); assertFalse(f.b.published); assertTrue(f.publishedReady);
        var after = f.states(); int probes = f.probes;
        assertEquals(Result.NO_EPOCH, f.close()); assertEquals(after, f.states()); assertEquals(probes, f.probes);
    }

    @Test void initialRejectionsPreservePrecedenceAndDoNotProbeTheWorld() {
        for (String failure : List.of("no_epoch", "mismatch", "fault", "persistence", "record", "identity")) {
            var f = new Fixture(); var s = f.b.state.value;
            f.edit(f.b, ignored -> new Replica<>(failure.equals("identity") ? null : s.computerId(), !failure.equals("persistence"), true,
                failure.equals("record") ? null : s.record(), s.binding(), failure.equals("no_epoch") ? null : s.epoch(),
                failure.equals("fault") ? Set.of("width") : Set.of()));
            if (failure.equals("mismatch")) f.expected = id(999);
            var expected = switch (failure) {
                case "no_epoch" -> Result.NO_EPOCH; case "mismatch" -> Result.EPOCH_MISMATCH;
                case "fault" -> Result.REQUIRES_REFORM; default -> Result.IDENTITY_CONFLICT;
            };
            var before = f.states(); assertEquals(expected, f.close(), failure);
            assertEquals(before, f.states(), failure); assertEquals(0, f.probes, failure); assertTrue(f.quiescenceCalls.isEmpty(), failure);
        }
    }

    @Test void worldFailuresAreDistinctAndCannotReachQuiescenceOrCommit() {
        for (String failure : List.of("caller", "old_partial", "old_space", "scan_partial", "scan_space", "scan_identity", "scan_open",
            "observed_identity", "current_partial", "current_space", "resolve_partial", "resolve_space", "resolve_identity", "replacement", "domain_partial", "domain_space")) {
            var f = new Fixture(); var expected = Result.IDENTITY_CONFLICT;
            switch (failure) {
                case "caller" -> f.exact = false;
                case "old_partial" -> { f.oldProof = Failure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "old_space" -> { f.oldProof = Failure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "scan_partial" -> { f.scanState = ProductionStructureScanner.State.PARTIAL_UNLOADED; expected = Result.PARTIAL_UNLOADED; }
                case "scan_space" -> { f.scanState = ProductionStructureScanner.State.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "scan_identity" -> f.scanState = ProductionStructureScanner.State.IDENTITY_INVALID;
                case "scan_open" -> { f.scanState = ProductionStructureScanner.State.OPEN_SHELL; expected = Result.STRUCTURE_INVALID; }
                case "observed_identity" -> f.observed = id(99);
                case "current_partial" -> { f.currentProof = Failure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "current_space" -> { f.currentProof = Failure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "resolve_partial" -> { f.resolveFailure = ResolveFailure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "resolve_space" -> { f.resolveFailure = ResolveFailure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "resolve_identity" -> f.resolveFailure = ResolveFailure.IDENTITY;
                case "replacement" -> f.replaceCaller = true;
                case "domain_partial" -> { f.domainFailure = Failure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "domain_space" -> { f.domainFailure = Failure.SPACE; expected = Result.SPACE_UNCERTAIN; }
            }
            var before = f.states(); assertEquals(expected, f.close(), failure); assertEquals(before, f.states(), failure);
            assertEquals(0, f.preparations, failure); assertTrue(f.quiescenceCalls.isEmpty(), failure);
        }
    }

    @Test void authorityAndRelatedReplicaConflictsCannotBeHiddenByTheCurrentMemberList() {
        for (String failure : List.of("missing_minimum", "authority_epoch", "authority_binding", "authority_structure", "authority_persistence",
            "replica_binding", "replica_epoch", "duplicate", "old_domain_copy")) {
            var f = new Fixture(); var a = f.a.state.value; var b = f.b.state.value;
            switch (failure) {
                case "missing_minimum" -> f.omitAuthority = true;
                case "authority_epoch" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(), null, Set.of()));
                case "authority_binding" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, false, s.record(), s.binding(), s.epoch(), Set.of()));
                case "authority_structure" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, true,
                    new ProductionStructure.Revision(id(99), 0, id(1), f.current), s.binding(), s.epoch(), Set.of()));
                case "authority_persistence" -> f.edit(f.a, s -> new Replica<>(s.computerId(), false, true, s.record(), s.binding(), s.epoch(), Set.of()));
                case "replica_binding" -> f.edit(f.b, s -> new Replica<>(s.computerId(), true, true, s.record(),
                    new ProductionBinding(id(20), 1, null, List.of()), s.epoch(), Set.of()));
                case "replica_epoch" -> f.edit(f.b, s -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(),
                    new ProductionEpoch(f.epoch.epochId(), id(99), id(10), id(1), f.current.layout()), Set.of()));
                case "duplicate" -> f.extra.add(new Host(pos(2), new State(a, 0)));
                case "old_domain_copy" -> f.extra.add(new Host(pos(2), new State(new Replica<>(id(9), true, true, b.record(), b.binding(), null, Set.of()), 0)));
            }
            var before = f.states(); assertEquals(Result.IDENTITY_CONFLICT, f.close(), failure);
            assertEquals(before, f.states(), failure); assertTrue(f.quiescenceCalls.isEmpty(), failure);
        }
    }

    @Test void frozenMembersKeepTheirExactPositionAndProfileAndOtherReplicasCanRequireReform() {
        for (String failure : List.of("missing", "position", "profile", "fault")) {
            var f = new Fixture(); var expected = Result.FROZEN_MEMBER_MISSING;
            switch (failure) {
                case "missing" -> f.current = structure(List.of(member(1, 1, FIRST)));
                case "position" -> f.current = structure(List.of(member(1, 1, FIRST), member(2, 2, SECOND)));
                case "profile" -> { f.current = structure(List.of(member(1, 1, FIRST), member(2, 3, FIRST))); expected = Result.PROFILE_NOT_READY; }
                case "fault" -> { f.edit(f.a, s -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(), s.epoch(), Set.of("depth"))); expected = Result.REQUIRES_REFORM; }
            }
            var before = f.states(); assertEquals(expected, f.close(), failure); assertEquals(before, f.states(), failure);
            assertTrue(f.quiescenceCalls.isEmpty(), failure);
        }
    }

    @Test void rootsTasksAndMailboxesMustBeIdleAndReentryGuardIsReleasedAfterAnException() {
        for (String failure : List.of("roots", "idle:2", "root:2", "mailbox:2")) {
            var f = new Fixture(); f.busy = failure; var before = f.states();
            assertEquals(failure.equals("roots") ? Result.ROOTS_REMAIN : Result.RUNTIME_NOT_QUIESCENT, f.close(), failure);
            assertEquals(before, f.states(), failure); assertEquals(0, f.revalidations, failure);
        }
        var f = new Fixture(); var before = f.states();
        f.rootEffect = () -> { assertEquals(Result.RUNTIME_NOT_QUIESCENT, f.close()); throw new IllegalStateException("probe unavailable"); };
        assertThrows(IllegalStateException.class, f::close); assertEquals(before, f.states());
        f.rootEffect = () -> {}; assertEquals(Result.CLOSED, f.close());
    }

    @Test void revalidationAndLastPreparedWitnessRejectChangesWithoutStaging() {
        for (String failure : List.of("domain", "resolved", "witness")) {
            var f = new Fixture(); var before = f.hosts.stream().map(h -> h.state.value).toList();
            switch (failure) {
                case "domain" -> f.domainValid = false;
                case "resolved" -> f.resolvedValid = false;
                case "witness" -> f.prepareEffect = host -> { if (host == f.b) f.edit(f.a, UnaryOperator.identity()); };
            }
            assertEquals(Result.IDENTITY_CONFLICT, f.close(), failure);
            assertEquals(before, f.hosts.stream().map(h -> h.state.value).toList(), failure);
            assertEquals(0, f.publications, failure); assertTrue(f.notifications.isEmpty(), failure);
        }
    }

    @Test void emptyPendingMemberParticipatesInQuiescenceButLeavesTheClosedStructureNotReady() {
        var f = new Fixture(); f.current = structure(List.of(member(1, 1, FIRST), member(2, 3, SECOND), member(3, 2, null)));
        var record = new ProductionStructure.Revision(id(10), 1, id(1), f.current);
        f.hosts.add(new Host(pos(2), new State(new Replica<>(id(3), true, true, record, f.binding, f.epoch, Set.of()), 0)));
        f.replaceRecord(record); f.scanState = ProductionStructureScanner.State.VALID_NOT_READY;
        assertEquals(Result.CLOSED, f.close()); assertFalse(f.publishedReady);
        assertTrue(f.hosts.stream().noneMatch(h -> h.published));
        assertTrue(f.quiescenceCalls.containsAll(List.of("idle:3", "root:3", "mailbox:3")));
        assertTrue(f.hosts.stream().allMatch(h -> h.state.value.epoch() == null));
        assertNull(f.a.state.value.record().structure().layout().members().getLast().profile());
    }

    @Test void changedRecordsCannotWrapRevisionButUnchangedMaximumRevisionCanClose() {
        var unchanged = new Fixture();
        unchanged.replaceRecord(new ProductionStructure.Revision(id(10), Long.MAX_VALUE, id(1), unchanged.current));
        assertEquals(Result.CLOSED, unchanged.close());
        assertEquals(Long.MAX_VALUE, unchanged.a.state.value.record().revision());
        var changed = new Fixture(); changed.replaceRecord(new ProductionStructure.Revision(id(10), Long.MAX_VALUE, id(1), changed.current));
        var shells = new HashSet<>(changed.current.casingPositions()); shells.add(pos(2));
        changed.current = new ProductionStructure(changed.current.layout(), shells, changed.current.containingChunks());
        var before = changed.states(); assertEquals(Result.PERSISTENCE_INVALID, changed.close()); assertEquals(before, changed.states());
        assertEquals(0, changed.preparations);
    }

    private static final ProductionResourceProfile FIRST = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
    private static final ProductionResourceProfile SECOND = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
    private static UUID id(int n) { return new UUID(0, n); }
    private static BlockPos pos(int x) { return new BlockPos(x, 1, 1); }
    private static ProductionLayout.Member member(int id, int x, ProductionResourceProfile profile) {
        return new ProductionLayout.Member(id(id), new ProductionLayout.Address(Level.OVERWORLD, null, pos(x)), profile);
    }
    private static ProductionStructure structure(List<ProductionLayout.Member> members) {
        var bounds = new ProductionLayout.Bounds(0, 0, 0, 4, 2, 2); var shells = new HashSet<BlockPos>();
        for (var p : BlockPos.betweenClosed(0, 0, 0, 4, 2, 2))
            if (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 2 || p.getZ() == 0 || p.getZ() == 2) shells.add(p.immutable());
        return new ProductionStructure(new ProductionLayout(bounds, members, List.of()), shells, ProductionStructure.chunksFor(bounds));
    }
    private record State(Replica<String> value, long version) {}
    private static final class Host {
        final BlockPos position; State state; boolean published;
        Host(BlockPos position, State state) { this.position = position; this.state = state; }
    }
    private static final class Fixture implements Access<Host,State,String>, ProductionQuiescence {
        ProductionStructure current = structure(List.of(member(1, 1, FIRST), member(2, 3, SECOND)));
        final ProductionBinding binding = new ProductionBinding(id(20), 0, null, List.of());
        final ProductionEpoch epoch = ProductionEpoch.freeze(id(20), id(10), current.layout());
        final ProductionStructure.Revision record = new ProductionStructure.Revision(id(10), 0, id(1), current);
        final Host a = new Host(pos(1), new State(new Replica<>(id(1), true, true, record, binding, epoch, Set.of()), 0));
        final Host b = new Host(pos(3), new State(new Replica<>(id(2), true, true, record, binding, epoch, Set.of()), 0));
        final List<Host> hosts = new ArrayList<>(List.of(a, b)), extra = new ArrayList<>();
        UUID expected = epoch.epochId(), observed = id(10);
        Failure oldProof = Failure.NONE, currentProof = Failure.NONE, domainFailure = Failure.NONE;
        ResolveFailure resolveFailure = ResolveFailure.NONE;
        ProductionStructureScanner.State scanState = ProductionStructureScanner.State.VALID;
        boolean exact = true, replaceCaller, omitAuthority, domainValid = true, resolvedValid = true, publishedReady;
        String busy; int probes, preparations, publications, revalidations;
        Runnable rootEffect = () -> {}; Consumer<Host> prepareEffect = h -> {};
        final List<String> quiescenceCalls = new ArrayList<>(); final List<List<Boolean>> notifications = new ArrayList<>();
        Fixture() { a.published = true; }
        Result close() { return ProductionEpochClose.close(b, expected, Set.of("width", "depth"), this, this); }
        List<State> states() { return hosts.stream().map(h -> h.state).toList(); }
        void edit(Host host, UnaryOperator<Replica<String>> edit) { host.state = new State(edit.apply(host.state.value), host.state.version + 1); }
        void replaceRecord(ProductionStructure.Revision record) { hosts.forEach(h -> edit(h, s -> new Replica<>(s.computerId(), true, true, record, s.binding(), s.epoch(), s.faults()))); }
        public State capture(Host host) { return host.state; }
        public Replica<String> value(State state) { return state.value; }
        public boolean exactCaller(Host caller, State state) { probes++; return exact; }
        public Failure proveOld(Host caller, ProductionStructure.Revision record, ProductionEpoch epoch) { probes++; return oldProof; }
        public ProductionStructureScanner.ScanResult scan(Host caller) { probes++; return new ProductionStructureScanner.ScanResult(scanState, current, observed); }
        public Failure proveCurrent(Host caller, ProductionStructure structure) { probes++; return currentProof; }
        public Resolved<Host> resolve(Host caller, ProductionStructure structure) {
            probes++; var byId = new HashMap<UUID,Host>(); hosts.forEach(h -> byId.put(h.state.value.computerId(), h));
            if (replaceCaller) byId.put(caller.state.value.computerId(), new Host(caller.position, caller.state));
            if (omitAuthority) byId.remove(id(1)); return new Resolved<>(hosts, byId, resolveFailure);
        }
        public Capture<Host,State> captureDomain(Host caller, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) {
            probes++; var cells = new ArrayList<ProductionDomainProof.Cell<Host,State>>();
            for (var host : hosts) cells.add(new ProductionDomainProof.Cell<>(host.position, host, host.state));
            for (var host : extra) cells.add(new ProductionDomainProof.Cell<>(host.position, host, host.state));
            return new Capture<>(new ProductionDomainProof.Domain(List.of(current.layout().bounds()), List.of()), domainFailure, cells);
        }
        public boolean revalidateDomain(Capture<Host,State> before, Host caller, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) { revalidations++; return domainValid; }
        public boolean revalidateResolved(Host caller, ProductionStructure current, ProductionEpoch epoch, Set<String> faults) { revalidations++; return resolvedValid; }
        public ProductionTopologyCommit.Replica prepare(Host host, State before, ProductionStructure.Revision record, boolean ready, boolean withdraw) {
            preparations++; prepareEffect.accept(host);
            return new ProductionTopologyCommit.Replica() {
                public boolean matchesExpected() { return host.state.equals(before); }
                public boolean published() { return host.published; }
                public boolean withdrawBeforeCommit() { return withdraw; }
                public void withdraw() { host.published = false; }
                public void stage() { edit(host, s -> new Replica<>(s.computerId(), s.persistenceAvailable(), s.bindingValid(), record, s.binding(), null, Set.of())); }
                public void restore() { host.state = before; }
                public void changed() { notifications.add(hosts.stream().map(h -> h.state.value.epoch() != null).toList()); }
                public void republish() { host.published = true; }
                public void markUnavailable() { throw new AssertionError("Close publication cannot fail in this fixture"); }
            };
        }
        public ProductionTopologyCommit.Publication publication(ProductionStructure current, Host elected, boolean ready) {
            return new ProductionTopologyCommit.Publication() {
                public boolean publish() { publications++; publishedReady = ready; if (ready && elected != null) elected.published = true; return true; }
                public boolean restore() { return true; }
                public void withdraw() { throw new AssertionError("Close publication cannot fail in this fixture"); }
            };
        }
        private boolean quiet(String key) { quiescenceCalls.add(key); return !key.equals(busy); }
        public boolean allRootsStopped() { rootEffect.run(); return quiet("roots"); }
        public boolean nodeIdle(UUID id) { return quiet("idle:" + id.getLeastSignificantBits()); }
        public boolean nodeRootFree(UUID id) { return quiet("root:" + id.getLeastSignificantBits()); }
        public boolean nodeMailboxEmpty(UUID id) { return quiet("mailbox:" + id.getLeastSignificantBits()); }
    }
}
