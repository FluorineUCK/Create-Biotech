package com.nobodiiiii.createbiotech.content.factorycluster.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.Level;
import sun.misc.Unsafe;

@SuppressWarnings({"deprecation", "removal"})
class ComputerProfileTest {
	private static final Unsafe UNSAFE = unsafe();
	private static Level testLevel;
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			java.lang.reflect.Field bootstrapped =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
	@ParameterizedTest
	@CsvSource({
		"VILLAGER,1,1,1", "VILLAGER,2,2,1", "VILLAGER,3,3,2",
		"VILLAGER,4,4,2", "VILLAGER,5,4,2",
		"LIBRARIAN,1,4,2", "LIBRARIAN,2,6,2", "LIBRARIAN,3,8,3",
		"LIBRARIAN,4,12,3", "LIBRARIAN,5,16,4",
		"NITWIT,1,1,0", "WANDERING_TRADER,1,2,1",
		"ZOMBIE_VILLAGER,1,0,0"
	})
	void approvedTable(NodeKind kind, int level, int slots, int depth) {
		ComputerProfile profile = ComputerProfile.forKind(kind, level, 64).orElseThrow();

		assertEquals(slots, profile.slots());
		assertEquals(depth, profile.depth());
		assertEquals(kind == NodeKind.NITWIT, profile.loopAbort());
		assertEquals(kind == NodeKind.WANDERING_TRADER ? 64 : 0,
			profile.patternRangeBonus());
		assertEquals(profile, ComputerProfile.load(profile.save()).orElseThrow());
	}

	@Test
	void codecRejectsEveryIllegalKindCombination() {
		assertFalse(load("VILLAGER", 16, 4, false, 0).isPresent());
		assertFalse(load("LIBRARIAN", 4, 2, true, 0).isPresent());
		assertFalse(load("NITWIT", 1, 0, false, 0).isPresent());
		assertFalse(load("NITWIT", 1, 0, true, 1).isPresent());
		assertFalse(load("WANDERING_TRADER", 2, 1, false, 4097).isPresent());
		assertFalse(load("ZOMBIE_VILLAGER", 1, 0, false, 0).isPresent());
		assertFalse(load("ZOMBIE_VILLAGER", 0, 1, false, 0).isPresent());
	}

	@Test
	void constructorsRejectInvalidLevelAndBonusInsteadOfClamping() {
		assertTrue(ComputerProfile.forKind(NodeKind.VILLAGER, 0, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.LIBRARIAN, 6, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.NITWIT, 0, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 2, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.ZOMBIE_VILLAGER, 2, 64).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, -1).isEmpty());
		assertTrue(ComputerProfile.forKind(NodeKind.WANDERING_TRADER, 1, 4097).isEmpty());
	}

	@Test
	void fromEntityMatchesEveryApprovedRowWithoutChangingEntityData() {
		for (EntityCase testCase : entityCases()) {
			ComputerProfile profile = ComputerProfile.fromEntity(testCase.entity(), 64).orElseThrow();
			assertEquals(testCase.kind(), profile.kind());
			assertEquals(testCase.slots(), profile.slots());
			assertEquals(testCase.depth(), profile.depth());
			assertEquals(testCase.kind() == NodeKind.NITWIT, profile.loopAbort());
			assertEquals(testCase.kind() == NodeKind.WANDERING_TRADER ? 64 : 0,
				profile.patternRangeBonus());
			if (testCase.entity() instanceof Villager villager)
				assertEquals(testCase.data(), villager.getVillagerData());
		}
	}

	@Test
	void codecRejectsWrongTypesMissingExtraUnknownAndNonCanonicalValuesWithoutMutation() {
		CompoundTag valid = ComputerProfile.forKind(NodeKind.VILLAGER, 4, 64).orElseThrow().save();

		CompoundTag wrongType = valid.copy();
		wrongType.putString("Slots", "4");
		assertRejectedWithoutMutation(wrongType);
		CompoundTag missing = valid.copy();
		missing.remove("Depth");
		assertRejectedWithoutMutation(missing);
		CompoundTag extra = valid.copy();
		extra.putInt("Extra", 1);
		assertRejectedWithoutMutation(extra);
		CompoundTag unknown = valid.copy();
		unknown.putString("Kind", "GOLEM");
		assertRejectedWithoutMutation(unknown);
		CompoundTag nonCanonicalBoolean = valid.copy();
		nonCanonicalBoolean.putByte("LoopAbort", (byte) 2);
		assertRejectedWithoutMutation(nonCanonicalBoolean);
		CompoundTag negative = valid.copy();
		negative.putInt("PatternRangeBonus", -1);
		assertRejectedWithoutMutation(negative);
		CompoundTag unsupportedVersion = valid.copy();
		unsupportedVersion.putInt("Version", 2);
		assertRejectedWithoutMutation(unsupportedVersion);
	}

	@Test
	void fromEntityRejectsUnsupportedAndBabyEntitiesWithoutMutation() {
		Villager babyVillager = villager(VillagerProfession.NONE, 3);
		setField(net.minecraft.world.entity.AgeableMob.class, babyVillager, "age", -24000);
		VillagerData babyData = babyVillager.getVillagerData();
		assertTrue(ComputerProfile.fromEntity(babyVillager, 64).isEmpty());
		assertEquals(babyData, babyVillager.getVillagerData());

		Zombie unsupported = entity(Zombie.class);
		assertTrue(ComputerProfile.fromEntity(unsupported, 64).isEmpty());
	}

	private static List<EntityCase> entityCases() {
		return List.of(
			new EntityCase(NodeKind.VILLAGER, 1, 1, 1, villager(VillagerProfession.NONE, 1)),
			new EntityCase(NodeKind.VILLAGER, 2, 2, 1, villager(VillagerProfession.NONE, 2)),
			new EntityCase(NodeKind.VILLAGER, 3, 3, 2, villager(VillagerProfession.NONE, 3)),
			new EntityCase(NodeKind.VILLAGER, 4, 4, 2, villager(VillagerProfession.NONE, 4)),
			new EntityCase(NodeKind.VILLAGER, 5, 4, 2, villager(VillagerProfession.NONE, 5)),
			new EntityCase(NodeKind.LIBRARIAN, 1, 4, 2, villager(VillagerProfession.LIBRARIAN, 1)),
			new EntityCase(NodeKind.LIBRARIAN, 2, 6, 2, villager(VillagerProfession.LIBRARIAN, 2)),
			new EntityCase(NodeKind.LIBRARIAN, 3, 8, 3, villager(VillagerProfession.LIBRARIAN, 3)),
			new EntityCase(NodeKind.LIBRARIAN, 4, 12, 3, villager(VillagerProfession.LIBRARIAN, 4)),
			new EntityCase(NodeKind.LIBRARIAN, 5, 16, 4, villager(VillagerProfession.LIBRARIAN, 5)),
			new EntityCase(NodeKind.NITWIT, 1, 1, 0, villager(VillagerProfession.NITWIT, 1)),
			new EntityCase(NodeKind.WANDERING_TRADER, 1, 2, 1,
				entity(WanderingTrader.class)),
			new EntityCase(NodeKind.ZOMBIE_VILLAGER, 1, 0, 0,
				entity(ZombieVillager.class))
		);
	}

	private static Villager villager(VillagerProfession profession, int level) {
		Villager villager = entity(Villager.class);
		villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession, level));
		return villager;
	}

	private static <T extends Entity> T entity(Class<T> type) {
		try {
			T entity = type.cast(UNSAFE.allocateInstance(type));
			setField(Entity.class, entity, "level", testLevel());
			if (entity instanceof Villager) {
				setEntityData(entity, accessor(Villager.class, "DATA_VILLAGER_DATA"),
					new VillagerData(VillagerType.PLAINS, VillagerProfession.NONE, 1));
			} else if (entity instanceof ZombieVillager) {
				setEntityData(entity, accessor(Zombie.class, "DATA_BABY_ID"), false);
			}
			return entity;
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static <T> void setEntityData(Entity entity, EntityDataAccessor<T> accessor, T value)
		throws ReflectiveOperationException {
		SynchedEntityData.DataItem<?>[] items = new SynchedEntityData.DataItem<?>[255];
		Arrays.fill(items, new SynchedEntityData.DataItem<>(accessor, value));
		Constructor<SynchedEntityData> constructor = SynchedEntityData.class.getDeclaredConstructor(
			SyncedDataHolder.class, SynchedEntityData.DataItem[].class);
		constructor.setAccessible(true);
		setField(Entity.class, entity, "entityData", constructor.newInstance(entity, items));
	}

	@SuppressWarnings("unchecked")
	private static <T> EntityDataAccessor<T> accessor(Class<?> owner, String name)
		throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return (EntityDataAccessor<T>) field.get(null);
	}

	private static java.util.Optional<ComputerProfile> load(String kind, int slots, int depth,
		boolean loopAbort, int patternRangeBonus) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1);
		tag.putString("Kind", kind);
		tag.putInt("Slots", slots);
		tag.putInt("Depth", depth);
		tag.putBoolean("LoopAbort", loopAbort);
		tag.putInt("PatternRangeBonus", patternRangeBonus);
		return ComputerProfile.load(tag);
	}

	private static void assertRejectedWithoutMutation(CompoundTag tag) {
		CompoundTag before = tag.copy();
		assertTrue(ComputerProfile.load(tag).isEmpty());
		assertEquals(before, tag);
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Unsafe unsafe() {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (Unsafe) field.get(null);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Level uninitializedLevel() {
		try {
			return (Level) UNSAFE.allocateInstance(net.minecraft.server.level.ServerLevel.class);
		} catch (InstantiationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Level testLevel() {
		if (testLevel == null) testLevel = uninitializedLevel();
		return testLevel;
	}

	private static void setField(Class<?> owner, Object target, String name, Object value) {
		try {
			Field field = owner.getDeclaredField(name);
			UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record EntityCase(NodeKind kind, int level, int slots, int depth, Entity entity,
		VillagerData data) {
		EntityCase(NodeKind kind, int level, int slots, int depth, Entity entity) {
			this(kind, level, slots, depth, entity,
				entity instanceof Villager villager ? villager.getVillagerData() : null);
		}
	}
}
