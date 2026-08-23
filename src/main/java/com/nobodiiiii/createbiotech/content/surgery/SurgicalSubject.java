package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;

import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** One independently placeable and editable creature stored by a surgical-table controller. */
public final class SurgicalSubject {
	private static final String ID_TAG = "SubjectId";
	private static final String FACING_TAG = "PlacementFacing";
	private static final String PROFILE_TAG = "MimicProfile";
	private static final String CUBE_COUNT_TAG = "CubeCount";
	private static final String PRESENT_CUBES_TAG = "PresentCubes";
	private static final String SEAMS_TAG = "Seams";
	private static final String CUT_SEAMS_TAG = "CutSeams";
	private static final String LAST_CUT_SEAM_TAG = "LastCutSeam";
	private static final String CUT_ORDER_TAG = "CutOrder";
	private static final String ORIGIN_OFFSET_X_TAG = "OriginOffsetX";
	private static final String ORIGIN_OFFSET_Z_TAG = "OriginOffsetZ";
	private static final String OFFSET_CUBES_TAG = "OffsetCubes";
	private static final String OFFSET_X_TAG = "OffsetX";
	private static final String OFFSET_Z_TAG = "OffsetZ";
	private static final String FOOTPRINTS_TAG = "Footprints";
	private static final String FOOTPRINT_ROOT_TAG = "Root";
	private static final String FOOTPRINT_MIN_X_TAG = "MinX";
	private static final String FOOTPRINT_MIN_Z_TAG = "MinZ";
	private static final String FOOTPRINT_MAX_X_TAG = "MaxX";
	private static final String FOOTPRINT_MAX_Z_TAG = "MaxZ";
	private static final String FOOTPRINT_GRID_X_TAG = "GridX";
	private static final String FOOTPRINT_GRID_Z_TAG = "GridZ";

	private int id;
	private final MimicProfile profile;
	private final Direction placementFacing;
	int cubeCount;
	BitSet presentCubes;
	List<SurgicalAssembly.Seam> seams;
	BitSet cutSeams;
	List<Integer> cutOrder;
	private double originOffsetX;
	private double originOffsetZ;
	Map<Integer, Vec3> componentOffsets;
	List<SurgicalTableLayout.Footprint> occupiedFootprints;
	private int clientRenderRevision;

	SurgicalSubject(int id, MimicProfile profile, Direction placementFacing, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, List<Integer> cutOrder,
		double originOffsetX, double originOffsetZ, Map<Integer, Vec3> componentOffsets,
		List<SurgicalTableLayout.Footprint> occupiedFootprints) {
		this.id = id;
		this.profile = profile;
		this.placementFacing = horizontal(placementFacing);
		this.cubeCount = cubeCount;
		this.presentCubes = (BitSet) presentCubes.clone();
		this.seams = List.copyOf(seams);
		this.cutSeams = (BitSet) cutSeams.clone();
		this.cutOrder = List.copyOf(cutOrder);
		this.originOffsetX = originOffsetX;
		this.originOffsetZ = originOffsetZ;
		this.componentOffsets = Map.copyOf(componentOffsets);
		this.occupiedFootprints = List.copyOf(occupiedFootprints);
	}

	public int id() {
		return id;
	}

	void setId(int id) {
		this.id = id;
	}

	public MimicProfile profile() {
		return profile;
	}

	public Direction placementFacing() {
		return placementFacing;
	}

	public int cubeCount() {
		return cubeCount;
	}

	public boolean matchesObservedTopology(int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams) {
		return SurgicalAssembly.validTopology(observedCubeCount, observedSeams)
			&& (cubeCount == 0 || cubeCount == observedCubeCount && seams.equals(observedSeams));
	}

	public BitSet presentCubesForRender(int observedCubeCount) {
		if (cubeCount == 0) {
			BitSet all = new BitSet(observedCubeCount);
			if (observedCubeCount > 0)
				all.set(0, observedCubeCount);
			return all;
		}
		return (BitSet) presentCubes.clone();
	}

	public List<SurgicalAssembly.Seam> seams() {
		return seams;
	}

	public BitSet cutSeamsForRender() {
		return (BitSet) cutSeams.clone();
	}

	public List<Integer> cutOrderForRender() {
		return cutOrder;
	}

	public double originOffsetX() {
		return originOffsetX;
	}

	public double originOffsetZ() {
		return originOffsetZ;
	}

	public Map<Integer, Vec3> componentOffsetsForRender() {
		return componentOffsets;
	}

	public List<SurgicalTableLayout.Footprint> occupiedFootprints() {
		return occupiedFootprints;
	}

	public int clientRenderRevision() {
		return clientRenderRevision;
	}

	void setClientRenderRevision(int revision) {
		clientRenderRevision = revision;
	}

	void rebase(BlockPos previousController, BlockPos nextController) {
		originOffsetX += previousController.getX() - nextController.getX();
		originOffsetZ += previousController.getZ() - nextController.getZ();
	}

	boolean initializeOrMatchTopology(int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams) {
		if (!SurgicalAssembly.validTopology(observedCubeCount, observedSeams))
			return false;
		if (cubeCount != 0)
			return cubeCount == observedCubeCount && seams.equals(observedSeams);
		cubeCount = observedCubeCount;
		seams = List.copyOf(observedSeams);
		presentCubes.clear();
		presentCubes.set(0, cubeCount);
		cutSeams.clear();
		cutOrder = List.of();
		return true;
	}

	boolean validPresentCube(int cubeId) {
		return cubeId >= 0 && cubeId < cubeCount && presentCubes.get(cubeId);
	}

	void applyLayout(SurgicalTableLayout.Proposal proposal) {
		Map<Integer, Vec3> offsets = new HashMap<>();
		for (SurgicalTableLayout.CubeOffset offset : proposal.offsets()) {
			if (Math.abs(offset.x()) <= 1.0e-12d && Math.abs(offset.z()) <= 1.0e-12d)
				continue;
			offsets.put(offset.cubeId(), new Vec3(offset.x(), 0.0d, offset.z()));
		}
		componentOffsets = Map.copyOf(offsets);
		occupiedFootprints = proposal.footprints();
	}

	void removeComponent(BitSet component) {
		presentCubes.andNot(component);
		int packedRoot = component.nextSetBit(0);
		occupiedFootprints = occupiedFootprints.stream()
			.filter(footprint -> footprint.componentRoot() != packedRoot)
			.toList();
		if (!componentOffsets.isEmpty()) {
			Map<Integer, Vec3> retainedOffsets = new HashMap<>(componentOffsets);
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1))
				retainedOffsets.remove(cube);
			componentOffsets = Map.copyOf(retainedOffsets);
		}
	}

	boolean isEmpty() {
		return cubeCount > 0 && presentCubes.isEmpty();
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt(ID_TAG, id);
		tag.putInt(FACING_TAG, placementFacing.get3DDataValue());
		tag.put(PROFILE_TAG, profile.save());
		if (originOffsetX != 0.0d || originOffsetZ != 0.0d) {
			tag.putDouble(ORIGIN_OFFSET_X_TAG, originOffsetX);
			tag.putDouble(ORIGIN_OFFSET_Z_TAG, originOffsetZ);
		}
		if (!occupiedFootprints.isEmpty())
			tag.put(FOOTPRINTS_TAG, writeFootprints(occupiedFootprints));
		if (cubeCount > 0) {
			tag.putInt(CUBE_COUNT_TAG, cubeCount);
			tag.putLongArray(PRESENT_CUBES_TAG, presentCubes.toLongArray());
			tag.putIntArray(SEAMS_TAG, SurgicalAssembly.encodeSeams(seams));
			if (!cutSeams.isEmpty())
				tag.putLongArray(CUT_SEAMS_TAG, cutSeams.toLongArray());
			if (!cutOrder.isEmpty())
				tag.putIntArray(CUT_ORDER_TAG, cutOrder.stream().mapToInt(Integer::intValue).toArray());
			writeOffsets(tag);
		}
		return tag;
	}

	@Nullable
	static SurgicalSubject load(CompoundTag tag, int fallbackId, Direction fallbackFacing) {
		if (!tag.contains(PROFILE_TAG, Tag.TAG_COMPOUND))
			return null;
		MimicProfile profile = MimicProfile.load(tag.getCompound(PROFILE_TAG));
		if (profile == null)
			return null;
		int id = tag.contains(ID_TAG, Tag.TAG_ANY_NUMERIC) ? tag.getInt(ID_TAG) : fallbackId;
		Direction facing = tag.contains(FACING_TAG, Tag.TAG_ANY_NUMERIC)
			? horizontal(Direction.from3DDataValue(tag.getInt(FACING_TAG))) : horizontal(fallbackFacing);
		double originX = tag.contains(ORIGIN_OFFSET_X_TAG, Tag.TAG_ANY_NUMERIC)
			? tag.getDouble(ORIGIN_OFFSET_X_TAG) : 0.0d;
		double originZ = tag.contains(ORIGIN_OFFSET_Z_TAG, Tag.TAG_ANY_NUMERIC)
			? tag.getDouble(ORIGIN_OFFSET_Z_TAG) : 0.0d;
		if (!finiteOffset(originX) || !finiteOffset(originZ)) {
			originX = 0.0d;
			originZ = 0.0d;
		}

		int cubeCount = tag.getInt(CUBE_COUNT_TAG);
		List<SurgicalAssembly.Seam> seams = tag.contains(SEAMS_TAG, Tag.TAG_INT_ARRAY)
			? SurgicalAssembly.decodeSeams(tag.getIntArray(SEAMS_TAG)) : null;
		if (seams == null || !SurgicalAssembly.validTopology(cubeCount, seams)) {
			cubeCount = 0;
			seams = List.of();
		}
		BitSet present = cubeCount > 0 && tag.contains(PRESENT_CUBES_TAG, Tag.TAG_LONG_ARRAY)
			? BitSet.valueOf(tag.getLongArray(PRESENT_CUBES_TAG)) : new BitSet();
		BitSet cuts = cubeCount > 0 && tag.contains(CUT_SEAMS_TAG, Tag.TAG_LONG_ARRAY)
			? BitSet.valueOf(tag.getLongArray(CUT_SEAMS_TAG)) : new BitSet();
		if (present.length() > cubeCount)
			present.clear(cubeCount, present.length());
		if (cuts.length() > seams.size())
			cuts.clear(seams.size(), cuts.length());
		List<Integer> cutOrder = readCutOrder(tag, cubeCount, cuts, seams.size());
		Map<Integer, Vec3> offsets = readOffsets(tag, cubeCount, present);
		List<SurgicalTableLayout.Footprint> footprints = readFootprints(tag);
		return new SurgicalSubject(id, profile, facing, cubeCount, present, seams, cuts, cutOrder,
			originX, originZ, offsets, footprints);
	}

	private void writeOffsets(CompoundTag tag) {
		if (componentOffsets.isEmpty())
			return;
		List<Map.Entry<Integer, Vec3>> entries = componentOffsets.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList();
		int[] cubes = new int[entries.size()];
		long[] xOffsets = new long[entries.size()];
		long[] zOffsets = new long[entries.size()];
		for (int index = 0; index < entries.size(); index++) {
			Map.Entry<Integer, Vec3> entry = entries.get(index);
			cubes[index] = entry.getKey();
			xOffsets[index] = Double.doubleToRawLongBits(entry.getValue().x);
			zOffsets[index] = Double.doubleToRawLongBits(entry.getValue().z);
		}
		tag.putIntArray(OFFSET_CUBES_TAG, cubes);
		tag.putLongArray(OFFSET_X_TAG, xOffsets);
		tag.putLongArray(OFFSET_Z_TAG, zOffsets);
	}

	private static Map<Integer, Vec3> readOffsets(CompoundTag tag, int cubeCount, BitSet present) {
		if (cubeCount <= 0 || !tag.contains(OFFSET_CUBES_TAG, Tag.TAG_INT_ARRAY)
			|| !tag.contains(OFFSET_X_TAG, Tag.TAG_LONG_ARRAY) || !tag.contains(OFFSET_Z_TAG, Tag.TAG_LONG_ARRAY))
			return Map.of();
		int[] cubes = tag.getIntArray(OFFSET_CUBES_TAG);
		long[] xOffsets = tag.getLongArray(OFFSET_X_TAG);
		long[] zOffsets = tag.getLongArray(OFFSET_Z_TAG);
		if (cubes.length != xOffsets.length || cubes.length != zOffsets.length || cubes.length > cubeCount)
			return Map.of();
		Map<Integer, Vec3> loaded = new HashMap<>();
		for (int index = 0; index < cubes.length; index++) {
			double x = Double.longBitsToDouble(xOffsets[index]);
			double z = Double.longBitsToDouble(zOffsets[index]);
			if (cubes[index] < 0 || cubes[index] >= cubeCount || !present.get(cubes[index])
				|| !finiteOffset(x) || !finiteOffset(z)
				|| loaded.put(cubes[index], new Vec3(x, 0.0d, z)) != null)
				return Map.of();
		}
		return Map.copyOf(loaded);
	}

	private static List<Integer> readCutOrder(CompoundTag tag, int cubeCount, BitSet cuts, int seamCount) {
		if (cubeCount <= 0)
			return List.of();
		List<Integer> loaded = new ArrayList<>();
		if (tag.contains(CUT_ORDER_TAG, Tag.TAG_INT_ARRAY)) {
			for (int seamId : tag.getIntArray(CUT_ORDER_TAG))
				loaded.add(seamId);
		} else if (tag.contains(LAST_CUT_SEAM_TAG, Tag.TAG_ANY_NUMERIC)) {
			int legacyLast = tag.getInt(LAST_CUT_SEAM_TAG);
			for (int seamId = cuts.nextSetBit(0); seamId >= 0; seamId = cuts.nextSetBit(seamId + 1))
				if (seamId != legacyLast)
					loaded.add(seamId);
			loaded.add(legacyLast);
		}
		return SurgicalAssembly.normalizeCutOrder(loaded, cuts, seamCount);
	}

	private static ListTag writeFootprints(List<SurgicalTableLayout.Footprint> footprints) {
		ListTag encoded = new ListTag();
		for (SurgicalTableLayout.Footprint footprint : footprints) {
			CompoundTag entry = new CompoundTag();
			entry.putInt(FOOTPRINT_ROOT_TAG, footprint.componentRoot());
			entry.putDouble(FOOTPRINT_MIN_X_TAG, footprint.minX());
			entry.putDouble(FOOTPRINT_MIN_Z_TAG, footprint.minZ());
			entry.putDouble(FOOTPRINT_MAX_X_TAG, footprint.maxX());
			entry.putDouble(FOOTPRINT_MAX_Z_TAG, footprint.maxZ());
			entry.putInt(FOOTPRINT_GRID_X_TAG, footprint.gridX());
			entry.putInt(FOOTPRINT_GRID_Z_TAG, footprint.gridZ());
			encoded.add(entry);
		}
		return encoded;
	}

	private static List<SurgicalTableLayout.Footprint> readFootprints(CompoundTag tag) {
		if (!tag.contains(FOOTPRINTS_TAG, Tag.TAG_LIST))
			return List.of();
		ListTag encoded = tag.getList(FOOTPRINTS_TAG, Tag.TAG_COMPOUND);
		if (encoded.isEmpty() || encoded.size() > SurgicalAssembly.MAX_CUBES)
			return List.of();
		List<SurgicalTableLayout.Footprint> loaded = new ArrayList<>(encoded.size());
		for (int index = 0; index < encoded.size(); index++) {
			CompoundTag entry = encoded.getCompound(index);
			SurgicalTableLayout.Footprint footprint = new SurgicalTableLayout.Footprint(
				entry.getInt(FOOTPRINT_ROOT_TAG), entry.getDouble(FOOTPRINT_MIN_X_TAG),
				entry.getDouble(FOOTPRINT_MIN_Z_TAG), entry.getDouble(FOOTPRINT_MAX_X_TAG),
				entry.getDouble(FOOTPRINT_MAX_Z_TAG), entry.getInt(FOOTPRINT_GRID_X_TAG),
				entry.getInt(FOOTPRINT_GRID_Z_TAG));
			if (!SurgicalTableLayout.validStoredFootprint(footprint))
				return List.of();
			loaded.add(footprint);
		}
		return List.copyOf(loaded);
	}

	private static boolean finiteOffset(double value) {
		return Double.isFinite(value) && Math.abs(value) <= SurgicalTablePlane.MAX_TILES + 2.0d;
	}

	private static Direction horizontal(Direction direction) {
		return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
	}
}
