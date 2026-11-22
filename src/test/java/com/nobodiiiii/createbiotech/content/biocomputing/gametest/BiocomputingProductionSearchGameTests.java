package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.priority.PatternPriorityAuthority;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibrarySearchCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingProductionSearchGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void restoredDirectoryWaitsForUnavailablePriorityBeforeQueryingLowerPeers(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        var frequency = CommunicationFrequency.of(" 频道 A ");
        var high = new LibraryRegistration(UUID.randomUUID(), UUID.randomUUID(),
            new RecipeSourceLocation(helper.getLevel().dimension(), UUID.randomUUID(), helper.absolutePos(new BlockPos(1, 1, 1))),
            3, frequency, 20, 7, LibraryRegistration.LoadState.UNLOADED, LibraryRegistration.Reachability.REACHABLE);
        var low = new LibraryRegistration(UUID.randomUUID(), high.clusterId(), high.address(), 2, frequency, 10, 8,
            LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE);
        var tag = CurrentSchemaStateNbt.save(high, registries);
        helper.assertValueEqual(tag.getString("Type"),
            "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryRegistration", "Historical registration identity");
        helper.assertValueEqual(tag.getCompound("Value").getCompound("address").getString("Type"),
            "com.nobodiiiii.createbiotech.content.factorycluster.SpaceAddress", "Historical source field identity");
        var restored = CurrentSchemaStateNbt.load(tag, registries, LibraryRegistration.class).orElseThrow();
        helper.assertValueEqual(restored, high, "All registration facts restored with the real registry");
        var directory = new LibraryDirectory(); directory.publish(low); directory.publish(restored);
        helper.assertValueEqual(directory.knownPeers(), List.of(high, low), "Stable priority order includes unavailable peer");
        var requester = UUID.randomUUID();
        helper.assertTrue(directory.visibleTo(requester, CommunicationFrequency.of("频道 A")).isEmpty(), "No implicit frequency trimming");
        var coordinator = new LibrarySearchCoordinator(directory);
        var request = new LibrarySearchCoordinator.SearchRequest(UUID.randomUUID(), UUID.randomUUID(), requester, UUID.randomUUID(),
            UUID.randomUUID(), frequency, new StackKey(Items.BOOK.getDefaultInstance()), 11, 0, UUID.randomUUID(), 2, 0, PatternPriorityAuthority.empty());
        var waiting = coordinator.begin(request);
        helper.assertValueEqual(waiting.status(), LibrarySearchCoordinator.Status.WAITING_LIBRARY, "Unavailable high priority blocks lower priority");
        helper.assertTrue(waiting.queries().isEmpty(), "No query silently bypasses the unloaded peer");
        directory.publish(restored.withLoadState(LibraryRegistration.LoadState.LOADED));
        var ready = coordinator.refresh(0);
        helper.assertValueEqual(ready.queries().size(), 1, "Only the newly available high priority is queried");
        var query = ready.queries().getFirst();
        helper.assertValueEqual(query.libraryId(), high.libraryId(), "Exact high-priority target");
        helper.assertValueEqual(query.formationGeneration(), high.formationGeneration(), "Formation fence preserved");
        helper.assertValueEqual(query.queryGeneration(), high.publicCatalogGeneration(), "Catalog fence preserved");
        helper.assertValueEqual(query.searchGeneration(), request.generation(), "Search fence preserved");
        var end = ProductionCatalogSearch.advance(new ProductionRecipeCatalog(high.libraryId(), 3, 7, true, List.of()), query).reply().orElseThrow();
        var next = coordinator.accept(end, 0);
        helper.assertValueEqual(next.queries().stream().map(PatternQuery::libraryId).toList(), List.of(low.libraryId()),
            "Lower priority starts only after an explicit empty group end");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void actualProductionWorkerConsumesRestoredApplicationCatalogAndPreservesReplyFences(GameTestHelper helper) {
        var level = helper.getLevel(); var registries = level.registryAccess();
        var source = new PatternPageKey(new RecipeSourceLocation(level.dimension(), null,
            helper.absolutePos(new BlockPos(1, 1, 1))), 0, 0);
        String raw = """
            {"version":2,"type":"standard","inputs":[],"outputs":[{"item":"minecraft:book","count":1}],"address":"search_probe"}
            """;
        var pattern = ((ParseResult.Valid) new PatternJsonParser(registries).parse(source, raw)).pattern();
        var query = new PatternQuery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new StackKey(Items.BOOK.getDefaultInstance()), 7, 0, 2, 3, 11, UUID.randomUUID());
        var catalog = new ProductionRecipeCatalog(query.libraryId(), 3, 7, true, List.of(pattern));
        var catalogTag = CurrentSchemaStateNbt.save(catalog, registries);
        var queryTag = CurrentSchemaStateNbt.save(query, registries);
        helper.assertValueEqual(catalogTag.getString("Type"),
            "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibraryCatalogSnapshot", "Historical catalog identity");
        helper.assertValueEqual(queryTag.getString("Type"),
            "com.nobodiiiii.createbiotech.content.factorycluster.pattern.PatternQuery", "Historical request identity");
        var restoredCatalog = CurrentSchemaStateNbt.load(catalogTag, registries, ProductionRecipeCatalog.class).orElseThrow();
        var restoredQuery = CurrentSchemaStateNbt.load(queryTag, registries, PatternQuery.class).orElseThrow();
        helper.assertValueEqual(restoredCatalog, catalog, "Native catalog restore");
        helper.assertValueEqual(restoredQuery, query, "Native request restore");
        var cluster = UUID.randomUUID(); var epoch = UUID.randomUUID();
        var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig());
        try {
            service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
            service.publishLibraryCatalog(restoredCatalog);
            helper.assertTrue(service.enqueueLibraryQuery(cluster, epoch, restoredQuery), "Actual worker accepts the restored request");
            collect(helper, service, cluster, query, pattern, new ArrayList<>(), 0);
        } catch (RuntimeException | Error failure) {
            service.close(); throw failure;
        }
    }

    private static void collect(GameTestHelper helper, FactoryRuntimeService service, UUID cluster,
            PatternQuery query, PatternRecord pattern, List<PatternReply> replies, int elapsed) {
        helper.runAfterDelay(1, () -> {
            try {
                replies.addAll(service.drainLibraryReplies(cluster, 8));
                if (replies.size() < 2) {
                    helper.assertTrue(elapsed < 120, "Actual worker must eventually return match and separate group end");
                    collect(helper, service, cluster, query, pattern, replies, elapsed + 1); return;
                }
                helper.assertValueEqual(replies, List.of(PatternReply.matchBatch(query, List.of(pattern), 1),
                    PatternReply.groupEnd(query, 1)), "Exact request/search/formation/attempt fences and reply ordering");
                for (var reply : replies) {
                    var encoded = CurrentSchemaStateNbt.save(reply, helper.getLevel().registryAccess());
                    helper.assertValueEqual(CurrentSchemaStateNbt.load(encoded, helper.getLevel().registryAccess(), PatternReply.class).orElseThrow(),
                        reply, "Real registry reply persistence");
                }
                helper.assertValueEqual(query.pageCursor(), 0, "Original immutable request stays unchanged");
                service.close(); helper.succeed();
            } catch (RuntimeException | Error failure) {
                service.close(); throw failure;
            }
        });
    }
}
