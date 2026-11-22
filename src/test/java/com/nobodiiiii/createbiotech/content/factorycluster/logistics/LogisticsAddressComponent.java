package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
/** Immutable durable last-seen projection of one addressed component on one Create network. */
public record LogisticsAddressComponent(UUID logisticsNetworkId,
	ComponentIdentity componentIdentity, String createAddress, ComponentType componentType,
	Set<ComponentRole> roles, Optional<UUID> agentId, long memberGeneration,
	long lastSeenTick, ComponentStatus status) {
	public LogisticsAddressComponent {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		componentIdentity = Objects.requireNonNull(componentIdentity, "componentIdentity");
		createAddress = RecipeAddressRules.requireNormalized(createAddress);
		componentType = Objects.requireNonNull(componentType, "componentType");
		roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
		if (roles.isEmpty()) throw new IllegalArgumentException("component needs at least one role");
		agentId = Objects.requireNonNull(agentId, "agentId");
		if (memberGeneration < 0 || agentId.isEmpty() && memberGeneration != 0)
			throw new IllegalArgumentException("invalid component member generation");
		if (lastSeenTick < 0) throw new IllegalArgumentException("last-seen tick cannot be negative");
		status = Objects.requireNonNull(status, "status");
	}

	public LogisticsAddressComponent withStatus(ComponentStatus nextStatus) {
		return new LogisticsAddressComponent(logisticsNetworkId, componentIdentity, createAddress,
			componentType, roles, agentId, memberGeneration, lastSeenTick,
			nextStatus);
	}

	public enum ComponentStatus {
		ONLINE,
		OFFLINE,
		CONFLICT
	}

	public enum ComponentType {
		PACKAGER,
		PACKAGE_PORT,
		REDSTONE_REQUESTER,
		FACTORY_GAUGE
	}

	public enum ComponentRole {
		ORDINARY,
		PROVIDER,
		INPUT,
		OUTPUT
	}

	/** Stable physical location plus an explicit adapter-owned subcomponent discriminator. */
	public record ComponentIdentity(SpaceAddress address, String discriminator) {
		public ComponentIdentity {
			address = Objects.requireNonNull(address, "address");
			discriminator = Objects.requireNonNull(discriminator, "discriminator").strip();
			if (discriminator.isEmpty() || discriminator.length() > 128
				|| discriminator.codePoints().anyMatch(Character::isISOControl))
				throw new IllegalArgumentException("invalid component discriminator");
		}
	}
}
