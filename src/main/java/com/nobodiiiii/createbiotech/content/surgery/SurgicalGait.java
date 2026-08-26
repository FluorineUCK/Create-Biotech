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
	/** HumanoidModel's normal walking-angle multiplier. */
	public static final float HUMANOID_LEG_SWING_FACTOR = 1.4f;
	/** Villager legs reach 1.4 * 0.5 = 0.7 radians. */
	public static final float VILLAGER_MAX_LEG_SWING = 0.7f;
	/** EndermanModel halves and then clamps each leg to 0.4 radians. */
	public static final float ENDERMAN_MAX_LEG_SWING = 0.4f;
	/** Vanilla caps this at 1; the bionic gait preserves phase up to its 0.45 movement endpoint. */
	public static final float MAX_WALK_ANIMATION_SPEED = (float) (ANGRY_ENDERMAN_SPEED * 4.0d);

	private static final float MIN_ANIMATION_FREQUENCY_SCALE = 0.25f;
	private static final float MAX_ANIMATION_FREQUENCY_SCALE = 4.0f;

	private SurgicalGait() {}

	/**
	 * Smoothly maps villager-length through enderman-length legs onto their characteristic speeds.
	 * Shorter and longer bodies stay capped at those readable vanilla movement endpoints.
	 */
	public static double movementSpeed(double legLength) {
		return Mth.lerp(smoothLegProgress(legLength), VILLAGER_WALK_SPEED, ANGRY_ENDERMAN_SPEED);
	}

	/** Long legs use a progressively narrower arc, bottoming out at EndermanModel's limit. */
	public static float maximumLegSwing(double legLength) {
		return Mth.lerp((float) smoothLegProgress(legLength),
			VILLAGER_MAX_LEG_SWING, ENDERMAN_MAX_LEG_SWING);
	}

	/** Maximum walk-animation amount that produces {@link #maximumLegSwing(double)}. */
	public static float maximumHumanoidSwingAmount(double legLength) {
		return maximumLegSwing(legLength) / HUMANOID_LEG_SWING_FACTOR;
	}

	/**
	 * Keeps stride speed matched as both leg radius and maximum angle change. The horizontal reach
	 * of one side of a pendulum-like step is {@code legLength * sin(maximumAngle)}.
	 */
	public static float animationFrequencyScale(double legLength) {
		if (!Double.isFinite(legLength) || legLength <= 0.0d)
			return 1.0f;
		double referenceReach = VILLAGER_LEG_LENGTH * Math.sin(VILLAGER_MAX_LEG_SWING);
		double actualReach = legLength * Math.sin(maximumLegSwing(legLength));
		return Mth.clamp((float) (referenceReach / actualReach),
			MIN_ANIMATION_FREQUENCY_SCALE, MAX_ANIMATION_FREQUENCY_SCALE);
	}

	private static double smoothLegProgress(double legLength) {
		if (!Double.isFinite(legLength) || legLength <= 0.0d)
			return 0.0d;
		double progress = Mth.clamp((legLength - VILLAGER_LEG_LENGTH)
			/ (ENDERMAN_LEG_LENGTH - VILLAGER_LEG_LENGTH), 0.0d, 1.0d);
		return progress * progress * (3.0d - 2.0d * progress);
	}
}
