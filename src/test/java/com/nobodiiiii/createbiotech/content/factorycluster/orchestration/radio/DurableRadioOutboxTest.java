package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class DurableRadioOutboxTest {
	@Test
	void deliveredButUnappliedEnvelopeRemainsRetryableInSessionAndAfterReload() {
		RadioEndpoint source = endpoint(1, id(901), 0, Set.of());
		RadioEndpoint target = endpoint(2, id(902), 1, Set.of());
		RadioEnvelope envelope = RadioEnvelope.create(id(903), source, target, CommunicationFrequency.of("7"),
			new ProcessProtocolMessage.Data("retry-business-application"));
		Store store = new Store();
		DurableRadioOutbox outbox = new DurableRadioOutbox(2, store);
		assertTrue(outbox.offer(envelope, List.of()));
		assertEquals(1, outbox.pump(router(source, target), 1).size());

		assertEquals(List.of(envelope), outbox.deliveredButUnapplied(2, Optional.empty()).stream()
			.map(DurableRadioOutbox.Entry::envelope).toList());
		DurableRadioOutbox reloaded = new DurableRadioOutbox(2, store);
		assertEquals(List.of(envelope), reloaded.deliveredButUnapplied(2, Optional.empty()).stream()
			.map(DurableRadioOutbox.Entry::envelope).toList());
		assertTrue(reloaded.markApplied(envelope.messageId()));
		assertTrue(reloaded.deliveredButUnapplied(2, Optional.empty()).isEmpty());
	}

	@Test
	void deliveredApplicationRetryBatchIsBoundedAndRoundRobin() {
		RadioEndpoint source = endpoint(1, id(910), 0, Set.of());
		RadioEndpoint target = endpoint(2, id(911), 1, Set.of());
		Store store = new Store();
		DurableRadioOutbox outbox = new DurableRadioOutbox(3, store);
		for (long value = 912; value <= 914; value++)
			assertTrue(outbox.offer(RadioEnvelope.create(id(value), source, target, CommunicationFrequency.of("7"),
				new ProcessProtocolMessage.Data("retry-" + value)), List.of()));
		assertEquals(3, outbox.pump(router(source, target), 1).size());

		UUID first = outbox.deliveredButUnapplied(1, Optional.empty()).getFirst()
			.envelope().messageId();
		UUID second = outbox.deliveredButUnapplied(1, Optional.of(first)).getFirst()
			.envelope().messageId();
		UUID third = outbox.deliveredButUnapplied(1, Optional.of(second)).getFirst()
			.envelope().messageId();
		assertEquals(3, Set.of(first, second, third).size(),
			"a blocked first recipient cannot starve later durable deliveries");
		assertEquals(first, outbox.deliveredButUnapplied(1, Optional.of(third)).getFirst()
			.envelope().messageId(), "the bounded cursor wraps deterministically");
	}

	@Test
	void retiredDeliveryFreesCapacityAndIsNotReplayedAfterRestart() {
		RadioEndpoint source = endpoint(1, id(21), 0, Set.of());
		RadioEndpoint target = endpoint(2, id(22), 1, Set.of());
		RadioEnvelope firstEnvelope = RadioEnvelope.create(id(23), source, target, CommunicationFrequency.of("7"),
			new ProcessProtocolMessage.Data("first"));
		RadioEnvelope secondEnvelope = RadioEnvelope.create(id(24), source, target, CommunicationFrequency.of("7"),
			new ProcessProtocolMessage.Data("second"));
		Store store = new Store();
		DurableRadioOutbox first = new DurableRadioOutbox(1, store);
		RadioRouter router = router(source, target);
		assertTrue(first.offer(firstEnvelope, List.of()));
		assertEquals(1, first.pump(router, 1).size());
		assertTrue(first.markApplied(firstEnvelope.messageId()));
		assertEquals(1, first.retireAppliedRoutes(router));
		assertEquals(0, router.activeRouteCount(),
			"applied outbox deliveries must not remain in the router's per-tick scan");
		assertTrue(first.entry(firstEnvelope.messageId()).isEmpty());
		assertTrue(first.offer(secondEnvelope, List.of()),
			"a completed message must not permanently consume bounded outbox capacity");

		DurableRadioOutbox restored = new DurableRadioOutbox(1, store);
		assertFalse(restored.entry(firstEnvelope.messageId()).isPresent());
	}

	@Test
	void currentFrameRouteLossKeepsTheOutboxWaitingWithoutReplayingThePriorRelay() {
		RadioEndpoint source = endpoint(1, id(1), 0, Set.of());
		RadioEndpoint relay = endpoint(2, id(2), 32, Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(3, id(3), 64, Set.of());
		RadioEnvelope envelope = RadioEnvelope.create(id(4), source, target,
			CommunicationFrequency.of("7"), new ProcessProtocolMessage.Data("moving-route"));
		DurableRadioOutbox outbox = new DurableRadioOutbox(2, new Store());
		RadioRouter router = router(source, relay, target);
		assertTrue(outbox.offer(envelope, List.of(relay.hop())));
		RadioTopologyFrame connected = new RadioTopologyFrame(40, true, Map.of(
			source.key(), currentSample(source, Vec3.atCenterOf(source.address().localPos())),
			relay.key(), currentSample(relay, Vec3.atCenterOf(relay.address().localPos())),
			target.key(), currentSample(target, Vec3.atCenterOf(target.address().localPos()))));

		assertTrue(outbox.pump(router, 40, connected).isEmpty());
		assertEquals(RadioRouter.RouteStatus.IN_TRANSIT,
			outbox.status(envelope.messageId()).orElseThrow());
		RadioTopologyFrame lost = new RadioTopologyFrame(60, true, Map.of(
			source.key(), currentSample(source, Vec3.atCenterOf(source.address().localPos())),
			relay.key(), currentSample(relay, Vec3.atCenterOf(relay.address().localPos())),
			target.key(), currentSample(target, new Vec3(96.5, .5, .5))));

		assertTrue(outbox.pump(router, 41, lost).isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			outbox.status(envelope.messageId()).orElseThrow());
		assertTrue(outbox.pump(router, 42, lost).isEmpty(),
			"a missing later hop must not replay the prior relay delivery");
		assertEquals(0, router.listenerOccupancy(relay.key()));
	}
	@Test
	void frozenRelayAndLeaseReplayAfterRestartWithoutApplyingFinalDeliveryTwice() {
		RadioEndpoint source = endpoint(1, id(1), 0, Set.of());
		RadioEndpoint relay = endpoint(2, id(2), 30, Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(3, id(3), 60, Set.of(ProductionCapability.ALCHEMIST));
		StackKey ingredient = new StackKey(new net.minecraft.world.item.ItemStack(
			net.minecraft.world.item.Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(11), id(12), 0, 0,
			id(14), 2, id(15), 0, InputMode.BULK, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(16), "input"), Map.of(ingredient, 1L))),
			Map.of(ingredient, 1L));
		RemoteProcessLease lease = new RemoteProcessLease(id(10), id(11), id(12),
			source.clusterId(), target.clusterId(), source.generation(), target.generation(),
			ProductionCapability.ALCHEMIST, request, 0);
		RadioEnvelope envelope = RadioEnvelope.create(id(13), source, target, CommunicationFrequency.of("7"),
			new ProcessProtocolMessage.LeaseOffer(lease));
		Store store = new Store();
		ProtocolStore protocolStore = new ProtocolStore();
		DurableRadioOutbox first = new DurableRadioOutbox(8, store);
		RadioRouter firstRouter = router(protocolStore, source, relay, target);
		assertTrue(first.offer(envelope, List.of(relay.hop())));

		assertTrue(first.pump(firstRouter, 1).isEmpty());
		assertEquals(RadioRouter.RouteStatus.IN_TRANSIT, first.status(envelope.messageId()).orElseThrow());
		assertEquals(1, firstRouter.listenerOccupancy(relay.key()));

		DurableRadioOutbox restored = new DurableRadioOutbox(8, store);
		RadioRouter restoredRouter = router(protocolStore, source, relay, target);
		assertTrue(restored.pump(restoredRouter, 2).isEmpty(),
			"restart replays the frozen first hop before the final recipient");
		List<RadioRouter.Delivery> delivered = restored.pump(restoredRouter, 3);
		assertEquals(1, delivered.size());
		assertTrue(restored.markApplied(envelope.messageId()));
		assertEquals(1, restoredRouter.appliedOfferCount(lease.leaseId()));

		DurableRadioOutbox replayed = new DurableRadioOutbox(8, store);
		RadioRouter replayRouter = router(protocolStore, source, relay, target);
		replayed.pump(replayRouter, 4);
		assertTrue(replayed.pump(replayRouter, 5).isEmpty(),
			"durable delivery acknowledgement suppresses duplicate world application");
		assertEquals(0, replayRouter.appliedOfferCount(lease.leaseId()),
			"retired transport entries are not a substitute for recipient protocol storage");
	}

	@Test
	void durableBoundaryAuthorityRestoresBeforeOutboxAndRetirementFencesOldGenerations() {
		RadioEndpoint source = endpoint(1, id(301), 0,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(2, id(302), 8,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		SubnetBoundaryBinding binding = new SubnetBoundaryBinding(source.clusterId(), id(303),
			id(304), target.clusterId(), source.frequency(), source.hop(), target.hop());
		AuthorityStore authority = new AuthorityStore();
		Store outboxStore = new Store();
		RadioRouter firstRouter = durableRouter(authority);
		assertTrue(firstRouter.register(source));
		assertTrue(firstRouter.register(target));
		assertTrue(firstRouter.registerBoundaryBinding(binding));
		RadioEnvelope forwarded = firstRouter.forwardBoundary(RadioEnvelope.create(id(305), source,
			target, source.frequency(), new ProcessProtocolMessage.Data("durable-boundary"),
			SubnetRouteTrace.start(binding.sourceNetworkId(), binding.sourceClusterId())))
			.orElseThrow();
		DurableRadioOutbox firstOutbox = new DurableRadioOutbox(2, outboxStore);
		assertTrue(firstOutbox.offer(forwarded, List.of()));

		RadioRouter restoredRouter = durableRouter(authority);
		DurableRadioOutbox restoredOutbox = new DurableRadioOutbox(2, outboxStore);
		assertTrue(restoredOutbox.pump(restoredRouter, 1).isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			restoredOutbox.status(forwarded.messageId()).orElseThrow());
		assertTrue(restoredRouter.register(source));
		assertTrue(restoredRouter.register(target));
		assertTrue(restoredRouter.registerBoundaryBinding(binding));
		assertEquals(1, restoredOutbox.pump(restoredRouter, 2).size());
		assertTrue(restoredOutbox.pump(restoredRouter, 3).isEmpty());

		assertTrue(restoredRouter.unregisterBoundaryBinding(binding));
		assertTrue(restoredRouter.unregisterExactEndpoint(source));
		assertTrue(restoredRouter.unregisterExactEndpoint(target));
		RadioRouter afterRetirement = durableRouter(authority);
		assertFalse(afterRetirement.register(source),
			"a process restart must not resurrect a retired endpoint generation");
		assertFalse(afterRetirement.register(target));
		assertFalse(afterRetirement.registerBoundaryBinding(binding),
			"the durable boundary tombstone must reject the retired listener authority");
		assertEquals(2, afterRetirement.nextEndpointGeneration(source.key()).orElseThrow());
	}

	@Test
	void runningReconfigurationRetiresPredecessorAuthorityBeforeFrozenSave() {
		RadioEndpoint source = endpoint(1, id(401), 0,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(2, id(402), 8,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		SubnetBoundaryBinding predecessor = new SubnetBoundaryBinding(source.clusterId(), id(403),
			id(404), target.clusterId(), source.frequency(), source.hop(), target.hop());
		AuthorityStore authority = new AuthorityStore();
		Store outboxStore = new Store();
		RadioRouter runningRouter = durableRouter(authority);
		assertTrue(runningRouter.register(source));
		assertTrue(runningRouter.register(target));
		assertTrue(runningRouter.registerBoundaryBinding(predecessor));
		RadioEnvelope predecessorEnvelope = runningRouter.forwardBoundary(RadioEnvelope.create(id(405),
			source, target, source.frequency(), new ProcessProtocolMessage.Data("retired-before-freeze"),
			SubnetRouteTrace.start(predecessor.sourceNetworkId(), predecessor.sourceClusterId())))
			.orElseThrow();
		DurableRadioOutbox runningOutbox = new DurableRadioOutbox(2, outboxStore);
		assertTrue(runningOutbox.offer(predecessorEnvelope, List.of()));

		assertTrue(runningRouter.unregisterBoundaryBinding(predecessor));
		assertTrue(runningRouter.unregisterExactEndpoint(source));
		assertTrue(runningRouter.unregisterExactEndpoint(target));
		RadioRouter frozenRestoredRouter = durableRouter(authority);
		DurableRadioOutbox frozenRestoredOutbox = new DurableRadioOutbox(2, outboxStore);
		assertFalse(frozenRestoredRouter.register(source));
		assertFalse(frozenRestoredRouter.register(target));
		assertFalse(frozenRestoredRouter.registerBoundaryBinding(predecessor));
		assertTrue(frozenRestoredOutbox.pump(frozenRestoredRouter, 1).isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			frozenRestoredOutbox.status(predecessorEnvelope.messageId()).orElseThrow(),
			"a predecessor retired while RUNNING must remain fenced across the frozen save");
	}

	private static RadioRouter router(RadioEndpoint... endpoints) {
		RadioRouter router = new RadioRouter(16, 48, 32);
		for (RadioEndpoint endpoint : endpoints) assertTrue(router.register(endpoint));
		return router;
	}
	private static RadioRouter router(ProtocolStore store, RadioEndpoint... endpoints) {
		RadioRouter router = new RadioRouter(16, 48, 32, store);
		for (RadioEndpoint endpoint : endpoints) assertTrue(router.register(endpoint));
		return router;
	}
	private static RadioRouter durableRouter(AuthorityStore authority) {
		return new RadioRouter(16, 48, 32, 32, new ProtocolStore(), state -> {}, authority);
	}
	private static RadioEndpoint endpoint(int member, UUID cluster, int x,
		Set<ProductionCapability> capabilities) {
		return new RadioEndpoint(cluster, id(member),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 0, 0)), 1, CommunicationFrequency.of("7"), 1,
			capabilities);
	}
	private static RadioTopologyFrame.Sample currentSample(RadioEndpoint endpoint, Vec3 center) {
		return new RadioTopologyFrame.Sample(endpoint.address().dimension(), endpoint.address(), center,
			true, endpoint.generation(), Optional.ofNullable(endpoint.address().subLevelId()));
	}
	private static UUID id(long value) { return new UUID(0, value); }

	private static final class Store implements DurableRadioOutbox.StateStore {
		private DurableRadioOutbox.State state;
		@Override public Optional<DurableRadioOutbox.State> load() { return Optional.ofNullable(state); }
		@Override public void persist(DurableRadioOutbox.State next) { state = next; }
	}

	private static final class ProtocolStore implements RadioRouter.AgentProtocolStateStore {
		private RadioRouter.AgentProtocolState state;
		@Override public Optional<RadioRouter.AgentProtocolState> load() {
			return Optional.ofNullable(state);
		}
		@Override public void persist(RadioRouter.AgentProtocolState next) { state = next; }
	}

	private static final class AuthorityStore implements RadioRouter.AuthorityStateStore {
		private RadioRouter.AuthorityState state;
		@Override public Optional<RadioRouter.AuthorityState> load() {
			return Optional.ofNullable(state);
		}
		@Override public boolean compareAndSet(RadioRouter.AuthorityState expected,
			RadioRouter.AuthorityState replacement) {
			RadioRouter.AuthorityState actual = state == null
				? new RadioRouter.AuthorityState(0, Map.of(), Map.of()) : state;
			if (!actual.equals(expected)) return false;
			state = replacement;
			return true;
		}
	}
}
