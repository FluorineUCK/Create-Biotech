package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ComputerMonitorViewRequestTest {
	@Test
	void activeLaneDoesNotDiscardDeeperSelectionOrOtherOffsets() {
		UUID computer = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID process = UUID.fromString("00000000-0000-0000-0000-000000000002");
		ComputerMonitorViewRequest computers = request(
			ComputerMonitorViewRequest.ActiveViewport.COMPUTERS, computer, process);
		ComputerMonitorViewRequest slots = request(
			ComputerMonitorViewRequest.ActiveViewport.SLOTS, computer, process);
		ComputerMonitorViewRequest tree = request(
			ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE, computer, process);
		assertEquals(3, computers.activeOffset());
		assertEquals(5, slots.activeOffset());
		assertEquals(7, tree.activeOffset());
		assertEquals(Optional.of(computer), computers.selectedComputerId());
		assertEquals(Optional.of(process), computers.selectedProcessId());
	}

	private static ComputerMonitorViewRequest request(
		ComputerMonitorViewRequest.ActiveViewport active, UUID computer, UUID process) {
		return new ComputerMonitorViewRequest(active, Optional.of(computer), Optional.of(process),
			new ComputerMonitorViewRequest.Viewport(3, 4),
			new ComputerMonitorViewRequest.Viewport(5, 4),
			new ComputerMonitorViewRequest.Viewport(7, 4));
	}
}
