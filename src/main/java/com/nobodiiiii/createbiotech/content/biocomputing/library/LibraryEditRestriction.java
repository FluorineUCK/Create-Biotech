package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding.ProductionBindingCodec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** A frozen local edit restriction, not an address, membership, or authorization to run production.
 * Keep the exact old payload even when this version cannot understand it. */
public final class LibraryEditRestriction {
    public static final String BINDING = "BindingState", INVALID = "BindingStateInvalid";
    public static final LibraryEditRestriction UNBOUND = new LibraryEditRestriction(null, false, true, List.of());
    private final @Nullable Tag original;
    private final boolean explicitlyInvalid;
    private final boolean understood;
    private final List<UUID> networks;

    private LibraryEditRestriction(@Nullable Tag original, boolean explicitlyInvalid, boolean understood, List<UUID> networks) {
        this.original = original == null ? null : original.copy();
        this.explicitlyInvalid = explicitlyInvalid;
        this.understood = understood;
        this.networks = List.copyOf(networks);
    }

    public static Optional<LibraryEditRestriction> read(CompoundTag pattern) {
        if (!pattern.contains(INVALID, Tag.TAG_BYTE) || pattern.getByte(INVALID) != 0 && pattern.getByte(INVALID) != 1)
            return Optional.empty();
        boolean denied = pattern.getBoolean(INVALID);
        var original = pattern.get(BINDING);
        if (original == null) return Optional.of(denied ? new LibraryEditRestriction(null, true, true, List.of()) : UNBOUND);
        if (!(original instanceof CompoundTag tag)) return Optional.empty();
        var decoded = ProductionBindingCodec.load(tag);
        return Optional.of(new LibraryEditRestriction(original, denied, decoded.isPresent(),
            decoded.map(binding -> binding.logisticsIds()).orElse(List.of())));
    }

    public boolean mayEdit(Predicate<UUID> mayAdministrate) {
        return understood && !explicitlyInvalid && networks.stream().allMatch(mayAdministrate);
    }

    public void write(CompoundTag pattern) {
        if (original == null) pattern.remove(BINDING);
        else pattern.put(BINDING, original.copy());
        pattern.putBoolean(INVALID, explicitlyInvalid);
    }
}
