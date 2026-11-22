package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.browser;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipeBrowserTest {
    private static final RecipeSourceLocation LOCATION = new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO);
    private static final BrowserFence FENCE = new BrowserFence(new UUID(1, 0), LOCATION, new UUID(2, 0), 7);
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        var loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true); Items.AIR.getDefaultInstance();
    }
    @Test void pagesTheCurrentAuthoritativeSnapshotAndLimitsDetailToVisibleRecords() {
        var facts = snapshot(7, false, 40); var reads = new AtomicInteger();
        var current = new AtomicReference<>(facts);
        var service = new PatternBrowserService(FENCE, () -> { reads.incrementAndGet(); return current.get(); });
        var page = service.page(FENCE, PatternBrowserFilter.EMPTY, 1, Optional.of(new UUID(0, 35)));
        assertEquals(PatternBrowserPage.Status.OK, page.status());
        assertEquals(40, page.totalMatches()); assertEquals(8, page.entries().size());
        assertSame(facts.records().get(35), page.detail().orElseThrow().pattern());
        assertEquals(1, reads.get(), "One response captures one completed generation");
        assertTrue(service.page(FENCE, PatternBrowserFilter.EMPTY, 0, Optional.of(new UUID(0, 35))).detail().isEmpty());
        current.set(snapshot(8, true, 1));
        var stale = service.page(FENCE, PatternBrowserFilter.EMPTY, 0);
        assertEquals(PatternBrowserPage.Status.STALE, stale.status()); assertTrue(stale.entries().isEmpty());
        assertEquals(8, stale.fence().generation()); assertTrue(stale.reindexing());
        assertEquals(1, service.page(FENCE.withGeneration(8), PatternBrowserFilter.EMPTY, 0).entries().size());
    }
    @Test void foreignFenceIsDeniedBeforeAnyFactsOrProductionProofAreRead() {
        var service = new PatternBrowserService(FENCE, () -> { throw new AssertionError("Must not read a foreign session"); });
        for (var foreign : List.of(
            new BrowserFence(UUID.randomUUID(), LOCATION, FENCE.libraryId(), 7),
            new BrowserFence(FENCE.sessionId(), LOCATION, UUID.randomUUID(), 7),
            new BrowserFence(FENCE.sessionId(), new RecipeSourceLocation(Level.NETHER, null, BlockPos.ZERO), FENCE.libraryId(), 7),
            new BrowserFence(FENCE.sessionId(), new RecipeSourceLocation(Level.OVERWORLD, UUID.randomUUID(), BlockPos.ZERO), FENCE.libraryId(), 7)))
            assertEquals(PatternBrowserPage.Status.DENIED, service.page(foreign, PatternBrowserFilter.EMPTY, 0).status());
    }

    @Test void priorityReadsAreBoundedToTheVisiblePageAndDoNotChangeFacts() {
        var facts = snapshot(7, false, 40); var proofs = new AtomicInteger(); var group = new UUID(9, 0);
        var service = new PatternBrowserService(FENCE, () -> facts, 4, group, () -> (owner, library, pattern, generation) -> {
            assertEquals(group, owner); assertEquals(FENCE.libraryId(), library); assertEquals(7, generation);
            proofs.incrementAndGet(); return pattern.equals(new UUID(0, 35));
        });
        var page = service.page(FENCE, PatternBrowserFilter.EMPTY, 1);
        assertEquals(8, proofs.get());
        assertEquals(9, page.entries().get(3).effectivePriority()); assertTrue(page.entries().get(3).overrideActive());
        assertEquals(4, page.entries().getFirst().effectivePriority()); assertFalse(page.entries().getFirst().overrideActive());
        assertEquals(snapshot(7, false, 40), facts);
        assertEquals(PatternBrowserPage.Status.INVALID_PAGE, service.page(FENCE, PatternBrowserFilter.EMPTY, -1).status());
        assertEquals(PatternBrowserPage.Status.INVALID_PAGE, service.page(FENCE, PatternBrowserFilter.EMPTY, Integer.MAX_VALUE).status());
        assertEquals(8, proofs.get(), "Invalid pages do not ask for authority proofs");
    }

    private static RecipeIndexSnapshot snapshot(long generation, boolean reindexing, int count) {
        var records = IntStream.range(0, count).mapToObj(i -> new PatternRecord(new UUID(0, i),
            new PatternPageKey(LOCATION, i / 100, i % 100), List.of(),
            List.of(new PatternOutput(new StackKey(Items.PAPER.getDefaultInstance()), 1)),
            new StandardPatternBody("browser"), OptionalInt.of(9))).toList();
        return new RecipeIndexSnapshot(generation, reindexing, records, 0, 0, List.of());
    }
}
