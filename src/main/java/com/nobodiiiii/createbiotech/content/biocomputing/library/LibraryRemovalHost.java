package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/** Internal physical recovery seam, never a program-callable world or storage operation. */
public interface LibraryRemovalHost {
    State captureLibraryRemoval();
    void completeLibraryResidentRelease();
    void deferLibraryResidentRelease();

    /** Reclaims the restored saved identity after the physical transaction reloads the whole BE. */
    boolean reclaimLibraryAfterRemoval();

    /** Copies prevent preparation, failed outputs or callers from mutating the resident/item owner. */
    record State(ItemStack resident, boolean unreadableResident, ItemStack coreItem) {
        public State {
            resident = Objects.requireNonNull(resident, "resident").copy();
            coreItem = Objects.requireNonNull(coreItem, "coreItem").copy();
        }
        @Override public ItemStack resident() { return resident.copy(); }
        @Override public ItemStack coreItem() { return coreItem.copy(); }
        public boolean residentRequired() { return unreadableResident || !resident.isEmpty(); }
    }
}
