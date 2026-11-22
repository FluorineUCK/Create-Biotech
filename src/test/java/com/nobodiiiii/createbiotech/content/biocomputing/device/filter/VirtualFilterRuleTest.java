package com.nobodiiiii.createbiotech.content.biocomputing.device.filter;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualFilterRuleTest {
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot");
    private static final ResourceLocation GOLD = ResourceLocation.parse("minecraft:gold_ingot");
    private static final ResourceLocation WATER = ResourceLocation.parse("minecraft:water");

    @Test void checksDomainBeforeDenyListAndRejectsUnknownMatchers() {
        var value = rule("item", "deny", "id", IRON.toString());
        var filter = VirtualFilterRule.compile(value, 1,
            (domain, kind, id) -> domain == VirtualFilterRule.Domain.ITEM && id.equals(IRON));
        assertFalse(filter.matches(VirtualFilterRule.Domain.ITEM, IRON, ignored -> false));
        assertTrue(filter.matches(VirtualFilterRule.Domain.ITEM, GOLD, ignored -> false));
        assertFalse(filter.matches(VirtualFilterRule.Domain.FLUID, WATER, ignored -> false));
        assertFalse(filter.matches(VirtualFilterRule.Domain.ITEM, null, ignored -> false));
        assertEquals("unknown_filter_matcher", assertThrows(IllegalArgumentException.class,
            () -> VirtualFilterRule.compile(value, 1, (domain, kind, id) -> false)).getMessage());
    }

    private static DataValue.RecordValue rule(String domain, String mode, String kind, String name) {
        return new DataValue.RecordValue(Map.of("domain", text(domain), "mode", text(mode),
            "matchers", new DataValue.ListValue(List.of(new DataValue.RecordValue(Map.of("kind", text(kind), "name", text(name)))))));
    }

    private static DataValue.TextValue text(String value) { return new DataValue.TextValue(value); }
}
