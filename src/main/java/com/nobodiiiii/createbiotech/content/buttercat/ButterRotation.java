package com.nobodiiiii.createbiotech.content.buttercat;

import com.nobodiiiii.createbiotech.content.buttercat.mob_effect.ButterRotationEffect;
import com.nobodiiiii.createbiotech.registry.CBMobEffects;

import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class ButterRotation {
	public static final float MELEE_HALF_ANGLE_DEGREES = 60.0F;
	private static final double MELEE_MIN_DOT =
		Math.cos(MELEE_HALF_ANGLE_DEGREES * Mth.DEG_TO_RAD);

	private ButterRotation() {}

	public static MobEffectInstance getEffect(LivingEntity entity) {
		return entity.getEffect(CBMobEffects.BUTTER_ROTATION.getDelegate());
	}

	/**
	 * Non-player entities use a world-time phase so rendering, projectiles and
	 * melee all derive the same angle without per-entity ticking state.
	 */
	public static float getVisualRotationDegrees(LivingEntity entity, float partialTick) {
		if (entity instanceof Player)
			return 0.0F;
		int amplifier = ((ButterRotationAccess) entity).createBiotech$getButterRotationAmplifier();
		if (amplifier < 0) {
			MobEffectInstance effect = getEffect(entity);
			if (effect == null)
				return 0.0F;
			amplifier = effect.getAmplifier();
		}

		double elapsedTicks = entity.level().getGameTime() + partialTick;
		double degrees = elapsedTicks * getTickAngleSpeed(amplifier);
		return (float) (degrees % 360.0D);
	}

	public static float getTickAngleSpeed(int amplifier) {
		return ButterRotationEffect.getRotationAngularSpeed()
			* (6 * Math.max(0, amplifier) + 1);
	}

	public static Vec3 rotateHorizontal(Vec3 movement, float degrees) {
		return movement.yRot(-degrees * Mth.DEG_TO_RAD);
	}

	public static boolean isFacingForMelee(LivingEntity attacker, LivingEntity target) {
		double targetX = (target.getBoundingBox().minX + target.getBoundingBox().maxX) * 0.5D;
		double targetZ = (target.getBoundingBox().minZ + target.getBoundingBox().maxZ) * 0.5D;
		double deltaX = targetX - attacker.getX();
		double deltaZ = targetZ - attacker.getZ();
		double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
		if (horizontalDistance < 1.0E-5D)
			return true;

		float visualYaw = attacker instanceof Player
			? attacker.getYRot()
			: attacker.getYHeadRot() + getVisualRotationDegrees(attacker, 0.0F);
		float yawRadians = visualYaw * Mth.DEG_TO_RAD;
		double facingX = -Mth.sin(yawRadians);
		double facingZ = Mth.cos(yawRadians);
		double dot = (facingX * deltaX + facingZ * deltaZ) / horizontalDistance;
		return dot >= MELEE_MIN_DOT;
	}
}
