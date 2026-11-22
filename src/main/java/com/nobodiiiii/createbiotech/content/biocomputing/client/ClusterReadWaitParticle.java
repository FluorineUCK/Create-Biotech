package com.nobodiiiii.createbiotech.content.biocomputing.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.minecraft.core.particles.SimpleParticleType;

/** End-rod appearance scoped to Cluster read waits; never changes vanilla particles. */
public final class ClusterReadWaitParticle extends EndRodParticle {
    private ClusterReadWaitParticle(ClientLevel level, double x, double y, double z,
            double dx, double dy, double dz, SpriteSet sprites) {
        super(level, x, y, z, dx, dy, dz, sprites);
    }

    @Override public ParticleRenderType getRenderType() {
        // Fancy/Fast render opaque particles before translucent blocks; the shell can then blend over the marker.
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    public record Provider(SpriteSet sprites) implements ParticleProvider<SimpleParticleType> {
        @Override public Particle createParticle(SimpleParticleType type, ClientLevel level,
                double x, double y, double z, double dx, double dy, double dz) {
            return new ClusterReadWaitParticle(level, x, y, z, dx, dy, dz, sprites);
        }
    }
}
