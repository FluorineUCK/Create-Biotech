package com.nobodiiiii.createbiotech.content.biocomputing.device.settings;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Native transport decides whether to hold a stack; only authorized effects run after block ticks. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class ManagedDeployerWorldTicks {
    private ManagedDeployerWorldTicks() {}

    private static final class Work {
        Runnable effect;
        Runnable maintenance;
    }
    private record Pending(long tick, Map<DeployerBlockEntity, Work> deployers) {}
    private static final Map<ServerLevel, Pending> PENDING = new IdentityHashMap<>();

    private static Work work(ServerLevel level, DeployerBlockEntity device) {
        long now = level.getGameTime();
        var pending = PENDING.get(level);
        if (pending == null || pending.tick() != now) {
            pending = new Pending(now, new LinkedHashMap<>());
            PENDING.put(level, pending);
        }
        return pending.deployers().computeIfAbsent(device, ignored -> new Work());
    }

    public static void observe(DeployerBlockEntity device, Runnable maintenance) {
        if (device.getLevel() instanceof ServerLevel level) work(level, device).maintenance = maintenance;
    }

    public static void offer(DeployerBlockEntity device, TransportedItemStackHandlerBehaviour target,
                             TransportedItemStack stack, Runnable effect) {
        if (!(device.getLevel() instanceof ServerLevel level)) return;
        var work = work(level, device);
        // One native material candidate per actuator and interval. Nothing is queued for another tick.
        if (work.effect != null) return;
        var access = (DeployerControl.Access)device;
        var action = access.biocomputingProcessId();
        var original = stack.stack.copy();
        work.effect = () -> {
            if (action == null || !action.equals(access.biocomputingProcessId()) || !access.biocomputingSuppressWorld()
                || DeployerControl.target(device, null) != target || !ItemStack.matches(original, stack.stack)) return;
            // The synchronous HOLD result keeps material in Create's inventory. Confirm it was not moved,
            // replaced or transformed by a later block tick before attempting a native effect.
            boolean[] stillPresent = {false};
            target.handleProcessingOnAllItems(candidate -> {
                if (candidate == stack) stillPresent[0] = true;
                return TransportedResult.doNothing();
            });
            if (stillPresent[0]) effect.run();
        };
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void afterBlockTicks(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var pending = PENDING.remove(level);
        if (pending == null || pending.tick() != level.getGameTime()) return;
        pending.deployers().forEach((device, work) -> {
            if (work.maintenance == null || device.isRemoved() || device.getLevel() != level) return;
            var pos = device.getBlockPos();
            if (!level.shouldTickBlocksAt(pos)) return;
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null || chunk.getBlockEntity(pos) != device) return;
            if (work.effect != null) work.effect.run();
            work.maintenance.run();
        });
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) PENDING.remove(level);
    }
}
