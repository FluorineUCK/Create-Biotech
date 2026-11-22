package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

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

/**
 * Durable production outbox for immutable radio routes. A fresh router replays every frozen
 * envelope to reconstruct pending hops and lease protocol state. Entries are pending-only:
 * callers retire a final delivery only after its recipient-side durable state is published.
 */
public final class DurableRadioOutbox {
	private final int capacity;
	private final StateStore store;
	private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
	private final Set<UUID> submittedThisSession = new LinkedHashSet<>();
	private final Set<UUID> appliedRouteRetirements = new LinkedHashSet<>();

	public DurableRadioOutbox(int capacity, StateStore store) {
		if (capacity <= 0) throw new IllegalArgumentException("radio outbox capacity must be positive");
		this.capacity = capacity;
		this.store = Objects.requireNonNull(store, "store");
		store.load().ifPresent(state -> {
			if (state.entries().size() > capacity)
				throw new IllegalArgumentException("durable radio outbox exceeds capacity");
			entries.putAll(state.entries());
		});
	}

	public boolean offer(RadioEnvelope envelope, List<RadioEndpoint.Hop> relays) {
		envelope = Objects.requireNonNull(envelope, "envelope");
		relays = List.copyOf(Objects.requireNonNull(relays, "relays"));
		Entry known = entries.get(envelope.messageId());
		if (known != null) return known.envelope().equals(envelope) && known.relays().equals(relays);
		if (entries.size() >= capacity) return false;
		LinkedHashMap<UUID, Entry> next = new LinkedHashMap<>(entries);
		next.put(envelope.messageId(), new Entry(envelope, relays,
			RadioRouter.RouteStatus.QUEUED));
		commit(next);
		return true;
	}

	/** Advances at most the router's one hop per server tick and returns unapplied final deliveries. */
	public List<RadioRouter.Delivery> pump(RadioRouter router, long serverTick) {
		return pump(router, serverTick, null);
	}

	/** Advances all frozen routes against the one immutable frame captured for this server tick. */
	public List<RadioRouter.Delivery> pump(RadioRouter router, long serverTick,
		RadioTopologyFrame frame) {
		router = Objects.requireNonNull(router, "router");
		for (Entry entry : entries.values()) {
			if (submittedThisSession.contains(entry.envelope().messageId())) continue;
			router.submit(entry.envelope(), entry.relays());
			submittedThisSession.add(entry.envelope().messageId());
		}
		RadioRouter.TickResult advanced = frame == null ? router.tick(serverTick)
			: router.tick(serverTick, frame);
		LinkedHashMap<UUID, Entry> next = new LinkedHashMap<>(entries);
		for (Entry entry : entries.values()) {
			if (!submittedThisSession.contains(entry.envelope().messageId())) continue;
			next.put(entry.envelope().messageId(), entry.withStatus(
				router.status(entry.envelope().messageId())));
		}
		if (!next.equals(entries)) commit(next);
		ArrayList<RadioRouter.Delivery> deliveries = new ArrayList<>();
		for (RadioRouter.Delivery delivery : advanced.deliveries()) {
			Entry entry = entries.get(delivery.envelope().messageId());
			if (delivery.finalRecipient() && entry != null)
				deliveries.add(delivery);
		}
		return List.copyOf(deliveries);
	}
	public boolean markApplied(UUID messageId) {
		messageId = Objects.requireNonNull(messageId, "messageId");
		Entry entry = entries.get(messageId);
		if (entry == null || entry.status() != RadioRouter.RouteStatus.DELIVERED) return false;
		LinkedHashMap<UUID, Entry> next = new LinkedHashMap<>(entries);
		next.remove(messageId);
		commit(next);
		submittedThisSession.remove(messageId);
		appliedRouteRetirements.add(messageId);
		return true;
	}

	/**
	 * Removes volatile DELIVERED routes only after their durable outbox owner was acknowledged and
	 * removed. A crash before this call reloads neither the outbox entry nor its volatile route.
	 */
	public int retireAppliedRoutes(RadioRouter router) {
		router = Objects.requireNonNull(router, "router");
		int retired = 0;
		for (UUID messageId : List.copyOf(appliedRouteRetirements)) {
			if (!router.retireDeliveredRoute(messageId)) continue;
			appliedRouteRetirements.remove(messageId);
			retired++;
		}
		return retired;
	}

	public Optional<RadioRouter.RouteStatus> status(UUID messageId) {
		return Optional.ofNullable(entries.get(Objects.requireNonNull(messageId, "messageId")))
			.map(Entry::status);
	}

	public Optional<Entry> entry(UUID messageId) {
		return Optional.ofNullable(entries.get(Objects.requireNonNull(messageId, "messageId")));
	}

	/**
	 * Bounded durable business-application retry set. Transport/protocol delivery has already
	 * happened exactly once; callers retry only the immutable recipient-side composition step.
	 */
	public List<Entry> deliveredButUnapplied(int limit, Optional<UUID> afterExclusive) {
		if (limit <= 0) throw new IllegalArgumentException("retry batch limit must be positive");
		afterExclusive = Objects.requireNonNull(afterExclusive, "afterExclusive");
		List<Entry> retryable = entries.values().stream()
			.filter(entry -> entry.status() == RadioRouter.RouteStatus.DELIVERED)
			.sorted(Comparator.comparing(entry -> entry.envelope().messageId()))
			.toList();
		if (retryable.isEmpty()) return List.of();
		int start = 0;
		if (afterExclusive.isPresent()) {
			UUID cursor = afterExclusive.orElseThrow();
			while (start < retryable.size()
				&& retryable.get(start).envelope().messageId().compareTo(cursor) <= 0) start++;
			if (start == retryable.size()) start = 0;
		}
		ArrayList<Entry> batch = new ArrayList<>(Math.min(limit, retryable.size()));
		for (int offset = 0; offset < Math.min(limit, retryable.size()); offset++)
			batch.add(retryable.get((start + offset) % retryable.size()));
		return List.copyOf(batch);
	}

	public State snapshot() { return new State(entries); }

	/** Preflights removal for an enclosing multi-record journal transaction. */
	public State without(Iterable<UUID> messageIds) {
		LinkedHashMap<UUID, Entry> next = new LinkedHashMap<>(entries);
		for (UUID messageId : Objects.requireNonNull(messageIds, "messageIds"))
			next.remove(Objects.requireNonNull(messageId, "messageId"));
		return new State(next);
	}

	/** Installs only a snapshot already committed by the composition root. */
	public void installPersistedState(State state) {
		state = Objects.requireNonNull(state, "state");
		if (state.entries().size() > capacity)
			throw new IllegalArgumentException("durable radio outbox exceeds capacity");
		entries.clear();
		entries.putAll(state.entries());
		submittedThisSession.retainAll(state.entries().keySet());
		appliedRouteRetirements.removeAll(state.entries().keySet());
	}

	private void commit(Map<UUID, Entry> next) {
		State state = new State(next);
		store.persist(state);
		installPersistedState(state);
	}

	public interface StateStore {
		default Optional<State> load() { return Optional.empty(); }
		void persist(State state);
	}

	public record State(Map<UUID, Entry> entries) {
		public State {
			entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
			for (Map.Entry<UUID, Entry> entry : entries.entrySet())
				if (!entry.getKey().equals(entry.getValue().envelope().messageId()))
					throw new IllegalArgumentException("radio outbox key mismatch");
		}
	}

	public record Entry(RadioEnvelope envelope, List<RadioEndpoint.Hop> relays,
		RadioRouter.RouteStatus status) {
		public Entry {
			envelope = Objects.requireNonNull(envelope, "envelope");
			relays = List.copyOf(Objects.requireNonNull(relays, "relays"));
			status = Objects.requireNonNull(status, "status");
		}
		Entry withStatus(RadioRouter.RouteStatus next) {
			return new Entry(envelope, relays, next);
		}
	}
}
