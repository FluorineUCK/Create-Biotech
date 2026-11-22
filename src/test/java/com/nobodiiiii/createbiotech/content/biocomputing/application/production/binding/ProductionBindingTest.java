package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class ProductionBindingTest {
    private static final UUID GROUP = new UUID(0, 1), MEMBER = new UUID(0, 2), NETWORK = new UUID(0, 3);

    @Test void independentlyLoadsTheExistingProductionBindingFormat() {
        var expected = new ProductionBinding(GROUP, 7,
            new ProductionBinding.Authority(ProductionBinding.Role.COORDINATOR, MEMBER), List.of(NETWORK));
        assertEquals(java.util.Optional.of(expected), ProductionBindingCodec.load(fixture()),
            "The new production application must independently read the historical binding");
    }

    @Test void writesTheUnchangedHistoricalRepresentation() {
        var loaded = ProductionBindingCodec.load(fixture()).orElseThrow();
        assertEquals(fixture(), ProductionBindingCodec.save(loaded));
    }

    @Test void normalizesOrderedInputsWithoutRetainingMutableReferences() {
        var ids = new java.util.ArrayList<>(List.of(NETWORK, MEMBER, NETWORK));
        var value = new ProductionBinding(GROUP, 0, null, ids); ids.clear();
        assertEquals(List.of(NETWORK, MEMBER), value.logisticsIds());
        assertThrows(UnsupportedOperationException.class, () -> value.logisticsIds().clear());
        assertThrows(IllegalArgumentException.class, () -> new ProductionBinding(GROUP, -1, null, List.of()));
        var many = java.util.stream.IntStream.range(0, 33).mapToObj(i -> new UUID(2, i)).toList();
        assertEquals(32, new ProductionBinding(GROUP, 0, null, many.subList(0, 32)).logisticsIds().size());
        assertThrows(IllegalArgumentException.class, () -> new ProductionBinding(GROUP, 0, null, many));
    }

    @Test void allHistoricalAuthorityRolesAndUnattachedStatesRoundTrip() {
        for (var role : ProductionBinding.Role.values()) {
            var value = new ProductionBinding(GROUP, Long.MAX_VALUE, new ProductionBinding.Authority(role, MEMBER), List.of());
            assertEquals(value, ProductionBindingCodec.load(ProductionBindingCodec.save(value)).orElseThrow());
        }
        var unattached = new ProductionBinding(GROUP, 0, null, List.of());
        var saved = ProductionBindingCodec.save(unattached);
        assertFalse(saved.contains("Authority"));
        assertEquals(unattached, ProductionBindingCodec.load(saved).orElseThrow());
        var forward = fixture(); forward.putInt("IgnoredHistoricalRootField", 1);
        forward.getCompound("Authority").putInt("IgnoredHistoricalAuthorityField", 1);
        assertEquals(ProductionBindingCodec.load(fixture()), ProductionBindingCodec.load(forward));
    }

    @Test void rejectsMalformedHistoricalBindingsWithoutMutatingInput() {
        var mutations = List.<java.util.function.Consumer<CompoundTag>>of(
            tag -> tag.putInt("Version", 1), tag -> tag.putLong("Version", 2),
            tag -> tag.putString("ClusterId", GROUP.toString()), tag -> tag.putInt("Revision", 7),
            tag -> tag.putLong("Revision", -1), tag -> tag.remove("LogisticsBindings"),
            tag -> tag.putString("Authority", "none"), tag -> tag.getCompound("Authority").putString("Type", "unknown"),
            tag -> tag.getCompound("Authority").remove("MemberId"),
            tag -> tag.getList("LogisticsBindings", 10).getCompound(0).putString("Alias", "old"),
            tag -> tag.getList("LogisticsBindings", 10).getCompound(0).putString("Id", NETWORK.toString()),
            tag -> tag.getList("LogisticsBindings", 10).add(tag.getList("LogisticsBindings", 10).getCompound(0).copy()),
            tag -> { var list = new ListTag(); list.add(net.minecraft.nbt.StringTag.valueOf("not a network")); tag.put("LogisticsBindings", list); },
            tag -> { var list = new ListTag(); for (int i=0; i<33; i++) { var n = new CompoundTag(); n.putUUID("Id",new UUID(2,i)); list.add(n); } tag.put("LogisticsBindings",list); });
        for (var mutation : mutations) {
            var bad = fixture(); mutation.accept(bad); var before = bad.copy();
            assertTrue(ProductionBindingCodec.load(bad).isEmpty(), bad::toString); assertEquals(before, bad);
        }
        assertTrue(ProductionBindingCodec.load(null).isEmpty());
        var original = fixture(); var value = ProductionBindingCodec.load(original).orElseThrow(); original.getCompound("Authority").putString("Type", "PANEL");
        assertEquals(fixture(), ProductionBindingCodec.save(value));
    }

    private static CompoundTag fixture() {
        var tag = new CompoundTag(); tag.putInt("Version", 2); tag.putUUID("ClusterId", GROUP); tag.putLong("Revision", 7);
        var authority = new CompoundTag(); authority.putString("Type", "COMPUTER_COORDINATOR"); authority.putUUID("MemberId", MEMBER);
        tag.put("Authority", authority);
        var networks = new ListTag(); var network = new CompoundTag(); network.putUUID("Id", NETWORK); networks.add(network);
        tag.put("LogisticsBindings", networks); return tag;
    }
}
