package com.nobodiiiii.createbiotech.content.buttercat.block;

public final class SuperButterStreak {
	static final int TICKS_PER_LEVEL = 40;
	static final int MAX_AMPLIFIER = 4;

	private long startTick;
	private long lastStepTick = Long.MIN_VALUE;

	public int recordStep(long gameTime) {
		if (lastStepTick != gameTime && lastStepTick != gameTime - 1)
			startTick = gameTime;

		lastStepTick = gameTime;
		return (int) Math.min(MAX_AMPLIFIER, Math.max(0, gameTime - startTick) / TICKS_PER_LEVEL);
	}
}
