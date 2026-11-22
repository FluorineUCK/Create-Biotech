package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochAccess.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochReform.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Capture;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.Failure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionEpochReformTest {
    @Test void followerReformsOnceAndFreezesEachMembersOwnProfileWithoutPooling() {
        var f = new Fixture();
        assertEquals(Result.REFORMED, f.reform());
        var next = f.a.state.value.epoch();
        assertNotNull(next); assertNotEquals(f.epoch.epochId(), next.epochId());
        assertEquals(f.epoch.groupId(), next.groupId()); assertEquals(f.record.structureId(), next.structureId());
        assertEquals(List.of(FIRST, SECOND), next.layout().members().stream().map(ProductionLayout.Member::profile).toList());
        assertNotEquals(FIRST, SECOND);
        for (var host : f.hosts) {
            assertEquals(next, host.state.value.epoch()); assertTrue(host.state.value.faults().isEmpty());
            assertEquals(1, host.state.value.record().revision()); assertEquals(f.binding, host.state.value.binding());
        }
        assertEquals(List.of("stop", "roots", "idle:1", "root:1", "mailbox:1", "idle:2", "root:2", "mailbox:2"), f.calls);
        assertEquals(Set.of("width", "depth"), f.revalidatedFaults);
        assertEquals(f.current.layout().panels(), f.publishedPanels);
        assertTrue(f.a.published); assertFalse(f.b.published);
        assertEquals(Result.EPOCH_MISMATCH, f.reform()); assertEquals(1, f.stops);
    }

    @Test void initialIdentityAndEpochGatesDoNotStopProduction() {
        for (String failure : List.of("epoch", "mismatch", "storage", "record", "identity", "caller")) {
            var f = new Fixture(); Result expected = Result.IDENTITY_CONFLICT;
            switch (failure) {
                case "epoch" -> { f.edit(f.b, s -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(), null, s.faults())); expected = Result.NO_EPOCH; }
                case "mismatch" -> { f.expected = id(999); expected = Result.EPOCH_MISMATCH; }
                case "storage" -> f.edit(f.b, s -> new Replica<>(s.computerId(), false, true, s.record(), s.binding(), s.epoch(), s.faults()));
                case "record" -> f.edit(f.b, s -> new Replica<>(s.computerId(), true, true, null, s.binding(), s.epoch(), s.faults()));
                case "identity" -> f.edit(f.b, s -> new Replica<>(null, true, true, s.record(), s.binding(), s.epoch(), s.faults()));
                case "caller" -> f.exact = false;
            }
            var before = f.states(); assertEquals(expected, f.reform(), failure); assertEquals(before, f.states(), failure);
            assertEquals(0, f.stops, failure); assertEquals(0, f.preparations, failure);
        }
    }

    @Test void proofScanResolutionProfileAndAuthorityFailuresPrecedeStopping() {
        for (String failure : List.of("old-partial", "old-space", "current-partial", "current-space", "resolve-partial", "resolve-space",
            "resolve-identity", "replace-caller", "authority-missing", "structure-id", "profile", "authority-storage", "binding",
            "authority-record", "authority-epoch", "authority-structure", "max-revision")) {
            var f = new Fixture(); Result expected = Result.IDENTITY_CONFLICT;
            switch (failure) {
                case "old-partial" -> { f.oldProof = Failure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "old-space" -> { f.oldProof = Failure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "current-partial" -> { f.currentProof = Failure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "current-space" -> { f.currentProof = Failure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "resolve-partial" -> { f.resolveFailure = ResolveFailure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "resolve-space" -> { f.resolveFailure = ResolveFailure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "resolve-identity" -> f.resolveFailure = ResolveFailure.IDENTITY;
                case "replace-caller" -> f.replaceCaller = true;
                case "authority-missing" -> f.omitAuthority = true;
                case "structure-id" -> f.observed = id(99);
                case "profile" -> { f.current = structure(List.of(member(1, 1, FIRST), member(2, 3, null))); expected = Result.PROFILE_NOT_READY; }
                case "authority-storage" -> f.edit(f.a, s -> new Replica<>(s.computerId(), false, true, s.record(), s.binding(), s.epoch(), s.faults()));
                case "binding" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, false, s.record(), null, s.epoch(), s.faults()));
                case "authority-record" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, true, null, s.binding(), s.epoch(), s.faults()));
                case "authority-epoch" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(), null, s.faults()));
                case "authority-structure" -> f.edit(f.a, s -> new Replica<>(s.computerId(), true, true,
                    new ProductionStructure.Revision(id(99), 0, id(1), f.current), s.binding(), s.epoch(), s.faults()));
                case "max-revision" -> { f.replaceRecord(new ProductionStructure.Revision(id(10), Long.MAX_VALUE, id(1), f.current)); expected = Result.PERSISTENCE_INVALID; }
            }
            var before = f.states(); assertEquals(expected, f.reform(), failure); assertEquals(before, f.states(), failure);
            assertEquals(0, f.stops, failure); assertEquals(0, f.preparations, failure);
        }
        for (var state : ProductionStructureScanner.State.values()) {
            if (state == ProductionStructureScanner.State.VALID) continue;
            var f = new Fixture(); f.scanState = state;
            var expected = switch (state) {
                case VALID_NOT_READY -> Result.PROFILE_NOT_READY;
                case PARTIAL_UNLOADED -> Result.PARTIAL_UNLOADED;
                case SPACE -> Result.SPACE_UNCERTAIN;
                case IDENTITY_INVALID, STRUCTURE_IDENTITY_CONFLICT, AMBIGUOUS -> Result.IDENTITY_CONFLICT;
                default -> Result.STRUCTURE_INVALID;
            };
            assertEquals(expected, f.reform(), state.name()); assertEquals(0, f.stops, state.name());
        }
        var missing = new Fixture(); missing.current = null;
        assertEquals(Result.STRUCTURE_INVALID, missing.reform()); assertEquals(0, missing.stops);
    }

    @Test void stopRootsAndEachMembersQuiescenceMustSucceedBeforeTopologyChanges() {
        for (String failure : List.of("stop", "roots", "idle:1", "root:1", "mailbox:1", "idle:2", "root:2", "mailbox:2")) {
            var f = new Fixture(); f.busy = failure; var before = f.states();
            var expected = switch (failure) { case "stop" -> Result.STOP_FAILED; case "roots" -> Result.ROOTS_REMAIN; default -> Result.RUNTIME_NOT_QUIESCENT; };
            assertEquals(expected, f.reform(), failure); assertEquals(before, f.states(), failure);
            assertEquals(1, f.stops, failure); assertEquals(0, f.preparations, failure);
            assertEquals(!failure.equals("stop"), f.productionStopped, failure);
        }
    }

    @Test void postStopDomainAndReplicaConflictsDoNotPretendToUndoTheProductionStop() {
        for (String failure : List.of("partial", "space", "binding", "epoch", "storage", "record")) {
            var f = new Fixture(); var before = f.states(); Result expected = Result.IDENTITY_CONFLICT;
            var replica = new Replica<String>(id(3), true, true, f.record, f.binding, f.epoch, Set.of());
            switch (failure) {
                case "partial" -> { f.domainFailure = Failure.PARTIAL; expected = Result.PARTIAL_UNLOADED; }
                case "space" -> { f.domainFailure = Failure.SPACE; expected = Result.SPACE_UNCERTAIN; }
                case "binding" -> replica = new Replica<>(id(3), true, true, f.record, new ProductionBinding(id(20), 1, null, List.of()), f.epoch, Set.of());
                case "epoch" -> replica = new Replica<>(id(3), true, true, f.record, f.binding, ProductionEpoch.freeze(id(20), id(10), f.current.layout()), Set.of());
                case "storage" -> replica = new Replica<>(id(3), false, true, f.record, f.binding, f.epoch, Set.of());
                case "record" -> replica = new Replica<>(id(3), true, true, null, f.binding, f.epoch, Set.of());
            }
            f.extra.add(new Host(pos(2), new State(replica, 0)));
            assertEquals(expected, f.reform(), failure); assertEquals(before, f.states(), failure);
            assertTrue(f.productionStopped, failure); assertEquals(0, f.preparations, failure);
        }
        var unrelated = new Fixture();
        unrelated.extra.add(new Host(pos(2), new State(new Replica<>(id(3), false, false, null, null, null, Set.of()), 0)));
        assertEquals(Result.REFORMED, unrelated.reform());
    }

    @Test void aFrozenMemberMayBeReplacedOnlyAfterItsAbsenceIsProvedAcrossTheOldDomain() {
        for (boolean stillPresent : List.of(false, true)) {
            var f = new Fixture(); f.caller = f.a; f.hosts.remove(f.b);
            f.current = structure(List.of(member(1, 1, FIRST), member(3, 2, SECOND)));
            f.hosts.add(new Host(pos(2), new State(new Replica<>(id(3), true, true, f.record, f.binding, f.epoch, Set.of()), 0)));
            if (stillPresent) f.extra.add(f.b);
            var before = f.states(); assertEquals(stillPresent ? Result.IDENTITY_CONFLICT : Result.REFORMED, f.reform());
            if (stillPresent) assertEquals(before, f.states());
            else {
                assertEquals(List.of(id(1), id(3)), f.a.state.value.epoch().layout().members().stream().map(ProductionLayout.Member::computerId).toList());
                assertFalse(f.calls.contains("idle:2")); assertTrue(f.calls.contains("mailbox:3"));
            }
        }
        for (String failure : List.of("duplicate", "profile", "address")) {
            var f = new Fixture();
            switch (failure) {
                case "duplicate" -> f.extra.add(new Host(pos(2), f.b.state));
                case "profile" -> f.current = structure(List.of(member(1, 1, FIRST), member(2, 3, FIRST)));
                case "address" -> f.current = structure(List.of(member(1, 1, FIRST), member(2, 2, SECOND)));
            }
            var before = f.states();
            assertEquals(failure.equals("duplicate") ? Result.IDENTITY_CONFLICT : Result.PROFILE_NOT_READY, f.reform(), failure);
            assertEquals(before, f.states(), failure); assertTrue(f.productionStopped, failure);
        }
    }

    @Test void revalidationAndFinalPreparedWitnessPreventStagingStaleEvidence() {
        for (String failure : List.of("domain", "resolved", "witness")) {
            var f = new Fixture(); var before = f.hosts.stream().map(h -> h.state.value).toList();
            switch (failure) {
                case "domain" -> f.domainValid = false;
                case "resolved" -> f.resolvedValid = false;
                case "witness" -> f.prepareEffect = h -> { if (h == f.b) f.edit(f.a, UnaryOperator.identity()); };
            }
            assertEquals(Result.IDENTITY_CONFLICT, f.reform(), failure);
            assertEquals(before, f.hosts.stream().map(h -> h.state.value).toList(), failure);
            assertEquals(0, f.publications, failure); assertTrue(f.notifications.isEmpty(), failure); assertTrue(f.productionStopped, failure);
        }
    }

    @Test void aStopCallbackCannotReenterAndAnExceptionAlwaysReleasesTheGuard() {
        var f = new Fixture(); var before = f.states();
        f.stopEffect = () -> {
            assertEquals(Result.RUNTIME_NOT_QUIESCENT, ProductionEpochReform.reform(f.a, f.expected, f, f));
            throw new IllegalStateException("production stop unavailable");
        };
        assertThrows(IllegalStateException.class, f::reform); assertEquals(before, f.states());
        assertEquals(1, f.stops); f.stopEffect = () -> {};
        assertEquals(Result.REFORMED, f.reform()); assertEquals(2, f.stops);
    }

    @Test void failedPublicationRestoresOnlyThePreviouslyPublishedSubsetAfterRestoringEveryReplica() {
        for (int count : List.of(0, 1, 2)) {
            var f = new Fixture(); f.publishOk = false;
            var previouslyPublished = List.copyOf(f.current.layout().panels().subList(0, count));
            f.publishedPanels = previouslyPublished; f.a.published = count > 0;
            var before = f.states(); assertEquals(Result.IDENTITY_CONFLICT, f.reform()); assertEquals(before, f.states());
            assertEquals(previouslyPublished, f.restorationPanels); assertEquals(previouslyPublished, f.publishedPanels);
            assertEquals(count > 0, f.a.published); assertFalse(f.b.published);
            assertEquals(4, f.notifications.size());
            assertTrue(f.notifications.get(0).stream().noneMatch(s -> f.epoch.equals(s.value.epoch())));
            assertEquals(before, f.notifications.get(2)); assertEquals(before, f.notifications.get(3));
            assertTrue(f.hosts.stream().noneMatch(h -> h.unavailable)); assertTrue(f.productionStopped);
        }
    }

    @Test void failedRecoveryWithdrawsPublicationAndMakesAllParticipantsUnavailable() {
        var f = new Fixture(); f.publishOk = false; f.restoreOk = false; var before = f.states();
        assertEquals(Result.IDENTITY_CONFLICT, f.reform()); assertEquals(before, f.states());
        assertTrue(f.publishedPanels.isEmpty()); assertTrue(f.hosts.stream().noneMatch(h -> h.published));
        assertTrue(f.hosts.stream().allMatch(h -> h.unavailable)); assertEquals(1, f.withdrawals);
        assertEquals(before, f.notifications.getLast()); assertTrue(f.productionStopped);
    }

    @Test void sharedEvidenceDomainsIncludePersistedCurrentAndFrozenBoundsAndAddresses() {
        var f = new Fixture();
        var records = List.of(f.record, new ProductionStructure.Revision(id(10), 1, id(1), f.current));
        assertEquals(List.of(f.record.structure().layout().bounds(), f.epoch.layout().bounds()), oldDomain(f.record, f.epoch).bounds());
        assertEquals(List.of(f.current.layout().bounds()), currentDomain(f.current).bounds());
        assertTrue(currentDomain(f.current).anchors().isEmpty());
        var combined = domain(records, f.epoch, f.current);
        assertEquals(4, combined.bounds().size());
        assertEquals(f.epoch.layout().members().stream().map(ProductionLayout.Member::address).toList(), combined.anchors());
        assertThrows(UnsupportedOperationException.class, () -> combined.bounds().clear());
    }

    private static final ProductionResourceProfile FIRST = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
    private static final ProductionResourceProfile SECOND = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
    private static UUID id(int n) { return new UUID(0, n); }
    private static BlockPos pos(int x) { return new BlockPos(x, 1, 1); }
    private static ProductionLayout.Address address(int x) { return new ProductionLayout.Address(Level.OVERWORLD, null, pos(x)); }
    private static ProductionLayout.Member member(int id, int x, ProductionResourceProfile profile) { return new ProductionLayout.Member(id(id), address(x), profile); }
    private static ProductionStructure structure(List<ProductionLayout.Member> members) {
        var bounds = new ProductionLayout.Bounds(0, 0, 0, 4, 2, 2); var shells = new HashSet<BlockPos>();
        for (var p : BlockPos.betweenClosed(0, 0, 0, 4, 2, 2))
            if (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 2 || p.getZ() == 0 || p.getZ() == 2) shells.add(p.immutable());
        var panels = List.of(new ProductionLayout.Panel(id(101), address(0), Direction.WEST, ComputerShellMaterial.OPAQUE),
            new ProductionLayout.Panel(id(102), address(4), Direction.EAST, ComputerShellMaterial.TRANSPARENT));
        return new ProductionStructure(new ProductionLayout(bounds, members, panels), shells, ProductionStructure.chunksFor(bounds));
    }
    private record State(Replica<String> value, long version) {}
    private static final class Host {
        final BlockPos position; State state; boolean published, unavailable;
        Host(BlockPos position, State state) { this.position = position; this.state = state; }
    }
    private static final class Fixture implements Access<Host,State,String>, ProductionReformControl {
        ProductionStructure current = structure(List.of(member(1, 1, FIRST), member(2, 3, SECOND)));
        final ProductionBinding binding = new ProductionBinding(id(20), 0, null, List.of());
        final ProductionEpoch epoch = ProductionEpoch.freeze(id(20), id(10), current.layout());
        final ProductionStructure.Revision record = new ProductionStructure.Revision(id(10), 0, id(1), current);
        final Host a = new Host(pos(1), new State(new Replica<>(id(1), true, true, record, binding, epoch, Set.of("width")), 0));
        final Host b = new Host(pos(3), new State(new Replica<>(id(2), true, true, record, binding, epoch, Set.of("depth")), 0));
        final List<Host> hosts = new ArrayList<>(List.of(a, b)), extra = new ArrayList<>();
        Host caller = b; UUID expected = epoch.epochId(), observed = id(10);
        Failure oldProof = Failure.NONE, currentProof = Failure.NONE, domainFailure = Failure.NONE;
        ResolveFailure resolveFailure = ResolveFailure.NONE;
        ProductionStructureScanner.State scanState = ProductionStructureScanner.State.VALID;
        boolean exact = true, replaceCaller, omitAuthority, domainValid = true, resolvedValid = true;
        boolean publishOk = true, restoreOk = true, productionStopped;
        String busy; int stops, preparations, publications, withdrawals;
        Runnable stopEffect = () -> {}; Consumer<Host> prepareEffect = h -> {};
        final List<String> calls = new ArrayList<>(); final List<List<State>> notifications = new ArrayList<>();
        List<ProductionLayout.Panel> publishedPanels = current.layout().panels(), restorationPanels;
        Set<String> revalidatedFaults;
        Fixture() { a.published = true; }
        Result reform() { return ProductionEpochReform.reform(caller, expected, this, this); }
        List<State> states() { return hosts.stream().map(h -> h.state).toList(); }
        void edit(Host host, UnaryOperator<Replica<String>> edit) { host.state = new State(edit.apply(host.state.value), host.state.version + 1); }
        void replaceRecord(ProductionStructure.Revision record) { hosts.forEach(h -> edit(h, s -> new Replica<>(s.computerId(), true, true, record, s.binding(), s.epoch(), s.faults()))); }
        public State capture(Host host) { return host.state; }
        public Replica<String> value(State state) { return state.value; }
        public boolean exactCaller(Host caller, State state) { return exact; }
        public Failure proveOld(Host caller, ProductionStructure.Revision record, ProductionEpoch epoch) { return oldProof; }
        public ProductionStructureScanner.ScanResult scan(Host caller) { return new ProductionStructureScanner.ScanResult(scanState, current, observed); }
        public Failure proveCurrent(Host caller, ProductionStructure structure) { return currentProof; }
        public Resolved<Host> resolve(Host caller, ProductionStructure structure) {
            var byId = new HashMap<UUID,Host>(); hosts.forEach(h -> byId.put(h.state.value.computerId(), h));
            if (replaceCaller) byId.put(caller.state.value.computerId(), new Host(caller.position, caller.state));
            if (omitAuthority) byId.remove(id(1)); return new Resolved<>(hosts, byId, resolveFailure);
        }
        public Capture<Host,State> captureDomain(Host caller, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) {
            assertTrue(productionStopped, "Capture the proof after the stop, not before");
            var cells = new ArrayList<ProductionDomainProof.Cell<Host,State>>();
            for (var host : hosts) cells.add(new ProductionDomainProof.Cell<>(host.position, host, host.state));
            for (var host : extra) cells.add(new ProductionDomainProof.Cell<>(host.position, host, host.state));
            return new Capture<>(domain(records, epoch, current), domainFailure, cells);
        }
        public boolean revalidateDomain(Capture<Host,State> before, Host caller, List<ProductionStructure.Revision> records, ProductionEpoch epoch, ProductionStructure current) { return domainValid; }
        public boolean revalidateResolved(Host caller, ProductionStructure current, ProductionEpoch epoch, Set<String> faults) { revalidatedFaults = faults; return resolvedValid; }
        public boolean panelPublished(ProductionLayout.Panel panel, ProductionEpoch epoch) { assertEquals(this.epoch, epoch); return publishedPanels.contains(panel); }
        public ProductionTopologyCommit.Replica prepare(Host host, State before, ProductionStructure.Revision record,
            ProductionBinding binding, ProductionEpoch replacement, boolean withdraw) {
            preparations++; prepareEffect.accept(host);
            return new ProductionTopologyCommit.Replica() {
                public boolean matchesExpected() { return host.state.equals(before); }
                public boolean published() { return host.published; }
                public boolean withdrawBeforeCommit() { return withdraw; }
                public void withdraw() { host.published = false; }
                public void stage() { edit(host, s -> new Replica<>(s.computerId(), s.persistenceAvailable(), true, record, binding, replacement, Set.of())); }
                public void restore() { host.state = before; }
                public void changed() { notifications.add(states()); }
                public void republish() { host.published = true; }
                public void markUnavailable() { host.unavailable = true; }
            };
        }
        public ProductionTopologyCommit.Publication publication(ProductionStructure current, Host stagingOwner, ProductionBinding binding,
            ProductionEpoch previous, ProductionEpoch replacement, List<ProductionLayout.Panel> previouslyPublished) {
            assertEquals(this.binding, binding); assertEquals(epoch, previous);
            return new ProductionTopologyCommit.Publication() {
                public boolean publish() { publications++; stagingOwner.published = true; publishedPanels = publishOk ? current.layout().panels() : List.of(); return publishOk; }
                public boolean restore() { restorationPanels = previouslyPublished; publishedPanels = previouslyPublished; return restoreOk; }
                public void withdraw() { withdrawals++; publishedPanels = List.of(); }
            };
        }
        private boolean quiet(String key) { calls.add(key); return !key.equals(busy); }
        public boolean stopAllAndClear() { stops++; stopEffect.run(); productionStopped = quiet("stop"); return productionStopped; }
        public boolean allRootsStopped() { return quiet("roots"); }
        public boolean nodeIdle(UUID id) { return quiet("idle:" + id.getLeastSignificantBits()); }
        public boolean nodeRootFree(UUID id) { return quiet("root:" + id.getLeastSignificantBits()); }
        public boolean nodeMailboxEmpty(UUID id) { return quiet("mailbox:" + id.getLeastSignificantBits()); }
    }
}
