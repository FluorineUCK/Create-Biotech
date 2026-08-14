package com.nobodiiiii.createbiotech.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CBConfigsTest {
	@Test
	void factoryClusterUsesApprovedDefaults() {
		CBConfigs.FactoryCluster factoryCluster = CBConfigs.SERVER.factoryCluster;

		assertEquals(3, factoryCluster.computerMinSize.getDefault());
		assertEquals(7, factoryCluster.computerMaxSize.getDefault());
		assertEquals(32, factoryCluster.computerMaxNodes.getDefault());
		assertEquals(64, factoryCluster.libraryMaxMembers.getDefault());
		assertEquals(16, factoryCluster.libraryMaxSpan.getDefault());
		assertEquals(64, factoryCluster.patternBaseRange.getDefault());
		assertEquals(64, factoryCluster.wanderingTraderRangeBonus.getDefault());
		assertEquals(512, factoryCluster.patternMaxRange.getDefault());
		assertEquals(600, factoryCluster.patternMaxPagesPerTick.getDefault());
	}
}
