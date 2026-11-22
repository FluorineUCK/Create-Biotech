package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Volatile appearance projection and its packet envelope, independent of installed applications. */
public final class ComputerResidentDisplay {
    public static final String ROOT = "ComputerClientState";
    private final Set<String> requiredKeys;
    private final Set<String> allowedKeys;
    private ComputerResidentView view = ComputerResidentView.EMPTY;

    /** Extension names belong to the physical host's packet schema, never to player programs. */
    public ComputerResidentDisplay(Set<String> requiredExtensions, Set<String> optionalExtensions) {
        var required = new HashSet<>(Set.copyOf(requiredExtensions));
        var optional = Set.copyOf(optionalExtensions);
        if (required.contains(ROOT) || optional.contains(ROOT)
            || required.stream().anyMatch(String::isBlank) || optional.stream().anyMatch(String::isBlank)
            || required.stream().anyMatch(optional::contains))
            throw new IllegalArgumentException("Resident packet extension keys must be disjoint and nonblank");
        required.add(ROOT);
        requiredKeys = Set.copyOf(required);
        required.addAll(optional);
        allowedKeys = Set.copyOf(required);
    }

    public ComputerResidentView view() { return view; }

    public ComputerResidentView refresh(ItemStack snapshot, HolderLookup.Provider registries) {
        view = ComputerResidentView.fromSnapshot(Objects.requireNonNull(snapshot), Objects.requireNonNull(registries));
        return view;
    }

    /** Start a fresh display-only packet; the host appends only its explicitly declared extensions. */
    public void writeFresh(CompoundTag target, ItemStack snapshot, HolderLookup.Provider registries) {
        var next = refresh(snapshot, registries);
        for (String key : List.copyOf(target.getAllKeys())) target.remove(key);
        target.put(ROOT, next.save());
    }

    /** No partial mutation on rejected roots or residents. Extensions may only be read after true. */
    public boolean readPacket(@Nullable CompoundTag packet) {
        if (packet == null || !packet.getAllKeys().containsAll(requiredKeys)
            || !allowedKeys.containsAll(packet.getAllKeys())
            || !(packet.get(ROOT) instanceof CompoundTag resident)) return false;
        var decoded = ComputerResidentView.load(resident);
        if (decoded.isEmpty()) return false;
        view = decoded.orElseThrow();
        return true;
    }
}
