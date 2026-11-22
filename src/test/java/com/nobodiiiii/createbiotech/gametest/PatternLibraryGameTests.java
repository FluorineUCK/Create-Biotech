package com.nobodiiiii.createbiotech.gametest;


import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.cardboardbox.CardboardBoxEntity;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import com.nobodiiiii.createbiotech.CreateBiotech;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatternLibraryGameTests {
	// Captured shape of the pre-directory envelopes, not a dependency on the retired query engine.
	private static void seedRetiredSearch(CompoundTag index, UUID requester, UUID network, long generation, ServerLevel level) {
		var common = new CompoundTag();
		common.putUUID("RequesterComputerId", requester);
		common.putUUID("LogisticsId", network);
		common.putLong("Generation", generation);
		var query = common.copy();
		query.putUUID("QueryId", UUID.randomUUID());
		query.putInt("Cursor", 0);
		query.put("RequestedOutput", com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs
			.saveStackKey(new StackKey(new ItemStack(Items.DIAMOND)), level.registryAccess()));
		var reply = common.copy();
		reply.putUUID("QueryId", UUID.randomUUID());
		reply.putString("Status", "NOT_FOUND");
		legacyList(index, "Queries").add(query);
		legacyList(index, "Replies").add(reply);
	}

	private static final float CAPTURED_HEALTH = 7.25F;
	private static final int ASSERTION_DELAY = 3;

	private PatternLibraryGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty")
	public static void panel_atlas_resource_loads(GameTestHelper helper) throws java.io.IOException {
		try (var stream = PatternLibraryGameTests.class.getResourceAsStream("/assets/create_biotech/textures/gui/factory_panel_monitor.png")) {
			helper.assertTrue(stream != null, "Panel atlas must exist in packaged runtime resources");
			var image = javax.imageio.ImageIO.read(stream);
			helper.assertTrue(image != null && image.getWidth() == 512 && image.getHeight() == 512, "runtime atlas must decode as exact 512 x 512 PNG");
		}
		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void configuredCorePlacementAndDirectLibrarianInstall(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(2, 2, 2));
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		ServerPlayer player = headlessPlayer(level, lower);
		UUID libraryId = UUID.randomUUID();
		LibraryCoreItemData placementData = LibraryCoreItemData.configured(
			CommunicationFrequency.of("gametest-library"), 37, libraryId, 4);
		ItemStack configuredCore = new ItemStack(CBItems.PATTERN_STORAGE_CORE.get());
		LibraryCoreItemData.write(configuredCore, placementData);
		player.setItemInHand(InteractionHand.MAIN_HAND, configuredCore);
		InteractionResult placement = configuredCore.getItem().useOn(new UseOnContext(player,
			InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(lower.below()),
				Direction.UP, lower.below(), false)));
		helper.assertTrue(placement.consumesAction(),
			"The configured Pattern Core item must use its production placement transaction");

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			assertCoreHalves(helper, level, lower);
			LibraryBlockEntity core = core(level, lower);
			helper.assertValueEqual(core.routingConfiguration().frequency(), placementData.frequency(),
				"Placed frequency must be frozen from the configured item");
			helper.assertValueEqual(core.memberId(), libraryId,
				"Placed Library identity must be the configured preserved identity");

			ItemStack sourceSnapshot = capturedLibrarian(level, CAPTURED_HEALTH);
			ItemStack expectedSnapshot = sourceSnapshot.copy();
			player.setItemInHand(InteractionHand.MAIN_HAND, sourceSnapshot);
			InteractionResult installation = player.gameMode.useItemOn(player, level, sourceSnapshot,
				InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(lower), Direction.UP,
					lower, false));
			helper.assertTrue(installation.consumesAction(),
				"A captured adult librarian must install directly on the lower Core");
			assertStackEqual(helper, expectedSnapshot, serializedSnapshot(core, level),
				"Direct installation must preserve the exact librarian snapshot");
			helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(player.getMainHandItem()),
				"Direct installation must clear the held capture box exactly once");
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
			LibraryBlockEntity core = core(level, lower);
			helper.assertValueEqual(core.structureState(), LibraryStructureScanner.State.VALID,
				"A connected 64-member cube must remain within the member limit");
			LibraryStructureScanner.Snapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"Valid 64-member structure must have a snapshot");
			helper.assertValueEqual(snapshot.members().size(), 64,
				"The upper half must not consume a logical member in the 4x4x4 cube");
			helper.assertValueEqual(snapshot.max().getX() - snapshot.min().getX() + 1, 4, "Inclusive X span must be four");
			helper.assertValueEqual(snapshot.max().getY() - snapshot.min().getY() + 1, 4, "Inclusive Y span must be four");
			helper.assertValueEqual(snapshot.max().getZ() - snapshot.min().getZ() + 1, 4, "Inclusive Z span must be four");
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
			LibraryBlockEntity core = core(level, lower);
			helper.assertValueEqual(core.structureState(), LibraryStructureScanner.State.TOO_WIDE,
				"Coordinates x=0..16 have inclusive span 17 and must be rejected");
			LibraryStructureScanner.Snapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"An over-wide scan must retain its diagnostic member snapshot");
			helper.assertValueEqual(snapshot.members().size(), 17,
				"The diagnostic snapshot must conserve the core and all sixteen shelves");
			helper.assertValueEqual(snapshot.max().getX() - snapshot.min().getX() + 1, 17,
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
			LibraryBlockEntity core = core(level, lower);
			LibraryStructureScanner.Snapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
				"The isolated lower core must still produce a valid singleton snapshot");
			helper.assertValueEqual(core.structureState(), LibraryStructureScanner.State.VALID,
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
			LibraryBlockEntity core = core(level, first);
			helper.assertValueEqual(core.structureState(),
				LibraryStructureScanner.State.CORE_CONFLICT,
				"A second lower core in the same connected component must conflict");
			LibraryStructureScanner.Snapshot snapshot = Objects.requireNonNull(core.structureSnapshot(),
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
		// The lower half owns the snapshot and must drive the one forced transaction.
		level.setBlock(lower, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			List<ItemEntity> items = entities(level, ItemEntity.class, around(lower, 7));
			List<ItemEntity> configuredCores = items.stream()
				.filter(item -> item.getItem().is(CBItems.PATTERN_STORAGE_CORE.get())).toList();
			List<ItemEntity> recoveries = items.stream()
				.filter(item -> CapturedEntityBoxHelper.hasCapturedEntity(item.getItem())).toList();
			helper.assertValueEqual(configuredCores.size(), 1,
				"Forced blocked removal must emit exactly one configured Core item");
			helper.assertTrue(LibraryCoreItemData.read(configuredCores.getFirst().getItem())
				.filter(LibraryCoreItemData::configured).isPresent(),
				"The forced Core output must carry its frozen routing and identity");
			helper.assertValueEqual(recoveries.size(), 1,
				"Forced blocked removal must emit exactly one recovery-box ItemEntity");
			helper.assertValueEqual(items.size(), 2,
				"Forced blocked removal must emit only the configured Core and recovery box");
			helper.assertValueEqual(entities(level, CardboardBoxEntity.class,
				around(lower, 7)).size(), 0,
				"Transactional recovery must not also emit a custom dropped-box entity");
			ItemStack recovered = recoveries.getFirst().getItem();
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
			List<ItemEntity> configuredCores = allItems.stream()
				.filter(item -> item.getItem().is(CBItems.PATTERN_STORAGE_CORE.get())).toList();
			List<CardboardBoxEntity> customBoxes = entities(level, CardboardBoxEntity.class,
				around(lower, 6));
			helper.assertValueEqual(customBoxes.size(), 0,
				"Upper delegation must not leave a custom dropped-box entity");
			helper.assertValueEqual(configuredCores.size(), 1,
				"Upper removal must delegate exactly one configured Core output");
			helper.assertTrue(LibraryCoreItemData.read(configuredCores.getFirst().getItem())
				.filter(LibraryCoreItemData::configured).isPresent(),
				"The delegated Core output must carry frozen routing and identity");
			helper.assertValueEqual(allVillagers.size() + recovery.size(), 1,
				"Upper removal must conserve the resident exactly once");
			if (!allVillagers.isEmpty()) {
				helper.assertValueEqual(allVillagers.size(), 1,
					"Direct release must emit exactly one Villager total");
				helper.assertValueEqual(allItems.size(), 1,
					"Direct release must emit only the configured Core ItemEntity");
				helper.assertValueEqual(allVillagers.getFirst().getUUID(), sourceUuid,
					"The only released Villager must retain the original UUID");
				assertHealth(helper, allVillagers.getFirst(), CAPTURED_HEALTH,
					"Delegated direct release must retain captured health");
			} else {
				helper.assertValueEqual(allVillagers.size(), 0,
					"Recovery must not also emit a Villager");
				helper.assertValueEqual(allItems.size(), 2,
					"Recovery must emit the configured Core and one recovery ItemEntity");
				helper.assertValueEqual(recovery.size(), 1,
					"Exactly one ItemEntity must be the filled recovery box");
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
		LibraryBlockEntity original = core(level, lower);
		CompoundTag fullMetadata = original.saveWithFullMetadata(level.registryAccess());
		helper.assertTrue(fullMetadata.contains("id", Tag.TAG_STRING)
			&& fullMetadata.contains("x", Tag.TAG_INT)
			&& fullMetadata.contains("PatternLibrary", Tag.TAG_COMPOUND),
			"Full metadata fixture must contain vanilla identity and owned nested state");

		level.removeBlockEntity(lower);
		BlockEntity loaded = BlockEntity.loadStatic(lower, level.getBlockState(lower),
			fullMetadata, level.registryAccess());
		helper.assertTrue(loaded instanceof LibraryBlockEntity,
			"loadStatic must recreate the registered pattern core from full metadata");
		level.setBlockEntity(Objects.requireNonNull(loaded));

		helper.runAfterDelay(1, () -> {
			assertStackEqual(helper, sourceSnapshot, serializedSnapshot(core(level, lower), level),
				"A real block-entity reload must retain the exact librarian snapshot");
			assertCoreHalves(helper, level, lower);
			helper.succeed();
		});
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 120)
	public static void reloadAndRealConflictRetainArchivedQueriesAndReplies(
		GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(4, 2, 4));
		BlockPos shelfPos = lower.east();
		BlockPos conflicting = lower.west();
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(conflicting.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, null);
		level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
		if (!(level.getBlockEntity(shelfPos) instanceof ChiseledBookShelfBlockEntity shelf))
			throw new IllegalStateException("Chiseled shelf fixture did not create its block entity");
		shelf.setItem(0, new ItemStack(Items.WRITABLE_BOOK));
		shelf.setChanged();

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			LibraryBlockEntity original = core(level, lower);
			helper.assertValueEqual(original.structureState(),
				LibraryStructureScanner.State.VALID,
				"Writable chiseled-shelf fixture must form a valid library");
			UUID requester = UUID.randomUUID(), logisticsId = UUID.randomUUID();
			CompoundTag seeded = original.saveWithoutMetadata(level.registryAccess());
			CompoundTag seededIndex = seeded.getCompound("PatternLibrary")
				.getCompound("ServerState").getCompound("PatternIndex");
			long generation = seededIndex.getLong("Generation");
			seedRetiredSearch(seededIndex, requester, logisticsId, generation, level);
			seeded.getCompound("PatternLibrary").getCompound("ServerState")
				.remove("PageTopologies");
			original.loadWithComponents(seeded, level.registryAccess());
			CompoundTag fullMetadata = original.saveWithFullMetadata(level.registryAccess());
			CompoundTag retainedIndex = serializedIndex(original, level);
			helper.assertValueEqual(retainedIndex.getList("PageOrder", Tag.TAG_COMPOUND).size(),
				100, "One writable slot must persist exactly one hundred logical pages");
			helper.assertValueEqual(retainedIndex.getCompound("RetiredProductionSearch").getList("Queries", Tag.TAG_COMPOUND).size(),
				1, "Reload fixture must contain one active query");
			helper.assertValueEqual(retainedIndex.getCompound("RetiredProductionSearch").getList("Replies", Tag.TAG_COMPOUND).size(),
				1, "Reload fixture must contain one ready reply");

			level.removeBlockEntity(lower);
			BlockEntity loaded = BlockEntity.loadStatic(lower, level.getBlockState(lower),
				fullMetadata, level.registryAccess());
			helper.assertTrue(loaded instanceof LibraryBlockEntity,
				"Full metadata must recreate the registered pattern core");
			level.setBlockEntity(Objects.requireNonNull(loaded));

			helper.runAfterDelay(1, () -> {
				LibraryBlockEntity reloaded = core(level, lower);
				helper.assertTrue(retainsRequests(serializedIndex(reloaded, level), retainedIndex),
					"First natural refresh after reload must not restart queries or drop replies");
				placeCore(level, conflicting, null);

				helper.runAfterDelay(22, () -> {
					LibraryBlockEntity conflicted = core(level, lower);
					helper.assertValueEqual(conflicted.structureState(),
						LibraryStructureScanner.State.CORE_CONFLICT,
						"A real adjacent lower core must enter CORE_CONFLICT");
					helper.assertTrue(retainsRequests(serializedIndex(conflicted, level), retainedIndex),
						"CORE_CONFLICT must retain the archived generation, queries and replies");
					level.removeBlock(conflicting, false);
					level.removeBlock(conflicting.above(), false);

					helper.runAfterDelay(22, () -> {
						LibraryBlockEntity resumed = core(level, lower);
						helper.assertValueEqual(resumed.structureState(),
							LibraryStructureScanner.State.VALID,
							"Removing the conflicting core must restore the same valid topology");
						helper.assertTrue(retainsRequests(serializedIndex(resumed, level), retainedIndex),
							"Returning to the same valid topology must not restart retained work");
						helper.runAfterDelay(1, () -> {
								CompoundTag advanced = serializedIndex(resumed, level);
								helper.assertValueEqual(advanced.getLong("Generation"), generation,
									"Resuming unchanged topology must retain the generation");
								helper.assertValueEqual(advanced.getCompound("RetiredProductionSearch").getList("Queries", Tag.TAG_COMPOUND),
									retainedIndex.getCompound("RetiredProductionSearch").getList("Queries", Tag.TAG_COMPOUND),
									"Incomplete scanning must retain the active query exactly");
								helper.assertValueEqual(advanced.getCompound("RetiredProductionSearch").getList("Replies", Tag.TAG_COMPOUND),
									retainedIndex.getCompound("RetiredProductionSearch").getList("Replies", Tag.TAG_COMPOUND),
									"Resuming scanning must retain the ready reply exactly");
								helper.succeed();
						});
					});
				});
			});
		});
	}

	private static net.minecraft.nbt.ListTag legacyList(CompoundTag index, String key) {
		var list = new net.minecraft.nbt.ListTag();
		index.put(key, list); return list;
	}

	private static boolean retainsRequests(CompoundTag now, CompoundTag prior) {
		return now.getLong("Generation") == prior.getLong("Generation")
			&& now.getCompound("RetiredProductionSearch").getList("Queries", Tag.TAG_COMPOUND).equals(prior.getCompound("RetiredProductionSearch").getList("Queries", Tag.TAG_COMPOUND))
			&& now.getCompound("RetiredProductionSearch").getList("Replies", Tag.TAG_COMPOUND).equals(prior.getCompound("RetiredProductionSearch").getList("Replies", Tag.TAG_COMPOUND));
	}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 80)
	public static void wrongLoadedShelfBlockEntityIsPartialAndRetainsWorkUntilRestored(
		GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos lower = helper.absolutePos(new BlockPos(4, 2, 4));
		BlockPos shelfPos = lower.east();
		level.setBlock(lower.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, lower, null);
		level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
		if (!(level.getBlockEntity(shelfPos) instanceof ChiseledBookShelfBlockEntity shelf))
			throw new IllegalStateException("Chiseled shelf fixture did not create its block entity");
		shelf.setItem(0, new ItemStack(Items.WRITABLE_BOOK));
		shelf.setChanged();

		helper.runAfterDelay(ASSERTION_DELAY, () -> {
			LibraryBlockEntity core = core(level, lower);
			helper.assertValueEqual(core.structureState(),
				LibraryStructureScanner.State.VALID,
				"Shelf-BE retention fixture must begin as a valid library");
			CompoundTag seeded = core.saveWithoutMetadata(level.registryAccess());
			CompoundTag index = seeded.getCompound("PatternLibrary")
				.getCompound("ServerState").getCompound("PatternIndex");
			long generation = index.getLong("Generation");
			UUID requester = UUID.randomUUID();
			UUID logistics = UUID.randomUUID();
			seedRetiredSearch(index, requester, logistics, generation, level);
			core.loadWithComponents(seeded, level.registryAccess());
			CompoundTag retained = serializedIndex(core, level);
			CompoundTag shelfMetadata = shelf.saveWithFullMetadata(level.registryAccess());
			BlockState shelfState = level.getBlockState(shelfPos);

			level.removeBlockEntity(shelfPos);
			level.setBlockEntity(new FurnaceBlockEntity(shelfPos,
				Blocks.FURNACE.defaultBlockState()));

			helper.runAfterDelay(22, () -> {
				LibraryBlockEntity partial = core(level, lower);
				helper.assertValueEqual(partial.structureState(),
					LibraryStructureScanner.State.PARTIAL,
					"Wrong loaded chiseled-shelf BE must be incomplete/SLP, not a smaller topology");
				helper.assertTrue(serializedIndex(partial, level).equals(retained),
					"Incomplete shelf inspection must retain index, query, reply, and cursors");

				level.removeBlockEntity(shelfPos);
				BlockEntity restoredShelf = BlockEntity.loadStatic(shelfPos, shelfState,
					shelfMetadata, level.registryAccess());
				helper.assertTrue(restoredShelf instanceof ChiseledBookShelfBlockEntity,
					"Saved shelf metadata must recreate the correct chiseled shelf BE");
				level.setBlockEntity(Objects.requireNonNull(restoredShelf));

				helper.runAfterDelay(2, () -> {
					LibraryBlockEntity resumed = core(level, lower);
					helper.assertValueEqual(resumed.structureState(),
						LibraryStructureScanner.State.VALID,
						"Restoring the expected loaded shelf BE must resume the same valid topology");
					var after = serializedIndex(resumed, level);
					helper.assertTrue(retainsRequests(after, retained)
						&& Objects.equals(after.get("PageOrder"), retained.get("PageOrder")),
						"Local indexing resumes on the same pages without changing archived work");
					helper.assertTrue(after.getBoolean("Complete")
						|| after.getInt("FingerprintCursor") > retained.getInt("FingerprintCursor"),
						"The repaired physical shelf resumes its local scan without a production coordinator");
					helper.succeed();
				});
			});
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
		level.setBlock(first, firstLower.setValue(LibraryCoreBlock.HALF,
			DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		level.setBlock(second.above(), secondUpper.setValue(LibraryCoreBlock.HALF,
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
			List<ItemEntity> configuredCores = entities(level, ItemEntity.class, transitionBounds);
			helper.assertValueEqual(configuredCores.size(), 2,
				"Each same-block transition must emit exactly one configured Core item");
			helper.assertTrue(configuredCores.stream().allMatch(item ->
				item.getItem().is(CBItems.PATTERN_STORAGE_CORE.get())
					&& LibraryCoreItemData.read(item.getItem())
						.filter(LibraryCoreItemData::configured).isPresent()),
				"Same-block transitions must not replace configured Cores with recovery items");
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
		LibraryBlockEntity core = core(level, lower);
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

	private static CompoundTag serializedIndex(LibraryBlockEntity core,
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

	private static void placeCore(ServerLevel level, BlockPos lower,
		@Nullable ItemStack snapshot) {
		BlockState lowerState = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
			.setValue(LibraryCoreBlock.FACING, Direction.NORTH)
			.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(lower, lowerState, Block.UPDATE_ALL);
		level.setBlock(lower.above(), lowerState.setValue(LibraryCoreBlock.HALF,
			DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		if (!core(level, lower).applyPlacementData(LibraryCoreItemData.configured(
			CommunicationFrequency.of("gametest-library"), 0, UUID.randomUUID(), 0)))
			throw new IllegalStateException("Could not claim configured GameTest Pattern Core");
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
		return state.getBlock() instanceof LibraryCoreBlock;
	}

	private static boolean isHalf(BlockState state, DoubleBlockHalf half) {
		return isCore(state) && state.getValue(LibraryCoreBlock.HALF) == half;
	}

	private static LibraryBlockEntity core(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof LibraryBlockEntity core))
			throw new IllegalStateException("Missing pattern storage core at " + pos);
		return core;
	}


	private static ItemStack serializedSnapshot(LibraryBlockEntity core,
		ServerLevel level) {
		CompoundTag root = core.saveWithoutMetadata(level.registryAccess());
		CompoundTag server = root.getCompound("PatternLibrary").getCompound("ServerState");
		return ItemStack.parseOptional(level.registryAccess(),
			server.getCompound("LibrarianSnapshotBox"));
	}

	private static CompoundTag serializedRawSnapshot(LibraryBlockEntity core,
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

}
