package com.nobodiiiii.createbiotech.content.factorycluster.logistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.nobodiiiii.createbiotech.content.factorycluster.agent.AgentEndpointRole;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.FactoryPanelAgentAccess;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentRole;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsAddressComponent.ComponentType;
import com.nobodiiiii.createbiotech.content.factorycluster.logistics.LogisticsNetworkAssociation.AssociationKind;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity;

import net.minecraft.world.level.block.entity.BlockEntity;

/** Closed registry of supported Create address carriers. No arbitrary field reflection is used. */
public final class LogisticsAddressAdapters {
	private final List<LogisticsAddressAdapter> adapters;

	public LogisticsAddressAdapters(List<? extends LogisticsAddressAdapter> adapters) {
		this.adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters"));
		if (this.adapters.isEmpty()) throw new IllegalArgumentException("adapter registry is empty");
	}

	public static LogisticsAddressAdapters createDefault() {
		return new LogisticsAddressAdapters(List.of(new PackagerAdapter(), new PackagePortAdapter(),
			new RedstoneRequesterAdapter(), new FactoryPanelAdapter()));
	}

	public boolean supports(BlockEntity blockEntity) {
		Objects.requireNonNull(blockEntity, "blockEntity");
		return adapters.stream().anyMatch(adapter -> adapter.supports(blockEntity));
	}

	public List<ObservedAddress> observe(BlockEntity blockEntity,
		Optional<LogisticallyLinkedBehaviour> directLink,
		LogisticsNetworkAssociation association) {
		Objects.requireNonNull(blockEntity, "blockEntity");
		directLink = Objects.requireNonNull(directLink, "directLink");
		association = Objects.requireNonNull(association, "association");
		for (LogisticsAddressAdapter adapter : adapters)
			if (adapter.supports(blockEntity))
				return List.copyOf(adapter.observe(blockEntity, directLink, association));
		return List.of();
	}

	private static final class PackagerAdapter implements LogisticsAddressAdapter {
		@Override public boolean supports(BlockEntity blockEntity) {
			return blockEntity instanceof PackagerBlockEntity;
		}

		@Override public List<ObservedAddress> observe(BlockEntity blockEntity,
			Optional<LogisticallyLinkedBehaviour> directLink,
			LogisticsNetworkAssociation association) {
			PackagerBlockEntity packager = (PackagerBlockEntity) blockEntity;
			String address = Boolean.TRUE.equals(packager.hasCustomComputerAddress)
				&& !packager.customComputerAddress.isBlank()
					? packager.customComputerAddress : packager.signBasedAddress;
			return ObservedAddress.tryCreate(address, ComponentType.PACKAGER,
				ComponentRole.ORDINARY).map(List::of).orElseGet(List::of);
		}
	}

	private static final class PackagePortAdapter implements LogisticsAddressAdapter {
		@Override public boolean supports(BlockEntity blockEntity) {
			return blockEntity instanceof PackagePortBlockEntity;
		}

		@Override public List<ObservedAddress> observe(BlockEntity blockEntity,
			Optional<LogisticallyLinkedBehaviour> directLink,
			LogisticsNetworkAssociation association) {
			return ObservedAddress.tryCreate(((PackagePortBlockEntity) blockEntity).addressFilter,
				ComponentType.PACKAGE_PORT, ComponentRole.ORDINARY)
				.map(List::of).orElseGet(List::of);
		}
	}

	private static final class RedstoneRequesterAdapter implements LogisticsAddressAdapter {
		@Override public boolean supports(BlockEntity blockEntity) {
			return blockEntity instanceof RedstoneRequesterBlockEntity;
		}

		@Override public List<ObservedAddress> observe(BlockEntity blockEntity,
			Optional<LogisticallyLinkedBehaviour> directLink,
			LogisticsNetworkAssociation association) {
			return ObservedAddress.tryCreate(
				((RedstoneRequesterBlockEntity) blockEntity).encodedTargetAdress,
				ComponentType.REDSTONE_REQUESTER, ComponentRole.ORDINARY)
				.map(List::of).orElseGet(List::of);
		}
	}

	private static final class FactoryPanelAdapter implements LogisticsAddressAdapter {
		@Override public boolean supports(BlockEntity blockEntity) {
			return blockEntity instanceof FactoryPanelBlockEntity;
		}

		@Override public List<ObservedAddress> observe(BlockEntity blockEntity,
			Optional<LogisticallyLinkedBehaviour> directLink,
			LogisticsNetworkAssociation association) {
			FactoryPanelBlockEntity panel = (FactoryPanelBlockEntity) blockEntity;
			if (panel.panels == null) return List.of();
			ArrayList<ObservedAddress> result = new ArrayList<>();
			List<String> recipeAddresses = new ArrayList<>();
			Optional<FactoryPanelAgentAccess.Configuration> agentConfiguration =
				blockEntity instanceof FactoryPanelAgentAccess access
					? access.createBiotech$getAgentConfiguration() : Optional.empty();
			boolean agentEndpoint = association.associationKind() == AssociationKind.AGENT_ENDPOINT;
			for (FactoryPanelBehaviour behaviour : panel.panels.values()) {
				if (!behaviour.isActive()
					|| !association.logisticsNetworkId().equals(behaviour.network)) continue;
				ObservedAddress.tryCreate(behaviour.recipeAddress,
					ComponentType.FACTORY_GAUGE, ComponentRole.ORDINARY).ifPresent(address -> {
						result.add(address);
						recipeAddresses.add(address.createAddress());
					});
			}
			if (blockEntity instanceof FactoryPanelAgentAccess access) {
				if (access.createBiotech$getProviderConfiguration().isPresent())
					for (String address : recipeAddresses)
						ObservedAddress.tryCreate(address, ComponentType.FACTORY_GAUGE,
							ComponentRole.PROVIDER).ifPresent(result::add);
				if (agentEndpoint)
					agentConfiguration.ifPresent(configuration ->
						ObservedAddress.tryCreate(configuration.normalizedAddress(),
							ComponentType.FACTORY_GAUGE,
							configuration.role() == AgentEndpointRole.INPUT
								? ComponentRole.INPUT : ComponentRole.OUTPUT).ifPresent(result::add));
			}
			return List.copyOf(result);
		}
	}
}
