package com.nobodiiiii.createbiotech.content.biocomputing.application.production.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/** Generation-aware directory of peer-equal Libraries; it assigns no special role. */
public final class LibraryDirectory {
	private static final Comparator<LibraryRegistration> ORDER = Comparator
		.comparingInt(LibraryRegistration::priority).reversed()
		.thenComparing(LibraryRegistration::libraryId);
	private final Map<UUID, LibraryRegistration> registrations = new LinkedHashMap<>();
	private final Map<UUID, Long> publicationRevisions = new LinkedHashMap<>();
	private long nextPublicationRevision = 1;

	public boolean publish(LibraryRegistration registration) {
		registration = Objects.requireNonNull(registration, "registration");
		LibraryRegistration previous = registrations.get(registration.libraryId());
		if (previous != null && compareGeneration(registration, previous) < 0)
			return false;
		registrations.put(registration.libraryId(), registration);
		publicationRevisions.put(registration.libraryId(), nextPublicationRevision++);
		return true;
	}

	public long publicationRevision(UUID libraryId) {
		return publicationRevisions.getOrDefault(Objects.requireNonNull(libraryId), 0L);
	}

	public Optional<LibraryRegistration> find(UUID libraryId) {
		return Optional.ofNullable(registrations.get(Objects.requireNonNull(libraryId,
			"libraryId")));
	}

	public List<LibraryRegistration> visibleTo(UUID requesterClusterId, CommunicationFrequency frequency) {
		requesterClusterId = Objects.requireNonNull(requesterClusterId, "requesterClusterId");
		frequency = Objects.requireNonNull(frequency, "frequency");
		List<LibraryRegistration> visible = new ArrayList<>();
		for (LibraryRegistration registration : registrations.values())
			if (registration.visibleTo(requesterClusterId, frequency))
				visible.add(registration);
		visible.sort(ORDER);
		return List.copyOf(visible);
	}

	/**
	 * Returns every explicitly published peer in stable priority order.  A known peer that is
	 * currently off-frequency, unloaded, or unreachable must remain in a search wave so the caller
	 * waits for that exact route instead of silently falling through to a lower-priority Library.
	 */
	public List<LibraryRegistration> knownPeers() {
		List<LibraryRegistration> known = new ArrayList<>(registrations.values());
		known.sort(ORDER);
		return List.copyOf(known);
	}

	/** Presence only, suitable for a protocol adapter without exposing the directory. */
	public boolean hasRegistrations() { return !registrations.isEmpty(); }

	private static int compareGeneration(LibraryRegistration left,
		LibraryRegistration right) {
		int formation = Long.compare(left.formationGeneration(), right.formationGeneration());
		return formation != 0 ? formation
			: Long.compare(left.publicCatalogGeneration(), right.publicCatalogGeneration());
	}
}
