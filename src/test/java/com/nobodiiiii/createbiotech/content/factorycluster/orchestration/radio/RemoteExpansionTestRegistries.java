package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;

/** Real built-in registry provider for registry-aware remote expansion fixtures. */
public final class RemoteExpansionTestRegistries {
	private RemoteExpansionTestRegistries() {}

	public static RegistryAccess provider() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
}
