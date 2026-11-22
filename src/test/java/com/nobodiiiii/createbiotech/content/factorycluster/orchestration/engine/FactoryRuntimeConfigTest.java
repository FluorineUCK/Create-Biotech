package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class FactoryRuntimeConfigTest {
	private static final FactoryRuntimeConfig APPROVED_DEFAULTS = new FactoryRuntimeConfig(
		50, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 1200, 16, 256, 512, true);

	@Test
	void serverSnapshotCopiesEveryApprovedDefault() {
		assertEquals(APPROVED_DEFAULTS, FactoryRuntimeConfig.fromServerConfig());
		assertEquals(16_384,
			FactoryRuntimeConfig.fromServerConfig().logisticsAddressRetentionCapacity());
	}

	@Test
	void topologyModeIsPartOfTheImmutableRuntimeConfigSnapshot() {
		assertFalse(config(50, 2000, 32, 256, 600, 64, 64, 512,
			200, 40, 1200, 16, 256, 512, false).enableDynamicPhysicalNetworkTopology());
	}

	@Test
	void everyBudgetAndCapacityMustBePositive() {
		List<Executable> invalidConfigurations = List.of(
			() -> config(0, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 0, 32, 256, 600, 64, 64, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 0, 256, 600, 64, 64, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 0, 600, 64, 64, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 0, 64, 64, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 0, 64, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 0, 512, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 0, 200, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512, 0, 40, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512, 200, 0, 1200, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 0, 16, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 1200, 0, 256, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 1200, 16, 0, 512, true),
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 1200, 16, 256, 0, true)
		);

		for (int index = 0; index < invalidConfigurations.size(); index++)
			assertThrows(IllegalArgumentException.class, invalidConfigurations.get(index),
				"field index " + index + " accepted zero");
	}

	@Test
	void extendedListenerRangeMustCoverTheOrdinaryRangeWithinTheProfileBound() {
		assertThrows(IllegalArgumentException.class,
			() -> config(50, 2000, 32, 256, 600, 64, 65, 64, 200, 40, 1200, 16, 256, 512, true));
		assertThrows(IllegalArgumentException.class,
			() -> config(50, 2000, 32, 256, 600, 64, 64, 4161, 200, 40, 1200, 16, 256, 512, true));
	}

	@Test
	void recoveryLedgerCapacityCannotExceedTheCurrentSchemaCollectionRepresentation() {
		assertDoesNotThrow(() -> config(50, 2000, 32, 256, 600, 64, 64, 512,
			200, 40, 1200, 16, 16_384, 512, true));
		assertThrows(IllegalArgumentException.class,
			() -> config(50, 2000, 32, 256, 600, 64, 64, 512,
				200, 40, 1200, 16, 16_385, 512, true));
	}

	@Test
	void pendingRunCapacityCannotExceedTheCurrentSchemaCollectionRepresentation() {
		assertDoesNotThrow(() -> config(50, 2000, 32, 16_384, 600, 64, 64, 512,
			200, 40, 1200, 16, 256, 512, true));
		assertThrows(IllegalArgumentException.class,
			() -> config(50, 2000, 32, 16_385, 600, 64, 64, 512,
				200, 40, 1200, 16, 256, 512, true));
	}

	@Test
	void logisticsAddressRetentionCapacityMustFitTheCurrentSchemaCollectionRepresentation() {
		assertDoesNotThrow(() -> configWithLogisticsAddressRetentionCapacity(1));
		assertDoesNotThrow(() -> configWithLogisticsAddressRetentionCapacity(16_384));
		assertThrows(IllegalArgumentException.class,
			() -> configWithLogisticsAddressRetentionCapacity(0));
		assertThrows(IllegalArgumentException.class,
			() -> configWithLogisticsAddressRetentionCapacity(16_385));
	}

	private static FactoryRuntimeConfig configWithLogisticsAddressRetentionCapacity(int capacity) {
		return new FactoryRuntimeConfig(50, 2000, 32, 256, 600, 64, 64, 512,
			200, 40, 1200, 16, 256, capacity, 512, true);
	}
	private static FactoryRuntimeConfig config(int workerCadenceMillis, long workerBudgetMicros,
		int maxCommittedIntentsPerTick, int pendingRunCapacity, int libraryScanBatch,
		int libraryReplyBatch, int ordinaryListenerRange, int wanderingTraderListenerRange,
		int inputStallTicks, int quietWindowTicks, int firstOutputTimeoutTicks,
		int maxNoProgressRuns, int recoveryLedgerCapacity, int panelRowLimit,
		boolean enableDynamicPhysicalNetworkTopology) {
		return new FactoryRuntimeConfig(workerCadenceMillis, workerBudgetMicros,
			maxCommittedIntentsPerTick, pendingRunCapacity, libraryScanBatch, libraryReplyBatch,
			ordinaryListenerRange, wanderingTraderListenerRange, inputStallTicks, quietWindowTicks,
			firstOutputTimeoutTicks, maxNoProgressRuns, recoveryLedgerCapacity, panelRowLimit,
			enableDynamicPhysicalNetworkTopology);
	}

 @Test void advancedGogglesIntervalIsImmutableAndBoundedOneToOneHundred() {
  var d=FactoryRuntimeConfig.fromServerConfig();assertEquals(5,d.goggleDiagnosticsRequestIntervalTicks());
  for(int interval:new int[]{0,101})assertThrows(IllegalArgumentException.class,()->withGogglesInterval(d,interval));
  assertEquals(1,withGogglesInterval(d,1).goggleDiagnosticsRequestIntervalTicks());assertEquals(100,withGogglesInterval(d,100).goggleDiagnosticsRequestIntervalTicks());
 }
 private static FactoryRuntimeConfig withGogglesInterval(FactoryRuntimeConfig d,int interval) {
  return new FactoryRuntimeConfig(d.workerCadenceMillis(),d.workerBudgetMicros(),d.maxCommittedIntentsPerTick(),d.pendingRunCapacity(),d.libraryScanBatch(),d.libraryReplyBatch(),d.ordinaryListenerRange(),d.wanderingTraderListenerRange(),d.inputStallTicks(),d.quietWindowTicks(),d.firstOutputTimeoutTicks(),d.maxNoProgressRuns(),d.recoveryLedgerCapacity(),d.logisticsAddressRetentionCapacity(),d.panelRowLimit(),d.enableDynamicPhysicalNetworkTopology(),interval);
 }
}
