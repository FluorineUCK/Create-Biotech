package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class FactoryPanelScrollHandleTest {
	@Test
	void realHandleCapturesDragsReleasesWheelsAndCallsBackOnlyOnChange() {
		FactoryPanelViewState.ViewState state = new FactoryPanelViewState()
			.forView(com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.TASKS);
		FactoryPanelScreen.ScrollHandle handle = new FactoryPanelScreen.ScrollHandle(state,
			FactoryPanelViewState.Viewport.PRIMARY);
		AtomicInteger callbacks = new AtomicInteger();
		handle.layout(0, 6, 20, 120, 20, 5);

		assertTrue(handle.mouseClicked(2, 25, 0, callbacks::incrementAndGet));
		assertTrue(handle.dragging());
		assertEquals(0, callbacks.get());
		assertTrue(handle.mouseDragged(120, 0, callbacks::incrementAndGet));
		assertEquals(15, handle.offset());
		assertEquals(1, callbacks.get());
		assertTrue(handle.mouseReleased(0));
		assertFalse(handle.dragging());
		assertFalse(handle.mouseReleased(0));

		assertTrue(handle.mouseScrolled(10, 40, 1, 0, 20, 100, 120,
			callbacks::incrementAndGet));
		assertEquals(14, handle.offset());
		assertEquals(2, callbacks.get());
		assertFalse(handle.mouseScrolled(110, 40, 1, 0, 20, 100, 120,
			callbacks::incrementAndGet));
		assertEquals(2, callbacks.get());
	}
}
