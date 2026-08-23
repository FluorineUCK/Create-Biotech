package com.nobodiiiii.createbiotech.content.surgery.client;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;

import net.minecraft.world.phys.Vec3;

/** Deterministic client geometry helpers; no result is trusted without server validation. */
public final class SurgicalClientTopology {
	public static final int[][] CUBE_FACES = {
		{0, 4, 6, 2}, {1, 3, 7, 5},
		{0, 1, 5, 4}, {2, 6, 7, 3},
		{0, 2, 3, 1}, {4, 5, 7, 6}
	};
	private static final int[][] CUBE_EDGES = {
		{0, 1}, {2, 3}, {4, 5}, {6, 7},
		{0, 2}, {1, 3}, {4, 6}, {5, 7},
		{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};
	private static final int[][] CUBE_TRIANGLES = {
		{0, 4, 6}, {0, 6, 2}, {1, 3, 7}, {1, 7, 5},
		{0, 1, 5}, {0, 5, 4}, {2, 6, 7}, {2, 7, 3},
		{0, 2, 3}, {0, 3, 1}, {4, 5, 7}, {4, 7, 6}
	};
	private static final double COMPONENT_OFFSET = 1.0d / 16.0d;
	private static final double DISTANCE_EPSILON = 1.0e-9d;
	private static final double INTERSECTION_EPSILON = 1.0e-12d;
	private static final double DEGENERATE_EPSILON = 1.0e-18d;

	private SurgicalClientTopology() {}

	public static List<SurgicalAssembly.Seam> buildMinimumSpanningTree(int cubeCount,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		if (!SurgicalAssembly.validCubeCount(cubeCount))
			return List.of();
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId = byId(cubes);
		if (byId.size() != cubeCount)
			return List.of();
		if (cubeCount == 1)
			return List.of();

		boolean[] included = new boolean[cubeCount];
		double[] bestDistance = new double[cubeCount];
		int[] parent = new int[cubeCount];
		java.util.Arrays.fill(bestDistance, Double.POSITIVE_INFINITY);
		java.util.Arrays.fill(parent, -1);
		bestDistance[0] = 0.0d;
		List<SurgicalAssembly.Seam> seams = new ArrayList<>(cubeCount - 1);

		for (int step = 0; step < cubeCount; step++) {
			int next = -1;
			for (int cube = 0; cube < cubeCount; cube++) {
				if (included[cube])
					continue;
				if (next < 0 || bestDistance[cube] < bestDistance[next] - DISTANCE_EPSILON
					|| Math.abs(bestDistance[cube] - bestDistance[next]) <= DISTANCE_EPSILON && cube < next)
					next = cube;
			}
			if (next < 0)
				return List.of();
			included[next] = true;
			if (parent[next] >= 0)
				seams.add(SurgicalAssembly.Seam.of(parent[next], next));

			for (int candidate = 0; candidate < cubeCount; candidate++) {
				if (included[candidate])
					continue;
				double distance = surfaceDistanceSquared(byId.get(next), byId.get(candidate));
				if (distance < bestDistance[candidate] - DISTANCE_EPSILON
					|| Math.abs(distance - bestDistance[candidate]) <= DISTANCE_EPSILON
						&& (parent[candidate] < 0 || next < parent[candidate])) {
					bestDistance[candidate] = distance;
					parent[candidate] = next;
				}
			}
		}

		seams.sort(Comparator.comparingInt(SurgicalAssembly.Seam::first)
			.thenComparingInt(SurgicalAssembly.Seam::second));
		return List.copyOf(seams);
	}

	public static List<Vec3> seamFace(SurgicalAssembly.Seam seam,
		List<SurgicalModelRenderContext.CubeGeometry> cubes) {
		Map<Integer, SurgicalModelRenderContext.CubeGeometry> byId = byId(cubes);
		SurgicalModelRenderContext.CubeGeometry first = byId.get(seam.first());
		SurgicalModelRenderContext.CubeGeometry second = byId.get(seam.second());
		if (first == null || second == null)
			return List.of();

		SurgicalModelRenderContext.CubeGeometry smaller = volume(first) <= volume(second) ? first : second;
		SurgicalModelRenderContext.CubeGeometry other = smaller == first ? second : first;
		Vec3 otherCenter = center(other);
		List<Vec3> bestFace = List.of();
		double bestSurfaceDistance = Double.MAX_VALUE;
		double bestCenterDistance = Double.MAX_VALUE;
		for (int[] indices : CUBE_FACES) {
			List<Vec3> face = List.of(smaller.corners().get(indices[0]), smaller.corners().get(indices[1]),
				smaller.corners().get(indices[2]), smaller.corners().get(indices[3]));
			double surfaceDistance = faceSurfaceDistanceSquared(face, other);
			double centerDistance = faceCenter(face).distanceToSqr(otherCenter);
			if (surfaceDistance < bestSurfaceDistance - DISTANCE_EPSILON
				|| Math.abs(surfaceDistance - bestSurfaceDistance) <= DISTANCE_EPSILON
					&& centerDistance < bestCenterDistance - DISTANCE_EPSILON) {
				bestSurfaceDistance = surfaceDistance;
				bestCenterDistance = centerDistance;
				bestFace = face;
			}
		}
		return bestFace;
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

	private static double surfaceDistanceSquared(SurgicalModelRenderContext.CubeGeometry first,
		SurgicalModelRenderContext.CubeGeometry second) {
		// A center/radius approximation makes most long or rotated model parts overlap and
		// collapses their edge weights to zero. Compare the actual transformed surfaces so
		// Prim's tree follows visible joints instead of cube enumeration order.
		List<Vec3> firstCorners = first.corners();
		List<Vec3> secondCorners = second.corners();

		for (int[] edge : CUBE_EDGES)
			for (int[] triangle : CUBE_TRIANGLES)
				if (segmentIntersectsTriangle(firstCorners.get(edge[0]), firstCorners.get(edge[1]),
					secondCorners.get(triangle[0]), secondCorners.get(triangle[1]),
					secondCorners.get(triangle[2]))
					|| segmentIntersectsTriangle(secondCorners.get(edge[0]), secondCorners.get(edge[1]),
						firstCorners.get(triangle[0]), firstCorners.get(triangle[1]),
						firstCorners.get(triangle[2])))
					return 0.0d;

		double best = Double.MAX_VALUE;
		for (Vec3 point : firstCorners)
			for (int[] triangle : CUBE_TRIANGLES)
				best = Math.min(best, pointTriangleDistanceSquared(point,
					secondCorners.get(triangle[0]), secondCorners.get(triangle[1]),
					secondCorners.get(triangle[2])));
		for (Vec3 point : secondCorners)
			for (int[] triangle : CUBE_TRIANGLES)
				best = Math.min(best, pointTriangleDistanceSquared(point,
					firstCorners.get(triangle[0]), firstCorners.get(triangle[1]),
					firstCorners.get(triangle[2])));
		for (int[] firstEdge : CUBE_EDGES)
			for (int[] secondEdge : CUBE_EDGES)
				best = Math.min(best, segmentDistanceSquared(firstCorners.get(firstEdge[0]),
					firstCorners.get(firstEdge[1]), secondCorners.get(secondEdge[0]),
					secondCorners.get(secondEdge[1])));
		return best <= DEGENERATE_EPSILON ? 0.0d : best;
	}

	private static double faceSurfaceDistanceSquared(List<Vec3> face,
		SurgicalModelRenderContext.CubeGeometry cube) {
		double best = Double.MAX_VALUE;
		List<Vec3> corners = cube.corners();
		for (int half = 0; half < 2; half++) {
			Vec3 faceA = face.get(0);
			Vec3 faceB = face.get(half == 0 ? 1 : 2);
			Vec3 faceC = face.get(half == 0 ? 2 : 3);
			for (int[] triangle : CUBE_TRIANGLES) {
				double distance = triangleDistanceSquared(faceA, faceB, faceC,
					corners.get(triangle[0]), corners.get(triangle[1]), corners.get(triangle[2]));
				if (distance <= DEGENERATE_EPSILON)
					return 0.0d;
				best = Math.min(best, distance);
			}
		}
		return best;
	}

	private static double triangleDistanceSquared(Vec3 firstA, Vec3 firstB, Vec3 firstC,
		Vec3 secondA, Vec3 secondB, Vec3 secondC) {
		Vec3[] first = {firstA, firstB, firstC};
		Vec3[] second = {secondA, secondB, secondC};
		for (int edge = 0; edge < 3; edge++) {
			if (segmentIntersectsTriangle(first[edge], first[(edge + 1) % 3], secondA, secondB, secondC)
				|| segmentIntersectsTriangle(second[edge], second[(edge + 1) % 3], firstA, firstB, firstC))
				return 0.0d;
		}

		double best = Double.MAX_VALUE;
		for (Vec3 point : first)
			best = Math.min(best, pointTriangleDistanceSquared(point, secondA, secondB, secondC));
		for (Vec3 point : second)
			best = Math.min(best, pointTriangleDistanceSquared(point, firstA, firstB, firstC));
		for (int firstEdge = 0; firstEdge < 3; firstEdge++)
			for (int secondEdge = 0; secondEdge < 3; secondEdge++)
				best = Math.min(best, segmentDistanceSquared(first[firstEdge], first[(firstEdge + 1) % 3],
					second[secondEdge], second[(secondEdge + 1) % 3]));
		return best;
	}

	private static boolean segmentIntersectsTriangle(Vec3 start, Vec3 end, Vec3 a, Vec3 b, Vec3 c) {
		Vec3 direction = end.subtract(start);
		Vec3 edgeAB = b.subtract(a);
		Vec3 edgeAC = c.subtract(a);
		Vec3 perpendicular = direction.cross(edgeAC);
		double determinant = edgeAB.dot(perpendicular);
		if (Math.abs(determinant) <= INTERSECTION_EPSILON)
			return false;

		double inverse = 1.0d / determinant;
		Vec3 fromA = start.subtract(a);
		double u = fromA.dot(perpendicular) * inverse;
		if (u < -DISTANCE_EPSILON || u > 1.0d + DISTANCE_EPSILON)
			return false;
		Vec3 cross = fromA.cross(edgeAB);
		double v = direction.dot(cross) * inverse;
		if (v < -DISTANCE_EPSILON || u + v > 1.0d + DISTANCE_EPSILON)
			return false;
		double distanceAlongSegment = edgeAC.dot(cross) * inverse;
		return distanceAlongSegment >= -DISTANCE_EPSILON
			&& distanceAlongSegment <= 1.0d + DISTANCE_EPSILON;
	}

	private static double pointTriangleDistanceSquared(Vec3 point, Vec3 a, Vec3 b, Vec3 c) {
		Vec3 edgeAB = b.subtract(a);
		Vec3 edgeAC = c.subtract(a);
		if (edgeAB.cross(edgeAC).lengthSqr() <= DEGENERATE_EPSILON)
			return Math.min(pointSegmentDistanceSquared(point, a, b),
				Math.min(pointSegmentDistanceSquared(point, b, c), pointSegmentDistanceSquared(point, c, a)));

		Vec3 fromA = point.subtract(a);
		double d1 = edgeAB.dot(fromA);
		double d2 = edgeAC.dot(fromA);
		if (d1 <= 0.0d && d2 <= 0.0d)
			return fromA.lengthSqr();

		Vec3 fromB = point.subtract(b);
		double d3 = edgeAB.dot(fromB);
		double d4 = edgeAC.dot(fromB);
		if (d3 >= 0.0d && d4 <= d3)
			return fromB.lengthSqr();

		double edgeABRegion = d1 * d4 - d3 * d2;
		if (edgeABRegion <= 0.0d && d1 >= 0.0d && d3 <= 0.0d) {
			double amount = d1 / (d1 - d3);
			return point.distanceToSqr(a.add(edgeAB.scale(amount)));
		}

		Vec3 fromC = point.subtract(c);
		double d5 = edgeAB.dot(fromC);
		double d6 = edgeAC.dot(fromC);
		if (d6 >= 0.0d && d5 <= d6)
			return fromC.lengthSqr();

		double edgeACRegion = d5 * d2 - d1 * d6;
		if (edgeACRegion <= 0.0d && d2 >= 0.0d && d6 <= 0.0d) {
			double amount = d2 / (d2 - d6);
			return point.distanceToSqr(a.add(edgeAC.scale(amount)));
		}

		double edgeBCRegion = d3 * d6 - d5 * d4;
		if (edgeBCRegion <= 0.0d && d4 - d3 >= 0.0d && d5 - d6 >= 0.0d) {
			double amount = (d4 - d3) / ((d4 - d3) + (d5 - d6));
			return point.distanceToSqr(b.add(c.subtract(b).scale(amount)));
		}

		double inverse = 1.0d / (edgeBCRegion + edgeACRegion + edgeABRegion);
		double v = edgeACRegion * inverse;
		double w = edgeABRegion * inverse;
		return point.distanceToSqr(a.add(edgeAB.scale(v)).add(edgeAC.scale(w)));
	}

	private static double pointSegmentDistanceSquared(Vec3 point, Vec3 start, Vec3 end) {
		Vec3 segment = end.subtract(start);
		double lengthSquared = segment.lengthSqr();
		if (lengthSquared <= DEGENERATE_EPSILON)
			return point.distanceToSqr(start);
		double amount = clamp(point.subtract(start).dot(segment) / lengthSquared);
		return point.distanceToSqr(start.add(segment.scale(amount)));
	}

	private static double segmentDistanceSquared(Vec3 firstStart, Vec3 firstEnd,
		Vec3 secondStart, Vec3 secondEnd) {
		Vec3 firstDirection = firstEnd.subtract(firstStart);
		Vec3 secondDirection = secondEnd.subtract(secondStart);
		Vec3 origins = firstStart.subtract(secondStart);
		double firstLengthSquared = firstDirection.lengthSqr();
		double secondLengthSquared = secondDirection.lengthSqr();
		double secondProjection = secondDirection.dot(origins);
		double firstAmount;
		double secondAmount;

		if (firstLengthSquared <= DEGENERATE_EPSILON && secondLengthSquared <= DEGENERATE_EPSILON)
			return firstStart.distanceToSqr(secondStart);
		if (firstLengthSquared <= DEGENERATE_EPSILON) {
			firstAmount = 0.0d;
			secondAmount = clamp(secondProjection / secondLengthSquared);
		} else {
			double firstProjection = firstDirection.dot(origins);
			if (secondLengthSquared <= DEGENERATE_EPSILON) {
				secondAmount = 0.0d;
				firstAmount = clamp(-firstProjection / firstLengthSquared);
			} else {
				double directionsProjection = firstDirection.dot(secondDirection);
				double denominator = firstLengthSquared * secondLengthSquared
					- directionsProjection * directionsProjection;
				firstAmount = denominator <= DEGENERATE_EPSILON ? 0.0d
					: clamp((directionsProjection * secondProjection
						- firstProjection * secondLengthSquared) / denominator);
				secondAmount = (directionsProjection * firstAmount + secondProjection) / secondLengthSquared;
				if (secondAmount < 0.0d) {
					secondAmount = 0.0d;
					firstAmount = clamp(-firstProjection / firstLengthSquared);
				} else if (secondAmount > 1.0d) {
					secondAmount = 1.0d;
					firstAmount = clamp((directionsProjection - firstProjection) / firstLengthSquared);
				}
			}
		}

		Vec3 firstClosest = firstStart.add(firstDirection.scale(firstAmount));
		Vec3 secondClosest = secondStart.add(secondDirection.scale(secondAmount));
		return firstClosest.distanceToSqr(secondClosest);
	}

	private static double clamp(double value) {
		return Math.max(0.0d, Math.min(1.0d, value));
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
}
