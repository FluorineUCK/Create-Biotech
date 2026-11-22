package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.PublicService.AGENT;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.PublicService.LIBRARY;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.PublicService.LOGISTICS_METADATA;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.PublicService.TASK_ENTRY;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.RouteDecision.FORWARDED;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.RouteDecision.CAPACITY;
import static com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio.SubnetBoundaryService.RouteDecision.LOOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class SubnetBoundaryServiceTest {
	private static final UUID NETWORK_A = id(1);
	private static final UUID NETWORK_B = id(2);
	private static final UUID NETWORK_C = id(3);
	private static final UUID CLUSTER_A = id(11);
	private static final UUID CLUSTER_B = id(12);
	private static final UUID CLUSTER_C = id(13);

	@Test
	void boundaryRejectsVisitedNetworkAndHidesInternalComputers() {
		SubnetBoundaryService boundary = boundary();
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);

		assertEquals(LOOP, boundary.forward(trace, NETWORK_A));
		assertTrue(boundary.project().computers().isEmpty());
		assertEquals(Set.of(AGENT, LIBRARY, TASK_ENTRY, LOGISTICS_METADATA),
			boundary.project().serviceKinds());
	}

	@Test
	void boundaryRejectsPreviouslyVisitedClusterBeforeForwarding() {
		SubnetBoundaryService boundary = boundary();
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);

		assertEquals(LOOP, boundary.forward(trace, NETWORK_C, CLUSTER_A));
		assertEquals(FORWARDED, boundary.forward(trace, NETWORK_C, CLUSTER_C));
		assertEquals(trace.visit(NETWORK_C, CLUSTER_C),
			boundary.extend(trace, NETWORK_C, CLUSTER_C).orElseThrow());
	}

	@Test
	void boundaryRejectsATraceThatDidNotReachItsOwnNetworkAndCluster() {
		SubnetBoundaryService boundary = boundary();
		SubnetRouteTrace unrelated = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A);

		assertEquals(LOOP, boundary.forward(unrelated, NETWORK_C, CLUSTER_C));
	}

	@Test
	void networkOnlyPreflightFailsClosedWhenEitherTraceIdentitySetIsFull() {
		Set<UUID> fullClusters = ids(1_000_000);
		fullClusters.remove(id(1_000_000));
		fullClusters.add(CLUSTER_B);
		SubnetRouteTrace clustersFull = new SubnetRouteTrace(Set.of(NETWORK_A, NETWORK_B),
			fullClusters);
		Set<UUID> fullNetworks = ids(2_000_000);
		fullNetworks.remove(id(2_000_000));
		fullNetworks.add(NETWORK_B);
		SubnetRouteTrace networksFull = new SubnetRouteTrace(fullNetworks,
			Set.of(CLUSTER_A, CLUSTER_B));

		assertEquals(CAPACITY, boundary().forward(clustersFull, NETWORK_C));
		assertEquals(CAPACITY, boundary().forward(networksFull, NETWORK_C));
	}

	@Test
	void traceDefensivelyCopiesAndBoundsBothVisitedIdentitySets() {
		Set<UUID> networks = ids(1_000);
		Set<UUID> clusters = ids(100_000);
		SubnetRouteTrace trace = new SubnetRouteTrace(networks, clusters);
		networks.clear();
		clusters.clear();

		assertEquals(FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES,
			trace.visitedNetworks().size());
		assertEquals(FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES,
			trace.visitedClusters().size());
		assertThrows(IllegalStateException.class,
			() -> trace.visit(id(999_001), id(999_002)));
		Set<UUID> tooManyNetworks = ids(200_000);
		tooManyNetworks.add(id(200_000 + FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES));
		assertThrows(IllegalArgumentException.class,
			() -> new SubnetRouteTrace(tooManyNetworks, Set.of(id(700_000))));
		Set<UUID> tooManyClusters = ids(300_000);
		tooManyClusters.add(id(300_000 + FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES));
		assertThrows(IllegalArgumentException.class,
			() -> new SubnetRouteTrace(Set.of(id(800_000)), tooManyClusters));
	}

	@Test
	void projectionIsOneExternalCardWithNoTopologyOrMutableCreateAddress() {
		SubnetBoundaryService.SubnetBoundaryProjection projection = boundary().project();

		assertEquals(1, projection.cards().size());
		assertEquals(CommunicationFrequency.of("external"),
			projection.cards().getFirst().listenerFrequency());
		assertEquals(List.of(), projection.computers());
	}

	@Test
	void routerRejectsAClusterReentryInsteadOfBypassingTheEnvelopeTrace() {
		RadioEndpoint source = endpoint(CLUSTER_A, 21, 0);
		RadioEndpoint relay = endpoint(CLUSTER_B, 22, 4);
		RadioEndpoint target = endpoint(CLUSTER_A, 23, 8);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(relay);
		router.register(target);
		RadioEnvelope envelope = RadioEnvelope.create(id(24), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"));

		assertThrows(IllegalArgumentException.class,
			() -> router.submit(envelope, List.of(relay.hop())));
	}

	@Test
	void envelopeCarriesAnExplicitInternalNetworkTraceAcrossHops() {
		RadioEndpoint source = endpoint(CLUSTER_A, 30, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 31, 4);
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_B, CLUSTER_A);

		RadioEnvelope envelope = RadioEnvelope.create(id(32), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), trace);

		assertEquals(trace, envelope.subnetTrace());
		assertEquals(trace, envelope.advanceHop().subnetTrace());
	}

	@Test
	void routerRejectsAClusterAlreadyPresentInTheEnvelopeTrace() {
		RadioEndpoint source = endpoint(CLUSTER_A, 40, 0);
		RadioEndpoint relay = endpoint(CLUSTER_B, 41, 4);
		RadioEndpoint target = endpoint(CLUSTER_C, 42, 8);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(relay);
		router.register(target);
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope envelope = RadioEnvelope.create(id(43), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), trace);

		assertThrows(IllegalArgumentException.class,
			() -> router.submit(envelope, List.of(relay.hop())));
	}

	@Test
	void boundaryForwardsAnEnvelopeOnlyWithAnExtendedImmutableTrace() {
		RadioEndpoint source = endpoint(CLUSTER_B, 50, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 51, 4);
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope envelope = RadioEnvelope.create(id(52), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), trace);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(target);
		assertTrue(router.registerBoundaryBinding(binding(source, target)));
		RadioEnvelope forwarded = router.forwardBoundary(envelope).orElseThrow();

		assertEquals(trace.visit(NETWORK_C, CLUSTER_C), forwarded.subnetTrace());
		assertEquals(forwarded.subnetTrace(), forwarded.advanceHop().subnetTrace());
	}

	@Test
	void boundaryRefusesANetworkReentryBeforeAnyRouterCanForward() {
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);

		assertEquals(LOOP, boundary().forward(trace, NETWORK_A, CLUSTER_C));
	}

	@Test
	void routerRejectsNewClustersWhenTheImmutableTraceIsFull() {
		RadioEndpoint source = endpoint(CLUSTER_A, 56, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 57, 4);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(target);
		Set<UUID> fullClusters = ids(400_000);
		fullClusters.remove(id(400_000));
		fullClusters.add(CLUSTER_A);
		SubnetRouteTrace full = new SubnetRouteTrace(ids(500_000), fullClusters);
		RadioEnvelope envelope = RadioEnvelope.create(id(58), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), full);

		assertThrows(IllegalArgumentException.class, () -> router.submit(envelope, List.of()));
	}

	@Test
	void routerAcceptsOnlyBoundaryProvenPrevisitedDestinationClusters() {
		RadioEndpoint source = endpoint(CLUSTER_B, 59, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 60, 4);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(target);
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope raw = RadioEnvelope.create(id(61), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), trace);
		assertTrue(router.registerBoundaryBinding(binding(source, target)));
		RadioEnvelope forwarded = router.forwardBoundary(raw).orElseThrow();
		RadioEnvelope forged = RadioEnvelope.create(id(62), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"),
			trace.visit(NETWORK_C, CLUSTER_C));

		assertDoesNotThrow(() -> router.submit(forwarded, List.of()));
		assertThrows(IllegalArgumentException.class, () -> router.submit(forged, List.of()));
	}

	@Test
	void routerFailsClosedUntilAnExactBoundaryBindingAuthorizesTheTransition() {
		RadioEndpoint source = endpoint(CLUSTER_B, 63, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 64, 4);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(target);
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope raw = RadioEnvelope.create(id(65), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), trace);

		assertTrue(router.forwardBoundary(raw).isEmpty());
		assertEquals(0, router.activeRouteCount());
		assertTrue(router.registerBoundaryBinding(binding(source, target)));
		assertTrue(router.registerBoundaryBinding(binding(source, target)));
		assertFalse(router.registerBoundaryBinding(new SubnetBoundaryBinding(CLUSTER_B, NETWORK_B,
			NETWORK_A, CLUSTER_A, CommunicationFrequency.of("external"), source.hop(),
			endpoint(CLUSTER_A, 99, 12).hop())));
		RadioEnvelope forwarded = router.forwardBoundary(raw).orElseThrow();

		assertDoesNotThrow(() -> router.submit(forwarded, List.of()));
		assertEquals(1, router.activeRouteCount());
	}

	@Test
	void routerRefusesBoundaryForwardingWhenFrequencyOrTargetDiffersFromItsBinding() {
		RadioEndpoint source = endpoint(CLUSTER_B, 66, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 67, 4);
		RadioRouter router = new RadioRouter(16, 48, 8);
		router.register(source);
		router.register(target);
		assertTrue(router.registerBoundaryBinding(binding(source, target)));
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope wrongFrequency = RadioEnvelope.create(id(68), source, target,
			CommunicationFrequency.of("other"), new ProcessProtocolMessage.Data("payload"), trace);
		RadioEndpoint otherTarget = endpoint(id(69), 70, 8);
		RadioEndpoint otherSource = endpoint(id(72), 73, 12);
		RadioEnvelope wrongTarget = RadioEnvelope.create(id(71), source, otherTarget,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"), trace);
		RadioEnvelope wrongSource = RadioEnvelope.create(id(74), otherSource, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("payload"),
			SubnetRouteTrace.start(NETWORK_A, otherSource.clusterId()));

		assertTrue(router.forwardBoundary(wrongFrequency).isEmpty());
		assertTrue(router.forwardBoundary(wrongTarget).isEmpty());
		assertTrue(router.forwardBoundary(wrongSource).isEmpty());
		assertEquals(0, router.activeRouteCount());
	}

	@Test
	void routerAuthorizesOnlyTheExactGatewayHopsAndRevokesOrReplacesThemSafely() {
		RadioEndpoint source = endpoint(CLUSTER_B, 80, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 81, 4);
		RadioEndpoint alternateSource = endpoint(CLUSTER_B, 82, 8);
		RadioEndpoint alternateTarget = endpoint(CLUSTER_C, 83, 12);
		RadioRouter router = new RadioRouter(16, 48, 8);
		for (RadioEndpoint endpoint : List.of(source, target, alternateSource, alternateTarget))
			assertTrue(router.register(endpoint));
		SubnetBoundaryBinding binding = binding(source, target);
		RadioEndpoint replacementSource = new RadioEndpoint(source.clusterId(), source.memberId(),
			source.address(), source.generation() + 1, source.frequency(), source.listenerCapacity(),
			source.capabilities());
		RadioEndpoint replacementTarget = new RadioEndpoint(target.clusterId(), target.memberId(),
			target.address(), target.generation() + 1, target.frequency(), target.listenerCapacity(),
			target.capabilities());
		SubnetBoundaryBinding replacement = binding(replacementSource, replacementTarget);
		SubnetBoundaryBinding stale = binding(alternateSource, target);
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope exact = RadioEnvelope.create(id(84), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("exact"), trace);
		RadioEnvelope alternate = RadioEnvelope.create(id(85), alternateSource, alternateTarget,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("alternate"), trace);

		assertTrue(router.registerBoundaryBinding(binding));
		RadioEnvelope forwarded = router.forwardBoundary(exact).orElseThrow();
		assertTrue(router.forwardBoundary(alternate).isEmpty(),
			"same Cluster and frequency must not substitute a different gateway hop");
		assertFalse(router.unregisterBoundaryBinding(replacement),
			"a different binding must not revoke the current authority");
		assertTrue(router.forwardBoundary(exact).isPresent(),
			"a failed mismatched revoke must leave the exact authority installed");
		assertTrue(router.unregisterBoundaryBinding(binding));
		assertTrue(router.unregisterBoundaryBinding(binding), "exact revoke is idempotent");
		assertEquals(RadioRouter.RouteStatus.QUEUED, router.submit(forwarded, List.of()));
		assertTrue(router.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			router.status(forwarded.messageId()));
		assertFalse(router.registerBoundaryBinding(binding),
			"a retired listener generation must not regain boundary authority");
		assertFalse(router.replaceBoundaryBinding(stale, replacement),
			"a stale expected binding must not replace current authority");
		assertTrue(router.register(replacementSource));
		assertTrue(router.register(replacementTarget));
		assertTrue(router.registerBoundaryBinding(replacement));
		assertTrue(router.registerBoundaryBinding(replacement), "replacement registration is idempotent");
		assertTrue(router.tick(2).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE,
			router.status(forwarded.messageId()),
			"a changed binding must not resume an envelope fenced to its predecessor");
		assertEquals(1, router.activeRouteCount());
	}

	@Test
	void routerFailsClosedWhenAnAuthorizedGatewayGenerationChanges() {
		RadioEndpoint source = endpoint(CLUSTER_B, 90, 0);
		RadioEndpoint target = endpoint(CLUSTER_C, 91, 4);
		RadioRouter router = new RadioRouter(16, 48, 8);
		assertTrue(router.register(source));
		assertTrue(router.register(target));
		SubnetBoundaryBinding binding = binding(source, target);
		assertTrue(router.registerBoundaryBinding(binding));
		SubnetRouteTrace trace = SubnetRouteTrace.start(NETWORK_A, CLUSTER_A)
			.visit(NETWORK_B, CLUSTER_B);
		RadioEnvelope raw = RadioEnvelope.create(id(92), source, target,
			CommunicationFrequency.of("external"), new ProcessProtocolMessage.Data("generation"), trace);
		RadioEnvelope forwarded = router.forwardBoundary(raw).orElseThrow();
		SubnetBoundaryBinding wrongGeneration = new SubnetBoundaryBinding(CLUSTER_B, NETWORK_B,
			NETWORK_C, CLUSTER_C, CommunicationFrequency.of("external"),
			new RadioEndpoint.Hop(source.key(), source.generation() + 1), target.hop());
		assertFalse(router.unregisterBoundaryBinding(wrongGeneration));
		assertTrue(router.forwardBoundary(raw).isPresent(),
			"a wrong-generation revoke must not remove the installed authority");
		RadioEndpoint replacementSource = new RadioEndpoint(source.clusterId(), source.memberId(),
			source.address(), source.generation() + 1, source.frequency(), source.listenerCapacity(),
			source.capabilities());
		assertFalse(router.register(replacementSource),
			"an active boundary may not expose one changed gateway generation without its exact binding");
		assertTrue(router.forwardBoundary(raw).isPresent(),
			"rejected partial authority replacement must retain the preceding exact gateway binding");
		assertEquals(RadioRouter.RouteStatus.QUEUED, router.submit(forwarded, List.of()));
		assertEquals(1, router.tick(1).deliveries().size());
		assertEquals(RadioRouter.RouteStatus.DELIVERED, router.status(forwarded.messageId()));
		assertEquals(1, router.activeRouteCount());
	}

	private static SubnetBoundaryService boundary() {
		return new SubnetBoundaryService(NETWORK_B, CLUSTER_B,
			CommunicationFrequency.of("external"), List.of(
				SubnetBoundaryService.PublicServiceSummary.of(AGENT),
				SubnetBoundaryService.PublicServiceSummary.of(LIBRARY),
				SubnetBoundaryService.PublicServiceSummary.of(TASK_ENTRY),
				SubnetBoundaryService.PublicServiceSummary.of(LOGISTICS_METADATA)));
	}

	private static SubnetBoundaryBinding binding(RadioEndpoint source, RadioEndpoint target) {
		return new SubnetBoundaryBinding(CLUSTER_B, NETWORK_B, NETWORK_C, CLUSTER_C,
			CommunicationFrequency.of("external"), source.hop(), target.hop());
	}

	private static RadioEndpoint endpoint(UUID clusterId, long memberId, int x) {
		return new RadioEndpoint(clusterId, id(memberId),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 64, 0)), 1,
			CommunicationFrequency.of("external"), 1, Set.of());
	}

	private static Set<UUID> ids(long start) {
		Set<UUID> values = new LinkedHashSet<>();
		for (int offset = 0; offset < FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES;
			offset++) values.add(id(start + offset));
		return values;
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
