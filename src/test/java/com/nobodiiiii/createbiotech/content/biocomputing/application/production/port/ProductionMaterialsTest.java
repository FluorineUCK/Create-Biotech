package com.nobodiiiii.createbiotech.content.biocomputing.application.production.port;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductionMaterialsTest {
    static final NativeNbtCodec.Limits BOUNDS = new NativeNbtCodec.Limits(65536, 1048576, new ArchiveLimits(4096, 65536, 65536));
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE);
    }
    static DataValue.RecordValue material(CompoundTag components) {
        var row = new LinkedHashMap<String, DataValue>();
        row.put("item", new DataValue.TextValue("minecraft:stone")); row.put("count", new DataValue.IntegerValue(4));
        if (components != null) row.put("components", NativeNbtCodec.snapshot(components, BOUNDS));
        return new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(new DataValue.RecordValue(row)))));
    }
    @Test void decodableButInvalidComponentsMustNotSilentlyDispatchPlainItems() {
        var patch = new CompoundTag(); patch.putInt("minecraft:max_damage", 1);
        // Stone remains stackable; Minecraft's lenient apply method logs and silently discards this patch.
        assertThrows(IllegalArgumentException.class, () -> ProductionMaterials.decode(material(patch), RegistryAccess.EMPTY, BOUNDS, 8));
    }
    @Test void validNativeComponentsRemainExactAndNeverModifySourceNbt() {
        var patch = new CompoundTag(); patch.putInt("minecraft:max_stack_size", 1); patch.putInt("minecraft:max_damage", 1);
        var before = patch.copy(); var decoded = ProductionMaterials.decode(material(patch), RegistryAccess.EMPTY, BOUNDS, 8);
        assertEquals(before, patch); assertEquals(1, decoded.size()); assertEquals(4L, decoded.values().iterator().next());
        assertNotEquals(ProductionMaterials.decode(material(null), RegistryAccess.EMPTY, BOUNDS, 8).keySet(), decoded.keySet());
    }
    @Test void callerCannotSmuggleAnAddressOrUnknownItemAndHugeCountsFailBeforeExpansion() {
        var fields = new LinkedHashMap<>(material(null).fields()); fields.put("address", new DataValue.TextValue("other"));
        assertThrows(IllegalArgumentException.class, () -> ProductionMaterials.decode(new DataValue.RecordValue(fields), RegistryAccess.EMPTY, BOUNDS, 8));
        for (String id : List.of("stone", "minecraft:missing", "minecraft:air")) {
            var data = new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(new DataValue.RecordValue(
                Map.of("item", new DataValue.TextValue(id), "count", new DataValue.IntegerValue(1)))))));
            assertThrows(IllegalArgumentException.class, () -> ProductionMaterials.decode(data, RegistryAccess.EMPTY, BOUNDS, 8));
        }
        var huge = new DataValue.RecordValue(Map.of("items", new DataValue.ListValue(List.of(new DataValue.RecordValue(
            Map.of("item", new DataValue.TextValue("minecraft:stone"), "count", new DataValue.IntegerValue(Long.MAX_VALUE)))))));
        assertEquals("capacity", assertThrows(IllegalArgumentException.class,
            () -> ProductionMaterials.decode(huge, RegistryAccess.EMPTY, BOUNDS, 8)).getMessage());
    }
}
