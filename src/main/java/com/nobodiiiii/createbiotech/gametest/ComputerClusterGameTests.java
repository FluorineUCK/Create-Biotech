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
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CardboardBoxEntity;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingSelection;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ClusterEpoch;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerAvailabilityReason;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerCasingBlock;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerNodeView;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerStructureSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochFault;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochQuiescence;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.EpochReformControl;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.NodeKind;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlock;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStorageCoreBlock;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStorageCoreBlockEntity;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.Create;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void publicBoxInteractionInstallsAtomically(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos acceptedPos = helper.absolutePos(new BlockPos(2, 2, 2));
		ComputerBlockEntity accepted = placeComputer(level, acceptedPos, LOW_ID);
		CapturedResident source = capturedResident(level, NodeKind.VILLAGER, 3);
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
			helper.assertValueEqual(accepted.installedProfile().orElseThrow(),
				exactExpectedProfile(new ProfileFixture(NodeKind.VILLAGER, 3)),
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
	public static void everyResidentProfileLocksThroughPublicInteraction(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		ServerPlayer player = headlessPlayer(level, helper.absolutePos(new BlockPos(1, 2, 1)));
		List<ProfileFixture> fixtures = new ArrayList<>();
		for (int villagerLevel = 1; villagerLevel <= 5; villagerLevel++) {
			fixtures.add(new ProfileFixture(NodeKind.VILLAGER, villagerLevel));
			fixtures.add(new ProfileFixture(NodeKind.LIBRARIAN, villagerLevel));
		}
		fixtures.add(new ProfileFixture(NodeKind.NITWIT, 1));
		fixtures.add(new ProfileFixture(NodeKind.WANDERING_TRADER, 1));
		fixtures.add(new ProfileFixture(NodeKind.ZOMBIE_VILLAGER, 1));
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
				ComputerProfile expected = exactExpectedProfile(fixture.fixture());
				ComputerProfile actual = fixture.computer().installedProfile().orElseThrow();
				helper.assertValueEqual(actual, expected,
					"All thirteen exact slot/depth rows must be locked at install time");
				helper.assertValueEqual(actual.loopAbort(),
					fixture.fixture().kind() == NodeKind.NITWIT,
					"Only nitwits may set the loop-abort capability");
				helper.assertValueEqual(actual.patternRangeBonus(),
					fixture.fixture().kind() == NodeKind.WANDERING_TRADER ? traderRangeBonus() : 0,
					"Only wandering traders may contribute the frozen range bonus");
			}
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void threeByThreeAndThreeByFiveBySevenForm(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos cubeMin = helper.absolutePos(new BlockPos(1, 1, 1));
		buildShell(level, cubeMin, 3, 3, 3);
		ComputerBlockEntity empty = placeComputer(level, cubeMin.offset(1, 1, 1), new UUID(0, 30));

		BlockPos rectangularMin = helper.absolutePos(new BlockPos(6, 1, 1));
		buildShell(level, rectangularMin, 3, 5, 7);
		ComputerBlockEntity high = placeComputer(level, rectangularMin.offset(1, 1, 1), HIGH_ID);
		ComputerBlockEntity low = placeComputer(level, rectangularMin.offset(1, 3, 5), LOW_ID);
		ServerPlayer player = headlessPlayer(level, rectangularMin.offset(1, 2, 2));
		useBox(player, level, high.getBlockPos(), capturedResident(level, NodeKind.VILLAGER, 1).box());
		useBox(player, level, low.getBlockPos(), capturedResident(level, NodeKind.LIBRARIAN, 5).box());

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			helper.assertValueEqual(empty.availabilityReason(), ComputerAvailabilityReason.NOT_READY,
				"An exact 3^3 shell with an empty Computer must be VALID_NOT_READY");
			assertBounds(helper, empty.currentStructureSnapshot().orElseThrow(), cubeMin, 3, 3, 3);
			helper.assertValueEqual(low.availabilityReason(), ComputerAvailabilityReason.NONE,
				"A fully profiled 3x5x7 shell must be VALID");
			ComputerStructureSnapshot snapshot = low.currentStructureSnapshot().orElseThrow();
			assertBounds(helper, snapshot, rectangularMin, 3, 5, 7);
			helper.assertValueEqual(snapshot.nodes().size(), 2,
				"The non-cubic shell must contain both interior Computers");
			helper.assertTrue(low.publishedCoordinatorMember().isPresent(),
				"The minimum UUID must publish the coordinator adapter");
			helper.assertTrue(high.publishedCoordinatorMember().isEmpty(),
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
		buildShell(level, firstMin, 3, 3, 3);
		buildShell(level, secondMin, 3, 3, 3);
		BlockPos spur = firstMin.offset(3, 1, 1);
		level.setBlock(spur, CBBlocks.COMPUTER_CASING.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity first = placeComputer(level, firstMin.offset(1, 1, 1), LOW_ID);
		ComputerBlockEntity second = placeComputer(level, secondMin.offset(1, 1, 1), HIGH_ID);
		first.tick();
		second.tick();
		assertBounds(helper, first.currentStructureSnapshot().orElseThrow(), firstMin, 3, 3, 3);
		assertBounds(helper, second.currentStructureSnapshot().orElseThrow(), secondMin, 3, 3, 3);
		helper.assertFalse(first.currentStructureSnapshot().orElseThrow().casingPositions().contains(spur),
			"An external casing spur must not alter accepted bounds");
		helper.assertFalse(first.computerStructureMemberId().equals(second.computerStructureMemberId()),
			"Casing-touching shells must remain separate structures");

		BlockPos outerMin = helper.absolutePos(new BlockPos(10, 1, 1));
		buildShell(level, outerMin, 7, 7, 7);
		BlockPos innerMin = outerMin.offset(2, 2, 2);
		buildShell(level, innerMin, 3, 3, 3);
		level.setBlock(outerMin.offset(5, 3, 3), CBBlocks.COMPUTER_CASING.get().defaultBlockState(),
			Block.UPDATE_ALL);
		ComputerBlockEntity ambiguous = placeComputer(level, innerMin.offset(1, 1, 1), MID_ID);
		ambiguous.tick();
		helper.assertValueEqual(ambiguous.availabilityReason(), ComputerAvailabilityReason.AMBIGUOUS,
			"Nested complete shells connected by the explicit interior bus bridge must be ambiguous");
		helper.assertTrue(ambiguous.currentStructureSnapshot().isEmpty(),
			"An ambiguous scan must never publish a structure");

		clearFixture(level, helper);
		BlockPos min = helper.absolutePos(new BlockPos(1, 1, 1));
		buildShell(level, min, 3, 3, 3);
		level.setBlock(min.offset(0, 1, 1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity hole = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
		hole.tick();
		helper.assertTrue(hole.currentStructureSnapshot().isEmpty(), "A shell hole must never form");

		clearFixture(level, helper);
		buildShell(level, min, 7, 7, 7);
		level.setBlock(min.offset(4, 3, 3), CBBlocks.COMPUTER_CASING.get().defaultBlockState(),
			Block.UPDATE_ALL);
		ComputerBlockEntity isolated = placeComputer(level, min.offset(3, 3, 3), LOW_ID);
		isolated.tick();
		helper.assertTrue(isolated.currentStructureSnapshot().isEmpty(),
			"An isolated interior casing bus must never form");

		int savedMin = CBConfigs.SERVER.factoryCluster.computerMinSize.get();
		int savedMax = CBConfigs.SERVER.factoryCluster.computerMaxSize.get();
		int savedNodes = CBConfigs.SERVER.factoryCluster.computerMaxNodes.get();
		try {
			clearFixture(level, helper);
			CBConfigs.SERVER.factoryCluster.computerMinSize.set(4);
			CBConfigs.SERVER.factoryCluster.computerMaxSize.set(7);
			buildShell(level, min, 3, 3, 3);
			ComputerBlockEntity tooSmall = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
			tooSmall.tick();
			helper.assertTrue(tooSmall.currentStructureSnapshot().isEmpty(),
				"Configured minimum minus one must never form");

			clearFixture(level, helper);
			CBConfigs.SERVER.factoryCluster.computerMinSize.set(3);
			CBConfigs.SERVER.factoryCluster.computerMaxSize.set(6);
			buildShell(level, min, 7, 7, 7);
			ComputerBlockEntity tooLarge = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
			tooLarge.tick();
			helper.assertTrue(tooLarge.currentStructureSnapshot().isEmpty(),
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
		for (int y = 1; y <= 5 && nodes.size() < 33; y++)
			for (int z = 1; z <= 5 && nodes.size() < 33; z++)
				for (int x : new int[] {1, 5}) {
					if (nodes.size() == 33) break;
					nodes.add(placeComputer(level, min.offset(x, y, z), new UUID(2, ordinal++)));
				}
		nodes.getFirst().tick();
		helper.assertTrue(nodes.getFirst().currentStructureSnapshot().isEmpty(),
			"The thirty-third Computer must reject formation at default limits");
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void breakReleasesExactlyOneHalfHeartResidentAndEmptyComputer(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(4, 3, 4));
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident captured = capturedResident(level, NodeKind.WANDERING_TRADER, 1);
		WanderingTrader decoded = (WanderingTrader) Objects.requireNonNull(
			CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(captured.box(), level));
		int despawnDelay = decoded.getDespawnDelay();
		String name = Objects.requireNonNull(decoded.getCustomName()).getString();
		CompoundTag offers = decoded.saveWithoutId(new CompoundTag()).getCompound("Offers").copy();
		ServerPlayer installer = headlessPlayer(level, pos);
		useBox(installer, level, pos, captured.box());
		ServerPlayer breaker = survivalPlayer(helper, pos);
		breaker.gameMode.destroyBlock(pos);

		helper.runAfterDelay(3, () -> {
			List<Entity> residents = entities(level, Entity.class, pos, 4).stream()
				.filter(entity -> entity.getUUID().equals(captured.uuid())).toList();
			helper.assertValueEqual(residents.size(), 1, "A controlled break must release exactly one resident");
			helper.assertTrue(residents.getFirst() instanceof WanderingTrader,
				"The released resident must retain its exact type");
			WanderingTrader trader = (WanderingTrader) residents.getFirst();
			helper.assertTrue(Math.abs(trader.getHealth() - 1.0F) < 0.0001F,
				"The released resident must have exactly one half-heart");
			helper.assertValueEqual(trader.getDespawnDelay(), despawnDelay,
				"Trader despawn delay must survive release");
			helper.assertValueEqual(Objects.requireNonNull(trader.getCustomName()).getString(), name,
				"Resident name must survive release");
			helper.assertValueEqual(trader.saveWithoutId(new CompoundTag())
				.getCompound("Offers"), offers,
				"Resident trades must survive release");
			List<ItemStack> computerDrops = new ArrayList<>();
			for (ItemEntity item : entities(level, ItemEntity.class, pos, 5))
				if (item.getItem().is(CBItems.COMPUTER.get())) computerDrops.add(item.getItem());
			for (int slot = 0; slot < breaker.getInventory().getContainerSize(); slot++) {
				ItemStack stack = breaker.getInventory().getItem(slot);
				if (stack.is(CBItems.COMPUTER.get())) computerDrops.add(stack);
			}
			long emptyComputers = computerDrops.stream().mapToLong(ItemStack::getCount).sum();
			helper.assertValueEqual(emptyComputers, 1L,
				"Controlled survival break must produce exactly one empty Computer");
			for (ItemStack drop : computerDrops) {
				helper.assertTrue(drop.get(DataComponents.BLOCK_ENTITY_DATA) == null,
					"The empty Computer drop must not retain block-entity data");
				helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(drop),
					"The empty Computer drop must not retain captured-resident data");
			}
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
		CapturedResident captured = capturedResident(level, NodeKind.VILLAGER, 4);
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
		CapturedResident captured = capturedResident(level, NodeKind.LIBRARIAN, 4);
		useBox(headlessPlayer(level, pos), level, pos, captured.box());
		CompoundTag before = serverTag(computer, level);
		ServerPlayer breaker = survivalPlayer(helper, pos);
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

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 140)
	public static void realPanelPatternComputerBindingAndAuthorityReload(GameTestHelper helper) {
		assertEmptyTemplateFixture(helper);
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos panelPos = helper.absolutePos(new BlockPos(1, 2, 2));
		BlockPos corePos = helper.absolutePos(new BlockPos(3, 2, 2));
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, corePos);
		BlockPos firstMin = helper.absolutePos(new BlockPos(7, 1, 1));
		buildShell(level, firstMin, 3, 5, 3);
		ComputerBlockEntity coordinator = placeComputer(level, firstMin.offset(1, 1, 1), LOW_ID);
		ComputerBlockEntity replica = placeComputer(level, firstMin.offset(1, 3, 1), HIGH_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, coordinator.getBlockPos(),
			capturedResident(level, NodeKind.LIBRARIAN, 5).box());
		useBox(player, level, replica.getBlockPos(),
			capturedResident(level, NodeKind.VILLAGER, 3).box());

		BlockPos secondMin = helper.absolutePos(new BlockPos(12, 1, 1));
		buildShell(level, secondMin, 3, 3, 3);
		ComputerBlockEntity second = placeComputer(level, secondMin.offset(1, 1, 1), MID_ID);
		useBox(player, level, second.getBlockPos(),
			capturedResident(level, NodeKind.VILLAGER, 2).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				PatternStorageCoreBlockEntity core = core(level, corePos);
				UUID clusterId = panel.clusterId();
				panel.commitClusterBinding(new ClusterBinding(clusterId, 0,
					new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
					List.of(new LogisticsBinding(logisticsId, "computer-world-gate"))));
				helper.assertValueEqual(ClusterBindingService.bind(player, panel, core),
					ClusterBindingService.BindResult.OK,
					"The real panel and pattern core must first share the logistics binding");
				selectPanelAndWrenchCasing(player, level, panelPos, firstMin);
				CompoundTag staleReplica = serverTag(replica, level);
				long staleRevision = ClusterBinding.tryLoad(bindingTag(replica, level))
					.orElseThrow().revision();
				selectPanelAndWrenchCasing(player, level, panelPos, firstMin);
				ClusterMember adapter = coordinator.publishedCoordinatorMember().orElseThrow();
				ClusterBinding bound = Objects.requireNonNull(adapter.bindingState());
				UUID structureId = coordinator.computerStructureMemberId().orElseThrow();
				helper.assertTrue(bound.revision() > staleRevision,
					"A second real wrench bind must create a newer authoritative revision");
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
						ClusterMember reloaded = coordinator.publishedCoordinatorMember().orElseThrow();
						helper.assertValueEqual(ClusterBindingService.bindingAccess(server, reloaded),
							ClusterBindingService.BindingAccess.READY,
							"Reloaded authoritative BE must republish a READY registered adapter");
					helper.assertValueEqual(bindingTag(replica, level),
							Objects.requireNonNull(reloaded.bindingState()).save(),
							"Reload reconciliation must adopt the authority into a stale replica");
						ClusterBinding firstBeforeConflict = Objects.requireNonNull(reloaded.bindingState());
						ClusterMember secondAdapter = second.publishedCoordinatorMember().orElseThrow();
						helper.assertFalse(selectPanelAndTryWrenchCasing(player, level,
							panelPos, secondMin).consumesAction(),
							"The real casing-wrench path must reject a second Computer coordinator");
						helper.assertValueEqual(ClusterBindingService.bind(player, panel, secondAdapter),
							ClusterBindingService.BindResult.CONFLICT,
							"The real binding service must report the second-Computer conflict");
						helper.assertTrue(secondAdapter.bindingState() == null,
							"Rejected conflict work must not bind the second Computer");
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
		BlockPos panelPos = helper.absolutePos(new BlockPos(1, 2, 2));
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 3, 5, 3);
		ComputerBlockEntity elected = placeComputer(level, min.offset(1, 1, 1), LOW_ID);
		ComputerBlockEntity remaining = placeComputer(level, min.offset(1, 3, 1), HIGH_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, elected.getBlockPos(), capturedResident(level, NodeKind.LIBRARIAN, 3).box());
		useBox(player, level, remaining.getBlockPos(), capturedResident(level, NodeKind.VILLAGER, 5).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				panel.commitClusterBinding(new ClusterBinding(clusterId, 0,
					new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
					List.of(new LogisticsBinding(logisticsId, "close-rebind"))));
				selectPanelAndWrenchCasing(player, level, panelPos, min);
				ClusterMember registered = elected.publishedCoordinatorMember().orElseThrow();
				helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
					ClusterMemberType.COMPUTER_COORDINATOR).size(), 1,
					"The multi-node Computer must have one registered adapter before the epoch");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, registered),
					ClusterBindingService.BindingAccess.READY,
					"The actual registered Computer binding must be READY before epoch start");
				helper.assertValueEqual(elected.startEpoch(clusterId),
					ComputerBlockEntity.EpochStartResult.STARTED,
					"A READY registered coordinator must start the epoch");
				ClusterEpoch closingEpoch = elected.epoch().orElseThrow();
				UUID oldEpochId = closingEpoch.epochId();
				Set<UUID> expectedQuiescenceIds = Set.of(LOW_ID, HIGH_ID);
				helper.assertValueEqual(new HashSet<>(closingEpoch.nodes().stream()
					.map(node -> node.computerId()).toList()), expectedQuiescenceIds,
					"The frozen epoch must contain both independently known fixture UUIDs");
				UUID structureId = elected.computerStructureMemberId().orElseThrow();
				CompoundTag bindingBefore = bindingTag(elected, level);
				QuiescenceProbe closeProbe = new QuiescenceProbe(true);
				helper.assertValueEqual(elected.closeIdleEpoch(oldEpochId, closeProbe),
					ComputerBlockEntity.EpochCloseResult.CLOSED,
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
					helper.assertTrue(replica.epoch().isEmpty(), "Close must clear the epoch on every replica");
					helper.assertTrue(replica.latchedEpochFaults().isEmpty(),
						"Fault-free close must leave every replica fault-free");
					helper.assertValueEqual(replica.computerStructureMemberId().orElseThrow(), structureId,
						"Close must preserve the stable Computer structure member ID");
					helper.assertValueEqual(bindingTag(replica, level), bindingBefore,
						"Close must preserve the exact binding bytes");
				}
				long revisionBeforeRebind = Objects.requireNonNull(registered.bindingState()).revision();
				selectPanelAndWrenchCasing(player, level, panelPos, min);
				ClusterBinding rebound = Objects.requireNonNull(
					elected.publishedCoordinatorMember().orElseThrow().bindingState());
				helper.assertTrue(rebound.revision() > revisionBeforeRebind,
					"A real post-close panel selection and casing wrench must rebind");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server,
					elected.publishedCoordinatorMember().orElseThrow()),
					ClusterBindingService.BindingAccess.READY,
					"The real post-close binding must remain READY");
				CompoundTag reboundBytes = rebound.save();
				ServerPlayer breaker = survivalPlayer(helper, elected.getBlockPos());
				breaker.gameMode.destroyBlock(elected.getBlockPos());

				helper.runAfterDelay(FORMATION_DELAY, () -> {
					try {
						ClusterMember replacement = remaining.publishedCoordinatorMember().orElseThrow();
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
						helper.assertValueEqual(remaining.closeIdleEpoch(oldEpochId, stale),
							ComputerBlockEntity.EpochCloseResult.NO_EPOCH,
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
		BlockPos panelPos = helper.absolutePos(new BlockPos(1, 2, 2));
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 3, 5, 3);
		BlockPos coordinatorPos = min.offset(1, 1, 1);
		BlockPos ordinaryPos = min.offset(1, 2, 1);
		BlockPos pendingPos = min.offset(1, 3, 1);
		ComputerBlockEntity coordinator = placeComputer(level, coordinatorPos, LOW_ID);
		ComputerBlockEntity ordinary = placeComputer(level, ordinaryPos, MID_ID);
		ServerPlayer player = headlessPlayer(level, panelPos);
		useBox(player, level, coordinatorPos, capturedResident(level, NodeKind.LIBRARIAN, 5).box());
		useBox(player, level, ordinaryPos, capturedResident(level, NodeKind.WANDERING_TRADER, 1).box());
		UUID logisticsId = UUID.randomUUID();
		GlobalPos link = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, link, null);

		helper.runAfterDelay(FORMATION_DELAY, () -> {
			try {
				FactoryPanelBlockEntity panel = panel(level, panelPos);
				UUID clusterId = panel.clusterId();
				panel.commitClusterBinding(new ClusterBinding(clusterId, 0,
					new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
					List.of(new LogisticsBinding(logisticsId, "active-reform"))));
				selectPanelAndWrenchCasing(player, level, panelPos, min);
				helper.assertValueEqual(coordinator.startEpoch(clusterId),
					ComputerBlockEntity.EpochStartResult.STARTED,
					"The real READY adapter must start the active-epoch fixture");
				ClusterEpoch frozen = coordinator.epoch().orElseThrow();
				CompoundTag frozenBytes = frozen.save();
				UUID frozenStructureId = frozen.computerStructureMemberId();
				CompoundTag frozenBindingBytes = bindingTag(coordinator, level);
				int frozenCapacity = frozen.nodes().stream().mapToInt(node -> node.profile().slots()).sum();
				ComputerBlockEntity pending = placeComputer(level, pendingPos, HIGH_ID);

				helper.runAfterDelay(FORMATION_DELAY, () -> {
					List<ComputerNodeView> pendingBefore = coordinator.pendingNodes();
					helper.assertValueEqual(pendingBefore.size(), 1,
						"A hot-added empty Computer must appear only as pending");
					helper.assertTrue(pendingBefore.getFirst().pending() && !pendingBefore.getFirst().online(),
						"An empty pending Computer must be pending=true, online=false");
					useBox(player, level, pendingPos,
						capturedResident(level, NodeKind.VILLAGER, 4).box());

					helper.runAfterDelay(FORMATION_DELAY, () -> {
						List<ComputerNodeView> pendingAfter = coordinator.pendingNodes();
						helper.assertValueEqual(pendingAfter.size(), 1,
							"Installed hot-add must remain outside the frozen epoch");
						helper.assertTrue(pendingAfter.getFirst().pending() && pendingAfter.getFirst().online(),
							"Installed hot-add must become pending=true, online=true");
						helper.assertValueEqual(coordinator.epoch().orElseThrow().save(), frozenBytes,
							"Pending installation must not change frozen epoch bytes/order/range");
						helper.assertValueEqual(coordinator.epoch().orElseThrow().nodes().stream()
							.mapToInt(node -> node.profile().slots()).sum(), frozenCapacity,
							"Pending installation must not change usable frozen capacity");
						helper.assertTrue(coordinator.latchedEpochFaults().isEmpty(),
							"Pending installation must not add a fault latch");
						helper.assertValueEqual(coordinator.latchEpochFault(frozen.epochId(), EpochFault.WIDTH),
							ComputerBlockEntity.FaultLatchResult.LATCHED,
							"The reform fixture must persist a real epoch fault");
						level.setBlock(ordinaryPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
						helper.assertValueEqual(coordinator.epoch().orElseThrow().save(), frozenBytes,
							"Removing a non-coordinator must preserve the complete frozen epoch");
						helper.assertValueEqual(bindingTag(coordinator, level), frozenBindingBytes,
							"Removing a non-coordinator must preserve binding bytes");
						helper.assertValueEqual(coordinator.computerStructureMemberId().orElseThrow(),
							frozenStructureId,
							"Removing a non-coordinator must preserve structure identity");
						level.setBlock(coordinatorPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

						helper.runAfterDelay(FORMATION_DELAY, () -> {
							try {
								helper.assertValueEqual(pending.availabilityReason(),
									ComputerAvailabilityReason.COORDINATOR_MISSING,
									"Active coordinator destruction must remain offline without re-election");
								helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
									ClusterMemberType.COMPUTER_COORDINATOR).size(), 0,
									"Active coordinator destruction must withdraw the registered adapter");
								helper.assertValueEqual(ClusterBindingService.bindingAccess(server, panel),
									ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE,
									"Foundation must observe the missing active coordinator as offline");
								helper.assertValueEqual(pending.epoch().orElseThrow().save(), frozenBytes,
									"Coordinator loss must preserve old epoch NBT");
								helper.assertTrue(pending.latchedEpochFaults().contains(EpochFault.WIDTH),
									"Coordinator loss must preserve the fault latch");
								helper.assertValueEqual(bindingTag(pending, level), frozenBindingBytes,
									"Coordinator loss must preserve binding bytes");
								helper.assertValueEqual(pending.computerStructureMemberId().orElseThrow(),
									frozenStructureId,
									"Coordinator loss must preserve structure identity");

								CompoundTag beforeFailedStop = serverTag(pending, level);
								ReformProbe failedStop = new ReformProbe(false);
								helper.assertValueEqual(pending.stopAllAndReform(frozen.epochId(), failedStop),
									ComputerBlockEntity.ReformResult.STOP_FAILED,
									"A failed Runtime stop must retain the old epoch and latch");
								helper.assertValueEqual(failedStop.stopCalls, 1,
									"STOP_FAILED must come from exactly one real stop/clear callback");
								helper.assertValueEqual(serverTag(pending, level), beforeFailedStop,
									"Failed stop must preserve epoch, binding, structure, and fault bytes");

								ReformProbe success = new ReformProbe(true);
								helper.assertValueEqual(pending.stopAllAndReform(frozen.epochId(), success),
									ComputerBlockEntity.ReformResult.REFORMED,
									"A fully loaded same-space scan omitting old IDs must reform");
								helper.assertValueEqual(success.stopCalls, 1,
									"Successful reform must execute exactly one real stop/clear callback");
								ClusterEpoch replacement = pending.epoch().orElseThrow();
								helper.assertFalse(replacement.epochId().equals(frozen.epochId()),
									"Reform must create a new epoch ID");
								helper.assertValueEqual(replacement.nodes().stream()
									.map(node -> node.computerId()).toList(), List.of(HIGH_ID),
									"Reform must freeze the exact surviving current member set");
								helper.assertTrue(pending.latchedEpochFaults().isEmpty(),
									"Successful reform must clear fault latches");
								helper.assertValueEqual(replacement.computerStructureMemberId(),
									frozen.computerStructureMemberId(),
									"Reform must preserve stable Computer structure identity");
								ClusterMember republished = pending.publishedCoordinatorMember().orElseThrow();
								helper.assertValueEqual(ClusterBindingService.bindingAccess(server, republished),
									ClusterBindingService.BindingAccess.READY,
									"Reform must restore READY Foundation publication");
								int callsBeforeMismatch = success.stopCalls;
								helper.assertValueEqual(pending.stopAllAndReform(frozen.epochId(), success),
									ComputerBlockEntity.ReformResult.EPOCH_MISMATCH,
									"The abandoned epoch ID must be rejected after reform");
								helper.assertValueEqual(success.stopCalls, callsBeforeMismatch,
									"EPOCH_MISMATCH must not invoke Runtime stop again");
								helper.succeed();
							} finally {
								Create.LOGISTICS.linkRemoved(logisticsId, link);
							}
						});
					});
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
		BlockPos panelPos = helper.absolutePos(new BlockPos(1, 2, 2));
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		BlockPos min = helper.absolutePos(new BlockPos(5, 1, 1));
		buildShell(level, min, 3, 3, 3);
		BlockPos pos = min.offset(1, 1, 1);
		ComputerBlockEntity computer = placeComputer(level, pos, LOW_ID);
		CapturedResident resident = capturedResident(level, NodeKind.LIBRARIAN, 5);
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
				panel.commitClusterBinding(new ClusterBinding(clusterId, 0,
					new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
					List.of(new LogisticsBinding(logisticsId, "restart"))));
				selectPanelAndWrenchCasing(player, level, panelPos, min);
				helper.assertValueEqual(computer.startEpoch(clusterId),
					ComputerBlockEntity.EpochStartResult.STARTED,
					"Restart fixture must begin with a real active epoch");
				UUID epochId = computer.epoch().orElseThrow().epochId();
				helper.assertValueEqual(computer.latchEpochFault(epochId, EpochFault.DEPTH),
					ComputerBlockEntity.FaultLatchResult.LATCHED,
					"Restart fixture must contain sensitive fault state");

				CompoundTag update = computer.getUpdateTag(level.registryAccess());
				Set<String> keys = new HashSet<>();
				List<String> strings = new ArrayList<>();
				collect(update, keys, strings);
				Set<String> forbidden = Set.of("CapturedEntity", "UUID", "ComputerId", "CoordinatorId",
					"EpochId", "Offers", "Recipes", "Brain", "Inventory", "NoAI", "DespawnDelay",
					"ClusterId", "LogisticsBindings", "Authority", "Revision", "StructureMemberId",
					"Epoch", "Nodes", "Address", "Frames", "Mailboxes");
				helper.assertTrue(java.util.Collections.disjoint(keys, forbidden),
					"Client update projection must recursively exclude every sensitive key");
				helper.assertFalse(strings.contains("server-only-sensitive-marker"),
					"Client update projection must exclude resident marker strings");

				CompoundTag serverState = serverTag(computer, level);
				ComputerBlockEntity replacement = new ComputerBlockEntity(pos,
					CBBlocks.COMPUTER.get().defaultBlockState());
				replacement.loadWithComponents(serverState, level.registryAccess());
				helper.assertValueEqual(replacement.computerId(), computer.computerId(),
					"Server restart must preserve Computer identity");
				helper.assertValueEqual(replacement.installedProfile(), computer.installedProfile(),
					"Server restart must preserve the locked profile");
				helper.assertValueEqual(replacement.currentStructureSnapshot(),
					computer.currentStructureSnapshot(), "Server restart must preserve structure state");
				helper.assertValueEqual(replacement.computerStructureMemberId(),
					computer.computerStructureMemberId(), "Server restart must preserve structure identity");
				helper.assertValueEqual(replacement.epoch(), computer.epoch(),
					"Server restart must preserve epoch state");
				helper.assertValueEqual(replacement.latchedEpochFaults(), computer.latchedEpochFaults(),
					"Server restart must preserve fault state");
				helper.assertTrue(ItemStack.matches(residentStack(replacement, level),
					residentStack(computer, level)), "Server restart must preserve exact resident data");
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
		for (int x = 0; x < sizeX; x++)
			for (int y = 0; y < sizeY; y++)
				for (int z = 0; z < sizeZ; z++) {
					boolean shell = x == 0 || x == sizeX - 1 || y == 0 || y == sizeY - 1
						|| z == 0 || z == sizeZ - 1;
					level.setBlock(min.offset(x, y, z), shell
						? CBBlocks.COMPUTER_CASING.get().defaultBlockState()
						: Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
	}

	private static ComputerBlockEntity computer(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof ComputerBlockEntity computer))
			throw new IllegalStateException("Missing Computer at " + pos);
		return computer;
	}

	private static CapturedResident capturedResident(ServerLevel level, NodeKind kind,
		int residentLevel) {
		LivingEntity entity;
		if (kind == NodeKind.WANDERING_TRADER) {
			WanderingTrader trader = Objects.requireNonNull(EntityType.WANDERING_TRADER.create(level));
			trader.setDespawnDelay(4321);
			trader.getOffers().add(knownOffer());
			entity = trader;
		} else if (kind == NodeKind.ZOMBIE_VILLAGER) {
			entity = Objects.requireNonNull(EntityType.ZOMBIE_VILLAGER.create(level));
		} else {
			Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
			VillagerProfession profession = kind == NodeKind.LIBRARIAN
				? VillagerProfession.LIBRARIAN : kind == NodeKind.NITWIT
					? VillagerProfession.NITWIT : VillagerProfession.FARMER;
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
		level.setBlock(pos, CBBlocks.COMPUTER.get().defaultBlockState(), Block.UPDATE_ALL);
		ComputerBlockEntity computer = computer(level, pos);
		CompoundTag tag = serverTag(computer, level);
		tag.getCompound("ComputerData").putUUID("ComputerId", id);
		computer.loadWithComponents(tag, level.registryAccess());
		return computer;
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

	private static int traderRangeBonus() {
		return CBConfigs.SERVER.factoryCluster.wanderingTraderRangeBonus.get();
	}

	private static ComputerProfile exactExpectedProfile(ProfileFixture fixture) {
		return switch (fixture.kind()) {
			case VILLAGER -> {
				int[][] rows = {{1, 1}, {2, 1}, {3, 2}, {4, 2}, {4, 2}};
				int[] row = rows[fixture.level() - 1];
				yield new ComputerProfile(NodeKind.VILLAGER, row[0], row[1], false, 0);
			}
			case LIBRARIAN -> {
				int[][] rows = {{4, 2}, {6, 2}, {8, 3}, {12, 3}, {16, 4}};
				int[] row = rows[fixture.level() - 1];
				yield new ComputerProfile(NodeKind.LIBRARIAN, row[0], row[1], false, 0);
			}
			case NITWIT -> new ComputerProfile(NodeKind.NITWIT, 1, 0, true, 0);
			case WANDERING_TRADER -> new ComputerProfile(NodeKind.WANDERING_TRADER,
				2, 1, false, traderRangeBonus());
			case ZOMBIE_VILLAGER -> new ComputerProfile(NodeKind.ZOMBIE_VILLAGER,
				0, 0, false, 0);
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

	private static void assertBounds(GameTestHelper helper, ComputerStructureSnapshot snapshot,
		BlockPos min, int sizeX, int sizeY, int sizeZ) {
		BoundingBox bounds = snapshot.bounds();
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

	private static ServerPlayer survivalPlayer(GameTestHelper helper, BlockPos absolutePos) {
		MinecraftServer server = helper.getLevel().getServer();
		List<ServerPlayer> before = List.copyOf(server.getPlayerList().getPlayers());
		ServerPlayer player;
		try {
			player = helper.makeMockServerPlayerInLevel();
		} catch (UnsupportedOperationException headlessPayload) {
			player = server.getPlayerList().getPlayers().stream()
				.filter(candidate -> before.stream().noneMatch(existing -> existing == candidate))
				.findFirst().orElseThrow(() -> headlessPayload);
		}
		if (server.getPlayerList().getPlayers().contains(player)) server.getPlayerList().remove(player);
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(absolutePos));
		return player;
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

	private static void placeCore(ServerLevel level, BlockPos lower) {
		BlockState lowerState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
			.setValue(PatternStorageCoreBlock.FACING, Direction.NORTH)
			.setValue(PatternStorageCoreBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(lower, lowerState, Block.UPDATE_ALL);
		level.setBlock(lower.above(), lowerState.setValue(PatternStorageCoreBlock.HALF,
			DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
	}

	private static FactoryPanelBlockEntity panel(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity panel))
			throw new IllegalStateException("Missing factory panel at " + pos);
		return panel;
	}

	private static PatternStorageCoreBlockEntity core(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof PatternStorageCoreBlockEntity core))
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
		UseOnContext panelContext = new UseOnContext(player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(panelPos), Direction.UP, panelPos, false));
		InteractionResult selected = ((FactoryPanelBlock) CBBlocks.FACTORY_PANEL.get())
			.onSneakWrenched(level.getBlockState(panelPos), panelContext);
		if (!selected.consumesAction()) throw new IllegalStateException("Panel selection failed");
		UseOnContext casingContext = new UseOnContext(player, InteractionHand.MAIN_HAND,
			new BlockHitResult(Vec3.atCenterOf(casingPos), Direction.UP, casingPos, false));
		return ((ComputerCasingBlock) CBBlocks.COMPUTER_CASING.get())
			.onSneakWrenched(level.getBlockState(casingPos), casingContext);
	}

	private static void collect(Tag tag, Set<String> keys, List<String> strings) {
		if (tag instanceof CompoundTag compound) {
			for (String key : compound.getAllKeys()) {
				keys.add(key);
				collect(Objects.requireNonNull(compound.get(key)), keys, strings);
			}
		} else if (tag instanceof ListTag list) {
			for (Tag child : list) collect(child, keys, strings);
		} else if (tag instanceof net.minecraft.nbt.StringTag string) {
			strings.add(string.getAsString());
		}
	}

	private record CapturedResident(ItemStack box, LivingEntity source, UUID uuid) {}
	private record RejectedInstall(ComputerBlockEntity computer, ItemStack held,
		CompoundTag computerBefore, CompoundTag heldBefore) {}
	private record ProfileFixture(NodeKind kind, int level) {}
	private record InstalledFixture(ProfileFixture fixture, ComputerBlockEntity computer) {}

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
