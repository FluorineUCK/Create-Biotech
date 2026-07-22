package com.nobodiiiii.createbiotech.content.frogportal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-persistent state for the Frog Stomach dimension:
 * <ul>
 *   <li>a monotonic counter that hands out a unique space index to every Frog Portal on its first
 *       teleport, and</li>
 *   <li>a per-player record of where each entity entered from, so the Frog Esophagus can send them
 *       back.</li>
 * </ul>
 * Stored on the overworld data storage, mirroring {@code ShulkerTeleporterSavedData}.
 */
public class FrogStomachSavedData extends SavedData {

	private static final String DATA_NAME = "create_biotech_frog_stomach";

	private long nextIndex = 0L;
	private final Map<UUID, Location> returnPoints = new HashMap<>();

	public static FrogStomachSavedData get(MinecraftServer server) {
		return server.overworld()
			.getDataStorage()
			.computeIfAbsent(new SavedData.Factory<>(FrogStomachSavedData::new, FrogStomachSavedData::load), DATA_NAME);
	}

	/** Allocate and return the next free space index. */
	public long allocateSpace() {
		long index = nextIndex++;
		setDirty();
		return index;
	}

	public void setReturn(UUID uuid, ResourceKey<Level> dimension, BlockPos pos) {
		returnPoints.put(uuid, new Location(dimension, pos.immutable()));
		setDirty();
	}

	@Nullable
	public Location getReturn(UUID uuid) {
		return returnPoints.get(uuid);
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putLong("NextIndex", nextIndex);
		ListTag entries = new ListTag();
		for (Map.Entry<UUID, Location> entry : returnPoints.entrySet()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putUUID("UUID", entry.getKey());
			entryTag.putString("Dimension", entry.getValue().dimension().location().toString());
			entryTag.putLong("Pos", entry.getValue().pos().asLong());
			entries.add(entryTag);
		}
		tag.put("ReturnPoints", entries);
		return tag;
	}

	private static FrogStomachSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		FrogStomachSavedData data = new FrogStomachSavedData();
		data.nextIndex = tag.getLong("NextIndex");
		ListTag entries = tag.getList("ReturnPoints", Tag.TAG_COMPOUND);
		for (Tag rawEntry : entries) {
			CompoundTag entryTag = (CompoundTag) rawEntry;
			try {
				ResourceLocation dimensionId = ResourceLocation.parse(entryTag.getString("Dimension"));
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				data.returnPoints.put(entryTag.getUUID("UUID"),
					new Location(dimension, BlockPos.of(entryTag.getLong("Pos"))));
			} catch (IllegalArgumentException ignored) {}
		}
		return data;
	}

	public record Location(ResourceKey<Level> dimension, BlockPos pos) {}
}
