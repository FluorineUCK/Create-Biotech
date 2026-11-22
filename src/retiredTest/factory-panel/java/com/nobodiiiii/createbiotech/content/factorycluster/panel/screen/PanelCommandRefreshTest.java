package com.nobodiiiii.createbiotech.content.factorycluster.panel.screen;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class PanelCommandRefreshTest {
	@Test void acknowledgedMutationRetriesMissingPublicationUntilCurrentResponseArrives() {
		var opening = new FactoryPanelOpening(new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO),
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var state = new PanelCommandRefresh();
		var requests = new AtomicInteger();
		state.begin(opening, 1);
		state.accept(opening, 1, requests::incrementAndGet);
		for (int tick = 0; tick < 19; tick++) state.tick(requests::incrementAndGet);
		assertEquals(1, requests.get(), "not a per-tick request flood");
		state.tick(requests::incrementAndGet);
		assertEquals(2, requests.get(), "a missing topology publication must not abandon refresh");
		state.refreshed();
		for (int tick = 0; tick < 60; tick++) state.tick(requests::incrementAndGet);
		assertEquals(2, requests.get(), "current response ends retry");
	}
	@Test void resultsRefreshOnceAfterMatchingCommandWithoutNavigating() {
		var opening = new FactoryPanelOpening(new SpaceAddress(Level.OVERWORLD, null, BlockPos.ZERO),
			UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		var replacement = new FactoryPanelOpening(opening.panelAddress(), opening.panelId(),
			UUID.randomUUID(), opening.clusterId(), opening.epochId());
		var state = new PanelCommandRefresh();
		var refreshes = new AtomicInteger();
		state.begin(opening, 1);
		state.begin(opening, 2);
		assertFalse(state.accept(opening, 1, refreshes::incrementAndGet));
		assertFalse(state.accept(replacement, 2, refreshes::incrementAndGet));
		assertEquals(0, refreshes.get());
		assertTrue(state.accept(opening, 2, refreshes::incrementAndGet));
		assertFalse(state.accept(opening, 2, refreshes::incrementAndGet));
		assertEquals(1, refreshes.get());
	}
}
