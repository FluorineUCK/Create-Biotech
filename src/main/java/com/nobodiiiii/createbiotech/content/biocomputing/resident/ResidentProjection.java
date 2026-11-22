package com.nobodiiiii.createbiotech.content.biocomputing.resident;

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.foundation.item.CBItemData;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;

/**
 * The only resident data allowed to cross from authoritative block state to a client renderer.
 * It deliberately contains registry identities and presentation metadata only, never entity NBT.
 */
public record ResidentProjection(EntityType<?> entityType, VillagerProfession profession, int level,
	@Nullable String customName) {
	private static final String ENTITY_TYPE = "EntityType";
	private static final String PROFESSION = "Profession";
	private static final String LEVEL = "Level";
	private static final String CUSTOM_NAME = "CustomName";
	private static final Set<String> REQUIRED = Set.of(ENTITY_TYPE, PROFESSION, LEVEL);

	public ResidentProjection {
		if (!supportedEntityType(entityType) || profession == null || level < 1 || level > 5
			|| (customName != null && customName.codePointCount(0, customName.length()) > 64))
			throw new IllegalArgumentException("invalid resident projection");
		if (BuiltInRegistries.ENTITY_TYPE.getKey(entityType) == null
			|| BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession) == null)
			throw new IllegalArgumentException("unregistered resident projection");
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString(ENTITY_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(entityType).toString());
		tag.putString(PROFESSION, BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).toString());
		tag.putInt(LEVEL, level);
		if (customName != null) tag.putString(CUSTOM_NAME, customName);
		return tag;
	}

	public static Optional<ResidentProjection> load(CompoundTag tag) {
		if (tag == null || !exactKeys(tag) || !has(tag, ENTITY_TYPE, Tag.TAG_STRING)
			|| !has(tag, PROFESSION, Tag.TAG_STRING) || !has(tag, LEVEL, Tag.TAG_INT)
			|| (tag.contains(CUSTOM_NAME) && !has(tag, CUSTOM_NAME, Tag.TAG_STRING)))
			return Optional.empty();
		ResourceLocation entityId = ResourceLocation.tryParse(tag.getString(ENTITY_TYPE));
		ResourceLocation professionId = ResourceLocation.tryParse(tag.getString(PROFESSION));
		if (entityId == null || professionId == null
			|| !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)
			|| !BuiltInRegistries.VILLAGER_PROFESSION.containsKey(professionId))
			return Optional.empty();
		try {
			return Optional.of(new ResidentProjection(BuiltInRegistries.ENTITY_TYPE.get(entityId),
				BuiltInRegistries.VILLAGER_PROFESSION.get(professionId), tag.getInt(LEVEL),
				tag.contains(CUSTOM_NAME) ? tag.getString(CUSTOM_NAME) : null));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	/** Converts an installed captured resident to the bounded projection without retaining entity NBT. */
	public static Optional<ResidentProjection> fromCapturedResident(ItemStack snapshot,
		HolderLookup.Provider registries) {
		if (snapshot.isEmpty() || !CapturedEntityBoxHelper.hasCapturedEntity(snapshot)) return Optional.empty();
		CompoundTag itemData = CBItemData.get(snapshot);
		if (itemData == null || !itemData.contains("CapturedEntity", Tag.TAG_COMPOUND))
			return Optional.empty();
		CompoundTag entity = itemData.getCompound("CapturedEntity");
		if (!entity.contains("id", Tag.TAG_STRING)) return Optional.empty();
		ResourceLocation entityId = ResourceLocation.tryParse(entity.getString("id"));
		if (entityId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) return Optional.empty();
		EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(entityId);
		if (!supportedEntityType(entityType)) return Optional.empty();
		String name = plainName(entity, registries);
		if (entityType == EntityType.WANDERING_TRADER)
			return Optional.of(new ResidentProjection(entityType, VillagerProfession.NONE, 1, name));
		if (!entity.contains("VillagerData", Tag.TAG_COMPOUND))
			return entityType == EntityType.ZOMBIE_VILLAGER
				? Optional.of(new ResidentProjection(entityType, VillagerProfession.NONE, 1, name))
				: Optional.empty();
		CompoundTag villagerData = entity.getCompound("VillagerData");
		if (!villagerData.contains("profession", Tag.TAG_STRING)
			|| !villagerData.contains("level", Tag.TAG_INT)) return Optional.empty();
		ResourceLocation professionId = ResourceLocation.tryParse(villagerData.getString("profession"));
		if (professionId == null || !BuiltInRegistries.VILLAGER_PROFESSION.containsKey(professionId))
			return Optional.empty();
		try {
			return Optional.of(new ResidentProjection(entityType,
				BuiltInRegistries.VILLAGER_PROFESSION.get(professionId), villagerData.getInt("level"), name));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	/** Legacy source compatibility; callers should use the resident-neutral name. */
	public static Optional<ResidentProjection> fromCapturedVillager(ItemStack snapshot,
		HolderLookup.Provider registries) {
		return fromCapturedResident(snapshot, registries);
	}

	public static float yawFor(Direction facing) {
		return switch (facing) {
			case SOUTH -> 0.0f;
			case WEST -> 270.0f;
			case NORTH -> 180.0f;
			case EAST -> 90.0f;
			default -> throw new IllegalArgumentException("resident requires horizontal facing");
		};
	}

	private static @Nullable String plainName(CompoundTag entity, HolderLookup.Provider registries) {
		if (!entity.contains(CUSTOM_NAME, Tag.TAG_STRING)) return null;
		try {
			Component name = Component.Serializer.fromJson(entity.getString(CUSTOM_NAME), registries);
			if (name == null) return null;
			String plain = name.getString();
			int codePoints = plain.codePointCount(0, plain.length());
			return codePoints <= 64 ? plain : plain.substring(0, plain.offsetByCodePoints(0, 64));
		} catch (RuntimeException malformedName) {
			return null;
		}
	}

	private static boolean exactKeys(CompoundTag tag) {
		Set<String> keys = tag.getAllKeys();
		return (keys.size() == REQUIRED.size() || keys.size() == REQUIRED.size() + 1)
			&& keys.containsAll(REQUIRED) && (keys.size() == REQUIRED.size() || keys.contains(CUSTOM_NAME));
	}

	private static boolean supportedEntityType(EntityType<?> entityType) {
		return entityType == EntityType.VILLAGER || entityType == EntityType.WANDERING_TRADER
			|| entityType == EntityType.ZOMBIE_VILLAGER;
	}

	private static boolean has(CompoundTag tag, String key, int type) {
		Tag value = tag.get(key);
		return value != null && value.getId() == type;
	}
}
