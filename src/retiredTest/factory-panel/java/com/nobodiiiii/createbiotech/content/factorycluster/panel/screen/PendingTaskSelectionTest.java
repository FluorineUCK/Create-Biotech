package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingTaskSelectionTest {
	@Test void failedExactAdmissionStopsPollingAndRetainsReasonUntilExplicitRetry() {
		var goal = UUID.randomUUID();
		var state = new PendingTaskSelection();
		state.accepted(goal);
		assertFalse(state.status(new com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks
			.TaskAdmissionStatus(UUID.randomUUID(), com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks
			.TaskAdmissionStatus.State.FAILED, "EPOCH_FENCED")));
		assertEquals(Optional.of(goal), state.goal());
		assertTrue(state.status(new com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks
			.TaskAdmissionStatus(goal, com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks
			.TaskAdmissionStatus.State.FAILED, "AMBIGUOUS_INPUT")));
		assertTrue(state.goal().isEmpty(), "failed admission must never poll forever");
		assertEquals(Optional.of("AMBIGUOUS_INPUT"), state.failure());
		state.page(0, 0, 0, Optional.empty());
		assertEquals(Optional.of("AMBIGUOUS_INPUT"), state.failure());
		state.accepted(UUID.randomUUID());
		assertTrue(state.failure().isEmpty(), "only a deliberate new submission starts retry");
	}
	@Test void acceptedAdmissionSurvivesEmptyAndMultiplePagesUntilGoalIsPublished() {
		var goal = UUID.randomUUID();
		var state = new PendingTaskSelection();
		state.accepted(goal);
		assertFalse(state.page(0, 0, 0, Optional.empty()));
		assertEquals(Optional.of(goal), state.goal());
		assertFalse(state.page(0, 4, 9, Optional.empty()));
		assertEquals(4, state.offset());
		assertFalse(state.page(4, 4, 9, Optional.empty()));
		assertEquals(8, state.offset());
		assertTrue(state.page(8, 1, 9, Optional.of(goal)));
		assertTrue(state.goal().isEmpty());
	}
	@Test void exhaustedPageScanRepeatsAndNewSelectionCancelsPendingNavigation() {
		var state = new PendingTaskSelection();
		state.accepted(UUID.randomUUID());
		state.page(0, 4, 4, Optional.empty());
		assertEquals(0, state.offset());
		assertTrue(state.goal().isPresent());
		state.cancel();
		assertTrue(state.goal().isEmpty());
	}
}
