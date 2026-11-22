package com.nobodiiiii.createbiotech.content.biocomputing.application.production.run;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Immutable finite production material contract/cursor; expansion policy is a separate owner. */
public record ProductionRun(UUID runId, UUID goalId, UUID patternId, long fence,
	long runMultiplier, InputMode inputMode, List<CreateRouteKey> inputRoutes,
	CreateRouteKey returnWarehouseRoute, long outputPolicyGeneration, Map<StackKey, Long> manifest) {
	public ProductionRun {
		runId = Objects.requireNonNull(runId, "runId");
		goalId = Objects.requireNonNull(goalId, "goalId");
		patternId = Objects.requireNonNull(patternId, "patternId");
		if (fence < 0)
			throw new IllegalArgumentException("Run fence must be non-negative");
		if (runMultiplier <= 0)
			throw new IllegalArgumentException("Run multiplier must be positive");
		inputMode = Objects.requireNonNull(inputMode, "inputMode");
		inputRoutes = List.copyOf(Objects.requireNonNull(inputRoutes, "inputRoutes"));
		if (inputRoutes.isEmpty())
			throw new IllegalArgumentException("Run needs at least one real input route");
		returnWarehouseRoute = Objects.requireNonNull(returnWarehouseRoute, "returnWarehouseRoute");
		if (outputPolicyGeneration < 0)
			throw new IllegalArgumentException("Output policy generation must be non-negative");
		manifest = immutablePositiveManifest(manifest);
	}

	static ProductionRun newRun(UUID goalId, UUID patternId, long fence, long multiplier,
		InputMode inputMode, List<CreateRouteKey> inputRoutes, CreateRouteKey returnWarehouseRoute,
		long outputPolicyGeneration, Map<StackKey, Long> manifest) {
		return new ProductionRun(UUID.randomUUID(), goalId, patternId, fence, multiplier, inputMode,
			inputRoutes, returnWarehouseRoute, outputPolicyGeneration, manifest);
	}

	static Map<StackKey, Long> immutablePositiveManifest(Map<StackKey, Long> manifest) {
		manifest = Map.copyOf(Objects.requireNonNull(manifest, "manifest"));
		for (Map.Entry<StackKey, Long> entry : manifest.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "manifest key");
			if (entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException("Manifest quantities must be positive");
		}
		return manifest;
	}

	/** Immutable serial cursor for one finite expanded request. */
	public record RunSequence(@Nullable ProductionRun currentRun, long remainingRuns) {
		public RunSequence {
			if (remainingRuns < 0)
				throw new IllegalArgumentException("Remaining Run count must be non-negative");
			if ((remainingRuns == 0) != (currentRun == null))
				throw new IllegalArgumentException("Only a non-empty sequence has a current Run");
			if (currentRun != null && currentRun.inputMode() == InputMode.EXACT
				&& currentRun.runMultiplier() != 1)
				throw new IllegalArgumentException("Exact sequences require unit Runs");
			if (currentRun != null && currentRun.inputMode() != InputMode.EXACT
				&& remainingRuns != 1)
				throw new IllegalArgumentException("Bulk and partial sequences contain one Run");
		}

		public static RunSequence completed() {
			return new RunSequence(null, 0);
		}

		/** Advances exactly one Run, and only after the currently frozen Run has sealed output. */
		public RunSequence afterOutputSealed(ProductionRun sealedRun) {
			if (currentRun == null)
				throw new IllegalStateException("Run sequence is complete");
			if (!currentRun.equals(Objects.requireNonNull(sealedRun, "sealedRun")))
				throw new IllegalArgumentException("Only the current Run may advance this sequence");
			if (remainingRuns == 1) return completed();
			ProductionRun next = newRun(currentRun.goalId(), currentRun.patternId(), currentRun.fence(),
				1, currentRun.inputMode(), currentRun.inputRoutes(), currentRun.returnWarehouseRoute(),
				currentRun.outputPolicyGeneration(), currentRun.manifest());
			return new RunSequence(next, Math.subtractExact(remainingRuns, 1));
		}
	}
}
