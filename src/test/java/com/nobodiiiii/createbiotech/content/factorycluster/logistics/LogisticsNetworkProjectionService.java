package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;


import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.ComponentView;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.NetworkView;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkProjection.RemoteCapability;

/**
 * Bounded read-only composition over the authoritative binding, global network directory, and
 * durable observer. It neither allocates identities nor mutates observation state.
 */
public final class LogisticsNetworkProjectionService {
	private static final Set<RemoteCapability> READ_ONLY_CAPABILITIES =
		Set.of(RemoteCapability.VIEW_LOGISTICS_DIRECTORY);
	private static final Comparator<LogisticsAddressComponent> COMPONENT_ORDER = Comparator
		.comparing(LogisticsAddressComponent::status)
		.thenComparing(LogisticsAddressComponent::componentType)
		.thenComparing(LogisticsAddressComponent::createAddress)
		.thenComparing(component -> component.componentIdentity().address(),
			LogisticsNetworkProjectionService::compareSpaceAddress)
		.thenComparing(component -> component.componentIdentity().discriminator());

	private final LogisticsNetworkDirectory directory;
	private final LogisticsAddressObserver observer;
	private final AuthorityBindingLookup bindings;
	private final int panelRowLimit;

	public LogisticsNetworkProjectionService(LogisticsNetworkDirectory directory,
		LogisticsAddressObserver observer, AuthorityBindingLookup bindings, int panelRowLimit) {
		this.directory = Objects.requireNonNull(directory, "directory");
		this.observer = Objects.requireNonNull(observer, "observer");
		this.bindings = Objects.requireNonNull(bindings, "bindings");
		if (panelRowLimit <= 0 || panelRowLimit > 65_536)
			throw new IllegalArgumentException("panel row limit is outside the Logistics wire bound");
		this.panelRowLimit = panelRowLimit;
	}

	/**
	 * Returns bound names and one selected bound network's last-seen component page.
	 * The authority lookup must have already passed the live Cluster reconciliation fence.
	 */
	public LogisticsNetworkProjection project(ClusterAuthority authority,
		Optional<UUID> selectedNetwork, int offset, int limit) {
		authority = Objects.requireNonNull(authority, "authority");
		selectedNetwork = Objects.requireNonNull(selectedNetwork, "selectedNetwork");
		validatePage(offset, limit);
		ClusterBinding binding = formedBinding(authority);
		UUID selected = selectedNetwork.orElseThrow(() ->
			new IllegalArgumentException("A logistics network must be selected"));
		if (binding.logisticsBindings().stream().map(LogisticsBinding::logisticsNetworkId)
			.noneMatch(selected::equals))
			throw new IllegalArgumentException("Selected logistics network is not bound");

		List<NetworkView> networks = networkViews(binding);
		List<ComponentView> ordered = observer.snapshot(selected).stream()
			.sorted(COMPONENT_ORDER)
			.map(LogisticsNetworkProjectionService::viewOf)
			.toList();
		return new LogisticsNetworkProjection(networks, page(ordered, offset, limit),
			READ_ONLY_CAPABILITIES);
	}



	private ClusterBinding formedBinding(ClusterAuthority authority) {
		ClusterBinding binding = bindings.find(authority).orElseThrow(() ->
			new IllegalStateException("Cluster authority is unavailable"));
		if (binding.authority() == null || !authority.equals(binding.authority()))
			throw new IllegalStateException("Cluster authority is not formed");
		return binding;
	}

	private ClusterBinding formedBinding(ClusterAuthority authority, UUID clusterId) {
		ClusterBinding binding = formedBinding(authority);
		if (!clusterId.equals(binding.clusterId()))
			throw new IllegalStateException("Cluster binding does not match the Panel opening");
		return binding;
	}

	private List<NetworkView> networkViews(ClusterBinding binding) {
		return binding.logisticsBindings().stream().map(LogisticsBinding::logisticsNetworkId)
			.map(network -> directory.find(network).orElseThrow(() ->
				new IllegalStateException("Bound logistics network is missing from the global directory")))
			.map(entry -> new NetworkView(entry.logisticsNetworkId(),
				LogisticsNetworkDirectory.displayName(entry), entry.customAlias(), entry.revision())).toList();
	}

	private static Optional<UUID> selectNetwork(Optional<UUID> requested,
		ClusterBinding binding) {
		if (requested.isPresent()) {
			UUID selected = requested.orElseThrow();
			if (binding.logisticsBindings().stream().map(LogisticsBinding::logisticsNetworkId)
				.noneMatch(selected::equals))
				throw new IllegalArgumentException("Selected logistics network is not bound");
			return requested;
		}
		return binding.logisticsBindings().stream().findFirst()
			.map(LogisticsBinding::logisticsNetworkId);
	}

	private void validatePage(int offset, int limit) {
		if (offset < 0 || limit < 0 || limit > panelRowLimit)
			throw new IllegalArgumentException("Invalid logistics projection page");
	}

	private static List<ComponentView> page(List<ComponentView> values, int offset, int limit) {
		if (limit == 0 || offset >= values.size()) return List.of();
		int endExclusive = Math.min(values.size(), Math.addExact(offset, limit));
		return values.subList(offset, endExclusive);
	}

	private static ComponentView viewOf(LogisticsAddressComponent component) {
		return new ComponentView(component.componentIdentity(), component.createAddress(),
			component.componentType(), component.status(), component.agentId(),
			component.memberGeneration(), component.roles(), component.lastSeenTick());
	}

	private static int compareSpaceAddress(SpaceAddress first, SpaceAddress second) {
		int dimension = first.dimension().location().toString().compareTo(
			second.dimension().location().toString());
		if (dimension != 0) return dimension;
		int subLevel = String.valueOf(first.subLevelId()).compareTo(String.valueOf(second.subLevelId()));
		if (subLevel != 0) return subLevel;
		return Long.compare(first.localPos().asLong(), second.localPos().asLong());
	}

	/** Read-only seam for an already-reconciled, currently live Cluster authority binding. */
	@FunctionalInterface
	public interface AuthorityBindingLookup {
		Optional<ClusterBinding> find(ClusterAuthority authority);
	}
}
