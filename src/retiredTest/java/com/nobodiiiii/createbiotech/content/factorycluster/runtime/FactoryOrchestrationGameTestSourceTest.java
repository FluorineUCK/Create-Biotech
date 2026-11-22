package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class FactoryOrchestrationGameTestSourceTest {
	private static final Path JAVA = Path.of("src/main/java/com/nobodiiiii/createbiotech");
	private static final Path GAME_TEST_JAVA = Path.of("src/test/java/com/nobodiiiii/createbiotech");

	@Test
	void diagnosticsAcceptanceScenariosRemainRegistered() throws Exception {
		for (var entry : java.util.Map.of("gametest/ComputerClusterGameTests.java", List.of(
			"normal_goggles_show_gameplay_state", "sneaking_goggles_show_exact_bounded_debug",
			"logistics_tooltip_hides_uuid", "local_agent_force_reset_only"),
			"gametest/PatternLibraryGameTests.java", List.of("panel_atlas_resource_loads")).entrySet()) {
			String source = read(entry.getKey());
			for (String method : entry.getValue())
				assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
					+ Pattern.quote(method) + "\\s*\\(").matcher(source).find(), method);
		}
	}

	@Test
	void separatedPanelProtocolHasVerticalGameTestsAndNoMixedSnapshotOrRemoteForce() throws Exception {
		String tests = read("gametest/ComputerClusterGameTests.java");
		for (String method : List.of("panel_requests_one_view", "panel_monitor_goal_tree",
			"panel_agent_new_task_defaults", "panel_network_collapses_subnet",
			"panel_logistics_is_read_only", "remote_panel_cannot_force_agent")) {
			assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
				+ Pattern.quote(method) + "\\s*\\(").matcher(tests).find(),
				() -> method + " must remain an executable vertical GameTest");
			assertTrue(methodBody(tests, method).contains(
				"FactoryProductionGameTestFixture.runPanelScenario("));
		}

		String network = read("network/CBPackets.java");
		String allProduction = Files.walk(JAVA).filter(path -> path.toString().endsWith(".java"))
			.map(path -> {
				try { return Files.readString(path); }
				catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
			}).collect(java.util.stream.Collectors.joining("\n"));
		assertFalse(Files.exists(JAVA.resolve(
			"content/factorycluster/panel/FactoryPanelCommandPacket.java")));
		assertFalse(Files.exists(JAVA.resolve(
			"content/factorycluster/panel/FactoryPanelSnapshotPacket.java")));
		for (String removed : List.of("FactoryPanelSnapshot.java", "FactoryPanelViewSession.java",
			"FactoryPanelRowBudget.java", "FactoryPanelRuntimeCommandGate.java", "AgentRowView.java",
			"GoalRowView.java", "ProcessRowView.java"))
			assertFalse(Files.exists(JAVA.resolve("content/factorycluster/panel/menu/" + removed)),
				() -> removed + " must not survive the separated Panel protocol migration");
		assertFalse(allProduction.contains("FactoryPanelCommandPacket.ForceReset"));
		assertFalse(network.contains("FactoryPanelSnapshotPacket"));
		for (String packet : List.of("FactoryPanelViewRequestPacket",
			"ComputerMonitorResponsePacket", "TaskListResponsePacket",
			"NewTaskContextResponsePacket", "CommunicationNetworkResponsePacket",
			"LogisticsNetworkResponsePacket", "FactoryPanelGoalCommandPacket",
			"FactoryPanelNewTaskPacket", "FactoryPanelCommunicationCommandPacket",
			"FactoryPanelLogisticsCommandPacket"))
			assertTrue(network.contains(packet + ".class"),
				() -> packet + " must have an independent Catnip registration");
		assertFalse(allProduction.contains("AgentLocalForceResetPacket"),
			"destructive force must have no client-constructible payload");
		String runtime = read("content/factorycluster/runtime/FactoryOrchestrationServer.java");
		for (String projector : List.of("capturePanelCommunicationDeclaration",
			"capturePanelCommunicationNetwork", "publishTaskSnapshot"))
			assertFalse(methodBody(runtime.substring(runtime.indexOf(
				"private static final class Runtime")), projector).contains(
					"communicationDirectory.ensure("),
				() -> projector + " must not initialize durable communication state from a Panel read");
		String localForce = methodBody(runtime.substring(runtime.indexOf(
			"private static final class Runtime")), "forceResetAgentLocally");
		for (String proof : List.of("endpoint.getLevel() != player.level()",
			"SubLevelCompat.canEntityInteractWith", "player.distanceToSqr(",
			"indexed.agentId().equals(configured.agentId())",
			"indexed.agentGeneration() != configured.agentGeneration()",
			"resetState.agentResetGeneration() != configured.agentGeneration()"))
			assertTrue(localForce.contains(proof), () -> "local force reset lacks proof: " + proof);
		String interaction = read("mixin/FactoryPanelBlockAgentInteractionMixin.java");
		assertTrue(interaction.contains("@Mixin(ServerPlayerGameMode.class)"));
		assertTrue(interaction.contains("player.isShiftKeyDown()"));
		assertTrue(interaction.contains("stack.isEmpty()"));
		assertFalse(read("content/factorycluster/panel/FactoryPanelGoalCommandPacket.java")
			.contains("ForceReset"), "Panel Goal commands must never expose destructive force reset");
	}

	@Test
	void gameTestsSubmitEveryScenarioThroughTheProductionPanelTopology() throws Exception {
		String tests = read("gametest/FactoryOrchestrationGameTests.java");
		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		List<String> methods = Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
			+ "([A-Za-z0-9_]+)\\s*\\(")
			.matcher(tests).results().map(match -> match.group(1)).toList();
		assertEquals(23, methods.size(), "every named orchestration acceptance must remain annotated");
		for (String method : methods) {
			String body = methodBody(tests, method);
			assertTrue(body.contains("FactoryProductionGameTestFixture.runScenario("),
				() -> method + " bypasses the shared production topology");
			for (String forbidden : List.of("PackageItem.containing(", "PackageItem.setOrder(",
				"packager.unwrapBox(", "new NativePackageObservation(",
				"LogisticsManager.findPackagersForRequest(", "dispatchAndDeliver("))
				assertFalse(body.contains(forbidden), () -> method + " directly uses " + forbidden);
		}

		assertTrue(methodBody(fixture, "runScenario").contains("new Topology(helper, scenario).place()"));
		String place = methodBody(fixture, "place");
		assertTrue(place.contains("CBBlocks.FACTORY_PANEL"));
		assertTrue(methodBody(fixture, "placeComputer").contains("CBBlocks.COMPUTER"));
		assertTrue(methodBody(fixture, "placeCore").contains("CBBlocks.PATTERN_STORAGE_CORE"));
		String bindAndStart = methodBody(fixture, "bindAndStart");
		assertTrue(bindAndStart.contains("computer.production().epoch().filter("));
        assertTrue(bindAndStart.contains("ClusterBindingService.replaceFormedBindings("));
        assertFalse(bindAndStart.contains("startEpoch("), "setup must consume automatic lifecycle, not inject an epoch");
		assertFalse(bindAndStart.contains("commandTarget()."));
		String send = methodBody(fixture, "sendPanelSubmit");
		assertTrue(send.contains("new FactoryPanelNewTaskPacket("));
		assertTrue(send.contains("menu.execute("));
		assertFalse(send.contains("commandTarget()."));
		assertTrue(methodBody(fixture, "awaitProductionDispatch")
			.contains("destination.inventory.insertItem("));

		String coreBlock = read("content/biocomputing/block/LibraryCoreBlock.java");
		assertTrue(methodBody(coreBlock, "useWithoutItem")
			.contains("host.useLibrary(player)"));
		assertFalse(methodBody(coreBlock, "useWithoutItem").contains("openMenu"));
		String core = read("content/biocomputing/library/LibraryBlockEntity.java");
		assertTrue(methodBody(core, "useLibrary").contains("openPatternBrowser(serverPlayer)"));
		assertFalse(core.contains("configureRouting("));
		assertFalse(core.contains("captureRoutingSessionFence("));
	}

	@Test
	void physicalShellGameTestsUseRegisteredBlocksAndLocalizedNames() throws Exception {
		String computerTests = read("gametest/ComputerClusterGameTests.java");
		String transparent = methodBody(computerTests, "transparent_shell_forms");
		assertTrue(transparent.contains("CBBlocks.TRANSPARENT_COMPUTER_CASING.get()"));
		assertTrue(transparent.contains("onSneakWrenched"));
		String facings = methodBody(computerTests, "computer_pair_all_facings");
		assertTrue(facings.contains("placeComputerThroughBlockMethods"));
		assertTrue(facings.contains("DoubleBlockHalf.UPPER"));

		String panel = methodBody(read("gametest/PatternLibraryGameTests.java"),
			"panel_shell_preserves_base");
		assertTrue(panel.contains("CBItems.FACTORY_PANEL.get().useOn"));
		assertTrue(panel.contains("onSneakWrenched"));
		assertTrue(panel.contains("expectedPayloads"));

		String panelBlock = read("content/factorycluster/panel/FactoryPanelBlock.java");
		String panelSneakWrench = methodBody(panelBlock, "onSneakWrenched");
		assertTrue(panelSneakWrench.contains("asConfiguredStack"));
		assertTrue(panelSneakWrench.contains("BASE_MATERIAL"));
		for (String removedPath : List.of("ClusterBindingSelection", "ClusterBindingService",
			"commitClusterBinding"))
			assertFalse(panelSneakWrench.contains(removedPath),
				() -> "embedded Panel restored removed binding path " + removedPath);

		JsonObject english = language("en_us.json");
		JsonObject chinese = language("zh_cn.json");
		assertEquals("Factory Panel", english.get("block.create_biotech.factory_panel").getAsString());
		assertEquals("Computer", english.get("block.create_biotech.computer").getAsString());
		assertEquals("Transparent Computer Casing",
			english.get("block.create_biotech.transparent_computer_casing").getAsString());
		assertEquals("工厂面板", chinese.get("block.create_biotech.factory_panel").getAsString());
		assertEquals("计算机", chinese.get("block.create_biotech.computer").getAsString());
		assertEquals("透明计算机外壳",
			chinese.get("block.create_biotech.transparent_computer_casing").getAsString());
	}

	@Test
	void logisticsLifecycleGameTestsUseRealCreateAndServerOwnedEvidence() throws Exception {
		String computerTests = read("gametest/ComputerClusterGameTests.java");
		for (String method : List.of("logistics_tool_binds_formed_cluster",
			"logistics_alias_is_global", "logistics_component_offline_last_seen",
			"bare_address_has_no_reverse_protocol")) {
			assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
				+ Pattern.quote(method) + "\\s*\\(").matcher(computerTests).find(),
				() -> method + " must remain a registered executable GameTest");
			assertTrue(methodBody(computerTests, method).contains(
				"FactoryProductionGameTestFixture.runLogisticsScenario("),
				() -> method + " must launch the shared physical logistics lifecycle");
		}

		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		assertTrue(methodBody(fixture, "placeCreateLink").contains("AllBlocks.STOCK_LINK")
			&& methodBody(fixture, "createLinkReady").contains(
				"LogisticallyLinkedBehaviour.getAllPresent"),
			"logistics acceptance must use initialized Create linked behaviours");
		String captureAndBind = methodBody(fixture, "captureAndBind");
		assertTrue(captureAndBind.contains("gameMode.useItemOn")
			&& captureAndBind.contains("LOGISTICS_CONFIGURATION_TOOL"));
		for (String forbidden : List.of("commitClusterBinding", "ClusterBindingService.bind",
			"ClusterBindingService.replace", "LogisticsAddressObserver",
			"LogisticsAddressObservationRegistry"))
			assertFalse(captureAndBind.contains(forbidden),
				() -> "the tool lifecycle must not fake production evidence with " + forbidden);
		String offline = methodBody(fixture, "observeThenUnloadComponent");
		assertTrue(offline.contains("level.removeBlock(targetLink.linkPos(), false)")
			&& offline.contains("offlineComponent(lastSeen)"));
		assertFalse(offline.contains(".observe("),
			"the offline GameTest must wait for the production observer cadence");
		String bare = methodBody(fixture, "exerciseBareCompatibility");
		assertTrue(bare.contains("LogisticsManager.getStockOf")
			&& bare.contains("LogisticsManager.broadcastPackageRequest")
			&& bare.contains("AgentResolution.NeverRegistered"),
			"bare compatibility must prove stock/dispatch without reverse Agent protocol");

		String projection = methodBody(fixture, "project");
		assertTrue(projection.contains("FactoryOrchestrationServer.logisticsDirectoryProjection"));
		assertFalse(projection.contains("new LogisticsNetworkProjectionService"),
			"the fixture must consume the production READY lookup composition");
		String runtime = read("content/factorycluster/runtime/FactoryOrchestrationServer.java");
		String refresh = methodBody(runtime, "refreshLogisticsAddressDirectoryNow");
		assertTrue(runtime.contains("Create.LOGISTICS.logisticsNetworks.keySet()")
			&& refresh.contains("directory.tryEnsure(network)"),
			"unknown live Create networks must enter the durable directory from server lifecycle");
		assertTrue(runtime.contains("new LogisticsAddressObservationRegistry(\n"
			+ "\t\t\t\tconfig.logisticsAddressRetentionCapacity()")
			&& runtime.contains("journal.logisticsAddressObserver(registryAccess,\n"
				+ "\t\t\t\tconfig.logisticsAddressRetentionCapacity())"),
			"the runtime snapshot must configure both live and durable logistics retention");
		assertTrue(runtime.contains("ClusterBindingService.bindingAccess(server, panel)")
			&& runtime.contains("new LogisticsNetworkProjectionService("),
			"production projection composition must fail closed behind the READY binding fence");

		JsonObject english = language("en_us.json");
		JsonObject chinese = language("zh_cn.json");
		assertEquals("Online", english.get("create_biotech.logistics.status.online").getAsString());
		assertEquals("Offline", english.get("create_biotech.logistics.status.offline").getAsString());
		assertEquals("Conflict", english.get("create_biotech.logistics.status.conflict").getAsString());
		assertEquals("在线", chinese.get("create_biotech.logistics.status.online").getAsString());
		assertEquals("离线", chinese.get("create_biotech.logistics.status.offline").getAsString());
		assertEquals("冲突", chinese.get("create_biotech.logistics.status.conflict").getAsString());
	}

	@Test
	void communicationAcceptanceGameTestsRemainRegisteredAndExerciseTheirPublicContracts()
		throws Exception {
		String computerTests = read("gametest/ComputerClusterGameTests.java");
		for (String method : List.of("literalCommunicationFrequencyMatchingIsCaseSensitive",
			"agentPublicationCarriesOnlyTheClusterPrimaryFrequency",
			"subnetBoundaryPublishesOnlyOpaquePublicServices",
			"wanderingTraderExternalListenersAreBoundedByTwoSlots")) {
			assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
				+ Pattern.quote(method) + "\\s*\\(").matcher(computerTests).find(),
				() -> method + " must remain a registered executable GameTest");
			assertTrue(methodBody(computerTests, method).contains("helper.succeed()"),
				() -> method + " must complete through the GameTest helper");
		}

		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		String productionTests = read("gametest/FactoryOrchestrationGameTests.java");
		assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
			+ "wanderingTraderListenersUseRealRuntimeRouterAndTopology\\s*\\(").matcher(
				productionTests).find(),
			"the external-listener production fixture must remain a registered executable GameTest");
		assertTrue(methodBody(productionTests,
			"wanderingTraderListenersUseRealRuntimeRouterAndTopology").contains(
			"Scenario.EXTERNAL_LISTENER"),
			"the external-listener GameTest must launch its real runtime scenario");
		assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
			+ "activeListenerRelayIsTheOnlyAutomaticExtendedRadioBridge\\s*\\(").matcher(
				productionTests).find(),
			"the automatic listener-relay production fixture must remain executable");
		assertTrue(methodBody(productionTests,
			"activeListenerRelayIsTheOnlyAutomaticExtendedRadioBridge").contains(
			"Scenario.LISTENER_RELAY"),
			"the listener-relay GameTest must launch its isolated real runtime scenario");
		assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
			+ "orderlyShutdownPreservesExactPendingRadioAuthority\\s*\\(").matcher(
				productionTests).find(),
			"the orderly-stop reconstruction acceptance must remain a registered executable GameTest");
		assertTrue(methodBody(productionTests,
			"orderlyShutdownPreservesExactPendingRadioAuthority").contains(
			"Scenario.ORDERLY_RADIO_RESTART"),
			"the orderly-stop GameTest must launch its isolated real runtime scenario");
		assertTrue(methodBody(fixture, "restartAndRepublish").contains("freeze")
			&& methodBody(fixture, "restartAndRepublish").contains("teardown"),
			"only the orderly-stop scenario may freeze authority before native teardown and restart");
		String admitted = methodBody(fixture, "continueAfterGoalAdmitted");
		assertTrue(admitted.contains("ORDERLY_RADIO_RESTART") && admitted.contains("-> agent()"),
			"the orderly-stop GameTest must dispatch through agent() instead of bare completion");
		String orderlyRestart = methodBody(fixture, "orderlyRestartPendingRadioRoute")
			+ methodBody(fixture, "orderlyAuthorityRestored");
		assertTrue(fixture.contains("beginOrderlyShutdown")
			&& orderlyRestart.contains("orderlyPreFreezeTarget")
			&& orderlyRestart.contains("remoteAgentRadioStatus"),
			"the orderly scenario must freeze, retain final-target authority, and observe its pending route");
		String listenerRuntime = read("content/factorycluster/communication/ExternalListenerRuntime.java");
		assertTrue(methodBody(listenerRuntime, "reserve").contains("external-listener-lease")
			&& methodBody(listenerRuntime, "reserve").contains("frequency.value()"),
			"a restarted listener must reclaim its deterministic slot lease before re-publishing authority");
		String lifecycle = methodBody(fixture, "assertProductionResolverRejectsReplacedComputerEvidence")
			+ methodBody(fixture, "continueProfileReplacementCheck")
			+ methodBody(fixture, "continueRestoredComputerEvidenceCheck")
			+ methodBody(fixture, "disassembleRestoredComputer");
		assertTrue(lifecycle.contains("shell disassembly must immediately withdraw"));
		assertTrue(lifecycle.contains("radioTopologyFrame()"));
		String traderListeners = methodBody(fixture, "exerciseExternalListenerTopology")
			+ methodBody(fixture, "assertExternalListenerPublications");
		assertTrue(traderListeners.contains("replaceExternalListeners(0, frequencies)"));
		String listenerProcessCapacity = methodBody(fixture,
			"exerciseExternalListenerIngressAndSubnetProjection");
		assertTrue(listenerProcessCapacity.contains("WAITING_PROCESS"),
			"the listener GameTest must prove the third requested frequency waits for a real process");
		String authorizedIngress = methodBody(fixture,
			"exerciseAuthorizedExternalListenerIngress") + methodBody(fixture,
				"continueAuthorizedExternalListenerIngress") + methodBody(fixture,
					"routeAuthorizedExternalListenerIngress");
		assertTrue(authorizedIngress.contains("forwardExternalListenerIngress("),
			"the listener GameTest must enter through the production Goal/lease ingress gate");
		assertTrue(authorizedIngress.contains("routeRadio("),
			"the listener GameTest must advance the authorized boundary envelope through durable outbox");
		assertFalse(authorizedIngress.contains("forwardExternalListenerBoundaryForTest("),
			"the listener GameTest must not bypass ingress authorization with the raw router seam");
		assertTrue(traderListeners.contains("radioTopologyFrame().sample(publication.endpoint().key())"));
		String traderRelay = methodBody(fixture, "traderRelayRouteReady");
		assertTrue(traderRelay.contains("routerRadioEndpoint("),
			"the relay dispatch gate must use the router's exact current endpoint, not a reconstructed copy");
		assertTrue(methodBody(fixture, "routerRadioEndpoint").contains("radioEndpointForTest("),
			"the relay dispatch gate must read the production router endpoint");
		assertTrue(methodBody(fixture, "agent").contains(
			"remoteAgentRadioProgressedPastFirstRelay("),
			"the Trader acceptance must read existing durable router/outbox progress, not write test evidence");
		String listenerRelay = methodBody(fixture, "configureListenerRelayAndRoute")
			+ methodBody(fixture, "awaitListenerRelayTopology");
		assertTrue(listenerRelay.contains("replaceExternalListeners(0")
			&& listenerRelay.contains("radioLinkForTest")
			&& listenerRelay.contains("LISTENER_RELAY_HOST_FREQUENCY"),
			"the listener-relay fixture must make the active listener the only real-frequency bridge");
		assertTrue(methodBody(fixture, "unloadListenerRelayAndSubmitRouteLossGoal").contains(
			"nativeComputerUnloaded(radioRelay.production())") && methodBody(fixture,
				"unloadListenerRelayAndSubmitRouteLossGoal").contains(
					"nativeComputerObserved(radioRelay.production())"),
			"the automatic relay fixture must inject a stale observe after retiring the actual listener");
		assertTrue(methodBody(fixture, "submitListenerRouteLossGoal").contains(
			"WaitReason.ROUTE"),
			"the post-unload production route must remain WAIT(ROUTE)");

		String productionServer = read("content/factorycluster/runtime/FactoryOrchestrationServer.java");
		assertTrue(productionServer.contains("listenerRelayCandidates()"),
			"active listener relays must enter the production automatic relay candidate registry");
		assertTrue(methodBody(productionServer, "discoverRadioRelays").contains(
			"listenerRelayCandidates"),
			"automatic discovery must inspect exact active listener relay candidates");
		assertTrue(productionServer.contains("remoteAgentCurrentRelayForTest"),
			"the automatic listener GameTest must read its selected first hop from the durable router/outbox");
		String remoteAgentDispatch = methodBody(productionServer, "scheduleRemoteAgentDispatch");
		assertTrue(remoteAgentDispatch.contains("radioLinkPossible(source, target, requesterFrequency)")
			&& remoteAgentDispatch.contains("relays.isEmpty()")
			&& remoteAgentDispatch.contains("WaitReason.ROUTE"),
			"a remote Agent must reject an absent direct-or-listener relay path before creating an outbox entry");
		assertFalse(productionServer.contains("remote_agent_relay/"),
			"ordinary production ticks must not persist test-only relay-hop artifacts");
		assertFalse(productionServer.contains("RemoteAgentRelayHopEvidence"),
			"test-only relay evidence must not remain in the production schema");
		assertFalse(productionServer.contains("observedComputerClusters"),
			"ordinary production observations must not retain unbounded test-only Cluster history");
		String savedData = read("content/factorycluster/runtime/FactoryOrchestrationSavedData.java");
		assertFalse(savedData.contains("remote_agent_relay/"),
			"run-artifact cleanup must not retain the removed test-only relay journal schema");
		assertTrue(methodBody(productionServer, "communicationConfigurationChanged").contains(
			"redriveClusterPrimaryFrequency"),
			"retune must re-drive all exact loaded Computer registrations instead of only the coordinator");
		assertFalse(productionServer.contains("installTestClusterPrimaryFrequencyResolver")
			|| productionServer.contains("restoreProductionClusterPrimaryFrequencyResolver")
			|| productionServer.contains("installTestPrimaryFrequencyResolver")
			|| productionServer.contains("refreshRadioTopologyForTest")
			|| productionServer.contains("forwardExternalListenerBoundaryForTest"),
			"production must not expose write-capable frequency/topology/listener test seams");
		assertFalse(productionServer.contains(".forwardBoundary("),
			"the live composition root must not call the router boundary primitive directly");
		assertFalse(read("content/factorycluster/communication/ExternalListenerRuntime.java")
			.contains(".forwardBoundary("),
			"listener runtime transitions must enter through the authorized listener service");
		assertFalse(read("content/factorycluster/orchestration/radio/RadioRouter.java")
			.contains("replaceTestResolverFrequency"),
			"the router must not expose a public test-only primary-frequency write alias");
		assertFalse(fixture.contains("installTestClusterPrimaryFrequencyResolver")
			|| fixture.contains("restoreProductionClusterPrimaryFrequencyResolver")
			|| fixture.contains("refreshRadioTopologyForTest"),
			"communication GameTests must use durable commands and real post-tick topology frames");
		assertTrue(methodBody(fixture, "configureScenarioPrimaryFrequency").contains(
			"declareClusterPrimary"),
			"scenario primaries must pass through the formed-authority durable command service");
		String coordinator = read("content/biocomputing/application/production/computer/ComputerCoordinatorMember.java");
		assertTrue(methodBody(coordinator, "primaryFrequencyDependencyChanged").contains(
			"coordination.refreshLoadedReplicas()"),
			"primary retunes must reach the application-owned replica set");
		String productionCoordinator = read("content/biocomputing/application/production/binding/ProductionCoordinator.java");
		assertTrue(methodBody(productionCoordinator, "refreshLoadedReplicas").contains(
			"filter(access::authoritativeLoaded).toList().forEach(access::dependencyChanged)"),
			"primary retunes must derive an authoritative current loaded replica snapshot");
		assertTrue(methodBody(coordinator, "authoritativeLoaded").contains(
			"host.isAuthoritativeLoadedInstance()")
			&& methodBody(coordinator, "dependencyChanged").contains("host.topologyDependencyChanged()"),
			"the application port must qualify and notify the actual host, not a disconnected replica model");
		String computer = read("content/biocomputing/computer/ComputerBlockEntity.java");
		assertTrue(methodBody(productionCoordinator, "refreshLoadedReplicas").contains(
			"filter(host -> access.coordinator(host).isAttachedTo(this))")
			&& methodBody(coordinator, "coordinator").contains("host.coordinatorMember.coordination"),
			"retune replicas must remain attached to this exact publication as well as currently loaded");
		assertTrue(methodBody(computer, "isAuthoritativeLoadedInstance").contains(
			"getBlockEntity(worldPosition) == this"),
			"production observations must fence stale replacements by current Level identity");
		assertTrue(methodBody(productionServer, "productionLibraryTopologyChanged").contains(
			"redriveClusterTopology"),
			"Core lifecycle changes must re-drive the primary Computer owned by the affected Cluster");
		String librarySession = read("content/biocomputing/application/production/ProductionLibrarySession.java");
		assertTrue(methodBody(librarySession, "topologyRebound").contains("backend.topologyChanged(priorGroup)")
			&& methodBody(librarySession, "topologyRebound").contains("backend.topologyChanged(binding.groupId())"),
			"Core rebind must re-drive both the prior and replacement Cluster primary lifecycles");
		String libraryBackend = read("content/biocomputing/application/production/ProductionLibraryRuntimeAccess.java");
		assertTrue(methodBody(libraryBackend, "topologyChanged").contains("productionLibraryTopologyChanged"),
			"The application callback must reach the actual production lifecycle, not a disconnected interface");
		String core = read("content/biocomputing/library/LibraryBlockEntity.java");
		assertTrue(methodBody(core, "notifyRuntimeTopologyChanged").contains("productionSession.topologyChanged()")
			&& methodBody(core, "notifyRuntimeTopologyRebound").contains("productionSession.topologyRebound(priorClusterId)"));
		assertTrue(methodBody(core, "commitClusterBinding").contains("priorClusterId"),
			"a Core rebind must retain the old Cluster identity until both topology sides are re-driven");
		assertTrue(authorizedIngress.contains(
			"publicSubnetProjectionForTest("),
			"the production listener fixture must inspect runtime-published opaque subnet services");
		assertTrue(authorizedIngress.contains("publication.relayEndpoint().key()"),
			"public subnet inspection must pass only an active listener publication identity");
		assertTrue(methodBody(productionServer, "publicSubnetProjectionForTest").contains(
			"activeListenerRelayKey"),
			"subnet projection must derive identity from the active exact listener binding, not test values");
		assertTrue(methodBody(fixture, "assertAllLoadedComputerOwnersRetuneTogether").contains(
			"radioReplica") && methodBody(fixture, "completeAllLoadedComputerOwnersRetune").contains(
			"listenerAfter.relayEndpoint().generation()"),
			"the production topology fixture must cover coordinator plus Trader non-coordinator retune");
		assertTrue(methodBody(fixture, "retireNonCoordinatorBeforePrimaryRetune").contains(
			"level.removeBlock(radioReplica.getBlockPos(), false)")
			&& methodBody(fixture, "assertRetainedReplicaRemainsRetired").contains(
				"hasIndexedShellMembership"),
			"the production topology fixture must keep a retained unloaded replica retired across retune");
		String radioTopologyLifecycle = methodBody(fixture, "exerciseRadioTopologyLifecycle")
			+ methodBody(fixture, "continueRadioTopologyLifecycle")
			+ methodBody(fixture, "continueRadioTopologyLifecycleAfterCoreRebind");
		assertTrue(radioTopologyLifecycle.contains(
			"radioEndpointRegisteredForTest"),
			"the reload GameTest must verify router and topology recover together");
		assertTrue(radioTopologyLifecycle.contains(
			"declareClusterPrimary(radioOwnerPanel(), CommunicationFrequency.UNCONFIGURED)")
			&& radioTopologyLifecycle.contains("nativeComputerUnloaded(radioTarget.production())"),
			"the lifecycle GameTest must cover a fenced retune followed by same-tick teardown");
		assertTrue(methodBody(fixture, "assertCoreRebindDrivesBothPrimaryEndpoints").contains(
			"afterOld.generation() > beforeOld.generation()") && methodBody(fixture,
				"assertCoreRebindDrivesBothPrimaryEndpoints").contains("afterNew.generation() > beforeNew.generation()"),
			"the Core rebind GameTest must check both old and new primary router generations");
	}

	@Test
	void orderlyServerShutdownFreezesBeforeStoppingTheFactoryRuntime() throws Exception {
		String createBiotech = read("CreateBiotech.java");
		String lifecycleEvents = methodBody(createBiotech, "registerAllayEvents");
		int stopping = lifecycleEvents.indexOf("ServerStoppingEvent");
		int freeze = lifecycleEvents.indexOf("FactoryOrchestrationServer.beginOrderlyShutdown");
		int stopped = lifecycleEvents.indexOf("ServerStoppedEvent");
		int stop = lifecycleEvents.indexOf("FactoryOrchestrationServer.stop");
		assertTrue(stopping >= 0 && freeze > stopping,
			"ServerStoppingEvent must freeze the factory runtime");
		assertTrue(stopped >= 0 && stop > stopped && freeze < stop,
			"ServerStoppedEvent must dispose the runtime only after its freeze barrier");

		String runtime = read("content/factorycluster/runtime/FactoryOrchestrationServer.java");
		String runtimeImplementation = runtime.substring(runtime.indexOf("private static final class Runtime"));
		assertTrue(methodBody(runtime, "computerUnloaded")
			.contains("lifecycle.technicalUnload() || !acceptsWork()"),
			"a frozen unload must return before it retires listener authority");
		assertTrue(methodBody(runtimeImplementation, "intentResolved")
			.contains("if (!acceptsWork()) return;"),
			"a frozen runtime must not journal late Create-intent resolution");
		assertTrue(methodBody(runtimeImplementation, "validateCommunicationAuthority")
			.contains("if (!acceptsWork()) return"),
			"a communication command retained before freeze must become unavailable");
		assertTrue(methodBody(runtimeImplementation, "panelViewResponse")
			.contains("if (!acceptsWork()) return Optional.empty();"),
			"all separated Panel reads must fail closed while the runtime is frozen");
		String runtimeService = read("content/factorycluster/orchestration/engine/FactoryRuntimeService.java");
		assertTrue(methodBody(runtimeService, "peekSnapshotForPanelView").contains(
			"return Optional.ofNullable(states.get"),
			"the frozen Panel snapshot path must read state without pumping worker results");
		assertTrue(methodBody(runtimeImplementation, "managedInputRoute")
			.contains("if (!acceptsWork()) return Optional.empty();"),
			"a frozen native package callback must not select a managed input route");
		int nativePublication = runtimeImplementation.indexOf(
			"@Override public void publish(NativePackageObservation observation)");
		assertTrue(nativePublication >= 0, "the native package publication callback must exist");
		assertTrue(methodBody(runtimeImplementation.substring(nativePublication), "publish")
			.contains("if (!acceptsWork()) return;"),
			"a frozen native package callback must not publish contracts or dispatch work");
		assertTrue(methodBody(runtimeImplementation, "recordRecoveryRequired")
			.contains("if (!acceptsWork()) return;"),
			"a frozen native package recovery callback must not write the journal");
		assertTrue(methodBody(runtime, "communicationConfiguration")
			.contains("runtime.communicationConfiguration"),
			"frozen communication configuration remains a read, independent of write admission");
		for (String frozenControl : List.of("suspendNextInputDispatch",
			"dropNextRecoveryAcknowledgement", "constrainRecoveryAdmissionHeadroom",
			"restoreConfiguredRecoveryAdmission"))
			assertTrue(methodBody(runtimeImplementation, frozenControl)
				.contains("if (!acceptsWork()) return false;"),
				"frozen task-control mutation must fail closed: " + frozenControl);
		assertFalse(runtime.contains("public static TestRuntime"),
			"production code must not export a mutable lifecycle test runtime");
		assertFalse(runtime.contains("public static final class TestRuntime"),
			"production code must not export a mutable lifecycle test runtime");
		assertTrue(runtime.contains("private Runtime(RuntimeComposition composition)"),
			"production and test injection must share the one Runtime composition constructor");
		assertFalse(runtime.contains("private Runtime(MinecraftServer server, FactoryRuntimeConfig config,"),
			"a shortened test-only Runtime constructor would drift from durable production wiring");
	}

	@Test
	void patternCoreAcceptanceRetainsItsTwoCellLifecycleCoverage() throws Exception {
		String patternTests = read("gametest/PatternLibraryGameTests.java");
		assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
			+ "configuredCorePlacementAndDirectLibrarianInstall\\s*\\(")
			.matcher(patternTests).find(),
			"configured placement/direct installation must be a registered executable GameTest");
		assertTrue(methodBody(patternTests, "configuredCorePlacementAndDirectLibrarianInstall")
			.contains("assertCoreHalves(helper, level, lower)"));
		assertTrue(methodBody(patternTests, "emptyHandUsesReadOnlyBrowserHook")
			.contains("runPatternBrowserScenario"));
		assertTrue(methodBody(patternTests, "upperRemovalDelegatesExactlyOnce").contains(
			"Upper removal must complete the lower transaction"));

		String coreBlock = read("content/biocomputing/block/LibraryCoreBlock.java");
		assertTrue(methodBody(coreBlock, "setPlacedBy").contains("DoubleBlockHalf.UPPER"));
		assertTrue(methodBody(coreBlock, "newBlockEntity").contains("DoubleBlockHalf.LOWER"));
		assertTrue(methodBody(coreBlock, "useWithoutItem").contains(
			"getMultiBlockAnchor(pos, state)"));
	}

	@Test
	void patternCoreBrowserVerticalAcceptancesStayRegisteredAndReadOnly() throws Exception {
		String patternTests = read("gametest/PatternLibraryGameTests.java");
		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		for (String method : List.of("pattern_core_requires_item_configuration",
			"pattern_core_routing_is_frozen", "pattern_browser_keeps_complete_generation",
			"pattern_browser_pages_valid_cache_only", "pattern_browser_does_not_consume_process")) {
			assertTrue(Pattern.compile("@GameTest\\([^)]*\\)\\s*public static void\\s+"
				+ method + "\\s*\\(").matcher(patternTests).find(),
				method + " must remain a registered executable GameTest");
			assertTrue(methodBody(patternTests, method).contains("runPatternBrowserScenario"),
				method + " must use the shared production vertical fixture");
		}
		assertTrue(Pattern.compile("@GameTest\\([^)]*batch\\s*=\\s*"
			+ "\\\"task5_pattern_browser_isolation\\\"[^)]*\\)\\s*public static void\\s+"
			+ "pattern_browser_does_not_consume_process\\s*\\(").matcher(patternTests).find(),
			"the long-lived four-phase browser-isolation acceptance must own its dedicated batch");
		for (String category : List.of("ITEM_FILTER", "TAG_FILTER", "ADDRESS_FILTER",
			"KIND_FILTER", "GHOST_FILTER"))
			assertTrue(fixture.contains(category),
				"the live browser fixture must retain the exact filter category: " + category);
		assertTrue(fixture.contains("roundTripPanelForTest(request, wire).handle(player)"),
			"vertical filters must pass through the registered request packet entry");
		assertTrue(fixture.contains("takePanelDeliveryForTest(player)"),
			"vertical assertions must consume the actual registered browser response");
		String core = read("content/biocomputing/library/LibraryBlockEntity.java");
		assertTrue(methodBody(core, "browserDiagnostics").contains("productionSession.workload()"),
			"browser diagnostics must report the production runtime Library workload");
		String session = read("content/biocomputing/application/production/ProductionLibrarySession.java");
		String backend = read("content/biocomputing/application/production/ProductionLibraryRuntimeAccess.java");
		assertTrue(methodBody(session, "workload").contains("backend.workload(binding.groupId())")
			&& methodBody(backend, "workload").contains("patternLibraryWorkload(server, group)"),
			"The migrated observation must still read the real production backend");
		assertFalse(methodBody(backend, "workload").contains("factoryRuntime("),
			"Browser observation must not create a production runtime");
		assertFalse(methodBody(backend, "workload").contains("ProductionRuntimeServices.get("),
			"Browser observation must not start the migrated production service either");
		assertFalse(methodBody(core, "browserDiagnostics").contains("libraryIndex.activeQueryCount"),
			"browser diagnostics must not report the disconnected local Index queues");
		String runtime = read("content/factorycluster/runtime/FactoryOrchestrationServer.java");
		String coordination = methodBody(runtime, "patternBrowserCoordination");
		assertTrue(coordination.contains("peekSnapshotForPanelView(clusterId)"),
			"browser coordination must use the non-pumping immutable runtime snapshot");
		assertFalse(coordination.contains("runtime.runtime.snapshot(clusterId)"),
			"browser coordination must not pump production results");

		String packets = read("network/CBPackets.java");
		assertFalse(packets.contains("PatternStorageCoreConfigurationPacket.class"),
			"placed Core routing mutation must not be registered");
		assertFalse(packets.contains("pattern_core/configuration"),
			"placed Core routing must not acquire a packet id");
	}

	@Test
	void browserIsolationCleanupCannotTurnResidualProductionStateGreen() throws Exception {
		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		String cleanup = methodBody(fixture, "cleanupProductionIsolation");
		assertFalse(cleanup.contains("remaining <= 0) { finished.run()"),
			"cleanup timeout must never invoke the success continuation");
		assertTrue(cleanup.contains("failureSink.accept("),
			"cleanup timeout must finish through its exact failure sink");
		assertTrue(methodBody(fixture, "failProductionIsolation")
			.contains("FactoryProductionGameTestFixture::finishProductionIsolationFailure"),
			"the live boundary must rethrow through the exact failure sink");
		for (String diagnostic : List.of("active=", "ready=", "process=", "pending=",
			"reservation=", "publishReturns=", "releaseReturns=", "runtimeDiagnostics="))
			assertTrue(cleanup.contains(diagnostic),
				"cleanup timeout must report production diagnostic: " + diagnostic);
		assertTrue(cleanup.contains("combinedProductionIsolationFailure("),
			"cleanup timeout must preserve and combine the original failure");
		assertTrue(methodBody(fixture, "failProductionIsolation")
			.contains("cleanupProductionIsolation(runtime, Objects.requireNonNull(failure)"),
			"failure cleanup must carry the original failure through cleanup");
		for (String phase : List.of("submitProductionQuery",
			"proveReadyReplyWithoutConsumption", "establishProcessWithoutConsumption",
			"finishProcessPhaseAndPublishIntent", "publishAndObservePendingIntent",
			"compareStablePendingIntentAcrossBrowserRead"))
			assertTrue(methodBody(fixture, phase).contains("runProductionIsolationPhase")
				|| methodBody(fixture, phase).contains("scheduleProductionIsolationPhase")
				|| methodBody(fixture, phase).contains("awaitProductionIsolation"),
				phase + " must remain inside the post-query failure boundary");
		assertTrue(fixture.contains("INJECTED_BROWSER_ISOLATION_FAILURE"),
			"the real non-empty vertical must inject an assertion failure before teardown");
		assertTrue(fixture.contains("assertProductionIsolationFullyClean"),
			"the fault-injection regression must prove all four production lanes are empty");
	}

	@Test
	void browserPaidAuthorityGateUsesOnlyThePureBindingSnapshotProof() throws Exception {
		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		String core = read("content/biocomputing/library/LibraryBlockEntity.java");
		String binding = read("content/factorycluster/ClusterBindingService.java");
		String browserService = methodBody(core, "browserService");
		assertTrue(browserService.contains("snapshotBindingAccess"));
		assertFalse(browserService.contains("authorityAccessReady"));
		String pure = methodBody(binding, "snapshotBindingAccess");
		assertFalse(pure.contains("reconcileLoaded"));
		assertFalse(pure.contains("prepare("));
		assertFalse(pure.contains("commitClusterBinding"));
		assertTrue(fixture.contains("stale paid-overlay browser read"));
		assertTrue(fixture.contains("panelPublicationCountsForTest"));
	}

	@Test
	void configuredPanelPublicationFailureKeepsCompleteNativeDataForStableRetry() throws Exception {
		String tests = read("gametest/FactoryPlayerEntryGameTests.java");
		String failure = methodBody(tests,
			"failedPartialPublicationRollsBackAndNextCycleRetriesStableIdentity");
		assertTrue(failure.contains("configuredPanelItem(network)"));
		assertTrue(failure.contains("selectedNetwork()"));
		assertTrue(failure.contains("saveWithoutMetadata"));
		assertTrue(failure.contains("Failed configured publication must preserve complete"));
		assertTrue(failure.contains("Failed publication cannot leave a visible Panel service"));
		assertTrue(failure.contains("Successful retry must retain the selected native network"));
		assertTrue(methodBody(tests, "configuredPanelItem").contains("SelectedNetwork"));
	}

	@Test
	void coreRoutingFixtureUsesOnlyFrozenPlacementData() throws Exception {
		String fixture = read("gametest/FactoryProductionGameTestFixture.java");
		String placement = methodBody(fixture, "placeCore");
		assertTrue(placement.contains("applyPlacementData(LibraryCoreItemData.configured("));
		String validation = methodBody(fixture, "configureCoreRouting");
		assertTrue(validation.contains("core.routingConfiguration()"));
		assertFalse(validation.contains("configureRouting("));
		assertFalse(fixture.contains("PatternStorageCoreConfigurationPacket"));
	}

	private static String read(String relative) throws Exception {
		Path root = relative.startsWith("gametest/") ? GAME_TEST_JAVA : JAVA;
		return Files.readString(root.resolve(relative));
	}

	private static JsonObject language(String file) throws Exception {
		return JsonParser.parseString(Files.readString(Path.of(
			"src/main/resources/assets/create_biotech/lang").resolve(file))).getAsJsonObject();
	}

	@Test
	void methodExtractionIgnoresCommentQuotesAndBracesWithoutConsumingLaterMethods() {
		String source = """
			void sample() {
				// A comment's closing brace } is not Java syntax.
				/* Neither is this opening brace { */
				String literal = "}";
				char brace = '{';
				if (true) {}
			}
			void later() {}
			""";
		String body = methodBody(source, "sample");
		assertTrue(body.contains("if (true) {}"));
		assertFalse(body.contains("void later"));
	}

	/** Extracts one named method's balanced body instead of scanning unrelated source text. */
	private static String methodBody(String source, String method) {
		var match = Pattern.compile("\\b" + Pattern.quote(method)
			+ "\\s*\\([^)]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{").matcher(source);
		if (!match.find()) throw new AssertionError("missing target method " + method);
		int start = source.indexOf('{', match.start());
		int depth = 0;
		boolean string = false, character = false, escaped = false;
		boolean lineComment = false, blockComment = false;
		for (int index = start; index < source.length(); index++) {
			char value = source.charAt(index);
			char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
			if (lineComment) { if (value == '\n') lineComment = false; continue; }
			if (blockComment) {
				if (value == '*' && next == '/') { blockComment = false; index++; }
				continue;
			}
			if (!string && !character && value == '/' && (next == '/' || next == '*')) {
				lineComment = next == '/'; blockComment = next == '*'; index++; continue;
			}
			if (escaped) { escaped = false; continue; }
			if ((string || character) && value == '\\') { escaped = true; continue; }
			if (!character && value == '"') { string = !string; continue; }
			if (!string && value == '\'') { character = !character; continue; }
			if (string || character) continue;
			if (value == '{') depth++;
			else if (value == '}' && --depth == 0) return source.substring(start + 1, index);
		}
		throw new AssertionError("unbalanced target method " + method);
	}
}
