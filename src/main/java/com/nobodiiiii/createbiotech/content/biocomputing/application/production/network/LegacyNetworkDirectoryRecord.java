package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Decode only the two historical logistics directory records, never the journal's generic object graph. */
final class LegacyNetworkDirectoryRecord {
    private static final String PREFIX = "com.nobodiiiii.createbiotech.content.factorycluster.logistics.";
    private static final String DIRECTORY = PREFIX + "LogisticsNetworkDirectory$State";
    private static final String ENTRY = PREFIX + "LogisticsNetworkDirectoryEntry";
    private LegacyNetworkDirectoryRecord() {}

    static LogisticsNetworkDirectory.State decode(CompoundTag root) throws IOException {
        exact(root, "Version", "Type", "Value");
        require(root.contains("Version", Tag.TAG_INT) && Set.of(1, 2, 4, 5, 6).contains(root.getInt("Version"))
            && DIRECTORY.equals(root.getString("Type")));
        var state = child(root, "Value");
        record(state, DIRECTORY, "entries", "nextOrdinal");
        var map = child(state, "entries");
        exact(map, "Kind", "Entries");
        require("map".equals(map.getString("Kind")));
        if (!(map.get("Entries") instanceof ListTag list) || list.size() > LogisticsNetworkDirectory.MAX_NETWORKS
                || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw invalid();
        var entries = new HashMap<UUID, LogisticsNetworkDirectoryEntry>();
        for (int i = 0; i < list.size(); i++) {
            var pair = list.getCompound(i);
            exact(pair, "Key", "Value");
            var key = uuid(child(pair, "Key"));
            var item = child(pair, "Value");
            record(item, ENTRY, "logisticsNetworkId", "ordinal", "customAlias", "revision");
            var entry = new LogisticsNetworkDirectoryEntry(uuid(child(item, "logisticsNetworkId")),
                number(child(item, "ordinal")), alias(child(item, "customAlias")), number(child(item, "revision")));
            require(entries.putIfAbsent(key, entry) == null);
        }
        // The same constructors as the current directory validate identities, revisions and the ordinal frontier.
        return new LogisticsNetworkDirectory.State(entries, number(child(state, "nextOrdinal")));
    }

    private static Optional<String> alias(CompoundTag tag) throws IOException {
        require("optional".equals(tag.getString("Kind")) && tag.contains("Present", Tag.TAG_BYTE));
        if (tag.getByte("Present") == 0) {
            exact(tag, "Kind", "Present");
            return Optional.empty();
        }
        require(tag.getByte("Present") == 1);
        exact(tag, "Kind", "Present", "Value");
        var text = child(tag, "Value");
        scalar(text, "string");
        require(text.contains("Value", Tag.TAG_STRING));
        return Optional.of(text.getString("Value"));
    }

    private static UUID uuid(CompoundTag tag) throws IOException {
        scalar(tag, "uuid");
        require(tag.hasUUID("Value"));
        return tag.getUUID("Value");
    }

    private static long number(CompoundTag tag) throws IOException {
        scalar(tag, "long");
        require(tag.contains("Value", Tag.TAG_LONG));
        return tag.getLong("Value");
    }

    private static void scalar(CompoundTag tag, String kind) throws IOException {
        exact(tag, "Kind", "Value");
        require(kind.equals(tag.getString("Kind")));
    }

    private static void record(CompoundTag tag, String type, String... fields) throws IOException {
        require("record".equals(tag.getString("Kind")) && type.equals(tag.getString("Type"))
            && tag.size() == fields.length + 2);
        for (String field : fields) require(tag.contains(field, Tag.TAG_COMPOUND));
    }

    private static CompoundTag child(CompoundTag tag, String key) throws IOException {
        if (!(tag.get(key) instanceof CompoundTag child)) throw invalid();
        return child;
    }

    private static void exact(CompoundTag tag, String... keys) throws IOException {
        require(tag.getAllKeys().equals(Set.of(keys)));
    }

    private static void require(boolean valid) throws IOException { if (!valid) throw invalid(); }
    private static IOException invalid() { return new IOException("Malformed historical logistics directory"); }
}
