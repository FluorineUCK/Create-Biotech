package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable view of one effective-priority query wave. */
public record LibrarySearchWave(int priority, List<UUID> libraryIds,
	Set<UUID> awaitingGroupEnd, List<UUID> waitingLibraries) {
	public LibrarySearchWave {
		libraryIds = List.copyOf(Objects.requireNonNull(libraryIds, "libraryIds"));
		awaitingGroupEnd = Set.copyOf(Objects.requireNonNull(awaitingGroupEnd,
			"awaitingGroupEnd"));
		waitingLibraries = List.copyOf(Objects.requireNonNull(waitingLibraries,
			"waitingLibraries"));
		if (!new LinkedHashSet<>(libraryIds).containsAll(awaitingGroupEnd)
			|| !new LinkedHashSet<>(libraryIds).containsAll(waitingLibraries))
			throw new IllegalArgumentException("Wave state must reference selected Libraries");
	}

	static LibrarySearchWave snapshot(int priority, List<UUID> libraryIds,
		Set<UUID> awaiting, Set<UUID> waiting) {
		List<UUID> stableWaiting = new ArrayList<>(waiting);
		stableWaiting.sort(UUID::compareTo);
		return new LibrarySearchWave(priority, libraryIds, awaiting, stableWaiting);
	}
}
