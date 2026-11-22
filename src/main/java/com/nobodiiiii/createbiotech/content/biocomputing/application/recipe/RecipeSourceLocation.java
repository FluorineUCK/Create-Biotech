package com.nobodiiiii.createbiotech.content.biocomputing.application.recipe;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Source identity only. Looking up or editing its world target belongs to an authorized host. */
public record RecipeSourceLocation(ResourceKey<Level> dimension, @Nullable UUID subLevelId,
	BlockPos localPos) {
	public RecipeSourceLocation {
		Objects.requireNonNull(dimension, "dimension");
		localPos = Objects.requireNonNull(localPos, "localPos").immutable();
	}

	/** Preserves the existing spatial representation used inside recipe page keys. */
	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Dimension", dimension.location().toString());
		if (subLevelId != null) tag.putUUID("SubLevel", subLevelId);
		tag.putLong("Pos", localPos.asLong());
		return tag;
	}

	public static Optional<RecipeSourceLocation> tryLoad(CompoundTag tag) {
		if (!tag.contains("Dimension", Tag.TAG_STRING)
			|| tag.getString("Dimension").isBlank()
			|| !tag.contains("Pos", Tag.TAG_LONG)
			|| (tag.contains("SubLevel") && !tag.hasUUID("SubLevel")))
			return Optional.empty();
		ResourceLocation location = ResourceLocation.tryParse(tag.getString("Dimension"));
		if (location == null) return Optional.empty();
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, location);
		UUID subLevelId = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
		return Optional.of(new RecipeSourceLocation(dimension, subLevelId, BlockPos.of(tag.getLong("Pos"))));
	}
}
