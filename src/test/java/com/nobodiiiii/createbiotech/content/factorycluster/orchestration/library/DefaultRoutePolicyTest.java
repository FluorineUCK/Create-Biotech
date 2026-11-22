package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.library;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCandidate;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteSelectionScope;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.ProductionRouteTable;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.DefaultRoutePolicy;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.routing.RouteCursorCodecs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class DefaultRoutePolicyTest {
	@Test
	void picksHighestPriorityAvailableRouteAcrossEveryProviderKind() {
		DefaultRoutePolicy policy = new DefaultRoutePolicy();
		RouteCandidate pattern = candidate(1, 1, "pattern", RouteCandidate.ProviderKind.PATTERN,
			4, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		RouteCandidate gauge = candidate(2, 2, "gauge", RouteCandidate.ProviderKind.FACTORY_GAUGE,
			9, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		RouteCandidate agent = candidate(3, 3, "agent", RouteCandidate.ProviderKind.AGENT,
			6, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		RouteCandidate registeredProvider = candidate(4, 4, "registered-provider",
			RouteCandidate.ProviderKind.FACTORY_GAUGE, 5, RouteCandidate.Availability.AVAILABLE,
			RouteCandidate.Blocker.NONE);

		DefaultRoutePolicy.Decision decision = policy.select(List.of(pattern, gauge, agent,
			registeredProvider));

		assertEquals(gauge, decision.winner().orElseThrow());
	}

	@Test
	void fourProviderKindsRetainDeterministicEqualPriorityOverflow() {
		RouteCandidate pattern = candidate(1, 1, "pattern", RouteCandidate.ProviderKind.PATTERN,
			8, RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
		RouteCandidate agent = candidate(1, 2, "agent", RouteCandidate.ProviderKind.AGENT,
			8, RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
		RouteCandidate gauge = candidate(1, 3, "gauge", RouteCandidate.ProviderKind.FACTORY_GAUGE,
			8, RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
		RouteCandidate provider = candidate(1, 4, "provider", RouteCandidate.ProviderKind.FACTORY_GAUGE,
			8, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);

		assertEquals(provider, new DefaultRoutePolicy().select(List.of(gauge, agent, provider,
			pattern)).winner().orElseThrow());
	}

	@Test
	void roundRobinsEqualPriorityAvailableRoutesFromPersistedCursor() {
		RouteCandidate first = available(1, 1, "a", 7);
		RouteCandidate second = available(1, 2, "b", 7);
		DefaultRoutePolicy policy = new DefaultRoutePolicy();

		assertEquals(first, policy.select(List.of(second, first)).winner().orElseThrow());
		net.minecraft.nbt.CompoundTag saved = RouteCursorCodecs.save(policy.cursorState());
		DefaultRoutePolicy restored = new DefaultRoutePolicy(
			RouteCursorCodecs.load(saved).orElseThrow());
		assertEquals(second, restored.select(List.of(first, second)).winner().orElseThrow());
		assertEquals(first, restored.select(List.of(first, second)).winner().orElseThrow());
	}

	@Test
	void busyHighestPriorityRoutesMayOverflowToAvailableLowerPriority() {
		RouteCandidate busy = candidate(1, 1, "busy", RouteCandidate.ProviderKind.PATTERN,
			10, RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
		RouteCandidate lower = available(2, 2, "lower", 3);

		DefaultRoutePolicy.Decision decision = new DefaultRoutePolicy().select(List.of(lower, busy));

		assertEquals(lower, decision.winner().orElseThrow());
	}

	@Test
	void overflowRequiresEveryRouteInTheHigherPriorityGroupToBeBusy() {
		RouteCandidate busy = candidate(1, 1, "busy", RouteCandidate.ProviderKind.PATTERN,
			10, RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
		RouteCandidate unavailable = candidate(1, 2, "offline", RouteCandidate.ProviderKind.AGENT,
			10, RouteCandidate.Availability.UNAVAILABLE, RouteCandidate.Blocker.NONE);
		RouteCandidate lower = available(2, 2, "lower", 3);

		assertTrue(new DefaultRoutePolicy().select(List.of(lower, unavailable)).winner().isEmpty());
		assertTrue(new DefaultRoutePolicy().select(List.of(lower, busy, unavailable)).winner().isEmpty());
	}

	@Test
	void structuredCursorUsesTheSameUuidOrderingAcrossTheSignBit() {
		RouteCandidate negativeSignedUuid = new RouteCandidate(new UUID(Long.MIN_VALUE, 0), id(1),
			"negative", RouteCandidate.ProviderKind.PATTERN, 7,
			RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		RouteCandidate positiveSignedUuid = new RouteCandidate(new UUID(0, 0), id(1),
			"positive", RouteCandidate.ProviderKind.PATTERN, 7,
			RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		DefaultRoutePolicy firstPolicy = new DefaultRoutePolicy();
		RouteCandidate first = firstPolicy.select(List.of(positiveSignedUuid, negativeSignedUuid))
			.winner().orElseThrow();
		DefaultRoutePolicy restored = new DefaultRoutePolicy(RouteCursorCodecs.load(RouteCursorCodecs.save(firstPolicy.cursorState())).orElseThrow());

		RouteCandidate second = restored.select(List.of(positiveSignedUuid, negativeSignedUuid))
			.winner().orElseThrow();

		assertEquals(negativeSignedUuid, first);
		assertEquals(positiveSignedUuid, second);
	}

	@Test
	void productionRouteFeedAggregatesPatternAgentAndGaugeBeforeSelection() {
		ProductionRouteTable table = new ProductionRouteTable();
		RouteSelectionScope scope = new RouteSelectionScope(id(89), id(90),
			new StackKey(new ItemStack(Items.STONE)), 3, 0, id(91));
		table.publish(new ProductionRouteTable.ProviderSource(scope, id(1), id(1), "pattern",
			RouteCandidate.ProviderKind.PATTERN, 3, RouteCandidate.Availability.AVAILABLE,
			RouteCandidate.Blocker.NONE));
		table.publish(new ProductionRouteTable.ProviderSource(scope, id(2), id(2), "agent",
			RouteCandidate.ProviderKind.AGENT, 5, RouteCandidate.Availability.AVAILABLE,
			RouteCandidate.Blocker.NONE));
		table.publish(new ProductionRouteTable.ProviderSource(scope, id(3), id(3), "gauge",
			RouteCandidate.ProviderKind.FACTORY_GAUGE, 9, RouteCandidate.Availability.AVAILABLE,
			RouteCandidate.Blocker.NONE));

		assertEquals(RouteCandidate.ProviderKind.FACTORY_GAUGE,
			table.select(scope).winner().orElseThrow().providerKind());
		assertEquals(3, table.snapshot(scope).size());
	}

	@Test
	void replacingAProviderKindOnlyTouchesTheExactScope() {
		ProductionRouteTable table = new ProductionRouteTable();
		RouteSelectionScope first = new RouteSelectionScope(id(89), id(92),
			new StackKey(new ItemStack(Items.STONE)), 3, 0, id(93));
		RouteSelectionScope second = new RouteSelectionScope(id(89), id(94),
			new StackKey(new ItemStack(Items.DIRT)), 3, 1, id(95));
		ProductionRouteTable.ProviderSource firstAgent = new ProductionRouteTable.ProviderSource(first,
			id(1), id(1), "first", RouteCandidate.ProviderKind.AGENT, 1,
			RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		ProductionRouteTable.ProviderSource secondAgent = new ProductionRouteTable.ProviderSource(second,
			id(2), id(2), "second", RouteCandidate.ProviderKind.AGENT, 1,
			RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
		table.publish(firstAgent); table.publish(secondAgent);

		table.replaceProviderKind(first, RouteCandidate.ProviderKind.AGENT, List.of());

		assertTrue(table.snapshot(first).isEmpty());
		assertEquals(List.of(secondAgent), table.snapshot(second));
	}

	@Test
	void stableIdentityBreaksTiesBeforeCursorRotation() {
		RouteCandidate laterLibrary = available(9, 1, "a", 4);
		RouteCandidate laterPattern = available(1, 9, "a", 4);
		RouteCandidate laterProvider = available(1, 1, "z", 4);
		RouteCandidate first = available(1, 1, "a", 4);

		assertEquals(first, new DefaultRoutePolicy().select(List.of(laterLibrary,
			laterProvider, laterPattern, first)).winner().orElseThrow());
	}

	@Test
	void hardFailuresBlockOverflowToLowerPriorities() {
		for (RouteCandidate.Blocker blocker : List.of(RouteCandidate.Blocker.AGENT_CONFLICT,
			RouteCandidate.Blocker.UNSUPPORTED_PATTERN,
			RouteCandidate.Blocker.MISSING_REQUIRED_AGENT,
			RouteCandidate.Blocker.UNREACHABLE_KNOWN_ROUTE)) {
			RouteCandidate blocked = candidate(1, blocker.ordinal() + 1, blocker.name(),
				RouteCandidate.ProviderKind.PATTERN, 10,
				RouteCandidate.Availability.UNAVAILABLE, blocker);
			DefaultRoutePolicy.Decision decision = new DefaultRoutePolicy().select(
				List.of(available(9, 9, "lower", 1), blocked));
			assertTrue(decision.winner().isEmpty(), blocker.name());
			assertEquals(blocker, decision.blocker(), blocker.name());
		}
	}

	@Test
	void availablePeerAtBlockedPriorityWinsWithoutOverflowing() {
		RouteCandidate available = available(1, 1, "live", 10);
		RouteCandidate conflict = candidate(1, 2, "conflict", RouteCandidate.ProviderKind.AGENT,
			10, RouteCandidate.Availability.UNAVAILABLE, RouteCandidate.Blocker.AGENT_CONFLICT);

		DefaultRoutePolicy.Decision decision = new DefaultRoutePolicy().select(List.of(
			available(9, 9, "lower", 1), conflict, available));

		assertEquals(available, decision.winner().orElseThrow());
		assertFalse(decision.overflowed());
	}

	@Test
	void orderingViewUsesPriorityAvailabilityThenStableIdentity() {
		RouteCandidate highBusy = candidate(2, 2, "busy", RouteCandidate.ProviderKind.AGENT,
			9, RouteCandidate.Availability.BUSY, RouteCandidate.Blocker.NONE);
		RouteCandidate highAvailable = available(3, 3, "available", 9);
		RouteCandidate lowAvailable = available(1, 1, "low", 2);

		assertEquals(List.of(highAvailable, highBusy, lowAvailable),
			DefaultRoutePolicy.ordered(List.of(lowAvailable, highBusy, highAvailable)));
	}

	private static RouteCandidate available(long library, long pattern, String provider, int priority) {
		return candidate(library, pattern, provider, RouteCandidate.ProviderKind.PATTERN,
			priority, RouteCandidate.Availability.AVAILABLE, RouteCandidate.Blocker.NONE);
	}

	private static RouteCandidate candidate(long library, long pattern, String provider,
		RouteCandidate.ProviderKind kind, int priority, RouteCandidate.Availability availability,
		RouteCandidate.Blocker blocker) {
		return new RouteCandidate(id(library), id(pattern), provider, kind, priority,
			availability, blocker);
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
