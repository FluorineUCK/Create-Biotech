package com.nobodiiiii.createbiotech.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicConeWaveParticleOption;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ShriekParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A scaled-up, directionally oriented vanilla shriek particle whose radius follows the cone wave.
 */
public class SonicConeWaveParticle extends ShriekParticle {

	private static final int LIFETIME = 16;
	private static final float MAX_RANGE = 16.0f;
	private static final float HALF_ANGLE = (float) Math.toRadians(60.0d);
	private static final float MAX_WAVE_RADIUS = MAX_RANGE * Mth.sin(HALF_ANGLE);

	private final Vec3 origin;
	private final Vec3 forward;
	private final Quaternionf waveRotation;
	private final Quaternionf reverseWaveRotation;
	private int delay;

	private SonicConeWaveParticle(ClientLevel level, double x, double y, double z,
		SonicConeWaveParticleOption option, SpriteSet sprites) {
		super(level, x, y, z, 0);
		origin = new Vec3(x, y, z);
		Vector3f optionDirection = option.direction();
		Vec3 suppliedDirection = new Vec3(optionDirection.x, optionDirection.y, optionDirection.z);
		forward = suppliedDirection.lengthSqr() < 1.0e-6d
			? new Vec3(0.0d, 0.0d, 1.0d)
			: suppliedDirection.normalize();
		waveRotation = new Quaternionf().rotationTo(
			new Vector3f(0.0f, 0.0f, 1.0f),
			new Vector3f((float) forward.x, (float) forward.y, (float) forward.z));
		reverseWaveRotation = new Quaternionf(waveRotation)
			.mul(new Quaternionf().rotationY((float) Math.PI));
		delay = option.delay();
		lifetime = LIFETIME;
		hasPhysics = false;
		xd = 0.0d;
		yd = 0.0d;
		zd = 0.0d;
		pickSprite(sprites);
		setAlpha(1.0f);
	}

	@Override
	public void tick() {
		if (delay > 0) {
			delay--;
			return;
		}

		super.tick();
		if (removed)
			return;

		double distance = MAX_RANGE * age / lifetime;
		Vec3 waveCenter = origin.add(forward.scale(distance));
		setPos(waveCenter.x, waveCenter.y, waveCenter.z);
	}

	@Override
	public float getQuadSize(float partialTick) {
		// Keep the expanded glyph on the same interpolated wavefront as its position.
		float progress = Mth.clamp((age - 1.0f + partialTick) / lifetime, 0.0f, 1.0f);
		return MAX_WAVE_RADIUS * progress;
	}

	@Override
	public void render(VertexConsumer buffer, Camera camera, float partialTick) {
		if (delay > 0)
			return;

		alpha = 1.0f - Mth.clamp((age + partialTick) / lifetime, 0.0f, 1.0f);
		renderRotatedQuad(buffer, camera, waveRotation, partialTick);
		renderRotatedQuad(buffer, camera, reverseWaveRotation, partialTick);
	}

	public static class Provider implements ParticleProvider<SonicConeWaveParticleOption> {

		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SonicConeWaveParticleOption option, ClientLevel level,
			double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
			return new SonicConeWaveParticle(level, x, y, z, option, sprites);
		}
	}
}
