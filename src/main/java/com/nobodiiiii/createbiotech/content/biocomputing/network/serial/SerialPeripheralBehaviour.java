package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Loaded/ticked device ownership only. Protocols remain in each hardware adapter, not the wire scheduler. */
public abstract class SerialPeripheralBehaviour extends BlockEntityBehaviour {
    private static final Map<ServerLevel, Map<BlockPos, WeakReference<SerialPeripheralBehaviour>>> WORLDS = new WeakHashMap<>();
    private long tick = Long.MIN_VALUE, processed = Long.MIN_VALUE;
    protected SerialPeripheralBehaviour(SmartBlockEntity owner) { super(owner); }
    public abstract SerialDevice device();
    protected abstract boolean available();
    protected abstract void processReceived();
    protected final void resetActivity() { tick = processed = Long.MIN_VALUE; }
    @Override public void tick() {
        if (!available() || !(blockEntity.getLevel() instanceof ServerLevel level)) return;
        tick = level.getGameTime();
        WORLDS.computeIfAbsent(level, ignored -> new HashMap<>()).put(blockEntity.getBlockPos().immutable(), new WeakReference<>(this));
    }
    public final void processSerialTick() {
        if (!available()) return;
        long now = blockEntity.getLevel().getGameTime();
        if (tick != now || processed == now) return;
        processed = now; processReceived();
    }
    public static List<SerialPeripheralBehaviour> ticking(ServerLevel level) {
        var entries = WORLDS.get(level); if (entries == null) return List.of();
        var result = new ArrayList<SerialPeripheralBehaviour>();
        entries.entrySet().removeIf(entry -> {
            var host = entry.getValue().get(); var pos = entry.getKey();
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (host == null || host.blockEntity.isRemoved() || chunk == null || chunk.getBlockEntity(pos) != host.blockEntity
                || host.blockEntity.getBehaviour(host.getType()) != host) return true;
            if (host.tick == level.getGameTime() && level.shouldTickBlocksAt(pos) && host.available()) result.add(host);
            return false;
        });
        if (entries.isEmpty()) WORLDS.remove(level);
        result.sort(Comparator.comparingInt((SerialPeripheralBehaviour h) -> h.blockEntity.getBlockPos().getY())
            .thenComparingInt(h -> h.blockEntity.getBlockPos().getZ()).thenComparingInt(h -> h.blockEntity.getBlockPos().getX()));
        return List.copyOf(result);
    }
    @Override public void unload() {
        resetActivity(); device().unload();
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) return;
        var entries = WORLDS.get(level); if (entries == null) return;
        var entry = entries.get(blockEntity.getBlockPos());
        if (entry != null && entry.get() == this) entries.remove(blockEntity.getBlockPos());
        if (entries.isEmpty()) WORLDS.remove(level);
    }
    public static void forget(ServerLevel level) { WORLDS.remove(level); }
}
