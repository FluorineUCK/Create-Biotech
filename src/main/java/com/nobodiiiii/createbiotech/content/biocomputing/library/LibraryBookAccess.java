package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Snapshot-fenced medium access. Remote batch admission and reply durability belong to the service, not this API. */
public final class LibraryBookAccess {
    public interface View {
        boolean mayEdit();
        List<BlockPos> shelves();
        List<ItemStack> contents(BlockPos shelf);
        /** Recheck the whole stack immediately before committing one replacement on the server thread. */
        boolean replace(BlockPos shelf, int slot, ItemStack expected, ItemStack replacement);
    }
    public static final class Failure extends IllegalArgumentException {
        private final String code;
        public Failure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
    public static final class Handle {
        private final BlockPos shelf; private final int slot; private final ItemStack book;
        private Handle(BlockPos shelf, int slot, ItemStack book) {
            this.shelf = shelf.immutable(); this.slot = slot; this.book = book.copy();
        }
        public BlockPos shelf() { return shelf; }
        public int slot() { return slot; }
    }
    private final View view; private final LibraryBookMedia media; private final LibraryBookVolumes volumes;
    // Menu-lifetime directory snapshots only. Every value read/write still rechecks the physical medium.
    private Map<BlockPos, List<Handle>> catalogSnapshots = Map.of();
    public LibraryBookAccess(View view, LibraryBookMedia media, LibraryBookVolumes volumes) {
        this.view = Objects.requireNonNull(view); this.media = Objects.requireNonNull(media); this.volumes = Objects.requireNonNull(volumes);
    }
    public List<Handle> list() { return refreshCatalog(true, Set.of()); }
    public List<Handle> listChanged(Set<BlockPos> changed) { return refreshCatalog(false, Objects.requireNonNull(changed)); }
    /** Selects a real slot, without resolving a volume or reading unrelated shelves. Not an authorization grant. */
    public Handle at(BlockPos shelf, int slot) {
        Objects.requireNonNull(shelf);
        // Six slots are the vanilla chiseled shelf layout, not a configurable capacity upgrade.
        if (slot < 0 || slot >= 6) throw new Failure("invalid_slot");
        if (!view.shelves().contains(shelf)) throw new Failure("missing_track");
        var slots = view.contents(shelf);
        if (slot >= slots.size() || slots.get(slot).isEmpty()) throw new Failure("missing_medium");
        if (!LibraryBookMedia.supports(slots.get(slot))) throw new Failure("unsupported_medium");
        return new Handle(shelf, slot, slots.get(slot));
    }
    private List<Handle> refreshCatalog(boolean all, Set<BlockPos> changed) {
        var updated = new LinkedHashMap<BlockPos, List<Handle>>();
        for (var pos : view.shelves()) {
            if (!all && !changed.contains(pos) && catalogSnapshots.containsKey(pos)) updated.put(pos, catalogSnapshots.get(pos));
            else {
                var result = new ArrayList<Handle>(); var items = view.contents(pos);
                for (int slot = 0; slot < items.size(); slot++)
                    if (LibraryBookMedia.supports(items.get(slot))) result.add(new Handle(pos, slot, items.get(slot)));
                updated.put(pos.immutable(), List.copyOf(result));
            }
        }
        catalogSnapshots = updated;
        return updated.values().stream().flatMap(List::stream).toList();
    }
    /** Owned physical book snapshot; no identity initialization or mutation. Authorization belongs to the positioned caller. */
    public ItemStack bookSnapshot(Handle handle) { return current(handle); }
    public Map<String, LibraryEntry> read(Handle handle) { return media.read(current(handle)); }
    public DataValue.RecordValue stat(Handle handle, String entry) {
        return media.stat(current(handle), entry);
    }
    public LibraryBookMedia.ReadSnapshot snapshot(Handle handle, String entry) { return media.snapshot(current(handle), entry); }
    public LibraryBookMedia.ReadSnapshot statSnapshot(Handle handle, String entry) { return media.statSnapshot(current(handle), entry); }
    public LibraryBookMedia.ReadSnapshot directorySnapshot(Handle handle, LibraryDirectoryQuery query) { return media.directorySnapshot(current(handle), query); }
    public DataValue.RecordValue revision(Handle handle) { return media.revision(current(handle)); }
    /** Internal capability, never a DataValue. The caller must close its conflict batch and preflight the reply first. */
    public record PreparedWrite(DataValue.RecordValue reply, boolean replay, Runnable commit) {
        public PreparedWrite { Objects.requireNonNull(reply); Objects.requireNonNull(commit); }
    }
    public PreparedWrite prepareWrite(Handle handle, MessageEnvelope message) {
        if (!view.mayEdit()) throw new Failure("denied");
        var before = currentVolume(handle, message); var prepared = media.prepareWrite(before, message);
        return preparedWrite(handle, before, prepared);
    }
    public PreparedWrite prepareWrite(Handle handle, LibraryDeviceWrite operation) {
        if (!view.mayEdit()) throw new Failure("denied");
        var before = currentVolume(handle, operation.mutation().volume());
        return preparedWrite(handle, before, media.prepareWrite(before, operation));
    }
    /** Upload admission only. Does not initialize a medium, reserve a book version or create a receipt. */
    public void verifyWriteTarget(Handle handle, String volume, LibraryMutation.Version expected) {
        if (!view.mayEdit()) throw new Failure("denied");
        var book = currentVolume(handle, volume);
        var actual = LibraryMutation.Version.decode(media.stat(book, expected.entry()).field("version"));
        if (!expected.medium().equals(actual.medium())) throw new Failure("medium_changed");
        if (!expected.equals(actual)) throw new Failure("stale_version");
    }
    private PreparedWrite preparedWrite(Handle handle, ItemStack before, LibraryBookMedia.Prepared prepared) {
        return new PreparedWrite(prepared.reply(), prepared.replay(), () -> {
            if (!view.mayEdit()) throw new Failure("denied");
            current(handle);
            if (!prepared.replay()) commit(handle, before, prepared.replacement());
        });
    }
    public void releaseReceipt(Handle handle, MessageEnvelope message) {
        if (!view.mayEdit()) throw new Failure("denied");
        var book = currentVolume(handle, message); var replacement = media.releaseReceipt(book, message);
        if (!ItemStack.matches(book, replacement)) commit(handle, book, replacement);
    }
    public void releaseReceipt(Handle handle, LibraryDeviceWrite operation) {
        if (!view.mayEdit()) throw new Failure("denied");
        var book = currentVolume(handle, operation.mutation().volume()); var replacement = media.releaseReceipt(book, operation);
        if (!ItemStack.matches(book, replacement)) commit(handle, book, replacement);
    }
    private ItemStack currentVolume(Handle handle, MessageEnvelope message) {
        return currentVolume(handle, LibraryMutation.decode(message.payload()).volume());
    }
    private ItemStack currentVolume(Handle handle, String volume) {
        var book = current(handle);
        if (volumes.name(book).filter(volume::equals).isEmpty()) throw new Failure("changed");
        return book;
    }
    public Optional<String> volumeName(Handle handle) { return volumes.name(current(handle)); }
    // Directory metadata only. A value read must still pass current(handle)'s complete physical fence.
    Optional<String> snapshotVolumeName(Handle handle) { return volumes.name(handle.book); }
    /** A full fresh physical directory is required before resolving a public name; never choose a duplicate winner. */
    public Handle findVolume(String name) {
        LibraryBookVolumes.requireName(name);
        Handle match = null; boolean duplicate = false;
        for (var handle : list()) {
            if (!volumes.name(handle.book).filter(name::equals).isPresent()) continue;
            if (match != null) duplicate = true; else match = handle;
        }
        if (duplicate) throw new Failure("duplicate_volume");
        if (match == null) throw new Failure("missing_volume");
        return match;
    }
    public Handle renameVolume(Handle handle, String name) {
        if (!view.mayEdit()) throw new Failure("denied");
        LibraryBookVolumes.requireName(name); var book = current(handle);
        for (var candidate : list()) {
            if (candidate.shelf.equals(handle.shelf) && candidate.slot == handle.slot) continue;
            if (volumes.name(candidate.book).filter(name::equals).isPresent()) throw new Failure("duplicate_volume");
        }
        return commit(handle, book, media.describe(volumes.rename(media.upgrade(book), name)));
    }
    /** Diagnostic from this directory snapshot. It grants no access and does not reread unchanged shelves. */
    public String volumeStatus(Handle handle) {
        try {
            var name = volumes.name(handle.book); if (name.isEmpty()) return "unnamed_volume";
            int matches = 0;
            for (var shelf : catalogSnapshots.values()) for (var candidate : shelf)
                if (volumes.name(candidate.book).equals(name)) matches++;
            return matches > 1 ? "duplicate_volume" : "ok";
        } catch (Failure invalid) { return invalid.code(); }
    }
    public Handle put(Handle handle, String name, LibraryEntry entry) {
        if (!view.mayEdit()) throw new Failure("denied");
        var book = current(handle); return commit(handle, book, media.describe(media.put(book, name, entry)));
    }
    public Handle delete(Handle handle, String name) {
        if (!view.mayEdit()) throw new Failure("denied");
        var book = current(handle);
        if (!media.read(book).containsKey(name)) throw new Failure("missing_entry");
        return commit(handle, book, media.describe(media.delete(book, name)));
    }
    private ItemStack current(Handle handle) {
        Objects.requireNonNull(handle);
        if (!view.shelves().contains(handle.shelf)) throw new Failure("missing_medium");
        var slots = view.contents(handle.shelf);
        if (handle.slot >= slots.size() || !ItemStack.matches(slots.get(handle.slot), handle.book)) throw new Failure("changed");
        return slots.get(handle.slot).copy();
    }
    private Handle commit(Handle handle, ItemStack before, ItemStack replacement) {
        if (!view.mayEdit()) throw new Failure("denied");
        if (!view.replace(handle.shelf, handle.slot, before.copy(), replacement.copy())) throw new Failure("changed");
        var updated = new Handle(handle.shelf, handle.slot, replacement);
        if (catalogSnapshots.containsKey(handle.shelf)) {
            var next = new LinkedHashMap<>(catalogSnapshots);
            next.put(handle.shelf, catalogSnapshots.get(handle.shelf).stream().map(old -> old.slot == handle.slot ? updated : old).toList());
            catalogSnapshots = next;
        }
        return updated;
    }
}
