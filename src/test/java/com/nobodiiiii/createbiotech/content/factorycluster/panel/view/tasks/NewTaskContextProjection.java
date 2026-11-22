package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentNewTaskDefaults;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Independent, semantically closed New Task form context. */
public record NewTaskContextProjection(UUID clusterId, UUID epochId, long generation,
	long revision, Status status, Optional<Defaults> defaults, Optional<Prefill> prefill) {
	public NewTaskContextProjection {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		epochId = Objects.requireNonNull(epochId, "epochId");
		status = Objects.requireNonNull(status, "status");
		defaults = Objects.requireNonNull(defaults, "defaults");
		prefill = Objects.requireNonNull(prefill, "prefill");
		if (generation < 0 || revision < 0)
			throw new IllegalArgumentException("invalid New Task context fence");
		if (status != Status.CURRENT && (defaults.isPresent() || prefill.isPresent()))
			throw new IllegalArgumentException("stale New Task context carries data");
		if (status == Status.CURRENT && defaults.isEmpty())
			throw new IllegalArgumentException("current New Task context lacks defaults");
		if (prefill.isPresent() && status != Status.CURRENT)
			throw new IllegalArgumentException("prefill requires current defaults");
		if (defaults.isPresent() && !clusterId.equals(defaults.orElseThrow().value().ownerClusterId()))
			throw new IllegalArgumentException("New Task defaults have foreign owner");
	}

	public static NewTaskContextProjection currentPlain(UUID clusterId, UUID epochId,
		long generation, long revision, AgentNewTaskDefaults defaults) {
		return new NewTaskContextProjection(clusterId, epochId, generation, revision, Status.CURRENT,
			Optional.of(new Defaults(defaults)), Optional.empty());
	}

	public static NewTaskContextProjection currentContextual(UUID clusterId, UUID epochId,
		long generation, long revision, AgentNewTaskDefaults defaults, Prefill prefill) {
		return new NewTaskContextProjection(clusterId, epochId, generation, revision, Status.CURRENT,
			Optional.of(new Defaults(defaults)), Optional.of(prefill));
	}

	public static NewTaskContextProjection stale(UUID clusterId, UUID epochId, long generation,
		long revision) {
		return new NewTaskContextProjection(clusterId, epochId, generation, revision, Status.STALE,
			Optional.empty(), Optional.empty());
	}

	public record Defaults(AgentNewTaskDefaults value) {
		public Defaults { value = Objects.requireNonNull(value, "value"); }
	}

	public record Prefill(UUID parentGoalId, StackKey output, long multiplier, int priority,
		Optional<CreateRouteKey> recommendedInput) {
		public Prefill {
			parentGoalId = Objects.requireNonNull(parentGoalId, "parentGoalId");
			output = Objects.requireNonNull(output, "output");
			recommendedInput = Objects.requireNonNull(recommendedInput, "recommendedInput");
			if (multiplier <= 0) throw new IllegalArgumentException("invalid prefill multiplier");
		}
	}

	public enum Status { CURRENT, STALE, NO_INPUT, INCOMPLETE_AGENT }
}
