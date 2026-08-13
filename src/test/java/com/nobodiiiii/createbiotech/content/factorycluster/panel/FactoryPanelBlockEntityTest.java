package com.nobodiiiii.createbiotech.content.factorycluster.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

class FactoryPanelBlockEntityTest {
	private static final UUID FIRST_NETWORK =
		UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID SECOND_NETWORK =
		UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID REBOUND_CLUSTER =
		UUID.fromString("20000000-0000-0000-0000-000000000001");

	@Test
	void bindingStateRoundTripsAndMissingIdentityTagsDoNotEraseGeneratedIds() {
		FactoryPanelBlockEntity source = panel();
		source.applyClusterBinding(REBOUND_CLUSTER, List.of(
			new LogisticsBinding(FIRST_NETWORK, "First"),
			new LogisticsBinding(FIRST_NETWORK, "Duplicate"),
			new LogisticsBinding(SECOND_NETWORK, "Second")));
		source.setSelectedNetwork(SECOND_NETWORK);
		CompoundTag saved = new CompoundTag();
		source.write(saved, RegistryAccess.EMPTY, false);

		FactoryPanelBlockEntity restored = panel();
		restored.read(saved, RegistryAccess.EMPTY, false);

		assertEquals(source.memberId(), restored.memberId());
		assertEquals(REBOUND_CLUSTER, restored.clusterId());
		assertEquals(List.of(FIRST_NETWORK, SECOND_NETWORK), restored.logisticsBindings().stream()
			.map(LogisticsBinding::logisticsId).toList());
		assertEquals(SECOND_NETWORK, restored.selectedNetwork());

		UUID generatedPanelId = restored.memberId();
		UUID loadedClusterId = restored.clusterId();
		restored.read(new CompoundTag(), RegistryAccess.EMPTY, false);
		assertEquals(generatedPanelId, restored.memberId());
		assertEquals(loadedClusterId, restored.clusterId());
	}

	@Test
	void panelOwnsBindingsWithoutCreateLogisticsBehaviour() {
		FactoryPanelBlockEntity panel = panel();

		assertTrue(panel.getAllBehaviours().isEmpty());
		assertFalse(panel.logisticsBindings().iterator().hasNext());
	}

	private static FactoryPanelBlockEntity panel() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
		return new FactoryPanelBlockEntity(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
	}

	private static void markMinecraftBootstrapped() {
		try {
			java.lang.reflect.Field bootstrapped =
				net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
			Object current = loadingModList.getMethod("get").invoke(null);
			if (current != null)
				return;
			Object empty = loadingModList.getMethod("of", List.class, List.class, List.class,
				List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null)
				throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
