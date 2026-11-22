package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.biocomputing.resident.ResidentProjection;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;

/** Physical display only. Production diagnostics, programs and entity NBT are not part of this packet. */
public record ComputerResidentView(@Nullable ResidentProjection resident) {
    public static final ComputerResidentView EMPTY = new ComputerResidentView(null);
    private static final int VERSION = 2;
    private static final Set<String> EMPTY_KEYS = Set.of("Version");
    private static final Set<String> RESIDENT_KEYS = Set.of("Version", "Resident");

    public ComputerResidentView {
        // A trader has no villager profession or experience level; this is not a production rule.
        if (resident != null && resident.entityType() == EntityType.WANDERING_TRADER
            && (resident.profession() != VillagerProfession.NONE || resident.level() != 1))
            throw new IllegalArgumentException("invalid trader appearance");
    }

    public boolean renderResident() { return resident != null; }

    public static ComputerResidentView fromSnapshot(ItemStack snapshot, HolderLookup.Provider registries) {
        return ResidentProjection.fromCapturedResident(snapshot, registries)
            .map(ComputerResidentView::new).orElse(EMPTY);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Version", VERSION);
        if (resident != null) tag.put("Resident", resident.save());
        return tag;
    }

    /** Version 1 was an ephemeral production-profile packet, never a durable resident archive. */
    public static Optional<ComputerResidentView> load(CompoundTag tag) {
        if (tag == null || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != VERSION)
            return Optional.empty();
        if (tag.getAllKeys().equals(EMPTY_KEYS)) return Optional.of(EMPTY);
        if (!tag.getAllKeys().equals(RESIDENT_KEYS) || !tag.contains("Resident", Tag.TAG_COMPOUND))
            return Optional.empty();
        try {
            return ResidentProjection.load(tag.getCompound("Resident")).map(ComputerResidentView::new);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
