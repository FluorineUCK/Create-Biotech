package com.yision.allay.logistics.courier;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AllayCourierTaskSavedData extends SavedData {

	private static final String DATA_NAME = "create_biotech_allay_courier_tasks";

	private final List<AllayCourierTask> tasks = new ArrayList<>();

	public AllayCourierTaskSavedData() {}

	public static AllayCourierTaskSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
		AllayCourierTaskSavedData data = new AllayCourierTaskSavedData();
		ListTag list = tag.getList("Tasks", Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag taskTag = list.getCompound(i);
			try {
				AllayCourierTask task = AllayCourierTask.load(registries, taskTag);
				data.tasks.add(task);
			} catch (Exception e) {
			}
		}
		return data;
	}

	@Override
	public @NotNull CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		ListTag list = new ListTag();
		for (AllayCourierTask task : tasks) {
			if (!task.isRemoved()) {
				list.add(task.save(registries, new CompoundTag()));
			}
		}
		tag.put("Tasks", list);
		return tag;
	}

	public List<AllayCourierTask> getTasks() {
		return tasks;
	}

	public void addTask(AllayCourierTask task) {
		tasks.add(task);
		setDirty();
	}

	public boolean hasTask(UUID id) {
		return tasks.stream().anyMatch(task -> !task.isRemoved() && task.id().equals(id));
	}

	public void removeCompleted() {
		tasks.removeIf(AllayCourierTask::isRemoved);
		setDirty();
	}

	public void markDirty() {
		setDirty();
	}

	public static AllayCourierTaskSavedData getOrCreate(MinecraftServer server) {
		return server.getLevel(net.minecraft.world.level.Level.OVERWORLD)
			.getDataStorage()
			.computeIfAbsent(new SavedData.Factory<>(
				AllayCourierTaskSavedData::new,
				AllayCourierTaskSavedData::load,
				null
			), DATA_NAME);
	}
}
