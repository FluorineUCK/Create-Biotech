package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessRecord;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessState;

/** Authoritative, pure Goal transition policy shared by runtime and Factory Panel views. */
public final class FactoryGoalTransitionPolicy {
	private FactoryGoalTransitionPolicy() {}

	public enum Action { CHANGE_PRIORITY, CHANGE_MODE, RESUME, CANCEL, VIEW_COMPUTATION }

	public static Set<Action> allowedActions(ClusterEngineState state, UUID goalId) {
		state = Objects.requireNonNull(state, "state");
		GoalRecord goal = state.goals().get(Objects.requireNonNull(goalId, "goalId"));
		if (goal == null || goal.state() == GoalState.TERMINAL) return Set.of();
		EnumSet<Action> actions = EnumSet.noneOf(Action.class);
		if (goal.policy().generation() != Long.MAX_VALUE) actions.add(Action.CHANGE_PRIORITY);
		if (!hasGoalProcess(state, goalId) && state.pendingIntents().isEmpty())
			actions.add(Action.CHANGE_MODE);
		if (goal.state() == GoalState.WAITING) actions.add(Action.RESUME);
		if (state.pendingIntents().isEmpty()) actions.add(Action.CANCEL);
		if (computationSelection(state, goalId).isPresent()) actions.add(Action.VIEW_COMPUTATION);
		return Set.copyOf(actions);
	}

	public static boolean allows(ClusterEngineState state, UUID goalId, Action action) {
		return allowedActions(state, goalId).contains(Objects.requireNonNull(action, "action"));
	}

	/**
	 * The sole display/command computation identity: exactly one live GOAL process for the Goal.
	 * Other process kinds, terminal processes, and ambiguous duplicate processes are never exposed.
	 */
	public static Optional<UUID> computationSelection(ClusterEngineState state, UUID goalId) {
		state = Objects.requireNonNull(state, "state");
		UUID expectedGoalId = Objects.requireNonNull(goalId, "goalId");
		List<ProcessRecord> matches = state.processes().values().stream()
			.filter(process -> process.goalId().equals(expectedGoalId))
			.filter(process -> process.kind() == ProcessKind.GOAL)
			.filter(process -> process.state() != ProcessState.TERMINAL)
			.sorted(java.util.Comparator.comparing(ProcessRecord::processId)).toList();
		return matches.size() == 1 ? Optional.of(matches.getFirst().processId()) : Optional.empty();
	}

	private static boolean hasGoalProcess(ClusterEngineState state, UUID goalId) {
		return state.processes().values().stream().anyMatch(process -> process.goalId().equals(goalId));
	}
}
