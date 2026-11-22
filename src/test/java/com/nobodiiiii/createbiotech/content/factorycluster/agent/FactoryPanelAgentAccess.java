package com.nobodiiiii.createbiotech.content.factorycluster.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Mixin API for Biotech's isolated native Factory Panel compound. */
public interface FactoryPanelAgentAccess {
	String ROOT_KEY = "CreateBiotechAgent";
	String PROVIDER_ROOT_KEY = "CreateBiotechFactoryProvider";
	Set<String> PROVIDER_KEYS = Set.of("routePriority");
	Set<String> CONFIG_KEYS = Set.of("agentId", "ownerClusterId", "clusterEpochId",
		"configurationRevision", "agentGeneration", "role", "address",
		"routePriority", "frequency", "completionMode", "signalSelector", "outputMode", "outputPolicyGeneration",
		"returnWarehouse");
	Set<String> ROUTE_KEYS = Set.of("network", "address");
	Set<String> OPTIONAL_SELECTOR_KEYS = Set.of("present", "value");

	Optional<Configuration> createBiotech$getAgentConfiguration();
	void createBiotech$setAgentConfiguration(@Nullable Configuration configuration);
	/** Batch composition seam: persists/syncs the BE but defers runtime formation rebuild. */
	void createBiotech$setAgentConfigurationSilently(@Nullable Configuration configuration);
	Optional<ProviderConfiguration> createBiotech$getProviderConfiguration();
	void createBiotech$setProviderConfiguration(ProviderConfiguration configuration);

	static void writeProviderConfiguration(CompoundTag root,
		@Nullable ProviderConfiguration configuration) {
		Objects.requireNonNull(root, "root");
		if (configuration == null) {
			root.remove(PROVIDER_ROOT_KEY);
			return;
		}
		CompoundTag tag = new CompoundTag();
		tag.putInt("routePriority", configuration.routePriority());
		root.put(PROVIDER_ROOT_KEY, tag);
	}

	static Optional<ProviderConfiguration> readProviderConfiguration(CompoundTag root) {
		Objects.requireNonNull(root, "root");
		if (!root.contains(PROVIDER_ROOT_KEY, Tag.TAG_COMPOUND)) return Optional.empty();
		CompoundTag tag = root.getCompound(PROVIDER_ROOT_KEY);
		if (!tag.getAllKeys().equals(PROVIDER_KEYS)
			|| !tag.contains("routePriority", Tag.TAG_INT)) return Optional.empty();
		return Optional.of(new ProviderConfiguration(tag.getInt("routePriority")));
	}

	static void writeConfiguration(CompoundTag root, @Nullable Configuration configuration) {
		Objects.requireNonNull(root, "root");
		if (configuration == null) {
			root.remove(ROOT_KEY);
			return;
		}
		CompoundTag tag = new CompoundTag();
		tag.putUUID("agentId", configuration.agentId());
		tag.putUUID("ownerClusterId", configuration.ownerClusterId());
		tag.putUUID("clusterEpochId", configuration.clusterEpochId());
		tag.putLong("configurationRevision", configuration.configurationRevision());
		tag.putLong("agentGeneration", configuration.agentGeneration());
		tag.putString("role", configuration.role().name());
		tag.putString("address", configuration.normalizedAddress());
		tag.putInt("routePriority", configuration.routePriority());
		configuration.frequency().write(tag, "frequency");
		tag.putString("completionMode", configuration.completionMode().name());
		CompoundTag signalSelector = new CompoundTag();
		signalSelector.putBoolean("present", configuration.signalSelector().isPresent());
		signalSelector.put("value", configuration.signalSelector()
			.map(AgentSignalSelector::save).orElseGet(CompoundTag::new));
		tag.put("signalSelector", signalSelector);
		tag.putString("outputMode", configuration.outputMode().name());
		tag.putLong("outputPolicyGeneration", configuration.outputPolicyGeneration());
		CompoundTag warehouse = new CompoundTag();
		warehouse.putUUID("network", configuration.returnWarehouseRoute().logisticsNetworkId());
		warehouse.putString("address", configuration.returnWarehouseRoute().address());
		tag.put("returnWarehouse", warehouse);
		root.put(ROOT_KEY, tag);
	}

	static Optional<Configuration> readConfiguration(CompoundTag root) {
		Objects.requireNonNull(root, "root");
		if (!root.contains(ROOT_KEY, Tag.TAG_COMPOUND)) return Optional.empty();
		CompoundTag tag = root.getCompound(ROOT_KEY);
		if (!tag.getAllKeys().equals(CONFIG_KEYS)
			|| !tag.hasUUID("agentId")
			|| !tag.hasUUID("ownerClusterId")
			|| !tag.hasUUID("clusterEpochId")
			|| !tag.contains("configurationRevision", Tag.TAG_LONG)
			|| !tag.contains("agentGeneration", Tag.TAG_LONG)
			|| !tag.contains("role", Tag.TAG_STRING)
			|| !tag.contains("address", Tag.TAG_STRING)
			|| !tag.contains("routePriority", Tag.TAG_INT)
			|| !tag.contains("frequency", Tag.TAG_STRING)
			|| !tag.contains("completionMode", Tag.TAG_STRING)
			|| !tag.contains("signalSelector", Tag.TAG_COMPOUND)
			|| !tag.contains("outputMode", Tag.TAG_STRING)
			|| !tag.contains("outputPolicyGeneration", Tag.TAG_LONG)
			|| !tag.contains("returnWarehouse", Tag.TAG_COMPOUND)) return Optional.empty();
		CompoundTag warehouse = tag.getCompound("returnWarehouse");
		CompoundTag signalSelector = tag.getCompound("signalSelector");
		byte selectorPresent = signalSelector.getByte("present");
		if (!warehouse.getAllKeys().equals(ROUTE_KEYS) || !warehouse.hasUUID("network")
			|| !warehouse.contains("address", Tag.TAG_STRING)
			|| !signalSelector.getAllKeys().equals(OPTIONAL_SELECTOR_KEYS)
			|| !signalSelector.contains("present", Tag.TAG_BYTE)
			|| selectorPresent != 0 && selectorPresent != 1
			|| !signalSelector.contains("value", Tag.TAG_COMPOUND)) return Optional.empty();
		try {
			Optional<AgentSignalSelector> selector = signalSelector.getBoolean("present")
				? AgentSignalSelector.load(signalSelector.getCompound("value")) : Optional.empty();
			if (signalSelector.getBoolean("present") != selector.isPresent()
				|| !signalSelector.getBoolean("present")
				&& !signalSelector.getCompound("value").isEmpty()) return Optional.empty();
			Optional<CommunicationFrequency> frequency = CommunicationFrequency.read(tag, "frequency");
			if (frequency.isEmpty()) return Optional.empty();
			return Optional.of(new Configuration(tag.getUUID("agentId"), tag.getUUID("ownerClusterId"),
				tag.getUUID("clusterEpochId"), tag.getLong("configurationRevision"),
				tag.getLong("agentGeneration"), AgentEndpointRole.valueOf(tag.getString("role")),
				tag.getString("address"), tag.getInt("routePriority"),
				frequency.orElseThrow(),
				AgentCompletionMode.valueOf(tag.getString("completionMode")),
				selector,
				AgentEndpointState.OutputMode.valueOf(tag.getString("outputMode")),
				tag.getLong("outputPolicyGeneration"), new CreateRouteKey(
					warehouse.getUUID("network"), warehouse.getString("address"))));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	/** Stateful helper matching the Mixin's native BE read/write lifecycle. */
	final class AgentCompound {
		@Nullable private Configuration configuration;

		public Optional<Configuration> get() {
			return Optional.ofNullable(configuration);
		}

		public void set(@Nullable Configuration configuration) {
			this.configuration = configuration;
		}

		public void read(CompoundTag root) {
			configuration = readConfiguration(root).orElse(null);
		}

		public void write(CompoundTag root) {
			writeConfiguration(root, configuration);
		}
	}

	final class ProviderCompound {
		@Nullable private ProviderConfiguration configuration;

		public Optional<ProviderConfiguration> get() {
			return Optional.ofNullable(configuration);
		}

		public void set(@Nullable ProviderConfiguration configuration) {
			this.configuration = configuration;
		}

		public void read(CompoundTag root) {
			configuration = readProviderConfiguration(root).orElse(null);
		}

		public void write(CompoundTag root) {
			writeProviderConfiguration(root, configuration);
		}
	}

	record ProviderConfiguration(int routePriority) {}

	record Configuration(UUID agentId, UUID ownerClusterId, UUID clusterEpochId,
		long configurationRevision, long agentGeneration, AgentEndpointRole role,
		String normalizedAddress, int routePriority, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency,
		AgentCompletionMode completionMode,
		Optional<AgentSignalSelector> signalSelector,
		AgentEndpointState.OutputMode outputMode, long outputPolicyGeneration,
		CreateRouteKey returnWarehouseRoute) {
		public Configuration {
			agentId = Objects.requireNonNull(agentId, "agentId");
			ownerClusterId = Objects.requireNonNull(ownerClusterId, "ownerClusterId");
			clusterEpochId = Objects.requireNonNull(clusterEpochId, "clusterEpochId");
			if (configurationRevision < 0 || agentGeneration < 0 || outputPolicyGeneration < 0)
				throw new IllegalArgumentException("Agent generations cannot be negative");
			role = Objects.requireNonNull(role, "role");
			normalizedAddress = RecipeAddressRules.requireNormalized(normalizedAddress);
			frequency = Objects.requireNonNull(frequency, "frequency");
			completionMode = Objects.requireNonNull(completionMode, "completionMode");
			signalSelector = Objects.requireNonNull(signalSelector, "signalSelector");
			if ((completionMode == AgentCompletionMode.SIGNAL) != signalSelector.isPresent())
				throw new IllegalArgumentException("SIGNAL completion needs exactly one selector");
			outputMode = Objects.requireNonNull(outputMode, "outputMode");
			returnWarehouseRoute = Objects.requireNonNull(returnWarehouseRoute,
				"returnWarehouseRoute");
		}

		public Configuration(UUID agentId, UUID ownerClusterId, long agentGeneration,
			AgentEndpointRole role, String normalizedAddress, int routePriority, com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency frequency,
			AgentCompletionMode completionMode, Optional<AgentSignalSelector> signalSelector,
			AgentEndpointState.OutputMode outputMode,
			long outputPolicyGeneration, CreateRouteKey returnWarehouseRoute) {
			this(agentId, ownerClusterId, new UUID(0, 0), agentGeneration, agentGeneration, role,
				normalizedAddress, routePriority, frequency, completionMode, signalSelector,
				outputMode, outputPolicyGeneration, returnWarehouseRoute);
		}
	}
}
