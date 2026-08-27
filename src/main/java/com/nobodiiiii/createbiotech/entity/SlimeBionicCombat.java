package com.nobodiiiii.createbiotech.entity;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-authoritative melee timing and volume, deliberately independent from animation curves. */
public final class SlimeBionicCombat {
	public static final int NORMAL_ATTACK_TICKS = 15;
	public static final float SECTOR_HALF_ANGLE_DEGREES = 60.0f;
	private static final int NORMAL_WINDUP_TICKS = 5;
	private static final int NORMAL_ACTIVE_TICKS = 4;
	private static final int MIN_ACTIVE_TICKS = 2;
	private static final double EPSILON = 1.0e-8d;

	private SlimeBionicCombat() {}

	public static int duration(int attackInterval) {
		return Math.max(1, Math.min(NORMAL_ATTACK_TICKS, attackInterval));
	}

	public static int activeStartTick(int duration) {
		int activeTicks = activeTicks(duration);
		int scaledWindup = Math.round((float) duration * NORMAL_WINDUP_TICKS
			/ NORMAL_ATTACK_TICKS);
		return Mth.clamp(scaledWindup, 0, duration - activeTicks);
	}

	public static int activeEndTick(int duration) {
		return activeStartTick(duration) + activeTicks(duration);
	}

	public static boolean isActiveTick(int elapsed, int duration) {
		return elapsed >= activeStartTick(duration) && elapsed < activeEndTick(duration);
	}

	private static int activeTicks(int duration) {
		int scaled = Math.round((float) duration * NORMAL_ACTIVE_TICKS / NORMAL_ATTACK_TICKS);
		return Math.min(duration, Math.max(MIN_ACTIVE_TICKS, scaled));
	}

	/** Broad-phase start envelope; exact facing is intentionally deferred to the active window. */
	public static boolean withinStartEnvelope(AABB target, Vec3 bodyPosition,
		SurgicalAssembly.ArmAttackGeometry arm) {
		double startReach = arm.reach()
			+ Math.sqrt(arm.origin().x * arm.origin().x + arm.origin().z * arm.origin().z);
		double dx = bodyPosition.x < target.minX ? target.minX - bodyPosition.x
			: bodyPosition.x > target.maxX ? bodyPosition.x - target.maxX : 0.0d;
		double dz = bodyPosition.z < target.minZ ? target.minZ - bodyPosition.z
			: bodyPosition.z > target.maxZ ? bodyPosition.z - target.maxZ : 0.0d;
		return dx * dx + dz * dz <= startReach * startReach
			&& target.maxY >= bodyPosition.y + arm.minimumY()
			&& target.minY <= bodyPosition.y + arm.maximumY();
	}

	/** Exact gameplay sector for one target. Only its bounding box is considered; no entity scan occurs. */
	public static boolean intersects(AABB target, Vec3 bodyPosition, float bodyYaw,
		SurgicalAssembly.ArmAttackGeometry arm) {
		Vec3 origin = arm.origin().yRot(-bodyYaw * Mth.DEG_TO_RAD).add(bodyPosition);
		if (target.maxY < bodyPosition.y + arm.minimumY()
			|| target.minY > bodyPosition.y + arm.maximumY())
			return false;

		double centerX = (target.minX + target.maxX) * 0.5d;
		double centerZ = (target.minZ + target.maxZ) * 0.5d;
		double halfX = (target.maxX - target.minX) * 0.5d;
		double halfZ = (target.maxZ - target.minZ) * 0.5d;
		double targetRadius = Math.sqrt(halfX * halfX + halfZ * halfZ);
		double dx = centerX - origin.x;
		double dz = centerZ - origin.z;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > arm.reach() + targetRadius)
			return false;
		if (distance <= targetRadius + EPSILON)
			return true;

		float targetYaw = (float) Mth.atan2(dz, dx) * Mth.RAD_TO_DEG - 90.0f;
		double angularAllowance = Math.asin(Mth.clamp(targetRadius / distance, 0.0d, 1.0d))
			* Mth.RAD_TO_DEG;
		return Math.abs(Mth.wrapDegrees(targetYaw - bodyYaw))
			<= SECTOR_HALF_ANGLE_DEGREES + angularAllowance;
	}
}
