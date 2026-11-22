package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.NativeNbtCodec;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class StockApplicationIcons {
    private StockApplicationIcons() {}
    static ItemStack resolve(DataValue.RecordValue icon, HolderLookup.Provider registries) {
        try {
            var id = ResourceLocation.tryParse(StockApplicationCodec.string(icon.field("item")));
            long count = StockApplicationCodec.integer(icon.field("count"));
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || count < 1 || count > Integer.MAX_VALUE
                || !(icon.field("components") instanceof DataValue.NbtValue nbt) || nbt.tagId() != 10) return ItemStack.EMPTY;
            var codec = CBConfigs.SERVER.biocomputing.serialCodec(CBConfigs.SERVER_SPEC.isLoaded());
            var limits = new NativeNbtCodec.Limits(codec.maxEncodedBytes(), codec.maxDecodeHeapBytes(), codec.values());
            var patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), NativeNbtCodec.toTag(nbt, limits)).result();
            if (patch.isEmpty()) return ItemStack.EMPTY;
            var stack = new ItemStack(BuiltInRegistries.ITEM.get(id), (int)count);
            stack.applyComponents(patch.orElseThrow());
            return ItemStack.validateComponents(stack.getComponents()).error().isEmpty() ? stack : ItemStack.EMPTY;
        } catch (RuntimeException invalid) { return ItemStack.EMPTY; }
    }
}
