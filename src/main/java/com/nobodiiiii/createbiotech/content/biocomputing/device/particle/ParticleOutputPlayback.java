package com.nobodiiiii.createbiotech.content.biocomputing.device.particle;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** A burst has no world write: preflight all bounded positions, then dispatch normal client particles. */
final class ParticleOutputPlayback {
    private ParticleOutputPlayback() {}

    static String emit(ServerLevel level, BlockPos emitter, Direction facing, ParticleOutputBurst burst) {
        var nozzle = Vec3.atCenterOf(emitter).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(0.625));
        var space = SubLevelCompat.getContaining(level, emitter);
        var positions = new ArrayList<Vec3>(burst.count());
        for (int i = 0; i < burst.count(); i++) {
            var point = SubLevelCompat.toWorld(space, nozzle.add(burst.sampleOffset(level.random)));
            if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z))
                return "target_unavailable";
            var block = BlockPos.containing(point);
            if (level.isOutsideBuildHeight(block) || !level.getWorldBorder().isWithinBounds(block) || !level.hasChunkAt(block))
                return "target_unavailable";
            positions.add(point);
        }
        boolean sent = false;
        for (var point : positions) {
            var velocity = burst.sampleVelocity(level.random);
            // Count=0 sends one exact particle using this initial velocity. Bulk Gaussian scatter
            // cannot enforce a hard spawn range. Use normal (not forced / long-distance) delivery.
            sent |= level.sendParticles(burst.particle(), point.x, point.y, point.z, 0,
                velocity.x, velocity.y, velocity.z, 1) > 0;
        }
        return sent ? "emitted" : "no_viewers";
    }
}
