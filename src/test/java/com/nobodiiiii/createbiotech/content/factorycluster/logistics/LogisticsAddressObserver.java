package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObservationRegistry.AgentMetadata;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObservationRegistry.LiveObservation;

/** Atomically publishes and persists a bounded 20-tick last-seen address directory. */
public final class LogisticsAddressObserver {
	public static final int PUBLICATION_INTERVAL_TICKS = 20;
	private static final Comparator<NetworkComponentKey> KEY_ORDER = Comparator
		.comparing((NetworkComponentKey key) -> key.logisticsNetworkId().toString())
		.thenComparing(key -> key.componentIdentity().address().dimension().location().toString())
		.thenComparing(key -> String.valueOf(key.componentIdentity().address().subLevelId()))
		.thenComparingLong(key -> key.componentIdentity().address().localPos().asLong())
		.thenComparing(key -> key.componentIdentity().discriminator());

	private final int capacity;
	private final Consumer<State> persistenceSink;
	private State state;

	public LogisticsAddressObserver(int capacity) {
		this(State.empty(), capacity, ignored -> {});
	}

	private LogisticsAddressObserver(State state, int capacity, Consumer<State> persistenceSink) {
		if (capacity <= 0 || capacity > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES)
			throw new IllegalArgumentException("invalid address directory capacity");
		this.capacity = capacity;
		this.persistenceSink = Objects.requireNonNull(persistenceSink, "persistenceSink");
		State restored = Objects.requireNonNull(state, "state");
		if (restored.components().size() <= capacity) {
			this.state = restored;
			return;
		}
		LinkedHashMap<NetworkComponentKey, LogisticsAddressComponent> trimmed =
			new LinkedHashMap<>(restored.components());
		trimToCapacity(trimmed, Set.of());
		State bounded = new State(trimmed, Math.incrementExact(restored.revision()),
			restored.lastPublicationTick());
		persistenceSink.accept(bounded);
		this.state = bounded;
	}

	public static LogisticsAddressObserver restore(State state, int capacity,
		Consumer<State> persistenceSink) {
		return new LogisticsAddressObserver(state, capacity, persistenceSink);
	}

	/** Testable scoped publication; only absence on this exact network is demoted offline. */
	public synchronized void observe(UUID logisticsNetworkId, long tick,
		List<LiveObservation> live) {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		live = List.copyOf(Objects.requireNonNull(live, "live"));
		UUID exactNetwork = logisticsNetworkId;
		if (live.stream().anyMatch(observation ->
			!observation.association().logisticsNetworkId().equals(exactNetwork)))
			throw new IllegalArgumentException("observation belongs to another network");
		publish(tick, Optional.of(logisticsNetworkId), live);
	}

	/** Publishes one complete global snapshot exactly once on each aligned 20-tick boundary. */
	public synchronized boolean publishIfDue(long tick, List<LiveObservation> live) {
		if (tick < 0) throw new IllegalArgumentException("tick cannot be negative");
		if (tick % PUBLICATION_INTERVAL_TICKS != 0 || tick == state.lastPublicationTick())
			return false;
		publish(tick, Optional.empty(), List.copyOf(Objects.requireNonNull(live, "live")));
		return true;
	}

	public synchronized List<LogisticsAddressComponent> snapshot(UUID logisticsNetworkId) {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		UUID exactNetwork = logisticsNetworkId;
		return state.components().entrySet().stream()
			.filter(entry -> entry.getKey().logisticsNetworkId().equals(exactNetwork))
			.sorted(Map.Entry.comparingByKey(KEY_ORDER)).map(Map.Entry::getValue).toList();
	}

	public synchronized State state() {
		return state;
	}

	private void publish(long tick, Optional<UUID> scopedNetwork, List<LiveObservation> live) {
		if (tick < 0) throw new IllegalArgumentException("tick cannot be negative");
		LinkedHashMap<NetworkComponentKey, LogisticsAddressComponent> prospective =
			new LinkedHashMap<>(state.components());
		prospective.replaceAll((key, component) -> scopedNetwork.isEmpty()
			|| scopedNetwork.orElseThrow().equals(key.logisticsNetworkId())
				? component.withStatus(ComponentStatus.OFFLINE) : component);

		LinkedHashMap<NetworkComponentKey, MutableLiveComponent> merged = new LinkedHashMap<>();
		for (LiveObservation observation : live) {
			UUID network = observation.association().logisticsNetworkId();
			if (scopedNetwork.isPresent() && !scopedNetwork.orElseThrow().equals(network)) continue;
			NetworkComponentKey key = new NetworkComponentKey(network,
				observation.componentIdentity());
			merged.computeIfAbsent(key, ignored -> new MutableLiveComponent(observation))
				.merge(observation);
		}
		for (Map.Entry<NetworkComponentKey, MutableLiveComponent> entry : merged.entrySet())
			prospective.put(entry.getKey(), entry.getValue().freeze(tick));

		Map<RouteClaim, Integer> claimCounts = new LinkedHashMap<>();
		for (LogisticsAddressComponent component : prospective.values())
			if (component.lastSeenTick() == tick && component.status() != ComponentStatus.OFFLINE)
				claimCounts.merge(new RouteClaim(component.logisticsNetworkId(),
					component.createAddress()), 1, Integer::sum);
		prospective.replaceAll((key, component) -> component.lastSeenTick() == tick
			&& component.status() != ComponentStatus.OFFLINE
			&& claimCounts.getOrDefault(new RouteClaim(component.logisticsNetworkId(),
				component.createAddress()), 0) > 1
					? component.withStatus(ComponentStatus.CONFLICT) : component);

		trimToCapacity(prospective, merged.keySet());
		State next = new State(prospective, Math.incrementExact(state.revision()), tick);
		persistenceSink.accept(next);
		state = next;
	}

	private void trimToCapacity(LinkedHashMap<NetworkComponentKey, LogisticsAddressComponent> values,
		Set<NetworkComponentKey> liveKeys) {
		if (values.size() <= capacity) return;
		ArrayList<NetworkComponentKey> eviction = new ArrayList<>(values.keySet());
		eviction.sort(Comparator
			.comparing((NetworkComponentKey key) -> liveKeys.contains(key))
			.thenComparingLong(key -> values.get(key).lastSeenTick())
			.thenComparing(KEY_ORDER));
		for (NetworkComponentKey key : eviction) {
			if (values.size() <= capacity) break;
			values.remove(key);
		}
	}

	public record NetworkComponentKey(UUID logisticsNetworkId,
		ComponentIdentity componentIdentity) {
		public NetworkComponentKey {
			logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId,
				"logisticsNetworkId");
			componentIdentity = Objects.requireNonNull(componentIdentity, "componentIdentity");
		}
	}

	public record State(Map<NetworkComponentKey, LogisticsAddressComponent> components,
		long revision, long lastPublicationTick) {
		public State {
			components = Map.copyOf(Objects.requireNonNull(components, "components"));
			if (components.size() > FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES
				|| revision < 0 || lastPublicationTick < -1)
				throw new IllegalArgumentException("invalid logistics address observer state");
			for (Map.Entry<NetworkComponentKey, LogisticsAddressComponent> entry :
				components.entrySet())
				if (!entry.getKey().logisticsNetworkId()
					.equals(entry.getValue().logisticsNetworkId())
					|| !entry.getKey().componentIdentity()
						.equals(entry.getValue().componentIdentity()))
					throw new IllegalArgumentException("address component key differs from value");
		}

		public static State empty() {
			return new State(Map.of(), 0, -1);
		}
	}

	private static final class MutableLiveComponent {
		private final UUID network;
		private final ComponentIdentity identity;
		private final String createAddress;
		private final LogisticsAddressComponent.ComponentType componentType;
		private final LinkedHashSet<ComponentRole> roles = new LinkedHashSet<>();
		private Optional<AgentMetadata> agentMetadata;
		private boolean incompatible;

		private MutableLiveComponent(LiveObservation first) {
			network = first.association().logisticsNetworkId();
			identity = first.componentIdentity();
			createAddress = first.address().createAddress();
			componentType = first.address().componentType();
			agentMetadata = first.agentMetadata();
		}

		private MutableLiveComponent merge(LiveObservation observation) {
			if (!createAddress.equals(observation.address().createAddress())
				|| componentType != observation.address().componentType()) incompatible = true;
			roles.add(observation.address().role());
			if (observation.agentMetadata().isPresent()) {
				if (agentMetadata.isPresent()
					&& !agentMetadata.equals(observation.agentMetadata())) incompatible = true;
				else agentMetadata = observation.agentMetadata();
			}
			return this;
		}

		private LogisticsAddressComponent freeze(long tick) {
			Optional<UUID> agentId = agentMetadata.map(AgentMetadata::agentId);
			long generation = agentMetadata.map(AgentMetadata::memberGeneration).orElse(0L);
			return new LogisticsAddressComponent(network, identity, createAddress, componentType,
				roles, agentId, generation, tick,
				incompatible ? ComponentStatus.CONFLICT : ComponentStatus.ONLINE);
		}
	}

	private record RouteClaim(UUID logisticsNetworkId, String createAddress) {}
}
