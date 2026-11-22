package com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class PriorityAssociationCodecsTest {
    private static final UUID PROCESS = new UUID(0, 1), LIBRARY = new UUID(0, 2), PATTERN = new UUID(0, 3);

    @Test void historicalListRoundTripsExactlyAndMalformedUpdatesAreAtomic() {
        var entry = fixture(); var list = new ListTag(); list.add(entry); var before = list.copy();
        var association = new CoordinationAssociation(PROCESS, LIBRARY, PATTERN, 7);
        assertEquals(Map.of(PROCESS, association), PriorityAssociationCodecs.load(list).orElseThrow());
        assertEquals(list, PriorityAssociationCodecs.save(List.of(association))); assertEquals(before, list);
        var second = new CoordinationAssociation(new UUID(0, 4), LIBRARY, PATTERN, 8);
        assertEquals(PROCESS, PriorityAssociationCodecs.save(List.of(second, association)).getCompound(0).getUUID("ProcessId"));
        var extra = fixture(); extra.putBoolean("Authorized", true);
        var wrongType = fixture(); wrongType.putInt("CatalogGeneration", 7);
        var missing = fixture(); missing.remove("LibraryId");
        var negative = fixture(); negative.putLong("CatalogGeneration", -1);
        var wrongId = fixture(); wrongId.putString("PatternId", PATTERN.toString());
        for (var bad : List.of(extra, wrongType, missing, negative, wrongId)) {
            bad.putUUID("ProcessId", new UUID(0, 9)); // Do not let duplicate-ID rejection hide a malformed-field regression.
            var input = new ListTag(); input.add(fixture()); input.add(bad); var untouched = input.copy();
            assertTrue(PriorityAssociationCodecs.load(input).isEmpty()); assertEquals(untouched, input);
        }
        var duplicate = list.copy(); duplicate.add(entry.copy()); assertTrue(PriorityAssociationCodecs.load(duplicate).isEmpty());
        var wrongList = new ListTag(); wrongList.add(StringTag.valueOf("not a compound")); assertTrue(PriorityAssociationCodecs.load(wrongList).isEmpty());
        assertTrue(PriorityAssociationCodecs.load(null).isEmpty());
        assertEquals(Map.of(), PriorityAssociationCodecs.load(new ListTag()).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> PriorityAssociationCodecs.save(List.of(association, association)));
    }

    @Test void onlyTheHistoricalAssociationIdentityIsPersistableNotAuthorityOrFacts() {
        var value = new CoordinationAssociation(PROCESS, LIBRARY, PATTERN, 7);
        String old = "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.CoordinationAssociation";
        var registries = net.minecraft.core.RegistryAccess.EMPTY;
        var tag = com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(value, registries);
        assertEquals(6, tag.getInt("Version")); assertEquals(old, tag.getString("Type"));
        assertEquals(old, tag.getCompound("Value").getString("Type"));
        assertEquals(value, com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(tag, registries, CoordinationAssociation.class).orElseThrow());
        var alias = tag.copy(); alias.putString("Type", value.getClass().getName());
        assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(alias, registries, CoordinationAssociation.class).isEmpty());
        var nested = tag.copy(); nested.getCompound("Value").putString("Type", value.getClass().getName());
        assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(nested, registries, CoordinationAssociation.class).isEmpty());
        var profile = com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile
            .forProfession(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession.CLERIC, 1, 0).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(
            new PriorityReservationSource.Resident(PROCESS, profile, true), registries));
        assertThrows(IllegalArgumentException.class, () -> com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(
            new PriorityReservationSource.Reservation(PROCESS, PROCESS, profile, true, true), registries));
        assertThrows(IllegalArgumentException.class, () -> com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(PatternPriorityAuthority.empty(), registries));
    }

    @Test void capacityLimitIsExplicitAndDecodedMapsCannotBeEdited() {
        var values = new java.util.ArrayList<CoordinationAssociation>();
        for (int i = 0; i < PatternPriorityAuthority.MAX_LEASES; i++) values.add(new CoordinationAssociation(new UUID(0, i), LIBRARY, PATTERN, 7));
        var saved = PriorityAssociationCodecs.save(values);
        var loaded = PriorityAssociationCodecs.load(saved).orElseThrow(); assertEquals(256, loaded.size());
        assertThrows(UnsupportedOperationException.class, loaded::clear);
        values.add(new CoordinationAssociation(new UUID(0, 999), LIBRARY, PATTERN, 7));
        assertThrows(IllegalArgumentException.class, () -> PriorityAssociationCodecs.save(values));
        saved.add(fixture()); assertTrue(PriorityAssociationCodecs.load(saved).isEmpty());
    }

    @Test void loadingKeepsPersistedAssociationOrderWhileSavingCanonicalizesIt() {
        var first = fixture(); var second = fixture(); second.putUUID("ProcessId", new UUID(0, 4));
        for (var entries : List.of(List.of(first, second), List.of(second, first))) {
            var input = new ListTag(); entries.forEach(input::add);
            var loaded = PriorityAssociationCodecs.load(input).orElseThrow();
            assertEquals(entries.stream().map(tag -> tag.getUUID("ProcessId")).toList(), List.copyOf(loaded.keySet()));
            assertEquals(PROCESS, PriorityAssociationCodecs.save(loaded.values()).getCompound(0).getUUID("ProcessId"));
        }
    }

    private static CompoundTag fixture() {
        var tag = new CompoundTag(); tag.putUUID("ProcessId", PROCESS); tag.putUUID("LibraryId", LIBRARY);
        tag.putUUID("PatternId", PATTERN); tag.putLong("CatalogGeneration", 7); return tag;
    }
}
