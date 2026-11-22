package com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan.FrozenProductionPlan.FrozenStage;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternIngredient;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.SequencedPatternBody;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StandardPatternBody;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRunExpansion;

/** Freezes one exact selected recipe and value manifest; never resolves or modifies world objects. */
public final class ProductionPlanFreezer {
	private ProductionPlanFreezer() {}

	public static Optional<FrozenProductionPlan> freeze(UUID goalId, UUID clusterId,
		SelectedProviderDescriptor selectedProvider, PatternRecord pattern,
		CreateRouteKey deliveryRoute, long multiplier,
		InputMode mode, long baselineCount, StockObservationScope observationScope,
		Map<StackKey, Long> availableManifest) {
		Objects.requireNonNull(pattern, "pattern");
		selectedProvider = Objects.requireNonNull(selectedProvider, "selectedProvider");
		if (!selectedProvider.patternId().equals(pattern.patternId())
			|| !selectedProvider.selectionScope().requestedStackKey()
				.equals(pattern.mainOutput().stack())
			|| multiplier <= 0 || !pattern.mainOutput().stack().equals(FrozenProductionPlan.scopeItem(observationScope)))
			return Optional.empty();
		UUID network = deliveryRoute.logisticsNetworkId();
		ArrayList<FrozenStage> stages = new ArrayList<>();
		if (pattern.body() instanceof StandardPatternBody standard) {
			ResolvedIngredients resolved = resolveIngredients(pattern.inputs(),
				availableManifest).orElse(null);
			if (resolved == null) return Optional.empty();
			stages.add(new FrozenStage(0, new CreateRouteKey(network, standard.address()),
				resolved.manifest(), resolved.ordered()));
		} else if (pattern.body() instanceof SequencedPatternBody sequenced) {
			for (var stage : sequenced.stages()) {
				ArrayList<PatternIngredient> ingredients = new ArrayList<>(stage.inputs());
				if (stage.index() == 0) ingredients.addAll(pattern.inputs());
				ResolvedIngredients resolved = resolveIngredients(ingredients,
					availableManifest).orElse(null);
				if (resolved == null) return Optional.empty();
				stages.add(new FrozenStage(stage.index(),
					new CreateRouteKey(network, stage.address()), resolved.manifest(),
					resolved.ordered()));
			}
		} else return Optional.empty();
		LinkedHashMap<StackKey, Long> unit = new LinkedHashMap<>();
		stages.forEach(stage -> stage.manifest().forEach((stack, count) ->
			unit.merge(stack, count, Math::addExact)));
		List<CreateRouteKey> routes = stages.stream().map(FrozenStage::route).toList();
		ProductionRun.RunSequence runs = ProductionRunExpansion.expand(goalId, pattern.patternId(), 0,
			multiplier, mode, routes, deliveryRoute, 0, unit, availableManifest);
		if (runs.currentRun() == null) return Optional.empty();
		long requested = Math.multiplyExact(pattern.mainOutput().count(), multiplier);
		if (FrozenProductionPlan.absoluteTarget(observationScope) != Math.addExact(baselineCount, requested))
			return Optional.empty();
		return Optional.of(new FrozenProductionPlan(goalId, clusterId, selectedProvider,
			pattern.kind(), pattern.mainOutput().stack(),
			pattern.mainOutput().count(), deliveryRoute, stages, runs, observationScope,
			baselineCount, requested, false, false, Optional.empty()));
	}

	private static Optional<ResolvedIngredients> resolveIngredients(
		List<PatternIngredient> ingredients, Map<StackKey, Long> availableManifest) {
		Map<StackKey, Long> available = Map.copyOf(Objects.requireNonNull(availableManifest,
			"availableManifest"));
		LinkedHashMap<StackKey, Long> manifest = new LinkedHashMap<>();
		ArrayList<OrderedIngredient> ordered = new ArrayList<>();
		for (PatternIngredient ingredient : ingredients) {
			List<StackKey> candidates = available.entrySet().stream()
				.filter(entry -> entry.getValue() != null && entry.getValue() > 0)
				.map(Map.Entry::getKey)
				.filter(key -> ingredient.matches(key.stack())).toList();
			if (candidates.size() != 1) return Optional.empty();
			StackKey stack = candidates.getFirst();
			long count = ingredient.count();
			manifest.merge(stack, count, Math::addExact);
			ordered.add(new OrderedIngredient(stack, count));
		}
		if (ordered.isEmpty()) return Optional.empty();
		return Optional.of(new ResolvedIngredients(manifest, ordered));
	}

	private record ResolvedIngredients(Map<StackKey, Long> manifest,
		List<OrderedIngredient> ordered) {
		private ResolvedIngredients {
			manifest = Map.copyOf(Objects.requireNonNull(manifest, "manifest"));
			ordered = List.copyOf(Objects.requireNonNull(ordered, "ordered"));
		}
	}

}
