package com.nobodiiiii.createbiotech.content.biocomputing.peripheral;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryItemTagsTest {
    private static final ReadLimits LIMITS = new ReadLimits(4096, 32768, 65536);
    @BeforeAll static void bootstrap() throws Exception { CapabilitySnapshotValuesTest.bootstrap(); }
    @Test void actualReadRegistryExposesSeparateTypedOptInItemTagFacts() {
        var descriptor = (DataValue.RecordValue)ReadComponents.registry().describe().field("create_biotech:inventory_item_tags");
        assertEquals(new DataValue.BooleanValue(true), descriptor.field("additional"));
    }
    @Test void registryBackedModdedTagsAreSortedAndSlotsAndCopiesArePreserved() {
        var registry = BuiltInRegistries.ITEM;
        var old = new LinkedHashMap<TagKey<Item>, List<net.minecraft.core.Holder<Item>>>();
        registry.getTags().forEach(pair -> old.put(pair.getFirst(), pair.getSecond().stream().toList()));
        var a = TagKey.create(Registries.ITEM, ResourceLocation.parse("test:alpha"));
        var z = TagKey.create(Registries.ITEM, ResourceLocation.parse("other_mod:zulu"));
        try {
            var tags = new LinkedHashMap<>(old); tags.put(z, List.of(Items.PAPER.builtInRegistryHolder()));
            tags.put(a, List.of(Items.PAPER.builtInRegistryHolder())); registry.bindTags(tags);
            var source = new ItemStackHandler(2); var stack = new ItemStack(Items.PAPER, 7); source.setStackInSlot(0, stack);
            var before = stack.copy();
            var result = assertInstanceOf(DataValue.ListValue.class, CapabilitySnapshotValues.inventoryItemTags(source, LIMITS));
            var first = (DataValue.RecordValue)result.values().getFirst();
            assertEquals(new DataValue.IntegerValue(0), first.field("slot"));
            assertEquals(new DataValue.TextValue("minecraft:paper"), first.field("item"));
            var actual = ((DataValue.ListValue)first.field("tags")).values();
            var expected = stack.getTags().map(tag -> tag.location().toString()).sorted().map(DataValue.TextValue::new).toList();
            assertEquals(expected, actual);
            assertTrue(actual.contains(new DataValue.TextValue("other_mod:zulu")));
            var empty = (DataValue.RecordValue)result.values().get(1);
            assertEquals(new DataValue.IntegerValue(1), empty.field("slot"));
            assertEquals(new DataValue.TextValue("minecraft:air"), empty.field("item"));
            assertEquals(new DataValue.ListValue(List.of()), empty.field("tags"));
            assertTrue(ItemStack.matches(before, source.getStackInSlot(0)));
            source.setStackInSlot(0, new ItemStack(Items.STONE));
            assertEquals(new DataValue.TextValue("minecraft:paper"), first.field("item"));
            assertThrows(UnsupportedOperationException.class, () -> actual.add(new DataValue.TextValue("fake:tag")));
        } finally { registry.bindTags(old); }
    }
    @Test void lazyEnumerationIsBoundedBeforeCollectOrSortAndEveryAggregateLimitIsEnforced() {
        var registry = BuiltInRegistries.ITEM;
        var old = new LinkedHashMap<TagKey<Item>, List<net.minecraft.core.Holder<Item>>>();
        registry.getTags().forEach(pair -> old.put(pair.getFirst(), pair.getSecond().stream().toList()));
        try {
            var tags = new LinkedHashMap<>(old);
            for (int i = 0; i < 100; i++) tags.put(TagKey.create(Registries.ITEM,
                ResourceLocation.parse("mod:tag_" + i)), List.of(Items.PAPER.builtInRegistryHolder()));
            registry.bindTags(tags);
            var source = new ItemStackHandler(1); source.setStackInSlot(0, new ItemStack(Items.PAPER));
            assertError("snapshot_limit", CapabilitySnapshotValues.inventoryItemTags(source, new ReadLimits(8, 10000, 10000)));
            assertEquals(100, Items.PAPER.builtInRegistryHolder().tags().filter(tag -> tag.location().getNamespace().equals("mod")).count());
        } finally { registry.bindTags(old); }
        var one = new ItemStackHandler(1); one.setStackInSlot(0, new ItemStack(Items.PAPER));
        var captured = CapabilitySnapshotValues.inventoryItemTags(one, LIMITS);
        assertError("snapshot_limit", CapabilitySnapshotValues.inventoryItemTags(one, new ReadLimits(4096, 1, 10000)));
        assertError("snapshot_limit", CapabilitySnapshotValues.inventoryItemTags(one, new ReadLimits(4096, 10000, captured.storageUnits() - 1)));
        assertEquals(captured, CapabilitySnapshotValues.inventoryItemTags(one, new ReadLimits(4096, 10000, captured.storageUnits())));
        var reads = new AtomicInteger();
        var many = new ItemStackHandler(0) {
            @Override public int getSlots() { return 10; }
            @Override public ItemStack getStackInSlot(int slot) { reads.incrementAndGet(); return ItemStack.EMPTY; }
        };
        assertError("snapshot_limit", CapabilitySnapshotValues.inventoryItemTags(many, new ReadLimits(10, 10000, 10000)));
        assertTrue(reads.get() < 10);
        assertError("unsupported", CapabilitySnapshotValues.inventoryItemTags(null, LIMITS));
        assertError("provider_failure", CapabilitySnapshotValues.inventoryItemTags(new ItemStackHandler(0) {
            @Override public int getSlots() { throw new IllegalStateException("private provider data"); }
        }, LIMITS));
    }
    private static void assertError(String code, DataValue value) {
        assertEquals(code, assertInstanceOf(DataValue.ErrorValue.class, value).code());
    }
}
