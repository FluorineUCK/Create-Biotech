package com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Strict unchanged association-list format; persisted associations alone do not confer authority. */
public final class PriorityAssociationCodecs {
    private static final Set<String> KEYS = Set.of("ProcessId", "LibraryId", "PatternId", "CatalogGeneration");
    private PriorityAssociationCodecs() {}

    public static ListTag save(Collection<CoordinationAssociation> associations) {
        Objects.requireNonNull(associations, "associations");
        if (associations.size() > PatternPriorityAuthority.MAX_LEASES)
            throw new IllegalArgumentException("coordination associations exceed bound");
        var result = new ListTag(); var seen = new HashSet<UUID>();
        associations.stream().sorted(Comparator.comparing(CoordinationAssociation::processId)).forEach(value -> {
            if (!seen.add(value.processId())) throw new IllegalArgumentException("duplicate coordination association");
            var tag = new CompoundTag(); tag.putUUID("ProcessId", value.processId());
            tag.putUUID("LibraryId", value.libraryId()); tag.putUUID("PatternId", value.patternId());
            tag.putLong("CatalogGeneration", value.catalogGeneration()); result.add(tag);
        });
        return result;
    }

    public static Optional<Map<UUID, CoordinationAssociation>> load(ListTag list) {
        if (list == null || list.size() > PatternPriorityAuthority.MAX_LEASES
            || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) return Optional.empty();
        var result = new LinkedHashMap<UUID, CoordinationAssociation>();
        try {
            for (Tag raw : list) {
                var tag = (CompoundTag) raw;
                if (!tag.getAllKeys().equals(KEYS) || !tag.hasUUID("ProcessId") || !tag.hasUUID("LibraryId")
                    || !tag.hasUUID("PatternId") || !tag.contains("CatalogGeneration", Tag.TAG_LONG)) return Optional.empty();
                var value = new CoordinationAssociation(tag.getUUID("ProcessId"), tag.getUUID("LibraryId"),
                    tag.getUUID("PatternId"), tag.getLong("CatalogGeneration"));
                if (result.putIfAbsent(value.processId(), value) != null) return Optional.empty();
            }
            return Optional.of(Collections.unmodifiableMap(result));
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }
}
