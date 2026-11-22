package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipeLibraryMaintenanceTest {
    private static final BlockPos CORE = BlockPos.ZERO, SHELF = CORE.east();
    private static final String RAW = """
        {"version":2,"type":"standard","inputs":[],"outputs":[{"item":"minecraft:paper","count":1}],"address":"maintenance"}
        """;
    @BeforeAll static void bootstrap() throws ReflectiveOperationException { RecipePageIndexTest.bootstrap(); }

    @Test void standaloneOwnerAdmitsPhysicalPagesAndAdvancesOnlyWithinBudget() {
        var maintenance = new RecipeLibraryMaintenance();
        assertTrue(maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address));
        assertTrue(maintenance.ready());
        assertEquals(LibraryStructureScanner.State.VALID, maintenance.state());
        assertEquals(100, maintenance.index().pageOrder().size());
        var reads = new AtomicInteger();
        RecipePageIndex.PageReader pages = key -> { reads.incrementAndGet(); return key.page() == 0 ? RAW : ""; };
        assertTrue(maintenance.advance(true, pos -> true, pos -> true, pages, new PatternJsonParser(RegistryAccess.EMPTY), 7));
        assertEquals(7, reads.get());
        assertEquals(7, maintenance.index().fingerprintCursor());
        assertFalse(maintenance.index().indexPassComplete());
        assertTrue(maintenance.index().completeRecords().isEmpty());
        assertTrue(maintenance.advance(true, pos -> true, pos -> true, pages, new PatternJsonParser(RegistryAccess.EMPTY), 93));
        assertEquals(100, reads.get());
        assertTrue(maintenance.index().indexPassComplete());
        assertEquals(1, maintenance.index().snapshot().records().size());
        assertEquals(new PatternPageKey(address(SHELF), 0, 0), maintenance.index().snapshot().records().getFirst().source());
    }

    @Test void rejectedSourcePreparationLeavesAdmissionUnchanged() {
        var maintenance = new RecipeLibraryMaintenance();
        var saved = maintenance.index().save(RegistryAccess.EMPTY);
        assertThrows(IllegalStateException.class, () -> maintenance.apply(layout(), Map.of(SHELF, 1), 10, true,
            pos -> { throw new IllegalStateException("source unavailable"); }));
        assertEquals(LibraryStructureScanner.State.UNFORMED, maintenance.state());
        assertNull(maintenance.layout());
        assertFalse(maintenance.ready());
        assertEquals(0, maintenance.efficiencyBonus());
        assertEquals(saved, maintenance.index().save(RegistryAccess.EMPTY));
    }

    @Test void metadataChangesDoNotRestartTheSamePageTopology() {
        var maintenance = new RecipeLibraryMaintenance();
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        var generation = maintenance.index().generation();
        var ordinary = CORE.south();
        var expanded = new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID,
            new LibraryStructureScanner.Snapshot(List.of(CORE, SHELF, ordinary), List.of(ordinary), List.of(SHELF), CORE, SHELF.south()));
        maintenance.apply(expanded, Map.of(SHELF, 1), 17, true, pos -> fail("Same slots need no new spatial witness"));
        maintenance.updateBudget(1, 20);
        assertEquals(20, maintenance.searchBudget());
        assertEquals(2, maintenance.queueCount());
        assertEquals(generation, maintenance.index().generation());
        assertFalse(maintenance.apply(expanded, Map.of(SHELF, 1), 17, true, RecipeLibraryMaintenanceTest::address));
    }

    @Test void unavailableSourcesRetainHistoryWithoutReadingOrAdvancingUntilRecovery() {
        var maintenance = new RecipeLibraryMaintenance();
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        var parser = new PatternJsonParser(RegistryAccess.EMPTY);
        maintenance.advance(true, pos -> true, pos -> true, key -> RAW, parser, 2);
        var before = maintenance.index().save(RegistryAccess.EMPTY);
        var priorLayout = maintenance.layout();
        assertFalse(maintenance.advance(true, pos -> !pos.equals(SHELF), pos -> fail("Unloaded source must not be read"),
            key -> fail("No page reads"), parser, 99));
        assertEquals(LibraryStructureScanner.State.PARTIAL, maintenance.state());
        assertSame(priorLayout, maintenance.layout());
        assertEquals(before, maintenance.index().save(RegistryAccess.EMPTY));
        assertFalse(maintenance.advance(true, pos -> true, pos -> true, key -> fail("Need physical revalidation"), parser, 99));
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        assertTrue(maintenance.ready());
        assertFalse(maintenance.advance(true, pos -> true, pos -> false, key -> fail("Unreadable is not an empty page"), parser, 99));
        assertTrue(maintenance.topologyRefreshPending());
        assertEquals(before, maintenance.index().save(RegistryAccess.EMPTY));
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        assertFalse(maintenance.topologyRefreshPending());
        assertEquals(before, maintenance.index().save(RegistryAccess.EMPTY));
    }

    @Test void identityAdmissionDefersTopologyAndCannotBeBypassedByAdvance() {
        var maintenance = new RecipeLibraryMaintenance();
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, false, RecipeLibraryMaintenanceTest::address);
        assertFalse(maintenance.ready());
        assertTrue(maintenance.topologyRefreshPending());
        assertTrue(maintenance.index().pageOrder().isEmpty());
        assertFalse(maintenance.advance(true, pos -> true, pos -> true, key -> fail("Deferred topology"), new PatternJsonParser(RegistryAccess.EMPTY), 99));
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        assertTrue(maintenance.ready());
        assertFalse(maintenance.advance(false, pos -> fail("Identity denied before world lookup"), pos -> true,
            key -> fail("Denied reader"), new PatternJsonParser(RegistryAccess.EMPTY), 99));
        assertEquals(0, maintenance.index().fingerprintCursor());
    }

    @Test void restoreAdoptsTheSingleDecodedIndexAndPendingPhysicalWitness() {
        var maintenance = new RecipeLibraryMaintenance();
        maintenance.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        maintenance.advance(true, pos -> true, pos -> true, key -> "", new PatternJsonParser(RegistryAccess.EMPTY), 5);
        var loaded = RecipePageIndex.load(maintenance.index().save(RegistryAccess.EMPTY), RegistryAccess.EMPTY).index();
        var restored = new RecipeLibraryMaintenance();
        restored.restore(loaded, maintenance.state(), maintenance.layout(), true, maintenance.topologies(), true);
        assertSame(loaded, restored.index());
        assertFalse(restored.ready());
        restored.apply(layout(), Map.of(SHELF, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        assertTrue(restored.ready());
        assertEquals(5, restored.index().fingerprintCursor());
        assertEquals(maintenance.index().generation(), restored.index().generation());
    }

    @Test void topologyOwnsImmutablePositionsRatherThanCallerMutableKeys() {
        var maintenance = new RecipeLibraryMaintenance();
        var position = new BlockPos.MutableBlockPos(SHELF.getX(), SHELF.getY(), SHELF.getZ());
        maintenance.apply(layout(), Map.of(position, 1), 0, true, RecipeLibraryMaintenanceTest::address);
        position.set(99, 99, 99);
        assertEquals(Map.of(SHELF, 1), maintenance.topologies());
        position.set(SHELF);
        maintenance.restore(maintenance.index(), maintenance.state(), maintenance.layout(), true, Map.of(position, 1), true);
        position.set(99, 99, 99);
        assertEquals(Map.of(SHELF, 1), maintenance.topologies());
    }

    private static RecipeSourceLocation address(BlockPos pos) { return new RecipeSourceLocation(Level.OVERWORLD, null, pos); }
    private static LibraryStructureScanner.ScanResult layout() {
        return new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID,
            new LibraryStructureScanner.Snapshot(List.of(CORE, SHELF), List.of(), List.of(SHELF), CORE, SHELF));
    }
}
