package com.nobodiiiii.createbiotech.content.biocomputing.library;

import java.util.Objects;
import javax.annotation.Nullable;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** Owns a physical Library's captured resident, not book data, recipe state or an entity reference. */
public final class LibraryResident {
    // Keep the existing saved shape while the physical host is migrated independently.
    public static final String SNAPSHOT = "LibrarianSnapshotBox";
    public static final String PENDING_RELEASE = "PendingSafeRelease";
    private State state = new State(ItemStack.EMPTY, null, false);

    public ItemStack snapshot() { return state.snapshot().copy(); }
    public @Nullable CompoundTag rawSnapshot() { return state.raw() == null ? null : state.raw().copy(); }
    public boolean hasSnapshot() {
        return !state.snapshot().isEmpty() && CapturedEntityBoxHelper.hasCapturedEntity(state.snapshot());
    }
    public boolean pendingSafeRelease() { return state.pending(); }
    public void markPendingSafeRelease() { state = new State(state.snapshot(), state.raw(), true); }
    public void clear() { state = new State(ItemStack.EMPTY, null, false); }

    /** Internal host restoration, not a public computation or world-write operation. */
    public void restoreSnapshot(ItemStack snapshot) {
        state = new State(Objects.requireNonNull(snapshot).copy(), null, false);
    }
    public void preserveRawSnapshot(CompoundTag raw) {
        state = new State(ItemStack.EMPTY, Objects.requireNonNull(raw).copy(), true);
    }

    public LibraryResidentCapture.Projection projection(HolderLookup.Provider registries) {
        return LibraryResidentCapture.project(state.snapshot(), registries);
    }

    /** Clear source first; any failed clear or host synchronization restores both owners. */
    public InstallResult install(ItemStack held, CaptureAccess access, Runnable synchronize) {
        Objects.requireNonNull(held); Objects.requireNonNull(access); Objects.requireNonNull(synchronize);
        if (!access.isBox(held)) return InstallResult.NOT_BOX;
        if (!access.hasCapturedEntity(held)) return InstallResult.EMPTY_BOX;
        if (!state.snapshot().isEmpty() || state.raw() != null) return InstallResult.OCCUPIED;
        ItemStack copied = held.copyWithCount(1);
        InstallResult inspected = access.inspect(copied);
        if (inspected != InstallResult.SUCCESS) return inspected == null ? InstallResult.MALFORMED : inspected;
        ItemStack heldBefore = held.copy();
        State before = state;
        try {
            if (!access.clearCapturedEntity(held) || access.hasCapturedEntity(held)) {
                restoreHeldStack(held, heldBefore);
                return InstallResult.CLEAR_FAILED;
            }
        } catch (RuntimeException clearFailure) {
            restoreHeldStack(held, heldBefore);
            return InstallResult.CLEAR_FAILED;
        }
        try {
            state = new State(copied, null, false);
            synchronize.run();
            return InstallResult.SUCCESS;
        } catch (RuntimeException commitFailure) {
            state = before;
            restoreHeldStack(held, heldBefore);
            return InstallResult.COMMIT_FAILED;
        }
    }

    /** Capture owns only our custom data; unrelated item components are not reverted. */
    private static void restoreHeldStack(ItemStack held, ItemStack before) {
        var custom = before.get(DataComponents.CUSTOM_DATA);
        if (custom == null) held.remove(DataComponents.CUSTOM_DATA);
        else held.set(DataComponents.CUSTOM_DATA, custom);
        held.setCount(before.getCount());
    }

    public void write(CompoundTag server, HolderLookup.Provider registries) {
        server.remove(SNAPSHOT);
        if (state.raw() != null) server.put(SNAPSHOT, state.raw().copy());
        else if (!state.snapshot().isEmpty()) server.put(SNAPSHOT, state.snapshot().save(registries));
        server.putBoolean(PENDING_RELEASE, state.pending());
    }

    /** Decode without changing the owner; the host commits only after its entire save is accepted. */
    public Loaded prepareLoad(CompoundTag server, HolderLookup.Provider registries) {
        Tag pending = server.get(PENDING_RELEASE);
        if (pending == null || pending.getId() != Tag.TAG_BYTE
            || (server.getByte(PENDING_RELEASE) != 0 && server.getByte(PENDING_RELEASE) != 1)
            || server.contains(SNAPSHOT) && !(server.get(SNAPSHOT) instanceof CompoundTag))
            throw new IllegalArgumentException("Invalid Library resident save fields");
        boolean nextPending = server.getBoolean(PENDING_RELEASE);
        if (!server.contains(SNAPSHOT)) return new Loaded(new State(ItemStack.EMPTY, null, nextPending), false);
        CompoundTag raw = server.getCompound(SNAPSHOT).copy();
        try {
            ItemStack parsed = ItemStack.parseOptional(registries, raw);
            if (!parsed.isEmpty() && CapturedEntityBoxHelper.hasCapturedEntity(parsed)
                && LibraryResidentCapture.project(parsed, registries).valid())
                return new Loaded(new State(parsed, null, nextPending), false);
        } catch (RuntimeException invalidSnapshot) {
            // Retain the exact serialized value, including unknown item/components, for recovery.
        }
        return new Loaded(new State(state.snapshot(), raw, true), true);
    }

    public void commitLoad(Loaded loaded) { state = Objects.requireNonNull(loaded).state; }

    public static final class Loaded {
        private final State state;
        private final boolean corruption;
        private Loaded(State state, boolean corruption) { this.state = state; this.corruption = corruption; }
        public boolean hadCorruption() { return corruption; }
    }
    // Private snapshots are never mutated or returned directly; state replacement makes rollback atomic.
    private record State(ItemStack snapshot, @Nullable CompoundTag raw, boolean pending) {}

    public interface CaptureAccess {
        boolean isBox(ItemStack stack);
        boolean hasCapturedEntity(ItemStack stack);
        InstallResult inspect(ItemStack copied);
        boolean clearCapturedEntity(ItemStack held);
    }
    public enum InstallResult {
        SUCCESS, NOT_BOX, EMPTY_BOX, OCCUPIED, MALFORMED, UNSUPPORTED_ENTITY, BABY,
        DUPLICATE_IDENTITY, CLEAR_FAILED, COMMIT_FAILED
    }
}
