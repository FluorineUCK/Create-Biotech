package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Relocates a managed arm's native update within its current server tick, never across ticks. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class ManagedArmWorldTicks {
    private ManagedArmWorldTicks() {}

    private record Pending(long tick, Map<ArmBlockEntity, Runnable> arms) {}
    private static final Map<ServerLevel, Pending> PENDING = new IdentityHashMap<>();

    public static void enqueue(ServerLevel level, ArmBlockEntity arm, Runnable tick) {
        var pending = PENDING.get(level);
        if (pending == null || pending.tick() != level.getGameTime()) {
            pending = new Pending(level.getGameTime(), new LinkedHashMap<>());
            PENDING.put(level, pending);
        }
        // Only arms reached by their native ticking block entity are admitted, once per interval.
        pending.arms().putIfAbsent(arm, tick);
    }

    /** Cluster availability for this interval is now known; serial hardware advances at LOWEST. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void afterBlockTicks(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pending = PENDING.remove(level);
        if (pending == null || pending.tick() != level.getGameTime()) return;
        pending.arms().forEach((arm, tick) -> {
            if (arm.isRemoved() || arm.getLevel() != level) return;
            var pos = arm.getBlockPos();
            if (!level.shouldTickBlocksAt(pos)) return;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk != null && chunk.getBlockEntity(pos) == arm) tick.run();
        });
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) PENDING.remove(level);
    }
}
