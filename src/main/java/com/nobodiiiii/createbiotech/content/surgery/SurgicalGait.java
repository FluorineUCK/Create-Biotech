package com.nobodiiiii.createbiotech.content.surgery;

import net.minecraft.util.Mth;

/** Shared anatomical calibration for a stitched body's movement and walk animation. */
public final class SurgicalGait {
	/** Villager legs are twelve model pixels long. */
	public static final double VILLAGER_LEG_LENGTH = 12.0d / 16.0d;
	/** Enderman legs are thirty model pixels long. */
	public static final double ENDERMAN_LEG_LENGTH = 30.0d / 16.0d;
	/** A villager's 0.5 movement attribute driven by its usual 0.5 behaviour speed. */
	public static final double VILLAGER_WALK_SPEED = 0.25d;
	/** An enderman's 0.30 base movement plus its 0.15 attacking modifier. */
	public static final double ANGRY_ENDERMAN_SPEED = 0.45d;

	private static final float MIN_ANIMATION_FREQUENCY_SCALE = 0.25f;
	private static final float MAX_ANIMATION_FREQUENCY_SCALE = 4.0f;

	private SurgicalGait() {}

	/**
	 * Smoothly maps villager-length through enderman-length legs onto their characteristic speeds.
	 * Shorter and longer bodies stay capped at those readable vanilla movement endpoints.
	 */
	public static double movementSpeed(double legLength) {
		if (!Double.isFinite(legLength) || legLength <= 0.0d)
			return VILLAGER_WALK_SPEED;
		double progress = Mth.clamp((legLength - VILLAGER_LEG_LENGTH)
			/ (ENDERMAN_LEG_LENGTH - VILLAGER_LEG_LENGTH), 0.0d, 1.0d);
		double smoothProgress = progress * progress * (3.0d - 2.0d * progress);
		return Mth.lerp(smoothProgress, VILLAGER_WALK_SPEED, ANGRY_ENDERMAN_SPEED);
	}

	/** Keeps the same ground speed from looking like foot sliding as leg length changes. */
	public static float animationFrequencyScale(double legLength) {
		if (!Double.isFinite(legLength) || legLength <= 0.0d)
			return 1.0f;
		return Mth.clamp((float) (VILLAGER_LEG_LENGTH / legLength),
			MIN_ANIMATION_FREQUENCY_SCALE, MAX_ANIMATION_FREQUENCY_SCALE);
	}
}
