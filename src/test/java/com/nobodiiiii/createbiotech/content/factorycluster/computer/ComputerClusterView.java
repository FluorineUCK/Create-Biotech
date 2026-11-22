package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ComputerClusterView(ComputerAvailabilityReason reason,
	boolean structureOnline, boolean coordinatorOnline, boolean epochActive,
	boolean requiresReform, List<ComputerNodeView> current,
	List<ComputerNodeView> frozen, List<ComputerNodeView> pending,
	Set<EpochFault> faults) {
	public ComputerClusterView {
		Objects.requireNonNull(reason, "reason");
		current = List.copyOf(Objects.requireNonNull(current, "current"));
		frozen = List.copyOf(Objects.requireNonNull(frozen, "frozen"));
		pending = List.copyOf(Objects.requireNonNull(pending, "pending"));
		faults = Set.copyOf(Objects.requireNonNull(faults, "faults"));
	}
}
