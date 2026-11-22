package com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.*;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.*;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** Real recipe/manifest fixture shared by old-archive capture and owner behavior tests. */
final class PlanFixture {
    static UUID id(long value) { return new UUID(0, value); }
    static void bootstrap() throws Exception {
        Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (type.getMethod("get").invoke(null) == null)
            type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
                .invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
        field.setAccessible(true); field.setBoolean(null, true);
    }
    static PatternRecord pattern() {
        return new PatternRecord(id(5), new PatternPageKey(
            new RecipeSourceLocation(Level.OVERWORLD, id(1), BlockPos.ZERO), 0, 0),
            List.of(ingredient("paper", 1)), List.of(new PatternOutput(stack(Items.IRON_INGOT), 3)),
            new SequencedPatternBody(List.of(
                new PatternStage(0, "press_input", List.of(ingredient("stone", 2))),
                new PatternStage(1, "furnace_input", List.of(ingredient("coal", 1))))), OptionalInt.empty());
    }
    static PatternIngredient ingredient(String name, int count) {
        return new PatternIngredient(ResourceLocation.withDefaultNamespace(name), null, count,
            DataComponentPredicate.EMPTY);
    }
    static StackKey stack(net.minecraft.world.item.Item item) { return new StackKey(new ItemStack(item)); }
    static SelectedProviderDescriptor provider(PatternRecord pattern) {
        return new SelectedProviderDescriptor(new RouteSelectionScope(id(90), id(91),
            pattern.mainOutput().stack(), 8, 9, id(92)), id(1), pattern.patternId(), "frozen-provider",
            RouteCandidate.ProviderKind.PATTERN, 23, RouteCandidate.Availability.AVAILABLE);
    }
    static Map<StackKey, Long> available() {
        return Map.of(stack(Items.STONE), 8L, stack(Items.PAPER), 4L, stack(Items.COAL), 4L);
    }
    static StockObservationScope scope() {
        return new StockObservationScope.NetworkSummary(id(4), stack(Items.IRON_INGOT), 116);
    }
    static FrozenProductionPlan freeze(String owner) throws Exception {
        var pattern = pattern();
        var method = Class.forName(owner).getMethod("freeze", UUID.class, UUID.class,
            SelectedProviderDescriptor.class, PatternRecord.class, CreateRouteKey.class,
            long.class, InputMode.class, long.class, StockObservationScope.class, Map.class);
        var result = (java.util.Optional<?>) method.invoke(null, id(2), id(3), provider(pattern),
            pattern, new CreateRouteKey(id(4), "delivery"), 4L, InputMode.EXACT, 104L,
            scope(), available());
        return (FrozenProductionPlan) result.orElseThrow();
    }
    static FrozenProductionPlan stableRun(FrozenProductionPlan plan) {
        var run = plan.runs().currentRun();
        var stable = new ProductionRun(id(50), run.goalId(), run.patternId(), run.fence(),
            run.runMultiplier(), run.inputMode(), run.inputRoutes(), run.returnWarehouseRoute(),
            run.outputPolicyGeneration(), run.manifest());
        return new FrozenProductionPlan(plan.goalId(), plan.clusterId(), plan.selectedProvider(),
            plan.patternKind(), plan.output(), plan.outputPerOperation(), plan.deliveryRoute(),
            plan.stages(), new ProductionRun.RunSequence(stable, plan.runs().remainingRuns()),
            plan.observationScope(), plan.baselineCount(), plan.requestedOutputCount(),
            plan.dispatchQueued(), plan.cancelAfterDrain(), plan.remoteExpansion());
    }
    static RemoteExpansionRequest request(FrozenProductionPlan plan) {
        var run = plan.runs().currentRun();
        return RemoteExpansionRequest.capture(RemoteExpansionTestRegistries.provider(),
            plan.goalId(), id(56), 3, 4, plan.patternId(), 2, run.runId(), run.fence(),
            run.inputMode(), run.runMultiplier(), plan.stages().stream().map(stage ->
                new RemoteExpansionRequest.StageInput(stage.index(), stage.route(), stage.manifest()))
                .toList(), run.manifest());
    }
    static FrozenProductionPlan installed(FrozenProductionPlan plan) {
        var request = request(plan); var revision = RemoteExpansionRevision.compute(request);
        return plan.installRemoteRevision(request, revision)
            .installRemoteResult(RemoteExpansionResult.compute(request, revision))
            .markDispatchQueued().cancelAfterDrainNow();
    }
}
