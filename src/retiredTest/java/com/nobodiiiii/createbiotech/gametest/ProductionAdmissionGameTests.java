package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.CreateBiotech;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Targeted runner for the existing real Panel/Library admission recovery fixture. */
@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionAdmissionGameTests {
    private ProductionAdmissionGameTests() {}

    @GameTest(templateNamespace = "create_biotech", template = "empty",
        batch = "production_admission_migration", timeoutTicks = 12000)
    public static void pendingAdmissionSurvivesLibraryAndCompositionReload(GameTestHelper helper) {
        FactoryProductionGameTestFixture.runScenario(helper,
            FactoryProductionGameTestFixture.Scenario.PENDING_ADMISSION_RELOAD);
    }
}
