package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskAdmissionStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListResponsePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class TasksViewAdmissionTest {
	@Test void exactFailedResultStopsTrackingWithoutReplacingUsersSelectionOrScroll() {
		var navigation = new FactoryPanelViewState().forView(FactoryPanelView.TASKS);
		UUID previous = UUID.randomUUID(), accepted = UUID.randomUUID();
		var view = new TasksView(navigation);
		var opening = new FactoryPanelOpening(new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO),
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var projection = new TaskListProjection(opening.clusterId(), opening.epochId(), 1, 1,
			TaskListProjection.Status.CURRENT, 0, 0, List.of(), Optional.empty(), 4);
		assertTrue(view.accept(new TaskListResponsePacket(opening, projection, view.beginRequest()), opening));
		navigation.select(previous).scrollTo(5);
		view.trackAdmission(accepted);
		long intent = view.beginRequest();
		var failedPage = new TaskListProjection(opening.clusterId(), opening.epochId(), 2, 2,
			TaskListProjection.Status.CURRENT, 0, 0, List.of(), Optional.empty(), 4);
		var failure = new TaskListResponsePacket(opening, failedPage, intent,
			Optional.of(new TaskAdmissionStatus(accepted, TaskAdmissionStatus.State.FAILED, "AMBIGUOUS_INPUT")));
		assertFalse(view.accept(new TaskListResponsePacket(opening, projection, intent + 1, failure.admission()), opening));
		assertTrue(view.accept(failure, opening));
		assertEquals(Optional.of("AMBIGUOUS_INPUT"), view.admissionFailure());
		assertEquals(Optional.of(previous), navigation.selection());
		assertEquals(5, navigation.scrollOffset());
		assertEquals(Optional.of(previous), view.requestSelection());
		assertEquals(5, view.requestOffset());
		assertEquals(OptionalLong.of(1), view.knownRevision(), "failure retains the displayed page, not the admission scan page");
	}
}
