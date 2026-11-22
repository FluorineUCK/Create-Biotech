package com.nobodiiiii.createbiotech.content.biocomputing.network.serial;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CableDiscovery;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Post-commit cosmetics only. Physical activity is not a receive or application acknowledgement. */
public final class SerialIndicator {
    private static final int MAX_POINTS = 33; // Rendering detail only; never a topology or throughput limit.
    private static final DustParticleOptions ACTIVE = new DustParticleOptions(new Vector3f(1, .75f, .15f), 1);
    private static final DustParticleOptions EMITTED = new DustParticleOptions(new Vector3f(.2f, .85f, 1), 1);
    private static final DustParticleOptions FAILED = new DustParticleOptions(new Vector3f(1, .15f, .15f), 1);
    private record Shape(List<BlockPos> cables, List<CableDiscovery.Connection> terminals) {}
    private record Point(BlockPos anchor, Vec3 position, ParticleOptions particle) {}
    private Map<Shape, SerialNetwork.Phase> previous = Map.of();
    private long lastTick = Long.MIN_VALUE;

    public void accept(ServerLevel level, List<SerialNetwork.Activity> activity, long tick, int interval) {
        if (tick == lastTick) return;
        lastTick = tick;
        var next = new HashMap<Shape, SerialNetwork.Phase>();
        for (var current : activity) {
            var shape = new Shape(current.cables(), current.terminals()); next.put(shape, current.phase());
            if (previous.get(shape) == current.phase() && Math.floorMod(tick, Math.max(1, interval)) != 0) continue;
            try { draw(level, current); }
            catch (RuntimeException | StackOverflowError ignored) { /* Display failure cannot replay, reject or stop a physical frame. */ }
        }
        previous = Map.copyOf(next); // No unbounded history or stale paths after an idle/disconnected interval.
    }
    private static void draw(ServerLevel level, SerialNetwork.Activity activity) {
        var particle = switch (activity.phase()) {
            case TRANSMITTING -> ACTIVE;
            case TRANSMITTED -> EMITTED;
            case COLLISION, INTERRUPTED -> FAILED;
        };
        var points = new ArrayList<Point>();
        for (var end : activity.terminals()) points.add(new Point(end.position(), Vec3.atCenterOf(end.position())
            .add(Vec3.atLowerCornerOf(end.side().getNormal()).scale(.6)).add(0, .2, 0), particle));
        // Sample actual cable blocks, never a chord between arbitrary endpoint coordinates.
        for (var cable : activity.cables()) points.add(new Point(cable, Vec3.atCenterOf(cable).add(.2, .2, .2), ParticleTypes.END_ROD));
        int count = Math.min(MAX_POINTS, points.size());
        for (int i = 0; i < count; i++) {
            var point = points.get(count == 1 ? 0 : (int)((long)i * (points.size() - 1) / (count - 1)));
            if (!level.hasChunkAt(point.anchor())) continue;
            var world = SubLevelCompat.toWorld(level, point.anchor(), point.position());
            level.sendParticles(point.particle(), world.x, world.y, world.z, 1, 0, 0, 0, 0);
        }
    }
}
