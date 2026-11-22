package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.ClusterEngineState;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryGoalTransitionPolicy;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryGoalTransitionPolicy.Action;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelAuthorityValidator;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelOpening;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.menu.FactoryPanelView;
import com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks.TaskProjectionSnapshot.PublishedSnapshot;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** Builds Tasks and New Task exclusively from one current frozen server publication. */
public final class TaskProjectionService {
	private final SnapshotLookup snapshots;
	private final int rowLimit;

	public TaskProjectionService(SnapshotLookup snapshots, int rowLimit) {
		this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
		if (rowLimit <= 0 || rowLimit > TaskListProjection.MAX_WIRE_ROWS)
			throw new IllegalArgumentException("invalid Task row limit");
		this.rowLimit = rowLimit;
	}

	public TaskListProjection projectTasks(FactoryPanelAuthorityValidator.ViewValidation authority) {
		FactoryPanelAuthorityValidator.ViewRequest request = request(authority, FactoryPanelView.TASKS);
		FactoryPanelOpening opening = request.opening();
		PublishedSnapshot snapshot = currentSnapshot(opening).orElse(null);
		if (snapshot == null) return staleTasks(opening);
		ClusterEngineState engine = snapshot.engine();
		if (request.knownRevision().isPresent()
			&& request.knownRevision().orElseThrow() != snapshot.tasksRevision())
			return new TaskListProjection(engine.clusterId(), engine.epochId(), engine.generation(),
				snapshot.tasksRevision(), TaskListProjection.Status.STALE, 0, engine.goals().size(),
				List.of(), Optional.empty(), rowLimit);

		List<GoalRecord> ordered = engine.goals().values().stream()
			.sorted(Comparator.comparingLong(goal -> engine.submissionSequences().get(goal.goalId())))
			.toList();
		int from = Math.min(request.offset(), ordered.size());
		int limit = Math.min(request.limit(), rowLimit);
		int to = Math.min(ordered.size(), Math.addExact(from, limit));
		List<TaskCardProjection> cards = new ArrayList<>(to - from);
		for (GoalRecord goal : ordered.subList(from, to)) {
			GoalDisplay display = snapshot.displays().get(goal.goalId());
			cards.add(new TaskCardProjection(goal.goalId(), display.output(), display.total(),
				display.done(), display.remaining(), goal.submission().inputMode(), goal.state(),
				goal.policy().priority(), display.latestWaitReason(), allowedActions(engine, goal.goalId())));
		}
		Optional<UUID> selected = request.selection().filter(selection -> cards.stream()
			.anyMatch(card -> card.goalId().equals(selection)));
		return new TaskListProjection(engine.clusterId(), engine.epochId(), engine.generation(),
			snapshot.tasksRevision(), TaskListProjection.Status.CURRENT, from, ordered.size(), cards,
			selected, rowLimit);
	}

	public NewTaskContextProjection projectNewTask(
		FactoryPanelAuthorityValidator.ViewValidation authority) {
		FactoryPanelAuthorityValidator.ViewRequest request = request(authority, FactoryPanelView.NEW_TASK);
		FactoryPanelOpening opening = request.opening();
		PublishedSnapshot snapshot = currentSnapshot(opening).orElse(null);
		if (snapshot == null) return NewTaskContextProjection.stale(opening.clusterId(),
			opening.epochId(), 0, 0);
		ClusterEngineState engine = snapshot.engine();
		if (request.knownRevision().isPresent()
			&& request.knownRevision().orElseThrow() != snapshot.newTaskRevision())
			return NewTaskContextProjection.stale(engine.clusterId(), engine.epochId(),
				engine.generation(), snapshot.newTaskRevision());
		if (snapshot.availableDefaults().isEmpty())
			return new NewTaskContextProjection(engine.clusterId(), engine.epochId(), engine.generation(),
				snapshot.newTaskRevision(), snapshot.newTaskStatus(), Optional.empty(), Optional.empty());
		UUID parentId = request.selection().orElse(null);
		GoalRecord parent = parentId == null ? null : engine.goals().get(parentId);
		GoalDisplay display = parentId == null ? null : snapshot.displays().get(parentId);
		if (parent == null || display == null || parent.state() == GoalState.TERMINAL
			|| !display.parentAuthorized())
			return NewTaskContextProjection.currentPlain(engine.clusterId(), engine.epochId(),
				engine.generation(), snapshot.newTaskRevision(), snapshot.defaults());
		long multiplier = Math.max(1, display.remaining() / display.outputPerBatch());
		return NewTaskContextProjection.currentContextual(engine.clusterId(), engine.epochId(),
			engine.generation(), snapshot.newTaskRevision(), snapshot.defaults(),
			new NewTaskContextProjection.Prefill(parentId, display.output(), multiplier,
				parent.policy().priority(), display.recommendedInput()));
	}



	/** UI action mask mapped directly from the runtime's authoritative transition policy. */
	public static Set<TaskCardProjection.Action> allowedActions(ClusterEngineState engine, UUID goalId) {
		EnumSet<TaskCardProjection.Action> actions = EnumSet.noneOf(TaskCardProjection.Action.class);
		for (Action action : FactoryGoalTransitionPolicy.allowedActions(engine, goalId)) {
			switch (action) {
				case CHANGE_PRIORITY -> actions.add(TaskCardProjection.Action.CHANGE_PRIORITY);
				case CHANGE_MODE -> actions.add(TaskCardProjection.Action.CHANGE_MODE);
				case RESUME -> actions.add(TaskCardProjection.Action.RESUME);
				case CANCEL -> actions.add(TaskCardProjection.Action.CANCEL);
				case VIEW_COMPUTATION -> actions.add(TaskCardProjection.Action.VIEW_COMPUTATION);
			}
		}
		return Set.copyOf(actions);
	}

	private FactoryPanelAuthorityValidator.ViewRequest request(
		FactoryPanelAuthorityValidator.ViewValidation authority, FactoryPanelView expected) {
		FactoryPanelAuthorityValidator.ViewRequest request = Objects.requireNonNull(authority,
			"authority").request();
		if (request.view() != expected || request.limit() > rowLimit)
			throw new IllegalArgumentException("wrong Factory Panel projection authority");
		return request;
	}

	private Optional<PublishedSnapshot> currentSnapshot(FactoryPanelOpening opening) {
		PublishedSnapshot snapshot = snapshots.capture(opening.clusterId()).orElse(null);
		if (snapshot == null || !snapshot.isCurrentPublication()) return Optional.empty();
		ClusterEngineState engine = snapshot.engine();
		return engine.clusterId().equals(opening.clusterId()) && engine.epochId().equals(opening.epochId())
			? Optional.of(snapshot) : Optional.empty();
	}

	private TaskListProjection staleTasks(FactoryPanelOpening opening) {
		return new TaskListProjection(opening.clusterId(), opening.epochId(), 0, 0,
			TaskListProjection.Status.STALE, 0, 0, List.of(), Optional.empty(), rowLimit);
	}

	@FunctionalInterface public interface SnapshotLookup {
		Optional<PublishedSnapshot> capture(UUID clusterId);
	}

	public record GoalDisplay(StackKey output, long total, long done, long remaining,
		long outputPerBatch, Optional<WaitReason> latestWaitReason,
		Optional<UUID> computationSelection, boolean parentAuthorized,
		Optional<com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey> recommendedInput) {
		public GoalDisplay {
			output = Objects.requireNonNull(output, "output");
			latestWaitReason = Objects.requireNonNull(latestWaitReason, "latestWaitReason");
			computationSelection = Objects.requireNonNull(computationSelection, "computationSelection");
			recommendedInput = Objects.requireNonNull(recommendedInput, "recommendedInput");
			if (total < 0 || done < 0 || remaining < 0 || done > total
				|| remaining != total - done || outputPerBatch <= 0)
				throw new IllegalArgumentException("invalid exact Goal display counts");
		}
	}
}
