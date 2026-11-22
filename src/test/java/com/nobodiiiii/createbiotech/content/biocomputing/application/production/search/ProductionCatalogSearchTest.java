package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionCatalogSearchTest {
    @BeforeAll static void bootstrap() throws ReflectiveOperationException {
        Class<?> loading = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (loading.getMethod("get").invoke(null) == null)
            loading.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var flag = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        flag.setAccessible(true); flag.setBoolean(null, true);
        Items.AIR.getDefaultInstance();
    }

    @Test void eachAdvanceExaminesOneItemAndLastMatchStillRequiresSeparateGroupEnd() {
        var query = query();
        var match = pattern("book", 1);
        var catalog = new ProductionRecipeCatalog(query.libraryId(), 3, 7, true,
            List.of(pattern("paper", 0), match));
        var miss = ProductionCatalogSearch.advance(catalog, query);
        assertTrue(miss.reply().isEmpty());
        assertEquals(query.continueAt(1), miss.continuation().orElseThrow());
        var found = ProductionCatalogSearch.advance(catalog, miss.continuation().orElseThrow());
        assertEquals(PatternReply.matchBatch(query, List.of(match), 2), found.reply().orElseThrow());
        assertEquals(query.continueAt(2), found.continuation().orElseThrow());
        var end = ProductionCatalogSearch.advance(catalog, found.continuation().orElseThrow());
        assertEquals(PatternReply.groupEnd(query, 2), end.reply().orElseThrow());
        assertTrue(end.continuation().isEmpty());
        assertEquals(0, query.pageCursor());
        assertEquals(List.of(pattern("paper", 0), match), catalog.patterns());
    }

    static PatternQuery query() {
        return new PatternQuery(id(1), id(2), id(3), id(4), new StackKey(Items.BOOK.getDefaultInstance()),
            7, 0, 2, 3, 11, id(5));
    }

    @Test void unavailableCatalogDoesNotLeakRecordsOrPretendToFinishSearch() {
        var query = query().continueAt(1);
        for (var catalog : List.of(
                new ProductionRecipeCatalog(query.libraryId(), 9, 12, false, List.of(pattern("book", 0))),
                new ProductionRecipeCatalog(id(99), 3, 7, true, List.of(pattern("book", 0))))) {
            var result = ProductionCatalogSearch.advance(catalog, query);
            assertEquals(PatternReply.unavailable(query), result.reply().orElseThrow());
            assertTrue(result.continuation().isEmpty());
        }
    }

    @Test void eitherCatalogOrFormationChangeRestartsWithoutChangingRequestIdentity() {
        var query = query().continueAt(1);
        for (var catalog : List.of(
                new ProductionRecipeCatalog(query.libraryId(), 4, 7, true, List.of()),
                new ProductionRecipeCatalog(query.libraryId(), 3, 8, true, List.of()),
                new ProductionRecipeCatalog(query.libraryId(), 4, 8, true, List.of()))) {
            var result = ProductionCatalogSearch.advance(catalog, query);
            assertEquals(new PatternReply(query.queryId(), query.requesterComputerId(), query.logisticsId(),
                query.libraryId(), catalog.catalogGeneration(), PatternReply.Status.RESTART_REQUIRED, List.of(),
                0, catalog.formationGeneration(), query.searchGeneration(), query.attemptId()), result.reply().orElseThrow());
            assertTrue(result.continuation().isEmpty());
        }
    }

    @Test void emptyAndExhaustedCatalogsEndWithoutOverflowOrAnExtraContinuation() {
        var query = query();
        for (int cursor : List.of(0, 1, Integer.MAX_VALUE)) {
            var result = ProductionCatalogSearch.advance(new ProductionRecipeCatalog(query.libraryId(), 3, 7, true, List.of()), query.continueAt(cursor));
            assertEquals(PatternReply.groupEnd(query, cursor), result.reply().orElseThrow());
            assertTrue(result.continuation().isEmpty());
        }
        var miss = ProductionCatalogSearch.advance(new ProductionRecipeCatalog(query.libraryId(), 3, 7, true,
            List.of(pattern("paper", 0))), query);
        assertEquals(PatternReply.groupEnd(query, 1), miss.reply().orElseThrow());
        assertTrue(miss.continuation().isEmpty());
    }

    @Test void searchUsesExactComponentsAndNeverMutatesItsFrozenCatalog() {
        var query = query();
        var base = pattern("book", 0);
        var namedStack = Items.BOOK.getDefaultInstance(); namedStack.set(DataComponents.CUSTOM_NAME, Component.literal("different"));
        var named = new PatternRecord(base.patternId(), base.source(), base.inputs(),
            List.of(new PatternOutput(new StackKey(namedStack), 1)), base.body(), base.priorityOverride());
        var mutable = new ArrayList<>(List.of(named, base));
        var catalog = new ProductionRecipeCatalog(query.libraryId(), 3, 7, true, mutable);
        mutable.clear(); namedStack.setCount(17);
        catalog.patterns().getFirst().mainOutput().stack().stack().remove(DataComponents.CUSTOM_NAME);
        assertThrows(UnsupportedOperationException.class, () -> catalog.patterns().clear());
        var miss = ProductionCatalogSearch.advance(catalog, query);
        assertTrue(miss.reply().isEmpty());
        var found = ProductionCatalogSearch.advance(catalog, miss.continuation().orElseThrow()).reply().orElseThrow();
        assertEquals(List.of(base), found.matches());
        assertThrows(UnsupportedOperationException.class, () -> found.matches().clear());
        assertEquals(List.of(named, base), catalog.patterns());
    }

    @Test void representationBoundsAndQueryBatchLimitStillRejectWholeValues() {
        var query = query(); var match = pattern("book", 0);
        assertThrows(IllegalArgumentException.class, () -> new ProductionRecipeCatalog(query.libraryId(), 3, 7, true,
            Collections.nCopies(ProductionRecipeCatalog.MAX_PATTERNS + 1, match)));
        assertThrows(IllegalArgumentException.class, () -> PatternReply.matchBatch(query, Collections.nCopies(3, match), 1));
        assertThrows(IllegalArgumentException.class, () -> new PatternReply(query.queryId(), query.requesterComputerId(),
            query.logisticsId(), query.libraryId(), 7, PatternReply.Status.MATCH_BATCH,
            Collections.nCopies(PatternQuery.MAX_REPLY_BATCH + 1, match), 1, 3, 11, query.attemptId()));
    }
    static UUID id(long value) { return new UUID(0, value); }
    static PatternRecord pattern(String item, int page) {
        var source = new PatternPageKey(new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 0, page);
        String json = "{\"version\":2,\"type\":\"standard\",\"inputs\":[],\"outputs\":[{\"item\":\"minecraft:"
            + item + "\",\"count\":1}],\"address\":\"press\"}";
        return ((ParseResult.Valid) new PatternJsonParser(RegistryAccess.EMPTY).parse(source, json)).pattern();
    }
}
