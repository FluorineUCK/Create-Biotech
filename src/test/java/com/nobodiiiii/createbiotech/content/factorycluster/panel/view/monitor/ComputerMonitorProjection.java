package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.monitor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One immutable monitor response produced from exactly one combined monitor view revision. */
public record ComputerMonitorProjection(UUID clusterId, UUID epochId, long revision, Status status,
	int offset, int totalComputers, List<ComputerCardProjection> computers,
	Optional<UUID> selectedComputerId, int slotOffset, int totalSlots,
	List<ComputerSlotProjection> slots,
	Optional<UUID> selectedProcessId, Optional<GoalTreeProjection> goalTree, int rowLimit) {
	public static final int MAX_WIRE_ROWS = 65_536;

	public ComputerMonitorProjection {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		epochId = Objects.requireNonNull(epochId, "epochId");
		status = Objects.requireNonNull(status, "status");
		computers = List.copyOf(Objects.requireNonNull(computers, "computers"));
		selectedComputerId = Objects.requireNonNull(selectedComputerId, "selectedComputerId");
		slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
		selectedProcessId = Objects.requireNonNull(selectedProcessId, "selectedProcessId");
		goalTree = Objects.requireNonNull(goalTree, "goalTree");
		if (revision < 0 || offset < 0 || totalComputers < 0 || offset > totalComputers
			|| computers.size() > totalComputers - offset || slotOffset < 0 || totalSlots < 0
			|| slotOffset > totalSlots || slots.size() > totalSlots - slotOffset
			|| rowLimit < 0 || rowLimit > MAX_WIRE_ROWS)
			throw new IllegalArgumentException("invalid Computer monitor bounds");
		for (int index = 0; index < computers.size(); index++) {
			if (index > 0 && computers.get(index - 1).memberId()
				.compareTo(computers.get(index).memberId()) >= 0)
				throw new IllegalArgumentException("Computer cards must be unique and sorted");
		}
		for (int index = 0; index < slots.size(); index++)
			if (slots.get(index).slotIndex() != slotOffset + index)
				throw new IllegalArgumentException("Computer slots must be contiguous and ordered");
		var occupiedProcessIds = new HashSet<UUID>();
		for (ComputerSlotProjection slot : slots)
			if (slot.processId().isPresent()
				&& !occupiedProcessIds.add(slot.processId().orElseThrow()))
				throw new IllegalArgumentException("a process cannot occupy multiple Computer slots");
		int returnedRows = Math.addExact(Math.addExact(computers.size(), slots.size()),
			goalTree.map(GoalTreeProjection::wireRowCount).orElse(0));
		if (returnedRows > rowLimit)
			throw new IllegalArgumentException("Computer monitor rows exceed bound");
		if (selectedComputerId.isEmpty() && (slotOffset != 0 || totalSlots != 0 || !slots.isEmpty()
			|| selectedProcessId.isPresent() || goalTree.isPresent()))
			throw new IllegalArgumentException("Computer detail requires a selected Computer");
		if (goalTree.isPresent() && selectedProcessId.isEmpty())
			throw new IllegalArgumentException("Goal tree requires a selected process");
		if (status == Status.STALE && (offset != 0 || totalComputers != 0 || !computers.isEmpty()
			|| selectedComputerId.isPresent() || slotOffset != 0 || totalSlots != 0 || !slots.isEmpty()
			|| selectedProcessId.isPresent() || goalTree.isPresent()))
			throw new IllegalArgumentException("stale monitor response must not carry mixed rows");
	}

	public int returnedRowCount() {
		return Math.addExact(Math.addExact(computers.size(), slots.size()),
			goalTree.map(GoalTreeProjection::wireRowCount).orElse(0));
	}

	/** A selected engine-legal goal-less process has no owning Goal tree to expand. */
	public boolean goalTreeUnavailable() {
		return selectedProcessId.isPresent() && goalTree.isEmpty();
	}

	public boolean hasMoreComputers() {
		return offset + computers.size() < totalComputers;
	}

	public boolean hasMoreSlots() {
		return slotOffset + slots.size() < totalSlots;
	}

	public static ComputerMonitorProjection stale(UUID clusterId, UUID epochId, long revision,
		int rowLimit) {
		return new ComputerMonitorProjection(clusterId, epochId, revision, Status.STALE, 0, 0,
			List.of(), Optional.empty(), 0, 0, List.of(), Optional.empty(), Optional.empty(), rowLimit);
	}

	public enum Status {
		CURRENT,
		STALE
	}
}
