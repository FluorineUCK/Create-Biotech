package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

/** Per-game-tick reply allowance that repeated callers cannot reset. */
final class ReplyDispatchAllowance {
	private long tick = Long.MIN_VALUE;
	private int remaining;

	int available(long currentTick, int allowance, int requested) {
		if (currentTick != tick) {
			tick = currentTick;
			remaining = Math.max(0, allowance);
		}
		return Math.min(remaining, Math.max(0, requested));
	}

	void consume(int dispatched) {
		if (dispatched < 0 || dispatched > remaining)
			throw new IllegalArgumentException("Dispatch exceeds available allowance");
		remaining -= dispatched;
	}
}
