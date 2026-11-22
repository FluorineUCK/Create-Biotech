package com.nobodiiiii.createbiotech.content.biocomputing.application.production.binding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Library's production attachment, independent of physical identity, RAM and storage grants. */
public final class ProductionLibraryAttachment {
    public static final String BINDING = "BindingState", INVALID = "BindingStateInvalid";
    @Nullable private ProductionBinding binding;
    private boolean valid = true;

    @Nullable public ProductionBinding binding() { return binding; }
    public boolean valid() { return valid; }
    public List<UUID> logisticsIds() { return binding == null ? List.of() : binding.logisticsIds(); }
    public boolean canRebind(boolean idle) { return valid && idle; }

    /** Pure preparation. Identity and idle facts must be captured by the authoritative host. */
    public Preparation prepareRebind(@Nullable ProductionBinding proposed, boolean identityReady, boolean idle) {
        if (!identityReady || !valid || proposed == null || proposed.authority() == null) return Preparation.IDENTITY;
        if (!idle) return Preparation.ACTIVE;
        if (binding != null && (proposed.revision() < binding.revision()
            || proposed.revision() == binding.revision() && !proposed.equals(binding))) return Preparation.REVISION;
        return Preparation.READY;
    }

    /** Changing the network list does not change the production authority or require an idle query queue. */
    public Preparation prepareLogistics(@Nullable ProductionBinding proposed, boolean identityReady) {
        if (!identityReady || !valid || proposed == null || proposed.authority() == null || binding == null
            || binding.authority() == null || !binding.groupId().equals(proposed.groupId())
            || !binding.authority().equals(proposed.authority())) return Preparation.IDENTITY;
        return proposed.revision() > binding.revision() ? Preparation.READY : Preparation.REVISION;
    }

    /** Called only by the host's existing two-phase binding transaction, not a public program operation. */
    public void commit(@Nullable ProductionBinding prepared) { binding = prepared; valid = true; }

    public void write(CompoundTag container) {
        if (binding == null) container.remove(BINDING);
        else container.put(BINDING, ProductionBindingCodec.save(binding));
        container.putBoolean(INVALID, !valid);
    }

    /** Malformed envelope rejects the candidate; corrupt binding data is retained as a denied state. */
    public static Optional<Loaded> prepareLoad(CompoundTag container) {
        if (container == null || !container.contains(INVALID, Tag.TAG_BYTE)
            || container.getByte(INVALID) != 0 && container.getByte(INVALID) != 1
            || container.contains(BINDING) && !container.contains(BINDING, Tag.TAG_COMPOUND)) return Optional.empty();
        boolean explicitlyInvalid = container.getBoolean(INVALID);
        if (!container.contains(BINDING)) return Optional.of(new Loaded(null, !explicitlyInvalid, false));
        var decoded = ProductionBindingCodec.load(container.getCompound(BINDING));
        return Optional.of(new Loaded(decoded.orElse(null), !explicitlyInvalid && decoded.isPresent(), decoded.isEmpty()));
    }

    public void restore(Loaded loaded) {
        Objects.requireNonNull(loaded); binding = loaded.binding(); valid = loaded.valid();
    }

    public record Loaded(@Nullable ProductionBinding binding, boolean valid, boolean hadCorruption) {}
    public enum Preparation { READY, IDENTITY, REVISION, ACTIVE }
}
