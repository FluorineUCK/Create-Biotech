package com.nobodiiiii.createbiotech.content.biocomputing.application.production;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Placement-owned production routing, independent of generic Library volume grants. */
public record ProductionLibraryRouting(CommunicationFrequency frequency, int priority, long revision) {
    public ProductionLibraryRouting {
        Objects.requireNonNull(frequency, "frequency");
        if (revision < 0) throw new IllegalArgumentException("Negative Library routing revision");
    }

    public CompoundTag save() {
        var tag = new CompoundTag(); frequency.write(tag, "Frequency");
        tag.putInt("Priority", priority); tag.putLong("Revision", revision); return tag;
    }
    public static Optional<ProductionLibraryRouting> load(CompoundTag tag) {
        if (!tag.getAllKeys().equals(Set.of("Frequency", "Priority", "Revision"))
            || !tag.contains("Frequency", Tag.TAG_STRING) || !tag.contains("Priority", Tag.TAG_INT)
            || !tag.contains("Revision", Tag.TAG_LONG) || tag.getLong("Revision") < 0) return Optional.empty();
        return CommunicationFrequency.read(tag, "Frequency").map(frequency ->
            new ProductionLibraryRouting(frequency, tag.getInt("Priority"), tag.getLong("Revision")));
    }
}
