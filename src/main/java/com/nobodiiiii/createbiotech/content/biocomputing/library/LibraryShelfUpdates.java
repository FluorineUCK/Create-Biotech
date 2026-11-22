package com.nobodiiiii.createbiotech.content.biocomputing.library;

import com.nobodiiiii.createbiotech.CreateBiotech;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.level.*;

/** Server-thread notifications, not periodic book reads. Watches own no Level or block-entity references. */
@EventBusSubscriber(modid = CreateBiotech.MOD_ID)
public final class LibraryShelfUpdates {
    private static final Map<Level, Set<Watch>> WATCHES = new WeakHashMap<>();
    private LibraryShelfUpdates() {}
    public static final class Watch {
        private Set<BlockPos> positions = Set.of();
        private final Set<BlockPos> dirty = new HashSet<>();
        public void observe(Collection<BlockPos> positions) {
            this.positions = positions.stream().map(BlockPos::immutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        public void changed(BlockPos pos) { if (positions.contains(pos)) dirty.add(pos.immutable()); }
        public void chunkChanged(int x, int z) {
            positions.stream().filter(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z).forEach(dirty::add);
        }
        public Set<BlockPos> consumePositions() { var result = Set.copyOf(dirty); dirty.clear(); return result; }
        public boolean consume() { return !consumePositions().isEmpty(); }
    }
    public static Watch watch(Level level) {
        var watch = new Watch();
        WATCHES.computeIfAbsent(level, ignored -> Collections.newSetFromMap(new WeakHashMap<>())).add(watch);
        return watch;
    }
    public static void forget(Level level, Watch watch) {
        var watches = WATCHES.get(level);
        if (watches != null) { watches.remove(watch); if (watches.isEmpty()) WATCHES.remove(level); }
    }
    public static void changed(Level level, BlockPos pos) {
        var watches = WATCHES.get(level); if (watches != null) watches.forEach(watch -> watch.changed(pos));
    }
    @SubscribeEvent(receiveCanceled = true)
    public static void onNeighbors(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide) changed(level, event.getPos());
    }
    /** Vanilla emits BLOCK_CHANGE even when replacing a book leaves all occupied-slot block states unchanged. */
    @SubscribeEvent(receiveCanceled = true)
    public static void onGameEvent(VanillaGameEvent event) {
        var kind = event.getVanillaEvent();
        if (kind.equals(GameEvent.BLOCK_CHANGE) || kind.equals(GameEvent.BLOCK_PLACE) || kind.equals(GameEvent.BLOCK_DESTROY))
            changed(event.getLevel(), BlockPos.containing(event.getEventPosition()));
    }
    @SubscribeEvent public static void onChunkUnload(ChunkEvent.Unload event) { chunkChanged(event); }
    @SubscribeEvent public static void onChunkLoad(ChunkEvent.Load event) { chunkChanged(event); }
    private static void chunkChanged(ChunkEvent event) {
        if (!(event.getLevel() instanceof Level level)) return;
        var watches = WATCHES.get(level);
        if (watches != null) watches.forEach(watch -> watch.chunkChanged(event.getChunk().getPos().x, event.getChunk().getPos().z));
    }
    @SubscribeEvent public static void onLevelUnload(LevelEvent.Unload event) { WATCHES.remove(event.getLevel()); }
}
