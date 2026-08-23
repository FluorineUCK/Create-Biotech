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
	private static final double COMPONENT_OFFSET = 1.0d / 16.0d;

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
				if (next < 0 || bestDistance[cube] < bestDistance[next] - 1.0e-9d
					|| Math.abs(bestDistance[cube] - bestDistance[next]) <= 1.0e-9d && cube < next)
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
				double distance = surfaceDistance(byId.get(next), byId.get(candidate));
				if (distance < bestDistance[candidate] - 1.0e-9d
					|| Math.abs(distance - bestDistance[candidate]) <= 1.0e-9d
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
		double bestDistance = Double.MAX_VALUE;
		for (int[] indices : CUBE_FACES) {
			List<Vec3> face = List.of(smaller.corners().get(indices[0]), smaller.corners().get(indices[1]),
				smaller.corners().get(indices[2]), smaller.corners().get(indices[3]));
			double distance = faceCenter(face).distanceToSqr(otherCenter);
			if (distance < bestDistance) {
				bestDistance = distance;
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

	private static double surfaceDistance(SurgicalModelRenderContext.CubeGeometry first,
		SurgicalModelRenderContext.CubeGeometry second) {
		Vec3 firstCenter = center(first);
		Vec3 secondCenter = center(second);
		double centerDistance = firstCenter.distanceTo(secondCenter);
		double firstRadius = first.corners().stream().mapToDouble(firstCenter::distanceTo).max().orElse(0.0d);
		double secondRadius = second.corners().stream().mapToDouble(secondCenter::distanceTo).max().orElse(0.0d);
		return Math.max(0.0d, centerDistance - firstRadius - secondRadius);
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
