package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.COMPUTER_MONITOR;
import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NETWORK_COMMUNICATION;
import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NETWORK_LOGISTICS;
import static com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.TASKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;

class FactoryPanelNavigationControllerTest {
	@Test void lateResultDoesNotOverrideNewNavigationAndSamePageSelectionExplicitlyRequests() {
		List<FactoryPanelView> requests = new ArrayList<>();
		FactoryPanelNavigationController controller = new FactoryPanelNavigationController(TASKS, requests::add);
		long intent = controller.navigationVersion();
		controller.switchTo(NETWORK_LOGISTICS);
		assertFalse(controller.navigateResult(intent, COMPUTER_MONITOR));
		assertEquals(NETWORK_LOGISTICS, controller.selected());
		controller.switchTo(COMPUTER_MONITOR);
		int prior = requests.size();
		assertTrue(controller.navigateResult(controller.navigationVersion(), COMPUTER_MONITOR));
		assertEquals(prior + 1, requests.size());
		assertEquals(COMPUTER_MONITOR, requests.getLast());
	}

	@Test
	void switchingRequestsOnlyTheDestinationAndRetainsTheNetworkSubview() {
		List<FactoryPanelView> requests = new ArrayList<>();
		FactoryPanelNavigationController controller = new FactoryPanelNavigationController(
			COMPUTER_MONITOR, requests::add);
		controller.switchTo(TASKS);
		controller.switchTo(NETWORK_LOGISTICS);
		controller.switchTo(TASKS);
		controller.openNetwork();
		controller.switchTo(NETWORK_COMMUNICATION);
		controller.openNetwork();
		assertEquals(List.of(TASKS, NETWORK_LOGISTICS, TASKS, NETWORK_LOGISTICS,
			NETWORK_COMMUNICATION), requests);
		assertEquals(NETWORK_COMMUNICATION, controller.selected());
	}

	@Test
	void screenReinitRetainsLastNetworkSubviewThroughTasks() {
		List<FactoryPanelView> requests = new ArrayList<>();
		FactoryPanelNavigationController controller = FactoryPanelScreen.initializeNavigation(
			COMPUTER_MONITOR, requests::add, null);
		controller.switchTo(NETWORK_LOGISTICS);
		controller.switchTo(TASKS);

		FactoryPanelNavigationController afterResize = FactoryPanelScreen.initializeNavigation(
			TASKS, requests::add, controller);
		assertSame(controller, afterResize);
		afterResize.openNetwork();

		assertEquals(NETWORK_LOGISTICS, afterResize.selected());
		assertEquals(List.of(NETWORK_LOGISTICS, TASKS, NETWORK_LOGISTICS), requests);
	}
}
