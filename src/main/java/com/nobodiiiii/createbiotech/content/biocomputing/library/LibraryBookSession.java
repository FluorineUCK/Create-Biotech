package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.ArchiveException;
import java.util.*;

/** Local management selection. Physical updates invalidate selection; no silent rebinding to another book. */
public final class LibraryBookSession {
    private final LibraryBookAccess access;
    private List<LibraryBookAccess.Handle> books = List.of();
    private LibraryBookAccess.Handle selected;
    private Map<String, LibraryEntry> entries = Map.of();
    private int selectedIndex = -1;
    private long revision;
    private String volume = "";
    public LibraryBookSession(LibraryBookAccess access) { this.access = Objects.requireNonNull(access); }
    public long revision() { return revision; }
    public void refresh() {
        revision++; selected = null; selectedIndex = -1; entries = Map.of(); books = List.of(); volume = "";
        books = access.list();
    }
    public void refreshChanged(Set<net.minecraft.core.BlockPos> changed) {
        revision++; selected = null; selectedIndex = -1; entries = Map.of(); books = List.of(); volume = "";
        books = access.listChanged(changed);
    }
    public void select(int index) {
        if (index < 0 || index >= books.size()) throw new LibraryBookAccess.Failure("missing_medium");
        var handle = books.get(index); var observed = access.read(handle);
        var name = access.volumeName(handle).orElse("");
        selected = handle; selectedIndex = index; entries = observed; volume = name; revision++;
    }
    public LibraryEntry read(String name) {
        requireSelection();
        var observed = access.read(selected);
        var entry = observed.get(name); if (entry == null) throw new LibraryBookAccess.Failure("missing_entry");
        entries = observed; return entry;
    }
    public String mutate(long expectedRevision, boolean delete, String name, LibraryEntry entry) {
        if (expectedRevision != revision) return "changed";
        try {
            requireSelection();
            selected = delete ? access.delete(selected, name) : access.put(selected, name, entry);
            var changed = new LinkedHashMap<>(entries);
            if (delete) changed.remove(name); else changed.put(name, entry);
            entries = Collections.unmodifiableMap(changed);
            updateSelectedHandle();
            revision++; return "ok";
        } catch (LibraryBookAccess.Failure failure) { return failure.code(); }
        catch (ArchiveException failure) { return archiveCode(failure); }
    }
    public String renameVolume(long expectedRevision, String name) {
        if (expectedRevision != revision) return "changed";
        try {
            requireSelection(); selected = access.renameVolume(selected, name);
            volume = name; updateSelectedHandle(); revision++; return "ok";
        } catch (LibraryBookAccess.Failure failure) { return failure.code(); }
    }
    private void updateSelectedHandle() {
        var handles = new ArrayList<>(books); handles.set(selectedIndex, selected); books = List.copyOf(handles);
    }
    public DataValue.RecordValue snapshot(LibraryEntry loaded) {
        var locations = books.stream().<DataValue>map(book -> new DataValue.TextValue(
            book.shelf().getX() + "," + book.shelf().getY() + "," + book.shelf().getZ() + " / " + (book.slot() + 1))).toList();
        var names = entries.entrySet().stream().<DataValue>map(entry -> new DataValue.RecordValue(Map.of(
            "name", new DataValue.TextValue(entry.getKey()), "kind", new DataValue.TextValue(entry.getValue().kind().name())))).toList();
        return new DataValue.RecordValue(Map.of("books", new DataValue.ListValue(locations),
            "selected", new DataValue.IntegerValue(selectedIndex), "entries", new DataValue.ListValue(names),
            "volume", new DataValue.TextValue(volume),
            "volumeStatus", new DataValue.TextValue(selected == null ? "" : access.volumeStatus(selected)),
            "loaded", loaded == null ? DataValue.NullValue.INSTANCE : new DataValue.RecordValue(Map.of(
                "kind", new DataValue.TextValue(loaded.kind().name()), "value", loaded.value()))));
    }
    /** Address metadata for the authorized management view; does not seek, reread or authorize a drive. */
    public DataValue.RecordValue snapshot(LibraryEntry loaded,
        java.util.function.Function<LibraryBookAccess.Handle, DataValue.RecordValue> address) {
        var fields = new LinkedHashMap<>(snapshot(loaded).fields());
        fields.put("addresses", new DataValue.ListValue(books.stream().<DataValue>map(address).toList()));
        return new DataValue.RecordValue(fields);
    }
    private void requireSelection() { if (selected == null) throw new LibraryBookAccess.Failure("missing_medium"); }
    public static String archiveCode(ArchiveException failure) {
        return switch (failure.code()) { case LIMIT_EXCEEDED -> "full"; case UNSUPPORTED_VERSION -> "unsupported_version"; case INVALID_FORMAT -> "invalid"; };
    }
}
