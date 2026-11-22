package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.model.GoalState;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

/** One bounded, immutable Tasks-view Goal card. */
public record TaskCardProjection(UUID goalId, StackKey output, long total, long done,
	long remaining, InputMode inputMode, GoalState state, int priority,
	Optional<WaitReason> latestWaitReason, Set<Action> allowedActions) {
	public TaskCardProjection {
		goalId = Objects.requireNonNull(goalId, "goalId");
		output = Objects.requireNonNull(output, "output");
		inputMode = Objects.requireNonNull(inputMode, "inputMode");
		state = Objects.requireNonNull(state, "state");
		latestWaitReason = Objects.requireNonNull(latestWaitReason, "latestWaitReason");
		allowedActions = Set.copyOf(Objects.requireNonNull(allowedActions, "allowedActions"));
		if (total < 0 || done < 0 || remaining < 0 || done > total || remaining != total - done)
			throw new IllegalArgumentException("invalid exact Task counts");
	}

	public enum Action {
		CHANGE_PRIORITY, CHANGE_MODE, RESUME, CANCEL, VIEW_COMPUTATION
	}

	static Set<Action> fromMask(int mask) {
		EnumSet<Action> actions = EnumSet.noneOf(Action.class);
		for (Action action : Action.values())
			if ((mask & 1 << action.ordinal()) != 0) actions.add(action);
		if ((mask >>> Action.values().length) != 0)
			throw new IllegalArgumentException("unknown Task action bit");
		return actions;
	}

	int actionMask() {
		int mask = 0;
		for (Action action : allowedActions) mask |= 1 << action.ordinal();
		return mask;
	}
}
