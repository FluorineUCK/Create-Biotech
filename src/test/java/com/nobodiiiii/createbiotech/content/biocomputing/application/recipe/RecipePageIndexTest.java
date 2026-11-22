package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class RecipePageIndexTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.EMPTY;
	private static final PatternPageKey A = new PatternPageKey(
		new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 0, 0);
	private static final PatternPageKey B = new PatternPageKey(A.shelf(), 0, 1);
	private static final String RAW = """
		{"version":2,"type":"standard","inputs":[],"outputs":[{"item":"minecraft:paper","count":1}],"address":"press"}
		""";

	@BeforeAll
	static void bootstrap() throws ReflectiveOperationException {
		Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
		if (loading.getMethod("get").invoke(null) == null)
			loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		net.minecraft.SharedConstants.tryDetectVersion();
		var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
		flag.setAccessible(true); flag.setBoolean(null, true);
		Items.AIR.getDefaultInstance();
	}

	@Test
	void recoveryDistinguishesDiscardedEnvelopeFromRepairablePageCache() {
		var index = new RecipePageIndex();
		index.rebuildPageOrder(List.of(A, B));
		index.tick(ignored -> RAW, new PatternJsonParser(REGISTRIES), 2);
		var clean = index.save(REGISTRIES);
		assertEquals(RecipePageIndex.Recovery.UNCHANGED, RecipePageIndex.load(clean, REGISTRIES).recovery());
		var damaged = clean.copy();
		damaged.getList("Cache", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("Fingerprint", "bad");
		var repaired = RecipePageIndex.load(damaged, REGISTRIES);
		assertEquals(RecipePageIndex.Recovery.REINDEXED, repaired.recovery());
		assertTrue(repaired.hadCorruption());
		assertEquals(index.generation() + 1, repaired.index().generation());
		assertEquals(1, repaired.index().cachedPageCount());
		assertTrue(repaired.index().completeRecords().isEmpty());
		assertTrue(repaired.index().snapshot().records().isEmpty());
		repaired.index().tick(ignored -> RAW, new PatternJsonParser(REGISTRIES), 2);
		assertEquals(index.snapshot().records(), repaired.index().snapshot().records());
		var foreign = clean.copy();
		foreign.put("Queries", new net.minecraft.nbt.ListTag());
		var discarded = RecipePageIndex.load(foreign, REGISTRIES);
		assertEquals(RecipePageIndex.Recovery.DISCARDED, discarded.recovery());
		assertTrue(discarded.hadCorruption());
		assertTrue(discarded.index().pageOrder().isEmpty());
		assertEquals(clean, index.save(REGISTRIES), "Failed restore never modifies the live data owner");
	}

	@Test
	void standaloneDataIndexPreservesCompletedFactsWithoutPublishingPartialReplacement() {
		var index = new RecipePageIndex();
		var parser = new PatternJsonParser(REGISTRIES);
		index.rebuildPageOrder(List.of(A));
		index.tick(ignored -> RAW, parser, 1);
		var completed = index.snapshot();
		assertEquals(List.of(A), completed.records().stream().map(PatternRecord::source).toList());
		assertSame(completed, index.snapshot());
		index.rebuildPageOrder(List.of(B, A));
		index.tick(ignored -> RAW, parser, 1);
		assertTrue(index.snapshot().reindexing());
		assertEquals(completed.records(), index.snapshot().records());
		assertTrue(index.completeRecords().isEmpty());
		assertFalse(index.indexPassComplete());
		assertEquals(Set.of("PageOrder", "Cache", "FingerprintCursor", "Generation", "Complete"),
			index.save(REGISTRIES).getAllKeys());
		var loaded = RecipePageIndex.load(index.save(REGISTRIES), REGISTRIES);
		assertFalse(loaded.hadCorruption());
		assertTrue(loaded.index().snapshot().records().isEmpty());
		loaded.index().tick(ignored -> RAW, parser, 1);
		assertEquals(List.of(A, B), loaded.index().completeRecords().stream().map(PatternRecord::source).toList());
		var stackCopy = loaded.index().completeRecords().getFirst().mainOutput().stack().stack();
		stackCopy.setCount(9);
		assertEquals(1, loaded.index().snapshot().records().getFirst().mainOutput().stack().stack().getCount());
		assertThrows(UnsupportedOperationException.class, () -> completed.records().clear());
		assertEquals(List.of(A), completed.records().stream().map(PatternRecord::source).toList());
	}
}
