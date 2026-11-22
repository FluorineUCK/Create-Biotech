package com.nobodiiiii.createbiotech.content.biocomputing.application.production.process;

import java.util.Objects;
import java.util.UUID;

public record ChildExecutionRevision(UUID childProcessId, long childGeneration, long revision,
	InputMode previousMode, InputMode effectiveMode, long totalPromise, long immediatelyFeasible,
	long remaining) {
	public ChildExecutionRevision {
		childProcessId = Objects.requireNonNull(childProcessId, "childProcessId");
		if (childGeneration < 0 || revision < 0)
			throw new IllegalArgumentException("Child generation and revision must be non-negative");
		previousMode = Objects.requireNonNull(previousMode, "previousMode");
		effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
		if (totalPromise <= 0 || immediatelyFeasible < 0 || remaining < 0
			|| Math.addExact(immediatelyFeasible, remaining) != totalPromise)
			throw new IllegalArgumentException("Child revision must preserve its total promise");
	}

	public Acknowledgement acknowledgement() {
		return new Acknowledgement(childProcessId, childGeneration, revision);
	}

	public record Acknowledgement(UUID childProcessId, long childGeneration, long revision) {
		public Acknowledgement {
			childProcessId = Objects.requireNonNull(childProcessId, "childProcessId");
			if (childGeneration < 0 || revision < 0)
				throw new IllegalArgumentException("Child generation and revision must be non-negative");
		}
	}
}
