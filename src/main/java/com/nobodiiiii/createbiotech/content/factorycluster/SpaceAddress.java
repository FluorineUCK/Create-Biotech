package com.nobodiiiii.createbiotech.content.factorycluster;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public record SpaceAddress(ResourceKey<Level> dimension, @Nullable UUID subLevelId,
	BlockPos localPos) {
	public SpaceAddress {
		Objects.requireNonNull(dimension, "dimension");
		localPos = Objects.requireNonNull(localPos, "localPos").immutable();
	}

	public static SpaceAddress capture(Level level, BlockPos pos) {
		return new SpaceAddress(level.dimension(), SubLevelCompat.getSpaceId(level, pos),
			pos.immutable());
	}

	public boolean matches(Level level, BlockPos pos) {
		return level.dimension().equals(dimension) && localPos.equals(pos)
			&& SubLevelCompat.matchesSpace(level, pos, subLevelId);
	}

	@Nullable
	public BlockEntity resolveBlockEntity(MinecraftServer server) {
		ServerLevel level = server.getLevel(dimension);
		return level == null ? null
			: SubLevelCompat.resolveBlockEntityFast(level, localPos, subLevelId);
	}

	public Vec3 worldCenter(ServerLevel level) {
		if (!level.dimension().equals(dimension))
			throw new IllegalArgumentException("Address belongs to another root dimension");
		return SubLevelCompat.toWorld(level, localPos, Vec3.atCenterOf(localPos));
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("Dimension", dimension.location().toString());
		if (subLevelId != null)
			tag.putUUID("SubLevel", subLevelId);
		tag.putLong("Pos", localPos.asLong());
		return tag;
	}

	public static Optional<SpaceAddress> tryLoad(CompoundTag tag) {
		return tryLoad(tag, null);
	}

	public static Optional<SpaceAddress> tryLoad(CompoundTag tag,
		@Nullable ResourceKey<Level> expectedDimension) {
		if (!tag.contains("Dimension", Tag.TAG_STRING)
			|| tag.getString("Dimension").isBlank()
			|| !tag.contains("Pos", Tag.TAG_LONG)
			|| (tag.contains("SubLevel") && !tag.hasUUID("SubLevel")))
			return Optional.empty();

		ResourceLocation location = ResourceLocation.tryParse(tag.getString("Dimension"));
		if (location == null)
			return Optional.empty();
		ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, location);
		if (expectedDimension != null && !expectedDimension.equals(dimension))
			return Optional.empty();
		UUID subLevelId = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
		return Optional.of(new SpaceAddress(dimension, subLevelId,
			BlockPos.of(tag.getLong("Pos"))));
	}
}
