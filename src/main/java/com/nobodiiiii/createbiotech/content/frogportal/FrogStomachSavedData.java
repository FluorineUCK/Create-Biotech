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
import net.minecraft.world.phys.Vec3;

/**
 * World-persistent state for the Frog Stomach dimension:
 * <ul>
 *   <li>a monotonic counter that hands out a unique space index to every Giant Frog on its first
 *       successful swallow, and</li>
 *   <li>a per-player, per-room record of where each entity entered from, so a Frog Esophagus sends
 *       them back to where that frog swallowed them into that specific room.</li>
 * </ul>
 * Stored on the overworld data storage, mirroring {@code ShulkerTeleporterSavedData}.
 */
public class FrogStomachSavedData extends SavedData {

	private static final String DATA_NAME = "create_biotech_frog_stomach";

	private long nextIndex = 0L;
	private final Map<ReturnKey, Location> returnPoints = new HashMap<>();

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

	public void setReturn(UUID uuid, long spaceIndex, ResourceKey<Level> dimension, Vec3 pos) {
		returnPoints.put(new ReturnKey(uuid, spaceIndex), new Location(dimension, pos));
		setDirty();
	}

	@Nullable
	public Location getReturn(UUID uuid, long spaceIndex) {
		return returnPoints.get(new ReturnKey(uuid, spaceIndex));
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		tag.putLong("NextIndex", nextIndex);
		ListTag entries = new ListTag();
		for (Map.Entry<ReturnKey, Location> entry : returnPoints.entrySet()) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putUUID("UUID", entry.getKey().uuid());
			entryTag.putLong("SpaceIndex", entry.getKey().spaceIndex());
			entryTag.putString("Dimension", entry.getValue().dimension().location().toString());
			entryTag.putDouble("X", entry.getValue().pos().x);
			entryTag.putDouble("Y", entry.getValue().pos().y);
			entryTag.putDouble("Z", entry.getValue().pos().z);
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
				if (!entryTag.contains("SpaceIndex", Tag.TAG_LONG))
					continue;
				ResourceLocation dimensionId = ResourceLocation.parse(entryTag.getString("Dimension"));
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				Vec3 pos = readReturnPos(entryTag);
				if (pos == null)
					continue;
				data.returnPoints.put(new ReturnKey(entryTag.getUUID("UUID"), entryTag.getLong("SpaceIndex")),
					new Location(dimension, pos));
			} catch (IllegalArgumentException ignored) {}
		}
		return data;
	}

	@Nullable
	private static Vec3 readReturnPos(CompoundTag tag) {
		if (tag.contains("X", Tag.TAG_DOUBLE) && tag.contains("Y", Tag.TAG_DOUBLE)
			&& tag.contains("Z", Tag.TAG_DOUBLE))
			return new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
		if (tag.contains("Pos", Tag.TAG_LONG))
			return Vec3.atBottomCenterOf(BlockPos.of(tag.getLong("Pos")).above());
		return null;
	}

	private record ReturnKey(UUID uuid, long spaceIndex) {}

	public record Location(ResourceKey<Level> dimension, Vec3 pos) {}
}
