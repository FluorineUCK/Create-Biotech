package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

/** Identity-only attachment marker. Ordinary Create Links get no port, scheduler, or application data. */
public final class StockLinkIdentity extends BlockEntityBehaviour {
    public static final BehaviourType<StockLinkIdentity> TYPE = new BehaviourType<>();
    private static final String ROOT = "BiocomputingStockLinkIdentity";
    private Tag saved;
    public StockLinkIdentity(PackagerLinkBlockEntity link) { super(link); }
    @Override public BehaviourType<?> getType() { return TYPE; }
    Optional<UUID> identity() {
        return saved instanceof CompoundTag tag && tag.getAllKeys().equals(Set.of("Version", "Id"))
            && tag.contains("Version", Tag.TAG_INT) && tag.getInt("Version") == 1 && tag.hasUUID("Id") ? Optional.of(tag.getUUID("Id")) : Optional.empty();
    }
    UUID bindIdentity() {
        if (saved == null) {
            var tag = new CompoundTag(); tag.putInt("Version", 1); tag.putUUID("Id", UUID.randomUUID()); saved = tag; blockEntity.setChanged();
        }
        return identity().orElseThrow(() -> new IllegalArgumentException("invalid_link"));
    }
    @Override public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        if (!clientPacket) saved = tag.contains(ROOT) ? tag.get(ROOT).copy() : null;
    }
    @Override public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        tag.remove(ROOT); if (!clientPacket && saved != null) tag.put(ROOT, saved.copy());
    }
}
