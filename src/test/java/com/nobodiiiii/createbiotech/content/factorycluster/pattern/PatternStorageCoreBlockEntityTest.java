package com.nobodiiiii.createbiotech.content.factorycluster.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.ClusterAuthority;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingPreparation;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterBindingService;
import com.nobodiiiii.createbiotech.content.factorycluster.ClusterMemberType;
import com.nobodiiiii.createbiotech.content.factorycluster.LogisticsBinding;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

class PatternStorageCoreBlockEntityTest {
	@Test
	void clientStateAcceptsConfiguredSafetyMaximaAndRejectsValuesAboveThem() {
		PatternCoreClientState state = new PatternCoreClientState(true, ResourceLocation.withDefaultNamespace("plains"),
			5, null, PatternLibraryScanner.StructureState.VALID, false, 1024, 1024, 1024, 10000, 1024);
		assertTrue(PatternCoreClientState.load(state.save()).isPresent());
		var invalid = state.save();
		invalid.putInt("LogicalMembers", 1025);
		assertFalse(PatternCoreClientState.load(invalid).isPresent());
	}

	@Test
	void freshCoreIsUnboundAndPreparationIsPure() {
		PatternStorageCoreBlockEntity core = core();
		UUID logistics = UUID.randomUUID();
		ClusterBinding proposed = new ClusterBinding(UUID.randomUUID(), 0,
			new ClusterAuthority(ClusterMemberType.PATTERN_CORE, core.memberId()),
			List.of(new LogisticsBinding(logistics, "network")));

		assertNull(core.bindingState());
		assertEquals(ClusterBindingPreparation.READY, core.prepareClusterBinding(proposed));
		assertNull(core.bindingState());
		assertTrue(PatternStorageCoreBlockEntity.bindingAllowsQuery(ClusterBindingService.BindingAccess.READY,
			proposed, logistics));
		assertFalse(PatternStorageCoreBlockEntity.bindingAllowsQuery(ClusterBindingService.BindingAccess.CONFLICT,
			proposed, logistics));
	}

	@Test
	void clientSyncUsesOnlyLibraryIdAndBoundedClientState() {
		PatternStorageCoreBlockEntity core = core();
		var tag = new net.minecraft.nbt.CompoundTag();
		core.write(tag, net.minecraft.core.RegistryAccess.EMPTY, true);
		assertEquals(java.util.Set.of("LibraryId", "ClientState"), tag.getAllKeys());
		assertEquals(java.util.Set.of("RenderLibrarian", "VillagerType", "LibrarianLevel", "StructureState",
			"PendingSafeRelease", "LogicalMembers", "OrdinaryBookshelves", "ChiseledBookshelves",
			"SearchBudget", "QueueCount"), tag.getCompound("ClientState").getAllKeys());
	}
	private static PatternStorageCoreBlockEntity core() {
		installEmptyLoadingModList();
		net.minecraft.SharedConstants.tryDetectVersion();
		markMinecraftBootstrapped();
		return new PatternStorageCoreBlockEntity(BlockEntityType.FURNACE, BlockPos.ZERO,
			Blocks.FURNACE.defaultBlockState());
	}

	private static void markMinecraftBootstrapped() {
		try {
			java.lang.reflect.Field bootstrapped = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			bootstrapped.setAccessible(true);
			bootstrapped.setBoolean(null, true);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void installEmptyLoadingModList() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) != null) return;
			Object empty = type.getMethod("of", List.class, List.class, List.class, List.class, java.util.Map.class)
				.invoke(null, List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
			if (empty == null) throw new IllegalStateException("LoadingModList.of returned null");
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
