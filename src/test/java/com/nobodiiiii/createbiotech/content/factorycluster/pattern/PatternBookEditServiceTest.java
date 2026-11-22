package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeBookPages;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternEditData;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.BrowserFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternCreateRequestPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditRequestPacket;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternEditResultPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.network.Filterable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.Level;

class PatternBookEditServiceTest {
	private static SpaceAddress worldAddress(RecipeSourceLocation source) {
		return new SpaceAddress(source.dimension(), source.subLevelId(), source.localPos());
	}

	private static PatternEditData editableOriginal() {
		var record = valid(ORIGINAL);
		return new PatternEditData(record.inputs(), record.outputs(), record.body(), record.priorityOverride());
	}

	private static final PatternPageKey PAGE = new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD, null,
		new BlockPos(2, 3, 4)), 1, 1);
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final String ORIGINAL = """
		{"version":2,"type":"standard","inputs":[],
		 "outputs":[{"item":"minecraft:paper","count":1}],"address":"old"}
		""";

	@BeforeAll static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
		Items.AIR.getDefaultInstance();
	}
	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
	}

	@Test void v2JsonRoundTripPreservesSelectorsComponentsOutputsStagesAndPriority() {
		ItemStack componentOutput = new ItemStack(Items.PAPER);
		componentOutput.set(DataComponents.MAX_STACK_SIZE, 1);
		PatternIngredient item = new PatternIngredient(ResourceLocation.withDefaultNamespace("iron_nugget"),
			null, 2, DataComponentPredicate.builder()
				.expect(DataComponents.CUSTOM_NAME, Component.literal("component-sensitive")).build());
		PatternIngredient tag = new PatternIngredient(null, ItemTags.PLANKS.location(), 3,
			DataComponentPredicate.EMPTY);
		PatternEditData data = new PatternEditData(List.of(item, tag), List.of(
			new PatternOutput(new StackKey(componentOutput), 4),
			new PatternOutput(new StackKey(new ItemStack(Items.DIAMOND)), 2)),
			new SequencedPatternBody(List.of(new PatternStage(0, "cut", List.of(tag)),
				new PatternStage(1, "press", List.of(item)))), OptionalInt.of(17));

		String json = data.toJson(REGISTRIES);
		var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
		var priorTags = registry.getTags().collect(java.util.stream.Collectors.toMap(
			com.mojang.datafixers.util.Pair::getFirst,
			pair -> pair.getSecond().stream().map(holder -> (net.minecraft.core.Holder<net.minecraft.world.item.Item>) holder).toList()));
		var fixtureTags = new java.util.HashMap<>(priorTags);
		fixtureTags.put(ItemTags.PLANKS, List.of(registry.wrapAsHolder(Items.OAK_PLANKS)));
		try {
			registry.bindTags(fixtureTags);
			ParseResult parseResult = new PatternJsonParser(REGISTRIES).parse(PAGE, json);
			PatternRecord parsed = assertInstanceOf(ParseResult.Valid.class, parseResult,
				() -> "serialized JSON rejected: " + json + " -> " + parseResult).pattern();
			assertEquals(data, new PatternEditData(parsed.inputs(), parsed.outputs(), parsed.body(), parsed.priorityOverride()));
			assertEquals(2, parsed.outputs().size());
			assertEquals(1, parsed.outputs().getFirst().stack().stack().get(DataComponents.MAX_STACK_SIZE));
		} finally { registry.bindTags(priorTags); }
	}

	@Test void replacesOnlyTheExactPageOnAClonedWritableBook() {
		ItemStack book = book("before", ORIGINAL, "after");
		PatternEditData edited = editableOriginal();
		var result = RecipeBookPages.replacePage(book, PAGE, ORIGINAL, edited, REGISTRIES);

		assertEquals("success", result.resultKey());
		assertNotSame(book, result.book());
		assertEquals("before", page(result.book(), 0));
		assertEquals("after", page(result.book(), 2));
		assertEquals(ORIGINAL, page(book, 1));
		assertInstanceOf(ParseResult.Valid.class,
			new PatternJsonParser(REGISTRIES).parse(PAGE, page(result.book(), 1)));
	}

	@Test void rejectsChangedPageChangedBookAndInvalidPayloadWithoutMutation() {
		PatternEditData edited = editableOriginal();
		ItemStack changedPage = book("before", ORIGINAL + " ", "after");
		assertEquals("conflict", RecipeBookPages.replacePage(changedPage, PAGE, ORIGINAL, edited,
			REGISTRIES).resultKey());
		assertEquals(ORIGINAL + " ", page(changedPage, 1));

		ItemStack wrongBook = new ItemStack(Items.WRITTEN_BOOK);
		assertEquals("conflict", RecipeBookPages.replacePage(wrongBook, PAGE, ORIGINAL, edited,
			REGISTRIES).resultKey());

		assertEquals("invalid", RecipeBookPages.replacePageJson(book("x", ORIGINAL, "y"), PAGE,
			ORIGINAL, "{", REGISTRIES).resultKey());
	}

	@Test void createsOnBlankPageOfAClonedWritableBookWithoutChangingItsNeighbors() {
		ItemStack book = book("existing", "   ", "later");
		PatternPageKey blankPage = new PatternPageKey(PAGE.shelf(), PAGE.slot(), 1);

		var result = RecipeBookPages.createPageJson(book, blankPage, ORIGINAL, REGISTRIES);

		assertEquals("success", result.resultKey());
		assertNotSame(book, result.book());
		assertEquals("existing", page(result.book(), 0));
		assertEquals(ORIGINAL, page(result.book(), 1));
		assertEquals("later", page(result.book(), 2));
		assertEquals("   ", page(book, 1));
	}

	@Test void appendsToAClonedWritableBookWhenTargetIsItsNextPage() {
		ItemStack book = book("existing");
		PatternPageKey appendedPage = new PatternPageKey(PAGE.shelf(), PAGE.slot(), 1);

		var result = RecipeBookPages.createPageJson(book, appendedPage, ORIGINAL, REGISTRIES);

		assertEquals("success", result.resultKey());
		assertEquals(2, result.book().get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size());
		assertEquals(ORIGINAL, page(result.book(), 1));
		assertEquals(1, book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size());
	}

	@Test void creationRejectsNonblankSignedFullAndInvalidTargetsWithoutMutation() {
		ItemStack nonblank = book("occupied");
		assertEquals("conflict", RecipeBookPages.createPageJson(nonblank,
			new PatternPageKey(PAGE.shelf(), PAGE.slot(), 0), ORIGINAL, REGISTRIES).resultKey());
		assertEquals("occupied", page(nonblank, 0));

		ItemStack signed = new ItemStack(Items.WRITTEN_BOOK);
		assertEquals("conflict", RecipeBookPages.createPageJson(signed,
			new PatternPageKey(PAGE.shelf(), PAGE.slot(), 0), ORIGINAL, REGISTRIES).resultKey());

		ItemStack full = book(java.util.stream.IntStream.range(0, WritableBookContent.MAX_PAGES)
			.mapToObj(index -> "page " + index).toArray(String[]::new));
		assertEquals("no_capacity", RecipeBookPages.createPageJson(full,
			new PatternPageKey(PAGE.shelf(), PAGE.slot(), WritableBookContent.MAX_PAGES - 1), ORIGINAL, REGISTRIES).resultKey());
		assertEquals(WritableBookContent.MAX_PAGES,
			full.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().size());

		ItemStack blank = book("");
		assertEquals("invalid", RecipeBookPages.createPageJson(blank,
			new PatternPageKey(PAGE.shelf(), PAGE.slot(), 0), "{", REGISTRIES).resultKey());
		assertEquals("", page(blank, 0));
	}

	@Test void editPacketsRoundTripSessionFenceAndIntentCorrelation() {
		BrowserFence fence = new BrowserFence(UUID.randomUUID(), PAGE.shelf(), UUID.randomUUID(), 9);
		PatternEditRequestPacket request = new PatternEditRequestPacket(3, fence, 11, UUID.randomUUID(), ORIGINAL);
		var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), REGISTRIES,
			net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
		try {
			request.write(wire);
			assertEquals(request, new PatternEditRequestPacket(wire));
		} finally { wire.release(); }
		PatternEditResultPacket result = new PatternEditResultPacket(3, fence, 11, "success");
		var resultWire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), REGISTRIES,
			net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
		try {
			result.write(resultWire);
			assertEquals(result, new PatternEditResultPacket(resultWire));
		} finally { resultWire.release(); }
	}

	@Test void createPacketRoundTripsFenceIntentAndJsonWithoutClientSelectedSource() {
		BrowserFence fence = new BrowserFence(UUID.randomUUID(), PAGE.shelf(), UUID.randomUUID(), 9);
		PatternCreateRequestPacket request = new PatternCreateRequestPacket(3, fence, 12, ORIGINAL);
		var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), REGISTRIES,
			net.neoforged.neoforge.network.connection.ConnectionType.OTHER);
		try {
			request.write(wire);
			assertEquals(request, new PatternCreateRequestPacket(wire));
		} finally { wire.release(); }
		assertEquals(4, PatternCreateRequestPacket.class.getRecordComponents().length,
			"create requests must not expose shelf, slot, page, or arbitrary source coordinates");
	}

	private static PatternRecord valid(String raw) {
		return assertInstanceOf(ParseResult.Valid.class, new PatternJsonParser(REGISTRIES).parse(PAGE, raw)).pattern();
	}

	private static ItemStack book(String... pages) {
		ItemStack stack = new ItemStack(Items.WRITABLE_BOOK);
		stack.set(DataComponents.WRITABLE_BOOK_CONTENT,
			new WritableBookContent(java.util.Arrays.stream(pages).map(Filterable::passThrough).toList()));
		return stack;
	}

	private static String page(ItemStack book, int page) {
		return book.get(DataComponents.WRITABLE_BOOK_CONTENT).pages().get(page).raw();
	}
}
