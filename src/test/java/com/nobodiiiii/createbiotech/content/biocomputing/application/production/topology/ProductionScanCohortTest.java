package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionScanCohort.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionScanCohortTest {
    private static final Hosts<Host> HOSTS = new Hosts<>() {
        public State<Host> state(Host h) { return h.state; }
        public UUID id(Host h) { return h.id; }
        public BlockPos position(Host h) { return h.pos; }
        public ProductionStructure.Revision revision(Host h) { return h.revision; }
    };

    @Test void failedDiscoveryUsesExplicitCadenceAndDirtyResetAndTimeRewindRearmIt() {
        var world = new Fixture(); var a = world.a;
        world.answer = owner -> new Refresh<>(List.of(), false, "open");
        world.tick(a, 0); world.tick(a, 1); world.tick(a, 19);
        assertEquals(1, world.refreshes.size());
        world.tick(a, 20); assertEquals(2, world.refreshes.size());
        a.state.markDirty(); world.tick(a, 21); world.tick(a, 22);
        assertEquals(3, world.refreshes.size()); assertFalse(a.state.dirty());
        a.state.reset(a); world.tick(a, 22); assertEquals(4, world.refreshes.size());
        world.tick(a, 0); assertEquals(5, world.refreshes.size());
        assertThrows(IllegalArgumentException.class, () -> tick(a, 1, 0, HOSTS, world));
    }

    @Test void aNonOwnerDiscoversOnceThenOnlyTheMinimumOwnerRefreshesTheGroup() {
        var world = new Fixture(); var a = world.a; var b = world.b;
        world.tick(b, 0); world.tick(a, 0);
        assertEquals(List.of(b, a), world.refreshes);
        world.tick(b, 19); world.tick(a, 19); assertEquals(2, world.refreshes.size());
        world.tick(b, 20); assertEquals(2, world.refreshes.size());
        world.tick(a, 20); assertEquals(3, world.refreshes.size());
        b.state.markDirty(); world.tick(b, 21); assertEquals(3, world.refreshes.size());
        world.tick(a, 21); world.tick(a, 22); world.tick(b, 22);
        assertEquals(4, world.refreshes.size()); assertSame(a, world.refreshes.getLast());
    }

    @Test void partialPersistedRebuildWithdrawsLoadedMembersWithoutScanningAndLaterRejoins() {
        var world = new Fixture(); world.persist();
        world.unloadedChunks.addAll(world.a.revision.structure().containingChunks());
        world.unloaded.add(world.b.pos);
        world.tick(world.a, 0);
        assertTrue(world.refreshes.isEmpty()); assertEquals(List.of(List.of(world.a)), world.withdrawn);
        assertFalse(world.objectReads.contains(world.b.pos));
        world.unloaded.clear(); world.unloadedChunks.clear();
        world.tick(world.b, 1); assertTrue(world.refreshes.isEmpty());
        world.tick(world.a, 1); assertEquals(List.of(world.a), world.refreshes);
    }

    @Test void invalidPersistedIdentitySpaceAddressOrRevisionRequiresFreshDiscovery() {
        for (String failure : List.of("identity", "space", "address", "revision", "caller_instance")) {
            var world = new Fixture(); world.persist();
            switch (failure) {
                case "identity" -> world.b.id = id(99);
                case "space" -> world.foreign.add(world.b.pos);
                case "address" -> world.addresses.put(world.b.pos, new ProductionLayout.Address(Level.NETHER, null, world.b.pos));
                case "revision" -> world.b.revision = world.b.revision.revise(world.a.id, world.b.revision.structure()).orElseThrow();
                case "caller_instance" -> world.replace(world.a);
            }
            world.answer = owner -> new Refresh<>(List.of(), false, "rejected");
            world.tick(world.a, 0);
            assertEquals(1, world.refreshes.size(), failure); assertTrue(world.withdrawn.isEmpty(), failure);
            if (failure.equals("space")) assertFalse(world.objectReads.contains(world.b.pos));
        }
    }

    @Test void staleInstanceDetachCannotRemoveTheEqualIdReplacementFromItsNewCohort() {
        var world = new Fixture(); world.persist(); world.tick(world.a, 0);
        var replacement = world.replace(world.b);
        assertEquals(world.b, replacement); assertNotSame(world.b, replacement);
        world.tick(replacement, 1); assertEquals(1, world.refreshes.size());
        world.b.state.detach(world.b);
        world.tick(replacement, 20); world.tick(world.a, 20); world.tick(replacement, 21);
        assertEquals(2, world.refreshes.size()); assertSame(world.a, world.refreshes.getLast());
        assertEquals(world.a.revision, replacement.revision);
    }

    @Test void observedSplitDoesNotRescanTheRemainingOwnerButExplicitRemovalDoes() {
        var world = new Fixture(); world.tick(world.a, 0);
        world.members = List.of(world.a); world.tick(world.a, 20);
        assertTrue(world.b.state.dirty());
        world.tick(world.a, 21); assertEquals(2, world.refreshes.size(), "A completed scan already knows the exclusion");
        world.members = List.of(world.a, world.b); world.tick(world.b, 21);
        int before = world.refreshes.size();
        world.a.state.detach(world.a); world.members = List.of(world.b);
        world.tick(world.b, 22);
        assertEquals(before + 1, world.refreshes.size()); assertSame(world.b, world.refreshes.getLast());
    }

    @Test void failedRefreshFansTheExactReasonToRetainedMembersWithoutReplacingTheirRevisions() {
        var world = new Fixture(); world.persist(); world.tick(world.a, 0);
        var before = world.a.revision;
        world.answer = owner -> new Refresh<>(List.of(), false, "persistence_invalid");
        world.tick(world.a, 20);
        assertEquals("persistence_invalid", world.failures.get(world.a));
        assertEquals("persistence_invalid", world.failures.get(world.b));
        assertSame(before, world.a.revision); assertSame(before, world.b.revision);
    }

    private static UUID id(int n) { return new UUID(0, n); }
    private static ProductionLayout.Address address(BlockPos p) { return new ProductionLayout.Address(Level.OVERWORLD, null, p); }
    private static final class Host {
        UUID id; final BlockPos pos; final State<Host> state = new State<>();
        ProductionStructure.Revision revision;
        Host(int n, BlockPos pos) { id = id(n); this.pos = pos; }
        // Deliberately equal-valued identities: cohort lifecycle must still use reference identity.
        @Override public boolean equals(Object o) { return o instanceof Host h && id.equals(h.id); }
        @Override public int hashCode() { return id.hashCode(); }
    }
    private static final class Fixture implements World<Host, String> {
        final Host a = new Host(1, BlockPos.ZERO), b = new Host(2, new BlockPos(2, 0, 0));
        final Map<BlockPos, Host> objects = new HashMap<>(Map.of(a.pos, a, b.pos, b));
        final Set<BlockPos> unloaded = new HashSet<>(), foreign = new HashSet<>(), objectReads = new HashSet<>();
        final Set<ChunkPos> unloadedChunks = new HashSet<>();
        final Map<BlockPos, ProductionLayout.Address> addresses = new HashMap<>();
        final List<Host> refreshes = new ArrayList<>();
        final List<List<Host>> withdrawn = new ArrayList<>();
        final IdentityHashMap<Host, String> failures = new IdentityHashMap<>();
        List<Host> members = List.of(a, b);
        Function<Host, Refresh<Host, String>> answer;
        void tick(Host h, long time) { ProductionScanCohort.tick(h, time, 20, HOSTS, this); }
        void persist() {
            var bounds = new ProductionLayout.Bounds(-1, -1, -1, 3, 1, 1);
            var casing = new HashSet<BlockPos>();
            for (var p : BlockPos.betweenClosed(-1, -1, -1, 3, 1, 1))
                if (p.getX() == -1 || p.getX() == 3 || p.getY() == -1 || p.getY() == 1 || p.getZ() == -1 || p.getZ() == 1)
                    casing.add(p.immutable());
            var layout = new ProductionLayout(bounds, List.of(new ProductionLayout.Member(a.id, address(a.pos), null),
                new ProductionLayout.Member(b.id, address(b.pos), null)), List.of());
            var structure = new ProductionStructure(layout, casing, ProductionStructure.chunksFor(bounds));
            a.revision = b.revision = new ProductionStructure.Revision(id(10), 0, a.id, structure);
        }
        Host replace(Host old) {
            var next = new Host((int) old.id.getLeastSignificantBits(), old.pos); next.revision = old.revision;
            objects.put(next.pos, next); members = members.stream().map(h -> h == old ? next : h).toList(); return next;
        }
        public boolean isChunkLoaded(ChunkPos chunk) { return !unloadedChunks.contains(chunk); }
        public boolean isLoaded(BlockPos p) { return !unloaded.contains(p); }
        public boolean sameSpace(BlockPos origin, BlockPos p) { assertFalse(unloaded.contains(p)); return !foreign.contains(p); }
        public ProductionLayout.Address address(BlockPos p) { return addresses.getOrDefault(p, ProductionScanCohortTest.address(p)); }
        public Host loadedComputer(BlockPos p) {
            assertFalse(unloaded.contains(p)); assertFalse(foreign.contains(p)); objectReads.add(p); return objects.get(p);
        }
        public Refresh<Host, String> refresh(Host owner) {
            refreshes.add(owner);
            return answer != null ? answer.apply(owner) : new Refresh<>(members,
                members.stream().min(Comparator.comparing(h -> h.id)).orElseThrow() == owner, null);
        }
        public void withdrawPartial(List<Host> members) { withdrawn.add(List.copyOf(members)); }
        public void fanFailure(List<Host> members, String reason) { members.forEach(h -> failures.put(h, reason)); }
    }
}
