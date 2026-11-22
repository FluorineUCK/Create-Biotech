package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.Objects;
import java.util.Optional;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;

/**
 * Server-thread authority which freezes the exact inventory attachment selected by a Create route.
 * The opaque fact is safe for worker threads; only this package can issue one.
 */
public final class AttachedInventoryBindingResolver {
	private final ServerThreadGuard serverThread;
	private final Authority authority;

	AttachedInventoryBindingResolver(ServerThreadGuard serverThread, Authority authority) {
		this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
		this.authority = Objects.requireNonNull(authority, "authority");
	}

	public Optional<BindingFact> resolve(CreateRouteKey destination) {
		destination = Objects.requireNonNull(destination, "destination");
		if (!serverThread.isServerThread()) return Optional.empty();
		Optional<ResolvedBinding> captured = Objects.requireNonNull(authority.resolve(destination),
			"resolved attached inventory binding");
		if (captured.isEmpty() || !captured.orElseThrow().destination().equals(destination))
			return Optional.empty();
		ResolvedBinding resolved = captured.orElseThrow();
		return Optional.of(new BindingFact(resolved.destination(), resolved.packager(),
			resolved.inventory()));
	}

	@FunctionalInterface
	interface ServerThreadGuard { boolean isServerThread(); }

	@FunctionalInterface
	interface Authority {
		Optional<ResolvedBinding> resolve(CreateRouteKey destination);
	}

	record ResolvedBinding(CreateRouteKey destination, SpaceAddress packager,
		InventoryIdentity inventory) {
		ResolvedBinding {
			destination = Objects.requireNonNull(destination, "destination");
			packager = Objects.requireNonNull(packager, "packager");
			inventory = Objects.requireNonNull(inventory, "inventory");
		}
	}

	/** Capability constructible only by a successful server-thread route resolution. */
	public static final class BindingFact {
		private final CreateRouteKey destination;
		private final SpaceAddress packager;
		private final InventoryIdentity inventory;

		private BindingFact(CreateRouteKey destination, SpaceAddress packager,
			InventoryIdentity inventory) {
			this.destination = destination;
			this.packager = packager;
			this.inventory = inventory;
		}

		public CreateRouteKey destination() { return destination; }
		public SpaceAddress packager() { return packager; }
		public InventoryIdentity inventory() { return inventory; }
	}
}
