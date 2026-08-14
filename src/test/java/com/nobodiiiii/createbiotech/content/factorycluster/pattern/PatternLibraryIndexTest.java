package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class PatternLibraryIndexTest {
	private static final UUID SPACE = UUID.fromString("00000000-0000-0000-0000-000000000031");
	private static final PatternPageKey PAGE_A = page(0, 0, 0, 0, 0);
	private static final PatternPageKey PAGE_B = page(1, 0, 0, 0, 0);
	private static final PatternPageKey PAGE_C = page(2, 0, 0, 0, 0);
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private final PatternJsonParser parser = new PatternJsonParser(REGISTRIES);

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void oneTickNeverReadsMoreThanBudget() {
		FakePages pages = new FakePages();
		for (int slot = 0; slot < 6; slot++)
			for (int page = 0; page < 100; page++)
				pages.put(page(0, 0, 0, slot, page), validJson(Items.IRON_INGOT));
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.rebuildPageOrder(pages.keys());

		index.tick(pages, parser, 32);

		assertEquals(32, pages.readCount());
		assertEquals(32, index.lastFingerprintUnits());
		assertEquals(32, index.lastTotalUnits());
	}

	@Test
	void defaultMaximumLibraryHasLinearBudgetedRecordMaintenance() {
		int pageCount = 63 * 6 * 100;
		String raw = validJson(Items.IRON_INGOT);
		FakePages pages = new FakePages();
		for (int ordinal = pageCount - 1; ordinal >= 0; ordinal--)
			pages.put(pageForOrdinal(ordinal), raw);
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.rebuildPageOrder(pages.keys());

		long recordMaintenance = 0;
		while (!index.indexPassComplete()) {
			index.tickFingerprintChecks(pages, parser, 600);
			assertTrue(index.lastRecordMaintenanceUnits() <= index.lastFingerprintUnits());
			recordMaintenance += index.lastRecordMaintenanceUnits();
		}

		assertEquals(pageCount, pages.readCount());
		assertEquals(pageCount, recordMaintenance);
		assertEquals(pageCount, index.indexedPatternCount());
	}

	@Test
	void largePersistedLibraryUsesSetMembershipAndOneLinearRecordPassOnLoad() {
		int pageCount = 6_000;
		FakePages pages = new FakePages();
		String raw = validJson(Items.IRON_INGOT);
		for (int ordinal = 0; ordinal < pageCount; ordinal++)
			pages.put(pageForOrdinal(ordinal), raw);
		PatternLibraryIndex source = fullyIndex(pages);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(source.save(REGISTRIES), REGISTRIES);

		assertFalse(loaded.hadCorruption());
		assertEquals(pageCount, loaded.index().lastLoadMembershipChecks());
		assertEquals(pageCount, loaded.index().lastLoadRecordVisits());
		assertEquals(pageCount, loaded.index().indexedPatternCount());
	}

	@Test
	void publicSummaryIsBoundedSortedAndReportsProgressWithoutNbtTraversal() {
		FakePages pages = new FakePages();
		for (int ordinal = 199; ordinal >= 0; ordinal--)
			pages.put(pageForOrdinal(ordinal), "{");
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.rebuildPageOrder(pages.keys());
		index.tickFingerprintChecks(pages, parser, 37);

		PatternLibrarySummary scanning = index.summary(
			PatternLibraryScanner.StructureState.VALID, 600);
		assertEquals(200, scanning.pages());
		assertEquals(600, scanning.capacity());
		assertEquals(37, scanning.indexedPages());
		assertEquals(163, scanning.queuedPages());
		assertEquals(37, scanning.errorPages());
		assertTrue(scanning.scanning());

		index.tickFingerprintChecks(pages, parser, 200);
		PatternLibrarySummary complete = index.summary(
			PatternLibraryScanner.StructureState.VALID, 600);
		assertFalse(complete.scanning());
		assertEquals(200, complete.indexedPages());
		assertEquals(0, complete.indexedPatterns());
		assertEquals(200, complete.errorPages());
		assertEquals(0, complete.queuedPages());
		assertEquals(128, complete.errors().size());
		assertEquals(index.pageOrder().subList(0, 128),
			complete.errors().stream().map(PatternPageError::source).toList());
		assertThrows(UnsupportedOperationException.class, () -> complete.errors().clear());
	}

	@Test
	void pageOrderIsCanonicalImmutableAndDuplicateFree() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.rebuildPageOrder(List.of(PAGE_C, PAGE_A, PAGE_B, PAGE_A));
		assertEquals(List.of(PAGE_A, PAGE_B, PAGE_C), index.pageOrder());
		assertThrows(UnsupportedOperationException.class, () -> index.pageOrder().clear());
	}

	@Test
	void roundRobinPreventsLargeQueryStarvation() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT);
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.enqueue(queryFor(Items.GOLD_INGOT));

		fixture.index.tickQueries(4);

		assertEquals(Items.GOLD_INGOT, fixture.index.pollReplies(1).getFirst()
			.pattern().mainOutput().stack().stack().getItem());
	}

	@Test
	void sharedSchedulerAlternatesEligibleLanesWithinTotalBudget() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT);
		fixture.index.beginNextFingerprintSweep();
		fixture.index.enqueue(queryFor(Items.DIAMOND));

		fixture.index.tick(fixture.pages, parser, 6);

		assertEquals(List.of(WorkLane.FINGERPRINT, WorkLane.QUERY,
			WorkLane.FINGERPRINT, WorkLane.QUERY,
			WorkLane.FINGERPRINT, WorkLane.QUERY),
			fixture.index.lastLaneTrace());
		assertEquals(3, fixture.index.lastFingerprintUnits());
		assertEquals(3, fixture.index.lastQueryUnits());
		assertEquals(6, fixture.index.lastTotalUnits());
	}

	@Test
	void nextLaneCarriesAcrossTicksAndOnlyEligibleLanesConsumeBudget() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.GOLD_INGOT);
		fixture.index.beginNextFingerprintSweep();
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.tick(fixture.pages, parser, 1);
		assertEquals(List.of(WorkLane.FINGERPRINT), fixture.index.lastLaneTrace());
		fixture.index.tick(fixture.pages, parser, 1);
		assertEquals(List.of(WorkLane.QUERY), fixture.index.lastLaneTrace());

		PatternLibraryIndex incomplete = new PatternLibraryIndex();
		incomplete.rebuildPageOrder(fixture.pages.keys());
		incomplete.enqueue(queryFor(Items.DIAMOND));
		incomplete.tick(fixture.pages, parser, 2);
		assertEquals(List.of(WorkLane.FINGERPRINT, WorkLane.FINGERPRINT), incomplete.lastLaneTrace());
	}

	@Test
	void changingOneFingerprintInvalidatesOnlyOnePage() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.pages.change(PAGE_A, validJson(Items.GOLD_INGOT));

		fixture.index.tick(fixture.pages, parser, 2);

		assertEquals(1, fixture.index.reparsedPageCount());
		assertEquals(Set.of(PAGE_A), fixture.index.lastInvalidatedKeys());
		assertTrue(fixture.index.cached(PAGE_B));
	}

	@Test
	void unchangedBlankAndInvalidPagesAreCached() {
		FakePages pages = new FakePages().put(PAGE_A, "   ").put(PAGE_B, "{");
		PatternLibraryIndex index = fullyIndex(pages);
		long generation = index.generation();
		index.tick(pages, parser, 2);
		assertEquals(0, index.reparsedPageCount());
		assertEquals(generation, index.generation());

		pages.change(PAGE_A, validJson(Items.GOLD_INGOT));
		index.tick(pages, parser, 2);
		assertEquals(1, index.reparsedPageCount());
		assertEquals(Set.of(PAGE_A), index.lastInvalidatedKeys());
	}

	@Test
	void missWaitsForCompleteStableGeneration() {
		FakePages pages = new FakePages().put(PAGE_A, validJson(Items.IRON_INGOT))
			.put(PAGE_B, validJson(Items.COPPER_INGOT));
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.rebuildPageOrder(pages.keys());
		index.enqueue(queryFor(Items.DIAMOND));

		index.tick(pages, parser, 2);
		assertTrue(index.pollReplies(1).isEmpty());
		index.tickQueries(2);
		assertEquals(PatternReplyStatus.NOT_FOUND, index.pollReplies(1).getFirst().status());
	}

	@Test
	void emptyStableGenerationEmitsMissInOneQueryUnit() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.enqueue(queryFor(Items.DIAMOND));
		index.tickQueries(1);
		assertEquals(PatternReplyStatus.NOT_FOUND, index.pollReplies(1).getFirst().status());
	}

	@Test
	void lateMatchConsumesOneComparisonPerCursorAdvance() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT, Items.GOLD_INGOT);
		fixture.index.enqueue(queryFor(Items.GOLD_INGOT));
		fixture.index.tickQueries(2);
		assertTrue(fixture.index.pollReplies(1).isEmpty());
		assertEquals(2, fixture.index.activeQueries().getFirst().cursor());
		fixture.index.tickQueries(1);
		assertEquals(new StackKey(new ItemStack(Items.GOLD_INGOT)),
			fixture.index.pollReplies(1).getFirst().pattern().mainOutput().stack());
	}

	@Test
	void rebuildDuringPassRestartsQueryWithoutPrematureMiss() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.index.enqueue(queryFor(Items.GOLD_INGOT));
		fixture.index.tickQueries(1);
		fixture.pages.put(PAGE_C, validJson(Items.GOLD_INGOT));
		fixture.index.rebuildPageOrder(fixture.pages.keys());

		assertEquals(0, fixture.index.activeQueries().getFirst().cursor());
		assertEquals(fixture.index.generation(), fixture.index.activeQueries().getFirst().passGeneration());
		assertFalse(fixture.index.indexPassComplete());
		assertTrue(fixture.index.pollReplies(1).isEmpty());
	}

	@Test
	void structureReplacementRestartsEvenWhenItsPageOrderIsUnchanged() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.tickQueries(1);
		long oldGeneration = fixture.index.generation();

		fixture.index.rebuildPageOrder(fixture.pages.keys());

		assertEquals(oldGeneration + 1, fixture.index.generation());
		assertFalse(fixture.index.indexPassComplete());
		assertEquals(0, fixture.index.activeQueries().getFirst().cursor());
	}

	@Test
	void latePageChangeRestartsFingerprintSweepAtFirstKey() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT, Items.PAPER);
		fixture.index.beginNextFingerprintSweep();
		fixture.index.tickFingerprintChecks(fixture.pages, parser, 2);
		fixture.pages.change(PAGE_C, validJson(Items.GOLD_INGOT));
		long oldGeneration = fixture.index.generation();

		fixture.index.tickFingerprintChecks(fixture.pages, parser, 1);

		assertEquals(oldGeneration + 1, fixture.index.generation());
		assertFalse(fixture.index.indexPassComplete());
		assertEquals(0, fixture.index.fingerprintCursor());
		fixture.index.tickFingerprintChecks(fixture.pages, parser, 1);
		assertEquals(PAGE_A, fixture.pages.lastReadKey());
	}

	@Test
	void twoNonMatchingQueriesAlternateAndBothComplete() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.index.enqueue(queryFor(Items.GOLD_INGOT));
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.tickQueries(4);
		assertEquals(List.of(PatternReplyStatus.NOT_FOUND, PatternReplyStatus.NOT_FOUND),
			fixture.index.pollReplies(2).stream().map(PatternReply::status).toList());
	}

	@Test
	void pollRepliesIsBoundedAndPreservesCompletionOrder() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		PatternQuery first = queryFor(Items.GOLD_INGOT);
		PatternQuery second = queryFor(Items.DIAMOND);
		index.enqueue(first);
		index.enqueue(second);
		index.tickQueries(2);
		assertTrue(index.pollReplies(-1).isEmpty());
		assertEquals(first.queryId(), index.pollReplies(1).getFirst().queryId());
		assertEquals(second.queryId(), index.pollReplies(4).getFirst().queryId());
	}

	@Test
	void repliesRetainBothRoutingIdentitiesAcrossCompletionAndPersistence() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		PatternQuery query = queryFor(Items.GOLD_INGOT);
		index.enqueue(query);
		index.tickQueries(1);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(
			index.save(REGISTRIES), REGISTRIES);
		PatternReply reply = loaded.index().drainReplies(query.requesterComputerId(), 1).getFirst();

		assertEquals(query.queryId(), reply.queryId());
		assertEquals(query.requesterComputerId(), reply.requesterComputerId());
		assertEquals(query.logisticsId(), reply.logisticsId());
		assertEquals(PatternReplyStatus.NOT_FOUND, reply.status());
	}

	@Test
	void requesterDrainCannotStealAnotherRequestersReply() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		PatternQuery first = queryFor(Items.GOLD_INGOT);
		PatternQuery second = queryFor(Items.DIAMOND);
		index.enqueue(first);
		index.enqueue(second);
		index.tickQueries(2);

		assertTrue(index.drainReplies(UUID.randomUUID(), 99).isEmpty());
		assertEquals(second.queryId(), index.drainReplies(second.requesterComputerId(), 99)
			.getFirst().queryId());
		assertEquals(1, index.readyReplyCount());
		assertEquals(first.queryId(), index.drainReplies(first.requesterComputerId(), 99)
			.getFirst().queryId());
	}

	@Test
	void generationChangeDiscardsOnlyStaleReadyRepliesAndRestartsActiveQueries() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.index.enqueue(queryFor(Items.IRON_INGOT));
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.tickQueries(2);
		assertEquals(1, fixture.index.activeQueryCount());
		fixture.pages.change(PAGE_A, validJson(Items.GOLD_INGOT));
		fixture.index.tick(fixture.pages, parser, 1);
		assertTrue(fixture.index.pollReplies(2).isEmpty());
		assertEquals(0, fixture.index.activeQueries().getFirst().cursor());
		assertEquals(fixture.index.generation(), fixture.index.activeQueries().getFirst().passGeneration());
	}

	@Test
	void saveLoadRoundTripsEveryPersistedStateWithoutDiagnostics() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.index.beginNextFingerprintSweep();
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.tick(fixture.pages, parser, 2);
		fixture.index.enqueue(queryFor(Items.IRON_INGOT));
		fixture.index.tickQueries(1);
		CompoundTag saved = fixture.index.save(REGISTRIES);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertFalse(loaded.hadCorruption());
		assertEquals(saved, loaded.index().save(REGISTRIES));
		assertTrue(loaded.index().lastLaneTrace().isEmpty());
		assertTrue(loaded.index().lastInvalidatedKeys().isEmpty());
		assertThrows(UnsupportedOperationException.class, () -> loaded.index().activeQueries().clear());
	}

	@Test
	void zeroInputCachedPatternAndReadyMatchReplyRoundTripExactly() {
		PatternQuery query = queryFor(Items.IRON_INGOT);
		FakePages pages = new FakePages().put(PAGE_A,
			"{\"v\":1,\"in\":[],\"out\":[{\"item\":\"minecraft:iron_ingot\",\"count\":1}],\"to\":\"free\"}");
		PatternLibraryIndex index = fullyIndex(pages);
		index.enqueue(query);
		index.tickQueries(1);
		CompoundTag saved = index.save(REGISTRIES);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertFalse(loaded.hadCorruption());
		assertEquals(saved, loaded.index().save(REGISTRIES));
		assertEquals(1, loaded.index().cachedPageCount());
		assertEquals(0, loaded.index().activeQueryCount());
		PatternReply reply = loaded.index().pollReplies(1).getFirst();
		assertEquals(query.queryId(), reply.queryId());
		assertEquals(PatternReplyStatus.MATCH, reply.status());
		assertTrue(reply.pattern().inputs().isEmpty());
	}

	@Test
	void blankAndInvalidCacheVariantsRoundTripExactly() {
		FakePages pages = new FakePages().put(PAGE_A, "   ").put(PAGE_B, "{");
		PatternLibraryIndex index = fullyIndex(pages);
		CompoundTag saved = index.save(REGISTRIES);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertFalse(loaded.hadCorruption());
		assertEquals(saved, loaded.index().save(REGISTRIES));
		assertEquals(2, loaded.index().cachedPageCount());
		assertTrue(loaded.index().indexPassComplete());
		assertEquals(2, loaded.index().fingerprintCursor());
		loaded.index().enqueue(queryFor(Items.DIAMOND));
		loaded.index().tickQueries(1);
		assertEquals(PatternReplyStatus.NOT_FOUND, loaded.index().pollReplies(1).getFirst().status());
	}

	@Test
	void corruptNestedEntriesAreIsolatedDuringLoad() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		fixture.index.enqueue(queryFor(Items.GOLD_INGOT));
		fixture.index.enqueue(queryFor(Items.DIAMOND));
		fixture.index.tickQueries(2);
		CompoundTag saved = fixture.index.save(REGISTRIES);
		saved.getList("Cache", Tag.TAG_COMPOUND).getCompound(0).putString("Page", "wrong-type");
		saved.getList("Queries", Tag.TAG_COMPOUND).getCompound(0).remove("QueryId");

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertEquals(1, loaded.index().cachedPageCount());
		assertEquals(1, loaded.index().activeQueryCount());
		assertTrue(loaded.hadCorruption());
		assertFalse(loaded.index().indexPassComplete());
		assertEquals(0, loaded.index().fingerprintCursor());
		assertEquals(0, loaded.index().activeQueries().getFirst().cursor());
	}

	@Test
	void malformedReplyChildIsDiscardedWhileValidSiblingSurvives() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		PatternQuery first = queryFor(Items.GOLD_INGOT);
		PatternQuery second = queryFor(Items.DIAMOND);
		index.enqueue(first);
		index.enqueue(second);
		index.tickQueries(2);
		CompoundTag saved = index.save(REGISTRIES);
		saved.getList("Replies", Tag.TAG_COMPOUND).getCompound(0).remove("QueryId");

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertTrue(loaded.hadCorruption());
		assertEquals(0, loaded.index().generation());
		assertTrue(loaded.index().indexPassComplete());
		PatternReply surviving = loaded.index().pollReplies(2).getFirst();
		assertEquals(second.queryId(), surviving.queryId());
		assertEquals(PatternReplyStatus.NOT_FOUND, surviving.status());
		assertTrue(loaded.index().pollReplies(1).isEmpty());
	}

	@Test
	void staleReadyReplyIsDiscardedWhileCurrentSiblingSurvives() {
		PatternLibraryIndex index = new PatternLibraryIndex();
		PatternQuery stale = queryFor(Items.GOLD_INGOT);
		PatternQuery current = queryFor(Items.DIAMOND);
		index.enqueue(stale);
		index.enqueue(current);
		index.tickQueries(2);
		CompoundTag saved = index.save(REGISTRIES);
		saved.getList("Replies", Tag.TAG_COMPOUND).getCompound(0)
			.putLong("Generation", saved.getLong("Generation") - 1);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertTrue(loaded.hadCorruption());
		assertEquals(saved.getLong("Generation"), loaded.index().generation());
		assertTrue(loaded.index().indexPassComplete());
		List<PatternReply> replies = loaded.index().pollReplies(2);
		assertEquals(1, replies.size());
		assertEquals(current.queryId(), replies.getFirst().queryId());
	}

	@Test
	void mismatchedQueryGenerationIsRejectedWithoutRestartingValidSiblings() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		PatternQuery stale = queryFor(Items.GOLD_INGOT);
		PatternQuery current = queryFor(Items.DIAMOND);
		fixture.index.enqueue(stale);
		fixture.index.enqueue(current);
		CompoundTag saved = fixture.index.save(REGISTRIES);
		long generation = saved.getLong("Generation");
		saved.getList("Queries", Tag.TAG_COMPOUND).getCompound(0)
			.putLong("Generation", generation - 1);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertTrue(loaded.hadCorruption());
		assertEquals(generation, loaded.index().generation());
		assertTrue(loaded.index().indexPassComplete());
		assertEquals(2, loaded.index().fingerprintCursor());
		assertEquals(1, loaded.index().activeQueryCount());
		assertEquals(current.queryId(), loaded.index().activeQueries().getFirst().queryId());
		assertEquals(generation, loaded.index().activeQueries().getFirst().passGeneration());
	}

	@Test
	void outOfBoundsQueryCursorIsRejectedWithoutRestartingValidSiblings() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT);
		PatternQuery invalid = queryFor(Items.GOLD_INGOT);
		PatternQuery valid = queryFor(Items.DIAMOND);
		fixture.index.enqueue(invalid);
		fixture.index.enqueue(valid);
		CompoundTag saved = fixture.index.save(REGISTRIES);
		long generation = saved.getLong("Generation");
		saved.getList("Queries", Tag.TAG_COMPOUND).getCompound(0).putInt("Cursor", 3);

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertTrue(loaded.hadCorruption());
		assertEquals(generation, loaded.index().generation());
		assertTrue(loaded.index().indexPassComplete());
		assertEquals(2, loaded.index().fingerprintCursor());
		assertEquals(1, loaded.index().activeQueryCount());
		assertEquals(valid.queryId(), loaded.index().activeQueries().getFirst().queryId());
		assertEquals(0, loaded.index().activeQueries().getFirst().cursor());
	}

	@Test
	void malformedPageOrderChildKeepsValidSiblingsButForcesRebuild() {
		Indexed fixture = indexed(Items.IRON_INGOT, Items.COPPER_INGOT, Items.GOLD_INGOT);
		CompoundTag saved = fixture.index.save(REGISTRIES);
		saved.getList("PageOrder", Tag.TAG_COMPOUND).getCompound(1).putString("Page", "bad");
		long oldGeneration = fixture.index.generation();

		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(saved, REGISTRIES);

		assertTrue(loaded.hadCorruption());
		assertEquals(List.of(PAGE_A, PAGE_C), loaded.index().pageOrder());
		assertEquals(2, loaded.index().cachedPageCount());
		assertEquals(oldGeneration + 1, loaded.index().generation());
		assertFalse(loaded.index().indexPassComplete());
	}

	@Test
	void strictRootInvariantsFallBackToFreshCorruptIndexWithoutThrowing() {
		Indexed fixture = indexed(Items.IRON_INGOT);
		CompoundTag wrongType = fixture.index.save(REGISTRIES);
		wrongType.putString("Generation", "bad");
		PatternLibraryIndex.LoadResult loaded = PatternLibraryIndex.load(wrongType, REGISTRIES);
		assertTrue(loaded.hadCorruption());
		assertTrue(loaded.index().pageOrder().isEmpty());
		assertTrue(loaded.index().indexPassComplete());

		CompoundTag extraRoot = fixture.index.save(REGISTRIES);
		extraRoot.putBoolean("Debug", true);
		assertTrue(PatternLibraryIndex.load(extraRoot, REGISTRIES).hadCorruption());
	}

	@Test
	void matchedReplyOwnsComponentSensitiveImmutableSnapshot() {
		ItemStack source = new ItemStack(Items.PAPER);
		source.set(DataComponents.MAX_STACK_SIZE, 1);
		FakePages pages = new FakePages().put(PAGE_A,
			"{\"v\":1,\"in\":[],\"out\":[{\"item\":\"minecraft:paper\",\"count\":1,"
				+ "\"components\":{\"minecraft:max_stack_size\":1}}],\"to\":\"x\"}");
		PatternLibraryIndex index = fullyIndex(pages);
		index.enqueue(new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			new StackKey(source), 99, 99));
		index.tickQueries(1);
		PatternRecord pattern = index.pollReplies(1).getFirst().pattern();
		assertThrows(UnsupportedOperationException.class, () -> pattern.outputs().clear());
		ItemStack obtained = pattern.mainOutput().stack().stack();
		obtained.set(DataComponents.MAX_STACK_SIZE, 2);
		assertEquals(1, pattern.mainOutput().stack().stack().get(DataComponents.MAX_STACK_SIZE));
	}

	private Indexed indexed(Item... outputs) {
		FakePages pages = new FakePages();
		List<PatternPageKey> keys = List.of(PAGE_A, PAGE_B, PAGE_C);
		for (int i = 0; i < outputs.length; i++)
			pages.put(keys.get(i), validJson(outputs[i]));
		return new Indexed(fullyIndex(pages), pages);
	}

	private PatternLibraryIndex fullyIndex(FakePages pages) {
		PatternLibraryIndex index = new PatternLibraryIndex();
		index.rebuildPageOrder(pages.keys());
		index.tickFingerprintChecks(pages, parser, pages.keys().size());
		assertTrue(index.indexPassComplete());
		return index;
	}

	private static PatternQuery queryFor(Item item) {
		return new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
			new StackKey(new ItemStack(item)), Long.MAX_VALUE, 73);
	}

	private static PatternPageKey page(int x, int y, int z, int slot, int page) {
		return new PatternPageKey(new SpaceAddress(Level.OVERWORLD, SPACE, new BlockPos(x, y, z)), slot, page);
	}

	private static PatternPageKey pageForOrdinal(int ordinal) {
		int shelf = ordinal / 600;
		int withinShelf = ordinal % 600;
		return page(shelf, shelf / 1024, 0, withinShelf / 100, withinShelf % 100);
	}

	private static String validJson(Item output) {
		String id;
		if (output == Items.IRON_INGOT) id = "minecraft:iron_ingot";
		else if (output == Items.GOLD_INGOT) id = "minecraft:gold_ingot";
		else if (output == Items.COPPER_INGOT) id = "minecraft:copper_ingot";
		else if (output == Items.DIAMOND) id = "minecraft:diamond";
		else if (output == Items.PAPER) id = "minecraft:paper";
		else throw new IllegalArgumentException("Unsupported test item");
		return "{\"v\":1,\"in\":[{\"item\":\"minecraft:iron_nugget\",\"count\":1}],"
			+ "\"out\":[{\"item\":\"" + id
			+ "\",\"count\":1}],\"to\":\"test\"}";
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null)
				return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Indexed(PatternLibraryIndex index, FakePages pages) {}

	private static final class FakePages implements PageReader {
		private final Map<PatternPageKey, String> values = new LinkedHashMap<>();
		private final List<PatternPageKey> reads = new ArrayList<>();

		FakePages put(PatternPageKey key, String raw) { values.put(key, raw); return this; }
		void change(PatternPageKey key, String raw) { values.put(key, raw); }
		List<PatternPageKey> keys() { return List.copyOf(values.keySet()); }
		int readCount() { return reads.size(); }
		PatternPageKey lastReadKey() { return reads.getLast(); }
		@Override public String read(PatternPageKey key) {
			reads.add(key);
			return values.getOrDefault(key, "");
		}
	}
}
