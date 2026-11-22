package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class RadioTopologyPublicationAuthorityTest {
	@Test
	void sameSampleTickCannotBlessChangedRouterOrFrameFacts() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioRouter router = new RadioRouter(32, 128, 16);
		RadioEndpoint first = endpoint(1, 0);
		observe(lifecycle, router, first, 1);
		RadioTopologyPublicationAuthority authority = new RadioTopologyPublicationAuthority(
			lifecycle, router);
		assertTrue(authority.capture().isCurrent());

		observe(lifecycle, router, endpoint(2, 8), 1);
		assertThrows(IllegalStateException.class, authority::capture);
	}

	@Test
	void routerRetirementImmediatelyInvalidatesOpaquePublication() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioRouter router = new RadioRouter(32, 128, 16);
		RadioEndpoint endpoint = endpoint(1, 0);
		observe(lifecycle, router, endpoint, 1);
		RadioTopologyPublicationAuthority authority = new RadioTopologyPublicationAuthority(
			lifecycle, router);
		RadioTopologyPublicationAuthority.Publication publication = authority.capture();

		assertTrue(router.unregisterExactEndpoint(endpoint));
		assertFalse(publication.isCurrent());
	}

	@Test
	void sameEndpointValueFromAReplacementLoadedOwnerInvalidatesTheOldProof() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioRouter router = new RadioRouter(32, 128, 16);
		RadioEndpoint endpoint = endpoint(1, 0);
		observe(lifecycle, router, endpoint, 1);
		RadioTopologyPublicationAuthority authority = new RadioTopologyPublicationAuthority(
			lifecycle, router);
		RadioTopologyPublicationAuthority.Publication publication = authority.capture();

		lifecycle.observe(new RadioTopologyLifecycle.Registration(id(999), endpoint.memberId()),
			endpoint, new RadioTopologyTracker.Pose(Optional.empty(),
				Vec3.atCenterOf(endpoint.address().localPos())), 2);
		assertFalse(publication.isCurrent());
		assertThrows(IllegalStateException.class, authority::capture);
	}

	@Test
	void boundaryWithAnUnpublishedEndpointCannotBeMinted() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioRouter router = new RadioRouter(32, 128, 16);
		RadioEndpoint source = endpoint(1, 0);
		RadioEndpoint missing = endpoint(2, 8);
		observe(lifecycle, router, source, 1);
		assertFalse(router.registerBoundaryBinding(new SubnetBoundaryBinding(source.clusterId(),
			id(30), id(31), missing.clusterId(), source.frequency(), source.hop(), missing.hop())));
	}

	private static void observe(RadioTopologyLifecycle lifecycle, RadioRouter router,
		RadioEndpoint endpoint, long tick) {
		assertTrue(router.register(endpoint));
		lifecycle.observe(new RadioTopologyLifecycle.Registration(endpoint.memberId(),
			endpoint.memberId()), endpoint, new RadioTopologyTracker.Pose(Optional.empty(),
			Vec3.atCenterOf(endpoint.address().localPos())), tick);
	}

	private static RadioEndpoint endpoint(long value, int x) {
		return new RadioEndpoint(id(value), id(value + 100), new SpaceAddress(Level.OVERWORLD,
			null, new BlockPos(x, 64, 0)), 1, CommunicationFrequency.of("factory"), 1, Set.of());
	}

	private static UUID id(long value) { return new UUID(0, value); }
}
