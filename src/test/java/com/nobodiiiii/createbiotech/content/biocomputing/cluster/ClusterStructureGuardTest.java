package com.nobodiiiii.createbiotech.content.biocomputing.cluster;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.cluster.EnclosureScanner.Status.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class ClusterStructureGuardTest {
    @Test void cachedStructureObservesMissingCornerAndItsRepairWithinTheSameWorldTick() {
        var world = new EnclosureScannerTest.World(EnclosureScannerTest.SMALL, EnclosureScannerTest.CONTROLLER, Direction.WEST);
        world.computer(EnclosureScannerTest.COMPUTER); var guard = new ClusterStructureGuard();
        var result = guard.current(world, world.controller, EnclosureScannerTest.LIMITS);
        assertEquals(READY, result.status()); assertSame(result, guard.current(world, world.controller, EnclosureScannerTest.LIMITS));
        world.cells.remove(BlockPos.ZERO);
        assertEquals(OPEN_SHELL, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
        world.put(BlockPos.ZERO, EnclosureScanner.Kind.CASING);
        assertEquals(READY, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
    }
    @Test void checksOutsidePreviousBoundsForAlternativeShellAndUnknownChunks() {
        var world = new EnclosureScannerTest.World(EnclosureScannerTest.SMALL, EnclosureScannerTest.CONTROLLER, Direction.WEST);
        world.computer(EnclosureScannerTest.COMPUTER); var guard = new ClusterStructureGuard();
        assertEquals(READY, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
        for (int y = 0; y <= 3; y++) for (int z = 0; z <= 2; z++) world.unloaded.add(new BlockPos(3, y, z));
        assertEquals(PARTIAL_UNLOADED, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
        world.unloaded.clear();
        for (int y = 0; y <= 3; y++) for (int z = 0; z <= 2; z++) world.put(new BlockPos(3, y, z), EnclosureScanner.Kind.CASING);
        assertEquals(AMBIGUOUS, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
    }
    @Test void changedLimitsAndSpaceCannotReuseAnOldReadySnapshot() {
        var world = new EnclosureScannerTest.World(EnclosureScannerTest.SMALL, EnclosureScannerTest.CONTROLLER, Direction.WEST);
        world.computer(EnclosureScannerTest.COMPUTER); var guard = new ClusterStructureGuard();
        assertEquals(READY, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
        assertNotEquals(READY, guard.current(world, world.controller, new EnclosureLimits(3, 3, 32)).status());
        world.foreign.add(EnclosureScannerTest.COMPUTER);
        assertEquals(WRONG_SPACE, guard.current(world, world.controller, EnclosureScannerTest.LIMITS).status());
    }
    @Test void stableMaximumWindowFreshlyReadsEveryPositionExactlyOnce() {
        var world = new EnclosureScannerTest.World(EnclosureScannerTest.SMALL, EnclosureScannerTest.CONTROLLER, Direction.WEST);
        world.computer(EnclosureScannerTest.COMPUTER); var guard = new ClusterStructureGuard();
        var limits = new EnclosureLimits(3, 16, 32);
        var result = guard.current(world, world.controller, limits);
        assertEquals(READY, result.status());
        var positions = java.util.Set.copyOf(world.reads.keySet());
        assertEquals(31 * 31 * 31, positions.size());
        for (int attempt = 0; attempt < 2; attempt++) {
            world.reads.clear();
            assertSame(result, guard.current(world, world.controller, limits));
            assertEquals(positions, world.reads.keySet());
            assertTrue(world.reads.values().stream().allMatch(count -> count == 1));
        }
    }

    @Test void terminalProofFreshlyRejectsSameTickChangesWithoutUpdatingSnapshot() {
        var world = new EnclosureScannerTest.World(EnclosureScannerTest.SMALL, EnclosureScannerTest.CONTROLLER, Direction.WEST);
        world.computer(EnclosureScannerTest.COMPUTER); var guard = new ClusterStructureGuard();
        var result = guard.current(world, world.controller, EnclosureScannerTest.LIMITS);
        assertEquals(READY, result.status());
        var positions = java.util.Set.copyOf(world.reads.keySet());
        world.reads.clear();
        assertTrue(guard.unchanged(world, world.controller, EnclosureScannerTest.LIMITS));
        assertEquals(positions, world.reads.keySet());
        assertTrue(world.reads.values().stream().allMatch(count -> count == 1));
        world.cells.remove(BlockPos.ZERO);
        assertFalse(guard.unchanged(world, world.controller, EnclosureScannerTest.LIMITS));
        world.put(BlockPos.ZERO, EnclosureScanner.Kind.CASING);
        assertTrue(guard.unchanged(world, world.controller, EnclosureScannerTest.LIMITS));
        var outside = world.controller.offset(EnclosureScannerTest.LIMITS.maxSize() - 1, 0, 0);
        world.unloaded.add(outside); world.reads.clear();
        assertFalse(guard.unchanged(world, world.controller, EnclosureScannerTest.LIMITS));
        assertFalse(world.reads.containsKey(outside));
        world.unloaded.clear(); world.foreign.add(outside); world.reads.clear();
        assertFalse(guard.unchanged(world, world.controller, EnclosureScannerTest.LIMITS));
        assertFalse(world.reads.containsKey(outside));
        world.foreign.clear();
        assertTrue(guard.unchanged(world, world.controller, EnclosureScannerTest.LIMITS));
        assertFalse(guard.unchanged(world, world.controller, new EnclosureLimits(3, 3, 32)));
        assertSame(result, guard.current(world, world.controller, EnclosureScannerTest.LIMITS));
    }
}
