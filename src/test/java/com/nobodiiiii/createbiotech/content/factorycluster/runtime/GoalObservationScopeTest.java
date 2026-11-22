package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.admission.ProductionAdmissions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.InventoryIdentity;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.StockObservationScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

class GoalObservationScopeTest {
	@BeforeAll
	static void bootstrapMinecraft() { bootstrap(); }

	@Test
	void restockerAdmissionRetainsExactAttachmentWhileFreezingAbsoluteTarget() {
		SpaceAddress packager = address(1);
		InventoryIdentity identity = new InventoryIdentity(address(2), Direction.WEST,
			ResourceLocation.withDefaultNamespace("chest"), Optional.empty(), id(3), 4);
		StackKey item = new StackKey(new ItemStack(Items.IRON_INGOT));
		StockObservationScope.AttachedInventory baseline =
			new StockObservationScope.AttachedInventory(packager, identity, item, 0);

		assertEquals(new StockObservationScope.AttachedInventory(packager, identity, item, 37),
			ProductionAdmissions.withAbsoluteTarget(baseline, 37));
	}

	private static SpaceAddress address(int x) {
		return new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 0, 0));
	}
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
