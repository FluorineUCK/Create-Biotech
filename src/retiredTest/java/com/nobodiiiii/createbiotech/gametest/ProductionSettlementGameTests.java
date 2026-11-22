package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.CreateBiotech;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Isolated real logistics/receipt settlement runner; processing is simulated by the existing fixture. */
@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionSettlementGameTests {
	private ProductionSettlementGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty",
		batch = "production_goal_settlement_migration", timeoutTicks = 12000)
	public static void receivedRunSettlementSurvivesCompositionReload(GameTestHelper helper) {
		FactoryProductionGameTestFixture.runSettlementScenario(helper);
	}
}
