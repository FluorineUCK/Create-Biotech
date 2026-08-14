package com.nobodiiiii.createbiotech.gametest;

import java.util.Objects;

import com.nobodiiiii.createbiotech.CreateBiotech;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerProfile;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.NodeKind;

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
		assertProfile(helper, villager(level, VillagerProfession.NONE, 1), NodeKind.VILLAGER, 1, 1,
			"villager level 1");
		assertProfile(helper, villager(level, VillagerProfession.NONE, 2), NodeKind.VILLAGER, 2, 1,
			"villager level 2");
		assertProfile(helper, villager(level, VillagerProfession.NONE, 3), NodeKind.VILLAGER, 3, 2,
			"villager level 3");
		assertProfile(helper, villager(level, VillagerProfession.NONE, 4), NodeKind.VILLAGER, 4, 2,
			"villager level 4");
		assertProfile(helper, villager(level, VillagerProfession.NONE, 5), NodeKind.VILLAGER, 4, 2,
			"villager level 5");
		assertProfile(helper, villager(level, VillagerProfession.LIBRARIAN, 1), NodeKind.LIBRARIAN,
			4, 2, "librarian level 1");
		assertProfile(helper, villager(level, VillagerProfession.LIBRARIAN, 2), NodeKind.LIBRARIAN,
			6, 2, "librarian level 2");
		assertProfile(helper, villager(level, VillagerProfession.LIBRARIAN, 3), NodeKind.LIBRARIAN,
			8, 3, "librarian level 3");
		assertProfile(helper, villager(level, VillagerProfession.LIBRARIAN, 4), NodeKind.LIBRARIAN,
			12, 3, "librarian level 4");
		assertProfile(helper, villager(level, VillagerProfession.LIBRARIAN, 5), NodeKind.LIBRARIAN,
			16, 4, "librarian level 5");
		assertProfile(helper, villager(level, VillagerProfession.NITWIT, 1), NodeKind.NITWIT, 1,
			0, "nitwit");
		assertProfile(helper, trader(level), NodeKind.WANDERING_TRADER, 2, 1,
			"wandering trader");
		assertProfile(helper, zombieVillager(level), NodeKind.ZOMBIE_VILLAGER, 0, 0,
			"zombie villager");
		helper.succeed();
	}

	private static void assertProfile(GameTestHelper helper, Entity entity, NodeKind expectedKind,
		int expectedSlots, int expectedDepth, String fixture) {
		ComputerProfile profile = ComputerProfile.fromEntity(entity, TRADER_RANGE_BONUS)
			.orElseThrow(() -> new AssertionError(fixture + " must have a computer profile"));
		helper.assertValueEqual(profile.kind(), expectedKind, fixture + " kind");
		helper.assertValueEqual(profile.slots(), expectedSlots, fixture + " slots");
		helper.assertValueEqual(profile.depth(), expectedDepth, fixture + " depth");
		helper.assertValueEqual(profile.loopAbort(), expectedKind == NodeKind.NITWIT,
			fixture + " loop abort");
		helper.assertValueEqual(profile.patternRangeBonus(),
			expectedKind == NodeKind.WANDERING_TRADER ? TRADER_RANGE_BONUS : 0,
			fixture + " pattern range bonus");
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