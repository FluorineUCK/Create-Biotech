package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Revision-fenced Tasks page. STALE pages deliberately carry no Goal cards. */
public record TaskListProjection(UUID clusterId, UUID epochId, long generation, long revision,
	Status status, int offset, int totalTasks, List<TaskCardProjection> tasks,
	Optional<UUID> selectedGoalId, int rowLimit) {
	public static final int MAX_WIRE_ROWS = 65_536;

	public TaskListProjection {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		epochId = Objects.requireNonNull(epochId, "epochId");
		status = Objects.requireNonNull(status, "status");
		tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks"));
		selectedGoalId = Objects.requireNonNull(selectedGoalId, "selectedGoalId");
		if (generation < 0 || revision < 0 || offset < 0 || totalTasks < 0
			|| rowLimit <= 0 || rowLimit > MAX_WIRE_ROWS || tasks.size() > rowLimit
			|| offset + (long) tasks.size() > totalTasks)
			throw new IllegalArgumentException("invalid Task page bounds");
		if (status == Status.STALE && !tasks.isEmpty())
			throw new IllegalArgumentException("stale Task projection has rows");
		HashSet<UUID> identities = new HashSet<>();
		for (TaskCardProjection task : tasks)
			if (!identities.add(task.goalId())) throw new IllegalArgumentException("duplicate Goal card");
		if (selectedGoalId.isPresent() && !identities.contains(selectedGoalId.orElseThrow()))
			throw new IllegalArgumentException("selected Goal is outside this Task page");
	}

	public boolean hasMore() {
		return offset + tasks.size() < totalTasks;
	}

	public enum Status { CURRENT, STALE }
}
