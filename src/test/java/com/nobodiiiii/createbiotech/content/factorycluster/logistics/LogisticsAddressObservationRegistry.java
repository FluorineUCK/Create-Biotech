package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAssociation.AssociationKind;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Bounded server-thread registry of explicitly associated, currently loaded address carriers. */
public final class LogisticsAddressObservationRegistry {
	private final int capacity;
	private final BooleanSupplier serverThread;
	private final LogisticsAddressAdapters adapters;
	private final LoadedAddressResolver loadedAddressResolver;
	private final AgentEndpointResolver agentEndpointResolver;
	private final LinkedHashMap<RegistryKey, Target> targets = new LinkedHashMap<>();

	public LogisticsAddressObservationRegistry(int capacity, BooleanSupplier serverThread) {
		this(capacity, serverThread, LogisticsAddressAdapters.createDefault(),
			LogisticsAddressObservationRegistry::loadedAddress, (blockEntity, network) -> Optional.empty());
	}

	public LogisticsAddressObservationRegistry(int capacity, BooleanSupplier serverThread,
		AgentEndpointResolver agentEndpointResolver) {
		this(capacity, serverThread, LogisticsAddressAdapters.createDefault(),
			LogisticsAddressObservationRegistry::loadedAddress, agentEndpointResolver);
	}

	public LogisticsAddressObservationRegistry(int capacity, BooleanSupplier serverThread,
		LogisticsAddressAdapters adapters) {
		this(capacity, serverThread, adapters, LogisticsAddressObservationRegistry::loadedAddress,
			(blockEntity, network) -> Optional.empty());
	}

	LogisticsAddressObservationRegistry(int capacity, BooleanSupplier serverThread,
		LogisticsAddressAdapters adapters, LoadedAddressResolver loadedAddressResolver,
		AgentEndpointResolver agentEndpointResolver) {
		if (capacity <= 0) throw new IllegalArgumentException("registry capacity must be positive");
		this.capacity = capacity;
		this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
		this.adapters = Objects.requireNonNull(adapters, "adapters");
		this.loadedAddressResolver = Objects.requireNonNull(loadedAddressResolver,
			"loadedAddressResolver");
		this.agentEndpointResolver = Objects.requireNonNull(agentEndpointResolver,
			"agentEndpointResolver");
	}

	/** Registers only a carrier whose association can be revalidated without guessing. */
	public boolean observe(BlockEntity blockEntity,
		Optional<LogisticallyLinkedBehaviour> associationLink,
		LogisticsNetworkAssociation association) {
		requireServerThread();
		blockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
		associationLink = Objects.requireNonNull(associationLink, "associationLink");
		association = Objects.requireNonNull(association, "association");
		if (!adapters.supports(blockEntity)) return false;
		Optional<SpaceAddress> resolved = associationAddress(blockEntity, associationLink,
			association);
		if (resolved.isEmpty()) return false;
		SpaceAddress address = resolved.orElseThrow();
		RegistryKey key = new RegistryKey(address, association.logisticsNetworkId(),
			association.associationKind());
		if (!targets.containsKey(key) && targets.size() >= capacity) {
			removeDeadTargets();
			if (targets.size() >= capacity) targets.remove(targets.keySet().iterator().next());
		}
		targets.put(key, new Target(new WeakReference<>(blockEntity),
			associationLink.map(WeakReference::new), association));
		return true;
	}

	public void unload(BlockEntity blockEntity) {
		requireServerThread();
		Objects.requireNonNull(blockEntity, "blockEntity");
		for (Iterator<Map.Entry<RegistryKey, Target>> iterator = targets.entrySet().iterator();
			iterator.hasNext();) {
			Map.Entry<RegistryKey, Target> entry = iterator.next();
			if (entry.getValue().blockEntity().get() == blockEntity) iterator.remove();
		}
	}

	/** Starts one authoritative {@code getAllPresent} scan by dropping prior link proofs. */
	public void beginLinkScan() {
		requireServerThread();
		targets.entrySet().removeIf(entry -> switch (entry.getKey().associationKind()) {
			case DIRECT_LINK, ADJACENT_PACKAGER_LINK -> true;
			case AGENT_ENDPOINT -> false;
		});
	}

	public List<LiveObservation> snapshot() {
		requireServerThread();
		removeDeadTargets();
		ArrayList<LiveObservation> observations = new ArrayList<>();
		for (Map.Entry<RegistryKey, Target> entry : targets.entrySet()) {
			Target target = entry.getValue();
			BlockEntity blockEntity = target.blockEntity().get();
			if (blockEntity == null) continue;
			Optional<LogisticallyLinkedBehaviour> link = target.associationLink()
				.flatMap(reference -> Optional.ofNullable(reference.get()));
			Optional<SpaceAddress> current = associationAddress(blockEntity, link,
				target.association());
			if (current.isEmpty()
				|| !current.orElseThrow().equals(entry.getKey().componentAddress())) continue;
			SpaceAddress location = entry.getKey().componentAddress();
			Map<PanelOccurrenceKey, Integer> panelOccurrences = new HashMap<>();
			for (ObservedAddress observed : adapters.observe(blockEntity, link,
				target.association())) {
				ComponentIdentity identity = componentIdentity(blockEntity, location,
					target.association(), observed, panelOccurrences);
				Optional<AgentMetadata> metadata = isAgentRole(observed.role())
					&& target.association().associationKind() == AssociationKind.AGENT_ENDPOINT
						? agentMetadata(blockEntity) : Optional.empty();
				observations.add(new LiveObservation(identity, observed, target.association(),
					metadata));
			}
		}
		return List.copyOf(observations);
	}

	public int size() {
		requireServerThread();
		removeDeadTargets();
		return targets.size();
	}

	private Optional<SpaceAddress> associationAddress(BlockEntity blockEntity,
		Optional<LogisticallyLinkedBehaviour> link,
		LogisticsNetworkAssociation association) {
		Optional<SpaceAddress> loaded = loadedAddressResolver.resolve(blockEntity);
		if (loaded.isEmpty()) return Optional.empty();
		return switch (association.associationKind()) {
			case DIRECT_LINK -> link.filter(candidate -> candidate.blockEntity == blockEntity
				&& candidate.freqId.equals(association.logisticsNetworkId()))
				.isPresent() ? loaded : Optional.empty();
			case ADJACENT_PACKAGER_LINK -> link.filter(candidate -> candidate.blockEntity
				instanceof PackagerLinkBlockEntity packagerLink
				&& candidate.freqId.equals(association.logisticsNetworkId())
				&& (packagerLink.getPackager() == blockEntity
					|| packagerLink.getBlockPos().closerThan(blockEntity.getBlockPos(), 2)))
				.isPresent() ? loaded : Optional.empty();
			case AGENT_ENDPOINT -> blockEntity instanceof FactoryPanelBlockEntity
				&& blockEntity instanceof FactoryPanelAgentAccess access
				&& access.createBiotech$getAgentConfiguration().isPresent()
					? agentEndpointResolver.resolve(blockEntity, association.logisticsNetworkId())
						.filter(loaded.orElseThrow()::equals) : Optional.empty();
		};
	}

	private static Optional<AgentMetadata> agentMetadata(BlockEntity blockEntity) {
		if (!(blockEntity instanceof FactoryPanelAgentAccess access)) return Optional.empty();
		return access.createBiotech$getAgentConfiguration().map(configuration ->
			new AgentMetadata(configuration.agentId(), configuration.agentGeneration()));
	}

	private ComponentIdentity componentIdentity(BlockEntity blockEntity,
		SpaceAddress location, LogisticsNetworkAssociation association, ObservedAddress observed,
		Map<PanelOccurrenceKey, Integer> occurrences) {
		String type = observed.componentType().name().toLowerCase(java.util.Locale.ROOT);
		if (isAgentRole(observed.role())
			|| isMatchingAuthoritativeAgentRole(blockEntity, association, observed))
			return new ComponentIdentity(location, type + ":agent_endpoint");
		if (!(blockEntity instanceof FactoryPanelBlockEntity panel) || panel.panels == null)
			return new ComponentIdentity(location, type);
		List<PanelSlot> matchingSlots = panel.panels.values().stream()
			.filter(FactoryPanelBehaviour::isActive)
			.filter(behaviour -> association.logisticsNetworkId().equals(behaviour.network))
			.filter(behaviour -> ObservedAddress.tryCreate(behaviour.recipeAddress,
				observed.componentType(), observed.role()).map(ObservedAddress::createAddress)
					.filter(observed.createAddress()::equals).isPresent())
			.map(behaviour -> behaviour.slot).toList();
		if (matchingSlots.isEmpty()) return new ComponentIdentity(location, type + ":agent_endpoint");
		PanelOccurrenceKey occurrenceKey = new PanelOccurrenceKey(observed.createAddress(),
			observed.role());
		int occurrence = isAgentRole(observed.role()) ? 0
			: occurrences.merge(occurrenceKey, 1, Integer::sum) - 1;
		PanelSlot slot = matchingSlots.get(Math.min(occurrence, matchingSlots.size() - 1));
		return new ComponentIdentity(location, type + ":" + slot.name().toLowerCase(
			java.util.Locale.ROOT));
	}

	private boolean isMatchingAuthoritativeAgentRole(BlockEntity blockEntity,
		LogisticsNetworkAssociation association, ObservedAddress observed) {
		if (!(blockEntity instanceof FactoryPanelAgentAccess access)
			|| isAgentRole(observed.role())
			|| agentEndpointResolver.resolve(blockEntity,
				association.logisticsNetworkId()).isEmpty()) return false;
		return access.createBiotech$getAgentConfiguration()
			.filter(configuration -> configuration.normalizedAddress()
				.equals(observed.createAddress())).isPresent();
	}

	private static boolean isAgentRole(ComponentRole role) {
		return role == ComponentRole.INPUT || role == ComponentRole.OUTPUT;
	}

	private void removeDeadTargets() {
		targets.entrySet().removeIf(entry -> {
			BlockEntity blockEntity = entry.getValue().blockEntity().get();
			return blockEntity == null || loadedAddressResolver.resolve(blockEntity).isEmpty();
		});
	}

	private static Optional<SpaceAddress> loadedAddress(BlockEntity blockEntity) {
		if (blockEntity == null || blockEntity.isRemoved() || blockEntity.getLevel() == null
			|| blockEntity.getLevel().isClientSide) return Optional.empty();
		return Optional.of(SpaceAddress.capture(blockEntity.getLevel(), blockEntity.getBlockPos()));
	}

	private void requireServerThread() {
		if (!serverThread.getAsBoolean())
			throw new IllegalStateException("logistics address observation is server-thread only");
	}

	private record RegistryKey(SpaceAddress componentAddress, UUID network,
		AssociationKind associationKind) {}

	private record Target(WeakReference<BlockEntity> blockEntity,
		Optional<WeakReference<LogisticallyLinkedBehaviour>> associationLink,
		LogisticsNetworkAssociation association) {}

	private record PanelOccurrenceKey(String createAddress, ComponentRole role) {}

	@FunctionalInterface
	interface LoadedAddressResolver {
		Optional<SpaceAddress> resolve(BlockEntity blockEntity);
	}

	@FunctionalInterface
	public interface AgentEndpointResolver {
		Optional<SpaceAddress> resolve(BlockEntity blockEntity, UUID logisticsNetworkId);
	}

	public record AgentMetadata(UUID agentId, long memberGeneration) {
		public AgentMetadata {
			agentId = Objects.requireNonNull(agentId, "agentId");
			if (memberGeneration < 0)
				throw new IllegalArgumentException("member generation cannot be negative");
		}
	}

	public record LiveObservation(ComponentIdentity componentIdentity, ObservedAddress address,
		LogisticsNetworkAssociation association, Optional<AgentMetadata> agentMetadata) {
		public LiveObservation {
			componentIdentity = Objects.requireNonNull(componentIdentity, "componentIdentity");
			address = Objects.requireNonNull(address, "address");
			association = Objects.requireNonNull(association, "association");
			agentMetadata = Objects.requireNonNull(agentMetadata, "agentMetadata");
		}
	}
}
