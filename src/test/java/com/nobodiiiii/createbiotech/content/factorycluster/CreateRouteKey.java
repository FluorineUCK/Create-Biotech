package com.nobodiiiii.createbiotech.content.factorycluster;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.RecipeAddressRules;

import java.util.Objects;
import java.util.UUID;

/** Identity of one real Create logistics address within one logistics network. */
public record CreateRouteKey(UUID logisticsNetworkId, String address) {
	public CreateRouteKey {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		address = RecipeAddressRules.requireNormalized(address);
	}
}
