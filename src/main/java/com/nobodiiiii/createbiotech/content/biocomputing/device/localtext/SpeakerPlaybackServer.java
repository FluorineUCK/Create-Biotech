package com.nobodiiiii.createbiotech.content.biocomputing.device.localtext;

import com.mojang.logging.LogUtils;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBookAccess;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import com.nobodiiiii.createbiotech.network.CBPackets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/** Ephemeral media playback attached to the existing Speaker. All world access stays on the server thread. */
final class SpeakerPlaybackServer {
    // Hard guards on retained archives and concurrent fan-out, independent of the import quota.
    private static final int MAX_JOBS = 4;
    private static final Semaphore SLOTS = new Semaphore(MAX_JOBS);
    private static final Executor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(MAX_JOBS), runnable -> Thread.ofPlatform().daemon().name("CB speaker archive").unstarted(runnable));
    private static final Map<ServerGamePacketListenerImpl, SpeakerPlaybackPreference> PREFERENCES = new WeakHashMap<>();

    private record Listener(UUID player, long preference) {}
    private UUID identity;
    private CompletableFuture<SpeakerMediaPlaybackSource.Decoded> work;
    private Supplier<SpeakerMediaPlaybackSource.Prepared> recheck;
    private byte[] track;
    private int offset, range;
    private List<Listener> listeners = new ArrayList<>();
    private boolean announced;
    private String status = "idle";

    static void preference(ServerPlayer player, SpeakerPlaybackPreference next) {
        if (player.connection != null) PREFERENCES.compute(player.connection, (key, previous) ->
            previous == null || previous.generation() < next.generation() ? next : previous);
    }

    boolean busy() { return recheck != null; }
    boolean hasRoom() { return SLOTS.availablePermits() > 0; }
    String status() { return status; }

    void start(ServerLevel level, BlockPos pos, int range, int maxListeners, SpeakerMediaPlaybackSource.Prepared source,
            Supplier<SpeakerMediaPlaybackSource.Prepared> recheck) {
        cancel(level, pos, "idle");
        this.range = range;
        var center = SubLevelCompat.toWorld(level, pos, Vec3.atCenterOf(pos));
        // Deterministic nearest-first arbitration when fan-out capacity is exhausted. No queued listeners.
        var candidates = level.players().stream()
            .filter(p -> eligible(p, level, center, range))
            .sorted(Comparator.<ServerPlayer>comparingDouble(p -> p.distanceToSqr(center)).thenComparing(ServerPlayer::getUUID))
            .limit(maxListeners).toList();
        if (candidates.isEmpty()) { status = "no_listeners"; return; }
        if (!SLOTS.tryAcquire()) { status = "media_budget_exhausted"; return; }
        try {
            work = CompletableFuture.supplyAsync(source::decode, WORKER);
        } catch (RejectedExecutionException rejected) {
            SLOTS.release();
            status = "media_budget_exhausted";
            return;
        }
        this.recheck = recheck;
        identity = UUID.randomUUID();
        listeners = new ArrayList<>(candidates.stream().map(p -> new Listener(p.getUUID(), PREFERENCES.get(p.connection).generation())).toList());
        status = "preparing";
    }

    void tick(ServerLevel level, BlockPos pos) {
        if (!busy()) return;
        try {
            var current = recheck.get(); // A reference never overrides live wire, controller or book fences.
            var center = SubLevelCompat.toWorld(level, pos, Vec3.atCenterOf(pos));
            listeners.removeIf(listener -> {
                var player = listener(level, listener.player());
                if (eligible(player, level, center, range)
                    && PREFERENCES.get(player.connection).generation() == listener.preference()) return false;
                if (announced && player != null) sendControl(level, pos, listener, player, SpeakerPlaybackPacket.Action.STOP);
                return true;
            });
            if (listeners.isEmpty()) { cancel(level, pos, "no_listeners"); return; }
            if (track == null) {
                if (!work.isDone()) return;
                var decoded = work.join();
                track = decoded.bytes();
                if (track.length > current.maximumBytes()) { cancel(level, pos, "media_file_limit"); return; }
                status = "transferring";
                announced = true;
                for (var listener : listeners) {
                    var player = listener(level, listener.player());
                    CBPackets.sendToPlayer(SpeakerPlaybackPacket.begin(level.dimension().location(), pos, identity,
                        listener.preference(), decoded.format(), track.length, range), player);
                }
                return;
            }
            if (track.length > current.maximumBytes()) { cancel(level, pos, "media_file_limit"); return; }
            if (offset == track.length) {
                broadcastControl(level, pos, SpeakerPlaybackPacket.Action.END);
                release();
                status = "dispatched";
                return;
            }
            int end = Math.min(track.length, offset + SpeakerMediaChunk.CHUNK_BYTES);
            byte[] part = Arrays.copyOfRange(track, offset, end);
            for (var listener : listeners) {
                var player = listener(level, listener.player());
                CBPackets.sendToPlayer(new SpeakerPlaybackPacket(level.dimension().location(), pos, identity, listener.preference(),
                    SpeakerPlaybackPacket.Action.DATA, null, offset, 0, part), player);
            }
            offset = end;
        } catch (CompletionException invalid) {
            cancel(level, pos, "media_invalid");
        } catch (LibraryBookAccess.Failure changed) {
            cancel(level, pos, changed.code());
        } catch (CableDiscovery.DiscoveryException changed) {
            cancel(level, pos, changed.code());
        } catch (RuntimeException failed) {
            cancel(level, pos, "device_error");
        }
    }

    void cancel(ServerLevel level, BlockPos pos, String result) {
        if (identity == null && result.equals("interrupted")) return;
        try {
            if (announced) for (var listener : listeners) {
                var player = listener(level, listener.player());
                if (player != null) try { sendControl(level, pos, listener, player, SpeakerPlaybackPacket.Action.STOP); }
                catch (RuntimeException disconnected) { LogUtils.getLogger().debug("Could not deliver speaker stop", disconnected); }
            }
        } finally {
            release();
            identity = null;
            announced = false;
            listeners.clear();
            status = result;
        }
    }

    private void release() {
        if (busy()) {
            // A submitted worker may still be using its archive when a newer session replaces us.
            if (work.isDone()) SLOTS.release(); else work.whenComplete((value, failure) -> SLOTS.release());
        }
        work = null; track = null; recheck = null; offset = 0;
    }

    private static boolean eligible(ServerPlayer player, ServerLevel level, Vec3 center, int range) {
        if (player == null || player.serverLevel() != level || !(player.distanceToSqr(center) <= (double)range * range)) return false;
        var preference = PREFERENCES.get(player.connection);
        return preference != null && preference.enabled();
    }

    private void broadcastControl(ServerLevel level, BlockPos pos, SpeakerPlaybackPacket.Action action) {
        for (var listener : listeners) {
            var player = listener(level, listener.player());
            if (player != null) sendControl(level, pos, listener, player, action);
        }
    }

    private void sendControl(ServerLevel level, BlockPos pos, Listener listener, ServerPlayer player, SpeakerPlaybackPacket.Action action) {
        CBPackets.sendToPlayer(SpeakerPlaybackPacket.control(level.dimension().location(), pos, identity, listener.preference(), action), player);
    }

    private static ServerPlayer listener(ServerLevel level, UUID id) {
        for (var player : level.players()) if (player.getUUID().equals(id)) return player;
        return null;
    }
}
