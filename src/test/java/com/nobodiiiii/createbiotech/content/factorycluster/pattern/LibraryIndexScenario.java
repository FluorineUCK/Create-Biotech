package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.ProductionLibrarySession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.*;
import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryStructureScanner.State;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** Test composition for historical multi-owner scenarios; no codec, query algorithm or synthetic accounting.
 * Actual host integration is exercised separately by LibraryBlockEntityTest. */
final class LibraryIndexScenario {
    private final RecipePageIndex data;
    private final ProductionLibraryQueries queries;
    LibraryIndexScenario() { this(new RecipePageIndex()); }
    LibraryIndexScenario(RecipePageIndex data) { this(data, new ProductionLibraryQueries(data)); }
    private LibraryIndexScenario(RecipePageIndex data, ProductionLibraryQueries queries) {
        this.data = data; this.queries = queries;
    }
    RecipePageIndex pageData() { return data; }
    RecipeIndexSnapshot recipeSnapshot() { return data.snapshot(); }
    void synchronizeData() { queries.synchronizeData(); }
    void synchronizeMaintenance() { queries.resetDiagnostics(); queries.synchronizeData(); }
    void rebuildPageOrder(Collection<PatternPageKey> keys) { data.rebuildPageOrder(keys); queries.synchronizeData(); }
    void enqueue(PatternQuery query) { queries.enqueue(query); }
    List<PatternReply> pollReplies(int count) { return queries.pollReplies(count); }
    List<PatternReply> drainReplies(UUID requester, int count) { return queries.drainReplies(requester, count); }
    void tick(RecipePageIndex.PageReader pages, PatternJsonParser parser, int units) {
        queries.resetDiagnostics(); data.tick(pages, parser, units); queries.synchronizeData();
    }
    void tickFingerprintChecks(RecipePageIndex.PageReader pages, PatternJsonParser parser, int units) {
        queries.resetDiagnostics(); data.tickFingerprintChecks(pages, parser, units); queries.synchronizeData();
    }
    int advanceSearch(int units) { data.resetDiagnostics(); return queries.advanceSearch(units); }
    void tickQueries(int units) { advanceSearch(units); }
    void beginNextFingerprintSweep() { data.beginNextFingerprintSweep(); }
    ProductionRecipeCatalog catalogSnapshot(UUID library, long generation, boolean loaded) {
        return ProductionLibrarySession.catalog(library, generation, data, loaded);
    }
    CompoundTag save(HolderLookup.Provider registries) { return ProductionLibraryIndexArchive.save(queries, registries); }
    static LoadResult load(CompoundTag root, HolderLookup.Provider registries) {
        var loaded = ProductionLibraryIndexArchive.load(root, registries);
        return new LoadResult(new LibraryIndexScenario(loaded.pageData(), loaded.state()), loaded.hadCorruption());
    }
    record LoadResult(LibraryIndexScenario index, boolean hadCorruption) {}
    List<PatternPageKey> pageOrder() { return data.pageOrder(); }
    long generation() { return data.generation(); }
    boolean indexPassComplete() { return data.indexPassComplete(); }
    int fingerprintCursor() { return data.fingerprintCursor(); }
    boolean cached(PatternPageKey key) { return data.cached(key); }
    int cachedPageCount() { return data.cachedPageCount(); }
    List<PatternQuery> activeQueries() { return queries.activeQueries(); }
    int activeQueryCount() { return queries.activeQueryCount(); }
    int readyReplyCount() { return queries.readyReplyCount(); }
    List<PatternPageError> pageErrors() { return data.pageErrors(); }
    List<UnsupportedPatternEnvelope> unsupportedEnvelopes() { return data.unsupportedEnvelopes(); }
    int indexedPatternCount() { return data.indexedPatternCount(); }
    int lastRecordMaintenanceUnits() { return data.lastRecordMaintenanceUnits(); }
    int lastInvalidationMaintenanceUnits() { return data.lastInvalidationMaintenanceUnits(); }
    int lastGenerationTransitionUnits() { return data.lastGenerationTransitionUnits(); }
    int lastRestartQueryVisits() { return queries.lastRestartTraversalStats().queryVisits(); }
    int lastRestartReplyVisits() { return queries.lastRestartTraversalStats().replyVisits(); }
    ProductionLibraryQueries.RestartTraversalStats lastRestartTraversalStats() { return queries.lastRestartTraversalStats(); }
    int lastLoadMembershipChecks() { return data.lastLoadMembershipChecks(); }
    int lastLoadRecordVisits() { return data.lastLoadRecordVisits(); }
    int lastFingerprintUnits() { return data.lastFingerprintUnits(); }
    Set<PatternPageKey> lastInvalidatedKeys() { return data.lastInvalidatedKeys(); }
    int reparsedPageCount() { return data.reparsedPageCount(); }
    RecipeLibrarySummary summary(State reason, int capacity) { return RecipeLibrarySummary.capture(data, reason, capacity); }
}
