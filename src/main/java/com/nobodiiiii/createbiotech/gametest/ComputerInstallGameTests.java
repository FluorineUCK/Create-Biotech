package com.nobodiiiii.createbiotech.gametest;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerBlockEntity;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerInstallResult;
import com.nobodiiiii.createbiotech.content.factorycluster.computer.ComputerProfile;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;
import com.nobodiiiii.createbiotech.registry.CBBlocks;
import com.nobodiiiii.createbiotech.registry.CBConfigs;
import com.nobodiiiii.createbiotech.registry.CBItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("create_biotech_task4")
@PrefixGameTestTemplate(false)
public final class ComputerInstallGameTests {
	private ComputerInstallGameTests() {}

	@GameTest(templateNamespace = "create_biotech_task4", template = "empty", timeoutTicks = 60)
	public static void productionValidatorRejectsEveryInvalidCaptureWithoutMutation(
		GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
		ComputerBlockEntity computer = placeComputer(level, pos);
		ServerPlayer player = headlessPlayer(level, pos);

		assertRejectedUnchanged(helper, level, computer, player, new ItemStack(Items.PAPER),
			ComputerInstallResult.NOT_BOX, "wrong item");
		assertRejectedUnchanged(helper, level, computer, player,
			new ItemStack(CBItems.CARDBOARD_BOX.get()), ComputerInstallResult.EMPTY_BOX,
			"empty captured-entity box");

		Villager baby = villager(level, 1);
		baby.setBaby(true);
		assertRejectedUnchanged(helper, level, computer, player, captured(baby),
			ComputerInstallResult.UNSUPPORTED_ENTITY, "baby villager profile");

		Zombie unsupported = create(EntityType.ZOMBIE, level);
		assertRejectedUnchanged(helper, level, computer, player, captured(unsupported),
			ComputerInstallResult.UNSUPPORTED_ENTITY, "unsupported entity type");

		Villager invalidProfile = villager(level, 6);
		assertRejectedUnchanged(helper, level, computer, player, captured(invalidProfile),
			ComputerInstallResult.UNSUPPORTED_ENTITY, "out-of-table villager profile");

		ItemStack malformedPassengers = captured(villager(level, 1));
		editCapturedEntityTag(malformedPassengers,
			captured -> captured.putString("Passengers", "not-a-list"));
		assertRejectedUnchanged(helper, level, computer, player, malformedPassengers,
			ComputerInstallResult.INVALID_IDENTITY, "mistyped passengers tag");

		ItemStack passengerBearing = captured(villager(level, 1));
		ListTag passengers = new ListTag();
		CompoundTag passenger = new CompoundTag();
		passenger.putString("id", "minecraft:villager");
		passenger.putUUID("UUID", UUID.randomUUID());
		passengers.add(passenger);
		editCapturedEntityTag(passengerBearing,
			captured -> captured.put("Passengers", passengers));
		assertRejectedUnchanged(helper, level, computer, player, passengerBearing,
			ComputerInstallResult.PASSENGERS_UNSUPPORTED, "passenger-bearing capture");

		ItemStack malformedUuid = captured(villager(level, 1));
		editCapturedEntityTag(malformedUuid,
			captured -> captured.putString("UUID", "not-an-int-array"));
		assertRejectedUnchanged(helper, level, computer, player, malformedUuid,
			ComputerInstallResult.INVALID_IDENTITY, "malformed UUID");

		ItemStack unknownType = captured(villager(level, 1));
		editCapturedEntityTag(unknownType,
			captured -> captured.putString("id", "create_biotech:missing_entity_type"));
		assertRejectedUnchanged(helper, level, computer, player, unknownType,
			ComputerInstallResult.INVALID_IDENTITY, "unregistered raw entity type");

		Villager sameTypeCollision = villager(level, 1);
		ItemStack sameTypeBox = captured(sameTypeCollision);
		addCollision(level, sameTypeCollision, "same-type collision fixture");
		try {
			assertRejectedUnchanged(helper, level, computer, player, sameTypeBox,
				ComputerInstallResult.INVALID_IDENTITY, "loaded same-UUID/same-type collision");
		} finally {
			sameTypeCollision.discard();
		}

		Villager capturedVillager = villager(level, 1);
		ItemStack differentTypeBox = captured(capturedVillager);
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER),
			"GameTest server did not load the Nether");
		FakePlayer differentTypeCollision = new FakePlayer(nether,
			new GameProfile(capturedVillager.getUUID(), "computer-install-collision"));
		differentTypeCollision.moveTo(0.5, 80, 0.5);
		nether.addNewPlayer(differentTypeCollision);
		if (nether.getEntity(capturedVillager.getUUID()) != differentTypeCollision)
			throw new IllegalStateException("Could not add cross-level different-type collision fixture");
		try {
			assertRejectedUnchanged(helper, level, computer, player, differentTypeBox,
				ComputerInstallResult.INVALID_IDENTITY,
				"cross-level same-UUID/different-type collision");
		} finally {
			nether.removePlayerImmediately(differentTypeCollision, Entity.RemovalReason.DISCARDED);
		}

		helper.succeed();
	}

	@GameTest(templateNamespace = "create_biotech_task4", template = "empty", timeoutTicks = 40)
	public static void productionInstallCommitsProfileBeforeClearingAndSecondInstallIsAtomic(
		GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
		ComputerBlockEntity computer = placeComputer(level, pos);
		ServerPlayer player = headlessPlayer(level, pos);
		Villager source = villager(level, 3);
		source.setVillagerData(source.getVillagerData()
			.setProfession(VillagerProfession.LIBRARIAN));
		ItemStack first = captured(source);
		Tag filledSnapshot = first.save(level.registryAccess());
		ComputerProfile expectedProfile = ComputerProfile.fromEntity(source,
			CBConfigs.SERVER.factoryCluster.wanderingTraderRangeBonus.get()).orElseThrow();
		player.setItemInHand(InteractionHand.MAIN_HAND, first);

		helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND),
			ComputerInstallResult.SUCCESS, "valid adult librarian must install");

		helper.assertFalse(CapturedEntityBoxHelper.hasCapturedEntity(player.getMainHandItem()),
			"successful commit must clear the same held box");
		helper.assertValueEqual(computer.installedProfile().orElseThrow(), expectedProfile,
			"production profile validator must commit the approved profile");
		CompoundTag committed = computer.saveWithoutMetadata(level.registryAccess())
			.getCompound("ComputerData");
		helper.assertValueEqual(committed.get("Resident"), filledSnapshot,
			"Computer must retain the exact filled snapshot committed before hand clear");
		helper.assertValueEqual(committed.getCompound("Profile"), expectedProfile.save(),
			"Computer must persist the exact validated profile");

		ItemStack second = captured(villager(level, 1));
		Tag secondBefore = second.save(level.registryAccess());
		CompoundTag computerBefore = computer.saveWithoutMetadata(level.registryAccess());
		player.setItemInHand(InteractionHand.MAIN_HAND, second);

		helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND),
			ComputerInstallResult.OCCUPIED, "second installation must be rejected");
		helper.assertValueEqual(player.getMainHandItem().save(level.registryAccess()), secondBefore,
			"second rejection must leave the held stack byte-identical");
		helper.assertValueEqual(computer.saveWithoutMetadata(level.registryAccess()), computerBefore,
			"second rejection must leave the complete server BE tag byte-identical");
		helper.succeed();
	}

	private static void assertRejectedUnchanged(GameTestHelper helper, ServerLevel level,
		ComputerBlockEntity computer, ServerPlayer player, ItemStack held,
		ComputerInstallResult expected, String fixture) {
		Tag heldBefore = held.save(level.registryAccess());
		CompoundTag computerBefore = computer.saveWithoutMetadata(level.registryAccess());
		player.setItemInHand(InteractionHand.MAIN_HAND, held);

		helper.assertValueEqual(computer.installResident(player, InteractionHand.MAIN_HAND), expected,
			fixture + " result");
		helper.assertValueEqual(player.getMainHandItem().save(level.registryAccess()), heldBefore,
			fixture + " must retain the held stack byte-identically");
		helper.assertValueEqual(computer.saveWithoutMetadata(level.registryAccess()), computerBefore,
			fixture + " must retain the complete server BE tag byte-identically");
	}

	private static ComputerBlockEntity placeComputer(ServerLevel level, BlockPos pos) {
		if (!level.setBlock(pos, CBBlocks.COMPUTER.get().defaultBlockState(), Block.UPDATE_ALL)
			|| !(level.getBlockEntity(pos) instanceof ComputerBlockEntity computer))
			throw new IllegalStateException("Could not place Computer fixture at " + pos);
		return computer;
	}

	private static ServerPlayer headlessPlayer(ServerLevel level, BlockPos pos) {
		FakePlayer player = new FakePlayer(level,
			new GameProfile(UUID.randomUUID(), "computer-install-test"));
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.moveTo(Vec3.atCenterOf(pos));
		return player;
	}

	private static Villager villager(ServerLevel level, int villagerLevel) {
		Villager villager = create(EntityType.VILLAGER, level);
		villager.setBaby(false);
		villager.setVillagerData(new VillagerData(VillagerType.PLAINS,
			VillagerProfession.NONE, villagerLevel));
		return villager;
	}

	private static ItemStack captured(LivingEntity entity) {
		ItemStack box = new ItemStack(CBItems.CARDBOARD_BOX.get());
		if (!CapturedEntityBoxHelper.captureEntity(box, entity))
			throw new IllegalStateException("Could not capture " + entity.getType());
		return box;
	}

	private static void editCapturedEntityTag(ItemStack box, Consumer<CompoundTag> editor) {
		CompoundTag root = CBItemData.getOrEmpty(box);
		CompoundTag captured = root.getCompound("CapturedEntity");
		editor.accept(captured);
		root.put("CapturedEntity", captured);
		CBItemData.set(box, root);
	}

	private static void addCollision(ServerLevel level, Entity entity, String fixture) {
		entity.moveTo(0.5, 80, 0.5);
		if (!level.addFreshEntity(entity) || level.getEntity(entity.getUUID()) != entity)
			throw new IllegalStateException("Could not add " + fixture);
	}

	private static <T extends Entity> T create(EntityType<T> type, ServerLevel level) {
		return Objects.requireNonNull(type.create(level), () -> "Could not create " + type);
	}
}
