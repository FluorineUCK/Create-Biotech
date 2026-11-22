package com.nobodiiiii.createbiotech.content.factorycluster.createbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

class StockObservationServiceTest {
	private static final UUID NETWORK = UUID.fromString("00000000-0000-0000-0000-000000000911");
	private static StackKey ITEM;
	private static final SpaceAddress PACKAGER = new SpaceAddress(Level.OVERWORLD, null,
		new BlockPos(1, 2, 3));
	private static final InventoryIdentity INVENTORY = new InventoryIdentity(
		new SpaceAddress(Level.OVERWORLD, null, new BlockPos(1, 2, 4)), Direction.NORTH,
		ResourceLocation.parse("minecraft:chest"), Optional.of(ResourceLocation.parse("minecraft:chest")),
		UUID.fromString("00000000-0000-0000-0000-000000000912"), 3);

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		try {
			var bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		ITEM = new StackKey(new ItemStack(Items.IRON_INGOT));
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			loadingModList.getMethod("of", List.class, List.class, List.class, List.class,
				Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void observationScopesAreSealedAndFreezeAbsoluteTargets() {
		assertTrue(StockObservationScope.class.isSealed());
		assertEquals(2, StockObservationScope.class.getPermittedSubclasses().length);
		assertEquals(12, new StockObservationScope.NetworkSummary(NETWORK, ITEM, 12).targetLevel());
		assertEquals(INVENTORY, new StockObservationScope.AttachedInventory(PACKAGER, INVENTORY,
			ITEM, 7).inventory());
	}

	@Test
	void networkSummaryUsesActualCountAndNeverRequestPromise() {
		StockObservationService service = new StockObservationService(() -> true,
			new Reader(new StockObservationService.NetworkSnapshot(true, 4, 200), null));
		StockObservationScope scope = new StockObservationScope.NetworkSummary(NETWORK, ITEM, 5);

		StockObservationService.Observation below = service.observe(scope);
		assertEquals(StockObservationService.Status.BELOW_TARGET, below.status());
		assertEquals(4, below.actualCount());
		assertEquals(200, below.requestPromiseCount());
	}

	@Test
	void exactAttachedInventoryCompletesOnlyAtAbsoluteTarget() {
		StockObservationService service = new StockObservationService(() -> true,
			new Reader(null, new StockObservationService.AttachedSnapshot(true,
				Optional.of(INVENTORY), 8, 40)));
		StockObservationScope scope = new StockObservationScope.AttachedInventory(PACKAGER,
			INVENTORY, ITEM, 8);

		StockObservationService.Observation observed = service.observe(scope);
		assertEquals(StockObservationService.Status.TARGET_REACHED, observed.status());
		assertEquals(8, observed.actualCount());
	}

	@Test
	void unloadOrIdentityChangeWaitsForStockObservation() {
		StockObservationScope scope = new StockObservationScope.AttachedInventory(PACKAGER,
			INVENTORY, ITEM, 1);
		StockObservationService unloaded = new StockObservationService(() -> true,
			new Reader(null, new StockObservationService.AttachedSnapshot(false,
				Optional.empty(), 0, 100)));
		InventoryIdentity replacement = new InventoryIdentity(INVENTORY.inventoryAddress(),
			INVENTORY.accessFace(), INVENTORY.blockId(), INVENTORY.blockEntityTypeId(),
			INVENTORY.attachmentInstanceId(), INVENTORY.attachmentGeneration() + 1);
		StockObservationService changed = new StockObservationService(() -> true,
			new Reader(null, new StockObservationService.AttachedSnapshot(true,
				Optional.of(replacement), 100, 0)));

		assertEquals(StockObservationService.Status.WAIT_STOCK_OBSERVATION,
			unloaded.observe(scope).status());
		assertEquals(Optional.of(WaitReason.STOCK_OBSERVATION), unloaded.observe(scope).waitReason());
		assertEquals(StockObservationService.Status.WAIT_STOCK_OBSERVATION,
			changed.observe(scope).status());
	}

	@Test
	void samePositionAndBlockTypeReplacementOrTopologyRebindCannotReplayIdentity() {
		InventoryIdentity rebound = new InventoryIdentity(INVENTORY.inventoryAddress(),
			INVENTORY.accessFace(), INVENTORY.blockId(), INVENTORY.blockEntityTypeId(),
			INVENTORY.attachmentInstanceId(), INVENTORY.attachmentGeneration() + 1);
		StockObservationScope scope = new StockObservationScope.AttachedInventory(PACKAGER,
			INVENTORY, ITEM, 1);
		StockObservationService service = new StockObservationService(() -> true,
			new Reader(null, new StockObservationService.AttachedSnapshot(true,
				Optional.of(rebound), 100, 0)));

		assertEquals(StockObservationService.Status.WAIT_STOCK_OBSERVATION,
			service.observe(scope).status());
	}

	@Test
	void persistentBindingRegistryIncrementsOnSameTypeReplacementAndCapabilityRebind() {
		Map<SpaceAddress, InventoryIdentity.PersistedAttachmentBinding> durable =
			new LinkedHashMap<>();
		InventoryIdentity.AttachmentBindingStore store = new InventoryIdentity.AttachmentBindingStore() {
			@Override public Optional<InventoryIdentity.PersistedAttachmentBinding> load(
				SpaceAddress packager) { return Optional.ofNullable(durable.get(packager)); }
			@Override public void persist(SpaceAddress packager,
				InventoryIdentity.PersistedAttachmentBinding binding) {
				durable.put(packager, binding);
			}
		};
		InventoryIdentity.PersistentAttachmentBindingRegistry registry =
			new InventoryIdentity.PersistentAttachmentBindingRegistry(store);
		InventoryIdentity.PhysicalAttachment physical = new InventoryIdentity.PhysicalAttachment(
			INVENTORY.inventoryAddress(), INVENTORY.accessFace(), INVENTORY.blockId(),
			INVENTORY.blockEntityTypeId());
		Object firstCapability = new Object();
		InventoryIdentity.AttachmentBinding first = registry.resolve(PACKAGER, physical,
			live(firstCapability)).orElseThrow();
		assertEquals(first, registry.resolve(PACKAGER, physical, live(firstCapability)).orElseThrow());

		InventoryIdentity.AttachmentBinding rebound = registry.resolve(PACKAGER, physical,
			live(new Object())).orElseThrow();
		assertEquals(first.generation() + 1, rebound.generation());
		InventoryIdentity.AttachmentBinding afterRestart =
			new InventoryIdentity.PersistentAttachmentBindingRegistry(store)
				.resolve(PACKAGER, physical, live(new Object())).orElseThrow();
		assertEquals(rebound.generation() + 1, afterRestart.generation());
	}

	@Test
	void stableBlockEntityDoesNotRebindWhenCapabilityWrapperIsRecreated() {
		Map<SpaceAddress, InventoryIdentity.PersistedAttachmentBinding> durable =
			new LinkedHashMap<>();
		InventoryIdentity.AttachmentBindingStore store = new InventoryIdentity.AttachmentBindingStore() {
			@Override public Optional<InventoryIdentity.PersistedAttachmentBinding> load(
				SpaceAddress packager) { return Optional.ofNullable(durable.get(packager)); }
			@Override public void persist(SpaceAddress packager,
				InventoryIdentity.PersistedAttachmentBinding binding) {
				durable.put(packager, binding);
			}
		};
		InventoryIdentity.PersistentAttachmentBindingRegistry registry =
			new InventoryIdentity.PersistentAttachmentBindingRegistry(store);
		InventoryIdentity.PhysicalAttachment physical = new InventoryIdentity.PhysicalAttachment(
			INVENTORY.inventoryAddress(), INVENTORY.accessFace(), INVENTORY.blockId(),
			INVENTORY.blockEntityTypeId());
		Object stableBlockEntity = new Object();
		InventoryIdentity.AttachmentBinding first = registry.resolve(PACKAGER, physical,
			new InventoryIdentity.LiveAttachmentBinding(stableBlockEntity, new Object(), 27))
			.orElseThrow();

		InventoryIdentity.AttachmentBinding newWrapper = registry.resolve(PACKAGER, physical,
			new InventoryIdentity.LiveAttachmentBinding(stableBlockEntity, new Object(), 27))
			.orElseThrow();
		assertEquals(first, newWrapper,
			"NeoForge may recreate a capability wrapper for the same live BlockEntity");

		InventoryIdentity.AttachmentBinding replaced = registry.resolve(PACKAGER, physical,
			new InventoryIdentity.LiveAttachmentBinding(new Object(), new Object(), 27))
			.orElseThrow();
		assertEquals(first.generation() + 1, replaced.generation(),
			"replacing the backing BlockEntity must still advance the durable binding");
	}

	private static InventoryIdentity.LiveAttachmentBinding live(Object capability) {
		return new InventoryIdentity.LiveAttachmentBinding(null, capability, 27);
	}

	@Test
	void mutableInventoryReadsAreRejectedOffServerThread() {
		StockObservationService service = new StockObservationService(() -> false,
			new Reader(new StockObservationService.NetworkSnapshot(true, 99, 0), null));
		StockObservationScope scope = new StockObservationScope.NetworkSummary(NETWORK, ITEM, 1);

		assertEquals(StockObservationService.Status.WAIT_STOCK_OBSERVATION,
			service.observe(scope).status());
	}

	@Test
	void immutableFactCannotClaimTargetReachedBelowItsFrozenAbsoluteTarget() {
		StockObservationScope scope = new StockObservationScope.NetworkSummary(NETWORK, ITEM, 10);
		assertThrows(IllegalArgumentException.class, () ->
			new StockObservationService.Observation(scope, 0, 100,
				StockObservationService.Status.TARGET_REACHED));
	}

	private record Reader(StockObservationService.NetworkSnapshot network,
		StockObservationService.AttachedSnapshot attached)
		implements StockObservationService.StockReader {
		@Override public StockObservationService.NetworkSnapshot readNetwork(UUID logisticsNetworkId,
			StackKey item) { return network; }
		@Override public StockObservationService.AttachedSnapshot readAttached(SpaceAddress packager,
			StackKey item) { return attached; }
	}
}
