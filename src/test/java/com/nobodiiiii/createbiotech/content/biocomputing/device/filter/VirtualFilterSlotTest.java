package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualFilterSlotTest {
    @Test void nativeFilterWinsAndClearingItDoesNotReactivateOldRule() {
        var rule = new DataValue.RecordValue(Map.of("domain", text("fluid"), "mode", text("allow"),
            "matchers", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of(
                "kind", text("id"), "name", text("minecraft:water")))))));
        var initial = VirtualFilterSlot.empty(VirtualFilterRule.Domain.FLUID, true);
        VirtualFilterRule.Registry registry = (domain, kind, id) -> id.equals(ResourceLocation.withDefaultNamespace("water"));
        var rejected = initial.set(0, rule, 4, registry);
        assertEquals("filter_slot_occupied", rejected.code());
        assertSame(initial, rejected.slot());
        var cleared = initial.nativeChanged(false);
        var installed = cleared.set(cleared.version(), rule, 4, registry);
        assertEquals("applied", installed.code());
        assertTrue(installed.slot().rule().isPresent());
        var manual = installed.slot().nativeChanged(true);
        assertTrue(manual.rule().isEmpty());
        var emptyAgain = manual.nativeChanged(false);
        assertTrue(emptyAgain.rule().isEmpty());
        assertEquals("version_conflict", emptyAgain.set(installed.slot().version(), rule, 4, registry).code());
        assertEquals("applied", emptyAgain.set(emptyAgain.version(), rule, 4, registry).code());
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
