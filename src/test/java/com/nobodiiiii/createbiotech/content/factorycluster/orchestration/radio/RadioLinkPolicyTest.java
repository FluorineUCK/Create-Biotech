package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class RadioLinkPolicyTest {
	private static final UUID FIRST_CLUSTER = id(1);
	private static final UUID SECOND_CLUSTER = id(2);

	@Test
	void requiresLoadedCurrentSamplesAtTheInclusiveOrdinaryBoundary() {
		RadioEndpoint source = endpoint(1, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.of("North"), Set.of());
		RadioEndpoint target = endpoint(2, SECOND_CLUSTER, Level.OVERWORLD, null, 64,
			CommunicationFrequency.of("North"), Set.of());
		RadioLinkPolicy policy = new RadioLinkPolicy(64, 128);

		assertTrue(policy.link(source, target, frame(20, true, sample(source), sample(target)))
			.reachable(), "distance exactly equal to ordinary range remains reachable");
		assertFalse(policy.link(source, target, frame(20, true, sample(source),
			sample(target, false, target.generation(), Vec3.atCenterOf(target.address().localPos()))))
			.reachable(), "an unloaded endpoint cannot use its old pose");
		assertFalse(policy.link(source, target, frame(20, true, sample(source),
			sample(target, true, target.generation() + 1,
				Vec3.atCenterOf(target.address().localPos())))).reachable(),
			"a stale endpoint generation cannot use a current point");
	}

	@Test
	void acceptsOnlyVirtualSameClusterBlankEdgesAndExactConfiguredFrequencies() {
		RadioLinkPolicy policy = new RadioLinkPolicy(64, 128);
		RadioEndpoint localA = endpoint(1, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.UNCONFIGURED, Set.of());
		RadioEndpoint localB = endpoint(2, FIRST_CLUSTER, Level.NETHER, id(20), 900,
			CommunicationFrequency.UNCONFIGURED, Set.of());
		RadioEndpoint blankRemote = endpoint(3, SECOND_CLUSTER, Level.OVERWORLD, null, 8,
			CommunicationFrequency.UNCONFIGURED, Set.of());
		RadioEndpoint mismatchedRemote = endpoint(4, SECOND_CLUSTER, Level.OVERWORLD, null, 8,
			CommunicationFrequency.of("north"), Set.of());
		RadioEndpoint configuredSource = endpoint(5, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.of("North"), Set.of());

		assertTrue(policy.link(localA, localB, new RadioTopologyFrame(20, true, Map.of()))
			.virtual());
		assertFalse(policy.link(localA, blankRemote, frame(20, true, sample(localA),
			sample(blankRemote))).reachable());
		assertFalse(policy.link(configuredSource, mismatchedRemote, frame(20, true,
			sample(configuredSource), sample(mismatchedRemote))).reachable(),
			"frequencies are literal and case-sensitive");
	}

	@Test
	void allowsExtendedRelayRangeOnlyWhenAnEndpointHasExtendedRadio() {
		RadioLinkPolicy policy = new RadioLinkPolicy(64, 128);
		RadioEndpoint source = endpoint(1, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.of("17"), Set.of());
		RadioEndpoint ordinary = endpoint(2, SECOND_CLUSTER, Level.OVERWORLD, null, 96,
			CommunicationFrequency.of("17"), Set.of());
		RadioEndpoint relay = endpoint(3, SECOND_CLUSTER, Level.OVERWORLD, null, 96,
			CommunicationFrequency.of("17"), Set.of(ProductionCapability.EXTENDED_RADIO));

		assertFalse(policy.link(source, ordinary, frame(20, true, sample(source), sample(ordinary)))
			.reachable());
		assertTrue(policy.link(source, relay, frame(20, true, sample(source), sample(relay)))
			.reachable());
	}

	@Test
	void classifiesTheSingleDirectedRouterEdgeAndRejectsAZeroCapacityReceiver() {
		RadioLinkPolicy policy = new RadioLinkPolicy(64, 128);
		RadioEndpoint source = endpoint(1, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.of("directed"), Set.of());
		RadioEndpoint extendedReceiver = endpoint(2, SECOND_CLUSTER, Level.OVERWORLD, null, 96,
			CommunicationFrequency.of("directed"), Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint zeroCapacityReceiver = new RadioEndpoint(extendedReceiver.clusterId(),
			extendedReceiver.memberId(), extendedReceiver.address(), extendedReceiver.generation(),
			extendedReceiver.frequency(), 0, extendedReceiver.capabilities());
		RadioTopologyFrame frame = frame(20, true, sample(source), sample(extendedReceiver));

		assertEquals(RadioLinkPolicy.EdgeResult.EXTENDED,
			policy.edge(source, extendedReceiver, frame));
		assertEquals(RadioLinkPolicy.EdgeResult.UNAVAILABLE,
			policy.edge(source, zeroCapacityReceiver, frame));
		assertEquals(RadioLinkPolicy.EdgeResult.EXTENDED,
			policy.edge(zeroCapacityReceiver, source, frame),
			"the physical edge is directed by the receiving endpoint capacity");
	}

	@Test
	void disabledTopologyRejectsOuterToStructureAndDifferentStructures() {
		RadioLinkPolicy policy = new RadioLinkPolicy(64, 128);
		UUID firstStructure = id(30);
		RadioEndpoint outer = endpoint(1, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.of("17"), Set.of());
		RadioEndpoint sameStructure = endpoint(2, SECOND_CLUSTER, Level.OVERWORLD, firstStructure, 64,
			CommunicationFrequency.of("17"), Set.of());
		RadioEndpoint anotherStructure = endpoint(3, FIRST_CLUSTER, Level.OVERWORLD, id(31), 64,
			CommunicationFrequency.of("17"), Set.of());
		RadioEndpoint peerOnFirstStructure = endpoint(4, FIRST_CLUSTER, Level.OVERWORLD,
			firstStructure, 64, CommunicationFrequency.of("17"), Set.of());

		assertFalse(policy.link(outer, sameStructure, frame(20, false, sample(outer),
			sample(sameStructure))).reachable());
		assertFalse(policy.link(sameStructure, anotherStructure, frame(20, false,
			sample(sameStructure), sample(anotherStructure))).reachable());
		assertTrue(policy.link(sameStructure, peerOnFirstStructure, frame(20, false,
			sample(sameStructure, true, sameStructure.generation(), new Vec3(900.5, 64.5, .5)), sample(peerOnFirstStructure, true, peerOnFirstStructure.generation(), new Vec3(-900.5, 64.5, .5)))).reachable());
	}

	@Test
	void rejectsCrossDimensionEvenWhenProjectedPointsCoincide() {
		RadioEndpoint overworld = endpoint(1, FIRST_CLUSTER, Level.OVERWORLD, null, 0,
			CommunicationFrequency.of("17"), Set.of());
		RadioEndpoint nether = endpoint(2, SECOND_CLUSTER, Level.NETHER, null, 0,
			CommunicationFrequency.of("17"), Set.of());

		assertFalse(new RadioLinkPolicy(64, 128).link(overworld, nether, frame(20, true,
			sample(overworld), sample(nether))).reachable());
	}

	private static RadioTopologyFrame frame(long tick, boolean dynamic, FrameSample... samples) {
		Map<RadioEndpoint.Key, RadioTopologyFrame.Sample> mapped = new LinkedHashMap<>();
		for (FrameSample sample : samples) mapped.put(sample.endpoint().key(), sample.point());
		return new RadioTopologyFrame(tick, dynamic, mapped);
	}

	private static FrameSample sample(RadioEndpoint endpoint) {
		return sample(endpoint, true, endpoint.generation(),
			Vec3.atCenterOf(endpoint.address().localPos()));
	}

	private static FrameSample sample(RadioEndpoint endpoint, boolean loaded, long generation,
		Vec3 center) {
		return new FrameSample(endpoint, new RadioTopologyFrame.Sample(endpoint.address().dimension(),
			endpoint.address(), center, loaded, generation,
			Optional.ofNullable(endpoint.address().subLevelId())));
	}

	private static RadioEndpoint endpoint(long member, UUID cluster, ResourceKey<Level> dimension,
		UUID structure, int x, CommunicationFrequency frequency, Set<ProductionCapability> capabilities) {
		return new RadioEndpoint(cluster, id(member), new SpaceAddress(dimension, structure,
			new BlockPos(x, 64, 0)), 1, frequency, 1, capabilities);
	}

	private record FrameSample(RadioEndpoint endpoint, RadioTopologyFrame.Sample point) {}

	private static UUID id(long value) { return new UUID(0, value); }
}
