package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Ephemeral consent on real connections for the audio-only speaker channel. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class NarratorOutputAudience {
    private static final Map<ServerGamePacketListenerImpl, NarratorPreferencePacket> PREFERENCES = new WeakHashMap<>();
    private NarratorOutputAudience() {}

    static void preference(ServerPlayer player, NarratorPreferencePacket preference) {
        if (player.connection == null) return;
        PREFERENCES.compute(player.connection, (connection, current) ->
            current == null || preference.generation() > current.generation() ? preference : current);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) PREFERENCES.remove(player.connection);
    }

    public static String dispatch(ServerLevel level, BlockPos emitter, int range, String text) {
        var center = SubLevelCompat.toWorld(level, emitter, Vec3.atCenterOf(emitter));
        boolean dispatched = false;
        double rangeSquared = (double)range * range;
        var dimension = level.dimension().location();
        for (ServerPlayer player : level.players()) {
            var preference = PREFERENCES.get(player.connection);
            if (preference == null || !preference.enabled() || player.serverLevel() != level
                || !(player.distanceToSqr(center) <= rangeSquared)) continue;
            CBPackets.sendToPlayer(new NarratorSpeechPacket(dimension, preference.generation(), text), player);
            dispatched = true;
        }
        return dispatched ? "dispatched" : "no_listeners";
    }
}
