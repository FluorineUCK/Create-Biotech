package com.nobodiiiii.createbiotech.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FactoryProductionGameTestFixtureRestartTest {
	@Test
	void traderRestartPublishesEveryComputerAndEndpointExactlyOnce() {
		List<String> calls = new ArrayList<>();

		FactoryProductionGameTestFixture.restartAndRepublish(
			() -> calls.add("stop"), () -> calls.add("start"),
			List.of("source"), List.of("radio-target", "radio-relay"),
			computer -> calls.add("observe:" + computer),
			List.of("input", "output"), List.of("input", "output", "warehouse"),
			panel -> calls.add("publish:" + panel));

		assertEquals(List.of("stop", "start", "observe:source", "observe:radio-target",
			"observe:radio-relay", "publish:input", "publish:output", "publish:warehouse"), calls);
	}
}
