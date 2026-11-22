package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Kind.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Status.*;
import java.time.Duration;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class EnclosureScannerTest {
    static final EnclosureLimits LIMITS = new EnclosureLimits(3, 7, 32);
    static final BlockPos CONTROLLER = new BlockPos(0, 1, 1);
    static final BlockPos COMPUTER = new BlockPos(1, 1, 1);
    static final ClusterEnclosure.Bounds SMALL = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(2, 3, 2));

    @Test void embeddedControllerCompletesEveryFaceWithoutInternalCables() {
        for (var facing : Direction.values()) {
            var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(4, 5, 6));
            var controller = switch (facing) {
                case WEST -> new BlockPos(0, 2, 3); case EAST -> new BlockPos(4, 2, 3);
                case DOWN -> new BlockPos(2, 0, 3); case UP -> new BlockPos(2, 5, 3);
                case NORTH -> new BlockPos(2, 2, 0); case SOUTH -> new BlockPos(2, 2, 6);
            };
            var world = new World(bounds, controller, facing); world.computer(COMPUTER);
            var result = EnclosureScanner.scan(world, controller, LIMITS);
            assertEquals(READY, result.status(), facing.toString());
            var structure = result.enclosure().orElseThrow();
            assertEquals(bounds, structure.bounds()); assertEquals(controller, structure.controller());
            assertEquals(facing, structure.outward()); assertEquals(1, structure.contacts().size());
            assertFalse(structure.contacts().get(COMPUTER).isEmpty());
            assertEquals(Set.of(COMPUTER), structure.members());
            assertTrue(structure.containsMember(COMPUTER));
            assertThrows(UnsupportedOperationException.class, () -> structure.members().clear());
            assertTrue(structure.shell().contains(controller));
            assertTrue(world.reads.values().stream().allMatch(n -> n == 1));
            assertTrue(world.reads.size() <= 13 * 13 * 13);
        }
    }

    @Test void removingFaceEdgeOrCornerInvalidatesExactRecheckAndRediscovery() {
        for (var missing : List.of(new BlockPos(2, 1, 1), new BlockPos(0, 0, 1), BlockPos.ZERO)) {
            var world = small(); world.cells.remove(missing);
            assertEquals(OPEN_SHELL, inspect(world).status());
            assertEquals(OPEN_SHELL, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        }
    }

    @Test void upperContactCountsOnceAndFloatingComputerRemainsVisibleButNotEligible() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(6, 6, 6));
        var world = new World(bounds, new BlockPos(0, 3, 3), Direction.WEST);
        var floating = new BlockPos(3, 2, 3); var top = new BlockPos(3, 4, 3);
        world.computer(floating); world.computer(top);
        var result = EnclosureScanner.scan(world, world.controller, LIMITS).enclosure().orElseThrow();
        assertEquals(Set.of(floating, top), result.contacts().keySet());
        assertTrue(result.contacts().get(floating).isEmpty());
        assertFalse(result.containsMember(floating));
        assertEquals(Set.of(top), result.members());
        assertEquals(Set.of(new ClusterEnclosure.Contact(top.above(), Direction.UP)), result.contacts().get(top));
    }

    @Test void onlyCasingConnectedToShellProvidesAccessNotAnIsolatedCasingCube() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(6, 6, 6));
        var world = new World(bounds, new BlockPos(0, 3, 3), Direction.WEST);
        var computer = new BlockPos(3, 2, 3); world.computer(computer);
        world.put(new BlockPos(3, 2, 2), CASING);
        assertTrue(EnclosureScanner.scan(world, world.controller, LIMITS).enclosure().orElseThrow().contacts().get(computer).isEmpty());
        world.put(new BlockPos(3, 2, 1), CASING);
        assertEquals(Set.of(new ClusterEnclosure.Contact(computer, Direction.NORTH)),
            EnclosureScanner.scan(world, world.controller, LIMITS).enclosure().orElseThrow().contacts().get(computer));
    }

    @Test void unloadedAndDifferentSpaceNeverCallCellGetterOrMasqueradeAsAir() {
        var world = small(); world.unloaded.add(COMPUTER);
        assertEquals(PARTIAL_UNLOADED, inspect(world).status());
        assertEquals(PARTIAL_UNLOADED, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        world.unloaded.clear(); world.foreign.add(COMPUTER);
        assertEquals(WRONG_SPACE, inspect(world).status());
        assertEquals(WRONG_SPACE, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        world.foreign.clear(); world.unloaded.add(new BlockPos(-1, 1, 1));
        assertEquals(READY, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
    }

    @Test void missingOrInwardControllerAndSecondControllerAreRejected() {
        var world = small(); world.cells.remove(CONTROLLER);
        assertEquals(NOT_CONTROLLER, inspect(world).status());
        world.cells.put(CONTROLLER, new EnclosureScanner.Cell(CONTROLLER_BLOCK, Direction.EAST));
        assertEquals(INVALID_CONTROLLER, inspect(world).status());
        assertNotEquals(READY, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        world = small(); world.cells.put(new BlockPos(2, 1, 1), new EnclosureScanner.Cell(CONTROLLER_BLOCK, Direction.EAST));
        assertEquals(CONTROLLER_CONFLICT, inspect(world).status());
        assertEquals(CONTROLLER_CONFLICT, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
    }

    @Test void cornerOrEdgeControllerCannotChooseAnArbitraryOutsideFace() {
        var world = new World(SMALL, BlockPos.ZERO, Direction.WEST);
        assertEquals(INVALID_CONTROLLER, EnclosureScanner.inspect(world, SMALL, BlockPos.ZERO, Direction.WEST, LIMITS).status());
        assertNotEquals(READY, EnclosureScanner.scan(world, BlockPos.ZERO, LIMITS).status());
    }

    @Test void invalidHalfOrFacingCannotBecomeAConfigurableComputer() {
        for (var replacement : List.of(new EnclosureScanner.Cell(AIR, null),
                new EnclosureScanner.Cell(COMPUTER_UPPER, Direction.SOUTH))) {
            var world = small(); world.cells.put(COMPUTER.above(), replacement);
            assertEquals(READY, inspect(world).status());
            assertTrue(inspect(world).enclosure().orElseThrow().contacts().isEmpty());
        }
        var world = small(); world.cells.remove(COMPUTER);
        assertEquals(READY, inspect(world).status());
        assertTrue(inspect(world).enclosure().orElseThrow().contacts().isEmpty());
    }

    @Test void internalContentsAreNotShellAndInvalidObservationsStillFail() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(3, 3, 3));
        var world = new World(bounds, CONTROLLER, Direction.WEST); world.computer(COMPUTER);
        world.put(new BlockPos(2, 1, 2), INTERNAL);
        assertEquals(READY, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        world.put(new BlockPos(2, 1, 2), ILLEGAL);
        assertEquals(ILLEGAL_INTERNAL, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        world = small(); world.put(BlockPos.ZERO, INTERNAL);
        assertEquals(OPEN_SHELL, inspect(world).status());
    }

    @Test void computerAndSizeLimitsDoNotReturnAPartialMemberList() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(3, 3, 3));
        var world = new World(bounds, CONTROLLER, Direction.WEST);
        world.computer(COMPUTER); world.computer(new BlockPos(2, 1, 2));
        var result = EnclosureScanner.scan(world, CONTROLLER, new EnclosureLimits(3, 7, 1));
        assertEquals(READY, result.status());
        assertEquals(2, result.enclosure().orElseThrow().contacts().size(), "Geometry must not truncate members to a routing budget");
        assertEquals(SIZE, EnclosureScanner.inspect(world, bounds, CONTROLLER, Direction.WEST, new EnclosureLimits(3, 3, 32)).status());
    }

    @Test void multipleCompleteCandidatesDoNotSilentlySelectOne() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(3, 3, 3));
        var world = new World(bounds, CONTROLLER, Direction.WEST);
        for (int x = 0; x <= 3; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 3; z++)
            world.put(new BlockPos(x, y, z), CASING);
        world.cells.put(CONTROLLER, new EnclosureScanner.Cell(CONTROLLER_BLOCK, Direction.WEST));
        var result = EnclosureScanner.scan(world, CONTROLLER, LIMITS);
        assertEquals(AMBIGUOUS, result.status()); assertTrue(result.enclosure().isEmpty());
    }

    @Test void airFilledRectangularInteriorAndAnUnusedInternalControllerDoNotBreakTheOuterShell() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(4, 5, 6));
        var world = new World(bounds, CONTROLLER, Direction.WEST);
        assertEquals(READY, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        world.computer(COMPUTER);
        world.cells.put(new BlockPos(2, 2, 3), new EnclosureScanner.Cell(CONTROLLER_BLOCK, Direction.NORTH));
        var result = EnclosureScanner.scan(world, CONTROLLER, LIMITS);
        assertEquals(READY, result.status());
        assertEquals(Set.of(COMPUTER), result.enclosure().orElseThrow().contacts().keySet());
        world.cells.remove(new BlockPos(4, 2, 3));
        assertEquals(OPEN_SHELL, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
    }

    @Test void coordinateOverflowFailsBeforeReadingWrappedPositions() {
        var world = small(); var edge = new BlockPos(Integer.MIN_VALUE, 1, 1);
        world.cells.put(edge, new EnclosureScanner.Cell(CONTROLLER_BLOCK, Direction.WEST));
        var result = EnclosureScanner.scan(world, edge, LIMITS);
        assertEquals(COORDINATE_RANGE, result.status()); assertTrue(world.reads.size() <= 1);
    }

    @Test void plausibleUnloadedAlternativePreventsPrematureFormationButKnownHolesDoNot() {
        var world = small();
        for (int y = 0; y <= 3; y++) for (int z = 0; z <= 2; z++) world.unloaded.add(new BlockPos(3, y, z));
        var result = EnclosureScanner.scan(world, CONTROLLER, LIMITS);
        assertEquals(PARTIAL_UNLOADED, result.status()); assertTrue(result.enclosure().isEmpty());
        world.unloaded.clear(); // The alternative's entire end face is now known air.
        assertEquals(READY, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
        for (int y = 0; y <= 3; y++) for (int z = 0; z <= 2; z++) world.put(new BlockPos(3, y, z), CASING);
        assertEquals(AMBIGUOUS, EnclosureScanner.scan(world, CONTROLLER, LIMITS).status());
    }

    @Test void maximumWindowHasBoundedReadsAndSearchWork() {
        var bounds = new ClusterEnclosure.Bounds(BlockPos.ZERO, new BlockPos(15, 15, 15));
        var world = new World(bounds, new BlockPos(0, 7, 7), Direction.WEST); world.computer(COMPUTER);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
            assertEquals(READY, EnclosureScanner.scan(world, world.controller, new EnclosureLimits(3, 16, 32)).status()));
        assertTrue(world.reads.size() <= 31 * 31 * 31);
        assertTrue(world.reads.values().stream().allMatch(n -> n == 1));
    }

    @Test void snapshotDoesNotChangeWhenWorldOrMutableInputsChange() {
        var world = small(); var input = new BlockPos.MutableBlockPos(0, 1, 1);
        var snapshot = EnclosureScanner.scan(world, input, LIMITS).enclosure().orElseThrow();
        input.set(99, 99, 99); world.cells.clear();
        assertEquals(CONTROLLER, snapshot.controller()); assertEquals(34, snapshot.shell().size());
        assertEquals(Set.of(COMPUTER), snapshot.contacts().keySet());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.contacts().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.contacts().get(COMPUTER).clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.shell().clear());
    }

    private static EnclosureScanner.Result inspect(World world) {
        return EnclosureScanner.inspect(world, SMALL, CONTROLLER, Direction.WEST, LIMITS);
    }
    private static World small() { var world = new World(SMALL, CONTROLLER, Direction.WEST); world.computer(COMPUTER); return world; }

    static final class World implements EnclosureScanner.View {
        final Map<BlockPos, EnclosureScanner.Cell> cells = new HashMap<>();
        final Set<BlockPos> unloaded = new HashSet<>(), foreign = new HashSet<>();
        final Map<BlockPos, Integer> reads = new HashMap<>();
        final BlockPos controller;
        World(ClusterEnclosure.Bounds bounds, BlockPos controller, Direction outward) {
            this.controller = controller;
            for (int x = bounds.min().getX(); x <= bounds.max().getX(); x++)
                for (int y = bounds.min().getY(); y <= bounds.max().getY(); y++)
                    for (int z = bounds.min().getZ(); z <= bounds.max().getZ(); z++)
                        if (x == bounds.min().getX() || x == bounds.max().getX() || y == bounds.min().getY()
                            || y == bounds.max().getY() || z == bounds.min().getZ() || z == bounds.max().getZ()) put(new BlockPos(x, y, z), CASING);
            cells.put(controller, new EnclosureScanner.Cell(CONTROLLER_BLOCK, outward));
        }
        void put(BlockPos pos, EnclosureScanner.Kind kind) { cells.put(pos, new EnclosureScanner.Cell(kind, null)); }
        void computer(BlockPos base) {
            cells.put(base, new EnclosureScanner.Cell(COMPUTER_LOWER, Direction.NORTH));
            cells.put(base.above(), new EnclosureScanner.Cell(COMPUTER_UPPER, Direction.NORTH));
        }
        public boolean loaded(BlockPos pos) { return !unloaded.contains(pos); }
        public boolean sameSpace(BlockPos origin, BlockPos pos) { return !foreign.contains(pos); }
        public EnclosureScanner.Cell cell(BlockPos pos) {
            if (unloaded.contains(pos) || foreign.contains(pos)) throw new AssertionError("unsafe world read: " + pos);
            reads.merge(pos.immutable(), 1, Integer::sum);
            return cells.getOrDefault(pos, new EnclosureScanner.Cell(AIR, null));
        }
    }
}
