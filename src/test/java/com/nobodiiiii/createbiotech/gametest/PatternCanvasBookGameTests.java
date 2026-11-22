package com.nobodiiiii.createbiotech.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;
import com.nobodiiiii.createbiotech.content.biocomputing.block.LibraryCoreBlock;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryBlockEntity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternCreateRequestPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.menu.PatternStorageCoreMenu;
import com.nobodiiiii.createbiotech.registry.CBBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PatternCanvasBookGameTests {
	private static final String PATTERN_JSON = """
		{"version":2,"type":"standard","inputs":[],
		 "outputs":[{"item":"minecraft:paper","count":1}],"address":"canvas-test"}
		""";
	private static final String EXISTING_JSON = """
		{"version":2,"type":"standard","inputs":[],
		 "outputs":[{"item":"minecraft:diamond","count":1}],"address":"existing-test"}
		""";

	private PatternCanvasBookGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 180)
	public static void createRequestWritesOnePhysicalBookPageAndReplayCannotDuplicate(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos corePos = helper.absolutePos(new BlockPos(3, 2, 3));
		BlockPos shelfPos = corePos.relative(Direction.EAST);
		level.setBlock(corePos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
		placeCore(level, corePos);
		level.setBlock(shelfPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), Block.UPDATE_ALL);
		ChiseledBookShelfBlockEntity shelf = shelf(level, shelfPos);
		ItemStack source = new ItemStack(Items.WRITABLE_BOOK);
		source.set(DataComponents.WRITABLE_BOOK_CONTENT,
			new WritableBookContent(List.of(Filterable.passThrough(EXISTING_JSON))));
		shelf.setItem(0, source);
		shelf.setChanged();

		FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "pattern-canvas-book"));
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(corePos));

		helper.runAfterDelay(5, () -> {
			helper.runAfterDelay(80, () -> {
			LibraryBlockEntity core = core(level, corePos);
			helper.assertFalse(core.recipeSnapshot().reindexing(),
				"The physical library must be indexed before opening the mutation session");
			PatternStorageCoreMenu menu = new PatternStorageCoreMenu(7, player.getInventory(), core);
			player.containerMenu = menu;
			var fence = menu.openingFence();
			PatternCreateRequestPacket request = new PatternCreateRequestPacket(7, fence, 1, PATTERN_JSON);

			menu.handleCreate(player, request);
			menu.handleCreate(player, request);

			WritableBookContent written = shelf(level, shelfPos).getItem(0)
				.get(DataComponents.WRITABLE_BOOK_CONTENT);
			helper.assertTrue(written != null, "Creation must retain writable-book content");
			helper.assertValueEqual(written.pages().size(), 2,
				"Replaying one mutation intent must not append a second Pattern");
			helper.assertValueEqual(written.pages().getFirst().raw(), EXISTING_JSON,
				"Appending must preserve the existing first page exactly");
			helper.assertValueEqual(written.pages().get(1).raw(), PATTERN_JSON,
				"The selected physical page must contain the submitted Pattern JSON exactly");
			helper.assertValueEqual(core.createPattern(player, fence, PATTERN_JSON), "stale_generation",
				"The old generation must not create another page after publication restarts indexing");
			helper.assertValueEqual(shelf(level, shelfPos).getItem(0)
				.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size(), 2,
				"A stale create request must leave the current physical source untouched");
			helper.runAfterDelay(30, () -> {
				helper.assertFalse(core.recipeSnapshot().reindexing(),
					"Creation must complete its normal catalog reindex");
				helper.assertTrue(core.recipeSnapshot().records().stream().map(com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry::new)
					.anyMatch(entry -> entry.addresses().contains("canvas-test")),
					"The appended Pattern must be present in the republished browser snapshot");
				helper.succeed();
			});
			});
		});
	}

	private static void placeCore(ServerLevel level, BlockPos lower) {
		BlockState state = CBBlocks.PATTERN_STORAGE_CORE.get().defaultBlockState()
			.setValue(LibraryCoreBlock.FACING, Direction.NORTH)
			.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.LOWER);
		level.setBlock(lower, state, Block.UPDATE_ALL);
		level.setBlock(lower.above(), state.setValue(LibraryCoreBlock.HALF, DoubleBlockHalf.UPPER),
			Block.UPDATE_ALL);
		if (!core(level, lower).applyPlacementData(LibraryCoreItemData.configured(
			CommunicationFrequency.of("pattern-canvas-book"), 0, UUID.randomUUID(), 0)))
			throw new IllegalStateException("Could not configure Pattern Core fixture");
	}

	private static LibraryBlockEntity core(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof LibraryBlockEntity core) return core;
		throw new IllegalStateException("Missing Pattern Core at " + pos);
	}

	private static ChiseledBookShelfBlockEntity shelf(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity shelf) return shelf;
		throw new IllegalStateException("Missing chiseled bookshelf at " + pos);
	}
}
