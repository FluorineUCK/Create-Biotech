package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.CreateBiotech;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Named Task-12 acceptances; every case delegates to the same real production topology. */
@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FactoryOrchestrationGameTests {
	private FactoryOrchestrationGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_01", timeoutTicks = 12000)
	public static void localStandardAgentWarehouseActualStockCreditsGoal(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.LOCAL_STANDARD_AGENT);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_02", timeoutTicks = 12000)
	public static void neverRegisteredBareStandardUsesNetworkSummaryAbsoluteTarget(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.BARE_STANDARD);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_03", timeoutTicks = 12000)
	public static void restockerBareTargetUsesAttachedInventoryIdentityAndAbsoluteTarget(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.RESTOCKER_BARE);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_04", timeoutTicks = 12000)
	public static void exactMultiplierTwoProducesTwoSerialOnePackageRuns(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.EXACT_TWO);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_05", timeoutTicks = 12000)
	public static void sequencedTwoAddressAgentDispatchesInNativeOrder(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.SEQUENCED_TWO_ADDRESS);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_06", timeoutTicks = 12000)
	public static void knownOfflineAgentWaitsRouteAndNeverFallsBackBare(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.KNOWN_OFFLINE_AGENT);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_07", timeoutTicks = 12000)
	public static void postDispatchCancelDrainsThenRequiresReset(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.CANCEL_DRAIN);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_08", timeoutTicks = 12000)
	public static void dispatchIntentReloadIsUncertainAndDoesNotOrderTwice(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.DISPATCH_RELOAD);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_09", timeoutTicks = 12000)
	public static void radioDeliversAtMostOneHopPerServerTick(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.RADIO_ONE_HOP);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_10", timeoutTicks = 12000)
	public static void unconfiguredCrossClusterRouteWaits(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.RADIO_FREQUENCY_MISMATCH);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_11", timeoutTicks = 12000)
	public static void wanderingTraderRelaysAcrossExtendedRange(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.TRADER_RELAY);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_12", timeoutTicks = 12000)
	public static void remoteComputeLeaseNeverTransfersGoalOrMaterialAuthority(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.REMOTE_COMPUTE);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_13", timeoutTicks = 12000)
	public static void lostAckStatusReconciliationDoesNotDuplicateNativeOrder(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.LOST_ACK);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_14", timeoutTicks = 12000)
	public static void recoveryLedgerBackpressurePreservesReceiptEvidence(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.RECOVERY_BACKPRESSURE);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_15", timeoutTicks = 12000)
	public static void blockTickPublishesFactsAndWorkerNeverTouchesWorld(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper, FactoryProductionGameTestFixture.Scenario.WORKER_FACTS);
	}
	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_16", timeoutTicks = 12000)
	public static void localPlayerForceResetTerminatesContractAndPublishesNextGeneration(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.LOCAL_FORCE_RESET);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_17", timeoutTicks = 12000)
	public static void configuredSignalRisingEdgeSealsTheExactActiveRun(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.SIGNAL_COMPLETION);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_18", timeoutTicks = 12000)
	public static void oneSubmitSurvivesAsynchronousAdmissionReload(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.PENDING_ADMISSION_RELOAD);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_19", timeoutTicks = 12000)
	public static void componentOutputSurvivesPanelSearchCreateDispatchAndReload(
		GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.COMPONENT_OUTPUT_RELOAD);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_20", timeoutTicks = 12000)
	public static void radioTopologyUsesRealComputerLifecycleAndCurrentPoseFrames(
		GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.RADIO_TOPOLOGY);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_21", timeoutTicks = 12000)
	public static void wanderingTraderListenersUseRealRuntimeRouterAndTopology(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.EXTERNAL_LISTENER);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_22", timeoutTicks = 12000)
	public static void activeListenerRelayIsTheOnlyAutomaticExtendedRadioBridge(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.LISTENER_RELAY);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_23", timeoutTicks = 12000)
	public static void orderlyShutdownPreservesExactPendingRadioAuthority(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runScenario(helper,
			FactoryProductionGameTestFixture.Scenario.ORDERLY_RADIO_RESTART);
	}
}
