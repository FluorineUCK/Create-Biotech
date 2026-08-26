package com.nobodiiiii.createbiotech.entity.client.animation;

import com.nobodiiiii.createbiotech.entity.client.animation.SlimeBionicAnimations.Rotation;

import net.minecraft.util.Mth;

/** Authored attack catalogue for the bionic slime's articulated limbs. */
final class SlimeBionicAttackAnimations {
	private static final float MALEDICTUS_SWING_DURATION_SECONDS = 1.125f;

	/**
	 * The attacking-arm subset of Maledictus's {@code swing_attack_right} animation.
	 *
	 * <p>Source:
	 * {@code ref/1.21.1/Cataclysm/src/main/java/com/github/L_Ender/cataclysm/client/animation/Maledictus_Animation.java}.
	 * Only {@code right_shoulder} and {@code right_front_arm} rotations are retained; weapon,
	 * translation, body and off-hand tracks are deliberately omitted to make this an empty-hand,
	 * one-arm attack.</p>
	 */
	private static final RotationTrack EMPTY_HAND_SWING_SHOULDER = new RotationTrack(
		new RotationKeyframe(0.0f, Rotation.degrees(0.0f, 0.0f, 0.0f)),
		new RotationKeyframe(0.3333f, Rotation.degrees(-84.9218f, -21.8243f, 44.1778f)),
		new RotationKeyframe(0.5833f, Rotation.degrees(26.4907f, -18.339f, 42.6343f)),
		new RotationKeyframe(0.9583f, Rotation.degrees(0.0f, 0.0f, 0.0f)));
	private static final RotationTrack EMPTY_HAND_SWING_ELBOW = new RotationTrack(
		new RotationKeyframe(0.0f, Rotation.degrees(0.0f, 0.0f, 0.0f)),
		new RotationKeyframe(0.3333f, Rotation.degrees(-40.3483f, -20.4366f, 29.0527f)),
		new RotationKeyframe(0.5833f, Rotation.degrees(-7.14f, 0.0f, 0.0f)),
		new RotationKeyframe(0.9583f, Rotation.degrees(0.0f, 0.0f, 0.0f)));

	private SlimeBionicAttackAnimations() {}

	static AttackPose emptyHandSwing(float progress) {
		float sourceTime = Mth.clamp(progress, 0.0f, 1.0f)
			* MALEDICTUS_SWING_DURATION_SECONDS;
		return new AttackPose(EMPTY_HAND_SWING_SHOULDER.sample(sourceTime),
			EMPTY_HAND_SWING_ELBOW.sample(sourceTime));
	}

	record AttackPose(Rotation shoulder, Rotation elbow) {}

	private record RotationKeyframe(float time, Rotation rotation) {}

	/** Catmull-Rom rotation track matching the interpolation used by the source animation. */
	private static final class RotationTrack {
		private final RotationKeyframe[] keyframes;

		private RotationTrack(RotationKeyframe... keyframes) {
			this.keyframes = keyframes;
		}

		private Rotation sample(float time) {
			if (keyframes.length == 0)
				return Rotation.IDENTITY;
			if (time <= keyframes[0].time())
				return keyframes[0].rotation();
			int upper = 1;
			while (upper < keyframes.length && time > keyframes[upper].time())
				upper++;
			if (upper >= keyframes.length)
				return keyframes[keyframes.length - 1].rotation();
			RotationKeyframe start = keyframes[upper - 1];
			RotationKeyframe end = keyframes[upper];
			Rotation previous = keyframes[Math.max(0, upper - 2)].rotation();
			Rotation next = keyframes[Math.min(keyframes.length - 1, upper + 1)].rotation();
			float span = end.time() - start.time();
			float progress = span <= 0.0f ? 0.0f : (time - start.time()) / span;
			return new Rotation(
				catmullRom(previous.x(), start.rotation().x(), end.rotation().x(), next.x(), progress),
				catmullRom(previous.y(), start.rotation().y(), end.rotation().y(), next.y(), progress),
				catmullRom(previous.z(), start.rotation().z(), end.rotation().z(), next.z(), progress));
		}

		private static float catmullRom(float previous, float start, float end, float next,
			float progress) {
			float squared = progress * progress;
			float cubed = squared * progress;
			return 0.5f * (2.0f * start + (end - previous) * progress
				+ (2.0f * previous - 5.0f * start + 4.0f * end - next) * squared
				+ (-previous + 3.0f * start - 3.0f * end + next) * cubed);
		}
	}
}
