package com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

class ProductionResourceProfileCodecsTest {
    @Test void reflectionSchemaPreservesOnlyTheThreeHistoricalTypeIdentities() {
        var cases = new java.util.LinkedHashMap<Object, String>();
        String old = "com.nobodiiiii.createbiotech.content.factorycluster.computer.";
        cases.put(ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 0).orElseThrow(), old + "ComputerProfile");
        for (var profession : ProductionProfession.values()) cases.put(profession, old + "ComputerProfession");
        for (var capability : ProductionCapability.values()) cases.put(capability, old + "ProcessCapability");
        cases.forEach((value, historical) -> {
            var registries = net.minecraft.core.RegistryAccess.EMPTY;
            var tag = com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.save(value, registries);
            assertEquals(6, tag.getInt("Version")); assertEquals(historical, tag.getString("Type"));
            assertEquals(historical, tag.getCompound("Value").getString("Type"));
            assertEquals(value, com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(tag, registries, value.getClass()).orElseThrow());
            var alias = tag.copy(); alias.putString("Type", value.getClass().getName());
            assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(alias, registries, value.getClass()).isEmpty());
            var nested = tag.copy(); nested.getCompound("Value").putString("Type", value.getClass().getName());
            assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt.load(nested, registries, value.getClass()).isEmpty());
        });
    }

    @Test void historicalFixtureRoundTripsExactlyAndRejectsForgedProfilesWithoutMutation() {
        var fixture = new CompoundTag();
        fixture.putInt("Version", 1); fixture.putString("Profession", "CLERIC"); fixture.putInt("ProcessSlots", 2);
        var capabilities = new ListTag(); capabilities.add(StringTag.valueOf("ALCHEMIST")); fixture.put("Capabilities", capabilities);
        fixture.putInt("ListenerRangeBonus", 0); var untouched = fixture.copy();
        var expected = new ProductionResourceProfile(ProductionProfession.CLERIC, 2, Set.of(ProductionCapability.ALCHEMIST), 0);
        assertEquals(expected, ProductionResourceProfileCodecs.load(fixture).orElseThrow());
        assertEquals(fixture, ProductionResourceProfileCodecs.save(expected)); assertEquals(untouched, fixture);
        var wrongType = fixture.copy(); wrongType.putString("ProcessSlots", "2");
        var forged = fixture.copy(); forged.putString("Profession", "LIBRARIAN"); forged.putInt("ProcessSlots", 4);
        var duplicate = fixture.copy(); var duplicates = new ListTag(); duplicates.add(StringTag.valueOf("ALCHEMIST")); duplicates.add(StringTag.valueOf("ALCHEMIST")); duplicate.put("Capabilities", duplicates);
        var nonString = fixture.copy(); var ints = new ListTag(); ints.add(IntTag.valueOf(1)); nonString.put("Capabilities", ints);
        var extra = fixture.copy(); extra.putBoolean("Allowed", true);
        var missing = fixture.copy(); missing.remove("Capabilities");
        var bonus = fixture.copy(); bonus.putInt("ListenerRangeBonus", 64);
        var unknown = fixture.copy(); unknown.putString("Profession", "ARCHIVIST");
        for (var bad : List.of(wrongType, forged, duplicate, nonString, extra, missing, bonus, unknown)) {
            var before = bad.copy(); assertTrue(ProductionResourceProfileCodecs.load(bad).isEmpty()); assertEquals(before, bad);
        }
        assertTrue(ProductionResourceProfileCodecs.load(null).isEmpty());
    }
}
