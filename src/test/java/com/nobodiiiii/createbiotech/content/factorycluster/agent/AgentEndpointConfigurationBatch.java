package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/**
 * Pure preflight for a formation-wide native endpoint mutation. Structural membership edits
 * advance every surviving member of each affected Agent in one immutable batch; policy edits do
 * not consume a membership generation.
 */
public final class AgentEndpointConfigurationBatch {
	private AgentEndpointConfigurationBatch() {}

	public static Optional<Plan> configure(
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> observed,
		ConfigureRequest request) {
		observed = Map.copyOf(Objects.requireNonNull(observed, "observed"));
		request = Objects.requireNonNull(request, "request");
		if (!coherent(observed)) return Optional.empty();
		FactoryPanelAgentAccess.Configuration prior = observed.get(request.endpoint());
		UUID requestedAgentId = request.agentId();
		UUID requestedOwnerId = request.ownerClusterId();
		UUID requestedEpochId = request.clusterEpochId();
		if (prior != null && (!prior.ownerClusterId().equals(request.ownerClusterId())
			|| !prior.clusterEpochId().equals(request.clusterEpochId()))
			|| observed.values().stream().filter(configuration ->
				configuration.agentId().equals(requestedAgentId)).anyMatch(configuration ->
					!configuration.ownerClusterId().equals(requestedOwnerId)
						|| !configuration.clusterEpochId().equals(requestedEpochId)))
			return Optional.empty();
		boolean structural = prior == null || !prior.agentId().equals(request.agentId())
			|| !prior.ownerClusterId().equals(request.ownerClusterId())
			|| prior.role() != request.role();
		LinkedHashMap<SpaceAddress, Optional<FactoryPanelAgentAccess.Configuration>> updates =
			new LinkedHashMap<>();
		LinkedHashSet<UUID> rebuild = new LinkedHashSet<>();
		if (structural && prior != null) {
			rebuild.add(prior.agentId());
			if (!prior.agentId().equals(request.agentId()))
				advanceSurvivors(observed, updates, prior.agentId(), request.endpoint(),
					request.snapshotGeneration());
		}
		rebuild.add(request.agentId());
		long targetGeneration;
		if (structural) {
			long current = generationFor(observed, request.agentId()).orElse(-1L);
			targetGeneration = current < 0 ? 0 : Math.incrementExact(current);
			for (Map.Entry<SpaceAddress, FactoryPanelAgentAccess.Configuration> entry :
				observed.entrySet()) {
				if (entry.getKey().equals(request.endpoint())
					|| !entry.getValue().agentId().equals(request.agentId())) continue;
				updates.put(entry.getKey(), Optional.of(withGeneration(entry.getValue(),
					targetGeneration, request.snapshotGeneration())));
			}
		} else {
			targetGeneration = prior.agentGeneration();
		}
		long revision = nextRevision(prior, request.snapshotGeneration());
		long outputGeneration = prior == null ? 0 : outputPolicyChanged(prior, request)
			? Math.incrementExact(prior.outputPolicyGeneration()) : prior.outputPolicyGeneration();
		FactoryPanelAgentAccess.Configuration configured = new FactoryPanelAgentAccess.Configuration(
			request.agentId(), request.ownerClusterId(), request.clusterEpochId(), revision,
			targetGeneration, request.role(), request.normalizedAddress(), request.routePriority(),
			request.frequency(), request.completionMode(), request.signalSelector(),
			request.outputMode(), outputGeneration,
			request.returnWarehouseRoute());
		updates.put(request.endpoint(), Optional.of(configured));
		return Optional.of(new Plan(updates, rebuild, structural));
	}

	public static Optional<Plan> clear(
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> observed,
		SpaceAddress endpoint, UUID ownerClusterId, UUID clusterEpochId,
		long snapshotGeneration) {
		observed = Map.copyOf(Objects.requireNonNull(observed, "observed"));
		endpoint = Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		Objects.requireNonNull(clusterEpochId, "clusterEpochId");
		if (snapshotGeneration < 0 || !coherent(observed)) return Optional.empty();
		FactoryPanelAgentAccess.Configuration prior = observed.get(endpoint);
		if (prior == null || !prior.ownerClusterId().equals(ownerClusterId)
			|| !prior.clusterEpochId().equals(clusterEpochId)) return Optional.empty();
		LinkedHashMap<SpaceAddress, Optional<FactoryPanelAgentAccess.Configuration>> updates =
			new LinkedHashMap<>();
		advanceSurvivors(observed, updates, prior.agentId(), endpoint, snapshotGeneration);
		updates.put(endpoint, Optional.empty());
		return Optional.of(new Plan(updates, Set.of(prior.agentId()), true));
	}

	private static void advanceSurvivors(
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> observed,
		Map<SpaceAddress, Optional<FactoryPanelAgentAccess.Configuration>> updates,
		UUID agentId, SpaceAddress removed, long snapshotGeneration) {
		long next = Math.incrementExact(generationFor(observed, agentId).orElseThrow());
		for (Map.Entry<SpaceAddress, FactoryPanelAgentAccess.Configuration> entry :
			observed.entrySet()) {
			if (entry.getKey().equals(removed) || !entry.getValue().agentId().equals(agentId))
				continue;
			updates.put(entry.getKey(), Optional.of(withGeneration(entry.getValue(), next,
				snapshotGeneration)));
		}
	}

	private static FactoryPanelAgentAccess.Configuration withGeneration(
		FactoryPanelAgentAccess.Configuration prior, long generation, long snapshotGeneration) {
		return new FactoryPanelAgentAccess.Configuration(prior.agentId(), prior.ownerClusterId(),
			prior.clusterEpochId(), nextRevision(prior, snapshotGeneration), generation, prior.role(),
			prior.normalizedAddress(), prior.routePriority(), prior.frequency(), prior.completionMode(),
			prior.signalSelector(), prior.outputMode(), prior.outputPolicyGeneration(),
			prior.returnWarehouseRoute());
	}

	private static long nextRevision(FactoryPanelAgentAccess.Configuration prior,
		long snapshotGeneration) {
		if (snapshotGeneration < 0)
			throw new IllegalArgumentException("snapshot generation cannot be negative");
		return prior == null ? snapshotGeneration
			: Math.max(snapshotGeneration, Math.incrementExact(prior.configurationRevision()));
	}

	private static Optional<Long> generationFor(
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> observed, UUID agentId) {
		return observed.values().stream().filter(configuration ->
			configuration.agentId().equals(agentId)).map(
			FactoryPanelAgentAccess.Configuration::agentGeneration).findFirst();
	}

	private static boolean coherent(
		Map<SpaceAddress, FactoryPanelAgentAccess.Configuration> observed) {
		Map<UUID, AgentIdentity> identities = new LinkedHashMap<>();
		for (FactoryPanelAgentAccess.Configuration configuration : observed.values()) {
			AgentIdentity identity = new AgentIdentity(configuration.ownerClusterId(),
				configuration.clusterEpochId(), configuration.agentGeneration());
			AgentIdentity previous = identities.putIfAbsent(configuration.agentId(), identity);
			if (previous != null && !previous.equals(identity))
				return false;
		}
		return true;
	}

	private record AgentIdentity(UUID ownerClusterId, UUID clusterEpochId, long generation) {}

	private static boolean outputPolicyChanged(FactoryPanelAgentAccess.Configuration prior,
		ConfigureRequest request) {
		return prior.role() != request.role()
			|| !prior.normalizedAddress().equals(request.normalizedAddress())
			|| prior.completionMode() != request.completionMode()
			|| !prior.signalSelector().equals(request.signalSelector())
			|| prior.outputMode() != request.outputMode()
			|| !prior.returnWarehouseRoute().equals(request.returnWarehouseRoute());
	}

	public record ConfigureRequest(SpaceAddress endpoint, UUID agentId, UUID ownerClusterId,
		UUID clusterEpochId, long snapshotGeneration, AgentEndpointRole role,
		String normalizedAddress, int routePriority, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency,
		AgentCompletionMode completionMode, Optional<AgentSignalSelector> signalSelector,
		AgentEndpointState.OutputMode outputMode,
		CreateRouteKey returnWarehouseRoute) {
		public ConfigureRequest {
			endpoint = Objects.requireNonNull(endpoint, "endpoint");
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			clusterEpochId = Objects.requireNonNull(clusterEpochId, "clusterEpochId");
			if (snapshotGeneration < 0)
				throw new IllegalArgumentException("snapshot generation cannot be negative");
			role = Objects.requireNonNull(role, "role");
			normalizedAddress = Objects.requireNonNull(normalizedAddress, "normalizedAddress");
			frequency = Objects.requireNonNull(frequency, "frequency");
			completionMode = Objects.requireNonNull(completionMode, "completionMode");
			signalSelector = Objects.requireNonNull(signalSelector, "signalSelector");
			if ((completionMode == AgentCompletionMode.SIGNAL) != signalSelector.isPresent())
				throw new IllegalArgumentException("SIGNAL request needs exactly one selector");
			outputMode = Objects.requireNonNull(outputMode, "outputMode");
			returnWarehouseRoute = Objects.requireNonNull(returnWarehouseRoute,
				"returnWarehouseRoute");
		}
	}

	public record Plan(
		Map<SpaceAddress, Optional<FactoryPanelAgentAccess.Configuration>> updates,
		Set<UUID> rebuildAgentIds, boolean membershipChanged) {
		public Plan {
			updates = Map.copyOf(Objects.requireNonNull(updates, "updates"));
			rebuildAgentIds = Set.copyOf(Objects.requireNonNull(rebuildAgentIds,
				"rebuildAgentIds"));
			if (updates.isEmpty() || rebuildAgentIds.isEmpty())
				throw new IllegalArgumentException("endpoint mutation plan cannot be empty");
		}
	}
}
