package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import static org.junit.jupiter.api.Assertions.*;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.ProductionCatalogSearchTest.*;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternDataCodecs;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternItemCodecs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductionSearchPersistenceTest {
    @BeforeAll static void bootstrapRegistry() throws ReflectiveOperationException { bootstrap(); }

    @Test void movedProductionFactsKeepExactHistoricalSchemaIdentities() {
        var query = query();
        var match = pattern("book", 0);
        var cases = new LinkedHashMap<Object, String>();
        String oldPattern = "com.nobodiiiii.createbiotech.content.factorycluster.pattern.";
        cases.put(query, oldPattern + "PatternQuery");
        cases.put(PatternReply.matchBatch(query, List.of(match), 1), oldPattern + "PatternReply");
        cases.put(PatternReply.Status.MATCH_BATCH, oldPattern + "PatternReply$Status");
        cases.put(new ProductionRecipeCatalog(query.libraryId(), 3, 7, true, List.of(match)),
            "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryCatalogSnapshot");
        cases.forEach((value, historicalName) -> {
            var tag = CurrentSchemaStateNbt.save(value, RegistryAccess.EMPTY);
            assertEquals(6, tag.getInt("Version"));
            assertEquals(historicalName, tag.getString("Type"));
            assertEquals(value, CurrentSchemaStateNbt.load(tag, RegistryAccess.EMPTY, value.getClass()).orElseThrow());
            var alias = tag.copy(); alias.putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(alias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
            var nestedAlias = tag.copy(); nestedAlias.getCompound("Value").putString("Type", value.getClass().getName());
            assertTrue(CurrentSchemaStateNbt.load(nestedAlias, RegistryAccess.EMPTY, value.getClass()).isEmpty());
        });
        var unrelated = new ProductionCatalogSearch.Advance(java.util.Optional.empty(), java.util.Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> CurrentSchemaStateNbt.save(unrelated, RegistryAccess.EMPTY));
    }

    @Test void preDirectoryNbtKeepsExactKeysAndOldStatusSpellings() {
        var addressed = query();
        var legacy = new PatternQuery(addressed.queryId(), addressed.requesterComputerId(), addressed.logisticsId(),
            addressed.requestedStackKey(), 7, 1);
        var oldQuery = new CompoundTag();
        oldQuery.putUUID("QueryId", legacy.queryId()); oldQuery.putUUID("RequesterComputerId", legacy.requesterComputerId());
        oldQuery.putUUID("LogisticsId", legacy.logisticsId());
        oldQuery.put("RequestedOutput", PatternItemCodecs.saveStackKey(legacy.requestedStackKey(), RegistryAccess.EMPTY));
        oldQuery.putLong("Generation", 7); oldQuery.putInt("Cursor", 1);
        assertEquals(legacy, PatternSearchCodecs.loadQuery(oldQuery, RegistryAccess.EMPTY).orElseThrow());
        assertEquals(oldQuery, PatternSearchCodecs.saveQuery(legacy, RegistryAccess.EMPTY));
        for (var entry : Map.of(PatternReply.Status.MATCH_BATCH, "MATCH", PatternReply.Status.GROUP_END, "NOT_FOUND",
                PatternReply.Status.UNAVAILABLE, "INVALID_LIBRARY").entrySet()) {
            var match = entry.getKey() == PatternReply.Status.MATCH_BATCH ? pattern("book", 0) : null;
            var reply = new PatternReply(legacy.queryId(), legacy.requesterComputerId(), legacy.logisticsId(), 7, entry.getKey(), match);
            var oldReply = new CompoundTag();
            oldReply.putUUID("QueryId", legacy.queryId()); oldReply.putUUID("RequesterComputerId", legacy.requesterComputerId());
            oldReply.putUUID("LogisticsId", legacy.logisticsId()); oldReply.putLong("Generation", 7); oldReply.putString("Status", entry.getValue());
            if (match != null) oldReply.put("Pattern", PatternDataCodecs.saveRecord(match, RegistryAccess.EMPTY));
            assertEquals(reply, PatternSearchCodecs.loadReply(oldReply, RegistryAccess.EMPTY).orElseThrow());
            assertEquals(oldReply, PatternSearchCodecs.saveReply(reply, RegistryAccess.EMPTY));
        }
    }

    @Test void addressedMessagesKeepAllFencesAndRejectMalformedOrOversizedEnvelopes() {
        var query = query(); var reply = PatternReply.matchBatch(query, List.of(pattern("book", 0)), 1);
        var queryTag = PatternSearchCodecs.saveQuery(query, RegistryAccess.EMPTY);
        var replyTag = PatternSearchCodecs.saveReply(reply, RegistryAccess.EMPTY);
        assertEquals(query, PatternSearchCodecs.loadQuery(queryTag, RegistryAccess.EMPTY).orElseThrow());
        assertEquals(reply, PatternSearchCodecs.loadReply(replyTag, RegistryAccess.EMPTY).orElseThrow());
        for (Consumer<CompoundTag> damage : List.<Consumer<CompoundTag>>of(
                tag -> tag.remove("AttemptId"), tag -> tag.putString("SearchGeneration", "11"),
                tag -> tag.putLong("FormationGeneration", -1), tag -> tag.putLong("Generation", -1),
                tag -> tag.putInt("Cursor", -1), tag -> tag.putInt("MaxReplyBatch", PatternQuery.MAX_REPLY_BATCH + 1),
                tag -> tag.putString("extra", "not allowed"))) {
            var broken = queryTag.copy(); damage.accept(broken);
            assertTrue(PatternSearchCodecs.loadQuery(broken, RegistryAccess.EMPTY).isEmpty());
        }
        for (Consumer<CompoundTag> damage : List.<Consumer<CompoundTag>>of(
                tag -> tag.remove("AttemptId"), tag -> tag.putInt("NextPageCursor", -1),
                tag -> tag.putLong("SearchGeneration", -1), tag -> tag.putString("Status", "MATCH"),
                tag -> { var wrong = new ListTag(); wrong.add(StringTag.valueOf("wrong")); tag.put("Matches", wrong); },
                tag -> { var many = new ListTag(); for (int i = 0; i <= PatternQuery.MAX_REPLY_BATCH; i++) many.add(new CompoundTag()); tag.put("Matches", many); })) {
            var broken = replyTag.copy(); damage.accept(broken);
            assertTrue(PatternSearchCodecs.loadReply(broken, RegistryAccess.EMPTY).isEmpty());
        }
        assertEquals(queryTag, PatternSearchCodecs.saveQuery(query, RegistryAccess.EMPTY));
        assertEquals(replyTag, PatternSearchCodecs.saveReply(reply, RegistryAccess.EMPTY));
    }
}
