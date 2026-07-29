package com.nobodiiiii.createbiotech.content.powerbelt;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.entity.LivingEntity;

public class PowerBeltWalkAnimation {

	private static final Map<LivingEntity, Float> SURFACE_MOVEMENT = new WeakHashMap<>();

	private PowerBeltWalkAnimation() {}

	public static void recordSurfaceMovement(LivingEntity entity, float distance) {
		if (!Float.isFinite(distance) || distance <= 0)
			return;
		SURFACE_MOVEMENT.merge(entity, distance, (current, candidate) -> Math.max(current, candidate));
	}

	public static Float consumeSurfaceMovement(LivingEntity entity) {
		return SURFACE_MOVEMENT.remove(entity);
	}

	public static float includeSurfaceMovement(float movementDistance, float surfaceMovement) {
		return (float) Math.sqrt(movementDistance * movementDistance + surfaceMovement * surfaceMovement);
	}
}
