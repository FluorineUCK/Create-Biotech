package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.Objects;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeSourceLocation;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/** Production discovery facts only; the source position cannot resolve or mutate a world target. */
public record LibraryRegistration(UUID libraryId, UUID clusterId, RecipeSourceLocation address,
	long formationGeneration, CommunicationFrequency frequency, int priority, long publicCatalogGeneration,
	LoadState loadState, Reachability reachability) {
	public LibraryRegistration {
		libraryId = Objects.requireNonNull(libraryId, "libraryId");
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		address = Objects.requireNonNull(address, "address");
		frequency = Objects.requireNonNull(frequency, "frequency");
		if (formationGeneration < 0 || publicCatalogGeneration < 0)
			throw new IllegalArgumentException("Library generations cannot be negative");
		loadState = Objects.requireNonNull(loadState, "loadState");
		reachability = Objects.requireNonNull(reachability, "reachability");
	}

	public LibraryRegistration withLoadState(LoadState next) {
		return new LibraryRegistration(libraryId, clusterId, address, formationGeneration,
			frequency, priority, publicCatalogGeneration, next, reachability);
	}

	public boolean visibleTo(UUID requesterClusterId, CommunicationFrequency requesterFrequency) {
		return clusterId.equals(requesterClusterId)
			|| requesterFrequency.configured() && frequency.equals(requesterFrequency);
	}

	public boolean queryableBy(UUID requesterClusterId) {
		return loadState == LoadState.LOADED && clusterId.equals(requesterClusterId);
	}

	public boolean queryableBy(UUID requesterClusterId, CommunicationFrequency requesterFrequency) {
		return loadState == LoadState.LOADED && (clusterId.equals(requesterClusterId)
			|| requesterFrequency.configured() && frequency.equals(requesterFrequency)
				&& reachability == Reachability.REACHABLE);
	}

	public enum LoadState { LOADED, UNLOADED }
	public enum Reachability { REACHABLE, UNREACHABLE }
}
