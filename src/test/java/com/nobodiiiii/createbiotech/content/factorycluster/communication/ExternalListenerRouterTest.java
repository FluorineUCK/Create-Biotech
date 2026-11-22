package com.nobodiiiii.createbiotech.content.factorycluster.communication;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionResourceProfile;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionProfession;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.ProcessProtocolMessage;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEndpoint;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioEnvelope;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.RadioRouter;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryBinding;
import com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetRouteTrace;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class ExternalListenerRouterTest {
	private static final UUID COMPUTER = id(1);
	private static final UUID CLUSTER_A = id(2);
	private static final UUID CLUSTER_B = id(3);
	private static final UUID NETWORK_A = id(4);
	private static final UUID NETWORK_B = id(5);
	private static final CommunicationFrequency EXTERNAL = CommunicationFrequency.of("external");

	@Test
	void activeEntryRegistersExactGatewayAndOnlyAuthorizedIngressCanForward() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		RadioEndpoint remote = endpoint(CLUSTER_B, 11, 9);
		Computer computer = new Computer();
		ExternalListenerService service = new ExternalListenerService(new OneSlotScheduler(), router,
			(host, frequency, reservation) -> new ExternalListenerService.Publication(
				endpoint(CLUSTER_A, 10, 0), remote, new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A,
					NETWORK_B, CLUSTER_B, frequency, endpoint(CLUSTER_A, 10, 0).hop(), remote.hop())),
			(host, frequency, authority) -> authority instanceof ExternalListenerService.IngressAuthority.LocalGoal);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		RadioEnvelope raw = RadioEnvelope.create(id(20), endpoint(CLUSTER_A, 10, 0), remote,
			EXTERNAL, new ProcessProtocolMessage.Data("ingress"),
			SubnetRouteTrace.start(NETWORK_A, CLUSTER_A));
		assertTrue(service.forwardIngress(computer, EXTERNAL, raw,
			new ExternalListenerService.IngressAuthority.ExistingLease(id(21), 1)).isEmpty());
		assertTrue(service.forwardIngress(computer, EXTERNAL, raw,
			new ExternalListenerService.IngressAuthority.LocalGoal(id(22))).isPresent());
	}

	@Test
	void removingOrStaleCleanupCannotRevokeAReplacementBinding() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		RadioEndpoint remote = endpoint(CLUSTER_B, 11, 9);
		RadioEndpoint source = endpoint(CLUSTER_A, 10, 0);
		Computer computer = new Computer();
		ExternalListenerService service = new ExternalListenerService(new OneSlotScheduler(), router,
			(host, frequency, reservation) -> new ExternalListenerService.Publication(
				source, remote, new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A,
					NETWORK_B, CLUSTER_B, frequency, source.hop(), remote.hop())),
			(host, frequency, authority) -> true);
		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		RadioEndpoint replacementSource = new RadioEndpoint(source.clusterId(), source.memberId(),
			source.address(), source.generation() + 1, source.frequency(), source.listenerCapacity(),
			source.capabilities());
		SubnetBoundaryBinding original = new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B,
			CLUSTER_B, EXTERNAL, source.hop(), remote.hop());
		SubnetBoundaryBinding replacement = new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B,
			CLUSTER_B, EXTERNAL, replacementSource.hop(), remote.hop());
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.of(new RadioRouter.ListenerAuthority(source, remote, original)),
			new RadioRouter.ListenerAuthority(replacementSource, remote, replacement)).orElseThrow()));

		service.unload(computer);
		RadioEnvelope raw = RadioEnvelope.create(id(23), replacementSource, remote,
			EXTERNAL, new ProcessProtocolMessage.Data("still-bound"),
			SubnetRouteTrace.start(NETWORK_A, CLUSTER_A));
		assertFalse(router.forwardBoundary(raw).isEmpty(),
			"only the exact stored binding may be revoked; a replacement survives stale cleanup");
	}

	@Test
	void staleCleanupLeavesTheExactPredecessorWhenItsAggregateRetirementIsStale() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		ExternalListenerService.Publication original = publicationForGeneration(1, EXTERNAL);
		Computer computer = new Computer();
		ExternalListenerService service = new ExternalListenerService(new OneSlotScheduler(), router,
			(host, frequency, reservation) -> original, (host, frequency, authority) -> true);
		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());

		RadioEndpoint successorSource = endpoint(CLUSTER_A, 20, 20, 2);
		RadioEndpoint successorRelay = endpoint(CLUSTER_B, 21, 21, 2);
		assertTrue(router.register(successorSource));
		assertTrue(router.register(successorRelay));
		SubnetBoundaryBinding successor = new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B,
			CLUSTER_B, EXTERNAL, successorSource.hop(), successorRelay.hop());
		assertTrue(router.replaceBoundaryBinding(original.boundaryBinding(), successor));

		service.unload(computer);
		assertEquals(original.endpoint(), router.registeredEndpoint(original.endpoint().key()).orElseThrow());
		assertEquals(original.relayEndpoint(), router.registeredEndpoint(original.relayEndpoint().key()).orElseThrow());
		RadioEnvelope envelope = RadioEnvelope.create(id(24), successorSource, successorRelay, EXTERNAL,
			new ProcessProtocolMessage.Data("successor"), SubnetRouteTrace.start(NETWORK_A, CLUSTER_A));
		assertTrue(router.forwardBoundary(envelope).isPresent(),
			"a stale aggregate retirement must not partially revoke the successor authority");
	}

	@Test
	void configurationRevisionReplacesOnlyItsExactGatewayBinding() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		RadioEndpoint remote = endpoint(CLUSTER_B, 11, 9);
		Computer computer = new Computer();
		AtomicLong generation = new AtomicLong(1);
		ExternalListenerService service = new ExternalListenerService(new OneSlotScheduler(), router,
			(host, frequency, reservation) -> {
				RadioEndpoint source = new RadioEndpoint(CLUSTER_A, id(10),
					new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)),
					generation.get(), frequency, 1,
					java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
				return new ExternalListenerService.Publication(source, remote,
					new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B, CLUSTER_B,
						frequency, source.hop(), remote.hop()));
			}, (host, frequency, authority) -> true);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		generation.incrementAndGet();
		service.refresh(computer);

		RadioEndpoint oldSource = new RadioEndpoint(CLUSTER_A, id(10),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)), 1, EXTERNAL, 1,
			java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint newSource = new RadioEndpoint(CLUSTER_A, id(10),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)), 2, EXTERNAL, 1,
			java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
		RadioEnvelope stale = RadioEnvelope.create(id(31), oldSource, remote, EXTERNAL,
			new ProcessProtocolMessage.Data("stale"), SubnetRouteTrace.start(NETWORK_A, CLUSTER_A));
		RadioEnvelope current = RadioEnvelope.create(id(32), newSource, remote, EXTERNAL,
			new ProcessProtocolMessage.Data("current"), SubnetRouteTrace.start(NETWORK_A, CLUSTER_A));
		assertTrue(service.forwardIngress(computer, EXTERNAL, stale,
			new ExternalListenerService.IngressAuthority.LocalGoal(id(33))).isEmpty());
		assertTrue(service.forwardIngress(computer, EXTERNAL, current,
			new ExternalListenerService.IngressAuthority.LocalGoal(id(34))).isPresent());

		service.unload(computer);
		assertTrue(router.forwardBoundary(current).isEmpty(),
			"unload revokes the exact current binding rather than a stale predecessor");
	}

	@Test
	void publicationRequiresAnExtendedRangeEndpoint() {
		RadioEndpoint ordinary = new RadioEndpoint(CLUSTER_A, id(10),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(0, 64, 0)), 1, EXTERNAL, 1,
			java.util.Set.of());
		RadioEndpoint remote = endpoint(CLUSTER_B, 11, 9);
		assertThrows(IllegalArgumentException.class, () -> new ExternalListenerService.Publication(
			ordinary, remote, new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B, CLUSTER_B,
				EXTERNAL, ordinary.hop(), remote.hop())));
	}

	@Test
	void staleOwnerUnloadCannotRevokeASuccessorPublication() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		Computer oldOwner = new Computer(id(100), 1);
		Computer successor = new Computer(id(101), 2);
		OneSlotScheduler scheduler = new OneSlotScheduler();
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> publicationForGeneration(host.publicationGeneration(),
				frequency), (host, frequency, authority) -> true);

		assertTrue(service.replace(oldOwner, 0, List.of(EXTERNAL)).applied());
		successor.configuration = oldOwner.configuration;
		service.reconcile(successor);
		service.unload(oldOwner);

		ExternalListenerService.Publication current = publicationForGeneration(2, EXTERNAL);
		RadioEnvelope envelope = RadioEnvelope.create(id(101), current.endpoint(),
			current.relayEndpoint(), EXTERNAL, new ProcessProtocolMessage.Data("successor"),
			SubnetRouteTrace.start(NETWORK_A, CLUSTER_A));
		assertTrue(router.forwardBoundary(envelope).isPresent());
		assertEquals(1, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
	}

	@Test
	void failedRefreshLeavesTheExactPredecessorWhenPublicationFails() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		Computer computer = new Computer();
		OneSlotScheduler scheduler = new OneSlotScheduler();
		java.util.concurrent.atomic.AtomicBoolean failRefresh = new java.util.concurrent.atomic.AtomicBoolean();
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> {
				if (!failRefresh.get()) return publicationForGeneration(1, frequency);
				throw new IllegalStateException("injected publication failure");
			}, (host, frequency, authority) -> true);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		failRefresh.set(true);
		service.refresh(computer);

		assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
			service.projection(COMPUTER).getFirst().state());
		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(publicationForGeneration(1, EXTERNAL).boundaryBinding(),
			router.boundaryBinding(CLUSTER_A).orElseThrow());
		assertEquals(endpoint(CLUSTER_A, 10, 0, 1), router.registeredEndpoint(
			endpoint(CLUSTER_A, 10, 0, 1).key()).orElseThrow());
	}

	@Test
	void staleBindingRefreshReleasesTheOldReservationWithoutRevokingSuccessorAuthority() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		OneSlotScheduler scheduler = new OneSlotScheduler();
		Computer computer = new Computer();
		ExternalListenerService.Publication original = publicationForGeneration(1, EXTERNAL);
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> original, (host, frequency, authority) -> true);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		RadioEndpoint successorSource = endpoint(CLUSTER_A, 10, 0, 2);
		SubnetBoundaryBinding successor = new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B,
			CLUSTER_B, EXTERNAL, successorSource.hop(), original.relayEndpoint().hop());
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.of(new RadioRouter.ListenerAuthority(original.endpoint(), original.relayEndpoint(),
				original.boundaryBinding())), new RadioRouter.ListenerAuthority(successorSource,
				original.relayEndpoint(), successor)).orElseThrow()));

		service.refresh(computer);

		assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
			service.projection(COMPUTER).getFirst().state());
		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(successor, router.boundaryBinding(CLUSTER_A).orElseThrow());
	}

	@Test
	void sameGenerationActivationCollisionDoesNotUnregisterForeignEndpoint() {
		RadioRouter router = new RadioRouter(8, 32, 8);
		OneSlotScheduler scheduler = new OneSlotScheduler();
		ExternalListenerService.Publication intended = publicationForGeneration(1, EXTERNAL);
		RadioEndpoint foreign = new RadioEndpoint(CLUSTER_A, id(10),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(99, 64, 0)), 1, EXTERNAL, 1,
			java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
		assertTrue(router.register(foreign));
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> intended, (host, frequency, authority) -> true);

		assertTrue(service.replace(new Computer(), 0, List.of(EXTERNAL)).applied());

		assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
			service.projection(COMPUTER).getFirst().state());
		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(foreign, router.registeredEndpoint(foreign.key()).orElseThrow());
	}

	@Test
	void rejectedRefreshKeepsItsExactPredecessorOwnedForRetryAndUnload() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = durableRouter(store);
		OneSlotScheduler scheduler = new OneSlotScheduler();
		Computer computer = new Computer();
		AtomicLong generation = new AtomicLong(1);
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> publicationForGeneration(generation.get(), frequency),
			(host, frequency, authority) -> true);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		ExternalListenerService.Publication predecessor = publicationForGeneration(1, EXTERNAL);
		generation.incrementAndGet();
		store.reject = true;
		service.refresh(computer);

		assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
			service.projection(COMPUTER).getFirst().state());
		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(predecessor.endpoint(), router.registeredEndpoint(predecessor.endpoint().key())
			.orElseThrow());
		assertEquals(predecessor.relayEndpoint(), router.registeredEndpoint(
			predecessor.relayEndpoint().key()).orElseThrow());

		store.reject = false;
		service.refresh(computer);
		ExternalListenerService.Publication replacement = publicationForGeneration(2, EXTERNAL);
		assertEquals(ExternalListenerService.ListenerState.ACTIVE,
			service.projection(COMPUTER).getFirst().state());
		assertEquals(1, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(replacement.endpoint(), router.registeredEndpoint(replacement.endpoint().key())
			.orElseThrow());
		assertEquals(replacement.relayEndpoint(), router.registeredEndpoint(
			replacement.relayEndpoint().key()).orElseThrow());

		service.unload(computer);
		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertTrue(router.registeredEndpoint(replacement.endpoint().key()).isEmpty());
		assertTrue(router.registeredEndpoint(replacement.relayEndpoint().key()).isEmpty());
		assertTrue(router.boundaryBinding(CLUSTER_A).isEmpty());
	}

	@Test
	void rejectedPendingPublicationRetiresItsExactPredecessorOnUnload() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = durableRouter(store);
		OneSlotScheduler scheduler = new OneSlotScheduler();
		Computer computer = new Computer();
		AtomicLong generation = new AtomicLong(1);
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> publicationForGeneration(generation.get(), frequency),
			(host, frequency, authority) -> true);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		ExternalListenerService.Publication predecessor = publicationForGeneration(1, EXTERNAL);
		generation.incrementAndGet();
		store.reject = true;
		service.refresh(computer);
		store.reject = false;

		service.unload(computer);

		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertTrue(router.registeredEndpoint(predecessor.endpoint().key()).isEmpty());
		assertTrue(router.registeredEndpoint(predecessor.relayEndpoint().key()).isEmpty());
		assertTrue(router.boundaryBinding(CLUSTER_A).isEmpty());
	}

	@Test
	void rejectedFrequencyRemovalKeepsExactRetirementUntilOneRetryCommits() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = durableRouter(store);
		OneSlotScheduler scheduler = new OneSlotScheduler();
		Computer computer = new Computer();
		ExternalListenerService.Publication predecessor = publicationForGeneration(1, EXTERNAL);
		ExternalListenerService service = new ExternalListenerService(scheduler, router,
			(host, frequency, reservation) -> predecessor,
			(host, frequency, authority) -> true);

		assertTrue(service.replace(computer, 0, List.of(EXTERNAL)).applied());
		service.drainCommittedTransitions(COMPUTER);
		store.reject = true;
		assertTrue(service.replace(computer, 1, List.of()).applied());

		assertEquals(0, scheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(List.of(ExternalListenerService.ListenerState.WAITING_ROUTE),
			service.projection(COMPUTER).stream()
				.map(ExternalListenerService.ListenerProjection::state).toList());
		assertEquals(predecessor.endpoint(), router.registeredEndpoint(predecessor.endpoint().key())
			.orElseThrow());
		assertEquals(predecessor.relayEndpoint(), router.registeredEndpoint(
			predecessor.relayEndpoint().key()).orElseThrow());
		assertEquals(predecessor.boundaryBinding(), router.boundaryBinding(CLUSTER_A).orElseThrow());
		assertTrue(service.drainCommittedTransitions(COMPUTER).isEmpty(),
			"a rejected aggregate tombstone cannot emit topology retirement");

		store.reject = false;
		service.reconcile(computer);
		RadioRouter.AuthorityState retired = router.authorityStateForTest();
		assertEquals(Optional.empty(), retired.endpoints().get(predecessor.endpoint().key()).current());
		assertEquals(Optional.empty(), retired.endpoints().get(predecessor.relayEndpoint().key()).current());
		assertFalse(retired.boundaries().get(CLUSTER_A).active());
		assertEquals(predecessor.boundaryBinding(), retired.boundaries().get(CLUSTER_A).binding());
		assertTrue(service.projection(COMPUTER).isEmpty());
		List<ExternalListenerService.ListenerCommit> committed =
			service.drainCommittedTransitions(COMPUTER);
		assertEquals(1, committed.size());
		assertEquals(Optional.of(predecessor), committed.getFirst().predecessor());
		assertEquals(Optional.empty(), committed.getFirst().replacement());
		service.reconcile(computer);
		assertTrue(service.drainCommittedTransitions(COMPUTER).isEmpty(),
			"the exact retirement transition must be emitted once");
	}

	@Test
	void throwingUnloadRetirementSurvivesFreshServiceAndCommitsOnce() {
		RecordingAuthorityStore authorityStore = new RecordingAuthorityStore();
		RecordingRetirementStore retirementStore = new RecordingRetirementStore();
		RadioRouter firstRouter = durableRouter(authorityStore);
		OneSlotScheduler firstScheduler = new OneSlotScheduler();
		Computer computer = new Computer();
		ExternalListenerService.Publication predecessor = publicationForGeneration(1, EXTERNAL);
		ExternalListenerService first = new ExternalListenerService(firstScheduler, firstRouter,
			(host, frequency, reservation) -> predecessor,
			(host, frequency, authority) -> true, retirementStore);
		assertTrue(first.replace(computer, 0, List.of(EXTERNAL)).applied());
		first.drainCommittedTransitions(COMPUTER);

		authorityStore.throwFailure = true;
		first.unload(computer);
		assertEquals(0, firstScheduler.occupancy(COMPUTER, ProcessKind.RADIO_LISTENER));
		assertEquals(List.of(ExternalListenerService.ListenerState.WAITING_ROUTE),
			first.projection(COMPUTER).stream()
				.map(ExternalListenerService.ListenerProjection::state).toList());
		assertEquals(1, retirementStore.state.retirements().size());
		assertEquals(predecessor, retirementStore.state.retirements().getFirst().publication());
		assertTrue(first.drainCommittedTransitions(COMPUTER).isEmpty());

		authorityStore.throwFailure = false;
		RadioRouter restoredRouter = durableRouter(authorityStore);
		ExternalListenerService restored = new ExternalListenerService(new OneSlotScheduler(),
			restoredRouter, (host, frequency, reservation) -> predecessor,
			(host, frequency, authority) -> true, retirementStore);
		restored.retryPendingRetirements();

		RadioRouter.AuthorityState retired = restoredRouter.authorityStateForTest();
		assertEquals(Optional.empty(), retired.endpoints().get(predecessor.endpoint().key()).current());
		assertEquals(Optional.empty(), retired.endpoints().get(predecessor.relayEndpoint().key()).current());
		assertFalse(retired.boundaries().get(CLUSTER_A).active());
		assertEquals(predecessor.boundaryBinding(), retired.boundaries().get(CLUSTER_A).binding());
		assertTrue(retirementStore.state.retirements().isEmpty());
		List<ExternalListenerService.ListenerCommit> committed =
			restored.drainCommittedTransitions(COMPUTER);
		assertEquals(1, committed.size());
		assertEquals(Optional.of(predecessor), committed.getFirst().predecessor());
		assertEquals(Optional.empty(), committed.getFirst().replacement());
		restored.retryPendingRetirements();
		assertTrue(restored.drainCommittedTransitions(COMPUTER).isEmpty());
	}

	@Test
	void freshOwnerCannotReplaceAStillPendingRecoveredRetirement() {
		RecordingAuthorityStore authorityStore = new RecordingAuthorityStore();
		RecordingRetirementStore retirementStore = new RecordingRetirementStore();
		Computer original = new Computer(id(1_100), 1);
		ExternalListenerService.Publication predecessor = publicationForGeneration(1, EXTERNAL);
		ExternalListenerService first = new ExternalListenerService(new OneSlotScheduler(),
			durableRouter(authorityStore), (host, frequency, reservation) -> predecessor,
			(host, frequency, authority) -> true, retirementStore);
		assertTrue(first.replace(original, 0, List.of(EXTERNAL)).applied());
		first.drainCommittedTransitions(COMPUTER);
		authorityStore.reject = true;
		first.unload(original);
		assertEquals(1, retirementStore.state.retirements().size());

		Computer successor = new Computer(id(1_101), 2);
		successor.configuration = original.configuration;
		authorityStore.reject = false;
		authorityStore.rejectNext = true;
		RadioRouter restoredRouter = durableRouter(authorityStore);
		ExternalListenerService restored = new ExternalListenerService(new OneSlotScheduler(),
			restoredRouter, (host, frequency, reservation) -> publicationForGeneration(
				host.publicationGeneration(), frequency), (host, frequency, authority) -> true,
			retirementStore);

		restored.reconcile(successor);
		assertEquals(ExternalListenerService.ListenerState.WAITING_ROUTE,
			restored.projection(COMPUTER).getFirst().state());
		assertEquals(Optional.of(predecessor.endpoint()), restoredRouter.authorityStateForTest()
			.endpoints().get(predecessor.endpoint().key()).current());
		assertEquals(1, retirementStore.state.retirements().size(),
			"a fresh candidate cannot bypass the exact recovered retirement after its retry fails");

		restored.reconcile(successor);
		ExternalListenerService.Publication replacement = publicationForGeneration(2, EXTERNAL);
		assertEquals(ExternalListenerService.ListenerState.ACTIVE,
			restored.projection(COMPUTER).getFirst().state());
		assertEquals(replacement.endpoint(), restoredRouter.registeredEndpoint(
			replacement.endpoint().key()).orElseThrow());
		assertTrue(retirementStore.state.retirements().isEmpty());
		List<ExternalListenerService.ListenerCommit> commits =
			restored.drainCommittedTransitions(COMPUTER);
		assertEquals(2, commits.size(), "retirement and successor activation each commit once");
		assertEquals(Optional.of(predecessor), commits.getFirst().predecessor());
		assertEquals(Optional.empty(), commits.getFirst().replacement());
		assertEquals(Optional.empty(), commits.getLast().predecessor());
		assertEquals(Optional.of(replacement), commits.getLast().replacement());
	}

	private static RadioRouter durableRouter(RecordingAuthorityStore store) {
		return new RadioRouter(8, 32, 8, 8, state -> {}, state -> {}, store);
	}

	private static ExternalListenerService.Publication publicationForGeneration(long generation,
		CommunicationFrequency frequency) {
		RadioEndpoint source = endpoint(CLUSTER_A, 10, 0, generation);
		RadioEndpoint relay = endpoint(CLUSTER_B, 11, 9, generation);
		return new ExternalListenerService.Publication(source, relay,
			new SubnetBoundaryBinding(CLUSTER_A, NETWORK_A, NETWORK_B, CLUSTER_B, frequency,
				source.hop(), relay.hop()));
	}

	private static RadioEndpoint endpoint(UUID cluster, long member, int x) {
		return endpoint(cluster, member, x, 1);
	}
	private static RadioEndpoint endpoint(UUID cluster, long member, int x, long generation) {
		return new RadioEndpoint(cluster, id(member), new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 64, 0)), generation, EXTERNAL, 1,
			java.util.Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.EXTENDED_RADIO));
	}
	private static UUID id(long value) { return new UUID(0, value); }

	private static final class Computer implements ExternalListenerService.ListenerComputer {
		private ExternalListenerConfiguration configuration = ExternalListenerConfiguration.empty();
		private final UUID ownerToken;
		private final long generation;
		private Computer() { this(ExternalListenerRouterTest.id(1_000), 0); }
		private Computer(UUID ownerToken, long generation) {
			this.ownerToken = ownerToken; this.generation = generation;
		}
		@Override public UUID id() { return COMPUTER; }
		@Override public ExternalListenerService.ListenerOwner listenerOwner() {
			return new ExternalListenerService.ListenerOwner(COMPUTER, ExternalListenerRouterTest.id(9), ownerToken);
		}
		@Override public long publicationGeneration() { return generation; }
		@Override public ProductionResourceProfile profile() {
			return ProductionResourceProfile.forProfession(ProductionProfession.WANDERING_TRADER, 1, 64).orElseThrow();
		}
		@Override public ExternalListenerConfiguration configuration() { return configuration; }
	@Override public boolean listenerPersistenceAuthorized() { return true; }
		@Override public boolean compareAndSet(ExternalListenerConfiguration expected,
			ExternalListenerConfiguration next) {
			if (!configuration.equals(expected)) return false;
			configuration = next;
			return true;
		}
	}

	private static final class OneSlotScheduler implements ExternalListenerService.Scheduler {
		private boolean held;
		@Override public Optional<ExternalListenerService.Reservation> reserve(UUID computerId,
			CommunicationFrequency frequency, int slotLimit) {
			if (held || slotLimit == 0) return Optional.empty();
			held = true;
			return Optional.of(new ExternalListenerService.Reservation(id(30), computerId, 0));
		}
		@Override public void release(ExternalListenerService.Reservation reservation) { held = false; }
		@Override public int occupancy(UUID computerId, ProcessKind kind) { return held ? 1 : 0; }
	}

	private static final class RecordingAuthorityStore implements RadioRouter.AuthorityStateStore {
		private RadioRouter.AuthorityState state = new RadioRouter.AuthorityState(0, java.util.Map.of(),
			java.util.Map.of());
		private boolean reject;
		private boolean rejectNext;
		private boolean throwFailure;
		@Override public Optional<RadioRouter.AuthorityState> load() { return Optional.of(state); }
		@Override public boolean compareAndSet(RadioRouter.AuthorityState expected,
			RadioRouter.AuthorityState replacement) {
			if (throwFailure) throw new IllegalStateException("injected authority persistence failure");
			if (rejectNext) { rejectNext = false; return false; }
			if (reject || !state.equals(expected)) return false;
			state = replacement;
			return true;
		}
	}

	private static final class RecordingRetirementStore
		implements ExternalListenerService.RetirementStore {
		private ExternalListenerService.RetirementState state =
			ExternalListenerService.RetirementState.empty();
		@Override public Optional<ExternalListenerService.RetirementState> load() {
			return Optional.of(state);
		}
		@Override public void persist(ExternalListenerService.RetirementState replacement) {
			state = replacement;
		}
	}
}
