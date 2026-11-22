package com.nobodiiiii.createbiotech.content.biocomputing.device.narrator;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.mojang.text2speech.Narrator;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;

/** One local switch for all nearby devices. Never touches Minecraft's GameNarrator or its options. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID, value = Dist.CLIENT)
public final class NarratorOutputClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping TOGGLE = new KeyMapping("key.create_biotech.narrator_output",
        InputConstants.UNKNOWN.getValue(), "key.categories.create_biotech");
    private static final Platform PLATFORM = new Platform();
    private static NarratorSpeechQueue queue;
    private static ClientPacketListener connection;
    private static ClientLevel level;
    private static Boolean sent;
    private static long generation, clientTick;
    private NarratorOutputClient() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) { event.register(TOGGLE); }

    static boolean enabled() {
        return CBConfigs.CLIENT_SPEC.isLoaded() && CBConfigs.CLIENT.narratorOutputEnabled.get();
    }

    public static Component toggleLabel() {
        return Component.translatable("create_biotech.narrator_output.listener." + (enabled()
            ? PLATFORM.available() ? "enabled" : "unavailable" : "muted"));
    }

    public static void toggle() {
        if (!CBConfigs.CLIENT_SPEC.isLoaded()) return;
        var option = CBConfigs.CLIENT.narratorOutputEnabled;
        boolean next = !option.get();
        option.set(next);
        synchronize(Minecraft.getInstance());
        option.save();
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(toggleLabel(), true);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var minecraft = Minecraft.getInstance();
        while (TOGGLE.consumeClick()) if (minecraft.player != null) toggle();
        synchronize(minecraft);
        if (queue != null && queue.tick(++clientTick) == NarratorSpeechQueue.Tick.SUBMITTED)
            LOGGER.debug("Biocomputing narration submitted to the local speech backend");
    }

    public static void receive(NarratorSpeechPacket packet, LocalPlayer player) {
        var minecraft = Minecraft.getInstance();
        if (queue == null || minecraft.player != player || minecraft.level == null || minecraft.level != level
            || minecraft.getConnection() != connection || !Boolean.TRUE.equals(sent) || !enabled()
            || packet.generation() != generation || !level.dimension().location().equals(packet.dimension())) return;
        var outcome = queue.offer(packet.text());
        if (outcome == NarratorSpeechQueue.Offer.FULL) LOGGER.debug("Biocomputing narration queue is full");
    }

    private static void synchronize(Minecraft minecraft) {
        var active = minecraft.getConnection();
        if (active == null) { detach(); return; }
        if (connection != active) {
            detach();
            connection = active;
        }
        if (minecraft.player == null || minecraft.level == null) {
            clearWorld(); // Retain the generation until the transport actually changes.
            return;
        }
        if (level != minecraft.level) {
            clearWorld();
            level = minecraft.level;
        }
        boolean effective = enabled() && PLATFORM.available();
        if (effective && queue == null) queue = new NarratorSpeechQueue(PLATFORM, new NarratorSpeechQueue.Limits(
            CBConfigs.CLIENT.narratorQueueCapacity.get(), CBConfigs.CLIENT.narratorMinimumGapTicks.get(),
            CBConfigs.CLIENT.narratorTicksPerCodePoint.get()));
        if (queue != null) queue.setEnabled(effective);
        if (sent == null || sent != effective) {
            CBPackets.sendToServer(new NarratorPreferencePacket(++generation, effective));
            sent = effective;
        }
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { detach(); }

    private static void clearWorld() {
        if (queue != null) queue.disconnect();
        queue = null; level = null; sent = null; clientTick = 0;
    }

    private static void detach() {
        clearWorld();
        connection = null; generation = 0;
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) {
        detach();
        PLATFORM.destroy();
    }

    private static final class Platform implements NarratorSpeechQueue.Backend {
        private Narrator voice;
        private boolean failed;

        @Override public boolean available() {
            if (failed) return false;
            if (voice == null) voice = Narrator.getNarrator();
            return voice.active();
        }

        @Override public void speak(String text) {
            try {
                // Keep the platform queue bounded. The local queue spaces calls by text length.
                voice.say(text, true);
            } catch (RuntimeException | LinkageError failure) {
                failed = true;
                LOGGER.debug("Biocomputing speech backend failed", failure);
                throw new IllegalStateException("speech_backend_unavailable");
            }
        }

        @Override public void clear() { if (voice != null && voice != Narrator.EMPTY) voice.clear(); }
        void destroy() { if (voice != null && voice != Narrator.EMPTY) voice.destroy(); }
    }
}
