package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.ProductionPlanFreezer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionTestRegistries;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternOutput;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternPageKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class FrozenProductionPlanTest {
	@BeforeAll static void bootstrap() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
	}

	@Test void freezesRealSequencedPatternExactRunsAndAbsoluteStockTarget() {
		UUID library = id(1), goal = id(2), cluster = id(3), network = id(4);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		PatternIngredient stone = new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"),
			null, 2, DataComponentPredicate.EMPTY);
		PatternIngredient coal = new PatternIngredient(ResourceLocation.withDefaultNamespace("coal"),
			null, 1, DataComponentPredicate.EMPTY);
		PatternRecord pattern = new PatternRecord(id(5), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 0, 0), List.of(),
			List.of(new PatternOutput(output, 3)), new SequencedPatternBody(List.of(
				new PatternStage(0, "press_input", List.of(stone)),
				new PatternStage(1, "furnace_input", List.of(coal)))), OptionalInt.empty());
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 116);

		FrozenProductionPlan plan = ProductionPlanFreezer.freeze(goal, cluster,
			patternProvider(library, pattern), pattern,
			new CreateRouteKey(network, "delivery"), 4, InputMode.EXACT, 104, scope,
			Map.of(new StackKey(new ItemStack(Items.STONE)), 8L,
				new StackKey(new ItemStack(Items.COAL)), 4L)).orElseThrow();

		assertEquals(pattern.patternId(), plan.patternId());
		assertEquals(104, plan.baselineCount());
		assertEquals(12, plan.requestedOutputCount());
		assertEquals(116, plan.absoluteTarget());
		assertEquals(4, plan.runs().remainingRuns());
		assertEquals(1, plan.runs().currentRun().runMultiplier());
		assertEquals(List.of(new CreateRouteKey(network, "press_input"),
			new CreateRouteKey(network, "furnace_input")),
			plan.stages().stream().map(FrozenProductionPlan.FrozenStage::route).toList());
		assertTrue(plan.stages().getFirst().manifest().containsKey(
			new StackKey(new ItemStack(Items.STONE))));
	}

	@Test void frozenBulkAdmissionRejectsAnExactModeMutationBeforeAllocation() {
		UUID library = id(11), goal = id(12), cluster = id(13), network = id(14);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		PatternRecord pattern = new PatternRecord(id(15), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, null, BlockPos.ZERO), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"), null, 1,
				DataComponentPredicate.EMPTY)), List.of(new PatternOutput(output, 1)),
			new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody("press"),
			OptionalInt.empty());
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 1);
		FrozenProductionPlan plan = ProductionPlanFreezer.freeze(goal, cluster,
			patternProvider(library, pattern), pattern,
			new CreateRouteKey(network, "delivery"), 1, InputMode.BULK, 0, scope,
			Map.of(new StackKey(new ItemStack(Items.STONE)), 1L)).orElseThrow();

		assertTrue(plan.acceptsModeCommand(InputMode.BULK));
		assertFalse(plan.acceptsModeCommand(InputMode.EXACT));
		assertEquals(InputMode.BULK, plan.runs().currentRun().inputMode());
	}

	@Test void componentBearingIngredientFreezesItsConcreteExactIdentityAndReloads() {
		UUID library = id(21), goal = id(22), cluster = id(23), network = id(24);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		ItemStack concrete = new ItemStack(Items.PAPER);
		concrete.set(DataComponents.MAX_STACK_SIZE, 1);
		StackKey exactIngredient = new StackKey(concrete);
		DataComponentPredicate predicate = DataComponentPredicate.builder()
			.expect(DataComponents.MAX_STACK_SIZE, 1).build();
		PatternRecord pattern = new PatternRecord(id(25), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("paper"), null, 2,
				predicate)), List.of(new PatternOutput(output, 1)),
			new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody("press"),
			OptionalInt.empty());
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 1);

		FrozenProductionPlan plan = ProductionPlanFreezer.freeze(goal, cluster,
			patternProvider(library, pattern), pattern,
			new CreateRouteKey(network, "delivery"), 1, InputMode.EXACT, 0, scope,
			Map.of(exactIngredient, 7L)).orElseThrow();

		assertEquals(Map.of(exactIngredient, 2L), plan.stages().getFirst().manifest());
		assertEquals(Map.of(exactIngredient, 2L), plan.runs().currentRun().manifest());
		var encoded = CurrentSchemaStateNbt.save(plan, RegistryAccess.EMPTY);
		FrozenProductionPlan reloaded = CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FrozenProductionPlan.class).orElseThrow();
		assertEquals(Map.of(exactIngredient, 2L), reloaded.stages().getFirst().manifest());
		assertEquals(Map.of(exactIngredient, 2L), reloaded.runs().currentRun().manifest());
	}

	@Test void ambiguousConcreteComponentVariantsFailClosed() {
		UUID library = id(31), goal = id(32), cluster = id(33), network = id(34);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		ItemStack specialPaper = new ItemStack(Items.PAPER);
		specialPaper.set(DataComponents.MAX_STACK_SIZE, 1);
		PatternRecord pattern = new PatternRecord(id(35), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("paper"), null, 1,
				DataComponentPredicate.EMPTY)), List.of(new PatternOutput(output, 1)),
			new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody("press"),
			OptionalInt.empty());
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 1);

		assertTrue(ProductionPlanFreezer.freeze(goal, cluster, patternProvider(library, pattern), pattern,
			new CreateRouteKey(network, "delivery"), 1, InputMode.EXACT, 0, scope,
			Map.of(new StackKey(new ItemStack(Items.PAPER)), 3L,
				new StackKey(specialPaper), 4L)).isEmpty());
	}

	@Test void freezesAndReloadsTheExactSelectedProviderInsteadOfOnlyItsPattern() {
		UUID library = id(41), goal = id(42), cluster = id(43), network = id(44);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		PatternRecord pattern = new PatternRecord(id(45), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"), null, 1,
				DataComponentPredicate.EMPTY)), List.of(new PatternOutput(output, 1)),
			new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody("press"),
			OptionalInt.empty());
		RouteSelectionScope fence = new RouteSelectionScope(id(46), id(47), output, 8, 9, id(48));
		SelectedProviderDescriptor provider = new SelectedProviderDescriptor(fence, library,
			pattern.patternId(), "factory-gauge/dim/1,2,3/0", RouteCandidate.ProviderKind.FACTORY_GAUGE,
			23, RouteCandidate.Availability.AVAILABLE);
		StockObservationScope scope = new StockObservationScope.NetworkSummary(network, output, 1);

		FrozenProductionPlan plan = ProductionPlanFreezer.freeze(goal, cluster, provider, pattern,
			new CreateRouteKey(network, "delivery"), 1, InputMode.BULK, 0, scope,
			Map.of(new StackKey(new ItemStack(Items.STONE)), 1L)).orElseThrow();
		assertEquals(provider, plan.selectedProvider());
		assertEquals(library, plan.libraryId());
		assertEquals(pattern.patternId(), plan.patternId());

		var encoded = CurrentSchemaStateNbt.save(plan, RegistryAccess.EMPTY);
		assertEquals(provider, CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FrozenProductionPlan.class).orElseThrow().selectedProvider());
		((net.minecraft.nbt.CompoundTag) encoded.get("Value")).remove("selectedProvider");
		assertTrue(CurrentSchemaStateNbt.load(encoded, RegistryAccess.EMPTY,
			FrozenProductionPlan.class).isEmpty(),
			"the prior provider-less plan shape is not part of the current schema");
	}

	@Test void remoteMigrationClearsOnlyTheExactInstalledExpansionRequest() {
		UUID library = id(51), goal = id(52), cluster = id(53), network = id(54);
		StackKey output = new StackKey(new ItemStack(Items.IRON_INGOT));
		StackKey ingredient = new StackKey(new ItemStack(Items.STONE));
		PatternRecord pattern = new PatternRecord(id(55), new PatternPageKey(
			new RecipeSourceLocation(Level.OVERWORLD, library, BlockPos.ZERO), 0, 0),
			List.of(new PatternIngredient(ResourceLocation.withDefaultNamespace("stone"), null, 1,
				DataComponentPredicate.EMPTY)), List.of(new PatternOutput(output, 1)),
			new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody("press"),
			OptionalInt.empty());
		FrozenProductionPlan plan = ProductionPlanFreezer.freeze(goal, cluster,
			patternProvider(library, pattern), pattern, new CreateRouteKey(network, "delivery"),
			1, InputMode.EXACT, 0,
			new StockObservationScope.NetworkSummary(network, output, 1),
			Map.of(ingredient, 1L)).orElseThrow();
		var run = plan.runs().currentRun();
		RemoteExpansionRequest request = remoteRequest(plan, run.runId(), run.fence(),
			id(56));
		RemoteExpansionRequest different = remoteRequest(plan, run.runId(), run.fence(),
			id(57));
		FrozenProductionPlan installed = plan.installRemoteRevision(request,
			RemoteExpansionRevision.compute(request));

		assertSame(plan, plan.clearRemoteExpansion(different),
			"clearing an already-empty installation must be idempotent");
		assertTrue(installed.clearRemoteExpansion(request).remoteExpansion().isEmpty());
		assertThrows(IllegalStateException.class,
			() -> installed.clearRemoteExpansion(different),
			"migration may not clear a different durable request");
	}

	private static UUID id(long value) { return new UUID(0, value); }

	private static SelectedProviderDescriptor patternProvider(UUID library,
		PatternRecord pattern) {
		StackKey output = pattern.mainOutput().stack();
		return new SelectedProviderDescriptor(new RouteSelectionScope(id(90), id(91), output,
			0, 0, id(92)), library, pattern.patternId(), pattern.patternId().toString(),
			RouteCandidate.ProviderKind.PATTERN, 0, RouteCandidate.Availability.AVAILABLE);
	}

	private static RemoteExpansionRequest remoteRequest(FrozenProductionPlan plan, UUID runId,
		long runFence, UUID parentProcessId) {
		var run = plan.runs().currentRun();
		return RemoteExpansionRequest.capture(RemoteExpansionTestRegistries.provider(),
			plan.goalId(), parentProcessId, 3, 4, plan.patternId(), 2, runId, runFence,
			run.inputMode(), run.runMultiplier(), plan.stages().stream()
				.map(stage -> new RemoteExpansionRequest.StageInput(stage.index(), stage.route(),
					stage.manifest())).toList(), run.manifest());
	}
}
