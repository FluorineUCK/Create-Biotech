package com.nobodiiiii.createbiotech.content.factorycluster.orchestration.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.nobodiiiii.createbiotech.content.biocomputing.spatial.SpaceAddress;
import com.nobodiiiii.createbiotech.content.biocomputing.network.CommunicationFrequency;
import com.nobodiiiii.createbiotech.content.biocomputing.application.production.resource.ProductionCapability;
import com.nobodiiiii.createbiotech.foundation.utility.SubLevelCompat;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic moving-structure acceptance matrix. The mutable fixture deliberately crosses the
 * live-Sable boundary only through {@link SubLevelAccess#logicalPose()} before the tracker freezes
 * a value-only frame.
 */
class AeronauticsTopologyMatrixTest {
	private static final CommunicationFrequency FREQUENCY = CommunicationFrequency.of("air-matrix");

	@Test
	void samplesWorldAndRotatedStructuresOnlyAtTwentyTickBoundaries() {
		MutableSubLevel firstStructure = new MutableSubLevel(id(11));
		MutableSubLevel secondStructure = new MutableSubLevel(id(12));
		firstStructure.setLogicalPose(32, 0, 0, 0);
		secondStructure.setLogicalPose(65, 0, 1, Math.PI);
		Matrix matrix = new Matrix(true, firstStructure, secondStructure);
		RadioEndpoint world = endpoint(1, 1, null, 0);
		RadioEndpoint first = endpoint(2, 2, firstStructure.id(), 0);
		RadioEndpoint second = endpoint(3, 3, secondStructure.id(), 0);
		matrix.observe(world, first, second);

		matrix.refresh(19);
		assertTrue(matrix.frame().samples().isEmpty(), "tick 19 must not publish a partial pose frame");
		matrix.refresh(20);
		assertEquals(20, matrix.frame().sampleTick());
		assertVecEquals(new Vec3(32.5, .5, .5), matrix.frame().sample(first.key()).worldCenter());
		assertVecEquals(new Vec3(64.5, .5, .5), matrix.frame().sample(second.key()).worldCenter());
		assertEquals(RadioLinkPolicy.LinkResult.RADIO, matrix.link(world, first),
			"the normal-range equality boundary must be reachable");
		assertEquals(RadioLinkPolicy.LinkResult.RADIO, matrix.link(first, second),
			"rotated structure-to-structure endpoints must use their current world poses");

		firstStructure.setLogicalPose(33, 0, 0, 0);
		secondStructure.setLogicalPose(67, 0, 1, Math.PI);
		RadioTopologyFrame tickTwenty = matrix.frame();
		matrix.refresh(39);
		assertSame(tickTwenty, matrix.frame(), "between boundaries workers must retain one complete frame");
		assertTrue(matrix.link(world, first).reachable(),
			"a moved endpoint must not alter the already-published tick-20 frame");
		matrix.refresh(40);
		assertEquals(40, matrix.frame().sampleTick());
		assertVecEquals(new Vec3(33.5, .5, .5), matrix.frame().sample(first.key()).worldCenter());
		assertVecEquals(new Vec3(66.5, .5, .5), matrix.frame().sample(second.key()).worldCenter());
		assertFalse(matrix.link(world, first).reachable(),
			"normal-range overflow by one block must be unavailable");
		assertFalse(matrix.link(first, second).reachable(),
			"rotated structure-to-structure overflow by one block must be unavailable");

		firstStructure.setLogicalPose(32, 0, 0, 0);
		secondStructure.setLogicalPose(65, 0, 1, Math.PI);
		matrix.refresh(60);
		assertEquals(60, matrix.frame().sampleTick());
		assertTrue(matrix.link(world, first).reachable());
		assertTrue(matrix.link(first, second).reachable());
	}

	@Test
	void movingRelayRouteLossWaitsAndAnUnloadCanThenBeFullyRetired() {
		MutableSubLevel relayStructure = new MutableSubLevel(id(21));
		relayStructure.setLogicalPose(32, 0, 0, 0);
		Matrix matrix = new Matrix(true, relayStructure);
		RadioEndpoint source = endpoint(1, 1, null, 0);
		RadioEndpoint relay = endpoint(2, 2, relayStructure.id(), 0);
		RadioEndpoint target = endpoint(3, 3, null, 64);
		matrix.observe(source, relay, target);
		matrix.refresh(20);

		RadioRouter router = new RadioRouter(32, 64, 8);
		assertTrue(router.register(source));
		assertTrue(router.register(relay));
		assertTrue(router.register(target));
		RadioEnvelope envelope = RadioEnvelope.create(id(90), source, target, FREQUENCY,
			new ProcessProtocolMessage.Data("one-hop-per-tick"));
		router.submit(envelope, List.of(relay.hop()));
		assertEquals(relay.key(), router.tick(20, matrix.frame()).deliveries().getFirst().recipient());
		assertEquals(1, router.listenerOccupancy(relay.key()));

		relayStructure.setLogicalPose(97, 0, 0, 0);
		matrix.refresh(40);
		assertTrue(router.tick(41, matrix.frame()).deliveries().isEmpty());
		assertEquals(RadioRouter.RouteStatus.WAITING_ROUTE, router.status(envelope.messageId()));
		assertEquals(new RadioRouter.RouteProgress(envelope.advanceHop(), 2, Optional.empty()),
			router.routeProgress(envelope.messageId()),
			"route loss must retain the already-delivered relay hop rather than replaying it");
		assertEquals(0, router.listenerOccupancy(relay.key()));

		assertEquals(List.of(relay), matrix.lifecycle().unload(relay.memberId(), relay.address(), 41));
		assertFalse(matrix.frame().sample(relay.key()).loaded(),
			"unload must immediately withdraw the old moving point without another pose read");
		assertEquals(List.of(relay), matrix.lifecycle().retire(relay.memberId(), relay.address()),
			"disassembly after unload must fully retire the stale unavailable observation");
		assertTrue(matrix.frame().find(relay.key()).isEmpty());
		matrix.refresh(60);
		assertTrue(matrix.frame().find(relay.key()).isEmpty(),
			"a disassembled relay must not reappear at the next sampling boundary");
	}

	@Test
	void disabledTopologyUsesStaticCoordinatesWithoutReadingMutableSablePoses() {
		MutableSubLevel firstStructure = new MutableSubLevel(id(31));
		MutableSubLevel otherStructure = new MutableSubLevel(id(32));
		firstStructure.setLogicalPose(999, 0, 0, Math.PI / 2);
		otherStructure.setLogicalPose(-999, 0, 0, Math.PI / 2);
		Matrix matrix = new Matrix(false, firstStructure, otherStructure);
		RadioEndpoint first = endpoint(1, 1, firstStructure.id(), 0);
		RadioEndpoint sameStructureAtBoundary = endpoint(2, 2, firstStructure.id(), 32);
		RadioEndpoint otherStructureAtBoundary = endpoint(3, 3, otherStructure.id(), 32);
		matrix.observe(first, sameStructureAtBoundary, otherStructureAtBoundary);

		AtomicInteger livePoseReads = new AtomicInteger();
		matrix.lifecycle().refresh(20, (endpoint, ignored) -> {
			livePoseReads.incrementAndGet();
			return matrix.resolve(endpoint);
		});

		assertFalse(matrix.frame().dynamic());
		assertEquals(0, livePoseReads.get(),
			"disabled topology must not dereference a mutable Sable logical pose");
		assertVecEquals(new Vec3(.5, .5, .5), matrix.frame().sample(first.key()).worldCenter());
		assertVecEquals(new Vec3(32.5, .5, .5),
			matrix.frame().sample(sameStructureAtBoundary.key()).worldCenter());
		assertTrue(matrix.link(first, sameStructureAtBoundary).reachable(),
			"static local range equality inside one physical structure remains reachable");
		assertFalse(matrix.link(first, otherStructureAtBoundary).reachable(),
			"disabled topology must not form a cross-structure physical link");
	}

	@Test
	void extendedRadioReachesExactlySixtyFourBlocksButNotSixtyFive() {
		Matrix matrix = new Matrix(true);
		RadioEndpoint origin = endpoint(1, 1, null, 0);
		RadioEndpoint atBoundary = endpoint(2, 2, null, 64,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		RadioEndpoint beyondBoundary = endpoint(3, 3, null, 65,
			Set.of(ProductionCapability.EXTENDED_RADIO));
		matrix.observe(origin, atBoundary, beyondBoundary);
		matrix.refresh(20);

		assertEquals(RadioLinkPolicy.LinkResult.RADIO, matrix.link(origin, atBoundary));
		assertEquals(RadioLinkPolicy.LinkResult.UNAVAILABLE, matrix.link(origin, beyondBoundary));
	}

	private static RadioEndpoint endpoint(long cluster, long member, UUID structure, int x) {
		return endpoint(cluster, member, structure, x, Set.of());
	}

	private static RadioEndpoint endpoint(long cluster, long member, UUID structure, int x,
		Set<ProductionCapability> capabilities) {
		return new RadioEndpoint(id(cluster), id(member), new SpaceAddress(Level.OVERWORLD, structure,
			new BlockPos(x, 0, 0)), 1, FREQUENCY, 1, capabilities);
	}

	private static RadioTopologyTracker.Pose pose(RadioEndpoint endpoint) {
		return new RadioTopologyTracker.Pose(Optional.ofNullable(endpoint.address().subLevelId()),
			Vec3.atCenterOf(endpoint.address().localPos()));
	}

	private static UUID id(long value) {
		return new UUID(0, value);
	}

	private static void assertVecEquals(Vec3 expected, Vec3 actual) {
		assertEquals(expected.x, actual.x, 1.0e-9);
		assertEquals(expected.y, actual.y, 1.0e-9);
		assertEquals(expected.z, actual.z, 1.0e-9);
	}

	private static final class Matrix {
		private final RadioTopologyLifecycle lifecycle;
		private final RadioLinkPolicy links = new RadioLinkPolicy(32, 64);
		private final Map<UUID, MutableSubLevel> structures = new LinkedHashMap<>();

		private Matrix(boolean dynamic, MutableSubLevel... sublevels) {
			lifecycle = new RadioTopologyLifecycle(dynamic);
			for (MutableSubLevel sublevel : sublevels) structures.put(sublevel.id(), sublevel);
		}

		private RadioTopologyLifecycle lifecycle() {
			return lifecycle;
		}

		private void observe(RadioEndpoint... endpoints) {
			for (RadioEndpoint endpoint : endpoints) lifecycle.observe(endpoint, pose(endpoint), 1);
		}

		private void refresh(long tick) {
			lifecycle.refresh(tick, (endpoint, ignored) -> resolve(endpoint));
		}

		private RadioTopologyTracker.ResolvedPose resolve(RadioEndpoint endpoint) {
			MutableSubLevel sublevel = endpoint.address().subLevelId() == null ? null
				: structures.get(endpoint.address().subLevelId());
			if (endpoint.address().subLevelId() != null && sublevel == null) return null;
			return new RadioTopologyTracker.ResolvedPose(SubLevelCompat.toWorld(
				sublevel == null ? null : sublevel.access(),
				Vec3.atCenterOf(endpoint.address().localPos())),
				Optional.ofNullable(sublevel).map(MutableSubLevel::id));
		}

		private RadioTopologyFrame frame() {
			return lifecycle.frame();
		}

		private RadioLinkPolicy.LinkResult link(RadioEndpoint first, RadioEndpoint second) {
			return links.link(first, second, frame());
		}
	}

	/** Mutable test double for the one Sable method the production projection may read. */
	private static final class MutableSubLevel {
		private final UUID id;
		private final SubLevelAccess access;
		private Object logicalPose;

		private MutableSubLevel(UUID id) {
			this.id = id;
			logicalPose = pose3d(0, 0, 0, 0);
			access = (SubLevelAccess) Proxy.newProxyInstance(SubLevelAccess.class.getClassLoader(),
				new Class<?>[] {SubLevelAccess.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "logicalPose", "lastPose" -> logicalPose;
					case "boundingBox" -> new BoundingBox3d(0, 0, 0, 16, 16, 16);
					case "getUniqueId" -> id;
					case "getName" -> "mutable-aeronautics-topology";
					default -> throw new UnsupportedOperationException(method.getName());
				});
		}

		private UUID id() {
			return id;
		}

		private SubLevelAccess access() {
			return access;
		}

		private void setLogicalPose(double x, double y, double z, double yawRadians) {
			logicalPose = pose3d(x, y, z, yawRadians);
		}
	}

	private static Object pose3d(double x, double y, double z, double yawRadians) {
		try {
			Class<?> vector = Class.forName("org.joml.Vector3d");
			Class<?> quaternion = Class.forName("org.joml.Quaterniond");
			Object position = vector.getConstructor(double.class, double.class, double.class)
				.newInstance(x, y, z);
			Object orientation = quaternion.getConstructor().newInstance();
			quaternion.getMethod("rotateY", double.class).invoke(orientation, yawRadians);
			return Class.forName("dev.ryanhcode.sable.companion.math.Pose3d")
				.getConstructor(vector, quaternion, vector, vector)
				.newInstance(position, orientation, vector.getConstructor().newInstance(),
					vector.getConstructor(double.class, double.class, double.class).newInstance(1, 1, 1));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
