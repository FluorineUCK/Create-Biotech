package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class RadioTopologyProjectionTest {
	@Test
	void acceptsOnlyExactLoadedComputerEvidence() {
		RadioEndpoint endpoint = endpoint(1, 2, null, 7);

		assertEquals(new RadioTopologyTracker.ResolvedPose(new Vec3(8.5, .5, .5),
			Optional.empty()), RadioTopologyProjection.resolve(endpoint,
			evidence(endpoint, new Vec3(8.5, .5, .5))));
	}

	@Test
	void unloadedOuterChunkAndUnloadedSublevelProduceNoProjectionEvidence() {
		assertNull(RadioTopologyProjection.resolve(endpoint(1, 2, null, 7), null));
		assertNull(RadioTopologyProjection.resolve(endpoint(1, 2, id(3), 7), null));
	}

	@Test
	void missingOrWrongBlockEntityIdentityFailsClosed() {
		RadioEndpoint expected = endpoint(1, 2, id(3), 7);
		RadioEndpoint wrongComputer = endpoint(1, 4, id(3), 7);
		RadioEndpoint wrongGeneration = endpoint(1, 2, id(3), 8);
		RadioEndpoint wrongSource = endpoint(1, 2, id(5), 7);

		assertNull(RadioTopologyProjection.resolve(expected, null));
		assertNull(RadioTopologyProjection.resolve(expected, evidence(wrongComputer, Vec3.ZERO)));
		assertNull(RadioTopologyProjection.resolve(expected, evidence(wrongGeneration, Vec3.ZERO)));
		assertNull(RadioTopologyProjection.resolve(expected, evidence(wrongSource, Vec3.ZERO)));
	}

	@Test
	void exactInstalledProfileProofRejectsReequippedComputerAtTheSameEndpointIdentity() {
		RadioEndpoint endpoint = endpoint(1, 2, null, 7);
		RadioTopologyProjection.Evidence listenerMismatch = evidence(endpoint, Vec3.ZERO,
			new RadioTopologyProjection.ProfileProof(2, endpoint.capabilities()));
		RadioTopologyProjection.Evidence capabilityMismatch = evidence(endpoint, Vec3.ZERO,
			new RadioTopologyProjection.ProfileProof(endpoint.listenerCapacity(),
				Set.of(com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability.ALCHEMIST)));

		assertNull(RadioTopologyProjection.resolve(endpoint, listenerMismatch));
		assertNull(RadioTopologyProjection.resolve(endpoint, capabilityMismatch));
	}

	private static RadioTopologyProjection.Evidence evidence(RadioEndpoint endpoint, Vec3 worldCenter) {
		return evidence(endpoint, worldCenter, new RadioTopologyProjection.ProfileProof(
			endpoint.listenerCapacity(), endpoint.capabilities()));
	}

	private static RadioTopologyProjection.Evidence evidence(RadioEndpoint endpoint, Vec3 worldCenter,
		RadioTopologyProjection.ProfileProof profile) {
		return new RadioTopologyProjection.Evidence(endpoint.key(), endpoint.generation(),
			endpoint.address(), worldCenter, Optional.ofNullable(endpoint.address().subLevelId()), profile);
	}

	private static RadioEndpoint endpoint(long cluster, long member, UUID structure, long generation) {
		return new RadioEndpoint(id(cluster), id(member), new SpaceAddress(Level.OVERWORLD, structure,
			BlockPos.ZERO), generation, CommunicationFrequency.of("radio"), 1, Set.of());
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}
}
