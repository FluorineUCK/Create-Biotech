package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;


import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

import com.simibubi.create.Create;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Server-global, revision-fenced aliases for Create logistics-network UUIDs. */
public final class LogisticsNetworkAliasService {
	private final LogisticsNetworkDirectory directory;
	private final BiPredicate<UUID, Player> mayAdministrate;

	public LogisticsNetworkAliasService(LogisticsNetworkDirectory directory) {
		this(directory, (network, player) -> Create.LOGISTICS.mayAdministrate(network, player));
	}

	LogisticsNetworkAliasService(LogisticsNetworkDirectory directory,
		BiPredicate<UUID, Player> mayAdministrate) {
		this.directory = Objects.requireNonNull(directory, "directory");
		this.mayAdministrate = Objects.requireNonNull(mayAdministrate, "mayAdministrate");
	}

	/** Applies the requested global alias only when Create administration and the exact revision match. */
	public RenameResult rename(Player player, UUID logisticsNetworkId, long expectedRevision,
		Optional<String> customAlias) {
		logisticsNetworkId = Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId");
		customAlias = Objects.requireNonNull(customAlias, "customAlias");
		if (!mayAdministrate.test(logisticsNetworkId, player)) return RenameResult.FORBIDDEN;
		if (!directory.tryEnsure(logisticsNetworkId).succeeded()) return RenameResult.STALE;
		return directory.compareAndSetAlias(logisticsNetworkId, expectedRevision, customAlias)
			? RenameResult.APPLIED : RenameResult.STALE;
	}

	public Component displayName(UUID logisticsNetworkId) {
		LogisticsNetworkDirectory.AllocationResult allocation = directory.tryEnsure(
			Objects.requireNonNull(logisticsNetworkId, "logisticsNetworkId"));
		return LogisticsNetworkDirectory.displayName(allocation.entry().orElseThrow(() ->
			new IllegalStateException("Logistics identity allocation was refused")));
	}

	public enum RenameResult { APPLIED, STALE, FORBIDDEN }
}
