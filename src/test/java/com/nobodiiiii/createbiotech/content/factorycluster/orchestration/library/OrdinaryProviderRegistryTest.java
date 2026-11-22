package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.material.OrderedIngredient;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.OrdinaryProviderRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class OrdinaryProviderRegistryTest {
	private static StackKey OUTPUT;

	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class,
					java.util.Map.class).invoke(null, List.of(), List.of(), List.of(), List.of(),
					java.util.Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
		OUTPUT = new StackKey(new ItemStack(Items.GOLD_INGOT));
	}

	@Test
	void registeredOrdinaryProviderJoinsTheProductionTableAndWinsAcrossFourKinds() {
		UUID network = id(1), library = id(2), pattern = id(3);
		RouteSelectionScope scope = scope(10, OUTPUT);
		OrdinaryProviderRegistry registry = new OrdinaryProviderRegistry();
		registry.register(new OrdinaryProviderRegistry.Registration(id(4), network, OUTPUT, 40,
			RouteCandidate.Availability.AVAILABLE));
		ProductionRouteTable table = new ProductionRouteTable();
		table.publish(source(scope, library, pattern, "pattern", RouteCandidate.ProviderKind.PATTERN,
			10));
		table.publish(source(scope, library, pattern, "agent", RouteCandidate.ProviderKind.AGENT,
			20));
		table.publish(source(scope, library, pattern, "gauge",
			RouteCandidate.ProviderKind.FACTORY_GAUGE, 30));
		registry.collect(scope, network, library, pattern).forEach(table::publish);

		RouteCandidate winner = table.select(scope).winner().orElseThrow();
		assertEquals(RouteCandidate.ProviderKind.ORDINARY_PROVIDER, winner.providerKind());
		assertEquals("ordinary/" + id(4), winner.providerKey());
		assertEquals(4, table.snapshot(scope).size());
	}

	@Test
	void equalPriorityAvailableProvidersOverflowDeterministicallyWithoutReorderingIngredients() {
		UUID network = id(20), library = id(21), pattern = id(22);
		RouteSelectionScope scope = scope(23, OUTPUT);
		OrdinaryProviderRegistry registry = new OrdinaryProviderRegistry();
		registry.register(new OrdinaryProviderRegistry.Registration(id(24), network, OUTPUT, 7,
			RouteCandidate.Availability.AVAILABLE));
		ProductionRouteTable table = new ProductionRouteTable();
		table.publish(source(scope, library, pattern, "pattern", RouteCandidate.ProviderKind.PATTERN,
			7));
		table.publish(source(scope, library, pattern, "agent", RouteCandidate.ProviderKind.AGENT, 7));
		table.publish(source(scope, library, pattern, "gauge",
			RouteCandidate.ProviderKind.FACTORY_GAUGE, 7));
		registry.collect(scope, network, library, pattern).forEach(table::publish);
		List<OrderedIngredient> ingredients = List.of(
			new OrderedIngredient(new StackKey(new ItemStack(Items.COPPER_INGOT)), 2),
			new OrderedIngredient(new StackKey(new ItemStack(Items.IRON_INGOT)), 1));

		ArrayList<RouteCandidate.ProviderKind> selected = new ArrayList<>();
		for (int index = 0; index < 8; index++)
			selected.add(table.select(scope).winner().orElseThrow().providerKind());

		assertEquals(selected.subList(0, 4), selected.subList(4, 8));
		assertEquals(4, new LinkedHashSet<>(selected.subList(0, 4)).size());
		assertEquals(List.of(new StackKey(new ItemStack(Items.COPPER_INGOT)),
			new StackKey(new ItemStack(Items.IRON_INGOT))),
			ingredients.stream().map(OrderedIngredient::stack).toList(),
			"provider priority must never become Create ingredient order");
	}

	@Test
	void registryMatchesExactNetworkAndOutputAndSupportsRemoval() {
		OrdinaryProviderRegistry registry = new OrdinaryProviderRegistry();
		UUID provider = id(30), network = id(31);
		registry.register(new OrdinaryProviderRegistry.Registration(provider, network, OUTPUT, 1,
			RouteCandidate.Availability.BUSY));
		assertTrue(registry.collect(scope(32, new StackKey(new ItemStack(Items.IRON_INGOT))),
			network, id(33), id(34)).isEmpty());
		assertTrue(registry.collect(scope(35, OUTPUT), id(36), id(33), id(34)).isEmpty());
		assertTrue(registry.unregister(provider));
		assertTrue(registry.collect(scope(37, OUTPUT), network, id(33), id(34)).isEmpty());
	}

	private static ProductionRouteTable.ProviderSource source(RouteSelectionScope scope,
		UUID library, UUID pattern, String key, RouteCandidate.ProviderKind kind, int priority) {
		return new ProductionRouteTable.ProviderSource(scope, library, pattern, key, kind, priority,
			RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
	}

	private static RouteSelectionScope scope(long value, StackKey output) {
		return new RouteSelectionScope(id(value), id(value + 1), output, 1, 0, id(value + 2));
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
