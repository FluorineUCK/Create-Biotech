package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTableLayout;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalTablePlane;

import net.minecraft.world.phys.Vec3;

/** Deterministic client geometry helpers; no result is trusted without server validation. */
public final class SurgicalClientTopology {
	public static final int[][] CUBE_FACES = {
		{0, 4, 6, 2}, {1, 3, 7, 5},
		{0, 1, 5, 4}, {2, 6, 7, 3},
		{0, 2, 3, 1}, {4, 5, 7, 6}
	};
	private static final double COMPONENT_OFFSET = 1.0d / 16.0d;
	private static final double LAYOUT_QUANTUM = 1.0d / 1024.0d;
	private static final double OUTER_RENDER_INFLATION = 0.1d / 16.0d;
	private static final double SEPARATION_GAP = 1.0d / 1024.0d;
	private static final int MAX_LAYOUT_SEARCH_NODES = 8192;
	private static final double DISTANCE_EPSILON = 1.0e-9d;
	private static final double INTERSECTION_EPSILON = 1.0e-12d;
	private static final double DEGENERATE_EPSILON = 1.0e-18d;
	private static final double POLYHEDRON_EPSILON = 1.0e-8d;
	private static final double VERTEX_MERGE_DISTANCE_SQR = 1.0e-14d;
	private static final double CONTACT_TOLERANCE = 1.0d / 64.0d;
	private static final double MIN_CONTACT_AREA = 1.0e-8d;

	private SurgicalClientTopology() {}

	/** Builds one stable seam for every intersecting or tolerance-adjacent pair of model cubes. */
	public static ContactTopology buildContactTopology(int cubeCount,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		if (!SurgicalAssembly.validCubeCount(cubeCount))
			return ContactTopology.EMPTY;
		Map<Integer, PreparedCube> byId = preparedById(cubes);
		if (byId.size() != cubeCount)
			return ContactTopology.EMPTY;

		// Broad-phase sweep avoids clipping every possible pair. Large models usually
		// contain many distant decorative cubes, so only AABBs close enough to touch
		// advance to the comparatively expensive oriented-face clipping below.
		List<PreparedCube> sweep = new ArrayList<>(byId.values());
		sweep.sort(Comparator.comparingDouble((PreparedCube cube) -> cube.bounds.minX)
			.thenComparingInt(cube -> cube.geometry.cubeId()));
		List<BitSet> candidates = new ArrayList<>(cubeCount);
		for (int cube = 0; cube < cubeCount; cube++)
			candidates.add(new BitSet(cubeCount));
		for (int firstIndex = 0; firstIndex < sweep.size(); firstIndex++) {
			PreparedCube first = sweep.get(firstIndex);
			for (int secondIndex = firstIndex + 1; secondIndex < sweep.size(); secondIndex++) {
				PreparedCube second = sweep.get(secondIndex);
				if (second.bounds.minX > first.bounds.maxX + CONTACT_TOLERANCE)
					break;
				if (!first.bounds.overlapsWithin(second.bounds, CONTACT_TOLERANCE))
					continue;
				int lower = Math.min(first.geometry.cubeId(), second.geometry.cubeId());
				int upper = Math.max(first.geometry.cubeId(), second.geometry.cubeId());
				candidates.get(lower).set(upper);
			}
		}

		List<SurgicalAssembly.Seam> seams = new ArrayList<>();
		List<Contact> contacts = new ArrayList<>();
		for (int first = 0; first < cubeCount && seams.size() < SurgicalAssembly.MAX_SEAMS; first++) {
			for (int second = candidates.get(first).nextSetBit(first + 1);
				second >= 0 && seams.size() < SurgicalAssembly.MAX_SEAMS;
				second = candidates.get(first).nextSetBit(second + 1)) {
				SurgicalAssembly.Seam seam = SurgicalAssembly.Seam.of(first, second);
				Contact contact = contactBetween(seam, byId);
				if (contact == null)
					continue;
				seams.add(seam);
				contacts.add(contact);
			}
		}
		return new ContactTopology(seams, contacts);
	}

	public static List<Contact> contactsFor(List<SurgicalAssembly.Seam> seams,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		Map<Integer, PreparedCube> byId = preparedById(cubes);
		List<Contact> contacts = new ArrayList<>(seams.size());
		for (SurgicalAssembly.Seam seam : seams) {
			Contact contact = contactBetween(seam, byId);
			if (contact != null)
				contacts.add(contact);
		}
		return List.copyOf(contacts);
	}

	@Nullable
	public static Contact contactBetween(SurgicalAssembly.Seam seam,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		return contactBetween(seam, preparedById(cubes));
	}

	@Nullable
	private static Contact contactBetween(SurgicalAssembly.Seam seam,
		Map<Integer, PreparedCube> byId) {
		PreparedCube first = byId.get(seam.first());
		PreparedCube second = byId.get(seam.second());
		if (first == null || second == null)
			return null;

		PreparedCube smaller = first.volume <= second.volume ? first : second;
		PreparedCube other = smaller == first ? second : first;
		List<Plane> intersectionPlanes = new ArrayList<>(first.planes.size() + second.planes.size());
		intersectionPlanes.addAll(first.planes);
		intersectionPlanes.addAll(second.planes);
		List<List<Vec3>> intersectionFaces = convexIntersectionFaces(intersectionPlanes);
		if (!intersectionFaces.isEmpty())
			return new Contact(seam, smaller.geometry.cubeId(), intersectionFaces);

		// Keep near-adjacent model parts connected. This is deliberately a planar
		// fallback: only real overlap is represented by the complete intersection solid.
		List<Vec3> bestContact = List.of();
		double bestCenterDistance = Double.MAX_VALUE;
		double bestArea = 0.0d;
		for (int[] indices : CUBE_FACES) {
			List<Vec3> face = List.of(smaller.geometry.corners().get(indices[0]),
				smaller.geometry.corners().get(indices[1]), smaller.geometry.corners().get(indices[2]),
				smaller.geometry.corners().get(indices[3]));
			List<Vec3> contact = clipAgainstPlanes(face, other.planes, CONTACT_TOLERANCE);
			double area = polygonArea(contact);
			if (area < MIN_CONTACT_AREA)
				continue;
			double centerDistance = faceCenter(face).distanceToSqr(other.center);
			if (centerDistance < bestCenterDistance - DISTANCE_EPSILON
				|| Math.abs(centerDistance - bestCenterDistance) <= DISTANCE_EPSILON
					&& area > bestArea + MIN_CONTACT_AREA) {
				bestCenterDistance = centerDistance;
				bestArea = area;
				bestContact = contact;
			}
		}
		return bestContact.isEmpty() ? null
			: new Contact(seam, smaller.geometry.cubeId(), List.of(bestContact));
	}

	/** Builds every boundary face of the convex polyhedron shared by all half-spaces. */
	private static List<List<Vec3>> convexIntersectionFaces(List<Plane> planes) {
		List<Vec3> vertices = new ArrayList<>();
		for (int first = 0; first < planes.size(); first++) {
			for (int second = first + 1; second < planes.size(); second++) {
				for (int third = second + 1; third < planes.size(); third++) {
					Vec3 vertex = intersect(planes.get(first), planes.get(second), planes.get(third));
					if (vertex == null || !insideAll(vertex, planes) || containsPoint(vertices, vertex))
						continue;
					vertices.add(vertex);
				}
			}
		}
		if (vertices.size() < 3)
			return List.of();

		List<List<Vec3>> faces = new ArrayList<>();
		Set<List<Integer>> uniqueFaces = new HashSet<>();
		for (Plane plane : planes) {
			List<Integer> onPlane = new ArrayList<>();
			for (int vertex = 0; vertex < vertices.size(); vertex++)
				if (Math.abs(plane.signedDistance(vertices.get(vertex))) <= POLYHEDRON_EPSILON)
					onPlane.add(vertex);
			if (onPlane.size() < 3)
				continue;

			List<Integer> ordered = orderFace(onPlane, vertices, plane.normal);
			ordered = removeCollinearVertices(ordered, vertices);
			if (ordered.size() < 3)
				continue;
			List<Vec3> polygon = new ArrayList<>(ordered.size());
			for (int vertex : ordered)
				polygon.add(vertices.get(vertex));
			if (polygonArea(polygon) < MIN_CONTACT_AREA)
				continue;

			List<Integer> faceKey = new ArrayList<>(ordered);
			faceKey.sort(Integer::compareTo);
			if (uniqueFaces.add(List.copyOf(faceKey)))
				faces.add(List.copyOf(polygon));
		}
		return List.copyOf(faces);
	}

	@Nullable
	private static Vec3 intersect(Plane first, Plane second, Plane third) {
		Vec3 secondCrossThird = second.normal.cross(third.normal);
		double determinant = first.normal.dot(secondCrossThird);
		if (Math.abs(determinant) <= INTERSECTION_EPSILON)
			return null;
		return secondCrossThird.scale(first.maximum)
			.add(third.normal.cross(first.normal).scale(second.maximum))
			.add(first.normal.cross(second.normal).scale(third.maximum))
			.scale(1.0d / determinant);
	}

	private static boolean insideAll(Vec3 point, List<Plane> planes) {
		for (Plane plane : planes)
			if (plane.signedDistance(point) > POLYHEDRON_EPSILON)
				return false;
		return true;
	}

	private static boolean containsPoint(List<Vec3> points, Vec3 candidate) {
		for (Vec3 point : points)
			if (point.distanceToSqr(candidate) <= VERTEX_MERGE_DISTANCE_SQR)
				return true;
		return false;
	}

	private static List<Integer> orderFace(List<Integer> face, List<Vec3> vertices, Vec3 normal) {
		Vec3 center = Vec3.ZERO;
		for (int vertex : face)
			center = center.add(vertices.get(vertex));
		center = center.scale(1.0d / face.size());
		final Vec3 faceCenter = center;

		Vec3 axisU = vertices.get(face.getFirst()).subtract(faceCenter);
		if (axisU.lengthSqr() <= DEGENERATE_EPSILON)
			return List.of();
		axisU = axisU.normalize();
		final Vec3 faceAxisU = axisU;
		final Vec3 faceAxisV = normal.cross(faceAxisU).normalize();
		List<Integer> ordered = new ArrayList<>(face);
		ordered.sort(Comparator.comparingDouble(vertex -> {
			Vec3 relative = vertices.get(vertex).subtract(faceCenter);
			return Math.atan2(relative.dot(faceAxisV), relative.dot(faceAxisU));
		}));

		Vec3 polygonNormal = vertices.get(ordered.get(1)).subtract(vertices.get(ordered.getFirst()))
			.cross(vertices.get(ordered.get(2)).subtract(vertices.get(ordered.getFirst())));
		if (polygonNormal.dot(normal) < 0.0d)
			java.util.Collections.reverse(ordered);
		return ordered;
	}

	private static List<Integer> removeCollinearVertices(List<Integer> face, List<Vec3> vertices) {
		List<Integer> simplified = new ArrayList<>(face);
		boolean changed;
		do {
			changed = false;
			for (int current = 0; current < simplified.size() && simplified.size() > 3; current++) {
				Vec3 previousPoint = vertices.get(simplified.get((current + simplified.size() - 1)
					% simplified.size()));
				Vec3 currentPoint = vertices.get(simplified.get(current));
				Vec3 nextPoint = vertices.get(simplified.get((current + 1) % simplified.size()));
				if (currentPoint.subtract(previousPoint).cross(nextPoint.subtract(currentPoint))
					.lengthSqr() > VERTEX_MERGE_DISTANCE_SQR)
					continue;
				simplified.remove(current);
				changed = true;
				break;
			}
		} while (changed);
		return List.copyOf(simplified);
	}

	public static Map<Integer, Vec3> componentOffsets(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet cutSeams,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		if (!SurgicalAssembly.validTopology(cubeCount, seams) || cutSeams.isEmpty())
			return Map.of();
		List<BitSet> components = SurgicalAssembly.components(cubeCount, presentCubes, seams, cutSeams);
		if (components.size() <= 1)
			return Map.of();

		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId = byId(cubes);
		Vec3 mainCenter = componentCenter(components.getFirst(), byId);
		Map<Integer, Vec3> offsets = new HashMap<>();
		for (int componentId = 1; componentId < components.size(); componentId++) {
			BitSet component = components.get(componentId);
			Vec3 direction = componentCenter(component, byId).subtract(mainCenter);
			if (direction.lengthSqr() < 1.0e-9d)
				direction = new Vec3(0.0d, 1.0d, 0.0d);
			Vec3 offset = direction.normalize().scale(COMPONENT_OFFSET);
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1))
				offsets.put(cube, offset);
		}
		return Map.copyOf(offsets);
	}

	/** Replays cuts in order and lays every separated component out without overlap. */
	public static Map<Integer, Vec3> componentOffsets(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet cutSeams,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, List<Integer> cutOrder, Vec3 tableCenter) {
		if (!SurgicalAssembly.validTopology(cubeCount, seams) || cutSeams.isEmpty())
			return Map.of();

		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId = byId(cubes);
		if (byId.size() < presentCubes.cardinality())
			return Map.of();
		Map<Integer, Bounds> baseBounds = new HashMap<>();
		for (Map.Entry<Integer, SurgicalModelRenderContext.CubeGeometry> entry : byId.entrySet())
			baseBounds.put(entry.getKey(), Bounds.of(entry.getValue()).inflate(OUTER_RENDER_INFLATION));

		List<Integer> normalizedOrder = SurgicalAssembly.normalizeCutOrder(cutOrder, cutSeams, seams.size());
		BitSet appliedCuts = new BitSet(seams.size());
		Map<Integer, Vec3> offsets = new HashMap<>();
		for (int seamId : normalizedOrder) {
			appliedCuts.set(seamId);
			SurgicalAssembly.Seam seam = seams.get(seamId);
			if (!presentCubes.get(seam.first()) || !presentCubes.get(seam.second()))
				continue;

			BitSet firstComponent = SurgicalAssembly.componentContaining(cubeCount, presentCubes,
				seams, appliedCuts, seam.first());
			BitSet secondComponent = SurgicalAssembly.componentContaining(cubeCount, presentCubes,
				seams, appliedCuts, seam.second());
			if (firstComponent.isEmpty() || secondComponent.isEmpty() || firstComponent.equals(secondComponent))
				continue;

			Vec3 firstCenter = componentCenter(firstComponent, byId, offsets);
			Vec3 secondCenter = componentCenter(secondComponent, byId, offsets);
			double firstDistance = firstCenter.distanceToSqr(tableCenter);
			double secondDistance = secondCenter.distanceToSqr(tableCenter);
			Vec3 firstCubeCenter = center(byId.get(seam.first())).add(offsets.getOrDefault(seam.first(), Vec3.ZERO));
			Vec3 secondCubeCenter = center(byId.get(seam.second())).add(offsets.getOrDefault(seam.second(), Vec3.ZERO));
			boolean moveFirst = firstDistance > secondDistance;
			if (Math.abs(firstDistance - secondDistance) < DISTANCE_EPSILON)
				moveFirst = firstCubeCenter.distanceToSqr(tableCenter)
					> secondCubeCenter.distanceToSqr(tableCenter);

			BitSet movingComponent = moveFirst ? firstComponent : secondComponent;
			Vec3 direction = moveFirst
				? firstCubeCenter.subtract(secondCubeCenter)
				: secondCubeCenter.subtract(firstCubeCenter);
			direction = new Vec3(direction.x, 0.0d, direction.z);
			if (direction.lengthSqr() < DISTANCE_EPSILON) {
				Vec3 componentDirection = (moveFirst ? firstCenter : secondCenter)
					.subtract(moveFirst ? secondCenter : firstCenter);
				direction = new Vec3(componentDirection.x, 0.0d, componentDirection.z);
			}
			if (direction.lengthSqr() < DISTANCE_EPSILON)
				direction = (seamId & 1) == 0 ? new Vec3(1.0d, 0.0d, 0.0d)
					: new Vec3(0.0d, 0.0d, 1.0d);

			Vec3 delta = findNonIntersectingOffset(movingComponent, presentCubes, baseBounds, offsets, direction);
			if (delta.lengthSqr() <= DISTANCE_EPSILON)
				continue;
			for (int cube = movingComponent.nextSetBit(0); cube >= 0;
				cube = movingComponent.nextSetBit(cube + 1))
				offsets.put(cube, offsets.getOrDefault(cube, Vec3.ZERO).add(delta));
		}
		offsets.entrySet().removeIf(entry -> entry.getValue().lengthSqr() <= DISTANCE_EPSILON);
		return Map.copyOf(offsets);
	}

	private static Vec3 findNonIntersectingOffset(BitSet moving, BitSet present,
		Map<Integer, Bounds> baseBounds, Map<Integer, Vec3> offsets, Vec3 direction) {
		int signX = direction.x > DISTANCE_EPSILON ? 1 : direction.x < -DISTANCE_EPSILON ? -1 : 0;
		int signZ = direction.z > DISTANCE_EPSILON ? 1 : direction.z < -DISTANCE_EPSILON ? -1 : 0;
		if (signX == 0 && signZ == 0)
			signX = 1;

		PriorityQueue<SearchPoint> frontier = new PriorityQueue<>(Comparator
			.comparingDouble(SearchPoint::distanceSqr)
			.thenComparingLong(SearchPoint::totalSteps)
			.thenComparingLong(SearchPoint::xSteps)
			.thenComparingLong(SearchPoint::zSteps));
		Set<SearchPoint> visited = new HashSet<>();
		frontier.add(new SearchPoint(0, 0));
		int examined = 0;
		while (!frontier.isEmpty() && examined++ < MAX_LAYOUT_SEARCH_NODES) {
			SearchPoint point = frontier.remove();
			if (!visited.add(point))
				continue;
			Vec3 candidate = point.offset(signX, signZ);
			Collision collision = firstCollision(moving, present, baseBounds, offsets, candidate);
			if (collision == null)
				return candidate;

			if (signX != 0) {
				// Clearance is measured from the leading face, not the trailing one.
				double extra = signX > 0
					? collision.obstacle.maxX + SEPARATION_GAP - collision.moving.minX
					: collision.moving.maxX + SEPARATION_GAP - collision.obstacle.minX;
				frontier.add(new SearchPoint(quantizedSteps(point.x() + Math.max(extra, LAYOUT_QUANTUM)),
					point.zSteps));
			}
			if (signZ != 0) {
				double extra = signZ > 0
					? collision.obstacle.maxZ + SEPARATION_GAP - collision.moving.minZ
					: collision.moving.maxZ + SEPARATION_GAP - collision.obstacle.minZ;
				frontier.add(new SearchPoint(point.xSteps,
					quantizedSteps(point.z() + Math.max(extra, LAYOUT_QUANTUM))));
			}
		}
		return fallbackOutsideAll(moving, present, baseBounds, offsets, direction, signX, signZ);
	}

	@Nullable
	private static Collision firstCollision(BitSet moving, BitSet present, Map<Integer, Bounds> baseBounds,
		Map<Integer, Vec3> offsets, Vec3 candidate) {
		for (int movingCube = moving.nextSetBit(0); movingCube >= 0;
			movingCube = moving.nextSetBit(movingCube + 1)) {
			Bounds source = baseBounds.get(movingCube);
			if (source == null)
				continue;
			Bounds moved = source.translate(offsets.getOrDefault(movingCube, Vec3.ZERO).add(candidate));
			for (int obstacleCube = present.nextSetBit(0); obstacleCube >= 0;
				obstacleCube = present.nextSetBit(obstacleCube + 1)) {
				if (moving.get(obstacleCube))
					continue;
				Bounds obstacle = baseBounds.get(obstacleCube);
				if (obstacle == null)
					continue;
				obstacle = obstacle.translate(offsets.getOrDefault(obstacleCube, Vec3.ZERO));
				if (moved.overlapsStrictly(obstacle))
					return new Collision(moved, obstacle);
			}
		}
		return null;
	}

	private static Vec3 fallbackOutsideAll(BitSet moving, BitSet present, Map<Integer, Bounds> baseBounds,
		Map<Integer, Vec3> offsets, Vec3 direction, int signX, int signZ) {
		Bounds movingBounds = unionBounds(moving, baseBounds, offsets);
		BitSet obstacles = (BitSet) present.clone();
		obstacles.andNot(moving);
		Bounds obstacleBounds = unionBounds(obstacles, baseBounds, offsets);
		if (movingBounds == null || obstacleBounds == null)
			return Vec3.ZERO;

		boolean useX = signX != 0 && (signZ == 0 || Math.abs(direction.x) >= Math.abs(direction.z));
		if (useX) {
			double distance = signX > 0
				? obstacleBounds.maxX + SEPARATION_GAP - movingBounds.minX
				: movingBounds.maxX + SEPARATION_GAP - obstacleBounds.minX;
			return new Vec3(signX * quantizeUp(Math.max(distance, 0.0d)), 0.0d, 0.0d);
		}
		double distance = signZ > 0
			? obstacleBounds.maxZ + SEPARATION_GAP - movingBounds.minZ
			: movingBounds.maxZ + SEPARATION_GAP - obstacleBounds.minZ;
		return new Vec3(0.0d, 0.0d, signZ * quantizeUp(Math.max(distance, 0.0d)));
	}

	@Nullable
	private static Bounds unionBounds(BitSet cubes, Map<Integer, Bounds> baseBounds, Map<Integer, Vec3> offsets) {
		Bounds result = null;
		for (int cube = cubes.nextSetBit(0); cube >= 0; cube = cubes.nextSetBit(cube + 1)) {
			Bounds bounds = baseBounds.get(cube);
			if (bounds == null)
				continue;
			bounds = bounds.translate(offsets.getOrDefault(cube, Vec3.ZERO));
			result = result == null ? bounds : result.union(bounds);
		}
		return result;
	}

	private static long quantizedSteps(double distance) {
		return Math.max(0L, (long) Math.ceil((distance - DISTANCE_EPSILON) / LAYOUT_QUANTUM));
	}

	private static double quantizeUp(double distance) {
		return quantizedSteps(distance) * LAYOUT_QUANTUM;
	}

	/** Centers a newly placed subject on the nearest fitting 1/4-block slot of the work area. */
	@Nullable
	public static PlacementPlan planInitialPlacement(List<SurgicalModelRenderContext.CubeGeometry> cubes,
		SurgicalTablePlane.WorkArea workArea) {
		Bounds bounds = null;
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes) {
			Bounds cubeBounds = Bounds.of(cube).inflate(OUTER_RENDER_INFLATION);
			bounds = bounds == null ? cubeBounds : bounds.union(cubeBounds);
		}
		if (bounds == null || workArea.isEmpty())
			return null;

		double targetX = (workArea.minX() + workArea.maxXExclusive()) * 0.5d;
		double targetZ = (workArea.minZ() + workArea.maxZExclusive()) * 0.5d;
		for (GridCell cell : orderedCells(workArea, targetX, targetZ)) {
			double offsetX = cell.centerX() - bounds.centerX();
			double offsetZ = cell.centerZ() - bounds.centerZ();
			Bounds placed = bounds.translate(new Vec3(offsetX, 0.0d, offsetZ));
			if (!fits(workArea, placed))
				continue;
			SurgicalTableLayout.Footprint footprint = footprint(-1, placed, cell);
			return new PlacementPlan(offsetX, offsetZ,
				new SurgicalTableLayout.Proposal(List.of(), List.of(footprint)));
		}
		return null;
	}

	/** Builds a proposal for a cut that does not create a new detached component. */
	@Nullable
	public static PlannedLayout currentLayout(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet proposedCuts,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, Map<Integer, Vec3> currentOffsets,
		SurgicalTablePlane.WorkArea workArea) {
		return planSnappedLayout(cubeCount, presentCubes, seams, proposedCuts, cubes, currentOffsets,
			workArea, List.of());
	}

	/** Snaps one detached component to the legal slot nearest the supplied world-space target. */
	@Nullable
	public static PlannedLayout snapComponent(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet proposedCuts,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, Map<Integer, Vec3> currentOffsets,
		SurgicalTablePlane.WorkArea workArea, BitSet movingComponent, double targetX, double targetZ) {
		return planSnappedLayout(cubeCount, presentCubes, seams, proposedCuts, cubes, currentOffsets,
			workArea, List.of(new SnapRequest((BitSet) movingComponent.clone(), targetX, targetZ)));
	}

	/** Sequentially places every newly detached batch-cut component at its nearest legal slot. */
	@Nullable
	public static PlannedLayout autoSnapComponents(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet proposedCuts,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, Map<Integer, Vec3> currentOffsets,
		SurgicalTablePlane.WorkArea workArea, List<BitSet> movingComponents) {
		Map<Integer, Bounds> baseBounds = layoutBounds(presentCubes, cubes);
		if (baseBounds.size() < presentCubes.cardinality())
			return null;
		List<SnapRequest> requests = new ArrayList<>(movingComponents.size());
		for (BitSet component : movingComponents) {
			Bounds bounds = unionBounds(component, baseBounds, currentOffsets);
			if (bounds == null)
				return null;
			requests.add(new SnapRequest((BitSet) component.clone(), bounds.centerX(), bounds.centerZ()));
		}
		return planSnappedLayout(cubeCount, presentCubes, seams, proposedCuts, cubes, currentOffsets,
			workArea, requests);
	}

	/** Aligns every detached component so its rendered outer bounds touch the table surface. */
	public static Map<Integer, Vec3> groundComponents(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet cutSeams,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, Map<Integer, Vec3> horizontalOffsets,
		double surfaceY) {
		if (!Double.isFinite(surfaceY) || !SurgicalAssembly.validTopology(cubeCount, seams)
			|| cutSeams.isEmpty())
			return Map.copyOf(horizontalOffsets);

		List<BitSet> components = SurgicalAssembly.components(cubeCount, presentCubes, seams, cutSeams);
		if (components.isEmpty())
			return Map.copyOf(horizontalOffsets);
		Map<Integer, Bounds> baseBounds = layoutBounds(presentCubes, cubes);
		if (baseBounds.size() < presentCubes.cardinality())
			return Map.copyOf(horizontalOffsets);

		Map<Integer, Vec3> grounded = new HashMap<>();
		for (BitSet component : components) {
			double bottomY = Double.POSITIVE_INFINITY;
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
				Bounds bounds = baseBounds.get(cube);
				if (bounds == null)
					continue;
				bottomY = Math.min(bottomY,
					bounds.minY + horizontalOffsets.getOrDefault(cube, Vec3.ZERO).y);
			}
			if (!Double.isFinite(bottomY))
				continue;
			double groundDelta = surfaceY - bottomY;
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
				Vec3 offset = horizontalOffsets.getOrDefault(cube, Vec3.ZERO);
				Vec3 adjusted = new Vec3(offset.x, offset.y + groundDelta, offset.z);
				if (adjusted.lengthSqr() > DISTANCE_EPSILON)
					grounded.put(cube, adjusted);
			}
		}
		return Map.copyOf(grounded);
	}

	@Nullable
	private static PlannedLayout planSnappedLayout(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet proposedCuts,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, Map<Integer, Vec3> currentOffsets,
		SurgicalTablePlane.WorkArea workArea, List<SnapRequest> requests) {
		if (!SurgicalAssembly.validTopology(cubeCount, seams) || workArea.isEmpty())
			return null;
		Map<Integer, Bounds> baseBounds = layoutBounds(presentCubes, cubes);
		if (baseBounds.size() < presentCubes.cardinality())
			return null;
		List<BitSet> components = SurgicalAssembly.components(cubeCount, presentCubes, seams, proposedCuts);
		Map<Integer, Vec3> plannedOffsets = new HashMap<>();
		for (int cube = presentCubes.nextSetBit(0); cube >= 0; cube = presentCubes.nextSetBit(cube + 1))
			plannedOffsets.put(cube, currentOffsets.getOrDefault(cube, Vec3.ZERO));
		Map<Integer, GridCell> snapped = new HashMap<>();

		for (SnapRequest request : requests) {
			BitSet moving = matchingComponent(components, request.component);
			if (moving == null)
				return null;
			Bounds movingBounds = unionBounds(moving, baseBounds, plannedOffsets);
			if (movingBounds == null)
				return null;
			GridCell selected = null;
			Vec3 selectedDelta = Vec3.ZERO;
			for (GridCell cell : orderedCells(workArea, request.targetX, request.targetZ)) {
				Vec3 delta = new Vec3(cell.centerX() - movingBounds.centerX(), 0.0d,
					cell.centerZ() - movingBounds.centerZ());
				Bounds candidate = movingBounds.translate(delta);
				if (!fits(workArea, candidate)
					|| collidesHorizontally(candidate, moving, components, baseBounds, plannedOffsets, delta))
					continue;
				selected = cell;
				selectedDelta = delta;
				break;
			}
			if (selected == null)
				return null;
			for (int cube = moving.nextSetBit(0); cube >= 0; cube = moving.nextSetBit(cube + 1))
				plannedOffsets.put(cube, plannedOffsets.getOrDefault(cube, Vec3.ZERO).add(selectedDelta));
			snapped.put(moving.nextSetBit(0), selected);
		}

		List<SurgicalTableLayout.Footprint> footprints = new ArrayList<>(presentCubes.cardinality());
		for (BitSet component : components) {
			int root = component.nextSetBit(0);
			Bounds bounds = unionBounds(component, baseBounds, plannedOffsets);
			if (bounds == null || !fits(workArea, bounds))
				return null;
			GridCell cell = snapped.get(root);
			footprints.addAll(footprints(root, component, baseBounds, plannedOffsets, cell));
		}
		for (int first = 0; first < footprints.size(); first++)
			for (int second = first + 1; second < footprints.size(); second++)
				if (footprints.get(first).componentRoot() != footprints.get(second).componentRoot()
					&& footprints.get(first).overlapsStrictly(footprints.get(second)))
					return null;

		List<SurgicalTableLayout.CubeOffset> offsets = new ArrayList<>(presentCubes.cardinality());
		for (int cube = presentCubes.nextSetBit(0); cube >= 0; cube = presentCubes.nextSetBit(cube + 1)) {
			Vec3 offset = plannedOffsets.getOrDefault(cube, Vec3.ZERO);
			offsets.add(new SurgicalTableLayout.CubeOffset(cube, offset.x, offset.z));
		}
		return new PlannedLayout(Map.copyOf(plannedOffsets),
			new SurgicalTableLayout.Proposal(offsets, footprints));
	}

	private static Map<Integer, Bounds> layoutBounds(BitSet presentCubes,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		Map<Integer, Bounds> bounds = new HashMap<>();
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
			if (presentCubes.get(cube.cubeId()))
				bounds.putIfAbsent(cube.cubeId(), Bounds.of(cube).inflate(OUTER_RENDER_INFLATION));
		return bounds;
	}

	@Nullable
	private static BitSet matchingComponent(List<BitSet> components, BitSet requested) {
		for (BitSet component : components)
			if (component.equals(requested))
				return component;
		return null;
	}

	private static boolean collidesHorizontally(Bounds candidate, BitSet moving, List<BitSet> components,
		Map<Integer, Bounds> baseBounds, Map<Integer, Vec3> offsets, Vec3 delta) {
		for (BitSet component : components) {
			if (component.equals(moving))
				continue;
			Bounds obstacleEnvelope = unionBounds(component, baseBounds, offsets);
			if (obstacleEnvelope == null || !candidate.overlapsHorizontally(obstacleEnvelope))
				continue;
			for (int movingCube = moving.nextSetBit(0); movingCube >= 0;
				movingCube = moving.nextSetBit(movingCube + 1)) {
				Bounds movingBounds = baseBounds.get(movingCube);
				if (movingBounds == null)
					continue;
				movingBounds = movingBounds.translate(offsets.getOrDefault(movingCube, Vec3.ZERO).add(delta));
				for (int obstacleCube = component.nextSetBit(0); obstacleCube >= 0;
					obstacleCube = component.nextSetBit(obstacleCube + 1)) {
					Bounds obstacleBounds = baseBounds.get(obstacleCube);
					if (obstacleBounds == null)
						continue;
					obstacleBounds = obstacleBounds.translate(offsets.getOrDefault(obstacleCube, Vec3.ZERO));
					if (movingBounds.overlapsHorizontally(obstacleBounds))
						return true;
				}
			}
		}
		return false;
	}

	private static boolean fits(SurgicalTablePlane.WorkArea workArea, Bounds bounds) {
		return workArea.contains(bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ, DISTANCE_EPSILON);
	}

	private static List<GridCell> orderedCells(SurgicalTablePlane.WorkArea workArea, double targetX,
		double targetZ) {
		List<GridCell> cells = new ArrayList<>(workArea.tileArea() * SurgicalTableLayout.SLOTS_PER_TILE);
		int minGridX = workArea.minX() * SurgicalTableLayout.SUBDIVISIONS;
		int maxGridX = workArea.maxXExclusive() * SurgicalTableLayout.SUBDIVISIONS;
		int minGridZ = workArea.minZ() * SurgicalTableLayout.SUBDIVISIONS;
		int maxGridZ = workArea.maxZExclusive() * SurgicalTableLayout.SUBDIVISIONS;
		for (int gridX = minGridX; gridX < maxGridX; gridX++)
			for (int gridZ = minGridZ; gridZ < maxGridZ; gridZ++)
				cells.add(new GridCell(gridX, gridZ));
		cells.sort(Comparator.comparingDouble((GridCell cell) -> cell.distanceToSqr(targetX, targetZ))
			.thenComparingInt(GridCell::gridX).thenComparingInt(GridCell::gridZ));
		return cells;
	}

	private static SurgicalTableLayout.Footprint footprint(int root, Bounds bounds, @Nullable GridCell cell) {
		return new SurgicalTableLayout.Footprint(root, bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ,
			cell == null ? SurgicalTableLayout.UNSNAPPED : cell.gridX,
			cell == null ? SurgicalTableLayout.UNSNAPPED : cell.gridZ);
	}

	private static List<SurgicalTableLayout.Footprint> footprints(int root, BitSet component,
		Map<Integer, Bounds> baseBounds, Map<Integer, Vec3> offsets, @Nullable GridCell cell) {
		List<SurgicalTableLayout.Footprint> footprints = new ArrayList<>(component.cardinality());
		for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
			Bounds bounds = baseBounds.get(cube);
			if (bounds != null)
				footprints.add(footprint(root, bounds.translate(offsets.getOrDefault(cube, Vec3.ZERO)), cell));
		}
		return footprints;
	}

	public static Vec3 center(SurgicalModelRenderContext.CubeGeometry cube) {
		Vec3 total = Vec3.ZERO;
		for (Vec3 corner : cube.corners())
			total = total.add(corner);
		return total.scale(1.0d / cube.corners().size());
	}

	public static List<Edge> cubeEdges(SurgicalModelRenderContext.CubeGeometry cube) {
		List<Edge> edges = new ArrayList<>(12);
		for (int[] face : CUBE_FACES) {
			for (int vertex = 0; vertex < face.length; vertex++) {
				Edge candidate = new Edge(cube.corners().get(face[vertex]),
					cube.corners().get(face[(vertex + 1) % face.length]));
				if (candidate.start().distanceToSqr(candidate.end()) <= DEGENERATE_EPSILON)
					continue;
				boolean duplicate = false;
				for (Edge edge : edges) {
					if (edge.sameUndirected(candidate)) {
						duplicate = true;
						break;
					}
				}
				if (!duplicate)
					edges.add(candidate);
			}
		}
		return List.copyOf(edges);
	}

	private static Vec3 componentCenter(BitSet component,
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId) {
		return componentCenter(component, byId, Map.of());
	}

	private static Vec3 componentCenter(BitSet component,
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId, Map<Integer, Vec3> offsets) {
		Vec3 total = Vec3.ZERO;
		int count = 0;
		for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
			SurgicalModelRenderContext.CubeGeometry geometry = byId.get(cube);
			if (geometry == null)
				continue;
			total = total.add(center(geometry).add(offsets.getOrDefault(cube, Vec3.ZERO)));
			count++;
		}
		return count == 0 ? Vec3.ZERO : total.scale(1.0d / count);
	}

	private static List<Vec3> clipAgainstPlanes(List<Vec3> polygon,
		List<Plane> planes, double tolerance) {
		List<Vec3> clipped = List.copyOf(polygon);
		for (Plane plane : planes) {
			if (clipped.isEmpty())
				break;
			List<Vec3> next = new ArrayList<>();
			Vec3 previous = clipped.getLast();
			double previousDistance = plane.signedDistance(previous) - tolerance;
			boolean previousInside = previousDistance <= INTERSECTION_EPSILON;
			for (Vec3 current : clipped) {
				double currentDistance = plane.signedDistance(current) - tolerance;
				boolean currentInside = currentDistance <= INTERSECTION_EPSILON;
				if (previousInside != currentInside) {
					double denominator = previousDistance - currentDistance;
					if (Math.abs(denominator) > INTERSECTION_EPSILON) {
						double amount = previousDistance / denominator;
						next.add(previous.add(current.subtract(previous).scale(amount)));
					}
				}
				if (currentInside)
					next.add(current);
				previous = current;
				previousDistance = currentDistance;
				previousInside = currentInside;
			}
			clipped = simplifyPolygon(next);
		}
		return List.copyOf(clipped);
	}

	private static List<Plane> clipPlanes(SurgicalModelRenderContext.CubeGeometry cube) {
		List<Vec3> corners = cube.corners();
		Vec3 cubeCenter = center(cube);
		List<Plane> planes = new ArrayList<>(6);
		for (int[] indices : CUBE_FACES) {
			Vec3 first = corners.get(indices[0]);
			Vec3 faceCenter = first.add(corners.get(indices[1]))
				.add(corners.get(indices[2])).add(corners.get(indices[3])).scale(0.25d);
			Vec3 normal = corners.get(indices[1]).subtract(first)
				.cross(corners.get(indices[3]).subtract(first));
			if (normal.lengthSqr() <= DEGENERATE_EPSILON)
				continue;
			normal = normal.normalize();
			if (normal.dot(faceCenter.subtract(cubeCenter)) < 0.0d)
				normal = normal.scale(-1.0d);
			planes.add(new Plane(normal, normal.dot(first)));
		}
		if (planes.size() == 6)
			return planes;

		// Zero-thickness model cubes have degenerate side faces. Treat them as a
		// tolerance-thick oriented box so their visible rectangle can still form a seam.
		List<Vec3> axes = orthonormalAxes(corners.get(1).subtract(corners.get(0)),
			corners.get(2).subtract(corners.get(0)), corners.get(4).subtract(corners.get(0)));
		planes.clear();
		for (Vec3 axis : axes) {
			double minimum = Double.POSITIVE_INFINITY;
			double maximum = Double.NEGATIVE_INFINITY;
			for (Vec3 corner : corners) {
				double projection = axis.dot(corner);
				minimum = Math.min(minimum, projection);
				maximum = Math.max(maximum, projection);
			}
			planes.add(new Plane(axis, maximum));
			planes.add(new Plane(axis.scale(-1.0d), -minimum));
		}
		return planes;
	}

	private static List<Vec3> orthonormalAxes(Vec3... candidates) {
		List<Vec3> axes = new ArrayList<>(3);
		for (Vec3 candidate : candidates) {
			Vec3 axis = candidate;
			for (Vec3 existing : axes)
				axis = axis.subtract(existing.scale(axis.dot(existing)));
			if (axis.lengthSqr() > DEGENERATE_EPSILON)
				axes.add(axis.normalize());
		}
		if (axes.isEmpty())
			axes.add(new Vec3(1.0d, 0.0d, 0.0d));
		if (axes.size() == 1) {
			Vec3 first = axes.getFirst();
			Vec3 helper = Math.abs(first.y) < 0.9d ? new Vec3(0.0d, 1.0d, 0.0d)
				: new Vec3(1.0d, 0.0d, 0.0d);
			axes.add(first.cross(helper).normalize());
		}
		if (axes.size() == 2)
			axes.add(axes.get(0).cross(axes.get(1)).normalize());
		return List.copyOf(axes.subList(0, 3));
	}

	private static List<Vec3> simplifyPolygon(List<Vec3> polygon) {
		if (polygon.size() < 2)
			return polygon;
		List<Vec3> simplified = new ArrayList<>(polygon.size());
		for (Vec3 point : polygon)
			if (simplified.isEmpty() || simplified.getLast().distanceToSqr(point) > DEGENERATE_EPSILON)
				simplified.add(point);
		if (simplified.size() > 1
			&& simplified.getFirst().distanceToSqr(simplified.getLast()) <= DEGENERATE_EPSILON)
			simplified.removeLast();
		return simplified;
	}

	private static double polygonArea(List<Vec3> polygon) {
		if (polygon.size() < 3)
			return 0.0d;
		Vec3 origin = polygon.getFirst();
		double area = 0.0d;
		for (int i = 1; i + 1 < polygon.size(); i++)
			area += polygon.get(i).subtract(origin).cross(polygon.get(i + 1).subtract(origin)).length() * 0.5d;
		return area;
	}

	private static double volume(SurgicalModelRenderContext.CubeGeometry cube) {
		List<Vec3> corners = cube.corners();
		return corners.get(0).distanceTo(corners.get(1))
			* corners.get(0).distanceTo(corners.get(2))
			* corners.get(0).distanceTo(corners.get(4));
	}

	private static Vec3 faceCenter(List<Vec3> face) {
		return face.get(0).add(face.get(1)).add(face.get(2)).add(face.get(3)).scale(0.25d);
	}

	private static Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId(
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> result = new HashMap<>();
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
			result.putIfAbsent(cube.cubeId(), cube);
		return result;
	}

	private static Map<Integer, PreparedCube> preparedById(
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		Map<Integer, PreparedCube> result = new HashMap<>();
		for (SurgicalModelRenderContext.CubeGeometry cube : cubes)
			result.computeIfAbsent(cube.cubeId(), ignored -> PreparedCube.of(cube));
		return result;
	}

	public record ContactTopology(List<SurgicalAssembly.Seam> seams, List<Contact> contacts) {
		private static final ContactTopology EMPTY = new ContactTopology(List.of(), List.of());

		public ContactTopology {
			seams = List.copyOf(seams);
			contacts = List.copyOf(contacts);
		}
	}

	public record Contact(SurgicalAssembly.Seam seam, int anchorCubeId, List<List<Vec3>> faces) {
		public Contact {
			List<List<Vec3>> copiedFaces = new ArrayList<>(faces.size());
			for (List<Vec3> face : faces) {
				if (face.size() < 3)
					throw new IllegalArgumentException("A surgical contact face requires at least three vertices");
				copiedFaces.add(List.copyOf(face));
			}
			if (copiedFaces.isEmpty())
				throw new IllegalArgumentException("A surgical contact requires at least one face");
			faces = List.copyOf(copiedFaces);
		}

		public List<Edge> edges() {
			List<Edge> edges = new ArrayList<>();
			for (List<Vec3> face : faces) {
				for (int vertex = 0; vertex < face.size(); vertex++) {
					Edge candidate = new Edge(face.get(vertex), face.get((vertex + 1) % face.size()));
					boolean duplicate = false;
					for (Edge edge : edges) {
						if (edge.sameUndirected(candidate)) {
							duplicate = true;
							break;
						}
					}
					if (!duplicate)
						edges.add(candidate);
				}
			}
			return List.copyOf(edges);
		}
	}

	public record Edge(Vec3 start, Vec3 end) {
		private boolean sameUndirected(Edge other) {
			return samePoint(start, other.start) && samePoint(end, other.end)
				|| samePoint(start, other.end) && samePoint(end, other.start);
		}

		private static boolean samePoint(Vec3 first, Vec3 second) {
			return first.distanceToSqr(second) <= VERTEX_MERGE_DISTANCE_SQR;
		}
	}

	private record PreparedCube(SurgicalModelRenderContext.CubeGeometry geometry, Vec3 center,
		double volume, List<Plane> planes, Bounds bounds) {
		private static PreparedCube of(SurgicalModelRenderContext.CubeGeometry geometry) {
			return new PreparedCube(geometry, SurgicalClientTopology.center(geometry),
				SurgicalClientTopology.volume(geometry),
				List.copyOf(clipPlanes(geometry)), Bounds.of(geometry));
		}
	}

	private record SearchPoint(long xSteps, long zSteps) {
		private double x() {
			return xSteps * LAYOUT_QUANTUM;
		}

		private double z() {
			return zSteps * LAYOUT_QUANTUM;
		}

		private double distanceSqr() {
			double x = x();
			double z = z();
			return x * x + z * z;
		}

		private long totalSteps() {
			return xSteps + zSteps;
		}

		private Vec3 offset(int signX, int signZ) {
			return new Vec3(signX * x(), 0.0d, signZ * z());
		}
	}

	private record Collision(Bounds moving, Bounds obstacle) {}

	public record PlacementPlan(double originOffsetX, double originOffsetZ,
		SurgicalTableLayout.Proposal proposal) {}

	public record PlannedLayout(Map<Integer, Vec3> offsets, SurgicalTableLayout.Proposal proposal) {
		public PlannedLayout {
			offsets = Map.copyOf(offsets);
		}
	}

	private record SnapRequest(BitSet component, double targetX, double targetZ) {}

	private record GridCell(int gridX, int gridZ) {
		private double centerX() {
			return SurgicalTableLayout.gridCenter(gridX);
		}

		private double centerZ() {
			return SurgicalTableLayout.gridCenter(gridZ);
		}

		private double distanceToSqr(double x, double z) {
			double dx = centerX() - x;
			double dz = centerZ() - z;
			return dx * dx + dz * dz;
		}
	}

	private record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		private static Bounds of(SurgicalModelRenderContext.CubeGeometry geometry) {
			double minX = Double.POSITIVE_INFINITY;
			double minY = Double.POSITIVE_INFINITY;
			double minZ = Double.POSITIVE_INFINITY;
			double maxX = Double.NEGATIVE_INFINITY;
			double maxY = Double.NEGATIVE_INFINITY;
			double maxZ = Double.NEGATIVE_INFINITY;
			for (Vec3 corner : geometry.corners()) {
				minX = Math.min(minX, corner.x);
				minY = Math.min(minY, corner.y);
				minZ = Math.min(minZ, corner.z);
				maxX = Math.max(maxX, corner.x);
				maxY = Math.max(maxY, corner.y);
				maxZ = Math.max(maxZ, corner.z);
			}
			return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
		}

		private boolean overlapsWithin(Bounds other, double tolerance) {
			return minX <= other.maxX + tolerance && maxX + tolerance >= other.minX
				&& minY <= other.maxY + tolerance && maxY + tolerance >= other.minY
				&& minZ <= other.maxZ + tolerance && maxZ + tolerance >= other.minZ;
		}

		private Bounds inflate(double amount) {
			return new Bounds(minX - amount, minY - amount, minZ - amount,
				maxX + amount, maxY + amount, maxZ + amount);
		}

		private Bounds translate(Vec3 offset) {
			return new Bounds(minX + offset.x, minY + offset.y, minZ + offset.z,
				maxX + offset.x, maxY + offset.y, maxZ + offset.z);
		}

		private Bounds union(Bounds other) {
			return new Bounds(Math.min(minX, other.minX), Math.min(minY, other.minY),
				Math.min(minZ, other.minZ), Math.max(maxX, other.maxX),
				Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
		}

		private boolean overlapsStrictly(Bounds other) {
			return minX < other.maxX - DISTANCE_EPSILON && maxX > other.minX + DISTANCE_EPSILON
				&& minY < other.maxY - DISTANCE_EPSILON && maxY > other.minY + DISTANCE_EPSILON
				&& minZ < other.maxZ - DISTANCE_EPSILON && maxZ > other.minZ + DISTANCE_EPSILON;
		}

		private boolean overlapsHorizontally(Bounds other) {
			return minX < other.maxX - DISTANCE_EPSILON && maxX > other.minX + DISTANCE_EPSILON
				&& minZ < other.maxZ - DISTANCE_EPSILON && maxZ > other.minZ + DISTANCE_EPSILON;
		}

		private double centerX() {
			return (minX + maxX) * 0.5d;
		}

		private double centerZ() {
			return (minZ + maxZ) * 0.5d;
		}
	}

	private record Plane(Vec3 normal, double maximum) {
		private double signedDistance(Vec3 point) {
			return normal.dot(point) - maximum;
		}
	}
}
