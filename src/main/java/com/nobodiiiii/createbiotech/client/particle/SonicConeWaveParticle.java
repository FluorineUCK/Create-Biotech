package com.nobodiiiii.createbiotech.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A single, large vibration-textured wavefront. Its spherical cap is exactly 60 degrees wide and
 * grows to a radius of 16 blocks, matching the server-side cone hit test.
 */
public class SonicConeWaveParticle extends TextureSheetParticle {

	private static final int LIFETIME = 16;
	private static final float MAX_RANGE = 16.0f;
	private static final float HALF_ANGLE = (float) Math.toRadians(30.0d);
	private static final int ANGULAR_SEGMENTS = 32;
	private static final int RADIAL_SEGMENTS = 6;
	private static final float TRAILING_SHELL_DISTANCE = 0.7f;

	private final Vec3 forward;
	private final Vec3 right;
	private final Vec3 up;

	private SonicConeWaveParticle(ClientLevel level, double x, double y, double z,
		double directionX, double directionY, double directionZ, SpriteSet sprites) {
		super(level, x, y, z);
		Vec3 suppliedDirection = new Vec3(directionX, directionY, directionZ);
		forward = suppliedDirection.lengthSqr() < 1.0e-6d
			? new Vec3(0.0d, 0.0d, 1.0d)
			: suppliedDirection.normalize();
		Vec3 reference = Math.abs(forward.y) < 0.99d
			? new Vec3(0.0d, 1.0d, 0.0d)
			: new Vec3(1.0d, 0.0d, 0.0d);
		right = forward.cross(reference).normalize();
		up = right.cross(forward).normalize();
		lifetime = LIFETIME;
		hasPhysics = false;
		pickSprite(sprites);
		setBoundingBox(new AABB(x - MAX_RANGE, y - MAX_RANGE, z - MAX_RANGE,
			x + MAX_RANGE, y + MAX_RANGE, z + MAX_RANGE));
	}

	@Override
	public void tick() {
		xo = x;
		yo = y;
		zo = z;
		if (age++ >= lifetime)
			remove();
	}

	@Override
	public void render(VertexConsumer buffer, Camera camera, float partialTick) {
		float progress = Mth.clamp((age + partialTick) / lifetime, 0.0f, 1.0f);
		float waveRadius = MAX_RANGE * progress;
		float fade = 1.0f - Mth.clamp((progress - 0.72f) / 0.28f, 0.0f, 1.0f) * 0.7f;
		Vec3 cameraPos = camera.getPosition();
		Vec3 renderOrigin = new Vec3(x - cameraPos.x, y - cameraPos.y, z - cameraPos.z);

		renderShell(buffer, renderOrigin, waveRadius, 0.82f * fade);
		if (waveRadius > TRAILING_SHELL_DISTANCE)
			renderShell(buffer, renderOrigin, waveRadius - TRAILING_SHELL_DISTANCE, 0.34f * fade);
	}

	private void renderShell(VertexConsumer buffer, Vec3 renderOrigin, float radius, float shellAlpha) {
		float u0 = sprite.getU0();
		float u1 = sprite.getU1();
		float v0 = sprite.getV0();
		float v1 = sprite.getV1();
		int light = 0xF000F0;

		for (int radial = 0; radial < RADIAL_SEGMENTS; radial++) {
			float theta0 = HALF_ANGLE * radial / RADIAL_SEGMENTS;
			float theta1 = HALF_ANGLE * (radial + 1) / RADIAL_SEGMENTS;
			float textureV0 = Mth.lerp((float) radial / RADIAL_SEGMENTS, v0, v1);
			float textureV1 = Mth.lerp((float) (radial + 1) / RADIAL_SEGMENTS, v0, v1);

			for (int angular = 0; angular < ANGULAR_SEGMENTS; angular++) {
				float phi0 = Mth.TWO_PI * angular / ANGULAR_SEGMENTS;
				float phi1 = Mth.TWO_PI * (angular + 1) / ANGULAR_SEGMENTS;
				float textureU0 = Mth.lerp((float) angular / ANGULAR_SEGMENTS, u0, u1);
				float textureU1 = Mth.lerp((float) (angular + 1) / ANGULAR_SEGMENTS, u0, u1);

				Vec3 p00 = renderOrigin.add(pointOnCap(radius, theta0, phi0));
				Vec3 p01 = renderOrigin.add(pointOnCap(radius, theta0, phi1));
				Vec3 p11 = renderOrigin.add(pointOnCap(radius, theta1, phi1));
				Vec3 p10 = renderOrigin.add(pointOnCap(radius, theta1, phi0));

				emit(buffer, p00, textureU0, textureV0, shellAlpha, light);
				emit(buffer, p01, textureU1, textureV0, shellAlpha, light);
				emit(buffer, p11, textureU1, textureV1, shellAlpha, light);
				emit(buffer, p10, textureU0, textureV1, shellAlpha, light);
				// Render the back face as well so the shooter and targets see the same wavefront.
				emit(buffer, p10, textureU0, textureV1, shellAlpha, light);
				emit(buffer, p11, textureU1, textureV1, shellAlpha, light);
				emit(buffer, p01, textureU1, textureV0, shellAlpha, light);
				emit(buffer, p00, textureU0, textureV0, shellAlpha, light);
			}
		}
	}

	private Vec3 pointOnCap(float radius, float theta, float phi) {
		double forwardScale = radius * Math.cos(theta);
		double lateralScale = radius * Math.sin(theta);
		return forward.scale(forwardScale)
			.add(right.scale(lateralScale * Math.cos(phi)))
			.add(up.scale(lateralScale * Math.sin(phi)));
	}

	private static void emit(VertexConsumer buffer, Vec3 position, float u, float v, float alpha, int light) {
		buffer.addVertex((float) position.x, (float) position.y, (float) position.z)
			.setUv(u, v)
			.setColor(0.72f, 1.0f, 0.94f, alpha)
			.setLight(light);
	}

	@Override
	public ParticleRenderType getRenderType() {
		return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
	}

	@Override
	public AABB getRenderBoundingBox(float partialTicks) {
		return new AABB(x - MAX_RANGE, y - MAX_RANGE, z - MAX_RANGE,
			x + MAX_RANGE, y + MAX_RANGE, z + MAX_RANGE);
	}

	public static class Provider implements ParticleProvider<SimpleParticleType> {

		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
			double directionX, double directionY, double directionZ) {
			return new SonicConeWaveParticle(level, x, y, z, directionX, directionY, directionZ, sprites);
		}
	}
}
