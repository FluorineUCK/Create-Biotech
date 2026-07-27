package com.nobodiiiii.createbiotech.client.particle;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.PortalParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;

/**
 * Keeps the vanilla portal particle movement while recolouring it to the warm Frog Stomach palette.
 */
public class FrogPortalParticle extends PortalParticle {

	private FrogPortalParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
		double zSpeed) {
		super(level, x, y, z, xSpeed, ySpeed, zSpeed);
		float brightness = random.nextFloat() * 0.35f + 0.65f;
		setColor(brightness, brightness * 0.58f, brightness * 0.32f);
	}

	public static class Provider implements ParticleProvider<SimpleParticleType> {

		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Nullable
		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
			double xSpeed, double ySpeed, double zSpeed) {
			FrogPortalParticle particle =
				new FrogPortalParticle(level, x, y, z, xSpeed, ySpeed, zSpeed);
			particle.pickSprite(sprites);
			return particle;
		}
	}
}
