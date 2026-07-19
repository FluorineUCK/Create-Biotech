package com.yision.allay.block.allayport;

import com.yision.allay.logistics.courier.AllayCourierTask;
import com.yision.allay.logistics.courier.AllayCourierTaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

final class AllayPortReturnQueue {

	static final int RETURN_RETRY_TICKS = 100;
	static final int RETURN_LAUNCH_DELAY_TICKS = 40;

	private final AllayPortBlockEntity port;
	private final AllayPortInventory inventory;
	private final Deque<PendingReturnCarrier> pendingReturnCarriers = new ArrayDeque<>();

	AllayPortReturnQueue(AllayPortBlockEntity port, AllayPortInventory inventory) {
		this.port = port;
		this.inventory = inventory;
	}

	void tick() {
		if (pendingReturnCarriers.isEmpty()) {
			return;
		}
		if (!inventory.hasStoredCarrier()) {
			pendingReturnCarriers.clear();
			port.setChanged();
			return;
		}
		PendingReturnCarrier task = pendingReturnCarriers.peekFirst();
		if (task == null) {
			return;
		}
		if (task.delayTicks() > 0) {
			pendingReturnCarriers.removeFirst();
			pendingReturnCarriers.addFirst(task.withDelay(task.delayTicks() - 1));
			port.setChanged();
			return;
		}
		if (task.isPlayerReturn()) {
			if (tryQueueStoredReturnCarrierToPlayer(task.playerId())) {
				pendingReturnCarriers.removeFirst();
				port.markPortContentsChanged();
				return;
			}
		} else if (task.isAllayPortReturn()) {
			if (tryQueueStoredReturnCarrier(task.dimension(), task.pos())) {
				pendingReturnCarriers.removeFirst();
				port.markPortContentsChanged();
				return;
			}
		}
		int remaining = task.retryTicks() - 1;
		if (remaining <= 0) {
			pendingReturnCarriers.removeFirst();
			if (task.isPlayerReturn()) {
				inventory.dropOneCarrier();
			}
			port.markPortContentsChanged();
			return;
		}
		pendingReturnCarriers.removeFirst();
		pendingReturnCarriers.addFirst(task.withRetry(remaining));
		port.setChanged();
	}

	boolean tryQueueReturnCarrier(@Nullable ResourceKey<Level> returnDimension,
								  @Nullable BlockPos returnPos) {
		if (!(port.getLevel() instanceof ServerLevel serverLevel) || returnDimension == null || returnPos == null) {
			return false;
		}
		launchTask(serverLevel, AllayCourierTask.forCarrierReturn(
			UUID.randomUUID(), serverLevel, returnDimension, returnPos,
			port.getCourierSpawnPosition(), port.getCourierLaunchDirection()));
		return true;
	}

	boolean receivePackageAndScheduleCarrierReturnToPlayer(ItemStack box, UUID playerId, int delayTicks) {
		if (!inventory.canReceivePackage(box) || !inventory.canReceiveCarrier()) {
			return false;
		}
		ItemStack carrier = com.yision.allay.registry.AllItems.ALLAY_COURIER.asStack();
		if (!inventory.carrierInventory.insertItem(0, carrier.copy(), false).isEmpty()) {
			return false;
		}
		if (!inventory.addPackage(box.copy(), false)) {
			inventory.carrierInventory.extractItem(0, 1, false);
			port.markPortContentsChanged();
			return false;
		}
		schedulePendingReturnToPlayer(playerId, delayTicks);
		return true;
	}

	AllayPortBlockEntity.CourierReceiveResult receivePackageAndHandleCarrier(
		ItemStack box,
		@Nullable ResourceKey<Level> returnDimension,
		@Nullable BlockPos returnPos
	) {
		if (!inventory.canReceivePackage(box)) {
			return AllayPortBlockEntity.CourierReceiveResult.REJECTED;
		}

		if (returnDimension != null && returnPos != null) {
			if (!inventory.canReceiveCarrier()) {
				return AllayPortBlockEntity.CourierReceiveResult.REJECTED;
			}
			if (!inventory.receivePackage(box)) {
				return AllayPortBlockEntity.CourierReceiveResult.REJECTED;
			}
			if (!inventory.receiveCarrier()) {
				return AllayPortBlockEntity.CourierReceiveResult.REJECTED;
			}
			schedulePendingReturnCarrier(returnDimension, returnPos, RETURN_LAUNCH_DELAY_TICKS);
			return AllayPortBlockEntity.CourierReceiveResult.CARRIER_STORED;
		}

		return inventory.receiveCourier(box)
			? AllayPortBlockEntity.CourierReceiveResult.CARRIER_STORED
			: AllayPortBlockEntity.CourierReceiveResult.REJECTED;
	}

	private void schedulePendingReturnCarrier(ResourceKey<Level> returnDimension, BlockPos returnPos, int delayTicks) {
		pendingReturnCarriers.addLast(PendingReturnCarrier.toAllayPort(
			returnDimension, returnPos, Math.max(0, delayTicks), RETURN_RETRY_TICKS));
		port.markPortContentsChanged();
	}

	private void schedulePendingReturnToPlayer(UUID playerId, int delayTicks) {
		pendingReturnCarriers.addLast(PendingReturnCarrier.toPlayer(
			playerId, Math.max(0, delayTicks), RETURN_RETRY_TICKS));
		port.markPortContentsChanged();
	}

	private boolean tryQueueStoredReturnCarrier(@Nullable ResourceKey<Level> returnDimension, @Nullable BlockPos returnPos) {
		if (!(port.getLevel() instanceof ServerLevel serverLevel) || returnDimension == null || returnPos == null) {
			return false;
		}
		if (!inventory.hasStoredCarrier()) {
			return false;
		}
		ItemStack storedCarrier = inventory.extractOneCarrier(false);
		if (storedCarrier.isEmpty()) {
			return false;
		}
		launchTask(serverLevel, AllayCourierTask.forCarrierReturn(
			UUID.randomUUID(), serverLevel, returnDimension, returnPos,
			port.getCourierSpawnPosition(), port.getCourierLaunchDirection()));
		port.markPortContentsChanged();
		return true;
	}

	private boolean tryQueueStoredReturnCarrierToPlayer(UUID playerId) {
		if (!(port.getLevel() instanceof ServerLevel serverLevel)) {
			return false;
		}
		ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(playerId);
		if (player == null || !player.isAlive()) {
			return false;
		}
		if (!inventory.hasStoredCarrier()) {
			return false;
		}
		ItemStack storedCarrier = inventory.extractOneCarrier(false);
		if (storedCarrier.isEmpty()) {
			return false;
		}
		launchTask(serverLevel, AllayCourierTask.forCarrierReturnToPlayer(
			UUID.randomUUID(), serverLevel, player.getUUID(), player.serverLevel().dimension(),
			port.getCourierSpawnPosition(), port.getCourierLaunchDirection()));
		port.markPortContentsChanged();
		return true;
	}

	private void launchTask(ServerLevel serverLevel, AllayCourierTask task) {
		AllayCourierTaskManager.addTask(serverLevel.getServer(), port.prepareCourierDeparture(task));
		port.flap(false);
	}

	void write(CompoundTag tag) {
		if (!pendingReturnCarriers.isEmpty()) {
			ListTag list = new ListTag();
			for (PendingReturnCarrier task : pendingReturnCarriers) {
				CompoundTag entry = new CompoundTag();
				if (task.isPlayerReturn()) {
					entry.putString("Type", "player");
					entry.putUUID("PlayerId", task.playerId());
				} else {
					entry.putString("Type", "allay_port");
					if (task.dimension() != null) {
						entry.putString("Dimension", task.dimension().location().toString());
					}
					if (task.pos() != null) {
						entry.put("Pos", NbtUtils.writeBlockPos(task.pos()));
					}
				}
				entry.putInt("DelayTicks", task.delayTicks());
				entry.putInt("RetryTicks", task.retryTicks());
				list.add(entry);
			}
			tag.put("PendingReturnCarriers", list);
		}
	}

	void read(CompoundTag tag) {
		pendingReturnCarriers.clear();
		if (tag.contains("PendingReturnCarriers", Tag.TAG_LIST)) {
			ListTag list = tag.getList("PendingReturnCarriers", Tag.TAG_COMPOUND);
			for (int i = 0; i < list.size(); i++) {
				CompoundTag entry = list.getCompound(i);
				String type = entry.getString("Type");
				int delay = entry.contains("DelayTicks") ? entry.getInt("DelayTicks") : 0;
				int retry = entry.contains("RetryTicks") ? entry.getInt("RetryTicks") : RETURN_RETRY_TICKS;
				if ("player".equals(type) && entry.hasUUID("PlayerId")) {
					pendingReturnCarriers.addLast(PendingReturnCarrier.toPlayer(entry.getUUID("PlayerId"), delay, retry));
				} else if ("allay_port".equals(type)) {
					ResourceKey<Level> dim = entry.contains("Dimension")
						? ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.getString("Dimension")))
						: null;
					BlockPos pos = NbtUtils.readBlockPos(entry, "Pos")
						.orElse(null);
					if (dim != null && pos != null) {
						pendingReturnCarriers.addLast(PendingReturnCarrier.toAllayPort(dim, pos, delay, retry));
					}
				}
			}
		}
	}

	private record PendingReturnCarrier(
		@Nullable ResourceKey<Level> dimension,
		@Nullable BlockPos pos,
		@Nullable UUID playerId,
		int delayTicks,
		int retryTicks
	) {
		static PendingReturnCarrier toAllayPort(ResourceKey<Level> dim, BlockPos p, int delay, int retry) {
			return new PendingReturnCarrier(dim, p.immutable(), null, delay, retry);
		}

		static PendingReturnCarrier toPlayer(UUID pid, int delay, int retry) {
			return new PendingReturnCarrier(null, null, pid, delay, retry);
		}

		PendingReturnCarrier withDelay(int newDelay) {
			return new PendingReturnCarrier(dimension, pos, playerId, newDelay, retryTicks);
		}

		PendingReturnCarrier withRetry(int newRetry) {
			return new PendingReturnCarrier(dimension, pos, playerId, delayTicks, newRetry);
		}

		boolean isPlayerReturn() {
			return playerId != null;
		}

		boolean isAllayPortReturn() {
			return dimension != null && pos != null;
		}
	}
}
