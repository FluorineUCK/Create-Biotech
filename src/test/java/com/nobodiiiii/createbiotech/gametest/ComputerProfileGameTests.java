package com.nobodiiiii.createbiotech.gametest;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResidentProfiles;

import java.util.Objects;
import java.util.Set;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CreateBiotech.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ComputerProfileGameTests {
	private static final int TRADER_RANGE_BONUS = 64;

	private ComputerProfileGameTests() {}

	@GameTest(templateNamespace = "create_biotech", template = "empty", timeoutTicks = 40)
	public static void adultEntityProfilesMatchApprovedTable(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		assertCurve(helper, level, VillagerProfession.ARMORER, ProductionProfession.ARMORER,
			new int[] {2, 3, 5, 7, 9});
		assertCurve(helper, level, VillagerProfession.BUTCHER, ProductionProfession.BUTCHER,
			new int[] {2, 3, 4, 5, 6});
		assertCurve(helper, level, VillagerProfession.CARTOGRAPHER,
			ProductionProfession.CARTOGRAPHER, new int[] {2, 3, 5, 7, 9});
		assertCurve(helper, level, VillagerProfession.CLERIC, ProductionProfession.CLERIC,
			new int[] {2, 3, 4, 5, 6});
		assertCurve(helper, level, VillagerProfession.FARMER, ProductionProfession.FARMER,
			new int[] {2, 3, 4, 5, 6});
		assertCurve(helper, level, VillagerProfession.FISHERMAN, ProductionProfession.FISHERMAN,
			new int[] {2, 3, 4, 5, 6});
		assertCurve(helper, level, VillagerProfession.FLETCHER, ProductionProfession.FLETCHER,
			new int[] {2, 3, 5, 7, 9});
		assertCurve(helper, level, VillagerProfession.LEATHERWORKER,
			ProductionProfession.LEATHERWORKER, new int[] {2, 3, 4, 5, 6});
		assertCurve(helper, level, VillagerProfession.LIBRARIAN, ProductionProfession.LIBRARIAN,
			new int[] {4, 6, 8, 12, 16});
		assertCurve(helper, level, VillagerProfession.MASON, ProductionProfession.MASON,
			new int[] {2, 3, 5, 7, 9});
		assertCurve(helper, level, VillagerProfession.SHEPHERD, ProductionProfession.SHEPHERD,
			new int[] {2, 3, 4, 5, 6});
		assertCurve(helper, level, VillagerProfession.TOOLSMITH, ProductionProfession.TOOLSMITH,
			new int[] {2, 3, 5, 7, 9});
		assertCurve(helper, level, VillagerProfession.WEAPONSMITH, ProductionProfession.WEAPONSMITH,
			new int[] {2, 3, 5, 7, 9});
		assertCurve(helper, level, VillagerProfession.NONE, ProductionProfession.UNEMPLOYED,
			new int[] {2, 2, 2, 2, 2});
		assertCurve(helper, level, VillagerProfession.NITWIT, ProductionProfession.NITWIT,
			new int[] {1, 1, 1, 1, 1});
		assertProfile(helper, trader(level), ProductionProfession.WANDERING_TRADER, 2,
			Set.of(ProductionCapability.EXTENDED_RADIO), "wandering trader");
		assertProfile(helper, zombieVillager(level), ProductionProfession.ZOMBIE_VILLAGER, 0,
			Set.of(), "zombie villager");
		helper.succeed();
	}

	private static void assertCurve(GameTestHelper helper, ServerLevel level,
		VillagerProfession sourceProfession, ProductionProfession expectedProfession, int[] slots) {
		for (int levelIndex = 1; levelIndex <= slots.length; levelIndex++)
			assertProfile(helper, villager(level, sourceProfession, levelIndex), expectedProfession,
				slots[levelIndex - 1], expectedProfession == ProductionProfession.CLERIC
					? Set.of(ProductionCapability.ALCHEMIST) : Set.of(),
				expectedProfession.name() + " level " + levelIndex);
	}

	private static void assertProfile(GameTestHelper helper, Entity entity,
		ProductionProfession expectedProfession, int expectedSlots,
		Set<ProductionCapability> expectedCapabilities, String fixture) {
		ProductionResourceProfile profile = ProductionResidentProfiles.fromEntity(entity, TRADER_RANGE_BONUS)
			.orElseThrow(() -> new AssertionError(fixture + " must have a computer profile"));
		helper.assertValueEqual(profile.profession(), expectedProfession, fixture + " profession");
		helper.assertValueEqual(profile.processSlots(), expectedSlots, fixture + " process slots");
		helper.assertValueEqual(profile.capabilities(), expectedCapabilities, fixture + " capabilities");
		helper.assertValueEqual(profile.listenerRangeBonus(),
			expectedProfession == ProductionProfession.WANDERING_TRADER ? TRADER_RANGE_BONUS : 0,
			fixture + " listener range bonus");
	}

	private static Villager villager(ServerLevel level, VillagerProfession profession,
		int villagerLevel) {
		Villager villager = create(EntityType.VILLAGER, level);
		villager.setBaby(false);
		villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession, villagerLevel));
		return villager;
	}

	private static WanderingTrader trader(ServerLevel level) {
		WanderingTrader trader = create(EntityType.WANDERING_TRADER, level);
		trader.setBaby(false);
		return trader;
	}

	private static ZombieVillager zombieVillager(ServerLevel level) {
		ZombieVillager zombieVillager = create(EntityType.ZOMBIE_VILLAGER, level);
		zombieVillager.setBaby(false);
		return zombieVillager;
	}

	private static <T extends Entity> T create(EntityType<T> type, ServerLevel level) {
		return Objects.requireNonNull(type.create(level), () -> "Could not create " + type);
	}
}
