package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class RadioTopologyLifecycleTest {
	@Test
	void keyReplacementRetiresThePreviousClusterEndpointByStableComputerIdentity() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint original = endpoint(1, 2, 0, "radio");
		RadioEndpoint replacement = endpoint(3, 2, 0, "radio");
		lifecycle.observe(original, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));

		assertEquals(Set.of(original), Set.copyOf(lifecycle.observe(replacement, pose(), 21)));
		assertTrue(lifecycle.frame().samples().isEmpty());
		lifecycle.refresh(40, resolverAt(12));
		assertEquals(new Vec3(12.5, .5, .5),
			lifecycle.frame().sample(replacement.key()).worldCenter());
	}

	@Test
	void invalidOrOfflineComputerRetirementCannotReappearAtTheNextCadence() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint endpoint = endpoint(1, 2, 0, "radio");
		lifecycle.observe(endpoint, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));

		assertEquals(Set.of(endpoint), Set.copyOf(lifecycle.retire(endpoint.memberId(),
			endpoint.address())));
		assertTrue(lifecycle.frame().samples().isEmpty());
		lifecycle.refresh(40, resolverAt(12));
		assertTrue(lifecycle.frame().samples().isEmpty());
	}

	@Test
	void unloadThenSameGenerationReloadInvalidatesOldPoseUntilTheNextFrame() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint endpoint = endpoint(1, 2, 0, "radio");
		lifecycle.observe(endpoint, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));

		lifecycle.unload(endpoint.memberId(), endpoint.address(), 21);
		assertFalse(lifecycle.frame().sample(endpoint.key()).loaded());
		lifecycle.observe(endpoint, pose(), 22);
		assertTrue(lifecycle.frame().find(endpoint.key()).isEmpty());
		lifecycle.refresh(40, resolverAt(12));
		assertTrue(lifecycle.frame().sample(endpoint.key()).loaded());
	}

	@Test
	void staleRouterGenerationFenceDoesNotBlockAnAvailableTopologyReload() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint endpoint = endpoint(1, 2, 0, "radio");
		RadioRouter router = new RadioRouter(16, 48, 64);
		assertTrue(router.register(endpoint));
		assertTrue(router.unregister(endpoint.key(), endpoint.generation()));
		assertFalse(router.register(endpoint), "router generation admission remains fenced");

		lifecycle.observe(endpoint, pose(), 21);
		lifecycle.refresh(40, resolverAt(12));

		assertTrue(lifecycle.frame().sample(endpoint.key()).loaded());
	}

	@Test
	void sameGenerationAddressAndFrequencyReplacementsDiscardTheOldSampleImmediately() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint original = endpoint(1, 2, 0, "radio");
		RadioEndpoint moved = endpoint(1, 2, 1, "radio");
		RadioEndpoint retuned = endpoint(1, 2, 1, "retuned");
		lifecycle.observe(original, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));

		lifecycle.observe(moved, pose(), 21);
		assertTrue(lifecycle.frame().find(original.key()).isEmpty());
		lifecycle.refresh(40, resolverAt(12));
		lifecycle.observe(retuned, pose(), 41);
		assertTrue(lifecycle.frame().find(retuned.key()).isEmpty());
	}

	@Test
	void replacementPublishesOnlyTheCompletePostReplacementFrame() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioEndpoint original = endpoint(1, 2, 0, "radio");
		RadioEndpoint replacement = endpoint(3, 2, 1, "retuned");
		lifecycle.observe(original, pose(), 1);
		RadioTopologyFrame before = lifecycle.frame();

		lifecycle.observe(replacement, pose(), 2);

		assertFalse(lifecycle.frame() == before);
		assertTrue(lifecycle.frame().find(original.key()).isEmpty());
		assertEquals(replacement.address(), lifecycle.frame().sample(replacement.key()).source());
		RadioTopologyFrame after = lifecycle.frame();
		lifecycle.observe(replacement, pose(), 3);
		assertSame(after, lifecycle.frame(), "no-op observations retain the exact publication");
	}

	@Test
	void delayedUnloadFromAnOlderRegistrationCannotWithdrawItsSuccessor() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint original = endpoint(1, 2, 0, "radio");
		RadioEndpoint successor = new RadioEndpoint(original.clusterId(), original.memberId(),
			original.address(), 2, original.frequency(), original.listenerCapacity(),
			original.capabilities());
		RadioTopologyLifecycle.Registration oldRegistration = registration(70, 71);
		RadioTopologyLifecycle.Registration successorRegistration = registration(72, 71);

		lifecycle.observe(oldRegistration, original, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));
		lifecycle.observe(successorRegistration, successor, pose(), 21);

		assertTrue(lifecycle.unload(oldRegistration, 22).isEmpty());
		lifecycle.refresh(40, resolverAt(12));
		assertTrue(lifecycle.isCurrent(successor));
		assertTrue(lifecycle.frame().sample(successor.key()).loaded());
	}

	@Test
	void listenerSourceAndRelayAtOneHostRemainIndependentTopologyObservations() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(false);
		RadioEndpoint source = endpoint(1, 2, 0, "radio");
		RadioEndpoint relay = endpoint(3, 4, 0, "radio");
		UUID hostToken = id(80);

		lifecycle.observe(new RadioTopologyLifecycle.Registration(hostToken, source.key().memberId()),
			source, pose(), 1);
		lifecycle.observe(new RadioTopologyLifecycle.Registration(hostToken, relay.key().memberId()),
			relay, pose(), 1);

		assertTrue(lifecycle.isCurrent(source));
		assertTrue(lifecycle.isCurrent(relay));
		assertEquals(Set.of(source.key(), relay.key()), lifecycle.frame().samples().keySet());
	}

	@Test
	void unavailableTombstonesAreBoundedAfterTheirRetirementWindow() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint endpoint = endpoint(1, 2, 0, "radio");
		RadioTopologyLifecycle.Registration registration = registration(90, 91);
		lifecycle.observe(registration, endpoint, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));

		lifecycle.unload(registration, 21);
		assertFalse(lifecycle.frame().sample(endpoint.key()).loaded(),
			"the immediate unavailable frame preserves the unload result");
		lifecycle.refresh(80, resolverAt(12));

		assertEquals(0, lifecycle.unloadedCount());
		assertTrue(lifecycle.frame().find(endpoint.key()).isEmpty());
	}

	@Test
	void reloadSupersedesItsUnavailablePredecessorWithoutLeavingASecondRetirement() {
		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioEndpoint original = endpoint(1, 2, 0, "radio");
		RadioEndpoint reload = new RadioEndpoint(original.clusterId(), original.memberId(),
			original.address(), 2, original.frequency(), original.listenerCapacity(),
			original.capabilities());
		RadioTopologyLifecycle.Registration owner = registration(100, 101);
		lifecycle.observe(owner, original, pose(), 1);
		lifecycle.refresh(20, resolverAt(8));

		lifecycle.unload(owner, 21);
		lifecycle.observe(owner, reload, pose(), 22);

		assertEquals(0, lifecycle.unloadedCount(),
			"a reloaded exact owner replaces its unavailable predecessor atomically");
		lifecycle.refresh(40, resolverAt(12));
		assertTrue(lifecycle.isCurrent(reload));
		assertTrue(lifecycle.frame().sample(reload.key()).loaded());
	}

	private static RadioTopologyTracker.Pose pose() {
		return new RadioTopologyTracker.Pose(Optional.empty(), Vec3.ZERO);
	}

	private static RadioTopologyTracker.ProjectionResolver resolverAt(double x) {
		return (endpoint, ignored) -> new RadioTopologyTracker.ResolvedPose(
			Vec3.atCenterOf(endpoint.address().localPos()).add(x, 0, 0),
			Optional.ofNullable(endpoint.address().subLevelId()));
	}

	private static RadioEndpoint endpoint(long cluster, long member, int x, String frequency) {
		return new RadioEndpoint(id(cluster), id(member), new SpaceAddress(Level.OVERWORLD, null,
			new BlockPos(x, 0, 0)), 1, CommunicationFrequency.of(frequency), 1, Set.of());
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private static RadioTopologyLifecycle.Registration registration(long owner, long identity) {
		return new RadioTopologyLifecycle.Registration(id(owner), id(identity));
	}
}
