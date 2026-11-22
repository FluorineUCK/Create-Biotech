package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;


import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Server-global identity directory for Create logistics networks. Entries intentionally outlive
 * observed components so that ordinals are never reused.
 */
public final class LogisticsNetworkDirectory {
	private static final String DEFAULT_NAME_KEY =
		"create_biotech.factory_cluster.logistics.default_name";
	private static final long FIRST_ORDINAL = 1;
	// Stable directory representation bound, independent of old production slots and scan budgets.
	static final int MAX_NETWORKS = 16_384;
	private final LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> entries;
	private long nextOrdinal;
	private final Function<State, PersistenceResult> persistenceSink;

	public LogisticsNetworkDirectory() {
		this(new State(Map.of(), FIRST_ORDINAL), ignored -> PersistenceResult.PERSISTED);
	}

	private LogisticsNetworkDirectory(State state,
		Function<State, PersistenceResult> persistenceSink) {
		state = Objects.requireNonNull(state, "state");
		this.entries = new LinkedHashMap<>(state.entries());
		this.nextOrdinal = state.nextOrdinal();
		this.persistenceSink = Objects.requireNonNull(persistenceSink, "persistenceSink");
	}

	/**
	 * Compatibility wrapper for callers that require an identity. New bounded-aware composition
	 * paths must use {@link #tryEnsure(UUID)} instead.
	 */
	public LogisticsNetworkDirectoryEntry ensure(UUID logisticsNetworkId) {
		return tryEnsure(logisticsNetworkId).entry().orElseThrow(() ->
			new IllegalStateException("Logistics identity allocation was refused"));
	}

	/** Returns an explicit durable allocation result; bounded refusals never mutate live state. */
	public synchronized AllocationResult tryEnsure(UUID logisticsNetworkId) {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		LogisticsNetworkDirectoryEntry existing = entries.get(logisticsNetworkId);
		if (existing != null) return AllocationResult.existing(existing);
		if (entries.size() >= MAX_NETWORKS
			|| nextOrdinal == Long.MAX_VALUE)
			return AllocationResult.capacityRefused();
		long allocated = nextOrdinal;
		long advanced = Math.incrementExact(allocated);
		LogisticsNetworkDirectoryEntry created = new LogisticsNetworkDirectoryEntry(logisticsNetworkId,
			allocated, Optional.empty(), 0);
		LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> prospectiveEntries =
			new LinkedHashMap<>(entries);
		prospectiveEntries.put(logisticsNetworkId, created);
		PersistenceResult persisted = Objects.requireNonNull(
			persistenceSink.apply(new State(prospectiveEntries, advanced)),
			"persistence result");
		if (persisted == PersistenceResult.CAPACITY_REFUSED)
			return AllocationResult.persistenceRefused();
		entries.put(logisticsNetworkId, created);
		nextOrdinal = advanced;
		return AllocationResult.allocated(created);
	}

	/** Atomically replaces a global alias when the caller holds this entry's exact revision. */
	public synchronized boolean compareAndSetAlias(UUID logisticsNetworkId, long expectedRevision,
		Optional<String> customAlias) {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		customAlias = Objects.requireNonNull(customAlias, "customAlias")
			.map(LogisticsNetworkDirectoryEntry::canonicalAlias);
		LogisticsNetworkDirectoryEntry current = entries.get(logisticsNetworkId);
		if (current == null || current.revision() != expectedRevision
			|| current.customAlias().equals(customAlias)) return false;
		LogisticsNetworkDirectoryEntry replacement = new LogisticsNetworkDirectoryEntry(
			logisticsNetworkId, current.ordinal(), customAlias,
			Math.incrementExact(current.revision()));
		LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> prospectiveEntries =
			new LinkedHashMap<>(entries);
		prospectiveEntries.put(logisticsNetworkId, replacement);
		PersistenceResult persisted = Objects.requireNonNull(
			persistenceSink.apply(new State(prospectiveEntries, nextOrdinal)),
			"persistence result");
		if (persisted == PersistenceResult.CAPACITY_REFUSED) return false;
		entries.put(logisticsNetworkId, replacement);
		return true;
	}
	public synchronized Optional<LogisticsNetworkDirectoryEntry> find(UUID logisticsNetworkId) {
		return Optional.ofNullable(entries.get(Objects.requireNonNull(logisticsNetworkId,
			"logisticsNetworkId")));
	}

	/** Observations are ephemeral; forgetting one must not delete its global identity. */
	public void forgetObservedComponent(UUID logisticsNetworkId) {
		Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
	}

	public synchronized CompoundTag save() {
		CompoundTag root = new CompoundTag();
		root.putLong("NextOrdinal", nextOrdinal);
		ListTag encodedEntries = new ListTag();
		entries.values().stream().sorted(Comparator.comparing(entry ->
			entry.logisticsNetworkId().toString())).forEach(entry -> encodedEntries.add(saveEntry(entry)));
		root.put("Entries", encodedEntries);
		return root;
	}

	public static LogisticsNetworkDirectory load(CompoundTag root) {
		State state = decode(Objects.requireNonNull(root, "root"));
		return new LogisticsNetworkDirectory(state, ignored -> PersistenceResult.PERSISTED);
	}

	public static Component displayName(LogisticsNetworkDirectoryEntry entry) {
		entry = Objects.requireNonNull(entry, "entry");
		if (entry.customAlias().isPresent()) return Component.literal(entry.customAlias().orElseThrow());
		return Component.translatable(DEFAULT_NAME_KEY, entry.ordinal());
	}

	public synchronized State snapshot() { return new State(entries, nextOrdinal); }

	/** Rehydrates a directory whose every durable mutation publishes a complete current-schema state. */
	public static LogisticsNetworkDirectory fromState(State state, Consumer<State> persistenceSink) {
		persistenceSink = Objects.requireNonNull(persistenceSink, "persistenceSink");
		Consumer<State> exactSink = persistenceSink;
		return new LogisticsNetworkDirectory(state, persisted -> {
			exactSink.accept(persisted);
			return PersistenceResult.PERSISTED;
		});
	}

	/** Result-aware persistence seam used by the bounded server-global journal. */
	public static LogisticsNetworkDirectory fromStateWithPersistenceResult(State state,
		Function<State, PersistenceResult> persistenceSink) {
		return new LogisticsNetworkDirectory(state, persistenceSink);
	}

	private static CompoundTag saveEntry(LogisticsNetworkDirectoryEntry entry) {
		CompoundTag encoded = new CompoundTag();
		encoded.putUUID("NetworkId", entry.logisticsNetworkId());
		encoded.putLong("Ordinal", entry.ordinal());
		entry.customAlias().ifPresent(alias -> encoded.putString("CustomAlias", alias));
		encoded.putLong("Revision", entry.revision());
		return encoded;
	}

	private static State decode(CompoundTag root) {
		if (!root.getAllKeys().equals(java.util.Set.of("NextOrdinal", "Entries"))
			|| !root.contains("NextOrdinal", Tag.TAG_LONG) || !(root.get("Entries") instanceof ListTag rawEntries)
			|| rawEntries.size() > MAX_NETWORKS
			|| !rawEntries.isEmpty() && rawEntries.getElementType() != Tag.TAG_COMPOUND)
			throw new IllegalArgumentException("Malformed logistics network directory");
		LinkedHashMap<UUID, LogisticsNetworkDirectoryEntry> decoded = new LinkedHashMap<>();
		for (Tag rawEntry : rawEntries) {
			if (!(rawEntry instanceof CompoundTag entry))
				throw new IllegalArgumentException("Malformed logistics network entry");
			if (!exactEntryKeys(entry) || !entry.hasUUID("NetworkId") || !entry.contains("Ordinal", Tag.TAG_LONG)
				|| !entry.contains("Revision", Tag.TAG_LONG))
				throw new IllegalArgumentException("Malformed logistics network entry");
			if (entry.contains("CustomAlias") && !entry.contains("CustomAlias", Tag.TAG_STRING))
				throw new IllegalArgumentException("Malformed logistics network alias");
			Optional<String> alias = entry.contains("CustomAlias", Tag.TAG_STRING)
				? Optional.of(entry.getString("CustomAlias")) : Optional.empty();
			LogisticsNetworkDirectoryEntry value = new LogisticsNetworkDirectoryEntry(
				entry.getUUID("NetworkId"), entry.getLong("Ordinal"), alias, entry.getLong("Revision"));
			if (decoded.putIfAbsent(value.logisticsNetworkId(), value) != null)
				throw new IllegalArgumentException("Duplicate logistics network id");
		}
		return new State(decoded, root.getLong("NextOrdinal"));
	}

	private static boolean exactEntryKeys(CompoundTag entry) {
		return entry.getAllKeys().equals(java.util.Set.of("NetworkId", "Ordinal", "Revision"))
			|| entry.getAllKeys().equals(java.util.Set.of("NetworkId", "Ordinal", "CustomAlias",
				"Revision"));
	}

	/** Persistence snapshot shared with the read-only legacy import format. */
	public record State(Map<UUID, LogisticsNetworkDirectoryEntry> entries, long nextOrdinal) {
		public State {
			entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
			if (entries.size() > MAX_NETWORKS
				|| nextOrdinal <= 0) throw new IllegalArgumentException("Invalid logistics directory state");
			Set<Long> ordinals = new HashSet<>();
			for (Map.Entry<UUID, LogisticsNetworkDirectoryEntry> entry : entries.entrySet()) {
				if (!entry.getKey().equals(entry.getValue().logisticsNetworkId())
					|| entry.getValue().ordinal() >= nextOrdinal
					|| !ordinals.add(entry.getValue().ordinal()))
					throw new IllegalArgumentException("Invalid logistics directory state");
			}
		}
	}

	public enum AllocationStatus {
		EXISTING,
		ALLOCATED,
		CAPACITY_REFUSED,
		PERSISTENCE_REFUSED
	}

	public enum PersistenceResult {
		PERSISTED,
		CAPACITY_REFUSED
	}

	public record AllocationResult(AllocationStatus status,
		Optional<LogisticsNetworkDirectoryEntry> entry) {
		public AllocationResult {
			status = Objects.requireNonNull(status, "status");
			entry = Objects.requireNonNull(entry, "entry");
			boolean succeeded = status == AllocationStatus.EXISTING
				|| status == AllocationStatus.ALLOCATED;
			if (succeeded != entry.isPresent())
				throw new IllegalArgumentException("allocation result entry differs from status");
		}

		public static AllocationResult existing(LogisticsNetworkDirectoryEntry entry) {
			return new AllocationResult(AllocationStatus.EXISTING, Optional.of(entry));
		}

		public static AllocationResult allocated(LogisticsNetworkDirectoryEntry entry) {
			return new AllocationResult(AllocationStatus.ALLOCATED, Optional.of(entry));
		}

		public static AllocationResult capacityRefused() {
			return new AllocationResult(AllocationStatus.CAPACITY_REFUSED, Optional.empty());
		}

		public static AllocationResult persistenceRefused() {
			return new AllocationResult(AllocationStatus.PERSISTENCE_REFUSED, Optional.empty());
		}

		public boolean succeeded() {
			return status == AllocationStatus.EXISTING || status == AllocationStatus.ALLOCATED;
		}

		public boolean allocated() { return status == AllocationStatus.ALLOCATED; }
	}
}
