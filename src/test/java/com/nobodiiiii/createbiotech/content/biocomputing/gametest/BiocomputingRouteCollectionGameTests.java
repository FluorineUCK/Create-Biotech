package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech")
@PrefixGameTestTemplate(false)
public final class BiocomputingRouteCollectionGameTests {
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void actualServiceSupersedeAndActiveRestoreKeepOldCollectorsFenced(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var cluster = UUID.randomUUID(); var epoch = UUID.randomUUID();
        var query = UUID.randomUUID(); var old = scope(epoch, query, 0); var replacement = scope(epoch, query, 1); CompoundTag saved;
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
            helper.assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(old)), "Begin original collection");
            helper.assertTrue(service.replaceRouteSources(cluster, old, RouteCandidate.ProviderKind.PATTERN, List.of(source(old, "old"))), "Publish original feed");
            helper.assertTrue(service.completeRouteCollector(cluster, old, RouteCandidate.ProviderKind.PATTERN), "Freeze original pattern collector");
            helper.assertTrue(service.supersedeRouteSelection(cluster, old, RouteSelectionRequest.patternOnly(replacement)), "Explicit CAS replaces original attempt");
            helper.assertTrue(!service.supersedeRouteSelection(cluster, old, RouteSelectionRequest.patternOnly(replacement)), "Old CAS cannot replay against newer state");
            helper.assertTrue(service.routeSourceSnapshot(cluster, old).isEmpty(), "Old source feed retired");
            helper.assertTrue(!service.replaceRouteSources(cluster, old, RouteCandidate.ProviderKind.PATTERN, List.of(source(old, "late"))), "Old source cannot return");
            helper.assertTrue(!service.completeRouteCollector(cluster, old, RouteCandidate.ProviderKind.PATTERN), "Late old completion refused");
            helper.assertTrue(service.replaceRouteSources(cluster, replacement, RouteCandidate.ProviderKind.PATTERN,
                List.of(source(replacement, "transient"))), "Publish unfinished replacement");
            helper.assertValueEqual(service.snapshot(cluster).orElseThrow().nextRouteSelectionSequence(), 2L, "Sequence committed for both admissions");
            saved = RuntimeNbt.save(service.snapshot(cluster).orElseThrow(), registries);
        }
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(RuntimeNbt.load(saved, registries).orElseThrow());
            helper.assertTrue(service.routeSourceSnapshot(cluster, replacement).isEmpty(), "Restore does not replay transient feeds");
            helper.assertTrue(!service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(replacement)), "Persisted monotonic fence rejects interrupted old scope");
            helper.assertTrue(!service.completeRouteCollector(cluster, replacement, RouteCandidate.ProviderKind.PATTERN), "Restored barrier is not implicitly completed");
            var fresh = scope(epoch, query, 2);
            helper.assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(fresh)), "Fresh fenced attempt accepted");
            select(helper, service, cluster, fresh, "restored/new");
            helper.assertValueEqual(service.snapshot(cluster).orElseThrow().nextRouteSelectionSequence(), 3L, "Fresh admission advances durable sequence");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void actualEpochFactRetiresApplicationCollectionBeforeNewEpochWork(GameTestHelper helper) {
        var cluster = UUID.randomUUID(); var epoch = UUID.randomUUID(); var nextEpoch = UUID.randomUUID();
        var query = UUID.randomUUID(); var old = scope(epoch, query, 0); var fresh = scope(nextEpoch, query, 0);
        var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig());
        try {
            service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
            helper.assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(old)), "Original epoch collection active");
            helper.assertTrue(service.replaceRouteSources(cluster, old, RouteCandidate.ProviderKind.PATTERN, List.of(source(old, "old/epoch"))), "Original epoch feed installed");
            helper.assertTrue(service.publishFact(new WorldFact.ClusterEpochFact(cluster, nextEpoch, 0)), "Actual epoch fact accepted");
            helper.assertTrue(service.routeSourceSnapshot(cluster, old).isEmpty(), "Epoch boundary immediately retires old application sources");
            awaitNewEpoch(helper, service, cluster, old, fresh, 0);
        } catch (RuntimeException | Error failure) { service.close(); throw failure; }
    }

    private static void awaitNewEpoch(GameTestHelper helper, FactoryRuntimeService service, UUID cluster,
            RouteSelectionScope old, RouteSelectionScope fresh, int elapsed) {
        helper.runAfterDelay(1, () -> {
            try {
                if (!service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(fresh))) {
                    helper.assertTrue(elapsed < 80, "Real worker must finish epoch transition before collection resumes");
                    awaitNewEpoch(helper, service, cluster, old, fresh, elapsed + 1); return;
                }
                helper.assertTrue(!service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(old)), "Old epoch cannot reopen");
                helper.assertTrue(!service.completeRouteCollector(cluster, old, RouteCandidate.ProviderKind.PATTERN), "Old epoch completion remains fenced");
                select(helper, service, cluster, fresh, "new/epoch");
                helper.assertValueEqual(service.snapshot(cluster).orElseThrow().epochId(), fresh.epochId(), "New durable epoch retained");
                helper.assertValueEqual(service.snapshot(cluster).orElseThrow().nextRouteSelectionSequence(), 1L, "Independent sequence in new epoch");
                service.close(); helper.succeed();
            } catch (RuntimeException | Error failure) { service.close(); throw failure; }
        });
    }

    private static void select(GameTestHelper helper, FactoryRuntimeService service, UUID cluster, RouteSelectionScope scope, String key) {
        var source = source(scope, key);
        helper.assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, List.of(source)), "Publish fresh source");
        for (var kind : RouteCandidate.ProviderKind.values()) helper.assertTrue(service.completeRouteCollector(cluster, scope, kind), "Explicit collector completion");
        helper.assertValueEqual(service.trySelectRoute(cluster, scope).orElseThrow().winner().orElseThrow(), source.candidate(), "Only fresh application source selected");
        helper.assertTrue(service.trySelectRoute(cluster, scope).isEmpty(), "Decision is one-shot");
    }
    private static RouteSelectionScope scope(UUID epoch, UUID query, long sequence) {
        return new RouteSelectionScope(epoch, query, new StackKey(Items.BOOK.getDefaultInstance()), 3, sequence, UUID.randomUUID());
    }
    private static ProductionRouteTable.ProviderSource source(RouteSelectionScope scope, String key) {
        return new ProductionRouteTable.ProviderSource(scope, new UUID(0, 1), new UUID(0, 2), key,
            RouteCandidate.ProviderKind.PATTERN, 7, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
    }
}
