package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.panel.screen.FactoryPanelScrollbar;

class FactoryPanelScrollbarTest {
	private static final int TRACK_TOP = 20;
	private static final int TRACK_BOTTOM = 120;

	@Test
	void thumbMapsDragToBoundedOffset() {
		FactoryPanelScrollbar scrollbar = new FactoryPanelScrollbar(TRACK_TOP, TRACK_BOTTOM,
			60, 40);

		assertEquals(40, scrollbar.dragTo(TRACK_BOTTOM, 100, 20));
		assertEquals(0, scrollbar.dragTo(TRACK_TOP - 50, 100, 20));
	}

	@Test
	void clickAndWheelRemainWithinTheAvailablePageRange() {
		FactoryPanelScrollbar scrollbar = new FactoryPanelScrollbar(TRACK_TOP, TRACK_BOTTOM,
			20, 8);

		assertEquals(8, scrollbar.clickTo(TRACK_BOTTOM));
		assertEquals(0, scrollbar.clickTo(TRACK_TOP));
		assertEquals(8, scrollbar.scroll(7, -4, 2));
		assertEquals(0, scrollbar.scroll(1, 4, 2));
	}

	@Test
	void trackAndThumbHitTestingUseHalfOpenBounds() {
		FactoryPanelScrollbar scrollbar = new FactoryPanelScrollbar(TRACK_TOP, TRACK_BOTTOM,
			20, 8);

		assertTrue(scrollbar.hitsTrack(TRACK_TOP));
		assertFalse(scrollbar.hitsTrack(TRACK_BOTTOM));
		assertTrue(scrollbar.hitsThumb(scrollbar.thumbTop(4), 4));
		assertFalse(scrollbar.hitsThumb(scrollbar.thumbBottom(4), 4));
	}

	@Test
	void proportionalThumbStaysVisibleForLargeContent() {
		assertEquals(12, FactoryPanelScrollbar.proportionalThumbLength(100, 1_000, 100, 12));
		assertEquals(100, FactoryPanelScrollbar.proportionalThumbLength(100, 5, 5, 12));
	}
}
