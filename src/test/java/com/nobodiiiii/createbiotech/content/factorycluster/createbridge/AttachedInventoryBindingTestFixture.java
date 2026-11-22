package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.Optional;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/** Test-source-only issuer for an authoritative attached-inventory route fact. */
public final class AttachedInventoryBindingTestFixture {
	private AttachedInventoryBindingTestFixture() {}

	public static AttachedInventoryBindingResolver.BindingFact fact(CreateRouteKey destination,
		SpaceAddress packager, InventoryIdentity inventory) {
		AttachedInventoryBindingResolver resolver = new AttachedInventoryBindingResolver(() -> true,
			requested -> Optional.of(new AttachedInventoryBindingResolver.ResolvedBinding(
				requested, packager, inventory)));
		return resolver.resolve(destination).orElseThrow();
	}
}
