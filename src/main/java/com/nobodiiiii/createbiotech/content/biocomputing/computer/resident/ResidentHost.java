package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Physical resident ownership only. Not a program service or access to production/RAM state. */
public interface ResidentHost {
    /** The implementing block entity; rollback restores its complete opaque server archive. */
    BlockEntity residentOwner();
    boolean hasResidentSource();
    /** Detached description of the installed resident, including its item components. */
    ItemStack residentSnapshot();
    @Nullable Tag rawResidentTag();
    byte[] serializedResident(HolderLookup.Provider registries);
    /** Called only inside the release transaction after a confirmed destination exists. */
    void clearResidentSource();
}
