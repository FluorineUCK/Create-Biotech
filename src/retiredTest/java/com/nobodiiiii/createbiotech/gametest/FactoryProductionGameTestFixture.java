package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationGraph;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ComputationMachine;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataType;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.DataValue;
import com.nobodiiiii.createbiotech.content.biocomputing.computation.ProgramDefinition;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerRuntime;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ProgramArchive;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionProtocol;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.RemoteExpansionPublication;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionRemoteExpansion;


import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionRuntimeServices;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.expansion.ProductionLocalExpansion;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.diagnostics.*;
import com.nobodiiiii.createbiotech.content.factorycluster.tooltip.*;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingSelection;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCompletionMode;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointState;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputPhase;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentSignalSelector;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAliasMenu;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAliasRenamePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsToolData;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalSubmission;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.PolicySnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.CoordinationAssociation;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.AgentProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioLinkPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetRouteTrace;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioTopologyFrame;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteProcessLease;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlock;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelGoalCommandPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelGoalCommandResultPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandResultPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandResultPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelNewTaskResultPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelMenu;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorViewRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.ComputerMonitorResponsePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor.GoalTreeProjection;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkResponsePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.LogisticsNetworkResponsePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.NewTaskContextResponsePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListResponsePacket;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserFilter;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserDiagnostics;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserPage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserRequestPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserResponsePacket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.library.menu.LibraryCorePlacementMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.nobodiiiii.createbiotech.registry.CBMenuTypes;
import com.nobodiiiii.createbiotech.network.CBPackets;
import com.simibubi.create.Create;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.packager.PackagerBlock;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.createmod.catnip.net.base.ClientboundPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.MenuProvider;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Real Panel + Library + Computer + Create topology shared by all Task-12 acceptances. */
final class FactoryProductionGameTestFixture {
	enum PanelScenario {
		DIAGNOSTICS_NORMAL, DIAGNOSTICS_ADVANCED, DIAGNOSTICS_TOOLTIP,
		REQUESTS_ONE_VIEW,
		MONITOR_GOAL_TREE,
		AGENT_NEW_TASK_DEFAULTS,
		NETWORK_COLLAPSES_SUBNET,
		LOGISTICS_IS_READ_ONLY,
		REMOTE_FORCE_DENIED
	}
	enum LogisticsScenario {
		TOOL_BINDS_FORMED_CLUSTER,
		ALIAS_IS_GLOBAL,
		COMPONENT_OFFLINE_LAST_SEEN,
		BARE_ADDRESS_NO_REVERSE_PROTOCOL
	}
	enum PatternBrowserScenario {
		REQUIRES_ITEM_CONFIGURATION,
		ROUTING_IS_FROZEN,
		KEEPS_COMPLETE_GENERATION,
		PAGES_VALID_CACHE_ONLY,
		DOES_NOT_CONSUME_PROCESS
	}

	enum Scenario {
		LOCAL_STANDARD_AGENT, LOCAL_PURE_EXPANSION, BARE_STANDARD, RESTOCKER_BARE, EXACT_TWO,
		SEQUENCED_TWO_ADDRESS, KNOWN_OFFLINE_AGENT, CANCEL_DRAIN, DISPATCH_RELOAD,
		RADIO_ONE_HOP, RADIO_FREQUENCY_MISMATCH, TRADER_RELAY, LISTENER_RELAY, REMOTE_COMPUTE,
		LOST_ACK, RECOVERY_BACKPRESSURE, WORKER_FACTS, LOCAL_FORCE_RESET,
		SIGNAL_COMPLETION, PENDING_ADMISSION_RELOAD, COMPONENT_OUTPUT_RELOAD, RADIO_TOPOLOGY,
		EXTERNAL_LISTENER, ORDERLY_RADIO_RESTART
	}

	private FactoryProductionGameTestFixture() {}
	static void runScenario(GameTestHelper helper, Scenario scenario) {
		new Topology(helper, scenario).place();
	}

	static void runSettlementScenario(GameTestHelper helper) {
		Topology topology = new Topology(helper, Scenario.LOCAL_STANDARD_AGENT);
		topology.verifyGoalSettlement = true;
		topology.place();
	}

	static void runLogisticsScenario(GameTestHelper helper, LogisticsScenario scenario) {
		new LogisticsLifecycleTopology(helper, scenario).placeLogisticsTopology();
	}

	static void runPanelScenario(GameTestHelper helper, PanelScenario scenario) {
		Objects.requireNonNull(helper); Objects.requireNonNull(scenario);
		if (scenario == PanelScenario.REMOTE_FORCE_DENIED) {
			new Topology(helper, Scenario.LOCAL_FORCE_RESET).place();
			return;
		}
		new Topology(helper, scenario).place();
	}

	static void runPatternBrowserScenario(GameTestHelper helper, PatternBrowserScenario scenario) {
		new PatternBrowserVerticalTopology(helper, scenario).run();
	}

	static String productionIsolationCleanupFailure(@Nullable String originalFailure,
		String cleanupDiagnostics) {
		String cleanupFailure = "production isolation cleanup timed out: "
			+ Objects.requireNonNull(cleanupDiagnostics, "cleanupDiagnostics");
		return originalFailure == null ? cleanupFailure : originalFailure + " | " + cleanupFailure;
	}

	static AssertionError combinedProductionIsolationFailure(@Nullable Throwable originalFailure,
		String cleanupDiagnostics) {
		String evidence = originalFailure == null ? null : originalFailure.getMessage();
		if (evidence == null && originalFailure != null)
			evidence = originalFailure.toString();
		AssertionError combined = new AssertionError(productionIsolationCleanupFailure(evidence,
			cleanupDiagnostics));
		if (originalFailure != null)
			combined.initCause(originalFailure);
		return combined;
	}

	static void finishProductionIsolationFailure(Throwable failure) {
		FactoryProductionGameTestFixture.<RuntimeException>throwUnchecked(
			Objects.requireNonNull(failure, "failure"));
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T {
		throw (T) failure;
	}

	/** A physical configured Core, live shelf index and registered browser packet round trip. */
	private static final class PatternBrowserVerticalTopology {
		private static final int AWAIT = 520;
		private static final int EXPECTED_VALID_PAGES = 35;
		private static final String ITEM_FILTER = "minecraft:paper";
		private static final String TAG_FILTER = "minecraft:planks";
		private static final String ADDRESS_FILTER = "stage-route";
		private static final PatternKind KIND_FILTER = PatternKind.SEQUENCED;
		private static final StackKey GHOST_FILTER = new StackKey(new ItemStack(Items.DIAMOND));

		private final GameTestHelper helper;
		private final PatternBrowserScenario scenario;
		private final ServerLevel level;
		private final BlockPos corePos;
		private final BrowserMenuPlayer player;
		private final UUID libraryId;
		private UUID clusterId;
		private final UUID logisticsId;
		private final CommunicationFrequency frequency;
		private final int priority = 23;
		private LibraryBlockEntity core;
		private ChiseledBookShelfBlockEntity shelf;
		private FactoryPanelBlockEntity productionPanel;
		private ComputerBlockEntity productionComputer;
		private com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine
			.FactoryRuntimeService.ExternalListenerProcessReservation productionReservation;
		private UUID productionRequester;
		private final Set<UUID> cleanupConfirmationsAccepted = new LinkedHashSet<>();
		private final Map<UUID, List<Boolean>> cleanupPublishReturns = new LinkedHashMap<>();
		private final List<Boolean> cleanupReleaseReturns = new ArrayList<>();

		private PatternBrowserVerticalTopology(GameTestHelper helper, PatternBrowserScenario scenario) {
			this.helper = Objects.requireNonNull(helper);
			this.scenario = Objects.requireNonNull(scenario);
			level = helper.getLevel();
			corePos = helper.absolutePos(new BlockPos(3, 2, 3));
			libraryId = stable("library");
			clusterId = stable("cluster");
			logisticsId = stable("logistics");
			frequency = CommunicationFrequency.of("t5-browser-" + scenario.ordinal());
			player = new BrowserMenuPlayer(level, "task5-browser-" + scenario.ordinal());
			player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			player.moveTo(Vec3.atCenterOf(corePos.west()));
		}

		private void run() {
			helper.assertTrue(FactoryOrchestrationServer.running(level.getServer()),
				"Pattern browser acceptance requires the production server lifecycle");
			if (scenario == PatternBrowserScenario.REQUIRES_ITEM_CONFIGURATION) {
				requireItemConfiguration();
				return;
			}
			placeConfiguredCore();
			if (scenario == PatternBrowserScenario.ROUTING_IS_FROZEN) {
				helper.runAfterDelay(3, this::assertRoutingFrozen);
				return;
			}
			placeShelf(scenario == PatternBrowserScenario.PAGES_VALID_CACHE_ONLY
				? pageSet() : scenario == PatternBrowserScenario.KEEPS_COMPLETE_GENERATION
					? generationPatterns(Items.DIAMOND, Items.GOLD_INGOT, "old")
					: List.of(scenario == PatternBrowserScenario.DOES_NOT_CONSUME_PROCESS
						? priorityPattern(Items.DIAMOND) : firstPattern(Items.DIAMOND)));
			if (scenario == PatternBrowserScenario.DOES_NOT_CONSUME_PROCESS) {
				placeProductionComputer();
				await(AWAIT, this::productionComputerFormed, this::bindProductionTopology,
					"real Computer did not form the production Cluster used by browser isolation");
				return;
			}
			await(AWAIT, this::coreCanAcceptBinding, this::bindAndAwaitIndex,
				"configured lower Core never registered its item-owned Library identity");
		}

		private void requireItemConfiguration() {
			level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			ItemStack fresh = new ItemStack(CBItems.PATTERN_STORAGE_CORE.get());
			player.setItemInHand(InteractionHand.MAIN_HAND, fresh);
			InteractionResult firstUse = player.gameMode.useItemOn(player, level, fresh,
				InteractionHand.MAIN_HAND, placementHit());
			helper.assertTrue(firstUse.consumesAction(),
				"fresh Core item must enter its registered configuration path");
			helper.assertTrue(level.getBlockState(corePos).isAir(),
				"an unconfigured Core item must not create a placed Library");
			helper.assertTrue(player.clientMenu instanceof LibraryCorePlacementMenu,
				"fresh Core item must open the registered placement menu");
			player.closeContainer();

			placeConfiguredCore();
			helper.runAfterDelay(3, () -> {
				helper.assertValueEqual(core.memberId(), libraryId,
					"configured item identity must reach the lower Core before Library registration");
				helper.assertValueEqual(core.routingConfiguration().frequency(), frequency,
					"configured item frequency must reach the lower Core before Library registration");
				openBrowser();
				PatternBrowserResponsePacket response = request(PatternBrowserFilter.EMPTY, 0);
				helper.assertValueEqual(response.page().status(), PatternBrowserPage.Status.OK,
					"the configured physical Core must expose its registered browser entry");
				helper.succeed();
			});
		}

		private void assertRoutingFrozen() {
			var before = core.routingConfiguration();
			boolean changed = core.applyPlacementData(LibraryCoreItemData.configured(
				CommunicationFrequency.of("forbidden-placed-retune"), -91, UUID.randomUUID(), before.revision() + 1));
			helper.assertFalse(changed, "a placed Core must reject a second routing claim");
			helper.assertValueEqual(core.routingConfiguration(), before,
				"rejected placed routing must preserve frequency, priority and revision");
			openBrowser();
			helper.assertValueEqual(player.serverBrowser.frequency(), frequency,
				"browser opening must expose the frozen read-only frequency");
			helper.assertValueEqual(player.serverBrowser.priority(), priority,
				"browser opening must expose the frozen read-only priority");
			PatternBrowserResponsePacket response = request(PatternBrowserFilter.EMPTY, 0);
			helper.assertValueEqual(response.page().status(), PatternBrowserPage.Status.OK,
				"routing rejection must leave the browser operational");
			helper.assertValueEqual(core.routingConfiguration(), before,
				"browser packet entry must not mutate placed routing");
			helper.succeed();
		}

		private void bindAndAwaitIndex() {
			ClusterBinding binding = new ClusterBinding(clusterId, 0,
				new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()), List.of());
			core.commitClusterBinding(binding);
			helper.assertValueEqual(ClusterBindingService.bindingAccess(level.getServer(), core),
				ClusterBindingService.BindingAccess.READY,
				"self-authoritative configured Library must enter READY access");
			awaitReadyIndex();
		}

		private void bindProductionTopology() {
			clusterId = Objects.requireNonNull(productionPanel.clusterId(), "formed production Cluster");
			player.moveTo(Vec3.atCenterOf(productionPanel.getBlockPos()));
			helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, productionPanel,
				List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
				"formed production Cluster must authorize the real Library logistics binding");
			helper.assertValueEqual(ClusterBindingService.bind(player, productionPanel, core),
				ClusterBindingService.BindResult.OK,
				"real configured Library must bind through the formed production authority");
			helper.assertValueEqual(ClusterBindingService.bindingAccess(level.getServer(), core),
				ClusterBindingService.BindingAccess.READY,
				"formed Computer authority must give the configured Library READY access");
			player.moveTo(Vec3.atCenterOf(corePos.west()));
			awaitReadyIndex();
		}

		private void awaitReadyIndex() {
			int expected = scenario == PatternBrowserScenario.PAGES_VALID_CACHE_ONLY
				? EXPECTED_VALID_PAGES : scenario == PatternBrowserScenario.KEEPS_COMPLETE_GENERATION ? 2 : 1;
			await(AWAIT, () -> core.recipeSnapshot().generation() > 0
				&& !core.recipeSnapshot().reindexing()
				&& core.recipeSnapshot().records().size() == expected,
				this::runReadyScenario,
				() -> "live Pattern index did not publish its complete generation: "
					+ core.recipeSnapshot() + ", summary=" + core.summary());
		}

		private void runReadyScenario() {
			switch (scenario) {
				case KEEPS_COMPLETE_GENERATION -> proveCompleteGenerationRetention();
				case PAGES_VALID_CACHE_ONLY -> proveValidOnlyPagesAndFilters();
				case DOES_NOT_CONSUME_PROCESS -> proveNoProductionConsumption();
				default -> throw new IllegalStateException("unexpected browser lifecycle scenario " + scenario);
			}
		}

		private void proveCompleteGenerationRetention() {
			openBrowser();
			long completeGeneration = core.recipeSnapshot().generation();
			PatternBrowserResponsePacket before = request(PatternBrowserFilter.EMPTY, 0);
			Set<UUID> oldPatternIds = patternIds(before);
			Set<net.minecraft.resources.ResourceLocation> oldOutputs = outputs(before);
			Set<net.minecraft.resources.ResourceLocation> expectedOldOutputs = Set.of(
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.DIAMOND),
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.GOLD_INGOT));
			Set<net.minecraft.resources.ResourceLocation> expectedNewOutputs = Set.of(
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.EMERALD),
				net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.COPPER_INGOT));
			helper.assertValueEqual(oldPatternIds.size(), 2,
				"first completed generation must expose two independently identifiable records");
			helper.assertValueEqual(oldOutputs, expectedOldOutputs,
				"first completed generation must expose the complete old record set");
			shelf.setItem(0, writableBook(generationPatterns(Items.EMERALD, Items.COPPER_INGOT, "new")));
			shelf.setChanged();
			await(AWAIT, () -> core.recipeSnapshot().reindexing(), () -> {
				helper.assertValueEqual(core.recipeSnapshot().generation(), completeGeneration,
					"reindexing must retain the last complete browser generation");
				PatternBrowserResponsePacket retained = request(PatternBrowserFilter.EMPTY, 0);
				helper.assertValueEqual(retained.page().status(), PatternBrowserPage.Status.OK,
					"the old opening fence must remain usable during reindexing");
				helper.assertTrue(retained.page().reindexing(),
					"the retained browser response must report the in-progress replacement");
				helper.assertValueEqual(patternIds(retained), oldPatternIds,
					"reindexing must retain the complete old Pattern identity set");
				helper.assertValueEqual(outputs(retained), expectedOldOutputs,
					"reindexing must expose every old record and no new record");
				helper.assertTrue(java.util.Collections.disjoint(outputs(retained), expectedNewOutputs),
					"reindexing must never publish an old/new mixed generation");
				await(AWAIT, () -> !core.recipeSnapshot().reindexing()
					&& core.recipeSnapshot().generation() > completeGeneration,
					() -> helper.runAfterDelay(1, () -> {
						PatternBrowserResponsePacket stale = request(PatternBrowserFilter.EMPTY, 0);
						helper.assertValueEqual(stale.page().status(), PatternBrowserPage.Status.STALE,
							"old completed fence must become explicitly stale after replacement completes");
						helper.runAfterDelay(1, () -> {
							PatternBrowserResponsePacket replacement = request(PatternBrowserFilter.EMPTY, 0);
							Set<UUID> newPatternIds = patternIds(replacement);
							helper.assertValueEqual(newPatternIds.size(), 2,
								"replacement generation must expose two independently identifiable records");
							helper.assertTrue(java.util.Collections.disjoint(oldPatternIds, newPatternIds),
								"old and replacement generations must have disjoint Pattern identities");
							helper.assertValueEqual(outputs(replacement), expectedNewOutputs,
								"only the complete replacement record set may reach the browser");
							helper.assertTrue(java.util.Collections.disjoint(outputs(replacement), oldOutputs),
								"completed replacement must contain no old/new mixed generation");
							helper.succeed();
						});
					}), "scanner did not publish the replacement complete generation");
			}, "scanner never exposed a retained complete generation while rebuilding");
		}

		private void proveValidOnlyPagesAndFilters() {
			openBrowser();
			PatternBrowserResponsePacket first = request(PatternBrowserFilter.EMPTY, 0);
			helper.assertValueEqual(first.page().entries().size(), PatternBrowserPage.PAGE_SIZE,
				"first browser page must contain the bounded valid-cache page size");
			helper.assertValueEqual(first.page().totalPatterns(), EXPECTED_VALID_PAGES,
				"blank, invalid and unsupported pages must not become browser entries");
			helper.assertValueEqual(first.page().invalidPages(), 1,
				"invalid live page must remain diagnostic-only");
			helper.assertValueEqual(first.page().unsupportedPages(), 1,
				"unsupported live page must remain diagnostic-only");
			helper.runAfterDelay(1, () -> {
				PatternBrowserResponsePacket second = request(PatternBrowserFilter.EMPTY, 1);
				helper.assertValueEqual(second.page().entries().size(), 3,
					"second browser page must contain only the remaining valid records");
				helper.runAfterDelay(1, () -> runFilterProbe(0));
			});
		}

		private void runFilterProbe(int probe) {
			List<PatternBrowserFilter> filters = List.of(
				new PatternBrowserFilter(ITEM_FILTER, Optional.empty(), Optional.empty()),
				new PatternBrowserFilter(TAG_FILTER, Optional.empty(), Optional.empty()),
				new PatternBrowserFilter(ADDRESS_FILTER, Optional.empty(), Optional.empty()),
				new PatternBrowserFilter("", Optional.empty(), Optional.of(KIND_FILTER)),
				new PatternBrowserFilter("", Optional.of(GHOST_FILTER), Optional.empty()));
			if (probe == filters.size()) {
				ItemStack mismatched = new ItemStack(Items.DIAMOND);
				mismatched.set(DataComponents.CUSTOM_NAME, Component.literal("component-mismatch"));
				PatternBrowserResponsePacket exact = request(new PatternBrowserFilter("",
					Optional.of(new StackKey(mismatched)), Optional.empty()), 0);
				helper.assertValueEqual(exact.page().totalMatches(), 0,
					"ghost filtering must remain exact and component-sensitive");
				helper.succeed();
				return;
			}
			PatternBrowserResponsePacket response = request(filters.get(probe), 0);
			helper.assertValueEqual(response.page().totalMatches(), 1,
				"live browser filter category " + probe + " must select exactly one valid cached record");
			helper.runAfterDelay(1, () -> runFilterProbe(probe + 1));
		}

		private void proveNoProductionConsumption() {
			openBrowser();
			var runtime = ProductionRuntimeServices.get(level.getServer());
			requireRuntimeState(runtime, "query baseline");
			UUID requester = productionComputer.computerId().orElse(null);
			helper.assertTrue(requester != null,
				"formed production Computer must retain its identity at the query baseline");
			productionRequester = requester;
			await(AWAIT, () -> !runtime.workerInFlight(clusterId),
				() -> proveStalePaidOverlayGate(runtime,
					requireRuntimeState(runtime, "settled paid-overlay baseline"), requester),
				"production runtime never settled before the paid-overlay authority proof");
		}

		private void proveStalePaidOverlayGate(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			ClusterEngineState baseline, UUID requester) {
			helper.assertTrue(baseline.processes().isEmpty() && baseline.pendingIntents().isEmpty(),
				"paid-overlay authority proof must begin before production work is submitted");
			var entry = core.recipeSnapshot().records().getFirst();
			helper.assertTrue(entry.priorityOverride().isPresent(),
				"paid-overlay authority proof requires one configured fine priority");
			var member = Objects.requireNonNull(baseline.member(requester),
				"formed Alchemist member snapshot");
			UUID goalId = stable("browser-paid-goal");
			UUID processId = stable("browser-paid-process");
			GoalSubmission submission = new GoalSubmission(goalId, entry.patternId(), 1,
				InputMode.BULK);
			GoalRecord goal = new GoalRecord(goalId, clusterId, submission,
				new CreateRouteKey(logisticsId, "browser-paid-proof"),
				new PolicySnapshot(0, 0, false), GoalState.RUNNABLE, null);
			ProcessRecord process = new ProcessRecord(processId, goalId, requester, 0,
				member.profile(), ProcessKind.PATTERN_PRIORITY_LEASE, ProcessState.RUNNABLE,
				null, 0, Map.of());
			ClusterEngineState coordinated = new ClusterEngineState(baseline.clusterId(),
				baseline.epochId(), baseline.generation(), baseline.nextSubmissionSequence() + 1,
				baseline.nextRouteSelectionSequence(), baseline.memberCursor(), 0,
				Map.of(goalId, goal), Map.of(goalId, baseline.nextSubmissionSequence()),
				Map.of(goalId, ProcessKind.PATTERN_PRIORITY_LEASE), Map.of(processId, process),
				baseline.members(), baseline.pendingIntents(), baseline.uncertainIntentIds(),
				baseline.goalScanOffset(), null, baseline.routeCursor(),
				Map.of(processId, new CoordinationAssociation(processId, libraryId,
					entry.patternId(), core.recipeSnapshot().generation())),
				baseline.priorityLeaseAssociations());
			runtime.restoreSnapshot(coordinated);
			PatternBrowserResponsePacket current = request(PatternBrowserFilter.EMPTY, 0);
			helper.assertTrue(current.page().entries().getFirst().overrideActive(),
				"current authority must expose the exact paid overlay before the stale proof");
			helper.runAfterDelay(1, () -> proveStalePaidOverlayRead(runtime, baseline,
				requester));
		}

		private void proveStalePaidOverlayRead(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			ClusterEngineState baseline, UUID requester) {
			ClusterBinding authoritative = Objects.requireNonNull(productionComputer.production().coordinatorMember
				.bindingState(), "formed Computer authority binding");
			helper.assertTrue(authoritative.revision() > 0,
				"stale paid-overlay proof requires a prior authoritative revision");
			ClusterBinding stale = new ClusterBinding(authoritative.clusterId(),
				authoritative.revision() - 1, authoritative.authority(),
				authoritative.logisticsBindings());
			core.commitClusterBinding(stale);

			CompoundTag coreBefore = core.saveWithoutMetadata(level.registryAccess());
			CompoundTag panelBefore = productionPanel.saveWithoutMetadata(level.registryAccess());
			CompoundTag computerBefore = productionComputer.saveWithoutMetadata(level.registryAccess());
			ClusterEngineState runtimeBefore = runtime.peekSnapshotForPanelView(clusterId).orElseThrow();
			var publicationsBefore = runtime.panelPublicationCountsForTest();
			PatternBrowserResponsePacket staleRead = request(PatternBrowserFilter.EMPTY, 0);

			helper.assertFalse(staleRead.page().entries().getFirst().overrideActive(),
				"stale paid-overlay browser read must fail closed without coordination authority");
			helper.assertValueEqual(core.bindingState(), stale,
				"stale paid-overlay browser read must not reconcile the Core binding");
			helper.assertValueEqual(productionPanel.bindingState(), authoritative,
				"stale paid-overlay browser read must not revise the Panel binding");
			helper.assertValueEqual(productionComputer.production().coordinatorMember.bindingState(), authoritative,
				"stale paid-overlay browser read must not revise the Computer binding");
			helper.assertValueEqual(core.saveWithoutMetadata(level.registryAccess()), coreBefore,
				"stale paid-overlay browser read must not notify or rewrite Core NBT");
			helper.assertValueEqual(productionPanel.saveWithoutMetadata(level.registryAccess()), panelBefore,
				"stale paid-overlay browser read must preserve complete Panel NBT");
			helper.assertValueEqual(productionComputer.saveWithoutMetadata(level.registryAccess()), computerBefore,
				"stale paid-overlay browser read must preserve complete Computer NBT");
			helper.assertValueEqual(runtime.peekSnapshotForPanelView(clusterId).orElseThrow(), runtimeBefore,
				"stale paid-overlay browser read must preserve the complete runtime snapshot");
			helper.assertValueEqual(runtime.panelPublicationCountsForTest(), publicationsBefore,
				"stale paid-overlay browser read must preserve publication notification counts");
			helper.assertValueEqual(ClusterBindingService.snapshotBindingAccess(level.getServer(), core),
				ClusterBindingService.BindingAccess.CONFLICT,
				"pure browser proof must expose stale loaded replicas as inactive");

			helper.assertValueEqual(ClusterBindingService.reconcileLoaded(level.getServer(), core),
				ClusterBindingService.ReconcileResult.UPDATED,
				"explicit normal lifecycle reconciliation must repair the stale replica");
			helper.runAfterDelay(1, () -> {
				PatternBrowserResponsePacket repaired = request(PatternBrowserFilter.EMPTY, 0);
				helper.assertTrue(repaired.page().entries().getFirst().overrideActive(),
					"paid overlay must reactivate only after normal lifecycle repair");
				await(AWAIT, () -> !runtime.workerInFlight(clusterId), () -> {
					runtime.restoreSnapshot(baseline);
					helper.runAfterDelay(1, () -> submitProductionQuery(runtime, requester));
				}, "production runtime never settled after normal binding lifecycle repair");
			});
		}

		private void submitProductionQuery(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			UUID requester) {
			ClusterEngineState queryState = requireRuntimeState(runtime, "query submission");
			UUID queryId = stable("browser-isolation-query");
			var query = new com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.PatternQuery(
				queryId, requester, logisticsId, libraryId, new StackKey(new ItemStack(Items.DIRT)),
				core.recipeSnapshot().generation(), 0, 1,
				Objects.requireNonNull(core.bindingState()).revision(), 0, stable("browser-isolation-attempt"));
			boolean submitted = core.enqueueQuery(query);
			runProductionIsolationPhase(runtime, () -> {
				helper.assertTrue(submitted,
					"real READY Library must enqueue one production query through the runtime API");
				var activeBaseline = core.browserDiagnostics();
				helper.assertTrue(activeBaseline.activeQueries() > 0,
					"browser isolation must first establish a non-empty production query baseline");
				var activeBrowserBaseline = core.recipeSnapshot();
				PatternBrowserResponsePacket activeRead = request(PatternBrowserFilter.EMPTY, 0);
				helper.assertValueEqual(activeRead.diagnostics(), activeBaseline,
					"registered browser response must observe the exact active-query baseline");
				helper.assertValueEqual(core.browserDiagnostics(), activeBaseline,
					"one registered browser read must not consume the active production query");
				helper.assertValueEqual(core.recipeSnapshot(), activeBrowserBaseline,
					"active-query browser read must leave the complete cache snapshot unchanged");

				awaitProductionIsolation(runtime, AWAIT, () -> {
					var diagnostics = core.browserDiagnostics();
					return diagnostics.activeQueries() == 0 && diagnostics.readyReplies() > 0
						&& !runtime.workerInFlight(clusterId);
				}, () -> proveReadyReplyWithoutConsumption(runtime, queryState.epochId(), requester),
					() -> "real production query never completed into a non-empty ready reply baseline: "
						+ core.browserDiagnostics());
			});
		}

		private void proveReadyReplyWithoutConsumption(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			UUID epochId, UUID requester) {
			runProductionIsolationPhase(runtime, () -> {
				var readyBaseline = core.browserDiagnostics();
				helper.assertTrue(readyBaseline.readyReplies() > 0,
					"browser isolation must first establish a non-empty ready-reply baseline");
				var readyBrowserBaseline = core.recipeSnapshot();
				PatternBrowserResponsePacket readyRead = request(PatternBrowserFilter.EMPTY, 0);
				helper.assertValueEqual(readyRead.diagnostics(), readyBaseline,
					"registered browser response must observe the exact ready-reply baseline");
				helper.assertValueEqual(core.browserDiagnostics(), readyBaseline,
					"one registered browser read must not consume a ready production reply");
				helper.assertValueEqual(core.recipeSnapshot(), readyBrowserBaseline,
					"ready-reply browser read must leave the complete cache snapshot unchanged");
				scheduleProductionIsolationPhase(1, runtime,
					() -> establishProcessWithoutConsumption(runtime, epochId, requester));
			});
		}

		private void establishProcessWithoutConsumption(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			UUID epochId, UUID requester) {
			runProductionIsolationPhase(runtime, () -> {
				var reservation = runtime.reserveExternalListenerProcess(clusterId, epochId, requester,
					stable("browser-isolation-process")).orElseThrow(() ->
						new IllegalStateException("real Computer did not expose one production process slot"));
				productionReservation = reservation;
				ClusterEngineState processBaseline = requireRuntimeState(runtime, "process baseline");
				helper.assertTrue(!processBaseline.processes().isEmpty(),
					"browser isolation must first establish a non-empty production process baseline");
				var processDiagnostics = core.browserDiagnostics();
				var processBrowser = core.recipeSnapshot();
				request(PatternBrowserFilter.EMPTY, 0);
				scheduleProductionIsolationPhase(2, runtime, () -> {
					request(new PatternBrowserFilter("minecraft:diamond", Optional.empty(), Optional.empty()), 0);
					scheduleProductionIsolationPhase(2, runtime,
						() -> finishProcessPhaseAndPublishIntent(runtime, epochId,
							reservation, processBaseline, processDiagnostics, processBrowser));
				});
			});
		}

		private void finishProcessPhaseAndPublishIntent(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			UUID epochId,
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService
				.ExternalListenerProcessReservation reservation,
			ClusterEngineState processBaseline, PatternBrowserDiagnostics processDiagnostics,
			RecipeIndexSnapshot processBrowser) {
			runProductionIsolationPhase(runtime, () -> {
				request(new PatternBrowserFilter("", Optional.of(GHOST_FILTER), Optional.empty()), 0);
				ClusterEngineState processAfterRead = requirePeekedRuntimeState(runtime,
					"process browser comparison");
				helper.assertValueEqual(processAfterRead, processBaseline,
					"cross-tick registered browser reads must preserve the complete non-empty process state");
				helper.assertValueEqual(core.browserDiagnostics(), processDiagnostics,
					"cross-tick process-phase browser reads must preserve ready-query diagnostics");
				helper.assertValueEqual(core.recipeSnapshot(), processBrowser,
					"cross-tick process-phase browser reads must preserve the complete cache snapshot");
				scheduleProductionIsolationPhase(4, runtime,
					() -> publishAndObservePendingIntent(runtime, epochId, reservation, processBaseline));
			});
		}

		private void publishAndObservePendingIntent(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			UUID epochId,
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService
				.ExternalListenerProcessReservation reservation,
			ClusterEngineState processBaseline) {
			runProductionIsolationPhase(runtime, () -> {
				helper.assertTrue(runtime.drainIntents(clusterId, level.getGameTime()).isEmpty(),
					"the pre-dispatch production drain must not invent an intent");
				runtime.releasePanelMutationReservation(clusterId);
				helper.assertTrue(runtime.publishFact(new com.nobodiiiii.createbiotech.content.factorycluster
					.orchestration.engine.WorldFact.PlayerCommand(clusterId, epochId, processBaseline.generation(),
					new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact.Command
						.DispatchCreate(reservation.processId(), stable("browser-isolation-run"),
							new CreateRouteKey(logisticsId, "browser-isolation-hold"), List.of(
								new com.nobodiiiii.createbiotech.content.biocomputing.application.production.material
									.OrderedIngredient(new StackKey(new ItemStack(Items.COBBLESTONE)), 1))))),
					"real production command API must accept one dispatch for the durable process");
				long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
				ClusterEngineState pending = runtime.snapshot(clusterId).orElse(null);
				while ((pending == null || pending.pendingIntents().isEmpty())
					&& System.nanoTime() < deadline) {
					Thread.yield();
					pending = runtime.snapshot(clusterId).orElse(null);
				}
				if (pending == null || pending.pendingIntents().isEmpty()) {
					throw new AssertionError("real dispatch did not expose a same-tick pending-intent baseline: generation="
						+ (pending == null ? "missing" : pending.generation()) + ", processes="
						+ (pending == null ? "missing" : pending.processes().keySet()) + ", diagnostics="
						+ runtime.drainDiagnostics());
				}
				compareStablePendingIntentAcrossBrowserRead(runtime);
			});
		}

		private void compareStablePendingIntentAcrossBrowserRead(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime) {
			runProductionIsolationPhase(runtime, () -> {
				ClusterEngineState beforeState = requireRuntimeState(runtime, "pending-intent baseline");
				var beforeDiagnostics = core.browserDiagnostics();
				var beforeBrowser = core.recipeSnapshot();
				helper.assertTrue(beforeDiagnostics.readyReplies() > 0,
					"browser isolation must first establish a non-empty ready reply baseline");
				helper.assertTrue(!beforeState.processes().isEmpty(),
					"browser isolation process baseline must remain non-empty before round trips");
				helper.assertTrue(!beforeState.pendingIntents().isEmpty(),
					"browser isolation must first establish a non-empty pending-intent baseline");
				request(PatternBrowserFilter.EMPTY, 0);
				var afterDiagnostics = core.browserDiagnostics();
				ClusterEngineState afterState = requirePeekedRuntimeState(runtime,
					"pending-intent browser comparison");
				helper.assertValueEqual(afterDiagnostics, beforeDiagnostics,
					"same-tick browser read must preserve the complete query/reply diagnostics");
				helper.assertValueEqual(afterState.processes(), beforeState.processes(),
					"same-tick browser read must preserve every production process record");
				helper.assertValueEqual(afterState.pendingIntents(), beforeState.pendingIntents(),
					"same-tick browser read must preserve every pending production intent");
				helper.assertValueEqual(afterState, beforeState,
					"same-tick browser read must preserve the complete runtime snapshot");
				helper.assertValueEqual(core.recipeSnapshot(), beforeBrowser,
					"same-tick browser read must preserve the complete browser snapshot");
				injectProductionIsolationFailure(runtime);
			});
		}

		private void failProductionIsolation(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			Throwable failure) {
			cleanupProductionIsolation(runtime, Objects.requireNonNull(failure), 60,
				FactoryProductionGameTestFixture::finishProductionIsolationFailure, helper::succeed);
		}

		private void runProductionIsolationPhase(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			Runnable phase) {
			try {
				Objects.requireNonNull(phase, "phase").run();
			} catch (Throwable failure) {
				failProductionIsolation(runtime, failure);
			}
		}

		private void scheduleProductionIsolationPhase(int delay,
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			Runnable phase) {
			helper.runAfterDelay(delay, () -> runProductionIsolationPhase(runtime, phase));
		}

		private void awaitProductionIsolation(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			int remaining, BooleanSupplier ready, Runnable success, Supplier<String> failure) {
			runProductionIsolationPhase(runtime, () -> {
				if (ready.getAsBoolean()) {
					success.run();
					return;
				}
				if (remaining <= 0)
					throw new AssertionError(failure.get());
				scheduleProductionIsolationPhase(1, runtime, () -> awaitProductionIsolation(runtime,
					remaining - 1, ready, success, failure));
			});
		}

		private void injectProductionIsolationFailure(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime) {
			Throwable injected;
			try {
				helper.assertTrue(false, "INJECTED_BROWSER_ISOLATION_FAILURE");
				throw new AssertionError("GameTest assertion did not fail");
			} catch (Throwable failure) {
				injected = failure;
			}
			Throwable expected = injected;
			cleanupProductionIsolation(runtime, expected, AWAIT, observed -> {
				helper.assertTrue(observed == expected,
					"cleanup must re-report the exact original GameTest assertion");
				helper.assertTrue(String.valueOf(observed.getMessage())
					.contains("INJECTED_BROWSER_ISOLATION_FAILURE"),
					"cleanup must preserve the original assertion evidence");
				assertProductionIsolationFullyClean(runtime);
				helper.succeed();
			}, () -> helper.fail("fault-injection cleanup returned success instead of the original failure"));
		}

		private void assertProductionIsolationFullyClean(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime) {
			var workload = core.browserDiagnostics();
			ClusterEngineState state = runtime.snapshot(clusterId).orElse(null);
			helper.assertValueEqual(workload.activeQueries(), 0,
				"fault-injection cleanup must leave zero active queries");
			helper.assertValueEqual(workload.readyReplies(), 0,
				"fault-injection cleanup must leave zero ready replies");
			helper.assertTrue(state == null || state.processes().isEmpty(),
				"fault-injection cleanup must leave zero processes");
			helper.assertTrue(state == null || state.pendingIntents().isEmpty(),
				"fault-injection cleanup must leave zero pending intents");
		}

		private void cleanupProductionIsolation(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			@Nullable Throwable originalFailure, int remaining, Consumer<Throwable> failureSink,
			Runnable success) {
			ClusterEngineState state = runtime.snapshot(clusterId).orElse(null);
			if (state != null && !state.pendingIntents().isEmpty()) {
				state.pendingIntents().values().stream()
					.filter(intent -> !cleanupConfirmationsAccepted.contains(intent.intentId()))
					.forEach(intent -> {
						boolean accepted = runtime.publishFact(
							new com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact.IntentResult(
								clusterId, state.epochId(), Math.max(state.generation(), intent.generation()),
								intent.intentId(), true, "browser isolation cleanup"));
						cleanupPublishReturns.computeIfAbsent(intent.intentId(), ignored -> new ArrayList<>())
							.add(accepted);
						if (accepted) cleanupConfirmationsAccepted.add(intent.intentId());
					});
			}
			var reservation = productionReservation;
			if (reservation != null && state != null
				&& state.processes().containsKey(reservation.processId()))
				cleanupReleaseReturns.add(runtime.releaseExternalListenerProcess(reservation));
			if (productionRequester != null) core.drainReplies(productionRequester, 256);
			var workload = core.browserDiagnostics();
			ClusterEngineState cleaned = runtime.snapshot(clusterId).orElse(null);
			boolean processGone = reservation == null || cleaned == null
				|| !cleaned.processes().containsKey(reservation.processId());
			if (workload.activeQueries() == 0 && workload.readyReplies() == 0
				&& (cleaned == null || cleaned.pendingIntents().isEmpty()) && processGone) {
				if (originalFailure == null) success.run();
				else failureSink.accept(originalFailure);
				return;
			}
			if (remaining <= 0) {
				String cleanupDiagnostics = "active=" + workload.activeQueries()
					+ ", ready=" + workload.readyReplies()
					+ ", process=" + (cleaned == null ? "missing" : cleaned.processes())
					+ ", pending=" + (cleaned == null ? "missing" : cleaned.pendingIntents())
					+ ", reservation=" + reservation
					+ ", publishReturns=" + cleanupPublishReturns
					+ ", releaseReturns=" + cleanupReleaseReturns
					+ ", runtimeDiagnostics=" + runtime.drainDiagnostics();
				failureSink.accept(combinedProductionIsolationFailure(originalFailure,
					cleanupDiagnostics));
				return;
			}
			helper.runAfterDelay(1,
				() -> cleanupProductionIsolation(runtime, originalFailure, remaining - 1,
					failureSink, success));
		}

		private void placeConfiguredCore() {
			level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			ItemStack configured = new ItemStack(CBItems.PATTERN_STORAGE_CORE.get());
			LibraryCoreItemData.write(configured, LibraryCoreItemData.configured(
				frequency, priority, libraryId, 0));
			player.setItemInHand(InteractionHand.MAIN_HAND, configured);
			helper.assertTrue(player.gameMode.useItemOn(player, level, configured,
				InteractionHand.MAIN_HAND, placementHit()).consumesAction(),
				"configured registered Core item must use its physical placement transaction");
			helper.assertTrue(level.getBlockEntity(corePos) instanceof LibraryBlockEntity,
				"configured placement must create the lower Core block entity");
			core = (LibraryBlockEntity) level.getBlockEntity(corePos);
			helper.assertValueEqual(core.memberId(), libraryId,
				"configured identity must be installed in the lower BE before its first server tick");
			helper.assertValueEqual(core.routingConfiguration().frequency(), frequency,
				"configured routing must be installed in the lower BE before its first server tick");
		}

		private void placeProductionComputer() {
			BlockPos min = helper.absolutePos(new BlockPos(8, 1, 2));
			for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) {
				boolean shell = x == 0 || x == 2 || y == 0 || y == 3 || z == 0 || z == 2;
				level.setBlock(min.offset(x, y, z), shell
					? CBBlocks.COMPUTER_CASING.get().defaultBlockState()
					: Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
			BlockPos panelPos = min.offset(0, 1, 0);
			level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
			productionPanel = (FactoryPanelBlockEntity) level.getBlockEntity(panelPos);
			BlockPos computerPos = min.offset(1, 1, 1);
			BlockState lower = CBBlocks.COMPUTER.get().defaultBlockState()
				.setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
			level.setBlock(computerPos, lower, Block.UPDATE_ALL);
			level.setBlock(computerPos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER),
				Block.UPDATE_ALL);
			productionComputer = (ComputerBlockEntity) level.getBlockEntity(computerPos);
			CompoundTag tag = productionComputer.saveWithoutMetadata(level.registryAccess());
			tag.getCompound("ComputerData").putUUID("ComputerId", stable("production-computer"));
			productionComputer.loadWithComponents(tag, level.registryAccess());

			Villager resident = Objects.requireNonNull(EntityType.VILLAGER.create(level));
			resident.setVillagerData(resident.getVillagerData().setType(VillagerType.PLAINS)
				.setProfession(VillagerProfession.CLERIC).setLevel(5));
			ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
			if (!CapturedEntityBoxHelper.captureEntity(box, resident))
				throw new IllegalStateException("could not capture browser-isolation Computer resident");
			player.moveTo(Vec3.atCenterOf(computerPos));
			player.setItemInHand(InteractionHand.MAIN_HAND, box);
			helper.assertTrue(player.gameMode.useItemOn(player, level, box, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(computerPos), Direction.UP, computerPos, false))
				.consumesAction(), "real browser-isolation Computer must accept its resident");
			level.getChunkAt(computerPos);
		}

		private boolean productionComputerFormed() {
			if (productionPanel == null || productionPanel.clusterId() == null
				|| productionComputer == null) return false;
			var epoch = productionComputer.production().epoch().orElse(null);
			if (epoch == null || !epoch.groupId().equals(productionPanel.clusterId())) return false;
			UUID computerId = productionComputer.computerId().orElse(null);
			if (computerId == null) return false;
			return ProductionRuntimeServices.get(level.getServer()).snapshot(epoch.groupId())
				.filter(state -> state.epochId().equals(epoch.epochId()))
				.filter(state -> state.member(computerId) != null)
				.isPresent();
		}

		private ClusterEngineState requireRuntimeState(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			String phase) {
			ClusterEngineState state = runtime.snapshot(clusterId).orElse(null);
			helper.assertTrue(state != null,
				"production runtime state must exist for browser isolation phase: " + phase
					+ ", cluster=" + clusterId + ", panel=" + productionPanel.clusterId()
					+ ", epoch=" + productionComputer.production().epoch());
			return state;
		}

		private ClusterEngineState requirePeekedRuntimeState(
			com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService runtime,
			String phase) {
			ClusterEngineState state = runtime.peekSnapshotForPanelView(clusterId).orElse(null);
			helper.assertTrue(state != null,
				"non-pumping production runtime state must exist for browser isolation phase: " + phase
					+ ", cluster=" + clusterId + ", panel=" + productionPanel.clusterId());
			return state;
		}

		private void placeShelf(List<String> pages) {
			BlockPos shelfPos = corePos.east();
			level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
			shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(shelfPos);
			shelf.setItem(0, writableBook(pages));
			shelf.setChanged();
		}

		private boolean coreCanAcceptBinding() {
			ClusterBinding binding = new ClusterBinding(clusterId, 0,
				new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()), List.of());
			return core.prepareClusterBinding(binding) ==
				com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation.READY;
		}

		private void openBrowser() {
			player.containerMenu = player.inventoryMenu;
			player.clientMenu = null;
			InteractionResult opened = player.gameMode.useItemOn(player, level, ItemStack.EMPTY,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(corePos.above()),
					Direction.WEST, corePos.above(), false));
			helper.assertTrue(opened.consumesAction(),
				"upper physical Core entry must open the read-only browser");
			helper.assertTrue(player.serverBrowser != null && player.clientMenu instanceof PatternStorageCoreMenu,
				"browser entry must construct both registered server and client menus");
		}

		private PatternBrowserResponsePacket request(PatternBrowserFilter filter, int page) {
			PatternStorageCoreMenu client = (PatternStorageCoreMenu) player.clientMenu;
			PatternBrowserRequestPacket request = client.browser().request(filter, page, Optional.empty());
			RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess(),
				net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
			try {
				CBPackets.beginPanelDeliveryObservationForTest(player);
				CBPackets.roundTripPanelForTest(request, wire).handle(player);
				player.serverBrowser.broadcastChanges();
				var payload = CBPackets.takePanelDeliveryForTest(player).orElse(null);
				helper.assertTrue(payload != null,
					"registered browser request must deliver in its unfenced server tick: generation="
						+ core.recipeSnapshot().generation() + ", page=" + page + ", filter=" + filter);
				helper.assertTrue(payload instanceof PatternBrowserResponsePacket,
					"registered browser request must deliver an actual bounded response packet");
				wire.clear();
				PatternBrowserResponsePacket response = CBPackets.roundTripPanelForTest(
					(PatternBrowserResponsePacket) payload, wire);
				helper.assertTrue(client.browser().accept(response),
					"registered browser response must apply to the exact opening and intent");
				return response;
			} finally {
				wire.release();
			}
		}

		private BlockHitResult placementHit() {
			return new BlockHitResult(Vec3.atCenterOf(corePos.below()), Direction.UP,
				corePos.below(), false);
		}

		private List<String> pageSet() {
			List<String> pages = new ArrayList<>();
			pages.add(itemPattern());
			pages.add(tagPattern());
			pages.add(sequencedPattern());
			for (int page = 3; page < EXPECTED_VALID_PAGES; page++)
				pages.add(genericPattern(page));
			pages.add("{not-valid-json");
			pages.add("{\"version\":2,\"type\":\"future_machine\",\"inputs\":[],"
				+ "\"outputs\":[{\"item\":\"minecraft:coal\",\"count\":1}]}");
			return List.copyOf(pages);
		}

		private static String itemPattern() {
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:paper\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"minecraft:diamond\",\"count\":1}],\"address\":\"item-route\"}";
		}

		private static String tagPattern() {
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"tag\":\"minecraft:planks\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"minecraft:emerald\",\"count\":1}],\"address\":\"tag-route\"}";
		}

		private static String sequencedPattern() {
			return "{\"version\":2,\"type\":\"sequenced\",\"inputs\":[{\"item\":\"minecraft:stick\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"minecraft:gold_ingot\",\"count\":1}],\"stages\":["
				+ "{\"address\":\"stage-route\",\"inputs\":[{\"item\":\"minecraft:coal\",\"count\":1}]}]}";
		}

		private static String genericPattern(int page) {
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],\"address\":\"bulk-" + page + "\"}";
		}

		private static String firstPattern(Item output) {
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output)
				+ "\",\"count\":1}],\"address\":\"generation-route\"}";
		}

		private static String priorityPattern(Item output) {
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output)
				+ "\",\"count\":1}],\"address\":\"generation-route\",\"priority\":91}";
		}

		private static List<String> generationPatterns(Item first, Item second, String generation) {
			return List.of(generationPattern(first, generation + "-a"),
				generationPattern(second, generation + "-b"));
		}

		private static String generationPattern(Item output, String address) {
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:stone\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(output)
				+ "\",\"count\":1}],\"address\":\"generation-" + address + "\"}";
		}

		private static Set<UUID> patternIds(PatternBrowserResponsePacket response) {
			return response.page().entries().stream().map(entry -> entry.patternId())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}

		private static Set<net.minecraft.resources.ResourceLocation> outputs(
			PatternBrowserResponsePacket response) {
			return response.page().entries().stream().map(entry -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entry.mainOutput().stack().getItem()))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		}

		private static ItemStack writableBook(List<String> pages) {
			ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
			stack.set(DataComponents.WRITABLE_BOOK_CONTENT,
				new WritableBookContent(pages.stream().map(Filterable::passThrough).toList()));
			return stack;
		}

		private UUID stable(String part) {
			return UUID.nameUUIDFromBytes((scenario + ":" + part + ":"
				+ helper.absolutePos(BlockPos.ZERO).toShortString()).getBytes(StandardCharsets.UTF_8));
		}

		private void await(int remaining, BooleanSupplier ready, Runnable success, String failure) {
			await(remaining, ready, success, () -> failure);
		}

		private void await(int remaining, BooleanSupplier ready, Runnable success,
			Supplier<String> failure) {
			if (ready.getAsBoolean()) { success.run(); return; }
			if (remaining <= 0) { helper.fail(failure.get()); return; }
			helper.runAfterDelay(1, () -> await(remaining - 1, ready, success, failure));
		}

		private void awaitWithFailureAction(int remaining, BooleanSupplier ready, Runnable success,
			Runnable failure) {
			if (ready.getAsBoolean()) { success.run(); return; }
			if (remaining <= 0) { failure.run(); return; }
			helper.runAfterDelay(1,
				() -> awaitWithFailureAction(remaining - 1, ready, success, failure));
		}
	}

	private static final class BrowserMenuPlayer extends FakePlayer {
		@Nullable private PatternStorageCoreMenu serverBrowser;
		@Nullable private AbstractContainerMenu clientMenu;
		private int nextContainerId = 1;

		private BrowserMenuPlayer(ServerLevel level, String name) {
			super(level, new GameProfile(UUID.randomUUID(), name));
		}

		@Override
		public OptionalInt openMenu(MenuProvider provider,
			@Nullable Consumer<RegistryFriendlyByteBuf> extraDataWriter) {
			int containerId = nextContainerId++;
			AbstractContainerMenu menu = provider.createMenu(containerId, getInventory(), this);
			if (menu == null) return OptionalInt.empty();
			containerMenu = menu;
			RegistryFriendlyByteBuf opening = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess(),
				net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
			try {
				if (extraDataWriter != null) extraDataWriter.accept(opening);
				if (menu instanceof PatternStorageCoreMenu browser) {
					serverBrowser = browser;
					clientMenu = CBMenuTypes.PATTERN_STORAGE_CORE.get()
						.create(containerId, getInventory(), opening);
				} else if (menu instanceof LibraryCorePlacementMenu) {
					serverBrowser = null;
					clientMenu = CBMenuTypes.PATTERN_CORE_PLACEMENT.get()
						.create(containerId, getInventory(), opening);
				}
			} finally {
				opening.release();
			}
			return OptionalInt.of(containerId);
		}
	}

	private static UUID stablePanelId(GameTestHelper helper, PanelScenario scenario, String part) {
		return UUID.nameUUIDFromBytes((scenario + ":" + part + ":"
			+ helper.absolutePos(BlockPos.ZERO).toShortString()).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Task-6 topology: the Create networks come from initialized Stock Links, both Computers form
	 * through the physical Panel/casing interactions, and every assertion reads server-owned state.
	 */
	private static final class LogisticsLifecycleTopology {
		private static final int AWAIT = 200;
		private static final String OBSERVED_ADDRESS = "task-6-bare-address";
		private static final String SHARED_ALIAS = "Task 6 Shared Logistics";
		private final GameTestHelper helper;
		private final LogisticsScenario scenario;
		private final ServerLevel level;
		private final LogisticsMenuPlayer firstOperator;
		private final LogisticsMenuPlayer secondOperator;
		private ClusterFixture firstCluster;
		private ClusterFixture secondCluster;
		private CreateLinkFixture targetLink;
		private LogisticsLifecycleTopology(GameTestHelper helper, LogisticsScenario scenario) {
			this.helper = Objects.requireNonNull(helper);
			this.scenario = Objects.requireNonNull(scenario);
			level = helper.getLevel();
			firstOperator = operator("logistics-first");
			secondOperator = operator("logistics-second");
		}

		private void placeLogisticsTopology() {
			helper.assertTrue(FactoryOrchestrationServer.running(level.getServer()),
				"the logistics acceptance requires the production server lifecycle");
			CreateLinkFixture firstBootstrap = placeCreateLink(abs(3, 2, 8), "bootstrap-one", 0);
			CreateLinkFixture secondBootstrap = placeCreateLink(abs(9, 2, 8), "bootstrap-two", 0);
			firstCluster = placeCluster(abs(4, 1, 2), firstBootstrap.networkId(),
				stable("first-computer"), firstOperator);
			secondCluster = placeCluster(abs(10, 1, 2), secondBootstrap.networkId(),
				stable("second-computer"), secondOperator);
			helper.runAfterDelay(30, () -> await(AWAIT,
				() -> createLinkReady(firstBootstrap) && createLinkReady(secondBootstrap),
				this::formClusters, "the bootstrap Stock Links never entered Create's live lifecycle"));
		}

		private void formClusters() {
			formCluster(firstCluster, firstOperator);
			formCluster(secondCluster, secondOperator);
			targetLink = placeCreateLink(abs(16, 2, 8), OBSERVED_ADDRESS, 4);
			await(AWAIT, () -> createLinkReady(targetLink), this::runScenario,
				"the target Stock Link never entered Create's live lifecycle");
		}

		private void runScenario() {
			switch (scenario) {
				case TOOL_BINDS_FORMED_CLUSTER -> bindFirstCluster();
				case ALIAS_IS_GLOBAL -> renameAliasAcrossTwoClusters();
				case COMPONENT_OFFLINE_LAST_SEEN -> observeThenUnloadComponent();
				case BARE_ADDRESS_NO_REVERSE_PROTOCOL -> exerciseBareCompatibility();
			}
		}

		private void bindFirstCluster() {
			ClusterBinding before = Objects.requireNonNull(firstCluster.panel().bindingState());
			captureAndBind(firstOperator, firstCluster.panel());
			ClusterBinding after = Objects.requireNonNull(firstCluster.panel().bindingState());
			helper.assertValueEqual(after.logisticsBindings(),
				List.of(new LogisticsBinding(targetLink.networkId())),
				"the real tool interaction must replace the formed Cluster logistics binding");
			helper.assertTrue(after.revision() > before.revision(),
				"the real tool interaction must advance the formed Cluster binding revision");
			helper.assertValueEqual(firstCluster.computer().production().publishedCoordinatorMember()
				.orElseThrow().bindingState(), after,
				"the formed coordinator and embedded Panel must expose the same revised binding");
			helper.succeed();
		}

		private void renameAliasAcrossTwoClusters() {
			captureAndBind(firstOperator, firstCluster.panel());
			helper.assertTrue(firstOperator.openedMenu instanceof LogisticsAliasMenu,
				"first allocation must open the production revision-fenced alias menu");
			LogisticsAliasMenu firstMenu = (LogisticsAliasMenu) firstOperator.openedMenu;
			captureAndBind(secondOperator, secondCluster.panel());
			ClusterBinding secondBinding = Objects.requireNonNull(secondCluster.panel().bindingState());
			helper.assertValueEqual(secondBinding.logisticsBindings(),
				List.of(new LogisticsBinding(targetLink.networkId())),
				"the second formed Cluster must bind the same persistent Create UUID");

			FactoryOrchestrationSavedData saved = FactoryOrchestrationSavedData.get(level.getServer());
			saved.setDirty(false);
			LogisticsAliasRenamePacket packet = firstMenu.renamePacket(SHARED_ALIAS);
			RegistryFriendlyByteBuf encoded = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				level.registryAccess());
			packet.write(encoded);
			LogisticsAliasRenamePacket decoded = new LogisticsAliasRenamePacket(encoded);
			helper.assertTrue(decoded.handle(firstOperator),
				"the exact live alias menu session must commit through the production packet");
			helper.assertTrue(saved.isDirty(),
				"the accepted global alias CAS must mark the server SavedData dirty");

			LogisticsNetworkProjection firstView = project(firstCluster.panel());
			LogisticsNetworkProjection secondView = project(secondCluster.panel());
			helper.assertValueEqual(firstView.networks(), secondView.networks(),
				"two formed Clusters bound to one UUID must read one global alias and revision");
			helper.assertValueEqual(firstView.networks().getFirst().displayName().getString(),
				SHARED_ALIAS, "both production projections must render the committed global alias");
			helper.assertValueEqual(firstView.networks().getFirst().revision(), 1L,
				"the shared alias projection must expose the accepted directory revision");
			helper.succeed();
		}

		private void observeThenUnloadComponent() {
			LogisticsNetworkDirectory directory = directory();
			await(AWAIT, () -> directory.find(targetLink.networkId()).isPresent()
				&& onlineComponent().isPresent(), () -> {
					LogisticsAddressComponent online = onlineComponent().orElseThrow();
					long lastSeen = online.lastSeenTick();
					level.removeBlock(targetLink.linkPos(), false);
					await(AWAIT, () -> offlineComponent(lastSeen).isPresent(), () -> {
						LogisticsAddressComponent offline = offlineComponent(lastSeen).orElseThrow();
						helper.assertValueEqual(offline.lastSeenTick(), lastSeen,
							"offline publication must retain the component's last real observation tick");
						helper.assertTrue(directory.find(targetLink.networkId()).isPresent(),
							"component unload must not delete the persistent global network identity");
						helper.succeed();
					}, "the removed addressed Packager never became durable OFFLINE last-seen state");
			}, "server lifecycle never allocated and observed the loaded Create network");
		}

		private void exerciseBareCompatibility() {
			await(AWAIT, () -> directory().find(targetLink.networkId()).isPresent()
				&& onlineComponent().isPresent(), () -> {
					CreateRouteKey bareRoute = new CreateRouteKey(targetLink.networkId(), OBSERVED_ADDRESS);
					AgentResolution resolution = FactoryOrchestrationServer.productionAgentResolution(
						level.getServer(), bareRoute, firstCluster.panel().clusterId()).orElseThrow();
					helper.assertTrue(resolution instanceof AgentResolution.NeverRegistered,
						"a bare Create address must not acquire Agent readiness/reset/stop protocol");
					LogisticsAddressComponent component = onlineComponent().orElseThrow();
					helper.assertTrue(component.agentId().isEmpty()
						&& component.roles().equals(Set.of(
							LogisticsAddressComponent.ComponentRole.ORDINARY)),
						"the observed bare Packager must remain an ordinary one-way component");

					int stock = LogisticsManager.getStockOf(targetLink.networkId(),
						new ItemStack(Items.IRON_INGOT), null);
					helper.assertValueEqual(stock, 4,
						"bare compatibility must retain real Create stock observation");
					boolean dispatched = LogisticsManager.broadcastPackageRequest(targetLink.networkId(),
						LogisticallyLinkedBehaviour.RequestType.PLAYER,
						PackageOrderWithCrafts.simple(List.of(new BigItemStack(
							new ItemStack(Items.IRON_INGOT), 1))), null, OBSERVED_ADDRESS);
					helper.assertTrue(dispatched,
						"bare compatibility must retain one-way Create dispatch");
					helper.assertTrue(firstCluster.panel().agentDiagnostics().isEmpty(),
						"no readiness/reset/stop reverse service may be projected for a bare address");
					helper.succeed();
			}, "the production lifecycle never published the bare addressed Packager");
		}

		private LogisticsNetworkProjection project(FactoryPanelBlockEntity panel) {
			return FactoryOrchestrationServer.logisticsDirectoryProjection(panel, 0, 32);
		}

		private Optional<LogisticsAddressComponent> onlineComponent() {
			return FactoryOrchestrationServer.logisticsAddressSnapshot(level.getServer(),
				targetLink.networkId()).stream().filter(component ->
					component.createAddress().equals(OBSERVED_ADDRESS)
						&& component.status() == LogisticsAddressComponent.ComponentStatus.ONLINE)
				.findFirst();
		}

		private Optional<LogisticsAddressComponent> offlineComponent(long lastSeen) {
			return FactoryOrchestrationServer.logisticsAddressSnapshot(level.getServer(),
				targetLink.networkId()).stream().filter(component ->
					component.createAddress().equals(OBSERVED_ADDRESS)
						&& component.status() == LogisticsAddressComponent.ComponentStatus.OFFLINE
						&& component.lastSeenTick() == lastSeen).findFirst();
		}

		private LogisticsNetworkDirectory directory() {
			return FactoryOrchestrationSavedData.get(level.getServer())
				.logisticsNetworkDirectory(level.registryAccess());
		}

		private void captureAndBind(LogisticsMenuPlayer player, FactoryPanelBlockEntity panel) {
			ItemStack tool = new ItemStack(CBItems.LOGISTICS_CONFIGURATION_TOOL.get());
			player.setItemInHand(InteractionHand.MAIN_HAND, tool);
			player.moveTo(Vec3.atCenterOf(targetLink.linkPos()));
			InteractionResult capture = player.gameMode.useItemOn(player, level, tool,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(targetLink.linkPos()),
					Direction.UP, targetLink.linkPos(), false));
			helper.assertTrue(capture.consumesAction(),
				"the tool must capture through the real Create linked behaviour interaction");
			helper.assertValueEqual(LogisticsToolData.read(tool).networkId(),
				Optional.of(targetLink.networkId()),
				"the captured tool must retain the exact persistent Create UUID");
			player.moveTo(Vec3.atCenterOf(panel.getBlockPos()));
			InteractionResult bind = player.gameMode.useItemOn(player, level, tool,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(panel.getBlockPos()),
					Direction.UP, panel.getBlockPos(), false));
			helper.assertTrue(bind.consumesAction(),
				"the captured tool must bind through the real formed Panel interaction");
		}

		private ClusterFixture placeCluster(BlockPos min, UUID bootstrapNetwork, UUID computerId,
			LogisticsMenuPlayer player) {
			buildShell(min);
			BlockPos panelPos = min.offset(0, 1, 1);
			ItemStack panelItem = configuredPanelItem(bootstrapNetwork);
			player.setItemInHand(InteractionHand.MAIN_HAND, panelItem);
			player.moveTo(Vec3.atCenterOf(panelPos));
			InteractionResult embedded = CBItems.FACTORY_PANEL.get().useOn(new UseOnContext(player,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(panelPos),
					Direction.WEST, panelPos, false)));
			helper.assertTrue(embedded.consumesAction(),
				"the configured Panel item must embed through its production placement path");
			ComputerBlockEntity computer = placeComputer(min.offset(1, 1, 1), computerId);
			installLibrarian(computer, player);
			return new ClusterFixture(min, biotechPanel(panelPos), computer);
		}

		private ItemStack configuredPanelItem(UUID networkId) {
			ItemStack panelItem = new ItemStack(CBItems.FACTORY_PANEL.get());
			CustomData.update(DataComponents.BLOCK_ENTITY_DATA, panelItem, tag -> {
				ListTag bindings = new ListTag();
				bindings.add(new LogisticsBinding(networkId).save());
				tag.put("LogisticsBindings", bindings);
				BlockEntity.addEntityType(tag, CBBlockEntityTypes.FACTORY_PANEL.get());
			});
			return panelItem;
		}

		private void formCluster(ClusterFixture cluster, LogisticsMenuPlayer player) {
			player.moveTo(Vec3.atCenterOf(cluster.panel().getBlockPos()));
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
			player.gameMode.useItemOn(player, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(cluster.panel().getBlockPos()), Direction.WEST,
					cluster.panel().getBlockPos(), false));
			helper.assertTrue(cluster.computer().production().epoch().isPresent(),
				"the configured physical Panel must restore and start through real player use");
			player.closeContainer();
			helper.assertTrue(cluster.panel().isFormedClusterMember(),
				"the embedded Panel must publish from the exact formed epoch");
		}

		private CreateLinkFixture placeCreateLink(BlockPos packagerPos, String address,
			int ironCount) {
			BlockPos chestPos = packagerPos.west();
			BlockPos linkPos = packagerPos.north();
			level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState()
				.setValue(PackagerBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
			level.setBlock(linkPos, AllBlocks.STOCK_LINK.getDefaultState()
				.setValue(PackagerLinkBlock.FACE, AttachFace.WALL)
				.setValue(PackagerLinkBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
			PackagerLinkBlockEntity link = (PackagerLinkBlockEntity) level.getBlockEntity(linkPos);
			PackagerBlockEntity packager = (PackagerBlockEntity) level.getBlockEntity(packagerPos);
			helper.assertTrue(link.getPackager() == packager,
				"the real Stock Link must be physically attached to its Packager");

			BlockPos signPos = packagerPos.above();
			level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), Block.UPDATE_ALL);
			SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(signPos);
			sign.setText(sign.getFrontText().setMessage(0, Component.literal(address)), true);
			packager.updateSignAddress();
			if (ironCount > 0)
				((Container) level.getBlockEntity(chestPos)).setItem(0,
					new ItemStack(Items.IRON_INGOT, ironCount));
			return new CreateLinkFixture(linkPos, packagerPos, chestPos, link.behaviour.freqId,
				link, packager);
		}

		private boolean createLinkReady(CreateLinkFixture fixture) {
			return Create.LOGISTICS.logisticsNetworks.containsKey(fixture.networkId())
				&& LogisticallyLinkedBehaviour.getAllPresent(fixture.networkId(), false)
					.stream().anyMatch(link -> link == fixture.link().behaviour);
		}

		private void buildShell(BlockPos min) {
			for (int x = 0; x < 3; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) {
				boolean shell = x == 0 || x == 2 || y == 0 || y == 3 || z == 0 || z == 2;
				level.setBlock(min.offset(x, y, z), shell
					? CBBlocks.COMPUTER_CASING.get().defaultBlockState()
					: Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}

		private ComputerBlockEntity placeComputer(BlockPos pos, UUID id) {
			BlockState lower = CBBlocks.COMPUTER.get().defaultBlockState()
				.setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
			level.setBlock(pos, lower, Block.UPDATE_ALL);
			level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER),
				Block.UPDATE_ALL);
			ComputerBlockEntity computer = (ComputerBlockEntity) level.getBlockEntity(pos);
			CompoundTag tag = computer.saveWithoutMetadata(level.registryAccess());
			tag.getCompound("ComputerData").putUUID("ComputerId", id);
			computer.loadWithComponents(tag, level.registryAccess());
			return computer;
		}

		private void installLibrarian(ComputerBlockEntity computer, LogisticsMenuPlayer player) {
			Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
			villager.setVillagerData(villager.getVillagerData().setType(VillagerType.PLAINS)
				.setProfession(VillagerProfession.LIBRARIAN).setLevel(5));
			ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
			helper.assertTrue(CapturedEntityBoxHelper.captureEntity(box, villager),
				"the logistics Computer resident must be captured by the production box");
			player.setItemInHand(InteractionHand.MAIN_HAND, box);
			player.moveTo(Vec3.atCenterOf(computer.getBlockPos()));
			InteractionResult installed = player.gameMode.useItemOn(player, level, box,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(computer.getBlockPos()),
					Direction.UP, computer.getBlockPos(), false));
			helper.assertTrue(installed.consumesAction(),
				"the real Computer must accept its librarian resident");
		}

		private void selectPanelAndWrenchCasing(LogisticsMenuPlayer player, BlockPos panelPos,
			BlockPos casingPos) {
			ClusterBindingSelection.clear(player);
			ClusterBindingSelection.begin(player, biotechPanel(panelPos));
			InteractionResult bound = ((ComputerCasingBlock) CBBlocks.COMPUTER_CASING.get())
				.onSneakWrenched(level.getBlockState(casingPos), new UseOnContext(player,
					InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(casingPos),
						Direction.UP, casingPos, false)));
			helper.assertTrue(bound.consumesAction(),
				"the selected embedded Panel must bind through the real casing wrench interaction");
		}

		private LogisticsMenuPlayer operator(String name) {
			LogisticsMenuPlayer player = new LogisticsMenuPlayer(level,
				new GameProfile(UUID.randomUUID(), name));
			player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			return player;
		}

		private FactoryPanelBlockEntity biotechPanel(BlockPos pos) {
			return (FactoryPanelBlockEntity) level.getBlockEntity(pos);
		}

		private BlockPos abs(int x, int y, int z) {
			return helper.absolutePos(new BlockPos(x, y, z));
		}

		private UUID stable(String value) {
			return UUID.nameUUIDFromBytes((scenario + ":" + value + ":"
				+ helper.absolutePos(BlockPos.ZERO).toShortString())
				.getBytes(StandardCharsets.UTF_8));
		}

		private void await(int remaining, BooleanSupplier ready, Runnable success,
			String failure) {
			if (ready.getAsBoolean()) { success.run(); return; }
			if (remaining <= 0) { helper.fail(failure); return; }
			helper.runAfterDelay(1, () -> await(remaining - 1, ready, success, failure));
		}
	}

	private record ClusterFixture(BlockPos min, FactoryPanelBlockEntity panel,
		ComputerBlockEntity computer) {}

	private record CreateLinkFixture(BlockPos linkPos, BlockPos packagerPos, BlockPos chestPos,
		UUID networkId, PackagerLinkBlockEntity link, PackagerBlockEntity packager) {}

	private static final class LogisticsMenuPlayer extends FakePlayer {
		@Nullable private AbstractContainerMenu openedMenu;
		private int nextContainerId = 1;

		private LogisticsMenuPlayer(ServerLevel level, GameProfile profile) {
			super(level, profile);
		}

		@Override
		public OptionalInt openMenu(MenuProvider provider,
			@Nullable Consumer<RegistryFriendlyByteBuf> extraDataWriter) {
			int containerId = nextContainerId++;
			AbstractContainerMenu menu = provider.createMenu(containerId, getInventory(), this);
			if (menu == null) return OptionalInt.empty();
			openedMenu = menu;
			containerMenu = menu;
			if (extraDataWriter != null) {
				RegistryFriendlyByteBuf opening = new RegistryFriendlyByteBuf(Unpooled.buffer(),
					registryAccess());
				extraDataWriter.accept(opening);
			}
			return OptionalInt.of(containerId);
		}
	}

	static <C, P> void restartAndRepublish(Runnable freeze, Runnable teardown, Runnable stop, Runnable start,
		Collection<? extends C> primaryComputers, Collection<? extends C> supplementalComputers,
		Consumer<? super C> observeComputer, Collection<? extends P> primaryPanels,
		Collection<? extends P> supplementalPanels, Consumer<? super P> publishPanel) {
		freeze.run();
		teardown.run();
		stop.run();
		start.run();
		distinct(primaryComputers, supplementalComputers).forEach(observeComputer);
		distinct(primaryPanels, supplementalPanels).forEach(publishPanel);
	}

	static <C, P> void restartAndRepublish(Runnable stop, Runnable start,
		Collection<? extends C> primaryComputers, Collection<? extends C> supplementalComputers,
		Consumer<? super C> observeComputer, Collection<? extends P> primaryPanels,
		Collection<? extends P> supplementalPanels, Consumer<? super P> publishPanel) {
		restartAndRepublish(() -> {}, () -> {}, stop, start, primaryComputers, supplementalComputers,
			observeComputer, primaryPanels, supplementalPanels, publishPanel);
	}

	private static <T> LinkedHashSet<T> distinct(Collection<? extends T> primary,
		Collection<? extends T> supplemental) {
		LinkedHashSet<T> distinct = new LinkedHashSet<>();
		distinct.addAll(primary);
		distinct.addAll(supplemental);
		return distinct;
	}

	private static final class Topology {
		/* GameTest ticks are intentionally uncapped while the production worker uses wall time. */
		private static final int AWAIT = 4_000;
		private static final CommunicationFrequency LISTENER_RELAY_HOST_FREQUENCY =
			CommunicationFrequency.of("listener-relay-host");
		private static final CommunicationFrequency MULTI_OWNER_RETUNED_FREQUENCY =
			CommunicationFrequency.of("multi-owner-retuned");
		private static final CommunicationFrequency RETAINED_REPLICA_RETUNED_FREQUENCY =
			CommunicationFrequency.of("retained-replica-retuned");
		private static final CommunicationFrequency MISMATCH_SCENARIO_FREQUENCY =
			CommunicationFrequency.of("task2-mismatch");
		private static final CommunicationFrequency REMOTE_COMPUTE_SCENARIO_FREQUENCY =
			CommunicationFrequency.of("task2-remote");
		private static final String INPUT = "agent-input", STAGE_A = "stage-a",
			STAGE_B = "stage-b", OUTPUT = "agent-output", WAREHOUSE = "warehouse",
			CAPACITY_INPUT = "capacity-input", CAPACITY_OUTPUT = "capacity-output";
		private final GameTestHelper helper;
		private final Scenario scenario;
		@Nullable private final PanelScenario panelScenario;
		private final ServerLevel level;
		private final UUID network, agentId;
		private final CommunicationFrequency testRadioFrequency;
		private final BlockPos panelPos, corePos, computerMin;
		private final BlockPos sourceChest, sourcePackager, inputChest, inputPackager;
		private final BlockPos outputChest, outputPackager, warehouseChest, warehousePackager;
		private final BlockPos inputEndpointPos, stageBEndpointPos, outputEndpointPos;
		private final BlockPos signalPos;
		private final List<String> nativeAddresses = new ArrayList<>();
		private FactoryPanelBlockEntity panel;
		private LibraryBlockEntity core;
		private ComputerBlockEntity computer, radioTarget, radioRelay, radioReceiver, radioReplica;
		private ExternalListenerService.Publication listenerRelayPublication;
		private ExternalListenerService.Publication orderlyPreFreezePublication;
		private RadioEndpoint orderlyPreFreezeSource;
		private RadioEndpoint orderlyPreFreezeTarget;
		private RadioRouter.RouteStatus orderlyPendingStatus;
		private UUID orderlyPreFreezeRelayComputerId;
		private UUID orderlyPreFreezeRelayClusterId;
		private com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity inputEndpoint;
		private com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity outputEndpoint;
		private com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity warehouseEndpoint;
		private com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity
			capacityInputEndpoint, capacityOutputEndpoint;
		private UUID goalId, goalProcessId;
		private ServerPlayer operator;
		private int nativeOrderCount;
		private long deliveredIron, deliveredCopper;
		private long warehouseBaseline;
		private int recoveryOccupancyBaseline;
		private boolean remoteComputationRestarted;
		private UUID remoteComputationLeaseId;
		private int submissionAttempts;
		private CompoundTag pendingAdmissionCoreSnapshot;
		private boolean verifyGoalSettlement;
		private UUID localExpansionRunId;
		private boolean localPreflightQueryComplete;
		private UUID localPreflightSearchId;

		private Topology(GameTestHelper helper, Scenario scenario) {
			this(helper, scenario, null);
		}

		private Topology(GameTestHelper helper, PanelScenario panelScenario) {
			this(helper, panelScenario == PanelScenario.NETWORK_COLLAPSES_SUBNET
				? Scenario.EXTERNAL_LISTENER : Scenario.LOCAL_STANDARD_AGENT, panelScenario);
		}

		private Topology(GameTestHelper helper, Scenario scenario,
			@Nullable PanelScenario panelScenario) {
			this.helper = Objects.requireNonNull(helper);
			this.scenario = Objects.requireNonNull(scenario);
			this.panelScenario = panelScenario;
			level = helper.getLevel();
			computerMin = abs(11, 1, 7); panelPos = computerMin.offset(0, 1, 0); corePos = abs(3, 2, 5);
			if (scenario == Scenario.TRADER_RELAY || isListenerRelayScenario()) {
				// Every configured native endpoint is owned by the remote formed target epoch.
				// Keep its accompanying Create network inside that epoch's immutable reach too;
				// the trader is then the only cross-range hop through the relay.
				sourceChest = abs(3, 73, 3); sourcePackager = abs(4, 73, 3);
				inputChest = abs(3, 73, 5); inputPackager = abs(4, 73, 5);
				outputChest = abs(5, 73, 5); outputPackager = abs(6, 73, 5);
				warehouseChest = abs(5, 73, 2); warehousePackager = abs(6, 73, 2);
				inputEndpointPos = abs(4, 74, 0); stageBEndpointPos = abs(5, 74, 0);
				outputEndpointPos = outputPackager.above();
			} else {
				sourceChest = abs(10, 2, 2); sourcePackager = abs(11, 2, 2);
				inputChest = abs(13, 2, 2); inputPackager = abs(14, 2, 2);
				outputChest = abs(16, 2, 2); outputPackager = abs(17, 2, 2);
				warehouseChest = abs(16, 2, 7); warehousePackager = abs(17, 2, 7);
				inputEndpointPos = abs(13, 3, 5); stageBEndpointPos = abs(14, 3, 5);
				outputEndpointPos = outputPackager.above();
			}
			signalPos = abs(15, 3, 8);
			network = UUID.nameUUIDFromBytes((scenario + ":" + panelPos.toShortString())
				.getBytes(StandardCharsets.UTF_8));
			agentId = UUID.nameUUIDFromBytes(("agent:" + network).getBytes(StandardCharsets.UTF_8));
			testRadioFrequency = CommunicationFrequency.of("gt-"
				+ Long.toUnsignedString(network.getMostSignificantBits(), 36));
		}

		private void place() {
			helper.assertTrue(FactoryOrchestrationServer.running(level.getServer()),
				"production orchestration must own this server");
			level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			placeCore(corePos); core = patternCore(corePos);
			BlockPos shelfPos = corePos.east();
			level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
			ChiseledBookShelfBlockEntity shelf = (ChiseledBookShelfBlockEntity) level.getBlockEntity(shelfPos);
			shelf.setItem(0, writableBook(patternJson())); shelf.setChanged();
			buildShell(computerMin, scenario == Scenario.RADIO_TOPOLOGY || scenario == Scenario.LOCAL_PURE_EXPANSION ? 4 : 3);
			level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
			panel = biotechPanel(panelPos);
			computer = placeComputer(computerMin.offset(1, 1, 1), scenario == Scenario.RADIO_TOPOLOGY || scenario == Scenario.LOCAL_PURE_EXPANSION
				? new UUID(Long.MIN_VALUE, 0L) : stable("computer"));
			boolean externalListenerScenario = scenario == Scenario.EXTERNAL_LISTENER;
			installResident(computer, scenario == Scenario.REMOTE_COMPUTE || scenario == Scenario.LOCAL_PURE_EXPANSION
				? VillagerProfession.NITWIT : externalListenerScenario
					? VillagerProfession.NONE : VillagerProfession.CLERIC, externalListenerScenario);
			if (scenario == Scenario.RADIO_TOPOLOGY) {
				radioReplica = placeComputer(computerMin.offset(2, 1, 1), new UUID(0L, 0L));
				installResident(radioReplica, VillagerProfession.NONE, true);
			}
			if (scenario == Scenario.LOCAL_PURE_EXPANSION) {
				radioReplica = placeComputer(computerMin.offset(2, 1, 1), new UUID(Long.MAX_VALUE, 0L));
				installResident(radioReplica, VillagerProfession.CLERIC, false);
			}
			placeCreateNetwork();
			if (isRadio()) placeRadioComputers();
			if (scenario == Scenario.TRADER_RELAY || isListenerRelayScenario())
				loadTraderChunksForNaturalTicks();
			helper.runAfterDelay(28, this::bindAndStart);
		}

		private void placeCreateNetwork() {
			placePackager(sourceChest, sourcePackager);
			placePackager(inputChest, inputPackager);
			placePackager(outputChest, outputPackager);
			placePackager(warehouseChest, warehousePackager);
			container(sourceChest).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
			container(sourceChest).setItem(1, new ItemStack(Items.COPPER_INGOT, 64));
			if (panelScenario == PanelScenario.MONITOR_GOAL_TREE) {
				ItemStack componentStock = new ItemStack(Items.DIAMOND, 17);
				componentStock.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
					net.minecraft.network.chat.Component.literal("Monitor exact component stock"));
				container(sourceChest).setItem(2, componentStock);
				container(sourceChest).setItem(3, new ItemStack(Items.DIAMOND, 3));
			}
			level.setBlock(inputEndpointPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			inputEndpoint = placeNativePanel(inputEndpointPos, INPUT, Items.IRON_INGOT, false);
			level.setBlock(stageBEndpointPos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			placeNativePanel(stageBEndpointPos, STAGE_B, Items.COPPER_INGOT, false);
			outputEndpoint = placeNativePanel(outputEndpointPos, OUTPUT, requestedOutputStack(), true);
			if (scenario == Scenario.REMOTE_COMPUTE) {
				BlockPos capacityInput = remoteCapacityInputPos();
				BlockPos capacityOutput = remoteCapacityOutputPos();
				level.setBlock(capacityInput.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(capacityOutput.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
				capacityInputEndpoint = placeNativePanel(capacityInput, CAPACITY_INPUT,
					Items.IRON_INGOT, false);
				capacityOutputEndpoint = placeNativePanel(capacityOutput, CAPACITY_OUTPUT,
					Items.GOLD_INGOT, false);
			}
			if (scenario == Scenario.RESTOCKER_BARE || usesAgent()) {
				warehouseEndpoint = placeNativePanel(warehousePackager.above(), WAREHOUSE,
					requestedOutputStack(), true);
			}
			if (scenario == Scenario.RESTOCKER_BARE) {
				container(warehouseChest).setItem(0, new ItemStack(Items.GOLD_INGOT, 3));
			}
			if (scenario == Scenario.SIGNAL_COMPLETION)
				level.setBlock(signalPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			warehouseBaseline = count(warehouseChest, Items.GOLD_INGOT);
		}

		private void bindAndStart() {
			ServerPlayer player = player(panelPos, "factory-production");
			operator = player;
			helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
				List.of(new LogisticsBinding(network))), ClusterBindingService.BindResult.OK,
				"the automatically formed Cluster must authorize its production logistics configuration");
			helper.assertValueEqual(ClusterBindingService.bind(player, panel, core),
				ClusterBindingService.BindResult.OK, "real Library must bind to the Panel");
			helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(panel.clusterId())).isPresent(),
				"the real Computer must retain its automatically started epoch");
			bindRadioComputers(player);
			await(AWAIT, this::scenarioCommunicationAuthoritiesReady, () -> {
				configureScenarioPrimaryFrequency();
				await(AWAIT, this::scenarioPrimaryProjectionReady,
					this::continueAfterScenarioPrimaryProjection,
					"durable scenario primary frequency never reached the communication projection");
			}, "formed Computer authorities never reached the durable communication command service");
		}

		private void continueAfterScenarioPrimaryProjection() {
			if (scenario == Scenario.RADIO_TOPOLOGY)
				FactoryOrchestrationServer.nativeComputerObserved(radioReplica.production());
			await(AWAIT, () -> computer.production().epoch().flatMap(epoch ->
				ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId())
					.filter(state -> state.epochId().equals(epoch.epochId()))).isPresent()
					&& (scenario != Scenario.EXTERNAL_LISTENER || computer.production().canConfigureExternalListeners())
					&& (!isListenerRelayScenario() || radioRelay.production().canConfigureExternalListeners()),
				() -> {
					if (scenario == Scenario.RADIO_TOPOLOGY) exerciseRadioTopologyLifecycle();
					else if (scenario == Scenario.EXTERNAL_LISTENER)
						exerciseExternalListenerTopology();
					else if (scenario == Scenario.TRADER_RELAY)
						await(AWAIT, this::traderRelayTopologyReady,
							() -> configureCoreRouting(Objects.requireNonNull(operator, "operator")),
							this::traderRelayRouteDiagnostics);
					else if (isListenerRelayScenario())
						configureListenerRelayAndRoute(Objects.requireNonNull(operator, "operator"));
					else configureCoreRouting(Objects.requireNonNull(operator, "operator"));
				},
				"real Computer epoch never became authoritative for the Core menu");
		}

		private boolean scenarioCommunicationAuthoritiesReady() {
			if (ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId()).isEmpty())
				return false;
			return scenarioPrimaryFrequencies().keySet().stream().allMatch(clusterId ->
				ProductionRuntimeServices.get(level.getServer()).snapshot(clusterId).isPresent());
		}

		private boolean scenarioPrimaryProjectionReady() {
			var service = FactoryOrchestrationServer.communicationCommandService(level.getServer())
				.orElseThrow();
			return scenarioPrimaryFrequencies().entrySet().stream().allMatch(entry -> service
				.configuration(entry.getKey()).map(configuration -> configuration.primaryFrequency()
					.equals(entry.getValue())).orElse(false));
		}

		/** Isolated production server acceptance for two bounded Trader listener publications. */
		private void exerciseExternalListenerTopology() {
			FactoryOrchestrationServer.nativeComputerObserved(computer.production());
			List<CommunicationFrequency> frequencies = List.of(
				CommunicationFrequency.of(testRadioFrequency.value() + "-a"),
				CommunicationFrequency.of(testRadioFrequency.value() + "-b"));
			await(AWAIT, () -> computer.production().replaceExternalListeners(0, frequencies)
				== ExternalListenerService.EditResult.APPLIED,
				() -> awaitExternalListenerPublications(frequencies),
				() -> "formed Trader never accepted listeners; availability="
					+ computer.production().availabilityReason() + ", epoch=" + computer.production().epoch());
		}

		private void awaitExternalListenerPublications(List<CommunicationFrequency> frequencies) {
			await(AWAIT, () -> {
				List<ExternalListenerService.ListenerProjection> projection =
					FactoryOrchestrationServer.externalListenerProjectionForTest(level.getServer(),
						computer.computerId().orElseThrow());
				long listenerProcesses = ProductionRuntimeServices.get(level.getServer())
					.snapshot(panel.clusterId()).orElseThrow().processes().values().stream()
					.filter(process -> process.kind() == ProcessKind.RADIO_LISTENER).count();
				return projection.size() == 2 && projection.stream().allMatch(listener ->
					listener.state() == ExternalListenerService.ListenerState.ACTIVE)
					&& listenerProcesses == 2;
			}, () -> {
				List<ExternalListenerService.Publication> publications =
					FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
						computer.computerId().orElseThrow());
				helper.assertValueEqual(publications.size(), 2,
					"each configured frequency must own one router source/relay publication");
				long previousSampleTick = radioTopologyFrame().sampleTick();
				awaitNextRadioTopologyFrame(previousSampleTick, () -> {
                    var current = FactoryOrchestrationServer.externalListenerPublicationsForTest(
                        level.getServer(), computer.computerId().orElseThrow());
                    return current.size() == 2 && current.stream()
                        .flatMap(publication -> java.util.stream.Stream.of(publication.endpoint(), publication.relayEndpoint()))
                        .allMatch(endpoint -> radioTopologyFrame().find(endpoint.key()).filter(sample -> sample.loaded()
                            && sample.generation() == endpoint.generation()
                            && sample.source().equals(endpoint.address())).isPresent());
                }, () -> assertExternalListenerPublications(frequencies,
                    FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
                        computer.computerId().orElseThrow())),
                    "current listener endpoint identities never entered a production post-tick topology frame");
			}, "formed Trader listeners never reserved two real process slots and router publications");
		}

		private void assertExternalListenerPublications(List<CommunicationFrequency> frequencies,
			List<ExternalListenerService.Publication> publications) {
			for (ExternalListenerService.Publication publication : publications) {
				helper.assertTrue(FactoryOrchestrationServer.radioEndpointRegisteredForTest(
					level.getServer(), publication.endpoint().key())
					&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
						publication.relayEndpoint().key()),
					"an active listener must register exact source and relay router endpoints");
				helper.assertTrue(radioTopologyFrame().sample(publication.endpoint().key()).loaded()
					&& radioTopologyFrame().sample(publication.relayEndpoint().key()).loaded(),
					"the active source/relay pair must join the shared frozen topology frame");
				helper.assertValueEqual(FactoryOrchestrationServer.radioLinkForTest(level.getServer(),
					publication.endpoint(), publication.relayEndpoint()), RadioLinkPolicy.LinkResult.RADIO,
					"the active gateway endpoints must be physically reachable across clusters; source="
                        + publication.endpoint() + "; relay=" + publication.relayEndpoint()
                        + "; sourceSample=" + radioTopologyFrame().find(publication.endpoint().key())
                        + "; relaySample=" + radioTopologyFrame().find(publication.relayEndpoint().key()));
			}
			helper.assertValueEqual(computer.production().replaceExternalListeners(1, List.of(
				frequencies.get(0), frequencies.get(1), CommunicationFrequency.of("third"))),
				ExternalListenerService.EditResult.TOO_MANY,
				"the frozen two-slot configuration rejects a third persisted listener");
			// The ingress authorization proof needs a real non-terminal local Goal. Retain one
			// already-proven active listener and release the other frozen slot for that Goal;
			// the two-frequency/third-WAIT contract above remains exercised before this phase.
			helper.assertValueEqual(computer.production().replaceExternalListeners(1, List.of(frequencies.get(0))),
				ExternalListenerService.EditResult.APPLIED,
				"listener reconfiguration must release one frozen slot for the authorized local Goal");
			await(AWAIT, () -> {
				List<ExternalListenerService.ListenerProjection> retained =
					FactoryOrchestrationServer.externalListenerProjectionForTest(level.getServer(),
						computer.computerId().orElseThrow());
				return retained.size() == 1 && retained.stream().allMatch(listener ->
					listener.frequency().equals(frequencies.get(0))
						&& listener.state() == ExternalListenerService.ListenerState.ACTIVE);
			},
				() -> configureCoreRouting(Objects.requireNonNull(operator, "listener operator")),
				"one retained listener did not release the second frozen process slot for Goal admission");
		}

		/** Exercises the authorized ingress -> durable outbox -> router path and opaque runtime subnet view. */
		private void exerciseExternalListenerIngressAndSubnetProjection() {
			CommunicationFrequency waitingFrequency = CommunicationFrequency.of(testRadioFrequency.value() + "-c");
			helper.assertValueEqual(computer.production().replaceExternalListeners(2, List.of(
				CommunicationFrequency.of(testRadioFrequency.value() + "-a"), waitingFrequency)),
				ExternalListenerService.EditResult.APPLIED,
				"the third requested listener frequency must enter the real process-capacity lifecycle");
			await(AWAIT, () -> {
				List<ExternalListenerService.ListenerProjection> projection = FactoryOrchestrationServer
					.externalListenerProjectionForTest(level.getServer(), computer.computerId().orElseThrow());
				return projection.size() == 2 && projection.getFirst().state()
					== ExternalListenerService.ListenerState.ACTIVE
					&& projection.get(1).frequency().equals(waitingFrequency)
					&& projection.get(1).state() == ExternalListenerService.ListenerState.WAITING_PROCESS;
			}, this::exerciseAuthorizedExternalListenerIngress,
				() -> "the third requested listener frequency must report WAIT(PROCESS) after the local Goal owns its slot; listeners="
					+ FactoryOrchestrationServer.externalListenerProjectionForTest(level.getServer(), computer.computerId().orElseThrow())
					+ "; " + productionDiagnostics(sourcePackager));
		}

		private void exerciseAuthorizedExternalListenerIngress() {
			// The capacity transition republishes the surviving gateway with a new fenced generation.
			// Sample that real endpoint pair before asking the physical router to advance its one hop.
			ExternalListenerService.Publication publication = FactoryOrchestrationServer
				.externalListenerPublicationsForTest(level.getServer(), computer.computerId().orElseThrow())
				.getFirst();
			long previousSampleTick = radioTopologyFrame().sampleTick();
			awaitNextRadioTopologyFrame(previousSampleTick, () ->
				radioTopologyFrame().find(publication.endpoint().key())
					.map(RadioTopologyFrame.Sample::loaded).orElse(false)
					&& radioTopologyFrame().find(publication.relayEndpoint().key())
						.map(RadioTopologyFrame.Sample::loaded).orElse(false),
				() -> continueAuthorizedExternalListenerIngress(publication),
				"retained listener gateway never entered a production post-tick topology frame");
		}

		private void continueAuthorizedExternalListenerIngress(
			ExternalListenerService.Publication publication) {
			helper.assertTrue(radioTopologyFrame().sample(publication.endpoint().key()).loaded()
				&& radioTopologyFrame().sample(publication.relayEndpoint().key()).loaded(),
				"the retained active listener gateway must republish before its authorized ingress route");
			if (panelScenario == PanelScenario.NETWORK_COLLAPSES_SUBNET) {
				// The capacity test's waiting C listener can legitimately activate while the
				// Goal advances. The one-subnet Panel case explicitly owns one configured listener.
				if (computer.production().configuration().frequencies().size() != 1) {
					helper.assertValueEqual(computer.production().replaceExternalListeners(
						computer.production().configuration().revision(), List.of(publication.endpoint().frequency())),
						ExternalListenerService.EditResult.APPLIED,
						"one-subnet acceptance must retire the capacity-test listener by real configuration CAS");
					await(AWAIT, () -> FactoryOrchestrationServer.externalListenerPublicationsForTest(
						level.getServer(), computer.computerId().orElseThrow()).size() == 1,
						this::exerciseAuthorizedExternalListenerIngress,
						"the explicitly removed listener must retire before the one-subnet capture");
					return;
				}
				await(AWAIT, this::exercisePanelAcceptance,
					() -> routeAuthorizedExternalListenerIngress(publication),
					this::panelCommunicationCaptureFailure);
				return;
			}
			routeAuthorizedExternalListenerIngress(publication);
		}

		private void routeAuthorizedExternalListenerIngress(
			ExternalListenerService.Publication publication) {
			RemoteRequestKey key = new RemoteRequestKey(stable("listener-ingress-request"),
				stable("listener-ingress-run"), 0, publication.endpoint().generation(),
				publication.relayEndpoint().generation());
			AgentProtocolMessage.Offer payload = new AgentProtocolMessage.Offer(key,
				new ProductionRequestTicket(key.requestId(), key.runId(), key.fence(),
					publication.endpoint().clusterId(), stable("listener-ingress-agent"),
					new CreateRouteKey(network, "listener-ingress"), core.memberId(), InputMode.BULK,
					1, 0, 0, 0));
			UUID messageId = stable("listener-ingress-message:" + goalId);
			RadioEnvelope incoming = RadioEnvelope.create(messageId, publication.endpoint(),
				publication.relayEndpoint(), publication.endpoint().frequency(), payload,
				SubnetRouteTrace.start(publication.boundaryBinding().sourceNetworkId(),
					publication.boundaryBinding().sourceClusterId()));
			RadioEnvelope forwarded = FactoryOrchestrationServer.forwardExternalListenerIngress(computer.production(),
				publication.endpoint().frequency(), incoming,
				new ExternalListenerService.IngressAuthority.LocalGoal(goalId)).orElseThrow(() ->
					new IllegalStateException("formed local Goal must authorize listener ingress"));
			helper.assertTrue(FactoryOrchestrationServer.routeRadio(level.getServer(), forwarded, List.of()),
				"authorized listener ingress must enter the durable outbox before router advancement");
			helper.assertValueEqual(FactoryOrchestrationServer.radioStatus(level.getServer(), messageId)
				.orElseThrow(), RadioRouter.RouteStatus.QUEUED,
				"the ingress transition must be durably queued before the next server tick");

			SubnetBoundaryService.SubnetBoundaryProjection projection = FactoryOrchestrationServer
				.publicSubnetProjectionForTest(level.getServer(), publication.relayEndpoint().key())
				.orElseThrow();
			helper.assertValueEqual(projection.cards().size(), 1,
				"a formed runtime subnet must project exactly one opaque public card");
			helper.assertValueEqual(projection.cards().getFirst().listenerFrequency(),
				publication.boundaryBinding().listenerFrequency(),
				"the public card frequency must derive from the exact active router boundary binding");
			helper.assertTrue(projection.computers().isEmpty()
				&& projection.serviceKinds().equals(Set.of(SubnetBoundaryService.PublicService.LIBRARY,
					SubnetBoundaryService.PublicService.TASK_ENTRY,
					SubnetBoundaryService.PublicService.LOGISTICS_METADATA)),
				"runtime publication may expose only opaque Library/task/logistics capabilities");
			RadioEnvelope looped = RadioEnvelope.create(stable("listener-loop:" + goalId),
				publication.endpoint(), publication.relayEndpoint(), publication.endpoint().frequency(), payload,
				SubnetRouteTrace.start(publication.boundaryBinding().sourceNetworkId(),
					publication.boundaryBinding().sourceClusterId()).visit(
						publication.boundaryBinding().targetNetworkId(),
						publication.boundaryBinding().targetClusterId()));
			helper.assertTrue(FactoryOrchestrationServer.forwardExternalListenerIngress(computer.production(),
				publication.endpoint().frequency(), looped,
				new ExternalListenerService.IngressAuthority.LocalGoal(goalId)).isEmpty(),
				"the authoritative router boundary must reject an already visited subnet destination");
			helper.runAfterDelay(1, () -> {
				helper.assertTrue(FactoryOrchestrationServer.radioStatus(level.getServer(), messageId)
					.isEmpty(), "router delivery must retire the generic ingress outbox entry after one hop");
				helper.succeed();
			});
		}

		private String panelCommunicationCaptureFailure() {
			RadioTopologyFrame frame = radioTopologyFrame();
			List<String> missing = FactoryOrchestrationServer.radioAuthorityStateForTest(level.getServer())
				.endpoints().values().stream().flatMap(authority -> authority.current().stream())
				.filter(endpoint -> FactoryOrchestrationServer.radioEndpointRegisteredForTest(
					level.getServer(), endpoint.key()))
				.filter(endpoint -> frame.find(endpoint.key()).isEmpty())
				.limit(1).map(endpoint -> {
					BlockEntity owner = com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat
						.getLoadedBlockEntity(level, endpoint.address().localPos());
					return endpoint.key() + "/generation=" + endpoint.generation()
						+ "/runtimeOwner=" + FactoryOrchestrationServer.radioEndpointForTest(
							level.getServer(), endpoint.key()).isPresent()
						+ "/loaded=" + (owner instanceof ComputerBlockEntity current
							? current.computerId() + "/" + current.production().radioEndpointGeneration()
								+ "/online=" + current.production().structureOnline() + "/profile="
								+ current.production().installedProfile() : owner);
				}).toList();
			return "the live communication view never crossed a complete production topology frame; "
				+ "missing=" + missing + ", frameTick=" + frame.sampleTick();
		}

		private void exerciseRadioTopologyLifecycle() {
			await(AWAIT, this::radioTargetHasCurrentTopologySample, () ->
				assertAllLoadedComputerOwnersRetuneTogether(this::continueRadioTopologyLifecycle),
				"production topology never captured the real radio Computer");
		}

		private void continueRadioTopologyLifecycle() {
			assertCoreRebindDrivesBothPrimaryEndpoints(this::continueRadioTopologyLifecycleAfterCoreRebind);
		}

		private void continueRadioTopologyLifecycleAfterCoreRebind() {
			RadioTopologyFrame initial = radioTopologyFrame();
			RadioEndpoint.Key key = radioTargetTopologyKey();
			helper.assertTrue(initial.dynamic(),
				"the production default must publish dynamic physical topology frames");
			helper.assertTrue(initial.sample(key).loaded(),
				"a real observed Computer must enter the production topology frame");
			RadioEndpoint beforeRetune = Objects.requireNonNull(routerRadioEndpoint(key),
				"the real Computer must own an exact router endpoint before retune");
			declareClusterPrimary(radioOwnerPanel(), CommunicationFrequency.UNCONFIGURED);
			RadioEndpoint retuned = Objects.requireNonNull(routerRadioEndpoint(key),
				"the exact Computer owner must republish after resolver retune");
			helper.assertTrue(retuned.generation() > beforeRetune.generation()
				&& retuned.frequency().equals(CommunicationFrequency.UNCONFIGURED),
				"retune must retain the loaded owner fence while publishing a new primary generation");

			FactoryOrchestrationServer.nativeComputerUnloaded(radioTarget.production());
			RadioTopologyFrame unloaded = radioTopologyFrame();
			helper.assertTrue(unloaded != initial && unloaded.find(key).isEmpty()
				&& unloaded.sampleTick() == initial.sampleTick(),
				"same-tick retune teardown must remove the old pose instead of publishing a ghost link");

			FactoryOrchestrationServer.nativeComputerObserved(radioTarget.production());
			RadioTopologyFrame reobserved = radioTopologyFrame();
			helper.assertTrue(reobserved != unloaded && reobserved.find(key).isEmpty(),
				"same-generation reload must atomically discard the old pose before cadence");
			await(AWAIT, this::radioTargetHasCurrentTopologySample, () -> {
				RadioTopologyFrame refreshed = radioTopologyFrame();
				helper.assertTrue(Math.floorMod(refreshed.sampleTick(), 20) == 0
					&& refreshed.sample(key).loaded(),
					"only a cadence refresh may publish the reloaded Computer's current pose");
				RadioEndpoint reloaded = routerRadioEndpoint(key);
				helper.assertTrue(reloaded != null
					&& reloaded.generation() == radioTarget.production().radioEndpointGeneration()
					&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), key),
					"reload must atomically restore the exact monotonic router endpoint with its topology frame");
				assertProductionResolverRejectsReplacedComputerEvidence();
			}, "production topology never recovered the real Computer after router-fenced reload");
		}

		/** A primary retune must publish every exact member of one formed Computer structure. */
		private void assertAllLoadedComputerOwnersRetuneTogether(Runnable onRetuned) {
			Objects.requireNonNull(onRetuned, "retuned continuation");
			helper.assertTrue(radioReplica != null && radioReplica.production().epoch().isPresent(),
				"the second formed Computer must share the coordinator epoch before primary retune");
			FactoryOrchestrationServer.nativeComputerObserved(radioReplica.production());
			await(AWAIT, () -> radioReplica.production().replaceExternalListeners(0,
				List.of(CommunicationFrequency.of("multi-owner-listener")))
					== ExternalListenerService.EditResult.APPLIED,
				() -> awaitAllLoadedComputerOwnersRetuneTogether(onRetuned),
				() -> "the non-coordinator Trader never became a loaded listener member; availability="
					+ radioReplica.production().availabilityReason() + ", epoch=" + radioReplica.production().epoch());
		}

		private void awaitAllLoadedComputerOwnersRetuneTogether(Runnable onRetuned) {
			/* The worker owns an immutable state snapshot intermittently.  Wait for the next real
			 * Computer observation to reserve its ordinary Trader slot, rather than assuming that
			 * the synchronous configuration CAS can mutate a worker-owned runtime state. */
			await(AWAIT, () -> FactoryOrchestrationServer.externalListenerPublicationsForTest(
				level.getServer(), radioReplica.computerId().orElseThrow()).size() == 1,
				() -> completeAllLoadedComputerOwnersRetune(onRetuned),
				"the non-coordinator Trader listener never acquired an ordinary process after worker release");
		}

		private void completeAllLoadedComputerOwnersRetune(Runnable onRetuned) {
			List<ExternalListenerService.Publication> beforeListeners =
				FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
					radioReplica.computerId().orElseThrow());
			RadioEndpoint.Key coordinatorKey = radioEndpointKey(computer, panel.clusterId());
			RadioEndpoint.Key replicaKey = radioEndpointKey(radioReplica, panel.clusterId());
			RadioEndpoint coordinatorBefore = Objects.requireNonNull(routerRadioEndpoint(coordinatorKey),
				"the coordinator endpoint must be registered before retune");
			RadioEndpoint replicaBefore = Objects.requireNonNull(routerRadioEndpoint(replicaKey),
				"the non-coordinator endpoint must be registered before retune");
			ExternalListenerService.Publication listenerBefore = beforeListeners.getFirst();
			declareClusterPrimary(panel, MULTI_OWNER_RETUNED_FREQUENCY);
			RadioEndpoint coordinatorAfter = Objects.requireNonNull(routerRadioEndpoint(coordinatorKey),
				"the coordinator must re-register synchronously on primary retune");
			RadioEndpoint replicaAfter = Objects.requireNonNull(routerRadioEndpoint(replicaKey),
				"the non-coordinator must re-register synchronously on primary retune");
			ExternalListenerService.Publication listenerAfter = FactoryOrchestrationServer
				.externalListenerPublicationsForTest(level.getServer(), radioReplica.computerId().orElseThrow())
				.getFirst();
			helper.assertTrue(coordinatorAfter.generation() > coordinatorBefore.generation()
				&& replicaAfter.generation() > replicaBefore.generation()
				&& coordinatorAfter.frequency().equals(MULTI_OWNER_RETUNED_FREQUENCY)
				&& replicaAfter.frequency().equals(MULTI_OWNER_RETUNED_FREQUENCY),
				"primary retune must update both coordinator and non-coordinator exact generations/frequencies");
			helper.assertTrue(listenerAfter.relayEndpoint().generation()
				> listenerBefore.relayEndpoint().generation()
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
					listenerAfter.relayEndpoint().key()),
				"the non-coordinator Trader listener relay must refresh its exact active candidate");
			long previousSampleTick = radioTopologyFrame().sampleTick();
			awaitNextRadioTopologyFrame(previousSampleTick, () ->
				radioTopologyFrame().find(coordinatorKey).map(RadioTopologyFrame.Sample::loaded)
					.orElse(false)
					&& radioTopologyFrame().find(replicaKey).map(RadioTopologyFrame.Sample::loaded)
						.orElse(false)
					&& radioTopologyFrame().find(listenerAfter.relayEndpoint().key())
						.map(RadioTopologyFrame.Sample::loaded).orElse(false), () -> {
					helper.assertTrue(radioTopologyFrame().sample(coordinatorKey).loaded()
						&& radioTopologyFrame().sample(replicaKey).loaded()
						&& radioTopologyFrame().sample(listenerAfter.relayEndpoint().key()).loaded(),
						"retune must synchronize router, topology and listener runtime for all loaded owners");
					retireNonCoordinatorBeforePrimaryRetune(onRetuned, coordinatorAfter, replicaKey,
						listenerAfter);
				}, "retuned owners never entered a production post-tick topology frame");
		}

		/** A retained ReplicaSet entry is not a loaded owner and must never receive a retune. */
		private void retireNonCoordinatorBeforePrimaryRetune(Runnable onRetuned,
			RadioEndpoint coordinatorBefore, RadioEndpoint.Key retiredReplicaKey,
			ExternalListenerService.Publication retiredListener) {
			long beforeCadence = level.getGameTime();
			level.removeBlock(radioReplica.getBlockPos(), false);
			helper.assertTrue(radioReplica.isRemoved()
				&& !FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
					retiredReplicaKey)
				&& FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
					radioReplica.computerId().orElseThrow()).isEmpty()
				&& !radioReplica.production().hasIndexedShellMembership(),
				"unloading the non-coordinator must retire endpoint, listener, and shell membership");

			declareClusterPrimary(panel, RETAINED_REPLICA_RETUNED_FREQUENCY);
			RadioEndpoint coordinatorAfter = Objects.requireNonNull(routerRadioEndpoint(
				radioEndpointKey(computer, panel.clusterId())),
				"the still-loaded coordinator must re-register after the retained-replica retune");
			helper.assertTrue(coordinatorAfter.generation() > coordinatorBefore.generation()
				&& coordinatorAfter.frequency().equals(RETAINED_REPLICA_RETUNED_FREQUENCY),
				"the authoritative loaded coordinator must still publish the new primary frequency");
			assertRetainedReplicaRemainsRetired(retiredReplicaKey, retiredListener);
			helper.runAfterDelay(25, () -> {
				helper.assertTrue(level.getGameTime() - beforeCadence >= 20,
					"the retained-replica assertion must cross a real 20-tick topology cadence");
				assertRetainedReplicaRemainsRetired(retiredReplicaKey, retiredListener);
				onRetuned.run();
			});
		}

		private void assertRetainedReplicaRemainsRetired(RadioEndpoint.Key retiredReplicaKey,
			ExternalListenerService.Publication retiredListener) {
			helper.assertTrue(!FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
				retiredReplicaKey) && radioTopologyFrame().find(retiredReplicaKey).isEmpty()
				&& !FactoryOrchestrationServer.externalListenerRelayCandidateForTest(level.getServer(),
					retiredListener.relayEndpoint().key())
				&& !FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
					retiredListener.relayEndpoint().key())
				&& !radioReplica.production().hasIndexedShellMembership(),
				"retune must not revive a retained stale replica's router, frame, listener, or shell membership");
		}

		/** Rebinding a real Core re-drives both old and new Cluster-owned primary Computers. */
		private void assertCoreRebindDrivesBothPrimaryEndpoints(Runnable onSampled) {
			RadioEndpoint.Key oldPrimary = radioEndpointKey(computer, panel.clusterId());
			RadioEndpoint.Key newPrimary = radioTargetTopologyKey();
			RadioEndpoint beforeOld = Objects.requireNonNull(routerRadioEndpoint(oldPrimary),
				"old Cluster primary must be router-registered before Core rebind");
			RadioEndpoint beforeNew = Objects.requireNonNull(routerRadioEndpoint(newPrimary),
				"new Cluster primary must be router-registered before Core rebind");
			ClusterBinding beforeBinding = Objects.requireNonNull(core.bindingState(),
				"real Core must be bound before the rebind lifecycle test");
			core.commitClusterBinding(new ClusterBinding(radioOwnerPanel().clusterId(),
				Math.incrementExact(beforeBinding.revision()), new ClusterAuthority(ClusterMemberType.PANEL,
					radioOwnerPanel().memberId()), List.of(new LogisticsBinding(network))));
			RadioEndpoint afterOld = Objects.requireNonNull(routerRadioEndpoint(oldPrimary),
				"old Cluster primary must remain registered after Core rebind");
			RadioEndpoint afterNew = Objects.requireNonNull(routerRadioEndpoint(newPrimary),
				"new Cluster primary must remain registered after Core rebind");
			helper.assertTrue(afterOld.generation() > beforeOld.generation()
				&& afterNew.generation() > beforeNew.generation()
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), oldPrimary)
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), newPrimary),
				"Core A-to-B rebind must re-register both Cluster primary endpoints with new generations");
			// This fixture continues with an independent reload/teardown regression. Restore the real
			// Library binding with a newer revision, rather than leaving that regression coupled to a
			// deliberately cross-Cluster Core. Both restoration publications must remain fenced too.
			core.commitClusterBinding(new ClusterBinding(beforeBinding.clusterId(),
				Math.incrementExact(Math.incrementExact(beforeBinding.revision())), beforeBinding.authority(),
				beforeBinding.logisticsBindings()));
			RadioEndpoint restoredOld = Objects.requireNonNull(routerRadioEndpoint(oldPrimary),
				"restored old Cluster primary must remain router-registered");
			RadioEndpoint restoredNew = Objects.requireNonNull(routerRadioEndpoint(newPrimary),
				"restored new Cluster primary must remain router-registered");
			helper.assertTrue(restoredOld.generation() > afterOld.generation()
				&& restoredNew.generation() > afterNew.generation()
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), oldPrimary)
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), newPrimary),
				"Core B-to-A restoration must preserve both exact primary router registrations");
			long previousSampleTick = radioTopologyFrame().sampleTick();
			awaitNextRadioTopologyFrame(previousSampleTick, () ->
				radioTopologyFrame().find(oldPrimary).map(RadioTopologyFrame.Sample::loaded).orElse(false)
					&& radioTopologyFrame().find(newPrimary).map(RadioTopologyFrame.Sample::loaded)
						.orElse(false), () -> {
					helper.assertTrue(radioTopologyFrame().sample(oldPrimary).loaded()
						&& radioTopologyFrame().sample(newPrimary).loaded(),
						"Core A-to-B rebind must republish both primaries into the current frozen topology frame");
					onSampled.run();
				}, "Core rebind endpoints never entered a production post-tick topology frame");
		}

		private void assertProductionResolverRejectsReplacedComputerEvidence() {
			CompoundTag saved = radioTarget.saveWithFullMetadata(level.registryAccess());
			RadioEndpoint.Key key = radioTargetTopologyKey();
			helper.assertTrue(radioTopologyFrame().sample(key).loaded(),
				"a real observed Computer must remain current before its replacement is checked");
			UUID priorOwner = radioTarget.production().listenerOwnerToken();
			long priorGeneration = radioTarget.production().radioEndpointGeneration();
			long priorSample = radioTopologyFrame().sampleTick();
			// A server-side NBT reload is a new physical owner even when every durable value is
			// unchanged. Exercise an immediate ordinary unload before a tick can republish it.
			radioTarget.loadWithComponents(saved, level.registryAccess());
			FactoryOrchestrationServer.nativeComputerUnloaded(radioTarget.production());
			helper.assertFalse(FactoryOrchestrationServer.radioEndpointRegisteredForTest(
				level.getServer(), key), "NBT reload then unload retained previous owner="
					+ priorOwner + ", newOwner=" + radioTarget.production().listenerOwnerToken()
					+ ", key=" + key + ", generation=" + priorGeneration);
			var retiredAuthority = FactoryOrchestrationServer.radioAuthorityStateForTest(
				level.getServer()).endpoints().get(key);
			helper.assertTrue(retiredAuthority != null && retiredAuthority.current().isEmpty()
				&& retiredAuthority.highestGeneration() == priorGeneration,
				"NBT retirement must retain the exact previous generation as an anti-replay tombstone");
			FactoryOrchestrationServer.nativeComputerObserved(radioTarget.production());
			helper.assertTrue(radioTarget.production().radioEndpointGeneration() > priorGeneration,
				"new NBT owner must cross the prior owner's generation tombstone");
			awaitNextRadioTopologyFrame(priorSample, () -> radioTargetHasCurrentTopologySample(),
				() -> assertProductionResolverRejectsChangedEvidence(saved, key),
				"the reloaded exact owner never recovered its current topology sample");
		}

		private void assertProductionResolverRejectsChangedEvidence(CompoundTag saved,
			RadioEndpoint.Key key) {

			CompoundTag wrongIdentity = saved.copy();
			wrongIdentity.getCompound("ComputerData").putUUID("ComputerId", stable("wrong-radio-id"));
			RadioTopologyFrame beforeWrongIdentity = radioTopologyFrame();
			radioTarget.loadWithComponents(wrongIdentity, level.registryAccess());
			helper.assertTrue(beforeWrongIdentity.sample(key).loaded()
				&& radioTopologyFrame().find(key).isEmpty()
				&& !FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), key),
				"NBT owner replacement must retire current authority without mutating the old frozen frame");
			awaitNextRadioTopologyFrame(beforeWrongIdentity.sampleTick(), () ->
				radioTopologyFrame().find(key).isEmpty(), () ->
				continueProfileReplacementCheck(saved, key),
				"the production resolver did not fail closed for a wrong loaded BE identity");
		}

		private void continueProfileReplacementCheck(CompoundTag saved, RadioEndpoint.Key key) {
			helper.assertTrue(radioTopologyFrame().find(key).isEmpty(),
				"the production resolver must fail closed for a wrong loaded BE identity");
			CompoundTag profileMismatch = saved.copy();
			CompoundTag profile = profileMismatch.getCompound("ComputerData").getCompound("Profile");
			profile.putString("Profession", "NITWIT"); profile.putInt("ProcessSlots", 1);
			profile.put("Capabilities", new ListTag()); profile.putInt("ListenerRangeBonus", 0);
			long previousSampleTick = radioTopologyFrame().sampleTick();
			radioTarget.loadWithComponents(profileMismatch, level.registryAccess());
			awaitNextRadioTopologyFrame(previousSampleTick, () ->
				radioTopologyFrame().find(key).isEmpty(), () ->
				continueRestoredComputerEvidenceCheck(saved, key),
				"the production resolver did not reject a same-key profile replacement");
		}

		private void continueRestoredComputerEvidenceCheck(CompoundTag saved, RadioEndpoint.Key key) {
			helper.assertTrue(radioTopologyFrame().find(key).isEmpty(),
				"the production resolver must reject a same-key profile replacement");
			long previousSampleTick = radioTopologyFrame().sampleTick();
			radioTarget.loadWithComponents(saved, level.registryAccess());
			FactoryOrchestrationServer.nativeComputerObserved(radioTarget.production());
			awaitNextRadioTopologyFrame(previousSampleTick, () ->
				radioTopologyFrame().find(key).map(RadioTopologyFrame.Sample::loaded).orElse(false),
				() -> disassembleRestoredComputer(key),
				"restored live evidence never entered a production post-tick topology frame");
		}

		private void disassembleRestoredComputer(RadioEndpoint.Key key) {
			helper.assertTrue(radioTopologyFrame().sample(key).loaded(),
				"restored live evidence must be sampled before physical disassembly is checked");
			BlockPos shellFace = radioTargetMin().offset(0, 1, 1);
			level.setBlock(shellFace, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			RadioTopologyFrame disassembled = radioTopologyFrame();
			helper.assertTrue(disassembled.find(key).isEmpty(),
				"shell disassembly must immediately withdraw the physical radio endpoint");
			helper.runAfterDelay(45, () -> {
				helper.assertTrue(radioTopologyFrame().sampleTick() > disassembled.sampleTick()
					&& Math.floorMod(radioTopologyFrame().sampleTick(), 20) == 0
					&& radioTopologyFrame().find(key).isEmpty(),
					"a retired shell endpoint must stay absent across two cadence boundaries");
				helper.assertFalse(FactoryOrchestrationServer.radioEndpointRegisteredForTest(
					level.getServer(), key),
					"the retired shell endpoint must not be retained by the router");
				helper.assertTrue(FactoryOrchestrationServer.externalListenerProjectionForTest(
					level.getServer(), radioTarget.computerId().orElseThrow()).isEmpty(),
					"shell teardown must not resurrect a listener process from the old block entity");
				helper.succeed();
			});
		}

		private void awaitNextRadioTopologyFrame(long previousSampleTick, BooleanSupplier ready,
			Runnable success, String failure) {
			await(AWAIT, () -> Math.floorMod(level.getGameTime(), 20) == 0, () ->
				await(AWAIT, () -> {
					RadioTopologyFrame frame = radioTopologyFrame();
					return frame.sampleTick() > previousSampleTick
						&& frame.sampleTick() <= level.getGameTime()
						&& Math.floorMod(frame.sampleTick(), 20) == 0
						&& ready.getAsBoolean();
				}, success, failure),
				"the GameTest never reached a real 20-tick topology cadence");
		}

		private RadioTopologyFrame radioTopologyFrame() {
			return FactoryOrchestrationServer.radioTopologyFrame(level.getServer()).orElseThrow();
		}

		private RadioEndpoint.Key radioTargetTopologyKey() {
			return new RadioEndpoint.Key(radioOwnerPanel().clusterId(),
				radioTarget.computerId().orElseThrow());
		}

		private boolean radioTargetHasCurrentTopologySample() {
			RadioTopologyFrame.Sample sample = radioTopologyFrame().find(radioTargetTopologyKey())
				.orElse(null);
			return sample != null && sample.loaded();
		}

		/** The relay route is dispatched only after the actual 20-tick frozen frame contains all hops. */
		private boolean traderRelayTopologyReady() {
			if (radioTarget == null || radioRelay == null) return false;
			RadioTopologyFrame frame = FactoryOrchestrationServer.radioTopologyFrame(level.getServer())
				.orElse(null);
			if (frame == null) return false;
			return List.of(radioEndpointKey(computer, panel.clusterId()), radioTargetTopologyKey(),
				radioEndpointKey(radioRelay,
					biotechPanel(radioRelayMin().offset(0, 1, 0)).clusterId())).stream()
				.map(frame::find).allMatch(sample -> sample.map(RadioTopologyFrame.Sample::loaded)
					.orElse(false));
		}

		private static RadioEndpoint.Key radioEndpointKey(ComputerBlockEntity computer,
			UUID clusterId) {
			return new RadioEndpoint.Key(Objects.requireNonNull(clusterId, "cluster id"),
				Objects.requireNonNull(computer, "computer").computerId().orElseThrow());
		}

		private void configureCoreRouting(ServerPlayer player) {
			int libraryPriority = scenario == Scenario.PENDING_ADMISSION_RELOAD ? 9 : 0;
			helper.assertValueEqual(core.routingConfiguration().frequency(),
				CommunicationFrequency.of("7"),
				"the Library frequency must be frozen from the configured placement item");
			helper.assertValueEqual(core.routingConfiguration().priority(), libraryPriority,
				"the Library priority must be frozen from the configured placement item");
			await(AWAIT, this::productionReady, this::configureAndSubmit,
				"real Computer member and Library Pattern never became ready");
		}

		private boolean productionReady() {
			ClusterEngineState state = ProductionRuntimeServices.get(level.getServer())
				.snapshot(panel.clusterId()).orElse(null);
			return state != null && state.members().stream()
				.anyMatch(ClusterEngineState.MemberSnapshot::loaded)
				&& core.summary().indexedPatterns() == 1;
		}

		private void configureAndSubmit() {
			if (usesDurableScenarioCommunication())
				declareClusterPrimary(panel, durableScenarioFrequency());
			if (!usesAgent()) {
				if (scenario == Scenario.PENDING_ADMISSION_RELOAD) {
					await(800, () -> FactoryOrchestrationServer.productionLibraryRegistration(
						level.getServer(), core.memberId()).map(registration ->
							registration.catalog().loaded()).orElse(false), () -> {
							restartCompositionRoot();
							helper.assertFalse(FactoryOrchestrationServer.productionLibraryRegistration(
								level.getServer(), core.memberId()).orElseThrow().catalog().loaded(),
								"bootstrap must durably demote stale LOADED before directory visibility");
							unloadCoreForPendingAdmission();
							submitGoal();
						}, "the live Core did not publish LOADED before the crash boundary");
					return;
				}
				submitGoal();
				return;
			}
			configureEndpoint(inputEndpointPos, AgentEndpointRole.INPUT,
				scenario == Scenario.SEQUENCED_TWO_ADDRESS ? STAGE_A : INPUT, () -> {
				if (scenario == Scenario.SEQUENCED_TWO_ADDRESS)
					configureEndpoint(stageBEndpointPos, AgentEndpointRole.INPUT, STAGE_B,
						this::configureOutputAndSubmit);
				else if (panelScenario == PanelScenario.AGENT_NEW_TASK_DEFAULTS)
					configureEndpoint(stageBEndpointPos, agentId, AgentEndpointRole.INPUT, STAGE_B, 10,
						CommunicationFrequency.of("7"), AgentCompletionMode.TARGET, Optional.empty(),
						AgentEndpointState.OutputMode.WAREHOUSE_ALL, WAREHOUSE, this::configureOutputAndSubmit);
				else configureOutputAndSubmit();
			});
		}

		private void configureOutputAndSubmit() {
			configureEndpoint(outputEndpointPos, AgentEndpointRole.OUTPUT, OUTPUT, () ->
			{
				if (scenario == Scenario.RADIO_FREQUENCY_MISMATCH) {
					declareStandardUnconfiguredRole();
					helper.assertTrue(agentPanel().agentDiagnostics().stream()
						.noneMatch(agent -> agent.agentId().equals(agentId)),
						"a configured endpoint must not publish before its Cluster declares AGENT");
					submitGoal();
					return;
				}
				if (scenario == Scenario.REMOTE_COMPUTE) {
					configureRemoteCapacityAgent();
					return;
				}
				declareAgentRole(); await(800, () -> agentPanel().agentDiagnostics().stream()
				.anyMatch(agent -> agent.agentId().equals(agentId)), () -> {
					if (panelScenario == PanelScenario.DIAGNOSTICS_NORMAL
						|| panelScenario == PanelScenario.DIAGNOSTICS_ADVANCED
						|| panelScenario == PanelScenario.DIAGNOSTICS_TOOLTIP) {
						helper.runAfterDelay(25, this::exerciseDiagnostics); return;
					}
					if (scenario == Scenario.TRADER_RELAY) {
						await(200, this::remoteTraderAgentReady, this::submitGoal,
							this::traderRelayRouteDiagnostics);
						return;
					}
					if (isListenerRelayScenario()) {
						await(200, this::remoteListenerRelayAgentReady, this::submitGoal,
							this::listenerRelayRouteDiagnostics);
						return;
					}
					if (scenario == Scenario.SEQUENCED_TWO_ADDRESS) {
						reconfigureMultiEndpointPolicyAndSubmit();
						return;
					}
					if (scenario == Scenario.DISPATCH_RELOAD)
						helper.assertTrue(FactoryOrchestrationServer
							.suspendNextInputDispatchAtDurableAttempt(level.getServer(), panel.clusterId()),
							"production composition root must arm the durable ATTEMPTING boundary");
					if (scenario == Scenario.RECOVERY_BACKPRESSURE) {
						recoveryOccupancyBaseline = FactoryOrchestrationServer
							.recoveryCorrectnessOccupancy(level.getServer());
						helper.assertTrue(FactoryOrchestrationServer.constrainRecoveryAdmissionHeadroom(
							level.getServer(), panel.clusterId(), 1),
							"production recovery ledger must reserve one real admission slot");
					}
					submitGoal();
			}, "authoritative endpoint commands did not form the Agent"); });
		}


		/** Formed production objects, transformed native Gauge and real server packet handlers. */
		private void exerciseDiagnostics() {
			if (panelScenario == PanelScenario.DIAGNOSTICS_TOOLTIP && level.getGameTime() % 20 != 0) {
				helper.runAfterDelay(1, this::exerciseDiagnostics); return;
			}
			CompoundTag before = FactoryOrchestrationSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess());
			if (panelScenario == PanelScenario.DIAGNOSTICS_NORMAL) {
				for (BlockEntity be : List.of(computer, panel, core, inputEndpoint)) {
					helper.assertTrue(be instanceof FactoryGoggleInformation, "registered BE diagnostics: " + be.getClass());
					var info = (FactoryGoggleInformation) be;
					var live = info.normalGoggles();
					helper.assertFalse(live.rows().isEmpty(), "actual BE ticker must publish gameplay rows: " + be.getClass());
					var packet = be.getUpdateTag(level.registryAccess());
					helper.assertValueEqual(FactoryGoggleState.load(packet.getCompound(FactoryGoggleState.ROOT)).orElseThrow(), info.normalGoggles(),
						"normal state must cross actual BE update packet");
					List<Component> lines = new ArrayList<>();
					helper.assertTrue(((com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation) be).addToGoggleTooltip(lines, false), "actual Create tooltip entry point must return gameplay text");
					helper.assertFalse(lines.toString().contains(network.toString()) || lines.toString().contains(agentId.toString()), "normal tooltip must hide UUIDs");
				}
				var rows = ((FactoryGoggleInformation) inputEndpoint).normalGoggles().rows();
				helper.assertTrue(rows.stream().anyMatch(r -> r.key().equals("address") && r.values().contains("=agent-input")), "native endpoint must show actual configured address");
				helper.assertTrue(rows.stream().anyMatch(r -> r.key().equals("role") && r.values().contains("@INPUT")), "native Agent must not appear as ordinary Factory");
			} else if (panelScenario == PanelScenario.DIAGNOSTICS_TOOLTIP) {
				ItemStack tool = new ItemStack(CBItems.LOGISTICS_CONFIGURATION_TOOL.get());
				LogisticsToolData.write(tool, new LogisticsToolData(Optional.of(network)));
				ItemStack panelItem = panel.asConfiguredStack(level.registryAccess());
				for (ItemStack stack : List.of(tool, panelItem)) {
					stack.getItem().inventoryTick(stack, level, operator, 0, true);
					helper.assertTrue(BoundLogisticsTooltipData.read(stack).isPresent(), "registered item must provide directory snapshot");
					List<Component> actualTooltip = new ArrayList<>();
					stack.getItem().appendHoverText(stack, Item.TooltipContext.of(level), actualTooltip, net.minecraft.world.item.TooltipFlag.NORMAL);
					helper.assertTrue(actualTooltip.toString().contains("configured"), "registered item hover entry must expose configured state");
					helper.assertFalse(actualTooltip.toString().contains(network.toString()), "actual item hover must not expose network UUID");
					for (boolean shift : List.of(false, true)) {
						var lines = BoundLogisticsTooltip.fromStack(stack).build(List.of(network), shift, stack == panelItem);
						helper.assertFalse(lines.toString().contains(network.toString()), "normal and Shift tooltip must hide UUID");
						helper.assertFalse(lines.toString().contains("synchronizing"), "known directory binding must resolve readable name");
					}
				}
			} else exerciseAdvancedDiagnostics();
			if (panelScenario != PanelScenario.DIAGNOSTICS_ADVANCED)
				helper.assertValueEqual(FactoryOrchestrationSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess()), before, "diagnostics read must not change durable runtime");
			helper.assertValueEqual(nativeOrderCount, 0, "diagnostics must never dispatch Create packages");
			helper.succeed();
		}

		private void exerciseAdvancedDiagnostics() {
			var player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "diagnostic-observer"));
			List<AdvancedGoggleResponsePacket> replies = new ArrayList<>();
			player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
				new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
				net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
				@Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
					if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
						&& custom.payload() instanceof AdvancedGoggleResponsePacket response) replies.add(response);
				}
			};
			// Native fixture Gauge is floor-mounted; aim through its actual occupied shape.
			Vec3 aim = level.getBlockState(inputEndpointPos).getShape(level, inputEndpointPos)
				.toAabbs().getFirst().getCenter().add(Vec3.atLowerCornerOf(inputEndpointPos));
			Vec3 eye = aim.add(0, 2, 0);
			player.moveTo(eye.x, eye.y - player.getEyeHeight(), eye.z, 0, 90); player.setOldPosAndRot();
			var target = ((FactoryGoggleInformation) inputEndpoint).advancedGoggleMetadata().capture(inputEndpoint);
			helper.assertTrue(target != null, "native loaded target metadata");
			java.util.function.Consumer<FactoryGoggleTarget> request = value -> {
				var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
				try { CBPackets.roundTripPanelForTest(new AdvancedGoggleRequestPacket(value, 31), wire).handle(player); }
				finally { wire.release(); }
			};
			player.setShiftKeyDown(true); request.accept(target);
			helper.assertTrue(replies.isEmpty(), "missing Goggles denies");
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, com.simibubi.create.AllItems.GOGGLES.asStack());
			player.setShiftKeyDown(false); request.accept(target);
			helper.assertTrue(replies.isEmpty(), "not sneaking denies");
			player.setShiftKeyDown(true);
			request.accept(new FactoryGoggleTarget(target.address(), UUID.randomUUID(), target.generation(), target.incarnation()));
			request.accept(new FactoryGoggleTarget(target.address(), target.memberId(), target.generation() + 1, target.incarnation()));
			request.accept(new FactoryGoggleTarget(target.address(), target.memberId(), target.generation(), UUID.randomUUID()));
			request.accept(new FactoryGoggleTarget(new SpaceAddress(level.dimension(), UUID.randomUUID(), target.address().localPos()), target.memberId(), target.generation(), target.incarnation()));
			helper.assertTrue(replies.isEmpty(), "wrong identity/generation/incarnation or unresolved sublevel denies");
			player.setXRot(-90); player.setOldPosAndRot(); request.accept(target);
			helper.assertTrue(replies.isEmpty(), "looking away denies nearby target");
			player.moveTo(eye.x, eye.y + 10 - player.getEyeHeight(), eye.z, 0, 90); player.setOldPosAndRot(); request.accept(target);
			helper.assertTrue(replies.isEmpty(), "out-of-range denies");
			player.moveTo(eye.x, eye.y - player.getEyeHeight(), eye.z, 0, 90); player.setOldPosAndRot();
			helper.assertValueEqual(((BlockHitResult) player.pick(player.blockInteractionRange(), 1, false)).getBlockPos(), inputEndpointPos, "actual player ray must hit exact native endpoint");
			CompoundTag before = FactoryOrchestrationSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess());
			request.accept(target);
			helper.assertValueEqual(replies.size(), 1, "authorized ray must deliver one typed response");
			var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
			AdvancedGoggleResponsePacket response;
			try { response = CBPackets.roundTripPanelForTest(replies.getFirst(), wire); } finally { wire.release(); }
			helper.assertValueEqual(response.projection().target(), target, "response identifies exact component");
			helper.assertValueEqual(response.requestId(), 31L, "response echoes correlation id");
			helper.assertTrue(response.projection().collections().stream().mapToInt(List::size).sum() <= response.projection().rowLimit(), "single total row budget");
			helper.assertTrue(response.projection().collections().toString().contains(agentId.toString()), "advanced output retains full Agent identity");
			request.accept(target);
			helper.assertValueEqual(replies.size(), 1, "same-tick request is rate limited");
			helper.assertValueEqual(FactoryOrchestrationSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess()), before, "advanced reads cannot mutate durable runtime");
			level.removeBlock(inputEndpointPos, false); request.accept(target);
			helper.assertValueEqual(replies.size(), 1, "removed endpoint denies immediately");
		}

		private void configureRemoteCapacityAgent() {
			UUID capacityAgentId = stable("remote-capacity-agent");
			configureEndpoint(remoteCapacityInputPos(), capacityAgentId, AgentEndpointRole.INPUT,
				CAPACITY_INPUT, () -> configureEndpoint(remoteCapacityOutputPos(), capacityAgentId,
					AgentEndpointRole.OUTPUT, CAPACITY_OUTPUT, () -> {
						declareAgentRole();
						await(800, this::remoteAgentOwnerCapacityReserved, () -> {
							declareStandardUnconfiguredRole(remoteComputeReceiverPanel());
							FactoryOrchestrationServer.nativeComputerObserved(radioTarget.production());
							submitGoal();
						}, this::remoteAgentOwnerCapacityDiagnostics);
					}));
		}

		private String remoteAgentOwnerCapacityDiagnostics() {
			ClusterEngineState owner = ProductionRuntimeServices.get(level.getServer())
				.snapshot(radioOwnerPanel().clusterId()).orElse(null);
			return "the real Agent owner did not reserve both finite Alchemist slots: rowAgents="
				+ agentPanel().agentDiagnostics().stream().map(row -> row.agentId() + "/"
					+ row.residentProcessId()).toList() + ", ownerProcesses="
				+ (owner == null ? "missing" : owner.processes().values().stream().map(process ->
					process.goalId() + "/" + process.kind() + "/slot=" + process.owningSlot()).toList())
				+ ", resolution=" + FactoryOrchestrationServer.productionAgentResolution(
					level.getServer(), new CreateRouteKey(network, INPUT), panel.clusterId())
					.map(value -> value.getClass().getSimpleName());
		}

		private boolean remoteAgentOwnerCapacityReserved() {
			if (scenario != Scenario.REMOTE_COMPUTE) return false;
			UUID capacityAgentId = stable("remote-capacity-agent");
			var rows = agentPanel().agentDiagnostics();
			boolean bothPublished = rows.stream().anyMatch(row -> row.agentId().equals(agentId))
				&& rows.stream().anyMatch(row -> row.agentId().equals(capacityAgentId));
			ClusterEngineState owner = ProductionRuntimeServices.get(level.getServer())
				.snapshot(radioOwnerPanel().clusterId()).orElse(null);
			UUID ownerComputer = radioTarget.computerId().orElse(null);
			return bothPublished && owner != null && ownerComputer != null
				&& owner.member(ownerComputer) != null
				&& owner.member(ownerComputer).profile().processSlots() == 2
				&& FactoryOrchestrationServer.productionAgentResolution(level.getServer(),
					new CreateRouteKey(network, INPUT), panel.clusterId()).orElse(null)
					instanceof com.nobodiiiii.createbiotech.content.factorycluster.agent
						.AgentResolution.Unique
				&& owner.processes().values().stream().filter(process ->
					process.kind() == ProcessKind.AGENT
						&& process.owningComputerId().equals(ownerComputer)
						&& process.state()
							!= com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState.TERMINAL).count() == 2;
		}

		private void declareStandardUnconfiguredRole() {
			declareStandardUnconfiguredRole(agentPanel());
		}

		private void declareStandardUnconfiguredRole(FactoryPanelBlockEntity owner) {
			var authority = communicationAuthority(owner);
			var service = FactoryOrchestrationServer.communicationCommandService(level.getServer())
				.orElseThrow();
			var before = service.configuration(owner.clusterId());
			long revision = before.map(com.nobodiiiii.createbiotech.content.factorycluster.communication
				.ClusterCommunicationConfiguration::revision).orElse(0L);
			helper.assertValueEqual(service.changePrimary(authority,
				CommunicationFrequency.UNCONFIGURED, revision),
				com.nobodiiiii.createbiotech.content.factorycluster.communication
					.ClusterCommunicationCommandService.ChangeResult.NO_CHANGE,
				"the scenario must materialize the durable STANDARD + blank default");
			var configured = service.configuration(owner.clusterId()).orElseThrow();
			helper.assertValueEqual(configured.primaryFrequency(),
				CommunicationFrequency.UNCONFIGURED,
				"an unconfigured Cluster must retain its exact blank primary");
			helper.assertValueEqual(configured.role(),
				com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole.STANDARD,
				"an unconfigured Cluster must remain STANDARD");
		}

		private void declareAgentRole() {
			if (!usesAgent()) return;
			FactoryPanelBlockEntity owner = agentPanel();
			var authority = communicationAuthority(owner);
			var service = FactoryOrchestrationServer.communicationCommandService(level.getServer())
				.orElseThrow();
			final CommunicationFrequency primary = usesDurableScenarioCommunication()
				? durableScenarioFrequency() : testRadioFrequency;
			declareClusterPrimary(owner, primary);
			long revision = service.configuration(owner.clusterId()).orElseThrow().revision();
			helper.assertValueEqual(service.changeRole(authority,
			com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole.AGENT,
			revision), com.nobodiiiii.createbiotech.content.factorycluster.communication
				.ClusterCommunicationCommandService.ChangeResult.APPLIED,
			"GameTest formed endpoints must declare the Cluster Agent role");
		}

		private com.nobodiiiii.createbiotech.content.factorycluster.communication
			.ClusterCommunicationCommandService.CommandAuthority communicationAuthority(
				FactoryPanelBlockEntity owner) {
			return new com.nobodiiiii.createbiotech.content.factorycluster.communication
				.ClusterCommunicationCommandService.CommandAuthority(owner.clusterId(),
					ProductionRuntimeServices.get(level.getServer()).snapshot(owner.clusterId())
						.orElseThrow().epochId(), owner.bindingState().authority(), owner.memberId());
		}

		private void declareClusterPrimary(FactoryPanelBlockEntity owner,
			CommunicationFrequency primary) {
			var service = FactoryOrchestrationServer.communicationCommandService(level.getServer())
				.orElseThrow();
			var current = service.configuration(owner.clusterId());
			long revision = current.map(com.nobodiiiii.createbiotech.content.factorycluster.communication
				.ClusterCommunicationConfiguration::revision).orElse(0L);
			var expected = current.map(configuration -> configuration.primaryFrequency().equals(primary))
				.orElse(primary.equals(CommunicationFrequency.UNCONFIGURED))
				? com.nobodiiiii.createbiotech.content.factorycluster.communication
					.ClusterCommunicationCommandService.ChangeResult.NO_CHANGE
				: com.nobodiiiii.createbiotech.content.factorycluster.communication
					.ClusterCommunicationCommandService.ChangeResult.APPLIED;
			helper.assertValueEqual(service.changePrimary(communicationAuthority(owner), primary,
				revision), expected, "scenario Cluster primary declaration must persist");
			helper.assertValueEqual(service.configuration(owner.clusterId()).orElseThrow()
				.primaryFrequency(), primary,
				"scenario Cluster must expose its durable exact primary");
		}

		private CommunicationFrequency durableScenarioFrequency() {
			return switch (scenario) {
				case RADIO_FREQUENCY_MISMATCH -> MISMATCH_SCENARIO_FREQUENCY;
				case REMOTE_COMPUTE -> REMOTE_COMPUTE_SCENARIO_FREQUENCY;
				default -> throw new IllegalStateException(
					"scenario does not own a durable communication frequency: " + scenario);
			};
		}

		private boolean remoteTraderAgentReady() {
			// GameTest's scheduled ticks, rather than poll-driven fixture calls, must publish
			// the real lower-owner and Create endpoint facts before the relay may be used. The
			// Agent-role mutation can replace the source sample after the initial formation
			// frame, so it must also wait for the exact frozen two-hop path used by dispatch.
			return agentPanel().agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId))
				.anyMatch(agent -> agent.residentProcessId() != null)
				&& traderRelayRouteReady();
		}

		/** Configures a real Trader listener whose relay is the sole possible extended radio hop. */
		private void configureListenerRelayAndRoute(ServerPlayer player) {
			FactoryOrchestrationServer.nativeComputerObserved(radioRelay.production());
			await(AWAIT, () -> radioRelay.production().replaceExternalListeners(0,
				List.of(testRadioFrequency)) == ExternalListenerService.EditResult.APPLIED,
				() -> awaitListenerRelayTopology(player),
				() -> "formed relay Trader never accepted listener; availability="
					+ radioRelay.production().availabilityReason() + ", epoch=" + radioRelay.production().epoch());
		}

		private void awaitListenerRelayTopology(ServerPlayer player) {
			await(AWAIT, this::listenerRelayTopologyReady, () -> {
				listenerRelayPublication = FactoryOrchestrationServer.externalListenerPublicationsForTest(
					level.getServer(), radioRelay.computerId().orElseThrow()).getFirst();
				RadioEndpoint source = Objects.requireNonNull(routerRadioEndpoint(
					radioEndpointKey(computer, panel.clusterId())),
					"source Computer must publish its real router endpoint");
				RadioEndpoint target = Objects.requireNonNull(routerRadioEndpoint(radioTargetTopologyKey()),
					"target Computer must publish its real router endpoint");
				RadioEndpoint ordinaryRelay = Objects.requireNonNull(routerRadioEndpoint(radioEndpointKey(
					radioRelay, biotechPanel(radioRelayMin().offset(0, 1, 0)).clusterId())),
					"listener host must also expose its distinct ordinary Computer endpoint");
				helper.assertTrue(FactoryOrchestrationServer.radioLinkForTest(level.getServer(), source,
					target) != RadioLinkPolicy.LinkResult.RADIO,
					"the ordinary source and target must be directly out of radio range");
				helper.assertValueEqual(ordinaryRelay.frequency(), LISTENER_RELAY_HOST_FREQUENCY,
					"the host Computer endpoint must be excluded from the requester frequency");
				helper.assertValueEqual(FactoryOrchestrationServer.radioLinkForTest(level.getServer(), source,
					listenerRelayPublication.relayEndpoint()), RadioLinkPolicy.LinkResult.RADIO,
					"the active listener relay must be reachable from the source in one frozen frame");
				helper.assertValueEqual(FactoryOrchestrationServer.radioLinkForTest(level.getServer(),
					listenerRelayPublication.relayEndpoint(), target), RadioLinkPolicy.LinkResult.RADIO,
					"the active listener relay must be reachable from the target in one frozen frame");
				configureCoreRouting(player);
			}, this::listenerRelayRouteDiagnostics);
		}

		private boolean listenerRelayTopologyReady() {
			if (!traderRelayTopologyReady()) return false;
			List<ExternalListenerService.Publication> publications =
				FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
					radioRelay.computerId().orElseThrow());
			if (publications.size() != 1) return false;
			ExternalListenerService.Publication publication = publications.getFirst();
			if (!publication.relayEndpoint().frequency().equals(testRadioFrequency)) return false;
			// The listener publication is committed before the next 20-tick topology sample.
			// This polling predicate must wait for that shared frozen frame, not manufacture a
			// future sample or turn the normal pre-sample state into a GameTest exception.
			RadioTopologyFrame frame = FactoryOrchestrationServer.radioTopologyFrame(level.getServer())
				.orElse(null);
			if (frame == null) return false;
			return List.of(publication.endpoint().key(), publication.relayEndpoint().key()).stream()
				.map(frame::find).allMatch(sample -> sample.map(RadioTopologyFrame.Sample::loaded)
					.orElse(false))
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
					publication.endpoint().key())
				&& FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
					publication.relayEndpoint().key());
		}

		private boolean remoteListenerRelayAgentReady() {
			return agentPanel().agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId))
				.anyMatch(agent -> agent.residentProcessId() != null)
				&& listenerRelayTopologyReady();
		}

		private String listenerRelayRouteDiagnostics() {
			return "real automatic listener relay did not enter the shared topology lifecycle: "
				+ traderRelayRouteDiagnostics() + ", listenerPublications="
				+ FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
					radioRelay.computerId().orElseThrow());
		}

		private boolean traderRelayRouteReady() {
			if (!traderRelayTopologyReady()) return false;
			RadioEndpoint source = routerRadioEndpoint(radioEndpointKey(computer, panel.clusterId()));
			RadioEndpoint relay = routerRadioEndpoint(radioEndpointKey(radioRelay,
				biotechPanel(radioRelayMin().offset(0, 1, 0)).clusterId()));
			RadioEndpoint target = routerRadioEndpoint(radioTargetTopologyKey());
			if (source == null || relay == null || target == null) return false;
			return FactoryOrchestrationServer.radioLinkForTest(level.getServer(), source, relay)
				== RadioLinkPolicy.LinkResult.RADIO
				&& FactoryOrchestrationServer.radioLinkForTest(level.getServer(), relay, target)
					== RadioLinkPolicy.LinkResult.RADIO;
		}

		private RadioEndpoint routerRadioEndpoint(RadioEndpoint.Key key) {
			return FactoryOrchestrationServer.radioEndpointForTest(level.getServer(), key).orElse(null);
		}

		private String traderRelayRouteDiagnostics() {
			RadioTopologyFrame frame = radioTopologyFrame();
			RadioRouter.AuthorityState authority =
				FactoryOrchestrationServer.radioAuthorityStateForTest(level.getServer());
			RadioEndpoint.Key sourceKey = radioEndpointKey(computer, panel.clusterId());
			RadioEndpoint.Key relayKey = radioEndpointKey(radioRelay,
				biotechPanel(radioRelayMin().offset(0, 1, 0)).clusterId());
			RadioEndpoint.Key targetKey = radioTargetTopologyKey();
			RadioEndpoint source = routerRadioEndpoint(sourceKey);
			RadioEndpoint relay = routerRadioEndpoint(relayKey);
			RadioEndpoint target = routerRadioEndpoint(targetKey);
			return "formed remote Trader topology never produced its exact router path: source="
				+ source + ", relay=" + relay + ", target=" + target + ", sourceSample="
				+ frame.find(sourceKey).orElse(null) + ", relaySample=" + frame.find(relayKey).orElse(null)
				+ ", targetSample=" + frame.find(targetKey).orElse(null) + ", registered="
				+ FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), sourceKey)
				+ "/" + FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), relayKey)
				+ "/" + FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(), targetKey)
				+ ", authority=" + authority.endpoints().size() + "/"
				+ FactoryRuntimeConfig.fromServerConfig().recoveryLedgerCapacity()
				+ ", sourceAuthority=" + authority.endpoints().get(sourceKey)
				+ ", relayAuthority=" + authority.endpoints().get(relayKey)
				+ ", targetAuthority=" + authority.endpoints().get(targetKey)
				+ ", links="
				+ routeLink(source, relay) + "/" + routeLink(relay, target)
				+ ", sourceState=" + radioLifecycleSummary(computer)
				+ ", relayState=" + radioLifecycleSummary(radioRelay)
				+ ", targetState=" + radioLifecycleSummary(radioTarget);
		}

		private static String radioLifecycleSummary(ComputerBlockEntity computer) {
			return "{authoritative=" + computer.isAuthoritativeLoadedInstance()
				+ ", epoch=" + computer.production().epoch().map(epoch -> epoch.groupId().toString()).orElse("none")
				+ ", profile=" + computer.production().installedProfile().map(profile -> profile.profession().toString())
					.orElse("none")
				+ ", availability=" + computer.production().availabilityReason()
				+ ", online=" + computer.production().structureOnline()
				+ ", radioEligible=" + computer.production().radioTopologyEligible()
				+ ", shellIndexed=" + computer.production().hasIndexedShellMembership()
				+ ", endpointGeneration=" + computer.production().radioEndpointGeneration() + "}";
		}

		private RadioLinkPolicy.LinkResult routeLink(RadioEndpoint source, RadioEndpoint target) {
			return source == null || target == null ? RadioLinkPolicy.LinkResult.UNAVAILABLE
				: FactoryOrchestrationServer.radioLinkForTest(level.getServer(), source, target);
		}

		private FactoryPanelBlockEntity agentPanel() { return isRadio() ? radioOwnerPanel() : panel; }

		private void reconfigureMultiEndpointPolicyAndSubmit() {
			long generation = panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow()
				.resetGeneration();
			configureEndpoint(inputEndpointPos, agentId, AgentEndpointRole.INPUT, STAGE_A, 9,
				CommunicationFrequency.of("7"), AgentCompletionMode.TARGET, Optional.empty(),
				AgentEndpointState.OutputMode.WAREHOUSE_ALL, WAREHOUSE, () -> {});
			await(800, () -> {
				var access = (com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess)
					Objects.requireNonNull(level.getBlockEntity(inputEndpointPos));
				return access.createBiotech$getAgentConfiguration()
					.filter(configuration -> configuration.routePriority() == 9
						&& configuration.agentGeneration() == generation).isPresent()
					&& panel.agentDiagnostics().stream()
						.filter(agent -> agent.agentId().equals(agentId))
						.anyMatch(agent -> agent.resetGeneration() == generation);
			}, this::submitGoal,
				"multi-endpoint policy edit advanced membership generation or broke formation");
		}

		/** Hold a real preceding query so the actual admission query uses the CLERIC instead of the one-slot source. */
		private void positionLocalSourceWithOrdinarySearch() {
			var runtime = ProductionRuntimeServices.get(level.getServer());
			StackKey absent = new StackKey(new ItemStack(Items.DIAMOND));
			await(AWAIT, () -> {
				runtime.selectProductionPattern(panel.clusterId(), network, absent);
				var search = runtime.productionSearchSnapshot(panel.clusterId(), network, absent).orElse(null);
				var state = runtime.snapshotForServerMutation(panel.clusterId()).orElse(null);
				if (search == null || state == null) return false;
				boolean pinned = state.processes().values().stream().anyMatch(process -> process.kind() == ProcessKind.LIBRARY_QUERY
					&& process.goalId().equals(search.searchId()) && process.owningComputerId().equals(computer.computerId().orElseThrow()));
				if (pinned) localPreflightSearchId = search.searchId();
				return pinned;
			}, () -> {
				localPreflightQueryComplete = true;
				System.out.println("LOCAL_EXPANSION_NATIVE ordinary preceding query pinned on real NITWIT: " + localPreflightSearchId);
				submitGoal();
			}, "ordinary preceding Library query never occupied the NITWIT source slot");
		}

		private void releasePrecedingQueryAfterActualAdmissionQueryPlacement() {
			var runtime = ProductionRuntimeServices.get(level.getServer());
			await(AWAIT, () -> {
				var search = runtime.productionSearchSnapshot(panel.clusterId(), network, new StackKey(requestedOutputStack())).orElse(null);
				var state = runtime.snapshotForServerMutation(panel.clusterId()).orElse(null);
				return search != null && state != null && state.processes().values().stream().anyMatch(process ->
					process.kind() == ProcessKind.LIBRARY_QUERY && process.goalId().equals(search.searchId())
					&& process.owningComputerId().equals(radioReplica.computerId().orElseThrow()));
			}, () -> {
				var state = runtime.snapshotForServerMutation(panel.clusterId()).orElseThrow();
				var receiver = state.member(radioReplica.computerId().orElseThrow());
				helper.assertTrue(receiver != null && receiver.loaded(), "actual CLERIC receiver is loaded before real admission");
				helper.assertValueEqual(receiver.profile().profession(), ProductionProfession.CLERIC, "preceding search cannot substitute the receiver hardware");
				helper.assertValueEqual(receiver.profile().processSlots(), 2, "real level-1 CLERIC has two production slots");
				var occupied = state.processes().values().stream().filter(process -> process.owningComputerId().equals(receiver.computerId()) && process.state() != ProcessState.TERMINAL).toList();
				helper.assertValueEqual(occupied.size(), 2, "real AGENT plus actual Panel admission query occupy both receiver slots");
				helper.assertTrue(occupied.stream().anyMatch(process -> process.kind() == ProcessKind.AGENT)
					&& occupied.stream().anyMatch(process -> process.kind() == ProcessKind.LIBRARY_QUERY), "ordinary allocator preconditions suppress a spare speculative receiver slot");
				System.out.println("LOCAL_EXPANSION_NATIVE admitted receiver preconditions: CLERIC slots=2, AGENT + LIBRARY_QUERY");
				await(AWAIT, () -> runtime.retireProductionSearch(localPreflightSearchId), () -> {
				System.out.println("LOCAL_EXPANSION_NATIVE ordinary preceding query retired after actual admission query placed on CLERIC");
				}, "ordinary preceding query failed exact release");
			}, "actual Panel admission query never allocated on the CLERIC");
		}

		private void submitGoal() {
			if (scenario == Scenario.LOCAL_PURE_EXPANSION && !localPreflightQueryComplete) {
				positionLocalSourceWithOrdinarySearch();
				return;
			}
			InputMode mode = scenario == Scenario.EXACT_TWO ? InputMode.EXACT : InputMode.BULK;
			long multiplier = scenario == Scenario.EXACT_TWO || scenario == Scenario.REMOTE_COMPUTE
				? 2 : 1;
			if (scenario == Scenario.PENDING_ADMISSION_RELOAD) mode = InputMode.PARTIAL;
			int priority = scenario == Scenario.PENDING_ADMISSION_RELOAD ? 13 : 0;
			StackKey requestedOutput = new StackKey(requestedOutputStack());
			if (scenario == Scenario.PENDING_ADMISSION_RELOAD) {
				submissionAttempts++;
				helper.assertTrue(sendPanelSubmit(requestedOutput, WAREHOUSE, multiplier, mode, priority),
					"one production Submit must durably accept asynchronous admission");
				helper.assertValueEqual(submissionAttempts, 1,
					"the player command must cross the production handler exactly once");
				var pending = FactoryOrchestrationServer.pendingProductionAdmissions(
					level.getServer(), panel.clusterId());
				helper.assertValueEqual(pending.size(), 1,
					"one click must create one durable pending admission");
				var request = pending.getFirst();
				var library = FactoryOrchestrationServer.productionLibraryRegistration(
					level.getServer(), core.memberId()).orElseThrow();
				helper.assertValueEqual(library.frequency(), CommunicationFrequency.of("7"),
					"production directory must use the player-configured Core frequency");
				helper.assertValueEqual(library.priority(), 9,
					"production directory must use the player-configured Core priority");
				helper.assertFalse(library.catalog().loaded(),
					"an unloaded Core must remain a durable known Library rather than disappear");
				helper.assertValueEqual(request.requestedOutput(), requestedOutput,
					"pending admission must retain exact output identity");
				helper.assertValueEqual(request.deliveryAddress(), WAREHOUSE,
					"pending admission must retain delivery");
				helper.assertValueEqual(request.mode(), InputMode.PARTIAL,
					"pending admission must retain mode");
				helper.assertValueEqual(request.priority(), 13,
					"pending admission must retain priority");
				await(800, () -> ProductionRuntimeServices.get(level.getServer())
					.productionSearchSnapshot(panel.clusterId(), network,
						new StackKey(requestedOutputStack()))
					.map(snapshot -> snapshot.status()
						== com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine
							.FactoryRuntimeService.ProductionSearchStatus.WAITING_LIBRARY)
					.orElse(false), () -> {
						restartCompositionRoot();
						var reloadedKnown = FactoryOrchestrationServer.productionLibraryRegistration(
							level.getServer(), core.memberId()).orElseThrow();
						helper.assertFalse(reloadedKnown.catalog().loaded(),
							"composition-root reload must retain the known-unloaded Library registration");
						reloadPendingAdmissionCore();
						await(1_000, () -> panel.goalDiagnostics().stream()
							.anyMatch(goal -> goal.state() != GoalState.TERMINAL), () -> {
								ClusterEngineState admitted = ProductionRuntimeServices.get(
									level.getServer()).snapshot(panel.clusterId()).orElseThrow();
								var goal = admitted.goals().values().stream()
									.filter(candidate -> candidate.state() != GoalState.TERMINAL)
									.findFirst().orElseThrow();
								helper.assertValueEqual(goal.submission().inputMode(), InputMode.PARTIAL,
									"reload must admit the original pending mode");
								helper.assertValueEqual(goal.policy().priority(), 13,
									"reload must admit the original pending priority");
								goalAdmitted();
							}, this::admissionDiagnostics);
					}, "known-unloaded Library did not hold admission at WAITING_LIBRARY");
				return;
			}
			submissionAttempts++;
			helper.assertTrue(sendPanelSubmit(requestedOutput, WAREHOUSE, multiplier, mode, priority),
				"one production Submit must durably accept asynchronous admission; rejection="
					+ FactoryOrchestrationServer.lastPanelSubmissionDiagnostic(level.getServer(),
						panel.clusterId()).orElse("NONE"));
			helper.assertValueEqual(submissionAttempts, 1,
				"the player command must cross the production handler exactly once");
			if (scenario == Scenario.LOCAL_PURE_EXPANSION) releasePrecedingQueryAfterActualAdmissionQueryPlacement();
			await(1_000, () -> panel.goalDiagnostics().stream()
				.anyMatch(goal -> goal.state() != GoalState.TERMINAL), this::goalAdmitted,
				this::admissionDiagnostics);
		}

		private String admissionDiagnostics() {
			ClusterEngineState state = ProductionRuntimeServices.get(level.getServer())
				.snapshot(panel.clusterId()).orElse(null);
			ClusterEngineState panelView = ProductionRuntimeServices.get(level.getServer())
				.snapshot(panel.clusterId()).orElse(null);
			var search = ProductionRuntimeServices.get(level.getServer())
				.productionSearchSnapshot(panel.clusterId(), network,
					new StackKey(requestedOutputStack()));
			return "production Panel must select the real Library Pattern and admit the Goal; panel="
				+ (panelView == null ? "missing" : panelView.epochId() + "/"
					+ panelView.generation()) + "; "
				+ "state=" + (state == null ? "none" : "epoch=" + state.epochId()
					+ ", generation=" + state.generation()
					+ ", goals=" + state.goals().size() + ", processes=" + state.processes().size()
					+ ", pending=" + state.pendingIntents().size())
				+ ", workerInFlight=" + ProductionRuntimeServices.get(level.getServer())
					.workerInFlight(panel.clusterId()) + ", admission="
				+ ProductionRuntimeServices.get(level.getServer()).panelAdmissionStatus(
					panel.clusterId(), panelView == null ? new UUID(0, 0) : panelView.epochId(),
					panelView == null ? 0 : panelView.generation())
				+ ", submitDiagnostic=" + FactoryOrchestrationServer.lastPanelSubmissionDiagnostic(
					level.getServer(), panel.clusterId()).orElse("NONE")
				+ ", search=" + search.map(snapshot -> snapshot.status() + "/"
					+ snapshot.selectedPatternId()).orElse("NONE")
				+ ", links=" + LogisticallyLinkedBehaviour.getAllPresent(network, false).size()
				+ ", iron=" + LogisticsManager.getSummaryOfNetwork(network, false)
					.getCountOf(new ItemStack(Items.IRON_INGOT));
		}

		private void goalAdmitted() {
			FactoryOrchestrationServer.GoalDiagnostic goal = panel.goalDiagnostics().stream()
				.filter(row -> row.state() != GoalState.TERMINAL).findFirst().orElseThrow();
			helper.assertValueEqual(goal.provider().libraryId(), core.memberId(),
				"the production Panel row must expose the selected Library");
			helper.assertFalse(goal.provider().key().isBlank(),
				"the production Panel row must expose the exact selected provider");
			if (scenario == Scenario.PENDING_ADMISSION_RELOAD)
				helper.assertValueEqual(goal.provider().effectiveRoutePriority(), 9,
					"the reloaded production row must retain effective route priority");
			goalId = goal.goalId();
			if (scenario == Scenario.LOCAL_PURE_EXPANSION)
				helper.assertValueEqual(submissionAttempts, 1, "local expansion uses exactly one actual Panel admission command");
			if (usesAgent() && scenario != Scenario.RADIO_FREQUENCY_MISMATCH)
				helper.assertValueEqual(goal.provider().kind(), RouteCandidate.ProviderKind.AGENT,
					"an Agent-owned route must freeze the exact Agent provider");
			if (scenario == Scenario.REMOTE_COMPUTE)
				helper.assertTrue(goal.provider().key().startsWith("agent/" + agentId + "/"),
					"remote computation must retain the intended material Agent identity");
			if (scenario == Scenario.RADIO_FREQUENCY_MISMATCH) {
				helper.assertValueEqual(goal.provider().kind(), RouteCandidate.ProviderKind.PATTERN,
					"an undeclared Agent must not replace the Pattern provider");
				helper.assertTrue(agentPanel().agentDiagnostics().stream()
					.noneMatch(agent -> agent.agentId().equals(agentId)),
					"an undeclared Agent must remain absent from production publication");
			}
			if (scenario == Scenario.LOST_ACK)
				helper.assertTrue(FactoryOrchestrationServer.dropNextRecoveryAcknowledgement(
					level.getServer(), goalId),
					"LOST_ACK must arm the production durable outbox fault boundary");
			if (!goal.processes().isEmpty()) {
				var detail = goal.processes().getFirst();
				goalProcessId = detail.processId();
				helper.assertTrue(detail.kind() != null && detail.state() != null,
					"the separated process diagnostic must expose runtime-owned state");
			}
			if (panelScenario == PanelScenario.MONITOR_GOAL_TREE) {
				await(AWAIT, this::hasLiveGoalProcess, () -> {
					helper.assertTrue(exercisePanelAcceptance(),
						"the live Monitor handler must deliver its typed response");
					exerciseMonitorInventoryChange((FactoryPanelMenu) operator.containerMenu);
				}, () -> "Monitor acceptance never observed a live process from the admitted Goal: "
					+ productionDiagnostics(sourcePackager));
				return;
			}
			if (scenario == Scenario.BARE_STANDARD) {
				await(AWAIT, this::hasLiveGoalProcess, () -> {
					var response = monitorGoal(openProductionPanelMenu(), goalId);
					helper.assertTrue(response.projection().goalTree().orElseThrow().nodes().stream()
						.anyMatch(node -> node.kind() == GoalTreeProjection.TreeNodeKind.CREATE_ADDRESS
							&& node.createAddress().filter(SpaceAddress.capture(level, inputEndpointPos)::equals).isPresent()),
						"bare Create provider must expose its actual execution endpoint in Monitor");
					continueAfterGoalAdmitted();
				}, "bare Monitor never acquired a live production process");
				return;
			}
			if (panelScenario == PanelScenario.AGENT_NEW_TASK_DEFAULTS) {
				await(AWAIT, this::exercisePanelAcceptance, this::continueAfterGoalAdmitted,
					"New Task validation never obtained a stable exact publication");
				return;
			}
			if (panelScenario != null && panelScenario != PanelScenario.NETWORK_COLLAPSES_SUBNET)
				helper.assertTrue(exercisePanelAcceptance(),
					"the live Panel handler must deliver its typed response");
			continueAfterGoalAdmitted();
		}

		private boolean hasLiveGoalProcess() {
			return ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId())
				.map(state -> state.processes().values().stream()
					.anyMatch(process -> process.goalId().equals(goalId)))
				.orElse(false);
		}

		private ComputerMonitorResponsePacket monitorGoal(FactoryPanelMenu menu, UUID selectedGoal) {
			var state = ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId()).orElseThrow();
			var process = state.processes().values().stream().filter(candidate -> candidate.goalId().equals(selectedGoal))
				.sorted(java.util.Comparator.comparing(candidate -> candidate.processId().toString())).findFirst().orElseThrow();
			int limit = Math.min(32, menu.rowLimit());
			var request = new ComputerMonitorViewRequest(ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE,
				Optional.of(process.owningComputerId()), Optional.of(process.processId()),
				new ComputerMonitorViewRequest.Viewport(0, 0), new ComputerMonitorViewRequest.Viewport(0, 0),
				new ComputerMonitorViewRequest.Viewport(0, limit), false);
			var response = (ComputerMonitorResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(
				menu.opening(), FactoryPanelView.COMPUTER_MONITOR, java.util.OptionalLong.empty(), 0, limit,
				Optional.of(process.processId()), 845, Optional.of(request))).orElseThrow();
			helper.assertTrue(CBPackets.takePanelDeliveryForTest(operator).isEmpty(), "one Monitor delivery");
			return CBPackets.roundTripPanelForTest(response, new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()));
		}

		private void exerciseMonitorInventoryChange(FactoryPanelMenu menu) {
			var before = monitorGoal(menu, goalId);
			ItemStack named = container(sourceChest).getItem(2).copyWithCount(23);
			helper.assertTrue(named.is(Items.DIAMOND), "fixture exact named diamond slot");
			container(sourceChest).setItem(2, named);
			container(sourceChest).setChanged();
			await(AWAIT, () -> LogisticsManager.getSummaryOfNetwork(network, false).getStacks().stream()
				.anyMatch(entry -> ItemStack.isSameItemSameComponents(entry.stack, named) && entry.count == 23), () -> {
				var after = monitorGoal(menu, goalId);
				helper.assertTrue(operator.containerMenu == menu && before.opening().equals(after.opening()),
					"inventory change must retain the exact live menu and source owner");
				helper.assertTrue(after.projection().revision() > before.projection().revision(), "actual stock change advances Monitor revision");
				helper.assertValueEqual(after.projection().epochId(), before.projection().epochId(), "same live source epoch");
				helper.assertTrue(after.projection().goalTree().orElseThrow().nodes().stream().anyMatch(node -> node.inventoryItem()
					.filter(new StackKey(named)::equals).isPresent() && node.count() == 23), "Monitor must show changed exact stock 23");
				continueAfterGoalAdmitted();
			}, "exact stock update did not reach Create summary");
		}

		private void continueAfterGoalAdmitted() {
			switch (scenario) {
				case KNOWN_OFFLINE_AGENT -> {
					FactoryOrchestrationServer.nativePanelUnloaded(inputEndpoint);
					level.removeBlock(inputEndpointPos, false);
					helper.runAfterDelay(2, this::knownOffline);
				}
				case EXACT_TWO -> exact(0);
				case SEQUENCED_TWO_ADDRESS -> sequenced();
				case REMOTE_COMPUTE -> remoteComputeRouteWait();
				case LOCAL_PURE_EXPANSION -> localPureExpansion();
				case COMPONENT_OUTPUT_RELOAD -> componentOutput();
				case EXTERNAL_LISTENER -> exerciseExternalListenerIngressAndSubnetProjection();
				case LOCAL_STANDARD_AGENT, CANCEL_DRAIN, LOST_ACK, RECOVERY_BACKPRESSURE,
					LOCAL_FORCE_RESET, RADIO_ONE_HOP, TRADER_RELAY, LISTENER_RELAY,
					ORDERLY_RADIO_RESTART, SIGNAL_COMPLETION -> agent();
				case DISPATCH_RELOAD -> dispatchReload();
				case RADIO_FREQUENCY_MISMATCH -> awaitGoal(GoalState.WAITING, WaitReason.ROUTE,
					() -> noDuplicate(0, helper::succeed));
				default -> bare();
			}
		}

		private void bare() {
			awaitProductionDispatch(sourcePackager, INPUT, inputPackager, () -> {
				put(warehouseChest, Items.GOLD_INGOT, 1);
				awaitGoal(GoalState.TERMINAL, null, () -> {
					assertGoalCounts(1, 1, 0);
					helper.assertValueEqual(count(inputChest, Items.IRON_INGOT), 1L,
						"bare path must unpack one real input package");
					helper.assertValueEqual(count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline,
						1L, "only post-admission output stock may credit the Goal");
					noDuplicate(1, this::afterProduction);
				});
			});
		}

		private void localPureExpansion() {
			await(AWAIT, () -> FactoryOrchestrationServer.productionLocalComputationJob(level.getServer(), goalId).isPresent(), () -> {
				var job = FactoryOrchestrationServer.productionLocalComputationJob(level.getServer(), goalId).orElseThrow();
				localExpansionRunId = job.runId();
				var runtime = ProductionRuntimeServices.get(level.getServer());
				var state = runtime.snapshotForServerMutation(panel.clusterId()).orElseThrow();
				var source = state.processes().get(job.lease().sourceProcessId());
				helper.assertTrue(source != null, "native tick must retain its actual admitted source process");
				helper.assertValueEqual(source.kind(), ProcessKind.GOAL, "the actual local expansion parent is the admitted Goal process");
				helper.assertValueEqual(source.owningComputerId(), computer.computerId().orElseThrow(), "actual Goal must be admitted on the real NITWIT source, not inferred from placement");
				helper.assertValueEqual(source.owningComputer(), computer.production().profile(), "actual source must retain its real installed production profile");
				helper.assertValueEqual(source.owningComputer().profession(), ProductionProfession.NITWIT, "source profession is NITWIT");
				helper.assertValueEqual(source.owningComputer().processSlots(), 1, "actual NITWIT source has one production slot");
				helper.assertValueEqual(job.receiverMemberId(), radioReplica.computerId().orElseThrow(), "local pure work belongs to the actual same-shell CLERIC");
				var process = state.processes().get(job.processId());
				helper.assertTrue(process != null, "observe actual receiver allocation before release");
				helper.assertValueEqual(process.owningComputerId(), job.receiverMemberId(), "actual process belongs to the receiver pinned by the durable job");
				helper.assertValueEqual(process.kind(), ProcessKind.PURE_EXPANSION, "real receiver slot is PURE_EXPANSION, not a radio listener");
				helper.assertValueEqual(process.owningSlot(), job.owningSlot(), "durable job owns the exact actual receiver slot");
				helper.assertValueEqual(process.owningComputer().profession(), ProductionProfession.CLERIC, "actual receiver profession is CLERIC");
				helper.assertValueEqual(process.owningComputer().processSlots(), 2, "actual receiver pure-work reservation uses its real two-slot profile");
				helper.assertValueEqual(process.owningComputer(), radioReplica.production().profile(), "receiver allocation uses its actual CLERIC profile");
				helper.assertTrue(process.owningComputer().supports(ProductionCapability.ALCHEMIST), "receiver is an actual alchemist");
				helper.assertTrue(runtime.ensureRemoteComputationProcess(job.processId(), job.lease(), job.receiverMemberId(), job.owningSlot()), "real runtime confirms exact slot and lease fence");
				helper.assertValueEqual(job.lease().sourceClusterId(), job.lease().receiverClusterId(), "same shell lease never crosses a radio boundary");
				assertNoLocalRadio(state);
				var before = FactoryOrchestrationServer.productionFrozenPlan(level.getServer(), goalId).orElseThrow();
				if (job.phase() != ProductionLocalExpansion.Phase.COMPLETED) {
					var status = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId).orElseThrow();
					helper.assertFalse(status.dispatchSubmitted() || status.inputPhase().isPresent(), "actual material input cannot dispatch before the durable local result");
				}
				System.out.println("LOCAL_EXPANSION_NATIVE before restart: phase=" + job.phase() + ", run=" + job.runId() + ", lease=" + job.lease().leaseId() + ", source=" + source.owningComputerId() + ", receiver=" + job.receiverMemberId() + ", slot=" + job.owningSlot() + ", sourceProfile=" + source.owningComputer() + ", receiverProfile=" + process.owningComputer());
				restartCompositionRoot();
				var recovered = FactoryOrchestrationServer.productionLocalComputationJob(level.getServer(), goalId).orElseThrow();
				helper.assertValueEqual(recovered, job, "actual host reconstruction retains identical durable run, lease, process and revision/result evidence");
				helper.assertValueEqual(FactoryOrchestrationServer.productionFrozenPlan(level.getServer(), goalId).orElseThrow(), before, "actual composed recovery retains the matching durable plan");
				System.out.println("LOCAL_EXPANSION_NATIVE recovered exact phase=" + recovered.phase());
				await(AWAIT, () -> FactoryOrchestrationServer.productionLocalComputationJob(level.getServer(), goalId)
					.map(value -> value.phase() == ProductionLocalExpansion.Phase.COMPLETED).orElse(false), () -> {
					var completed = FactoryOrchestrationServer.productionLocalComputationJob(level.getServer(), goalId).orElseThrow();
					var plan = FactoryOrchestrationServer.productionFrozenPlan(level.getServer(), goalId).orElseThrow();
					helper.assertTrue(plan.remoteExpansionMaterialReady(), "durable local revision/result must open the actual material gate");
					helper.assertValueEqual(plan.remoteExpansion().orElseThrow().result(), completed.result(), "plan and owner publish identical durable pure result");
					System.out.println("LOCAL_EXPANSION_NATIVE durable material ready: run=" + completed.runId());
					awaitAgentInput();
				}, "recovered actual local pure work never opened the material gate");
			}, () -> "native tick did not create a same-shell local job; " + admissionDiagnostics() + "; actualProcesses=" + ProductionRuntimeServices.get(level.getServer()).snapshotForServerMutation(panel.clusterId()).map(ClusterEngineState::processes));
		}

		private void assertNoLocalRadio(ClusterEngineState state) {
			helper.assertTrue(state.processes().values().stream().noneMatch(process -> process.kind() == ProcessKind.REMOTE_LISTENER || process.kind() == ProcessKind.RADIO_LISTENER), "same-shell pure expansion has no radio listener allocation");
			helper.assertTrue(FactoryOrchestrationServer.productionRemoteComputation(level.getServer(), goalId).isEmpty(), "local-first expansion creates no radio exchange");
			helper.assertTrue(FactoryOrchestrationServer.productionRemoteComputationJob(level.getServer(), goalId).isEmpty(), "local expansion creates no remote receiver job");
		}

		private void agent() {
			if (scenario == Scenario.TRADER_RELAY) {
				await(AWAIT, () -> FactoryOrchestrationServer.remoteAgentRadioProgressedPastFirstRelay(
					level.getServer(), goalId),
					() -> {
						restartCompositionRootAtFrozenRadioHop();
						awaitAgentInput();
					}, () -> "Trader route never advanced past its first relay hop in the durable outbox: "
						+ productionDiagnostics(sourcePackager));
				return;
			}
			if (isListenerRelayScenario()) {
				await(AWAIT, () -> FactoryOrchestrationServer.remoteAgentRadioProgressedPastFirstRelay(
					level.getServer(), goalId), () -> {
					helper.assertValueEqual(FactoryOrchestrationServer.remoteAgentCurrentRelayForTest(
						level.getServer(), goalId).orElseThrow(),
						listenerRelayPublication.relayEndpoint().key(),
						"the frozen production outbox/router first hop must be the active listener relay");
					if (scenario == Scenario.ORDERLY_RADIO_RESTART) orderlyRestartPendingRadioRoute();
					else awaitAgentInput();
				},
					() -> "the production Agent dispatch never crossed its automatic listener relay: "
						+ productionDiagnostics(sourcePackager));
				return;
			}
			awaitAgentInput();
		}

		/** Real block interaction -> menu -> typed wire codec -> handler -> projector -> delivery. */
		private boolean exercisePanelAcceptance() {
			PanelScenario exact = Objects.requireNonNull(panelScenario, "panel scenario");
			FactoryPanelMenu menu = openProductionPanelMenu();
			int limit = Math.min(32, menu.rowLimit());
			long intent = 700L + exact.ordinal();
			FactoryPanelViewRequestPacket request = switch (exact) {
				case REQUESTS_ONE_VIEW -> new FactoryPanelViewRequestPacket(menu.opening(),
					FactoryPanelView.TASKS, java.util.OptionalLong.empty(), 0, limit,
					Optional.of(goalId), intent);
				case MONITOR_GOAL_TREE -> {
					var state = ProductionRuntimeServices.get(level.getServer())
						.snapshot(panel.clusterId()).orElseThrow();
					var process = state.processes().values().stream()
						.filter(candidate -> candidate.goalId().equals(goalId))
						.sorted(java.util.Comparator.comparing(candidate -> candidate.processId().toString()))
						.findFirst().orElseThrow(() -> new IllegalStateException(
							"Monitor acceptance requires one live Goal process"));
					goalProcessId = process.processId();
					ComputerMonitorViewRequest monitor = new ComputerMonitorViewRequest(
						ComputerMonitorViewRequest.ActiveViewport.GOAL_TREE,
						Optional.of(process.owningComputerId()), Optional.of(goalProcessId),
						new ComputerMonitorViewRequest.Viewport(0, 0),
						new ComputerMonitorViewRequest.Viewport(0, 0),
						new ComputerMonitorViewRequest.Viewport(0, limit), false);
					yield new FactoryPanelViewRequestPacket(menu.opening(),
						FactoryPanelView.COMPUTER_MONITOR, java.util.OptionalLong.empty(), 0,
						limit, Optional.of(goalProcessId), intent, Optional.of(monitor));
				}
				case AGENT_NEW_TASK_DEFAULTS -> new FactoryPanelViewRequestPacket(
					menu.opening(), FactoryPanelView.NEW_TASK, java.util.OptionalLong.empty(),
					0, limit, Optional.empty(), intent);
				case NETWORK_COLLAPSES_SUBNET -> new FactoryPanelViewRequestPacket(
					menu.opening(), FactoryPanelView.NETWORK_COMMUNICATION,
					java.util.OptionalLong.empty(), 0, limit, Optional.empty(), intent);
				case LOGISTICS_IS_READ_ONLY -> new FactoryPanelViewRequestPacket(
					menu.opening(), FactoryPanelView.NETWORK_LOGISTICS,
					java.util.OptionalLong.empty(), 0, limit, Optional.of(network), intent);
				case REMOTE_FORCE_DENIED, DIAGNOSTICS_NORMAL, DIAGNOSTICS_ADVANCED, DIAGNOSTICS_TOOLTIP -> throw new IllegalStateException(
					"remote force uses its destructive interaction acceptance");
			};
			CompoundTag journalBefore = exact == PanelScenario.LOGISTICS_IS_READ_ONLY
				? com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData
					.get(level.getServer()).save(new CompoundTag(), level.registryAccess()).copy() : null;
			ClientboundPacketPayload response = deliverDecodedViewRequest(request).orElse(null);
			if (response == null) return false;
			assertPanelResponse(exact, response, intent);
			helper.assertTrue(CBPackets.takePanelDeliveryForTest(operator).isEmpty(),
				"one typed view request must deliver exactly one response");
			if (exact == PanelScenario.AGENT_NEW_TASK_DEFAULTS && !exerciseNewTaskCommandResults(menu))
				return false;
			if (exact == PanelScenario.LOGISTICS_IS_READ_ONLY) {
				ClientboundPacketPayload repeated = deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(
					menu.opening(), FactoryPanelView.NETWORK_LOGISTICS,
					java.util.OptionalLong.empty(), 0, limit, Optional.of(network), intent + 1))
					.orElseThrow(() -> new IllegalStateException(
						"the repeated Logistics request did not deliver its typed response"));
				LogisticsNetworkResponsePacket first = (LogisticsNetworkResponsePacket) response;
				LogisticsNetworkResponsePacket second = (LogisticsNetworkResponsePacket) repeated;
				helper.assertValueEqual(second.projection(), first.projection(),
					"a repeated Logistics view request must not mutate durable directory state");
				helper.assertValueEqual(com.nobodiiiii.createbiotech.content.factorycluster.runtime
					.FactoryOrchestrationSavedData.get(level.getServer())
						.save(new CompoundTag(), level.registryAccess()), journalBefore,
					"both Logistics reads must leave the complete durable journal, directory and bindings unchanged");
				helper.assertTrue(CBPackets.takePanelDeliveryForTest(operator).isEmpty(),
					"the repeated Logistics request must emit exactly one typed delivery");
			}
			return true;
		}

		private List<net.createmod.catnip.net.base.ServerboundPacketPayload> validAuthorityPackets;

		private void exerciseMutationRefreshCycle(FactoryPanelMenu menu, Runnable complete) {
			var before = communicationView(menu);
			var beforeAlias = logisticsView(menu);
			long aliasRevision = beforeAlias.projection().networks().stream()
				.filter(value -> value.logisticsNetworkId().equals(network)).findFirst().orElseThrow().revision();
			var changedFrequency = new CommunicationFrequency("t7-r4-" + stable("cas-frequency").toString().substring(0, 12));
			var first = new FactoryPanelCommunicationCommandPacket(menu.opening(), before.projection().generation(),
				before.projection().revision(), 940, new FactoryPanelCommunicationCommandPacket.ChangePrimary(
					before.projection().localEdits().orElseThrow().declarationRevision(), changedFrequency));
			helper.assertTrue(((FactoryPanelCommunicationCommandResultPacket) deliverCommand(first)).applied(), "Communication first mutation CAS");
			awaitNextRadioTopologyFrame(radioTopologyFrame().sampleTick(), () -> true, () -> {
			var updated = communicationView(menu);
			helper.assertTrue(updated.projection().revision() > before.projection().revision(), "Communication ack then fresh publication");
			helper.assertTrue(((FactoryPanelCommunicationCommandResultPacket) deliverCommand(new FactoryPanelCommunicationCommandPacket(
				menu.opening(), updated.projection().generation(), updated.projection().revision(), 941,
				new FactoryPanelCommunicationCommandPacket.ChangePrimary(updated.projection().localEdits().orElseThrow()
					.declarationRevision(), testRadioFrequency)))).applied(), "Communication second CAS uses refreshed publication");
			helper.assertTrue(((FactoryPanelLogisticsCommandResultPacket) deliverCommand(new FactoryPanelLogisticsCommandPacket(
				menu.opening(), network, 942, new FactoryPanelLogisticsCommandPacket.RenameAlias(aliasRevision,
					Optional.of("Round 4 first"))))).applied(), "Logistics first mutation CAS");
			var renamed = logisticsView(menu);
			long nextRevision = renamed.projection().networks().stream().filter(value -> value.logisticsNetworkId().equals(network))
				.findFirst().orElseThrow().revision();
			helper.assertTrue(nextRevision > aliasRevision && renamed.projection().revision() > beforeAlias.projection().revision(),
				"Logistics ack then fresh publication and alias revision");
			helper.assertTrue(((FactoryPanelLogisticsCommandResultPacket) deliverCommand(new FactoryPanelLogisticsCommandPacket(
				menu.opening(), network, 943, new FactoryPanelLogisticsCommandPacket.RenameAlias(nextRevision,
					Optional.of("Round 4 second"))))).applied(), "Logistics second CAS uses refreshed alias revision");
			awaitNextRadioTopologyFrame(radioTopologyFrame().sampleTick(), () -> true, complete,
				"restored primary frequency must join the next actual topology publication");
			}, "changed primary frequency must join the next actual topology publication");
		}

		private CommunicationNetworkResponsePacket communicationView(FactoryPanelMenu menu) {
			return (CommunicationNetworkResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(menu.opening(),
				FactoryPanelView.NETWORK_COMMUNICATION, java.util.OptionalLong.empty(), 0, Math.min(32, menu.rowLimit()),
				Optional.empty(), 950)).orElseThrow(() -> new IllegalStateException("Communication live refresh returned no response"));
		}
		private LogisticsNetworkResponsePacket logisticsView(FactoryPanelMenu menu) {
			return (LogisticsNetworkResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(menu.opening(),
				FactoryPanelView.NETWORK_LOGISTICS, java.util.OptionalLong.empty(), 0, Math.min(32, menu.rowLimit()),
				Optional.of(network), 951)).orElseThrow(() -> new IllegalStateException("Logistics live refresh returned no response"));
		}
		private ClientboundPacketPayload deliverCommand(net.createmod.catnip.net.base.ServerboundPacketPayload command) {
			CBPackets.beginPanelDeliveryObservationForTest(operator);
			CBPackets.roundTripPanelForTest(command, new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess())).handle(operator);
			var response = CBPackets.takePanelDeliveryForTest(operator).orElseThrow(() ->
				new IllegalStateException("Live command produced no acknowledgement: " + command));
			helper.assertTrue(CBPackets.takePanelDeliveryForTest(operator).isEmpty(), "one command acknowledgement");
			return CBPackets.roundTripPanelForTest(response, new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()));
		}
		private List<net.createmod.catnip.net.base.ServerboundPacketPayload> validAuthorityPackets(FactoryPanelMenu menu) {
			var fence = FactoryOrchestrationServer.panelTaskCommandFence(panel)
				.orElseThrow(() -> new IllegalStateException("Authority fixture missing Task publication"));
			var alias = logisticsView(menu).projection().networks().stream()
				.filter(value -> value.logisticsNetworkId().equals(network)).findFirst()
				.orElseThrow(() -> new IllegalStateException("Authority fixture missing Logistics network"));
			var comm = communicationView(menu).projection();
			return List.of(new FactoryPanelViewRequestPacket(menu.opening(), FactoryPanelView.TASKS,
				java.util.OptionalLong.empty(), 0, 4, Optional.empty(), 910),
				new FactoryPanelGoalCommandPacket(menu.opening(), fence.generation(), fence.tasksRevision(), goalId, 911,
					new FactoryPanelGoalCommandPacket.ChangePriority(1)),
				new FactoryPanelNewTaskPacket(menu.opening(), fence.generation(), fence.newTaskRevision(), 912,
					new FactoryPanelNewTaskPacket.Draft(new StackKey(requestedOutputStack()), network, WAREHOUSE, 1, InputMode.EXACT, 0)),
				new FactoryPanelCommunicationCommandPacket(menu.opening(), comm.generation(), comm.revision(), 913,
					new FactoryPanelCommunicationCommandPacket.ChangePrimary(comm.localEdits().orElseThrow(() ->
						new IllegalStateException("Authority fixture missing editable declaration")).declarationRevision(), testRadioFrequency)),
				new FactoryPanelLogisticsCommandPacket(menu.opening(), network, 914,
					new FactoryPanelLogisticsCommandPacket.RenameAlias(alias.revision(), Optional.of("Authority accepted"))));
		}

		private void exerciseLivePanelAuthorityMatrix(FactoryPanelMenu menu, int domain) {
			if (domain < 5) {
				// A just-published admission may still own an in-flight worker. Retry only the
				// positive command with a newly captured fence; no denial counts as success.
				await(AWAIT, () -> {
				var packet = validAuthorityPackets(menu).get(domain);
				var response = deliverCommand(packet);
				boolean applied = switch (response) {
					case TaskListResponsePacket tasks -> tasks.projection().status() == com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListProjection.Status.CURRENT;
					case FactoryPanelGoalCommandResultPacket goal -> goal.result().applied();
					case FactoryPanelNewTaskResultPacket task -> task.result().admissionAccepted();
					case FactoryPanelCommunicationCommandResultPacket comm -> comm.applied();
					case FactoryPanelLogisticsCommandResultPacket alias -> alias.applied();
					default -> false;
				};
				return applied;
				}, () -> exerciseLivePanelAuthorityMatrix(menu, domain + 1),
					"fully valid live authority positive control domain=" + domain);
				return;
			}
			validAuthorityPackets = validAuthorityPackets(menu);
			assertIndependentDomainCasDenials(menu);
			// Domain CAS probes can install a ready worker. Capture the upstream matrix
			// baseline afterwards, and reapply its exact harmless Comm command after each gate.
			validAuthorityPackets = validAuthorityPackets(menu);
			var opening = menu.opening();
			var address = opening.panelAddress();
			for (var changed : List.of(
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening(
					address, opening.panelId(), UUID.randomUUID(), opening.clusterId(), opening.epochId()),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening(
					address, UUID.randomUUID(), opening.menuSessionId(), opening.clusterId(), opening.epochId()),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening(
					address, opening.panelId(), opening.menuSessionId(), UUID.randomUUID(), opening.epochId()),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening(
					address, opening.panelId(), opening.menuSessionId(), opening.clusterId(), UUID.randomUUID()),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening(
					new SpaceAddress(Level.NETHER, null, panelPos), opening.panelId(),
					opening.menuSessionId(), opening.clusterId(), opening.epochId()),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening(
					new SpaceAddress(Level.OVERWORLD, UUID.randomUUID(), panelPos), opening.panelId(),
					opening.menuSessionId(), opening.clusterId(), opening.epochId())))
			{
				assertFiveDomainDenials(changed, "changed opening");
				assertCommunicationBaselineStillApplies("opening envelope");
			}
			operator.moveTo(Vec3.atCenterOf(panelPos.offset(20, 0, 0)));
			assertFiveDomainDenials(opening, "out of range");
			operator.moveTo(Vec3.atCenterOf(panelPos));
			assertCommunicationBaselineStillApplies("range");
			operator.containerMenu = operator.inventoryMenu;
			assertFiveDomainDenials(opening, "closed menu");
			operator.containerMenu = menu;
			assertCommunicationBaselineStillApplies("closed menu");
			operator.setServerLevel(level.getServer().getLevel(Level.NETHER));
			try { assertFiveDomainDenials(opening, "actual player changed world with unchanged opening"); }
			finally { operator.setServerLevel(level); }
			assertCommunicationBaselineStillApplies("player world");
			var originalBinding = panel.bindingState();
			var originalEpoch = computer.production().epoch().orElseThrow();
			FactoryPanelBlockEntity replacement = new FactoryPanelBlockEntity(panelPos, panel.getBlockState());
			level.setBlockEntity(replacement);
			try { assertFiveDomainDenials(opening, "replaced loaded Panel owner"); }
			finally {
				panel.clearRemoved(); level.setBlockEntity(panel);
				// Replacement legitimately invalidates the old member publication. Restore via
				// the real active-epoch publication API, not only its block-entity pointer.
				helper.assertTrue(panel.publishForComputerEpoch(originalBinding, originalEpoch),
					"restored Panel must republish its real active epoch");
			}
			assertCommunicationBaselineStillApplies("loaded Panel owner");
			helper.succeed();
		}

		private void assertCommunicationBaselineStillApplies(String restoredGate) {
			var response = (FactoryPanelCommunicationCommandResultPacket) deliverCommand(validAuthorityPackets.get(3));
			helper.assertTrue(response.applied(), "restored " + restoredGate + " must accept the same Communication CAS baseline; published="
				+ panel.isPublishedFor(response.opening().clusterId(), response.opening().epochId())
				+ ", expectedGeneration=" + ((FactoryPanelCommunicationCommandPacket) validAuthorityPackets.get(3)).expectedGeneration()
				+ ", currentGeneration=" + ProductionRuntimeServices.get(level.getServer()).peekSnapshotForPanelView(panel.clusterId())
					.map(state -> Long.toString(state.generation())).orElse("absent"));
		}

		private void assertIndependentDomainCasDenials(FactoryPanelMenu menu) {
			var goal = (FactoryPanelGoalCommandPacket) validAuthorityPackets.get(1);
			var task = (FactoryPanelNewTaskPacket) validAuthorityPackets.get(2);
			var comm = (FactoryPanelCommunicationCommandPacket) validAuthorityPackets.get(3);
			var alias = (FactoryPanelLogisticsCommandPacket) validAuthorityPackets.get(4);
			List<net.createmod.catnip.net.base.ServerboundPacketPayload> stale = List.of(
				new FactoryPanelGoalCommandPacket(menu.opening(), goal.expectedGeneration() + 1, goal.expectedRevision(), goal.goalId(), 961, goal.command()),
				new FactoryPanelGoalCommandPacket(menu.opening(), goal.expectedGeneration(), goal.expectedRevision() + 1, goal.goalId(), 962, goal.command()),
				new FactoryPanelNewTaskPacket(menu.opening(), task.expectedGeneration() + 1, task.expectedRevision(), 963, task.draft()),
				new FactoryPanelNewTaskPacket(menu.opening(), task.expectedGeneration(), task.expectedRevision() + 1, 964, task.draft()),
				new FactoryPanelCommunicationCommandPacket(menu.opening(), comm.expectedGeneration() + 1, comm.expectedViewRevision(), 965, comm.command()),
				new FactoryPanelCommunicationCommandPacket(menu.opening(), comm.expectedGeneration(), comm.expectedViewRevision() + 1, 966, comm.command()),
				new FactoryPanelLogisticsCommandPacket(menu.opening(), network, 967,
					new FactoryPanelLogisticsCommandPacket.RenameAlias(alias.command().expectedRevision() + 1, Optional.of("must-not-apply"))));
			for (var packet : stale) {
				var response = deliverCommand(packet);
				boolean rejected = switch (response) {
					case FactoryPanelGoalCommandResultPacket result -> !result.result().applied();
					case FactoryPanelNewTaskResultPacket result -> !result.result().admissionAccepted();
					case FactoryPanelCommunicationCommandResultPacket result -> !result.applied();
					case FactoryPanelLogisticsCommandResultPacket result -> !result.applied();
					default -> false;
				};
				helper.assertTrue(rejected, "exactly one stale generation/domain revision gate: " + packet);
			}
		}

		private void assertFiveDomainDenials(
			com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening opening,
			String reason) {
			CompoundTag before = com.nobodiiiii.createbiotech.content.factorycluster.runtime
				.FactoryOrchestrationSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess());
			var goal = (FactoryPanelGoalCommandPacket) validAuthorityPackets.get(1);
			var task = (FactoryPanelNewTaskPacket) validAuthorityPackets.get(2);
			var comm = (FactoryPanelCommunicationCommandPacket) validAuthorityPackets.get(3);
			var alias = (FactoryPanelLogisticsCommandPacket) validAuthorityPackets.get(4);
			List<net.createmod.catnip.net.base.ServerboundPacketPayload> packets = List.of(
				new FactoryPanelViewRequestPacket(opening, FactoryPanelView.TASKS,
					java.util.OptionalLong.empty(), 0, 4, Optional.empty(), 910),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelGoalCommandPacket(
					opening, goal.expectedGeneration(), goal.expectedRevision(), goalId, 911, goal.command()),
				new FactoryPanelNewTaskPacket(opening, task.expectedGeneration(), task.expectedRevision(), 912,
					new FactoryPanelNewTaskPacket.Draft(new StackKey(requestedOutputStack()),
						network, WAREHOUSE, 1, InputMode.EXACT, 0)),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket(
					opening, comm.expectedGeneration(), comm.expectedViewRevision(), 913,
					new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket
						.ChangePrimary(((FactoryPanelCommunicationCommandPacket.ChangePrimary) comm.command()).expectedConfigurationRevision(), testRadioFrequency)),
				new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandPacket(
					opening, network, 914,
					new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandPacket
						.RenameAlias(alias.command().expectedRevision(), Optional.of("must-not-apply"))));
			for (var packet : packets) {
				CBPackets.beginPanelDeliveryObservationForTest(operator);
				var decoded = CBPackets.roundTripPanelForTest(packet,
					new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()));
				decoded.handle(operator);
				var response = CBPackets.takePanelDeliveryForTest(operator);
				if (packet instanceof com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket)
					helper.assertFalse(((com.nobodiiiii.createbiotech.content.factorycluster.panel
						.FactoryPanelCommunicationCommandResultPacket) response.orElseThrow(() ->
							new IllegalStateException(reason + " missing Communication rejection"))).applied(), reason);
				else if (packet instanceof com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelLogisticsCommandPacket)
					helper.assertFalse(((com.nobodiiiii.createbiotech.content.factorycluster.panel
						.FactoryPanelLogisticsCommandResultPacket) response.orElseThrow(() ->
							new IllegalStateException(reason + " missing Logistics rejection"))).applied(), reason);
				else helper.assertTrue(response.isEmpty(), reason + " must reject before runtime ingress");
				helper.assertTrue(CBPackets.takePanelDeliveryForTest(operator).isEmpty(), "denial response bound");
			}
			helper.assertValueEqual(com.nobodiiiii.createbiotech.content.factorycluster.runtime
				.FactoryOrchestrationSavedData.get(level.getServer()).save(new CompoundTag(), level.registryAccess()),
				before, reason + " cannot mutate the durable journal");
		}

		private boolean exerciseNewTaskCommandResults(FactoryPanelMenu menu) {
			var fence = FactoryOrchestrationServer.panelTaskCommandFence(panel).orElse(null);
			if (fence == null) return false;
			StackKey output = new StackKey(requestedOutputStack());
			FactoryPanelNewTaskPacket.Draft rejectedDraft = new FactoryPanelNewTaskPacket.Draft(
				output, stable("unbound-new-task-network"), WAREHOUSE, 1, InputMode.BULK, 0);
			FactoryPanelNewTaskResultPacket rejected = deliverNewTaskCommand(menu,
				new FactoryPanelNewTaskPacket(menu.opening(), fence.generation(),
					fence.newTaskRevision(), 811, rejectedDraft));
			helper.assertValueEqual(rejected.result().preservedDraft(), rejectedDraft,
				"every rejected in-flight command must preserve its exact draft");
			// A completed worker may advance between the two real captures. Keep that denial,
			// refresh the fence on the next bounded attempt, and still require INVALID_ROUTE.
			if (rejected.result().failure().filter(
				FactoryPanelNewTaskPacket.Failure.AUTHORITY_MISMATCH::equals).isPresent()) return false;
			helper.assertValueEqual(rejected.result().failure(),
				Optional.of(FactoryPanelNewTaskPacket.Failure.INVALID_ROUTE),
				"New Task failure must be independently typed");
			helper.assertValueEqual(rejected.result().preservedDraft(), rejectedDraft,
				"New Task failure must return the exact draft");
			return true;
		}

		private FactoryPanelNewTaskResultPacket deliverNewTaskCommand(FactoryPanelMenu menu,
			FactoryPanelNewTaskPacket request) {
			RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				level.registryAccess());
			FactoryPanelNewTaskPacket decoded = CBPackets.roundTripPanelForTest(request, wire);
			helper.assertValueEqual(decoded, request,
				"New Task command must cross its independent registered codec");
			CBPackets.beginPanelDeliveryObservationForTest(operator);
			decoded.handle(operator);
			FactoryPanelNewTaskResultPacket result = (FactoryPanelNewTaskResultPacket)
				CBPackets.takePanelDeliveryForTest(operator).orElseThrow();
			RegistryFriendlyByteBuf resultWire = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				level.registryAccess());
			FactoryPanelNewTaskResultPacket roundTrip = CBPackets.roundTripPanelForTest(result, resultWire);
			helper.assertValueEqual(roundTrip.intentId(), request.intentId(),
				"New Task result must retain its intent fence");
			return roundTrip;
		}

		private FactoryPanelMenu openProductionPanelMenu() {
			operator.moveTo(Vec3.atCenterOf(panelPos));
			operator.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			InteractionResult opened = operator.gameMode.useItemOn(operator, level, ItemStack.EMPTY,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(panelPos),
					Direction.NORTH, panelPos, false));
			helper.assertTrue(opened.consumesAction() && operator.containerMenu instanceof FactoryPanelMenu,
				"the production Factory Panel interaction must open its live server menu");
			return (FactoryPanelMenu) operator.containerMenu;
		}

		private Optional<ClientboundPacketPayload> deliverDecodedViewRequest(
			FactoryPanelViewRequestPacket request) {
			RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				level.registryAccess());
			FactoryPanelViewRequestPacket decoded = CBPackets.roundTripPanelForTest(request, wire);
			helper.assertValueEqual(decoded, request,
				"the registered typed ViewRequest codec must preserve its exact envelope");
			CBPackets.beginPanelDeliveryObservationForTest(operator);
			decoded.handle(operator);
			return CBPackets.takePanelDeliveryForTest(operator);
		}

		private void assertPanelResponse(PanelScenario exact, ClientboundPacketPayload raw,
			long intent) {
			RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(),
				level.registryAccess());
			switch (exact) {
				case REQUESTS_ONE_VIEW -> {
					TaskListResponsePacket packet = (TaskListResponsePacket) raw;
					packet.write(wire);
					TaskListResponsePacket decoded = new TaskListResponsePacket(wire);
					helper.assertValueEqual(decoded.intentId(), intent,
						"Tasks response must retain the request intent fence");
					helper.assertTrue(decoded.projection().tasks().stream()
						.anyMatch(task -> task.goalId().equals(goalId)),
						"Tasks response must project the live admitted Goal");
				}
				case MONITOR_GOAL_TREE -> {
					ComputerMonitorResponsePacket packet = (ComputerMonitorResponsePacket) raw;
					packet.write(wire);
					ComputerMonitorResponsePacket decoded = new ComputerMonitorResponsePacket(wire);
					helper.assertValueEqual(decoded.projection().selectedProcessId(),
						Optional.of(goalProcessId), "Monitor response must retain selected process");
					var nodes = decoded.projection().goalTree().orElseThrow().nodes();
					long actualIron = LogisticsManager.getSummaryOfNetwork(network, false)
						.getCountOf(new ItemStack(Items.IRON_INGOT));
					helper.assertTrue(nodes.stream().anyMatch(node -> node.kind()
						== GoalTreeProjection.TreeNodeKind.INVENTORY
						&& node.output().equals("minecraft:iron_ingot") && node.count() == actualIron),
						"Monitor inventory must equal frozen Create stock, not the planned ingredient amount");
					ItemStack componentStock = new ItemStack(Items.DIAMOND);
					componentStock.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
						net.minecraft.network.chat.Component.literal("Monitor exact component stock"));
					StackKey exactStock = new StackKey(componentStock);
					helper.assertTrue(nodes.stream().anyMatch(node -> node.inventoryItem()
						.filter(exactStock::equals).isPresent() && node.count() == 17),
						"real Create stock must retain the distinct named-stack components and count");
					helper.assertTrue(nodes.stream().anyMatch(node -> node.inventoryItem()
						.filter(new StackKey(new ItemStack(Items.DIAMOND))::equals).isPresent() && node.count() == 3),
						"plain and named stock of the same item must not be merged");
					helper.assertTrue(nodes.stream().anyMatch(node -> node.kind()
						== GoalTreeProjection.TreeNodeKind.CREATE_ADDRESS
						&& node.createAddress().filter(SpaceAddress.capture(level, inputEndpointPos)::equals)
							.isPresent()), "Monitor Create address must be the execution endpoint, not the Library");
					Set<GoalTreeProjection.TreeNodeKind> kinds = nodes.stream()
						.map(GoalTreeProjection.TreeNode::kind).collect(java.util.stream.Collectors.toSet());
					helper.assertTrue(kinds.containsAll(Set.of(GoalTreeProjection.TreeNodeKind.GOAL,
						GoalTreeProjection.TreeNodeKind.PROCESS,
						GoalTreeProjection.TreeNodeKind.LIBRARY,
						GoalTreeProjection.TreeNodeKind.CREATE_ADDRESS,
						GoalTreeProjection.TreeNodeKind.INVENTORY,
						GoalTreeProjection.TreeNodeKind.LOGISTICS_ALIAS)),
						"one frozen Monitor capture must project real Library/Create/inventory/logistics evidence");
					var runId = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
						.orElseThrow().runId();
					var agentQueue = ProductionRuntimeServices.get(level.getServer())
						.pendingAgentRuns().forAgent(agentId);
					var queued = agentQueue.stream().filter(ticket -> ticket.runId().equals(runId)).findFirst();
					var queueNodes = nodes.stream().filter(node -> node.kind()
						== GoalTreeProjection.TreeNodeKind.AGENT_QUEUE).toList();
					helper.assertValueEqual(queueNodes.size(), queued.isPresent() ? 1 : 0,
						"only a real pending ticket may contribute queue evidence");
					if (queued.isPresent()) helper.assertValueEqual(queueNodes.getFirst().detail(),
						Optional.of("queue=" + agentQueue.indexOf(queued.orElseThrow())),
						"Monitor queue position must follow the real ordered pending queue");
					helper.assertTrue(nodes.stream().filter(node -> node.kind()
						== GoalTreeProjection.TreeNodeKind.INVENTORY).allMatch(node -> node.inventoryItem()
						.isPresent()), "observed inventory must retain its component-bearing StackKey on wire");
					helper.assertTrue(nodes.stream().filter(node -> node.kind()
						== GoalTreeProjection.TreeNodeKind.PROCESS).allMatch(node -> node.detail()
						.orElse("").contains("remote=NONE")),
						"local computation must not be reported as remote work");
				}
				case AGENT_NEW_TASK_DEFAULTS -> {
					NewTaskContextResponsePacket packet = (NewTaskContextResponsePacket) raw;
					packet.write(wire);
					NewTaskContextResponsePacket decoded = new NewTaskContextResponsePacket(wire);
					AgentNewTaskDefaults defaults = decoded.projection().defaults().orElseThrow().value();
					helper.assertValueEqual(defaults, new AgentNewTaskDefaults(panel.clusterId(), STAGE_B,
						network, InputMode.EXACT, 1, network, STAGE_B, 0, new CreateRouteKey(network, STAGE_B)),
						"Agent defaults must freeze EXACT, multiplier, priority and every real input/observation/finish route");
					helper.assertValueEqual(defaults.ownerClusterId(), panel.clusterId(),
						"New Task defaults must derive from the live Agent owner");
					helper.assertValueEqual(defaults.selectedInput(), new CreateRouteKey(network, STAGE_B),
						"New Task defaults must derive from the formed Agent input");
				}
				case NETWORK_COLLAPSES_SUBNET -> {
					CommunicationNetworkResponsePacket packet = (CommunicationNetworkResponsePacket) raw;
					packet.write(wire);
					CommunicationNetworkResponsePacket decoded =
						new CommunicationNetworkResponsePacket(wire);
					helper.assertTrue(decoded.projection().cards().stream().filter(card -> !card.remote()).findFirst()
						.orElseThrow().capabilities().contains(com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network
						.ClusterCardProjection.Capability.EDIT_LISTENERS), "real local Wandering must expose listener editing");
					FactoryPanelMenu menu = (FactoryPanelMenu) operator.containerMenu;
					var oneRow = (CommunicationNetworkResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(menu.opening(),
						FactoryPanelView.NETWORK_COMMUNICATION, java.util.OptionalLong.empty(), 0, 1, Optional.empty(), 2201)).orElseThrow();
					helper.assertValueEqual(oneRow.projection().cards().size(), 1, "one-row real Wandering overview remains readable");
					var detail = (CommunicationNetworkResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(menu.opening(),
						FactoryPanelView.NETWORK_COMMUNICATION, java.util.OptionalLong.empty(), 0, 1, Optional.empty(), 2202, Optional.empty(),
						com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationPage.LISTENERS)).orElseThrow();
					helper.assertTrue(detail.projection().cards().isEmpty() && detail.projection().localEdits().orElseThrow().listenerEditors().size() == 1,
						"one-row real Wandering listener detail is independently reachable within request budget");
					var subnets = decoded.projection().cards().stream()
						.filter(card -> card.subnetBoundary()).toList();
					helper.assertValueEqual(subnets.size(), 1,
						"the live listener subnet must collapse to exactly one opaque card; cards=" + subnets
						+ "; active=" + FactoryOrchestrationServer.externalListenerPublicationsForTest(
							level.getServer(), computer.computerId().orElseThrow()));
					helper.assertTrue(subnets.getFirst().remote()
						&& subnets.getFirst().usedSlots() == 0
						&& subnets.getFirst().totalSlots() == 0,
						"the typed communication response must not expose subnet computers or slots");
				}
				case LOGISTICS_IS_READ_ONLY -> {
					LogisticsNetworkResponsePacket packet = (LogisticsNetworkResponsePacket) raw;
					packet.write(wire);
					LogisticsNetworkResponsePacket decoded = new LogisticsNetworkResponsePacket(wire);
					helper.assertValueEqual(decoded.projection().selectedNetworkId(), Optional.of(network),
						"Logistics response must derive its selected bound network from the live menu");
					helper.assertValueEqual(decoded.commandKinds(),
						Set.of(LogisticsNetworkResponsePacket.CommandKind.RENAME_ALIAS),
						"Logistics projection must remain read-only except its separate alias command");
				}
				case REMOTE_FORCE_DENIED, DIAGNOSTICS_NORMAL, DIAGNOSTICS_ADVANCED, DIAGNOSTICS_TOOLTIP -> throw new IllegalStateException();
			}
		}

		/** Stops only after the durable route has crossed its immutable listener hop. */
		private void orderlyRestartPendingRadioRoute() {
			orderlyPreFreezePublication = listenerRelayPublication;
			orderlyPreFreezeSource = Objects.requireNonNull(routerRadioEndpoint(
				radioEndpointKey(computer, panel.clusterId())),
				"orderly restart must capture the exact source generation before freeze");
			orderlyPreFreezeTarget = Objects.requireNonNull(routerRadioEndpoint(radioTargetTopologyKey()),
				"orderly restart must capture the exact final target generation before freeze");
			orderlyPreFreezeRelayComputerId = radioRelay.computerId().orElseThrow();
			orderlyPreFreezeRelayClusterId = radioRelay.production().epoch().orElseThrow().groupId();
			orderlyPendingStatus = FactoryOrchestrationServer.remoteAgentRadioStatus(level.getServer(), goalId)
				.orElseThrow(() -> new IllegalStateException(
					"orderly restart must retain a pending durable radio message before freeze"));
			restartCompositionRootAtFrozenRadioHop();
			await(AWAIT, this::orderlyAuthorityRestored, this::awaitAgentInput,
				this::orderlyAuthorityDiagnostics);
		}

		private String orderlyAuthorityDiagnostics() {
			return "orderly restart changed an exact endpoint or listener boundary generation; "
				+ "frozenPublication=" + orderlyPreFreezePublication
				+ ", restoredPublications=" + FactoryOrchestrationServer
					.externalListenerPublicationsForTest(level.getServer(),
						radioRelay.computerId().orElseThrow())
				+ ", frozenSource=" + orderlyPreFreezeSource
				+ ", restoredSource=" + routerRadioEndpoint(radioEndpointKey(computer, panel.clusterId()))
				+ ", frozenTarget=" + orderlyPreFreezeTarget
				+ ", restoredTarget=" + routerRadioEndpoint(radioTargetTopologyKey())
				+ ", frozenPendingStatus=" + orderlyPendingStatus
				+ ", restoredPendingStatus=" + FactoryOrchestrationServer
					.remoteAgentRadioStatus(level.getServer(), goalId).orElse(null)
				+ ", frozenRelayComputerId=" + orderlyPreFreezeRelayComputerId
				+ ", restoredRelayComputerId=" + radioRelay.computerId().orElse(null)
				+ ", frozenRelayClusterId=" + orderlyPreFreezeRelayClusterId
				+ ", restoredRelayClusterId=" + radioRelay.production().epoch().map(epoch -> epoch.groupId()).orElse(null);
		}

		private boolean orderlyAuthorityRestored() {
			List<ExternalListenerService.Publication> publications =
				FactoryOrchestrationServer.externalListenerPublicationsForTest(level.getServer(),
					radioRelay.computerId().orElseThrow());
			return publications.size() == 1 && publications.getFirst().equals(orderlyPreFreezePublication)
				&& orderlyPreFreezeSource.equals(routerRadioEndpoint(
					radioEndpointKey(computer, panel.clusterId())))
				&& orderlyPreFreezeTarget.equals(routerRadioEndpoint(radioTargetTopologyKey()))
				&& FactoryOrchestrationServer.remoteAgentRadioStatus(level.getServer(), goalId)
					.map(status -> status == orderlyPendingStatus || status == RadioRouter.RouteStatus.IN_TRANSIT)
					.orElse(false);
		}

		private void componentOutput() {
			StackKey exact = new StackKey(requestedOutputStack());
			var plan = FactoryOrchestrationServer.productionFrozenPlan(level.getServer(), goalId)
				.orElseThrow();
			helper.assertValueEqual(plan.output(), exact,
				"the selected Library output must freeze its exact components");
			StackKey observed = switch (plan.observationScope()) {
				case com.nobodiiiii.createbiotech.content.factorycluster.createbridge
					.StockObservationScope.NetworkSummary networkSummary -> networkSummary.item();
				case com.nobodiiiii.createbiotech.content.factorycluster.createbridge
					.StockObservationScope.AttachedInventory inventory -> inventory.item();
			};
			helper.assertValueEqual(observed, exact,
				"the stock observation must query the same exact component-bearing output");
			FactoryPanelMenu menu = openProductionPanelMenu();
			var tasks = (TaskListResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(
				menu.opening(), FactoryPanelView.TASKS, java.util.OptionalLong.empty(), 0,
				Math.min(32, menu.rowLimit()), Optional.of(goalId), 1901)).orElseThrow();
			helper.assertValueEqual(tasks.projection().tasks().stream().filter(task -> task.goalId().equals(goalId))
				.findFirst().orElseThrow().output(), exact, "production Tasks must preserve exact output components");
			var context = (NewTaskContextResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(
				menu.opening(), FactoryPanelView.NEW_TASK, java.util.OptionalLong.empty(), 0,
				Math.min(32, menu.rowLimit()), Optional.of(goalId), 1902)).orElseThrow();
			helper.assertValueEqual(context.projection().prefill().orElseThrow().output(), exact,
				"contextual New Task must preserve the production Goal variant");
			awaitAgentInput();
		}

        private List<Object> remoteSourceGenericBefore;
        private List<Object> remoteReceiverGenericBefore;
        private UUID remoteSourceGenericId;
        private UUID remoteReceiverGenericId;
        private ProgramArchive remoteSourceProgram;
        private ProgramArchive remoteReceiverProgram;
        private static final DataValue.RecordValue GENERIC_EMPTY = new DataValue.RecordValue(Map.of());

        private ComputerBehaviour remoteGeneric(ComputerBlockEntity physical) {
            return Objects.requireNonNull(physical.getBehaviour(ComputerBehaviour.TYPE), "real Computer generic behaviour");
        }
        private List<Object> remoteGenericState(ComputerBlockEntity physical) {
            var behaviour = remoteGeneric(physical);
            return List.of(behaviour.snapshot(), behaviour.flowSnapshot(), behaviour.hardware(), behaviour.programRevision());
        }
        private static ProgramArchive isolationProgram(String cell, long initial, long increment) {
            var graph = new ComputationGraph(Map.of(), Map.of(
                "old", new ComputationGraph.ReadMemory(cell),
                "increment", new ComputationGraph.Constant(new DataValue.IntegerValue(increment)),
                "next", new ComputationGraph.Binary(ComputationGraph.BinaryOp.ADD_INTEGER, "old", "increment")),
                Map.of("next", "next"));
            return new ProgramArchive(new ProgramDefinition(graph,
                Map.of(cell, new ProgramDefinition.Cell(DataType.Scalar.INTEGER, new DataValue.IntegerValue(initial))),
                List.of(new ProgramDefinition.Write(cell, "next"))), Map.of());
        }
        private void installRemoteIndependentPrograms() {
            var source = remoteGeneric(computer);
            var receiver = remoteGeneric(radioReceiver);
            remoteSourceProgram = isolationProgram("sourceCount", 10, 3);
            remoteReceiverProgram = isolationProgram("receiverCount", 100, 7);
            helper.assertValueEqual(source.install(remoteSourceProgram, ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED, "source accepts a real ordinary program");
            helper.assertValueEqual(receiver.install(remoteReceiverProgram, ComputerRuntime.MemoryPolicy.RESET), ComputerRuntime.InstallResult.APPLIED, "receiver accepts its distinct ordinary program");
            source.setPaused(true); receiver.setPaused(true);
            source.begin(GENERIC_EMPTY); receiver.begin(GENERIC_EMPTY);
            // Public single-step execution establishes actual committed RAM before the production recovery sample.
            for (int step = 0; step < 32 && !remoteGenericComplete(); step++) {
                if (source.snapshot().orElseThrow().machine().status() != ComputationMachine.Status.SUCCEEDED) source.step();
                if (receiver.snapshot().orElseThrow().machine().status() != ComputationMachine.Status.SUCCEEDED) receiver.step();
            }
            helper.assertTrue(remoteGenericComplete(), "both installed generic graphs finish through public stepping");
            assertRemoteGenericMemory(13, 107);
            remoteSourceGenericId = source.snapshot().orElseThrow().id();
            remoteReceiverGenericId = receiver.snapshot().orElseThrow().id();
            helper.assertFalse(remoteSourceGenericId.equals(remoteReceiverGenericId), "two physical Computers keep separate generic runtime identities");
            source.begin(GENERIC_EMPTY); receiver.begin(GENERIC_EMPTY);
            helper.assertTrue(source.snapshot().orElseThrow().machine().paused() && receiver.snapshot().orElseThrow().machine().paused(), "second generic continuations are deliberately paused at pending production recovery");
            helper.assertFalse(remoteGenericComplete(), "pending recovery samples real non-empty continuations, not idle archives");
            remoteSourceGenericBefore = remoteGenericState(computer);
            remoteReceiverGenericBefore = remoteGenericState(radioReceiver);
            System.out.println("PRODUCTION_DISPATCH_GENERIC pending: steppedRuns=1, continuation=paused, sourceRAM=13, receiverRAM=107, sourceId=" + remoteSourceGenericId + ", receiverId=" + remoteReceiverGenericId);
        }
        private boolean remoteGenericComplete() {
            return remoteGeneric(computer).snapshot().orElseThrow().machine().status() == ComputationMachine.Status.SUCCEEDED
                && remoteGeneric(radioReceiver).snapshot().orElseThrow().machine().status() == ComputationMachine.Status.SUCCEEDED;
        }
        private void assertRemoteGenericMemory(long source, long receiver) {
            helper.assertValueEqual(remoteGeneric(computer).snapshot().orElseThrow().machine().memory().field("sourceCount"), new DataValue.IntegerValue(source), "source owns its distinct committed RAM");
            helper.assertValueEqual(remoteGeneric(radioReceiver).snapshot().orElseThrow().machine().memory().field("receiverCount"), new DataValue.IntegerValue(receiver), "receiver owns its distinct committed RAM");
        }
        private void assertRemoteGenericIdentityAndHardware() {
            var source = remoteGeneric(computer); var receiver = remoteGeneric(radioReceiver);
            helper.assertValueEqual(source.snapshot().orElseThrow().id(), remoteSourceGenericId, "source generic identity survives production/recovery");
            helper.assertValueEqual(receiver.snapshot().orElseThrow().id(), remoteReceiverGenericId, "receiver generic identity survives production/recovery");
            helper.assertValueEqual(source.snapshot().orElseThrow().program(), remoteSourceProgram, "source keeps its installed distinct program");
            helper.assertValueEqual(receiver.snapshot().orElseThrow().program(), remoteReceiverProgram, "receiver keeps its installed distinct program");
            helper.assertValueEqual(source.hardware(), remoteSourceGenericBefore.get(2), "production never shares/replaces source generic hardware limits");
            helper.assertValueEqual(receiver.hardware(), remoteReceiverGenericBefore.get(2), "production never shares/replaces receiver generic hardware limits");
            helper.assertValueEqual(source.hardware().resident().orElseThrow().level(), 1, "generic source remains level 1");
            helper.assertValueEqual(receiver.hardware().resident().orElseThrow().level(), 5, "generic receiver remains level 5");
        }
        private void assertRemoteGenericUnchanged() {
            assertRemoteGenericIdentityAndHardware();
            helper.assertValueEqual(remoteGenericState(computer), remoteSourceGenericBefore, "paused or completed source generic state remains independent of production");
            helper.assertValueEqual(remoteGenericState(radioReceiver), remoteReceiverGenericBefore, "paused or completed receiver generic state remains independent of production");
        }
        private void resumeRemoteGenericContinuations() {
            assertRemoteGenericUnchanged();
            remoteGeneric(computer).setPaused(false); remoteGeneric(radioReceiver).setPaused(false);
            System.out.println("PRODUCTION_DISPATCH_GENERIC resumed: continuations=active, advancement=natural-server-ticks");
        }
        private void awaitRemoteGenericResultsAlongsideProduction() {
            await(AWAIT, this::remoteGenericComplete, () -> {
                assertRemoteGenericIdentityAndHardware(); assertRemoteGenericMemory(16, 114);
                remoteSourceGenericBefore = remoteGenericState(computer); remoteReceiverGenericBefore = remoteGenericState(radioReceiver);
                System.out.println("PRODUCTION_DISPATCH_GENERIC material-ready: naturalRuns=1, continuations=completed, sourceRAM=16, receiverRAM=114");
                awaitAgentInput();
            }, "independent generic continuations did not finish on actual server ticks during remote production");
        }
        private ComputerBlockEntity reloadRemoteGenericComputer(ComputerBlockEntity before) {
            UUID physicalId = before.computerId().orElseThrow();
            CompoundTag saved = before.saveWithFullMetadata(level.registryAccess());
            var after = (ComputerBlockEntity) Objects.requireNonNull(BlockEntity.loadStatic(before.getBlockPos(),
                before.getBlockState(), saved, level.registryAccess()), "durable Computer NBT reload");
            before.invalidate(); level.removeBlockEntity(before.getBlockPos()); level.setBlockEntity(after);
            remoteGeneric(after).document();
            helper.assertValueEqual(after.computerId().orElseThrow(), physicalId, "physical Computer identity survives actual block-entity NBT reload");
            return after;
        }
        private void runRemoteGenericAfterProduction() {
            assertRemoteGenericUnchanged();
            computer = reloadRemoteGenericComputer(computer);
            radioReceiver = reloadRemoteGenericComputer(radioReceiver);
            assertRemoteGenericIdentityAndHardware(); assertRemoteGenericMemory(16, 114);
            System.out.println("PRODUCTION_DISPATCH_GENERIC physical-reload: both=NBT-restored, sourceRAM=16, receiverRAM=114, identities/programs/hardware=preserved");
            remoteGeneric(computer).begin(GENERIC_EMPTY); remoteGeneric(radioReceiver).begin(GENERIC_EMPTY);
            await(AWAIT, this::remoteGenericComplete, () -> {
                assertRemoteGenericIdentityAndHardware(); assertRemoteGenericMemory(19, 121);
                System.out.println("PRODUCTION_DISPATCH_GENERIC after-retirement: naturalRuns=2, continuations=completed, sourceRAM=19, receiverRAM=121");
                helper.succeed();
            }, "each Computer must still execute its own ordinary program after production retirement");
        }

        private void remoteCompute() {
			await(AWAIT, () -> FactoryOrchestrationServer.productionRemoteComputationJob(
				level.getServer(), goalId).filter(job -> ProductionRuntimeServices.get(
					level.getServer()).snapshot(job.lease().receiverClusterId())
					.map(state -> state.processes().containsKey(job.processId())).orElse(false))
					.isPresent(), () -> {
				var exchange = FactoryOrchestrationServer.productionRemoteComputation(
					level.getServer(), goalId).orElseThrow(() -> new IllegalStateException(
						"remote receiver process became visible without its source exchange"));
				var receiverJob = FactoryOrchestrationServer.productionRemoteComputationJob(
					level.getServer(), goalId).orElseThrow(() -> new IllegalStateException(
						"remote receiver process became visible without its durable job"));
				remoteComputationLeaseId = exchange.lease().leaseId();
				ClusterEngineState receiverState = ProductionRuntimeServices.get(level.getServer())
					.snapshot(receiverJob.lease().receiverClusterId()).orElseThrow(() ->
						new IllegalStateException("remote receiver cluster snapshot disappeared"));
				var receiverProcess = receiverState.processes().get(receiverJob.processId());
                var sourceStateBefore = ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId()).orElseThrow();
                var sourceProcessBefore = sourceStateBefore.processes().get(exchange.lease().sourceProcessId());
                helper.assertTrue(sourceProcessBefore != null, "remote source keeps its actual process");
                helper.assertValueEqual(sourceProcessBefore.owningComputer().profession(), ProductionProfession.NITWIT, "remote admission uses real NITWIT source hardware");
                helper.assertValueEqual(sourceProcessBefore.owningComputer().processSlots(), 1, "real NITWIT source retains one production slot");
                helper.assertFalse(sourceProcessBefore.owningComputer().supports(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.ALCHEMIST), "source must not gain receiver profession capability");
                helper.assertValueEqual(receiverProcess.owningComputer().profession(), ProductionProfession.CLERIC, "real receiver retains CLERIC hardware");
                helper.assertValueEqual(receiverProcess.owningComputer().processSlots(), 6, "level-5 receiver has its own finite six-slot profile");
                helper.assertValueEqual(receiverProcess.owningComputer(), radioReceiver.production().profile(), "receiver allocation must use its installed production profile");
                var sourceGeneric = computer.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour.TYPE);
                var receiverGeneric = radioReceiver.getBehaviour(com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBehaviour.TYPE);
                helper.assertValueEqual(sourceGeneric.hardware().resident().orElseThrow().level(), 1, "actual generic source resident level");
                helper.assertValueEqual(receiverGeneric.hardware().resident().orElseThrow().level(), 5, "actual generic receiver resident level");
                installRemoteIndependentPrograms();
                System.out.println("REMOTE_EXPANSION_NATIVE allocation: source=" + sourceProcessBefore.owningComputer() + ", receiver=" + receiverProcess.owningComputer() + ", slot=" + receiverJob.owningSlot() + ", genericPrograms=" + sourceGeneric.snapshot().isPresent() + "/" + receiverGeneric.snapshot().isPresent());
				helper.assertValueEqual(receiverJob.receiverMemberId(),
					radioReceiver.computerId().orElseThrow(() -> new IllegalStateException(
						"remote receiver Computer lost its member identity")),
					"the durable remote job must belong to the exact receiver Computer");
				helper.assertValueEqual(receiverProcess.kind(), ProcessKind.REMOTE_LISTENER,
					"receiver computation must consume a real finite Computer process slot");
				helper.assertValueEqual(receiverProcess.owningSlot(), receiverJob.owningSlot(),
					"durable receiver job and live process allocation must agree");
				helper.assertFalse(receiverState.goals().containsKey(goalId),
					"the receiver process must never copy the source Goal");
				helper.assertValueEqual(exchange.lease().sourceGoalId(), goalId,
					"the remote pure computation must retain the exact source Goal lineage");
				helper.assertValueEqual(exchange.lease().goalOwnerClusterId(), panel.clusterId(),
					"the receiver must never take Goal ownership");
				if (!exchange.materialDispatchAllowed()) {
					var status = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
						.orElseThrow();
					helper.assertFalse(status.dispatchSubmitted() || status.inputPhase().isPresent(),
						"material dispatch must remain fenced before the durable computation result");
				}
				if (!remoteComputationRestarted && !exchange.materialDispatchAllowed()) {
					remoteComputationRestarted = true;
                    assertRemoteGenericUnchanged();
                    System.out.println("REMOTE_EXPANSION_NATIVE pending restart: phase=" + exchange.phase() + ", receiverJob=" + receiverJob.phase() + ", materialAllowed=" + exchange.materialDispatchAllowed());
					restartCompositionRoot();
					FactoryOrchestrationServer.nativeComputerObserved(radioTarget.production());
					FactoryOrchestrationServer.nativeComputerObserved(radioReceiver.production());
                    assertRemoteGenericUnchanged();
				}
                resumeRemoteGenericContinuations();
				await(AWAIT, () -> FactoryOrchestrationServer.productionRemoteComputation(
					level.getServer(), goalId).map(
						RemoteExpansionProtocol.RemoteComputationExchange::materialDispatchAllowed)
						.orElse(false), () -> {
						var completed = FactoryOrchestrationServer.productionRemoteComputation(
							level.getServer(), goalId).orElseThrow(() -> new IllegalStateException(
								"completed source exchange disappeared before output dispatch"));
						var completedJob = FactoryOrchestrationServer.productionRemoteComputationJob(
							level.getServer(), goalId).orElseThrow(() -> new IllegalStateException(
								"completed receiver job disappeared before output dispatch"));
						helper.assertValueEqual(completedJob.phase(),
							RemoteExpansionProtocol.RemoteComputationJobPhase.COMPLETED,
							"receiver result must be durable before source material authority resumes");
						helper.assertTrue(completedJob.revisionState().isPresent()
							&& completedJob.result().isPresent(),
							"receiver worker must durably generate both protocol values");
						var installed = FactoryOrchestrationServer.productionFrozenPlan(
							level.getServer(), goalId).orElseThrow(() -> new IllegalStateException(
								"remote production plan disappeared before output dispatch"))
							.remoteExpansion().orElseThrow(() -> new IllegalStateException(
								"installed remote expansion disappeared before output dispatch"));
						helper.assertValueEqual(installed.request(), completed.lease().expansionRequest(),
							"source FrozenPlan must install the exact bounded receiver request");
						helper.assertValueEqual(installed.revision(),
							completedJob.revisionState().orElseThrow(),
							"source child lineage must install the receiver-created revision");
						helper.assertValueEqual(installed.result().orElseThrow(),
							completedJob.result().orElseThrow(),
							"source execution state must install the receiver-created result");
						helper.assertValueEqual(installed.result().orElseThrow().manifest().get(
							new StackKey(new ItemStack(Items.IRON_INGOT))), 2L,
							"receiver expansion must multiply the real production manifest");
						ClusterEngineState sourceState = ProductionRuntimeServices.get(
							level.getServer()).snapshot(panel.clusterId()).orElseThrow(() ->
								new IllegalStateException("remote source cluster snapshot disappeared"));
						var sourceProcess = sourceState.processes().get(
							completed.lease().sourceProcessId());
						helper.assertTrue(sourceProcess != null && sourceProcess
							.isChildAcknowledgedDurably(installed.revision().childRevision()
								.childProcessId(), installed.revision().childRevision()
								.childGeneration(), installed.revision().childRevision().revision()),
							"source material authority must be gated by its durable child acknowledgement");
						helper.assertValueEqual(FactoryOrchestrationServer.radioGoalOwner(
							level.getServer(), completed.lease().leaseId()).orElseThrow(() ->
								new IllegalStateException("remote lease lost its durable source owner")),
							panel.clusterId(), "durable router reload must retain source ownership");
                        assertRemoteGenericIdentityAndHardware();
                        System.out.println("REMOTE_EXPANSION_NATIVE result: pendingRestartObserved=" + remoteComputationRestarted + ", exactResult=" + completedJob.result().orElseThrow().fingerprint());
                        awaitRemoteGenericResultsAlongsideProduction();
                    }, this::remoteComputationDiagnostics);
			}, "the exhausted source Computer never allocated a real receiver computation process");
		}

		private String remoteComputationDiagnostics() {
			var exchange = FactoryOrchestrationServer.productionRemoteComputation(
				level.getServer(), goalId).orElse(null);
			var job = FactoryOrchestrationServer.productionRemoteComputationJob(
				level.getServer(), goalId).orElse(null);
			var plan = FactoryOrchestrationServer.productionFrozenPlan(
				level.getServer(), goalId).orElse(null);
			ClusterEngineState source = ProductionRuntimeServices.get(level.getServer())
				.snapshot(panel.clusterId()).orElse(null);
			UUID sourceProcessId = exchange == null ? goalProcessId
				: exchange.lease().sourceProcessId();
			var process = source == null ? null : source.processes().get(sourceProcessId);
			return "source-owned remote pure computation never completed after durable replay: "
				+ "exchange=" + (exchange == null ? "none" : exchange.phase() + "/revision="
					+ exchange.revision() + "/sourceGeneration="
					+ exchange.lease().sourceGeneration() + "/requestStateGeneration="
					+ exchange.lease().expansionRequest().parentStateGeneration())
				+ ", job=" + (job == null ? "none" : job.phase() + "/slot=" + job.owningSlot())
				+ ", planExpansion=" + (plan == null ? "none" : plan.remoteExpansion())
				+ ", sourceStateGeneration=" + (source == null ? "none" : source.generation())
				+ ", sourceProcessId=" + sourceProcessId
				+ ", process=" + (process == null ? "none" : process.state() + "/generation="
					+ process.generation() + "/owner=" + process.owningComputerId())
				+ ", sourceProcesses=" + (source == null ? "none" : source.processes().values()
					.stream().map(candidate -> candidate.processId() + ":" + candidate.kind() + ":"
						+ candidate.state() + ":g" + candidate.generation() + ":goal="
						+ candidate.goalId()).toList())
				+ ", activeLeases=" + FactoryOrchestrationServer
					.productionRemoteComputationActiveLeaseCount(level.getServer());
		}

		private void remoteComputeRouteWait() {
			awaitGoal(GoalState.WAITING, WaitReason.ROUTE, () -> {
				var status = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
					.orElseThrow();
				helper.assertFalse(status.dispatchSubmitted() || status.inputPhase().isPresent(),
					"an exhausted source without a reachable receiver must not bypass remote material fencing");
				helper.assertTrue(FactoryOrchestrationServer.productionRemoteComputation(
					level.getServer(), goalId).isEmpty(),
					"no receiver route must not fabricate a remote exchange");
				declareClusterPrimary(remoteComputeReceiverPanel(), durableScenarioFrequency());
				FactoryOrchestrationServer.nativeComputerObserved(radioReceiver.production());
				observeRemoteMonitor(openProductionPanelMenu(), AWAIT, -1);
			});
		}

		private void observeRemoteMonitor(FactoryPanelMenu menu, int remaining, long waitingRevision) {
			var exchange = FactoryOrchestrationServer.productionRemoteComputation(level.getServer(), goalId).orElse(null);
			long observedWaiting = waitingRevision;
			if (exchange != null) {
				boolean waiting = switch (exchange.phase()) {
					case OFFER_PENDING, REVISION_PENDING, ACK_PENDING, RESULT_PENDING -> true;
					default -> false;
				};
				boolean running = exchange.phase() == RemoteExpansionProtocol.RemoteComputationPhase.REVISION_COMPUTING
					|| exchange.phase() == RemoteExpansionProtocol.RemoteComputationPhase.RESULT_COMPUTING;
				if (waiting || running) {
					var response = monitorGoal(menu, goalId);
					String expected = waiting ? "remote=WAITING" : "remote=RUNNING";
					helper.assertTrue(response.projection().goalTree().orElseThrow().nodes().stream().anyMatch(node ->
						node.kind() == GoalTreeProjection.TreeNodeKind.PROCESS && node.detail().orElse("").contains(expected)),
						"Monitor must follow the actual remote protocol phase " + exchange.phase());
					if (waiting && observedWaiting < 0) observedWaiting = response.projection().revision();
					if (running && observedWaiting >= 0) {
						helper.assertTrue(response.projection().revision() > observedWaiting,
							"real remote WAITING to RUNNING advances same-owner Monitor revision");
						remoteCompute();
						return;
					}
				}
			}
			if (remaining <= 0) { helper.fail("Monitor never observed actual remote WAITING then RUNNING"); return; }
			long nextWaiting = observedWaiting;
			LockSupport.parkNanos(1_000_000L);
			helper.runAfterDelay(1, () -> observeRemoteMonitor(menu, remaining - 1, nextWaiting));
		}

		private void dispatchReload() {
			await(AWAIT, () -> FactoryOrchestrationServer.inputCommitAttemptState(
				level.getServer(), goalId).orElse(null)
					== com.nobodiiiii.createbiotech.content.factorycluster.createbridge
						.CreateLogisticsGateway.CommitAttemptState.ATTEMPTING, () -> {
				helper.assertValueEqual(nativeOrderCount, 0,
					"ATTEMPTING crash boundary must precede every native Create order");
				helper.assertFalse(hasPhysicalPackage(sourcePackager, INPUT),
					"suspended ATTEMPTING boundary must not expose a physical package");
				restartCompositionRoot();
				awaitGoal(GoalState.WAITING, WaitReason.RESET_REQUIRED, () -> {
					var status = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
						.orElseThrow();
					helper.assertValueEqual(status.inputPhase().orElseThrow(),
						AgentInputPhase.RESET_REQUIRED,
						"reload of durable ATTEMPTING must recover as uncertain");
					helper.assertValueEqual(count(inputChest, Items.IRON_INGOT), 0L,
						"uncertain recovery must never invent input stock");
					noDuplicate(0, this::afterProduction);
				});
			}, "production gateway never exposed its durable ATTEMPTING crash boundary");
		}

		private void awaitAgentInput() {
			awaitProductionDispatch(sourcePackager, INPUT, inputPackager, () -> {
				if (scenario == Scenario.LOCAL_FORCE_RESET) {
					forceResetLocally();
					return;
				}
				if (scenario == Scenario.CANCEL_DRAIN)
					helper.assertTrue(sendPanelCancel(goalId),
						"post-dispatch cancellation packet must enter drain");
				if (scenario == Scenario.RECOVERY_BACKPRESSURE) {
					FactoryOrchestrationServer.nativePanelUnloaded(outputEndpoint);
					await(800, () -> panel.agentDiagnostics().isEmpty(), () -> {
						var interrupted = FactoryOrchestrationServer.productionStatus(
							level.getServer(), goalId).orElseThrow();
						helper.assertValueEqual(interrupted.inputPhase().orElseThrow(),
							AgentInputPhase.INPUT_ENDED,
							"active contract must retain its input evidence while output is unloaded");
						FactoryOrchestrationServer.nativePanelLoaded(outputEndpoint);
						await(800, () -> panel.agentDiagnostics().stream()
							.anyMatch(agent -> agent.agentId().equals(agentId)),
							this::produceAgentOutput,
							"reloaded output endpoint did not restore the active Agent");
					}, "output endpoint unload did not become visible to the Panel");
					return;
				}
				produceAgentOutput();
			});
		}

		private void restartCompositionRootAtFrozenRadioHop() {
			helper.assertTrue(radioTarget.production().epoch().isPresent() && radioRelay.production().epoch().isPresent(),
				"restart must retain formed target and relay epochs before either is republished");
			restartCompositionRoot(List.of(radioTarget, radioRelay), List.of(warehouseEndpoint));
		}

		private void restartCompositionRoot() {
			restartCompositionRoot(scenario == Scenario.REMOTE_COMPUTE
				? List.of(radioTarget, radioReceiver) : scenario == Scenario.LOCAL_PURE_EXPANSION
					? List.of(radioReplica) : List.of(),
				scenario == Scenario.REMOTE_COMPUTE
					? List.of(capacityInputEndpoint, capacityOutputEndpoint) : List.of());
		}

		private void restartCompositionRoot(List<ComputerBlockEntity> supplementalComputers,
			List<com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity> supplementalPanels) {
			Runnable freeze = scenario == Scenario.ORDERLY_RADIO_RESTART
				? () -> FactoryOrchestrationServer.beginOrderlyShutdown(level.getServer()) : () -> {};
			Runnable teardown = scenario == Scenario.ORDERLY_RADIO_RESTART
				? () -> distinct(List.of(computer), supplementalComputers).forEach(
					c -> FactoryOrchestrationServer.nativeComputerUnloaded(c.production())) : () -> {};
			restartAndRepublish(freeze, teardown, () -> FactoryOrchestrationServer.stop(level.getServer()),
				() -> com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServerTestAccess.restartHistoricalWorld(level.getServer()), List.of(computer),
				supplementalComputers, c -> FactoryOrchestrationServer.nativeComputerObserved(c.production()),
				List.of(inputEndpoint, outputEndpoint), supplementalPanels,
				FactoryOrchestrationServer::nativePanelLoaded);
		}

		private void configureScenarioPrimaryFrequency() {
			if (usesDurableScenarioCommunication()) return;
			if (isRadio() || scenario == Scenario.EXTERNAL_LISTENER)
				declareClusterPrimary(panel, testRadioFrequency);
			if (isRadio()) declareClusterPrimary(radioOwnerPanel(), testRadioFrequency);
			if (scenario == Scenario.TRADER_RELAY)
				declareClusterPrimary(biotechPanel(radioRelayMin().offset(0, 1, 0)),
					testRadioFrequency);
			if (isListenerRelayScenario())
				declareClusterPrimary(biotechPanel(radioRelayMin().offset(0, 1, 0)),
					LISTENER_RELAY_HOST_FREQUENCY);
		}

		private Map<UUID, CommunicationFrequency> scenarioPrimaryFrequencies() {
			if (usesDurableScenarioCommunication()) return Map.of();
			LinkedHashMap<UUID, CommunicationFrequency> frequencies = new LinkedHashMap<>();
			if (isRadio() || scenario == Scenario.EXTERNAL_LISTENER)
				frequencies.put(panel.clusterId(), testRadioFrequency);
			if (isRadio()) frequencies.put(radioOwnerPanel().clusterId(), testRadioFrequency);
			if (scenario == Scenario.TRADER_RELAY)
				frequencies.put(biotechPanel(radioRelayMin().offset(0, 1, 0)).clusterId(),
					testRadioFrequency);
			if (isListenerRelayScenario())
				frequencies.put(biotechPanel(radioRelayMin().offset(0, 1, 0)).clusterId(),
					LISTENER_RELAY_HOST_FREQUENCY);
			return Map.copyOf(frequencies);
		}

		private boolean usesDurableScenarioCommunication() {
			return scenario == Scenario.RADIO_FREQUENCY_MISMATCH
				|| scenario == Scenario.REMOTE_COMPUTE;
		}

		private void forceResetLocally() {
			var before = panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow();
			helper.assertFalse(sendLocalForceResetAtActor(panelPos, panelPos, agentId, before),
				"sneaking empty-hand interaction on the remote Biotech Panel must not force the Agent");
			helper.assertValueEqual(panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow().resetGeneration(),
				before.resetGeneration(), "remote Panel interaction must preserve exact reset generation");
			helper.assertFalse(sendLocalForceResetAtActor(sourceChest, sourceChest, agentId, before),
				"a non-endpoint position must not advance Force Reset");
			helper.assertValueEqual(panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow()
				.resetGeneration(), before.resetGeneration(),
				"rejected non-local Force Reset must retain the active generation");
			helper.assertFalse(sendLocalForceResetAtActor(panelPos.above(9), inputEndpointPos,
				agentId, before),
				"A player beyond the real endpoint interaction radius must not Force Reset");
			helper.assertValueEqual(panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow()
				.resetGeneration(), before.resetGeneration(),
				"out-of-range request must retain the active generation");
			operator.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
			helper.assertFalse(sendLocalForceResetAtActor(inputEndpointPos, inputEndpointPos,
				agentId, before),
				"spectator interaction authorization must deny destructive Force Reset");
			helper.assertValueEqual(panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow()
				.resetGeneration(), before.resetGeneration(),
				"spectator denial must retain the active generation");
			operator.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			Consumer<PlayerInteractEvent.RightClickBlock> cancelExactEndpoint = event -> {
				if (event.getEntity() == operator && event.getPos().equals(inputEndpointPos))
					event.setCanceled(true);
			};
			NeoForge.EVENT_BUS.addListener(cancelExactEndpoint);
			try {
				helper.assertFalse(sendLocalForceResetAtActor(inputEndpointPos, inputEndpointPos,
					agentId, before),
					"a canceled RightClickBlock event must deny destructive Force Reset");
				helper.assertValueEqual(panel.agentDiagnostics().stream()
					.filter(agent -> agent.agentId().equals(agentId)).findFirst().orElseThrow()
					.resetGeneration(), before.resetGeneration(),
					"event cancellation must retain the active generation");
			} finally {
				NeoForge.EVENT_BUS.unregister(cancelExactEndpoint);
			}
			helper.assertTrue(sendLocalForceResetAtActor(inputEndpointPos, inputEndpointPos,
				agentId, before),
				"a player-local configured Agent endpoint must authorize Force Reset");
			await(AWAIT, () -> panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(agentId))
				.anyMatch(agent -> agent.resetGeneration() == before.resetGeneration() + 1
					&& agent.activeRunId() == null)
				&& goal() != null && goal().state() == GoalState.WAITING
				&& goal().waitReason() == WaitReason.RESET_REQUIRED, () -> {
				var status = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
					.orElseThrow();
				helper.assertValueEqual(status.inputPhase().orElseThrow(),
					AgentInputPhase.RESET_REQUIRED,
					"Force Reset must fence the old input contract as late-order evidence");
				helper.assertValueEqual(count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline,
					0L, "Force Reset must stop output dispatch for the terminated contract");
				noDuplicate(1, this::afterProduction);
			}, "local Force Reset did not terminate the contract and publish generation +1");
		}

		private void produceAgentOutput() {
			int produced = scenario == Scenario.REMOTE_COMPUTE ? 2 : 1;
			consume(inputChest, Items.IRON_INGOT, produced);
			put(outputChest, requestedOutputStack(), produced);
			if (scenario == Scenario.COMPONENT_OUTPUT_RELOAD) {
				await(AWAIT, () -> hasPhysicalPackage(outputPackager, WAREHOUSE), () -> {
					ItemStack packageStack = PackageItem.getContents(packager(outputPackager).heldBox)
						.getStackInSlot(0);
					helper.assertTrue(ItemStack.isSameItemSameComponents(packageStack,
						requestedOutputStack()),
						"the real Create output package must retain exact output components");
					restartCompositionRoot();
					helper.assertValueEqual(FactoryOrchestrationServer.productionFrozenPlan(
						level.getServer(), goalId).orElseThrow().output(),
						new StackKey(requestedOutputStack()),
						"reload must restore the exact component-bearing FrozenPlan");
					awaitProductionDispatch(outputPackager, WAREHOUSE, warehousePackager, () ->
						awaitGoal(GoalState.TERMINAL, null, () -> finishAgent(2)));
				}, "component-bearing output never entered a real Create package");
				return;
			}
			if (scenario == Scenario.SIGNAL_COMPLETION) {
				helper.runAfterDelay(5, () -> level.setBlock(signalPos,
					Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL));
			}
			awaitProductionDispatch(outputPackager, WAREHOUSE, warehousePackager, () ->
				awaitGoal(scenario == Scenario.CANCEL_DRAIN ? GoalState.WAITING : GoalState.TERMINAL,
					scenario == Scenario.CANCEL_DRAIN ? WaitReason.RESET_REQUIRED : null,
					() -> finishAgent(2)));
		}

		private void finishAgent(int orders) {
			long expected = scenario == Scenario.REMOTE_COMPUTE ? 2L : 1L;
			helper.assertValueEqual(deliveredIron, expected,
				"Agent input must be credited by an actual native inventory increment");
			helper.assertValueEqual(count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline, expected,
				"Agent output must reach the real warehouse once");
			if (scenario == Scenario.COMPONENT_OUTPUT_RELOAD)
				helper.assertValueEqual(countExact(warehouseChest, requestedOutputStack()), 1L,
					"warehouse stock must retain the exact component-bearing output after reload");
			if (scenario != Scenario.CANCEL_DRAIN) assertGoalCounts(expected, expected, 0);
			if (scenario == Scenario.RECOVERY_BACKPRESSURE) {
				var recovered = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
					.orElseThrow();
				helper.assertValueEqual(recovered.outputMilestone().orElseThrow(),
					ProductionOutputContract.Milestone.OUTPUT_RECEIVED,
					"recovery must retain the exact native output receipt through reload");
				recoveryBackpressure();
				return;
			}
			if (scenario == Scenario.LOST_ACK) {
				lostAckReconciliation();
				return;
			}
			noDuplicate(orders, this::afterProduction);
		}

		private void recoveryBackpressure() {
			UUID completedGoal = goalId;
			helper.assertValueEqual(FactoryOrchestrationServer.recoveryCorrectnessOccupancy(
				level.getServer()), recoveryOccupancyBaseline + 1,
				"the first real Agent contract must fill the reserved ledger slot");
			helper.assertTrue(sendPanelSubmit(new StackKey(new ItemStack(Items.GOLD_INGOT)),
				WAREHOUSE, 1, InputMode.BULK, 0),
				"one ledger-pressure Submit must durably enter asynchronous admission");
			await(1_000, () ->
				panel.goalDiagnostics().stream()
					.anyMatch(row -> !row.goalId().equals(completedGoal)), () -> {
					goalId = panel.goalDiagnostics().stream()
						.filter(row -> !row.goalId().equals(completedGoal)).findFirst().orElseThrow()
						.goalId();
					awaitGoal(GoalState.WAITING, WaitReason.RECOVERY_LEDGER_FULL, () -> {
						helper.assertValueEqual(FactoryOrchestrationServer
							.recoveryCorrectnessOccupancy(level.getServer()),
							recoveryOccupancyBaseline + 1,
							"backpressure must reject the new offer without leaking evidence");
						await(800, () -> sendPanelCancel(goalId), () ->
							awaitGoal(GoalState.TERMINAL, null,
								() -> noDuplicate(2, this::afterProduction)),
							() -> "real Panel cancel did not clear the rejected intent confirmation: "
								+ productionDiagnostics(sourcePackager));
					});
				}, "second production Goal was not admitted for ledger pressure");
		}

		private void lostAckReconciliation() {
			await(AWAIT, () -> FactoryOrchestrationServer.remoteRecoveryStatus(
				level.getServer(), goalId).orElse(null)
					== FactoryOrchestrationServer.RemoteRecoveryPhase.ACK_LOST, () -> {
				restartCompositionRoot();
				FactoryOrchestrationServer.nativeComputerObserved(radioTarget.production());
				await(AWAIT, () -> FactoryOrchestrationServer.remoteRecoveryStatus(
					level.getServer(), goalId).orElse(null)
						== FactoryOrchestrationServer.RemoteRecoveryPhase.RECONCILED, () -> {
					var status = FactoryOrchestrationServer.productionStatus(
						level.getServer(), goalId).orElseThrow();
					helper.assertValueEqual(status.outputMilestone().orElseThrow(),
						ProductionOutputContract.Milestone.OUTPUT_RECEIVED,
						"status query must retain exact output receipt evidence");
					noDuplicate(2, this::afterProduction);
				}, "lost ACK did not reconcile through a durable status query after reload");
			}, () -> "production outbox did not drop the armed evidence ACK; phase="
				+ FactoryOrchestrationServer.remoteRecoveryStatus(level.getServer(), goalId)
					.orElse(null));
		}

		private void exact(int completed) {
			if (completed == 2) {
				awaitGoal(GoalState.TERMINAL, null, () -> {
					assertGoalCounts(2, 2, 0);
					helper.assertValueEqual(deliveredIron, 2L,
						"EXACT multiplier two must unpack two serial unit inputs");
					helper.assertValueEqual(count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline,
						2L, "EXACT multiplier two must deliver two outputs");
					noDuplicate(4, this::afterProduction);
				});
				return;
			}
			awaitProductionDispatch(sourcePackager, INPUT, inputPackager, () -> {
				consume(inputChest, Items.IRON_INGOT, 1);
				put(outputChest, Items.GOLD_INGOT, 1);
				awaitProductionDispatch(outputPackager, WAREHOUSE, warehousePackager,
					() -> exact(completed + 1));
			});
		}

		private void sequenced() {
			awaitProductionDispatch(sourcePackager, STAGE_A, inputPackager, () ->
				awaitProductionDispatch(sourcePackager, STAGE_B, inputPackager, () -> {
					consume(inputChest, Items.IRON_INGOT, 1);
					consume(inputChest, Items.COPPER_INGOT, 1);
					put(outputChest, Items.GOLD_INGOT, 1);
					awaitProductionDispatch(outputPackager, WAREHOUSE, warehousePackager, () ->
						awaitGoal(GoalState.TERMINAL, null, () -> {
							helper.assertValueEqual(nativeAddresses.subList(0, 2),
								List.of(STAGE_A, STAGE_B), "sequenced native addresses must be ordered");
							helper.assertValueEqual(deliveredIron, 1L,
								"stage A must receive iron");
							helper.assertValueEqual(deliveredCopper, 1L,
								"stage B must receive copper");
							noDuplicate(3, this::afterProduction);
						}));
				}));
		}

		private void knownOffline() {
			awaitGoal(GoalState.WAITING, WaitReason.AGENT_UNLOADED, () -> {
				helper.assertValueEqual(nativeOrderCount, 0,
					"known offline Agent must never fall back to bare dispatch");
				helper.assertValueEqual(count(inputChest, Items.IRON_INGOT), 0L,
					"offline Agent must not mutate input stock");
				helper.assertValueEqual(count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline,
					0L, "offline Agent must not credit output stock");
				FactoryPanelMenu menu = openProductionPanelMenu();
				var tasks = (TaskListResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(menu.opening(),
					FactoryPanelView.TASKS, java.util.OptionalLong.empty(), 0, Math.min(16,menu.rowLimit()), Optional.of(goalId), 2101)).orElseThrow();
				helper.assertTrue(tasks.projection().tasks().stream().anyMatch(task -> task.goalId().equals(goalId)),
					"incomplete Agent must still publish its existing Goal");
				var context = (NewTaskContextResponsePacket) deliverDecodedViewRequest(new FactoryPanelViewRequestPacket(menu.opening(),
					FactoryPanelView.NEW_TASK, java.util.OptionalLong.empty(), 0, 1, Optional.empty(), 2102)).orElseThrow();
				helper.assertValueEqual(context.projection().status(),
					com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.NewTaskContextProjection.Status.INCOMPLETE_AGENT,
					"incomplete Agent has structured non-submittable context, not invented defaults");
				await(800, () -> sendPanelCancel(goalId), () -> awaitGoal(GoalState.TERMINAL, null, this::afterProduction),
					"incomplete Agent must allow original safe Goal cancellation");
			});
		}

		private void afterProduction() {
			if (panelScenario == PanelScenario.REQUESTS_ONE_VIEW) {
				FactoryPanelMenu menu = openProductionPanelMenu();
				awaitNextRadioTopologyFrame(radioTopologyFrame().sampleTick(), () -> true,
					() -> exerciseMutationRefreshCycle(menu, this::exerciseAcceptedNewTaskPublication),
					"live authority controls require a current topology publication");
				return;
			}
			if (panelScenario == PanelScenario.AGENT_NEW_TASK_DEFAULTS) {
				exerciseFailedNewTaskAdmission();
				return;
			}
			if (scenario == Scenario.RECOVERY_BACKPRESSURE)
				FactoryOrchestrationServer.restoreConfiguredRecoveryAdmission(level.getServer(),
					panel.clusterId());
			if (scenario == Scenario.REMOTE_COMPUTE) {
				await(AWAIT, this::remoteComputationRetired, () -> {
					assertRemoteComputationRetired();
					restartCompositionRoot();
					assertRemoteComputationRetired();
                    assertRemoteGenericUnchanged();
                    System.out.println("REMOTE_EXPANSION_NATIVE retired: pendingRestartObserved=" + remoteComputationRestarted + ", inputIron=" + deliveredIron + ", warehouseGold=" + (count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline) + ", nativeOrders=" + nativeOrderCount);
					runRemoteGenericAfterProduction();
				}, this::remoteComputationRetirementDiagnostics);
				return;
			}
			if (scenario == Scenario.TRADER_RELAY) {
				clearShell(radioTargetMin());
				clearShell(radioRelayMin());
			}
			if (scenario == Scenario.LISTENER_RELAY) {
				unloadListenerRelayAndSubmitRouteLossGoal();
				return;
			}
			if (scenario == Scenario.LOCAL_PURE_EXPANSION) {
				await(AWAIT, () -> FactoryOrchestrationServer.productionLocalComputationCount(level.getServer()) == 0 && durableGoalSettlementComplete(), () -> {
					assertDurableGoalSettlement();
					assertNoLocalRadio(ProductionRuntimeServices.get(level.getServer()).snapshotForServerMutation(panel.clusterId()).orElseThrow());
					helper.assertValueEqual(nativeOrderCount, 2, "reload must not duplicate actual input/output orders");
					System.out.println("LOCAL_EXPANSION_NATIVE settled: run=" + localExpansionRunId + ", inputIron=" + deliveredIron + ", warehouseGold=" + (count(warehouseChest, Items.GOLD_INGOT) - warehouseBaseline) + ", orders=" + nativeOrderCount);
					restartCompositionRoot();
					assertDurableGoalSettlement();
					helper.assertValueEqual(FactoryOrchestrationServer.productionLocalComputationCount(level.getServer()), 0, "retired local authority must stay retired after restart");
					helper.succeed();
				}, "local cleanup and actual output credit did not cross the durable retirement boundary");
				return;
			}
			if (verifyGoalSettlement) {
				await(AWAIT, this::durableGoalSettlementComplete, () -> {
					assertDurableGoalSettlement();
					restartCompositionRoot();
					assertDurableGoalSettlement();
					helper.succeed();
				}, "received Run did not atomically retire its active artifacts and persist confirmed Goal credit");
				return;
			}

			helper.succeed();
		}

		private boolean durableGoalSettlementComplete() {
			var journal = com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData.get(level.getServer());
			var observation = journal.read("goal_observation/" + goalId,
				com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState.class,
				level.registryAccess()).orElse(null);
			if (observation == null || observation.confirmedOutputCount() != observation.targetCount()
				|| observation.lastReceivedRunId().isEmpty()) return false;
			String runId = observation.lastReceivedRunId().orElseThrow().toString();
			CompoundTag persisted = journal.save(new CompoundTag(), level.registryAccess());
			Set<String> active = Set.of("input_contract/" + runId, "output_policy/" + runId,
				"output_contract/" + runId, "output_probe/" + runId, "signal_probe/" + runId,
				"output_ledger/" + runId, "safe_create_retry/" + runId);
			var entries = persisted.getList("Entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
			for (int index = 0; index < entries.size(); index++)
				if (active.contains(entries.getCompound(index).getString("Key"))) return false;
			return true;
		}

		private void assertDurableGoalSettlement() {
			helper.assertTrue(durableGoalSettlementComplete(), "confirmed credit and active-Run artifact removal survive composition reconstruction");
			var journal = com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData.get(level.getServer());
			var observation = journal.read("goal_observation/" + goalId,
				com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.GoalObservationState.class,
				level.registryAccess()).orElseThrow();
			UUID receivedRun = observation.lastReceivedRunId().orElseThrow();
			var recovery = journal.read("recovery/global", RecoveryLedger.State.class,
				level.registryAccess()).orElseThrow();
			helper.assertTrue(recovery.records().entrySet().stream().anyMatch(entry ->
				entry.getKey().runId().equals(receivedRun) && entry.getValue().isTerminal())
				|| recovery.acknowledgedTombstones().keySet().stream().anyMatch(key -> key.runId().equals(receivedRun)),
				"Run retirement preserves terminal RecoveryLedger anti-resurrection evidence");
		}

		private void exerciseFailedNewTaskAdmission() {
			ItemStack namedIron = new ItemStack(Items.IRON_INGOT, 7);
			namedIron.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
				net.minecraft.network.chat.Component.literal("Ambiguous admission input"));
			container(sourceChest).setItem(8, namedIron);
			container(sourceChest).setChanged();
			await(AWAIT, () -> com.simibubi.create.content.logistics.packagerLink.LogisticsManager
				.getSummaryOfNetwork(network, false).getStacks().stream().anyMatch(entry ->
					ItemStack.isSameItemSameComponents(entry.stack, namedIron) && entry.count == 7), () -> {
				FactoryPanelMenu menu = openProductionPanelMenu();
				var fence = FactoryOrchestrationServer.panelTaskCommandFence(panel).orElseThrow();
				var draft = new FactoryPanelNewTaskPacket.Draft(new StackKey(requestedOutputStack()),
					network, WAREHOUSE, 1, InputMode.EXACT, 0);
				var accepted = deliverNewTaskCommand(menu, new FactoryPanelNewTaskPacket(menu.opening(),
					fence.generation(), fence.newTaskRevision(), 831, draft));
				helper.assertTrue(accepted.result().admissionAccepted(), "ambiguous EXACT must first be accepted asynchronously");
				UUID failedGoal = accepted.result().acceptedGoalId().orElseThrow();
				await(AWAIT, () -> FactoryOrchestrationServer.lastPanelSubmissionDiagnostic(level.getServer(),
					panel.clusterId()).filter("AMBIGUOUS_INPUT"::equals).isPresent(), () -> {
					TaskListResponsePacket response = (TaskListResponsePacket) deliverDecodedViewRequest(
						new FactoryPanelViewRequestPacket(menu.opening(), FactoryPanelView.TASKS,
							java.util.OptionalLong.empty(), 0, 1, Optional.of(failedGoal), 832)).orElseThrow();
					helper.assertTrue(response.admission().isPresent(), "accepted FAILED admission requires a client terminal status");
					var status = response.admission().orElseThrow();
					helper.assertValueEqual(status.goalId(), failedGoal, "terminal status exact admission identity");
					helper.assertValueEqual(status.state(), com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks
						.TaskAdmissionStatus.State.FAILED, "normal asynchronous terminal failure");
					helper.assertValueEqual(status.diagnostic(), "AMBIGUOUS_INPUT", "actual failure diagnostic");
					var roundTrip = CBPackets.roundTripPanelForTest(response,
						new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess()));
					helper.assertValueEqual(roundTrip.admission(), response.admission(), "registered codec terminal status");
					TaskListResponsePacket foreign = (TaskListResponsePacket) deliverDecodedViewRequest(
						new FactoryPanelViewRequestPacket(menu.opening(), FactoryPanelView.TASKS,
							java.util.OptionalLong.empty(), 0, 1, Optional.of(UUID.randomUUID()), 833)).orElseThrow();
					helper.assertTrue(foreign.admission().isEmpty(), "another Goal cannot query this menu's admission");
					helper.assertFalse(ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId())
						.orElseThrow().goals().containsKey(failedGoal), "failed admission never publishes a Goal");
					container(sourceChest).setItem(8, ItemStack.EMPTY);
					container(sourceChest).setChanged();
					await(AWAIT, () -> com.simibubi.create.content.logistics.packagerLink.LogisticsManager.getSummaryOfNetwork(network, false)
						.getStacks().stream().noneMatch(entry -> ItemStack.isSameItemSameComponents(entry.stack, namedIron)),
						this::exerciseAcceptedNewTaskPublication, "removed ambiguous stock never refreshed");
				}, "accepted EXACT never reached AMBIGUOUS_INPUT");
			}, "named iron never entered live stock summary");
		}

		private void exerciseAcceptedNewTaskPublication() {
			FactoryPanelMenu menu = openProductionPanelMenu();
			var fence = FactoryOrchestrationServer.panelTaskCommandFence(panel).orElseThrow();
			var draft = new FactoryPanelNewTaskPacket.Draft(new StackKey(requestedOutputStack()),
				network, WAREHOUSE, 1, InputMode.EXACT, 0);
			var response = deliverNewTaskCommand(menu, new FactoryPanelNewTaskPacket(menu.opening(),
				fence.generation(), fence.newTaskRevision(), 821, draft));
			helper.assertTrue(response.result().admissionAccepted(),
				"real New Task admission must succeed: " + response.result().failure());
			helper.assertValueEqual(response.result().preservedDraft(), draft, "accepted draft identity");
			UUID accepted = response.result().acceptedGoalId().orElseThrow();
			helper.assertTrue(CBPackets.takePanelDeliveryForTest(operator).isEmpty(), "one admission result");
			await(AWAIT, () -> ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId())
				.map(state -> state.goals().containsKey(accepted)).orElse(false), () -> {
				var state = ProductionRuntimeServices.get(level.getServer()).snapshot(panel.clusterId()).orElseThrow();
				var ordered = state.goals().keySet().stream().sorted(java.util.Comparator.comparingLong(
					id -> state.submissionSequences().get(id))).toList();
				int page = ordered.indexOf(accepted);
				helper.assertTrue(page > 0, "newly published Goal must require a later one-row page");
				TaskListResponsePacket located = (TaskListResponsePacket) deliverDecodedViewRequest(
					new FactoryPanelViewRequestPacket(menu.opening(), FactoryPanelView.TASKS,
						java.util.OptionalLong.empty(), page, 1, Optional.of(accepted), 822)).orElseThrow();
				helper.assertValueEqual(located.projection().selectedGoalId(), Optional.of(accepted),
					"the actual published Goal must be selectable beyond the first page");
				helper.assertTrue(located.projection().revision() > fence.tasksRevision(),
					"admission publication must advance Tasks revision");
				helper.assertValueEqual(located.admission().orElseThrow().state(),
					com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskAdmissionStatus.State.PUBLISHED,
					"accepted admission must reach published terminal status");
				if (panelScenario == PanelScenario.REQUESTS_ONE_VIEW) {
					goalId = accepted;
					exerciseLivePanelAuthorityMatrix(menu, 0);
					return;
				}
				helper.succeed();
			}, "accepted admission never published a selectable Goal");
		}

		/** The next real Goal must discover after the listener host has conclusively left topology. */
		private void unloadListenerRelayAndSubmitRouteLossGoal() {
			FactoryOrchestrationServer.nativeComputerUnloaded(radioRelay.production());
			clearShell(radioRelayMin());
			FactoryOrchestrationServer.nativeComputerObserved(radioRelay.production());
			await(AWAIT, () -> FactoryOrchestrationServer.externalListenerPublicationsForTest(
				level.getServer(), radioRelay.computerId().orElseThrow()).isEmpty()
				&& !FactoryOrchestrationServer.radioEndpointRegisteredForTest(level.getServer(),
					listenerRelayPublication.relayEndpoint().key()), () -> helper.runAfterDelay(25, () -> {
				helper.assertTrue(FactoryOrchestrationServer.externalListenerPublicationsForTest(
					level.getServer(), radioRelay.computerId().orElseThrow()).isEmpty()
					&& !FactoryOrchestrationServer.externalListenerRelayCandidateForTest(level.getServer(),
						listenerRelayPublication.relayEndpoint().key())
					&& radioTopologyFrame().find(listenerRelayPublication.relayEndpoint().key()).isEmpty(),
					"a delayed stale observe must not revive the retired listener across topology cadence");
				submitListenerRouteLossGoal();
			}),
				"listener unload must retire its exact relay publication before a new route is admitted");
		}

		private void submitListenerRouteLossGoal() {
			UUID completedGoal = goalId;
			helper.assertTrue(sendPanelSubmit(new StackKey(requestedOutputStack()), WAREHOUSE,
				1, InputMode.BULK, 0),
				"a second production Panel submit must re-resolve the now-unreachable Agent route");
			await(AWAIT, () -> panel.goalDiagnostics().stream().filter(goal ->
				!goal.goalId().equals(completedGoal) && goal.state() != GoalState.TERMINAL).findFirst()
				.map(goal -> { goalId = goal.goalId(); return true; }).orElse(false), () ->
				awaitGoal(GoalState.WAITING, WaitReason.ROUTE, () -> {
					helper.assertTrue(FactoryOrchestrationServer.remoteAgentRadioStatus(level.getServer(),
						goalId).isEmpty(), "route discovery must reject the missing listener before it can"
							+ " create a replacement radio outbox entry");
					helper.succeed();
				}), this::admissionDiagnostics);
		}

		private void assertRemoteComputationRetired() {
			UUID exactLeaseId = Objects.requireNonNull(remoteComputationLeaseId,
				"remote computation lease was never observed");
			helper.assertTrue(FactoryOrchestrationServer.productionRemoteComputation(
				level.getServer(), goalId).isEmpty(),
				"downstream durable output must retire the source exchange");
			helper.assertTrue(FactoryOrchestrationServer.productionRemoteComputationJob(
				level.getServer(), goalId).isEmpty(),
				"downstream durable output must retire the receiver job");
			helper.assertTrue(FactoryOrchestrationServer.radioGoalOwner(
				level.getServer(), exactLeaseId).isEmpty(),
				"the completed exact lease must leave the router active set");
			helper.assertValueEqual(FactoryOrchestrationServer
				.productionRemoteComputationActiveRouteCount(level.getServer(), exactLeaseId), 0,
				"the completed exact lease's protocol routes must leave the router tick scan set");
			helper.assertValueEqual(FactoryOrchestrationServer
				.productionRetiredRemoteComputationCount(level.getServer(), goalId), 1,
				"serial remote Runs must share one durable logical-child tombstone");
			helper.assertTrue(FactoryOrchestrationServer
				.productionRetiredRemoteComputationCount(level.getServer())
				<= FactoryRuntimeConfig.fromServerConfig().recoveryLedgerCapacity(),
				"global remote tombstones must remain within configured recovery capacity");
			ClusterEngineState source = ProductionRuntimeServices.get(level.getServer())
				.snapshot(panel.clusterId()).orElseThrow();
			var sourceProcesses = source.processes().values().stream()
				.filter(candidate -> candidate.goalId().equals(goalId)).toList();
			helper.assertTrue(sourceProcesses.size() <= 1 && sourceProcesses.stream()
				.allMatch(process -> process.childRevisionStates().size() <= 1),
				"remote EXACT lineage must stay beneath the current-schema child bound");
			helper.assertTrue(com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt
				.load(com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt
					.save(source)).isPresent(),
				"retired production runtime must survive a genuine current-schema round trip");
		}

		private boolean remoteComputationRetired() {
			UUID exactLeaseId = remoteComputationLeaseId;
			return exactLeaseId != null
				&& FactoryOrchestrationServer.productionRemoteComputation(
					level.getServer(), goalId).isEmpty()
				&& FactoryOrchestrationServer.productionRemoteComputationJob(
					level.getServer(), goalId).isEmpty()
				&& FactoryOrchestrationServer.radioGoalOwner(
					level.getServer(), exactLeaseId).isEmpty()
				&& FactoryOrchestrationServer.productionRemoteComputationActiveRouteCount(
					level.getServer(), exactLeaseId) == 0
				&& FactoryOrchestrationServer.productionRetiredRemoteComputationCount(
					level.getServer(), goalId) == 1;
		}

		private String remoteComputationRetirementDiagnostics() {
			UUID exactLeaseId = remoteComputationLeaseId;
			return "exact remote computation retirement did not converge: lease=" + exactLeaseId
				+ ", exchange=" + FactoryOrchestrationServer.productionRemoteComputation(
					level.getServer(), goalId)
				+ ", job=" + FactoryOrchestrationServer.productionRemoteComputationJob(
					level.getServer(), goalId)
				+ ", exactOwner=" + (exactLeaseId == null ? "uncaptured"
					: FactoryOrchestrationServer.radioGoalOwner(level.getServer(), exactLeaseId))
				+ ", exactRoutes=" + (exactLeaseId == null ? "uncaptured"
					: FactoryOrchestrationServer.productionRemoteComputationActiveRouteCount(
						level.getServer(), exactLeaseId))
				+ ", globalLeases=" + FactoryOrchestrationServer
					.productionRemoteComputationActiveLeaseCount(level.getServer())
				+ ", globalRoutes=" + FactoryOrchestrationServer
					.productionRemoteComputationActiveRouteCount(level.getServer())
				+ ", retiredForGoal=" + FactoryOrchestrationServer
					.productionRetiredRemoteComputationCount(level.getServer(), goalId);
		}

		private void noDuplicate(int expected, Runnable next) {
			helper.runAfterDelay(25, () -> {
				helper.assertValueEqual(nativeOrderCount, expected,
					"durable evidence must prevent duplicate native orders");
				next.run();
			});
		}

		private void awaitProductionDispatch(BlockPos sourcePos, String expectedAddress,
			BlockPos destinationPos, Runnable delivered) {
			await(AWAIT, this::productionDispatchSubmitted, () ->
			await(AWAIT, () -> hasPhysicalPackage(sourcePos, expectedAddress), () -> {
				PackagerBlockEntity source = packager(sourcePos);
				helper.assertValueEqual(PackageItem.getAddress(source.heldBox), expectedAddress,
					"production gateway must write the selected Pattern address");
				if (scenario == Scenario.DISPATCH_RELOAD && nativeOrderCount == 0)
					reloadComputer();
				nativeOrderCount++; nativeAddresses.add(expectedAddress);
				helper.runAfterDelay(PackagerBlockEntity.CYCLE + 2, () -> {
					if (panelScenario == PanelScenario.MONITOR_GOAL_TREE && destinationPos.equals(inputPackager)) {
						var response = monitorGoal((FactoryPanelMenu) operator.containerMenu, goalId);
						helper.assertTrue(response.projection().goalTree().orElseThrow().nodes().stream()
							.anyMatch(node -> node.kind() == GoalTreeProjection.TreeNodeKind.HANDOFF),
							"real input command with a physical source package must show handoff");
						helper.assertValueEqual(count(inputChest, Items.IRON_INGOT), 0L, "handoff precedes physical input receipt");
					}
					ItemStack box = source.inventory.extractItem(0, 1, false);
					helper.assertFalse(box.isEmpty(), "native order must become a physical package");
					PackagerBlockEntity destination = packager(destinationPos);
					long ironBefore = count(inputChest, Items.IRON_INGOT);
					long copperBefore = count(inputChest, Items.COPPER_INGOT);
					ItemStack remainder = destination.inventory.insertItem(0, box, false);
					helper.assertTrue(remainder.isEmpty(),
						"destination.inventory.insertItem() must accept and unpack the package");
					if (destinationPos.equals(inputPackager)) {
						long ironDelta = count(inputChest, Items.IRON_INGOT) - ironBefore;
						long copperDelta = count(inputChest, Items.COPPER_INGOT) - copperBefore;
						helper.assertTrue(ironDelta > 0 || copperDelta > 0,
							"native input package must increment the managed input inventory");
						deliveredIron += ironDelta;
						deliveredCopper += copperDelta;
					}
					helper.runAfterDelay(PackagerBlockEntity.CYCLE + 2, delivered);
				});
			}, () -> "production gateway did not dispatch native address " + expectedAddress
				+ "; " + productionDiagnostics(sourcePos)),
				() -> "production plan was never submitted for native address " + expectedAddress
					+ "; " + productionDiagnostics(sourcePos));
		}

		private boolean productionDispatchSubmitted() {
			return FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
				.map(status -> status.dispatchSubmitted() || status.inputPhase().isPresent())
				.orElse(false);
		}

		private boolean hasPhysicalPackage(BlockPos sourcePos, String address) {
			PackagerBlockEntity source = packager(sourcePos);
			return !source.heldBox.isEmpty() && address.equals(PackageItem.getAddress(source.heldBox));
		}

		private void awaitGoal(GoalState state, WaitReason reason, Runnable reached) {
			await(AWAIT, () -> {
				FactoryOrchestrationServer.GoalDiagnostic goal = goal();
				return goal != null && goal.state() == state && goal.waitReason() == reason;
			}, reached, () -> {
				FactoryOrchestrationServer.GoalDiagnostic current = goal();
				return "Goal did not reach " + state + "/" + reason + "; actual="
					+ (current == null ? "none" : current.state() + "/" + current.waitReason()
						+ ", requested=" + current.requestedActualCount()
						+ ", received=" + current.receivedActualCount()
						+ ", remaining=" + current.remainingActualCount())
					+ ", warehouse=" + count(warehouseChest, Items.GOLD_INGOT)
					+ "; " + productionDiagnostics(sourcePackager);
			});
		}

		private FactoryOrchestrationServer.GoalDiagnostic goal() {
			return panel.goalDiagnostics().stream()
				.filter(row -> row.goalId().equals(goalId)).findFirst().orElse(null);
		}

		private void assertGoalCounts(long requested, long received, long remaining) {
			FactoryOrchestrationServer.GoalDiagnostic goal = Objects.requireNonNull(goal());
			helper.assertValueEqual(goal.requestedActualCount(), requested,
				"Panel must retain the frozen requested delta");
			helper.assertValueEqual(goal.receivedActualCount(), received,
				"Panel must credit only actual stock evidence");
			helper.assertValueEqual(goal.remainingActualCount(), remaining,
				"remaining count must match actual stock evidence");
		}

		private void configureEndpoint(BlockPos endpoint, AgentEndpointRole role, String address,
			Runnable configured) {
			configureEndpoint(endpoint, agentId, role, address, 1, CommunicationFrequency.of("7"),
				scenario == Scenario.SIGNAL_COMPLETION && role == AgentEndpointRole.OUTPUT
					? AgentCompletionMode.SIGNAL : AgentCompletionMode.TARGET,
				scenario == Scenario.SIGNAL_COMPLETION && role == AgentEndpointRole.OUTPUT
					? Optional.of(new AgentSignalSelector(SpaceAddress.capture(level, signalPos),
						Direction.UP, 1)) : Optional.empty(),
				AgentEndpointState.OutputMode.WAREHOUSE_ALL, WAREHOUSE, configured);
		}

		private void configureEndpoint(BlockPos endpoint, UUID configuredAgentId,
			AgentEndpointRole role, String address, Runnable configured) {
			AgentCompletionMode completionMode = scenario == Scenario.SIGNAL_COMPLETION
				&& role == AgentEndpointRole.OUTPUT ? AgentCompletionMode.SIGNAL
				: AgentCompletionMode.TARGET;
			Optional<AgentSignalSelector> selector = completionMode == AgentCompletionMode.SIGNAL
				? Optional.of(new AgentSignalSelector(SpaceAddress.capture(level, signalPos),
					Direction.UP, 1)) : Optional.empty();
			configureEndpoint(endpoint, configuredAgentId, role, address, 1,
				CommunicationFrequency.of("7"), completionMode, selector,
				AgentEndpointState.OutputMode.WAREHOUSE_ALL, WAREHOUSE, configured);
		}

		private void configureEndpoint(BlockPos endpoint, UUID configuredAgentId,
			AgentEndpointRole role, String address, int routePriority,
			CommunicationFrequency frequency, AgentCompletionMode completionMode,
			Optional<AgentSignalSelector> selector, AgentEndpointState.OutputMode outputMode,
			String warehouseAddress, Runnable configured) {
			FactoryPanelBlockEntity commandPanel = isRadio() ? radioOwnerPanel() : panel;
			await(800, () -> configureEndpoint(endpoint, configuredAgentId, role, address,
				routePriority, frequency, completionMode, selector, outputMode, warehouseAddress),
				() -> await(800, () -> {
					BlockEntity loaded = level.getBlockEntity(endpoint);
					return loaded instanceof com.nobodiiiii.createbiotech.content.factorycluster.agent
						.FactoryPanelAgentAccess access
						&& access.createBiotech$getAgentConfiguration().isPresent();
				}, configured, "server-authoritative endpoint configuration did not persist " + address),
				() -> {
					ClusterEngineState state = ProductionRuntimeServices.get(level.getServer())
						.snapshot(commandPanel.clusterId()).orElse(null);
					return "server-authoritative endpoint configuration rejected " + address
						+ "; scenario=" + scenario + ", distance="
						+ commandPanel.getBlockPos().distSqr(endpoint) + ", runtime="
						+ (state == null ? "missing" : state.epochId() + "/" + state.generation()
							+ ", members=" + state.members().size() + ", processes="
							+ state.processes().size()) + ", endpoint=" + level.getBlockEntity(endpoint);
				});
		}

		private boolean configureEndpoint(BlockPos endpoint, UUID configuredAgentId,
			AgentEndpointRole role, String address, int routePriority,
			CommunicationFrequency frequency, AgentCompletionMode completionMode,
			Optional<AgentSignalSelector> selector, AgentEndpointState.OutputMode outputMode,
			String warehouseAddress) {
			FactoryPanelBlockEntity commandPanel = isRadio() ? radioOwnerPanel() : panel;
			ensurePanelMenu(commandPanel);
			operator.moveTo(Vec3.atCenterOf(commandPanel.getBlockPos()));
			return FactoryOrchestrationServer.configureAgentEndpointLocally(operator, commandPanel,
				SpaceAddress.capture(level, endpoint), configuredAgentId, role, address, routePriority,
				frequency, completionMode, selector, outputMode, warehouseAddress);
		}

		private boolean sendPanelSubmit(StackKey output, String deliveryAddress, long multiplier,
			InputMode mode, int priority) {
			FactoryPanelMenu menu = ensurePanelMenu(panel);
			var fence = FactoryOrchestrationServer.panelTaskCommandFence(panel).orElse(null);
			if (fence == null) return false;
			operator.moveTo(Vec3.atCenterOf(panel.getBlockPos()));
			return menu.execute(new FactoryPanelNewTaskPacket(menu.opening(), fence.generation(),
				fence.newTaskRevision(), new FactoryPanelNewTaskPacket.Draft(output, network,
					deliveryAddress, multiplier, mode, priority)), operator);
		}

		private String lastPanelCancelFailure = "none";
		private boolean sendPanelCancel(UUID cancelledGoalId) {
			FactoryPanelMenu menu = ensurePanelMenu(panel);
			var fence = FactoryOrchestrationServer.panelTaskCommandFence(panel).orElse(null);
			if (fence == null) { lastPanelCancelFailure = "no task command publication"; return false; }
			operator.moveTo(Vec3.atCenterOf(panel.getBlockPos()));
			boolean applied = menu.execute(new FactoryPanelGoalCommandPacket(menu.opening(), fence.generation(),
				fence.tasksRevision(), cancelledGoalId, new FactoryPanelGoalCommandPacket.Cancel()),
				operator);
			if (!applied) {
				var state = ProductionRuntimeServices.get(level.getServer())
					.peekSnapshotForPanelView(panel.clusterId()).orElseThrow();
				lastPanelCancelFailure = "menuValid=" + menu.stillValid(operator)
					+ ",openingEpoch=" + menu.opening().epochId() + ",stateEpoch=" + state.epochId()
					+ ",fence=" + fence + ",stateGeneration=" + state.generation()
					+ ",actions=" + com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine
						.FactoryGoalTransitionPolicy.allowedActions(state, cancelledGoalId);
			}
			return applied;
		}

		private boolean sendLocalForceResetAtActor(BlockPos actorPos, BlockPos endpointPos,
			UUID configuredAgentId, FactoryOrchestrationServer.AgentDiagnostic expected) {
			operator.moveTo(Vec3.atCenterOf(actorPos));
			operator.setShiftKeyDown(true);
			operator.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			InteractionResult result = operator.gameMode.useItemOn(operator, level, ItemStack.EMPTY,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(endpointPos),
					Direction.UP, endpointPos, false));
			operator.setShiftKeyDown(false);
			return result.consumesAction() && panel.agentDiagnostics().stream()
				.filter(agent -> agent.agentId().equals(configuredAgentId))
				.anyMatch(agent -> agent.resetGeneration() > expected.resetGeneration()
					|| agent.stateRevision() > expected.stateRevision());
		}

		private FactoryPanelMenu ensurePanelMenu(FactoryPanelBlockEntity commandPanel) {
			if (operator == null) throw new IllegalStateException("production Panel operator is absent");
			operator.moveTo(Vec3.atCenterOf(commandPanel.getBlockPos()));
			FactoryPanelMenu menu = operator.containerMenu instanceof FactoryPanelMenu current
				&& current.ownsServerBlockEntity(commandPanel) ? current : null;
			if (menu != null) return menu;
			operator.closeContainer();
			menu = (FactoryPanelMenu) commandPanel.createMenu(7, operator.getInventory(), operator);
			operator.containerMenu = menu;
			if (!menu.ownsServerBlockEntity(commandPanel))
				throw new IllegalStateException("real Factory Panel menu has the wrong owner");
			return menu;
		}
		private void reloadComputer() {
			CompoundTag saved = computer.saveWithFullMetadata(level.registryAccess());
			BlockEntity loaded = BlockEntity.loadStatic(computer.getBlockPos(), computer.getBlockState(),
				saved, level.registryAccess());
			helper.assertTrue(loaded instanceof ComputerBlockEntity,
				"real Computer must decode its durable production epoch");
			computer.invalidate(); level.removeBlockEntity(computer.getBlockPos());
			level.setBlockEntity(Objects.requireNonNull(loaded)); computer = (ComputerBlockEntity) loaded;
		}

		private void unloadCoreForPendingAdmission() {
			pendingAdmissionCoreSnapshot = core.saveWithFullMetadata(level.registryAccess());
			core.invalidate();
			level.removeBlockEntity(corePos);
		}

		private void reloadPendingAdmissionCore() {
			BlockEntity loaded = BlockEntity.loadStatic(corePos, level.getBlockState(corePos),
				Objects.requireNonNull(pendingAdmissionCoreSnapshot), level.registryAccess());
			helper.assertTrue(loaded instanceof LibraryBlockEntity,
				"the exact unloaded Core must decode from its current schema");
			level.setBlockEntity(Objects.requireNonNull(loaded));
			core = (LibraryBlockEntity) loaded;
		}

		private boolean usesAgent() {
			return switch (scenario) {
				case LOCAL_STANDARD_AGENT, LOCAL_PURE_EXPANSION, EXACT_TWO, SEQUENCED_TWO_ADDRESS,
					KNOWN_OFFLINE_AGENT, CANCEL_DRAIN, LOST_ACK, RECOVERY_BACKPRESSURE,
					LOCAL_FORCE_RESET, DISPATCH_RELOAD, RADIO_ONE_HOP, RADIO_FREQUENCY_MISMATCH,
					TRADER_RELAY, LISTENER_RELAY, ORDERLY_RADIO_RESTART, REMOTE_COMPUTE,
					SIGNAL_COMPLETION,
					COMPONENT_OUTPUT_RELOAD -> true;
				default -> false;
			};
		}

		private boolean isRadio() {
			return scenario == Scenario.RADIO_ONE_HOP || scenario == Scenario.RADIO_FREQUENCY_MISMATCH
				|| scenario == Scenario.TRADER_RELAY || isListenerRelayScenario()
				|| scenario == Scenario.REMOTE_COMPUTE
				|| scenario == Scenario.LOST_ACK || scenario == Scenario.RADIO_TOPOLOGY;
		}

		private boolean isListenerRelayScenario() {
			return scenario == Scenario.LISTENER_RELAY || scenario == Scenario.ORDERLY_RADIO_RESTART;
		}

		private String patternJson() {
			if (scenario == Scenario.SEQUENCED_TWO_ADDRESS)
				return "{\"version\":2,\"type\":\"sequenced\",\"inputs\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],"
					+ "\"outputs\":[{\"item\":\"minecraft:gold_ingot\",\"count\":1}],\"stages\":["
					+ "{\"address\":\"stage-a\",\"inputs\":[]},{\"address\":\"stage-b\",\"inputs\":[{\"item\":\"minecraft:copper_ingot\",\"count\":1}]}]}";
			if (scenario == Scenario.COMPONENT_OUTPUT_RELOAD)
				return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],"
					+ "\"outputs\":[{\"item\":\"minecraft:gold_ingot\",\"count\":1,"
					+ "\"components\":{\"minecraft:max_stack_size\":1}}],\"address\":\"agent-input\"}";
			return "{\"version\":2,\"type\":\"standard\",\"inputs\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],"
				+ "\"outputs\":[{\"item\":\"minecraft:gold_ingot\",\"count\":1}],\"address\":\"agent-input\"}";
		}

		private ItemStack requestedOutputStack() {
			ItemStack output = new ItemStack(Items.GOLD_INGOT);
			if (scenario == Scenario.COMPONENT_OUTPUT_RELOAD)
				output.set(DataComponents.MAX_STACK_SIZE, 1);
			return output;
		}

		private void placeRadioComputers() {
			BlockPos targetMin = radioTargetMin();
			buildShell(targetMin);
			radioTarget = placeComputer(targetMin.offset(1, 1, 1), stable("radio-target"));
			installResident(radioTarget, VillagerProfession.CLERIC, false);
			level.setBlock(targetMin.offset(0, 1, 0),
				CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
			if (scenario == Scenario.REMOTE_COMPUTE) {
				BlockPos receiverMin = remoteComputeReceiverMin();
				buildShell(receiverMin);
				radioReceiver = placeComputer(receiverMin.offset(1, 1, 1),
					stable("radio-receiver"));
				installResident(radioReceiver, VillagerProfession.CLERIC, false);
				level.setBlock(receiverMin.offset(0, 1, 0),
					CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
			}
			if (scenario == Scenario.TRADER_RELAY || isListenerRelayScenario()) {
				BlockPos relayMin = radioRelayMin();
				buildShell(relayMin);
				radioRelay = placeComputer(relayMin.offset(1, 1, 1), stable("radio-relay"));
				installResident(radioRelay, VillagerProfession.NONE, true);
				level.setBlock(relayMin.offset(0, 1, 0),
					CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
			}
		}

		private void loadTraderChunksForNaturalTicks() {
			// Ask the server for each real owner chunk once. Thereafter GameTest's scheduled
			// server ticks—not the readiness poll—advance the Computer and Create block entities.
			for (BlockPos position : List.of(computer.getBlockPos(), radioTarget.getBlockPos(),
			radioRelay.getBlockPos(), sourcePackager, inputPackager, outputPackager,
			warehousePackager, inputEndpointPos, outputEndpointPos, warehousePackager.above())) {
				level.getChunkAt(position);
			}
		}

		private void bindRadioComputers(ServerPlayer player) {
			if (!isRadio()) return;
			bindRadioComputer(player, radioOwnerPanel(), radioTargetMin(), radioTarget,
				"radio-target");
			if (scenario == Scenario.REMOTE_COMPUTE)
				bindRadioComputer(player, remoteComputeReceiverPanel(), remoteComputeReceiverMin(),
					radioReceiver, "radio-receiver");
			if (scenario == Scenario.TRADER_RELAY || isListenerRelayScenario())
				bindRadioComputer(player, biotechPanel(radioRelayMin().offset(0, 1, 0)), radioRelayMin(), radioRelay,
					"radio-relay");
		}

		private FactoryPanelBlockEntity radioOwnerPanel() {
			return biotechPanel(radioTargetMin().offset(0, 1, 0));
		}
		private FactoryPanelBlockEntity remoteComputeReceiverPanel() {
			if (scenario != Scenario.REMOTE_COMPUTE)
				throw new IllegalStateException("scenario has no remote computation receiver");
			return biotechPanel(remoteComputeReceiverMin().offset(0, 1, 0));
		}
		private BlockPos radioTargetMin() {
			return scenario == Scenario.TRADER_RELAY || isListenerRelayScenario()
				? abs(0, 72, 0) : abs(11, 7, 3);
		}
		private BlockPos remoteComputeReceiverMin() { return abs(15, 7, 7); }
		private BlockPos remoteCapacityInputPos() { return abs(15, 3, 5); }
		private BlockPos remoteCapacityOutputPos() { return abs(16, 3, 5); }
		private BlockPos radioRelayMin() { return abs(6, 38, 4); }
		private void clearShell(BlockPos min) {
			for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
				level.removeBlock(min.offset(x, y, z), false);
		}

		private void bindRadioComputer(ServerPlayer player, FactoryPanelBlockEntity radioPanel,
			BlockPos shellMin, ComputerBlockEntity radioComputer, String alias) {
			player.moveTo(Vec3.atCenterOf(radioPanel.getBlockPos()));
			helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, radioPanel,
				List.of(new LogisticsBinding(network))), ClusterBindingService.BindResult.OK,
				"radio endpoint must authorize logistics on its automatically formed Cluster");
			helper.assertTrue(radioComputer.production().epoch().filter(epoch -> epoch.groupId().equals(radioPanel.clusterId())).isPresent(),
				"radio endpoint must be a real active Computer cluster");
		}

		private void placePackager(BlockPos chestPos, BlockPos packagerPos) {
			level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(packagerPos, AllBlocks.PACKAGER.getDefaultState()
				.setValue(PackagerBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
			BlockPos linkPos = packagerPos.north();
			level.setBlock(linkPos, AllBlocks.STOCK_LINK.getDefaultState()
				.setValue(PackagerLinkBlock.FACE, AttachFace.WALL)
				.setValue(PackagerLinkBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
			PackagerLinkBlockEntity link = (PackagerLinkBlockEntity) level.getBlockEntity(linkPos);
			helper.assertTrue(link.getPackager() == packager(packagerPos),
				"Stock Link must be physically attached to its exact Packager");
			LogisticallyLinkedBehaviour.remove(link.behaviour);
			link.behaviour.freqId = network;
			LogisticallyLinkedBehaviour.keepAlive(link.behaviour);
		}

		private com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity placeNativePanel(
			BlockPos pos, String address, Item filter, boolean restocker) {
			return placeNativePanel(pos, address, new ItemStack(filter), restocker);
		}

		private com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity placeNativePanel(
			BlockPos pos, String address, ItemStack filter, boolean restocker) {
			BlockState state = AllBlocks.FACTORY_GAUGE.getDefaultState()
				.setValue(com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.FACE,
					AttachFace.FLOOR)
				.setValue(com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.FACING,
					Direction.NORTH);
			level.setBlock(pos, state, Block.UPDATE_ALL);
			var nativePanel = (com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity)
				level.getBlockEntity(pos);
			nativePanel.addPanel(PanelSlot.BOTTOM_LEFT, network);
			FactoryPanelBehaviour behaviour = nativePanel.panels.get(PanelSlot.BOTTOM_LEFT);
			behaviour.setNetwork(network); behaviour.recipeAddress = address; behaviour.recipeOutput = 1;
			behaviour.setFilter(filter.copyWithCount(1)); nativePanel.restocker = restocker;
			nativePanel.setChanged();
			FactoryOrchestrationServer.nativePanelLoaded(nativePanel);
			return nativePanel;
		}

		private void placeCore(BlockPos lower) {
			BlockState state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
				.setValue(LibraryCoreBlock.FACING, Direction.NORTH)
				.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
			level.setBlock(lower, state, Block.UPDATE_ALL);
			LibraryBlockEntity placed = patternCore(lower);
			int priority = scenario == Scenario.PENDING_ADMISSION_RELOAD ? 9 : 0;
			helper.assertTrue(placed.applyPlacementData(LibraryCoreItemData.configured(
				CommunicationFrequency.of("7"), priority, Optional.empty(), 0)),
				"the fixture must apply the same frozen routing transaction as item placement");
			level.setBlock(lower.above(), state.setValue(LibraryCoreBlock.HALF,
				DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		}

		private void buildShell(BlockPos min) {
			buildShell(min, 3);
		}

		private void buildShell(BlockPos min, int width) {
			for (int x = 0; x < width; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 3; z++) {
				boolean shell = x == 0 || x == width - 1 || y == 0 || y == 3 || z == 0 || z == 2;
				level.setBlock(min.offset(x, y, z), shell
					? CBBlocks.COMPUTER_CASING.get().defaultBlockState()
					: Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}

		private ComputerBlockEntity placeComputer(BlockPos pos, UUID id) {
			BlockPos support = pos.below();
			if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP))
				level.setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			BlockState lower = CBBlocks.COMPUTER.get().defaultBlockState()
				.setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
			level.setBlock(pos, lower, Block.UPDATE_ALL);
			level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER),
				Block.UPDATE_ALL);
			ComputerBlockEntity value = (ComputerBlockEntity) level.getBlockEntity(pos);
			CompoundTag tag = value.saveWithoutMetadata(level.registryAccess());
			tag.getCompound("ComputerData").putUUID("ComputerId", id);
			value.loadWithComponents(tag, level.registryAccess()); return value;
		}

		private void installResident(ComputerBlockEntity target, VillagerProfession profession,
			boolean wandering) {
			LivingEntity resident;
			if (wandering) resident = Objects.requireNonNull(EntityType.WANDERING_TRADER.create(level));
			else {
				Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
				villager.setVillagerData(villager.getVillagerData().setType(VillagerType.PLAINS)
					.setProfession(profession).setLevel((scenario == Scenario.REMOTE_COMPUTE
						&& (target == computer || target == radioTarget))
						|| (scenario == Scenario.LOCAL_PURE_EXPANSION && (target == computer || target == radioReplica)) ? 1 : 5));
				resident = villager;
			}
			resident.setCustomName(Component.literal("production-computer"));
			ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
			if (!CapturedEntityBoxHelper.captureEntity(box, resident))
				throw new IllegalStateException("could not capture Computer resident");
			ServerPlayer player = player(target.getBlockPos(), "factory-resident");
			player.setItemInHand(InteractionHand.MAIN_HAND, box);
			InteractionResult result = player.gameMode.useItemOn(player, level, box,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(target.getBlockPos()),
					Direction.UP, target.getBlockPos(), false));
			helper.assertTrue(result.consumesAction(), "real Computer must accept its resident");
		}

		private void selectPanelAndWrenchCasing(ServerPlayer player, BlockPos selectedPanel,
			BlockPos casing) {
			ClusterBindingSelection.clear(player);
			ClusterBindingSelection.begin(player, biotechPanel(selectedPanel));
			UseOnContext casingContext = new UseOnContext(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(Vec3.atCenterOf(casing), Direction.UP, casing, false));
			InteractionResult bound = ((ComputerCasingBlock) CBBlocks.COMPUTER_CASING.get())
				.onSneakWrenched(level.getBlockState(casing), casingContext);
			if (!bound.consumesAction()) throw new IllegalStateException("Computer binding failed");
		}

		private ServerPlayer player(BlockPos pos, String name) {
			FakePlayer value = new LogisticsMenuPlayer(level,
				new GameProfile(UUID.randomUUID(), name));
			value.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			value.moveTo(Vec3.atCenterOf(pos)); return value;
		}

		private FactoryPanelBlockEntity biotechPanel(BlockPos pos) {
			return (FactoryPanelBlockEntity) level.getBlockEntity(pos);
		}
		private LibraryBlockEntity patternCore(BlockPos pos) {
			return (LibraryBlockEntity) level.getBlockEntity(pos);
		}
		private PackagerBlockEntity packager(BlockPos pos) {
			return (PackagerBlockEntity) level.getBlockEntity(pos);
		}
		private Container container(BlockPos pos) { return (Container) level.getBlockEntity(pos); }
		private BlockPos abs(int x, int y, int z) { return helper.absolutePos(new BlockPos(x, y, z)); }
		private UUID stable(String kind) {
			return UUID.nameUUIDFromBytes((kind + ":" + network).getBytes(StandardCharsets.UTF_8));
		}

		private long count(BlockPos pos, Item item) {
			Container inventory = container(pos); long count = 0;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++)
				if (inventory.getItem(slot).is(item)) count += inventory.getItem(slot).getCount();
			return count;
		}

		private long countExact(BlockPos pos, ItemStack exact) {
			Container inventory = container(pos); long count = 0;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack present = inventory.getItem(slot);
				if (ItemStack.isSameItemSameComponents(present, exact)) count += present.getCount();
			}
			return count;
		}

		private void put(BlockPos pos, Item item, int count) {
			put(pos, new ItemStack(item), count);
		}

		private void put(BlockPos pos, ItemStack exact, int count) {
			Container inventory = container(pos);
			for (int slot = 0; slot < inventory.getContainerSize(); slot++)
				if (inventory.getItem(slot).isEmpty()) {
					inventory.setItem(slot, exact.copyWithCount(count)); return;
				}
			throw new IllegalStateException("fixture inventory is full");
		}

		private void consume(BlockPos pos, Item item, int count) {
			Container inventory = container(pos);
			int remaining = count;
			for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (!stack.is(item)) continue;
				int removed = Math.min(remaining, stack.getCount());
				stack.shrink(removed);
				remaining -= removed;
			}
			helper.assertValueEqual(remaining, 0,
				"the simulated factory must consume only physically delivered Agent inputs");
		}

		private void await(int remaining, BooleanSupplier ready, Runnable success, String failure) {
			await(remaining, ready, success, () -> failure);
		}

		private void await(int remaining, BooleanSupplier ready, Runnable success,
			Supplier<String> failure) {
			if (ready.getAsBoolean()) { success.run(); return; }
			if (remaining <= 0) { helper.fail(failure.get()); return; }
			// Production compute is wall-clock paced while GameTest ticks are uncapped.
			LockSupport.parkNanos(1_000_000L);
			helper.runAfterDelay(1, () -> await(remaining - 1, ready, success, failure));
		}

		private String productionDiagnostics(BlockPos sourcePos) {
			ClusterEngineState state = ProductionRuntimeServices.get(level.getServer())
				.snapshot(panel.clusterId()).orElse(null);
			FactoryOrchestrationServer.GoalDiagnostic goal = goal();
			PackagerBlockEntity source = packager(sourcePos);
			long availableIron = source.getAvailableItems().getCountOf(new ItemStack(Items.IRON_INGOT));
			long availableGold = source.getAvailableItems().getCountOf(new ItemStack(Items.GOLD_INGOT));
			var production = FactoryOrchestrationServer.productionStatus(level.getServer(), goalId)
				.orElse(null);
			return "scenario=" + scenario + ", goal=" + (goal == null ? "none"
				: goal.state() + "/" + goal.waitReason()) + ", processes="
				+ (goal == null ? List.of() : goal.processes().stream()
					.map(process -> process.kind() + ":" + process.state() + "/" + process.waitReason())
					.toList()) + ", agents=" + panel.agentDiagnostics().size()
				+ ", pending=" + (state == null ? -1 : state.pendingIntents().size())
				+ ", uncertain=" + (state == null ? -1 : state.uncertainIntentIds().size())
				+ ", intentFences=" + (state == null ? List.of() : state.pendingIntents().values()
					.stream().map(intent -> intent.fence().validationFence()).toList())
				+ ", computerFence=" + computer.production().radioEndpointGeneration()
				+ ", lastPanelCancelFailure=" + lastPanelCancelFailure
				+ ", observationFence=" + NativePackageObservation.installedRecoveryFence(
					level.getServer()).map(fence -> "global=" + fence.globallyFenced()
						+ ", input=" + fence.routeFenced(new CreateRouteKey(network,
							expectedInputAddress()))).orElse("missing")
				+ ", links=" + LogisticallyLinkedBehaviour.getAllPresent(network, false).size()
				+ ", production=" + production
				+ ", sourceAvailableIron=" + availableIron + ", sourceAvailableGold=" + availableGold
				+ ", sourceBusy=" + source.isTooBusyFor(
					com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour.RequestType.PLAYER)
				+ ", sourceHeld=" + source.heldBox
				+ ", heldAddress=" + PackageItem.getAddress(source.heldBox)
				+ ", sourceIron=" + count(sourceChest, Items.IRON_INGOT)
				+ ", sourceCopper=" + count(sourceChest, Items.COPPER_INGOT)
				+ ", inputIron=" + count(inputChest, Items.IRON_INGOT)
				+ ", inputCopper=" + count(inputChest, Items.COPPER_INGOT)
				+ ", outputGold=" + count(outputChest, Items.GOLD_INGOT)
				+ ", warehouseGold=" + count(warehouseChest, Items.GOLD_INGOT)
				+ ", nativeOrders=" + nativeOrderCount
				+ traderRadioDiagnostics(state);
		}

		private String traderRadioDiagnostics(ClusterEngineState sourceState) {
			if (scenario != Scenario.TRADER_RELAY) return "";
			ClusterEngineState targetState = radioTarget.production().epoch().flatMap(epoch ->
				ProductionRuntimeServices.get(level.getServer()).snapshot(epoch.groupId())).orElse(null);
			return ", traderRadio={source=" + radioSummary(computer)
				+ ", target=" + radioSummary(radioTarget)
				+ ", relay=" + radioSummary(radioRelay)
				+ ", status=" + FactoryOrchestrationServer.remoteAgentRadioStatus(level.getServer(), goalId)
				+ ", sourceOwners=" + (sourceState == null ? List.of() : sourceState.processes().values()
					.stream().map(process -> process.kind() + ":" + process.owningComputerId()).toList())
				+ ", targetOwners=" + (targetState == null ? List.of() : targetState.processes().values()
					.stream().map(process -> process.kind() + ":" + process.owningComputerId()).toList()) + "}";
		}

		private static String radioSummary(ComputerBlockEntity value) {
			return value.computerId().orElse(null) + "/" + value.production().epoch().map(epoch -> epoch.groupId())
				.orElse(null) + "/online=" + value.production().structureOnline()
				+ "/g=" + value.production().radioEndpointGeneration();
		}

		private String expectedInputAddress() {
			return scenario == Scenario.SEQUENCED_TWO_ADDRESS ? STAGE_A : INPUT;
		}

		private static ItemStack writableBook(String page) {
			ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
			stack.set(DataComponents.WRITABLE_BOOK_CONTENT,
				new WritableBookContent(List.of(Filterable.passThrough(page))));
			return stack;
		}
	}
}
