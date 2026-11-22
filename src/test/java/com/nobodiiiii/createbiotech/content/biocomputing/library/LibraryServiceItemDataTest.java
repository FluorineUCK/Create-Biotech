package com.nobodiiiii.createbiotech.content.biocomputing.library;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Test;

class LibraryServiceItemDataTest {
    @Test void serialHardwareAndCompleteFramesTravelWithTheSameCoreButDoNotInitializeBookStorage() {
        var raw = new CompoundTag(); raw.putString("Origin", "physical");
        var carried = new LibraryServiceItemData(UUID.randomUUID(), null, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(raw));
        raw.putString("Origin", "mutated");
        var restored = LibraryServiceItemData.decode(carried.encode());
        assertEquals(carried, restored); assertNull(restored.archive()); assertTrue(restored.drive().isEmpty());
        assertEquals("physical", ((CompoundTag)restored.serial().orElseThrow()).getString("Origin"));
        ((CompoundTag)restored.serial().orElseThrow()).putString("Origin", "another mutation");
        assertEquals(carried, LibraryServiceItemData.decode(restored.encode()));
        assertEquals(4, restored.encode().copyTag().getInt("Version"));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(restored.encode().update(t -> t.remove("Serial"))));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(restored.encode().update(t -> t.putInt("Version", 3))));
    }
    @Test void mechanicalWorkTravelsWithoutInitializingLegacyServiceAndCopiesItsCheckpoint() {
        var raw = new CompoundTag(); raw.putLong("Remaining", 27);
        var carried = new LibraryServiceItemData(UUID.randomUUID(), null, java.util.Optional.empty(), java.util.Optional.of(raw));
        raw.putLong("Remaining", 0);
        var decoded = LibraryServiceItemData.decode(carried.encode());
        assertEquals(carried, decoded); assertNull(decoded.archive()); assertTrue(decoded.control().isEmpty());
        assertEquals(27, ((CompoundTag)decoded.drive().orElseThrow()).getLong("Remaining"));
        ((CompoundTag)decoded.drive().orElseThrow()).putLong("Remaining", 99);
        assertEquals(27, ((CompoundTag)decoded.drive().orElseThrow()).getLong("Remaining"));
        assertEquals(3, carried.encode().copyTag().getInt("Version"));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(carried.encode().update(t -> t.remove("Drive"))));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(carried.encode().update(t -> t.putInt("Version", 2))));
    }
    @Test void localControlArchiveTravelsIndependentlyOfAnUntouchedLegacyService() {
        var raw = new CompoundTag(); raw.putLong("Revision", 3);
        var carried = new LibraryServiceItemData(UUID.randomUUID(), null, java.util.Optional.of(raw));
        raw.putLong("Revision", 99);
        var decoded = LibraryServiceItemData.decode(carried.encode());
        assertEquals(carried, decoded); assertNull(decoded.archive());
        assertEquals(3, ((CompoundTag)decoded.control().orElseThrow()).getLong("Revision"));
        ((CompoundTag)decoded.control().orElseThrow()).putLong("Revision", 42);
        assertEquals(3, ((CompoundTag)decoded.control().orElseThrow()).getLong("Revision"));
        assertEquals(2, carried.encode().copyTag().getInt("Version"));
    }
    @Test void ownedEnvelopeCopiesMutableArchiveAtEveryBoundary() {
        var raw = new CompoundTag(); raw.putString("request", "original");
        var id = UUID.randomUUID(); var carried = new LibraryServiceItemData(id, raw);
        raw.putString("request", "changed");
        ((CompoundTag)carried.archive()).putString("request", "another change");
        var decoded = LibraryServiceItemData.decode(carried.encode());
        assertEquals(id, decoded.libraryId());
        assertEquals("original", ((CompoundTag)decoded.archive()).getString("request"));
        assertEquals(carried, decoded);
    }
    @Test void outerEnvelopeRejectsProjectionUnknownVersionAndExtraFields() {
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(CustomData.EMPTY));
        var data = new LibraryServiceItemData(UUID.randomUUID(), new CompoundTag()).encode();
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(data.update(t -> t.putInt("Version", 2))));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(data.update(t -> t.putLong("Version", 1))));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(data.update(t -> t.remove("LibraryId"))));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(data.update(t -> t.putString("extra", "ignored?"))));
        assertThrows(IllegalArgumentException.class, () -> LibraryServiceItemData.decode(data.update(t -> t.remove("Service"))));
    }
    @Test void unknownInnerArchiveSurvivesForStoreDiagnosisRatherThanResetting() {
        var malformed = new LibraryServiceItemData(UUID.randomUUID(), StringTag.valueOf("unknown service"));
        assertEquals(malformed, LibraryServiceItemData.decode(malformed.encode()));
        var large = new CompoundTag(); large.putString("untouched", "x".repeat(70000));
        var carried = new LibraryServiceItemData(UUID.randomUUID(), large);
        assertEquals(large, LibraryServiceItemData.decode(carried.encode()).archive());
        assertThrows(IllegalArgumentException.class, () -> new LibraryServiceItemData(UUID.randomUUID(), EndTag.INSTANCE));
    }
}
