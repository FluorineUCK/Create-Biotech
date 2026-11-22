package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;


public record FactoryRuntimeConfig(
	int workerCadenceMillis,
	long workerBudgetMicros,
	int maxCommittedIntentsPerTick,
	int pendingRunCapacity,
	int libraryScanBatch,
	int libraryReplyBatch,
	int ordinaryListenerRange,
	int wanderingTraderListenerRange,
	int inputStallTicks,
	int quietWindowTicks,
	int firstOutputTimeoutTicks,
	int maxNoProgressRuns,
	int recoveryLedgerCapacity,
	int logisticsAddressRetentionCapacity,
	int panelRowLimit,
	boolean enableDynamicPhysicalNetworkTopology,
	int goggleDiagnosticsRequestIntervalTicks
) {
	public FactoryRuntimeConfig(int workerCadenceMillis, long workerBudgetMicros,
  int maxCommittedIntentsPerTick,int pendingRunCapacity,int libraryScanBatch,int libraryReplyBatch,
  int ordinaryListenerRange,int wanderingTraderListenerRange,int inputStallTicks,int quietWindowTicks,
  int firstOutputTimeoutTicks,int maxNoProgressRuns,int recoveryLedgerCapacity,int logisticsAddressRetentionCapacity,
  int panelRowLimit,boolean enableDynamicPhysicalNetworkTopology) {
  this(workerCadenceMillis,workerBudgetMicros,maxCommittedIntentsPerTick,pendingRunCapacity,libraryScanBatch,libraryReplyBatch,ordinaryListenerRange,wanderingTraderListenerRange,inputStallTicks,quietWindowTicks,firstOutputTimeoutTicks,maxNoProgressRuns,recoveryLedgerCapacity,logisticsAddressRetentionCapacity,panelRowLimit,enableDynamicPhysicalNetworkTopology,5);
 }

	/** Compatibility constructor for existing worker fixtures; production snapshots opt in by default. */
	public FactoryRuntimeConfig(int workerCadenceMillis, long workerBudgetMicros,
		int maxCommittedIntentsPerTick, int pendingRunCapacity, int libraryScanBatch,
		int libraryReplyBatch, int ordinaryListenerRange, int wanderingTraderListenerRange,
		int inputStallTicks, int quietWindowTicks, int firstOutputTimeoutTicks,
		int maxNoProgressRuns, int recoveryLedgerCapacity, int panelRowLimit) {
		this(workerCadenceMillis, workerBudgetMicros, maxCommittedIntentsPerTick,
			pendingRunCapacity, libraryScanBatch, libraryReplyBatch, ordinaryListenerRange,
			wanderingTraderListenerRange, inputStallTicks, quietWindowTicks,
			firstOutputTimeoutTicks, maxNoProgressRuns, recoveryLedgerCapacity,
			FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES, panelRowLimit, true);
	}

	/** Compatibility constructor for fixtures that explicitly select topology mode. */
	public FactoryRuntimeConfig(int workerCadenceMillis, long workerBudgetMicros,
		int maxCommittedIntentsPerTick, int pendingRunCapacity, int libraryScanBatch,
		int libraryReplyBatch, int ordinaryListenerRange, int wanderingTraderListenerRange,
		int inputStallTicks, int quietWindowTicks, int firstOutputTimeoutTicks,
		int maxNoProgressRuns, int recoveryLedgerCapacity, int panelRowLimit,
		boolean enableDynamicPhysicalNetworkTopology) {
		this(workerCadenceMillis, workerBudgetMicros, maxCommittedIntentsPerTick,
			pendingRunCapacity, libraryScanBatch, libraryReplyBatch, ordinaryListenerRange,
			wanderingTraderListenerRange, inputStallTicks, quietWindowTicks,
			firstOutputTimeoutTicks, maxNoProgressRuns, recoveryLedgerCapacity,
			FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES, panelRowLimit,
			enableDynamicPhysicalNetworkTopology);
	}

	public FactoryRuntimeConfig {
		if(goggleDiagnosticsRequestIntervalTicks<1||goggleDiagnosticsRequestIntervalTicks>100)throw new IllegalArgumentException("Goggles interval outside 1..100");
		if (workerCadenceMillis <= 0 || workerBudgetMicros <= 0
			|| maxCommittedIntentsPerTick <= 0 || pendingRunCapacity <= 0
			|| libraryScanBatch <= 0 || libraryReplyBatch <= 0
			|| ordinaryListenerRange <= 0 || wanderingTraderListenerRange <= 0
			|| inputStallTicks <= 0 || quietWindowTicks <= 0
			|| firstOutputTimeoutTicks <= 0 || maxNoProgressRuns <= 0
			|| recoveryLedgerCapacity <= 0 || logisticsAddressRetentionCapacity <= 0
			|| panelRowLimit <= 0)
			throw new IllegalArgumentException("factory runtime budgets must be positive");
		if (wanderingTraderListenerRange < ordinaryListenerRange)
			throw new IllegalArgumentException("extended listener range cannot be shorter");
		if (wanderingTraderListenerRange - ordinaryListenerRange > 4096)
			throw new IllegalArgumentException("listener range bonus exceeds the profile bound");
		if (recoveryLedgerCapacity > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(
				"recovery ledger exceeds the durable collection representation");
		if (pendingRunCapacity > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(
				"pending Run capacity exceeds the durable collection representation");
		if (logisticsAddressRetentionCapacity
			> FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException(
				"logistics address retention exceeds the durable collection representation");
	}

	/** Frozen defaults used by the historical test harness, not by the game's server configuration. */
	public static FactoryRuntimeConfig fromServerConfig() {
		return new FactoryRuntimeConfig(50, 2000, 32, 256, 600, 64, 64, 512, 200, 40, 1200,
			16, 256, FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES, 512, true, 5);
	}

	public int wanderingTraderListenerBonus() {
		return Math.subtractExact(wanderingTraderListenerRange, ordinaryListenerRange);
	}
}
