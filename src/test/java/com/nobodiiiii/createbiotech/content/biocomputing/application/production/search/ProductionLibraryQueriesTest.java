package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionLibraryQueriesTest {
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
    void advancesAddressedQueriesByCallerBudgetAndEmitsTerminalReply() {
        var index = completeIndex();
        var queries = new ProductionLibraryQueries(index);
        var query = addressed(index.generation(), UUID.randomUUID());
        queries.enqueue(query);
        assertEquals(0, queries.advanceSearch(0));
        assertEquals(1, queries.advanceSearch(1));
        assertEquals(1, queries.readyReplyCount());
        assertEquals(1, queries.activeQueryCount());
        assertEquals(2, queries.advanceSearch(2));
        var replies = queries.pollReplies(3);
        assertEquals(List.of(PatternReply.Status.MATCH_BATCH, PatternReply.Status.MATCH_BATCH,
            PatternReply.Status.GROUP_END), replies.stream().map(PatternReply::status).toList());
        assertTrue(replies.stream().allMatch(reply -> reply.queryId().equals(query.queryId())));
        assertEquals(0, queries.activeQueryCount());
        assertEquals(0, queries.readyReplyCount());
        assertEquals(2, index.completeRecords().size());
    }

    private static RecipePageIndex completeIndex() {
        var index = new RecipePageIndex();
        index.rebuildPageOrder(List.of(A, B));
        index.tickFingerprintChecks(ignored -> RAW, new PatternJsonParser(REGISTRIES), 2);
        assertTrue(index.indexPassComplete());
        return index;
    }

    @Test
    void savesOnlyQueryStateAndResumesTheNextRequesterWithoutCopyingTheIndex() {
        var index = completeIndex();
        var dataBefore = index.save(REGISTRIES);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var queries = new ProductionLibraryQueries(index);
        queries.enqueue(addressed(index.generation(), a));
        queries.enqueue(addressed(index.generation(), b));
        assertEquals(2, queries.advanceSearch(2));
        assertTrue(queries.drainReplies(b, 1).isEmpty());
        assertEquals(a, queries.drainReplies(a, 1).getFirst().requesterComputerId());
        var saved = queries.save(REGISTRIES);
        assertEquals(Set.of("Queries", "Replies", "ReplyRequesterCursor"), saved.getAllKeys());
        var restored = ProductionLibraryQueries.load(saved, REGISTRIES, index, index.generation(), false);
        assertFalse(restored.hadCorruption());
        var reloaded = restored.queries();
        saved.getList("Replies", net.minecraft.nbt.Tag.TAG_COMPOUND).clear();
        assertTrue(reloaded.drainReplies(a, 8).isEmpty());
        assertEquals(b, reloaded.drainReplies(b, 8).getFirst().requesterComputerId());
        assertEquals(2, reloaded.activeQueryCount());
        assertEquals(dataBefore, index.save(REGISTRIES));
        assertEquals(4, reloaded.advanceSearch(20));
        assertEquals(0, reloaded.activeQueryCount());
    }

    private static PatternQuery addressed(long generation, UUID requester) {
        return new PatternQuery(UUID.randomUUID(), requester, UUID.randomUUID(), UUID.randomUUID(),
            new StackKey(new ItemStack(Items.PAPER)), generation, 0, 1);
    }

    @Test
    void repairedIndexKeepsAddressedIdentityButNeverResurrectsOldReplies() {
        var index = completeIndex();
        var queries = new ProductionLibraryQueries(index);
        var addressed = addressed(index.generation(), UUID.randomUUID());
        queries.enqueue(addressed);
        queries.advanceSearch(1);
        var saved = queries.save(REGISTRIES);
        var damaged = index.save(REGISTRIES);
        damaged.getList("Cache", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("Fingerprint", "broken");
        var repaired = RecipePageIndex.load(damaged, REGISTRIES);
        assertEquals(RecipePageIndex.Recovery.REINDEXED, repaired.recovery());
        var loaded = ProductionLibraryQueries.load(saved, REGISTRIES, repaired.index(), index.generation(), true);
        assertFalse(loaded.hadCorruption(), "Valid query archive survives a separately repaired data index");
        assertEquals(0, loaded.queries().readyReplyCount());
        assertEquals(addressed.queryId(), loaded.queries().activeQueries().getFirst().queryId());
        var reloaded = ProductionLibraryQueries.load(loaded.queries().save(REGISTRIES), REGISTRIES,
            repaired.index(), repaired.index().generation(), false);
        assertFalse(reloaded.hadCorruption());
        assertEquals(0, reloaded.queries().advanceSearch(10));
        repaired.index().tickFingerprintChecks(ignored -> RAW, new PatternJsonParser(REGISTRIES), 2);
        assertEquals(1, reloaded.queries().advanceSearch(10));
        var reply = reloaded.queries().pollReplies(10).getFirst();
        assertEquals(PatternReply.Status.RESTART_REQUIRED, reply.status());
        assertEquals(addressed.queryId(), reply.queryId());
    }

    @Test
    void rejectsMalformedRootsAndIsolatesBadEntriesWithoutMutatingTheSource() {
        var index = completeIndex();
        var queries = new ProductionLibraryQueries(index);
        queries.enqueue(addressed(index.generation(), UUID.randomUUID()));
        queries.advanceSearch(1);
        var saved = queries.save(REGISTRIES);
        var malformed = saved.copy();
        malformed.putString("unexpected", "not a queue field");
        var rejected = ProductionLibraryQueries.load(malformed, REGISTRIES, index, index.generation(), false);
        assertTrue(rejected.hadCorruption());
        assertEquals(0, rejected.queries().activeQueryCount());
        assertEquals(0, rejected.queries().readyReplyCount());
        var damaged = saved.copy();
        damaged.getList("Queries", net.minecraft.nbt.Tag.TAG_COMPOUND).add(new net.minecraft.nbt.CompoundTag());
        damaged.putUUID("ReplyRequesterCursor", UUID.randomUUID());
        var recovered = ProductionLibraryQueries.load(damaged, REGISTRIES, index, index.generation(), false);
        assertTrue(recovered.hadCorruption());
        assertEquals(1, recovered.queries().activeQueryCount());
        assertEquals(1, recovered.queries().readyReplyCount());
        assertEquals(saved, queries.save(REGISTRIES));
        assertEquals(saved, recovered.queries().save(REGISTRIES));
    }

    @Test
    void generationChangeRetiresRepliesWithoutScanningQueriesOrExecutingThem() {
        var index = completeIndex();
        var queries = new ProductionLibraryQueries(index);
        var addressed = addressed(index.generation(), UUID.randomUUID());
        var ordinary = new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new StackKey(new ItemStack(Items.PAPER)), Long.MAX_VALUE, 73);
        queries.enqueue(addressed);
        queries.enqueue(ordinary);
        assertEquals(1, queries.advanceSearch(1));
        assertEquals(1, queries.readyReplyCount());
        index.rebuildPageOrder(List.of(A));
        queries.synchronizeData();
        assertEquals(0, queries.readyReplyCount());
        assertEquals(2, queries.activeQueryCount());
        assertEquals(0, queries.lastRestartTraversalStats().totalVisits());
        assertEquals(index.generation(), queries.activeQueries().getFirst().passGeneration());
        assertEquals(addressed.passGeneration(), queries.activeQueries().getLast().passGeneration());
        assertEquals(0, queries.advanceSearch(20), "Incomplete catalog does not execute historical queries");
        index.tickFingerprintChecks(ignored -> RAW, new PatternJsonParser(REGISTRIES), 1);
        queries.synchronizeData();
        assertEquals(0, queries.readyReplyCount(), "Page maintenance is not a query worker");
        assertEquals(2, queries.advanceSearch(20));
        var replies = queries.pollReplies(20);
        assertEquals(List.of(PatternReply.Status.MATCH_BATCH, PatternReply.Status.RESTART_REQUIRED),
            replies.stream().map(PatternReply::status).toList());
        assertEquals(addressed.queryId(), replies.getLast().queryId());
        assertEquals(index.generation(), replies.getLast().generation());
        assertThrows(UnsupportedOperationException.class, () -> replies.clear());
    }
}
