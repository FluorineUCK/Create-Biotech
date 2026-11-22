package com.nobodiiiii.createbiotech.content.biocomputing.computer.resident;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Physical resident archive only: no production profile, runtime, RAM or live entity reference. */
public final class ComputerResident {
    public static final String SNAPSHOT = "Resident";
    private State state = new State(ItemStack.EMPTY, null);

    public ItemStack snapshot() { return state.snapshot().copy(); }
    public @Nullable Tag raw() { return state.raw() == null ? null : state.raw().copy(); }
    public boolean hasSource() { return hasSnapshot() || hadCorruption(); }
    public boolean hasSnapshot() { return !state.snapshot().isEmpty(); }
    public boolean hadCorruption() { return state.raw() != null; }
    public void clear() { state = new State(ItemStack.EMPTY, null); }

    /** Trusted physical installation/restoration, not an exposed program effect. */
    public void restoreSnapshot(ItemStack snapshot) {
        state = new State(Objects.requireNonNull(snapshot).copy(), null);
    }
    public void preserveRaw(Tag raw) { state = new State(ItemStack.EMPTY, Objects.requireNonNull(raw).copy()); }

    public byte[] serialized(HolderLookup.Provider registries) {
        return state.snapshot().isEmpty() ? new byte[0]
            : state.snapshot().save(registries).toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Preserve the existing field format, including unreadable values. Reusing a tag is safe. */
    public void write(CompoundTag parent, HolderLookup.Provider registries) {
        parent.remove(SNAPSHOT);
        if (state.raw() != null) parent.put(SNAPSHOT, state.raw().copy());
        else if (!state.snapshot().isEmpty()) parent.put(SNAPSHOT, state.snapshot().save(registries));
    }

    public Loaded prepareLoad(@Nullable Tag raw, HolderLookup.Provider registries) {
        if (raw == null) return new Loaded(new State(ItemStack.EMPTY, null));
        if (raw instanceof CompoundTag compound) {
            try {
                ItemStack parsed = ItemStack.parseOptional(registries, compound);
                Identity identity = identity(parsed);
                if (!parsed.isEmpty() && CapturedEntityBoxHelper.hasCapturedEntity(parsed)
                    && identity != null && !identity.passengers()) return new Loaded(new State(parsed.copy(), null));
            } catch (RuntimeException unreadable) {
                // Keep the exact source; an unavailable item/component must not become an empty resident.
            }
        }
        return new Loaded(new State(ItemStack.EMPTY, raw.copy()));
    }
    public void commitLoad(Loaded loaded) { state = Objects.requireNonNull(loaded).state; }

    public static final class Loaded {
        private final State state;
        private Loaded(State state) { this.state = state; }
        public boolean hadCorruption() { return state.raw() != null; }
    }
    private record State(ItemStack snapshot, @Nullable Tag raw) {}

    /** Strict saved identity; describing a known entity type does not instantiate or authorize it. */
    public static @Nullable Identity identity(ItemStack stack) {
        Tag raw = CBItemData.getOrEmpty(stack).get("CapturedEntity");
        if (!(raw instanceof CompoundTag captured)
            || !(captured.get("UUID") instanceof IntArrayTag uuid) || uuid.getAsIntArray().length != 4
            || !captured.hasUUID("UUID") || !captured.contains("id", Tag.TAG_STRING)) return null;
        ResourceLocation id = ResourceLocation.tryParse(captured.getString("id"));
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return null;
        Tag passengers = captured.get("Passengers");
        if (passengers != null && !(passengers instanceof ListTag)) return null;
        return new Identity(captured.getUUID("UUID"), id, passengers instanceof ListTag list && !list.isEmpty());
    }
    public record Identity(UUID uuid, ResourceLocation id, boolean passengers) {}
}
