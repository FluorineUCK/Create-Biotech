package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListResponsePacket;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class TasksViewAcceptanceTest {
	private static final UUID CLUSTER = id(1), EPOCH = id(2);
	private static final FactoryPanelOpening OPENING = new FactoryPanelOpening(new SpaceAddress(
		Level.OVERWORLD, null, BlockPos.ZERO), id(3), id(4), CLUSTER, EPOCH);

	@Test
	void sameRevisionPagesAndStaleRecoveryFollowLatestOutstandingIntent() {
		FactoryPanelViewState.ViewState state = new FactoryPanelViewState()
			.forView(FactoryPanelView.TASKS);
		TasksView view = new TasksView(state);
		long initial = view.beginRequest();
		assertTrue(view.accept(current(7, 0, initial), OPENING));

		state.scrollTo(4);
		long pageFour = view.beginRequest();
		assertTrue(view.accept(current(7, 4, pageFour), OPENING));
		assertEquals(4, view.requestOffset());

		state.scrollTo(5); long superseded = view.beginRequest();
		state.scrollTo(6); long latest = view.beginRequest();
		assertFalse(view.accept(current(7, 5, superseded), OPENING));
		assertTrue(view.accept(current(7, 6, latest), OPENING));

		long staleIntent = view.beginRequest();
		assertTrue(view.accept(stale(8, staleIntent), OPENING));
		long recovery = view.beginRequest();
		assertTrue(view.accept(current(8, 6, recovery), OPENING));
		long lower = view.beginRequest();
		assertFalse(view.accept(current(7, 6, lower), OPENING));
		assertFalse(view.accept(current(8, 6, lower + 1), OPENING));
	}

	@Test
	void liveRefreshAcceptsNewProductionRevisionWithoutAStaleRetryLoop() {
		var state = new FactoryPanelViewState().forView(FactoryPanelView.TASKS);
		TasksView view = new TasksView(state);
		assertTrue(view.accept(current(7,4,view.beginRequest()),OPENING));
		long refresh = view.beginRefreshRequest();
		assertTrue(view.accept(current(8,4,refresh),OPENING),
			"a live refresh must read the newest immutable revision directly, not chase changing exact revisions");
		assertEquals(4,view.requestOffset());
	}

	private static TaskListResponsePacket current(long revision, int offset, long intent) {
		return new TaskListResponsePacket(OPENING, new TaskListProjection(CLUSTER, EPOCH, 1,
			revision, TaskListProjection.Status.CURRENT, offset, 10, List.of(), Optional.empty(),
			16), intent);
	}

	private static TaskListResponsePacket stale(long revision, long intent) {
		return new TaskListResponsePacket(OPENING, new TaskListProjection(CLUSTER, EPOCH, 1,
			revision, TaskListProjection.Status.STALE, 0, 10, List.of(), Optional.empty(), 16),
			intent);
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
