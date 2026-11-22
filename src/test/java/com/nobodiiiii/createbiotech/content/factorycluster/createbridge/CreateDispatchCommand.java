package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Immutable command exposed only after its Agent has durably recorded DISPATCH_INTENT. */
public record CreateDispatchCommand(UUID intentId, UUID agentId, long agentResetGeneration,
	long fence, UUID runId, UUID outputObservationEpoch, int stageIndex, CreateRouteKey route,
	long sourceInventorySnapshotGeneration, Map<StackKey, Long> manifest)
	implements CreateLogisticsGateway.DispatchRequest {
	public CreateDispatchCommand {
		intentId = Objects.requireNonNull(intentId, "intentId");
		agentId = Objects.requireNonNull(agentId, "agentId");
		runId = Objects.requireNonNull(runId, "runId");
		outputObservationEpoch = Objects.requireNonNull(outputObservationEpoch,
			"outputObservationEpoch");
		route = Objects.requireNonNull(route, "route");
		if (agentResetGeneration < 0 || fence < 0 || stageIndex < 0
			|| sourceInventorySnapshotGeneration < 0)
			throw new IllegalArgumentException("Create dispatch fence values cannot be negative");
		manifest = immutablePositiveManifest(manifest);
	}

	public CreateDispatchCommand withFence(long nextFence) {
		return new CreateDispatchCommand(intentId, agentId, agentResetGeneration, nextFence, runId,
			outputObservationEpoch, stageIndex, route, sourceInventorySnapshotGeneration, manifest);
	}

	public CreateDispatchCommand withSourceInventorySnapshotGeneration(long generation) {
		return new CreateDispatchCommand(intentId, agentId, agentResetGeneration, fence, runId,
			outputObservationEpoch, stageIndex, route, generation, manifest);
	}

	public CreateDispatchCommand withIntentId(UUID nextIntentId) {
		return new CreateDispatchCommand(nextIntentId, agentId, agentResetGeneration, fence, runId,
			outputObservationEpoch, stageIndex, route, sourceInventorySnapshotGeneration, manifest);
	}

	private static Map<StackKey, Long> immutablePositiveManifest(Map<StackKey, Long> values) {
		values = Map.copyOf(Objects.requireNonNull(values, "manifest"));
		if (values.isEmpty()) throw new IllegalArgumentException("Create dispatch needs material");
		for (Map.Entry<StackKey, Long> entry : values.entrySet()) {
			Objects.requireNonNull(entry.getKey(), "manifest key");
			if (entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException("Manifest quantities must be positive");
		}
		return values;
	}
}
