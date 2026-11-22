package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class ManagedOutputEndpointTest {
	private static final UUID RUN = id(1);
	private static final CreateRouteKey ROUTE = new CreateRouteKey(id(2), "output");
	private static final SpaceAddress PACKAGER = address(3);
	private static final InventoryIdentity INVENTORY = new InventoryIdentity(address(4),
		Direction.WEST, ResourceLocation.withDefaultNamespace("chest"), Optional.empty(), id(5), 0);

	@BeforeAll
	static void bootstrapMinecraft() { bootstrap(); }

	@Test
	void outputProbeFreezesExactManagedPackagerAndAttachmentIdentity() {
		FactoryOrchestrationServer.ManagedOutputEndpoint managed =
			new FactoryOrchestrationServer.ManagedOutputEndpoint(PACKAGER, INVENTORY);
		FactoryOrchestrationServer.OutputProbeState probe =
			new FactoryOrchestrationServer.OutputProbeState(RUN, id(6), 0,
				Map.of(ROUTE, Optional.of(iron())), Map.of(ROUTE, 0L), Map.of(ROUTE, 0L),
				Map.of(ROUTE, managed), Set.of());

		assertTrue(FactoryOrchestrationServer.matchesManagedOutput(probe, ROUTE,
			PACKAGER, INVENTORY));
		assertFalse(FactoryOrchestrationServer.matchesManagedOutput(probe, ROUTE,
			address(7), INVENTORY));
		InventoryIdentity rebound = new InventoryIdentity(INVENTORY.inventoryAddress(),
			INVENTORY.accessFace(), INVENTORY.blockId(), INVENTORY.blockEntityTypeId(),
			INVENTORY.attachmentInstanceId(), 1);
		assertFalse(FactoryOrchestrationServer.matchesManagedOutput(probe, ROUTE,
			PACKAGER, rebound));
	}

	@Test
	void probeRejectsAManagedEndpointMapThatDoesNotMatchItsRoutes() {
		assertThrows(IllegalArgumentException.class, () ->
			new FactoryOrchestrationServer.OutputProbeState(RUN, id(6), 0,
				Map.of(ROUTE, Optional.of(iron())), Map.of(ROUTE, 0L), Map.of(ROUTE, 0L),
				Map.of(), Set.of()));
	}

	private static SpaceAddress address(int x) {
		return new SpaceAddress(Level.OVERWORLD,
			null, new BlockPos(x, 2, 3));
	}
	private static StackKey iron() { return new StackKey(new ItemStack(Items.IRON_INGOT)); }
	private static void bootstrap() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true); field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
