package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;


import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentStatus;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;

import net.minecraft.network.chat.Component;

/** Immutable, command-free data prepared for a bounded logistics directory view. */
public record LogisticsNetworkProjection(List<NetworkView> networks,
	List<ComponentView> components, Set<RemoteCapability> capabilities) {
	public LogisticsNetworkProjection {
		networks = List.copyOf(Objects.requireNonNull(networks, "networks"));
		components = List.copyOf(Objects.requireNonNull(components, "components"));
		capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
	}

	/** A bound network's display identity; UUID supports selection but is never rendered by default. */
	public record NetworkView(UUID logisticsNetworkId, Component displayName,
		Optional<String> customAlias, long revision) {
		public NetworkView {
			logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
			displayName = Objects.requireNonNull(displayName, "displayName");
			customAlias = Objects.requireNonNull(customAlias, "customAlias");
			customAlias = customAlias.map(LogisticsNetworkDirectoryEntry::canonicalAlias);
			if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
		}
		public NetworkView(UUID logisticsNetworkId, Component displayName, long revision) {
			this(logisticsNetworkId, displayName, Optional.empty(), revision);
		}
	}

	/** Last-observed state only; this view intentionally carries no mutation authority. */
	public record ComponentView(ComponentIdentity componentIdentity, String createAddress,
		ComponentType componentType, ComponentStatus status, Optional<UUID> agentId,
		long memberGeneration, Set<ComponentRole> roles, long lastObservedTick) {
		public ComponentView {
			componentIdentity = Objects.requireNonNull(componentIdentity, "componentIdentity");
			createAddress = Objects.requireNonNull(createAddress, "createAddress");
			componentType = Objects.requireNonNull(componentType, "componentType");
			status = Objects.requireNonNull(status, "status");
			agentId = Objects.requireNonNull(agentId, "agentId");
			roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
			if (memberGeneration < 0 || lastObservedTick < 0)
				throw new IllegalArgumentException("invalid observed component state");
		}

		public SpaceAddress spaceAddress() { return componentIdentity.address(); }
	}

	/** Projection capability labels only; command tokens are deliberately not part of this DTO. */
	public enum RemoteCapability {
		VIEW_LOGISTICS_DIRECTORY,
		EDIT_CREATE_ADDRESS
	}
}
