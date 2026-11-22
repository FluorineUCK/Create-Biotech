package com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource;

import static org.junit.jupiter.api.Assertions.*;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionResourceProfileTest {
    @Test void canonicalProductionProfileIsAnImmutableValueNotGenericHardware() {
        var capabilities = EnumSet.of(ProductionCapability.ALCHEMIST);
        var value = new ProductionResourceProfile(ProductionProfession.CLERIC, 2, capabilities, 0);
        capabilities.clear();
        assertEquals(ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 1, 64).orElseThrow(), value);
        assertTrue(value.supports(ProductionCapability.ALCHEMIST));
        assertThrows(UnsupportedOperationException.class, () -> value.capabilities().clear());
        assertThrows(IllegalArgumentException.class, () -> new ProductionResourceProfile(ProductionProfession.LIBRARIAN, 4, Set.of(ProductionCapability.ALCHEMIST), 0));
        assertThrows(IllegalArgumentException.class, () -> new ProductionResourceProfile(ProductionProfession.CLERIC, 16, Set.of(ProductionCapability.ALCHEMIST), 0));
        assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 0, 0).isEmpty());
        assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.CLERIC, 6, 0).isEmpty());
        assertTrue(ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 4097).isEmpty());
        assertEquals(2, ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 64).orElseThrow().processSlots());
        assertEquals(0, ProductionResourceProfile.forProfession(ProductionProfession.ZOMBIE_VILLAGER, 1, 0).orElseThrow().processSlots());
    }
}
