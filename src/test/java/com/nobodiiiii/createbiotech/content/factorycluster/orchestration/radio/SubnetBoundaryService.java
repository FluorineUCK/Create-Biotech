package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.search.LibraryDirectory;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/**
 * A non-merging subnet gateway. Its external representation is one opaque card: only explicit
 * public service capabilities cross this boundary, never members, slots, topology, pages, or
 * Create addresses.
 */
public final class SubnetBoundaryService {
	private final UUID internalNetworkId;
	private final UUID owningClusterId;
	private final SubnetBoundaryProjection projection;

	public SubnetBoundaryService(UUID internalNetworkId, UUID owningClusterId,
		CommunicationFrequency listenerFrequency, List<PublicServiceSummary> publicServices) {
		this.internalNetworkId = Objects.requireNonNull(internalNetworkId, "internalNetworkId");
		this.owningClusterId = Objects.requireNonNull(owningClusterId, "owningClusterId");
		listenerFrequency = Objects.requireNonNull(listenerFrequency, "listenerFrequency");
		if (!listenerFrequency.configured())
			throw new IllegalArgumentException("a subnet boundary needs a configured listener frequency");
		publicServices = List.copyOf(Objects.requireNonNull(publicServices, "publicServices"));
		if (publicServices.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException("public subnet services exceed the representation bound");
		Map<PublicService, PublicServiceSummary> byKind = new LinkedHashMap<>();
		for (PublicServiceSummary summary : publicServices) {
			summary = Objects.requireNonNull(summary, "public service summary");
			if (byKind.putIfAbsent(summary.kind(), summary) != null)
				throw new IllegalArgumentException("a public service may appear once on a boundary");
		}
		this.projection = new SubnetBoundaryProjection(List.of(new BoundaryCard(listenerFrequency,
			List.copyOf(byKind.values()))));
	}

	/**
	 * Evaluates a network-only preflight when the destination Cluster is not yet known. It never
	 * reserves a route; both trace representations must still have capacity before callers may
	 * resolve the final Cluster through the authoritative router binding.
	 */
	public RouteDecision forward(SubnetRouteTrace trace, UUID targetNetworkId) {
		trace = Objects.requireNonNull(trace, "trace");
		targetNetworkId = Objects.requireNonNull(targetNetworkId, "targetNetworkId");
		if (!trace.hasVisitedNetwork(internalNetworkId)
			|| !trace.hasVisitedCluster(owningClusterId)) return RouteDecision.LOOP;
		if (trace.hasVisitedNetwork(targetNetworkId)) return RouteDecision.LOOP;
		return trace.visitedNetworks().size() >= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
			|| trace.visitedClusters().size() >= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
			? RouteDecision.CAPACITY : RouteDecision.FORWARDED;
	}

	/** Rejects either network or Cluster re-entry before the caller forwards the message. */
	public RouteDecision forward(SubnetRouteTrace trace, UUID targetNetworkId,
		UUID targetClusterId) {
		trace = Objects.requireNonNull(trace, "trace");
		targetNetworkId = Objects.requireNonNull(targetNetworkId, "targetNetworkId");
		targetClusterId = Objects.requireNonNull(targetClusterId, "targetClusterId");
		if (!trace.hasVisitedNetwork(internalNetworkId)
			|| !trace.hasVisitedCluster(owningClusterId)) return RouteDecision.LOOP;
		if (trace.hasVisitedNetwork(targetNetworkId) || trace.hasVisitedCluster(targetClusterId))
			return RouteDecision.LOOP;
		return trace.canVisit(targetNetworkId, targetClusterId)
			? RouteDecision.FORWARDED : RouteDecision.CAPACITY;
	}

	/** Supplies immutable next-hop trace evidence only after the route passes its loop check. */
	public Optional<SubnetRouteTrace> extend(SubnetRouteTrace trace, UUID targetNetworkId,
		UUID targetClusterId) {
		return forward(trace, targetNetworkId, targetClusterId) == RouteDecision.FORWARDED
			? Optional.of(trace.visit(targetNetworkId, targetClusterId)) : Optional.empty();
	}

	public SubnetBoundaryProjection project() {
		return projection;
	}

	/** Old radio projection owns only an opaque capability marker, never Library facts. */
	public static Optional<PublicServiceSummary> librarySummary(LibraryDirectory directory) {
		return Objects.requireNonNull(directory, "directory").hasRegistrations()
			? Optional.of(PublicServiceSummary.of(PublicService.LIBRARY)) : Optional.empty();
	}

	public enum RouteDecision { FORWARDED, LOOP, CAPACITY }

	/** The complete finite capability vocabulary that may be published outside a subnet. */
	public enum PublicService { AGENT, LIBRARY, TASK_ENTRY, LOGISTICS_METADATA }

	/**
	 * Opaque public-service marker. It intentionally carries no agent/member identity, page,
	 * route, slot count, topology, or Create address. Logistics remains a capability marker only;
	 * its concrete directory projection belongs to the later logistics slice.
	 */
	public record PublicServiceSummary(PublicService kind) {
		public PublicServiceSummary {
			kind = Objects.requireNonNull(kind, "kind");
		}

		public static PublicServiceSummary of(PublicService kind) {
			return new PublicServiceSummary(kind);
		}
	}

	/** Immutable external view. Exactly one card replaces all subnet internals. */
	public record SubnetBoundaryProjection(List<BoundaryCard> cards) {
		public SubnetBoundaryProjection {
			cards = List.copyOf(Objects.requireNonNull(cards, "cards"));
			if (cards.size() != 1)
				throw new IllegalArgumentException("a subnet projection must expose exactly one card");
		}

		public Set<PublicService> serviceKinds() {
			EnumSet<PublicService> kinds = EnumSet.noneOf(PublicService.class);
			for (PublicServiceSummary summary : cards.getFirst().services())
				kinds.add(summary.kind());
			return Set.copyOf(kinds);
		}

		/** Explicit privacy seam retained for consumers that otherwise render member cards. */
		public List<UUID> computers() {
			return List.of();
		}
	}

	/** The single external card contains only listener frequency and opaque authorized summaries. */
	public record BoundaryCard(CommunicationFrequency listenerFrequency,
		List<PublicServiceSummary> services) {
		public BoundaryCard {
			listenerFrequency = Objects.requireNonNull(listenerFrequency, "listenerFrequency");
			if (!listenerFrequency.configured())
				throw new IllegalArgumentException("boundary cards need a configured listener frequency");
			services = List.copyOf(Objects.requireNonNull(services, "services"));
			if (services.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
				throw new IllegalArgumentException("public subnet services exceed the representation bound");
			Set<PublicService> kinds = EnumSet.noneOf(PublicService.class);
			for (PublicServiceSummary summary : services)
				if (!kinds.add(Objects.requireNonNull(summary, "public service summary").kind()))
					throw new IllegalArgumentException("a boundary card cannot duplicate a service");
		}
	}
}
