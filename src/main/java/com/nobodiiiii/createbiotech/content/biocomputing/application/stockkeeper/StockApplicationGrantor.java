package com.nobodiiiii.createbiotech.content.biocomputing.application.stockkeeper;

import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

/** Account permissions and the actor used for target-world permission checks are separate witnesses. */
final class StockApplicationGrantor {
    // Bound the native NBT parse, not a gameplay limit. Unknown or oversized accounts remain closed offline.
    private static final long PLAYER_DATA_PARSE_BUDGET = 2 * 1024 * 1024L;
    private StockApplicationGrantor() {}

    static Optional<ServerPlayer> worldActor(ServerLevel level, GameProfile profile, BlockPos adapter) {
        var server = level.getServer();
        var online = server.getPlayerList().getPlayer(profile.getId());
        if (online != null) {
            if (!online.mayBuild() || online.isSpectator()) return Optional.empty();
            if (online.serverLevel() == level) return Optional.of(online);
            profile = online.getGameProfile();
        } else if (!savedMayOperate(readSaved(server,profile),profile.getId())) return Optional.empty();

        // Never use proxy defaults as account authority. It only represents the known identity in this world.
        var proxy = FakePlayerFactory.get(level,profile);
        proxy.moveTo(Vec3.atCenterOf(adapter));
        return Optional.of(proxy);
    }

    private static CompoundTag readSaved(MinecraftServer server, GameProfile profile) {
        // The loaded level.dat tag is a startup snapshot, not the current integrated owner save.
        if (server.isSingleplayerOwner(profile)) return server.getPlayerList().getSingleplayerData();
        // Use only the current vanilla player archive. No entity load, DataFixer, backup or .dat_old fallback.
        var path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(profile.getId() + ".dat");
        try {
            return Files.isRegularFile(path) ? NbtIo.readCompressed(path,NbtAccounter.create(PLAYER_DATA_PARSE_BUDGET)) : null;
        } catch (IOException | RuntimeException invalid) { return null; }
    }

    static boolean savedMayOperate(CompoundTag account, UUID expected) {
        if (account == null || !account.hasUUID("UUID") || !expected.equals(account.getUUID("UUID"))
            || !account.contains("playerGameType",Tag.TAG_INT) || !account.contains("abilities",Tag.TAG_COMPOUND)) return false;
        int mode = account.getInt("playerGameType");
        var abilities = account.getCompound("abilities");
        return (mode == GameType.SURVIVAL.getId() || mode == GameType.CREATIVE.getId())
            && abilities.contains("mayBuild",Tag.TAG_BYTE) && abilities.getByte("mayBuild") == 1;
    }
}
