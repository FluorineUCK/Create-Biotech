package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.COMPUTER_MONITOR;
import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.TASKS;
import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NETWORK_LOGISTICS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.panel.screen.FactoryPanelViewState;

class FactoryPanelViewStateTest {
	private static final UUID COMPUTER = new UUID(0, 1);
	private static final UUID GOAL = new UUID(0, 2);

	@Test
	void eachViewRetainsIndependentViewportAndSelection() {
		FactoryPanelViewState state = new FactoryPanelViewState();

		state.forView(COMPUTER_MONITOR).select(COMPUTER).scrollTo(9);
		state.forView(TASKS).select(GOAL).scrollTo(2);

		assertNotSame(state.forView(COMPUTER_MONITOR), state.forView(TASKS));
		assertEquals(9, state.forView(COMPUTER_MONITOR).scrollOffset());
		assertEquals(COMPUTER, state.forView(COMPUTER_MONITOR).selection().orElseThrow());
		assertEquals(2, state.forView(TASKS).scrollOffset());
		assertEquals(GOAL, state.forView(TASKS).selection().orElseThrow());
	}

	@Test
	void monitorGoalTreeViewportIsIndependentFromItsComputerList() {
		FactoryPanelViewState.ViewState monitor = new FactoryPanelViewState()
			.forView(COMPUTER_MONITOR);

		monitor.scrollTo(7);
		monitor.scrollTo(FactoryPanelViewState.Viewport.GOAL_TREE, 3);

		assertEquals(7, monitor.scrollOffset());
		assertEquals(3, monitor.scrollOffset(FactoryPanelViewState.Viewport.GOAL_TREE));
	}

	@Test
	void negativeViewportOffsetsAreRejectedInsteadOfLeakingIntoRequests() {
		FactoryPanelViewState.ViewState tasks = new FactoryPanelViewState().forView(TASKS);

		assertThrows(IllegalArgumentException.class, () -> tasks.scrollTo(-1));
		assertThrows(IllegalArgumentException.class,
			() -> tasks.scrollTo(FactoryPanelViewState.Viewport.DETAIL, -1));
	}

	@Test
	void logisticsNetworkAndComponentViewportsRemainIndependent() {
		FactoryPanelViewState.ViewState logistics = new FactoryPanelViewState()
			.forView(NETWORK_LOGISTICS);
		logistics.scrollTo(FactoryPanelViewState.Viewport.PRIMARY, 11);
		logistics.scrollTo(FactoryPanelViewState.Viewport.DETAIL, 4);
		assertEquals(11, logistics.scrollOffset(FactoryPanelViewState.Viewport.PRIMARY));
		assertEquals(4, logistics.scrollOffset(FactoryPanelViewState.Viewport.DETAIL));
	}
}
