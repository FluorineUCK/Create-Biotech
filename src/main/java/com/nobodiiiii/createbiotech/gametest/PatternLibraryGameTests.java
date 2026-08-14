package com.nobodiiiii.createbiotech.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.cardboardbox.CardboardBoxEntity;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMember;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberIndex;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.FactoryPanelBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternLibraryScanner;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternQuery;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStorageCoreBlock;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStorageCoreBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternStructureSnapshot;
import com.nobodiiiii.createbiotech.content.factorycluster.pattern.StackKey;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;
import com.simibubi.create.Create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatternLibraryGameTests {
	private static final float CAPTURED_HEALTH = 7.25F;
	private static final int ASSERTION_DELAY = 3;

	private PatternLibraryGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void librarianConversionAndRelease(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(lower, Blocks.LECTERN.defaultBlockState()
			.setValue(LecternBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);

		ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		Villager source = loadedLibrarian(sourceSnapshot, level);
		UUID sourceUuid = source.getUUID();

		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER),
			"GameTest server did not load the Nether");
		FakePlayer collision = new FakePlayer(nether,
			new GameProfile(sourceUuid, "pattern-library-collision"));
		collision.moveTo(0.5, 80, 0.5);
		nether.addNewPlayer(collision);
		try {
			helper.assertTrue(nether.getEntity(sourceUuid) == collision,
				"Cross-level collision fixture must be visible through ServerLevel UUID lookup");
			helper.assertTrue(CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(
				sourceSnapshot, level) == null,
				"UUID-preserving load must refuse a collision in another ServerLevel");
			Entity reseeded = CapturedEntityBoxHelper.createCapturedEntity(sourceSnapshot, level);
			helper.assertTrue(reseeded instanceof Villager,
				"Legacy captured-entity load must still create the librarian after a UUID collision");
			helper.assertFalse(sourceUuid.equals(reseeded.getUUID()),
				"Legacy captured-entity load must reseed a colliding UUID");
			assertHealth(helper, reseeded, CAPTURED_HEALTH,
				"Legacy captured-entity load must restore captured health");
		} finally {
			nether.removePlayerImmediately(collision, Entity.RemovalReason.DISCARDED);
		}

		ServerPlayer player = headlessPlayer(level, lower);
		player.setItemInHand(InteractionHand.MAIN_HAND, sourceSnapshot.copy());
		PlayerInteractEvent.RightClickBlock interaction = new PlayerInteractEvent.RightClickBlock(
			player, InteractionHand.MAIN_HAND, lower,
			new BlockHitResult(Vec3.atCenterOf(lower), Direction.UP, lower, false));
		NeoForge.EVENT_BUS.post(interaction);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			helper.assertTrue(interaction.isCanceled(),
				"A filled adult-librarian box on an empty lectern must be consumed by conversion");
			assertCoreHalves(helper, level, lower);
			PatternStorageCoreBlockEntity core = core(level, lower);
			assertStackEqual(helper, sourceSnapshot, serializedSnapshot(core, level),
				"Conversion must install the exact captured librarian snapshot");
			helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(
				player.getMainHandItem()), "Successful conversion must empty the held box");

			player.gameMode.destroyBlock(lower.above());
			helper.runAfterDelay(ASSERTION_DELAY, () -> {
				List<Villager> librarians = entities(level, Villager.class, around(lower, 5));
				helper.assertValueEqual(librarians.size(), 1,
					"Controlled release must conserve exactly one librarian");
				Villager released = librarians.getFirst();
				helper.assertValueEqual(released.getUUID(), sourceUuid,
					"Controlled release must preserve the captured UUID");
				assertHealth(helper, released, CAPTURED_HEALTH,
					"Controlled release must preserve captured health");
				List<ItemEntity> drops = entities(level, ItemEntity.class, around(lower, 5));
				long worldLecterns = drops.stream().filter(drop -> drop.getItem().is(Items.LECTERN))
					.mapToInt(drop -> drop.getItem().getCount()).sum();
				long inventoryLecterns = countInventoryItem(player, Items.LECTERN);
				helper.assertValueEqual(worldLecterns + inventoryLecterns, 1L,
					"Breaking either half must conserve the lower core's lectern loot exactly once");
				helper.assertValueEqual(drops.stream()
					.filter(drop -> !drop.getItem().is(Items.LECTERN)).count(), 0L,
					"Direct release must not emit a recovery box or unrelated block loot");
				helper.assertValueEqual(entities(level, CardboardBoxEntity.class,
					around(lower, 5)).size(), 0,
					"Direct release must not emit a custom cardboard-box entity");
				helper.assertFalse(isCore(level.getBlockState(lower)),
					"Controlled release must remove the lower half");
				helper.assertFalse(isCore(level.getBlockState(lower.above())),
					"Controlled release must remove the upper half");
				helper.succeed();
			});
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 40)
	public static void blockedUpperHalfRefusesConversion(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(lower, Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(lower.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		ServerPlayer player = headlessPlayer(level, lower);
		player.setItemInHand(InteractionHand.MAIN_HAND, sourceSnapshot.copy());
		PlayerInteractEvent.RightClickBlock interaction = new PlayerInteractEvent.RightClickBlock(
			player, InteractionHand.MAIN_HAND, lower,
			new BlockHitResult(Vec3.atCenterOf(lower), Direction.UP, lower, false));
		NeoForge.EVENT_BUS.post(interaction);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			helper.assertFalse(interaction.isCanceled(),
				"A blocked upper position must leave the right-click unclaimed");
			helper.assertTrue(level.getBlockState(lower).is(Blocks.LECTERN),
				"Blocked conversion must retain the lectern");
			helper.assertTrue(level.getBlockState(lower.above()).is(Blocks.STONE),
				"Blocked conversion must not replace the obstruction");
			assertStackEqual(helper, sourceSnapshot, player.getMainHandItem(),
				"Blocked conversion must preserve the filled held box exactly");
			helper.assertValueEqual(entities(level, Villager.class, around(lower, 4)).size(), 0,
				"Blocked conversion must not spawn the captured librarian");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void sixtyFourMemberBoundary(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos cubeMin = helper.absolutePos(new BlockPos(1, 2, 1));
		BlockPos lower = cubeMin.offset(0, 3, 0);
		for (int x = 0; x < 4; x++)
			for (int y = 0; y < 4; y++)
				for (int z = 0; z < 4; z++) {
					BlockPos member = cubeMin.offset(x, y, z);
					if (!member.equals(lower))
						level.setBlock(member, Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
				}
		placeCore(level, lower, null);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			PatternStorageCoreBlockEntity core = core(level, lower);
			helper.assertValueEqual(core.structureState(), PatternLibraryScanner.StructureState.VALID,
				"A connected 64-member cube must remain within the member limit");
			PatternStructureSnapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"Valid 64-member structure must have a snapshot");
			helper.assertValueEqual(snapshot.members().size(), 64,
				"The upper half must not consume a logical member in the 4x4x4 cube");
			helper.assertValueEqual(snapshot.axisSpanX(), 4, "Inclusive X span must be four");
			helper.assertValueEqual(snapshot.axisSpanY(), 4, "Inclusive Y span must be four");
			helper.assertValueEqual(snapshot.axisSpanZ(), 4, "Inclusive Z span must be four");
			helper.assertValueEqual(snapshot.ordinaryShelves().size(), 63,
				"Every non-core logical member must be conserved as one shelf");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void seventeenPositionAxisIsTooWide(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		assertEmptyTemplateFixture(helper);
		BlockPos lower = helper.absolutePos(new BlockPos(1, 2, 2));
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, null);
		for (int x = 1; x <= 16; x++)
			level.setBlock(lower.offset(x, 0, 0), Blocks.BOOKSHELF.defaultBlockState(),
				Block.UPDATE_ALL);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			PatternStorageCoreBlockEntity core = core(level, lower);
			helper.assertValueEqual(core.structureState(), PatternLibraryScanner.StructureState.TOO_WIDE,
				"Coordinates x=0..16 have inclusive span 17 and must be rejected");
			PatternStructureSnapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"An over-wide scan must retain its diagnostic member snapshot");
			helper.assertValueEqual(snapshot.members().size(), 17,
				"The diagnostic snapshot must conserve the core and all sixteen shelves");
			helper.assertValueEqual(snapshot.axisSpanX(), 17,
				"The diagnostic snapshot must retain the rejected inclusive span");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 40)
	public static void upperSideShelfDoesNotBridge(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, null);
		BlockPos upperSideShelf = lower.above().east();
		level.setBlock(upperSideShelf, Blocks.BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			PatternStorageCoreBlockEntity core = core(level, lower);
			PatternStructureSnapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"The isolated lower core must still produce a valid singleton snapshot");
			helper.assertValueEqual(core.structureState(), PatternLibraryScanner.StructureState.VALID,
				"An upper-side-only shelf must not invalidate the lower-connected structure");
			helper.assertFalse(snapshot.members().contains(upperSideShelf),
				"A shelf adjacent only to the upper half must not bridge into the library");
			helper.assertValueEqual(snapshot.members().size(), 1,
				"The disconnected shelf must not be counted indirectly");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 40)
	public static void secondCoreConflicts(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos first = helper.absolutePos(new BlockPos(2, 2, 2));
		BlockPos second = first.east();
		level.setBlock(first.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(second.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, first, null);
		placeCore(level, second, null);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			PatternStorageCoreBlockEntity core = core(level, first);
			helper.assertValueEqual(core.structureState(),
				PatternLibraryScanner.StructureState.CORE_CONFLICT,
				"A second lower core in the same connected component must conflict");
			PatternStructureSnapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"A conflict scan must retain its diagnostic snapshot");
			helper.assertValueEqual(snapshot.members().size(), 1,
				"The conflicting second core must not be counted as a shelf member");
			helper.assertFalse(snapshot.members().contains(second),
				"The diagnostic snapshot must not admit the conflicting core");
			assertCoreHalves(helper, level, first);
			assertCoreHalves(helper, level, second);
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void blockedControlledBreakKeepsCoreAndSnapshot(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(5, 3, 5));
		ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, sourceSnapshot);
		blockReleaseCandidates(level, lower);
		ServerPlayer player = survivalPlayer(helper, lower);
		player.gameMode.destroyBlock(lower.above());

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			assertCoreHalves(helper, level, lower);
			assertStackEqual(helper, sourceSnapshot, serializedSnapshot(core(level, lower), level),
				"A canceled controlled break must preserve the exact snapshot");
			helper.assertValueEqual(entities(level, Villager.class, around(lower, 7)).size(), 0,
				"A blocked controlled break must not lose the snapshot into a librarian");
			helper.assertValueEqual(entities(level, ItemEntity.class, around(lower, 7)).size(), 0,
				"Break-event cancellation must roll back prepared lower-half loot");
			helper.assertValueEqual(entities(level, CardboardBoxEntity.class,
				around(lower, 7)).size(), 0,
				"Canceled controlled break must not emit a custom cardboard-box entity");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void forcedBlockedRemovalDropsRecoverableSnapshot(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(5, 3, 5));
		ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		Villager source = loadedLibrarian(sourceSnapshot, level);
		UUID sourceUuid = source.getUUID();
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, sourceSnapshot);
		blockReleaseCandidates(level, lower);
		level.setBlock(lower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			List<ItemEntity> items = entities(level, ItemEntity.class, around(lower, 7));
			helper.assertValueEqual(items.size(), 1,
				"Forced blocked removal must emit exactly one recovery-box ItemEntity");
			helper.assertValueEqual(entities(level, CardboardBoxEntity.class,
				around(lower, 7)).size(), 0,
				"Transactional recovery must not also emit a custom dropped-box entity");
			ItemStack recovered = items.getFirst().getItem();
			helper.assertTrue(CapturedEntityBoxHelper.hasCapturedEntity(recovered),
				"Forced blocked removal must keep the captured entity inside the recovery box");
			Entity recoveredLibrarian = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(
				recovered, level);
			helper.assertTrue(recoveredLibrarian instanceof Villager,
				"The recovery box must contain a loadable librarian snapshot");
			helper.assertValueEqual(recoveredLibrarian.getUUID(), sourceUuid,
				"Recovery must preserve the original UUID");
			assertHealth(helper, recoveredLibrarian, CAPTURED_HEALTH,
				"Recovery must preserve captured health");
			helper.assertValueEqual(entities(level, Villager.class, around(lower, 7)).size(), 0,
				"Blocked forced removal must not also spawn a librarian");
			long lecterns = items.stream().filter(item -> item.getItem().is(Items.LECTERN))
				.mapToInt(item -> item.getItem().getCount()).sum();
			helper.assertValueEqual(lecterns, 0L,
				"Forced setBlock replacement must not duplicate controlled-break lectern loot");
			helper.assertFalse(isCore(level.getBlockState(lower.above())),
				"Successful recovery must remove the delegated upper half");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void upperRemovalDelegatesExactlyOnce(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(3, 2, 3));
		ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		UUID sourceUuid = loadedLibrarian(sourceSnapshot, level).getUUID();
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, sourceSnapshot);
		level.setBlock(lower.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			List<Villager> allVillagers = entities(level, Villager.class, around(lower, 6));
			List<ItemEntity> allItems = entities(level, ItemEntity.class, around(lower, 6));
			List<ItemEntity> recovery = allItems.stream()
				.filter(item -> CapturedEntityBoxHelper.hasCapturedEntity(item.getItem())).toList();
			List<CardboardBoxEntity> customBoxes = entities(level, CardboardBoxEntity.class,
				around(lower, 6));
			helper.assertValueEqual(customBoxes.size(), 0,
				"Upper delegation must not leave a custom dropped-box entity");
			helper.assertValueEqual(allVillagers.size() + allItems.size(), 1,
				"Upper removal must delegate one lower transaction with one conserved snapshot");
			if (!allVillagers.isEmpty()) {
				helper.assertValueEqual(allVillagers.size(), 1,
					"Direct release must emit exactly one Villager total");
				helper.assertValueEqual(allItems.size(), 0,
					"Direct release must not emit any ItemEntity");
				helper.assertValueEqual(allVillagers.getFirst().getUUID(), sourceUuid,
					"The only released Villager must retain the original UUID");
				assertHealth(helper, allVillagers.getFirst(), CAPTURED_HEALTH,
					"Delegated direct release must retain captured health");
			} else {
				helper.assertValueEqual(allVillagers.size(), 0,
					"Recovery must not also emit a Villager");
				helper.assertValueEqual(allItems.size(), 1,
					"Recovery must emit exactly one ItemEntity total");
				helper.assertValueEqual(recovery.size(), 1,
					"The only ItemEntity must be the filled recovery box");
				Entity nested = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(
					recovery.getFirst().getItem(), level);
				helper.assertTrue(nested instanceof Villager,
					"Delegated recovery must retain a loadable librarian");
				helper.assertValueEqual(nested.getUUID(), sourceUuid,
					"Delegated recovery must retain the source UUID");
				assertHealth(helper, nested, CAPTURED_HEALTH,
					"Delegated recovery must retain captured health");
			}
			helper.assertFalse(isCore(level.getBlockState(lower)),
				"Upper removal must complete the lower transaction");
			helper.assertFalse(isCore(level.getBlockState(lower.above())),
				"The initiating upper half must remain removed");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void fullMetadataReloadRetainsLibrarianSnapshot(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(3, 2, 3));
		ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, sourceSnapshot);
		PatternStorageCoreBlockEntity original = core(level, lower);
		CompoundTag fullMetadata = original.saveWithFullMetadata(level.registryAccess());
		helper.assertTrue(fullMetadata.contains("id", Tag.TAG_STRING)
			&& fullMetadata.contains("x", Tag.TAG_INT)
			&& fullMetadata.contains("PatternLibrary", Tag.TAG_COMPOUND),
			"Full metadata fixture must contain vanilla identity and owned nested state");

		level.removeBlockEntity(lower);
		BlockEntity loaded = BlockEntity.loadStatic(lower, level.getBlockState(lower),
			fullMetadata, level.registryAccess());
		helper.assertTrue(loaded instanceof PatternStorageCoreBlockEntity,
			"loadStatic must recreate the registered pattern core from full metadata");
		level.setBlockEntity(Objects.requireNonNull(loaded));

		helper.runAfterDelay(1, () -> {
			assertStackEqual(helper, sourceSnapshot, serializedSnapshot(core(level, lower), level),
				"A real block-entity reload must retain the exact librarian snapshot");
			assertCoreHalves(helper, level, lower);
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void sameBlockLowerUpperTransitionsConserveBothLibrarians(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos first = helper.absolutePos(new BlockPos(3, 2, 3));
		BlockPos second = helper.absolutePos(new BlockPos(9, 2, 3));
		ItemStack firstSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
		ItemStack secondSnapshot = capturedLibrarian(level, CAPTURED_HEALTH + 1);
		UUID firstUuid = loadedLibrarian(firstSnapshot, level).getUUID();
		UUID secondUuid = loadedLibrarian(secondSnapshot, level).getUUID();
		for (BlockPos lower : List.of(first, second))
			level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, first, firstSnapshot);
		placeCore(level, second, secondSnapshot);

		BlockState firstLower = level.getBlockState(first);
		BlockState secondUpper = level.getBlockState(second.above());
		level.setBlock(first, firstLower.setValue(PatternStorageCoreBlock.HALF,
			DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		level.setBlock(second.above(), secondUpper.setValue(PatternStorageCoreBlock.HALF,
			DoubleBlockHalf.LOWER), Block.UPDATE_ALL);
		AABB transitionBounds = new AABB(Vec3.atCenterOf(first),
			Vec3.atCenterOf(second.above())).inflate(6);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			List<Villager> released = entities(level, Villager.class, transitionBounds);
			helper.assertValueEqual(released.size(), 2,
				"Lower-to-upper and upper-to-lower replacements must each conserve one librarian");
			helper.assertTrue(released.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet())
				.equals(java.util.Set.of(firstUuid, secondUuid)),
				"Both same-block half transitions must preserve their source UUIDs exactly");
			helper.assertValueEqual(entities(level, ItemEntity.class, transitionBounds).size(), 0,
				"Clear same-block transitions must not emit recovery items");
			helper.assertValueEqual(entities(level, CardboardBoxEntity.class, transitionBounds).size(), 0,
				"Same-block transitions must not emit custom box entities");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 60)
	public static void corruptRawSnapshotSurvivesForcedRemoval(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(3, 2, 3));
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, null);
		PatternStorageCoreBlockEntity core = core(level, lower);
		CompoundTag raw = new CompoundTag();
		raw.putString("id", "create_biotech:deliberately_corrupt_item");
		raw.putByteArray("OpaqueRecoveryBytes", new byte[] { 7, 1, 9, 4 });
		CompoundTag saved = core.saveWithoutMetadata(level.registryAccess());
		saved.getCompound("PatternLibrary").getCompound("ServerState")
			.put("LibrarianSnapshotBox", raw.copy());
		core.loadWithComponents(saved, level.registryAccess());
		helper.assertValueEqual(serializedRawSnapshot(core, level), raw,
			"Corrupt raw snapshot fixture must survive initial server load byte-for-byte");

		level.setBlock(lower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			assertCoreHalves(helper, level, lower);
			helper.assertValueEqual(serializedRawSnapshot(core(level, lower), level), raw,
				"Forced removal must restore corrupt raw snapshot state losslessly");
			helper.assertValueEqual(entities(level, Villager.class, around(lower, 6)).size(), 0,
				"Opaque corrupt state must not be guessed into an entity");
			helper.assertValueEqual(entities(level, ItemEntity.class, around(lower, 6)).size(), 0,
				"Opaque corrupt state must remain recoverable in the restored core");
			helper.assertValueEqual(entities(level, CardboardBoxEntity.class, around(lower, 6)).size(), 0,
				"Opaque corrupt state must not duplicate into a custom box entity");
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 100)
	public static void foundationBindReconcileAndAccessGate(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		BlockPos panelPos = helper.absolutePos(new BlockPos(2, 2, 2));
		BlockPos lower = helper.absolutePos(new BlockPos(4, 2, 2));
		level.setBlock(panelPos, CBBlocks.FACTORY_PANEL.get().defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, null);
		FactoryPanelBlockEntity panel = panel(level, panelPos);
		PatternStorageCoreBlockEntity core = core(level, lower);

		ClusterMemberIndex.register(server, core);
		helper.assertTrue(core.bindingState() == null,
			"Registering an unbound core must leave it unbound");
		helper.assertValueEqual(ClusterMemberIndex.members(server, panel.clusterId(),
			ClusterMemberType.PATTERN_CORE).size(), 0,
			"Registration/reconciliation must not invent an authority for an unbound core");

		UUID logisticsId = UUID.randomUUID();
		UUID clusterId = Objects.requireNonNull(panel.clusterId());
		GlobalPos realLink = GlobalPos.of(level.dimension(), panelPos.above(3));
		Create.LOGISTICS.linkAdded(logisticsId, realLink, null);
		panel.commitClusterBinding(new ClusterBinding(clusterId, 0,
			new ClusterAuthority(ClusterMemberType.PANEL, panel.memberId()),
			List.of(new LogisticsBinding(logisticsId, "test"))));
		ServerPlayer player = headlessPlayer(level, panelPos);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			try {
				helper.assertValueEqual(ClusterBindingService.bind(player, panel, core),
					ClusterBindingService.BindResult.OK,
					"The public bind path must accept the authorized real logistics frequency");
				ClusterBinding first = Objects.requireNonNull(core.bindingState());
				helper.assertValueEqual(first.revision(), 1L,
					"First public transaction must advance revision 0 to revision 1");
				helper.assertValueEqual(first.authority(),
					new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()),
					"Pattern core must outrank the panel as first authority");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, core),
					ClusterBindingService.BindingAccess.READY,
					"The real index must reconcile the bound pattern authority");

				TestCoordinator coordinator = new TestCoordinator(server,
					SpaceAddress.capture(level, lower.east(2)));
				ClusterMemberIndex.register(server, coordinator);
				helper.assertTrue(coordinator.bindingState() == null,
					"Registering an unbound coordinator must remain a no-op");
				helper.assertValueEqual(core.bindingState(), first,
					"Unbound registration must not revise or replace the pattern authority");

				helper.assertValueEqual(ClusterBindingService.bind(player, panel, coordinator),
					ClusterBindingService.BindResult.OK,
					"The second public bind path must merge the real loaded cluster");
				ClusterBinding second = Objects.requireNonNull(core.bindingState());
				helper.assertTrue(second.revision() > first.revision(),
					"Coordinator authority transaction must use a higher revision");
				helper.assertValueEqual(second.authority(),
					new ClusterAuthority(ClusterMemberType.COMPUTER_COORDINATOR,
						coordinator.memberId()),
					"Computer coordinator must outrank the pattern core");
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, core),
					ClusterBindingService.BindingAccess.READY,
					"The core must reconcile as a READY replica");
				assertCommittedIndex(helper, server, clusterId);
				PatternQuery completed = new PatternQuery(UUID.randomUUID(), coordinator.memberId(),
					logisticsId, new StackKey(new ItemStack(Items.DIAMOND)), 0, 0);
				helper.assertTrue(core.enqueueQuery(completed),
					"READY authority must accept a reply-retention fixture");
				core.tick();
				PatternQuery queued = new PatternQuery(UUID.randomUUID(), coordinator.memberId(),
					logisticsId, new StackKey(new ItemStack(Items.GOLD_INGOT)), 23, 0);
				helper.assertTrue(core.enqueueQuery(queued),
					"A READY core must accept the query used by both SLP fixtures");
				CompoundTag invalidState = core.saveWithoutMetadata(level.registryAccess());
				invalidState.getCompound("PatternLibrary").getCompound("ServerState")
					.putString("StructureState", PatternLibraryScanner.StructureState.CORE_CONFLICT.name());
				core.loadWithComponents(invalidState, level.registryAccess());
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, core),
					ClusterBindingService.BindingAccess.READY,
					"Invalid-structure fixture must isolate SLP from an otherwise READY authority");
				CompoundTag invalidIndex = serializedIndex(core, level);
				PatternQuery invalidDenied = new PatternQuery(UUID.randomUUID(), coordinator.memberId(),
					logisticsId, new StackKey(new ItemStack(Items.IRON_INGOT)), 0, 0);
				helper.assertFalse(core.enqueueQuery(invalidDenied),
					"CORE_CONFLICT must reject enqueue despite READY authority");
				helper.assertTrue(core.drainReplies(coordinator.memberId(), 99).isEmpty(),
					"CORE_CONFLICT must retain rather than drain its ready reply");
				core.tick();
				helper.assertValueEqual(serializedIndex(core, level), invalidIndex,
					"CORE_CONFLICT must not advance a queued query or consume a ready reply");
				CompoundTag validState = core.saveWithoutMetadata(level.registryAccess());
				validState.getCompound("PatternLibrary").getCompound("ServerState")
					.putString("StructureState", PatternLibraryScanner.StructureState.VALID.name());
				core.loadWithComponents(validState, level.registryAccess());
				CompoundTag queuedIndex = serializedIndex(core, level);
				helper.assertValueEqual(queuedIndex.getList("Queries", Tag.TAG_COMPOUND).size(), 1,
					"The offline advancement fixture must begin with one queued query");
				helper.assertValueEqual(queuedIndex.getList("Replies", Tag.TAG_COMPOUND).size(), 1,
					"The offline drain fixture must begin with one retained ready reply");

				ClusterMemberIndex.unregister(server, coordinator);
				helper.assertValueEqual(ClusterBindingService.bindingAccess(server, core),
					ClusterBindingService.BindingAccess.AUTHORITY_OFFLINE,
					"Removing the committed coordinator must close the access gate");
				PatternQuery denied = new PatternQuery(UUID.randomUUID(), coordinator.memberId(),
					logisticsId, new StackKey(new ItemStack(Items.IRON_INGOT)), 17, 0);
				helper.assertFalse(core.enqueueQuery(denied),
					"An offline authority must reject enqueue at the server gate");
				helper.assertTrue(core.drainReplies(coordinator.memberId(), 99).isEmpty(),
					"An offline authority must reject draining without stealing its retained reply");
				core.tick();
				helper.assertValueEqual(serializedIndex(core, level), queuedIndex,
					"A public offline tick must not advance the queued query or cursor");
				helper.runAfterDelay(1, () -> {
					try {
						helper.assertValueEqual(serializedIndex(core, level), queuedIndex,
							"A natural offline server tick must leave the queued query and cursor byte-identical");
						helper.succeed();
					} finally {
						Create.LOGISTICS.linkRemoved(logisticsId, realLink);
					}
				});
			} catch (RuntimeException | Error failure) {
				Create.LOGISTICS.linkRemoved(logisticsId, realLink);
				throw failure;
			}
		});
	}

	private static ItemStack capturedLibrarian(ServerLevel level, float health) {
		Villager villager = Objects.requireNonNull(EntityType.VILLAGER.create(level));
		villager.setVillagerData(villager.getVillagerData()
			.setType(VillagerType.PLAINS)
			.setProfession(VillagerProfession.LIBRARIAN)
			.setLevel(3));
		villager.setHealth(health);
		ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
		if (!CapturedEntityBoxHelper.captureEntity(box, villager))
			throw new IllegalStateException("Could not capture GameTest librarian");
		return box;
	}

	private static void assertEmptyTemplateFixture(GameTestHelper helper) {
		try (InputStream stream = PatternLibraryGameTests.class
			.getResourceAsStream("/data/create_biotech/structure/empty.nbt")) {
			helper.assertTrue(stream != null, "create_biotech:empty must be present on the runtime classpath");
			CompoundTag root = NbtIo.readCompressed(Objects.requireNonNull(stream),
				NbtAccounter.unlimitedHeap());
			ListTag size = root.getList("size", Tag.TAG_INT);
			helper.assertValueEqual(size.size(), 3, "Template size must contain three axes");
			helper.assertTrue(size.getInt(0) >= 18,
				"Template X size must contain relative coordinate x=17");
			helper.assertTrue(size.getInt(1) >= 8 && size.getInt(2) >= 8,
				"Template Y/Z size must contain every fixture");
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

	private static CompoundTag serializedIndex(PatternStorageCoreBlockEntity core,
		ServerLevel level) {
		return core.saveWithoutMetadata(level.registryAccess())
			.getCompound("PatternLibrary").getCompound("ServerState")
			.getCompound("PatternIndex").copy();
	}

	private static Villager loadedLibrarian(ItemStack snapshot, ServerLevel level) {
		Entity loaded = CapturedEntityBoxHelper.createCapturedEntityPreservingUuid(snapshot, level);
		if (!(loaded instanceof Villager villager))
			throw new IllegalStateException("Captured GameTest snapshot did not load as a villager");
		return villager;
	}

	private static ServerPlayer survivalPlayer(GameTestHelper helper, BlockPos absolutePos) {
		MinecraftServer server = helper.getLevel().getServer();
		List<ServerPlayer> before = List.copyOf(server.getPlayerList().getPlayers());
		ServerPlayer player;
		try {
			player = helper.makeMockServerPlayerInLevel();
		} catch (UnsupportedOperationException createLoginPayloadOnHeadlessServer) {
			player = server.getPlayerList().getPlayers().stream()
				.filter(candidate -> before.stream().noneMatch(existing -> existing == candidate))
				.findFirst()
				.orElseThrow(() -> createLoginPayloadOnHeadlessServer);
		}
		if (server.getPlayerList().getPlayers().contains(player))
			server.getPlayerList().remove(player);
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(absolutePos));
		return player;
	}

	private static ServerPlayer headlessPlayer(ServerLevel level, BlockPos absolutePos) {
		FakePlayer player = new FakePlayer(level,
			new GameProfile(UUID.randomUUID(), "pattern-library-test"));
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(absolutePos));
		return player;
	}

	private static long countInventoryItem(ServerPlayer player, net.minecraft.world.item.Item item) {
		long count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item))
				count += stack.getCount();
		}
		return count;
	}

	private static void placeCore(ServerLevel level, BlockPos lower,
		@Nullable ItemStack snapshot) {
		BlockState lowerState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
			.setValue(PatternStorageCoreBlock.FACING, Direction.NORTH)
			.setValue(PatternStorageCoreBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(lower, lowerState, Block.UPDATE_ALL);
		level.setBlock(lower.above(), lowerState.setValue(PatternStorageCoreBlock.HALF,
			DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		if (snapshot != null)
			core(level, lower).installLibrarianSnapshot(snapshot);
	}

	private static void blockReleaseCandidates(ServerLevel level, BlockPos lower) {
		for (Direction direction : Direction.Plane.HORIZONTAL)
			level.setBlock(lower.relative(direction), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		for (int x = -2; x <= 2; x++)
			for (int z = -2; z <= 2; z++)
				if (Math.max(Math.abs(x), Math.abs(z)) == 2)
					level.setBlock(lower.offset(x, 0, z), Blocks.STONE.defaultBlockState(),
						Block.UPDATE_ALL);
	}

	private static void assertCoreHalves(GameTestHelper helper, ServerLevel level,
		BlockPos lower) {
		helper.assertTrue(isHalf(level.getBlockState(lower), DoubleBlockHalf.LOWER),
			"Expected lower pattern-storage-core half");
		helper.assertTrue(isHalf(level.getBlockState(lower.above()), DoubleBlockHalf.UPPER),
			"Expected upper pattern-storage-core half");
	}

	private static boolean isCore(BlockState state) {
		return state.getBlock() instanceof PatternStorageCoreBlock;
	}

	private static boolean isHalf(BlockState state, DoubleBlockHalf half) {
		return isCore(state) && state.getValue(PatternStorageCoreBlock.HALF) == half;
	}

	private static PatternStorageCoreBlockEntity core(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof PatternStorageCoreBlockEntity core))
			throw new IllegalStateException("Missing pattern storage core at " + pos);
		return core;
	}

	private static FactoryPanelBlockEntity panel(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof FactoryPanelBlockEntity panel))
			throw new IllegalStateException("Missing factory panel at " + pos);
		return panel;
	}

	private static ItemStack serializedSnapshot(PatternStorageCoreBlockEntity core,
		ServerLevel level) {
		CompoundTag root = core.saveWithoutMetadata(level.registryAccess());
		CompoundTag server = root.getCompound("PatternLibrary").getCompound("ServerState");
		return ItemStack.parseOptional(level.registryAccess(),
			server.getCompound("LibrarianSnapshotBox"));
	}

	private static CompoundTag serializedRawSnapshot(PatternStorageCoreBlockEntity core,
		ServerLevel level) {
		return core.saveWithoutMetadata(level.registryAccess()).getCompound("PatternLibrary")
			.getCompound("ServerState").getCompound("LibrarianSnapshotBox").copy();
	}

	private static void assertStackEqual(GameTestHelper helper, ItemStack expected,
		ItemStack actual, String message) {
		helper.assertTrue(ItemStack.matches(expected, actual), message);
	}

	private static void assertHealth(GameTestHelper helper, Entity entity, float expected,
		String message) {
		helper.assertTrue(entity instanceof Villager villager
			&& Math.abs(villager.getHealth() - expected) < 0.0001F, message);
	}

	private static AABB around(BlockPos center, double radius) {
		return new AABB(center).inflate(radius);
	}

	private static <T extends Entity> List<T> entities(ServerLevel level, Class<T> type,
		AABB bounds) {
		return level.getEntitiesOfClass(type, bounds, entity -> !entity.isRemoved());
	}

	private static void assertCommittedIndex(GameTestHelper helper, MinecraftServer server,
		UUID clusterId) {
		helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
			ClusterMemberType.PANEL).size(), 1,
			"Committed cluster key must contain exactly one panel");
		helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
			ClusterMemberType.PATTERN_CORE).size(), 1,
			"Committed cluster key must contain exactly one pattern core");
		helper.assertValueEqual(ClusterMemberIndex.members(server, clusterId,
			ClusterMemberType.COMPUTER_COORDINATOR).size(), 1,
			"Committed cluster key must contain exactly one coordinator");
	}

	private static final class TestCoordinator implements ClusterMember {
		private final MinecraftServer server;
		private final UUID id = UUID.randomUUID();
		private final SpaceAddress address;
		@Nullable private ClusterBinding binding;

		private TestCoordinator(MinecraftServer server, SpaceAddress address) {
			this.server = server;
			this.address = address;
		}

		@Override public UUID memberId() { return id; }
		@Override public @Nullable ClusterBinding bindingState() { return binding; }
		@Override public ClusterMemberType memberType() {
			return ClusterMemberType.COMPUTER_COORDINATOR;
		}
		@Override public SpaceAddress memberAddress() { return address; }
		@Override public boolean canRebind() { return true; }

		@Override
		public ClusterBindingPreparation prepareClusterBinding(ClusterBinding proposed) {
			if (proposed == null || proposed.authority() == null)
				return ClusterBindingPreparation.IDENTITY;
			if (binding != null && (proposed.revision() < binding.revision()
				|| proposed.revision() == binding.revision() && !proposed.equals(binding)))
				return ClusterBindingPreparation.REVISION;
			return ClusterBindingPreparation.READY;
		}

		@Override
		public void commitClusterBinding(ClusterBinding prepared) {
			ClusterMemberIndex.rebind(server, this, () -> binding = prepared);
		}
	}
}
