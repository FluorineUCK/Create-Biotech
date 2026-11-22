package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
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
public final class BiocomputingSearchCoordinatorGameTests {
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void actualWorkerAndCollectorCompleteNewApplicationSearchOnlyOnce(GameTestHelper helper) {
        var level = helper.getLevel(); var registries = level.registryAccess();
        var cluster = UUID.randomUUID(); var epoch = UUID.randomUUID(); var library = UUID.randomUUID();
        var source = new PatternPageKey(new RecipeSourceLocation(level.dimension(), library,
            helper.absolutePos(new BlockPos(1, 1, 1))), 0, 0);
        var pattern = ((ParseResult.Valid) new PatternJsonParser(registries).parse(source, """
            {"version":2,"type":"standard","inputs":[],"outputs":[{"item":"minecraft:book","count":1}],"address":"coordinator_probe"}
            """)).pattern();
        var catalog = new ProductionRecipeCatalog(library, 3, 7, true, List.of(pattern));
        catalog = CurrentSchemaStateNbt.load(CurrentSchemaStateNbt.save(catalog, registries), registries,
            ProductionRecipeCatalog.class).orElseThrow();
        var directory = new LibraryDirectory();
        var peer = new LibraryRegistration(library, cluster, source.shelf(), 3, CommunicationFrequency.of("coordinator"),
            8, 7, LibraryRegistration.LoadState.LOADED, LibraryRegistration.Reachability.REACHABLE);
        directory.publish(peer);
        var coordinator = new LibrarySearchCoordinator(directory);
        var scope = new RouteSelectionScope(epoch, UUID.randomUUID(), new StackKey(Items.BOOK.getDefaultInstance()), 11, 0, UUID.randomUUID());
        var request = new LibrarySearchCoordinator.SearchRequest(scope.queryId(), UUID.randomUUID(), cluster, epoch,
            UUID.randomUUID(), peer.frequency(), scope.requestedStackKey(), 11, 0, scope.attemptId(), 2, 0, PatternPriorityAccess.NONE);
        var query = coordinator.begin(request).queries().getFirst();
        var wave = coordinator.currentWave().orElseThrow();
        var waveTag = CurrentSchemaStateNbt.save(wave, registries);
        helper.assertValueEqual(waveTag.getString("Type"),
            "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.LibrarySearchWave", "Historical wave identity");
        helper.assertValueEqual(CurrentSchemaStateNbt.load(waveTag, registries, LibrarySearchWave.class).orElseThrow(), wave,
            "Real registry wave round trip");
        var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig());
        try {
            service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
            helper.assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(scope)), "Actual collector starts before worker submission");
            service.publishLibraryCatalog(catalog);
            helper.assertTrue(service.enqueueLibraryQuery(cluster, epoch, query), "Actual worker consumes the application request");
            collect(helper, service, cluster, scope, coordinator, directory, peer, query, pattern, new ArrayList<>(), 0);
        } catch (RuntimeException | Error failure) { service.close(); throw failure; }
    }

    private static void collect(GameTestHelper helper, FactoryRuntimeService service, UUID cluster, RouteSelectionScope scope,
            LibrarySearchCoordinator coordinator, LibraryDirectory directory, LibraryRegistration peer,
            PatternQuery query, PatternRecord pattern, List<PatternReply> replies, int elapsed) {
        helper.runAfterDelay(1, () -> {
            try {
                replies.addAll(service.drainLibraryReplies(cluster, 8));
                if (replies.size() < 2) {
                    helper.assertTrue(elapsed < 120, "Worker returns a match and independent end marker");
                    collect(helper, service, cluster, scope, coordinator, directory, peer, query, pattern, replies, elapsed + 1); return;
                }
                helper.assertValueEqual(replies, List.of(PatternReply.matchBatch(query, List.of(pattern), 1), PatternReply.groupEnd(query, 1)),
                    "Worker results retain all request fences");
                var matched = coordinator.accept(replies.getFirst(), 0);
                helper.assertTrue(matched.providerSources().isEmpty(), "A match is not a completed wave");
                var ready = coordinator.accept(replies.get(1), 0);
                helper.assertValueEqual(ready.status(), LibrarySearchCoordinator.Status.SELECTING_ROUTE, "Explicit end closes the wave");
                helper.assertValueEqual(ready.providerSources().size(), 1, "Exactly one source is computed");
                helper.assertValueEqual(ready.providerSources().getFirst().scope(), scope, "Source has the exact collector attempt");
                finish(helper, service, cluster, scope, coordinator, directory, peer, query, pattern, ready, 0);
            } catch (RuntimeException | Error failure) { service.close(); throw failure; }
        });
    }

    private static void finish(GameTestHelper helper, FactoryRuntimeService service, UUID cluster, RouteSelectionScope scope,
            LibrarySearchCoordinator coordinator, LibraryDirectory directory, LibraryRegistration peer, PatternQuery query,
            PatternRecord pattern, LibrarySearchCoordinator.Update ready, int elapsed) {
        try {
            if (!service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, ready.providerSources())) {
                helper.assertTrue(elapsed < 40, "Actual worker gate eventually admits the validated source");
                helper.runAfterDelay(1, () -> finish(helper, service, cluster, scope, coordinator, directory, peer, query, pattern, ready, elapsed + 1)); return;
            }
            for (var kind : RouteCandidate.ProviderKind.values())
                helper.assertTrue(service.completeRouteCollector(cluster, scope, kind), "Explicit collector completion");
            var decision = service.trySelectRoute(cluster, scope).orElseThrow();
            var completed = coordinator.completeRouteSelection(decision);
            helper.assertValueEqual(completed.status(), LibrarySearchCoordinator.Status.COMPLETE, "Application reaches terminal state");
            helper.assertValueEqual(completed.winner().orElseThrow().patternId(), pattern.patternId(), "Actual route selection chooses the matched pattern");
            directory.publish(new LibraryRegistration(peer.libraryId(), peer.clusterId(), peer.address(), 3, peer.frequency(), 8, 8,
                peer.loadState(), peer.reachability()));
            for (var late : List.of(PatternReply.groupEnd(query, 1), PatternReply.matchBatch(query, List.of(pattern), 1),
                    PatternReply.unavailable(query), PatternReply.restartRequired(query, 8))) {
                var rejected = coordinator.accept(late, 4);
                helper.assertValueEqual(rejected.status(), LibrarySearchCoordinator.Status.COMPLETE, "Late delivery cannot reopen a completed search");
                helper.assertTrue(rejected.staleReplyRejected() && rejected.queries().isEmpty() && rejected.providerSources().isEmpty()
                    && rejected.speculativeAllocations().isEmpty() && rejected.winner().isEmpty(), "No second work or decision emitted");
            }
            helper.assertTrue(service.trySelectRoute(cluster, scope).isEmpty(), "Actual selection remains one-shot");
            helper.assertTrue(service.routeSourceSnapshot(cluster, scope).isEmpty(), "Completed collector feed is gone");
            var state = service.snapshot(cluster).orElseThrow();
            helper.assertValueEqual(state.nextRouteSelectionSequence(), 1L, "Only one attempt consumed");
            helper.assertTrue(state.pendingIntents().isEmpty() && state.processes().isEmpty(), "Search data does not mint world actions or production resources");
            service.close(); helper.succeed();
        } catch (RuntimeException | Error failure) { service.close(); throw failure; }
    }
}
