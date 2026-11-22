package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionDomainProof.*;
import java.time.Duration;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionDomainProofTest {
    private static final Bounds BOX=new Bounds(0,0,0,2,2,2);
    private static final BlockPos MEMBER=new BlockPos(1,1,1);

    @Test void domainsCopyCoordinatesAndCollectionsAndDeduplicateOverlaps() {
        var mutable=new BlockPos.MutableBlockPos(5,0,0);
        var bounds=new ArrayList<>(List.of(BOX,new Bounds(1,0,0,3,2,2)));
        var anchors=new ArrayList<>(List.of(address(BlockPos.ZERO),address(mutable)));
        var domain=new Domain(bounds,anchors); bounds.clear(); anchors.clear(); mutable.set(90,90,90);
        var positions=positions(domain);
        assertEquals(37,positions.size()); assertEquals(BlockPos.ZERO,positions.getFirst());
        assertEquals(new BlockPos(5,0,0),positions.getLast());
        assertThrows(UnsupportedOperationException.class,() -> positions.clear());
        assertThrows(UnsupportedOperationException.class,() -> domain.bounds().clear());
        assertThrows(UnsupportedOperationException.class,() -> domain.anchors().clear());
    }

    @Test void failedLoadingOrSpaceProofNeverReadsComputerObjects() {
        var domain=domain();
        for (String failure : List.of("chunk","block","space","dimension","sublevel")) {
            var world=new Fixture();
            switch (failure) {
                case "chunk" -> world.unloadedChunks.add(new ChunkPos(0,0));
                case "block" -> world.unloaded.add(MEMBER);
                case "space" -> world.wrongSpace.add(MEMBER);
                case "dimension" -> world.addresses.put(MEMBER,new Address(Level.NETHER,null,MEMBER));
                case "sublevel" -> world.addresses.put(MEMBER,new Address(Level.OVERWORLD,UUID.randomUUID(),MEMBER));
            }
            var captured=capture(BlockPos.ZERO,domain,world);
            assertEquals(failure.equals("chunk") || failure.equals("block") ? Failure.PARTIAL : Failure.SPACE,captured.failure());
            assertTrue(captured.cells().isEmpty()); assertEquals(0,world.reads);
            assertFalse(revalidate(captured,BlockPos.ZERO,domain,new Fixture()));
        }
    }

    @Test void captureRetainsEmptyCellsAndImmutableStateWitnesses() {
        var world=new Fixture(); var host=world.put(MEMBER,1); var captured=capture(BlockPos.ZERO,domain(),world);
        assertEquals(Failure.NONE,captured.failure()); assertEquals(27,captured.cells().size());
        assertEquals(26,captured.cells().stream().filter(c -> c.computer()==null).count());
        var cell=captured.cells().stream().filter(c -> c.computer()!=null).findFirst().orElseThrow();
        assertSame(host,cell.computer()); assertEquals(new State(0,"librarian"),cell.state());
        world.states.put(host,new State(1,"fletcher"));
        assertEquals(new State(0,"librarian"),cell.state());
        assertThrows(UnsupportedOperationException.class,() -> captured.cells().clear());
    }

    @Test void revalidationRejectsSameValueReplacementRemovalAndPreviouslyEmptyCellInsertion() {
        for (String change : List.of("replacement","removal","insertion")) {
            var world=new Fixture(); var original=world.put(MEMBER,1); var captured=capture(BlockPos.ZERO,domain(),world);
            assertTrue(revalidate(captured,BlockPos.ZERO,domain(),world));
            switch (change) {
                case "replacement" -> {
                    var replacement=world.put(MEMBER,1);
                    assertEquals(original,replacement); assertNotSame(original,replacement);
                }
                case "removal" -> world.computers.remove(MEMBER);
                case "insertion" -> world.put(BlockPos.ZERO,2);
            }
            assertFalse(revalidate(captured,BlockPos.ZERO,domain(),world));
        }
    }

    @Test void revalidationChecksTheExactCapturedProductionStateAndVersion() {
        for (State changed : List.of(new State(1,"librarian"),new State(0,"fletcher"))) {
            var world=new Fixture(); var host=world.put(MEMBER,1); var captured=capture(BlockPos.ZERO,domain(),world);
            world.states.put(host,changed);
            assertFalse(revalidate(captured,BlockPos.ZERO,domain(),world));
            world.states.put(host,new State(2,"librarian"));
            assertFalse(revalidate(captured,BlockPos.ZERO,domain(),world), "Restoring a value cannot undo an advanced host fence");
        }
    }

    @Test void changedDomainAndLostLoadingInvalidatePreviouslySuccessfulEvidence() {
        var world=new Fixture(); world.put(MEMBER,1); var captured=capture(BlockPos.ZERO,domain(),world);
        assertFalse(revalidate(captured,BlockPos.ZERO,new Domain(List.of(BOX),List.of()),world));
        world.unloaded.add(MEMBER); world.reads=0;
        assertFalse(revalidate(captured,BlockPos.ZERO,domain(),world)); assertEquals(0,world.reads);
        world.unloaded.clear(); world.wrongSpace.add(MEMBER);
        assertFalse(revalidate(captured,BlockPos.ZERO,domain(),world)); assertEquals(0,world.reads);
    }

    @Test void finiteBoundsAtTheAbsoluteCoordinateLimitDoNotWrap() {
        var domain=new Domain(List.of(new Bounds(Integer.MAX_VALUE-2,0,0,Integer.MAX_VALUE,2,2)),List.of());
        assertTimeoutPreemptively(Duration.ofSeconds(1),() -> {
            var positions=positions(domain); assertEquals(27,positions.size());
            assertEquals(new BlockPos(Integer.MAX_VALUE,2,2),positions.getLast());
            assertEquals(Failure.NONE,prove(positions.getFirst(),domain,new Fixture()));
        });
    }

    private static Domain domain() { return new Domain(List.of(BOX),List.of(address(MEMBER))); }
    private static Address address(BlockPos pos) { return new Address(Level.OVERWORLD,null,pos); }
    // Equal identifiers do not make two independently loaded objects the same host.
    private record Host(int id) {}
    private record State(long version,String profile) {}
    private static final class Fixture implements World<Host,State> {
        final Map<BlockPos,Host> computers=new HashMap<>();
        final IdentityHashMap<Host,State> states=new IdentityHashMap<>();
        final Set<BlockPos> unloaded=new HashSet<>(),wrongSpace=new HashSet<>();
        final Set<ChunkPos> unloadedChunks=new HashSet<>();
        final Map<BlockPos,Address> addresses=new HashMap<>();
        int reads;
        Host put(BlockPos pos,int id) { var host=new Host(id); computers.put(pos,host); states.put(host,new State(0,"librarian")); return host; }
        public boolean isChunkLoaded(ChunkPos chunk) { return !unloadedChunks.contains(chunk); }
        public boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
        public boolean sameSpace(BlockPos seed,BlockPos pos) { return !wrongSpace.contains(pos); }
        public Address address(BlockPos pos) { return addresses.getOrDefault(pos,ProductionDomainProofTest.address(pos)); }
        public Host loadedComputer(BlockPos pos) {
            assertFalse(unloaded.contains(pos)); assertFalse(wrongSpace.contains(pos)); reads++; return computers.get(pos);
        }
        public State state(Host host) { return Objects.requireNonNull(states.get(host)); }
        public boolean matches(Host host,State expected) { return expected.equals(states.get(host)); }
    }
}
