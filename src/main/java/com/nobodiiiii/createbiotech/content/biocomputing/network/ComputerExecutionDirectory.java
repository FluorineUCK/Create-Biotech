package com.nobodiiiii.createbiotech.content.biocomputing.network;

import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import java.lang.ref.WeakReference;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Server-thread physical lifecycle index, not a persistent directory, endpoint grant or shared runtime.
 * Both world keys and device references are weak, so this index cannot keep a closed world alive. */
public final class ComputerExecutionDirectory {
    private ComputerExecutionDirectory() {}
    private static final Map<ServerLevel, Map<BlockPos, Entry>> WORLDS = new WeakHashMap<>();
    private static final Comparator<ComputerBehaviour> POSITION = Comparator
        .comparingInt((ComputerBehaviour computer) -> computer.blockEntity.getBlockPos().getY())
        .thenComparingInt(computer -> computer.blockEntity.getBlockPos().getZ())
        .thenComparingInt(computer -> computer.blockEntity.getBlockPos().getX());
    private static final class Entry {
        private final WeakReference<ComputerBehaviour> computer;
        private long tick;
        private boolean ticked;
        private Entry(ComputerBehaviour computer) { this.computer = new WeakReference<>(computer); }
    }
    public record Snapshot(long tick, List<ComputerBehaviour> loaded, List<ComputerBehaviour> ticking) {
        public Snapshot { loaded = List.copyOf(loaded); ticking = List.copyOf(ticking); }
    }
    public static void register(ComputerBehaviour computer) { entry(computer); }
    /** Called by the real behaviour tick, not by observation or a capability query. */
    public static void ticked(ComputerBehaviour computer) {
        var entry = entry(computer);
        if (entry != null) { entry.tick = computer.blockEntity.getLevel().getGameTime(); entry.ticked = true; }
    }
    private static Entry entry(ComputerBehaviour computer) {
        if (!(computer.blockEntity.getLevel() instanceof ServerLevel level)) return null;
        var position = computer.blockEntity.getBlockPos();
        if (!present(level, position, computer)) return null;
        var entries = WORLDS.computeIfAbsent(level, ignored -> new HashMap<>());
        var entry = entries.get(position);
        if (entry == null || entry.computer.get() != computer) {
            entry = new Entry(computer); entries.put(position.immutable(), entry);
        }
        return entry;
    }
    public static void unregister(ComputerBehaviour computer) {
        if (!(computer.blockEntity.getLevel() instanceof ServerLevel level)) return;
        var entries = WORLDS.get(level); if (entries == null) return;
        var position = computer.blockEntity.getBlockPos(); var entry = entries.get(position);
        // Delayed unload of an old instance must not unregister its same-position replacement.
        if (entry != null && entry.computer.get() == computer) entries.remove(position);
        if (entries.isEmpty()) WORLDS.remove(level);
    }
    public static Snapshot snapshot(ServerLevel level) {
        long tick = level.getGameTime(); var entries = WORLDS.get(level);
        if (entries == null) return new Snapshot(tick, List.of(), List.of());
        var loaded = new ArrayList<ComputerBehaviour>(); var ticking = new ArrayList<ComputerBehaviour>();
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next(); var entry = item.getValue(); var computer = entry.computer.get();
            if (computer == null || !present(level, item.getKey(), computer)) { iterator.remove(); continue; }
            loaded.add(computer);
            if (entry.ticked && entry.tick == tick) ticking.add(computer);
        }
        if (entries.isEmpty()) WORLDS.remove(level);
        loaded.sort(POSITION); ticking.sort(POSITION);
        return new Snapshot(tick, loaded, ticking);
    }
    private static boolean present(ServerLevel level, BlockPos position, ComputerBehaviour computer) {
        var entity = computer.blockEntity;
        // Level#getBlockEntity goes through getChunk(..., true), which can renew UNKNOWN tickets.
        // The directory observes loaded owners; it must never keep a non-ticking chunk resident.
        var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        return entity.getLevel() == level && !entity.isRemoved() && entity.getBlockPos().equals(position)
            && chunk != null && chunk.getBlockEntity(position) == entity
            && entity.getBehaviour(ComputerBehaviour.TYPE) == computer;
    }
}
