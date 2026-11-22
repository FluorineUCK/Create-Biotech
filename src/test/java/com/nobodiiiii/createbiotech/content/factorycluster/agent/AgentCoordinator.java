package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

/** Coordinator-side world integration for one coreless Agent formation. */
public final class AgentCoordinator {
	private AgentFormation formation;
	private final AgentProcess process;
	private final StateStore stateStore;
	private final int retiredRouteCapacity;
	private final Map<CreateRouteKey, AgentAddressRegistration> publishedInputs =
		new LinkedHashMap<>();
	private final Map<CreateRouteKey, Long> retiredRouteGenerations = new LinkedHashMap<>();
	private long publicationGeneration;

	public AgentCoordinator(AgentFormation formation, AgentProcess process,
		StateStore stateStore, int retiredRouteCapacity) {
		formation = Objects.requireNonNull(formation, "formation");
		process = Objects.requireNonNull(process, "process");
		this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
		if (retiredRouteCapacity <= 0) throw new IllegalArgumentException("retired route capacity must be positive");
		this.retiredRouteCapacity = retiredRouteCapacity;
		if (!process.managedEndpoints().equals(formation.members()))
			throw new IllegalArgumentException("Agent process belongs to another formation");
		Optional<PersistedState> restored = stateStore.load(formation.agentId());
		if (restored.isPresent()) {
			process.restoreInPlace(formation, restored.orElseThrow());
			this.process = process;
			this.formation = this.process.formation();
			restorePublicationState(restored.orElseThrow());
		} else {
			this.formation = formation;
			this.process = process;
			this.publicationGeneration = formation.agentGeneration();
			persist();
		}
	}

	private AgentCoordinator(AgentFormation observed, AgentProcess process, StateStore stateStore,
		@javax.annotation.Nullable PersistedState restored, int retiredRouteCapacity) {
		this.process = Objects.requireNonNull(process, "process");
		this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
		if (retiredRouteCapacity <= 0) throw new IllegalArgumentException("retired route capacity must be positive");
		this.retiredRouteCapacity = retiredRouteCapacity;
		this.formation = restored == null ? Objects.requireNonNull(observed, "observed")
			: process.formation();
		if (restored == null) {
			publicationGeneration = formation.agentGeneration();
			persist();
		} else restorePublicationState(restored);
	}

	public static AgentCoordinator formOrRestore(AgentFormation observed,
		AgentProcess.SlotAllocator allocator, StateStore stateStore, int retiredRouteCapacity) {
		observed = Objects.requireNonNull(observed, "observed");
		allocator = Objects.requireNonNull(allocator, "allocator");
		stateStore = Objects.requireNonNull(stateStore, "stateStore");
		Optional<PersistedState> restored = stateStore.load(observed.agentId());
		AgentProcess process = restored.isPresent()
			? AgentProcess.restore(observed, allocator, restored.orElseThrow())
			: AgentProcess.form(observed, allocator);
		return new AgentCoordinator(observed, process, stateStore, restored.orElse(null),
			retiredRouteCapacity);
	}

	public AgentFormation formation() { return formation; }
	public AgentProcess process() { return process; }

	public boolean refresh(AgentFormation next) {
		boolean refreshed = process.refreshFormation(next);
		if (refreshed) formation = next;
		persist();
		return refreshed;
	}

	public boolean startContract(UUID contractId) {
		return startContract(contractId, 0);
	}

	public boolean startContract(UUID contractId, long fence) {
		boolean started = process.startContract(contractId, fence);
		if (started) persist();
		return started;
	}

	public boolean finishContract(UUID contractId) {
		boolean finished = process.finishContract(contractId);
		if (finished) persist();
		return finished;
	}

	/** Rebinds the same physical formation to the generation durably issued by Force Reset. */
	public boolean advanceResetGeneration(AgentFormation next) {
		next = Objects.requireNonNull(next, "next");
		if (!publishedInputs.isEmpty() || !process.advanceResetGeneration(next)) return false;
		formation = next;
		publicationGeneration = Math.max(publicationGeneration, next.agentGeneration());
		persist();
		return true;
	}

	public boolean canForceReset(UUID requestId) {
		requestId = Objects.requireNonNull(requestId, "requestId");
		Set<CreateRouteKey> expected = formation.inputs().stream().map(AgentEndpointState::route)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return process.activeContract().filter(requestId::equals).isPresent()
			&& publishedInputs.keySet().equals(expected);
	}

	/** Pure coordinator preflight; no route claim or durable state changes before the outer batch. */
	public Optional<ForceResetPreparation> prepareForceReset(UUID requestId,
		AgentFormation next) {
		requestId = Objects.requireNonNull(requestId, "requestId");
		next = Objects.requireNonNull(next, "next");
		if (!canForceReset(requestId) || !process.canInstallForceReset(requestId, next))
			return Optional.empty();
		PersistedState expected = snapshot();
		LinkedHashMap<CreateRouteKey, Long> withdrawals = new LinkedHashMap<>();
		LinkedHashMap<CreateRouteKey, Long> retired = new LinkedHashMap<>(retiredRouteGenerations);
		long nextPublication = publicationGeneration;
		try {
			for (CreateRouteKey route : publishedInputs.keySet()) {
				if (!retired.containsKey(route) && retired.size() >= retiredRouteCapacity)
					return Optional.empty();
				nextPublication = Math.incrementExact(nextPublication);
				withdrawals.put(route, nextPublication);
				retired.put(route, nextPublication);
			}
		} catch (ArithmeticException exhausted) { return Optional.empty(); }
		PersistedState target = new PersistedState(next.agentId(), next.ownerClusterId(),
			next.agentGeneration(), next.coordinatorAddress(), next.members(), process.residentSlot(),
			Optional.empty(), process.availableAfterForceReset(), nextPublication, List.of(),
			retiredRouteCapacity, retired);
		return Optional.of(new ForceResetPreparation(requestId, expected, target, withdrawals));
	}

	/** Applies only a coordinator transition already persisted in the outer Force Reset batch. */
	public void installCommittedForceReset(ForceResetPreparation prepared,
		AgentAddressDirectory directory) {
		prepared = Objects.requireNonNull(prepared, "prepared");
		directory = Objects.requireNonNull(directory, "directory");
		if (!snapshot().equals(prepared.expectedState()))
			throw new IllegalStateException("Agent coordinator changed after Force Reset preflight");
		AgentFormation next = AgentFormation.form(prepared.nextState().endpointSnapshots());
		if (!process.installCommittedForceReset(prepared.contractId(), next))
			throw new IllegalStateException("Prepared Agent process Force Reset became stale");
		for (Map.Entry<CreateRouteKey, Long> withdrawal : prepared.withdrawalVersions().entrySet()) {
			AgentAddressDirectory.PublicationResult result = directory.withdraw(withdrawal.getKey(),
				formation.agentId(), formation.agentGeneration(), withdrawal.getValue());
			if (result == AgentAddressDirectory.PublicationResult.STALE)
				throw new IllegalStateException("Prepared Agent route withdrawal became stale");
		}
		formation = next;
		publicationGeneration = prepared.nextState().publicationGeneration();
		publishedInputs.clear();
		retiredRouteGenerations.clear();
		retiredRouteGenerations.putAll(prepared.nextState().retiredRouteGenerations());
		if (!snapshot().equals(prepared.nextState()))
			throw new IllegalStateException("Installed Force Reset differs from durable coordinator state");
	}

	/** Pure preflight for an idle formation-wide endpoint membership generation change. */
	public Optional<MembershipChangePreparation> prepareMembershipChange(AgentFormation next) {
		next = Objects.requireNonNull(next, "next");
		if (!process.canInstallMembershipChange(next)) return Optional.empty();
		PersistedState expected = snapshot();
		LinkedHashMap<CreateRouteKey, Long> withdrawals = new LinkedHashMap<>();
		LinkedHashMap<CreateRouteKey, Long> retired = new LinkedHashMap<>(retiredRouteGenerations);
		long nextPublication = publicationGeneration;
		try {
			for (CreateRouteKey route : publishedInputs.keySet()) {
				if (!retired.containsKey(route) && retired.size() >= retiredRouteCapacity)
					return Optional.empty();
				nextPublication = Math.incrementExact(nextPublication);
				withdrawals.put(route, nextPublication);
				retired.put(route, nextPublication);
			}
		} catch (ArithmeticException exhausted) { return Optional.empty(); }
		PersistedState target = new PersistedState(next.agentId(), next.ownerClusterId(),
			next.agentGeneration(), next.coordinatorAddress(), next.members(), process.residentSlot(),
			Optional.empty(), process.availableAfterMembershipChange(), nextPublication, List.of(),
			retiredRouteCapacity, retired);
		return Optional.of(new MembershipChangePreparation(expected, target, withdrawals));
	}

	/** Installs only a membership transition already committed with all native endpoint configs. */
	public void installCommittedMembershipChange(MembershipChangePreparation prepared,
		AgentAddressDirectory directory) {
		prepared = Objects.requireNonNull(prepared, "prepared");
		directory = Objects.requireNonNull(directory, "directory");
		if (!snapshot().equals(prepared.expectedState()))
			throw new IllegalStateException("Agent coordinator changed after membership preflight");
		AgentFormation next = AgentFormation.form(prepared.nextState().endpointSnapshots());
		if (!process.installCommittedMembershipChange(next))
			throw new IllegalStateException("Prepared Agent membership change became stale");
		for (Map.Entry<CreateRouteKey, Long> withdrawal : prepared.withdrawalVersions().entrySet()) {
			AgentAddressDirectory.PublicationResult result = directory.withdraw(withdrawal.getKey(),
				formation.agentId(), formation.agentGeneration(), withdrawal.getValue());
			if (result == AgentAddressDirectory.PublicationResult.STALE)
				throw new IllegalStateException("Prepared Agent membership withdrawal became stale");
		}
		formation = next;
		publicationGeneration = prepared.nextState().publicationGeneration();
		publishedInputs.clear();
		retiredRouteGenerations.clear();
		retiredRouteGenerations.putAll(prepared.nextState().retiredRouteGenerations());
		if (!snapshot().equals(prepared.nextState()))
			throw new IllegalStateException("Installed Agent membership differs from durable state");
	}

	public void reconcileResidentSlot() {
		process.reconcileResidentSlot();
		persist();
	}

	public boolean recover(AgentFormation recovered) {
		boolean available = process.recover(recovered);
		if (available) formation = recovered;
		persist();
		return available;
	}

	/** A declared Cluster Agent projects its one primary frequency onto every public route. */
	public int publishInputRegistrations(AgentAddressDirectory directory,
		ClusterCommunicationConfiguration declaration) {
		directory = Objects.requireNonNull(directory, "directory");
		declaration = Objects.requireNonNull(declaration, "declaration");
		CommunicationFrequency primary = declaration.primaryFrequency();
		formation.requireDeclaredAgent(declaration);
		long generationBefore = publicationGeneration;
		Map<CreateRouteKey, AgentEndpointState> desired = new LinkedHashMap<>();
		for (AgentEndpointState input : formation.inputs()) desired.put(input.route(), input);
		boolean changed = false;
		for (CreateRouteKey priorRoute : List.copyOf(publishedInputs.keySet())) {
			if (desired.containsKey(priorRoute)) continue;
			AgentAddressRegistration prior = publishedInputs.get(priorRoute);
			if (!canTrackRetired(priorRoute)) {
				if (prior.loadState() == AgentAddressRegistration.LoadState.LOADED
					|| prior.reachability() == AgentAddressRegistration.Reachability.REACHABLE) {
					long generation = nextPublicationGeneration();
					AgentAddressRegistration unavailable = unavailableCopy(prior);
					if (directory.publish(unavailable, generation) == AgentAddressDirectory.PublicationResult.ACCEPTED) {
						publishedInputs.put(priorRoute, unavailable); changed = true;
					}
				}
				continue;
			}
			long generation = nextPublicationGeneration();
			AgentAddressDirectory.PublicationResult result = directory.withdraw(priorRoute,
				formation.agentId(), formation.agentGeneration(), generation);
			if (result == AgentAddressDirectory.PublicationResult.ACCEPTED
				|| result == AgentAddressDirectory.PublicationResult.NOT_FOUND) {
				publishedInputs.remove(priorRoute);
				retiredRouteGenerations.merge(priorRoute, generation, Math::max);
				changed = true;
			} else if (result == AgentAddressDirectory.PublicationResult.CAPACITY_FULL) {
				publishedInputs.put(priorRoute, unavailableCopy(prior));
				changed = true;
			}
		}

		int synchronizedInputs = 0;
		for (AgentEndpointState input : desired.values()) {
			AgentAddressRegistration prior = publishedInputs.get(input.route());
			if (prior == null && !canTrackRetired(input.route())) continue;
			AgentAddressRegistration candidate;
			if (prior == null) {
				candidate = registration(input, primary);
			} else {
				candidate = registration(input, primary);
				if (samePublicationState(prior, candidate)) {
					if (directory.publish(candidate, publicationGeneration)
						== AgentAddressDirectory.PublicationResult.ACCEPTED)
						synchronizedInputs++;
					continue;
				}
				nextPublicationGeneration();
			}
			long version = prior == null ? firstOrNextGeneration(input.route()) : publicationGeneration;
			if (directory.publish(candidate, version) == AgentAddressDirectory.PublicationResult.ACCEPTED) {
				publishedInputs.put(input.route(), candidate);
				retiredRouteGenerations.remove(input.route());
				synchronizedInputs++;
				changed = true;
			} else if (prior == null) {
				retiredRouteGenerations.merge(input.route(), version, Math::max);
				changed = true;
			} else {
				long generation = nextPublicationGeneration();
				AgentAddressDirectory.PublicationResult withdrawn = directory.withdraw(prior.route(),
					prior.agentId(), formation.agentGeneration(), generation);
				if (withdrawn != AgentAddressDirectory.PublicationResult.STALE) {
					publishedInputs.put(prior.route(), unavailableCopy(prior));
					changed = true;
				}
			}
		}
		if (changed || publicationGeneration != generationBefore) persist();
		return synchronizedInputs;
	}

	/** Retires every input claim before this loaded formation leaves the server lifecycle. */
	public int withdrawInputRegistrations(AgentAddressDirectory directory) {
		directory = Objects.requireNonNull(directory, "directory");
		int withdrawn = 0; boolean changed = false;
		for (CreateRouteKey route : List.copyOf(publishedInputs.keySet())) {
			if (!canTrackRetired(route)) continue;
			long generation = nextPublicationGeneration();
			AgentAddressDirectory.PublicationResult result = directory.withdraw(route,
				formation.agentId(), formation.agentGeneration(), generation);
			if (result == AgentAddressDirectory.PublicationResult.ACCEPTED
				|| result == AgentAddressDirectory.PublicationResult.NOT_FOUND) {
				publishedInputs.remove(route); retiredRouteGenerations.merge(route, generation, Math::max);
				withdrawn++; changed = true;
			} else if (result == AgentAddressDirectory.PublicationResult.CAPACITY_FULL) {
				AgentAddressRegistration prior = publishedInputs.get(route);
				publishedInputs.put(route, unavailableCopy(prior)); changed = true;
			}
		}
		if (changed) persist();
		return withdrawn;
	}

	boolean ownsRegistration(AgentAddressRegistration registration) {
		registration = Objects.requireNonNull(registration, "registration");
		return registration.agentId().equals(formation.agentId())
			&& registration.ownerClusterId().equals(formation.ownerClusterId())
			&& registration.equals(publishedInputs.get(registration.route()));
	}

	private AgentAddressRegistration registration(AgentEndpointState input,
		CommunicationFrequency primary) {
		boolean reachable = input.loaded() && input.reachable() && process.available();
		return new AgentAddressRegistration(input.route(), formation.agentId(),
			formation.ownerClusterId(), formation.agentGeneration(),
			primary,
			reachable ? AgentAddressRegistration.LoadState.LOADED
				: AgentAddressRegistration.LoadState.UNLOADED,
			reachable ? AgentAddressRegistration.Reachability.REACHABLE
				: AgentAddressRegistration.Reachability.UNREACHABLE);
	}

	private static AgentAddressRegistration unavailableCopy(AgentAddressRegistration prior) {
		return new AgentAddressRegistration(prior.route(), prior.agentId(), prior.ownerClusterId(),
			prior.agentGeneration(), prior.frequency(), AgentAddressRegistration.LoadState.UNLOADED,
			AgentAddressRegistration.Reachability.UNREACHABLE);
	}

	private static boolean samePublicationState(AgentAddressRegistration left,
		AgentAddressRegistration right) {
		return left.route().equals(right.route()) && left.agentId().equals(right.agentId())
			&& left.ownerClusterId().equals(right.ownerClusterId())
			&& left.frequency().equals(right.frequency()) && left.loadState() == right.loadState()
			&& left.reachability() == right.reachability();
	}

	private long nextPublicationGeneration() {
		if (publicationGeneration == Long.MAX_VALUE)
			throw new IllegalStateException("Agent publication generation exhausted");
		return ++publicationGeneration;
	}

	private long firstOrNextGeneration(CreateRouteKey route) {
		return retiredRouteGenerations.containsKey(route) ? nextPublicationGeneration()
			: formation.agentGeneration();
	}

	private boolean canTrackRetired(CreateRouteKey route) {
		return retiredRouteGenerations.containsKey(route)
			|| retiredRouteGenerations.size() < retiredRouteCapacity;
	}

	public PersistedState snapshot() {
		return new PersistedState(formation.agentId(), formation.ownerClusterId(),
			formation.agentGeneration(), formation.coordinatorAddress(),
			formation.members(), process.residentSlot(), process.activeContractState(),
			process.available(), publicationGeneration, List.copyOf(publishedInputs.values()),
			retiredRouteCapacity, Map.copyOf(retiredRouteGenerations));
	}

	private void persist() {
		stateStore.persist(snapshot());
	}

	private void restorePublicationState(PersistedState restored) {
		if (restored.retiredRouteCapacity() != retiredRouteCapacity)
			throw new IllegalArgumentException("retired route capacity changed across restore");
		publicationGeneration = restored.publicationGeneration();
		for (AgentAddressRegistration registration : restored.publishedInputRegistrations())
			publishedInputs.put(registration.route(), registration);
		retiredRouteGenerations.putAll(restored.retiredRouteGenerations());
	}

	public interface StateStore {
		default Optional<PersistedState> load(UUID agentId) {
			return Optional.empty();
		}
		/** The implementation writes this bounded snapshot into the owning Cluster runtime. */
		void persist(PersistedState state);
	}

	public record ForceResetPreparation(UUID contractId, PersistedState expectedState,
		PersistedState nextState, Map<CreateRouteKey, Long> withdrawalVersions) {
		public ForceResetPreparation {
			contractId = Objects.requireNonNull(contractId, "contractId");
			expectedState = Objects.requireNonNull(expectedState, "expectedState");
			nextState = Objects.requireNonNull(nextState, "nextState");
			withdrawalVersions = java.util.Collections.unmodifiableMap(
				new LinkedHashMap<>(Objects.requireNonNull(withdrawalVersions,
					"withdrawalVersions")));
			if (!expectedState.agentId().equals(nextState.agentId())
				|| !expectedState.ownerClusterId().equals(nextState.ownerClusterId())
				|| nextState.agentGeneration() != expectedState.agentGeneration() + 1
				|| nextState.activeContract().isPresent()
				|| !nextState.publishedInputRegistrations().isEmpty())
				throw new IllegalArgumentException("Invalid Agent coordinator Force Reset preparation");
		}
	}

	public record MembershipChangePreparation(PersistedState expectedState,
		PersistedState nextState, Map<CreateRouteKey, Long> withdrawalVersions) {
		public MembershipChangePreparation {
			expectedState = Objects.requireNonNull(expectedState, "expectedState");
			nextState = Objects.requireNonNull(nextState, "nextState");
			withdrawalVersions = java.util.Collections.unmodifiableMap(
				new LinkedHashMap<>(Objects.requireNonNull(withdrawalVersions,
					"withdrawalVersions")));
			if (!expectedState.agentId().equals(nextState.agentId())
				|| !expectedState.ownerClusterId().equals(nextState.ownerClusterId())
				|| nextState.agentGeneration() != expectedState.agentGeneration() + 1
				|| expectedState.activeContract().isPresent()
				|| nextState.activeContract().isPresent()
				|| !nextState.publishedInputRegistrations().isEmpty())
				throw new IllegalArgumentException("Invalid Agent membership preparation");
		}
	}

	public record PersistedState(UUID agentId, UUID ownerClusterId, long agentGeneration,
		SpaceAddress coordinatorAddress, List<AgentEndpointState> endpointSnapshots,
		Optional<AgentProcess.ResidentSlot> residentSlot,
		Optional<AgentProcess.ActiveContract> activeContractState, boolean available,
		long publicationGeneration,
		List<AgentAddressRegistration> publishedInputRegistrations,
		int retiredRouteCapacity,
		Map<CreateRouteKey, Long> retiredRouteGenerations) {
		public PersistedState {
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			coordinatorAddress = Objects.requireNonNull(coordinatorAddress, "coordinatorAddress");
			endpointSnapshots = List.copyOf(Objects.requireNonNull(endpointSnapshots,
				"endpointSnapshots"));
			residentSlot = Objects.requireNonNull(residentSlot, "residentSlot");
			activeContractState = Objects.requireNonNull(activeContractState,
				"activeContractState");
			publishedInputRegistrations = List.copyOf(Objects.requireNonNull(
				publishedInputRegistrations, "publishedInputRegistrations"));
			retiredRouteGenerations = Map.copyOf(Objects.requireNonNull(retiredRouteGenerations,
				"retiredRouteGenerations"));
			if (agentGeneration < 0 || publicationGeneration < 0 || endpointSnapshots.isEmpty()
				|| retiredRouteCapacity <= 0 || retiredRouteGenerations.size() > retiredRouteCapacity)
				throw new IllegalArgumentException("Invalid persisted Agent state");
			for (long generation : retiredRouteGenerations.values())
				if (generation < 0 || generation > publicationGeneration)
					throw new IllegalArgumentException("Invalid retired route generation");
		}

		public Optional<UUID> residentProcessId() {
			return residentSlot.map(AgentProcess.ResidentSlot::processId);
		}
		public Optional<UUID> activeContract() {
			return activeContractState.map(AgentProcess.ActiveContract::contractId);
		}
		public List<SpaceAddress> membership() {
			return endpointSnapshots.stream().map(AgentEndpointState::endpointAddress).toList();
		}
	}
}
