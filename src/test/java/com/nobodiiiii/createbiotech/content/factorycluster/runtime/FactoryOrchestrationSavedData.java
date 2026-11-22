package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentCoordinator;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentInputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputContract;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.output.ProductionOutputLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentResetService;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RecoveryLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.CreateLogisticsGateway;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.OutputLogisticsGateway;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationConfiguration;
import com.nobodiiiii.createbiotech.content.factorycluster.communication.ClusterCommunicationDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressObserver;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** Server-global, current-schema-only journal for orchestration evidence outside Computer NBT. */
public final class FactoryOrchestrationSavedData extends SavedData {
	private static final String DATA_NAME = "create_biotech_factory_orchestration";
	private static final int VERSION = 1;
	private static final int MAX_ENTRIES = 65_536;
	private final LinkedHashMap<String, CompoundTag> entries = new LinkedHashMap<>();
	private boolean persistenceValid = true;
	private InventoryIdentity.PersistentAttachmentBindingRegistry liveAttachmentBindings;
	private LogisticsNetworkDirectory liveLogisticsNetworkDirectory;
	private LogisticsAddressObserver liveLogisticsAddressObserver;

	public static FactoryOrchestrationSavedData get(MinecraftServer server) {
		return Objects.requireNonNull(server, "server").overworld().getDataStorage()
			.computeIfAbsent(new SavedData.Factory<>(FactoryOrchestrationSavedData::new,
				FactoryOrchestrationSavedData::load), DATA_NAME);
	}

	public FactoryOrchestrationSavedData() {}

	private static FactoryOrchestrationSavedData load(CompoundTag root,
		HolderLookup.Provider registries) {
		FactoryOrchestrationSavedData data = new FactoryOrchestrationSavedData();
		try {
			if (!root.getAllKeys().equals(java.util.Set.of("Version", "Entries"))
				|| !root.contains("Version", Tag.TAG_INT) || root.getInt("Version") != VERSION)
				throw new IllegalArgumentException("Foreign factory state schema");
			Tag raw = root.get("Entries");
			if (!(raw instanceof ListTag list) || list.size() > MAX_ENTRIES
				|| !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
				throw new IllegalArgumentException("Invalid factory state journal");
			for (Tag rawEntry : list) {
				CompoundTag entry = (CompoundTag) rawEntry;
				if (!entry.getAllKeys().equals(java.util.Set.of("Key", "Value"))
					|| !entry.contains("Key", Tag.TAG_STRING)
					|| !(entry.get("Value") instanceof CompoundTag value)
					|| data.entries.putIfAbsent(entry.getString("Key"), value.copy()) != null)
					throw new IllegalArgumentException("Invalid factory state entry");
			}
		} catch (RuntimeException invalid) {
			data.entries.clear(); data.persistenceValid = false;
		}
		return data;
	}

	@Override
	public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
		root.putInt("Version", VERSION); ListTag list = new ListTag();
		entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			CompoundTag child = new CompoundTag(); child.putString("Key", entry.getKey());
			child.put("Value", entry.getValue().copy()); list.add(child);
		});
		root.put("Entries", list); return root;
	}

	public boolean persistenceValid() { return persistenceValid; }

	public synchronized <T> Optional<T> read(String key, Class<T> type,
		HolderLookup.Provider registries) {
		CompoundTag encoded = entries.get(requireKey(key));
		if (encoded == null) return Optional.empty();
		Optional<T> decoded = CurrentSchemaStateNbt.load(encoded, registries, type);
		if (decoded.isEmpty()) {
			persistenceValid = false;
			throw new IllegalStateException("Malformed current-schema singleton " + key);
		}
		return decoded;
	}

	/** Enumerates a bounded namespace for exact current-schema recovery; one malformed entry fences all. */
	public synchronized <T> Map<String, T> readPrefix(String prefix, Class<T> type,
		HolderLookup.Provider registries) {
		prefix = requireKey(prefix); Objects.requireNonNull(type, "type");
		Objects.requireNonNull(registries, "registries");
		LinkedHashMap<String, T> recovered = new LinkedHashMap<>();
		for (Map.Entry<String, CompoundTag> entry : entries.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList()) {
			if (!entry.getKey().startsWith(prefix)) continue;
			T decoded = CurrentSchemaStateNbt.load(entry.getValue(), registries, type)
				.orElseThrow(() -> new IllegalStateException(
					"Malformed current-schema recovery entry " + entry.getKey()));
			recovered.put(entry.getKey(), decoded);
		}
		return Map.copyOf(recovered);
	}

	public synchronized void write(String key, Object value, HolderLookup.Provider registries) {
		if (!writeIfCapacityAvailable(key, value, registries))
			throw new IllegalStateException("Factory state journal exceeds representation bound");
	}

	/** Returns false only for a valid journal whose next singleton cannot fit. */
	private synchronized boolean writeIfCapacityAvailable(String key, Object value,
		HolderLookup.Provider registries) {
		if (!persistenceValid) throw new IllegalStateException("Factory state journal is invalid");
		key = requireKey(key); Objects.requireNonNull(value, "value");
		if (!entries.containsKey(key) && entries.size() >= MAX_ENTRIES) return false;
		entries.put(key, CurrentSchemaStateNbt.save(value, registries)); setDirty();
		return true;
	}

	/** Encodes and bounds the complete write-set before publishing one durable batch. */
	public synchronized void writeBatch(Map<String, ?> values,
		HolderLookup.Provider registries) {
		if (!persistenceValid) throw new IllegalStateException("Factory state journal is invalid");
		values = Map.copyOf(Objects.requireNonNull(values, "values"));
		Objects.requireNonNull(registries, "registries");
		if (values.isEmpty()) return;
		LinkedHashMap<String, CompoundTag> encoded = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			String key = requireKey(entry.getKey());
			Object value = Objects.requireNonNull(entry.getValue(), "value");
			encoded.put(key, CurrentSchemaStateNbt.save(value, registries));
		}
		long newKeys = encoded.keySet().stream().filter(key -> !entries.containsKey(key)).count();
		if ((long) entries.size() + newKeys > MAX_ENTRIES)
			throw new IllegalStateException("Factory state journal exceeds representation bound");
		encoded.forEach(entries::put);
		setDirty();
	}

	/**
	 * Publishes a durable Goal update and retires one Run's mutable execution artifacts in the
	 * same SavedData mutation. Every value is encoded and the final representation size is
	 * checked before either the writes or removals become visible.
	 */
	public synchronized void writeBatchAndRemoveActiveRunArtifacts(Map<String, ?> values,
		UUID runId, HolderLookup.Provider registries) {
		writeBatchAndRemoveActiveRunArtifacts(values, runId, Map.of(), registries);
	}

	/**
	 * Atomically retires a Run and its fully reconciled remote transport pair. Remote values are
	 * exact compare-and-remove preconditions: the caller cannot erase a missing, changed, active,
	 * or only-half-present protocol record. The bounded {@code recovery/global} tombstone is never
	 * part of this removal set.
	 */
	public synchronized void writeBatchAndRemoveActiveRunArtifacts(Map<String, ?> values,
		UUID runId, Map<String, ?> terminalRemoteArtifacts,
		HolderLookup.Provider registries) {
		if (!persistenceValid) throw new IllegalStateException("Factory state journal is invalid");
		values = Map.copyOf(Objects.requireNonNull(values, "values"));
		terminalRemoteArtifacts = Map.copyOf(Objects.requireNonNull(terminalRemoteArtifacts,
			"terminalRemoteArtifacts"));
		if (!terminalRemoteArtifacts.isEmpty()
			&& (terminalRemoteArtifacts.size() != 2
				|| terminalRemoteArtifacts.keySet().stream()
					.filter(key -> key.startsWith("remote_agent_dispatch/")).count() != 1
				|| terminalRemoteArtifacts.keySet().stream()
					.filter(key -> key.startsWith("remote_recovery_exchange/")).count() != 1))
			throw new IllegalArgumentException(
				"Remote Run retirement requires one exact dispatch/exchange pair");
		java.util.LinkedHashSet<String> removedKeys =
			new java.util.LinkedHashSet<>(activeRunArtifactKeys(runId));
		for (String key : terminalRemoteArtifacts.keySet())
			removedKeys.add(requireKey(key));
		Objects.requireNonNull(registries, "registries");
		LinkedHashMap<String, CompoundTag> encoded = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : values.entrySet()) {
			String key = requireKey(entry.getKey());
			if (removedKeys.contains(key))
				throw new IllegalArgumentException("Cannot rewrite a retired Run artifact");
			Object value = Objects.requireNonNull(entry.getValue(), "value");
			encoded.put(key, CurrentSchemaStateNbt.save(value, registries));
		}
		LinkedHashMap<String, CompoundTag> expectedRemote = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : terminalRemoteArtifacts.entrySet()) {
			String key = requireKey(entry.getKey());
			if (encoded.containsKey(key))
				throw new IllegalArgumentException("Cannot rewrite a retired remote artifact");
			expectedRemote.put(key, CurrentSchemaStateNbt.save(
				Objects.requireNonNull(entry.getValue(), "value"), registries));
		}
		for (Map.Entry<String, CompoundTag> expected : expectedRemote.entrySet())
			if (!expected.getValue().equals(entries.get(expected.getKey())))
				throw new IllegalStateException(
					"Remote Run retirement evidence changed before durable publication");
		long removed = removedKeys.stream().filter(entries::containsKey).count();
		long newKeys = encoded.keySet().stream().filter(key -> !entries.containsKey(key)).count();
		if ((long) entries.size() - removed + newKeys > MAX_ENTRIES)
			throw new IllegalStateException("Factory state journal exceeds representation bound");
		encoded.forEach(entries::put);
		removedKeys.forEach(entries::remove);
		if (!encoded.isEmpty() || removed > 0) setDirty();
	}

	/** Removes only an exact, preflighted key; callers retain their own durable tombstone. */
	public synchronized boolean remove(String key) {
		if (!persistenceValid) throw new IllegalStateException("Factory state journal is invalid");
		boolean removed = entries.remove(requireKey(key)) != null;
		if (removed) setDirty();
		return removed;
	}

	/** Mutable per-Run execution state; irreversible native commit/receipt evidence is excluded. */
	static Set<String> activeRunArtifactKeys(UUID runId) {
		String id = Objects.requireNonNull(runId, "runId").toString();
		return Set.of("input_contract/" + id, "output_policy/" + id,
			"output_contract/" + id, "output_probe/" + id, "signal_probe/" + id,
			"output_ledger/" + id, "safe_create_retry/" + id);
	}

	public synchronized AgentCoordinator.StateStore agentCoordinatorStore(
		HolderLookup.Provider registries) {
		return new AgentCoordinator.StateStore() {
			@Override public Optional<AgentCoordinator.PersistedState> load(UUID agentId) {
				return read("agent/" + agentId, AgentCoordinator.PersistedState.class, registries);
			}
			@Override public void persist(AgentCoordinator.PersistedState state) {
				write("agent/" + state.agentId(), state, registries);
			}
		};
	}

	public synchronized RecoveryLedger.StateStore recoveryStore(HolderLookup.Provider registries) {
		return new RecoveryLedger.StateStore() {
			@Override public Optional<RecoveryLedger.State> load() {
				return read("recovery/global", RecoveryLedger.State.class, registries);
			}
			@Override public void persist(RecoveryLedger.State state) {
				write("recovery/global", state, registries);
			}
		};
	}

	public synchronized AgentResetService.StateStore resetStore(HolderLookup.Provider registries) {
		return new AgentResetService.StateStore() {
			@Override public Optional<AgentResetService.State> load(UUID agentId) {
				return read("reset/" + agentId, AgentResetService.State.class, registries);
			}
			@Override public synchronized boolean compareAndSet(UUID agentId, long expectedRevision,
				AgentResetService.State state) {
				Optional<AgentResetService.State> current = load(agentId);
				if (current.map(AgentResetService.State::revision).orElse(-1L) != expectedRevision)
					return false;
				write("reset/" + agentId, state, registries); return true;
			}
		};
	}

	/** One exact current-schema CAS record per Cluster communication declaration. */
	public synchronized ClusterCommunicationDirectory.StateStore clusterCommunicationStore(
		HolderLookup.Provider registries) {
		return new ClusterCommunicationDirectory.StateStore() {
			@Override public Optional<ClusterCommunicationConfiguration> load(UUID clusterId) {
				clusterId = Objects.requireNonNull(clusterId, "clusterId");
				Optional<ClusterCommunicationConfiguration> loaded = read(clusterCommunicationKey(clusterId),
					ClusterCommunicationConfiguration.class, registries);
				if (loaded.isPresent() && !loaded.orElseThrow().clusterId().equals(clusterId)) {
					persistenceValid = false;
					throw new IllegalStateException("Cluster communication key/id mismatch");
				}
				return loaded;
			}

			@Override public synchronized boolean compareAndSet(UUID clusterId,
				long expectedRevision, ClusterCommunicationConfiguration replacement) {
				clusterId = Objects.requireNonNull(clusterId, "clusterId");
				replacement = Objects.requireNonNull(replacement, "replacement");
				if (!clusterId.equals(replacement.clusterId()) || expectedRevision < -1
					|| replacement.revision() != expectedRevision + 1) return false;
				long actualRevision = load(clusterId)
					.map(ClusterCommunicationConfiguration::revision).orElse(-1L);
				if (actualRevision != expectedRevision) return false;
				write(clusterCommunicationKey(clusterId), replacement, registries);
				return true;
			}
		};
	}

	/** One server-global, durable identity authority for all observed Create logistics networks. */
	public synchronized LogisticsNetworkDirectory logisticsNetworkDirectory(
		HolderLookup.Provider registries) {
		if (liveLogisticsNetworkDirectory == null) {
			LogisticsNetworkDirectory.State state = read("logistics_network_directory/global",
				LogisticsNetworkDirectory.State.class, registries).orElseGet(() ->
					new LogisticsNetworkDirectory.State(Map.of(), 1));
			liveLogisticsNetworkDirectory = LogisticsNetworkDirectory
				.fromStateWithPersistenceResult(state, persisted -> writeIfCapacityAvailable(
					"logistics_network_directory/global", persisted, registries)
						? LogisticsNetworkDirectory.PersistenceResult.PERSISTED
						: LogisticsNetworkDirectory.PersistenceResult.CAPACITY_REFUSED);
		}
		return liveLogisticsNetworkDirectory;
	}

	/** One server-global durable last-seen directory for addressed Create components. */
	public synchronized LogisticsAddressObserver logisticsAddressObserver(
		HolderLookup.Provider registries, int capacity) {
		if (liveLogisticsAddressObserver == null) {
			LogisticsAddressObserver.State state = read("logistics_address_observer/global",
				LogisticsAddressObserver.State.class, registries)
				.orElseGet(LogisticsAddressObserver.State::empty);
			liveLogisticsAddressObserver = LogisticsAddressObserver.restore(state, capacity,
				persisted -> write("logistics_address_observer/global", persisted, registries));
		}
		return liveLogisticsAddressObserver;
	}

	public synchronized AgentInputContract.StateStore inputContractStore(UUID contractId,
		HolderLookup.Provider registries) {
		Objects.requireNonNull(contractId, "contractId");
		return state -> write("input_contract/" + contractId, state, registries);
	}

	public synchronized ProductionOutputContract.Store outputContractStore(UUID contractId,
		HolderLookup.Provider registries) {
		Objects.requireNonNull(contractId, "contractId");
		return new ProductionOutputContract.Store() {
			@Override public void persist(ProductionOutputContract.Snapshot state) {
				write("output_contract/" + contractId, state, registries);
			}
			@Override public void persistLedger(ProductionOutputLedger ledger) {
				write("output_ledger/" + contractId, ledger, registries);
			}
		};
	}

	public synchronized OutputLogisticsGateway.CommitStore outputCommitStore(
		HolderLookup.Provider registries) {
		return new OutputLogisticsGateway.CommitStore() {
			@Override public Optional<OutputLogisticsGateway.PersistedCommit> load(UUID intentId) {
				return read("output_commit/" + intentId,
					OutputLogisticsGateway.PersistedCommit.class, registries);
			}
			@Override public void persist(OutputLogisticsGateway.PersistedCommit commit) {
				write("output_commit/" + commit.instruction().intentId(), commit, registries);
			}
		};
	}

	public synchronized InventoryIdentity.AttachmentBindingStore attachmentBindingStore(
		HolderLookup.Provider registries) {
		return new InventoryIdentity.AttachmentBindingStore() {
			@Override public Optional<InventoryIdentity.PersistedAttachmentBinding> load(
				SpaceAddress packager) {
				return read("attachment/" + stableAddress(packager),
					InventoryIdentity.PersistedAttachmentBinding.class, registries);
			}
			@Override public void persist(SpaceAddress packager,
				InventoryIdentity.PersistedAttachmentBinding binding) {
				write("attachment/" + stableAddress(packager), binding, registries);
			}
		};
	}

	/** One live authority per server journal; orchestration-root reload is not an inventory rebind. */
	public synchronized InventoryIdentity.PersistentAttachmentBindingRegistry attachmentBindingRegistry(
		HolderLookup.Provider registries) {
		if (liveAttachmentBindings == null)
			liveAttachmentBindings = new InventoryIdentity.PersistentAttachmentBindingRegistry(
				attachmentBindingStore(registries));
		return liveAttachmentBindings;
	}

	public synchronized CreateLogisticsGateway.CommitStore inputCommitStore(
		HolderLookup.Provider registries) {
		return new CreateLogisticsGateway.CommitStore() {
			@Override public Optional<CreateLogisticsGateway.PersistedCommit> load(UUID intentId) {
				return read("input_commit/" + intentId,
					CreateLogisticsGateway.PersistedCommit.class, registries);
			}
			@Override public void persist(CreateLogisticsGateway.PersistedCommit commit) {
				write("input_commit/" + commit.command().intentId(), commit, registries);
			}
		};
	}

	public synchronized CreateLogisticsGateway.BareCommitStore bareCommitStore(
		HolderLookup.Provider registries) {
		return new CreateLogisticsGateway.BareCommitStore() {
			@Override public Optional<CreateLogisticsGateway.PersistedBareCommit> load(UUID intentId) {
				return read("bare_commit/" + intentId,
					CreateLogisticsGateway.PersistedBareCommit.class, registries);
			}
			@Override public void persist(CreateLogisticsGateway.PersistedBareCommit commit) {
				write("bare_commit/" + commit.request().intentId(), commit, registries);
			}
		};
	}

	private static String requireKey(String key) {
		key = Objects.requireNonNull(key, "key");
		if (key.isBlank() || key.length() > 512) throw new IllegalArgumentException("Invalid journal key");
		return key;
	}

	private static String clusterCommunicationKey(UUID clusterId) {
		return "cluster_communication/" + Objects.requireNonNull(clusterId, "clusterId");
	}

	private static String stableAddress(SpaceAddress address) {
		address = Objects.requireNonNull(address, "address");
		return address.dimension().location() + "/"
			+ (address.subLevelId() == null ? "root" : address.subLevelId()) + "/"
			+ address.localPos().getX() + "," + address.localPos().getY() + ","
			+ address.localPos().getZ();
	}
}
