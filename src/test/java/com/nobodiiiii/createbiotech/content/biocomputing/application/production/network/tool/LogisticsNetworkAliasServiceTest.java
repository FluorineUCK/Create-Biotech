package com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.tool;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectory;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.network.LogisticsNetworkDirectoryEntry;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;

class LogisticsNetworkAliasServiceTest {
	private static final UUID NETWORK = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID FIRST_CLUSTER = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID SECOND_CLUSTER = UUID.fromString("00000000-0000-0000-0000-000000000202");

	@Test
	void authorizedRenameIsGlobalAndStaleRevisionFails() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		LogisticsNetworkAliasService aliases = new LogisticsNetworkAliasService(directory,
			(network, player) -> true);
		LogisticsNetworkDirectoryEntry entry = directory.ensure(NETWORK);

		assertEquals(LogisticsNetworkAliasService.RenameResult.APPLIED,
			aliases.rename(null, NETWORK, entry.revision(), Optional.of("主仓")));
		assertTrue(displayName(aliases, NETWORK).contains("主仓"));
		assertEquals(1, directory.find(NETWORK).orElseThrow().revision());
		assertEquals(LogisticsNetworkAliasService.RenameResult.STALE,
			aliases.rename(null, NETWORK, entry.revision(), Optional.of("旧值")));
		assertEquals(1, directory.find(NETWORK).orElseThrow().revision());
		assertEquals(LogisticsNetworkAliasService.RenameResult.FORBIDDEN,
			new LogisticsNetworkAliasService(directory, (network, player) -> false)
				.rename(null, NETWORK, 1, Optional.of("x")));
		assertEquals(1, directory.find(NETWORK).orElseThrow().revision());
	}

	@Test
	void aliasesAreSharedByUuidAcrossClustersAndClearingRestoresTheDefaultName() {
		LogisticsNetworkDirectory directory = new LogisticsNetworkDirectory();
		LogisticsNetworkAliasService aliases = new LogisticsNetworkAliasService(directory,
			(network, player) -> true);
		LogisticsNetworkDirectoryEntry initial = directory.ensure(NETWORK);
		ClusterBinding first = new ClusterBinding(FIRST_CLUSTER, 0, null,
			java.util.List.of(new LogisticsBinding(NETWORK)));
		ClusterBinding second = new ClusterBinding(SECOND_CLUSTER, 0, null,
			java.util.List.of(new LogisticsBinding(NETWORK)));

		assertEquals(first.logisticsBindings().getFirst().logisticsNetworkId(),
			second.logisticsBindings().getFirst().logisticsNetworkId());
		assertEquals(LogisticsNetworkAliasService.RenameResult.APPLIED,
			aliases.rename(null, NETWORK, initial.revision(), Optional.of("主仓")));
		assertTrue(displayName(aliases, first.logisticsBindings().getFirst()
			.logisticsNetworkId()).contains("主仓"));
		assertTrue(displayName(aliases, second.logisticsBindings().getFirst()
			.logisticsNetworkId()).contains("主仓"));
		assertEquals(LogisticsNetworkAliasService.RenameResult.APPLIED,
			aliases.rename(null, NETWORK, 1, Optional.empty()));
		assertTrue(directory.find(NETWORK).orElseThrow().customAlias().isEmpty());
		assertTrue(displayName(aliases, NETWORK).contains("default_name"));
	}

	private static String displayName(LogisticsNetworkAliasService aliases, UUID network) {
		Object name = aliases.displayName(network);
		return name.toString();
	}
}
