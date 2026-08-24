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
		double originOffsetZ, Proposal proposal, List<Footprint> occupiedFootprints) {
		if (!plane.valid() || plane.workArea().isEmpty() || !finiteBounded(originOffsetX)
			|| !finiteBounded(originOffsetZ) || !proposal.offsets().isEmpty()
			|| proposal.footprints().size() != 1)
			return false;
		Footprint footprint = proposal.footprints().getFirst();
		return footprint.componentRoot() == -1
			&& validFootprints(plane.workArea(), List.of(footprint), true)
			&& doesNotOverlap(proposal.footprints(), occupiedFootprints);
	}

	public static boolean validateComponents(SurgicalTablePlane.Plane plane, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, Proposal proposal,
		List<Footprint> occupiedFootprints) {
		return validateComponents(plane, cubeCount, presentCubes, seams, cutSeams, proposal,
			occupiedFootprints, null, false, false);
	}

	/**
	 * Validates one source restored from a packed glued assembly. Components and sources in the
	 * same assembly may overlap because the glue points themselves can be inside both models.
	 * Smart-glue rotation can also leave different cubes in one native component with different
	 * translations; the placement path separately verifies those translations against the packed
	 * server-owned assembly before calling this method.
	 */
	public static boolean validateCompositeComponents(SurgicalTablePlane.Plane plane, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, Proposal proposal,
		List<Footprint> occupiedFootprints, Footprint assemblyEnvelope) {
		return validateComponents(plane, cubeCount, presentCubes, seams, cutSeams, proposal,
			occupiedFootprints, assemblyEnvelope, true, true);
	}

	/** Validates exact glue-preview placement while allowing the already-glued native components to overlap. */
	public static boolean validateGlueComponents(SurgicalTablePlane.Plane plane, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, Proposal proposal,
		List<Footprint> occupiedFootprints) {
		return validateComponents(plane, cubeCount, presentCubes, seams, cutSeams, proposal,
			occupiedFootprints, null, true, false);
	}

	/** Validates a smart-glue rigid-body preview whose rotation can give each cube a distinct offset. */
	public static boolean validateEditedGlueComponents(SurgicalTablePlane.Plane plane, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, Proposal proposal,
		List<Footprint> occupiedFootprints) {
		return validateComponents(plane, cubeCount, presentCubes, seams, cutSeams, proposal,
			occupiedFootprints, null, true, true);
	}

	private static boolean validateComponents(SurgicalTablePlane.Plane plane, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, Proposal proposal,
		List<Footprint> occupiedFootprints, Footprint assemblyEnvelope, boolean allowComponentOverlap,
		boolean allowPerCubeOffsets) {
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
		if (proposal.footprints().size() != presentCubes.cardinality())
			return false;
		Map<Integer, List<Footprint>> footprints = new HashMap<>();
		for (Footprint footprint : proposal.footprints()) {
			if (footprint == null || !validFootprintBounds(plane.workArea(), footprint)
				|| assemblyEnvelope != null && !contains(assemblyEnvelope, footprint))
				return false;
			footprints.computeIfAbsent(footprint.componentRoot(), ignored -> new java.util.ArrayList<>())
				.add(footprint);
		}

		Set<Integer> expectedRoots = new HashSet<>();
		for (BitSet component : components) {
			int root = component.nextSetBit(0);
			expectedRoots.add(root);
			CubeOffset componentOffset = offsets.get(root);
			List<Footprint> componentFootprints = footprints.get(root);
			if (componentOffset == null || componentFootprints == null
				|| componentFootprints.size() != component.cardinality()
				|| !validFootprints(plane.workArea(), componentFootprints, false))
				return false;
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
				CubeOffset offset = offsets.get(cube);
				if (offset == null || !allowPerCubeOffsets
					&& (Math.abs(offset.x() - componentOffset.x()) > EPSILON
						|| Math.abs(offset.z() - componentOffset.z()) > EPSILON))
					return false;
			}
		}
		if (!footprints.keySet().equals(expectedRoots))
			return false;

		if (!allowComponentOverlap) {
			List<Integer> roots = List.copyOf(expectedRoots);
			for (int firstRoot = 0; firstRoot < roots.size(); firstRoot++)
				for (int secondRoot = firstRoot + 1; secondRoot < roots.size(); secondRoot++)
					for (Footprint first : footprints.get(roots.get(firstRoot)))
						for (Footprint second : footprints.get(roots.get(secondRoot)))
							if (first.overlapsStrictly(second))
								return false;
		}
		return doesNotOverlap(proposal.footprints(), occupiedFootprints);
	}

	private static boolean contains(Footprint envelope, Footprint footprint) {
		return footprint.minX() >= envelope.minX() - EPSILON
			&& footprint.minZ() >= envelope.minZ() - EPSILON
			&& footprint.maxX() <= envelope.maxX() + EPSILON
			&& footprint.maxZ() <= envelope.maxZ() + EPSILON;
	}

	public static boolean validStoredFootprint(Footprint footprint) {
		if (footprint == null || !Double.isFinite(footprint.minX()) || !Double.isFinite(footprint.minZ())
			|| !Double.isFinite(footprint.maxX()) || !Double.isFinite(footprint.maxZ())
			|| footprint.maxX() < footprint.minX() || footprint.maxZ() < footprint.minZ())
			return false;
		boolean snappedX = footprint.gridX() != UNSNAPPED;
		boolean snappedZ = footprint.gridZ() != UNSNAPPED;
		return snappedX == snappedZ;
	}

	private static boolean validFootprintBounds(SurgicalTablePlane.WorkArea area, Footprint footprint) {
		if (!validStoredFootprint(footprint)
			|| !area.contains(footprint.minX(), footprint.minZ(), footprint.maxX(), footprint.maxZ(), EPSILON))
			return false;
		return true;
	}

	private static boolean validFootprints(SurgicalTablePlane.WorkArea area, List<Footprint> footprints,
		boolean requireSnapped) {
		if (footprints.isEmpty())
			return false;
		Footprint first = footprints.getFirst();
		if (!validFootprintBounds(area, first))
			return false;
		boolean snapped = first.gridX() != UNSNAPPED;
		if (requireSnapped && !snapped)
			return false;
		double minX = first.minX();
		double minZ = first.minZ();
		double maxX = first.maxX();
		double maxZ = first.maxZ();
		for (int index = 1; index < footprints.size(); index++) {
			Footprint footprint = footprints.get(index);
			if (!validFootprintBounds(area, footprint)
				|| footprint.componentRoot() != first.componentRoot()
				|| (footprint.gridX() != UNSNAPPED) != snapped
				|| snapped && (footprint.gridX() != first.gridX() || footprint.gridZ() != first.gridZ()))
				return false;
			minX = Math.min(minX, footprint.minX());
			minZ = Math.min(minZ, footprint.minZ());
			maxX = Math.max(maxX, footprint.maxX());
			maxZ = Math.max(maxZ, footprint.maxZ());
		}
		if (!snapped)
			return true;
		double gridCenterX = gridCenter(first.gridX());
		double gridCenterZ = gridCenter(first.gridZ());
		return Math.abs((minX + maxX) * 0.5d - gridCenterX) <= EPSILON
			&& Math.abs((minZ + maxZ) * 0.5d - gridCenterZ) <= EPSILON
			&& gridCenterX >= area.minX() && gridCenterX < area.maxXExclusive()
			&& gridCenterZ >= area.minZ() && gridCenterZ < area.maxZExclusive();
	}

	private static boolean doesNotOverlap(List<Footprint> proposed, List<Footprint> occupied) {
		for (Footprint footprint : proposed)
			for (Footprint obstacle : occupied)
				if (footprint.overlapsStrictly(obstacle))
					return false;
		return true;
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
