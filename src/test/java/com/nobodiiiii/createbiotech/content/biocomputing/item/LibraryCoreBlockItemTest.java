package com.nobodiiiii.createbiotech.content.biocomputing.item;

import com.nobodiiiii.createbiotech.content.biocomputing.library.LibraryCoreItemData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.CustomData;

class LibraryCoreBlockItemTest {
	private static final Path JAVA = Path.of("src/main/java/com/nobodiiiii/createbiotech");

	@BeforeAll
	static void bootstrap() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
	}

    @Test void freshStackPlacesWithoutConfigurationAndBadOwnedPayloadNeverPlaces() {
        var probe = new PlacementProbe(true, true);
        var fresh = new ItemStack(Items.STONE);
        assertTrue(LibraryCoreBlockItem.transferPlacement(fresh, probe));
        assertEquals(1, probe.claimAttempts);
        var tag = new CompoundTag();
        tag.putString("create_biotech:pattern_core", "broken");
        fresh.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        assertFalse(LibraryCoreBlockItem.transferPlacement(fresh, probe));
        assertEquals(1, probe.lowerPlacements);
    }

	@Test
	void configuredPlacementTransferRollsBackWithoutConsumingWhenClaimFails() {
		LibraryCoreItemData configured = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), 12, Optional.empty(), 1);
		PlacementProbe rejected = new PlacementProbe(true, false);

		assertFalse(LibraryCoreBlockItem.transferPlacement(configured, rejected));
		assertEquals(1, rejected.lowerPlacements);
		assertEquals(1, rejected.claimAttempts);
		assertEquals(1, rejected.rollbacks);
		assertEquals(1, rejected.sourceCount,
			"BlockItem consumption must remain outside a rejected transfer");

		PlacementProbe accepted = new PlacementProbe(true, true);
		assertTrue(LibraryCoreBlockItem.transferPlacement(configured, accepted));
		assertEquals(1, accepted.lowerPlacements);
		assertEquals(1, accepted.claimAttempts);
		assertEquals(0, accepted.rollbacks);
	}

	@Test
	void creativePickBlockDataAndBlockStateComponentsAreRejectedBeforePlacement() {
		LibraryCoreItemData configured = LibraryCoreItemData.configured(
			CommunicationFrequency.of("Library-A"), 12, Optional.empty(), 1);
		ItemStack ctrlPickWithData = new ItemStack(Items.STONE);
		LibraryCoreItemData.write(ctrlPickWithData, configured);
		CompoundTag copiedCoreData = new CompoundTag();
		copiedCoreData.putUUID("LibraryId", java.util.UUID.randomUUID());
		ctrlPickWithData.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(copiedCoreData));
		ItemStack beforeDataRejection = ctrlPickWithData.copy();
		PlacementProbe dataProbe = new PlacementProbe(true, true);

		assertFalse(LibraryCoreBlockItem.transferPlacement(ctrlPickWithData, dataProbe));
		assertEquals(0, dataProbe.lowerPlacements);
		assertEquals(0, dataProbe.claimAttempts);
		assertEquals(0, dataProbe.rollbacks);
		assertTrue(ItemStack.matches(beforeDataRejection, ctrlPickWithData));

		ItemStack stateReplay = new ItemStack(Items.STONE);
		LibraryCoreItemData.write(stateReplay, configured);
		stateReplay.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
		ItemStack beforeStateRejection = stateReplay.copy();
		PlacementProbe stateProbe = new PlacementProbe(true, true);
		assertFalse(LibraryCoreBlockItem.transferPlacement(stateReplay, stateProbe));
		assertEquals(0, stateProbe.lowerPlacements);
		assertEquals(0, stateProbe.claimAttempts);
		assertEquals(0, stateProbe.rollbacks);
		assertTrue(ItemStack.matches(beforeStateRejection, stateReplay));
	}

	@Test
	void registeredBlockItemIsTheSoleNewlyPlacedCorePath() throws Exception {
		String items = Files.readString(JAVA.resolve("registry/CBItems.java"));
		String coreItem = Files.readString(JAVA.resolve(
			"content/biocomputing/item/LibraryCoreBlockItem.java"));

		assertTrue(items.contains("new LibraryCoreBlockItem(CBBlocks.PATTERN_STORAGE_CORE.get()"));
		assertFalse(items.contains("new BlockItem(CBBlocks.PATTERN_STORAGE_CORE.get()"));
		assertFalse(coreItem.contains("openEditor("));
		assertTrue(coreItem.contains("super.useOn(context)"));
		assertFalse(Files.exists(JAVA.resolve(
			"content/factorycluster/pattern/PatternStorageCoreConversionHandler.java")));
	}

	@Test
	void removingLecternConversionKeepsControlledPlayerBreakReachable() throws Exception {
		Path handler = JAVA.resolve(
			"content/biocomputing/library/LibraryBreakHandler.java");
		assertTrue(Files.exists(handler),
			"the controlled lifecycle needs an actual BlockEvent entry point");
		String source = Files.readString(handler);
		assertTrue(source.contains("BlockEvent.BreakEvent"));
		assertTrue(source.contains("event.setCanceled(true)"));
		assertTrue(source.contains("LibraryRemoval.controlledPlayerBreak"));
		assertFalse(source.contains("LECTERN"));
		assertFalse(source.contains("tryConvert"));
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (loadingModList.getMethod("get").invoke(null) != null) return;
			Object empty = loadingModList.getMethod("of", java.util.List.class,
				java.util.List.class, java.util.List.class, java.util.List.class,
				java.util.Map.class).invoke(null, java.util.List.of(), java.util.List.of(),
				java.util.List.of(), java.util.List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void markMinecraftBootstrapped() {
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class
				.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class PlacementProbe
		implements LibraryCoreBlockItem.PlacementOps {
		private final boolean placeLower;
		private final boolean claim;
		private int sourceCount = 1;
		private int lowerPlacements;
		private int claimAttempts;
		private int rollbacks;

		private PlacementProbe(boolean placeLower, boolean claim) {
			this.placeLower = placeLower;
			this.claim = claim;
		}

		@Override public boolean placeLower() {
			lowerPlacements++;
			return placeLower;
		}

		@Override public boolean applyPlacementData(LibraryCoreItemData data) {
			claimAttempts++;
			return claim;
		}

		@Override public void rollbackLower() { rollbacks++; }
	}
}
