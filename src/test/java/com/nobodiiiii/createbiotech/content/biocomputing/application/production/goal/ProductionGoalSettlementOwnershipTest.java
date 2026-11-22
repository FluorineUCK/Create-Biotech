package com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionGoalSettlementOwnershipTest {

	@Test
	void actualHostUsesOneSettlementOwnerInsteadOfAnObservationCache() throws Exception {
		Class<?> host = Class.forName("com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer$Runtime");
		assertTrue(Arrays.stream(host.getDeclaredFields()).anyMatch(field -> field.getType().getName().equals(
			"com.nobodiiiii.createbiotech.content.biocomputing.application.production.goal.ProductionGoalSettlement")), "actual host must compose the settlement owner");
		assertFalse(Arrays.stream(host.getDeclaredFields()).anyMatch(field -> field.getName().equals("goalObservations")));
	}

	@Test
	void cancellationReplayObservationAndSettlementKeepTheirOriginalTickPositions() throws Exception {
		String host = Files.readString(Path.of("src/test/java/com/nobodiiiii/createbiotech/content/factorycluster/runtime/FactoryOrchestrationServer.java"));
		int start = host.indexOf("\t\tprivate void tick() {");
		String tick = host.substring(start, host.indexOf("\n\t\tprivate void refreshLogisticsAddressDirectory()", start));
		String[] steps = { "reconcileCancellationDrainPlans();", "goalSettlement.replayDurableCredits();",
			"productionAdmissions.advanceTick(", "goalSettlement.observeStocks();", "advanceOutputContracts();",
			"localExpansion.retireCompleted();", "remoteExpansion.retireCompleted();", "goalSettlement.retireReceivedRuns();" };
		int prior = -1;
		for (String step : steps) {
			int current = tick.indexOf(step);
			assertTrue(current > prior, step + " must retain its actual tick position");
			prior = current;
		}
		assertFalse(host.contains("private boolean retireCompletedRunArtifacts("));
		assertFalse(host.contains("static OptionalLong activeRunRetirementCredit("));
		assertFalse(host.contains("static Optional<CompletedRunRemoteArtifactRetirement>"));
	}
}
