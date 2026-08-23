package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	private static final String PERSISTENT_ID_TAG = "PersistentId";
	private static final String FACING_TAG = "PlacementFacing";
	private static final String LAY_AXIS_TAG = "LayAxis";
	private static final String LAY_YAW_TAG = "LayYaw";
	private static final String LAY_TRANSLATE_X_TAG = "LayTranslateX";
	private static final String LAY_TRANSLATE_Y_TAG = "LayTranslateY";
	private static final String LAY_TRANSLATE_Z_TAG = "LayTranslateZ";
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
	private static final String OFFSET_Y_TAG = "OffsetY";
	private static final String OFFSET_Z_TAG = "OffsetZ";
	private static final String GLUE_JOINTS_TAG = "GlueJoints";
	private static final String FOOTPRINTS_TAG = "Footprints";
	private static final String FOOTPRINT_ROOT_TAG = "Root";
	private static final String FOOTPRINT_MIN_X_TAG = "MinX";
	private static final String FOOTPRINT_MIN_Z_TAG = "MinZ";
	private static final String FOOTPRINT_MAX_X_TAG = "MaxX";
	private static final String FOOTPRINT_MAX_Z_TAG = "MaxZ";
	private static final String FOOTPRINT_GRID_X_TAG = "GridX";
	private static final String FOOTPRINT_GRID_Z_TAG = "GridZ";

	private int id;
	private final UUID persistentId;
	private final MimicProfile profile;
	private Direction placementFacing;
	private SurgicalLayPose layPose;
	int cubeCount;
	BitSet presentCubes;
	List<SurgicalAssembly.Seam> seams;
	BitSet cutSeams;
	List<Integer> cutOrder;
	private double originOffsetX;
	private double originOffsetZ;
	Map<Integer, Vec3> componentOffsets;
	List<SurgicalTableLayout.Footprint> occupiedFootprints;
	List<SurgicalGlueJoint> glueJoints;
	private int clientRenderRevision;

	SurgicalSubject(int id, MimicProfile profile, Direction placementFacing, SurgicalLayPose layPose, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, List<Integer> cutOrder,
		double originOffsetX, double originOffsetZ, Map<Integer, Vec3> componentOffsets,
		List<SurgicalTableLayout.Footprint> occupiedFootprints) {
		this(id, UUID.randomUUID(), profile, placementFacing, layPose, cubeCount, presentCubes, seams, cutSeams, cutOrder,
			originOffsetX, originOffsetZ, componentOffsets, occupiedFootprints, List.of());
	}

	SurgicalSubject(int id, UUID persistentId, MimicProfile profile, Direction placementFacing,
		SurgicalLayPose layPose, int cubeCount,
		BitSet presentCubes, List<SurgicalAssembly.Seam> seams, BitSet cutSeams, List<Integer> cutOrder,
		double originOffsetX, double originOffsetZ, Map<Integer, Vec3> componentOffsets,
		List<SurgicalTableLayout.Footprint> occupiedFootprints, List<SurgicalGlueJoint> glueJoints) {
		this.id = id;
		this.persistentId = persistentId;
		this.profile = profile;
		this.placementFacing = horizontal(placementFacing);
		this.layPose = layPose == null ? SurgicalLayPose.IDENTITY : layPose;
		this.cubeCount = cubeCount;
		this.presentCubes = (BitSet) presentCubes.clone();
		this.seams = List.copyOf(seams);
		this.cutSeams = (BitSet) cutSeams.clone();
		this.cutOrder = List.copyOf(cutOrder);
		this.originOffsetX = originOffsetX;
		this.originOffsetZ = originOffsetZ;
		this.componentOffsets = Map.copyOf(componentOffsets);
		this.occupiedFootprints = List.copyOf(occupiedFootprints);
		this.glueJoints = List.copyOf(glueJoints);
	}

	public int id() {
		return id;
	}

	public UUID persistentId() {
		return persistentId;
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

	public SurgicalLayPose layPose() {
		return layPose;
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

	public boolean containsFootprint(BitSet cubes, SurgicalTableLayout.Footprint footprint) {
		if (cubes == null || footprint == null)
			return false;
		int root = footprint.componentRoot();
		return root == -1 ? cubes.equals(presentCubes) : root >= 0 && cubes.get(root);
	}

	public List<SurgicalGlueJoint> glueJoints() {
		return glueJoints;
	}

	public boolean hasGlueConnection(int cubeId) {
		if (!validPresentCube(cubeId))
			return false;
		for (SurgicalGlueJoint joint : glueJoints)
			if (joint.touches(persistentId, cubeId))
				return true;
		return false;
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
			double existingY = componentOffsets.getOrDefault(offset.cubeId(), Vec3.ZERO).y;
			if (Math.abs(offset.x()) <= 1.0e-12d && Math.abs(existingY) <= 1.0e-12d
				&& Math.abs(offset.z()) <= 1.0e-12d)
				continue;
			offsets.put(offset.cubeId(), new Vec3(offset.x(), existingY, offset.z()));
		}
		componentOffsets = Map.copyOf(offsets);
		occupiedFootprints = proposal.footprints();
	}

	void removeComponent(BitSet component) {
		presentCubes.andNot(component);
		java.util.Set<Integer> packedRoots = new java.util.HashSet<>();
		for (BitSet nativeComponent : SurgicalAssembly.components(cubeCount, component, seams, cutSeams))
			packedRoots.add(nativeComponent.nextSetBit(0));
		occupiedFootprints = occupiedFootprints.stream()
			.filter(footprint -> !packedRoots.contains(footprint.componentRoot()))
			.toList();
		if (!componentOffsets.isEmpty()) {
			Map<Integer, Vec3> retainedOffsets = new HashMap<>(componentOffsets);
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1))
				retainedOffsets.remove(cube);
			componentOffsets = Map.copyOf(retainedOffsets);
		}
	}

	BitSet componentContaining(int cubeId) {
		return SurgicalAssembly.componentContaining(cubeCount, presentCubes, seams, cutSeams, cubeId);
	}

	void translateComponent(BitSet component, Vec3 delta) {
		Map<Integer, Vec3> translated = new HashMap<>(componentOffsets);
		for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
			Vec3 offset = translated.getOrDefault(cube, Vec3.ZERO).add(delta);
			if (offset.lengthSqr() <= 1.0e-24d)
				translated.remove(cube);
			else
				translated.put(cube, offset);
		}
		componentOffsets = Map.copyOf(translated);
		if (Math.abs(delta.x) <= 1.0e-12d && Math.abs(delta.z) <= 1.0e-12d)
			return;
		java.util.Set<Integer> movedRoots = new java.util.HashSet<>();
		for (BitSet nativeComponent : SurgicalAssembly.components(cubeCount, component, seams, cutSeams))
			movedRoots.add(nativeComponent.nextSetBit(0));
		occupiedFootprints = occupiedFootprints.stream().map(footprint -> movedRoots.contains(footprint.componentRoot())
			? new SurgicalTableLayout.Footprint(footprint.componentRoot(), footprint.minX() + delta.x,
				footprint.minZ() + delta.z, footprint.maxX() + delta.x, footprint.maxZ() + delta.z,
				SurgicalTableLayout.UNSNAPPED, SurgicalTableLayout.UNSNAPPED)
			: footprint).toList();
	}

	void applyComponentOffsets(BitSet component, Map<Integer, Vec3> offsets) {
		if (offsets.size() != component.cardinality())
			throw new IllegalArgumentException("Incomplete surgical component offsets");
		Map<Integer, Vec3> updated = new HashMap<>(componentOffsets);
		for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1)) {
			Vec3 offset = offsets.get(cube);
			if (offset == null)
				throw new IllegalArgumentException("Missing surgical component offset");
			if (offset.lengthSqr() <= 1.0e-24d)
				updated.remove(cube);
			else
				updated.put(cube, offset);
		}
		componentOffsets = Map.copyOf(updated);
	}

	void addGlueJoint(SurgicalGlueJoint joint) {
		if (glueJoints.contains(joint))
			return;
		List<SurgicalGlueJoint> updated = new ArrayList<>(glueJoints);
		updated.add(joint);
		glueJoints = List.copyOf(updated);
	}

	void removeGlueJoints(java.util.Set<SurgicalGlueJoint> removed) {
		if (!removed.isEmpty())
			glueJoints = glueJoints.stream().filter(joint -> !removed.contains(joint)).toList();
	}

	void replaceGlueJoints(List<SurgicalGlueJoint> joints) {
		glueJoints = List.copyOf(joints);
	}

	SurgicalSubject extract(int extractedId, BitSet extracted) {
		BitSet selected = (BitSet) extracted.clone();
		selected.and(presentCubes);
		if (selected.isEmpty() || selected.equals(presentCubes))
			throw new IllegalArgumentException("A surgical extraction must be a proper non-empty subset");
		Map<Integer, Vec3> extractedOffsets = new HashMap<>();
		for (int cube = selected.nextSetBit(0); cube >= 0; cube = selected.nextSetBit(cube + 1)) {
			Vec3 offset = componentOffsets.get(cube);
			if (offset != null)
				extractedOffsets.put(cube, offset);
		}
		List<SurgicalTableLayout.Footprint> extractedFootprints = occupiedFootprints.stream()
			.filter(footprint -> containsFootprint(selected, footprint)).toList();
		SurgicalSubject result = new SurgicalSubject(extractedId, profile, placementFacing, layPose,
			cubeCount, selected, seams, cutSeams, cutOrder, originOffsetX, originOffsetZ,
			extractedOffsets, extractedFootprints);
		removeComponent(selected);
		return result;
	}

	void applyGlueMove(Direction targetFacing, SurgicalLayPose targetPose, Map<Integer, Vec3> offsets,
		List<SurgicalTableLayout.Footprint> footprints) {
		if (targetPose == null || offsets.size() != presentCubes.cardinality())
			throw new IllegalArgumentException("Incomplete surgical glue move");
		for (int cube = presentCubes.nextSetBit(0); cube >= 0; cube = presentCubes.nextSetBit(cube + 1))
			if (!offsets.containsKey(cube))
				throw new IllegalArgumentException("Missing surgical glue cube offset");
		placementFacing = horizontal(targetFacing);
		layPose = targetPose;
		componentOffsets = Map.copyOf(offsets);
		occupiedFootprints = List.copyOf(footprints);
	}

	boolean isEmpty() {
		return cubeCount > 0 && presentCubes.isEmpty();
	}

	CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt(ID_TAG, id);
		tag.putUUID(PERSISTENT_ID_TAG, persistentId);
		tag.putInt(FACING_TAG, placementFacing.get3DDataValue());
		tag.putInt(LAY_AXIS_TAG, layPose.axis().ordinal());
		tag.putInt(LAY_YAW_TAG, layPose.yaw());
		tag.putDouble(LAY_TRANSLATE_X_TAG, layPose.translation().x);
		tag.putDouble(LAY_TRANSLATE_Y_TAG, layPose.translation().y);
		tag.putDouble(LAY_TRANSLATE_Z_TAG, layPose.translation().z);
		tag.put(PROFILE_TAG, profile.save());
		if (originOffsetX != 0.0d || originOffsetZ != 0.0d) {
			tag.putDouble(ORIGIN_OFFSET_X_TAG, originOffsetX);
			tag.putDouble(ORIGIN_OFFSET_Z_TAG, originOffsetZ);
		}
		if (!occupiedFootprints.isEmpty())
			tag.put(FOOTPRINTS_TAG, writeFootprints(occupiedFootprints));
		if (!glueJoints.isEmpty()) {
			ListTag encodedJoints = new ListTag();
			for (SurgicalGlueJoint joint : glueJoints)
				encodedJoints.add(joint.save());
			tag.put(GLUE_JOINTS_TAG, encodedJoints);
		}
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
		UUID persistentId = tag.hasUUID(PERSISTENT_ID_TAG) ? tag.getUUID(PERSISTENT_ID_TAG) : UUID.randomUUID();
		Direction facing = tag.contains(FACING_TAG, Tag.TAG_ANY_NUMERIC)
			? horizontal(Direction.from3DDataValue(tag.getInt(FACING_TAG))) : horizontal(fallbackFacing);
		SurgicalLayPose layPose = readLayPose(tag);
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
		List<SurgicalGlueJoint> glueJoints = readGlueJoints(tag);
		return new SurgicalSubject(id, persistentId, profile, facing, layPose, cubeCount, present, seams, cuts, cutOrder,
			originX, originZ, offsets, footprints, glueJoints);
	}

	private static SurgicalLayPose readLayPose(CompoundTag tag) {
		if (!tag.contains(LAY_AXIS_TAG, Tag.TAG_ANY_NUMERIC)
			|| !tag.contains(LAY_YAW_TAG, Tag.TAG_ANY_NUMERIC))
			return SurgicalLayPose.IDENTITY;
		int axisId = tag.getInt(LAY_AXIS_TAG);
		if (axisId < 0 || axisId >= SurgicalLayPose.RotationAxis.values().length)
			return SurgicalLayPose.IDENTITY;
		try {
			return new SurgicalLayPose(SurgicalLayPose.RotationAxis.values()[axisId], tag.getInt(LAY_YAW_TAG),
				new Vec3(tag.getDouble(LAY_TRANSLATE_X_TAG), tag.getDouble(LAY_TRANSLATE_Y_TAG),
					tag.getDouble(LAY_TRANSLATE_Z_TAG)));
		} catch (IllegalArgumentException ignored) {
			return SurgicalLayPose.IDENTITY;
		}
	}

	private void writeOffsets(CompoundTag tag) {
		if (componentOffsets.isEmpty())
			return;
		List<Map.Entry<Integer, Vec3>> entries = componentOffsets.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList();
		int[] cubes = new int[entries.size()];
		long[] xOffsets = new long[entries.size()];
		long[] yOffsets = new long[entries.size()];
		long[] zOffsets = new long[entries.size()];
		for (int index = 0; index < entries.size(); index++) {
			Map.Entry<Integer, Vec3> entry = entries.get(index);
			cubes[index] = entry.getKey();
			xOffsets[index] = Double.doubleToRawLongBits(entry.getValue().x);
			yOffsets[index] = Double.doubleToRawLongBits(entry.getValue().y);
			zOffsets[index] = Double.doubleToRawLongBits(entry.getValue().z);
		}
		tag.putIntArray(OFFSET_CUBES_TAG, cubes);
		tag.putLongArray(OFFSET_X_TAG, xOffsets);
		tag.putLongArray(OFFSET_Y_TAG, yOffsets);
		tag.putLongArray(OFFSET_Z_TAG, zOffsets);
	}

	private static Map<Integer, Vec3> readOffsets(CompoundTag tag, int cubeCount, BitSet present) {
		if (cubeCount <= 0 || !tag.contains(OFFSET_CUBES_TAG, Tag.TAG_INT_ARRAY)
			|| !tag.contains(OFFSET_X_TAG, Tag.TAG_LONG_ARRAY) || !tag.contains(OFFSET_Z_TAG, Tag.TAG_LONG_ARRAY))
			return Map.of();
		int[] cubes = tag.getIntArray(OFFSET_CUBES_TAG);
		long[] xOffsets = tag.getLongArray(OFFSET_X_TAG);
		long[] yOffsets = tag.contains(OFFSET_Y_TAG, Tag.TAG_LONG_ARRAY)
			? tag.getLongArray(OFFSET_Y_TAG) : new long[cubes.length];
		long[] zOffsets = tag.getLongArray(OFFSET_Z_TAG);
		if (cubes.length != xOffsets.length || cubes.length != yOffsets.length
			|| cubes.length != zOffsets.length || cubes.length > cubeCount)
			return Map.of();
		Map<Integer, Vec3> loaded = new HashMap<>();
		for (int index = 0; index < cubes.length; index++) {
			double x = Double.longBitsToDouble(xOffsets[index]);
			double y = Double.longBitsToDouble(yOffsets[index]);
			double z = Double.longBitsToDouble(zOffsets[index]);
			if (cubes[index] < 0 || cubes[index] >= cubeCount || !present.get(cubes[index])
				|| !finiteOffset(x) || !finiteOffset(y) || !finiteOffset(z)
				|| loaded.put(cubes[index], new Vec3(x, y, z)) != null)
				return Map.of();
		}
		return Map.copyOf(loaded);
	}

	private static List<SurgicalGlueJoint> readGlueJoints(CompoundTag tag) {
		if (!tag.contains(GLUE_JOINTS_TAG, Tag.TAG_LIST))
			return List.of();
		ListTag encoded = tag.getList(GLUE_JOINTS_TAG, Tag.TAG_COMPOUND);
		if (encoded.size() > SurgicalAssembly.MAX_SEAMS)
			return List.of();
		List<SurgicalGlueJoint> joints = new ArrayList<>();
		for (int index = 0; index < encoded.size(); index++) {
			SurgicalGlueJoint joint = SurgicalGlueJoint.load(encoded.getCompound(index));
			if (joint != null && !joints.contains(joint))
				joints.add(joint);
		}
		return List.copyOf(joints);
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
