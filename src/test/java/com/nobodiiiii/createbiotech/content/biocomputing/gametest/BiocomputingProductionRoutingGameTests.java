package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeConfig;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.CurrentSchemaStateNbt;
import com.nobodiiiii.createbiotech.content.factorycluster.runtime.RuntimeNbt;
import java.util.ArrayList;
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
public final class BiocomputingProductionRoutingGameTests {
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void actualProductionServiceRestoresCursorAndFencesLateCollectors(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess(); var cluster = UUID.randomUUID(); var epoch = UUID.randomUUID();
        var oldScope = scope(epoch, 0); CompoundTag saved;
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
            var chosen = collectAndSelect(helper, service, cluster, oldScope);
            helper.assertValueEqual(chosen.providerKind(), RouteCandidate.ProviderKind.PATTERN, "First stable route");
            helper.assertTrue(service.trySelectRoute(cluster, oldScope).isEmpty(), "Selection is one-shot");
            helper.assertTrue(service.routeSourceSnapshot(cluster, oldScope).isEmpty(), "Completed feed is retired");
            saved = RuntimeNbt.save(service.snapshot(cluster).orElseThrow(), registries);
        }
        var restored = RuntimeNbt.load(saved, registries).orElseThrow();
        helper.assertValueEqual(RouteCursorCodecs.save(restored.routeCursor()), saved.getCompound("RouteCursor"), "Historical cursor format retained");
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(restored);
            helper.assertTrue(!service.beginRouteSelection(cluster, RouteSelectionRequest.allRequired(oldScope)), "Old attempt cannot reopen after restore");
            helper.assertTrue(!service.replaceRouteSources(cluster, oldScope, RouteCandidate.ProviderKind.PATTERN,
                List.of(source(oldScope, 1, RouteCandidate.ProviderKind.PATTERN, "PATTERN"))), "Late old collector stays fenced");
            var nextScope = scope(epoch, restored.nextRouteSelectionSequence());
            var chosen = collectAndSelect(helper, service, cluster, nextScope);
            helper.assertValueEqual(chosen.providerKind(), RouteCandidate.ProviderKind.AGENT, "Restored round robin advances to next equal-priority source");
            var descriptor = SelectedProviderDescriptor.selected(nextScope, chosen);
            var tag = CurrentSchemaStateNbt.save(descriptor, registries);
            helper.assertValueEqual(tag.getString("Type"),
                "com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library.SelectedProviderDescriptor", "Historical selected-provider identity");
            var decoded = CurrentSchemaStateNbt.load(tag, registries, SelectedProviderDescriptor.class).orElseThrow();
            helper.assertTrue(decoded.matches(source(nextScope, 2, RouteCandidate.ProviderKind.AGENT, "AGENT")), "Exact scoped evidence restored");
            helper.assertTrue(!decoded.matches(source(oldScope, 2, RouteCandidate.ProviderKind.AGENT, "AGENT")), "Old scope is not execution evidence");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void actualCollectorOverflowPreservesOldFeedAndCanRetry(GameTestHelper helper) {
        var cluster = UUID.randomUUID(); var epoch = UUID.randomUUID(); var scope = scope(epoch, 0);
        try (var service = new FactoryRuntimeService(FactoryRuntimeConfig.fromServerConfig())) {
            service.start(); service.restoreSnapshot(ClusterEngineState.empty(cluster, epoch));
            helper.assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.patternOnly(scope)), "Begin actual collector barrier");
            var oldFeed = new ArrayList<ProductionRouteTable.ProviderSource>(); var tooMany = new ArrayList<ProductionRouteTable.ProviderSource>();
            for (int i = 0; i < ProductionRouteTable.MAX_SOURCES; i++) {
                oldFeed.add(source(scope, i, RouteCandidate.ProviderKind.PATTERN, "old/" + i));
                tooMany.add(source(scope, i, RouteCandidate.ProviderKind.PATTERN, "new/" + i));
            }
            tooMany.add(source(scope, ProductionRouteTable.MAX_SOURCES, RouteCandidate.ProviderKind.PATTERN, "extra"));
            helper.assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, oldFeed), "Install full valid feed");
            var cursor = service.snapshot(cluster).orElseThrow().routeCursor(); boolean rejected = false;
            try { service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, tooMany); }
            catch (IllegalStateException overflow) { rejected = true; }
            helper.assertTrue(rejected, "Oversized replacement is explicitly rejected");
            helper.assertValueEqual(service.routeSourceSnapshot(cluster, scope), oldFeed, "Original feed survives without a partial prefix");
            helper.assertValueEqual(service.snapshot(cluster).orElseThrow().routeCursor(), cursor, "Failure does not advance cursor");
            helper.assertTrue(service.replaceRouteSources(cluster, scope, RouteCandidate.ProviderKind.PATTERN, List.of(oldFeed.getFirst())), "Valid retry is still accepted");
            for (var kind : RouteCandidate.ProviderKind.values())
                helper.assertTrue(service.completeRouteCollector(cluster, scope, kind), "Explicitly complete required or empty optional collector");
            helper.assertValueEqual(service.trySelectRoute(cluster, scope).orElseThrow().winner().orElseThrow(), oldFeed.getFirst().candidate(), "Retry selects only the validated route");
        }
        helper.succeed();
    }

    private static RouteCandidate collectAndSelect(GameTestHelper helper, FactoryRuntimeService service, UUID cluster, RouteSelectionScope scope) {
        helper.assertTrue(service.beginRouteSelection(cluster, RouteSelectionRequest.allRequired(scope)), "Begin all-provider selection");
        int identity = 1;
        for (var kind : RouteCandidate.ProviderKind.values()) {
            helper.assertTrue(service.replaceRouteSources(cluster, scope, kind, List.of(source(scope, identity++, kind, kind.name()))), "Publish exact provider scope");
            helper.assertTrue(service.trySelectRoute(cluster, scope).isEmpty(), "Every frozen collector must finish before selection");
            helper.assertTrue(service.completeRouteCollector(cluster, scope, kind), "Complete collector");
        }
        return service.trySelectRoute(cluster, scope).orElseThrow().winner().orElseThrow();
    }

    private static RouteSelectionScope scope(UUID epoch, long sequence) {
        return new RouteSelectionScope(epoch, UUID.randomUUID(), new StackKey(Items.BOOK.getDefaultInstance()), 3, sequence, UUID.randomUUID());
    }
    private static ProductionRouteTable.ProviderSource source(RouteSelectionScope scope, long identity, RouteCandidate.ProviderKind kind, String key) {
        return new ProductionRouteTable.ProviderSource(scope, new UUID(0, identity), new UUID(0, identity), key,
            kind, 7, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
    }
}
