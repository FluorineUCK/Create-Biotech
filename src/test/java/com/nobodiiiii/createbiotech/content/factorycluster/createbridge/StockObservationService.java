package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Server-thread gateway that freezes mutable Create inventory state into immutable facts. */
public final class StockObservationService {
	private final ServerThreadGuard serverThread;
	private final StockReader reader;

	public StockObservationService(ServerThreadGuard serverThread, StockReader reader) {
		this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
		this.reader = Objects.requireNonNull(reader, "reader");
	}

	public static StockObservationService usingCreate(MinecraftServer server,
		InventoryIdentity.PersistentAttachmentBindingRegistry bindings) {
		Objects.requireNonNull(server, "server");
		return new StockObservationService(server::isSameThread,
			new CreateStockReader(server, bindings));
	}

	public Observation observe(StockObservationScope scope) {
		scope = Objects.requireNonNull(scope, "scope");
		if (!serverThread.isServerThread()) return Observation.waiting(scope, 0, 0);
		if (scope instanceof StockObservationScope.NetworkSummary network) {
			NetworkSnapshot snapshot = Objects.requireNonNull(reader.readNetwork(
				network.logisticsNetworkId(), network.item()), "network snapshot");
			if (!snapshot.loaded()) return Observation.waiting(scope, snapshot.actualCount(),
				snapshot.requestPromiseCount());
			return Observation.actual(scope, snapshot.actualCount(), snapshot.requestPromiseCount(),
				network.targetLevel());
		}
		StockObservationScope.AttachedInventory attached =
			(StockObservationScope.AttachedInventory) scope;
		AttachedSnapshot snapshot = Objects.requireNonNull(reader.readAttached(attached.packager(),
			attached.item()), "attached snapshot");
		if (!snapshot.loaded() || snapshot.identity().isEmpty()
			|| !snapshot.identity().orElseThrow().equals(attached.inventory()))
			return Observation.waiting(scope, snapshot.actualCount(),
				snapshot.requestPromiseCount());
		return Observation.actual(scope, snapshot.actualCount(), snapshot.requestPromiseCount(),
			attached.targetLevel());
	}

	public enum Status { TARGET_REACHED, BELOW_TARGET, WAIT_STOCK_OBSERVATION }

	public record Observation(StockObservationScope scope, long actualCount,
		long requestPromiseCount, Status status) {
		public Observation {
			scope = Objects.requireNonNull(scope, "scope");
			status = Objects.requireNonNull(status, "status");
			if (actualCount < 0 || requestPromiseCount < 0)
				throw new IllegalArgumentException("Observed stock cannot be negative");
			long target = targetLevel(scope);
			if (status == Status.TARGET_REACHED && actualCount < target
				|| status == Status.BELOW_TARGET && actualCount >= target)
				throw new IllegalArgumentException("Stock status contradicts its absolute target");
		}

		private static Observation actual(StockObservationScope scope, long actual,
			long promises, long target) {
			return new Observation(scope, actual, promises,
				actual >= target ? Status.TARGET_REACHED : Status.BELOW_TARGET);
		}

		private static Observation waiting(StockObservationScope scope, long actual,
			long promises) {
			return new Observation(scope, Math.max(0, actual), Math.max(0, promises),
				Status.WAIT_STOCK_OBSERVATION);
		}

		public Optional<WaitReason> waitReason() {
			return status == Status.WAIT_STOCK_OBSERVATION
				? Optional.of(WaitReason.STOCK_OBSERVATION) : Optional.empty();
		}

		private static long targetLevel(StockObservationScope scope) {
			return scope instanceof StockObservationScope.NetworkSummary network
				? network.targetLevel()
				: ((StockObservationScope.AttachedInventory) scope).targetLevel();
		}
	}

	public record NetworkSnapshot(boolean loaded, long actualCount, long requestPromiseCount) {
		public NetworkSnapshot {
			if (actualCount < 0 || requestPromiseCount < 0)
				throw new IllegalArgumentException("Observed stock cannot be negative");
		}
	}

	public record AttachedSnapshot(boolean loaded, Optional<InventoryIdentity> identity,
		long actualCount, long requestPromiseCount) {
		public AttachedSnapshot {
			identity = Objects.requireNonNull(identity, "identity");
			if (actualCount < 0 || requestPromiseCount < 0)
				throw new IllegalArgumentException("Observed stock cannot be negative");
			if (!loaded && identity.isPresent())
				throw new IllegalArgumentException("An unloaded inventory has no readable identity");
		}
	}

	@FunctionalInterface
	public interface ServerThreadGuard { boolean isServerThread(); }

	public interface StockReader {
		NetworkSnapshot readNetwork(UUID logisticsNetworkId, StackKey item);
		AttachedSnapshot readAttached(SpaceAddress packager, StackKey item);
	}

	private static final class CreateStockReader implements StockReader {
		private final MinecraftServer server;
		private final InventoryIdentity.PersistentAttachmentBindingRegistry bindings;
		private CreateStockReader(MinecraftServer server,
			InventoryIdentity.PersistentAttachmentBindingRegistry bindings) {
			this.server = server;
			this.bindings = Objects.requireNonNull(bindings, "bindings");
		}

		@Override
		public NetworkSnapshot readNetwork(UUID logisticsNetworkId, StackKey item) {
			InventorySummary summary = LogisticsManager.getSummaryOfNetwork(logisticsNetworkId,
				false);
			long actual = Math.max(0L, summary.getCountOf(item.stack()));
			return new NetworkSnapshot(true, actual, 0);
		}

		@Override
		public AttachedSnapshot readAttached(SpaceAddress address, StackKey item) {
			if (!(address.resolveBlockEntity(server) instanceof PackagerBlockEntity packager)
				|| packager.targetInventory == null || !packager.targetInventory.hasInventory())
				return new AttachedSnapshot(false, Optional.empty(), 0, 0);
			Optional<InventoryIdentity> identity = InventoryIdentity.capture(packager, bindings);
			if (identity.isEmpty()) return new AttachedSnapshot(false, Optional.empty(), 0, 0);
			IItemHandler inventory = packager.targetInventory.getInventory();
			long count = 0;
			for (int slot = 0; slot < inventory.getSlots(); slot++) {
				ItemStack stack = inventory.getStackInSlot(slot);
				if (ItemStack.isSameItemSameComponents(stack, item.stack()))
					count = Math.addExact(count, stack.getCount());
			}
			return new AttachedSnapshot(true, identity, count, 0);
		}
	}
}
