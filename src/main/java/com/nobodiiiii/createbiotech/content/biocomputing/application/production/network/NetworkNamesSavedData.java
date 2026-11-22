package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

/** Display names only, shared across dimensions. A directory entry never grants access to stock. */
public final class NetworkNamesSavedData extends SavedData {
    static final String NAME = "create_biotech_logistics_names";
    private static final Factory<NetworkNamesSavedData> FACTORY = new Factory<>(NetworkNamesSavedData::empty,
        (tag, registries) -> restore(tag));
    private final Optional<LogisticsNetworkDirectory> directory;

    private NetworkNamesSavedData(Optional<LogisticsNetworkDirectory.State> state) {
        directory = state.map(initial -> LogisticsNetworkDirectory.fromState(initial, next -> setDirty()));
    }

    private static NetworkNamesSavedData empty() {
        return new NetworkNamesSavedData(Optional.of(new LogisticsNetworkDirectory.State(Map.of(), 1)));
    }

    public static Optional<LogisticsNetworkDirectory> directory(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Network names require the server thread");
        return get(server.overworld().getDataStorage(), server.getWorldPath(LevelResource.ROOT).resolve("data")).directory;
    }

    static NetworkNamesSavedData get(DimensionDataStorage storage, Path folder) {
        var current = storage.get(FACTORY, NAME);
        if (current != null) return current;
        // Vanilla returns null for both absent and unreadable saves. Never import stale names over a failed read.
        if (!Files.notExists(folder.resolve(NAME + ".dat"))) {
            current = unavailable("Cannot read current network names", folder, null);
        } else {
            try {
                var legacy = LegacyNetworkNames.read(folder);
                current = legacy.map(state -> new NetworkNamesSavedData(Optional.of(state))).orElseGet(NetworkNamesSavedData::empty);
                if (legacy.isPresent()) current.setDirty();
            } catch (java.io.IOException | RuntimeException bad) {
                current = unavailable("Cannot import historical network names", folder, bad);
            }
        }
        storage.set(NAME, current);
        return current;
    }

    static NetworkNamesSavedData restore(CompoundTag tag) {
        if (!tag.getAllKeys().equals(Set.of("Version", "Directory")) || !tag.contains("Version", Tag.TAG_INT)
                || tag.getInt("Version") != 1 || !tag.contains("Directory", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Unknown network names archive");
        return new NetworkNamesSavedData(Optional.of(LogisticsNetworkDirectory.load(tag.getCompound("Directory")).snapshot()));
    }

    private static NetworkNamesSavedData unavailable(String message, Path folder, Throwable cause) {
        LogUtils.getLogger().warn("{} in {}; leaving the original file untouched", message, folder, cause);
        return new NetworkNamesSavedData(Optional.empty());
    }

    Optional<LogisticsNetworkDirectory> names() { return directory; }

    @Override public void save(java.io.File file, HolderLookup.Provider registries) {
        if (directory.isPresent()) super.save(file, registries);
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", 1);
        tag.put("Directory", directory.orElseThrow(() -> new IllegalStateException("Network names unavailable")).save());
        return tag;
    }
}
