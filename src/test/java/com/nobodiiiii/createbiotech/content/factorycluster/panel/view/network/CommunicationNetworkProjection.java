package com.nobodiiiii.createbiotech.content.factorycluster.panel.view.network;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** One bounded page from one exact reachable-communication publication. */
public record CommunicationNetworkProjection(UUID clusterId, UUID epochId, long generation,
	long revision, Status status, int offset, int totalCards,
	List<ClusterCardProjection> cards, int rowLimit,
	Optional<LocalCommunicationEditProjection> localEdits, CommunicationPage page) {
	public static final int MAX_WIRE_ROWS = com.nobodiiiii.createbiotech.content.biocomputing.application.production
		.FactoryStateRepresentationBounds.MAX_PANEL_ROWS;

	public CommunicationNetworkProjection(UUID clusterId, UUID epochId, long generation,
		long revision, Status status, int offset, int totalCards, List<ClusterCardProjection> cards,
		int rowLimit, Optional<LocalCommunicationEditProjection> localEdits) {
		this(clusterId, epochId, generation, revision, status, offset, totalCards, cards, rowLimit,
			localEdits, CommunicationPage.OVERVIEW);
	}

	public CommunicationNetworkProjection {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		epochId = Objects.requireNonNull(epochId, "epochId");
		status = Objects.requireNonNull(status, "status");
		page = Objects.requireNonNull(page, "page");
		cards = List.copyOf(Objects.requireNonNull(cards, "cards"));
		localEdits = Objects.requireNonNull(localEdits, "local edits");
		int editorRows = localEdits.map(edits -> edits.listenerEditors().size()).orElse(0);
		if (generation < 0 || revision < 0 || offset < 0 || totalCards < 0
			|| rowLimit <= 0 || rowLimit > MAX_WIRE_ROWS
			|| Math.addExact(cards.size(), editorRows) > rowLimit)
			throw new IllegalArgumentException("invalid communication projection bounds");
		int pinned = page == CommunicationPage.OVERVIEW ? 1 : 0;
		if (status == Status.CURRENT && (totalCards < 1 || (page == CommunicationPage.LISTENERS
			? !cards.isEmpty() || offset > localEdits.orElseThrow().totalListeners()
				|| editorRows > localEdits.orElseThrow().totalListeners() - offset
			: offset > totalCards - 1 || cards.size() < pinned
				|| cards.size() - pinned > totalCards - 1 - offset
				|| page == CommunicationPage.REMOTE && editorRows != 0)))
			throw new IllegalArgumentException("invalid communication lane bounds");
		Set<String> identities = new HashSet<>();
		int localCards = 0;
		String expectedLocalId = ClusterCardProjection.shortId(clusterId);
		for (ClusterCardProjection card : cards) {
			if (!identities.add(card.clusterId()))
				throw new IllegalArgumentException("duplicate communication card identity");
			if (!card.remote()) {
				localCards++;
				if (card.subnetBoundary() || !card.clusterId().equals(expectedLocalId)
					|| card.linkType() != ClusterCardProjection.LinkType.ADJACENT)
					throw new IllegalArgumentException("invalid local communication card");
			} else if (!card.capabilities().equals(Set.of(ClusterCardProjection.Capability.VIEW)))
				throw new IllegalArgumentException("remote communication card is editable");
		}
		if (status == Status.CURRENT && localCards != pinned)
			throw new IllegalArgumentException("current communication page has wrong local card count");
		if (status == Status.CURRENT && localEdits.isEmpty())
			throw new IllegalArgumentException("current communication page lacks local edit state");
		if (status == Status.STALE && (offset != 0 || totalCards != 0 || !cards.isEmpty()
			|| localEdits.isPresent()))
			throw new IllegalArgumentException("stale communication projection carries rows");
	}

	public boolean hasMore() {
		return status == Status.CURRENT
			&& (page == CommunicationPage.LISTENERS
			? offset + localEdits.orElseThrow().listenerEditors().size() < localEdits.orElseThrow().totalListeners()
			: offset + cards.size() - (page == CommunicationPage.OVERVIEW ? 1 : 0) < totalCards - 1);
	}

	public static CommunicationNetworkProjection stale(UUID clusterId, UUID epochId,
		long generation, long revision, int rowLimit) {
		return new CommunicationNetworkProjection(clusterId, epochId, generation, revision,
			Status.STALE, 0, 0, List.of(), rowLimit, Optional.empty());
	}

	public enum Status { CURRENT, STALE }
}
