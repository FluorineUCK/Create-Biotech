package com.nobodiiiii.createbiotech.content.factorycluster.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.NativeOrderLedger;
import com.nobodiiiii.createbiotech.content.factorycluster.createbridge.NativePackageObservation;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class OutputNativeReceiptStateTest {
	private static final CreateRouteKey ROUTE = new CreateRouteKey(id(1), "receiver");
	private final StackKey iron = new StackKey(new ItemStack(Items.IRON_INGOT));

	@BeforeAll static void bootstrap() {
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

	@Test
	void exactNativeOrderAggregatesSixtyFourPlusThirtySixAndIgnoresUnrelatedOrder() {
		NativeOrderLedger ledger = new NativeOrderLedger(Map.of(iron, 100L),
			List.of(new NativeOrderLedger.NativeOrderIdentity(41, Set.of(0))));
		FactoryOrchestrationServer.OutputNativeReceiptState initial =
			new FactoryOrchestrationServer.OutputNativeReceiptState(id(2), id(3), ROUTE,
				id(4), id(5), ledger.snapshot(), false);

		var unrelated = observation(42, 0, 100, true, true, true);
		var ignored = FactoryOrchestrationServer.observeOutputReceipt(initial, unrelated);
		assertEquals(initial, ignored);
		assertFalse(FactoryOrchestrationServer.outputReceiptComplete(ignored));

		var first = FactoryOrchestrationServer.observeOutputReceipt(initial,
			observation(41, 0, 64, true, false, true));
		assertFalse(FactoryOrchestrationServer.outputReceiptComplete(first));
		var completed = FactoryOrchestrationServer.observeOutputReceipt(first,
			observation(41, 1, 36, true, true, false));
		assertTrue(FactoryOrchestrationServer.outputReceiptComplete(completed));
		assertEquals(completed, FactoryOrchestrationServer.observeOutputReceipt(completed,
			observation(41, 1, 36, true, true, false)), "duplicate fragment is idempotent");
	}

	private NativePackageObservation observation(int orderId, int fragment, int count,
		boolean finalLink, boolean last, boolean withContext) {
		return new NativePackageObservation(ROUTE, true, orderId, 0, fragment, finalLink, last,
			withContext ? new NativeOrderLedger.OrderContext(Map.of(iron, 100L)) : null,
			Map.of(iron, (long) count));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
