package com.nobodiiiii.createbiotech.content.surgery;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Side-safe wire data and validation for surgical-table component placement. */
public final class SurgicalTableLayout {
	public static final int SUBDIVISIONS = 4;
	public static final int SLOTS_PER_TILE = SUBDIVISIONS * SUBDIVISIONS;
	public static final int UNSNAPPED = Integer.MIN_VALUE;
	private static final double EPSILON = 1.0e-6d;
	private static final double MAX_OFFSET = SurgicalTablePlane.MAX_TILES + 2.0d;

	private SurgicalTableLayout() {}

	public static double gridCenter(int gridCoordinate) {
		return (gridCoordinate + 0.5d) / SUBDIVISIONS;
	}

	public static int gridCoordinate(double position) {
		return (int) Math.floor(position * SUBDIVISIONS);
	}

	public static boolean validatePlacement(SurgicalTablePlane.Plane plane, double originOffsetX,
		double originOffsetZ, Proposal proposal) {
		if (!plane.valid() || plane.workArea().isEmpty() || !finiteBounded(originOffsetX)
			|| !finiteBounded(originOffsetZ) || !proposal.offsets().isEmpty()
			|| proposal.footprints().size() != 1)
			return false;
		Footprint footprint = proposal.footprints().getFirst();
		return footprint.componentRoot() == -1 && validFootprint(plane.workArea(), footprint, true);
	}

	public static boolean validateComponents(SurgicalTablePlane.Plane plane, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, Proposal proposal) {
		if (!plane.valid() || plane.workArea().isEmpty()
			|| !SurgicalAssembly.validTopology(cubeCount, seams)
			|| proposal.offsets().size() != presentCubes.cardinality())
			return false;

		Map<Integer, CubeOffset> offsets = new HashMap<>();
		for (CubeOffset offset : proposal.offsets()) {
			if (offset == null || offset.cubeId() < 0 || offset.cubeId() >= cubeCount
				|| !presentCubes.get(offset.cubeId()) || !finiteBounded(offset.x())
				|| !finiteBounded(offset.z()) || offsets.putIfAbsent(offset.cubeId(), offset) != null)
				return false;
		}

		List<BitSet> components = SurgicalAssembly.components(cubeCount, presentCubes, seams, cutSeams);
		if (proposal.footprints().size() != components.size())
			return false;
		Map<Integer, Footprint> footprints = new HashMap<>();
		for (Footprint footprint : proposal.footprints()) {
			if (footprint == null || !validFootprint(plane.workArea(), footprint, false)
				|| footprints.putIfAbsent(footprint.componentRoot(), footprint) != null)
				return false;
		}

		Set<Integer> expectedRoots = new HashSet<>();
		for (BitSet component : components) {
			int root = component.nextSetBit(0);
			expectedRoots.add(root);
			CubeOffset componentOffset = offsets.get(root);
			if (componentOffset == null || !footprints.containsKey(root))
				return false;
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
				CubeOffset offset = offsets.get(cube);
				if (offset == null || Math.abs(offset.x() - componentOffset.x()) > EPSILON
					|| Math.abs(offset.z() - componentOffset.z()) > EPSILON)
					return false;
			}
		}
		if (!footprints.keySet().equals(expectedRoots))
			return false;

		List<Footprint> footprintList = proposal.footprints();
		for (int first = 0; first < footprintList.size(); first++)
			for (int second = first + 1; second < footprintList.size(); second++)
				if (footprintList.get(first).overlapsStrictly(footprintList.get(second)))
					return false;
		return true;
	}

	private static boolean validFootprint(SurgicalTablePlane.WorkArea area, Footprint footprint,
		boolean requireSnapped) {
		if (!Double.isFinite(footprint.minX()) || !Double.isFinite(footprint.minZ())
			|| !Double.isFinite(footprint.maxX()) || !Double.isFinite(footprint.maxZ())
			|| footprint.maxX() < footprint.minX() || footprint.maxZ() < footprint.minZ()
			|| !area.contains(footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ(), EPSILON))
			return false;
		boolean snappedX = footprint.gridX() != UNSNAPPED;
		boolean snappedZ = footprint.gridZ() != UNSNAPPED;
		if (snappedX != snappedZ || requireSnapped && !snappedX)
			return false;
		if (!snappedX)
			return true;
		double centerX = (footprint.minX() + footprint.maxX()) * 0.5d;
		double centerZ = (footprint.minZ() + footprint.maxZ()) * 0.5d;
		double gridCenterX = gridCenter(footprint.gridX());
		double gridCenterZ = gridCenter(footprint.gridZ());
		return Math.abs(centerX - gridCenterX) <= EPSILON && Math.abs(centerZ - gridCenterZ) <= EPSILON
			&& gridCenterX >= area.minX() && gridCenterX < area.maxXExclusive()
			&& gridCenterZ >= area.minZ() && gridCenterZ < area.maxZExclusive();
	}

	private static boolean finiteBounded(double value) {
		return Double.isFinite(value) && Math.abs(value) <= MAX_OFFSET;
	}

	public record CubeOffset(int cubeId, double x, double z) {}

	public record Footprint(int componentRoot, double minX, double minZ, double maxX, double maxZ,
		int gridX, int gridZ) {
		public boolean overlapsStrictly(Footprint other) {
			return minX < other.maxX - EPSILON && maxX > other.minX + EPSILON
				&& minZ < other.maxZ - EPSILON && maxZ > other.minZ + EPSILON;
		}
	}

	public record Proposal(List<CubeOffset> offsets, List<Footprint> footprints) {
		public static final Proposal EMPTY = new Proposal(List.of(), List.of());

		public Proposal {
			offsets = List.copyOf(offsets);
			footprints = List.copyOf(footprints);
		}
	}
}
