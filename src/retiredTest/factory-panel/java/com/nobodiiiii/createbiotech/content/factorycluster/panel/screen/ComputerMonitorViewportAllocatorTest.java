package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerCardProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorResponsePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorViewRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorViewRequest.ActiveViewport;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerSlotProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.ExecutionState;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNode;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection.TreeNodeKind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ComputerMonitorViewportAllocatorTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2), PANEL = id(3), SESSION = id(4);
	private static final UUID COMPUTER = id(10), PROCESS = id(20), GOAL = id(30);
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(
		new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO), PANEL, SESSION, CLUSTER, EPOCH);
	@Test
	void everyActiveLaneGetsAPageAtEachLegalLowBudget() {
		for (int budget = 1; budget <= 4; budget++) {
			for (ActiveViewport active : ActiveViewport.values()) {
				ComputerMonitorView.ViewportAllocation allocation =
					ComputerMonitorView.allocateRows(active, new int[] { 8, 4, 5 }, 0, budget);
				assertTrue(allocation.rows()[active.ordinal()] > 0,
					active + " starved at budget " + budget);
				assertTrue(allocation.requestedRows() <= budget);
			}
		}
	}

	@Test
	void activeFirstThenPrerequisitesAndTreeContextOnlyWhenTreeHasRows() {
		var slots = ComputerMonitorView.allocateRows(ActiveViewport.SLOTS,
			new int[] { 8, 4, 0 }, 9, 2);
		assertEquals(1, slots.rows()[ActiveViewport.SLOTS.ordinal()]);
		assertEquals(1, slots.rows()[ActiveViewport.COMPUTERS.ordinal()]);
		assertEquals(0, slots.rows()[ActiveViewport.GOAL_TREE.ordinal()]);
		assertFalse(slots.includeTreeContext());
		assertEquals(2, slots.requestedRows());

		var tree = ComputerMonitorView.allocateRows(ActiveViewport.GOAL_TREE,
			new int[] { 8, 4, 5 }, 3, 4);
		assertEquals(1, tree.rows()[ActiveViewport.GOAL_TREE.ordinal()]);
		assertTrue(tree.includeTreeContext());
		assertEquals(4, tree.requestedRows());
	}

	@Test
	void allocatedLimitsDriveAcceptedResponseScrollGeometryAtBudgetsOneThroughFour() {
		for (int budget = 1; budget <= 4; budget++) {
			for (ActiveViewport active : ActiveViewport.values()) {
				FactoryPanelViewState.ViewState state = deepState();
				ComputerMonitorView view = new ComputerMonitorView(state);
				view.activateLane(active);
				long intent = view.beginRequest();
				ComputerMonitorViewRequest request = view.monitorRequest(budget).orElseThrow();
				ComputerMonitorProjection projection = projection(request, budget);
				assertTrue(view.accept(new ComputerMonitorResponsePacket(OPENING, projection, intent),
					OPENING));

				view.layoutScrollbars(projection, 0, 6, 10, 16, 20, 70, 75, 125);
				for (ActiveViewport lane : ActiveViewport.values()) {
					int allocated = laneLimit(request, lane);
					assertEquals(5 - allocated, view.scrollHandle(lane).maximumOffset(),
						"wrong max scroll for " + active + "/" + lane + "/" + budget);
				}
				AtomicInteger requests = new AtomicInteger();
				int mouseX = active == ActiveViewport.COMPUTERS ? 2 : 12;
				int mouseY = active == ActiveViewport.GOAL_TREE ? 80 : 30;
				int laneTop = active == ActiveViewport.GOAL_TREE ? 75 : 20;
				int laneBottom = active == ActiveViewport.SLOTS ? 70 : 125;
				assertTrue(view.scrollLane(active, mouseX, mouseY, -1,
					active == ActiveViewport.COMPUTERS ? 0 : 10, laneTop,
					active == ActiveViewport.COMPUTERS ? 6 : 16, laneBottom,
					requests::incrementAndGet));
				assertEquals(1, requests.get());
				ComputerMonitorViewRequest paged = view.monitorRequest(budget).orElseThrow();
				assertEquals(3, laneOffset(paged, active));
			}
		}
	}

	@Test
	void zeroRowInactiveLaneCanActivateByNoOpWheelAndThenPageOrClick() {
		FactoryPanelViewState.ViewState state = deepState();
		ComputerMonitorView view = new ComputerMonitorView(state);
		view.activateLane(ActiveViewport.SLOTS);
		long intent = view.beginRequest();
		ComputerMonitorViewRequest slotsRequest = view.monitorRequest(1).orElseThrow();
		ComputerMonitorProjection projection = projection(slotsRequest, 1);
		view.accept(new ComputerMonitorResponsePacket(OPENING, projection, intent), OPENING);
		view.layoutScrollbars(projection, 0, 6, 10, 16, 20, 70, 75, 125);
		assertEquals(0, slotsRequest.computers().limit());

		AtomicInteger requests = new AtomicInteger();
		assertTrue(view.scrollLane(ActiveViewport.COMPUTERS, 2, 30, 0,
			0, 20, 6, 125, requests::incrementAndGet));
		assertEquals(ActiveViewport.COMPUTERS, view.activeViewport());
		assertEquals(1, requests.get());
		assertEquals(1, view.monitorRequest(1).orElseThrow().computers().limit());

		view.activateLane(ActiveViewport.SLOTS);
		assertTrue(view.clickScrollbar(ActiveViewport.COMPUTERS, 2, 124, 0,
			requests::incrementAndGet));
		assertEquals(ActiveViewport.COMPUTERS, view.activeViewport());
		assertEquals(2, requests.get());
	}

	@Test
	void viewComputationResultSelectsComputerAndProcessInGoalTreeLane() {
		FactoryPanelViewState.ViewState state = new FactoryPanelViewState()
			.forView(FactoryPanelView.COMPUTER_MONITOR);
		ComputerMonitorView view = new ComputerMonitorView(state);

		view.navigateTo(COMPUTER, PROCESS);

		assertEquals(ActiveViewport.GOAL_TREE, view.activeViewport());
		ComputerMonitorViewRequest request = view.monitorRequest(8).orElseThrow();
		assertEquals(Optional.of(COMPUTER), request.selectedComputerId());
		assertEquals(Optional.of(PROCESS), request.selectedProcessId());
		assertEquals(0, request.goalTree().offset());
	}

	private static FactoryPanelViewState.ViewState deepState() {
		return new FactoryPanelViewState().forView(FactoryPanelView.COMPUTER_MONITOR)
			.select(COMPUTER).selectDetail(PROCESS)
			.scrollTo(FactoryPanelViewState.Viewport.PRIMARY, 2)
			.scrollTo(FactoryPanelViewState.Viewport.DETAIL, 2)
			.scrollTo(FactoryPanelViewState.Viewport.GOAL_TREE, 2);
	}

	private static int laneLimit(ComputerMonitorViewRequest request, ActiveViewport lane) {
		return switch (lane) {
			case COMPUTERS -> request.computers().limit();
			case SLOTS -> request.slots().limit();
			case GOAL_TREE -> request.goalTree().limit();
		};
	}

	private static int laneOffset(ComputerMonitorViewRequest request, ActiveViewport lane) {
		return switch (lane) {
			case COMPUTERS -> request.computers().offset();
			case SLOTS -> request.slots().offset();
			case GOAL_TREE -> request.goalTree().offset();
		};
	}

	private static ComputerMonitorProjection projection(ComputerMonitorViewRequest request,
		int rowLimit) {
		List<ComputerCardProjection> cards = new ArrayList<>();
		for (int index = 0; index < request.computers().limit(); index++)
			cards.add(new ComputerCardProjection(id(10 + request.computers().offset() + index),
				ProductionProfession.CLERIC,
				2, 0, 5, ComputerCardProjection.AggregateState.IDLE, 0));
		List<ComputerSlotProjection> slots = new ArrayList<>();
		for (int index = 0; index < request.slots().limit(); index++)
			slots.add(ComputerSlotProjection.empty(request.slots().offset() + index));
		List<TreeNode> allTree = treeNodes();
		int treeOffset = request.goalTree().offset();
		int treeEnd = Math.min(allTree.size(), treeOffset + request.goalTree().limit());
		Optional<TreeNode> parent = request.includeTreeContext() && treeEnd > treeOffset
			&& allTree.get(treeOffset).depth() == 2 ? Optional.of(allTree.get(treeOffset - 1))
			: Optional.empty();
		Optional<GoalTreeProjection> tree = Optional.of(new GoalTreeProjection(GOAL,
			"minecraft:iron", 1, treeOffset, 5, parent,
			allTree.subList(treeOffset, treeEnd)));
		return new ComputerMonitorProjection(CLUSTER, EPOCH, 0,
			ComputerMonitorProjection.Status.CURRENT, request.computers().offset(), 5, cards,
			Optional.of(COMPUTER), request.slots().offset(), 5, slots,
			Optional.of(PROCESS), tree, rowLimit);
	}

	private static List<TreeNode> treeNodes() {
		String assigned = ComputerCardProjection.shortId(COMPUTER);
		return List.of(
			new TreeNode(0, TreeNodeKind.GOAL, "minecraft:iron", 1, "", Optional.empty(),
				ExecutionState.RUN, Optional.empty(), -1, Optional.empty(), Optional.empty()),
			new TreeNode(1, TreeNodeKind.PROCESS, "minecraft:iron", 1, "", Optional.empty(),
				ExecutionState.RUN, Optional.of(assigned), 0, Optional.empty(), Optional.of("kind=GOAL")),
			new TreeNode(2, TreeNodeKind.INVENTORY, "minecraft:iron", 1, "", Optional.empty(),
				ExecutionState.RUN, Optional.of(assigned), 0, Optional.empty(), Optional.empty()),
			new TreeNode(1, TreeNodeKind.PROCESS, "minecraft:iron", 1, "", Optional.empty(),
				ExecutionState.RUN, Optional.of(assigned), 1, Optional.empty(), Optional.of("kind=AGENT")),
			new TreeNode(2, TreeNodeKind.LISTENER, "minecraft:iron", 1, "", Optional.empty(),
				ExecutionState.RUN, Optional.of(assigned), 1, Optional.empty(), Optional.empty()));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
