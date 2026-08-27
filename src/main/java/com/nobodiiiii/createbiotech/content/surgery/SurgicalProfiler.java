package com.nobodiiiii.createbiotech.content.surgery;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Opt-in timing probe for the surgical table, enabled with
 * {@code -Dcreate_biotech.surgery.profile=true} on the client command line.
 *
 * <p>The table has several independently expensive phases spread across the packet-decode path, the
 * render thread and a worker pool, and an averaged frame-rate readout hides a single long frame
 * almost completely. This reports any individual phase that runs long, plus a running total per
 * phase, so a stutter can be attributed instead of guessed at.
 */
public final class SurgicalProfiler {
	public static final boolean ENABLED = Boolean.getBoolean("create_biotech.surgery.profile");
	/** Only individually slow runs are worth a line; anything quicker is noise at 50 ms per tick. */
	private static final long REPORT_THRESHOLD_NANOS = 2_000_000L;
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<String, AtomicLong> TOTAL_NANOS = new ConcurrentHashMap<>();
	private static final Map<String, AtomicLong> CALLS = new ConcurrentHashMap<>();

	private SurgicalProfiler() {}

	/** Returns a start stamp, or 0 when profiling is off so callers cost nothing. */
	public static long begin() {
		return ENABLED ? System.nanoTime() : 0L;
	}

	public static void end(String phase, long started) {
		if (!ENABLED || started == 0L)
			return;
		long elapsed = System.nanoTime() - started;
		long total = TOTAL_NANOS.computeIfAbsent(phase, ignored -> new AtomicLong()).addAndGet(elapsed);
		long calls = CALLS.computeIfAbsent(phase, ignored -> new AtomicLong()).incrementAndGet();
		if (elapsed < REPORT_THRESHOLD_NANOS)
			return;
		LOGGER.info("[surgery] {} took {} ms on thread {} (call #{}, {} ms total)",
			phase, format(elapsed), Thread.currentThread().getName(), calls, format(total));
	}

	private static String format(long nanos) {
		return String.format("%.2f", nanos / 1.0e6d);
	}
}
