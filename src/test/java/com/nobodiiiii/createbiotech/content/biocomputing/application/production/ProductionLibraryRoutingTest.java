package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class ProductionLibraryRoutingTest {
    @Test void onlyExactCurrentFieldTypesAndNonnegativeRevisionsAreAccepted() {
        var value = new ProductionLibraryRouting(CommunicationFrequency.UNCONFIGURED, Integer.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(value, ProductionLibraryRouting.load(value.save()).orElseThrow());
        for (String key : java.util.List.of("Frequency", "Priority", "Revision")) {
            var missing = value.save(); missing.remove(key); assertTrue(ProductionLibraryRouting.load(missing).isEmpty());
        }
        var extra = value.save(); extra.putBoolean("Extra", true); assertTrue(ProductionLibraryRouting.load(extra).isEmpty());
        var wrong = value.save(); wrong.putLong("Priority", 1); assertTrue(ProductionLibraryRouting.load(wrong).isEmpty());
        wrong = value.save(); wrong.putInt("Revision", 1); assertTrue(ProductionLibraryRouting.load(wrong).isEmpty());
        wrong = value.save(); wrong.putLong("Revision", -1); assertTrue(ProductionLibraryRouting.load(wrong).isEmpty());
        wrong = value.save(); wrong.putString("Frequency", "x".repeat(26)); assertTrue(ProductionLibraryRouting.load(wrong).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ProductionLibraryRouting(CommunicationFrequency.UNCONFIGURED, 0, -1));
    }

    @Test void historicalShapeAndLiteralFrequencyRoundTripWithoutSharingNbt() {
        var value = new ProductionLibraryRouting(CommunicationFrequency.of("  频道  A "), -4, 17);
        var expected = new CompoundTag(); expected.putString("Frequency", "  频道  A ");
        expected.putInt("Priority", -4); expected.putLong("Revision", 17);
        assertEquals(expected, value.save());
        var restored = ProductionLibraryRouting.load(expected).orElseThrow(); assertEquals(value, restored);
        expected.putString("Frequency", "changed");
        assertEquals(value, restored); assertEquals("  频道  A ", value.save().getString("Frequency"));
    }
}
