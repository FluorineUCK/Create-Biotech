package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionLayout;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionStructure;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpochCodec;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.topology.ProductionEpoch;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationServer;
import com.nobodiiiii.createbiotech.content.cardboardbox.CardboardBoxEntity;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingSelection;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAliasMenu;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAliasRenamePacket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsConfigurationToolItem;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkDirectoryEntry;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsToolData;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerAvailabilityReason;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ComputerCoordinatorMember;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerNodeView;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetRouteTrace;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.FactoryOrchestrationSavedData;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerShellMaterial;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochFault;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochQuiescence;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochReformControl;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlock;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelOpeningHint;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelMenu;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.Create;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import io.netty.buffer.Unpooled;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.stats.Stat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ComputerClusterGameTests {
	private static final int FORMATION_DELAY = 25;
	private static final UUID LOW_ID = new UUID(0, 1);
	private static final UUID MID_ID = new UUID(0, 2);
	private static final UUID HIGH_ID = new UUID(0, 3);

	private ComputerClusterGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "diagnostics_diagnostics_normal", timeoutTicks = 12000)
	public static void normal_goggles_show_gameplay_state(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper, FactoryProductionGameTestFixture.PanelScenario.DIAGNOSTICS_NORMAL);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "diagnostics_diagnostics_advanced", timeoutTicks = 12000)
	public static void sneaking_goggles_show_exact_bounded_debug(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper, FactoryProductionGameTestFixture.PanelScenario.DIAGNOSTICS_ADVANCED);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "diagnostics_diagnostics_tooltip", timeoutTicks = 12000)
	public static void logistics_tooltip_hides_uuid(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper, FactoryProductionGameTestFixture.PanelScenario.DIAGNOSTICS_TOOLTIP);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "diagnostics_remote_force_denied", timeoutTicks = 12000)
	public static void local_agent_force_reset_only(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper, FactoryProductionGameTestFixture.PanelScenario.REMOTE_FORCE_DENIED);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 12000)
	public static void panel_requests_one_view(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper,
			FactoryProductionGameTestFixture.PanelScenario.REQUESTS_ONE_VIEW);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 12000)
	public static void panel_monitor_goal_tree(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper,
			FactoryProductionGameTestFixture.PanelScenario.MONITOR_GOAL_TREE);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 12000)
	public static void panel_agent_new_task_defaults(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper,
			FactoryProductionGameTestFixture.PanelScenario.AGENT_NEW_TASK_DEFAULTS);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 12000)
	public static void panel_network_collapses_subnet(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper,
			FactoryProductionGameTestFixture.PanelScenario.NETWORK_COLLAPSES_SUBNET);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 12000)
	public static void panel_logistics_is_read_only(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper,
			FactoryProductionGameTestFixture.PanelScenario.LOGISTICS_IS_READ_ONLY);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_24",
		timeoutTicks = 12000)
	public static void remote_panel_cannot_force_agent(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runPanelScenario(helper,
			FactoryProductionGameTestFixture.PanelScenario.REMOTE_FORCE_DENIED);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 280)
	public static void logistics_tool_binds_formed_cluster(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runLogisticsScenario(helper,
			FactoryProductionGameTestFixture.LogisticsScenario.TOOL_BINDS_FORMED_CLUSTER);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 280)
	public static void logistics_alias_is_global(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runLogisticsScenario(helper,
			FactoryProductionGameTestFixture.LogisticsScenario.ALIAS_IS_GLOBAL);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 320)
	public static void logistics_component_offline_last_seen(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runLogisticsScenario(helper,
			FactoryProductionGameTestFixture.LogisticsScenario.COMPONENT_OFFLINE_LAST_SEEN);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 280)
	public static void bare_address_has_no_reverse_protocol(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runLogisticsScenario(helper,
			FactoryProductionGameTestFixture.LogisticsScenario.BARE_ADDRESS_NO_REVERSE_PROTOCOL);
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 20)
	public static void literalCommunicationFrequencyMatchingIsCaseSensitive(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		CommunicationFrequency literal = CommunicationFrequency.of("Radio*?[]");
		helper.assertTrue(literal.configured(), "literal punctuation must remain a configured frequency");
		helper.assertValueEqual(literal, CommunicationFrequency.of("Radio*?[]"),
			"an exact literal frequency must match itself");
		helper.assertFalse(literal.equals(CommunicationFrequency.of("radio*?[]")),
			"frequency matching must never case-fold");
		helper.assertFalse(literal.equals(CommunicationFrequency.of("Radio")),
			"glob-looking punctuation must remain ordinary literal text");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 20)
	public static void agentPublicationCarriesOnlyTheClusterPrimaryFrequency(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		UUID network = new UUID(0, 41);
		UUID owner = new UUID(0, 42);
		CreateRouteKey route = new CreateRouteKey(network, "agent-input");
		CommunicationFrequency primary = CommunicationFrequency.of("Agent*Channel");
		AgentAddressDirectory directory = new AgentAddressDirectory(2, 2);
		AgentAddressRegistration declared = new AgentAddressRegistration(route, new UUID(0, 43),
			owner, 1, CommunicationFrequency.of("stale"),
			AgentAddressRegistration.LoadState.LOADED,
			AgentAddressRegistration.Reachability.REACHABLE);
		ClusterCommunicationConfiguration configuration = new ClusterCommunicationConfiguration(owner,
			primary, ClusterServiceRole.AGENT, 1);

		helper.assertValueEqual(directory.publish(declared, configuration, 1),
			AgentAddressDirectory.PublicationResult.ACCEPTED,
			"an Agent declaration must publish through its Cluster-owned primary frequency");
		AgentResolution exact = directory.resolve(route, new UUID(0, 44), primary);
		helper.assertTrue(exact instanceof AgentResolution.Unique,
			"the exact literal primary frequency must resolve the published Agent");
		helper.assertValueEqual(((AgentResolution.Unique) exact).registration().frequency(), primary,
			"Agent publication must not retain a per-Agent alternate frequency");
		helper.assertTrue(directory.resolve(route, new UUID(0, 44),
			CommunicationFrequency.of("agent*channel")) instanceof AgentResolution.KnownUnavailable,
			"case-different requester frequencies must not reach the Agent");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 20)
	public static void subnetBoundaryPublishesOnlyOpaquePublicServices(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		SubnetBoundaryService boundary = new SubnetBoundaryService(new UUID(0, 51), new UUID(0, 52),
			CommunicationFrequency.of("external"), List.of(
				SubnetBoundaryService.PublicServiceSummary.of(SubnetBoundaryService.PublicService.AGENT),
				SubnetBoundaryService.PublicServiceSummary.of(SubnetBoundaryService.PublicService.LIBRARY),
				SubnetBoundaryService.PublicServiceSummary.of(SubnetBoundaryService.PublicService.TASK_ENTRY),
				SubnetBoundaryService.PublicServiceSummary.of(
					SubnetBoundaryService.PublicService.LOGISTICS_METADATA)));
		SubnetBoundaryService.SubnetBoundaryProjection projection = boundary.project();

		helper.assertValueEqual(projection.cards().size(), 1,
			"a subnet must project one opaque external card");
		helper.assertTrue(projection.computers().isEmpty(),
			"a subnet boundary must never publish internal Computer identities");
		helper.assertValueEqual(projection.serviceKinds(), Set.of(
			SubnetBoundaryService.PublicService.AGENT, SubnetBoundaryService.PublicService.LIBRARY,
			SubnetBoundaryService.PublicService.TASK_ENTRY,
			SubnetBoundaryService.PublicService.LOGISTICS_METADATA),
			"only explicit public services may cross a subnet boundary");
		UUID originNetwork = new UUID(0, 53), originCluster = new UUID(0, 54);
		UUID targetNetwork = new UUID(0, 55), targetCluster = new UUID(0, 56);
		SubnetRouteTrace acceptedTaskTrace = SubnetRouteTrace.start(originNetwork, originCluster)
			.visit(new UUID(0, 51), new UUID(0, 52));
		helper.assertValueEqual(boundary.forward(acceptedTaskTrace, targetNetwork, targetCluster),
			SubnetBoundaryService.RouteDecision.FORWARDED,
			"an accepted task entry and public logistics metadata route must cross the boundary");
		SubnetRouteTrace forwarded = boundary.extend(acceptedTaskTrace, targetNetwork, targetCluster)
			.orElseThrow();
		helper.assertTrue(forwarded.hasVisitedNetwork(targetNetwork)
			&& forwarded.hasVisitedCluster(targetCluster),
			"the accepted boundary route must persist both opaque visited identities");
		helper.assertValueEqual(boundary.forward(forwarded, originNetwork, originCluster),
			SubnetBoundaryService.RouteDecision.LOOP,
			"a real subnet route must reject visited-network and visited-cluster re-entry");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 20)
	public static void wanderingTraderExternalListenersAreBoundedByTwoSlots(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ExternalListenerConfigurationStore store = new ExternalListenerConfigurationStore(
			ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 64).orElseThrow());
		List<CommunicationFrequency> twoListeners = List.of(CommunicationFrequency.of("first"),
			CommunicationFrequency.of("second"));

		helper.assertValueEqual(ExternalListenerService.replaceConfiguration(store, 0, twoListeners),
			ExternalListenerService.EditResult.APPLIED,
			"two configured listeners must consume the Trader's two frozen slots");
		helper.assertValueEqual(store.configuration().frequencies(), twoListeners,
			"the accepted listener list must retain its two exact literals");
		helper.assertValueEqual(ExternalListenerService.replaceConfiguration(store, 1, List.of(
			CommunicationFrequency.of("first"), CommunicationFrequency.of("second"),
			CommunicationFrequency.of("third"))), ExternalListenerService.EditResult.TOO_MANY,
			"a third listener must never exceed the Trader's frozen slot count");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void publicBoxInteractionInstallsAtomically(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos acceptedPos = helper.absolutePos(new BlockPos(2, 2, 2));
		ComputerBlockEntity accepted = placeComputer(level, acceptedPos, LOW_ID);
		CapturedResident source = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		ItemStack original = source.box().copy();
		ServerPlayer player = headlessPlayer(level, acceptedPos);
		InteractionResult result = useBox(player, level, acceptedPos, source.box());
		helper.assertTrue(result.consumesAction(), "The real useItemOn path must consume a valid install");

		List<RejectedInstall> rejected = new ArrayList<>();
		Villager baby = Objects.requireNonNull(EntityType.VILLAGER.create(level));
		baby.setAge(-24000);
		rejected.add(rejectedInstall(level, player, helper.absolutePos(new BlockPos(4, 2, 2)),
			captured(level, baby)));
		LivingEntity cow = Objects.requireNonNull(EntityType.COW.create(level));
		rejected.add(rejectedInstall(level, player, helper.absolutePos(new BlockPos(6, 2, 2)),
			captured(level, cow)));
		BlockPos wrongPos = helper.absolutePos(new BlockPos(8, 2, 2));
		ComputerBlockEntity wrongComputer = placeComputer(level, wrongPos, new UUID(0, 8));
		ItemStack wrong = new ItemStack(Items.DIAMOND);
		CompoundTag wrongBefore = stackTag(wrong, level);
		CompoundTag wrongComputerBefore = serverTag(wrongComputer, level);
		useBox(player, level, wrongPos, wrong);
		rejected.add(new RejectedInstall(wrongComputer, wrong, wrongComputerBefore, wrongBefore));

		helper.runAfterDelay(3, () -> {
			helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(source.box()),
				"Successful installation must empty the held box");
			ItemStack stored = residentStack(accepted, level);
			helper.assertTrue(ItemStack.matches(original, stored),
				"The serialized resident must equal the captured source");
			helper.assertValueEqual(accepted.production().installedProfile().orElseThrow(),
				exactExpectedProfile(new ProfileFixture(ProductionProfession.UNEMPLOYED, 3)),
				"The public install must lock the expected villager profile");
			for (RejectedInstall denial : rejected) {
				helper.assertValueEqual(serverTag(denial.computer(), level), denial.computerBefore(),
					"Rejected installation must leave the Computer byte-identical");
				helper.assertValueEqual(stackTag(denial.held(), level), denial.heldBefore(),
					"Rejected installation must leave the held stack byte-identical");
			}
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void representativeResidentProfilesLockThroughPublicInteraction(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = headlessPlayer(level, helper.absolutePos(new BlockPos(1, 2, 1)));
		List<ProfileFixture> fixtures = new ArrayList<>();
		for (int villagerLevel = 1; villagerLevel <= 5; villagerLevel++) {
			fixtures.add(new ProfileFixture(ProductionProfession.UNEMPLOYED, villagerLevel));
			fixtures.add(new ProfileFixture(ProductionProfession.LIBRARIAN, villagerLevel));
		}
		fixtures.add(new ProfileFixture(ProductionProfession.NITWIT, 1));
		fixtures.add(new ProfileFixture(ProductionProfession.WANDERING_TRADER, 1));
		fixtures.add(new ProfileFixture(ProductionProfession.ZOMBIE_VILLAGER, 1));
		List<InstalledFixture> installed = new ArrayList<>();
		for (int index = 0; index < fixtures.size(); index++) {
			ProfileFixture fixture = fixtures.get(index);
			BlockPos pos = helper.absolutePos(new BlockPos(1 + index, 2, 2));
			ComputerBlockEntity computer = placeComputer(level, pos, new UUID(1, index + 1));
			CapturedResident resident = capturedResident(level, fixture.kind(), fixture.level());
			InteractionResult result = useBox(player, level, pos, resident.box());
			helper.assertTrue(result.consumesAction(), "Every supported resident must install publicly");
			mutateSourceAfterCapture(resident.source());
			installed.add(new InstalledFixture(fixture, computer));
		}

		helper.runAfterDelay(3, () -> {
			for (InstalledFixture fixture : installed) {
				ProductionResourceProfile expected = exactExpectedProfile(fixture.fixture());
				ProductionResourceProfile actual = fixture.computer().production().installedProfile().orElseThrow();
				helper.assertValueEqual(actual, expected,
					"Each representative process-capacity profile must be locked at install time");
				helper.assertValueEqual(actual.profession(), fixture.fixture().kind(),
					"Installed profile must retain its exact profession");
				helper.assertValueEqual(actual.capabilities(),
					fixture.fixture().kind() == ProductionProfession.WANDERING_TRADER
						? Set.of(ProductionCapability.EXTENDED_RADIO) : Set.of(),
					"Only wandering traders in this fixture set expose a special capability");
				helper.assertValueEqual(actual.listenerRangeBonus(),
					fixture.fixture().kind() == ProductionProfession.WANDERING_TRADER
						? traderListenerRangeBonus() : 0,
					"Only wandering traders contribute the frozen listener range bonus");
			}
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void threeByThreeAndThreeByFiveBySevenForm(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos cubeMin = helper.absolutePos(new BlockPos(1, 1, 1));
		buildShell(level, cubeMin, 3, 4, 3);
		ComputerBlockEntity empty = placeComputer(level, cubeMin.offset(1, 1, 1), new UUID(0, 30));

		BlockPos rectangularMin = helper.absolutePos(new BlockPos(6, 1, 1));
		buildShell(level, rectangularMin, 3, 5, 7);
		ComputerBlockEntity high = placeComputer(level, rectangularMin.offset(1, 1, 1), HIGH_ID);
		ComputerBlockEntity low = placeComputer(level, rectangularMin.offset(1, 1, 5), LOW_ID);
		ServerPlayer player = headlessPlayer(level, rectangularMin.offset(1, 2, 2));
		useBox(player, level, high.getBlockPos(), capturedResident(level, ProductionProfession.UNEMPLOYED, 1).box());
		useBox(player, level, low.getBlockPos(), capturedResident(level, ProductionProfession.LIBRARIAN, 5).box());

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			helper.assertValueEqual(empty.production().availabilityReason(), ComputerAvailabilityReason.NOT_READY,
				"An exact 3^3 shell with an empty Computer must be VALID_NOT_READY");
			assertBounds(helper, empty.production().currentStructureSnapshot().orElseThrow(), cubeMin, 3, 4, 3);
			helper.assertValueEqual(low.production().availabilityReason(), ComputerAvailabilityReason.NONE,
				"A fully profiled 3x5x7 shell must be VALID");
			ProductionStructure snapshot = low.production().currentStructureSnapshot().orElseThrow();
			assertBounds(helper, snapshot, rectangularMin, 3, 5, 7);
			helper.assertValueEqual(snapshot.layout().members().size(), 2,
				"The non-cubic shell must contain both interior Computers");
			helper.assertTrue(low.production().publishedCoordinatorMember().isPresent(),
				"The minimum UUID must publish the coordinator adapter");
			helper.assertTrue(high.production().publishedCoordinatorMember().isEmpty(),
				"Block position must not override minimum-UUID election");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 120)
	public static void spurTouchingAmbiguityAndLimits(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos firstMin = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos secondMin = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, firstMin, 3, 4, 3);
		buildShell(level, secondMin, 3, 4, 3);
		BlockPos spur = firstMin.offset(3, 1, 1);
		level.setBlock(spur, CBBlocks.COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity first = placeComputer(level, firstMin.offset(1, 1, 1), LOW_ID);
		ComputerBlockEntity second = placeComputer(level, secondMin.offset(1, 1, 1), HIGH_ID);
		first.tick();
		second.tick();
		assertBounds(helper, first.production().currentStructureSnapshot().orElseThrow(), firstMin, 3, 4, 3);
		assertBounds(helper, second.production().currentStructureSnapshot().orElseThrow(), secondMin, 3, 4, 3);
		helper.assertFalse(first.production().currentStructureSnapshot().orElseThrow().casingPositions().contains(spur),
			"An external casing spur must not alter accepted bounds");
		helper.assertFalse(first.production().computerStructureMemberId().equals(second.production().computerStructureMemberId()),
			"Casing-touching shells must remain separate structures");

		BlockPos outerMin = helper.absolutePos(new BlockPos(10, 1, 1));
		buildShell(level, outerMin, 7, 7, 7);
		BlockPos innerMin = outerMin.offset(2, 2, 2);
		buildShell(level, innerMin, 3, 4, 3);
		level.setBlock(outerMin.offset(5, 3, 3), CBBlocks.COMPUTER_CASING.get().defaultBlockState(),
			Block.UPDATE_ALL);
		ComputerBlockEntity ambiguous = placeComputer(level, innerMin.offset(1, 1, 1), MID_ID);
		ambiguous.tick();
		helper.assertValueEqual(ambiguous.production().availabilityReason(), ComputerAvailabilityReason.AMBIGUOUS,
			"Nested complete shells connected by the explicit interior bus bridge must be ambiguous");
		helper.assertTrue(ambiguous.production().currentStructureSnapshot().isEmpty(),
			"An ambiguous scan must never publish a structure");

		clearFixture(level, helper);
		BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
		buildShell(level, min, 3, 4, 3);
		level.setBlock(min.offset(0, 1, 1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity hole = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
		hole.tick();
		helper.assertTrue(hole.production().currentStructureSnapshot().isEmpty(), "A shell hole must never form");

		clearFixture(level, helper);
		buildShell(level, min, 7, 7, 7);
		level.setBlock(min.offset(4, 3, 3), CBBlocks.COMPUTER_CASING.get().defaultBlockState(),
			Block.UPDATE_ALL);
		ComputerBlockEntity isolated = placeComputer(level, min.offset(3, 3, 3), LOW_ID);
		isolated.tick();
		helper.assertTrue(isolated.production().currentStructureSnapshot().isEmpty(),
			"An isolated interior casing bus must never form");

		int savedMin = CBConfigs.SERVER.factoryCluster.computerMinSize.get();
		int savedMax = CBConfigs.SERVER.factoryCluster.computerMaxSize.get();
		int savedNodes = CBConfigs.SERVER.factoryCluster.computerMaxNodes.get();
		try {
			clearFixture(level, helper);
			CBConfigs.SERVER.factoryCluster.computerMinSize.set(4);
			CBConfigs.SERVER.factoryCluster.computerMaxSize.set(7);
			buildShell(level, min, 3, 4, 3);
			ComputerBlockEntity tooSmall = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
			tooSmall.tick();
			helper.assertTrue(tooSmall.production().currentStructureSnapshot().isEmpty(),
				"Configured minimum minus one must never form");

			clearFixture(level, helper);
			CBConfigs.SERVER.factoryCluster.computerMinSize.set(3);
			CBConfigs.SERVER.factoryCluster.computerMaxSize.set(6);
			buildShell(level, min, 7, 7, 7);
			ComputerBlockEntity tooLarge = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
			tooLarge.tick();
			helper.assertTrue(tooLarge.production().currentStructureSnapshot().isEmpty(),
				"Configured maximum plus one must never form");
		} finally {
			CBConfigs.SERVER.factoryCluster.computerMinSize.set(savedMin);
			CBConfigs.SERVER.factoryCluster.computerMaxSize.set(savedMax);
			CBConfigs.SERVER.factoryCluster.computerMaxNodes.set(savedNodes);
		}

		clearFixture(level, helper);
		buildShell(level, min, 7, 7, 7);
		List<ComputerBlockEntity> nodes = new ArrayList<>();
		int ordinal = 1;
		for (int y : new int[] {1, 3})
			for (int z = 1; z <= 5 && nodes.size() < 33; z++)
				for (int x = 1; x <= 5; x++) {
					if (nodes.size() == 33) break;
					nodes.add(placeComputer(level, min.offset(x, y, z), new UUID(2, ordinal++)));
				}
		nodes.getFirst().tick();
		helper.assertTrue(nodes.getFirst().production().currentStructureSnapshot().isEmpty(),
			"The thirty-third Computer must reject formation at default limits");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void breakReleasesExactlyOneHalfHeartResidentAndEmptyComputer(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.WANDERING_TRADER, 1);
		WanderingTrader decoded = (WanderingTrader) Objects.requireNonNull(
			CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(captured.box(), level));
		int despawnDelay = decoded.getDespawnDelay();
		String name = Objects.requireNonNull(decoded.getCustomName()).getString();
		CompoundTag offers = decoded.saveWithoutId(new CompoundTag()).getCompound("Offers").copy();
		ServerPlayer installer = headlessPlayer(level, pos);
		useBox(installer, level, pos, captured.box());
		ServerPlayer breaker = headlessPlayer(level, pos);
		breaker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
		helper.assertValueEqual(breaker.gameMode.getGameModeForPlayer(), GameType.SURVIVAL,
			"Controlled-break player must report SURVIVAL game mode");
		helper.assertFalse(breaker.isCreative(),
			"Controlled-break SURVIVAL player must not report creative status");
		helper.assertFalse(breaker.getAbilities().instabuild,
			"Controlled-break SURVIVAL player must not retain instabuild ability");
		breaker.gameMode.destroyBlock(pos);
		List<Entity> releasedImmediately = entities(level, Entity.class, pos, 4).stream()
			.filter(entity -> entity.getUUID().equals(captured.uuid())).toList();
		helper.assertValueEqual(releasedImmediately.size(), 1,
			"A controlled break must synchronously release exactly one resident");
		helper.assertTrue(releasedImmediately.getFirst() instanceof WanderingTrader,
			"The synchronously released resident must retain its exact type");
		helper.assertValueEqual(((WanderingTrader) releasedImmediately.getFirst()).getDespawnDelay(),
			despawnDelay, "Trader despawn delay must be copied exactly at release time");
		List<ItemStack> immediateComputerDrops = new ArrayList<>();
		long immediateWorldCount = 0;
		for (ItemEntity item : entities(level, ItemEntity.class, pos, 5)) {
			if (!item.getItem().is(CBItems.COMPUTER.get())) continue;
			immediateComputerDrops.add(item.getItem());
			immediateWorldCount += item.getItem().getCount();
		}
		long immediateInventoryCount = 0;
		for (int slot = 0; slot < breaker.getInventory().getContainerSize(); slot++) {
			ItemStack stack = breaker.getInventory().getItem(slot);
			if (!stack.is(CBItems.COMPUTER.get())) continue;
			immediateComputerDrops.add(stack);
			immediateInventoryCount += stack.getCount();
		}
		long immediateComputerCount = immediateWorldCount + immediateInventoryCount;
		helper.assertValueEqual(immediateComputerCount, 1L,
			"Controlled break must synchronously emit exactly one empty Computer");
		ItemStack emptyComputerDrop = immediateComputerDrops.getFirst().copy();
		helper.assertTrue(emptyComputerDrop.get(DataComponents.BLOCK_ENTITY_DATA) == null,
			"The synchronous empty Computer drop must not retain block-entity data");
		helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(emptyComputerDrop),
			"The synchronous empty Computer drop must not retain captured-resident data");

		helper.runAfterDelay(3, () -> {
			List<Entity> residents = entities(level, Entity.class, pos, 4).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).toList();
			helper.assertValueEqual(residents.size(), 1, "A controlled break must release exactly one resident");
			helper.assertTrue(residents.getFirst() instanceof WanderingTrader,
				"The released resident must retain its exact type");
			WanderingTrader trader = (WanderingTrader) residents.getFirst();
			helper.assertTrue(Math.abs(trader.getHealth() - 1.0F) < 0.0001F,
				"The released resident must have exactly one half-heart");
			helper.assertValueEqual(Objects.requireNonNull(trader.getCustomName()).getString(), name,
				"Resident name must survive release");
			helper.assertValueEqual(trader.saveWithoutId(new CompoundTag())
				.getCompound("Offers"), offers,
				"Resident trades must survive release");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"A successful release must not emit a filled recovery box");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void forcedSpawnFailureRecoversResident(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.UNEMPLOYED, 4);
		ItemStack untouched = captured.box().copy();
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
		FakePlayer collision = new FakePlayer(nether,
			new GameProfile(captured.uuid(), "computer-collision"));
		nether.addNewPlayer(collision);
		try {
			level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			helper.runAfterDelay(3, () -> {
				try {
					List<ItemEntity> recovery = filledRecoveryItems(level, pos, 5);
					helper.assertValueEqual(recovery.size(), 1,
						"A cross-level UUID/type collision must emit exactly one recovery box");
					helper.assertTrue(ItemStack.matches(untouched, recovery.getFirst().getItem()),
						"Forced recovery must preserve the captured resident byte-for-byte");
					helper.assertValueEqual(entities(level, Villager.class, pos, 5).stream()
						.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
						"Collision fallback must not release a duplicate resident");
					for (ItemEntity item : entities(level, ItemEntity.class, pos, 5))
						if (item.getItem().is(CBItems.COMPUTER.get()))
							helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(item.getItem()),
								"Empty Computer loot must not contain resident data");
					helper.succeed();
				} finally {
					nether.removePlayerImmediately(collision, Entity.RemovalReason.DISCARDED);
				}
			});
		} catch (RuntimeException | Error failure) {
			nether.removePlayerImmediately(collision, Entity.RemovalReason.DISCARDED);
			throw failure;
		}
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void controlledBreakDoubleFailureKeepsOccupiedComputerAndNoLoot(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		CompoundTag before = serverTag(computer, level);
		ServerPlayer breaker = headlessPlayer(level, pos);
		DoubleFailureListener listener = new DoubleFailureListener(level, captured.uuid());
		NeoForge.EVENT_BUS.register(listener);
		try {
			breaker.gameMode.destroyBlock(pos);
			helper.assertValueEqual(listener.rejectedResidents, 1,
				"First controlled attempt must reject exactly one resident spawn");
			helper.assertValueEqual(listener.rejectedRecoveries, 1,
				"First controlled attempt must reject exactly one filled recovery");
			assertOccupiedUnchanged(helper, level, pos, before);
			breaker.gameMode.destroyBlock(pos);
			helper.assertValueEqual(listener.rejectedResidents, 2,
				"Second controlled attempt must reject exactly one additional resident");
			helper.assertValueEqual(listener.rejectedRecoveries, 2,
				"Second controlled attempt must reject exactly one additional recovery");
			assertOccupiedUnchanged(helper, level, pos, before);
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
		}

		helper.runAfterDelay(3, () -> {
			assertOccupiedUnchanged(helper, level, pos, before);
			helper.assertValueEqual(entities(level, Entity.class, pos, 5).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
				"Double failure must not leave a resident entity");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"Double failure must roll back every recovery item");
			helper.assertValueEqual(entities(level, CardboardBoxEntity.class, pos, 5).size(), 0,
				"Double failure must not create a cardboard-box replacement entity");
			helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get())
				+ countInventoryItem(breaker, CBItems.COMPUTER.get()), 0L,
				"Double failure must roll back ordinary Computer loot");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void forcedDestroyBlockDoubleFailureKeepsOccupiedComputerAndNoLoot(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		CompoundTag before = serverTag(computer, level);
		DoubleFailureListener listener = new DoubleFailureListener(level, captured.uuid());
		NeoForge.EVENT_BUS.register(listener);
		try {
			level.destroyBlock(pos, true);
			assertOccupiedUnchanged(helper, level, pos, before);
			level.destroyBlock(pos, true);
			assertOccupiedUnchanged(helper, level, pos, before);
			helper.assertValueEqual(listener.rejectedResidents, 2,
				"Each forced destroy must reach exactly one resident release attempt");
			helper.assertValueEqual(listener.rejectedRecoveries, 2,
				"Each forced destroy must reach exactly one recovery attempt");
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
		}
		helper.runAfterDelay(3, () -> {
			assertOccupiedUnchanged(helper, level, pos, before);
			helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get()), 0L,
				"Repeated destroyBlock(pos,true) rollback must never emit an empty Computer");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"Repeated forced rollback must leave no recovery output");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void forcedDestroyBlockSuccessDropsOneEmptyComputer(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());

		helper.assertTrue(level.destroyBlock(pos, true),
			"A successful forced destroy must remove the occupied Computer");
		helper.assertFalse(level.getBlockState(pos).is(CBBlocks.COMPUTER.get()),
			"Successful forced destroy must leave no Computer block");
		helper.assertFalse(level.getBlockEntity(pos) instanceof ComputerBlockEntity,
			"Successful forced destroy must leave no Computer block entity");
		Entity released = level.getEntity(captured.uuid());
		helper.assertTrue(released instanceof LivingEntity,
			"Successful forced destroy must release the exact resident UUID");
		helper.assertValueEqual(((LivingEntity) released).getHealth(), 1.0F,
			"Released resident health must be exactly half a heart");
		List<ItemEntity> drops = computerDrops(level, pos, 5);
		helper.assertValueEqual(drops.size(), 1,
			"Successful destroyBlock(pos,true) must emit exactly one Computer stack");
		helper.assertValueEqual(drops.getFirst().getItem().getCount(), 1,
			"The committed Computer loot stack must contain exactly one item");
		assertEmptyComputerStack(helper, drops.getFirst().getItem());
		helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
			"Successful resident release must not emit a recovery item");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void transparent_shell_forms(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		Block transparent = CBBlocks.TRANSPARENT_COMPUTER_CASING.get();
		Block opaque = CBBlocks.COMPUTER_CASING.get();
		helper.assertValueEqual(CBBlocks.TRANSPARENT_COMPUTER_CASING.getId(),
			CreateBiotech.asResource("transparent_computer_casing"),
			"The formed shell must use the registered transparent Computer casing");
		BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
		buildShell(level, min, 3, 4, 3, transparent.defaultBlockState());
		ComputerBlockEntity computer = placeComputerPair(level, min.offset(1, 1, 1), LOW_ID);
		CapturedResident resident = capturedResident(level, ProductionProfession.LIBRARIAN, 3);
		InteractionResult installed = useBox(headlessPlayer(level, computer.getBlockPos()), level,
			computer.getBlockPos(), resident.box());
		helper.assertTrue(installed.consumesAction(),
			"The registered transparent-shell Computer must accept a resident");

		BlockPos transparentNormalWrench = helper.absolutePos(new BlockPos(14, 1, 1));
		BlockPos transparentSneakWrench = helper.absolutePos(new BlockPos(15, 1, 1));
		BlockPos opaqueSneakWrench = helper.absolutePos(new BlockPos(16, 1, 1));
		BlockPos opaqueNormalWrench = helper.absolutePos(new BlockPos(17, 1, 1));
		BlockPos transparentPlayerBreak = helper.absolutePos(new BlockPos(18, 1, 1));
		level.setBlock(transparentNormalWrench, transparent.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(transparentSneakWrench, transparent.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(opaqueSneakWrench, opaque.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(opaqueNormalWrench, opaque.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(transparentPlayerBreak, transparent.defaultBlockState(), Block.UPDATE_ALL);
		ServerPlayer wrenchUser = headlessPlayer(level, transparentNormalWrench);
		wrenchUser.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
		ClusterBindingSelection.clear(wrenchUser);
		long transparentInventoryBefore = countInventoryItem(wrenchUser,
			CBItems.TRANSPARENT_COMPUTER_CASING.get());
		long opaqueInventoryBefore = countInventoryItem(wrenchUser, CBItems.COMPUTER_CASING.get());
		helper.assertValueEqual(((IWrenchable) transparent).onWrenched(
			level.getBlockState(transparentNormalWrench), wrenchContext(wrenchUser, transparentNormalWrench)),
			InteractionResult.FAIL,
			"A transparent Computer casing must refuse ordinary wrench rotation like the opaque casing");
		helper.assertValueEqual(((IWrenchable) opaque).onWrenched(
			level.getBlockState(opaqueNormalWrench), wrenchContext(wrenchUser, opaqueNormalWrench)),
			InteractionResult.FAIL,
			"The opaque casing comparison must retain the ordinary wrench refusal shared by its transparent peer");
		helper.assertValueEqual(((IWrenchable) transparent).onSneakWrenched(
			level.getBlockState(transparentSneakWrench), wrenchContext(wrenchUser, transparentSneakWrench)),
			InteractionResult.SUCCESS,
			"An unselected transparent Computer casing must preserve Create's default sneak-wrench removal");
		helper.assertValueEqual(((IWrenchable) opaque).onSneakWrenched(
			level.getBlockState(opaqueSneakWrench), wrenchContext(wrenchUser, opaqueSneakWrench)),
			InteractionResult.SUCCESS,
			"Opaque and transparent Computer casings must share the default unselected sneak-wrench result");
		helper.assertValueEqual(countInventoryItem(wrenchUser,
			CBItems.TRANSPARENT_COMPUTER_CASING.get()) - transparentInventoryBefore, 1L,
			"Unselected transparent sneak-wrench removal must return exactly one casing item");
		helper.assertValueEqual(countInventoryItem(wrenchUser, CBItems.COMPUTER_CASING.get())
			- opaqueInventoryBefore, 1L,
			"Unselected opaque sneak-wrench removal must return exactly one casing item");
		ServerPlayer breaker = headlessPlayer(level, transparentPlayerBreak);
		breaker.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
		long transparentDropsBefore = countWorldItem(level, transparentPlayerBreak, 2,
			CBItems.TRANSPARENT_COMPUTER_CASING.get());
		helper.assertTrue(breaker.gameMode.destroyBlock(transparentPlayerBreak),
			"A normal survival-player break must remove transparent casing through vanilla loot");
		helper.assertValueEqual(countWorldItem(level, transparentPlayerBreak, 2,
			CBItems.TRANSPARENT_COMPUTER_CASING.get()) - transparentDropsBefore, 1L,
			"A normal transparent-casing break must emit exactly one self drop");

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			helper.assertTrue(level.getBlockState(transparentNormalWrench).is(transparent),
				"A refused ordinary wrench must leave the transparent casing in place");
			helper.assertTrue(level.getBlockState(opaqueNormalWrench).is(opaque),
				"The opaque comparison's refused ordinary wrench must also leave its casing in place");
			helper.assertTrue(level.getBlockState(transparentSneakWrench).isAir(),
				"The default transparent sneak-wrench path must remove its casing");
			helper.assertTrue(level.getBlockState(opaqueSneakWrench).isAir(),
				"The opaque comparison casing must use the same default removal path");
			helper.assertValueEqual(computer.production().availabilityReason(), ComputerAvailabilityReason.NONE,
				"A closed registered transparent shell must form an operational Computer");
			ProductionStructure snapshot = computer.production().currentStructureSnapshot().orElseThrow();
			assertBounds(helper, snapshot, min, 3, 4, 3);
			helper.assertValueEqual(snapshot.casingPositions().size(), 34,
				"The transparent 3x4x3 cuboid must contribute every shell cell");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void playerComputerBreakPreservesVanillaPipelineFromEitherHalf(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		List<BlockPos> positions = List.of(
			helper.absolutePos(new BlockPos(2, 3, 4)),
			helper.absolutePos(new BlockPos(7, 3, 4)),
			helper.absolutePos(new BlockPos(12, 3, 4)),
			helper.absolutePos(new BlockPos(17, 3, 4)));
		for (int index = 0; index < positions.size(); index++) {
			BlockPos lowerPos = positions.get(index);
			boolean resident = index >= 2;
			boolean upper = index % 2 == 1;
			placeComputerPair(level, lowerPos, new UUID(8, index + 1));
			CapturedResident captured = resident
				? capturedResident(level, ProductionProfession.UNEMPLOYED, 3) : null;
			TrackingPlayer player = trackingPlayer(level, lowerPos);
			if (captured != null)
				useBox(player, level, lowerPos, captured.box());
			ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
			player.setItemInHand(InteractionHand.MAIN_HAND, tool);
			int damageBefore = tool.getDamageValue();
			int statBefore = player.computerMined;
			long dropsBefore = countWorldItem(level, lowerPos, 2, CBItems.COMPUTER.get());

			boolean outerResult = player.gameMode.destroyBlock(upper ? lowerPos.above() : lowerPos);
			helper.assertValueEqual(outerResult, !upper && !resident,
				"Only the untouched empty-lower call returns directly; redirected transactional outer "
					+ "events report canceled after their inner vanilla removal for fixture " + index);
			helper.assertFalse(level.getBlockState(lowerPos).is(CBBlocks.COMPUTER.get()),
				"A committed player break must remove the lower owner for fixture " + index);
			helper.assertFalse(level.getBlockState(lowerPos.above()).is(CBBlocks.COMPUTER.get()),
				"A committed player break must remove the upper half for fixture " + index);
			helper.assertValueEqual(tool.getDamageValue() - damageBefore, 1,
				"Vanilla player break must damage the held tool exactly once for fixture " + index);
			helper.assertValueEqual(player.computerMined - statBefore, 1,
				"Vanilla playerDestroy must award the Computer mining stat exactly once for fixture " + index);
			helper.assertValueEqual(countWorldItem(level, lowerPos, 2, CBItems.COMPUTER.get())
				- dropsBefore, 1L,
				"Vanilla playerDestroy must emit one Computer drop for fixture " + index);
			if (captured != null)
				helper.assertValueEqual(entities(level, Entity.class, lowerPos, 2).stream()
					.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 1L,
					"Resident transaction must release its exact UUID once for fixture " + index);
		}
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void protectedResidentUpperBreakRollsBackWithoutVanillaSideEffects(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos lowerPos = helper.absolutePos(new BlockPos(8, 3, 7));
		ComputerBlockEntity computer = placeComputerPair(level, lowerPos, new UUID(9, 1));
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		TrackingPlayer player = trackingPlayer(level, lowerPos);
		useBox(player, level, lowerPos, captured.box());
		ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
		player.setItemInHand(InteractionHand.MAIN_HAND, tool);
		CompoundTag before = serverTag(computer, level);
		int statBefore = player.computerMined;
		long dropsBefore = countWorldItem(level, lowerPos, 2, CBItems.COMPUTER.get());
		BreakProtectionListener protection = new BreakProtectionListener(level, lowerPos);
		NeoForge.EVENT_BUS.register(protection);
		try {
			helper.assertFalse(player.gameMode.destroyBlock(lowerPos.above()),
				"A protection listener rejecting the redirected lower break must reject the upper request");
		} finally {
			NeoForge.EVENT_BUS.unregister(protection);
		}
		helper.assertValueEqual(protection.lowerAttempts, 1,
			"The upper redirect must cross the ordinary lower BreakEvent exactly once");
		assertOccupiedPairUnchanged(helper, level, lowerPos, before);
		helper.assertValueEqual(tool.getDamageValue(), 0,
			"A protected break must not damage the tool");
		helper.assertValueEqual(player.computerMined, statBefore,
			"A protected break must not award mining stats");
		helper.assertValueEqual(countWorldItem(level, lowerPos, 2, CBItems.COMPUTER.get()), dropsBefore,
			"A protected break must not emit Computer loot");
		helper.assertValueEqual(entities(level, Entity.class, lowerPos, 2).stream()
			.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
			"Resident release must roll back when another protection listener denies destruction");
		helper.succeed();
	}

	private static UseOnContext wrenchContext(ServerPlayer player, BlockPos pos) {
		return new UseOnContext(player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void occupiedPairRemovesAtomicallyFromEitherHalfAndExplosion(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos lowerBreak = helper.absolutePos(new BlockPos(2, 3, 2));
		BlockPos upperBreak = helper.absolutePos(new BlockPos(8, 3, 2));
		BlockPos explosion = helper.absolutePos(new BlockPos(14, 3, 2));
		CapturedResident lowerResident = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		CapturedResident upperResident = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		CapturedResident explosionResident = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		ServerPlayer player = headlessPlayer(level, lowerBreak);
		placeComputerPair(level, lowerBreak, LOW_ID);
		placeComputerPair(level, upperBreak, MID_ID);
		placeComputerPair(level, explosion, HIGH_ID);
		useBox(player, level, lowerBreak, lowerResident.box());
		useBox(player, level, upperBreak, upperResident.box());
		useBox(player, level, explosion, explosionResident.box());

		helper.assertTrue(level.destroyBlock(lowerBreak, true),
			"Destroying the lower half must remove its occupied pair");
		assertReleasedPair(helper, level, lowerBreak, lowerResident.uuid(), "lower-half destroy");
		helper.assertTrue(level.destroyBlock(upperBreak.above(), true),
			"Destroying the upper half must resolve through the lower owner");
		assertReleasedPair(helper, level, upperBreak, upperResident.uuid(), "upper-half destroy");
		level.explode(null, explosion.getX() + 0.5, explosion.getY() + 0.5, explosion.getZ() + 0.5,
			4.0F, Level.ExplosionInteraction.TNT);
		assertReleasedPair(helper, level, explosion, explosionResident.uuid(), "explosion");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void computer_pair_all_facings(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		ComputerBlock block = (ComputerBlock) CBBlocks.COMPUTER.get();
		List<BlockPos> positions = List.of(helper.absolutePos(new BlockPos(2, 3, 2)),
			helper.absolutePos(new BlockPos(7, 3, 2)), helper.absolutePos(new BlockPos(12, 3, 2)),
			helper.absolutePos(new BlockPos(17, 3, 2)));
		List<Float> yaws = List.of(0.0F, 90.0F, 180.0F, 270.0F);
		Set<Direction> facings = new HashSet<>();

		for (int index = 0; index < positions.size(); index++) {
			BlockPos lowerPos = positions.get(index);
			level.setBlock(lowerPos.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
			ServerPlayer player = headlessPlayer(level, lowerPos);
			player.setYRot(yaws.get(index));
			BlockState lower = placeComputerThroughBlockMethods(level, player, lowerPos);
			BlockState upper = level.getBlockState(lowerPos.above());
			facings.add(lower.getValue(ComputerBlock.FACING));
			helper.assertValueEqual(lower.getValue(ComputerBlock.HALF), DoubleBlockHalf.LOWER,
				"Every real placement must create a lower Computer owner");
			helper.assertValueEqual(upper.getValue(ComputerBlock.HALF), DoubleBlockHalf.UPPER,
				"Every real placement must create the matching upper Computer half");
			helper.assertValueEqual(upper.getValue(ComputerBlock.FACING), lower.getValue(ComputerBlock.FACING),
				"Each pair must retain a single facing across both physical halves");
			helper.assertTrue(level.getBlockEntity(lowerPos) instanceof ComputerBlockEntity,
				"Only the lower Computer half may own a block entity");
			helper.assertTrue(level.getBlockEntity(lowerPos.above()) == null,
				"The upper Computer half must not own a block entity");
			helper.assertTrue(block.getTicker(level, lower, CBBlockEntityTypes.COMPUTER.get()) != null,
				"Only the lower Computer half may tick");
			helper.assertTrue(block.getTicker(level, upper, CBBlockEntityTypes.COMPUTER.get()) == null,
				"The upper Computer half must not tick");

			CapturedResident resident = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
			helper.assertTrue(useBox(player, level, lowerPos.above(), resident.box()).consumesAction(),
				"Upper-half use must install through the lower owner");
			player.gameMode.destroyBlock(lowerPos.above());
			assertReleasedPair(helper, level, lowerPos, resident.uuid(),
				"upper-half break after real placement");
		}
		helper.assertValueEqual(facings, Set.of(Direction.NORTH, Direction.EAST, Direction.SOUTH,
			Direction.WEST), "Four actual placement directions must form all four facings");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void actualComputerPlacementRejectsObstructionAndCleansMismatchedPair(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		ComputerBlock block = (ComputerBlock) CBBlocks.COMPUTER.get();
		BlockPos obstructed = helper.absolutePos(new BlockPos(3, 3, 3));
		level.setBlock(obstructed.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(obstructed.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		ServerPlayer player = headlessPlayer(level, obstructed);
		BlockPlaceContext obstructionContext = placementContext(player, obstructed);
		helper.assertTrue(block.getStateForPlacement(obstructionContext) == null,
			"Production placement must reject a blocked upper cell");

		BlockPos mismatch = helper.absolutePos(new BlockPos(10, 3, 3));
		level.setBlock(mismatch.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		BlockState lower = placeComputerThroughBlockMethods(level, player, mismatch);
		level.setBlock(mismatch.above(), level.getBlockState(mismatch.above()).setValue(ComputerBlock.FACING,
			lower.getValue(ComputerBlock.FACING).getClockWise()), Block.UPDATE_ALL);
		block.tick(level.getBlockState(mismatch), level, mismatch, net.minecraft.util.RandomSource.create());
		helper.assertFalse(level.getBlockState(mismatch).is(CBBlocks.COMPUTER.get()),
			"A mismatched pair must remove its lower owner on the production validation path");
		helper.assertFalse(level.getBlockState(mismatch.above()).is(CBBlocks.COMPUTER.get()),
			"A mismatched pair must remove its upper half on the production validation path");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void upperRemovalDoubleFailureRestoresACompletePair(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(6, 3, 6));
		level.setBlock(pos.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputerPair(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		CompoundTag before = serverTag(computer, level);
		DoubleFailureListener listener = new DoubleFailureListener(level, captured.uuid());
		NeoForge.EVENT_BUS.register(listener);
		try {
			helper.assertFalse(level.destroyBlock(pos.above(), true),
				"A failed occupied upper removal must be rejected before either half changes");
			helper.assertValueEqual(listener.rejectedResidents, 1,
				"Upper removal must make exactly one resident release attempt");
			helper.assertValueEqual(listener.rejectedRecoveries, 1,
				"Upper removal must make exactly one recovery attempt");
			assertOccupiedPairUnchanged(helper, level, pos, before);
			helper.assertValueEqual(entities(level, Entity.class, pos, 5).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
				"An atomic rollback must not create a resident before the caller returns");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"An atomic rollback must not create recovery output before the caller returns");
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
		}
		helper.runAfterDelay(3, () -> {
			assertOccupiedPairUnchanged(helper, level, pos, before);
			helper.assertValueEqual(entities(level, Entity.class, pos, 5).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
				"A rolled-back upper removal must not release a resident");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"A rolled-back upper removal must not leave recovery output");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void failedUpperReplacementPreservesTheResidentPair(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(13, 3, 6));
		level.setBlock(pos.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputerPair(level, pos, MID_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		CompoundTag before = serverTag(computer, level);
		DoubleFailureListener listener = new DoubleFailureListener(level, captured.uuid());
		NeoForge.EVENT_BUS.register(listener);
		try {
			helper.assertFalse(level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(),
				Block.UPDATE_ALL), "A failed external upper replacement must be rejected atomically");
			helper.assertValueEqual(listener.rejectedResidents, 1,
				"Rejected replacement must make one resident release attempt");
			helper.assertValueEqual(listener.rejectedRecoveries, 1,
				"Rejected replacement must make one recovery attempt");
			assertOccupiedPairUnchanged(helper, level, pos, before);
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
		}
		helper.runAfterDelay(3, () -> {
			assertOccupiedPairUnchanged(helper, level, pos, before);
			helper.assertValueEqual(entities(level, Entity.class, pos, 5).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
				"Rejected replacement must preserve the resident source rather than lose it");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"Rejected replacement must not leave a recovery item");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void fourArgumentUpperReplacementPreservesRecursionBudget(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(5, 3, 9));
		level.setBlock(pos.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		placeComputerPair(level, pos, HIGH_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		BlockPos fencePos = pos.above().east();
		level.setBlock(fencePos, Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
		helper.assertFalse(level.getBlockState(fencePos).getValue(BlockStateProperties.WEST),
			"The fence must start disconnected from the non-sturdy computer upper");
		int flags = Block.UPDATE_ALL;
		int recursionLeft = 0;
		helper.assertTrue(level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), flags,
			recursionLeft), "The four-argument upper replacement must commit");
		helper.assertFalse(level.getBlockState(fencePos).getValue(BlockStateProperties.WEST),
			"A zero recursion budget must prevent the actual upper write from connecting the fence");
		helper.assertValueEqual(level.getBlockState(pos.above()).getBlock(), Blocks.STONE,
			"The requested replacement must remain at the actual upper position");
		assertReleasedPair(helper, level, pos, captured.uuid(), "four-argument upper replacement");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void upperRoutedPlayerWrenchAndExplosionFailuresKeepCompletePairs(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		ComputerBlock block = (ComputerBlock) CBBlocks.COMPUTER.get();
		BlockPos playerBreak = helper.absolutePos(new BlockPos(2, 3, 8));
		BlockPos wrenchBreak = helper.absolutePos(new BlockPos(9, 3, 8));
		BlockPos explosionBreak = helper.absolutePos(new BlockPos(16, 3, 8));
		for (BlockPos pos : List.of(playerBreak, wrenchBreak, explosionBreak))
			level.setBlock(pos.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity playerComputer = placeComputerPair(level, playerBreak, LOW_ID);
		ComputerBlockEntity wrenchComputer = placeComputerPair(level, wrenchBreak, MID_ID);
		ComputerBlockEntity explosionComputer = placeComputerPair(level, explosionBreak, HIGH_ID);
		CapturedResident playerResident = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		CapturedResident wrenchResident = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		CapturedResident explosionResident = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		ServerPlayer player = headlessPlayer(level, playerBreak);
		useBox(player, level, playerBreak, playerResident.box());
		useBox(player, level, wrenchBreak, wrenchResident.box());
		useBox(player, level, explosionBreak, explosionResident.box());
		CompoundTag playerBefore = serverTag(playerComputer, level);
		CompoundTag wrenchBefore = serverTag(wrenchComputer, level);
		CompoundTag explosionBefore = serverTag(explosionComputer, level);

		assertUpperRouteDoubleFailure(helper, level, playerBreak, playerResident.uuid(), () ->
			player.gameMode.destroyBlock(playerBreak.above()), "player upper break");
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
		assertUpperRouteDoubleFailure(helper, level, wrenchBreak, wrenchResident.uuid(), () ->
			block.onSneakWrenched(level.getBlockState(wrenchBreak.above()), new UseOnContext(player,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(wrenchBreak.above()),
					Direction.UP, wrenchBreak.above(), false))), "upper wrench");
		assertUpperRouteDoubleFailure(helper, level, explosionBreak, explosionResident.uuid(), () ->
			level.explode(null, explosionBreak.getX() + .5, explosionBreak.getY() + 1.5,
				explosionBreak.getZ() + .5, 4.0F, Level.ExplosionInteraction.TNT), "upper explosion");

		helper.runAfterDelay(3, () -> {
			assertOccupiedPairUnchanged(helper, level, playerBreak, playerBefore);
			assertOccupiedPairUnchanged(helper, level, wrenchBreak, wrenchBefore);
			assertOccupiedPairUnchanged(helper, level, explosionBreak, explosionBefore);
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void forcedDestroyWithoutDropsReleasesResidentButNoComputer(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());

		helper.assertTrue(level.destroyBlock(pos, false),
			"A no-drop forced destroy must still release and remove the occupied Computer");
		helper.assertTrue(level.getEntity(captured.uuid()) instanceof LivingEntity,
			"No-drop forced destruction must still preserve the resident");
		helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get()), 0L,
			"destroyBlock(pos,false) must not synthesize Computer loot");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void forcedDestroyHonorsDisabledBlockDrops(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		GameRules.BooleanValue blockDrops = level.getGameRules().getRule(GameRules.RULE_DOBLOCKDROPS);
		boolean previous = blockDrops.get();
		try {
			blockDrops.set(false, level.getServer());
			helper.assertTrue(level.destroyBlock(pos, true),
				"Forced destruction must still release and remove while block drops are disabled");
			helper.assertTrue(level.getEntity(captured.uuid()) instanceof LivingEntity,
				"Disabling block drops must not discard the resident");
			helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get()), 0L,
				"Forced destruction must honor doTileDrops=false");
		} finally {
			blockDrops.set(previous, level.getServer());
		}
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void standaloneDropEventCannotArmLaterNoDropDestroy(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.UNEMPLOYED, 3);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());

		Block.dropResources(level.getBlockState(pos), level, pos, computer, null, ItemStack.EMPTY);
		helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get()), 0L,
			"A standalone drop event for an occupied Computer must not emit loot");
		helper.assertTrue(level.destroyBlock(pos, false),
			"A later no-drop destroy in the same tick must still release and remove the Computer");
		helper.assertTrue(level.getEntity(captured.uuid()) instanceof LivingEntity,
			"The no-drop destruction must preserve the resident");
		helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get()), 0L,
			"A prior drop event must not arm loot for an unrelated no-drop destruction");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void actualExplosionDoubleFailureKeepsOccupiedComputerAndNoLoot(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		CompoundTag before = serverTag(computer, level);
		DoubleFailureListener listener = new DoubleFailureListener(level, captured.uuid());
		NeoForge.EVENT_BUS.register(listener);
		try {
			for (int attempt = 0; attempt < 2; attempt++) {
				level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					4.0F, Level.ExplosionInteraction.TNT);
				assertOccupiedUnchanged(helper, level, pos, before);
			}
			helper.assertValueEqual(listener.rejectedResidents, 2,
				"Each actual explosion must reach exactly one resident release attempt");
			helper.assertValueEqual(listener.rejectedRecoveries, 2,
				"Each actual explosion must reach exactly one recovery attempt");
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
		}
		helper.runAfterDelay(3, () -> {
			assertOccupiedUnchanged(helper, level, pos, before);
			helper.assertValueEqual(countWorldItem(level, pos, 5, CBItems.COMPUTER.get()), 0L,
				"Repeated explosion rollback must never emit an empty Computer");
			helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
				"Repeated explosion rollback must leave no recovery output");
			helper.assertValueEqual(entities(level, Entity.class, pos, 5).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).count(), 0L,
				"Repeated explosion rollback must leave no released resident");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void actualExplosionSuccessDropsOneEmptyComputer(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, ProductionProfession.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());

		level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
			4.0F, Level.ExplosionInteraction.TNT);

		helper.assertFalse(level.getBlockState(pos).is(CBBlocks.COMPUTER.get()),
			"Successful explosion must remove the occupied Computer");
		helper.assertTrue(level.getEntity(captured.uuid()) instanceof LivingEntity,
			"Successful explosion must release the exact resident UUID");
		List<ItemEntity> drops = computerDrops(level, pos, 5);
		helper.assertValueEqual(drops.size(), 1,
			"Successful explosion must commit exactly one Computer stack");
		helper.assertValueEqual(drops.getFirst().getItem().getCount(), 1,
			"Explosion Computer loot must contain exactly one item");
		assertEmptyComputerStack(helper, drops.getFirst().getItem());
		helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
			"Successful explosion release must not emit a recovery item");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 140)
	public static void realPanelPatternComputerBindingAndAuthorityReload(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos panelPos = helper.absolutePos(new BlockPos(7, 2, 2));
		BlockPos corePos = helper.absolutePos(new BlockPos(3, 2, 2));
		level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, corePos);
		BlockPos firstMin = helper.absolutePos(new BlockPos(7, 1, 1));
		buildShell(level, firstMin, 4, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity coordinator = placeComputer(level, firstMin.offset(1, 1, 1), LOW_ID);
		ComputerBlockEntity replica = placeComputer(level, firstMin.offset(2, 1, 1), HIGH_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, coordinator.getBlockPos(),
			capturedResident(level, ProductionProfession.LIBRARIAN, 5).box());
		useBox(player, level, replica.getBlockPos(),
			capturedResident(level, ProductionProfession.UNEMPLOYED, 3).box());

		BlockPos secondMin = helper.absolutePos(new BlockPos(12, 1, 1));
		buildShell(level, secondMin, 3, 4, 3);
		ComputerBlockEntity second = placeComputer(level, secondMin.offset(1, 1, 1), MID_ID);
		useBox(player, level, second.getBlockPos(),
			capturedResident(level, ProductionProfession.UNEMPLOYED, 2).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				LibraryBlockEntity core = core(level, corePos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The real active Cluster must authorize its configured logistics");
				helper.assertValueEqual(ClusterBindingService.bind(player, panel, core),
					ClusterBindingService.BindResult.OK,
					"The real panel and pattern core must first share the logistics binding");
				helper.assertTrue(ClusterBindingService.replaceFormedBindings(player, panel, panel.logisticsBindings()).succeeded(),
					"The active authority must publish a new exact logistics revision");
				CompoundTag staleReplica = serverTag(replica, level);
				long staleRevision = ClusterBinding.tryLoad(bindingTag(replica, level))
					.orElseThrow().revision();
				helper.assertTrue(ClusterBindingService.replaceFormedBindings(player, panel, panel.logisticsBindings()).succeeded(),
					"The active authority must publish a new exact logistics revision");
				ClusterMember adapter = coordinator.production().publishedCoordinatorMember().orElseThrow();
				ClusterBinding bound = Objects.requireNonNull(adapter.bindingState());
				UUID structureId = coordinator.production().computerStructureMemberId().orElseThrow();
				helper.assertTrue(bound.revision() > staleRevision,
					"A second real logistics update must create a newer authoritative revision");
				helper.assertValueEqual(bound.authority(),
					new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR, structureId),
					"Computer structure identity must become Foundation authority");
				helper.assertValueEqual(panel.bindingState(), bound,
					"Panel must receive the exact internal binding propagation");
				helper.assertValueEqual(core.bindingState(), bound,
					"Pattern core must receive the exact internal binding propagation");
				helper.assertValueEqual(bindingTag(replica, level), bound.save(),
					"Every Computer replica must receive the exact binding bytes");
				helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
					ClusterMemberType.COMPUTER_COORDINATOR).size(), 1,
					"Exactly one registered Computer adapter must exist");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, adapter),
					ClusterBindingService.BindingAccess.READY,
					"The registered Computer adapter must be READY through the real binding gate");

				CompoundTag authoritative = serverTag(coordinator, level);
				coordinator.invalidate();
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, core),
					ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE,
					"Invalidating the elected BE must make Foundation authority offline");
				helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
					ClusterMemberType.COMPUTER_COORDINATOR).size(), 0,
					"Active authority loss must not publish a replacement");
				replica.loadWithComponents(staleReplica, level.registryAccess());
				coordinator.loadWithComponents(authoritative, level.registryAccess());

				helper.runAfterDelay(FORMATION_DELAY, () -> {
					try {
						ClusterMember reloaded = coordinator.production().publishedCoordinatorMember().orElseThrow();
						helper.assertValueEqual(ClusterBindingService.bindingAccess(server, reloaded),
							ClusterBindingService.BindingAccess.READY,
							"Reloaded authoritative BE must republish a READY registered adapter");
					helper.assertValueEqual(bindingTag(replica, level),
							Objects.requireNonNull(reloaded.bindingState()).save(),
							"Reload reconciliation must adopt the authority into a stale replica");
						ClusterBinding firstBeforeConflict = Objects.requireNonNull(reloaded.bindingState());
						ClusterMember secondAdapter = second.production().publishedCoordinatorMember().orElseThrow();
						helper.assertFalse(selectPanelAndTryWrenchCasing(player, level,
							panelPos, secondMin).consumesAction(),
							"The real casing-wrench path must reject a second Computer coordinator");
						var secondBefore = secondAdapter.bindingState();
						helper.assertValueEqual(ClusterBindingService.bind(player, panel, secondAdapter),
							ClusterBindingService.BindResult.ACTIVE,
							"The real binding service must report the second-Computer conflict");
						helper.assertTrue(Objects.equals(secondAdapter.bindingState(), secondBefore),
							"Rejected conflict work must not change the second Computer's existing binding state");
						helper.assertValueEqual(reloaded.bindingState(), firstBeforeConflict,
							"Rejected conflict work must not mutate the registered authority");
						helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
							ClusterMemberType.COMPUTER_COORDINATOR).size(), 1,
							"Rejected conflict work must leave exactly one registered adapter");
						helper.succeed();
					} finally {
						Create.LOGISTICS.linkRemoved(logisticsId, link);
					}
				});
			} catch (RuntimeException | Error failure) {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
				throw failure;
			}
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 160)
	public static void faultFreeLastRootCloseAllowsRebindAndInactiveReelection(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos panelPos = helper.absolutePos(new BlockPos(5, 2, 2));
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 4, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity elected = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
		ComputerBlockEntity remaining = placeComputer(level, min.offset(2, 1, 1), HIGH_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, elected.getBlockPos(), capturedResident(level, ProductionProfession.LIBRARIAN, 3).box());
		useBox(player, level, remaining.getBlockPos(), capturedResident(level, ProductionProfession.UNEMPLOYED, 5).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				ClusterMember registered = elected.production().publishedCoordinatorMember().orElseThrow();
				helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
					ClusterMemberType.COMPUTER_COORDINATOR).size(), 1,
					"The multi-node Computer must have one registered adapter before the epoch");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, registered),
					ClusterBindingService.BindingAccess.READY,
					"The actual registered Computer binding must be READY before epoch start");
				helper.assertTrue(elected.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"A READY registered coordinator must start the epoch");
				ProductionEpoch closingEpoch = elected.production().epoch().orElseThrow();
				UUID oldEpochId = closingEpoch.epochId();
				Set<UUID> expectedQuiescenceIds = Set.of(LOW_ID, HIGH_ID);
				helper.assertValueEqual(new HashSet<>(closingEpoch.layout().members().stream()
					.map(node -> node.computerId()).toList()), expectedQuiescenceIds,
					"The frozen epoch must contain both independently known fixture UUIDs");
				UUID structureId = elected.production().computerStructureMemberId().orElseThrow();
				CompoundTag bindingBefore = bindingTag(elected, level);
				QuiescenceProbe closeProbe = new QuiescenceProbe(true);
				helper.assertValueEqual(elected.production().closeIdleEpoch(oldEpochId, closeProbe),
					com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.EpochCloseResult.CLOSED,
					"Natural last-root completion must close a fault-free idle epoch");
				helper.assertTrue(closeProbe.allRootsCalls > 0,
					"Natural close must probe last-root completion");
				helper.assertValueEqual(closeProbe.idleIds, expectedQuiescenceIds,
					"Natural close must probe idle state for every frozen/current UUID");
				helper.assertValueEqual(closeProbe.rootFreeIds, expectedQuiescenceIds,
					"Natural close must probe root-free state for every frozen/current UUID");
				helper.assertValueEqual(closeProbe.mailboxEmptyIds, expectedQuiescenceIds,
					"Natural close must probe mailbox-empty state for every frozen/current UUID");
				for (ComputerBlockEntity replica : List.of(elected, remaining)) {
					helper.assertTrue(replica.production().epoch().isEmpty(), "Close must clear the epoch on every replica");
					helper.assertTrue(replica.production().latchedEpochFaults().isEmpty(),
						"Fault-free close must leave every replica fault-free");
					helper.assertValueEqual(replica.production().computerStructureMemberId().orElseThrow(), structureId,
						"Close must preserve the stable Computer structure member ID");
					helper.assertValueEqual(bindingTag(replica, level), bindingBefore,
						"Close must preserve the exact binding bytes");
				}
				long revisionBeforeRebind = Objects.requireNonNull(registered.bindingState()).revision();
				selectPanelAndWrenchCasing(player, level, panelPos, min);
				ClusterBinding rebound = Objects.requireNonNull(
					elected.production().publishedCoordinatorMember().orElseThrow().bindingState());
				helper.assertTrue(rebound.revision() > revisionBeforeRebind,
					"A real post-close panel selection and casing wrench must rebind");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server,
					elected.production().publishedCoordinatorMember().orElseThrow()),
					ClusterBindingService.BindingAccess.READY,
					"The real post-close binding must remain READY");
				CompoundTag reboundBytes = rebound.save();
				ServerPlayer breaker = headlessPlayer(level, elected.getBlockPos());
				breaker.gameMode.destroyBlock(elected.getBlockPos());

				helper.runAfterDelay(FORMATION_DELAY, () -> {
					try {
						ClusterMember replacement = remaining.production().publishedCoordinatorMember().orElseThrow();
						helper.assertValueEqual(remaining.computerId().orElseThrow(), HIGH_ID,
							"The remaining minimum UUID must be elected after inactive destruction");
						helper.assertValueEqual(replacement.memberId(), structureId,
							"Inactive re-election must preserve the Foundation structure identity");
						helper.assertValueEqual(Objects.requireNonNull(replacement.bindingState()).save(),
							reboundBytes, "Inactive re-election must preserve the new binding");
						helper.assertValueEqual(ClusterBindingService.bindingAccess(server, replacement),
							ClusterBindingService.BindingAccess.READY,
							"The re-elected registered adapter must publish READY");
						QuiescenceProbe stale = new QuiescenceProbe(true);
						helper.assertValueEqual(remaining.production().closeIdleEpoch(oldEpochId, stale),
							com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.EpochCloseResult.NO_EPOCH,
							"A stale close after natural completion must report NO_EPOCH");
						helper.assertValueEqual(stale.calls, 0,
							"NO_EPOCH must return without probing Runtime state");
						helper.succeed();
					} finally {
						Create.LOGISTICS.linkRemoved(logisticsId, link);
					}
				});
			} catch (RuntimeException | Error failure) {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
				throw failure;
			}
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 220)
	public static void activeEpochHotAddAndCoordinatorDestruction(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos panelPos = helper.absolutePos(new BlockPos(5, 2, 2));
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 5, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		BlockPos coordinatorPos = min.offset(1, 1, 1);
		BlockPos ordinaryPos = min.offset(2, 1, 1);
		BlockPos pendingPos = min.offset(3, 1, 1);
		ComputerBlockEntity coordinator = placeComputer(level, coordinatorPos, LOW_ID);
		ComputerBlockEntity ordinary = placeComputer(level, ordinaryPos, MID_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, coordinatorPos, capturedResident(level, ProductionProfession.LIBRARIAN, 5).box());
		useBox(player, level, ordinaryPos, capturedResident(level, ProductionProfession.WANDERING_TRADER, 1).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				helper.assertTrue(coordinator.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"The real READY adapter must start the active-epoch fixture");
				ProductionEpoch frozen = coordinator.production().epoch().orElseThrow();
				CompoundTag frozenBytes = ProductionEpochCodec.save(frozen);
				UUID frozenStructureId = frozen.structureId();
				CompoundTag frozenBindingBytes = bindingTag(coordinator, level);
				Set<EpochFault> frozenFaults = coordinator.production().latchedEpochFaults();
				int frozenCapacity = frozen.layout().members().stream().mapToInt(node -> node.profile().processSlots()).sum();
				ComputerBlockEntity pending = placeComputer(level, pendingPos, HIGH_ID);

				helper.runAfterDelay(FORMATION_DELAY, () -> {
					try {
						List<ComputerNodeView> pendingBefore = coordinator.production().pendingNodes();
						helper.assertValueEqual(pendingBefore.size(), 1,
							"A hot-added empty Computer must appear only as pending");
						helper.assertTrue(pendingBefore.getFirst().pending()
							&& !pendingBefore.getFirst().online(),
							"An empty pending Computer must be pending=true, online=false");
						useBox(player, level, pendingPos,
							capturedResident(level, ProductionProfession.UNEMPLOYED, 4).box());

						helper.runAfterDelay(FORMATION_DELAY, () -> {
							try {
								List<ComputerNodeView> pendingAfter = coordinator.production().pendingNodes();
								helper.assertValueEqual(pendingAfter.size(), 1,
									"Installed hot-add must remain outside the frozen epoch");
								helper.assertTrue(pendingAfter.getFirst().pending()
									&& pendingAfter.getFirst().online(),
									"Installed hot-add must become pending=true, online=true");
								helper.assertValueEqual(ProductionEpochCodec.save(coordinator.production().epoch().orElseThrow()), frozenBytes,
									"Pending installation must not change frozen epoch bytes/order/range");
								helper.assertValueEqual(coordinator.production().epoch().orElseThrow().layout().members().stream()
									.mapToInt(node -> node.profile().processSlots()).sum(), frozenCapacity,
									"Pending installation must not change usable frozen capacity");
								helper.assertValueEqual(coordinator.production().latchedEpochFaults(), frozenFaults,
									"Pending installation must not change fault latches");
								List<ClusterMember> indexedBefore = ClusterMemberIndex.members(server, clusterId,
									ClusterMemberType.COMPUTER_COORDINATOR);
								helper.assertValueEqual(indexedBefore.size(), 1,
									"The healthy active epoch must have exactly one registered adapter");
								helper.assertTrue(indexedBefore.getFirst() instanceof ComputerCoordinatorMember,
									"The registered Computer member must be the concrete coordinator adapter");
								ComputerCoordinatorMember registeredBefore =
									(ComputerCoordinatorMember) indexedBefore.getFirst();
								helper.assertTrue(coordinator.production().publishedCoordinatorMember().orElseThrow()
									== registeredBefore,
									"The pre-degradation index entry must be the exact published adapter");
								helper.assertValueEqual(registeredBefore.coordinatorComputerId(), LOW_ID,
									"The pre-degradation registered adapter must be owned by LOW");
								level.setBlock(ordinaryPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

								helper.runAfterDelay(FORMATION_DELAY, () -> {
									try {
										helper.assertValueEqual(coordinator.production().availabilityReason(),
											ComputerAvailabilityReason.FROZEN_MEMBER_MISSING,
											"A production rescan must observe the missing frozen non-coordinator");
										helper.assertValueEqual(ProductionEpochCodec.save(coordinator.production().epoch().orElseThrow()), frozenBytes,
											"Missing a non-coordinator must preserve complete frozen epoch/order bytes");
										helper.assertValueEqual(coordinator.production().epoch().orElseThrow().coordinatorId(),
											LOW_ID, "Missing a non-coordinator must not replace the frozen coordinator");
										helper.assertValueEqual(bindingTag(coordinator, level), frozenBindingBytes,
											"Missing a non-coordinator must preserve binding bytes");
										helper.assertValueEqual(coordinator.production().computerStructureMemberId().orElseThrow(),
											frozenStructureId,
											"Missing a non-coordinator must preserve structure identity");
										helper.assertValueEqual(coordinator.production().latchedEpochFaults(), frozenFaults,
											"Missing a non-coordinator must preserve fault latches");
										List<ClusterMember> indexedAfter = ClusterMemberIndex.members(server, clusterId,
											ClusterMemberType.COMPUTER_COORDINATOR);
										helper.assertValueEqual(indexedAfter.size(), 1,
											"Missing a non-coordinator must retain exactly one registered adapter");
										helper.assertTrue(indexedAfter.getFirst() instanceof ComputerCoordinatorMember,
											"The degraded index entry must remain a Computer coordinator adapter");
										ComputerCoordinatorMember registeredAfter =
											(ComputerCoordinatorMember) indexedAfter.getFirst();
										ComputerCoordinatorMember retained = coordinator
											.production().publishedCoordinatorMember().orElseThrow();
										helper.assertTrue(registeredAfter == registeredBefore,
											"Degradation must not withdraw and replace the registered adapter object");
										helper.assertTrue(retained == registeredBefore,
											"The published adapter must remain the exact pre-degradation object");
										helper.assertValueEqual(retained.coordinatorComputerId(), LOW_ID,
											"Missing a non-coordinator must retain LOW as physical coordinator");
										helper.assertValueEqual(retained.memberId(), frozenStructureId,
											"The retained adapter must keep the stable Foundation identity");
										helper.assertValueEqual(Objects.requireNonNull(retained.bindingState()).authority(),
											new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
												frozenStructureId),
											"Missing a non-coordinator must not replace Foundation authority");
										helper.assertValueEqual(ClusterBindingService.bindingAccess(server, retained),
											ClusterBindingService.BindingAccess.READY,
											"The retained registered authority must remain READY");

										helper.assertValueEqual(coordinator.production().latchEpochFault(frozen.epochId(),
											EpochFault.WIDTH), com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.FaultLatchResult.LATCHED,
											"The reform fixture must persist a real epoch fault");
										level.setBlock(coordinatorPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

										helper.runAfterDelay(FORMATION_DELAY, () -> {
							try {
								helper.assertValueEqual(pending.production().availabilityReason(),
									ComputerAvailabilityReason.COORDINATOR_MISSING,
									"Active coordinator destruction must remain offline without re-election");
								helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
									ClusterMemberType.COMPUTER_COORDINATOR).size(), 0,
									"Active coordinator destruction must withdraw the registered adapter");
								helper.assertValueEqual(ClusterBindingService.bindingAccess(server, panel),
									ClusterBindingService.BindingAccess.CONFLICT,
									"A Panel withdrawn with its destroyed active epoch must reject service access");
								helper.assertValueEqual(ProductionEpochCodec.save(pending.production().epoch().orElseThrow()), frozenBytes,
									"Coordinator loss must preserve old epoch NBT");
								helper.assertTrue(pending.production().latchedEpochFaults().contains(EpochFault.WIDTH),
									"Coordinator loss must preserve the fault latch");
								helper.assertValueEqual(bindingTag(pending, level), frozenBindingBytes,
									"Coordinator loss must preserve binding bytes");
								helper.assertValueEqual(pending.production().computerStructureMemberId().orElseThrow(),
									frozenStructureId,
									"Coordinator loss must preserve structure identity");

								CompoundTag beforeFailedStop = serverTag(pending, level);
								ReformProbe failedStop = new ReformProbe(false);
								helper.assertValueEqual(pending.production().stopAllAndReform(frozen.epochId(), failedStop),
									com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.ReformResult.STOP_FAILED,
									"A failed Runtime stop must retain the old epoch and latch");
								helper.assertValueEqual(failedStop.stopCalls, 1,
									"STOP_FAILED must come from exactly one real stop/clear callback");
								helper.assertValueEqual(serverTag(pending, level), beforeFailedStop,
									"Failed stop must preserve epoch, binding, structure, and fault bytes");

								ReformProbe success = new ReformProbe(true);
								helper.assertValueEqual(pending.production().stopAllAndReform(frozen.epochId(), success),
									com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.ReformResult.REFORMED,
									"A fully loaded same-space scan omitting old IDs must reform");
								helper.assertValueEqual(success.stopCalls, 1,
									"Successful reform must execute exactly one real stop/clear callback");
								ProductionEpoch replacement = pending.production().epoch().orElseThrow();
								helper.assertFalse(replacement.epochId().equals(frozen.epochId()),
									"Reform must create a new epoch ID");
								helper.assertValueEqual(replacement.layout().members().stream()
									.map(node -> node.computerId()).toList(), List.of(HIGH_ID),
									"Reform must freeze the exact surviving current member set");
								helper.assertTrue(pending.production().latchedEpochFaults().isEmpty(),
									"Successful reform must clear fault latches");
								helper.assertValueEqual(replacement.structureId(),
									frozen.structureId(),
									"Reform must preserve stable Computer structure identity");
								ClusterMember republished = pending.production().publishedCoordinatorMember().orElseThrow();
								helper.assertValueEqual(ClusterBindingService.bindingAccess(server, republished),
									ClusterBindingService.BindingAccess.READY,
									"Reform must restore READY Foundation publication");
								int callsBeforeMismatch = success.stopCalls;
								helper.assertValueEqual(pending.production().stopAllAndReform(frozen.epochId(), success),
									com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.ReformResult.EPOCH_MISMATCH,
									"The abandoned epoch ID must be rejected after reform");
								helper.assertValueEqual(success.stopCalls, callsBeforeMismatch,
									"EPOCH_MISMATCH must not invoke Runtime stop again");
								helper.succeed();
							} finally {
								Create.LOGISTICS.linkRemoved(logisticsId, link);
							}
										});
									} catch (RuntimeException | Error failure) {
										Create.LOGISTICS.linkRemoved(logisticsId, link);
										throw failure;
									}
								});
							} catch (RuntimeException | Error failure) {
								Create.LOGISTICS.linkRemoved(logisticsId, link);
								throw failure;
							}
						});
					} catch (RuntimeException | Error failure) {
						Create.LOGISTICS.linkRemoved(logisticsId, link);
						throw failure;
					}
				});
			} catch (RuntimeException | Error failure) {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
				throw failure;
			}
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 120)
	public static void clientProjectionAndServerRestart(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos panelPos = helper.absolutePos(new BlockPos(5, 2, 2));
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 3, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		BlockPos pos = min.offset(1, 1, 1);
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident resident = capturedResident(level, ProductionProfession.LIBRARIAN, 5);
		resident.source().setCustomName(Component.literal("server-only-sensitive-marker"));
		ItemStack marked = captured(level, resident.source());
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, pos, marked);
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"Restart fixture must begin with a real active epoch");
				UUID epochId = computer.production().epoch().orElseThrow().epochId();
				helper.assertValueEqual(computer.production().latchEpochFault(epochId, EpochFault.DEPTH),
					com.nobodiiiii.createbiotech.content.biocomputing.application.production.computer.ProductionComputer.FaultLatchResult.LATCHED,
					"Restart fixture must contain sensitive fault state");

				CompoundTag serverState = serverTag(computer, level);
				ComputerBlockEntity replacement = new ComputerBlockEntity(pos,
					CBBlocks.COMPUTER.get().defaultBlockState());
				replacement.loadWithComponents(serverState, level.registryAccess());
				helper.assertValueEqual(replacement.computerId(), computer.computerId(),
					"Server restart must preserve Computer identity");
				helper.assertValueEqual(replacement.production().installedProfile(), computer.production().installedProfile(),
					"Server restart must preserve the locked profile");
				helper.assertValueEqual(replacement.production().currentStructureSnapshot(),
					computer.production().currentStructureSnapshot(), "Server restart must preserve structure state");
				helper.assertValueEqual(replacement.production().computerStructureMemberId(),
					computer.production().computerStructureMemberId(), "Server restart must preserve structure identity");
				helper.assertValueEqual(replacement.production().epoch(), computer.production().epoch(),
					"Server restart must preserve epoch state");
				helper.assertValueEqual(replacement.production().latchedEpochFaults(), computer.production().latchedEpochFaults(),
					"Server restart must preserve fault state");
				helper.assertTrue(ItemStack.matches(residentStack(replacement, level),
					residentStack(computer, level)), "Server restart must preserve exact resident data");
				CompoundTag restoredEntity = CBItemData.getOrEmpty(residentStack(replacement, level))
					.getCompound("CapturedEntity");
				helper.assertTrue(restoredEntity.getString("CustomName")
					.contains("server-only-sensitive-marker"),
					"Server restart must retain the sensitive captured CustomName bytes");
				helper.assertTrue(restoredEntity.hasUUID("UUID"),
					"Server restart must retain the captured resident UUID field");
				helper.assertValueEqual(restoredEntity.getUUID("UUID"), resident.uuid(),
					"Server restart must retain the exact captured resident identity");
				helper.assertValueEqual(serverTag(replacement, level).getCompound("ComputerData"),
					serverState.getCompound("ComputerData"),
					"Server restart must round-trip resident/binding/epoch/fault bytes exactly");
				helper.succeed();
			} finally {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
			}
		});
	}

	private static void buildShell(ServerLevel level, BlockPos min,
		int sizeX, int sizeY, int sizeZ) {
		buildShell(level, min, sizeX, sizeY, sizeZ,
			CBBlocks.COMPUTER_CASING.get().defaultBlockState());
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void formedPanelRefusesOrdinaryWrenchRotation(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		BlockPos panelPos = min.offset(0, 1, 1);
		buildShell(level, min, 3, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputerPair(level, min.offset(1, 1, 1), LOW_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, computer.getBlockPos(),
			capturedResident(level, ProductionProfession.LIBRARIAN, 4).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"The real formed Computer must publish its embedded Panel");
				ProductionEpoch epoch = computer.production().epoch().orElseThrow();
				helper.assertTrue(panel.isPublishedFor(clusterId, epoch.epochId()),
					"The Panel must be published before the wrench contract is exercised");
				BlockState before = level.getBlockState(panelPos);
				InteractionResult result = ((FactoryPanelBlock) before.getBlock()).onWrenched(before,
					wrenchContext(player, panelPos));
				helper.assertValueEqual(result, InteractionResult.FAIL,
					"A formed embedded Panel must reject ordinary wrench rotation");
				helper.assertValueEqual(level.getBlockState(panelPos), before,
					"Rejected ordinary wrench use must preserve the exact Panel block state");
				helper.assertTrue(panel.isPublishedFor(clusterId, epoch.epochId()),
					"Rejected ordinary wrench use must preserve active Panel authority");
				helper.succeed();
			} finally {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
			}
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 140)
	public static void panelOpeningContextIsScopedToTheExactPlayerMenuAttempt(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos panelPos = helper.absolutePos(new BlockPos(5, 2, 2));
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 3, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
		ServerPlayer binder = headlessPlayer(level, panelPos);
		useBox(binder, level, computer.getBlockPos(),
			capturedResident(level, ProductionProfession.LIBRARIAN, 3).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, binder.getUUID());

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(binder, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"The session-isolation fixture must publish a formed Panel");
				ProductionEpoch epoch = computer.production().epoch().orElseThrow();
				ClusterBinding binding = Objects.requireNonNull(panel.bindingState());

				MenuOpeningPlayer first = new MenuOpeningPlayer(level,
					new GameProfile(UUID.randomUUID(), "panel-first-opening"));
				first.moveTo(Vec3.atCenterOf(panelPos));
				first.abortBeforeCreate(panel::withdrawComputerEpochPublication);
				ItemStack tool = new ItemStack(CBItems.LOGISTICS_CONFIGURATION_TOOL.get());
				first.setItemInHand(InteractionHand.MAIN_HAND, tool);
				InteractionResult attempted = ((LogisticsConfigurationToolItem) tool.getItem())
					.useOn(new UseOnContext(first, InteractionHand.MAIN_HAND,
						new BlockHitResult(Vec3.atCenterOf(panelPos), Direction.UP, panelPos, false)));
				helper.assertTrue(attempted.consumesAction(),
					"The first player's logistics open must reach the production menu attempt");
				helper.assertTrue(first.constructionAborted,
					"The first menu construction must abort after publication withdrawal");

				helper.assertTrue(panel.publishForComputerEpoch(binding, epoch),
					"The exact formed Panel publication must recover for the second player");
				MenuOpeningPlayer second = new MenuOpeningPlayer(level,
					new GameProfile(UUID.randomUUID(), "panel-second-opening"));
				second.moveTo(Vec3.atCenterOf(panelPos));
				second.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				InteractionResult ordinary = second.gameMode.useItemOn(second, level, ItemStack.EMPTY,
					InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(panelPos),
						Direction.UP, panelPos, false));
				helper.assertTrue(ordinary.consumesAction(),
					"The second player's ordinary Panel open must succeed after recovery");
				helper.assertTrue(second.openedMenu instanceof FactoryPanelMenu,
					"The ordinary production provider must construct a Panel menu");
				FactoryPanelMenu serverMenu = (FactoryPanelMenu) second.openedMenu;
				helper.assertValueEqual(serverMenu.openingContext(),
					FactoryPanelOpeningHint.OpeningContext.defaultContext(),
					"An aborted logistics attempt must not leak into another player's server menu");
				helper.assertTrue(second.decodedMenu instanceof FactoryPanelMenu,
					"The second player's opening payload must decode through the real client constructor");
				helper.assertValueEqual(((FactoryPanelMenu) second.decodedMenu).openingContext(),
					FactoryPanelOpeningHint.OpeningContext.defaultContext(),
					"The ordinary client payload must remain DEFAULT after another player's abort");
				helper.succeed();
			} finally {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
			}
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 180)
	public static void logisticsToolAndAliasPacketUseProductionAuthorityFences(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		BlockPos panelPos = min.offset(0, 1, 1);
		BlockPos tickerPos = helper.absolutePos(new BlockPos(2, 1, 8));
		BlockPos corePos = helper.absolutePos(new BlockPos(1, 1, 5));
		BlockPos stonePos = tickerPos.east();
		buildShell(level, min, 3, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(tickerPos, AllBlocks.STOCK_TICKER.getDefaultState(), Block.UPDATE_ALL);
		level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, corePos);
		level.setBlock(stonePos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
		MenuOpeningPlayer owner = new MenuOpeningPlayer(level,
			new GameProfile(UUID.randomUUID(), "logistics-tool-owner"));
		owner.moveTo(Vec3.atCenterOf(panelPos));
		useBox(owner, level, computer.getBlockPos(),
			capturedResident(level, ProductionProfession.LIBRARIAN, 3).box());
		UUID existingNetwork = UUID.randomUUID();
		UUID capturedNetwork = UUID.randomUUID();
		GlobalPos existingLink = GlobalPos.of(level.dimension(), panelPos.above(3));
		GlobalPos capturedLink = GlobalPos.of(level.dimension(), tickerPos);
		Create.LOGISTICS.linkAdded(existingNetwork, existingLink, owner.getUUID());
		Create.LOGISTICS.linkAdded(capturedNetwork, capturedLink, owner.getUUID());

		LogisticallyLinkedBehaviour linked = BlockEntityBehaviour.get(level, tickerPos,
			LogisticallyLinkedBehaviour.TYPE);
		helper.assertTrue(linked != null,
			"The production Stock Ticker must expose Create's linked behaviour");
		Objects.requireNonNull(linked).freqId = capturedNetwork;

		helper.runAfterDelay(FORMATION_DELAY, () -> awaitLogisticsNetworkObservation(helper,
			level, capturedNetwork, 120, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(owner, panel,
					List.of(new LogisticsBinding(existingNetwork))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"The production tool fixture must publish a formed Panel");
				ClusterBinding before = Objects.requireNonNull(panel.bindingState());
				LibraryBlockEntity library = core(level, corePos);
				helper.assertValueEqual(ClusterBindingService.bind(owner, panel, library),
					ClusterBindingService.BindResult.OK, "The Core must be attached to this formed Cluster");
				helper.assertValueEqual(before.authority(),
					new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
						computer.production().computerStructureMemberId().orElseThrow()),
					"The formed Computer must be the binding authority");

				ItemStack tool = new ItemStack(CBItems.LOGISTICS_CONFIGURATION_TOOL.get());
				owner.setItemInHand(InteractionHand.MAIN_HAND, tool);
				InteractionResult capture = owner.gameMode.useItemOn(owner, level, tool,
					InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(tickerPos),
						Direction.UP, tickerPos, false));
				helper.assertTrue(capture.consumesAction(),
					"The real Stock Ticker useOn path must capture its linked UUID");
				helper.assertValueEqual(LogisticsToolData.read(tool).networkId(),
					Optional.of(capturedNetwork),
					"The linked behaviour UUID must be stored on the exact held tool");
				helper.assertTrue(tool.getItem().isFoil(tool),
					"A captured production tool must render foil");
				helper.assertValueEqual(Block.byItem(tool.getItem()), Blocks.AIR,
					"The logistics configuration tool must remain non-placeable");

				LogisticsToolData capturedData = LogisticsToolData.read(tool);
				((LogisticsConfigurationToolItem) tool.getItem()).use(level, owner,
					InteractionHand.MAIN_HAND);
				helper.assertTrue(LogisticsToolData.read(tool).networkId().isEmpty(),
					"Right-click air must use Create's native clear-tuning interaction");
				owner.gameMode.useItemOn(owner, level, tool, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(tickerPos), Direction.UP, tickerPos, false));
				helper.assertValueEqual(LogisticsToolData.read(tool), capturedData,
					"The real linked component must retune the cleared tool before binding");
				BlockState stoneBefore = level.getBlockState(stonePos);
				owner.gameMode.useItemOn(owner, level, tool, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(stonePos), Direction.UP, stonePos, false));
				helper.assertValueEqual(level.getBlockState(stonePos), stoneBefore,
					"The non-placeable tool must not replace an ordinary clicked block");
				helper.assertValueEqual(LogisticsToolData.read(tool), capturedData,
					"An unrelated block use must preserve the captured UUID");

				owner.clearMenuCapture();
				owner.gameMode.useItemOn(owner, level, tool, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(corePos.above()), Direction.NORTH, corePos.above(), false));
				List<LogisticsBinding> expectedBindings = LogisticsBinding.normalize(List.of(
					new LogisticsBinding(existingNetwork), new LogisticsBinding(capturedNetwork)));
				helper.assertValueEqual(library.logisticsBindings(), expectedBindings,
					"Tool click on the upper Core half must append a captured network without losing the primary");
				long appendedRevision = library.bindingState().revision();
				owner.gameMode.useItemOn(owner, level, tool, InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(corePos), Direction.NORTH, corePos, false));
				helper.assertValueEqual(library.bindingState().revision(), appendedRevision,
					"Clicking the lower Core half with the same network must be idempotent");
				InteractionResult bind = owner.gameMode.useItemOn(owner, level, tool,
					InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(panelPos),
						Direction.UP, panelPos, false));
				helper.assertTrue(bind.consumesAction(),
					"The formed Panel must route the held tool to production useOn");

				ClusterBinding bound = Objects.requireNonNull(panel.bindingState());
				helper.assertValueEqual(bound.logisticsBindings(),
					expectedBindings,
					"The Panel and Core tool entries must preserve the same complete network list");
				helper.assertValueEqual(bound.revision(), appendedRevision,
					"Repeated Panel addition must not advance the binding revision");
				helper.assertValueEqual(computer.production().publishedCoordinatorMember().orElseThrow()
					.bindingState(), bound,
					"The coordinator and embedded Panel must commit the same binding");
				helper.assertValueEqual(bound.authority(), before.authority(),
					"Replacing logistics must preserve formed coordinator authority");
				helper.assertTrue(bound.revision() > before.revision(),
					"Replacing logistics must advance the Cluster binding revision");
				helper.assertTrue(owner.openedMenu instanceof FactoryPanelMenu,
					"An observer-allocated network must select the production logistics page");

				owner.clearMenuCapture();
				LogisticsAliasMenu.OpeningProvider aliasProvider = LogisticsAliasMenu.provider(owner,
					InteractionHand.MAIN_HAND, tool, capturedNetwork);
				owner.openMenu(aliasProvider, aliasProvider::writeOpeningData);
				helper.assertTrue(owner.openedProvider instanceof LogisticsAliasMenu.OpeningProvider,
					"Alias editing must use the real revision-fenced menu provider");
				helper.assertTrue(owner.openedMenu instanceof LogisticsAliasMenu,
					"The real provider must construct the server alias menu");
				helper.assertTrue(owner.decodedMenu instanceof LogisticsAliasMenu,
					"The real opening payload must decode through the client alias menu");

				LogisticsAliasMenu serverMenu = (LogisticsAliasMenu) owner.openedMenu;
				LogisticsAliasMenu clientMenu = (LogisticsAliasMenu) owner.decodedMenu;
				helper.assertValueEqual(clientMenu.networkId(), capturedNetwork,
					"The opening payload must carry the exact captured network");
				helper.assertValueEqual(clientMenu.customAlias(), Optional.empty(),
					"An observer-allocated directory entry must open without an alias");
				LogisticsAliasRenamePacket packet = clientMenu.renamePacket("A".repeat(64));
				RegistryFriendlyByteBuf packetBuffer = new RegistryFriendlyByteBuf(
					Unpooled.buffer(), level.registryAccess());
				packet.write(packetBuffer);
				LogisticsAliasRenamePacket decodedPacket =
					new LogisticsAliasRenamePacket(packetBuffer);
				helper.assertValueEqual(decodedPacket, packet,
					"The real rename payload must encode and decode every session fence");

				LogisticsNetworkDirectory directory = FactoryOrchestrationSavedData
					.get(level.getServer()).logisticsNetworkDirectory(level.registryAccess());
				LogisticsNetworkDirectoryEntry allocated = directory.find(capturedNetwork)
					.orElseThrow();
				LogisticsAliasRenamePacket.SessionFence fence = packet.sessionFence();
				helper.assertValueEqual(fence.stackFingerprint().length(), 64,
					"The production opening must use a bounded SHA-256 fingerprint");

				Consumer<LogisticsAliasRenamePacket> reject = candidate -> {
					helper.assertFalse(candidate.handle(owner),
						"A mutated session fence must fail closed");
					helper.assertValueEqual(directory.find(capturedNetwork).orElseThrow(),
						allocated, "A rejected packet must not mutate the directory");
				};
				reject.accept(new LogisticsAliasRenamePacket(new LogisticsAliasRenamePacket.SessionFence(
					fence.containerId() + 1, fence.hand(), fence.slot(), fence.stackFingerprint(),
					fence.networkId(), fence.expectedRevision()), packet.customAlias()));
				reject.accept(new LogisticsAliasRenamePacket(new LogisticsAliasRenamePacket.SessionFence(
					fence.containerId(), InteractionHand.OFF_HAND, 40, fence.stackFingerprint(),
					fence.networkId(), fence.expectedRevision()), packet.customAlias()));
				int otherSlot = fence.slot() == 0 ? 1 : 0;
				reject.accept(new LogisticsAliasRenamePacket(new LogisticsAliasRenamePacket.SessionFence(
					fence.containerId(), InteractionHand.MAIN_HAND, otherSlot,
					fence.stackFingerprint(), fence.networkId(), fence.expectedRevision()),
					packet.customAlias()));
				reject.accept(new LogisticsAliasRenamePacket(new LogisticsAliasRenamePacket.SessionFence(
					fence.containerId(), fence.hand(), fence.slot(), "0".repeat(64),
					fence.networkId(), fence.expectedRevision()), packet.customAlias()));
				reject.accept(new LogisticsAliasRenamePacket(new LogisticsAliasRenamePacket.SessionFence(
					fence.containerId(), fence.hand(), fence.slot(), fence.stackFingerprint(),
					UUID.randomUUID(), fence.expectedRevision()), packet.customAlias()));
				reject.accept(new LogisticsAliasRenamePacket(new LogisticsAliasRenamePacket.SessionFence(
					fence.containerId(), fence.hand(), fence.slot(), fence.stackFingerprint(),
					fence.networkId(), fence.expectedRevision() + 1), packet.customAlias()));

				owner.containerMenu = owner.inventoryMenu;
				reject.accept(packet);
				owner.containerMenu = serverMenu;
				int selected = owner.getInventory().selected;
				owner.getInventory().selected = otherSlot;
				reject.accept(packet);
				owner.getInventory().selected = selected;
				owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
				owner.setItemInHand(InteractionHand.OFF_HAND, tool);
				reject.accept(packet);
				owner.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
				owner.setItemInHand(InteractionHand.MAIN_HAND, tool);
				tool.set(DataComponents.CUSTOM_NAME, Component.literal("mutated"));
				reject.accept(packet);
				tool.remove(DataComponents.CUSTOM_NAME);
				tool.setCount(2);
				reject.accept(packet);
				tool.setCount(1);
				LogisticsToolData.write(tool,
					new LogisticsToolData(Optional.of(UUID.randomUUID())));
				reject.accept(packet);
				LogisticsToolData.write(tool, capturedData);

				MenuOpeningPlayer intruder = new MenuOpeningPlayer(level,
					new GameProfile(UUID.randomUUID(), "logistics-tool-intruder"));
				intruder.moveTo(Vec3.atCenterOf(panelPos));
				intruder.setItemInHand(InteractionHand.MAIN_HAND, tool.copy());
				intruder.containerMenu = serverMenu;
				helper.assertFalse(packet.handle(intruder),
					"An exact copied session must not cross the Create network administrator");
				helper.assertValueEqual(directory.find(capturedNetwork).orElseThrow(),
					allocated, "Another player's rejected packet must not mutate the directory");

				helper.assertTrue(decodedPacket.handle(owner),
					"The exact live production packet must reach LogisticsAliasMenu.apply");
				LogisticsNetworkDirectoryEntry renamed = directory.find(capturedNetwork)
					.orElseThrow();
				helper.assertValueEqual(renamed.customAlias(), Optional.of("A".repeat(64)),
					"The exact 64-character alias must be committed");
				helper.assertValueEqual(renamed.revision(), allocated.revision() + 1,
					"The accepted CAS rename must advance the directory revision once");
				helper.assertFalse(decodedPacket.handle(owner),
					"Replaying the accepted packet must fail the revision CAS");
				owner.containerMenu = owner.inventoryMenu;
				helper.assertFalse(decodedPacket.handle(owner),
					"A packet from a closed menu must fail before mutation");
				helper.assertValueEqual(directory.find(capturedNetwork).orElseThrow(), renamed,
					"Replay and closed-menu rejection must preserve the committed alias");
				helper.succeed();
			} finally {
				Create.LOGISTICS.linkRemoved(existingNetwork, existingLink);
				Create.LOGISTICS.linkRemoved(capturedNetwork, capturedLink);
			}
		}));
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", batch = "factory_prod_25", timeoutTicks = 180)
	public static void unboundFormedPanelReadsTasksWithoutNewTaskDefaults(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		BlockPos panelPos = min.offset(0, 1, 1);
		buildShell(level, min, 3, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputerPair(level, min.offset(1, 1, 1), LOW_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, computer.getBlockPos(),
			capturedResident(level, ProductionProfession.LIBRARIAN, 4).box());
		UUID logistics = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logistics, link, null);
		helper.runAfterDelay(FORMATION_DELAY, () -> {
			FactoryPanelBlockEntity panel = panel(level, panelPos);
			helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logistics))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
			helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(panel.clusterId())).isPresent(),
					"unbound Computer must be formed");
			helper.runAfterDelay(10, () -> {
				helper.assertTrue(ClusterBindingService.replaceFormedBindings(player, panel, List.of()).succeeded(),
					"the live Cluster must accept removing its logistics binding");
				helper.assertTrue(FactoryOrchestrationServer.panelTaskCommandFence(panel).isPresent(),
					"valid unbound Cluster must publish Tasks independently of New Task defaults");
				FactoryPanelMenu menu = (FactoryPanelMenu) panel.createMenu(39,player.getInventory(),player);
				player.containerMenu = menu;
				var tasks = (com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListResponsePacket)
					readFinalIdlePanel(menu,player,com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.TASKS,2301);
				helper.assertValueEqual(tasks.projection().status(),com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskListProjection.Status.CURRENT,
					"unbound formed Cluster returns CURRENT Tasks through real menu and wire");
				var context = (com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.NewTaskContextResponsePacket)
					readFinalIdlePanel(menu,player,com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NEW_TASK,2302);
				helper.assertValueEqual(context.projection().status(),com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.NewTaskContextProjection.Status.NO_INPUT,
					"unbound New Task reports missing input without inventing an address");
				helper.runAfterDelay(25, () -> {
					var before = (com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network.CommunicationNetworkResponsePacket)
						readFinalIdlePanel(menu,player,com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView.NETWORK_COMMUNICATION,2303);
					var command = new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket(menu.opening(),
						before.projection().generation(),before.projection().revision(),
						new com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelCommunicationCommandPacket.ChangePrimary(
							before.projection().localEdits().orElseThrow().declarationRevision(),com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency.of("final-idle")));
					helper.runAfterDelay(25, () -> {
						helper.assertTrue(menu.execute(command,player),"idle real topology resampling must not expire a lawful edit");
						helper.assertFalse(menu.execute(command,player),"real configuration change still rejects stale edit CAS");
						helper.succeed();
					});
				});
			});
		});
	}

	private static net.createmod.catnip.net.base.ClientboundPacketPayload readFinalIdlePanel(FactoryPanelMenu menu,
		ServerPlayer player, com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView view,long intent) {
		com.nobodiiiii.createbiotech.network.CBPackets.beginPanelDeliveryObservationForTest(player);
		var request = new com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelViewRequestPacket(menu.opening(),view,
			java.util.OptionalLong.empty(),0,1,Optional.empty(),intent);
		if (!menu.execute(request,player)) throw new IllegalStateException("idle Panel view request rejected: " + view);
		var response = com.nobodiiiii.createbiotech.network.CBPackets.takePanelDeliveryForTest(player).orElseThrow();
		return com.nobodiiiii.createbiotech.network.CBPackets.roundTripPanelForTest(response,
			new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),player.registryAccess()));
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 140)
	public static void panelStateTamperImmediatelyRejectsAuthorityThenRescanWithdraws(
		GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		BlockPos panelPos = min.offset(0, 1, 1);
		buildShell(level, min, 3, 4, 3);
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = placeComputerPair(level, min.offset(1, 1, 1), LOW_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, computer.getBlockPos(),
			capturedResident(level, ProductionProfession.LIBRARIAN, 4).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				helper.assertValueEqual(ClusterBindingService.replaceFormedBindings(player, panel,
					List.of(new LogisticsBinding(logisticsId))), ClusterBindingService.BindResult.OK,
					"The automatically formed Cluster must authorize its fixture logistics");
				helper.assertTrue(computer.production().epoch().filter(epoch -> epoch.groupId().equals(clusterId)).isPresent(),
					"The real formed Computer must start before Panel state tampering");
				ProductionEpoch epoch = computer.production().epoch().orElseThrow();
				helper.runAfterDelay(10, () -> {
					try {
						player.moveTo(Vec3.atCenterOf(panelPos));
						FactoryPanelMenu menu = (FactoryPanelMenu) panel.createMenu(37,
							player.getInventory(), player);
						player.containerMenu = menu;
						helper.assertTrue(menu.stillValid(player),
							"The exact live Panel menu must be valid before state tampering");

						BlockState before = level.getBlockState(panelPos);
						BlockState tampered = before.setValue(FactoryPanelBlock.BASE_MATERIAL,
							ComputerShellMaterial.TRANSPARENT);
						helper.assertTrue(level.setBlock(panelPos, tampered, Block.UPDATE_CLIENTS),
							"The external state-tamper fixture must commit without replacing the Panel BE");
						helper.assertTrue(level.getBlockEntity(panelPos) == panel,
							"The external state tamper must retain the same loaded Panel identity");
						helper.assertFalse(panel.isPublishedFor(clusterId, epoch.epochId()),
							"Panel publication must fail closed in the same tick when frozen state changes");
						helper.assertFalse(panel.canPlayerUse(player),
							"Panel interaction authority must fail closed before the next topology scan");
						helper.assertFalse(menu.stillValid(player),
							"An already-open menu must fail after same-tick Panel state tampering");

						helper.runAfterDelay(FORMATION_DELAY, () -> {
							try {
								helper.assertTrue(ClusterMemberIndex.members(server, clusterId,
									ClusterMemberType.PANEL).isEmpty(),
									"The next real topology scan must withdraw the tampered Panel publication");
								helper.assertTrue(panel.canRebind(),
									"Topology withdrawal must release the stale Panel publication marker");
								helper.succeed();
							} finally {
								Create.LOGISTICS.linkRemoved(logisticsId, link);
							}
						});
					} catch (RuntimeException | Error failure) {
						Create.LOGISTICS.linkRemoved(logisticsId, link);
						throw failure;
					}
				});
			} catch (RuntimeException | Error failure) {
				Create.LOGISTICS.linkRemoved(logisticsId, link);
				throw failure;
			}
		});
	}

	private static void buildShell(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ,
		BlockState casing) {
		for (int x = 0; x < sizeX; x++)
			for (int y = 0; y < sizeY; y++)
				for (int z = 0; z < sizeZ; z++) {
					boolean shell = x == 0 || x == sizeX - 1 || y == 0 || y == sizeY - 1
						|| z == 0 || z == sizeZ - 1;
					level.setBlock(min.offset(x, y, z), shell ? casing : Blocks.AIR.defaultBlockState(),
						Block.UPDATE_ALL);
				}
	}

	private static ComputerBlockEntity computer(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof ComputerBlockEntity computer))
			throw new IllegalStateException("Missing Computer at " + pos);
		return computer;
	}

	private static CapturedResident capturedResident(ServerLevel level, ProductionProfession kind,
		int residentLevel) {
		LivingEntity entity;
		if (kind == ProductionProfession.WANDERING_TRADER) {
			WanderingTrader trader = Objects.requireNonNull(EntityType.WANDERING_TRADER.create(level));
			trader.setDespawnDelay(4321);
			trader.getOffers().add(knownOffer());
			entity = trader;
		} else if (kind == ProductionProfession.ZOMBIE_VILLAGER) {
			entity = Objects.requireNonNull(EntityType.ZOMBIE_VILLAGER.create(level));
		} else {
			Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
			VillagerProfession profession = switch (kind) {
				case LIBRARIAN -> VillagerProfession.LIBRARIAN;
				case NITWIT -> VillagerProfession.NITWIT;
				case UNEMPLOYED -> VillagerProfession.NONE;
				default -> throw new IllegalArgumentException("unsupported fixture profession " + kind);
			};
			villager.setVillagerData(villager.getVillagerData().setType(VillagerType.PLAINS)
				.setProfession(profession).setLevel(residentLevel));
			villager.getOffers().add(knownOffer());
			entity = villager;
		}
		entity.setCustomName(Component.literal("computer-" + kind.name() + "-" + residentLevel));
		entity.setHealth(13.0F);
		UUID uuid = entity.getUUID();
		ItemStack box = captured(level, entity);
		return new CapturedResident(box, entity, uuid);
	}

	private static ServerPlayer headlessPlayer(ServerLevel level, BlockPos absolutePos) {
		FakePlayer player = new FakePlayer(level,
			new GameProfile(UUID.randomUUID(), "computer-cluster-test"));
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(absolutePos));
		return player;
	}

	private static TrackingPlayer trackingPlayer(ServerLevel level, BlockPos absolutePos) {
		TrackingPlayer player = new TrackingPlayer(level,
			new GameProfile(UUID.randomUUID(), "computer-pipeline-test"));
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(absolutePos));
		return player;
	}

	private static <T extends Entity> List<T> entities(ServerLevel level, Class<T> type,
		BlockPos center, double radius) {
		return level.getEntitiesOfClass(type, new AABB(center).inflate(radius),
			entity -> !entity.isRemoved());
	}

	private static void awaitLogisticsNetworkObservation(GameTestHelper helper,
		ServerLevel level, UUID networkId, int remaining, Runnable success) {
		LogisticsNetworkDirectory directory = FactoryOrchestrationSavedData.get(level.getServer())
			.logisticsNetworkDirectory(level.registryAccess());
		if (directory.find(networkId).isPresent()) {
			success.run();
			return;
		}
		if (remaining <= 0) {
			helper.fail("Create logistics observer never allocated the captured network");
			return;
		}
		helper.runAfterDelay(1, () -> awaitLogisticsNetworkObservation(helper, level,
			networkId, remaining - 1, success));
	}

	private static void assertEmptyTemplateFixture(GameTestHelper helper) {
		try (InputStream stream = ComputerClusterGameTests.class
			.getResourceAsStream("/data/create_biotech/structure/empty.nbt")) {
			helper.assertTrue(stream != null, "create_biotech:empty must be present on the runtime classpath");
			CompoundTag root = NbtIo.readCompressed(Objects.requireNonNull(stream),
				NbtAccounter.unlimitedHeap());
			ListTag size = root.getList("size", Tag.TAG_INT);
			helper.assertValueEqual(size.size(), 3, "Template size must contain three axes");
			helper.assertTrue(size.getInt(0) >= 20 && size.getInt(1) >= 12 && size.getInt(2) >= 12,
				"Template dimensions must contain the largest Computer fixture");
			helper.assertValueEqual(root.getList("palette", Tag.TAG_COMPOUND).size(), 0,
				"Empty template must not define a block palette");
			helper.assertValueEqual(root.getList("blocks", Tag.TAG_COMPOUND).size(), 0,
				"Empty template must not place blocks");
			helper.assertValueEqual(root.getList("entities", Tag.TAG_COMPOUND).size(), 0,
				"Empty template must not place entities");
			helper.forEveryBlockInStructure(pos -> helper.assertTrue(
				helper.getLevel().getBlockState(pos).isAir(),
				"Empty template placed content at " + pos.toShortString()));
		} catch (IOException exception) {
			helper.fail("Could not decode create_biotech:empty: " + exception.getMessage());
		}
	}

	private static ComputerBlockEntity placeComputer(ServerLevel level, BlockPos pos, UUID id) {
		return placeComputerPair(level, pos, id);
	}

	private static ComputerBlockEntity placeComputerPair(ServerLevel level, BlockPos pos, UUID id) {
		BlockPos support = pos.below();
		if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP))
			level.setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		BlockState lower = CBBlocks.COMPUTER.get().defaultBlockState()
			.setValue(ComputerBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(pos, lower, Block.UPDATE_ALL);
		level.setBlock(pos.above(), lower.setValue(ComputerBlock.HALF, DoubleBlockHalf.UPPER),
			Block.UPDATE_ALL);
		ComputerBlockEntity computer = computer(level, pos);
		CompoundTag tag = serverTag(computer, level);
		tag.getCompound("ComputerData").putUUID("ComputerId", id);
		computer.loadWithComponents(tag, level.registryAccess());
		return computer;
	}

	private static BlockPlaceContext placementContext(ServerPlayer player, BlockPos pos) {
		return new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
	}

	/** Drives the same two placement hooks as the normal item placement path. */
	private static BlockState placeComputerThroughBlockMethods(ServerLevel level, ServerPlayer player,
		BlockPos pos) {
		BlockPos support = pos.below();
		if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP))
			level.setBlock(support, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlock block = (ComputerBlock) CBBlocks.COMPUTER.get();
		BlockState lower = block.getStateForPlacement(placementContext(player, pos));
		if (lower == null) throw new IllegalStateException("Computer placement unexpectedly rejected");
		level.setBlock(pos, lower, Block.UPDATE_ALL);
		block.setPlacedBy(level, pos, lower, player, new ItemStack(CBItems.COMPUTER.get()));
		return lower;
	}

	private static void assertReleasedPair(GameTestHelper helper, ServerLevel level, BlockPos pos,
		UUID residentId, String route) {
		helper.assertFalse(level.getBlockState(pos).is(CBBlocks.COMPUTER.get()),
			route + " must remove the lower owner");
		helper.assertFalse(level.getBlockState(pos.above()).is(CBBlocks.COMPUTER.get()),
			route + " must remove the upper half");
		helper.assertValueEqual(entities(level, Entity.class, pos, 5).stream()
			.filter(entity -> entity.getUUID().equals(residentId)).count(), 1L,
			route + " must release the resident exactly once");
		helper.assertValueEqual(filledRecoveryItems(level, pos, 5).size(), 0,
			route + " must not roll back to a recovery item");
	}

	private static InteractionResult useBox(ServerPlayer player, ServerLevel level,
		BlockPos pos, ItemStack held) {
		player.setItemInHand(InteractionHand.MAIN_HAND, held);
		return player.gameMode.useItemOn(player, level, held, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
	}

	private static ItemStack captured(ServerLevel level, LivingEntity entity) {
		ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
		if (!CapturedEntityBoxHelper.captureEntity(box, entity))
			throw new IllegalStateException("Could not capture Computer GameTest resident");
		return box;
	}

	private static RejectedInstall rejectedInstall(ServerLevel level, ServerPlayer player,
		BlockPos pos, ItemStack held) {
		ComputerBlockEntity computer = placeComputer(level, pos, UUID.randomUUID());
		CompoundTag computerBefore = serverTag(computer, level);
		CompoundTag heldBefore = stackTag(held, level);
		useBox(player, level, pos, held);
		return new RejectedInstall(computer, held, computerBefore, heldBefore);
	}

	private static void mutateSourceAfterCapture(LivingEntity source) {
		source.setCustomName(Component.literal("mutated-after-install"));
		source.setHealth(1.0F);
		if (source instanceof Villager villager)
			villager.setVillagerData(villager.getVillagerData()
				.setProfession(VillagerProfession.NITWIT).setLevel(1));
		if (source instanceof WanderingTrader trader) trader.setDespawnDelay(1);
	}

	private static MerchantOffer knownOffer() {
		return new MerchantOffer(new ItemCost(Items.EMERALD, 1), Optional.empty(),
			new ItemStack(Items.BREAD, 3), 0, 12, 2, 0.05F, 0);
	}

	private static int traderListenerRangeBonus() {
		return FactoryRuntimeConfig.fromServerConfig().wanderingTraderListenerBonus();
	}

	private static ProductionResourceProfile exactExpectedProfile(ProfileFixture fixture) {
		return switch (fixture.kind()) {
			case UNEMPLOYED -> new ProductionResourceProfile(ProductionProfession.UNEMPLOYED,
				2, Set.of(), 0);
			case LIBRARIAN -> {
				int[] slots = {4, 6, 8, 12, 16};
				yield new ProductionResourceProfile(ProductionProfession.LIBRARIAN,
					slots[fixture.level() - 1], Set.of(), 0);
			}
			case NITWIT -> new ProductionResourceProfile(ProductionProfession.NITWIT,
				1, Set.of(), 0);
			case WANDERING_TRADER -> new ProductionResourceProfile(ProductionProfession.WANDERING_TRADER,
				2, Set.of(ProductionCapability.EXTENDED_RADIO), traderListenerRangeBonus());
			case ZOMBIE_VILLAGER -> new ProductionResourceProfile(ProductionProfession.ZOMBIE_VILLAGER,
				0, Set.of(), 0);
			default -> throw new IllegalArgumentException("unsupported fixture profession "
				+ fixture.kind());
		};
	}

	private static CompoundTag serverTag(ComputerBlockEntity computer, ServerLevel level) {
		return computer.saveWithoutMetadata(level.registryAccess());
	}

	private static CompoundTag bindingTag(ComputerBlockEntity computer, ServerLevel level) {
		return serverTag(computer, level).getCompound("ComputerData").getCompound("Binding").copy();
	}

	private static ItemStack residentStack(ComputerBlockEntity computer, ServerLevel level) {
		return ItemStack.parseOptional(level.registryAccess(),
			serverTag(computer, level).getCompound("ComputerData").getCompound("Resident"));
	}

	private static CompoundTag stackTag(ItemStack stack, ServerLevel level) {
		return (CompoundTag) stack.save(level.registryAccess(), new CompoundTag());
	}

	private static void assertBounds(GameTestHelper helper, ProductionStructure snapshot,
		BlockPos min, int sizeX, int sizeY, int sizeZ) {
		ProductionLayout.Bounds bounds = snapshot.layout().bounds();
		helper.assertValueEqual(bounds.minX(), min.getX(), "Structure min X must be exact");
		helper.assertValueEqual(bounds.minY(), min.getY(), "Structure min Y must be exact");
		helper.assertValueEqual(bounds.minZ(), min.getZ(), "Structure min Z must be exact");
		helper.assertValueEqual(bounds.maxX(), min.getX() + sizeX - 1, "Structure max X must be exact");
		helper.assertValueEqual(bounds.maxY(), min.getY() + sizeY - 1, "Structure max Y must be exact");
		helper.assertValueEqual(bounds.maxZ(), min.getZ() + sizeZ - 1, "Structure max Z must be exact");
	}

	private static void clearFixture(ServerLevel level, GameTestHelper helper) {
		for (int x = 0; x < 20; x++)
			for (int y = 0; y < 12; y++)
				for (int z = 0; z < 12; z++)
					level.setBlock(helper.absolutePos(new BlockPos(x, y, z)),
						Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static long countInventoryItem(ServerPlayer player, net.minecraft.world.item.Item item) {
		long count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static long countWorldItem(ServerLevel level, BlockPos center, double radius,
		net.minecraft.world.item.Item item) {
		return entities(level, ItemEntity.class, center, radius).stream()
			.filter(entity -> entity.getItem().is(item)).mapToLong(entity -> entity.getItem().getCount()).sum();
	}

	private static List<ItemEntity> computerDrops(ServerLevel level, BlockPos center, double radius) {
		return entities(level, ItemEntity.class, center, radius).stream()
			.filter(entity -> entity.getItem().is(CBItems.COMPUTER.get())).toList();
	}

	private static void assertEmptyComputerStack(GameTestHelper helper, ItemStack stack) {
		helper.assertTrue(stack.is(CBItems.COMPUTER.get()),
			"Committed forced loot must be the Computer block item");
		helper.assertTrue(stack.get(DataComponents.BLOCK_ENTITY_DATA) == null,
			"Committed Computer loot must not carry block-entity data");
		helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(stack),
			"Committed Computer loot must not carry resident data");
	}

	private static List<ItemEntity> filledRecoveryItems(ServerLevel level, BlockPos center,
		double radius) {
		return entities(level, ItemEntity.class, center, radius).stream()
			.filter(entity -> CapturedEntityBoxHelper.hasCapturedEntity(entity.getItem())).toList();
	}

	private static void assertOccupiedUnchanged(GameTestHelper helper, ServerLevel level,
		BlockPos pos, CompoundTag before) {
		helper.assertTrue(level.getBlockState(pos).is(CBBlocks.COMPUTER.get()),
			"Double failure must reconstruct the occupied Computer block");
		helper.assertValueEqual(serverTag(computer(level, pos), level), before,
			"Double failure must restore the complete authoritative BE tag");
	}

	private static void assertOccupiedPairUnchanged(GameTestHelper helper, ServerLevel level,
		BlockPos pos, CompoundTag before) {
		BlockState lower = level.getBlockState(pos);
		BlockState upper = level.getBlockState(pos.above());
		helper.assertTrue(lower.is(CBBlocks.COMPUTER.get())
			&& lower.getValue(ComputerBlock.HALF) == DoubleBlockHalf.LOWER,
			"Rollback must retain the exact lower owner");
		helper.assertTrue(upper.is(CBBlocks.COMPUTER.get())
			&& upper.getValue(ComputerBlock.HALF) == DoubleBlockHalf.UPPER,
			"Rollback must restore the missing upper half instead of leaving an orphan lower");
		helper.assertValueEqual(upper.getValue(ComputerBlock.FACING), lower.getValue(ComputerBlock.FACING),
			"Rollback must restore matching pair orientation");
		helper.assertTrue(level.getBlockEntity(pos.above()) == null,
			"The restored upper half must never own a block entity");
		helper.assertValueEqual(serverTag(computer(level, pos), level), before,
			"Rollback must restore the complete authoritative lower BE tag");
	}

	private static void assertUpperRouteDoubleFailure(GameTestHelper helper, ServerLevel level,
		BlockPos pos, UUID residentId, Runnable route, String routeName) {
		DoubleFailureListener listener = new DoubleFailureListener(level, residentId);
		NeoForge.EVENT_BUS.register(listener);
		try {
			route.run();
			helper.assertValueEqual(listener.rejectedResidents, 1,
				routeName + " must make one resident release attempt");
			helper.assertValueEqual(listener.rejectedRecoveries, 1,
				routeName + " must make one recovery attempt");
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
		}
	}

	private static void placeCore(ServerLevel level, BlockPos lower) {
		BlockState lowerState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
			.setValue(LibraryCoreBlock.FACING, Direction.NORTH)
			.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(lower, lowerState, Block.UPDATE_ALL);
		level.setBlock(lower.above(), lowerState.setValue(LibraryCoreBlock.HALF,
			DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
	}

	private static FactoryPanelBlockEntity panel(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity panel))
			throw new IllegalStateException("Missing factory panel at " + pos);
		return panel;
	}

	private static LibraryBlockEntity core(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof LibraryBlockEntity core))
			throw new IllegalStateException("Missing pattern core at " + pos);
		return core;
	}

	private static void selectPanelAndWrenchCasing(ServerPlayer player, ServerLevel level,
		BlockPos panelPos, BlockPos casingPos) {
		InteractionResult bound = selectPanelAndTryWrenchCasing(player, level, panelPos, casingPos);
		if (!bound.consumesAction()) throw new IllegalStateException("Computer casing bind failed");
	}

	private static InteractionResult selectPanelAndTryWrenchCasing(ServerPlayer player,
		ServerLevel level, BlockPos panelPos, BlockPos casingPos) {
		ClusterBindingSelection.clear(player);
		ClusterBindingSelection.begin(player, panel(level, panelPos));
		UseOnContext casingContext = new UseOnContext(player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(casingPos), Direction.UP, casingPos, false));
		return ((ComputerCasingBlock) CBBlocks.COMPUTER_CASING.get())
			.onSneakWrenched(level.getBlockState(casingPos), casingContext);
	}

	private record CapturedResident(ItemStack box, LivingEntity source, UUID uuid) {}
	private record RejectedInstall(ComputerBlockEntity computer, ItemStack held,
		CompoundTag computerBefore, CompoundTag heldBefore) {}
	private record ProfileFixture(ProductionProfession kind, int level) {}
	private record InstalledFixture(ProfileFixture fixture, ComputerBlockEntity computer) {}

	private static final class ExternalListenerConfigurationStore
		implements ExternalListenerService.ConfigurationStore {
		private final ProductionResourceProfile profile;
		private ExternalListenerConfiguration configuration = ExternalListenerConfiguration.empty();

		private ExternalListenerConfigurationStore(ProductionResourceProfile profile) {
			this.profile = Objects.requireNonNull(profile, "profile");
		}

		@Override public ProductionResourceProfile profile() { return profile; }
		@Override public ExternalListenerConfiguration configuration() { return configuration; }
		@Override public boolean listenerPersistenceAuthorized() { return true; }
		@Override public boolean compareAndSet(ExternalListenerConfiguration expected,
			ExternalListenerConfiguration next) {
			if (!configuration.equals(expected)) return false;
			configuration = next;
			return true;
		}
	}

	private static final class DoubleFailureListener {
		private final ServerLevel level;
		private final UUID residentId;
		private int rejectedResidents;
		private int rejectedRecoveries;

		private DoubleFailureListener(ServerLevel level, UUID residentId) {
			this.level = level;
			this.residentId = residentId;
		}

		@SubscribeEvent
		public void onJoin(EntityJoinLevelEvent event) {
			if (event.getLevel() != level) return;
			if (event.getEntity().getUUID().equals(residentId)) {
				rejectedResidents++;
				event.setCanceled(true);
				return;
			}
			if (event.getEntity() instanceof ItemEntity item
				&& CapturedEntityBoxHelper.hasCapturedEntity(item.getItem())) {
				rejectedRecoveries++;
				event.setCanceled(true);
			}
		}
	}

	private static final class BreakProtectionListener {
		private final ServerLevel level;
		private final BlockPos protectedLower;
		private int lowerAttempts;

		private BreakProtectionListener(ServerLevel level, BlockPos protectedLower) {
			this.level = level;
			this.protectedLower = protectedLower;
		}

		@SubscribeEvent
		public void onBreak(BlockEvent.BreakEvent event) {
			if (event.getLevel() != level || !event.getPos().equals(protectedLower)) return;
			lowerAttempts++;
			event.setCanceled(true);
		}
	}

	private static final class TrackingPlayer extends FakePlayer {
		private int computerMined;

		private TrackingPlayer(ServerLevel level, GameProfile profile) {
			super(level, profile);
		}

		@Override
		public void awardStat(Stat<?> stat, int amount) {
			if (stat == Stats.BLOCK_MINED.get(CBBlocks.COMPUTER.get()))
				computerMined += amount;
		}
	}

	private static final class MenuOpeningPlayer extends FakePlayer {
		@Nullable private Runnable beforeCreate;
		@Nullable private MenuProvider openedProvider;
		@Nullable private AbstractContainerMenu openedMenu;
		@Nullable private AbstractContainerMenu decodedMenu;
		private boolean constructionAborted;
		private boolean expectConstructionAbort;
		private int nextContainerId = 1;

		private MenuOpeningPlayer(ServerLevel level, GameProfile profile) {
			super(level, profile);
			gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		}

		private void abortBeforeCreate(Runnable action) {
			beforeCreate = Objects.requireNonNull(action);
			expectConstructionAbort = true;
		}

		private void clearMenuCapture() {
			openedProvider = null;
			openedMenu = null;
			decodedMenu = null;
			constructionAborted = false;
		}

		@Override
		public OptionalInt openMenu(MenuProvider provider,
			@Nullable Consumer<RegistryFriendlyByteBuf> extraDataWriter) {
			int containerId = nextContainerId++;
			clearMenuCapture();
			openedProvider = provider;
			try {
				Runnable action = beforeCreate;
				beforeCreate = null;
				if (action != null) action.run();
				AbstractContainerMenu menu = provider.createMenu(containerId, getInventory(), this);
				if (menu == null) return OptionalInt.empty();
				openedMenu = menu;
				containerMenu = menu;
				if (extraDataWriter != null) {
					RegistryFriendlyByteBuf opening = new RegistryFriendlyByteBuf(
						Unpooled.buffer(), registryAccess());
					extraDataWriter.accept(opening);
					if (menu instanceof FactoryPanelMenu)
						decodedMenu = new FactoryPanelMenu(containerId, getInventory(), opening);
					else if (menu instanceof LogisticsAliasMenu)
						decodedMenu = new LogisticsAliasMenu(containerId, getInventory(), opening);
				}
				expectConstructionAbort = false;
				return OptionalInt.of(containerId);
			} catch (IllegalStateException expectedAbort) {
				if (!expectConstructionAbort) throw expectedAbort;
				expectConstructionAbort = false;
				constructionAborted = true;
				return OptionalInt.empty();
			}
		}
	}

	private static class QuiescenceProbe implements EpochQuiescence {
		private final boolean quiet;
		private int calls;
		private int allRootsCalls;
		private final Set<UUID> idleIds = new HashSet<>();
		private final Set<UUID> rootFreeIds = new HashSet<>();
		private final Set<UUID> mailboxEmptyIds = new HashSet<>();
		private QuiescenceProbe(boolean quiet) { this.quiet = quiet; }
		@Override public boolean allRootsStopped() { calls++; allRootsCalls++; return quiet; }
		@Override public boolean nodeIdle(UUID computerId) {
			calls++;
			idleIds.add(computerId);
			return quiet;
		}
		@Override public boolean nodeRootFree(UUID computerId) {
			calls++;
			rootFreeIds.add(computerId);
			return quiet;
		}
		@Override public boolean nodeMailboxEmpty(UUID computerId) {
			calls++;
			mailboxEmptyIds.add(computerId);
			return quiet;
		}
	}

	private static final class ReformProbe extends QuiescenceProbe implements EpochReformControl {
		private final boolean stopResult;
		private int stopCalls;
		private ReformProbe(boolean stopResult) {
			super(true);
			this.stopResult = stopResult;
		}
		@Override public boolean stopAllAndClear() { stopCalls++; return stopResult; }
	}
}
