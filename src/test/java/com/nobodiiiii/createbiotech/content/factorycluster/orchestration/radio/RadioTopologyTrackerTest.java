package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

class RadioTopologyTrackerTest {
	@Test
	void replacesWholeFrameOnlyAtTwentyTickBoundary() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint endpoint = endpoint(1, null, 0, 0, 0, 1);
		tracker.observe(endpoint, poseAt(0, 0, 0), true, 19);

		assertEquals(0, tracker.frame().sampleTick());
		assertTrue(tracker.frame().samples().isEmpty());

		tracker.refresh(20, resolverAt(48, 0, 0));

		assertEquals(20, tracker.frame().sampleTick());
		assertEquals(new Vec3(48.5, .5, .5), tracker.frame().sample(endpoint.key()).worldCenter());
	}

	@Test
	void projectsRotationUsingTheCurrentSableLogicalPose() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint endpoint = endpoint(1, id(10), 2, 3, 4, 1);
		SubLevelAccess rotated = translatedRotatedSubLevel(id(10), 100, 50, -20);
		tracker.observe(endpoint, poseAt(0, 0, 0), true, 1);

		tracker.refresh(20, resolverUsing(rotated));

		assertEquals(new Vec3(104.5, 53.5, -22.5),
			tracker.frame().sample(endpoint.key()).worldCenter());
	}

	@Test
	void capturesWorldToStructureAndStructureToStructureCoordinatesInOneFrame() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint outer = endpoint(1, null, 40, 0, 0, 1);
		RadioEndpoint first = endpoint(2, id(20), 0, 0, 0, 1);
		RadioEndpoint second = endpoint(3, id(21), 0, 0, 0, 1);
		SubLevelAccess firstStructure = translatedRotatedSubLevel(id(20), 40, 0, 0);
		SubLevelAccess secondStructure = translatedRotatedSubLevel(id(21), -16, 0, 0);
		tracker.observe(outer, poseAt(0, 0, 0), true, 1);
		tracker.observe(first, poseAt(0, 0, 0), true, 1);
		tracker.observe(second, poseAt(0, 0, 0), true, 1);

		tracker.refresh(20, (endpoint, ignored) -> {
			SubLevelAccess structure = endpoint.key().equals(first.key()) ? firstStructure
				: endpoint.key().equals(second.key()) ? secondStructure : null;
			return new RadioTopologyTracker.ResolvedPose(SubLevelCompat.toWorld(structure,
				Vec3.atCenterOf(endpoint.address().localPos())),
				Optional.ofNullable(structure).map(SubLevelAccess::getUniqueId));
		});

		assertEquals(new Vec3(40.5, .5, .5), tracker.frame().sample(outer.key()).worldCenter());
		assertVecEquals(new Vec3(40.5, .5, -.5), tracker.frame().sample(first.key()).worldCenter());
		assertVecEquals(new Vec3(-15.5, .5, -.5), tracker.frame().sample(second.key()).worldCenter());
	}

	@Test
	void unloadImmediatelyPublishesUnavailableWithoutTakingANewPose() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint endpoint = endpoint(1, id(30), 0, 0, 0, 1);
		tracker.observe(endpoint, poseAt(0, 0, 0), true, 1);
		tracker.refresh(20, resolverAt(80, 0, 0));

		tracker.observe(endpoint, poseAt(900, 0, 0), false, 21);

		assertEquals(20, tracker.frame().sampleTick());
		assertFalse(tracker.frame().sample(endpoint.key()).loaded());
		assertEquals(new Vec3(80.5, .5, .5), tracker.frame().sample(endpoint.key()).worldCenter());
	}

	@Test
	void generationReplacementRemovesOldPoseUntilTheNextCompleteFrame() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint first = endpoint(1, id(40), 0, 0, 0, 1);
		RadioEndpoint replacement = endpoint(1, id(40), 0, 0, 0, 2);
		tracker.observe(first, poseAt(0, 0, 0), true, 1);
		tracker.refresh(20, resolverAt(10, 0, 0));

		tracker.observe(replacement, poseAt(0, 0, 0), true, 21);

		assertTrue(tracker.frame().find(replacement.key()).isEmpty());
		tracker.refresh(40, resolverAt(20, 0, 0));
		assertEquals(2, tracker.frame().sample(replacement.key()).generation());
		assertEquals(new Vec3(20.5, .5, .5),
			tracker.frame().sample(replacement.key()).worldCenter());
	}

	@Test
	void disabledModeUsesInvariantLocalCoordinatesWithoutRetainingDynamicPoints() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(false);
		RadioEndpoint endpoint = endpoint(1, id(50), 2, 3, 4, 1);
		tracker.observe(endpoint, poseAt(500, 0, 0), true, 1);

		tracker.refresh(20, resolverAt(700, 0, 0));

		RadioTopologyFrame.Sample sample = tracker.frame().sample(endpoint.key());
		assertFalse(tracker.frame().dynamic());
		assertEquals(Vec3.atCenterOf(endpoint.address().localPos()), sample.worldCenter());
		assertEquals(Optional.of(id(50)), sample.physicalStructureId());
	}

	@Test
	void disabledOuterWorldSampleHasNoPhysicalStructureIdentity() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(false);
		RadioEndpoint endpoint = endpoint(1, null, 2, 3, 4, 1);

		tracker.observe(endpoint, poseAt(500, 0, 0), true, 1);

		assertEquals(Optional.empty(), tracker.frame().sample(endpoint.key()).physicalStructureId());
		assertEquals(Vec3.atCenterOf(endpoint.address().localPos()),
			tracker.frame().sample(endpoint.key()).worldCenter());
	}

	@Test
	void unchangedObservationDoesNotRepublishTheCurrentFrame() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint endpoint = endpoint(1, null, 0, 0, 0, 1);
		RadioTopologyTracker.Pose pose = poseAt(0, 0, 0);
		tracker.observe(endpoint, pose, true, 1);
		tracker.refresh(20, resolverAt(8, 0, 0));
		RadioTopologyFrame published = tracker.frame();

		tracker.observe(endpoint, pose, true, 21);
		tracker.refresh(21, resolverAt(900, 0, 0));

		assertSame(published, tracker.frame());
		assertEquals(20, tracker.frame().sampleTick());
	}

	@Test
	void frequencyReplacementImmediatelyInvalidatesThePreviousDynamicSample() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint original = endpoint(1, null, 0, 0, 0, 1);
		RadioEndpoint replacement = endpoint(1, null, 0, 0, 0, 1,
			CommunicationFrequency.of("replaced"));
		tracker.observe(original, poseAt(0, 0, 0), true, 1);
		tracker.refresh(20, resolverAt(8, 0, 0));

		tracker.observe(replacement, poseAt(0, 0, 0), true, 21);

		assertTrue(tracker.frame().find(replacement.key()).isEmpty());
		tracker.refresh(40, resolverAt(12, 0, 0));
		assertEquals(new Vec3(12.5, .5, .5),
			tracker.frame().sample(replacement.key()).worldCenter());
	}

	@Test
	void atomicReplacementPublishesOneDynamicFrameWithoutGivingTheNewEndpointAnOldPose() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint original = endpoint(1, null, 0, 0, 0, 1);
		RadioEndpoint replacement = endpoint(2, null, 1, 0, 0, 1);
		tracker.observe(original, poseAt(0, 0, 0), true, 1);
		tracker.refresh(20, resolverAt(8, 0, 0));
		RadioTopologyFrame before = tracker.frame();

		tracker.replace(List.of(original), replacement, poseAt(0, 0, 0), 21);

		assertFalse(tracker.frame() == before);
		assertEquals(20, tracker.frame().sampleTick());
		assertTrue(tracker.frame().samples().isEmpty());
		tracker.refresh(40, resolverAt(12, 0, 0));
		assertEquals(new Vec3(13.5, .5, .5),
			tracker.frame().sample(replacement.key()).worldCenter());
	}

	@Test
	void initialAndNegativeTicksFollowTheSameTwentyTickCadence() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint endpoint = endpoint(1, null, 0, 0, 0, 1);
		tracker.observe(endpoint, poseAt(0, 0, 0), true, -21);

		tracker.refresh(-20, resolverAt(12, 0, 0));
		assertEquals(-20, tracker.frame().sampleTick());
		assertEquals(new Vec3(12.5, .5, .5), tracker.frame().sample(endpoint.key()).worldCenter());
		RadioTopologyFrame sampled = tracker.frame();
		tracker.refresh(-1, resolverAt(8, 0, 0));
		assertSame(sampled, tracker.frame());
	}

	@Test
	void acceptsAFrameAfterSignedTickOverflowWithoutComparingTicks() {
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		RadioEndpoint endpoint = endpoint(1, null, 0, 0, 0, 1);
		tracker.observe(endpoint, poseAt(0, 0, 0), true, 1);
		long beforeOverflow = Long.MAX_VALUE - Math.floorMod(Long.MAX_VALUE, 20);
		long afterOverflow = Long.MIN_VALUE;
		while (Math.floorMod(afterOverflow, 20) != 0) afterOverflow++;

		tracker.refresh(beforeOverflow, resolverAt(1, 0, 0));
		tracker.refresh(afterOverflow, resolverAt(2, 0, 0));

		assertEquals(afterOverflow, tracker.frame().sampleTick());
		assertEquals(new Vec3(2.5, .5, .5), tracker.frame().sample(endpoint.key()).worldCenter());
	}

	@Test
	void rejectsBackwardTrackerAndLifecycleTicksBeforeMutatingState() {
		RadioEndpoint endpoint = endpoint(1, null, 0, 0, 0, 1);
		RadioTopologyTracker tracker = new RadioTopologyTracker(true);
		tracker.observe(endpoint, poseAt(0, 0, 0), true, 20);
		tracker.refresh(40, resolverAt(1, 0, 0));
		RadioTopologyFrame frame = tracker.frame();

		assertThrows(IllegalArgumentException.class,
			() -> tracker.refresh(20, resolverAt(2, 0, 0)));
		assertSame(frame, tracker.frame());

		RadioTopologyLifecycle lifecycle = new RadioTopologyLifecycle(true);
		RadioTopologyLifecycle.Registration registration =
			new RadioTopologyLifecycle.Registration(id(77), endpoint.memberId());
		lifecycle.observe(registration, endpoint, poseAt(0, 0, 0), 40);
		assertThrows(IllegalArgumentException.class,
			() -> lifecycle.unload(registration, 20));
		assertTrue(lifecycle.owns(registration, endpoint),
			"a rejected stale unload must not retire the current owner");
	}

	private static RadioTopologyTracker.Pose poseAt(double x, double y, double z) {
		return new RadioTopologyTracker.Pose(Optional.of(id(999)), new Vec3(x, y, z));
	}

	private static RadioTopologyTracker.ProjectionResolver resolverAt(double x, double y, double z) {
		return (endpoint, pose) -> new RadioTopologyTracker.ResolvedPose(
			Vec3.atCenterOf(endpoint.address().localPos()).add(x, y, z),
			Optional.ofNullable(endpoint.address().subLevelId()));
	}

	private static RadioTopologyTracker.ProjectionResolver resolverUsing(SubLevelAccess structure) {
		return (endpoint, ignored) -> new RadioTopologyTracker.ResolvedPose(
			SubLevelCompat.toWorld(structure, Vec3.atCenterOf(endpoint.address().localPos())),
			Optional.of(structure.getUniqueId()));
	}

	private static RadioEndpoint endpoint(long member, UUID structure, int x, int y, int z,
		long generation) {
		return endpoint(member, structure, x, y, z, generation, CommunicationFrequency.of("radio"));
	}

	private static RadioEndpoint endpoint(long member, UUID structure, int x, int y, int z,
		long generation, CommunicationFrequency frequency) {
		return new RadioEndpoint(id(1), id(member), new SpaceAddress(Level.OVERWORLD, structure,
			new BlockPos(x, y, z)), generation, frequency, 1, Set.of());
	}

	private static SubLevelAccess translatedRotatedSubLevel(UUID id, double x, double y, double z) {
		Object pose = translatedRotatedPose(x, y, z);
		return (SubLevelAccess) Proxy.newProxyInstance(SubLevelAccess.class.getClassLoader(),
			new Class<?>[] {SubLevelAccess.class}, (proxy, method, arguments) -> switch (method.getName()) {
				case "logicalPose", "lastPose" -> pose;
				case "boundingBox" -> new BoundingBox3d(0, 0, 0, 16, 16, 16);
				case "getUniqueId" -> id;
				case "getName" -> "radio-topology-test";
				default -> throw new UnsupportedOperationException(method.getName());
			});
	}

	private static Object translatedRotatedPose(double x, double y, double z) {
		try {
			Class<?> vector = Class.forName("org.joml.Vector3d");
			Class<?> quaternion = Class.forName("org.joml.Quaterniond");
			Object position = vector.getConstructor(double.class, double.class, double.class)
				.newInstance(x, y, z);
			Object orientation = quaternion.getConstructor().newInstance();
			quaternion.getMethod("rotateY", double.class).invoke(orientation, Math.PI / 2);
			return Class.forName("dev.ryanhcode.sable.companion.math.Pose3d")
				.getConstructor(vector, quaternion, vector, vector)
				.newInstance(position, orientation, vector.getConstructor().newInstance(),
					vector.getConstructor(double.class, double.class, double.class)
						.newInstance(1, 1, 1));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private static void assertVecEquals(Vec3 expected, Vec3 actual) {
		assertEquals(expected.x, actual.x, 1.0e-9);
		assertEquals(expected.y, actual.y, 1.0e-9);
		assertEquals(expected.z, actual.z, 1.0e-9);
	}
}
