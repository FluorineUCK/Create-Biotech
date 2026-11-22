package com.nobodiiiii.createbiotech.content.biocomputing.application.production.run;

import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun.immutablePositiveManifest;
import static com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun.newRun;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.run.ProductionRun.RunSequence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;

/** Finite material planning policy, not an executable program or a Computer resource pool. */
public final class ProductionRunExpansion {
    private ProductionRunExpansion() {}

	/**
	 * Creates a finite cursor. EXACT stores only its current Run and remaining count; the next
	 * Run becomes available only by advancing this cursor after the current output has sealed.
	 */
	public static RunSequence expand(UUID goalId, UUID patternId, long fence,
		long requestedMultiplier, InputMode inputMode, List<CreateRouteKey> inputRoutes,
		CreateRouteKey returnWarehouseRoute, long outputPolicyGeneration,
		Map<StackKey, Long> unitManifest, Map<StackKey, Long> availableManifest) {
		goalId = Objects.requireNonNull(goalId, "goalId");
		patternId = Objects.requireNonNull(patternId, "patternId");
		inputMode = Objects.requireNonNull(inputMode, "inputMode");
		if (requestedMultiplier <= 0)
			throw new IllegalArgumentException("Requested multiplier must be positive");
		Map<StackKey, Long> units = immutablePositiveManifest(unitManifest);
		long effectiveMultiplier = switch (inputMode) {
			case BULK, EXACT -> requestedMultiplier;
			case PARTIAL -> Math.min(requestedMultiplier,
				completeFeasibleOperations(units, availableManifest));
		};
		if (effectiveMultiplier == 0) return RunSequence.completed();
		if (inputMode == InputMode.EXACT)
			return new RunSequence(newRun(goalId, patternId, fence, 1, inputMode, inputRoutes,
				returnWarehouseRoute, outputPolicyGeneration, units), effectiveMultiplier);
		return new RunSequence(newRun(goalId, patternId, fence, effectiveMultiplier, inputMode,
			inputRoutes, returnWarehouseRoute, outputPolicyGeneration,
			multiplyManifest(units, effectiveMultiplier)), 1);
	}

	private static long completeFeasibleOperations(Map<StackKey, Long> units,
		Map<StackKey, Long> availableManifest) {
		availableManifest = Map.copyOf(Objects.requireNonNull(availableManifest,
			"availableManifest"));
		long feasible = Long.MAX_VALUE;
		for (Map.Entry<StackKey, Long> unit : units.entrySet()) {
			Long available = availableManifest.get(unit.getKey());
			if (available == null || available < 0)
				return 0;
			feasible = Math.min(feasible, available / unit.getValue());
		}
		return feasible;
	}

	private static Map<StackKey, Long> multiplyManifest(Map<StackKey, Long> units,
		long multiplier) {
		Map<StackKey, Long> multiplied = new HashMap<>();
		for (Map.Entry<StackKey, Long> unit : units.entrySet()) {
			long quantity = Math.multiplyExact(unit.getValue(), multiplier);
			multiplied.merge(unit.getKey(), quantity, Math::addExact);
		}
		return Map.copyOf(multiplied);
	}

}
