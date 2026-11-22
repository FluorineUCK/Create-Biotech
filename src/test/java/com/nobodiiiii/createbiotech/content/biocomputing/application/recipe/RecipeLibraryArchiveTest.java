package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibraryRouting;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryEditRestriction;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryResident;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;

class RecipeLibraryArchiveTest {
    private static final UUID ID = new UUID(0, 8), TOKEN = new UUID(0, 9);
    private static final ProductionLibraryRouting ROUTING = new ProductionLibraryRouting(CommunicationFrequency.of("A"), 4, 7);
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true); Items.AIR.getDefaultInstance();
    }

    @Test void preparesWholeEnvelopeWithoutMutatingCurrentResidentOrDecodingTheIndexTwice() {
        var current = new LibraryResident(); current.markPendingSafeRelease();
        var reader = new IndexReader(); var raw = archive();
        var prepared = RecipeLibraryArchive.prepare(raw, UUID.randomUUID(), current, RegistryAccess.EMPTY, 1024, null, reader).orElseThrow();
        assertTrue(current.pendingSafeRelease(), "Preparation cannot install state");
        assertEquals(ID, prepared.libraryId()); assertEquals(TOKEN, prepared.movementToken());
        assertEquals(ROUTING, prepared.routing()); assertTrue(prepared.restriction().mayEdit(id -> false));
        assertEquals(1, reader.loads); assertSame(prepared.indexState(), prepared.recipe().index());
        assertTrue(prepared.recipe().refreshPending()); assertTrue(prepared.needsSave());
        raw.getCompound("PatternLibrary").putUUID("LibraryId", UUID.randomUUID());
        server(raw).putBoolean("PendingSafeRelease", true);
        current.commitLoad(prepared.resident());
        assertFalse(current.pendingSafeRelease()); assertEquals(ID, prepared.libraryId());
    }

    @Test void saveRetainsHistoricalEnvelopeWithoutSharingTheIndexDocument() {
        var recipes = new RecipeLibraryMaintenance(); var indexTag = recipes.index().save(RegistryAccess.EMPTY);
        var saved = RecipeLibraryArchive.save(ID, TOKEN, LibraryEditRestriction.UNBOUND, new LibraryResident(),
            recipes, indexTag, ROUTING, RegistryAccess.EMPTY);
        assertEquals(archive(), saved);
        indexTag.putString("Foreign", "changed after saving");
        assertEquals(archive(), saved);
        var restored = RecipeLibraryArchive.prepare(saved, TOKEN, new LibraryResident(), RegistryAccess.EMPTY,
            1024, null, new IndexReader()).orElseThrow();
        assertEquals(ID, restored.libraryId()); assertEquals(ROUTING, restored.routing());
    }

    @Test void malformedEnvelopeRejectsBeforeIndexDecodeAndDoesNotInstallResidentChanges() {
        List<Consumer<CompoundTag>> corruptions = List.of(
            root -> root.putString("PatternLibrary", "bad"),
            root -> root.getCompound("PatternLibrary").putString("LibraryId", "bad"),
            root -> root.getCompound("PatternLibrary").putBoolean("Extra", true),
            root -> root.getCompound("PatternLibrary").putByte("BindingStateInvalid", (byte)2),
            root -> root.getCompound("PatternLibrary").putString("BindingState", "bad"),
            root -> server(root).putInt("PendingSafeRelease", 1),
            root -> server(root).putByte("PendingSafeRelease", (byte)2),
            root -> server(root).putString("StructureState", "bad"),
            root -> server(root).putString("StructureSnapshot", "bad"),
            root -> server(root).putString("LibrarianSnapshotBox", "bad"),
            root -> server(root).putString("PageTopologies", "bad"),
            root -> server(root).putString("PatternIndex", "bad"),
            root -> server(root).putString("MovementClaimToken", "bad"),
            root -> server(root).putBoolean("Extra", true),
            root -> server(root).getCompound("RoutingConfiguration").putLong("Priority", 1));
        for (var corruption : corruptions) {
            var raw = archive(); corruption.accept(raw); var reader = new IndexReader();
            var resident = new LibraryResident(); resident.markPendingSafeRelease();
            assertTrue(RecipeLibraryArchive.prepare(raw, TOKEN, resident, RegistryAccess.EMPTY, 1024, null, reader).isEmpty(), raw::toString);
            assertEquals(0, reader.loads); assertTrue(resident.pendingSafeRelease());
        }
    }

    @Test void priorShapeRequiresCodecWitnessAndMissingMovementKeepsTheCurrentToken() {
        var raw = archive(); server(raw).remove("PageTopologies"); server(raw).remove("MovementClaimToken");
        var reader = new IndexReader(); var resident = new LibraryResident();
        assertTrue(RecipeLibraryArchive.prepare(raw, TOKEN, resident, RegistryAccess.EMPTY, 1024, null, reader).isEmpty());
        assertEquals(1, reader.priorChecks); assertEquals(0, reader.loads);
        reader.acceptPrior = true;
        var prior = RecipeLibraryArchive.prepare(raw, TOKEN, resident, RegistryAccess.EMPTY, 1024, null, reader).orElseThrow();
        assertEquals(TOKEN, prior.movementToken()); assertTrue(prior.recipe().refreshPending()); assertTrue(prior.needsSave());
        assertEquals(1, reader.loads);
        var current = archive(); current.put("OtherBehaviour", new CompoundTag());
        var freshReader = new IndexReader();
        assertTrue(RecipeLibraryArchive.prepare(current, TOKEN, resident, RegistryAccess.EMPTY, 1024, null, freshReader).isPresent());
        assertEquals(0, freshReader.priorChecks, "Current format is not downgraded through the historical recognizer");
    }

    @Test void damagedChildrenAreIsolatedAndUnknownResidentNbtIsRetainedAsAnOwnedCopy() {
        var raw = archive(); raw.getCompound("PatternLibrary").put("BindingState", new CompoundTag());
        var unreadable = new CompoundTag(); unreadable.putString("RecoveryMarker", "exact original");
        server(raw).put("LibrarianSnapshotBox", unreadable);
        server(raw).getCompound("PatternIndex").putString("Complete", "bad");
        var resident = new LibraryResident(); var old = new CompoundTag(); old.putString("RecoveryMarker", "before");
        resident.preserveRawSnapshot(old);
        var prepared = RecipeLibraryArchive.prepare(raw, TOKEN, resident, RegistryAccess.EMPTY, 1024, null, new IndexReader()).orElseThrow();
        assertEquals(old, resident.rawSnapshot());
        assertFalse(prepared.restriction().mayEdit(id -> true));
        assertTrue(prepared.resident().hadCorruption()); assertTrue(prepared.recipe().hadCorruption()); assertTrue(prepared.needsSave());
        unreadable.putString("RecoveryMarker", "external mutation");
        resident.commitLoad(prepared.resident());
        assertEquals("exact original", resident.rawSnapshot().getString("RecoveryMarker")); assertTrue(resident.pendingSafeRelease());
        var resaved = new CompoundTag(); resident.write(resaved, RegistryAccess.EMPTY);
        assertEquals(resident.rawSnapshot(), resaved.getCompound("LibrarianSnapshotBox"));
    }

    private static CompoundTag archive() {
        var pattern = new CompoundTag(); pattern.putUUID("LibraryId", ID); pattern.putBoolean("BindingStateInvalid", false);
        var server = new CompoundTag(); new LibraryResident().write(server, RegistryAccess.EMPTY);
        var recipes = new RecipeLibraryMaintenance(); RecipeMaintenanceArchive.writeMetadata(recipes, server);
        server.put("PatternIndex", recipes.index().save(RegistryAccess.EMPTY));
        server.put("RoutingConfiguration", ROUTING.save()); server.putUUID("MovementClaimToken", TOKEN);
        pattern.put("ServerState", server); var root = new CompoundTag(); root.put("PatternLibrary", pattern); return root;
    }
    private static CompoundTag server(CompoundTag root) { return root.getCompound("PatternLibrary").getCompound("ServerState"); }

    private static class IndexReader implements RecipeLibraryArchive.IndexCodec<RecipePageIndex> {
        int loads, priorChecks; boolean acceptPrior;
        @Override public boolean hasPriorShape(CompoundTag tag) { priorChecks++; return acceptPrior; }
        @Override public RecipeLibraryArchive.LoadedIndex<RecipePageIndex> load(CompoundTag tag, HolderLookup.Provider registries) {
            loads++; var result = RecipePageIndex.load(tag, registries);
            return new RecipeLibraryArchive.LoadedIndex<>(result.index(), result.index(), result.hadCorruption());
        }
    }
}
