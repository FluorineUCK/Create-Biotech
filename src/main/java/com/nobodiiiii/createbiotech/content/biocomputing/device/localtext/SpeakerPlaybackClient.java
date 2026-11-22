package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import java.util.concurrent.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** One bounded playback slot per client; mute never touches the platform narrator. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class SpeakerPlaybackClient {
    private static final KeyMapping TOGGLE = new KeyMapping("key.create_biotech.speaker_media",
        InputConstants.UNKNOWN.getValue(), "key.categories.create_biotech");
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(
        runnable -> Thread.ofPlatform().daemon().name("CB speaker audio").unstarted(runnable));
    private static final Semaphore PREPARATION_SLOT = new Semaphore(1);
    private static final int TRANSFER_IDLE_TICKS = 100;
    private static ClientPacketListener connection;
    private static ClientLevel level;
    private static Boolean sent;
    private static long generation;
    private static SpeakerPlaybackPacket begin;
    private static SpeakerPlaybackBuffer buffer;
    private static CompletableFuture<SpeakerAudioPlayback> opening;
    private static SpeakerAudioPlayback playing;
    private static int silence;
    private SpeakerPlaybackClient() {}

    private static boolean enabled() { return CBConfigs.CLIENT_SPEC.isLoaded() && CBConfigs.CLIENT.speakerMediaEnabled.get(); }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) { event.register(TOGGLE); }

    public static Component toggleLabel() {
        return Component.translatable("create_biotech.narrator_output.media_listener." + (enabled() ? "enabled" : "muted"));
    }

    public static void toggle() {
        if (!CBConfigs.CLIENT_SPEC.isLoaded()) return;
        var option = CBConfigs.CLIENT.speakerMediaEnabled;
        option.set(!option.get());
        synchronize(Minecraft.getInstance());
        option.save();
    }

    public static void receive(SpeakerPlaybackPacket packet, LocalPlayer player) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player != player || level == null || minecraft.level != level || minecraft.getConnection() != connection
            || packet.preference() != generation || !enabled() || !Boolean.TRUE.equals(sent)
            || !level.dimension().location().equals(packet.dimension())) return;
        if (packet.action() == SpeakerPlaybackPacket.Action.BEGIN) {
            if (begin != null || !near(player, packet)) return; // No hidden queue, and do not interrupt an unrelated speaker.
            begin = packet;
            buffer = new SpeakerPlaybackBuffer(packet.id(), packet.value());
            silence = 0;
            return;
        }
        if (begin == null || !begin.id().equals(packet.id()) || !begin.speaker().equals(packet.speaker())) return;
        if (packet.action() == SpeakerPlaybackPacket.Action.STOP || !near(player, begin)) { clear(); return; }
        if (buffer == null) return;
        if (packet.action() == SpeakerPlaybackPacket.Action.DATA) {
            if (buffer.append(packet.id(), packet.value(), packet.bytes())) silence = 0;
            else clear();
        } else if (packet.action() == SpeakerPlaybackPacket.Action.END) {
            byte[] bytes = buffer.finish(packet.id());
            buffer = null;
            if (bytes == null || !PREPARATION_SLOT.tryAcquire()) { clear(); return; }
            var format = begin.format();
            try {
                opening = CompletableFuture.supplyAsync(() -> {
                    try {
                        return switch (format) {
                            case MIDI -> SpeakerMidiTrack.decode(bytes).open();
                            case MP3 -> SpeakerMp3Audio.open(bytes);
                        };
                    }
                    catch (Exception failure) { throw new CompletionException(failure); }
                }, AUDIO);
            } catch (RejectedExecutionException unavailable) {
                PREPARATION_SLOT.release();
                clear();
            }
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        while (TOGGLE.consumeClick()) if (minecraft.player != null) {
            toggle();
            minecraft.player.displayClientMessage(toggleLabel(), true);
        }
        synchronize(minecraft);
        if (begin == null) return;
        if (minecraft.player == null || !near(minecraft.player, begin) || buffer != null && ++silence > TRANSFER_IDLE_TICKS) {
            clear();
            return;
        }
        if (opening != null && opening.isDone()) {
            var prepared = opening;
            opening = null;
            PREPARATION_SLOT.release();
            try {
                playing = prepared.join();
                playing.start();
                LogUtils.getLogger().debug("Biocomputing {} submitted to the local audio backend", begin.format());
            } catch (RuntimeException unavailable) {
                LogUtils.getLogger().debug("Biocomputing media preparation or playback failed", unavailable);
                clear();
            }
        } else if (playing != null && !playing.isRunning()) clear();
    }

    private static boolean near(LocalPlayer player, SpeakerPlaybackPacket emitter) {
        var center = SubLevelCompat.toWorld(level, emitter.speaker(), Vec3.atCenterOf(emitter.speaker()));
        return player.distanceToSqr(center) <= (double)emitter.range() * emitter.range();
    }

    private static void synchronize(Minecraft minecraft) {
        var active = minecraft.getConnection();
        if (active != connection) { detach(); connection = active; }
        if (connection == null || minecraft.level == null || minecraft.player == null) { clearWorld(); return; }
        if (level != minecraft.level) { clearWorld(); level = minecraft.level; }
        boolean effective = enabled();
        if (sent == null || sent != effective) {
            clear();
            CBPackets.sendToServer(new SpeakerPlaybackPreference(++generation, effective));
            sent = effective;
        }
    }

    private static void clear() {
        begin = null;
        buffer = null;
        if (opening != null) {
            var previous = opening;
            opening = null;
            previous.whenComplete((playback, failure) -> {
                try { if (playback != null) playback.close(); }
                finally { PREPARATION_SLOT.release(); }
            });
        }
        if (playing != null) {
            var previous = playing;
            playing = null;
            try { previous.close(); }
            catch (RuntimeException failed) { LogUtils.getLogger().debug("Could not close the local audio backend", failed); }
        }
    }

    private static void clearWorld() { clear(); level = null; sent = null; }
    private static void detach() { clearWorld(); connection = null; generation = 0; }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { detach(); }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { detach(); AUDIO.shutdown(); }
}
