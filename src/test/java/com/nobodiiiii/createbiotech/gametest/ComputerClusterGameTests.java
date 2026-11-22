package com.nobodiiiii.createbiotech.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.cardboardbox.CardboardBoxEntity;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentAddressRegistration;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResolution;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ExternalListenerService;
import com.nobodiiiii.createbiotech.content.biocomputing.block.ComputerBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetRouteTrace;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.stats.Stat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
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
import com.nobodiiiii.createbiotech.CreateBiotech;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ComputerClusterGameTests {
	private static final UUID LOW_ID = new UUID(0, 1);
	private static final UUID MID_ID = new UUID(0, 2);
	private static final UUID HIGH_ID = new UUID(0, 3);

	private ComputerClusterGameTests() {}

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
			helper.assertTrue(accepted.computerId().isPresent() && !serverTag(accepted, level).getCompound("ComputerData").contains("Profile"),
                "Public installation does not create a production profile");
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
				var view = com.nobodiiiii.createbiotech.content.biocomputing.computer.resident.ComputerResidentView
                    .fromSnapshot(fixture.computer().residentSnapshot(), level.registryAccess()).resident();
                helper.assertTrue(view != null, "Real resident is available after installation");
                helper.assertValueEqual(view.customName(), "computer-" + fixture.fixture().kind().name() + "-" + fixture.fixture().level(),
                    "Stored resident keeps its original name after the source is changed");
                if (view.entityType() == EntityType.VILLAGER)
                    helper.assertValueEqual(view.level(), fixture.fixture().level(), "Stored villager keeps its experience level");
			}
			helper.succeed();
		});
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

	private static CompoundTag serverTag(ComputerBlockEntity computer, ServerLevel level) {
		return computer.saveWithoutMetadata(level.registryAccess());
	}

	private static ItemStack residentStack(ComputerBlockEntity computer, ServerLevel level) {
		return ItemStack.parseOptional(level.registryAccess(),
			serverTag(computer, level).getCompound("ComputerData").getCompound("Resident"));
	}

	private static CompoundTag stackTag(ItemStack stack, ServerLevel level) {
		return (CompoundTag) stack.save(level.registryAccess(), new CompoundTag());
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



}
