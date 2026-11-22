package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.util.Objects;
import java.util.Optional;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;

/** Adapter-local transient Create address; network association is resolved separately. */
public record ObservedAddress(String createAddress, ComponentType componentType,
	ComponentRole role) {
	public ObservedAddress {
		createAddress = RecipeAddressRules.requireNormalized(createAddress);
		componentType = Objects.requireNonNull(componentType, "componentType");
		role = Objects.requireNonNull(role, "role");
	}

	public static Optional<ObservedAddress> tryCreate(String address, ComponentType componentType,
		ComponentRole role) {
		try {
			return Optional.of(new ObservedAddress(address, componentType, role));
		} catch (IllegalArgumentException | NullPointerException invalid) {
			return Optional.empty();
		}
	}
}
