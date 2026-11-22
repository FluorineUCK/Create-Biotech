package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionSearchWavePersistenceTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException { ProductionCatalogSearchTest.bootstrap(); }

    @Test void historicalWaveAndStatusNamesStayExactWithoutPackageWideAuthorization() {
        String old = "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.";
        var cases = new LinkedHashMap<Object, String>();
        var id = new UUID(0, 1);
        cases.put(new LibrarySearchWave(7, List.of(id), Set.of(id), List.of()), old + "LibrarySearchWave");
        for (var status : LibrarySearchCoordinator.Status.values()) cases.put(status, old + "LibrarySearchCoordinator$Status");
        cases.forEach((value, historical) -> {
            var tag = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
            assertEquals(6, tag.getInt("Version"));
            assertEquals(historical, tag.getString("Type"));
            assertEquals(historical, tag.getCompound("Value").getString("Type"));
            assertEquals(value, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, value.getClass()).orElseThrow());
            var alias = tag.copy(); alias.putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(alias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
            var nestedAlias = tag.copy(); nestedAlias.getCompound("Value").putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(nestedAlias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
        });
    }

    @Test void waveOwnsCopiesAndRejectsUnselectedMembersIncludingOnRestore() {
        var first = new UUID(0, 1); var second = new UUID(0, 2);
        var ids = new ArrayList<>(List.of(first, second));
        var awaiting = new LinkedHashSet<>(Set.of(first)); var waiting = new ArrayList<>(List.of(second));
        var wave = new LibrarySearchWave(7, ids, awaiting, waiting);
        ids.clear(); awaiting.clear(); waiting.clear();
        assertEquals(List.of(first, second), wave.libraryIds());
        assertEquals(Set.of(first), wave.awaitingGroupEnd());
        assertEquals(List.of(second), wave.waitingLibraries());
        assertThrows(UnsupportedOperationException.class, () -> wave.waitingLibraries().clear());
        assertThrows(IllegalArgumentException.class, () -> new LibrarySearchWave(7, List.of(first), Set.of(second), List.of()));
        var tag = CurrentSchemaStateNbt.save(wave, RegistryAccess.EMPTY);
        tag.getCompound("Value").put("libraryIds", CurrentSchemaStateNbt.save(List.of(first), RegistryAccess.EMPTY).getCompound("Value"));
        assertTrue(CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, LibrarySearchWave.class).isEmpty());
    }
}
