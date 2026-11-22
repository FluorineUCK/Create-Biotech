package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.component.WritableBookContent;

/** Bounded immutable Pattern expansion input. It contains no world or material authority. */
public record RemoteExpansionRequest(UUID sourceGoalId, UUID parentProcessId,
	long parentProcessGeneration, long parentStateGeneration, UUID patternId,
	int patternVersion, UUID runId, long runFence, InputMode inputMode, long runMultiplier,
	List<StageInput> stages, Map<StackKey, Long> dependencyManifest,
	RemoteExpansionStackSnapshot exactStackSnapshot) {
	public static final int MAX_SERIALIZED_BYTES = 1 << 20;

	/**
	 * Server-thread integration seam. Exact stacks are serialized once with the authoritative
	 * registry provider before this immutable request can enter a worker or durable radio lease.
	 */
	public static RemoteExpansionRequest capture(HolderLookup.Provider registries,
		UUID sourceGoalId, UUID parentProcessId, long parentProcessGeneration,
		long parentStateGeneration, UUID patternId, int patternVersion, UUID runId,
		long runFence, InputMode inputMode, long runMultiplier, List<StageInput> stages,
		Map<StackKey, Long> dependencyManifest) {
		Objects.requireNonNull(stages, "stages");
		Objects.requireNonNull(dependencyManifest, "dependencyManifest");
		ArrayList<StackKey> stacks = new ArrayList<>(dependencyManifest.keySet());
		stages.forEach(stage -> stacks.addAll(Objects.requireNonNull(stage, "stage")
			.manifest().keySet()));
		return new RemoteExpansionRequest(sourceGoalId, parentProcessId,
			parentProcessGeneration, parentStateGeneration, patternId, patternVersion, runId,
			runFence, inputMode, runMultiplier, stages, dependencyManifest,
			RemoteExpansionStackSnapshot.capture(registries, stacks));
	}

	public RemoteExpansionRequest {
		sourceGoalId = Objects.requireNonNull(sourceGoalId, "sourceGoalId");
		parentProcessId = Objects.requireNonNull(parentProcessId, "parentProcessId");
		patternId = Objects.requireNonNull(patternId, "patternId");
		runId = Objects.requireNonNull(runId, "runId");
		inputMode = Objects.requireNonNull(inputMode, "inputMode");
		exactStackSnapshot = Objects.requireNonNull(exactStackSnapshot, "exactStackSnapshot");
		if (parentProcessGeneration < 0 || parentStateGeneration < 0 || runFence < 0)
			throw new IllegalArgumentException("negative expansion fence");
		if (patternVersion != 2)
			throw new IllegalArgumentException("remote expansion requires current Pattern version 2");
		if (runMultiplier <= 0) throw new IllegalArgumentException("non-positive expansion multiplier");
		stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
		dependencyManifest = positiveManifest(dependencyManifest);
		if (stages.isEmpty() || stages.size() > WritableBookContent.PAGE_EDIT_LENGTH)
			throw new IllegalArgumentException("expansion stages exceed the Pattern representation bound");
		int entries = dependencyManifest.size();
		LinkedHashMap<StackKey, Long> combined = new LinkedHashMap<>();
		try {
			for (int index = 0; index < stages.size(); index++) {
				StageInput stage = stages.get(index);
				if (stage.index() != index)
					throw new IllegalArgumentException("expansion stages are not contiguous");
				entries = Math.addExact(entries, stage.manifest().size());
				stage.manifest().forEach((stack, count) -> {
					Math.multiplyExact(count, runMultiplier);
					combined.merge(stack, count, Math::addExact);
				});
			}
			dependencyManifest.values().forEach(count -> Math.multiplyExact(count, runMultiplier));
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("remote expansion arithmetic exceeds its bound", overflow);
		}
		if (entries > WritableBookContent.PAGE_EDIT_LENGTH
			|| !Map.copyOf(combined).equals(dependencyManifest))
			throw new IllegalArgumentException("expansion dependency manifest is not the stage aggregate");
		ArrayList<StackKey> exactStacks = new ArrayList<>(dependencyManifest.keySet());
		stages.forEach(stage -> exactStacks.addAll(stage.manifest().keySet()));
		exactStackSnapshot.requireExactly(exactStacks);
		RemoteExpansionDigest.requireRequestFits(sourceGoalId, parentProcessId,
			parentProcessGeneration, parentStateGeneration, patternId, patternVersion, runId,
			runFence, inputMode, runMultiplier, stages, dependencyManifest, exactStackSnapshot);
	}

	public String fingerprint() { return RemoteExpansionDigest.request(this); }

	public List<RemoteExpansionResult.ExpandedStage> expandedStages() {
		ArrayList<RemoteExpansionResult.ExpandedStage> expanded = new ArrayList<>(stages.size());
		for (StageInput stage : stages)
			expanded.add(new RemoteExpansionResult.ExpandedStage(stage.index(), stage.route(),
				multiply(stage.manifest(), runMultiplier)));
		return List.copyOf(expanded);
	}

	public Map<StackKey, Long> expandedManifest() {
		return multiply(dependencyManifest, runMultiplier);
	}

	private static Map<StackKey, Long> multiply(Map<StackKey, Long> source, long multiplier) {
		LinkedHashMap<StackKey, Long> result = new LinkedHashMap<>();
		source.forEach((stack, count) -> result.put(stack, Math.multiplyExact(count, multiplier)));
		return Map.copyOf(result);
	}

	static Map<StackKey, Long> positiveManifest(Map<StackKey, Long> source) {
		Map<StackKey, Long> result = Map.copyOf(Objects.requireNonNull(source, "manifest"));
		if (result.isEmpty() || result.size() > WritableBookContent.PAGE_EDIT_LENGTH)
			throw new IllegalArgumentException("invalid expansion manifest size");
		result.forEach((stack, count) -> {
			Objects.requireNonNull(stack, "manifest stack");
			if (count == null || count <= 0) throw new IllegalArgumentException("non-positive expansion count");
		});
		return result;
	}

	public record StageInput(int index, CreateRouteKey route, Map<StackKey, Long> manifest)
		implements RemoteExpansionDigest.RemoteExpansionStage {
		public StageInput {
			if (index < 0) throw new IllegalArgumentException("negative expansion stage");
			route = Objects.requireNonNull(route, "route");
			manifest = positiveManifest(manifest);
		}
	}
}
