package com.nobodiiiii.createbiotech.content.biocomputing.network;

import java.util.*;
import java.util.function.Consumer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.DimensionDataStorage;

/**
 * Dimension-owned connection declarations, not transport authority or in-flight exchange history.
 * Server-thread configuration service only: callers must authorize the player and bind real cable endpoints.
 */
public final class XBusWiringSavedData extends SavedData {
    private static final String DATA_NAME = "create_biotech_xbus_wiring";
    private static final Set<EnabledConnection> NO_CONNECTIONS = Set.of();
    private UUID epoch = UUID.randomUUID();
    private final Map<UUID, View> owners = new LinkedHashMap<>();
    // Derived only from declarations. Immutable neighborhoods also fence transient component captures.
    private final Map<XBusNetwork.Pin, Set<EnabledConnection>> neighborhoods = new HashMap<>();
    // Weak transient leases retain no removed-pin tombstones or abandoned per-tick captures.
    private final Set<ComponentView> captures = Collections.newSetFromMap(new WeakHashMap<>());
    private CompoundTag unreadable;
    private boolean unreadableFile;

    public record Entry(String name, XBusCableTarget origin, XBusCableTarget peer, boolean enabled) {
        public Entry(String name, XBusCableTarget origin, XBusCableTarget peer) { this(name, origin, peer, false); }
        public Entry {
            new EndpointConfiguration(name, true);
            Objects.requireNonNull(origin); Objects.requireNonNull(peer);
            if (origin.computer().equals(peer.computer()) && origin.pin().equals(peer.pin()))
                throw new IllegalArgumentException("self_pin");
        }
    }
    public record View(UUID epoch, UUID device, long revision, List<Entry> entries) {
        public View {
            Objects.requireNonNull(epoch); Objects.requireNonNull(device);
            entries = List.copyOf(entries);
            if (revision < 0) throw new IllegalArgumentException("invalid_revision");
            var names = new HashSet<String>();
            for (var entry : entries) {
                if (!entry.origin().device().equals(device)) throw new IllegalArgumentException("owner_mismatch");
                if (!names.add(entry.name())) throw new IllegalArgumentException("duplicate_name");
            }
        }
    }
    /** Configuration proof only. Not an access grant, payload, history witness or arbitration order. */
    public record EnabledConnection(View owner, Entry entry) {
        public EnabledConnection {
            Objects.requireNonNull(owner); Objects.requireNonNull(entry);
            if (!entry.enabled() || !owner.entries().contains(entry)) throw new IllegalArgumentException("invalid_selection");
        }
    }
    public static XBusWiringSavedData get(ServerLevel level) {
        var storage = level.getDataStorage();
        var existing = storage.get(new SavedData.Factory<>(XBusWiringSavedData::new, XBusWiringSavedData::load), DATA_NAME);
        if (existing != null) return existing;
        var directory = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("data");
        return createMissing(storage, directory);
    }
    static XBusWiringSavedData get(DimensionDataStorage storage, Path directory) {
        var existing = storage.get(new SavedData.Factory<>(XBusWiringSavedData::new, XBusWiringSavedData::load), DATA_NAME);
        return existing != null ? existing : createMissing(storage, directory);
    }
    private static XBusWiringSavedData createMissing(DimensionDataStorage storage, Path directory) {
        // Vanilla get() returns null for both absent files and read/parse errors. Only absence permits creation.
        var file = directory.resolve(DATA_NAME + ".dat");
        boolean absent = false;
        try { Files.readAttributes(file, BasicFileAttributes.class); }
        catch (NoSuchFileException missing) { absent = true; }
        catch (IOException | SecurityException inaccessible) { /* Do not interpret denied I/O as an empty store. */ }
        var created = new XBusWiringSavedData(); created.unreadableFile = !absent;
        storage.set(DATA_NAME, created);
        return created;
    }
    public View view(UUID device) {
        requireReadable();
        Objects.requireNonNull(device);
        return owners.getOrDefault(device, new View(epoch, device, 0, List.of()));
    }
    public List<EnabledConnection> enabledConnections() {
        requireReadable();
        return owners.values().stream().flatMap(owner -> owner.entries().stream().filter(Entry::enabled)
            .map(entry -> new EnabledConnection(owner, entry))).toList();
    }
    /** Callback-free final fence: disabling/removing/recreating never resurrects a captured revision. */
    public boolean current(EnabledConnection connection) {
        Objects.requireNonNull(connection);
        return !unreadableFile && unreadable == null && connection.owner().epoch().equals(epoch)
            && connection.owner().equals(owners.get(connection.owner().device()));
    }
    /** No callbacks or world scans: another owner's new branch must invalidate the physical component too. */
    final class ComponentView {
        private final Map<XBusNetwork.Pin, Set<EnabledConnection>> captured;
        private boolean invalidated;
        private ComponentView(Map<XBusNetwork.Pin, Set<EnabledConnection>> captured) { this.captured = Map.copyOf(captured); }
        boolean current() {
            return !invalidated && !unreadableFile && unreadable == null && captured.entrySet().stream()
                .allMatch(entry -> connections(entry.getKey()) == entry.getValue());
        }
    }
    Set<EnabledConnection> connections(XBusNetwork.Pin pin) {
        requireReadable();
        return neighborhoods.getOrDefault(pin, NO_CONNECTIONS);
    }
    ComponentView componentView(List<EnabledConnection> connections) {
        return componentView(connections.stream().flatMap(c -> pins(c.entry()).stream()).toList(), connections);
    }
    ComponentView componentView(Collection<XBusNetwork.Pin> componentPins, List<EnabledConnection> connections) {
        requireReadable();
        var selected = new HashSet<>(connections);
        var captured = new HashMap<XBusNetwork.Pin, Set<EnabledConnection>>();
        for (var pin : componentPins) captured.put(Objects.requireNonNull(pin), connections(pin));
        for (var connection : connections) {
            if (!current(connection)) throw new IllegalArgumentException("stale");
            if (!captured.keySet().containsAll(pins(connection.entry()))) throw new IllegalArgumentException("incomplete_configuration");
        }
        for (var neighbors : captured.values())
            if (neighbors.size() > selected.size() || !selected.containsAll(neighbors))
                throw new IllegalArgumentException("incomplete_configuration");
        var result = new ComponentView(captured); captures.add(result); return result;
    }
    private static List<XBusNetwork.Pin> pins(Entry entry) {
        return List.of(new XBusNetwork.Pin(entry.origin().computer(), entry.origin().pin()),
            new XBusNetwork.Pin(entry.peer().computer(), entry.peer().pin()));
    }
    private void index(View previous, View next) {
        var changes = new HashMap<XBusNetwork.Pin, Set<EnabledConnection>>();
        if (previous != null) for (var entry : previous.entries()) if (entry.enabled()) {
            var connection = new EnabledConnection(previous, entry);
            for (var pin : pins(entry)) changes.computeIfAbsent(pin,
                key -> new HashSet<>(neighborhoods.getOrDefault(key, Set.of()))).remove(connection);
        }
        for (var entry : next.entries()) if (entry.enabled()) {
            var connection = new EnabledConnection(next, entry);
            for (var pin : pins(entry)) changes.computeIfAbsent(pin,
                key -> new HashSet<>(neighborhoods.getOrDefault(key, Set.of()))).add(connection);
        }
        // A new branch followed by removal must also revoke a formerly internal-only component.
        captures.removeIf(capture -> {
            if (Collections.disjoint(capture.captured.keySet(), changes.keySet())) return false;
            capture.invalidated = true; return true;
        });
        for (var entry : changes.entrySet()) {
            if (entry.getValue().isEmpty()) neighborhoods.remove(entry.getKey());
            else neighborhoods.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
    }
    private void requireReadable() {
        if (unreadableFile || unreadable != null) throw new IllegalArgumentException("invalid_save");
    }
    public View add(View expected, String name, XBusCableTarget origin, XBusCableTarget peer,
            int maxEntries, Consumer<View> preflight) {
        requireCurrent(expected);
        if (maxEntries <= expected.entries().size()) throw new IllegalArgumentException("capacity");
        var entries = new ArrayList<>(expected.entries());
        entries.add(new Entry(name, origin, peer));
        return publish(expected, entries, preflight);
    }
    public View setEnabled(View expected, String name, boolean enabled, Consumer<View> preflight) {
        requireCurrent(expected); new EndpointConfiguration(name, true);
        var selected = expected.entries().stream().filter(entry -> entry.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("not_found"));
        var entries = expected.entries().stream().map(entry -> entry == selected
            ? new Entry(entry.name(), entry.origin(), entry.peer(), enabled) : entry).toList();
        return publish(expected, entries, preflight);
    }
    /** Removing a declaration invalidates its captures, but cannot retire any transport history. */
    public View remove(View expected, String name, Consumer<View> preflight) {
        requireCurrent(expected); new EndpointConfiguration(name, true);
        var entries = new ArrayList<>(expected.entries());
        if (!entries.removeIf(entry -> entry.name().equals(name))) throw new IllegalArgumentException("not_found");
        return publish(expected, entries, preflight);
    }
    private View publish(View expected, List<Entry> entries, Consumer<View> preflight) {
        if (expected.revision() == Long.MAX_VALUE) throw new IllegalArgumentException("revision_exhausted");
        var next = new View(epoch, expected.device(), Math.incrementExact(expected.revision()), entries);
        preflight.accept(next);
        requireCurrent(expected);
        index(owners.get(next.device()), next);
        owners.put(next.device(), next); setDirty();
        return next;
    }
    private void requireCurrent(View expected) {
        if (!view(expected.device()).equals(expected)) throw new IllegalArgumentException("stale");
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (unreadableFile) throw new IllegalArgumentException("invalid_save");
        if (unreadable != null) return unreadable.copy();
        tag.putInt("Version", 2); tag.putUUID("Epoch", epoch);
        var savedOwners = new ListTag();
        for (var view : owners.values()) {
            var owner = new CompoundTag(); owner.putUUID("Device", view.device());
            owner.putLong("Revision", view.revision());
            var entries = new ListTag();
            for (var entry : view.entries()) {
                var value = new CompoundTag(); value.putString("Name", entry.name());
                value.put("Origin", encode(entry.origin())); value.put("Peer", encode(entry.peer()));
                value.putBoolean("Enabled", entry.enabled());
                entries.add(value);
            }
            owner.put("Entries", entries); savedOwners.add(owner);
        }
        tag.put("Owners", savedOwners); return tag;
    }
    @Override public void save(File file, HolderLookup.Provider registries) {
        // Even an unrelated dirty mark must not replace bytes that could not be decoded in the first place.
        if (!unreadableFile) super.save(file, registries);
    }
    public static XBusWiringSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new XBusWiringSavedData();
        try {
            data.read(tag);
            for (var view : data.owners.values()) data.index(null, view);
        }
        catch (IllegalArgumentException invalid) { data.owners.clear(); data.unreadable = tag.copy(); }
        return data;
    }
    private void read(CompoundTag tag) {
        fields(tag, "Version", "Epoch", "Owners"); requireType(tag, "Version", Tag.TAG_INT);
        int version = tag.getInt("Version");
        if (version != 1 && version != 2) throw new IllegalArgumentException("invalid_version");
        epoch = tag.getUUID("Epoch"); compounds(tag, "Owners");
        for (var raw : tag.getList("Owners", Tag.TAG_COMPOUND)) {
            var owner = (CompoundTag)raw; var entries = new ArrayList<Entry>();
            fields(owner, "Device", "Revision", "Entries"); requireType(owner, "Revision", Tag.TAG_LONG);
            compounds(owner, "Entries");
            for (var value : owner.getList("Entries", Tag.TAG_COMPOUND)) {
                var entry = (CompoundTag)value;
                if (version == 1) fields(entry, "Name", "Origin", "Peer");
                else {
                    fields(entry, "Name", "Origin", "Peer", "Enabled"); requireType(entry, "Enabled", Tag.TAG_BYTE);
                    if (entry.getByte("Enabled") != 0 && entry.getByte("Enabled") != 1) throw new IllegalArgumentException("invalid_boolean");
                }
                requireType(entry, "Name", Tag.TAG_STRING);
                requireType(entry, "Origin", Tag.TAG_COMPOUND); requireType(entry, "Peer", Tag.TAG_COMPOUND);
                entries.add(new Entry(entry.getString("Name"), decode(entry.getCompound("Origin")), decode(entry.getCompound("Peer")),
                    version == 2 && entry.getBoolean("Enabled")));
            }
            var view = new View(epoch, owner.getUUID("Device"), owner.getLong("Revision"), entries);
            if (view.revision() == 0 || owners.putIfAbsent(view.device(), view) != null)
                throw new IllegalArgumentException("invalid_owner");
        }
    }
    private static CompoundTag encode(XBusCableTarget target) {
        var tag = new CompoundTag(); var pos = target.position();
        tag.putIntArray("Position", new int[] {pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("Domain", target.domain()); tag.putString("Endpoint", target.endpoint());
        tag.putString("Pin", target.pin()); tag.putUUID("Device", target.device()); tag.putUUID("Computer", target.computer());
        return tag;
    }
    private static XBusCableTarget decode(CompoundTag tag) {
        fields(tag, "Position", "Domain", "Endpoint", "Pin", "Device", "Computer");
        requireType(tag, "Position", Tag.TAG_INT_ARRAY); var pos = tag.getIntArray("Position");
        if (pos.length != 3) throw new IllegalArgumentException("invalid_position");
        for (String key : List.of("Domain", "Endpoint", "Pin")) requireType(tag, key, Tag.TAG_STRING);
        return new XBusCableTarget(new BlockPos(pos[0], pos[1], pos[2]), tag.getString("Domain"),
            tag.getString("Endpoint"), tag.getString("Pin"), tag.getUUID("Device"), tag.getUUID("Computer"));
    }
    private static void fields(CompoundTag tag, String... names) {
        if (!tag.getAllKeys().equals(Set.of(names))) throw new IllegalArgumentException("invalid_fields");
    }
    private static void requireType(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("invalid_type");
    }
    private static void compounds(CompoundTag tag, String key) {
        requireType(tag, key, Tag.TAG_LIST);
        var list = (ListTag)tag.get(key);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("invalid_type");
    }
}
