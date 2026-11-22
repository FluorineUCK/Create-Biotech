package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipeMaintenanceArchiveTest {
    private static final BlockPos CORE = BlockPos.ZERO, SHELF = CORE.east();
    private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
    @BeforeAll static void bootstrap() throws ReflectiveOperationException { RecipePageIndexTest.bootstrap(); }

    @Test void independentArchiveRestoresTheSingleIndexAndItsExactBudgetContinuation() {
        var owner = owner();
        var saved = RecipeMaintenanceArchive.save(owner, REGISTRIES);
        var decoded = RecipeMaintenanceArchive.load(saved, REGISTRIES, 32, address(CORE));
        assertTrue(decoded.isPresent(), "Independent recipe maintenance archive must be usable without a legacy host");
        var restored = new RecipeLibraryMaintenance();
        decoded.orElseThrow().commitTo(restored);
        assertEquals(owner.index().save(REGISTRIES), restored.index().save(REGISTRIES));
        assertEquals(owner.layout(), restored.layout());
        assertEquals(owner.topologies(), restored.topologies());
        assertTrue(restored.ready());
        restored.advance(true, pos -> true, pos -> true, key -> "", new PatternJsonParser(REGISTRIES), 93);
        assertTrue(restored.index().indexPassComplete());
        assertEquals(100, restored.index().fingerprintCursor());
    }

    @Test void rejectedArchiveShapeNeverChangesTheRunningOwner() {
        var owner = owner(); var before = RecipeMaintenanceArchive.save(owner, REGISTRIES);
        var wrongType = before.copy(); wrongType.putInt(RecipeMaintenanceArchive.STATE, 1);
        var extraField = before.copy(); extraField.putString("WorldWrite", "not permitted");
        var missingTopology = before.copy(); missingTopology.remove(RecipeMaintenanceArchive.TOPOLOGIES);
        var tooMany = before.copy();
        var list = tooMany.getList(RecipeMaintenanceArchive.TOPOLOGIES, Tag.TAG_COMPOUND);
        list.add(list.getCompound(0).copy());
        assertTrue(RecipeMaintenanceArchive.load(wrongType, REGISTRIES, 32, address(CORE)).isEmpty());
        assertTrue(RecipeMaintenanceArchive.load(extraField, REGISTRIES, 32, address(CORE)).isEmpty());
        assertTrue(RecipeMaintenanceArchive.load(missingTopology, REGISTRIES, 32, address(CORE)).isEmpty());
        assertTrue(RecipeMaintenanceArchive.load(tooMany, REGISTRIES, 1, address(CORE)).isEmpty());
        assertEquals(before, RecipeMaintenanceArchive.save(owner, REGISTRIES));
    }

    @Test void corruptGeometryRetainsOnlyDiagnosticHistoryAndStopsAdmission() {
        var old = owner(); var oldLayout = old.layout();
        var corrupt = RecipeMaintenanceArchive.save(old, REGISTRIES);
        corrupt.put(RecipeMaintenanceArchive.LAYOUT, new CompoundTag());
        var prepared = RecipeMaintenanceArchive.load(corrupt, REGISTRIES, 32, address(CORE)).orElseThrow();
        assertTrue(prepared.hadCorruption()); assertTrue(prepared.refreshPending()); assertFalse(prepared.replaceLayout());
        prepared.commitTo(old);
        assertSame(oldLayout, old.layout()); assertSame(prepared.index(), old.index());
        assertFalse(old.ready());
        assertFalse(old.advance(true, pos -> true, pos -> true, key -> fail("No reads from unvalidated history"), new PatternJsonParser(REGISTRIES), 100));
    }

    @Test void badSlotMasksAndDuplicatePositionsRecoverWithoutServingAFalseEmptyLibrary() {
        var owner = owner(); var archive = RecipeMaintenanceArchive.save(owner, REGISTRIES);
        for (int mask : new int[] {-1, 64}) {
            var broken = archive.copy(); broken.getList(RecipeMaintenanceArchive.TOPOLOGIES, Tag.TAG_COMPOUND).getCompound(0).putInt("WritableSlots", mask);
            var prepared = RecipeMaintenanceArchive.load(broken, REGISTRIES, 32, address(CORE)).orElseThrow();
            assertTrue(prepared.hadCorruption()); assertTrue(prepared.refreshPending()); assertTrue(prepared.topologies().isEmpty());
            assertEquals(100, prepared.index().pageOrder().size());
        }
        var duplicate = archive.copy(); var list = duplicate.getList(RecipeMaintenanceArchive.TOPOLOGIES, Tag.TAG_COMPOUND);
        list.add(list.getCompound(0).copy());
        var prepared = RecipeMaintenanceArchive.load(duplicate, REGISTRIES, 32, address(CORE)).orElseThrow();
        assertTrue(prepared.hadCorruption()); assertTrue(prepared.refreshPending());
    }

    @Test void foreignAndMissingSpatialWitnessesRequireAnActualRevalidation() {
        var archive = RecipeMaintenanceArchive.save(owner(), REGISTRIES);
        for (var space : List.of(new RecipeSourceLocation(Level.NETHER, null, CORE),
            new RecipeSourceLocation(Level.OVERWORLD, java.util.UUID.randomUUID(), CORE), address(CORE.south()))) {
            var loaded = RecipeMaintenanceArchive.load(archive, REGISTRIES, 32, space).orElseThrow();
            assertTrue(loaded.refreshPending()); assertFalse(loaded.hadCorruption());
        }
        assertTrue(RecipeMaintenanceArchive.load(archive, REGISTRIES, 32, null).orElseThrow().refreshPending());
        var empty = owner(); empty.apply(empty.layout(), Map.of(SHELF, 0), 0, true, RecipeMaintenanceArchiveTest::address);
        assertTrue(RecipeMaintenanceArchive.load(RecipeMaintenanceArchive.save(empty, REGISTRIES), REGISTRIES, 32, address(CORE)).orElseThrow().refreshPending());
    }

    @Test void preparedCandidateOwnsNbtValuesAndTheAdapterSuppliesExactlyOneIndex() {
        var original = owner(); var archive = RecipeMaintenanceArchive.save(original, REGISTRIES);
        var loadedIndex = RecipePageIndex.load(archive.getCompound(RecipeMaintenanceArchive.INDEX), REGISTRIES).index();
        var candidate = RecipeMaintenanceArchive.prepare(archive, loadedIndex, false, false, 32, address(CORE)).orElseThrow();
        assertSame(loadedIndex, candidate.index());
        archive.getCompound(RecipeMaintenanceArchive.LAYOUT).putLong("Min", CORE.south().asLong());
        archive.getList(RecipeMaintenanceArchive.TOPOLOGIES, Tag.TAG_COMPOUND).getCompound(0).putInt("WritableSlots", 0);
        archive.getCompound(RecipeMaintenanceArchive.INDEX).putInt("FingerprintCursor", 0);
        var restored = new RecipeLibraryMaintenance(); candidate.commitTo(restored);
        assertSame(loadedIndex, restored.index()); assertEquals(7, restored.index().fingerprintCursor());
        assertEquals(original.layout(), restored.layout()); assertEquals(original.topologies(), restored.topologies());
    }

    @Test void legacyMetadataAndRepairedIndexStayPendingWithoutLosingContinuation() {
        var owner = owner(); var archive = RecipeMaintenanceArchive.save(owner, REGISTRIES);
        archive.remove(RecipeMaintenanceArchive.TOPOLOGIES);
        var prior = RecipeMaintenanceArchive.prepare(archive, owner.index(), false, true, 32, address(CORE)).orElseThrow();
        assertTrue(prior.refreshPending()); assertFalse(prior.hadCorruption()); assertSame(owner.index(), prior.index());
        archive = RecipeMaintenanceArchive.save(owner, REGISTRIES);
        var repaired = RecipeMaintenanceArchive.prepare(archive, owner.index(), true, false, 32, address(CORE)).orElseThrow();
        assertTrue(repaired.refreshPending()); assertTrue(repaired.hadCorruption());
        var partial = owner(); partial.markIncomplete();
        var restored = new RecipeLibraryMaintenance();
        RecipeMaintenanceArchive.load(RecipeMaintenanceArchive.save(partial, REGISTRIES), REGISTRIES, 32, address(CORE)).orElseThrow().commitTo(restored);
        assertEquals(LibraryStructureScanner.State.PARTIAL, restored.state()); assertEquals(partial.layout(), restored.layout());
        assertFalse(restored.ready());
    }

    @Test void historicalGeometryRequiresExactDerivedCountsChunksBoundsAndMembership() {
        var scan = owner().layout(); var encoded = RecipeStructureArchive.save(scan);
        assertEquals(scan, RecipeStructureArchive.load(encoded).orElseThrow());
        var badQueue = encoded.copy(); badQueue.putInt("QueueCount", 99);
        var badBound = encoded.copy(); badBound.putLong("Max", CORE.above().asLong());
        var badChunk = encoded.copy(); badChunk.getList("Chunks", Tag.TAG_COMPOUND).getCompound(0).putInt("X", 99);
        var duplicate = encoded.copy(); var members = duplicate.getList("Members", Tag.TAG_LONG); members.add(members.get(0).copy());
        var extra = encoded.copy(); extra.putInt("Capacity", 99);
        for (var invalid : List.of(badQueue, badBound, badChunk, duplicate, extra)) assertTrue(RecipeStructureArchive.load(invalid).isEmpty());
        var host = new CompoundTag(); host.putString("OtherService", "preserved"); host.put(RecipeMaintenanceArchive.LAYOUT, encoded.copy());
        RecipeMaintenanceArchive.writeMetadata(new RecipeLibraryMaintenance(), host);
        assertEquals("preserved", host.getString("OtherService")); assertFalse(host.contains(RecipeMaintenanceArchive.LAYOUT));
    }

    private static RecipeLibraryMaintenance owner() {
        var owner = new RecipeLibraryMaintenance();
        var scan = new LibraryStructureScanner.ScanResult(LibraryStructureScanner.State.VALID,
            new LibraryStructureScanner.Snapshot(List.of(CORE, SHELF), List.of(), List.of(SHELF), CORE, SHELF));
        owner.apply(scan, Map.of(SHELF, 1), 0, true, RecipeMaintenanceArchiveTest::address);
        owner.advance(true, pos -> true, pos -> true, key -> "", new PatternJsonParser(REGISTRIES), 7);
        return owner;
    }
    private static RecipeSourceLocation address(BlockPos pos) { return new RecipeSourceLocation(Level.OVERWORLD, null, pos); }
}
