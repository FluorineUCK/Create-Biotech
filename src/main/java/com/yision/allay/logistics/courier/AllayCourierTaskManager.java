package com.yision.allay.logistics.courier;

import com.yision.allay.entity.courier.AllayCourierEntity;
import com.yision.allay.logistics.courier.hud.AllayCourierHudSync;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AllayCourierTaskManager {

	private static @Nullable AllayCourierTaskSavedData savedData;
	private static final Map<UUID, AllayCourierEntity> courierEntities = new HashMap<>();

	private AllayCourierTaskManager() {}

	public static void onServerStarting(MinecraftServer server) {
		AllayCourierHudSync.clear();
		courierEntities.values().forEach(entity -> {
			if (entity.isAlive()) {
				entity.discard();
			}
		});
		courierEntities.clear();
		savedData = AllayCourierTaskSavedData.getOrCreate(server);
	}

	public static void onServerTick(ServerTickEvent.Post event) {
		MinecraftServer server = event.getServer();
		if (savedData == null) {
			savedData = AllayCourierTaskSavedData.getOrCreate(server);
		}

		List<AllayCourierTask> tasks = savedData.getTasks();
		List<AllayCourierTask> completed = new ArrayList<>();

		for (AllayCourierTask task : tasks) {
			ServerLevel level = server.getLevel(task.currentDimension());
			AllayCourierEntity courier = findActiveCourier(task, level);
			if (courier == null && level != null && canShowEntity(level, task.position())) {
				courier = spawnCourier(level, task);
			}

			task.tick(server, courier);

			if (task.isRemoved()) {
				if (task.placeCourierWhenRemoved()) {
					placeCourier(server, task);
				} else {
					removeCourier(task);
				}
				completed.add(task);
				continue;
			}
			if (task.relocatedThisTick()) {
				removeCourier(task);
				courier = null;
			}

			level = server.getLevel(task.currentDimension());
			if (level == null) {
				removeCourier(task);
				continue;
			}

			courier = findActiveCourier(task, level);
			if (canShowEntity(level, task.position())) {
				if (courier == null) {
					courier = spawnCourier(level, task);
				}
				if (courier != null) {
					syncCourierMetadata(courier, task);
				}
			} else {
				removeCourier(task);
			}
		}

		if (!completed.isEmpty()) {
			savedData.removeCompleted();
		} else if (!tasks.isEmpty()) {
			savedData.markDirty();
		}
		AllayCourierHudSync.sync(server, tasks);
	}

	public static void addTask(MinecraftServer server, AllayCourierTask task) {
		if (savedData == null) {
			savedData = AllayCourierTaskSavedData.getOrCreate(server);
		}
		savedData.addTask(task);
		ServerLevel level = server.getLevel(task.currentDimension());
		if (level != null && canShowEntity(level, task.position())) {
			spawnCourier(level, task);
		}
	}

	public static boolean isCourierValidationReady() {
		return savedData != null;
	}

	public static boolean isCurrentCourier(UUID taskId, AllayCourierEntity entity) {
		return savedData != null
			&& savedData.hasTask(taskId)
			&& courierEntities.get(taskId) == entity;
	}

	private static boolean canShowEntity(ServerLevel level, net.minecraft.world.phys.Vec3 pos) {
		return level.isPositionEntityTicking(BlockPos.containing(pos));
	}

	private static @Nullable AllayCourierEntity findActiveCourier(AllayCourierTask task,
		@Nullable ServerLevel level) {
		AllayCourierEntity existing = courierEntities.get(task.id());
		if (existing == null) {
			return null;
		}
		if (!existing.isAlive() || existing.level() != level) {
			if (existing.isAlive()) {
				existing.discard();
			}
			courierEntities.remove(task.id());
			return null;
		}
		return existing;
	}

	private static @Nullable AllayCourierEntity spawnCourier(ServerLevel level, AllayCourierTask task) {
		removeCourier(task);
		AllayCourierEntity courier = AllayCourierEntity.createFromTask(level, task);
		if (level.addFreshEntity(courier)) {
			courierEntities.put(task.id(), courier);
			return courier;
		}
		return null;
	}

	private static void removeCourier(AllayCourierTask task) {
		AllayCourierEntity entity = courierEntities.remove(task.id());
		if (entity != null && entity.isAlive()) {
			entity.discard();
		}
	}

	private static void placeCourier(MinecraftServer server, AllayCourierTask task) {
		AllayCourierEntity entity = courierEntities.remove(task.id());
		ServerLevel level = server.getLevel(task.currentDimension());
		if (level == null) {
			if (entity != null && entity.isAlive()) {
				entity.becomePlaced(entity.position());
			}
			return;
		}

		if (entity != null && entity.isAlive() && entity.level() == level) {
			entity.becomePlaced(task.position());
			return;
		}

		level.getChunkAt(BlockPos.containing(task.position()));
		AllayCourierEntity placed = AllayCourierEntity.createFromTask(level, task);
		placed.becomePlaced(task.position());
		if (level.addFreshEntity(placed)) {
			if (entity != null && entity.isAlive()) {
				entity.discard();
			}
		} else if (entity != null && entity.isAlive()) {
			entity.becomePlaced(entity.position());
		}
	}

	private static void syncCourierMetadata(AllayCourierEntity entity, AllayCourierTask task) {
		entity.setPackage(task.box());
		entity.setPhase(task.phase());
		entity.setMission(task.mission());
	}
}
