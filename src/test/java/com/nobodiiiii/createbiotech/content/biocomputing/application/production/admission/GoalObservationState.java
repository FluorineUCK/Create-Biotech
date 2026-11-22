package com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import net.minecraft.core.registries.BuiltInRegistries;

public record GoalObservationState(UUID goalId, UUID clusterId,
	StockObservationScope scope, long baselineCount, long targetCount, long creditedCount,
	long confirmedOutputCount, Optional<UUID> lastReceivedRunId) {
	public GoalObservationState {
		goalId = Objects.requireNonNull(goalId, "goalId");
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		scope = Objects.requireNonNull(scope, "scope");
		lastReceivedRunId = Objects.requireNonNull(lastReceivedRunId, "lastReceivedRunId");
		long absoluteTarget = switch (scope) {
			case StockObservationScope.NetworkSummary network -> network.targetLevel();
			case StockObservationScope.AttachedInventory inventory -> inventory.targetLevel();
		};
		if (baselineCount < 0 || targetCount <= 0 || confirmedOutputCount < 0
			|| confirmedOutputCount > creditedCount || creditedCount > targetCount)
			throw new IllegalArgumentException("Invalid Goal observation evidence");
		if (absoluteTarget != Math.addExact(baselineCount, targetCount))
			throw new IllegalArgumentException("Goal scope target is not baseline plus request");
	}

	public GoalObservationState withActualCount(long actualCount) {
		if (actualCount < 0) throw new IllegalArgumentException("Actual stock cannot be negative");
		long delta = actualCount <= baselineCount ? 0 : actualCount - baselineCount;
		return new GoalObservationState(goalId, clusterId, scope, baselineCount, targetCount,
			Math.max(confirmedOutputCount, Math.min(targetCount, delta)),
			confirmedOutputCount, lastReceivedRunId);
	}

	public GoalObservationState withNewlyConfirmedOutput(UUID runId, long count) {
		runId = Objects.requireNonNull(runId, "runId");
		if (count < 0)
			throw new IllegalArgumentException("Newly confirmed output cannot be negative");
		if (count == 0) return new GoalObservationState(goalId, clusterId, scope, baselineCount,
			targetCount, creditedCount, confirmedOutputCount, Optional.of(runId));
		long nextConfirmed = Math.addExact(confirmedOutputCount, count);
		if (nextConfirmed > targetCount)
			throw new IllegalArgumentException("Confirmed output exceeds the Goal target");
		return new GoalObservationState(goalId, clusterId, scope, baselineCount, targetCount,
			Math.max(creditedCount, nextConfirmed), nextConfirmed, Optional.of(runId));
	}

	public StackKey item() {
		return switch (scope) {
			case StockObservationScope.NetworkSummary network -> network.item();
			case StockObservationScope.AttachedInventory inventory -> inventory.item();
		};
	}

	public String itemKey() {
		return BuiltInRegistries.ITEM.getKey(item().stack().getItem()).toString();
	}
}
