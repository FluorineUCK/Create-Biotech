package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochFaultLatch.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionTopologyReconciler.Replica;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionEpochFaultLatchTest {
    @Test void followerUnionsFaultsAndWithdrawsAllServicesBeforeNotifyingWithoutChangingProfiles() {
        var f = new Fixture(); f.faults(f.b, Set.of("depth")); f.b.published = true;
        var epoch = f.epoch; var records = f.hosts.stream().map(h -> h.state.value.record()).toList();
        assertEquals(Result.LATCHED, f.latch(f.b, "width"));
        assertEquals(List.of("stage:1", "stage:2", "withdraw:1", "withdraw:2", "panel", "changed:1", "changed:2"), f.effects);
        assertEquals(List.of(Set.of("depth", "width"), Set.of("depth", "width")), f.lastNotifiedFaults);
        assertEquals(List.of(FIRST, SECOND), epoch.layout().members().stream().map(ProductionLayout.Member::profile).toList());
        for (int i = 0; i < f.hosts.size(); i++) {
            assertEquals(epoch, f.hosts.get(i).state.value.epoch());
            assertEquals(records.get(i), f.hosts.get(i).state.value.record());
            assertEquals(f.binding, f.hosts.get(i).state.value.binding());
        }
        var after = f.states();
        assertEquals(Result.ALREADY_LATCHED, f.latch(f.a, "depth")); assertEquals(after, f.states());
    }

    @Test void initialRejectionsRetainTheirPriorityWithoutWorldProbesOrWrites() {
        for (String failure : List.of("persistence", "identity", "record", "no_epoch", "epoch_mismatch", "not_frozen")) {
            var f = new Fixture(); var s = f.a.state.value;
            f.edit(f.a, ignored -> new Replica<>(failure.equals("identity") ? null : failure.equals("not_frozen") ? id(9) : s.computerId(),
                !failure.equals("persistence"), true, failure.equals("record") ? null : s.record(), s.binding(),
                failure.equals("no_epoch") ? null : s.epoch(), s.faults()));
            var before = f.states();
            var expected = switch (failure) {
                case "no_epoch" -> Result.NO_EPOCH;
                case "epoch_mismatch" -> Result.EPOCH_MISMATCH;
                default -> Result.IDENTITY_INVALID;
            };
            assertEquals(expected, ProductionEpochFaultLatch.latch(f.a,
                failure.equals("epoch_mismatch") ? id(99) : f.epoch.epochId(), "width", f), failure);
            assertEquals(0, f.probes, failure); assertEquals(before, f.states(), failure); assertTrue(f.effects.isEmpty(), failure);
        }
    }

    @Test void callerMustBeTheLoadedFrozenInstanceAtTheExactAddress() {
        for (String failure : List.of("unloaded", "space", "replacement", "address")) {
            var f = new Fixture();
            switch (failure) {
                case "unloaded" -> f.unloaded.add(f.a.position);
                case "space" -> f.wrongSpace.add(f.a.position);
                case "replacement" -> f.at.put(f.a.position, new Host(f.a.position, f.a.state));
                case "address" -> f.addresses.put(f.a.position, new ProductionLayout.Address(Level.NETHER, null, f.a.position));
            }
            var before = f.states(); assertEquals(Result.IDENTITY_INVALID, f.latch(f.a, "width"), failure);
            assertEquals(before, f.states(), failure); assertTrue(f.effects.isEmpty(), failure);
        }
    }

    @Test void currentMemberPositionsTakePrecedenceAndFrozenOnlyMembersStillReceiveFaults() {
        var current = new Fixture();
        current.at.remove(current.b.position); current.b.position = pos(2); current.at.put(current.b.position, current.b);
        var pending = new Host(pos(3), new State(new Replica<>(id(3), true, true, current.record, current.binding, current.epoch, Set.of()), 0));
        current.hosts.add(pending); current.at.put(pending.position, pending);
        var expanded = structure(List.of(member(1, 1, FIRST), member(2, 2, SECOND), member(3, 3, FIRST)));
        current.record(current.a, new ProductionStructure.Revision(id(10), 1, id(1), expanded));
        assertEquals(Result.LATCHED, current.latch(current.a, "width"));
        assertTrue(current.hosts.stream().allMatch(h -> h.state.value.faults().equals(Set.of("width"))));
        assertEquals(3, current.effects.stream().filter(e -> e.startsWith("stage:")).count());

        var frozenOnly = new Fixture();
        frozenOnly.record(frozenOnly.a, new ProductionStructure.Revision(id(10), 1, id(1), structure(List.of(member(1, 1, FIRST)))));
        assertEquals(Result.LATCHED, frozenOnly.latch(frozenOnly.a, "depth"));
        assertEquals(Set.of("depth"), frozenOnly.b.state.value.faults());
    }

    @Test void unavailableCandidatesAreNotReadAndUnrelatedCandidatesAreNotWrittenWhileRepeatLatchCatchesUp() {
        for (String failure : List.of("unloaded", "space", "absent", "identity", "epoch_missing", "epoch_mismatch", "structure")) {
            var f = new Fixture(); var s = f.b.state.value;
            switch (failure) {
                case "unloaded" -> f.unloaded.add(f.b.position);
                case "space" -> f.wrongSpace.add(f.b.position);
                case "absent" -> f.at.remove(f.b.position);
                case "identity" -> f.edit(f.b, ignored -> new Replica<>(id(90), true, true, s.record(), s.binding(), s.epoch(), Set.of()));
                case "epoch_missing" -> f.edit(f.b, ignored -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(), null, Set.of()));
                case "epoch_mismatch" -> f.edit(f.b, ignored -> new Replica<>(s.computerId(), true, true, s.record(), s.binding(),
                    new ProductionEpoch(id(99), f.epoch.groupId(), f.epoch.structureId(), f.epoch.coordinatorId(), f.epoch.layout()), Set.of()));
                case "structure" -> f.record(f.b, new ProductionStructure.Revision(id(99), 0, id(1), f.record.structure()));
            }
            var before = f.b.state; assertEquals(Result.LATCHED, f.latch(f.a, "width"), failure);
            assertEquals(before, f.b.state, failure); assertEquals(Set.of("width"), f.a.state.value.faults(), failure);
        }
        var f = new Fixture(); f.unloaded.add(f.b.position);
        assertEquals(Result.LATCHED, f.latch(f.a, "width")); f.unloaded.clear();
        assertEquals(Result.ALREADY_LATCHED, f.latch(f.b, "width"));
        assertEquals(Set.of("width"), f.b.state.value.faults());
    }

    @Test void staleVersionRejectsTheWholeSetBeforeAnyStageWithdrawalOrNotification() {
        var f = new Fixture(); f.stale = f.b; var before = f.states();
        assertEquals(Result.IDENTITY_INVALID, f.latch(f.a, "width"));
        assertEquals(before, f.states()); assertTrue(f.effects.isEmpty()); assertTrue(f.a.published); assertTrue(f.panelPublished);
    }

    @Test void exactFrozenCallerIsRetainedEvenWhenTheCurrentRecordPointsElsewhere() {
        var f = new Fixture();
        f.record(f.a, new ProductionStructure.Revision(id(10), 1, id(1), structure(List.of(member(1, 2, FIRST), member(2, 3, SECOND)))));
        assertEquals(Result.LATCHED, f.latch(f.a, "width"));
        assertEquals(List.of(Set.of("width"), Set.of("width")), f.lastNotifiedFaults);
        assertEquals(List.of("stage:2", "stage:1", "withdraw:1", "panel", "changed:2", "changed:1"), f.effects);
    }

    private static final ProductionResourceProfile FIRST = ProductionResourceProfile.forProfession(ProductionProfession.LIBRARIAN, 1, 0).orElseThrow();
    private static final ProductionResourceProfile SECOND = ProductionResourceProfile.forProfession(ProductionProfession.FLETCHER, 2, 0).orElseThrow();
    private static UUID id(int value) { return new UUID(0, value); }
    private static BlockPos pos(int x) { return new BlockPos(x, 1, 1); }
    private static ProductionLayout.Address addressOf(BlockPos position) { return new ProductionLayout.Address(Level.OVERWORLD, null, position); }
    private static ProductionLayout.Member member(int id, int x, ProductionResourceProfile profile) { return new ProductionLayout.Member(id(id), addressOf(pos(x)), profile); }
    private static ProductionStructure structure(List<ProductionLayout.Member> members) {
        var bounds = new ProductionLayout.Bounds(0, 0, 0, 4, 2, 2);
        var panel = new ProductionLayout.Panel(id(30), addressOf(pos(0)), Direction.EAST, ComputerShellMaterial.OPAQUE);
        var shells = new LinkedHashSet<BlockPos>();
        for (var p : BlockPos.betweenClosed(0, 0, 0, 4, 2, 2))
            if (p.getX() == 0 || p.getX() == 4 || p.getY() == 0 || p.getY() == 2 || p.getZ() == 0 || p.getZ() == 2) shells.add(p.immutable());
        return new ProductionStructure(new ProductionLayout(bounds, members, List.of(panel)), shells, ProductionStructure.chunksFor(bounds));
    }
    private record State(Replica<String> value, long version) {}
    private static final class Host {
        BlockPos position; State state; boolean published;
        Host(BlockPos position, State state) { this.position = position; this.state = state; }
    }
    private static final class Fixture implements Access<Host, State, String> {
        final ProductionStructure.Revision record = new ProductionStructure.Revision(id(10), 0, id(1), structure(List.of(member(1, 1, FIRST), member(2, 3, SECOND))));
        final ProductionBinding binding = new ProductionBinding(id(20), 0, null, List.of());
        final ProductionEpoch epoch = ProductionEpoch.freeze(id(20), id(10), record.structure().layout());
        final Host a = new Host(pos(1), new State(new Replica<>(id(1), true, true, record, binding, epoch, Set.of()), 0));
        final Host b = new Host(pos(3), new State(new Replica<>(id(2), true, true, record, binding, epoch, Set.of()), 0));
        final List<Host> hosts = new ArrayList<>(List.of(a, b));
        final Map<BlockPos, Host> at = new HashMap<>(Map.of(a.position, a, b.position, b));
        final Map<BlockPos, ProductionLayout.Address> addresses = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>(), wrongSpace = new HashSet<>();
        final List<String> effects = new ArrayList<>();
        List<Set<String>> lastNotifiedFaults; int probes; Host stale; boolean panelPublished = true;
        Fixture() { a.published = true; }
        Result latch(Host caller, String fault) { return ProductionEpochFaultLatch.latch(caller, epoch.epochId(), fault, this); }
        List<State> states() { return hosts.stream().map(h -> h.state).toList(); }
        void edit(Host host, UnaryOperator<Replica<String>> edit) { host.state = new State(edit.apply(host.state.value), host.state.version + 1); }
        void faults(Host host, Set<String> faults) {
            if (!faults.equals(host.state.value.faults())) edit(host, s -> new Replica<>(s.computerId(), s.persistenceAvailable(), s.bindingValid(), s.record(), s.binding(), s.epoch(), faults));
        }
        void record(Host host, ProductionStructure.Revision next) { edit(host, s -> new Replica<>(s.computerId(), s.persistenceAvailable(), s.bindingValid(), next, s.binding(), s.epoch(), s.faults())); }
        public Capture<State, String> capture(Host host) { return new Capture<>(host.state, host.state.value); }
        public BlockPos position(Host host) { return host.position; }
        public boolean loaded(BlockPos position) { probes++; return !unloaded.contains(position); }
        public boolean sameSpace(Host caller, BlockPos position) { probes++; return !wrongSpace.contains(position); }
        public Host computerAt(BlockPos position) {
            probes++; assertFalse(unloaded.contains(position)); assertFalse(wrongSpace.contains(position)); return at.get(position);
        }
        public ProductionLayout.Address address(BlockPos position) { return addresses.getOrDefault(position, addressOf(position)); }
        public boolean matches(Host host, State witness) { return host != stale && host.state.equals(witness); }
        public void stageFaults(Host host, Set<String> faults) { effects.add("stage:" + host.state.value.computerId().getLeastSignificantBits()); faults(host, faults); }
        public boolean published(Host host) { return host.published; }
        public void withdraw(Host host) { effects.add("withdraw:" + host.state.value.computerId().getLeastSignificantBits()); host.published = false; }
        public void withdrawPanel(ProductionLayout.Panel panel) { assertEquals(record.structure().layout().panels().getFirst(), panel); effects.add("panel"); panelPublished = false; }
        public void changed(Host host) {
            assertFalse(panelPublished); assertFalse(host.published);
            lastNotifiedFaults = hosts.stream().map(h -> h.state.value.faults()).toList();
            effects.add("changed:" + host.state.value.computerId().getLeastSignificantBits());
        }
    }
}
