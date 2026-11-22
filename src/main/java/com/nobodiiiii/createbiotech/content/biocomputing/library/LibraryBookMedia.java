package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.messaging.MessageEnvelope;
import com.nobodiiiii.createbiotech.content.biocomputing.library.service.LibraryMutation;
import com.nobodiiiii.createbiotech.content.biocomputing.persistence.*;
import java.util.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;

/** Copy-on-write for one dedicated book component. Never touches original text, recipe pages or other components. */
public final class LibraryBookMedia {
    private final DataComponentType<CustomData> component;
    private final ArchiveLimits limits;
    private final int maxBytes;
    public LibraryBookMedia(DataComponentType<CustomData> component, ArchiveLimits limits) {
        this(component, limits, Integer.MAX_VALUE);
    }
    public LibraryBookMedia(DataComponentType<CustomData> component, ArchiveLimits limits, int maxBytes) {
        this.component = Objects.requireNonNull(component); this.limits = Objects.requireNonNull(limits);
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid book byte capacity");
        this.maxBytes = maxBytes;
    }
    public static boolean supports(ItemStack book) {
        return book.getCount() == 1 && (book.is(Items.WRITABLE_BOOK) || book.is(Items.WRITTEN_BOOK));
    }
    public Map<String, LibraryEntry> read(ItemStack book) {
        return contents(book).entries();
    }
    /** A read-only, persistable data-region witness. Legacy media remain readable without gaining an identity. */
    public DataValue.RecordValue revision(ItemStack book) { return revision(contents(book)); }
    public record ReadSnapshot(DataValue.RecordValue revision, DataValue.RecordValue value) {}
    /** Metadata only, in stable name order. Each page has its own snapshot; the caller owns traversal. */
    public ReadSnapshot directorySnapshot(ItemStack book, LibraryDirectoryQuery query) {
        var contents = contents(book);
        NavigableMap<String, LibraryEntry> ordered = new TreeMap<>(contents.entries());
        if (query.after().isPresent()) ordered = ordered.tailMap(query.after().orElseThrow(), false);
        var entries = ordered.entrySet().iterator(); var rows = new ArrayList<DataValue>(); String last = null;
        while (rows.size() < query.count() && entries.hasNext()) {
            var entry = entries.next(); last = entry.getKey();
            rows.add(new DataValue.RecordValue(Map.of("name", new DataValue.TextValue(last), "kind", new DataValue.TextValue(entry.getValue().kind().name()))));
        }
        DataValue version = contents.medium() == null ? DataValue.NullValue.INSTANCE : new DataValue.RecordValue(Map.of(
            "medium", new DataValue.TextValue(contents.medium().toString()), "counter", new DataValue.IntegerValue(contents.counter())));
        return new ReadSnapshot(revision(contents), new DataValue.RecordValue(Map.of("entries", new DataValue.ListValue(rows),
            "next", entries.hasNext() ? new DataValue.TextValue(last) : DataValue.NullValue.INSTANCE, "version", version)));
    }
    /** Value, version and witness are decoded from the same copied book. Does not initialize or edit it. */
    public ReadSnapshot snapshot(ItemStack book, String name) {
        requireName(name); var contents = contents(book); var entry = contents.entries().get(name);
        if (entry == null) throw new LibraryBookAccess.Failure("missing_entry");
        return new ReadSnapshot(revision(contents), new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue(entry.kind().name()), "value", entry.value(),
            "version", contents.medium() == null ? DataValue.NullValue.INSTANCE : version(contents, name).value())));
    }
    private static DataValue.RecordValue revision(Contents contents) {
        // Versioned edits advance the durable counter; receipt release does not change entry data.
        // Legacy books have no identity: compare their complete typed data instead, never Java hashes.
        return new DataValue.RecordValue(Map.of(
            "medium", contents.medium() == null ? DataValue.NullValue.INSTANCE : new DataValue.TextValue(contents.medium().toString()),
            "counter", new DataValue.IntegerValue(contents.counter()),
            "legacy", contents.medium() == null ? entryValues(contents.entries()) : DataValue.NullValue.INSTANCE));
    }
    private static DataValue.RecordValue entryValues(Map<String, LibraryEntry> entries) {
        var fields = new LinkedHashMap<String, DataValue>();
        entries.forEach((name, entry) -> fields.put(name, new DataValue.RecordValue(Map.of(
            "kind", new DataValue.TextValue(entry.kind().name()), "value", entry.value()))));
        return new DataValue.RecordValue(fields);
    }
    private record Contents(UUID medium, long counter, Map<String, Long> revisions, Map<String, LibraryEntry> entries,
        Map<String, LibraryWriteReceipt> receipts) {}
    private Contents contents(ItemStack book) {
        requireBook(book);
        var stored = book.get(component);
        if (stored == null) return new Contents(null, 0, Map.of(), Map.of(), Map.of());
        var decoded = ValueNbtCodec.decode(stored.copyTag(), limits);
        if (!(decoded instanceof DataValue.RecordValue object)) throw invalid("Expected book record");
        if (!(object.fields().get("version") instanceof DataValue.IntegerValue version) || version.value() < 1 || version.value() > 4)
            throw new ArchiveException(ArchiveException.Code.UNSUPPORTED_VERSION, "Unsupported book entries version");
        var root = record(decoded, version.value() == 1 ? Set.of("version", "entries")
            : version.value() == 2 ? Set.of("version", "entries", "medium", "counter", "revisions")
            : Set.of("version", "entries", "medium", "counter", "revisions", "receipts"));
        if (!(root.get("entries") instanceof DataValue.RecordValue entries)) throw invalid("Expected named entries");
        var result = new LinkedHashMap<String, LibraryEntry>();
        entries.fields().forEach((name, value) -> {
            requireName(name); var fields = record(value, Set.of("kind", "value"));
            if (!(fields.get("kind") instanceof DataValue.TextValue kind)) throw invalid("Missing entry kind");
            try { result.put(name, new LibraryEntry(LibraryEntry.Kind.valueOf(kind.value()), fields.get("value"))); }
            catch (IllegalArgumentException invalid) { throw invalid("Invalid library entry: " + name); }
        });
        var immutable = Collections.unmodifiableMap(result);
        if (version.value() == 1) return new Contents(null, 0, Map.of(), immutable, Map.of());
        UUID medium;
        try {
            if (!(root.get("medium") instanceof DataValue.TextValue id)) throw invalid("Missing medium identity");
            medium = UUID.fromString(id.value());
            if (!medium.toString().equals(id.value())) throw invalid("Noncanonical medium identity");
        } catch (IllegalArgumentException malformed) { throw invalid("Invalid medium identity"); }
        if (!(root.get("counter") instanceof DataValue.IntegerValue counter) || counter.value() < 0)
            throw invalid("Invalid medium revision counter");
        if (!(root.get("revisions") instanceof DataValue.RecordValue revisions)) throw invalid("Expected entry revisions");
        var numbers = new LinkedHashMap<String, Long>();
        revisions.fields().forEach((name, value) -> {
            requireName(name);
            if (!(value instanceof DataValue.IntegerValue number) || number.value() <= 0 || number.value() > counter.value())
                throw invalid("Invalid entry revision");
            numbers.put(name, number.value());
        });
        if (!numbers.keySet().containsAll(result.keySet())) throw invalid("Missing live entry revision");
        var receipts = new LinkedHashMap<String, LibraryWriteReceipt>();
        if (version.value() >= 3) {
            if (!(root.get("receipts") instanceof DataValue.RecordValue values)) throw invalid("Expected completion receipts");
            values.fields().forEach((key, value) -> {
                try {
                    var receipt = LibraryWriteReceipt.decode(value);
                    if (version.value() < 4 && receipt instanceof LibraryDeviceWrite.Receipt) throw invalid("Device receipt requires book schema4");
                    var token = LibraryWriteReceipt.version(receipt.reply());
                    if (!key.equals(receipt.key()) || !token.medium().equals(medium)
                        || token.revision() > numbers.getOrDefault(token.entry(), 0L)) throw invalid("Receipt is not owned by this medium revision");
                    receipts.put(key, receipt);
                } catch (IllegalArgumentException malformed) { throw invalid("Invalid book receipt"); }
            });
        }
        return new Contents(medium, counter.value(), Collections.unmodifiableMap(numbers), immutable, Collections.unmodifiableMap(receipts));
    }
    /** Only an explicit local edit may initialize identity. Reading never upgrades a medium. */
    public ItemStack upgrade(ItemStack book) {
        var contents = contents(book);
        if (contents.medium() != null) return book.copy();
        return replace(book, initialize(contents));
    }
    /** Explicit local management only; ordinary reads and remote data transactions never edit text. */
    public ItemStack describe(ItemStack book) {
        var contents = contents(book);
        if (contents.medium() == null) throw new LibraryBookAccess.Failure("unversioned_medium");
        return LibraryBookIdentityPage.describe(book, contents.medium());
    }
    private static Contents initialize(Contents contents) {
        if (contents.medium() != null) return contents;
        var revisions = new LinkedHashMap<String, Long>();
        contents.entries().keySet().forEach(name -> revisions.put(name, 1L));
        return new Contents(UUID.randomUUID(), revisions.isEmpty() ? 0 : 1, revisions, contents.entries(), Map.of());
    }
    /** Value and opaque expected-version token are from the same immutable medium snapshot. */
    public DataValue.RecordValue stat(ItemStack book, String name) {
        return statSnapshot(book, name).value();
    }
    /** Existence, creation/update version and witness share the same immutable decoded medium. */
    public ReadSnapshot statSnapshot(ItemStack book, String name) {
        requireName(name); var contents = contents(book);
        if (contents.medium() == null) throw new LibraryBookAccess.Failure("unversioned_medium");
        var entry = contents.entries().get(name);
        var version = version(contents, name).value();
        return new ReadSnapshot(revision(contents), new DataValue.RecordValue(Map.of("present", new DataValue.BooleanValue(entry != null),
            "kind", entry == null ? DataValue.NullValue.INSTANCE : new DataValue.TextValue(entry.kind().name()),
            "value", entry == null ? DataValue.NullValue.INSTANCE : entry.value(), "version", version)));
    }
    private static LibraryMutation.Version version(Contents contents, String name) {
        return new LibraryMutation.Version(contents.medium(), name, contents.revisions().getOrDefault(name, 0L));
    }
    public record Prepared(ItemStack replacement, DataValue.RecordValue reply, boolean replay) {
        public Prepared { replacement = replacement.copy(); Objects.requireNonNull(reply); }
        @Override public ItemStack replacement() { return replacement.copy(); }
    }
    public Prepared prepareWrite(ItemStack book, LibraryDeviceWrite operation) {
        Objects.requireNonNull(operation);
        return prepareWrite(book, operation.mutation(), operation.key(), receipt -> replay(receipt, operation),
            reply -> new LibraryDeviceWrite.Receipt(operation, reply));
    }
    /** Pure candidate preparation. The host must preflight its reply and fence the physical slot before committing. */
    public Prepared prepareWrite(ItemStack book, MessageEnvelope message) {
        return prepareWrite(book, LibraryMutation.decode(message.payload()), LibraryMutation.Receipt.key(message),
            receipt -> replay(receipt, message), reply -> new LibraryMutation.Receipt(message, reply));
    }
    private Prepared prepareWrite(ItemStack book, LibraryMutation mutation, String key,
        java.util.function.Function<LibraryWriteReceipt, DataValue.RecordValue> replay,
        java.util.function.Function<DataValue.RecordValue, LibraryWriteReceipt> completion) {
        var contents = contents(book);
        if (contents.medium() == null) throw new LibraryBookAccess.Failure("unversioned_medium");
        if (!contents.medium().equals(mutation.expected().medium())) throw new LibraryBookAccess.Failure("medium_changed");
        var previous = contents.receipts().get(key);
        if (previous != null) return new Prepared(book, replay.apply(previous), true);
        mutation.requireVersion(version(contents, mutation.entry()));
        var entries = new LinkedHashMap<>(contents.entries());
        if (mutation.replacement().isPresent()) entries.put(mutation.entry(), mutation.replacement().orElseThrow());
        else if (entries.remove(mutation.entry()) == null) throw new LibraryBookAccess.Failure("missing_entry");
        var next = edited(contents, mutation.entry(), entries);
        var reply = new DataValue.RecordValue(Map.of("code", new DataValue.TextValue("ok"), "data",
            new DataValue.RecordValue(Map.of("version", version(next, mutation.entry()).value()))));
        var receipts = new LinkedHashMap<>(next.receipts());
        receipts.put(key, completion.apply(reply));
        return new Prepared(replace(book, new Contents(next.medium(), next.counter(), next.revisions(), next.entries(), receipts)), reply, false);
    }
    /** A confirmed reply may release its exact receipt, never its entry/version or another request's evidence. */
    public ItemStack releaseReceipt(ItemStack book, MessageEnvelope message) {
        return releaseReceipt(book, LibraryMutation.decode(message.payload()), LibraryMutation.Receipt.key(message), receipt -> replay(receipt, message));
    }
    public ItemStack releaseReceipt(ItemStack book, LibraryDeviceWrite operation) {
        return releaseReceipt(book, operation.mutation(), operation.key(), receipt -> replay(receipt, operation));
    }
    private ItemStack releaseReceipt(ItemStack book, LibraryMutation mutation, String key,
        java.util.function.Function<LibraryWriteReceipt, DataValue.RecordValue> replay) {
        var contents = contents(book);
        if (!mutation.expected().medium().equals(contents.medium())) throw new LibraryBookAccess.Failure("medium_changed");
        var previous = contents.receipts().get(key);
        if (previous == null) return book.copy();
        replay.apply(previous);
        var remaining = new LinkedHashMap<>(contents.receipts()); remaining.remove(key);
        return replace(book, new Contents(contents.medium(), contents.counter(), contents.revisions(), contents.entries(), remaining));
    }
    private static DataValue.RecordValue replay(LibraryWriteReceipt receipt, MessageEnvelope message) {
        if (receipt instanceof LibraryMutation.Receipt legacy) return legacy.replay(message);
        throw new LibraryBookAccess.Failure("identity_conflict");
    }
    private static DataValue.RecordValue replay(LibraryWriteReceipt receipt, LibraryDeviceWrite operation) {
        if (receipt instanceof LibraryDeviceWrite.Receipt device) return device.replay(operation);
        throw new LibraryBookAccess.Failure("identity_conflict");
    }
    public ItemStack put(ItemStack book, String name, LibraryEntry entry) {
        requireName(name); Objects.requireNonNull(entry);
        var contents = initialize(contents(book));
        var entries = new LinkedHashMap<>(contents.entries()); entries.put(name, entry);
        return replace(book, edited(contents, name, entries));
    }
    public ItemStack delete(ItemStack book, String name) {
        requireName(name); var contents = initialize(contents(book));
        var entries = new LinkedHashMap<>(contents.entries());
        if (entries.remove(name) == null) throw new IllegalArgumentException("missing_entry");
        return replace(book, edited(contents, name, entries));
    }
    private static Contents edited(Contents before, String name, Map<String, LibraryEntry> entries) {
        if (before.counter() == Long.MAX_VALUE) throw new LibraryBookAccess.Failure("version_exhausted");
        long revision = before.counter() + 1;
        var revisions = new LinkedHashMap<>(before.revisions());
        // Retain deleted names: pruning tombstones would revive an earlier absence token (ABA).
        revisions.put(name, revision);
        return new Contents(before.medium(), revision, revisions, entries, before.receipts());
    }
    private ItemStack replace(ItemStack book, Contents contents) {
        var root = new LinkedHashMap<String, DataValue>();
        root.put("version", new DataValue.IntegerValue(contents.receipts().values().stream()
            .anyMatch(LibraryDeviceWrite.Receipt.class::isInstance) ? 4 : 3));
        root.put("entries", entryValues(contents.entries()));
        root.put("medium", new DataValue.TextValue(contents.medium().toString()));
        root.put("counter", new DataValue.IntegerValue(contents.counter()));
        var revisions = new LinkedHashMap<String, DataValue>();
        contents.revisions().forEach((name, revision) -> revisions.put(name, new DataValue.IntegerValue(revision)));
        root.put("revisions", new DataValue.RecordValue(revisions));
        var receipts = new LinkedHashMap<String, DataValue>();
        contents.receipts().forEach((key, receipt) -> receipts.put(key, receipt.value()));
        root.put("receipts", new DataValue.RecordValue(receipts));
        var value = new DataValue.RecordValue(root);
        var tag = ValueNbtCodec.encode(value, limits);
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(Math.min(256, maxBytes), maxBytes));
        try { buffer.writeNbt(tag); }
        catch (RuntimeException exceeded) { throw new ArchiveException(ArchiveException.Code.LIMIT_EXCEEDED, "Book component byte capacity exceeded"); }
        finally { buffer.release(); }
        var data = CustomData.of(tag);
        var copy = book.copy(); copy.set(component, data); return copy;
    }
    private static void requireBook(ItemStack book) {
        if (!supports(Objects.requireNonNull(book))) throw new IllegalArgumentException("unsupported_medium");
    }
    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw invalid("Blank library entry name");
    }
    private static Map<String, DataValue> record(DataValue value, Set<String> fields) {
        if (!(value instanceof DataValue.RecordValue record) || !record.fields().keySet().equals(fields))
            throw invalid("Unexpected book entry schema");
        return record.fields();
    }
    private static ArchiveException invalid(String detail) {
        return new ArchiveException(ArchiveException.Code.INVALID_FORMAT, detail);
    }
}
