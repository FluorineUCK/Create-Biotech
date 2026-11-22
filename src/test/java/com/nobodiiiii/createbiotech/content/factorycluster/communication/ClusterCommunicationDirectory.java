package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The sole Cluster-primary directory. The supplied store is the durable CAS
 * boundary; this class only retains a coherent server-thread cache.
 */
public final class ClusterCommunicationDirectory implements ClusterPrimaryFrequencyResolver {
	private final StateStore store;
	private final Map<UUID, ClusterCommunicationConfiguration> configurations =
		new LinkedHashMap<>();

	/** Test-only in-memory directory. Production supplies SavedData-backed storage. */
	public ClusterCommunicationDirectory() {
		this(new InMemoryStateStore());
	}

	public ClusterCommunicationDirectory(StateStore store) {
		this.store = Objects.requireNonNull(store, "store");
	}

	public synchronized Optional<ClusterCommunicationConfiguration> configuration(UUID clusterId) {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		ClusterCommunicationConfiguration cached = configurations.get(clusterId);
		if (cached != null) return Optional.of(cached);
		Optional<ClusterCommunicationConfiguration> loaded = store.load(clusterId);
		if (loaded.isPresent()) configurations.put(clusterId, loaded.orElseThrow());
		return loaded;
	}

	/** Creates the only permitted default: blank primary and ordinary Cluster role. */
	public synchronized ClusterCommunicationConfiguration ensure(UUID clusterId) {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		ClusterCommunicationConfiguration current = configuration(clusterId).orElse(null);
		if (current != null) return current;
		ClusterCommunicationConfiguration initial = new ClusterCommunicationConfiguration(clusterId,
			CommunicationFrequency.UNCONFIGURED, ClusterServiceRole.STANDARD, 0);
		if (store.compareAndSet(clusterId, -1, initial)) {
			configurations.put(clusterId, initial);
			return initial;
		}
		return configuration(clusterId).orElseThrow(() -> new IllegalStateException(
			"Cluster communication initialization lost its durable CAS"));
	}

	/**
	 * Publishes exactly one successor revision. The replacement must retain the
	 * Cluster identity and advance the revision by exactly one.
	 */
	public synchronized boolean compareAndSet(UUID clusterId, long expectedRevision,
		ClusterCommunicationConfiguration replacement) {
		clusterId = Objects.requireNonNull(clusterId, "clusterId");
		replacement = Objects.requireNonNull(replacement, "replacement");
		if (!clusterId.equals(replacement.clusterId()) || expectedRevision < 0
			|| replacement.revision() != expectedRevision + 1) return false;
		ClusterCommunicationConfiguration current = configuration(clusterId).orElse(null);
		if (current == null || current.revision() != expectedRevision) return false;
		if (!store.compareAndSet(clusterId, expectedRevision, replacement)) {
			configurations.remove(clusterId);
			return false;
		}
		configurations.put(clusterId, replacement);
		return true;
	}

	@Override
	public synchronized CommunicationFrequency primaryFrequency(UUID clusterId) {
		return configuration(Objects.requireNonNull(clusterId, "clusterId"))
			.map(ClusterCommunicationConfiguration::primaryFrequency)
			.orElse(CommunicationFrequency.UNCONFIGURED);
	}

	public interface StateStore {
		Optional<ClusterCommunicationConfiguration> load(UUID clusterId);
		boolean compareAndSet(UUID clusterId, long expectedRevision,
			ClusterCommunicationConfiguration replacement);
	}

	private static final class InMemoryStateStore implements StateStore {
		private final Map<UUID, ClusterCommunicationConfiguration> values = new LinkedHashMap<>();

		@Override public Optional<ClusterCommunicationConfiguration> load(UUID clusterId) {
			return Optional.ofNullable(values.get(Objects.requireNonNull(clusterId, "clusterId")));
		}

		@Override public boolean compareAndSet(UUID clusterId, long expectedRevision,
			ClusterCommunicationConfiguration replacement) {
			ClusterCommunicationConfiguration current = values.get(Objects.requireNonNull(clusterId,
				"clusterId"));
			long actualRevision = current == null ? -1 : current.revision();
			if (actualRevision != expectedRevision) return false;
			values.put(clusterId, Objects.requireNonNull(replacement, "replacement"));
			return true;
		}
	}
}
