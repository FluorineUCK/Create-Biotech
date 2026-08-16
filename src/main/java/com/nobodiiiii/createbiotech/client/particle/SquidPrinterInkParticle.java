package com.nobodiiiii.createbiotech.client.particle;

import com.nobodiiiii.createbiotech.content.squidprinter.SquidPrinterInkParticleOption;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.SquidInkParticle;
import net.minecraft.util.FastColor;

/**
 * Squid ink that stops sinking a set distance below where it was released.
 *
 * <p>Vanilla ink keeps accelerating for as long as it is in open air and lives
 * for a random 6 to 30 ticks, so a printer's trail reaches anywhere from a
 * quarter of a block to nearly three blocks down — deep enough to sink past the
 * depot it is printing onto. Capping by distance rather than by lifetime keeps
 * the trail inside the machine however long any individual particle happened to
 * roll.
 */
public class SquidPrinterInkParticle extends SquidInkParticle {

	private static final int INK_TINT = FastColor.ARGB32.color(255, 255, 255, 255);

	private final double vanishBelowY;

	protected SquidPrinterInkParticle(ClientLevel level, double x, double y, double z, double xSpeed, double ySpeed,
		double zSpeed, double fallLimit, SpriteSet sprites) {
		super(level, x, y, z, xSpeed, ySpeed, zSpeed, INK_TINT, sprites);
		this.vanishBelowY = y - fallLimit;
	}

	@Override
	public void tick() {
		super.tick();
		if (y <= vanishBelowY)
			remove();
	}

	public static class Provider implements ParticleProvider<SquidPrinterInkParticleOption> {

		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SquidPrinterInkParticleOption options, ClientLevel level, double x, double y,
			double z, double dx, double dy, double dz) {
			return new SquidPrinterInkParticle(level, x, y, z, dx, dy, dz, options.fallLimit(), sprites);
		}
	}
}
