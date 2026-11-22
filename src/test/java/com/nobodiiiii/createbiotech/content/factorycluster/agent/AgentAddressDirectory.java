package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterServiceRole;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService;

/** Bounded active claims plus bounded evidence that a route was previously registered. */
public final class AgentAddressDirectory {
	private static final Comparator<AgentAddressRegistration> DIAGNOSTIC_ORDER =
		Comparator.comparing(AgentAddressRegistration::agentId);
	private final int activeCapacity;
	private final int tombstoneCapacity;
	private final Map<CreateRouteKey, Map<UUID, Claim>> active =
		new LinkedHashMap<>();
	private final Map<ClaimKey, Tombstone> tombstones = new LinkedHashMap<>();
	private final Set<ClaimKey> withdrawalsAwaitingTombstoneCapacity = new HashSet<>();
	private int activeCount;

	public AgentAddressDirectory(int activeCapacity, int tombstoneCapacity) {
		if (activeCapacity <= 0 || tombstoneCapacity <= 0)
			throw new IllegalArgumentException("directory capacities must be positive");
		this.activeCapacity = activeCapacity;
		this.tombstoneCapacity = tombstoneCapacity;
	}

	public PublicationResult publish(AgentAddressRegistration registration) {
		return publish(registration, registration.agentGeneration());
	}

	/** Publication ordering is deliberately independent from membership/reset generation. */
	public PublicationResult publish(AgentAddressRegistration registration,
		long publicationVersion) {
		registration = Objects.requireNonNull(registration, "registration");
		if (publicationVersion < 0)
			throw new IllegalArgumentException("Publication version cannot be negative");
		ClaimKey key = new ClaimKey(registration.route(), registration.agentId());
		Map<UUID, Claim> routeClaims = active.get(registration.route());
		Claim previous = routeClaims == null ? null
			: routeClaims.get(registration.agentId());
		if (previous != null && withdrawalsAwaitingTombstoneCapacity.contains(key)
			&& publicationVersion <= previous.publicationVersion())
			return PublicationResult.STALE;
		if (previous != null && publicationVersion < previous.publicationVersion())
			return PublicationResult.STALE;
		if (previous != null && publicationVersion == previous.publicationVersion())
			return previous.registration().equals(registration)
				? PublicationResult.ACCEPTED : PublicationResult.STALE;
		Tombstone tombstone = tombstones.get(key);
		if (tombstone != null && publicationVersion <= tombstone.publicationVersion())
			return PublicationResult.STALE;
		if (previous == null && activeCount >= activeCapacity)
			return PublicationResult.CAPACITY_FULL;

		if (routeClaims == null) {
			routeClaims = new LinkedHashMap<>();
			active.put(registration.route(), routeClaims);
		}
		routeClaims.put(registration.agentId(), new Claim(registration, publicationVersion));
		if (previous == null)
			activeCount++;
		if (tombstone != null)
			tombstones.remove(key);
		withdrawalsAwaitingTombstoneCapacity.remove(key);
		return PublicationResult.ACCEPTED;
	}

	/** Reprojects a declared Cluster Agent registration onto its durable primary frequency. */
	public PublicationResult publish(AgentAddressRegistration registration,
		ClusterCommunicationConfiguration declaration, long publicationVersion) {
		registration = Objects.requireNonNull(registration, "registration");
		declaration = Objects.requireNonNull(declaration, "declaration");
		if (declaration.role() != ClusterServiceRole.AGENT
			|| !registration.ownerClusterId().equals(declaration.clusterId()))
			throw new IllegalArgumentException("Registration differs from declared Agent Cluster");
		return publish(new AgentAddressRegistration(registration.route(), registration.agentId(),
			registration.ownerClusterId(), registration.agentGeneration(),
			declaration.primaryFrequency(), registration.loadState(), registration.reachability()),
			publicationVersion);
	}

	public PublicationResult withdraw(CreateRouteKey route, UUID agentId, long generation) {
		return withdraw(route, agentId, generation, generation);
	}

	public PublicationResult withdraw(CreateRouteKey route, UUID agentId,
		long membershipGeneration, long publicationVersion) {
		route = Objects.requireNonNull(route, "route");
		agentId = Objects.requireNonNull(agentId, "agentId");
		if (membershipGeneration < 0 || publicationVersion < 0)
			throw new IllegalArgumentException("Generations cannot be negative");
		Map<UUID, Claim> routeClaims = active.get(route);
		Claim claim = routeClaims == null ? null : routeClaims.get(agentId);
		ClaimKey key = new ClaimKey(route, agentId);
		Tombstone oldTombstone = tombstones.get(key);
		if (claim == null) {
			if (oldTombstone == null)
				return PublicationResult.NOT_FOUND;
			if (publicationVersion < oldTombstone.publicationVersion())
				return PublicationResult.STALE;
			tombstones.put(key, new Tombstone(membershipGeneration, publicationVersion));
			return PublicationResult.ACCEPTED;
		}
		AgentAddressRegistration registration = claim.registration();
		if (publicationVersion < claim.publicationVersion())
			return PublicationResult.STALE;
		if (oldTombstone == null && tombstones.size() >= tombstoneCapacity) {
			routeClaims.put(agentId, new Claim(new AgentAddressRegistration(route, agentId,
				registration.ownerClusterId(), membershipGeneration, registration.frequency(),
				AgentAddressRegistration.LoadState.UNLOADED,
				AgentAddressRegistration.Reachability.UNREACHABLE), publicationVersion));
			withdrawalsAwaitingTombstoneCapacity.add(key);
			return PublicationResult.CAPACITY_FULL;
		}

		routeClaims.remove(agentId);
		activeCount--;
		if (routeClaims.isEmpty())
			active.remove(route);
		tombstones.put(key, new Tombstone(membershipGeneration, publicationVersion));
		withdrawalsAwaitingTombstoneCapacity.remove(key);
		return PublicationResult.ACCEPTED;
	}

	public AgentResolution resolve(CreateRouteKey route, UUID requesterClusterId, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency) {
		route = Objects.requireNonNull(route, "route");
		requesterClusterId = Objects.requireNonNull(requesterClusterId, "requesterClusterId");
		frequency = Objects.requireNonNull(frequency, "frequency");
		List<AgentAddressRegistration> claims = active.getOrDefault(route, Map.of()).values()
			.stream().map(Claim::registration).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		claims.sort(DIAGNOSTIC_ORDER);
		claims = List.copyOf(claims);
		if (claims.size() > 1)
			return new AgentResolution.Conflict(route, claims);
		if (claims.size() == 1) {
			AgentAddressRegistration claim = claims.getFirst();
			if (claim.availableTo(requesterClusterId, frequency))
				return new AgentResolution.Unique(route, claim);
			return new AgentResolution.KnownUnavailable(route, claims,
				claim.agentGeneration());
		}

		long newestGeneration = -1;
		for (Map.Entry<ClaimKey, Tombstone> entry : tombstones.entrySet())
			if (entry.getKey().route().equals(route))
				newestGeneration = Math.max(newestGeneration,
					entry.getValue().membershipGeneration());
		return newestGeneration >= 0
			? new AgentResolution.KnownUnavailable(route, List.of(), newestGeneration)
			: new AgentResolution.NeverRegistered(route);
	}

	public int activeCount() {
		return activeCount;
	}

	public int tombstoneCount() {
		return tombstones.size();
	}

	/**
	 * The subnet boundary may learn only that this public directory can serve Agent work; it never
	 * receives a route, Agent identity, generation, or allocation detail from this projection.
	 */
	public java.util.Optional<SubnetBoundaryService.PublicServiceSummary> publicSubnetSummary() {
		return activeCount == 0 ? java.util.Optional.empty() : java.util.Optional.of(
			SubnetBoundaryService.PublicServiceSummary.of(
				SubnetBoundaryService.PublicService.AGENT));
	}

	public enum PublicationResult { ACCEPTED, STALE, CAPACITY_FULL, NOT_FOUND }

	private record ClaimKey(CreateRouteKey route, UUID agentId) {
		private ClaimKey {
			Objects.requireNonNull(route, "route");
			Objects.requireNonNull(agentId, "agentId");
		}
	}

	private record Claim(AgentAddressRegistration registration, long publicationVersion) {}
	private record Tombstone(long membershipGeneration, long publicationVersion) {}
}
