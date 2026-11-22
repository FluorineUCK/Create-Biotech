package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

/** Server-owned lifecycle fence for orderly runtime shutdown. */
public final class FactoryRuntimeLifecycle {
	public enum Phase { RUNNING, STOPPING_FROZEN, STOPPED }

	private Phase phase = Phase.RUNNING;

	public Phase phase() { return phase; }

	public boolean acceptsWork() { return phase == Phase.RUNNING; }

	public boolean technicalUnload() { return phase == Phase.STOPPING_FROZEN; }

	public boolean beginOrderlyShutdown() {
		if (phase != Phase.RUNNING) return false;
		phase = Phase.STOPPING_FROZEN;
		return true;
	}

	public boolean markStopped() {
		if (phase == Phase.STOPPED) return false;
		if (phase == Phase.RUNNING) phase = Phase.STOPPING_FROZEN;
		phase = Phase.STOPPED;
		return true;
	}
}
