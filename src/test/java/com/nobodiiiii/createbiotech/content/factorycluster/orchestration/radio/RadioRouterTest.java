package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey;

import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.factorycluster.CreateRouteKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.FactoryStateRepresentationBounds;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.ProductionRequestTicket;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentPhase;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteAgentRecord;
import com.nobodiiiii.createbiotech.content.factorycluster.agent.RemoteRequestKey;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.InputMode;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.ProcessKind;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.process.WaitReason;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class RadioRouterTest {
	private static final UUID SOURCE_CLUSTER = id(1);
	private static final UUID TARGET_CLUSTER = id(2);

	@BeforeAll
	static void bootstrapMinecraft() {
		bootstrapTestMinecraft();
	}

	private static void bootstrapTestMinecraft() {
		try {
			Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
			if (type.getMethod("get").invoke(null) == null)
				type.getMethod("of", List.class, List.class, List.class, List.class, Map.class)
					.invoke(null, List.of(), List.of(), List.of(), List.of(), Map.of());
			net.minecraft.SharedConstants.tryDetectVersion();
			var field = net.minecraft.server.Bootstrap.class.getDeclaredField("isBootstrapped");
			field.setAccessible(true);
			field.setBoolean(null, true);
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException(failure);
		}
	}

	@Test
 void diagnosticsReturnsOnlyBoundedExactEndpointRoutesWithoutAdvancingTraffic() {
  var source=endpoint(10,SOURCE_CLUSTER,0,CommunicationFrequency.of("17"),1,Set.of());
  var target=endpoint(11,TARGET_CLUSTER,8,CommunicationFrequency.of("17"),1,Set.of());var router=router(source,target);
  router.submit(envelope(99,source,target,CommunicationFrequency.of("17"),new ProcessProtocolMessage.Data("private payload")),List.of());
  var before=router.routeProgress(id(99));var rows=router.diagnosticRouteRows(source.key(),16);
  assertTrue(rows.stream().anyMatch(line->line.contains("message="+id(99))));
  assertTrue(rows.stream().anyMatch(line->line.contains("path=")));
  assertTrue(rows.stream().anyMatch(line->line.contains("visitedCluster=")));
  assertFalse(rows.stream().anyMatch(line->line.contains("private payload")));
  assertEquals(1,router.diagnosticRouteRows(source.key(),1).size());
  assertTrue(router.diagnosticRouteRows(new RadioEndpoint.Key(id(998),id(999)),16).isEmpty());
  assertEquals(before,router.routeProgress(id(99)));assertEquals(RadioRouter.RouteStatus.QUEUED,router.status(id(99)));
 }

	@Test
	void blankFrequencyCannotCreateCrossClusterRadioLink() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0,
			CommunicationFrequency.UNCONFIGURED, 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8,
			CommunicationFrequency.UNCONFIGURED, 1, Set.of());
		RadioRouter router = router(source, target);
		router.submit(envelope(99, source, target, CommunicationFrequency.UNCONFIGURED,
			new ProcessProtocolMessage.Data("blank-is-local-only")), List.of());

		assertTrue(router.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(99)));
	}

	@Test
	void clusterPrimaryProjectionChangesOnlyExactEndpointFrequency() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1,
			Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 40, CommunicationFrequency.of("17"), 1,
			Set.of());
		RadioRouter router = router(source, target);

		assertTrue(router.replaceClusterPrimaryFrequency(source.key(), source.generation(),
			CommunicationFrequency.of("18")));
		assertTrue(router.replaceClusterPrimaryFrequency(target.key(), target.generation(),
			CommunicationFrequency.of("18")));
		assertFalse(router.replaceClusterPrimaryFrequency(target.key(), target.generation() + 1,
			CommunicationFrequency.of("19")));
		router.submit(envelope(98, source, target, CommunicationFrequency.of("18"),
			new ProcessProtocolMessage.Data("frequency-only")), List.of());

		assertTrue(router.tick(1).deliveries().isEmpty(),
			"test resolver seam must not replace the endpoint address or capabilities");
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(98)));

		RadioEndpoint matchingSource = endpoint(12, SOURCE_CLUSTER, 0,
			CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint matchingTarget = endpoint(13, TARGET_CLUSTER, 8,
			CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter matchingRouter = router(matchingSource, matchingTarget);
		assertTrue(matchingRouter.replaceClusterPrimaryFrequency(matchingSource.key(),
			matchingSource.generation(), CommunicationFrequency.of("18")));
		assertTrue(matchingRouter.replaceClusterPrimaryFrequency(matchingTarget.key(),
			matchingTarget.generation(), CommunicationFrequency.of("18")));
		matchingRouter.submit(envelope(99, matchingSource, matchingTarget,
			CommunicationFrequency.of("18"), new ProcessProtocolMessage.Data("frequency-only")), List.of());
		assertTrue(matchingRouter.tick(1).deliveries().getFirst().finalRecipient(),
			"test resolver seam must update the exact endpoint frequency");

		RadioEndpoint inRangeSource = endpoint(14, SOURCE_CLUSTER, 0,
			CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint noListener = endpoint(15, TARGET_CLUSTER, 8,
			CommunicationFrequency.of("17"), 0, Set.of());
		RadioRouter listenerRouter = router(inRangeSource, noListener);
		assertTrue(listenerRouter.replaceClusterPrimaryFrequency(inRangeSource.key(),
			inRangeSource.generation(), CommunicationFrequency.of("18")));
		assertTrue(listenerRouter.replaceClusterPrimaryFrequency(noListener.key(),
			noListener.generation(), CommunicationFrequency.of("18")));
		listenerRouter.submit(envelope(100, inRangeSource, noListener,
			CommunicationFrequency.of("18"), new ProcessProtocolMessage.Data("capacity")), List.of());
		assertTrue(listenerRouter.tick(1).deliveries().isEmpty(),
			"test resolver seam must not replace listener capacity");
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, listenerRouter.status(id(100)));
	}

	@Test
	void durableClusterFrequencyUpdateRebuildsOnlyTheExactEndpointFrequency() {
		RadioEndpoint source = endpoint(18, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"),
			1, Set.of());
		RadioRouter router = router(source);

		assertTrue(router.replaceClusterPrimaryFrequency(source.key(), source.generation(),
			CommunicationFrequency.of("18")));
		assertFalse(router.replaceClusterPrimaryFrequency(source.key(), source.generation() + 1,
			CommunicationFrequency.of("19")));
	}

	@Test
	void endpointGenerationAllocationAndAuthorityRevisionAdvanceOnlyForRealChanges() {
		RadioEndpoint source = endpoint(19, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"),
			1, Set.of(), 0);
		RadioRouter router = new RadioRouter(16, 48, 2);

		assertEquals(0, router.authorityRevision());
		assertEquals(0, router.nextEndpointGeneration(source.key()).orElseThrow());
		assertTrue(router.register(source));
		assertEquals(1, router.authorityRevision());
		assertEquals(1, router.nextEndpointGeneration(source.key()).orElseThrow());
		assertTrue(router.register(source), "the current exact endpoint is idempotent");
		assertEquals(1, router.authorityRevision());
		assertTrue(router.replaceClusterPrimaryFrequency(source.key(), source.generation(),
			CommunicationFrequency.of("17")), "an unchanged frequency is idempotent");
		assertEquals(1, router.authorityRevision());
		assertTrue(router.replaceClusterPrimaryFrequency(source.key(), source.generation(),
			CommunicationFrequency.of("18")));
		assertEquals(2, router.authorityRevision());
		assertFalse(router.replaceClusterPrimaryFrequency(source.key(), source.generation() + 1,
			CommunicationFrequency.of("19")));
		assertEquals(2, router.authorityRevision());
		assertTrue(router.unregister(source.key(), source.generation()));
		assertEquals(3, router.authorityRevision());
		assertFalse(router.unregister(source.key(), source.generation()));
		assertEquals(3, router.authorityRevision());
	}

	@Test
	void sameClusterDeliveryIsVirtualAndUsesNoRadioListener() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("11"), 0, Set.of());
		RadioEndpoint target = endpoint(11, SOURCE_CLUSTER, 10_000, CommunicationFrequency.of("99"), 0, Set.of());
		RadioRouter router = router(source, target);

		router.submit(envelope(100, source, target, CommunicationFrequency.of("11"), new ProcessProtocolMessage.Data("local")), List.of());
		RadioRouter.Delivery delivery = router.tick(1).deliveries().getFirst();

		assertTrue(delivery.finalRecipient());
		assertTrue(delivery.virtualDelivery());
		assertTrue(delivery.listenerProcess().isEmpty());
		assertEquals(RadioRouter.RouteStatus.DELIVERED, router.status(id(100)));
	}

	@Test
	void normalRangeCrossClusterDeliveryChargesARemoteListener() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 12, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, target);

		router.submit(envelope(101, source, target, CommunicationFrequency.of("17"), new ProcessProtocolMessage.Data("remote")), List.of());
		RadioRouter.Delivery delivery = router.tick(1).deliveries().getFirst();

		assertEquals(ProcessKind.REMOTE_LISTENER, delivery.listenerProcess().orElseThrow());
		assertFalse(delivery.virtualDelivery());
		assertEquals(0, router.listenerOccupancy(target.key()), "the final hop acknowledgement releases the listener");
	}

	@Test
	void extendedRangeNeedsAnExtendedRadioCapability() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint ordinary = endpoint(11, TARGET_CLUSTER, 40, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter ordinaryRouter = router(source, ordinary);
		ordinaryRouter.submit(envelope(102, source, ordinary, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("too-far")), List.of());

		assertTrue(ordinaryRouter.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, ordinaryRouter.status(id(102)));

		RadioEndpoint trader = endpoint(11, TARGET_CLUSTER, 40, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioRouter extendedRouter = router(source, trader);
		extendedRouter.submit(envelope(103, source, trader, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("extended")), List.of());
		assertTrue(extendedRouter.tick(1).deliveries().getFirst().finalRecipient());
	}

	@Test
	void frequencyMismatchNeverFallsThroughToAnotherMember() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint exactTarget = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("18"), 1, Set.of());
		RadioEndpoint temptingAlternative = endpoint(12, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, exactTarget, temptingAlternative);
		router.submit(envelope(104, source, exactTarget, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("exact-only")), List.of());

		assertTrue(router.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(104)));
		assertEquals(0, router.listenerOccupancy(temptingAlternative.key()));
	}

	@Test
	void absentExactRecipientAndAbsentListenerBothWaitWithoutSubstitution() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint absent = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint noListener = endpoint(12, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 0, Set.of());
		RadioRouter router = router(source, noListener);
		router.submit(envelope(105, source, absent, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("missing")), List.of());
		router.submit(envelope(106, source, noListener, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("busy")), List.of());

		assertTrue(router.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(105)));
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(106)));
	}

	@Test
	void explicitRelayAdvancesOneHopPerTickAndRetainsItsListenerUntilAcknowledged() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint relay = endpoint(20, id(3), 35, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 70, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, relay, target);
		router.submit(envelope(107, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("relayed")), List.of(relay.hop()));

		RadioRouter.Delivery firstHop = router.tick(10).deliveries().getFirst();
		assertEquals(relay.key(), firstHop.recipient());
		assertEquals(1, firstHop.envelope().hopSequence());
		assertFalse(firstHop.finalRecipient());
		assertEquals(1, router.listenerOccupancy(relay.key()));
		assertTrue(router.tick(10).deliveries().isEmpty(), "a second call in one server tick cannot advance again");

		RadioRouter.Delivery secondHop = router.tick(11).deliveries().getFirst();
		assertEquals(target.key(), secondHop.recipient());
		assertEquals(2, secondHop.envelope().hopSequence());
		assertTrue(secondHop.finalRecipient());
		assertEquals(0, router.listenerOccupancy(relay.key()));
	}

	@Test
	void relayReleasesItsListenerWhenTheFrozenNextHopBecomesUnavailable() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint relay = endpoint(20, id(3), 35, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 70, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, relay, target);
		router.submit(envelope(108, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("lost")), List.of(relay.hop()));
		router.tick(1);
		router.unregister(target.key(), target.generation());

		assertTrue(router.tick(2).deliveries().isEmpty());
		assertEquals(0, router.listenerOccupancy(relay.key()));
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(108)));
	}

	@Test
	void currentFrameHopLossWaitsWithoutReplayingOrAuthorizingMaterial() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1,
			Set.of());
		RadioEndpoint relay = endpoint(20, id(3), 32, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 64, CommunicationFrequency.of("17"),
			1, Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, relay, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		RadioEnvelope offer = envelope(109, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease));
		router.submit(offer, List.of(relay.hop()));
		RadioTopologyFrame connected = new RadioTopologyFrame(40, true, Map.of(
			source.key(), currentSample(source, Vec3.atCenterOf(source.address().localPos())),
			relay.key(), currentSample(relay, Vec3.atCenterOf(relay.address().localPos())),
			receiver.key(), currentSample(receiver, Vec3.atCenterOf(receiver.address().localPos()))));

		assertEquals(relay.key(), router.tick(40, connected).deliveries().getFirst().recipient());
		assertEquals(1, router.listenerOccupancy(relay.key()));
		RadioTopologyFrame movedOutOfRange = new RadioTopologyFrame(60, true, Map.of(
			source.key(), currentSample(source, Vec3.atCenterOf(source.address().localPos())),
			relay.key(), currentSample(relay, Vec3.atCenterOf(relay.address().localPos())),
			receiver.key(), currentSample(receiver, new Vec3(96.5, 64.5, .5))));

		assertTrue(router.tick(41, movedOutOfRange).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(offer.messageId()));
		assertEquals(new RadioRouter.RouteProgress(offer.advanceHop(), 2, Optional.empty()),
			router.routeProgress(offer.messageId()));
		assertEquals(0, router.listenerOccupancy(relay.key()));
		assertEquals(0, router.appliedOfferCount(lease.leaseId()));
		assertTrue(router.issueMaterialIntentPermit(receiver.key(), lease.leaseId(), 4).isEmpty());
	}
	@Test
	void staleEndpointGenerationCannotReceiveAnEnvelope() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint generationFour = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of(), 4);
		RadioEndpoint generationThree = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of(), 3);
		RadioRouter router = router(source, generationFour);
		router.submit(envelope(109, source, generationThree, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("stale")), List.of());

		assertTrue(router.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(109)));
	}

	@Test
	void activeLeaseRouteCountCanBeScopedToOneLease() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1,
			Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"),
			1, Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease exact = lease(source, receiver);
		RemoteProcessLease foreign = foreignLease(source, receiver);
		RadioEnvelope exactOffer = envelope(108, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(exact));
		RadioEnvelope foreignOffer = envelope(109, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(foreign));

		router.submit(exactOffer, List.of());
		router.submit(foreignOffer, List.of());
		assertEquals(2, router.tick(1).deliveries().size());
		assertEquals(2, router.activeLeaseRouteCount());
		assertEquals(1, router.activeLeaseRouteCount(exact.leaseId()));
		assertEquals(1, router.activeLeaseRouteCount(foreign.leaseId()));

		assertTrue(router.retireDeliveredRoute(exactOffer.messageId()));
		assertEquals(0, router.activeLeaseRouteCount(exact.leaseId()));
		assertEquals(1, router.activeLeaseRouteCount(foreign.leaseId()));
		assertEquals(1, router.activeLeaseRouteCount());
	}

	@Test
	void remoteLeaseOffersAndResultsAreIdempotentAndKeepSourceGoalOwnership() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);

		router.submit(envelope(110, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		assertFalse(router.tick(1).deliveries().getFirst().duplicateProtocolMessage());
		router.submit(envelope(111, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		assertTrue(router.tick(2).deliveries().getFirst().duplicateProtocolMessage());

		router.submit(envelope(112, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))),
			List.of());
		router.tick(3);
		router.submit(envelope(113, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(4);
		ProcessProtocolMessage.LeaseResult result =
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease));
		router.submit(envelope(114, receiver, source, CommunicationFrequency.of("17"), result), List.of());
		assertFalse(router.tick(5).deliveries().getFirst().duplicateProtocolMessage());
		router.submit(envelope(115, receiver, source, CommunicationFrequency.of("17"), result), List.of());
		assertTrue(router.tick(6).deliveries().getFirst().duplicateProtocolMessage());

		assertEquals(1, router.appliedOfferCount(lease.leaseId()));
		assertEquals(1, router.appliedResultCount(lease.leaseId()));
		assertEquals(SOURCE_CLUSTER, router.goalOwner(lease.leaseId()).orElseThrow());
	}

	@Test
	void leaseRecipientEvidenceAndConsumedMaterialFenceSurviveRouterRestart() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RecordingLeaseProtocolStore store = new RecordingLeaseProtocolStore();
		RadioRouter first = new RadioRouter(16, 48, 64, 64,
			new RecordingAgentProtocolStore(), store);
		first.register(source);
		first.register(receiver);
		RemoteProcessLease lease = lease(source, receiver);
		deliverAcknowledgedRevision(first, source, receiver, lease, 210);
		first.submit(envelope(213, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))), List.of());
		assertFalse(first.tick(4).deliveries().getFirst().duplicateProtocolMessage());
		RadioRouter.MaterialIntentPermit permit = first.issueMaterialIntentPermit(
			receiver.key(), lease.leaseId(), 4).orElseThrow();
		assertTrue(first.consumeMaterialIntentPermit(permit));

		RadioRouter restored = new RadioRouter(16, 48, 64, 64,
			new RecordingAgentProtocolStore(), store);
		restored.register(source);
		restored.register(receiver);
		assertEquals(SOURCE_CLUSTER, restored.goalOwner(lease.leaseId()).orElseThrow());
		assertEquals(result(lease), restored.result(lease.leaseId(), 4).orElseThrow());
		assertFalse(restored.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4),
			"a consumed material fence cannot resurrect after outbox retirement and restart");
		restored.submit(envelope(214, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 3, result(lease))), List.of());
		assertTrue(restored.tick(1).deliveries().getFirst().duplicateProtocolMessage());
		assertEquals(1, restored.appliedResultCount(lease.leaseId()));
		restored.submit(envelope(215, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))), List.of());
		assertTrue(restored.tick(2).deliveries().getFirst().duplicateProtocolMessage());
	}

	@Test
	void serialExactCompletedLeasesRetireToOneDurableLineageAndCannotResurrect() {
		RadioEndpoint source = endpoint(20, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioEndpoint receiver = endpoint(21, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RecordingLeaseProtocolStore store = new RecordingLeaseProtocolStore();
		RadioRouter router = new RadioRouter(16, 48, 4, 4,
			new RecordingAgentProtocolStore(), store);
		router.register(source);
		router.register(receiver);
		RemoteProcessLease firstLease = null;
		RadioEnvelope firstOffer = null;
		long tick = 1;

		for (int run = 0; run < 257; run++) {
			RemoteProcessLease lease = serialExactLease(source, receiver, run);
			RemoteExpansionRevision revision = revision(lease);
			RemoteExpansionResult result = result(lease);
			long firstMessage = 10_000L + run * 4L;
			RadioEnvelope offer = envelope(firstMessage, source, receiver, CommunicationFrequency.of("17"),
				new ProcessProtocolMessage.LeaseOffer(lease));
			if (run == 0) { firstLease = lease; firstOffer = offer; }
			router.submit(offer, List.of());
			router.tick(tick++);
			router.submit(envelope(firstMessage + 1, receiver, source, CommunicationFrequency.of("17"),
				new ProcessProtocolMessage.LeaseRevision(lease.leaseId(),
					lease.revisionFence(), revision)), List.of());
			router.tick(tick++);
			router.submit(envelope(firstMessage + 2, source, receiver, CommunicationFrequency.of("17"),
				new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(),
					lease.revisionFence())), List.of());
			router.tick(tick++);
			RadioRouter.MaterialIntentPermit permit = router.issueMaterialIntentPermit(
				receiver.key(), lease.leaseId(), lease.revisionFence()).orElseThrow();
			assertTrue(router.consumeMaterialIntentPermit(permit));
			assertTrue(router.issueMaterialIntentPermit(receiver.key(), lease.leaseId(),
				lease.revisionFence()).isEmpty(), "one Run cannot double-authorize material");
			router.submit(envelope(firstMessage + 3, receiver, source, CommunicationFrequency.of("17"),
				new ProcessProtocolMessage.LeaseResult(lease.leaseId(),
					lease.revisionFence(), result)), List.of());
			router.tick(tick++);

			assertTrue(router.retireCompletedLease(lease, result),
				"completed material-authorized lease must compact durably");
			for (long message = firstMessage; message < firstMessage + 4; message++)
				assertTrue(router.retireDeliveredRoute(id(message)));
			assertEquals(0, router.activeLeaseCount());
			assertEquals(1, router.retiredLeaseLineageCount());
			assertEquals(0, router.activeRouteCount(),
				"tick must not scan already-applied routes forever");

			if (run == 128) {
				router = new RadioRouter(16, 48, 4, 4,
					new RecordingAgentProtocolStore(), store);
				router.register(source);
				router.register(receiver);
				tick = 1;
			}
		}

		RadioRouter restored = new RadioRouter(16, 48, 4, 4,
			new RecordingAgentProtocolStore(), store);
		restored.register(source);
		restored.register(receiver);
		assertEquals(0, restored.activeLeaseCount());
		assertEquals(1, restored.retiredLeaseLineageCount(),
			"257 serial Runs must persist one configured-capacity tombstone lineage");
		RadioEnvelope staleOffer = firstOffer;
		assertThrows(IllegalArgumentException.class,
			() -> restored.submit(staleOffer, List.of()),
			"a late OFFER must not resurrect retired material authority");
		assertTrue(restored.goalOwner(firstLease.leaseId()).isEmpty());
	}

	@Test
	void cancelledLeaseRetiresWithoutAResultRevokesMaterialAndAllowsOnlyANewerFence() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RecordingLeaseProtocolStore store = new RecordingLeaseProtocolStore();
		RadioRouter router = new RadioRouter(16, 48, 64, 64,
			new RecordingAgentProtocolStore(), store);
		router.register(source); router.register(receiver);
		RemoteProcessLease cancelled = serialExactLease(source, receiver, 0);
		RadioEnvelope cancelledOffer = envelope(198, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(cancelled));
		router.submit(cancelledOffer, List.of());
		assertEquals(1, router.tick(1).deliveries().size());

		RadioRouter.TerminalLeaseRetirement retirement = router
			.prepareTerminalLeaseRetirement(cancelled,
				RadioRouter.LeaseTerminalOutcome.CANCELLED).orElseThrow();
		store.persist(retirement.state());
		router.completePersistedLeaseRetirement(retirement);
		assertEquals(0, router.activeLeaseCount());
		assertEquals(1, router.retiredLeaseLineageCount());
		assertTrue(router.issueMaterialIntentPermit(receiver.key(), cancelled.leaseId(),
			cancelled.revisionFence()).isEmpty());

		RadioRouter reloaded = new RadioRouter(16, 48, 64, 64,
			new RecordingAgentProtocolStore(), store);
		reloaded.register(source); reloaded.register(receiver);
		assertEquals(0, reloaded.activeLeaseCount());
		assertEquals(1, reloaded.retiredLeaseLineageCount());
		assertThrows(IllegalArgumentException.class,
			() -> reloaded.submit(cancelledOffer, List.of()),
			"a cancelled pre-result offer cannot resurrect after reload");

		RemoteProcessLease migrated = serialExactLease(source, receiver, 1);
		reloaded.submit(envelope(199, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(migrated)), List.of());
		assertEquals(1, reloaded.tick(1).deliveries().size());
		assertEquals(1, reloaded.activeLeaseCount());
		assertEquals(0, reloaded.retiredLeaseLineageCount(),
			"the newer fenced generation replaces the same logical-child tombstone");
	}

	@Test
	void newerUnappliedOfferCanBeTerminallyRetiredOverThePriorLineageTombstone() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RecordingLeaseProtocolStore store = new RecordingLeaseProtocolStore();
		RadioRouter router = new RadioRouter(16, 48, 64, 64,
			new RecordingAgentProtocolStore(), store);
		RemoteProcessLease cancelled = serialExactLease(source, receiver, 0);
		RadioRouter.TerminalLeaseRetirement first = router
			.prepareTerminalLeaseRetirement(cancelled,
				RadioRouter.LeaseTerminalOutcome.CANCELLED).orElseThrow();
		store.persist(first.state());
		router.completePersistedLeaseRetirement(first);

		RemoteProcessLease migratedBeforeOfferDelivery = serialExactLease(source, receiver, 1);
		RadioRouter.TerminalLeaseRetirement replacement = router
			.prepareTerminalLeaseRetirement(migratedBeforeOfferDelivery,
				RadioRouter.LeaseTerminalOutcome.MIGRATED).orElseThrow();
		assertEquals(migratedBeforeOfferDelivery.leaseId(), replacement.leaseId());
		RadioRouter.RetiredLeaseState retired = replacement.state().retiredLineages()
			.values().iterator().next();
		assertEquals(migratedBeforeOfferDelivery.revisionFence(), retired.revisionFence());
		assertEquals(RadioRouter.LeaseTerminalOutcome.MIGRATED, retired.outcome());
	}

	@Test
	void protocolEvidenceCapacityCannotExceedTheDurableCollectionRepresentation() {
		assertDoesNotThrow(() -> new RadioRouter(16, 48, 16_384, 16_384,
			new RecordingAgentProtocolStore(), new RecordingLeaseProtocolStore()));
		assertThrows(IllegalArgumentException.class,
			() -> new RadioRouter(16, 48, 16_385, 16_385,
				new RecordingAgentProtocolStore(), new RecordingLeaseProtocolStore()));
	}

	@Test
	void endpointGenerationFenceCapacityCannotExceedTheDurableCollectionRepresentation() {
		int maximum = FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES;
		assertDoesNotThrow(() -> new RadioRouter(16, 48, maximum, 1,
			state -> {}, state -> {}, new RadioRouter.AuthorityStateStore() {}));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter(16, 48,
			maximum + 1, 1, state -> {}, state -> {}, new RadioRouter.AuthorityStateStore() {}));
	}

	@Test
	void leaseRouterRejectsAResultUntilTheExactReceiverRevisionWasAcknowledged() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(214, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(215, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))),
			List.of());
		router.tick(2);
		router.submit(envelope(216, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))), List.of());

		assertThrows(IllegalArgumentException.class, () -> router.tick(3));
		assertEquals(0, router.appliedResultCount(lease.leaseId()));
	}

	@Test
	void leaseRouterRejectsRevisionEvidenceForAnotherExpansionRequest() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		RemoteProcessLease foreign = foreignLease(source, receiver);
		router.submit(envelope(217, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(218, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(foreign))),
			List.of());

		assertThrows(IllegalArgumentException.class, () -> router.tick(2));
	}

	@Test
	void malformedLeaseEvidenceCannotBeRestoredAsCurrentProtocolState() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RemoteProcessLease lease = lease(source, receiver);
		RemoteProcessLease foreign = foreignLease(source, receiver);
		RecordingLeaseProtocolStore store = new RecordingLeaseProtocolStore();
		store.state = new RadioRouter.LeaseProtocolState(Map.of(lease.leaseId(),
			new RadioRouter.DurableLeaseState(lease, source.hop(), receiver.hop(),
				Map.of(4L, revision(foreign)), Set.of(4L), Map.of(4L, result(foreign)),
				Set.of(), Set.of(), 1)), Map.of());

		assertThrows(IllegalArgumentException.class, () -> new RadioRouter(16, 48, 64, 64,
			new RecordingAgentProtocolStore(), store));
	}

	@Test
	void receiverCannotMaterializeUntilTheSourceAcknowledgesTheExactRevision() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(114, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(115, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.tick(2);

		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
		router.submit(envelope(116, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(3);

		assertTrue(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 3));
		assertFalse(router.canCommitMaterialIntent(source.key(), lease.leaseId(), 4));
	}

	@Test
	void materialAuthorizationExpiresWhenTheExactReceiverGenerationChanges() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(120, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(121, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.tick(2);
		router.submit(envelope(122, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(3);
		assertTrue(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));

		router.register(endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST), 2));
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
	}

	@Test
	void materialAuthorizationExpiresWhenTheExactSourceGenerationChanges() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(132, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(133, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.tick(2);
		router.submit(envelope(134, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(3);
		assertTrue(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));

		router.unregister(source.key(), source.generation());
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
		router.register(endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of(), 2));
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
	}

	@Test
	void materialPermitIsOneShotAndRevisionCannotBeAuthorizedTwice() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(136, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(137, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.tick(2);
		router.submit(envelope(138, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(3);

		RadioRouter.MaterialIntentPermit permit = router.issueMaterialIntentPermit(
			receiver.key(), lease.leaseId(), 4).orElseThrow();
		assertTrue(router.consumeMaterialIntentPermit(permit));
		assertFalse(router.consumeMaterialIntentPermit(permit));
		assertTrue(router.issueMaterialIntentPermit(receiver.key(), lease.leaseId(), 4).isEmpty());
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
	}

	@Test
	void materialAuthorizationCanBePreparedWithoutBurningBeforeTheOwnerBatch() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		deliverAcknowledgedRevision(router, source, receiver, lease, 330);

		RadioRouter.PreparedMaterialAuthorization prepared = router
			.prepareMaterialIntentAuthorization(receiver.key(), lease.leaseId(), 4)
			.orElseThrow();
		assertTrue(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4),
			"preflight must not mutate or persist the live router");
		assertFalse(router.materialIntentAuthorized(receiver.key(), lease.leaseId(), 4));

		router.installPersistedLeaseProtocolState(prepared.state());
		assertTrue(router.materialIntentAuthorized(receiver.key(), lease.leaseId(), 4));
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
		assertTrue(router.prepareTerminalLeaseRetirement(lease,
			RadioRouter.LeaseTerminalOutcome.MIGRATED).isEmpty(),
			"a durable material handoff cannot be cancelled as pre-material work");
	}

	@Test
	void issuedMaterialPermitIsRevokedByEndpointGenerationChangeBeforeConsumption() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(139, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(140, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.tick(2);
		router.submit(envelope(141, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(3);
		RadioRouter.MaterialIntentPermit permit = router.issueMaterialIntentPermit(
			receiver.key(), lease.leaseId(), 4).orElseThrow();

		assertTrue(router.register(endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST), 2)));
		assertFalse(router.consumeMaterialIntentPermit(permit));
		assertFalse(router.consumeMaterialIntentPermit(permit), "a failed permit is burned too");
	}

	@Test
	void unregisteredEndpointsCannotResurrectSameGenerationMaterialAuthority() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter sourceRouter = router(source, receiver);
		RemoteProcessLease sourceLease = lease(source, receiver);
		deliverAcknowledgedRevision(sourceRouter, source, receiver, sourceLease, 142);
		RadioRouter.MaterialIntentPermit sourcePermit = sourceRouter.issueMaterialIntentPermit(
			receiver.key(), sourceLease.leaseId(), 4).orElseThrow();

		assertTrue(sourceRouter.unregister(source.key(), source.generation()));
		assertFalse(sourceRouter.register(source), "same-generation source resurrection is stale");
		assertFalse(sourceRouter.canCommitMaterialIntent(receiver.key(), sourceLease.leaseId(), 4));
		assertFalse(sourceRouter.consumeMaterialIntentPermit(sourcePermit));

		RadioRouter receiverRouter = router(source, receiver);
		RemoteProcessLease receiverLease = lease(source, receiver);
		deliverAcknowledgedRevision(receiverRouter, source, receiver, receiverLease, 145);
		RadioRouter.MaterialIntentPermit receiverPermit = receiverRouter.issueMaterialIntentPermit(
			receiver.key(), receiverLease.leaseId(), 4).orElseThrow();

		assertTrue(receiverRouter.unregister(receiver.key(), receiver.generation()));
		assertFalse(receiverRouter.register(receiver),
			"same-generation receiver resurrection is stale");
		assertFalse(receiverRouter.canCommitMaterialIntent(receiver.key(), receiverLease.leaseId(), 4));
		assertFalse(receiverRouter.consumeMaterialIntentPermit(receiverPermit));
	}

	@Test
	void onlyAStrictlyHigherEndpointGenerationCanWorkAfterUnregister() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, target);
		assertTrue(router.unregister(target.key(), target.generation()));
		assertFalse(router.register(target));

		RadioEndpoint nextTarget = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of(), 2);
		assertTrue(router.register(nextTarget));
		router.submit(envelope(148, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("stale generation")), List.of());
		router.submit(envelope(149, source, nextTarget, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("new generation")), List.of());

		RadioRouter.TickResult result = router.tick(1);
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(148)));
		assertEquals(RadioRouter.RouteStatus.DELIVERED, router.status(id(149)));
		assertEquals(1, result.deliveries().size());
		assertEquals(id(149), result.deliveries().getFirst().envelope().messageId());

		RadioRouter sourceRouter = router(source, target);
		assertTrue(sourceRouter.unregister(source.key(), source.generation()));
		assertFalse(sourceRouter.register(source));
		RadioEndpoint nextSource = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of(), 2);
		assertTrue(sourceRouter.register(nextSource));
		sourceRouter.submit(envelope(150, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("stale source generation")), List.of());
		sourceRouter.submit(envelope(151, nextSource, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("new source generation")), List.of());

		RadioRouter.TickResult sourceResult = sourceRouter.tick(1);
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, sourceRouter.status(id(150)));
		assertEquals(RadioRouter.RouteStatus.DELIVERED, sourceRouter.status(id(151)));
		assertEquals(1, sourceResult.deliveries().size());
		assertEquals(id(151), sourceResult.deliveries().getFirst().envelope().messageId());
	}

	@Test
	void endpointGenerationFenceSaturationBackpressuresWithoutLosingEvidence() {
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter(16, 48, 0));
		RadioRouter router = new RadioRouter(16, 48, 1);
		RadioEndpoint first = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint unknown = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		assertTrue(router.register(first));
		assertTrue(router.unregister(first.key(), first.generation()));

		assertFalse(router.register(unknown), "a full fence registry must backpressure new keys");
		assertFalse(router.register(first), "saturation must not erase the old generation fence");
		RadioEndpoint nextFirst = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of(), 2);
		assertTrue(router.register(nextFirst), "known keys may advance while the registry is full");
		assertTrue(router.register(nextFirst), "the exact current endpoint remains idempotent");
		assertTrue(router.unregister(nextFirst.key(), nextFirst.generation()));
		assertFalse(router.register(first), "the advanced fence must survive another unregister");
		assertFalse(router.register(unknown));
	}

	@Test
	void leaseOfferWaitsWhenTheExactReceiverLacksItsRequiredCapability() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint incapable = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, incapable);
		RemoteProcessLease lease = lease(source, incapable);
		router.submit(envelope(123, source, incapable, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());

		assertTrue(router.tick(1).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(123)));
		assertEquals(WaitReason.ROUTE, router.leaseWaitReason(lease.leaseId()).orElseThrow());
	}

	@Test
	void anotherMemberInTheReceiverClusterCannotSubmitTheLeaseResponse() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioEndpoint intruder = endpoint(12, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver, intruder);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(124, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);

		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(125, intruder, source, CommunicationFrequency.of("17"),
				new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))),
			List.of()));
		assertEquals(0, router.appliedResultCount(lease.leaseId()));
	}

	@Test
	void successfulLeaseTrafficDoesNotClearAnotherMessagesRouteWait() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioEndpoint missingRelay = endpoint(20, id(3), 4, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(126, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);

		router.submit(envelope(127, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))),
			List.of(missingRelay.hop()));
		router.submit(envelope(128, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))),
			List.of());
		router.tick(2);

		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(127)));
		assertEquals(RadioRouter.RouteStatus.DELIVERED, router.status(id(128)));
		assertEquals(WaitReason.ROUTE, router.leaseWaitReason(lease.leaseId()).orElseThrow());
	}

	@Test
	void leaseRouteWaitEvidenceBackpressuresAtItsConfiguredDurableCapacity() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RecordingLeaseProtocolStore store = new RecordingLeaseProtocolStore();
		RadioRouter router = new RadioRouter(16, 48, 64, 1,
			new RecordingAgentProtocolStore(), store);
		router.register(source);
		router.register(receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(180, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.unregister(source.key(), source.generation());

		router.submit(envelope(181, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.submit(envelope(182, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))), List.of());
		router.tick(2);

		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(id(181)));
		assertEquals(RadioRouter.RouteStatus.WAITING_PROTOCOL_CAPACITY, router.status(id(182)));
		assertEquals(Set.of(id(181)), store.state.leases().get(lease.leaseId())
			.waitingRouteMessages());
		assertEquals(SOURCE_CLUSTER, new RadioRouter(16, 48, 64, 1,
			new RecordingAgentProtocolStore(), store).goalOwner(lease.leaseId()).orElseThrow());
	}

	@Test
	void endpointPropertiesCannotChangeWithoutAdvancingGeneration() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, target);
		RadioEndpoint rewritten = endpoint(11, TARGET_CLUSTER, 80, CommunicationFrequency.of("18"), 0,
			Set.of(ProductionCapability.EXTENDED_RADIO));

		assertFalse(router.register(rewritten));
		router.submit(envelope(129, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("frozen")), List.of());
		assertTrue(router.tick(1).deliveries().getFirst().finalRecipient());
	}

	@Test
	void aMessageIdCollisionCannotChangeTheOriginalEnvelopeOrFrozenPath() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint relay = endpoint(20, id(3), 4, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, relay, target);
		RadioEnvelope original = envelope(135, source, target, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.Data("original"));
		router.submit(original, List.of());
		assertEquals(RadioRouter.RouteStatus.QUEUED, router.submit(original, List.of()));

		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(135, source, target, CommunicationFrequency.of("17"), new ProcessProtocolMessage.Data("collision")),
			List.of()));
		assertThrows(IllegalArgumentException.class,
			() -> router.submit(original, List.of(relay.hop())));
	}

	@Test
	void receiverCannotRespondBeforeItsLeaseOfferIsActuallyDelivered() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioEndpoint missingRelay = endpoint(20, id(3), 4, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(130, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of(missingRelay.hop()));
		router.tick(1);
		assertEquals(0, router.appliedOfferCount(lease.leaseId()));

		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(131, receiver, source, CommunicationFrequency.of("17"),
				new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))),
			List.of()));
		assertFalse(router.canCommitMaterialIntent(receiver.key(), lease.leaseId(), 4));
	}

	@Test
	void routeLossLeavesTheLeaseWaitingAtItsSourceOwner() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, receiver);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(117, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.unregister(source.key(), source.generation());
		router.submit(envelope(118, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseResult(lease.leaseId(), 4, result(lease))), List.of());

		assertTrue(router.tick(2).deliveries().isEmpty());
		assertEquals(WaitReason.ROUTE, router.leaseWaitReason(lease.leaseId()).orElseThrow());
		assertEquals(SOURCE_CLUSTER, router.goalOwner(lease.leaseId()).orElseThrow());
	}

	@Test
	void aMissingRelayHopResumesWithoutReplayingThePriorDelivery() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint relay = endpoint(20, id(3), 35, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint receiver = endpoint(11, TARGET_CLUSTER, 70, CommunicationFrequency.of("17"), 1,
			Set.of(ProductionCapability.ALCHEMIST));
		RadioRouter router = router(source, relay);
		RemoteProcessLease lease = lease(source, receiver);
		router.submit(envelope(119, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of(relay.hop()));
		router.tick(1);
		router.tick(2);
		assertEquals(WaitReason.ROUTE, router.leaseWaitReason(lease.leaseId()).orElseThrow());

		router.register(receiver);
		assertEquals(receiver.key(), router.tick(3).deliveries().getFirst().recipient(),
			"the missing next hop resumes without replaying the already delivered relay hop");
		assertTrue(router.leaseWaitReason(lease.leaseId()).isEmpty());
	}

	@Test
	void agentProtocolBindsEveryDirectionToTheExactOfferPeers() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint sourceIntruder = endpoint(12, SOURCE_CLUSTER, 4, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint targetIntruder = endpoint(13, TARGET_CLUSTER, 10, CommunicationFrequency.of("17"), 1, Set.of());
		RadioRouter router = router(source, sourceIntruder, target, targetIntruder);
		RemoteRequestKey key = agentKey();
		ProductionRequestTicket ticket = agentTicket();
		router.submit(envelope(160, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.Offer(key, ticket)), List.of());
		router.tick(1);

		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(161, sourceIntruder, target, CommunicationFrequency.of("17"),
				new AgentProtocolMessage.Cancel(key)), List.of()));
		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(162, sourceIntruder, target, CommunicationFrequency.of("17"),
				new AgentProtocolMessage.EvidenceAck(key, 1)), List.of()));
		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(163, targetIntruder, source, CommunicationFrequency.of("17"),
				new AgentProtocolMessage.Status(key,
					AgentProtocolMessage.StatusCode.REMOTE_RECOVERY, Optional.empty())), List.of()));
		assertThrows(IllegalArgumentException.class, () -> router.submit(
			envelope(164, targetIntruder, source, CommunicationFrequency.of("17"),
				new AgentProtocolMessage.Terminal(key, terminalRecord(
					RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT))), List.of()));
	}

	@Test
	void duplicateAndConflictingAgentTerminalEvidenceSurvivesRouterRestart() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RecordingAgentProtocolStore store = new RecordingAgentProtocolStore();
		RadioRouter first = new RadioRouter(16, 48, 64, store);
		first.register(source);
		first.register(target);
		RemoteRequestKey key = agentKey();
		first.submit(envelope(165, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.Offer(key, agentTicket())), List.of());
		first.tick(1);
		AgentProtocolMessage.Terminal terminal = new AgentProtocolMessage.Terminal(key,
			terminalRecord(RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT));
		first.submit(envelope(166, target, source, CommunicationFrequency.of("17"), terminal), List.of());
		assertFalse(first.tick(2).deliveries().getFirst().duplicateProtocolMessage());

		RadioRouter restored = new RadioRouter(16, 48, 64, store);
		restored.register(source);
		restored.register(target);
		restored.submit(envelope(167, target, source, CommunicationFrequency.of("17"), terminal), List.of());
		assertTrue(restored.tick(1).deliveries().getFirst().duplicateProtocolMessage());
		AgentProtocolMessage.Terminal conflicting = new AgentProtocolMessage.Terminal(key,
			terminalRecord(RemoteAgentRecord.TerminalOutcome.RETRIED_PRE_INTENT));
		restored.submit(envelope(168, target, source, CommunicationFrequency.of("17"), conflicting), List.of());
		assertThrows(IllegalStateException.class, () -> restored.tick(2));
	}

	@Test
	void acknowledgedAgentPeersStillBackpressureAtConfiguredCorrectnessCapacity() {
		RadioEndpoint source = endpoint(10, SOURCE_CLUSTER, 0, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint target = endpoint(11, TARGET_CLUSTER, 8, CommunicationFrequency.of("17"), 1, Set.of());
		RadioEndpoint relay = endpoint(12, id(998), 4, CommunicationFrequency.of("17"), 1, Set.of());
		RecordingAgentProtocolStore store = new RecordingAgentProtocolStore();
		RadioRouter router = new RadioRouter(16, 48, 64, 1, store);
		router.register(source);
		router.register(target);
		router.register(relay);
		RemoteRequestKey first = agentKey();
		router.submit(envelope(170, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.Offer(first, agentTicket())), List.of());
		router.tick(1);
		router.submit(envelope(171, target, source, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.Status(first, AgentProtocolMessage.StatusCode.PRESENT,
				Optional.of(queuedRecord()))), List.of());
		router.tick(2);
		AgentProtocolMessage.Terminal terminal = new AgentProtocolMessage.Terminal(first,
			terminalRecord(RemoteAgentRecord.TerminalOutcome.CANCELLED_PRE_INTENT));
		router.submit(envelope(172, target, source, CommunicationFrequency.of("17"), terminal), List.of());
		assertEquals(1, router.tick(3).deliveries().size(),
			"terminal high-watermark must replace older status at correctness capacity");
		router.submit(envelope(173, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.StatusQuery(first)), List.of());
		assertEquals(1, router.tick(4).deliveries().size(),
			"reconciliation traffic must remain available at correctness capacity");
		router.submit(envelope(174, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.EvidenceAck(first, terminal.record().revision())), List.of());
		router.tick(5);

		RemoteRequestKey second = agentKey(310);
		UUID waitingMessage = id(175);
		router.submit(envelope(175, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.Offer(second, agentTicket(310))), List.of(relay.hop()));
		RadioRouter.RouteProgress before = router.routeProgress(waitingMessage);
		Map<RadioEndpoint.Key, Integer> occupancyBefore = router.listenerOccupancySnapshot();
		assertTrue(router.tick(6).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_PROTOCOL_CAPACITY,
			router.status(waitingMessage));
		assertEquals(before, router.routeProgress(waitingMessage));
		assertEquals(occupancyBefore, router.listenerOccupancySnapshot());
		assertTrue(router.tick(7).deliveries().isEmpty());
		assertEquals(before, router.routeProgress(waitingMessage),
			"repeated capacity retries cannot advance the frozen route");
		assertEquals(occupancyBefore, router.listenerOccupancySnapshot(),
			"repeated capacity retries cannot leak listener occupancy");
		assertEquals(1, store.state.peers().size());
		RadioRouter.AgentProtocolCompactionEvidenceIssuer issuer =
			new RadioRouter.AgentProtocolCompactionEvidenceIssuer(() -> true, request -> Optional.of(
				new RadioRouter.ResolvedAgentProtocolCompactionFacts(request.key(), id(999), true,
					2, 2)));
		assertTrue(router.compactAgentProtocolPeer(issuer.capture(first).orElseThrow()));
		assertEquals(1, router.tick(8).deliveries().size());
		assertEquals(RadioRouter.RouteStatus.IN_TRANSIT, router.status(waitingMessage));
		assertEquals(1, router.tick(9).deliveries().size());
		assertEquals(RadioRouter.RouteStatus.DELIVERED, router.status(waitingMessage));
		RadioRouter restored = new RadioRouter(16, 48, 64, 1, store);
		restored.register(source);
		restored.register(target);
		restored.submit(envelope(176, source, target, CommunicationFrequency.of("17"),
			new AgentProtocolMessage.StatusQuery(second)), List.of());
		assertEquals(1, restored.tick(1).deliveries().size(),
			"restored exact-peer reconciliation remains available at capacity");
	}

	@Test
	void listenerActivationUsesOneAuthorityCas() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = authorityRouter(8, store);
		RadioRouter.ListenerAuthority authority = listenerAuthority(0);

		RadioRouter.ListenerAuthorityTransaction transaction = router.prepareListenerPublication(
			Optional.empty(), authority).orElseThrow();

		assertTrue(router.compareAndSetListenerAuthority(transaction));
		assertEquals(1, store.casCalls);
		assertEquals(Optional.of(authority.sourceEndpoint()), router.registeredEndpoint(
			authority.sourceEndpoint().key()));
		assertEquals(Optional.of(authority.relayEndpoint()), router.registeredEndpoint(
			authority.relayEndpoint().key()));
		assertEquals(Optional.of(authority.boundaryBinding()), router.boundaryBinding(
			authority.boundaryBinding().sourceClusterId()));
		assertEquals(1, router.authorityRevision());
	}

	@Test
	void listenerReplacementUsesOneAuthorityCas() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = authorityRouter(8, store);
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.empty(), current).orElseThrow()));
		store.casCalls = 0;
		RadioRouter.ListenerAuthority replacement = listenerAuthority(1);

		RadioRouter.ListenerAuthorityTransaction transaction = router.prepareListenerPublication(
			Optional.of(current), replacement).orElseThrow();

		assertTrue(router.compareAndSetListenerAuthority(transaction));
		assertEquals(1, store.casCalls);
		assertEquals(Optional.of(replacement.sourceEndpoint()), router.registeredEndpoint(
			replacement.sourceEndpoint().key()));
		assertEquals(Optional.of(replacement.relayEndpoint()), router.registeredEndpoint(
			replacement.relayEndpoint().key()));
		assertEquals(Optional.of(replacement.boundaryBinding()), router.boundaryBinding(
			replacement.boundaryBinding().sourceClusterId()));
		assertEquals(2, router.authorityRevision());
	}

	@Test
	void listenerRetirementUsesOneAuthorityCas() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = authorityRouter(8, store);
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.empty(), current).orElseThrow()));
		store.casCalls = 0;

		RadioRouter.ListenerAuthorityTransaction transaction = router.prepareListenerRetirement(current)
			.orElseThrow();

		assertTrue(router.compareAndSetListenerAuthority(transaction));
		assertEquals(1, store.casCalls);
		assertEquals(Optional.empty(), router.registeredEndpoint(current.sourceEndpoint().key()));
		assertEquals(Optional.empty(), router.registeredEndpoint(current.relayEndpoint().key()));
		assertEquals(Optional.empty(), router.boundaryBinding(current.boundaryBinding().sourceClusterId()));
		assertEquals(2, router.authorityRevision());
	}

	@Test
	void rejectedOrThrowingListenerCasLeavesExactPredecessor() {
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		RadioRouter router = authorityRouter(8, store);
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.empty(), current).orElseThrow()));
		RadioRouter.ListenerAuthorityTransaction transaction = router.prepareListenerRetirement(current)
			.orElseThrow();
		ListenerAuthoritySnapshot predecessor = listenerSnapshot(router, current);

		store.casCalls = 0;
		store.reject = true;
		assertFalse(router.compareAndSetListenerAuthority(transaction));
		assertEquals(1, store.casCalls);
		assertEquals(predecessor, listenerSnapshot(router, current));

		store.casCalls = 0;
		store.reject = false;
		store.fail = true;
		assertFalse(router.compareAndSetListenerAuthority(transaction));
		assertEquals(1, store.casCalls);
		assertEquals(predecessor, listenerSnapshot(router, current));
	}

	@Test
	void staleOrFullListenerTransactionDoesNotCallStore() {
		RecordingAuthorityStore staleStore = new RecordingAuthorityStore();
		RadioRouter staleRouter = authorityRouter(8, staleStore);
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		assertTrue(staleRouter.compareAndSetListenerAuthority(staleRouter.prepareListenerPublication(
			Optional.empty(), current).orElseThrow()));
		RadioRouter.ListenerAuthorityTransaction stale = staleRouter.prepareListenerRetirement(current)
			.orElseThrow();
		assertTrue(staleRouter.register(endpoint(990, id(991), 0, CommunicationFrequency.of("stale"),
			1, Set.of())));
		ListenerAuthoritySnapshot stalePredecessor = listenerSnapshot(staleRouter, current);
		staleStore.casCalls = 0;
		assertFalse(staleRouter.compareAndSetListenerAuthority(stale));
		assertEquals(0, staleStore.casCalls);
		assertEquals(stalePredecessor, listenerSnapshot(staleRouter, current));

		RecordingAuthorityStore fullStore = new RecordingAuthorityStore();
		RadioRouter fullRouter = authorityRouter(1, fullStore);
		RadioRouter.ListenerAuthority unavailable = listenerAuthority(0);
		ListenerAuthoritySnapshot fullPredecessor = listenerSnapshot(fullRouter, unavailable);
		assertTrue(fullRouter.prepareListenerPublication(Optional.empty(), unavailable).isEmpty());
		assertEquals(0, fullStore.casCalls);
		assertEquals(fullPredecessor, listenerSnapshot(fullRouter, unavailable));
	}

	@Test
	void restoredAuthorityRejectsExhaustedCountersAndMalformedActiveGraphs() {
		RadioRouter.ListenerAuthority authority = listenerAuthority(0);
		Map<RadioEndpoint.Key, RadioRouter.EndpointAuthority> endpoints = Map.of(
			authority.sourceEndpoint().key(), new RadioRouter.EndpointAuthority(0,
				Optional.of(authority.sourceEndpoint())),
			authority.relayEndpoint().key(), new RadioRouter.EndpointAuthority(0,
				Optional.of(authority.relayEndpoint())));
		Map<UUID, RadioRouter.BoundaryAuthority> boundaries = Map.of(
			authority.boundaryBinding().sourceClusterId(), new RadioRouter.BoundaryAuthority(0,
				authority.boundaryBinding(), true));

		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(
			Long.MAX_VALUE, endpoints, boundaries));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4, endpoints,
			Map.of(authority.boundaryBinding().sourceClusterId(), new RadioRouter.BoundaryAuthority(5,
				authority.boundaryBinding(), true))));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4, Map.of(
			authority.sourceEndpoint().key(), new RadioRouter.EndpointAuthority(Long.MAX_VALUE,
				Optional.empty())), Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4, endpoints,
			Map.of(authority.boundaryBinding().sourceClusterId(), new RadioRouter.BoundaryAuthority(
				Long.MAX_VALUE, authority.boundaryBinding(), true))));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4, Map.of(),
			boundaries));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4,
			Map.of(authority.sourceEndpoint().key(), new RadioRouter.EndpointAuthority(0,
				Optional.of(authority.sourceEndpoint()))), boundaries));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4,
			Map.of(authority.relayEndpoint().key(), new RadioRouter.EndpointAuthority(0,
				Optional.of(authority.sourceEndpoint()))), Map.of()));
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4, endpoints,
			Map.of(id(999), new RadioRouter.BoundaryAuthority(0, authority.boundaryBinding(), true))));
		SubnetBoundaryBinding mismatchedGeneration = new SubnetBoundaryBinding(
			authority.boundaryBinding().sourceClusterId(), authority.boundaryBinding().sourceNetworkId(),
			authority.boundaryBinding().targetNetworkId(), authority.boundaryBinding().targetClusterId(),
			authority.boundaryBinding().listenerFrequency(), new RadioEndpoint.Hop(
				authority.sourceEndpoint().key(), 1), authority.relayEndpoint().hop());
		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(4, endpoints,
			Map.of(mismatchedGeneration.sourceClusterId(), new RadioRouter.BoundaryAuthority(0,
				mismatchedGeneration, true))));
	}

	@Test
	void restoredAuthorityRejectsEndpointCollectionBeyondRepresentationBound() {
		Map<RadioEndpoint.Key, RadioRouter.EndpointAuthority> oversized = new java.util.LinkedHashMap<>();
		for (int index = 0; index <= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES; index++)
			oversized.put(new RadioEndpoint.Key(id(20_000L + index), id(40_000L + index)),
				new RadioRouter.EndpointAuthority(0, Optional.empty()));

		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(0,
			oversized, Map.of()));
	}

	@Test
	void restoredAuthorityRejectsBoundaryCollectionBeyondRepresentationBound() {
		Map<UUID, RadioRouter.BoundaryAuthority> oversized = new java.util.LinkedHashMap<>();
		for (int index = 0; index <= FactoryStateRepresentationBounds.MAX_COLLECTION_ENTRIES; index++) {
			UUID sourceCluster = id(60_000L + index);
			SubnetBoundaryBinding binding = new SubnetBoundaryBinding(sourceCluster, id(80_000L + index),
				id(100_000L + index), id(120_000L + index), CommunicationFrequency.of("bound"),
				new RadioEndpoint.Hop(new RadioEndpoint.Key(sourceCluster, id(140_000L + index)), 0),
				new RadioEndpoint.Hop(new RadioEndpoint.Key(id(120_000L + index),
					id(160_000L + index)), 0));
			oversized.put(sourceCluster, new RadioRouter.BoundaryAuthority(0, binding, false));
		}

		assertThrows(IllegalArgumentException.class, () -> new RadioRouter.AuthorityState(0,
			Map.of(), oversized));
	}

	@Test
	void reservedMaximumListenerCandidatesReturnEmptyWithoutChangingPredecessor() {
		RecordingAuthorityStore revisionStore = new RecordingAuthorityStore();
		revisionStore.state = new RadioRouter.AuthorityState(Long.MAX_VALUE - 1, Map.of(), Map.of());
		RadioRouter revisionRouter = authorityRouter(8, revisionStore);
		RadioRouter.AuthorityState revisionPredecessor = revisionRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertTrue(revisionRouter.prepareListenerPublication(Optional.empty(),
			listenerAuthority(0)).isEmpty()));
		assertEquals(revisionPredecessor, revisionRouter.authorityStateForTest());
		RadioRouter.ListenerAuthority retired = listenerAuthority(0);
		RecordingAuthorityStore boundaryStore = new RecordingAuthorityStore();
		boundaryStore.state = new RadioRouter.AuthorityState(Long.MAX_VALUE - 1, Map.of(),
			Map.of(retired.boundaryBinding().sourceClusterId(), new RadioRouter.BoundaryAuthority(
				Long.MAX_VALUE - 1, retired.boundaryBinding(), false)));
		RadioRouter boundaryRouter = authorityRouter(8, boundaryStore);
		RadioRouter.AuthorityState boundaryPredecessor = boundaryRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(boundaryRouter.registerBoundaryBinding(
			listenerAuthority(1).boundaryBinding())));
		assertEquals(boundaryPredecessor, boundaryRouter.authorityStateForTest());

		RadioRouter endpointRouter = authorityRouter(8, new RecordingAuthorityStore());
		RadioRouter.AuthorityState endpointPredecessor = endpointRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertTrue(endpointRouter.prepareListenerPublication(Optional.empty(),
			listenerAuthority(Long.MAX_VALUE)).isEmpty()));
		assertEquals(endpointPredecessor, endpointRouter.authorityStateForTest());
	}

	@Test
	void nonListenerStoreRuntimeFailuresReturnFalseAndPreservePredecessors() {
		RadioEndpoint source = endpoint(900, id(901), 0, CommunicationFrequency.of("store"), 1, Set.of());
		RadioEndpoint relay = endpoint(902, id(903), 4, CommunicationFrequency.of("store"), 1, Set.of());
		SubnetBoundaryBinding binding = new SubnetBoundaryBinding(source.clusterId(), id(904), id(905),
			relay.clusterId(), source.frequency(), source.hop(), relay.hop());

		RecordingAuthorityStore registerStore = new RecordingAuthorityStore();
		RadioRouter registerRouter = authorityRouter(8, registerStore);
		registerStore.runtimeFailure = true;
		RadioRouter.AuthorityState registerPredecessor = registerRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(registerRouter.register(source)));
		assertEquals(registerPredecessor, registerRouter.authorityStateForTest());
		assertTrue(registerRouter.registeredEndpoint(source.key()).isEmpty());

		RecordingAuthorityStore unregisterStore = new RecordingAuthorityStore();
		RadioRouter unregisterRouter = authorityRouter(8, unregisterStore);
		assertTrue(unregisterRouter.register(source));
		unregisterStore.runtimeFailure = true;
		RadioRouter.AuthorityState unregisterPredecessor = unregisterRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(unregisterRouter.unregisterExactEndpoint(source)));
		assertEquals(unregisterPredecessor, unregisterRouter.authorityStateForTest());
		assertEquals(source, unregisterRouter.registeredEndpoint(source.key()).orElseThrow());

		RecordingAuthorityStore boundaryStore = new RecordingAuthorityStore();
		RadioRouter boundaryRouter = authorityRouter(8, boundaryStore);
		assertTrue(boundaryRouter.register(source));
		assertTrue(boundaryRouter.register(relay));
		boundaryStore.runtimeFailure = true;
		RadioRouter.AuthorityState boundaryPredecessor = boundaryRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(boundaryRouter.registerBoundaryBinding(binding)));
		assertEquals(boundaryPredecessor, boundaryRouter.authorityStateForTest());
		assertTrue(boundaryRouter.boundaryBinding(binding.sourceClusterId()).isEmpty());

		RecordingAuthorityStore revokeStore = new RecordingAuthorityStore();
		RadioRouter revokeRouter = authorityRouter(8, revokeStore);
		assertTrue(revokeRouter.register(source));
		assertTrue(revokeRouter.register(relay));
		assertTrue(revokeRouter.registerBoundaryBinding(binding));
		revokeStore.runtimeFailure = true;
		RadioRouter.AuthorityState revokePredecessor = revokeRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(revokeRouter.unregisterBoundaryBinding(binding)));
		assertEquals(revokePredecessor, revokeRouter.authorityStateForTest());
		assertEquals(binding, revokeRouter.boundaryBinding(binding.sourceClusterId()).orElseThrow());

		RecordingAuthorityStore replaceStore = new RecordingAuthorityStore();
		RadioRouter replaceRouter = authorityRouter(8, replaceStore);
		assertTrue(replaceRouter.register(source));
		assertTrue(replaceRouter.register(relay));
		assertTrue(replaceRouter.registerBoundaryBinding(binding));
		SubnetBoundaryBinding replacement = new SubnetBoundaryBinding(source.clusterId(), id(904), id(906),
			relay.clusterId(), source.frequency(), source.hop(), relay.hop());
		replaceStore.runtimeFailure = true;
		RadioRouter.AuthorityState replacePredecessor = replaceRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(replaceRouter.replaceBoundaryBinding(binding, replacement)));
		assertEquals(replacePredecessor, replaceRouter.authorityStateForTest());
		assertEquals(binding, replaceRouter.boundaryBinding(binding.sourceClusterId()).orElseThrow());
	}

	@Test
	void activeListenerRetirementAtReservedGlobalFenceReturnsEmptyWithoutMutation() {
		RadioRouter router = authorityRouter(8, new RecordingAuthorityStore());
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.empty(), current).orElseThrow()));
		setAuthorityRevisions(router, Long.MAX_VALUE - 1);
		ListenerAuthoritySnapshot predecessor = listenerSnapshot(router, current);

		assertDoesNotThrow(() -> assertTrue(router.prepareListenerRetirement(current).isEmpty()));
		assertEquals(predecessor, listenerSnapshot(router, current));
	}

	@Test
	void activeListenerRetirementAndReplacementAtReservedBoundaryFencePreservePredecessor() {
		RadioRouter router = authorityRouter(8, new RecordingAuthorityStore());
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		assertTrue(router.compareAndSetListenerAuthority(router.prepareListenerPublication(
			Optional.empty(), current).orElseThrow()));
		setActiveBoundaryRevision(router, current, Long.MAX_VALUE - 1);
		setAuthorityRevisions(router, Long.MAX_VALUE - 1);
		ListenerAuthoritySnapshot predecessor = listenerSnapshot(router, current);

		assertDoesNotThrow(() -> assertTrue(router.prepareListenerRetirement(current).isEmpty()));
		assertDoesNotThrow(() -> assertTrue(router.prepareListenerPublication(Optional.of(current),
			listenerAuthority(1)).isEmpty()));
		assertEquals(predecessor, listenerSnapshot(router, current));
	}

	@Test
	void durableListenerReactivationAtReservedGlobalFenceReturnsEmptyWithoutMutation() {
		RadioRouter.ListenerAuthority current = listenerAuthority(0);
		RecordingAuthorityStore store = new RecordingAuthorityStore();
		store.state = new RadioRouter.AuthorityState(Long.MAX_VALUE - 1, Map.of(
			current.sourceEndpoint().key(), new RadioRouter.EndpointAuthority(0,
				Optional.of(current.sourceEndpoint())), current.relayEndpoint().key(),
			new RadioRouter.EndpointAuthority(0, Optional.of(current.relayEndpoint()))), Map.of(
			current.boundaryBinding().sourceClusterId(), new RadioRouter.BoundaryAuthority(0,
				current.boundaryBinding(), true)));
		RadioRouter router = authorityRouter(8, store);
		RadioRouter.AuthorityState predecessor = router.authorityStateForTest();

		assertDoesNotThrow(() -> assertTrue(router.prepareListenerPublication(Optional.empty(),
			current).isEmpty()));
		assertEquals(predecessor, router.authorityStateForTest());
	}

	@Test
	void genericStoreRuntimeFailurePreservesFrequencyUpdateAndGenerationUnregister() {
		RadioEndpoint endpoint = endpoint(970, id(971), 0, CommunicationFrequency.of("old"), 1,
			Set.of());
		RecordingAuthorityStore frequencyStore = new RecordingAuthorityStore();
		RadioRouter frequencyRouter = authorityRouter(8, frequencyStore);
		assertTrue(frequencyRouter.register(endpoint));
		frequencyStore.runtimeFailure = true;
		RadioRouter.AuthorityState frequencyPredecessor = frequencyRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(frequencyRouter.replaceClusterPrimaryFrequency(endpoint.key(),
			endpoint.generation(), CommunicationFrequency.of("new"))));
		assertEquals(frequencyPredecessor, frequencyRouter.authorityStateForTest());
		assertEquals(endpoint, frequencyRouter.registeredEndpoint(endpoint.key()).orElseThrow());

		RecordingAuthorityStore unregisterStore = new RecordingAuthorityStore();
		RadioRouter unregisterRouter = authorityRouter(8, unregisterStore);
		assertTrue(unregisterRouter.register(endpoint));
		unregisterStore.runtimeFailure = true;
		RadioRouter.AuthorityState unregisterPredecessor = unregisterRouter.authorityStateForTest();
		assertDoesNotThrow(() -> assertFalse(unregisterRouter.unregister(endpoint.key(),
			endpoint.generation())));
		assertEquals(unregisterPredecessor, unregisterRouter.authorityStateForTest());
		assertEquals(endpoint, unregisterRouter.registeredEndpoint(endpoint.key()).orElseThrow());
	}

	private static RadioRouter router(RadioEndpoint... endpoints) {
		RadioRouter router = new RadioRouter(16, 48, 64);
		for (RadioEndpoint endpoint : endpoints)
			router.register(endpoint);
		return router;
	}

	private static RadioRouter authorityRouter(int endpointCapacity,
		RecordingAuthorityStore authorityStore) {
		return new RadioRouter(16, 48, endpointCapacity, 64,
			new RadioRouter.AgentProtocolStateStore() {
				@Override public void persist(RadioRouter.AgentProtocolState state) {}
			}, new RadioRouter.LeaseProtocolStateStore() {
				@Override public void persist(RadioRouter.LeaseProtocolState state) {}
			}, authorityStore);
	}

	private static RadioRouter.ListenerAuthority listenerAuthority(long generation) {
		CommunicationFrequency frequency = CommunicationFrequency.of("listener-authority");
		RadioEndpoint source = endpoint(803, id(800), 0, frequency, 1,
			Set.of(ProductionCapability.EXTENDED_RADIO), generation);
		RadioEndpoint relay = endpoint(804, id(802), 4, frequency, 1,
			Set.of(ProductionCapability.EXTENDED_RADIO), generation);
		return new RadioRouter.ListenerAuthority(source, relay, new SubnetBoundaryBinding(id(800),
			id(805), id(806), id(802), frequency, source.hop(), relay.hop()));
	}

	private static ListenerAuthoritySnapshot listenerSnapshot(RadioRouter router,
		RadioRouter.ListenerAuthority authority) {
		return new ListenerAuthoritySnapshot(router.authorityStateForTest(),
			router.registeredEndpoint(authority.sourceEndpoint().key()),
			router.registeredEndpoint(authority.relayEndpoint().key()), router.boundaryBinding(
				authority.boundaryBinding().sourceClusterId()), router.authorityRevision());
	}

	private record ListenerAuthoritySnapshot(RadioRouter.AuthorityState authority,
		Optional<RadioEndpoint> source, Optional<RadioEndpoint> relay,
		Optional<SubnetBoundaryBinding> boundary, long revision) {}

	private static void setAuthorityRevisions(RadioRouter router, long revision) {
		try {
			for (String fieldName : List.of("authorityRevision", "durableAuthorityRevision")) {
				java.lang.reflect.Field field = RadioRouter.class.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.setLong(router, revision);
			}
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	@SuppressWarnings("unchecked")
	private static void setActiveBoundaryRevision(RadioRouter router,
		RadioRouter.ListenerAuthority authority, long revision) {
		try {
			java.lang.reflect.Field field = RadioRouter.class.getDeclaredField("boundaryAuthorities");
			field.setAccessible(true);
			Map<UUID, RadioRouter.BoundaryAuthority> boundaries =
				(Map<UUID, RadioRouter.BoundaryAuthority>) field.get(router);
			boundaries.put(authority.boundaryBinding().sourceClusterId(), new RadioRouter.BoundaryAuthority(
				revision, authority.boundaryBinding(), true));
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	private static void deliverAcknowledgedRevision(RadioRouter router, RadioEndpoint source,
		RadioEndpoint receiver, RemoteProcessLease lease, long firstMessageId) {
		router.submit(envelope(firstMessageId, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseOffer(lease)), List.of());
		router.tick(1);
		router.submit(envelope(firstMessageId + 1, receiver, source, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.LeaseRevision(lease.leaseId(), 4, revision(lease))), List.of());
		router.tick(2);
		router.submit(envelope(firstMessageId + 2, source, receiver, CommunicationFrequency.of("17"),
			new ProcessProtocolMessage.RevisionAcknowledgement(lease.leaseId(), 4)), List.of());
		router.tick(3);
	}

	private static RadioEndpoint endpoint(long member, UUID cluster, int x, CommunicationFrequency frequency,
		int listeners, Set<ProductionCapability> capabilities) {
		return endpoint(member, cluster, x, frequency, listeners, capabilities, 1);
	}

	private static RadioEndpoint endpoint(long member, UUID cluster, int x, CommunicationFrequency frequency,
		int listeners, Set<ProductionCapability> capabilities, long generation) {
		return new RadioEndpoint(cluster, id(member),
			new SpaceAddress(Level.OVERWORLD, null, new BlockPos(x, 64, 0)), generation,
			frequency, listeners, capabilities);
	}

	private static RadioTopologyFrame.Sample currentSample(RadioEndpoint endpoint, Vec3 center) {
		return new RadioTopologyFrame.Sample(endpoint.address().dimension(), endpoint.address(), center,
			true, endpoint.generation(), Optional.ofNullable(endpoint.address().subLevelId()));
	}
	private static RadioEnvelope envelope(long message, RadioEndpoint source,
		RadioEndpoint target, CommunicationFrequency frequency, ProcessProtocolMessage payload) {
		return RadioEnvelope.create(id(message), source, target, frequency, payload);
	}

	private static RemoteProcessLease lease(RadioEndpoint source, RadioEndpoint receiver) {
		var ingredient = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey(
			new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(201), id(202), 0, 4,
			id(203), 2, id(204), 4, InputMode.BULK, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(205), "input"), Map.of(ingredient, 1L))),
			Map.of(ingredient, 1L));
		return new RemoteProcessLease(id(200), id(201), id(202), source.clusterId(),
			receiver.clusterId(), source.generation(), receiver.generation(),
			ProductionCapability.ALCHEMIST, request, 4);
	}

	private static RemoteProcessLease foreignLease(RadioEndpoint source,
		RadioEndpoint receiver) {
		var ingredient = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey(
			new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COPPER_INGOT));
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(201), id(202), 0, 4,
			id(206), 2, id(207), 4, InputMode.BULK, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(208), "foreign-input"), Map.of(ingredient, 1L))),
			Map.of(ingredient, 1L));
		return new RemoteProcessLease(id(209), id(201), id(202), source.clusterId(),
			receiver.clusterId(), source.generation(), receiver.generation(),
			ProductionCapability.ALCHEMIST, request, 4);
	}

	private static RemoteProcessLease serialExactLease(RadioEndpoint source,
		RadioEndpoint receiver, int run) {
		var ingredient = new com.nobodiiiii.createbiotech.content.biocomputing.application.recipe.StackKey(
			new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
		long fence = run + 1L;
		RemoteExpansionRequest request = RemoteExpansionRequest.capture(
			RemoteExpansionTestRegistries.provider(), id(401), id(402), 0,
			fence, id(403), 2, new UUID(40, fence), fence, InputMode.EXACT, 1,
			List.of(new RemoteExpansionRequest.StageInput(0,
				new CreateRouteKey(id(404), "serial-exact-input"), Map.of(ingredient, 1L))),
			Map.of(ingredient, 1L));
		return new RemoteProcessLease(new UUID(41, fence), id(401), id(402),
			source.clusterId(), receiver.clusterId(), source.generation(), receiver.generation(),
			ProductionCapability.ALCHEMIST, request, fence);
	}

	private static RemoteExpansionRevision revision(RemoteProcessLease lease) {
		return RemoteExpansionRevision.compute(lease.expansionRequest());
	}

	private static RemoteExpansionResult result(RemoteProcessLease lease) {
		return RemoteExpansionResult.compute(lease.expansionRequest(), revision(lease));
	}

	private static RemoteRequestKey agentKey() {
		return new RemoteRequestKey(id(300), id(301), 4, 1, 1);
	}

	private static RemoteRequestKey agentKey(long base) {
		return new RemoteRequestKey(id(base), id(base + 1), 4, 1, 1);
	}

	private static ProductionRequestTicket agentTicket() {
		return new ProductionRequestTicket(id(300), id(301), 4, SOURCE_CLUSTER, id(302),
			new CreateRouteKey(id(303), "agent-input"), id(304), InputMode.EXACT,
			1, 0, 0, 1);
	}

	private static ProductionRequestTicket agentTicket(long base) {
		return new ProductionRequestTicket(id(base), id(base + 1), 4, SOURCE_CLUSTER,
			id(base + 2), new CreateRouteKey(id(base + 3), "agent-input"), id(base + 4),
			InputMode.EXACT, 1, 0, 0, 1);
	}

	private static RemoteAgentRecord terminalRecord(RemoteAgentRecord.TerminalOutcome outcome) {
		return new RemoteAgentRecord(agentKey(), TARGET_CLUSTER, agentTicket(),
			RemoteAgentPhase.RELEASED, RemoteAgentRecord.RecoveryCondition.NORMAL,
			true, true, Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.of(outcome), 1);
	}

	private static RemoteAgentRecord queuedRecord() {
		return new RemoteAgentRecord(agentKey(), TARGET_CLUSTER, agentTicket(),
			RemoteAgentPhase.QUEUED, RemoteAgentRecord.RecoveryCondition.NORMAL,
			false, false, Optional.empty(), Optional.empty(), Optional.empty(),
			Optional.empty(), 0);
	}

	private static final class RecordingAgentProtocolStore
		implements RadioRouter.AgentProtocolStateStore {
		private RadioRouter.AgentProtocolState state =
			new RadioRouter.AgentProtocolState(Map.of());
		@Override public Optional<RadioRouter.AgentProtocolState> load() {
			return Optional.of(state);
		}
		@Override public void persist(RadioRouter.AgentProtocolState next) { state = next; }
	}

	private static final class RecordingLeaseProtocolStore
		implements RadioRouter.LeaseProtocolStateStore {
		private RadioRouter.LeaseProtocolState state =
			new RadioRouter.LeaseProtocolState(Map.of(), Map.of());
		@Override public Optional<RadioRouter.LeaseProtocolState> load() {
			return Optional.of(state);
		}
		@Override public void persist(RadioRouter.LeaseProtocolState next) { state = next; }
	}

	private static final class RecordingAuthorityStore implements RadioRouter.AuthorityStateStore {
		private RadioRouter.AuthorityState state = new RadioRouter.AuthorityState(0, Map.of(), Map.of());
		private int casCalls;
		private boolean reject;
		private boolean fail;
		private boolean runtimeFailure;
		@Override public Optional<RadioRouter.AuthorityState> load() { return Optional.of(state); }
		@Override public boolean compareAndSet(RadioRouter.AuthorityState expected,
			RadioRouter.AuthorityState replacement) {
			casCalls++;
			if (runtimeFailure) throw new UnsupportedOperationException("injected generic persistence failure");
			if (fail) throw new IllegalStateException("injected authority persistence failure");
			if (reject || !state.equals(expected)) return false;
			state = replacement;
			return true;
		}
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
