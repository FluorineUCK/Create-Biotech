package com.nobodiiiii.createbiotech.content.biocomputing.application.production.plan;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.SelectedProviderDescriptor;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRequest;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionResult;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RemoteExpansionRevision;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.PatternKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;


/** Immutable admitted production plan; recipe conversion belongs to ProductionPlanFreezer. */
public record FrozenProductionPlan(UUID goalId, UUID clusterId,
	SelectedProviderDescriptor selectedProvider, PatternKind patternKind,
	StackKey output, long outputPerOperation,
	CreateRouteKey deliveryRoute, List<FrozenStage> stages, ProductionRun.RunSequence runs,
	StockObservationScope observationScope, long baselineCount, long requestedOutputCount,
	boolean dispatchQueued, boolean cancelAfterDrain,
	Optional<RemoteExpansionInstallation> remoteExpansion) {
	public FrozenProductionPlan {
		goalId = Objects.requireNonNull(goalId, "goalId");
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		selectedProvider = Objects.requireNonNull(selectedProvider, "selectedProvider");
		patternKind = Objects.requireNonNull(patternKind, "patternKind");
		output = Objects.requireNonNull(output, "output");
		deliveryRoute = Objects.requireNonNull(deliveryRoute, "deliveryRoute");
		stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
		runs = Objects.requireNonNull(runs, "runs");
		observationScope = Objects.requireNonNull(observationScope, "observationScope");
		remoteExpansion = Objects.requireNonNull(remoteExpansion, "remoteExpansion");
		if (outputPerOperation <= 0 || baselineCount < 0 || requestedOutputCount <= 0
			|| stages.isEmpty() || absoluteTarget(observationScope) != Math.addExact(baselineCount,
				requestedOutputCount) || !scopeItem(observationScope).equals(output)
			|| !selectedProvider.selectionScope().requestedStackKey().equals(output)
			|| runs.currentRun() != null
				&& !runs.currentRun().patternId().equals(selectedProvider.patternId()))
			throw new IllegalArgumentException("Invalid frozen production plan");
		for (int index = 0; index < stages.size(); index++)
			if (stages.get(index).index() != index)
				throw new IllegalArgumentException("Frozen stages must be contiguous");
		if (remoteExpansion.isPresent()) validateRemoteInstallation(remoteExpansion.orElseThrow(),
			goalId, selectedProvider.patternId(), stages, runs.currentRun());
	}

	public UUID libraryId() { return selectedProvider.libraryId(); }
	public UUID patternId() { return selectedProvider.patternId(); }

	public List<FrozenStage> expandedStages(ProductionRun run) {
		if (runs.currentRun() == null || !runs.currentRun().runId().equals(run.runId()))
			throw new IllegalArgumentException("Run is not current");
		Optional<RemoteExpansionResult> installed = remoteExpansion
			.flatMap(RemoteExpansionInstallation::result);
		if (installed.isPresent()) {
			List<RemoteExpansionResult.ExpandedStage> expanded = installed.orElseThrow().stages();
			ArrayList<FrozenStage> result = new ArrayList<>(expanded.size());
			for (int index = 0; index < expanded.size(); index++) {
				RemoteExpansionResult.ExpandedStage remote = expanded.get(index);
				FrozenStage multiplied = stages.get(index).multiplied(run.runMultiplier());
				result.add(new FrozenStage(remote.index(), remote.route(), remote.manifest(),
					multiplied.orderedIngredients()));
			}
			return List.copyOf(result);
		}
		return stages.stream().map(stage -> stage.multiplied(run.runMultiplier())).toList();
	}

	/** Exact Pattern ingredient sequence frozen before provider routing and durable reloads. */
	public List<OrderedIngredient> orderedIngredientsForCurrentRun(long fence) {
		ProductionRun run = materializeCurrentRun(fence);
		List<OrderedIngredient> ordered = expandedStages(run).stream()
			.flatMap(stage -> stage.orderedIngredients().stream()).toList();
		LinkedHashMap<StackKey, Long> aggregate = new LinkedHashMap<>();
		ordered.forEach(ingredient -> aggregate.merge(ingredient.stack(), ingredient.amount(),
			Math::addExact));
		if (!Map.copyOf(aggregate).equals(run.manifest()))
			throw new IllegalStateException("ordered ingredients diverged from frozen Run manifest");
		return ordered;
	}

	public ProductionRun materializeCurrentRun(long fence) {
		ProductionRun current = Objects.requireNonNull(runs.currentRun(), "sequence complete");
		if (remoteExpansion.isPresent() && !remoteExpansionMaterialReady())
			throw new IllegalStateException("remote expansion result is not installed");
		Map<StackKey, Long> executionManifest = remoteExpansion
			.flatMap(RemoteExpansionInstallation::result)
			.map(RemoteExpansionResult::manifest).orElse(current.manifest());
		return new ProductionRun(current.runId(), current.goalId(), current.patternId(), fence,
			current.runMultiplier(), current.inputMode(), current.inputRoutes(),
			current.returnWarehouseRoute(), current.outputPolicyGeneration(), executionManifest);
	}

	public FrozenProductionPlan installRemoteRevision(RemoteExpansionRequest request,
		RemoteExpansionRevision revision) {
		RemoteExpansionInstallation installed = new RemoteExpansionInstallation(request, revision,
			Optional.empty());
		validateRemoteInstallation(installed, goalId, patternId(), stages, runs.currentRun());
		if (remoteExpansion.isPresent()) {
			if (remoteExpansion.orElseThrow().equals(installed)) return this;
			throw new IllegalStateException("a different remote expansion is already installed");
		}
		return copy(dispatchQueued, cancelAfterDrain, Optional.of(installed), runs);
	}

	public FrozenProductionPlan installRemoteResult(RemoteExpansionResult result) {
		RemoteExpansionInstallation prior = remoteExpansion.orElseThrow(() ->
			new IllegalStateException("remote revision is not installed"));
		if (prior.result().isPresent()) {
			if (prior.result().orElseThrow().equals(result)) return this;
			throw new IllegalStateException("a different remote expansion result is already installed");
		}
		RemoteExpansionInstallation installed = new RemoteExpansionInstallation(prior.request(),
			prior.revision(), Optional.of(Objects.requireNonNull(result, "result")));
		validateRemoteInstallation(installed, goalId, patternId(), stages, runs.currentRun());
		return copy(dispatchQueued, cancelAfterDrain, Optional.of(installed), runs);
	}

	/** Clears only the exact installed request before a fenced remote migration is reissued. */
	public FrozenProductionPlan clearRemoteExpansion(RemoteExpansionRequest expected) {
		expected = Objects.requireNonNull(expected, "expected");
		if (remoteExpansion.isEmpty()) return this;
		if (!remoteExpansion.orElseThrow().request().equals(expected))
			throw new IllegalStateException("a different remote expansion is installed");
		return copy(dispatchQueued, cancelAfterDrain, Optional.empty(), runs);
	}

	public boolean remoteExpansionMaterialReady() {
		return remoteExpansion.flatMap(RemoteExpansionInstallation::result).isPresent();
	}

	public long absoluteTarget() { return absoluteTarget(observationScope); }

	/** Once admitted, the immutable run sequence is the mode authority. */
	public boolean acceptsModeCommand(InputMode requested) {
		requested = Objects.requireNonNull(requested, "requested");
		ProductionRun current = runs.currentRun();
		return current != null && current.inputMode() == requested;
	}

	public FrozenProductionPlan markDispatchQueued() {
		return copy(true, cancelAfterDrain, remoteExpansion, runs);
	}

	public FrozenProductionPlan cancelAfterDrainNow() {
		return copy(dispatchQueued, true, remoteExpansion, runs);
	}

	public FrozenProductionPlan afterOutputSealed(ProductionRun sealed) {
		ProductionRun current = Objects.requireNonNull(runs.currentRun(), "sequence complete");
		if (!current.runId().equals(sealed.runId()))
			throw new IllegalArgumentException("Only the current Run may advance this sequence");
		return copy(false, cancelAfterDrain, Optional.empty(), runs.afterOutputSealed(current));
	}

	private FrozenProductionPlan copy(boolean nextDispatchQueued, boolean nextCancelAfterDrain,
		Optional<RemoteExpansionInstallation> nextRemoteExpansion,
		ProductionRun.RunSequence nextRuns) {
		return new FrozenProductionPlan(goalId, clusterId, selectedProvider, patternKind,
			output, outputPerOperation, deliveryRoute, stages, nextRuns, observationScope,
			baselineCount, requestedOutputCount, nextDispatchQueued, nextCancelAfterDrain,
			nextRemoteExpansion);
	}

	private static void validateRemoteInstallation(RemoteExpansionInstallation installation,
		UUID goalId, UUID patternId, List<FrozenStage> stages, ProductionRun current) {
		if (current == null) throw new IllegalArgumentException(
			"completed production plans cannot own remote expansion state");
		RemoteExpansionRequest request = installation.request();
		if (!request.sourceGoalId().equals(goalId) || !request.patternId().equals(patternId)
			|| !request.runId().equals(current.runId()) || request.runFence() != current.fence()
			|| request.inputMode() != current.inputMode()
			|| request.runMultiplier() != current.runMultiplier()
			|| !request.expandedManifest().equals(current.manifest())
			|| request.stages().size() != stages.size())
			throw new IllegalArgumentException("remote expansion does not match the current frozen Run");
		for (int index = 0; index < stages.size(); index++) {
			FrozenStage frozen = stages.get(index);
			RemoteExpansionRequest.StageInput input = request.stages().get(index);
			if (input.index() != frozen.index() || !input.route().equals(frozen.route())
				|| !input.manifest().equals(frozen.manifest()))
				throw new IllegalArgumentException("remote expansion changed frozen Pattern input");
		}
		if (!installation.revision().matches(request)
			|| installation.result().filter(result ->
				!result.matches(request, installation.revision())).isPresent())
			throw new IllegalArgumentException("remote expansion evidence is not receiver-verifiable");
	}

	static StackKey scopeItem(StockObservationScope scope) {
		return switch (scope) {
			case StockObservationScope.NetworkSummary network -> network.item();
			case StockObservationScope.AttachedInventory inventory -> inventory.item();
		};
	}

	static long absoluteTarget(StockObservationScope scope) {
		return switch (scope) {
			case StockObservationScope.NetworkSummary network -> network.targetLevel();
			case StockObservationScope.AttachedInventory inventory -> inventory.targetLevel();
		};
	}

	public record FrozenStage(int index, CreateRouteKey route, Map<StackKey, Long> manifest,
		List<OrderedIngredient> orderedIngredients) {
		public FrozenStage {
			if (index < 0) throw new IllegalArgumentException("negative stage");
			route = Objects.requireNonNull(route, "route");
			manifest = Map.copyOf(Objects.requireNonNull(manifest, "manifest"));
			orderedIngredients = List.copyOf(Objects.requireNonNull(orderedIngredients,
				"orderedIngredients"));
			if (manifest.isEmpty() || manifest.values().stream().anyMatch(value -> value == null || value <= 0))
				throw new IllegalArgumentException("Invalid stage manifest");
			LinkedHashMap<StackKey, Long> aggregate = new LinkedHashMap<>();
			orderedIngredients.forEach(ingredient -> aggregate.merge(ingredient.stack(),
				ingredient.amount(), Math::addExact));
			if (orderedIngredients.isEmpty() || !Map.copyOf(aggregate).equals(manifest))
				throw new IllegalArgumentException("ordered stage ingredients must equal its manifest");
		}
		FrozenStage multiplied(long multiplier) {
			LinkedHashMap<StackKey, Long> result = new LinkedHashMap<>();
			manifest.forEach((stack, count) -> result.put(stack, Math.multiplyExact(count, multiplier)));
			List<OrderedIngredient> multipliedOrder = orderedIngredients.stream()
				.map(ingredient -> new OrderedIngredient(ingredient.stack(),
					Math.multiplyExact(ingredient.amount(), multiplier))).toList();
			return new FrozenStage(index, route, result, multipliedOrder);
		}
	}

	public record RemoteExpansionInstallation(RemoteExpansionRequest request,
		RemoteExpansionRevision revision, Optional<RemoteExpansionResult> result) {
		public RemoteExpansionInstallation {
			request = Objects.requireNonNull(request, "request");
			revision = Objects.requireNonNull(revision, "revision");
			result = Objects.requireNonNull(result, "result");
			if (!revision.matches(request)
				|| (result.isPresent() && !result.orElseThrow().matches(request, revision)))
				throw new IllegalArgumentException("invalid remote expansion installation");
		}
	}
}
