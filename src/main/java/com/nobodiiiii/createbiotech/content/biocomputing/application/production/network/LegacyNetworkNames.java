package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/** One-way import of a single historical record. Never open a live orchestration service or save its file. */
final class LegacyNetworkNames {
    static final String FILE = "create_biotech_factory_orchestration.dat";
    private static final String KEY = "logistics_network_directory/global";
    // Import guard, not a game balance limit. Refusal leaves the archive intact.
    private static final long MAX_IMPORT_BYTES = 128L << 20;

    static Optional<LogisticsNetworkDirectory.State> read(Path folder) throws IOException {
        try (var input = Files.newInputStream(folder.resolve(FILE))) {
            var file = NbtIo.readCompressed(input, NbtAccounter.create(MAX_IMPORT_BYTES));
            if (!file.contains("data", Tag.TAG_COMPOUND)) throw new IOException("Missing historical SavedData");
            var journal = file.getCompound("data");
            if (!journal.getAllKeys().equals(Set.of("Version","Entries")) || !journal.contains("Version", Tag.TAG_INT)
                    || journal.getInt("Version") != 1 || !(journal.get("Entries") instanceof ListTag entries)
                    || entries.size() > 65_536 || !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
                throw new IOException("Unknown historical journal format");
            LogisticsNetworkDirectory.State state = null;
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                if (!entry.contains("Key", Tag.TAG_STRING)) throw new IOException("Missing journal key");
                if (!KEY.equals(entry.getString("Key"))) continue;
                if (state != null || !entry.contains("Value", Tag.TAG_COMPOUND)) throw new IOException("Ambiguous network directory");
                state = LegacyNetworkDirectoryRecord.decode(entry.getCompound("Value"));
            }
            return Optional.ofNullable(state);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
    }

    private LegacyNetworkNames() {}
}
