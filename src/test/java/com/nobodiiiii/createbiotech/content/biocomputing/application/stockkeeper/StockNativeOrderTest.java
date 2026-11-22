package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper.StockApplicationTest.*;

class StockNativeOrderTest {
    @Test void previewRejectionSurvivesWireAndCannotTargetAnotherAttemptOrMenu() {
        var requestId = UUID.randomUUID();
        var request = new StockNativeOrderRequest(12, requestId, new byte[]{1}, "Depot A");
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            request.write(buffer);
            var decoded = new StockNativeOrderRequest(buffer);
            assertEquals(requestId, decoded.request());
            assertEquals(12, decoded.container());
            assertArrayEquals(request.order(), decoded.order());
            buffer.clear();
            new StockNativeOrderResponse(decoded.container(), decoded.request(), "access_denied").write(buffer);
            var response = new StockNativeOrderResponse(buffer);
            assertEquals("access_denied", response.code());
            assertTrue(response.matches(12, requestId));
            assertFalse(response.matches(13, requestId));
            assertFalse(response.matches(12, UUID.randomUUID()));
            assertFalse(response.matches(12, null));
        } finally { buffer.release(); }
    }

    @org.junit.jupiter.api.BeforeAll static void bootstrap() throws Exception {
        Class<?> mods = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (mods.getMethod("get").invoke(null) == null)
            mods.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true);
    }
    @Test void retiredDisplayIdsCannotBecomeNativeOrdersEvenInsideCrafts() {
        var item = net.minecraft.world.item.Items.PAPER.getDefaultInstance();
        var normal = new com.simibubi.create.content.logistics.BigItemStack(item, 2);
        var id = net.minecraft.resources.ResourceLocation.parse("create_biotech:pattern_display");
        var items = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        if (!items.containsKey(id)) net.minecraft.core.Registry.register(items, id,
            new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties()));
        var token = new com.simibubi.create.content.logistics.BigItemStack(items.get(id).getDefaultInstance(), 1);
        var real = new com.simibubi.create.content.logistics.stockTicker.PackageOrder(List.of(normal));
        var old = new com.simibubi.create.content.logistics.stockTicker.PackageOrder(List.of(token));
        var nested = new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts(real,
            List.of(new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CraftingEntry(old, 1)));
        for (var order : List.of(nested,
                com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.simple(List.of(normal, token)))) {
            assertEquals("retired_pattern", assertThrows(IllegalArgumentException.class, () ->
                StockNativeOrder.capture(order, net.minecraft.core.RegistryAccess.EMPTY, LIMITS)).getMessage());
        }
        var imitation = new net.minecraft.nbt.CompoundTag();
        imitation.putString("create_biotech:pattern_offer", "not a virtual item");
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(imitation));
        assertDoesNotThrow(() -> StockNativeOrder.capture(
            com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.simple(List.of(normal)),
            net.minecraft.core.RegistryAccess.EMPTY, LIMITS));
    }

    @Test void captureUsesCreatesActualCodecWithoutLosingComponentsOrEmptyCraftingSlots() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 2);
        stack.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 16);
        stack.remove(net.minecraft.core.component.DataComponents.ITEM_NAME);
        var item = new com.simibubi.create.content.logistics.BigItemStack(stack, 7);
        var empty = new com.simibubi.create.content.logistics.BigItemStack(net.minecraft.world.item.ItemStack.EMPTY, 1);
        var pattern = new com.simibubi.create.content.logistics.stockTicker.PackageOrder(List.of(empty, item));
        var order = new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts(
            new com.simibubi.create.content.logistics.stockTicker.PackageOrder(List.of(item)),
            List.of(new com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CraftingEntry(pattern, 2)));
        var registries = net.minecraft.core.RegistryAccess.EMPTY;
        var expected = com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts.CODEC
            .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), order).getOrThrow();
        var captured = StockNativeOrder.capture(order, registries, LIMITS);
        item.count = 99; stack.setCount(8); stack.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 32);
        assertEquals(expected, NativeNbtCodec.toTag(captured, new NativeNbtCodec.Limits(65536,1048576,StockApplicationValues.FORMAT)));
        var tight = new StockApplication.Limits(1,4,100000,256,128);
        assertThrows(IllegalArgumentException.class, () -> StockNativeOrder.capture(order, registries, tight));
    }

    @Test void versionOneCatalogReceiptsStillLoadWithoutInventingNativeOrders() {
        var start = initial(); var catalog = StockApplication.replace(start,ACCESS,start.fence(),List.of(entry()),LIMITS).state();
        var pending = StockApplication.confirm(catalog,ACCESS,PLAYER,request(catalog),LIMITS).state();
        var root = new LinkedHashMap<>(StockApplicationValues.state(pending).fields());
        root.put("version", number(1));
        var input = new LinkedHashMap<>(StockApplicationValues.input(pending.inputs().getFirst()).fields());
        var oldRequest = new LinkedHashMap<>(((DataValue.RecordValue)input.get("request")).fields());
        oldRequest.remove("kind"); oldRequest.remove("order");
        input.put("request", new DataValue.RecordValue(oldRequest));
        root.put("inputs", new DataValue.ListValue(List.of(new DataValue.RecordValue(input))));
        var archive = new CompoundTag(); archive.putInt("Version",1);
        archive.put("State",com.nobodiiiii.createbiotech.content.biocomputing.persistence.ValueNbtCodec.encode(new DataValue.RecordValue(root),StockApplicationValues.FORMAT));
        var loaded = StockApplicationCodec.load(archive,UUID.randomUUID());
        assertEquals(pending.inputs(),loaded.inputs());
        assertEquals("existing",StockApplication.confirm(loaded,ACCESS,PLAYER,request(catalog),LIMITS).code());
        assertEquals(2,StockApplicationCodec.save(loaded).getInt("Version"));
    }

    @Test void nativeInputUsesTheExistingQueueAndKeepsTypedComponentsAcrossReloadAndAcknowledgement() {
        // Real Create codec layout, including a removed component and crafting context.
        var tag = new CompoundTag();
        var stack = new CompoundTag(); stack.putString("id", "minecraft:stone"); stack.putInt("count", 1);
        var components = new CompoundTag(); components.putString("minecraft:custom_name", "\"Ordered stone\"");
        components.put("!minecraft:lore", new CompoundTag()); stack.put("components", components);
        var row = new CompoundTag(); row.put("item_stack", stack); row.putInt("count", 3);
        var entries = new ListTag(); entries.add(row);
        var ordered = new CompoundTag(); ordered.put("entries", entries); tag.put("ordered_stacks", ordered);
        var crafts = new ListTag(); var craft = new CompoundTag(); craft.put("pattern", ordered.copy()); craft.putInt("count", 1); crafts.add(craft);
        tag.put("ordered_crafts", crafts);
        var nbt = NativeNbtCodec.snapshot(tag, new NativeNbtCodec.Limits(65536, 1048576, StockApplicationValues.FORMAT));
        var start = initial();
        var request = StockApplication.Request.nativeOrder(start.fence(), start.nextSequence(), "Depot A", nbt);
        var owner = new StockApplicationSerial(start);
        assertEquals("input_too_large", owner.confirm(ACCESS, PLAYER, request, LIMITS, (s, i) -> false).code());
        assertSame(start, owner.state()); // A reader frame refusal must not consume the confirmation identity.
        var accepted = owner.confirm(ACCESS, PLAYER, request, LIMITS);
        assertEquals("input_queued", accepted.code());
        assertTrue(owner.state().catalog().isEmpty());
        assertTrue(accepted.input().orElseThrow().items().isEmpty()); // Never fabricate a catalog entry.
        components.putString("minecraft:custom_name", "changed after capture");
        var loaded = StockApplicationCodec.load(StockApplicationCodec.save(owner.state()), UUID.randomUUID());
        var head = (DataValue.RecordValue) StockApplicationHost.publicInput(loaded, loaded.inputs().stream().findFirst()).field("head");
        assertEquals(text("native_order"), head.field("kind"));
        assertEquals(nbt, head.field("order"));
        assertEquals("existing", StockApplication.confirm(loaded, ACCESS, PLAYER, request, LIMITS).code());
        var changed = StockApplication.Request.nativeOrder(request.fence(), request.sequence(), "Changed", nbt);
        assertEquals("conflict", StockApplication.confirm(loaded, ACCESS, PLAYER, changed, LIMITS).code());
        var catalog = StockApplication.replace(loaded, ACCESS, loaded.fence(), List.of(entry()), LIMITS).state();
        var oneSlot = new StockApplication.Limits(8, 1, 100000, 256, 128);
        assertEquals("capacity", StockApplication.confirm(catalog, ACCESS, PLAYER, request(catalog), oneSlot).code());
        var acked = StockApplication.ack(loaded, ACCESS, loaded.fence(), request.sequence()).state();
        assertEquals("retired", StockApplication.confirm(acked, ACCESS, PLAYER, request, LIMITS).code());
        assertTrue(acked.inputs().isEmpty());
    }
}
