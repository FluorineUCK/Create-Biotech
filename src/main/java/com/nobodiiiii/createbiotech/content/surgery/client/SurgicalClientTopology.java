package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;

import net.minecraft.world.phys.Vec3;

/** Deterministic client geometry helpers; no result is trusted without server validation. */
public final class SurgicalClientTopology {
	public static final int[][] CUBE_FACES = {
		{0, 4, 6, 2}, {1, 3, 7, 5},
		{0, 1, 5, 4}, {2, 6, 7, 3},
		{0, 2, 3, 1}, {4, 5, 7, 6}
	};
	private static final double COMPONENT_OFFSET = 1.0d / 16.0d;
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

	/**
	 * Separates only the farther side of the last severed connection. The moving
	 * side is selected relative to the table centre, while the separation axis is
	 * the line through the two cubes that actually shared the severed seam.
	 */
	public static Map<Integer, Vec3> componentOffsets(int cubeCount, BitSet presentCubes,
		List<SurgicalAssembly.Seam> seams, BitSet cutSeams,
		List<SurgicalModelRenderContext.CubeGeometry> cubes, int lastCutSeam, Vec3 tableCenter) {
		if (!SurgicalAssembly.validTopology(cubeCount, seams)
			|| lastCutSeam < 0 || lastCutSeam >= seams.size() || !cutSeams.get(lastCutSeam))
			return Map.of();

		SurgicalAssembly.Seam seam = seams.get(lastCutSeam);
		BitSet firstComponent = SurgicalAssembly.componentContaining(cubeCount, presentCubes,
			seams, cutSeams, seam.first());
		BitSet secondComponent = SurgicalAssembly.componentContaining(cubeCount, presentCubes,
			seams, cutSeams, seam.second());
		if (firstComponent.isEmpty() || secondComponent.isEmpty() || firstComponent.equals(secondComponent))
			return Map.of();

		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId = byId(cubes);
		SurgicalModelRenderContext.CubeGeometry firstCube = byId.get(seam.first());
		SurgicalModelRenderContext.CubeGeometry secondCube = byId.get(seam.second());
		if (firstCube == null || secondCube == null)
			return Map.of();

		Vec3 firstCenter = componentCenter(firstComponent, byId);
		Vec3 secondCenter = componentCenter(secondComponent, byId);
		double firstDistance = firstCenter.distanceToSqr(tableCenter);
		double secondDistance = secondCenter.distanceToSqr(tableCenter);
		boolean moveFirst = firstDistance > secondDistance;
		if (Math.abs(firstDistance - secondDistance) < DISTANCE_EPSILON)
			moveFirst = center(firstCube).distanceToSqr(tableCenter) > center(secondCube).distanceToSqr(tableCenter);

		BitSet movingComponent = moveFirst ? firstComponent : secondComponent;
		Vec3 direction = moveFirst
			? center(firstCube).subtract(center(secondCube))
			: center(secondCube).subtract(center(firstCube));
		if (direction.lengthSqr() < DISTANCE_EPSILON)
			direction = (moveFirst ? firstCenter : secondCenter).subtract(tableCenter);
		if (direction.lengthSqr() < DISTANCE_EPSILON)
			direction = new Vec3(0.0d, 1.0d, 0.0d);

		Vec3 offset = direction.normalize().scale(COMPONENT_OFFSET);
		Map<Integer, Vec3> offsets = new HashMap<>();
		for (int cube = movingComponent.nextSetBit(0); cube >= 0; cube = movingComponent.nextSetBit(cube + 1))
			offsets.put(cube, offset);
		return Map.copyOf(offsets);
	}

	public static Vec3 center(SurgicalModelRenderContext.CubeGeometry cube) {
		Vec3 total = Vec3.ZERO;
		for (Vec3 corner : cube.corners())
			total = total.add(corner);
		return total.scale(1.0d / cube.corners().size());
	}

	private static Vec3 componentCenter(BitSet component,
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId) {
		Vec3 total = Vec3.ZERO;
		int count = 0;
		for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
			SurgicalModelRenderContext.CubeGeometry geometry = byId.get(cube);
			if (geometry == null)
				continue;
			total = total.add(center(geometry));
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
	}

	private record Plane(Vec3 normal, double maximum) {
		private double signedDistance(Vec3 point) {
			return normal.dot(point) - maximum;
		}
	}
}
