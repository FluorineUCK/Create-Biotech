package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeLibrarySummary;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageError;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternErrorReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternJsonParser;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.ParseResult;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser.PatternBrowserEntry;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeIndexSnapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternBrowserSnapshotTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final PatternPageKey A = page(0);
	private static final PatternPageKey B = page(1);
	private static final PatternPageKey C = page(2);
	private final PatternJsonParser parser = new PatternJsonParser(REGISTRIES);

	@BeforeAll
	static void bootstrap() {
		PatternLibraryIndexTest.bootstrapMinecraftRegistries();
		Items.AIR.getDefaultInstance();
	}

	@Test
	void freshBrowserIsEmptyAndRepeatedReadsDoNotStartIndexWork() {
		LibraryIndexScenario index = new LibraryIndexScenario();
		RecipeIndexSnapshot empty = index.recipeSnapshot();
		assertEquals(0, empty.generation());
		assertFalse(empty.reindexing());
		assertTrue(empty.records().isEmpty());
		assertEquals(0, empty.errorPages());
		assertSame(empty, index.recipeSnapshot());
		assertEquals(0, index.lastFingerprintUnits());
		assertEquals(0, index.cachedPageCount());
	}

	@Test
	void incompleteGenerationKeepsPriorCompleteEntriesAndOnlySwapsOnCompletion() {
		Pages pages = new Pages().put(A, standard("iron_ingot")).put(B, standard("gold_ingot"));
		LibraryIndexScenario index = complete(pages);
		RecipeIndexSnapshot old = index.recipeSnapshot();
		pages.put(C, standard("copper_ingot"));
		index.rebuildPageOrder(List.of(C, B, A));
		index.tick(pages, parser, 1);
		assertTrue(index.recipeSnapshot().reindexing());
		assertEquals(old.generation(), index.recipeSnapshot().generation());
		assertEquals(old.records(), index.recipeSnapshot().records());
		assertEquals(List.of(A, B), sources(old));
		assertSame(old.records(), index.recipeSnapshot().records(),
			"Starting a generation must reuse completed entries, not traverse/project the entire old catalog");
		assertFalse(old.reindexing());
		index.tick(pages, parser, 2);
		assertFalse(index.recipeSnapshot().reindexing());
		assertEquals(index.generation(), index.recipeSnapshot().generation());
		assertEquals(List.of(A, B, C), sources(index.recipeSnapshot()));
		assertEquals(List.of(A, B), sources(old));
	}

	@Test
	void invalidationOnFinalRebuildFingerprintNeverPublishesAbortedGeneration() {
		Pages pages = new Pages().put(A, standard("iron_ingot")).put(B, standard("gold_ingot"));
		LibraryIndexScenario index = complete(pages);
		RecipeIndexSnapshot old = index.recipeSnapshot();
		index.rebuildPageOrder(List.of(A, B));
		index.tick(pages, parser, 1);
		pages.put(B, standard("copper_ingot"));
		index.tick(pages, parser, 1);
		assertFalse(index.indexPassComplete());
		assertEquals(old.generation(), index.recipeSnapshot().generation());
		assertEquals(old.records(), index.recipeSnapshot().records());
		assertTrue(index.recipeSnapshot().reindexing());
		index.tick(pages, parser, 2);
		assertEquals(index.generation(), index.recipeSnapshot().generation());
		assertTrue(index.recipeSnapshot().records().get(1).outputs().getFirst().stack().stack().is(Items.COPPER_INGOT));
		assertTrue(old.records().get(1).outputs().getFirst().stack().stack().is(Items.GOLD_INGOT));
	}

	@Test
	void maintenanceInvalidationKeepsOldCacheAndUnchangedSweepsKeepSnapshotIdentity() {
		Pages pages = new Pages().put(A, standard("iron_ingot")).put(B, standard("gold_ingot"));
		LibraryIndexScenario index = complete(pages);
		RecipeIndexSnapshot old = index.recipeSnapshot();
		index.tick(pages, parser, 2);
		assertSame(old, index.recipeSnapshot());
		pages.put(B, "{");
		index.tick(pages, parser, 2);
		assertTrue(index.recipeSnapshot().reindexing());
		assertEquals(old.records(), index.recipeSnapshot().records());
		assertEquals(0, index.recipeSnapshot().errorPages());
		index.tick(pages, parser, 2);
		assertEquals(List.of(A), sources(index.recipeSnapshot()));
		assertEquals(1, index.recipeSnapshot().errorPages());
	}

	@Test
	void invalidAndUnsupportedPagesContributeOnlyBoundedCompletedErrors() {
		Pages pages = new Pages().put(A, standard("iron_ingot"))
			.put(B, standard("gold_ingot").replace("standard", "future")).put(C, "   ");
		for (int i = 3; i < RecipeLibrarySummary.MAX_ERRORS + 7; i++)
			pages.put(page(i), "{");
		RecipeIndexSnapshot snapshot = complete(pages).recipeSnapshot();
		assertEquals(List.of(A), sources(snapshot));
		assertEquals(RecipeLibrarySummary.MAX_ERRORS + 4, snapshot.invalidPages());
		assertEquals(1, snapshot.unsupportedPages());
		assertEquals(RecipeLibrarySummary.MAX_ERRORS + 5, snapshot.errorPages());
		assertEquals(RecipeLibrarySummary.MAX_ERRORS, snapshot.errors().size());
		assertEquals(B, snapshot.errors().getFirst().source());
		assertEquals(PatternErrorReason.UNSUPPORTED_PATTERN_TYPE, snapshot.errors().getFirst().reason());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.errors().clear());
	}

	@Test
	void emptyRebuildPublishesCompletedEmptyGenerationImmediately() {
		LibraryIndexScenario index = complete(new Pages().put(A, standard("iron_ingot")));
		long oldGeneration = index.recipeSnapshot().generation();
		index.rebuildPageOrder(List.of());
		assertEquals(oldGeneration + 1, index.recipeSnapshot().generation());
		assertFalse(index.recipeSnapshot().reindexing());
		assertTrue(index.recipeSnapshot().records().isEmpty());
	}

	@Test
	void completeCacheRestoresOfflineWithoutReadsOrSchemaChanges() {
		LibraryIndexScenario index = complete(new Pages().put(A, standard("iron_ingot")).put(B, "{"));
		CompoundTag saved = index.save(REGISTRIES);
		LibraryIndexScenario.LoadResult restored = LibraryIndexScenario.load(saved, REGISTRIES);
		assertFalse(restored.hadCorruption());
		assertEquals(List.of(A), sources(restored.index().recipeSnapshot()));
		assertEquals(1, restored.index().recipeSnapshot().errorPages());
		assertEquals(index.recipeSnapshot(), restored.index().recipeSnapshot());
		assertEquals(saved, restored.index().save(REGISTRIES));
		assertEquals(0, restored.index().lastFingerprintUnits());
	}

	@Test
	void unfinishedRestoreDoesNotAdvertisePrefixRecordsAndCompletesThroughTick() {
		Pages pages = new Pages().put(A, standard("iron_ingot")).put(B, standard("gold_ingot"));
		LibraryIndexScenario index = new LibraryIndexScenario();
		index.rebuildPageOrder(pages.keys());
		index.tick(pages, parser, 1);
		CompoundTag saved = index.save(REGISTRIES);
		LibraryIndexScenario.LoadResult restored = LibraryIndexScenario.load(saved, REGISTRIES);
		assertFalse(restored.hadCorruption());
		assertTrue(restored.index().recipeSnapshot().records().isEmpty());
		assertTrue(restored.index().recipeSnapshot().reindexing());
		assertEquals(0, restored.index().recipeSnapshot().generation());
		assertEquals(saved, restored.index().save(REGISTRIES));
		restored.index().tick(pages, parser, 1);
		assertEquals(List.of(A, B), sources(restored.index().recipeSnapshot()));
		assertFalse(restored.index().recipeSnapshot().reindexing());
	}

	@Test
	void repairedCompleteCacheDoesNotPublishSurvivingFragmentsBeforeRestartCompletes() {
		Pages pages = new Pages().put(A, standard("iron_ingot")).put(B, standard("gold_ingot"));
		CompoundTag saved = complete(pages).save(REGISTRIES);
		saved.getList("Cache", Tag.TAG_COMPOUND).getCompound(0).getCompound("Record")
			.putUUID("PatternId", UUID.randomUUID());
		LibraryIndexScenario.LoadResult restored = LibraryIndexScenario.load(saved, REGISTRIES);
		assertTrue(restored.hadCorruption());
		assertTrue(restored.index().recipeSnapshot().records().isEmpty());
		assertTrue(restored.index().recipeSnapshot().reindexing());
		restored.index().tick(pages, parser, 2);
		assertEquals(List.of(A, B), sources(restored.index().recipeSnapshot()));
	}

	@Test
	void entriesPreserveSelectorsStagesComponentsSourceAndStoredPriorityWithoutLiveContext() {
		String raw = """
			{"version":2,"type":"sequenced","inputs":[{"item":"minecraft:iron_ingot","count":2}],
			"outputs":[{"item":"minecraft:paper","count":3,"components":{"minecraft:max_stack_size":1}}],
			"stages":[{"address":"press","inputs":[{"item":"minecraft:paper","count":1,"components":{"minecraft:max_stack_size":1}}]},
			{"address":"depot","inputs":[]}],"priority":7}
			""";
		ParseResult result = parser.parse(A, raw);
		PatternRecord parsed = assertInstanceOf(ParseResult.Valid.class, result, result.toString()).pattern();
		PatternRecord tagged = new PatternRecord(parsed.patternId(), parsed.source(),
			List.of(new PatternIngredient(null, ResourceLocation.parse("c:ingots/iron"), 2,
				net.minecraft.core.component.DataComponentPredicate.EMPTY)),
			parsed.outputs(), parsed.body(), parsed.priorityOverride());
		RecipeIndexSnapshot snapshot = new RecipeIndexSnapshot(1, false,
			List.of(tagged), 0, 0, List.of());
		assertEquals(1, snapshot.records().size());
		PatternBrowserEntry entry = new PatternBrowserEntry(snapshot.records().getFirst());
		assertEquals(PatternKind.SEQUENCED, entry.kind());
		assertEquals(A, entry.source());
		assertEquals(7, entry.storedPriorityOverride().orElseThrow());
		assertNull(entry.inputs().getFirst().itemId());
		assertEquals(ResourceLocation.parse("c:ingots/iron"), entry.inputs().getFirst().tagId());
		assertEquals(2, entry.inputs().getFirst().count());
		assertEquals(List.of("press", "depot"), entry.addresses());
		assertEquals(2, entry.stages().size());
		ItemStack paper = new ItemStack(Items.PAPER);
		paper.set(DataComponents.MAX_STACK_SIZE, 1);
		assertTrue(entry.stages().getFirst().inputs().getFirst().matches(paper));
		paper.set(DataComponents.MAX_STACK_SIZE, 2);
		assertFalse(entry.stages().getFirst().inputs().getFirst().matches(paper));
		ItemStack exposed = entry.outputs().getFirst().stack().stack();
		exposed.set(DataComponents.MAX_STACK_SIZE, 2);
		assertEquals(1, entry.outputs().getFirst().stack().stack().get(DataComponents.MAX_STACK_SIZE));
		assertEquals(3, entry.outputs().getFirst().count());
		assertThrows(UnsupportedOperationException.class, () -> snapshot.records().clear());
		assertThrows(UnsupportedOperationException.class, () -> entry.inputs().clear());
		assertThrows(UnsupportedOperationException.class, () -> entry.outputs().clear());
		assertThrows(UnsupportedOperationException.class, () -> entry.stages().clear());
		assertThrows(UnsupportedOperationException.class, () -> entry.stages().getFirst().inputs().clear());
		assertThrows(UnsupportedOperationException.class, () -> entry.addresses().clear());
	}

	@Test
	void snapshotCopiesCallerCollectionsAndRejectsUnboundedErrorDetails() {
		PatternRecord entry = complete(new Pages().put(A, standard("iron_ingot")))
			.recipeSnapshot().records().getFirst();
		List<PatternRecord> entries = new ArrayList<>(List.of(entry));
		List<PatternPageError> errors = new ArrayList<>();
		RecipeIndexSnapshot snapshot = new RecipeIndexSnapshot(4, false, entries, 0, 0, errors);
		entries.clear();
		assertEquals(List.of(entry), snapshot.records());
		assertEquals(List.of("test"), new PatternBrowserEntry(entry).addresses());
		assertTrue(new PatternBrowserEntry(entry).stages().isEmpty());
		for (int i = 0; i <= RecipeLibrarySummary.MAX_ERRORS; i++)
			errors.add(new PatternPageError(page(i), PatternErrorReason.JSON, "invalid"));
		assertThrows(IllegalArgumentException.class, () ->
			new RecipeIndexSnapshot(4, false, List.of(), errors.size(), 0, errors));
	}

	private LibraryIndexScenario complete(Pages pages) {
		LibraryIndexScenario index = new LibraryIndexScenario();
		index.rebuildPageOrder(pages.keys());
		index.tick(pages, parser, pages.keys().size());
		assertTrue(index.indexPassComplete());
		return index;
	}
	private static List<PatternPageKey> sources(RecipeIndexSnapshot snapshot) {
		return snapshot.records().stream().map(PatternRecord::source).toList();
	}
	private static PatternPageKey page(int x) {
		return new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD, null, new BlockPos(x, 0, 0)), 0, 0);
	}
	private static String standard(String output) {
		return "{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:"
			+ output + "\",\"count\":1}],\"address\":\"test\"}";
	}
	private static final class Pages implements com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipePageIndex.PageReader {
		private final Map<PatternPageKey, String> values = new LinkedHashMap<>();
		Pages put(PatternPageKey key, String raw) { values.put(key, raw); return this; }
		List<PatternPageKey> keys() { return List.copyOf(values.keySet()); }
		@Override public String read(PatternPageKey key) { return values.get(key); }
	}
}
