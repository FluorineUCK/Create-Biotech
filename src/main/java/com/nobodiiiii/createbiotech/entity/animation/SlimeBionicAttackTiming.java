package com.nobodiiiii.createbiotech.entity.animation;

/** Shared server/client timing contract for authored bionic-slime attacks. */
public final class SlimeBionicAttackTiming {
	/** Normal playback length; faster real attack intervals compress the whole curve below this. */
	public static final int PLAYBACK_TICKS = 15;
	/** Full Ender Golem Attack 1 timeline: wind-up 10, strike 5, hold 5, recovery 5. */
	public static final float ENDER_GOLEM_SOURCE_TICKS = 25.0f;
	private static final float ENDER_GOLEM_IMPACT_PROGRESS = 13.0f / ENDER_GOLEM_SOURCE_TICKS;
	private static final float MALEDICTUS_IMPACT_PROGRESS = 0.5833f / 1.125f;
	private static final float ENDER_GOLEM_HIT_START = 10.0f / ENDER_GOLEM_SOURCE_TICKS;
	private static final float ENDER_GOLEM_HIT_END = 15.0f / ENDER_GOLEM_SOURCE_TICKS;
	private static final float MALEDICTUS_HIT_START = 0.3333f / 1.125f;
	private static final float MALEDICTUS_HIT_END = 0.6667f / 1.125f;

	private SlimeBionicAttackTiming() {}

	public static int playbackTicks(int attackInterval) {
		return Math.max(1, Math.min(PLAYBACK_TICKS, attackInterval));
	}

	/** Returns the elapsed playback tick at which server damage should be applied. */
	public static int impactTick(int playbackTicks, boolean weaponAttack) {
		float progress = weaponAttack ? MALEDICTUS_IMPACT_PROGRESS : ENDER_GOLEM_IMPACT_PROGRESS;
		return Math.max(1, Math.min(playbackTicks, Math.round(playbackTicks * progress)));
	}

	/** Normalized part of the curve whose swept hand is allowed to deal damage. */
	public static float hitWindowStart(boolean weaponAttack) {
		return weaponAttack ? MALEDICTUS_HIT_START : ENDER_GOLEM_HIT_START;
	}

	public static float hitWindowEnd(boolean weaponAttack) {
		return weaponAttack ? MALEDICTUS_HIT_END : ENDER_GOLEM_HIT_END;
	}
}
