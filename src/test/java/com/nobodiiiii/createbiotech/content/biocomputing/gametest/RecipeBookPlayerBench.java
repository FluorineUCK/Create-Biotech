package com.nobodiiiii.createbiotech.content.biocomputing.gametest;

import java.io.IOException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech_book_scan") @PrefixGameTestTemplate(false)
public final class RecipeBookPlayerBench {
    @GameTest(template="empty",timeoutTicks=120000)
    public static void searchMixedPagesOnThePhysicalDrive(GameTestHelper helper)throws IOException {
        RecipeStreamPlayerBench.prepare(helper,true);
    }
}
