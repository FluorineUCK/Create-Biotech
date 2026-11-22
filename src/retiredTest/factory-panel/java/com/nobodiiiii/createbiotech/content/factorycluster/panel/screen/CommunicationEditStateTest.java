package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.ListenerEditorProjection;

class CommunicationEditStateTest {
	private static final String FIRST = "listener-0000000000000001";
	private static final String SECOND = "listener-0000000000000002";

	@Test
	void samePublicationPageRefreshRetainsEachDirtyControlAndListenerIdentity() {
		CommunicationEditState state = new CommunicationEditState();
		state.accept(4, 7, "server-primary", List.of(editor(FIRST, "a", 2),
			editor(SECOND, "b", 3)));
		state.selectListener(SECOND);
		state.editListener("draft-listener");
		state.editPrimary("draft-primary");

		state.accept(4, 7, "refreshed-primary", List.of(editor(FIRST, "refresh-a", 2),
			editor(SECOND, "refresh-b", 3)));

		assertEquals("draft-primary", state.primaryText());
		assertTrue(state.primaryDirty());
		assertEquals(SECOND, state.listenerTarget());
		assertEquals("draft-listener", state.listenerText());
		assertTrue(state.listenerDirty());
	}

	@Test
	void explicitTargetSwitchAndConfirmedPublicationResetOnlyTheExpectedDrafts() {
		CommunicationEditState state = new CommunicationEditState();
		state.accept(4, 7, "old", List.of(editor(FIRST, "a", 2), editor(SECOND, "b", 3)));
		state.editPrimary("primary-draft");
		state.editListener("listener-draft");
		state.selectListener(SECOND);
		assertEquals("b", state.listenerText());
		assertFalse(state.listenerDirty());
		assertEquals("primary-draft", state.primaryText());

		state.accept(4, 8, "confirmed", List.of(editor(FIRST, "next-a", 4),
			editor(SECOND, "next-b", 5)));
		assertEquals("confirmed", state.primaryText());
		assertFalse(state.primaryDirty());
		assertEquals(SECOND, state.listenerTarget());
		assertEquals("next-b", state.listenerText());
		assertFalse(state.listenerDirty());
	}

	@Test
	void unrelatedPublicationChangesDoNotDiscardUnsubmittedDraft() {
		CommunicationEditState state = new CommunicationEditState();
		state.accept(4, 7, "old", List.of(editor(FIRST, "a", 2)));
		state.editPrimary("draft"); state.editListener("draft-listener");
		state.accept(4, 8, "old", List.of(editor(FIRST, "a", 2)));
		assertEquals("draft", state.primaryText());
		assertEquals("draft-listener", state.listenerText());
	}

	private static ListenerEditorProjection editor(String target, String frequency, long revision) {
		return new ListenerEditorProjection(target,
			List.of(CommunicationFrequency.of(frequency)), revision);
	}
}
