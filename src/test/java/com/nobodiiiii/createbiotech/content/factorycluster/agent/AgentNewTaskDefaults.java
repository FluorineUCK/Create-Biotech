package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentFormation.AgentServiceIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.engine.FactoryRuntimeService.PanelPublicationFence;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;

/** Immutable New Task policy selected from one current, formed local Agent publication. */
public record AgentNewTaskDefaults(UUID ownerClusterId, String deliveryAddress,
	UUID logisticsNetworkId, InputMode inputMode, long multiplier,
	UUID outputObservationNetworkId, String finishRoute, int priority,
	CreateRouteKey selectedInput) {
	public AgentNewTaskDefaults {
		ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
		deliveryAddress = Objects.requireNonNull(deliveryAddress, "deliveryAddress");
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		inputMode = Objects.requireNonNull(inputMode, "inputMode");
		if (multiplier <= 0) throw new IllegalArgumentException("New Task multiplier must be positive");
		outputObservationNetworkId = Objects.requireNonNull(outputObservationNetworkId,
			"outputObservationNetworkId");
		finishRoute = Objects.requireNonNull(finishRoute, "finishRoute");
		selectedInput = Objects.requireNonNull(selectedInput, "selectedInput");
		if (!deliveryAddress.equals(selectedInput.address())
			|| !logisticsNetworkId.equals(selectedInput.logisticsNetworkId())
			|| !outputObservationNetworkId.equals(selectedInput.logisticsNetworkId())
			|| !finishRoute.equals(selectedInput.address()))
			throw new IllegalArgumentException("New Task routes must name one selected local input");
	}

	public static AgentNewTaskDefaults resolve(FormationPublication publication,
		Optional<CreateRouteKey> explicitOverride) {
		return tryResolve(publication, explicitOverride).orElseThrow(() ->
			new IllegalStateException("Agent publication has no approved reachable input"));
	}
	public static Optional<AgentNewTaskDefaults> tryResolve(FormationPublication publication,
		Optional<CreateRouteKey> explicitOverride) {
		publication = Objects.requireNonNull(publication, "publication");
		explicitOverride = Objects.requireNonNull(explicitOverride, "explicitOverride");
		if (!publication.isCurrentPublication()) return Optional.empty();
		AgentFormation formation = publication.formation();
		AgentServiceIdentity declared = publication.declared();
		if (!formation.agentId().equals(declared.agentId())
			|| formation.agentGeneration() != declared.generation()) return Optional.empty();
		List<AgentEndpointState> approved = formation.inputs().stream()
			.filter(AgentEndpointState::reachable).sorted(order(publication.aliases())).toList();
		AgentEndpointState selected;
		if (explicitOverride.isPresent()) {
			CreateRouteKey route = explicitOverride.orElseThrow();
			selected = approved.stream().filter(endpoint -> endpoint.route().equals(route))
				.findFirst().orElse(null);
		} else selected = approved.isEmpty() ? null : approved.getFirst();
		if (selected == null) return Optional.empty();
		CreateRouteKey input = selected.route();
		return Optional.of(new AgentNewTaskDefaults(formation.ownerClusterId(), input.address(),
			input.logisticsNetworkId(), InputMode.EXACT, 1, input.logisticsNetworkId(),
			input.address(), publication.normalPriority(), input));
	}
	public static AgentNewTaskDefaults standard(UUID clusterId, CreateRouteKey deliveryRoute,
		InputMode inputMode, long multiplier, int priority) {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		deliveryRoute = Objects.requireNonNull(deliveryRoute, "deliveryRoute");
		return new AgentNewTaskDefaults(clusterId, deliveryRoute.address(),
			deliveryRoute.logisticsNetworkId(), inputMode, multiplier,
			deliveryRoute.logisticsNetworkId(), deliveryRoute.address(), priority, deliveryRoute);
	}
	private static Comparator<AgentEndpointState> order(Map<UUID, String> aliases) {
		return Comparator.comparingInt(AgentEndpointState::routePriority).reversed()
			.thenComparing(endpoint -> alias(aliases, endpoint.route().logisticsNetworkId()))
			.thenComparing(endpoint -> endpoint.route().address())
			.thenComparing(endpoint -> endpoint.route().logisticsNetworkId().toString())
			.thenComparing(endpoint -> endpoint.endpointAddress().toString());
	}
	private static String alias(Map<UUID, String> aliases, UUID networkId) {
		String alias = aliases.get(networkId);
		return alias == null || alias.isBlank() ? networkId.toString() : alias.strip();
	}

	/** Server/runtime-owned Agent directory authority; it owns no JVM-global state. */
	public static final class PublicationAuthority implements AutoCloseable {
		private final Map<UUID, FormationPublication> current = new HashMap<>();
		private boolean closed;
		private PublicationAuthority() {}
		public static PublicationAuthority runtimeAuthority() { return new PublicationAuthority(); }
		/** Runtime-only path; an opaque fence rejects delayed or revoked epochs. */
		public FormationPublication publish(PanelPublicationFence fence, UUID epochId,
			long directoryGeneration,
			AgentFormation formation, AgentServiceIdentity declared, Map<UUID, String> aliases,
			int normalPriority) {
			Objects.requireNonNull(fence, "fence").requireAgentFormation(epochId, directoryGeneration,
				formation, declared);
			synchronized (this) {
				if (closed) throw new IllegalStateException("Agent publication authority is closed");
				return publishUnchecked(fence, epochId, directoryGeneration, formation, declared, aliases,
					normalPriority);
			}
		}
		private FormationPublication publishUnchecked(PanelPublicationFence fence, UUID epochId,
			long directoryGeneration,
			AgentFormation formation, AgentServiceIdentity declared, Map<UUID, String> aliases,
			int normalPriority) {
			epochId = Objects.requireNonNull(epochId, "epochId"); formation = Objects.requireNonNull(formation, "formation");
			declared = Objects.requireNonNull(declared, "declared"); aliases = Map.copyOf(Objects.requireNonNull(aliases, "aliases"));
			if (directoryGeneration < 0 || !formation.agentId().equals(declared.agentId())
				|| formation.agentGeneration() != declared.generation())
				throw new IllegalArgumentException("Agent publication differs from declared formation");
			FormationPublication prior = current.get(formation.ownerClusterId());
			if (prior != null && prior.epochId().equals(epochId)) {
				if (directoryGeneration < prior.directoryGeneration())
					throw new IllegalArgumentException("Agent directory generation regressed");
				if (directoryGeneration == prior.directoryGeneration()) {
					if (prior.sameFacts(formation, declared, aliases, normalPriority)) {
						if (prior.hasSameFence(fence)) return prior;
						prior.revoke();
					} else {
						throw new IllegalArgumentException("Agent directory changed without generation");
					}
				}
			}
			FormationPublication publication = new FormationPublication(this, fence, epochId, directoryGeneration,
				formation, declared, aliases, normalPriority);
			current.put(formation.ownerClusterId(), publication); return publication;
		}
		public synchronized void revoke(UUID clusterId, UUID epochId) {
			FormationPublication publication = current.get(Objects.requireNonNull(clusterId, "clusterId"));
			if (publication != null && publication.epochId().equals(Objects.requireNonNull(epochId, "epochId"))) {
				publication.revoke(); current.remove(clusterId);
			}
		}
		public synchronized void revokeCluster(UUID clusterId) {
			FormationPublication publication = current.remove(Objects.requireNonNull(clusterId, "clusterId"));
			if (publication != null) publication.revoke();
		}
		private synchronized boolean isCurrent(FormationPublication candidate) {
			return !closed && !candidate.revoked && current.get(candidate.formation.ownerClusterId()) == candidate;
		}
		public synchronized int publicationCountForTest() { return current.size(); }
		@Override public synchronized void close() { if (!closed) { closed = true; current.values().forEach(FormationPublication::revoke); current.clear(); } }
	}

	/** Opaque exact Cluster-epoch/directory publication. */
	public static final class FormationPublication {
		private final PublicationAuthority authority;
		private final PanelPublicationFence fence;
		private final UUID epochId;
		private final long directoryGeneration;
		private final AgentFormation formation;
		private final AgentServiceIdentity declared;
		private final Map<UUID, String> aliases;
		private final int normalPriority;
		private boolean revoked;
		private FormationPublication(PublicationAuthority authority, PanelPublicationFence fence,
			UUID epochId, long directoryGeneration,
			AgentFormation formation, AgentServiceIdentity declared, Map<UUID, String> aliases, int normalPriority) {
			this.authority = authority; this.fence = fence; this.epochId = epochId; this.directoryGeneration = directoryGeneration;
			this.formation = formation; this.declared = declared; this.aliases = aliases; this.normalPriority = normalPriority;
		}
		public UUID epochId() { return epochId; }
		public long directoryGeneration() { return directoryGeneration; }
		public AgentFormation formation() { return formation; }
		public AgentServiceIdentity declared() { return declared; }
		public Map<UUID, String> aliases() { return aliases; }
		public int normalPriority() { return normalPriority; }
		public boolean isCurrentPublication() {
			return authority.isCurrent(this) && (fence == null
				|| fence.isCurrentAgentPublication(epochId, directoryGeneration));
		}
		/** A Task snapshot cannot consume a token produced by another runtime authority. */
		public boolean belongsTo(PanelPublicationFence candidate) {
			return fence != null && fence.sameRuntime(Objects.requireNonNull(candidate, "candidate"));
		}
		private boolean sameFacts(AgentFormation next, AgentServiceIdentity nextDeclared,
			Map<UUID, String> nextAliases, int nextPriority) {
			return formation.equals(next) && declared.equals(nextDeclared) && aliases.equals(nextAliases)
				&& normalPriority == nextPriority;
		}
		private boolean hasSameFence(PanelPublicationFence candidate) {
			return fence == null ? candidate == null
				: candidate != null && fence.samePublicationFence(candidate);
		}
		private void revoke() { revoked = true; }
	}
}
