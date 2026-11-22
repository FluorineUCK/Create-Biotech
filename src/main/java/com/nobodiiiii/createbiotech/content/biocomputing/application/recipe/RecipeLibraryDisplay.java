package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;

/** Accepted recipe diagnostics and the historical client envelope, never authoritative storage. */
public final class RecipeLibraryDisplay {
    public static final String ID = "LibraryId";
    public static final String VIEW = "ClientState";
    private final Set<String> requiredKeys;
    private final Set<String> allowedKeys;
    private RecipeLibraryClientView view = RecipeLibraryClientView.EMPTY;

    /** Extension names are host protocol fields, not player-configurable data. */
    public RecipeLibraryDisplay(Set<String> requiredExtensions, Set<String> optionalExtensions) {
        var required = new HashSet<>(Set.copyOf(requiredExtensions));
        var optional = Set.copyOf(optionalExtensions);
        if (required.contains(ID) || required.contains(VIEW) || optional.contains(ID) || optional.contains(VIEW)
            || required.stream().anyMatch(String::isBlank) || optional.stream().anyMatch(String::isBlank)
            || required.stream().anyMatch(optional::contains))
            throw new IllegalArgumentException("Library packet extension keys must be disjoint and nonblank");
        required.add(ID);
        required.add(VIEW);
        requiredKeys = Set.copyOf(required);
        required.addAll(optional);
        allowedKeys = Set.copyOf(required);
    }

    public RecipeLibraryClientView view() { return view; }

    /** The physical resident transaction may publish a new projection, or restore its previous one. */
    public void replace(RecipeLibraryClientView next) { view = Objects.requireNonNull(next); }

    /** Start a fresh display-only packet; the host appends its declared diagnostic extensions. */
    public void writeFresh(CompoundTag target, UUID identity, RecipeLibraryClientView current) {
        Objects.requireNonNull(identity);
        var encoded = Objects.requireNonNull(current).save();
        for (String key : List.copyOf(target.getAllKeys())) target.remove(key);
        target.putUUID(ID, identity);
        target.put(VIEW, encoded);
    }

    /** Rejection leaves the view unchanged. Only accepted packets may update host identity/extensions. */
    public Optional<UUID> readPacket(@Nullable CompoundTag packet) {
        if (packet == null || !packet.getAllKeys().containsAll(requiredKeys)
            || !allowedKeys.containsAll(packet.getAllKeys()) || !packet.hasUUID(ID)
            || !(packet.get(VIEW) instanceof CompoundTag raw)) return Optional.empty();
        var decoded = RecipeLibraryClientView.load(raw);
        if (decoded.isEmpty()) return Optional.empty();
        UUID identity = packet.getUUID(ID);
        view = decoded.orElseThrow();
        return Optional.of(identity);
    }
}
