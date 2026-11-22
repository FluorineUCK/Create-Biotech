package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class FactoryPanelResponseTrackerTest {
	@Test
	void acceptsOnlyTheLatestOutstandingIntentAtTheExpectedPublication() {
		FactoryPanelResponseTracker tracker = new FactoryPanelResponseTracker();
		long firstPage = tracker.begin(OptionalLong.of(7));
		long secondPage = tracker.begin(OptionalLong.of(7));
		assertFalse(tracker.acceptCurrent(firstPage, 7));
		assertTrue(tracker.acceptCurrent(secondPage, 7));
		assertFalse(tracker.acceptCurrent(secondPage, 7));
	}

	@Test
	void rejectsLowerRevisionAndUnexpectedSameRevisionConflict() {
		FactoryPanelResponseTracker tracker = new FactoryPanelResponseTracker();
		long first = tracker.begin(OptionalLong.empty());
		assertTrue(tracker.acceptCurrent(first, 9));
		long lower = tracker.begin(OptionalLong.of(9));
		assertFalse(tracker.acceptCurrent(lower, 8));
		long conflict = tracker.begin(OptionalLong.of(9));
		assertFalse(tracker.acceptCurrent(conflict + 1, 9));
	}

	@Test
	void explicitlyAllowsExpectedStaleThenCurrentRecoveryAtSameRevision() {
		FactoryPanelResponseTracker tracker = new FactoryPanelResponseTracker();
		long staleIntent = tracker.begin(OptionalLong.of(4));
		assertTrue(tracker.acceptStale(staleIntent, 5));
		long recovery = tracker.begin(OptionalLong.of(5));
		assertTrue(tracker.acceptCurrent(recovery, 5));
	}
}
