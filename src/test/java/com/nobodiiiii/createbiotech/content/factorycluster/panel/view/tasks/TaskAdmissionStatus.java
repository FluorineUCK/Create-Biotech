package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.tasks;

import java.util.Objects;
import java.util.UUID;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.WorldFact;

/** Status of the exact admission accepted by this menu, not an inferred missing Goal. */
public record TaskAdmissionStatus(UUID goalId, State state, String diagnostic) {
	public enum State { PENDING, PUBLISHED, FAILED }
	public TaskAdmissionStatus {
		Objects.requireNonNull(goalId); Objects.requireNonNull(state); Objects.requireNonNull(diagnostic);
		if (diagnostic.length() > WorldFact.MAX_TEXT_LENGTH)
			throw new IllegalArgumentException("admission diagnostic exceeds wire bound");
	}
}
