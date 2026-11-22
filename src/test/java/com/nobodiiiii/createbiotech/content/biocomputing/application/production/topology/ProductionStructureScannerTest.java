package com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructureScanner.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class ProductionStructureScannerTest {
    private static final BlockPos SEED = BlockPos.ZERO;
    private static final UUID STRUCTURE = new UUID(9, 1);
    private static final Limits LIMITS = Limits.fromValues(3, 7, 32);
    private static final Bounds SMALL = new Bounds(-1, -1, -1, 1, 1, 1);
    private static final ProductionResourceProfile LIBRARIAN = profile(ProductionProfession.LIBRARIAN, 1);

    @Test void preservesEachMemberProfileAndEmptyReadinessWithoutLegacyValues() {
        var box = new Bounds(-1, -1, -1, 5, 1, 1);
        var second = new BlockPos(2, 0, 0); var third = new BlockPos(4, 0, 0);
        var fletcher = profile(ProductionProfession.FLETCHER, 2);
        var world = new Fixture().seal(box).computer(SEED, 3, LIBRARIAN).computer(second, 1, fletcher).computer(third, 2, null);
        var result = scan(world, SEED, LIMITS);
        assertEquals(State.VALID_NOT_READY, result.state()); assertEquals(STRUCTURE, result.observedStructureMemberId());
        assertEquals(List.of(new Member(id(1), address(second), fletcher), new Member(id(2), address(third), null),
            new Member(id(3), address(SEED), LIBRARIAN)), result.structure().layout().members());
        assertEquals(box, result.structure().layout().bounds());
        assertEquals(ProductionStructure.chunksFor(box), result.structure().containingChunks());
        assertEquals(result.structure(), ProductionStructureCodec.loadSnapshot(ProductionStructureCodec.saveSnapshot(result.structure())).orElseThrow());
        world.computer(third, 2, profile(ProductionProfession.CLERIC, 4));
        var ready = scan(world, SEED, LIMITS);
        assertEquals(State.VALID, ready.state());
        assertNull(result.structure().layout().members().get(1).profile(), "Later observations cannot mutate the first result");
        assertEquals(fletcher, ready.structure().layout().members().getFirst().profile());
    }

    @Test void airIsAllowedButMissingFacesEdgesCornersAndIllegalInteriorsAreNot() {
        for (var hole : List.of(new BlockPos(-1, 0, 0), new BlockPos(-1, -1, 0), new BlockPos(-1, -1, -1))) {
            var world = basic(); world.cells.remove(hole);
            assertEquals(State.OPEN_SHELL, scan(world, SEED, LIMITS).state());
        }
        var world = new Fixture().seal(new Bounds(-1, -1, -1, 3, 1, 1)).computer(SEED, 1, LIBRARIAN);
        assertEquals(State.VALID, scan(world, SEED, LIMITS).state());
        world.cells.put(new BlockPos(1, 0, 0), CellKind.ILLEGAL);
        assertEquals(State.ILLEGAL_INTERNAL, scan(world, SEED, LIMITS).state());
    }

    @Test void contactRequiresCasingConnectedToTheOuterShell() {
        var world = new Fixture().seal(new Bounds(-3, -3, -3, 3, 3, 3)).computer(SEED, 1, LIBRARIAN);
        world.cells.put(new BlockPos(1, 0, 0), CellKind.CASING);
        assertEquals(State.DISCONNECTED_BUS, scan(world, SEED, LIMITS).state());
        world.cells.put(new BlockPos(2, 0, 0), CellKind.CASING);
        var result = scan(world, SEED, LIMITS);
        assertEquals(State.VALID, result.state());
        assertTrue(result.structure().casingPositions().contains(new BlockPos(1, 0, 0)));
    }

    @Test void unknownAndForeignCellsAreNotReadAndDoNotBecomeZeroOrAir() {
        for (boolean foreign : List.of(false, true)) {
            var world = basic(); var position = new BlockPos(-1, 0, 0);
            (foreign ? world.foreign : world.unloaded).add(position);
            var result = scan(world, SEED, LIMITS);
            assertEquals(foreign ? State.SPACE : State.PARTIAL_UNLOADED, result.state());
            assertNull(result.structure()); assertFalse(world.cellReads.containsKey(position));
            assertTrue(world.cellReads.size() <= LIMITS.observationWindowVolume());
            assertTrue(world.cellReads.values().stream().allMatch(count -> count == 1));
        }
    }

    @Test void anEmbeddedPanelUsesTheSameImmutableStructureAndInvalidIdentityIsRejected() {
        var world = basic(); var pos = new BlockPos(-1, 0, 0);
        world.panels.put(pos, new PanelObservation(id(50), address(pos), Direction.WEST, ComputerShellMaterial.TRANSPARENT));
        var result = scan(world, SEED, LIMITS);
        assertEquals(State.VALID, result.state());
        assertEquals(List.of(new Panel(id(50), address(pos), Direction.WEST, ComputerShellMaterial.TRANSPARENT)),
            result.structure().layout().panels());
        assertTrue(result.structure().casingPositions().contains(pos));
        world.panels.put(pos, new PanelObservation(null, address(pos), Direction.WEST, ComputerShellMaterial.TRANSPARENT));
        assertEquals(State.IDENTITY_INVALID, scan(world, SEED, LIMITS).state());
    }

    @Test void duplicateIdentityOrConflictingStructureCannotSilentlyChooseAMember() {
        var second = new BlockPos(2, 0, 0);
        var world = new Fixture().seal(new Bounds(-1, -1, -1, 3, 1, 1)).computer(SEED, 1, LIBRARIAN).computer(second, 1, LIBRARIAN);
        assertEquals(State.IDENTITY_INVALID, scan(world, SEED, LIMITS).state());
        world.computers.put(second, new ComputerObservation(id(2), id(99), ProfileLoadState.VALID, LIBRARIAN, address(second)));
        assertEquals(State.STRUCTURE_IDENTITY_CONFLICT, scan(world, SEED, LIMITS).state());
        world.computers.put(second, new ComputerObservation(id(2), STRUCTURE, ProfileLoadState.CORRUPT, null, address(second)));
        assertEquals(State.IDENTITY_INVALID, scan(world, SEED, LIMITS).state());
    }

    @Test void nestedShellsAreAmbiguousAndLimitsRemainExplicitAndBounded() {
        var world = basic().seal(new Bounds(-2, -2, -2, 2, 2, 2));
        var result = scan(world, SEED, LIMITS);
        assertEquals(State.AMBIGUOUS, result.state()); assertNull(result.structure());
        assertEquals(new Limits(3, 16, 4096, 256), Limits.fromValues(-1, 90, 900));
        assertEquals(29 * 29 * 29, Limits.fromValues(3, 16, 32).observationWindowVolume());
        assertThrows(IllegalArgumentException.class, () -> new Limits(3, 7, 342, 32));
        assertTrue(world.cellReads.size() <= LIMITS.observationWindowVolume());
    }

    private static Fixture basic() { return new Fixture().seal(SMALL).computer(SEED, 1, LIBRARIAN); }
    private static UUID id(int n) { return new UUID(0, n); }
    private static Address address(BlockPos p) { return new Address(Level.OVERWORLD, null, p); }
    private static ProductionResourceProfile profile(ProductionProfession p, int level) {
        return ProductionResourceProfile.forProfession(p, level, 64).orElseThrow();
    }
    private static final class Fixture implements View {
        final Map<BlockPos, CellKind> cells = new HashMap<>();
        final Map<BlockPos, ComputerObservation> computers = new HashMap<>();
        final Map<BlockPos, PanelObservation> panels = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>(), foreign = new HashSet<>();
        final Map<BlockPos, Integer> cellReads = new HashMap<>();
        Fixture seal(Bounds b) {
            for (var p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))
                if (p.getX() == b.minX() || p.getX() == b.maxX() || p.getY() == b.minY() || p.getY() == b.maxY()
                    || p.getZ() == b.minZ() || p.getZ() == b.maxZ()) cells.put(p.immutable(), CellKind.CASING);
            return this;
        }
        Fixture computer(BlockPos p, int n, ProductionResourceProfile profile) {
            cells.put(p.immutable(), CellKind.COMPUTER);
            computers.put(p.immutable(), new ComputerObservation(id(n), STRUCTURE,
                profile == null ? ProfileLoadState.EMPTY : ProfileLoadState.VALID, profile, address(p)));
            return this;
        }
        public boolean isLoaded(BlockPos p) { return !unloaded.contains(p); }
        public boolean sameSpace(BlockPos origin, BlockPos p) { assertFalse(unloaded.contains(p)); return !foreign.contains(p); }
        private void readable(BlockPos p) { assertFalse(unloaded.contains(p)); assertFalse(foreign.contains(p)); }
        public CellKind cellAt(BlockPos p) { readable(p); cellReads.merge(p.immutable(), 1, Integer::sum); return cells.getOrDefault(p, CellKind.AIR); }
        public ComputerObservation computerAt(BlockPos p) { readable(p); return computers.get(p); }
        public PanelObservation panelAt(BlockPos p) { readable(p); return panels.get(p); }
    }
}
