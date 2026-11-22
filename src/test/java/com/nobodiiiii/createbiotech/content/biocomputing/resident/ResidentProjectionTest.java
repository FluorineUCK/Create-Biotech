package com.nobodiiiii.createbiotech.content.biocomputing.resident;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;

class ResidentProjectionTest {
	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			Field bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void rejectsUnboundedProjection() {
		assertThrows(IllegalArgumentException.class,
			() -> new ResidentProjection(EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 0, "x"));
		assertThrows(IllegalArgumentException.class,
			() -> new ResidentProjection(EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 6, "x"));
		assertThrows(IllegalArgumentException.class,
			() -> new ResidentProjection(EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 1,
				"x".repeat(65)));
	}

	@Test
	void roundTripsEveryWhitelistedResidentDisplayType() {
		List<ResidentProjection> projections = List.of(
			new ResidentProjection(EntityType.VILLAGER, VillagerProfession.LIBRARIAN, 4, "Archivist"),
			new ResidentProjection(EntityType.WANDERING_TRADER, VillagerProfession.NONE, 1, "Trader"),
			new ResidentProjection(EntityType.ZOMBIE_VILLAGER, VillagerProfession.CLERIC, 3, "Zombie"));

		for (ResidentProjection projection : projections)
			assertEquals(projection, ResidentProjection.load(projection.save()).orElseThrow());
	}

	@Test
	void rejectsMalformedOrUnboundedProjectionPayloads() {
		ResidentProjection valid = new ResidentProjection(EntityType.VILLAGER,
			VillagerProfession.LIBRARIAN, 1, null);

		CompoundTag levelZero = valid.save();
		levelZero.putInt("Level", 0);
		assertTrue(ResidentProjection.load(levelZero).isEmpty());
		CompoundTag levelSix = valid.save();
		levelSix.putInt("Level", 6);
		assertTrue(ResidentProjection.load(levelSix).isEmpty());
		CompoundTag unknownType = valid.save();
		unknownType.putString("EntityType", "create_biotech:unknown_resident");
		assertTrue(ResidentProjection.load(unknownType).isEmpty());
		CompoundTag unknownProfession = valid.save();
		unknownProfession.putString("Profession", "create_biotech:unknown_profession");
		assertTrue(ResidentProjection.load(unknownProfession).isEmpty());
		CompoundTag wrongType = valid.save();
		wrongType.putInt("EntityType", 1);
		assertTrue(ResidentProjection.load(wrongType).isEmpty());
		CompoundTag extraKey = valid.save();
		extraKey.putString("CapturedEntity", "never forwarded");
		assertTrue(ResidentProjection.load(extraKey).isEmpty());
	}

	@Test
	void countsResidentNamesByUnicodeCodePoint() {
		String sixtyFourCodePoints = "\ud83d\ude42".repeat(64);
		assertEquals(sixtyFourCodePoints, new ResidentProjection(EntityType.VILLAGER,
			VillagerProfession.LIBRARIAN, 1, sixtyFourCodePoints).customName());
		assertThrows(IllegalArgumentException.class, () -> new ResidentProjection(EntityType.VILLAGER,
			VillagerProfession.LIBRARIAN, 1, "\ud83d\ude42".repeat(65)));
	}

	@Test
	void mapsHorizontalFacingToResidentYaw() {
		assertEquals(0.0f, ResidentProjection.yawFor(Direction.SOUTH));
		assertEquals(270.0f, ResidentProjection.yawFor(Direction.WEST));
		assertEquals(180.0f, ResidentProjection.yawFor(Direction.NORTH));
		assertEquals(90.0f, ResidentProjection.yawFor(Direction.EAST));
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class,
				java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
