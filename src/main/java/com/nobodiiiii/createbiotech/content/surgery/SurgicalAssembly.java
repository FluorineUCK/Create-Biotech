package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

/** Immutable, side-safe topology and source-model state for one packed surgical body. */
public final class SurgicalAssembly {
	public static final int MAX_CUBES = 1024;
	public static final int MAX_SEAMS = 4096;
	public static final int MAX_SOURCES = 256;
	private static final int CURRENT_VERSION = 8;
	private static final String VERSION_TAG = "Version";
	private static final String PROFILE_TAG = "MimicProfile";
	private static final String CUBE_COUNT_TAG = "CubeCount";
	private static final String PRESENT_CUBES_TAG = "PresentCubes";
	private static final String SEAMS_TAG = "Seams";
	private static final String CUT_SEAMS_TAG = "CutSeams";
	private static final String CUT_ORDER_TAG = "CutOrder";
	private static final String SOURCES_TAG = "Sources";
	private static final String JOINTS_TAG = "GlueJoints";
	private static final String COMBINATIONS_TAG = "Combinations";
	private static final String COMBINATION_ID_TAG = "Id";
	private static final String COMBINATION_MEMBERS_TAG = "Members";
	private static final String MEMBER_SOURCE_TAG = "Source";
	private static final String MEMBER_CUBE_TAG = "Cube";
	private static final String LIMBS_TAG = "Limbs";
	private static final String LIMB_TYPE_TAG = "Type";
	private static final String LIMB_CHILD_SOURCE_TAG = "ChildSource";
	private static final String LIMB_CHILD_CUBE_TAG = "ChildCube";
	private static final String LIMB_PARENT_SOURCE_TAG = "ParentSource";
	private static final String LIMB_PARENT_CUBE_TAG = "ParentCube";
	private static final String PRESERVE_LAYOUT_TAG = "PreserveLayout";
	private static final String LAYOUT_FACING_TAG = "LayoutFacing";
	private static final String LAYOUT_LAY_POSE_TAG = "LayoutLayPose";
	private static final String FACING_TAG = "Facing";
	private static final String LAY_POSE_TAG = "LayPose";
	private static final String POSE_AXIS_TAG = "Axis";
	private static final String POSE_YAW_TAG = "Yaw";
	private static final String POSE_X_TAG = "TranslateX";
	private static final String POSE_Y_TAG = "TranslateY";
	private static final String POSE_Z_TAG = "TranslateZ";
	private static final String ORIGIN_X_TAG = "OriginX";
	private static final String ORIGIN_Y_TAG = "OriginY";
	private static final String ORIGIN_Z_TAG = "OriginZ";
	private static final String OFFSET_CUBES_TAG = "OffsetCubes";
	private static final String OFFSET_X_TAG = "OffsetX";
	private static final String OFFSET_Y_TAG = "OffsetY";
	private static final String OFFSET_Z_TAG = "OffsetZ";
	private static final String ROTATIONS_TAG = "CubeRotations";
	private static final String ROTATION_CUBE_TAG = "Cube";
	private static final String ROTATION_VALUE_TAG = "Rotation";
	private static final String FIRST_SOURCE_TAG = "FirstSource";
	private static final String FIRST_CUBE_TAG = "FirstCube";
	private static final String SECOND_SOURCE_TAG = "SecondSource";
	private static final String SECOND_CUBE_TAG = "SecondCube";

	private final List<Source> sources;
	private final List<Joint> joints;
	private final List<Combination> combinations;
	private final List<Limb> limbs;
	private final boolean preserveLayout;
	private final Direction layoutFacing;
	private final SurgicalLayPose layoutLayPose;

	private SurgicalAssembly(List<Source> sources, List<Joint> joints, List<Combination> combinations,
		List<Limb> limbs, boolean preserveLayout,
		Direction layoutFacing, SurgicalLayPose layoutLayPose) {
		this.sources = List.copyOf(sources);
		this.joints = List.copyOf(joints);
		this.combinations = List.copyOf(combinations);
		this.limbs = List.copyOf(limbs);
		this.preserveLayout = preserveLayout;
		this.layoutFacing = horizontal(layoutFacing);
		this.layoutLayPose = layoutLayPose == null ? SurgicalLayPose.IDENTITY : layoutLayPose;
	}

	@Nullable
	public static SurgicalAssembly create(MimicProfile profile, int cubeCount, BitSet presentCubes,
		List<Seam> seams, BitSet cutSeams) {
		return create(profile, cubeCount, presentCubes, seams, cutSeams, List.of());
	}

	@Nullable
	public static SurgicalAssembly create(MimicProfile profile, int cubeCount, BitSet presentCubes,
		List<Seam> seams, BitSet cutSeams, List<Integer> cutOrder) {
		Source source = Source.create(profile, cubeCount, presentCubes, seams, cutSeams, cutOrder,
			Direction.NORTH, SurgicalLayPose.IDENTITY, Vec3.ZERO, Map.of());
		return source == null ? null
			: new SurgicalAssembly(List.of(source), List.of(), List.of(), false, Direction.NORTH,
				SurgicalLayPose.IDENTITY);
	}

	@Nullable
	public static SurgicalAssembly createComposite(List<Source> sources, List<Joint> joints) {
		return createComposite(sources, joints, List.of(), inferLayoutFacing(sources), inferLayoutLayPose(sources));
	}

	@Nullable
	public static SurgicalAssembly createComposite(List<Source> sources, List<Joint> joints,
		Direction layoutFacing) {
		return createComposite(sources, joints, List.of(), layoutFacing, inferLayoutLayPose(sources));
	}

	@Nullable
	public static SurgicalAssembly createComposite(List<Source> sources, List<Joint> joints,
		Direction layoutFacing, SurgicalLayPose layoutLayPose) {
		return createComposite(sources, joints, List.of(), layoutFacing, layoutLayPose);
	}

	@Nullable
	public static SurgicalAssembly createComposite(List<Source> sources, List<Joint> joints,
		List<Combination> combinations, Direction layoutFacing, SurgicalLayPose layoutLayPose) {
		if (sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES || joints == null
			|| joints.size() > MAX_SEAMS || combinations == null || combinations.size() > MAX_CUBES)
			return null;
		List<Source> frozenSources = new ArrayList<>(sources.size());
		int totalCubes = 0;
		for (Source source : sources) {
			if (source == null || !source.valid())
				return null;
			totalCubes += source.presentCubes.cardinality();
			if (totalCubes > MAX_CUBES)
				return null;
			frozenSources.add(source.copy());
		}
		Set<Joint> unique = new HashSet<>();
		List<Joint> frozenJoints = new ArrayList<>(joints.size());
		for (Joint joint : joints) {
			Joint normalized = joint == null ? null : joint.normalized();
			if (normalized == null || !normalized.validFor(frozenSources) || !unique.add(normalized))
				return null;
			frozenJoints.add(normalized);
		}
		Set<UUID> combinationIds = new HashSet<>();
		Set<CombinationMember> combinedMembers = new HashSet<>();
		List<Combination> frozenCombinations = new ArrayList<>(combinations.size());
		for (Combination combination : combinations) {
			Combination normalized = combination == null ? null : combination.normalized();
			if (normalized == null || !normalized.validFor(frozenSources)
				|| !combinationIds.add(normalized.id()))
				return null;
			for (CombinationMember member : normalized.members())
				if (!combinedMembers.add(member))
					return null;
			frozenCombinations.add(normalized);
		}
		return new SurgicalAssembly(frozenSources, frozenJoints, frozenCombinations, true,
			layoutFacing, layoutLayPose);
	}

	@Nullable
	public static SurgicalAssembly load(CompoundTag tag) {
		int version = tag.getInt(VERSION_TAG);
		if (version == 1 || version == 2)
			return loadLegacy(tag, version);
		if (version < 3 || version > CURRENT_VERSION || !tag.contains(SOURCES_TAG, Tag.TAG_LIST))
			return null;

		ListTag encodedSources = tag.getList(SOURCES_TAG, Tag.TAG_COMPOUND);
		if (encodedSources.isEmpty() || encodedSources.size() > MAX_SOURCES)
			return null;
		List<Source> sources = new ArrayList<>(encodedSources.size());
		for (int index = 0; index < encodedSources.size(); index++) {
			Source source = Source.load(encodedSources.getCompound(index));
			if (source == null)
				return null;
			sources.add(source);
		}

		List<Joint> joints = new ArrayList<>();
		if (tag.contains(JOINTS_TAG, Tag.TAG_LIST)) {
			ListTag encodedJoints = tag.getList(JOINTS_TAG, Tag.TAG_COMPOUND);
			if (encodedJoints.size() > MAX_SEAMS)
				return null;
			for (int index = 0; index < encodedJoints.size(); index++) {
				CompoundTag encoded = encodedJoints.getCompound(index);
				joints.add(new Joint(encoded.getInt(FIRST_SOURCE_TAG), encoded.getInt(FIRST_CUBE_TAG),
					encoded.getInt(SECOND_SOURCE_TAG), encoded.getInt(SECOND_CUBE_TAG)));
			}
		}
		List<Combination> combinations = new ArrayList<>();
		if (version >= 7 && tag.contains(COMBINATIONS_TAG, Tag.TAG_LIST)) {
			ListTag encodedCombinations = tag.getList(COMBINATIONS_TAG, Tag.TAG_COMPOUND);
			if (encodedCombinations.size() > MAX_CUBES)
				return null;
			for (int index = 0; index < encodedCombinations.size(); index++) {
				CompoundTag encoded = encodedCombinations.getCompound(index);
				if (!encoded.hasUUID(COMBINATION_ID_TAG)
					|| !encoded.contains(COMBINATION_MEMBERS_TAG, Tag.TAG_LIST))
					return null;
				ListTag encodedMembers = encoded.getList(COMBINATION_MEMBERS_TAG, Tag.TAG_COMPOUND);
				if (encodedMembers.size() < 2 || encodedMembers.size() > MAX_CUBES)
					return null;
				List<CombinationMember> members = new ArrayList<>(encodedMembers.size());
				for (int memberIndex = 0; memberIndex < encodedMembers.size(); memberIndex++) {
					CompoundTag member = encodedMembers.getCompound(memberIndex);
					if (!member.contains(MEMBER_SOURCE_TAG, Tag.TAG_ANY_NUMERIC)
						|| !member.contains(MEMBER_CUBE_TAG, Tag.TAG_ANY_NUMERIC))
						return null;
					members.add(new CombinationMember(member.getInt(MEMBER_SOURCE_TAG),
						member.getInt(MEMBER_CUBE_TAG)));
				}
				combinations.add(new Combination(encoded.getUUID(COMBINATION_ID_TAG), members));
			}
		}
		Direction layoutFacing = version >= 4 && tag.contains(LAYOUT_FACING_TAG, Tag.TAG_ANY_NUMERIC)
			? Direction.from3DDataValue(tag.getInt(LAYOUT_FACING_TAG)) : inferLayoutFacing(sources);
		SurgicalLayPose layoutLayPose = version >= 5 && tag.contains(LAYOUT_LAY_POSE_TAG, Tag.TAG_COMPOUND)
			? readLayPose(tag.getCompound(LAYOUT_LAY_POSE_TAG)) : inferLayoutLayPose(sources);
		SurgicalAssembly assembly = createComposite(sources, joints, combinations, layoutFacing, layoutLayPose);
		if (assembly == null)
			return null;
		return tag.getBoolean(PRESERVE_LAYOUT_TAG) ? assembly
			: new SurgicalAssembly(assembly.sources, assembly.joints, assembly.combinations, false,
				assembly.layoutFacing,
				assembly.layoutLayPose);
	}

	@Nullable
	private static SurgicalAssembly loadLegacy(CompoundTag tag, int version) {
		if (!tag.contains(PROFILE_TAG, Tag.TAG_COMPOUND)
			|| !tag.contains(CUBE_COUNT_TAG, Tag.TAG_ANY_NUMERIC)
			|| !tag.contains(PRESENT_CUBES_TAG, Tag.TAG_LONG_ARRAY)
			|| !tag.contains(SEAMS_TAG, Tag.TAG_INT_ARRAY))
			return null;
		MimicProfile profile = MimicProfile.load(tag.getCompound(PROFILE_TAG));
		int cubeCount = tag.getInt(CUBE_COUNT_TAG);
		List<Seam> seams = decodeSeams(tag.getIntArray(SEAMS_TAG));
		if (profile == null || seams == null || !validTopology(cubeCount, seams))
			return null;
		BitSet present = BitSet.valueOf(tag.getLongArray(PRESENT_CUBES_TAG));
		BitSet cut = tag.contains(CUT_SEAMS_TAG, Tag.TAG_LONG_ARRAY)
			? BitSet.valueOf(tag.getLongArray(CUT_SEAMS_TAG)) : new BitSet();
		List<Integer> cutOrder = version >= 2 && tag.contains(CUT_ORDER_TAG, Tag.TAG_INT_ARRAY)
			? decodeCutOrder(tag.getIntArray(CUT_ORDER_TAG)) : List.of();
		return create(profile, cubeCount, present, seams, cut, cutOrder);
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putInt(VERSION_TAG, CURRENT_VERSION);
		ListTag encodedSources = new ListTag();
		for (Source source : sources)
			encodedSources.add(source.save());
		tag.put(SOURCES_TAG, encodedSources);
		if (!joints.isEmpty()) {
			ListTag encodedJoints = new ListTag();
			for (Joint joint : joints) {
				CompoundTag encoded = new CompoundTag();
				encoded.putInt(FIRST_SOURCE_TAG, joint.firstSource);
				encoded.putInt(FIRST_CUBE_TAG, joint.firstCube);
				encoded.putInt(SECOND_SOURCE_TAG, joint.secondSource);
				encoded.putInt(SECOND_CUBE_TAG, joint.secondCube);
				encodedJoints.add(encoded);
			}
			tag.put(JOINTS_TAG, encodedJoints);
		}
		if (!combinations.isEmpty()) {
			ListTag encodedCombinations = new ListTag();
			for (Combination combination : combinations) {
				CompoundTag encoded = new CompoundTag();
				encoded.putUUID(COMBINATION_ID_TAG, combination.id());
				ListTag encodedMembers = new ListTag();
				for (CombinationMember member : combination.members()) {
					CompoundTag encodedMember = new CompoundTag();
					encodedMember.putInt(MEMBER_SOURCE_TAG, member.source());
					encodedMember.putInt(MEMBER_CUBE_TAG, member.cube());
					encodedMembers.add(encodedMember);
				}
				encoded.put(COMBINATION_MEMBERS_TAG, encodedMembers);
				encodedCombinations.add(encoded);
			}
			tag.put(COMBINATIONS_TAG, encodedCombinations);
		}
		if (preserveLayout)
			tag.putBoolean(PRESERVE_LAYOUT_TAG, true);
		tag.putInt(LAYOUT_FACING_TAG, layoutFacing.get3DDataValue());
		tag.put(LAYOUT_LAY_POSE_TAG, writeLayPose(layoutLayPose));
		return tag;
	}

	public List<Source> sources() { return sources; }
	public List<Joint> joints() { return joints; }
	public List<Combination> combinations() { return combinations; }
	public boolean preservesLayout() { return preserveLayout; }
	public Direction layoutFacing() { return layoutFacing; }
	public SurgicalLayPose layoutLayPose() { return layoutLayPose; }

	public SurgicalLayPose placedLayPose(Direction placementFacing) {
		return layoutLayPose.rotateClockwise(clockwiseTurns(layoutFacing, horizontal(placementFacing)));
	}

	/** Rotates every stored source from the packed layout frame into a new table-facing frame. */
	public List<PlacedSource> placedSources(Direction placementFacing) {
		int turns = clockwiseTurns(layoutFacing, horizontal(placementFacing));
		List<PlacedSource> placed = new ArrayList<>(sources.size());
		for (Source source : sources) {
			Map<Integer, Vec3> offsets = new HashMap<>();
			for (Map.Entry<Integer, Vec3> entry : source.cubeOffsets.entrySet())
				offsets.put(entry.getKey(), rotateClockwise(entry.getValue(), turns));
			Map<Integer, SurgicalCubeRotation> rotations = new HashMap<>();
			for (Map.Entry<Integer, SurgicalCubeRotation> entry : source.cubeRotations.entrySet())
				rotations.put(entry.getKey(), entry.getValue().rotateClockwise(turns));
			placed.add(new PlacedSource(source, rotateClockwise(source.facing, turns),
				source.layPose.rotateClockwise(turns),
				rotateClockwise(source.originOffset, turns), offsets, rotations));
		}
		return List.copyOf(placed);
	}

	/** Legacy single-source view retained for ordinary assemblies. */
	public MimicProfile profile() { return sources.getFirst().profile; }
	public int cubeCount() { return sources.getFirst().cubeCount; }
	public boolean containsCube(int cubeId) { return sources.getFirst().containsCube(cubeId); }
	public boolean isSeamCut(int seamId) { return sources.getFirst().isSeamCut(seamId); }
	public BitSet presentCubes() { return sources.getFirst().presentCubes(); }
	public List<Seam> seams() { return sources.getFirst().seams; }
	public BitSet cutSeams() { return sources.getFirst().cutSeams(); }
	public List<Integer> cutOrder() { return sources.getFirst().cutOrder; }
	public Map<Integer, SurgicalCubeRotation> cubeRotations() { return sources.getFirst().cubeRotations; }
	public boolean validCubeId(int cubeId) { return sources.getFirst().validCubeId(cubeId); }

	public BitSet componentContaining(int cubeId) {
		Source source = sources.getFirst();
		return componentContaining(source.cubeCount, source.presentCubes, source.seams, source.cutSeams, cubeId);
	}

	public static boolean validCubeCount(int cubeCount) {
		return cubeCount > 0 && cubeCount <= MAX_CUBES;
	}

	public static boolean validTopology(int cubeCount, List<Seam> seams) {
		if (!validCubeCount(cubeCount) || seams == null
			|| seams.size() > Math.min(MAX_SEAMS, cubeCount * (cubeCount - 1) / 2))
			return false;
		if (cubeCount == 1)
			return seams.isEmpty();
		Set<Long> unique = new HashSet<>();
		for (Seam seam : seams) {
			if (seam == null || seam.first < 0 || seam.second >= cubeCount || seam.first >= seam.second)
				return false;
			long key = ((long) seam.first << 32) | (seam.second & 0xffffffffL);
			if (!unique.add(key))
				return false;
		}
		return true;
	}

	public static BitSet componentContaining(int cubeCount, BitSet presentCubes, List<Seam> seams,
		BitSet cutSeams, int startCube) {
		if (startCube < 0 || startCube >= cubeCount || !presentCubes.get(startCube))
			return new BitSet(cubeCount);
		return floodComponent(cubeCount, startCube, adjacency(cubeCount, presentCubes, seams, cutSeams));
	}

	private static BitSet floodComponent(int cubeCount, int startCube, List<List<Integer>> adjacency) {
		BitSet component = new BitSet(cubeCount);
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		component.set(startCube);
		queue.add(startCube);
		while (!queue.isEmpty()) {
			int current = queue.removeFirst();
			for (int next : adjacency.get(current)) {
				if (component.get(next))
					continue;
				component.set(next);
				queue.addLast(next);
			}
		}
		return component;
	}

	public static List<BitSet> components(int cubeCount, BitSet presentCubes, List<Seam> seams, BitSet cutSeams) {
		List<BitSet> result = new ArrayList<>();
		BitSet unvisited = normalize(presentCubes, cubeCount);
		List<List<Integer>> adjacency = adjacency(cubeCount, unvisited, seams, cutSeams);
		for (int cube = unvisited.nextSetBit(0); cube >= 0; cube = unvisited.nextSetBit(0)) {
			BitSet component = floodComponent(cubeCount, cube, adjacency);
			result.add(component);
			unvisited.andNot(component);
		}
		result.sort(Comparator.<BitSet>comparingInt(BitSet::cardinality).reversed()
			.thenComparingInt(bits -> bits.nextSetBit(0)));
		return result;
	}

	private static List<List<Integer>> adjacency(int cubeCount, BitSet presentCubes, List<Seam> seams,
		BitSet cutSeams) {
		List<List<Integer>> adjacency = new ArrayList<>(cubeCount);
		for (int cube = 0; cube < cubeCount; cube++)
			adjacency.add(new ArrayList<>());
		for (int seamId = 0; seamId < seams.size(); seamId++) {
			if (cutSeams.get(seamId))
				continue;
			Seam seam = seams.get(seamId);
			if (seam.first < 0 || seam.second < 0 || seam.first >= cubeCount || seam.second >= cubeCount
				|| !presentCubes.get(seam.first) || !presentCubes.get(seam.second))
				continue;
			adjacency.get(seam.first).add(seam.second);
			adjacency.get(seam.second).add(seam.first);
		}
		return adjacency;
	}

	public static int[] encodeSeams(List<Seam> seams) {
		int[] encoded = new int[seams.size() * 2];
		for (int i = 0; i < seams.size(); i++) {
			encoded[i * 2] = seams.get(i).first;
			encoded[i * 2 + 1] = seams.get(i).second;
		}
		return encoded;
	}

	@Nullable
	public static List<Seam> decodeSeams(int[] encoded) {
		if (encoded == null || (encoded.length & 1) != 0 || encoded.length > MAX_SEAMS * 2)
			return null;
		List<Seam> seams = new ArrayList<>(encoded.length / 2);
		for (int i = 0; i < encoded.length; i += 2)
			seams.add(Seam.of(encoded[i], encoded[i + 1]));
		return List.copyOf(seams);
	}

	private static BitSet normalize(BitSet input, int size) {
		BitSet normalized = input == null ? new BitSet() : (BitSet) input.clone();
		if (normalized.length() > size)
			normalized.clear(size, normalized.length());
		return normalized;
	}

	public static List<Integer> normalizeCutOrder(List<Integer> input, BitSet cutSeams, int seamCount) {
		BitSet included = new BitSet(seamCount);
		List<Integer> normalized = new ArrayList<>();
		if (input != null) {
			for (Integer seamId : input) {
				if (seamId == null || seamId < 0 || seamId >= seamCount
					|| !cutSeams.get(seamId) || included.get(seamId))
					continue;
				included.set(seamId);
				normalized.add(seamId);
			}
		}
		for (int seamId = cutSeams.nextSetBit(0); seamId >= 0 && seamId < seamCount;
			seamId = cutSeams.nextSetBit(seamId + 1)) {
			if (!included.get(seamId))
				normalized.add(seamId);
		}
		return List.copyOf(normalized);
	}

	private static List<Integer> decodeCutOrder(int[] encoded) {
		if (encoded == null || encoded.length > MAX_SEAMS)
			return List.of();
		List<Integer> decoded = new ArrayList<>(encoded.length);
		for (int seamId : encoded)
			decoded.add(seamId);
		return List.copyOf(decoded);
	}

	public static final class Source {
		private final MimicProfile profile;
		private final int cubeCount;
		private final BitSet presentCubes;
		private final List<Seam> seams;
		private final BitSet cutSeams;
		private final List<Integer> cutOrder;
		private final Direction facing;
		private final SurgicalLayPose layPose;
		private final Vec3 originOffset;
		private final Map<Integer, Vec3> cubeOffsets;
		private final Map<Integer, SurgicalCubeRotation> cubeRotations;

		private Source(MimicProfile profile, int cubeCount, BitSet presentCubes, List<Seam> seams,
			BitSet cutSeams, List<Integer> cutOrder, Direction facing, SurgicalLayPose layPose,
			Vec3 originOffset,
			Map<Integer, Vec3> cubeOffsets, Map<Integer, SurgicalCubeRotation> cubeRotations) {
			this.profile = profile;
			this.cubeCount = cubeCount;
			this.presentCubes = normalize(presentCubes, cubeCount);
			this.seams = List.copyOf(seams);
			this.cutSeams = normalize(cutSeams, seams.size());
			this.cutOrder = normalizeCutOrder(cutOrder, this.cutSeams, seams.size());
			this.facing = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
			this.layPose = layPose == null ? SurgicalLayPose.IDENTITY : layPose;
			this.originOffset = originOffset;
			this.cubeOffsets = Map.copyOf(cubeOffsets);
			this.cubeRotations = Map.copyOf(cubeRotations);
		}

		@Nullable
		public static Source create(MimicProfile profile, int cubeCount, BitSet presentCubes,
			List<Seam> seams, BitSet cutSeams, List<Integer> cutOrder, Direction facing,
			SurgicalLayPose layPose, Vec3 originOffset, Map<Integer, Vec3> cubeOffsets) {
			return create(profile, cubeCount, presentCubes, seams, cutSeams, cutOrder, facing, layPose,
				originOffset, cubeOffsets, Map.of());
		}

		@Nullable
		public static Source create(MimicProfile profile, int cubeCount, BitSet presentCubes,
			List<Seam> seams, BitSet cutSeams, List<Integer> cutOrder, Direction facing,
			SurgicalLayPose layPose, Vec3 originOffset, Map<Integer, Vec3> cubeOffsets,
			Map<Integer, SurgicalCubeRotation> cubeRotations) {
			if (profile == null || presentCubes == null || seams == null || cutSeams == null
				|| cubeOffsets == null || cubeOffsets.size() > cubeCount || cubeRotations == null
				|| cubeRotations.size() > cubeCount)
				return null;
			for (Map.Entry<Integer, Vec3> entry : cubeOffsets.entrySet()) {
				Integer cube = entry.getKey();
				if (cube == null || cube < 0 || cube >= cubeCount || !presentCubes.get(cube)
					|| !finiteVector(entry.getValue()))
					return null;
			}
			for (Map.Entry<Integer, SurgicalCubeRotation> entry : cubeRotations.entrySet()) {
				Integer cube = entry.getKey();
				if (cube == null || cube < 0 || cube >= cubeCount || !presentCubes.get(cube)
					|| entry.getValue() == null)
					return null;
			}
			Map<Integer, Vec3> sanitized = sanitizeOffsets(cubeOffsets, cubeCount, presentCubes);
			Map<Integer, SurgicalCubeRotation> sanitizedRotations = sanitizeRotations(cubeRotations,
				cubeCount, presentCubes);
			Source source = new Source(profile, cubeCount, presentCubes, seams, cutSeams, cutOrder, facing,
				layPose,
				originOffset == null ? Vec3.ZERO : originOffset, sanitized, sanitizedRotations);
			return source.valid() ? source : null;
		}

		private boolean valid() {
			if (profile == null || !validTopology(cubeCount, seams) || presentCubes.isEmpty()
				|| layPose == null || !layPose.valid() || !finiteVector(originOffset))
				return false;
			for (Map.Entry<Integer, Vec3> entry : cubeOffsets.entrySet())
				if (entry.getKey() == null || !containsCube(entry.getKey()) || !finiteVector(entry.getValue()))
					return false;
			for (Map.Entry<Integer, SurgicalCubeRotation> entry : cubeRotations.entrySet())
				if (entry.getKey() == null || !containsCube(entry.getKey()) || entry.getValue() == null)
					return false;
			return true;
		}

		private Source copy() {
			return new Source(profile, cubeCount, presentCubes, seams, cutSeams, cutOrder, facing, layPose,
				originOffset, cubeOffsets, cubeRotations);
		}

		public MimicProfile profile() { return profile; }
		public int cubeCount() { return cubeCount; }
		public BitSet presentCubes() { return (BitSet) presentCubes.clone(); }
		public List<Seam> seams() { return seams; }
		public BitSet cutSeams() { return (BitSet) cutSeams.clone(); }
		public List<Integer> cutOrder() { return cutOrder; }
		public Direction facing() { return facing; }
		public SurgicalLayPose layPose() { return layPose; }
		public Vec3 originOffset() { return originOffset; }
		public Map<Integer, Vec3> cubeOffsets() { return cubeOffsets; }
		public Map<Integer, SurgicalCubeRotation> cubeRotations() { return cubeRotations; }
		public boolean containsCube(int cubeId) { return validCubeId(cubeId) && presentCubes.get(cubeId); }
		public boolean validCubeId(int cubeId) { return cubeId >= 0 && cubeId < cubeCount; }
		public boolean isSeamCut(int seamId) {
			return seamId >= 0 && seamId < seams.size() && cutSeams.get(seamId);
		}

		private CompoundTag save() {
			CompoundTag tag = new CompoundTag();
			tag.put(PROFILE_TAG, profile.save());
			tag.putInt(CUBE_COUNT_TAG, cubeCount);
			tag.putLongArray(PRESENT_CUBES_TAG, presentCubes.toLongArray());
			tag.putIntArray(SEAMS_TAG, encodeSeams(seams));
			if (!cutSeams.isEmpty())
				tag.putLongArray(CUT_SEAMS_TAG, cutSeams.toLongArray());
			if (!cutOrder.isEmpty())
				tag.putIntArray(CUT_ORDER_TAG, cutOrder.stream().mapToInt(Integer::intValue).toArray());
			tag.putInt(FACING_TAG, facing.get3DDataValue());
			tag.put(LAY_POSE_TAG, writeLayPose(layPose));
			if (!originOffset.equals(Vec3.ZERO)) {
				tag.putDouble(ORIGIN_X_TAG, originOffset.x);
				tag.putDouble(ORIGIN_Y_TAG, originOffset.y);
				tag.putDouble(ORIGIN_Z_TAG, originOffset.z);
			}
			writeOffsets(tag, cubeOffsets);
			writeRotations(tag, cubeRotations);
			return tag;
		}

		@Nullable
		private static Source load(CompoundTag tag) {
			if (!tag.contains(PROFILE_TAG, Tag.TAG_COMPOUND)
				|| !tag.contains(CUBE_COUNT_TAG, Tag.TAG_ANY_NUMERIC)
				|| !tag.contains(PRESENT_CUBES_TAG, Tag.TAG_LONG_ARRAY)
				|| !tag.contains(SEAMS_TAG, Tag.TAG_INT_ARRAY))
				return null;
			MimicProfile profile = MimicProfile.load(tag.getCompound(PROFILE_TAG));
			int cubeCount = tag.getInt(CUBE_COUNT_TAG);
			List<Seam> seams = decodeSeams(tag.getIntArray(SEAMS_TAG));
			if (profile == null || seams == null)
				return null;
			BitSet present = BitSet.valueOf(tag.getLongArray(PRESENT_CUBES_TAG));
			BitSet cuts = tag.contains(CUT_SEAMS_TAG, Tag.TAG_LONG_ARRAY)
				? BitSet.valueOf(tag.getLongArray(CUT_SEAMS_TAG)) : new BitSet();
			List<Integer> order = tag.contains(CUT_ORDER_TAG, Tag.TAG_INT_ARRAY)
				? decodeCutOrder(tag.getIntArray(CUT_ORDER_TAG)) : List.of();
			Direction facing = Direction.from3DDataValue(tag.getInt(FACING_TAG));
			SurgicalLayPose layPose = tag.contains(LAY_POSE_TAG, Tag.TAG_COMPOUND)
				? readLayPose(tag.getCompound(LAY_POSE_TAG)) : SurgicalLayPose.IDENTITY;
			Vec3 origin = new Vec3(tag.getDouble(ORIGIN_X_TAG), tag.getDouble(ORIGIN_Y_TAG),
				tag.getDouble(ORIGIN_Z_TAG));
			return create(profile, cubeCount, present, seams, cuts, order, facing, layPose, origin,
				readOffsets(tag, cubeCount, present), readRotations(tag, cubeCount, present));
		}
	}

	/** One immutable source after rotating the packed layout to the placing player's direction. */
	public record PlacedSource(Source source, Direction facing, SurgicalLayPose layPose, Vec3 originOffset,
		Map<Integer, Vec3> cubeOffsets, Map<Integer, SurgicalCubeRotation> cubeRotations) {
		public PlacedSource {
			if (source == null)
				throw new IllegalArgumentException("A placed surgical source requires source data");
			facing = horizontal(facing);
			layPose = layPose == null ? SurgicalLayPose.IDENTITY : layPose;
			originOffset = originOffset == null ? Vec3.ZERO : originOffset;
			cubeOffsets = Map.copyOf(cubeOffsets);
			cubeRotations = Map.copyOf(cubeRotations);
		}
	}

	public record Joint(int firstSource, int firstCube, int secondSource, int secondCube) {
		private Joint normalized() {
			return firstSource < secondSource || firstSource == secondSource && firstCube <= secondCube
				? this : new Joint(secondSource, secondCube, firstSource, firstCube);
		}

		private boolean validFor(List<Source> sources) {
			return firstSource >= 0 && firstSource < sources.size()
				&& secondSource >= 0 && secondSource < sources.size()
				&& sources.get(firstSource).containsCube(firstCube)
				&& sources.get(secondSource).containsCube(secondCube)
				&& (firstSource != secondSource || firstCube != secondCube);
		}
	}

	public record Combination(UUID id, List<CombinationMember> members) {
		public Combination {
			members = members == null ? List.of() : List.copyOf(members);
		}

		@Nullable
		private Combination normalized() {
			if (id == null || members.size() < 2 || members.size() > MAX_CUBES)
				return null;
			Set<CombinationMember> unique = new HashSet<>(members);
			if (unique.size() != members.size())
				return null;
			List<CombinationMember> normalized = new ArrayList<>(unique);
			normalized.sort(Comparator.comparingInt(CombinationMember::source)
				.thenComparingInt(CombinationMember::cube));
			return new Combination(id, normalized);
		}

		private boolean validFor(List<Source> sources) {
			for (CombinationMember member : members)
				if (member.source < 0 || member.source >= sources.size()
					|| !sources.get(member.source).containsCube(member.cube))
					return false;
			return true;
		}
	}

	public record CombinationMember(int source, int cube) {}

	public record Seam(int first, int second) {
		public static Seam of(int first, int second) {
			return first <= second ? new Seam(first, second) : new Seam(second, first);
		}
	}

	private static boolean finiteVector(Vec3 value) {
		return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
			&& Math.abs(value.x) <= SurgicalTablePlane.MAX_TILES + 2.0d
			&& Math.abs(value.y) <= SurgicalTablePlane.MAX_TILES + 2.0d
			&& Math.abs(value.z) <= SurgicalTablePlane.MAX_TILES + 2.0d;
	}

	private static CompoundTag writeLayPose(SurgicalLayPose pose) {
		CompoundTag tag = new CompoundTag();
		tag.putInt(POSE_AXIS_TAG, pose.axis().ordinal());
		tag.putInt(POSE_YAW_TAG, pose.yaw());
		tag.putDouble(POSE_X_TAG, pose.translation().x);
		tag.putDouble(POSE_Y_TAG, pose.translation().y);
		tag.putDouble(POSE_Z_TAG, pose.translation().z);
		return tag;
	}

	private static SurgicalLayPose readLayPose(CompoundTag tag) {
		int axis = tag.getInt(POSE_AXIS_TAG);
		if (axis < 0 || axis >= SurgicalLayPose.RotationAxis.values().length)
			return SurgicalLayPose.IDENTITY;
		try {
			return new SurgicalLayPose(SurgicalLayPose.RotationAxis.values()[axis], tag.getInt(POSE_YAW_TAG),
				new Vec3(tag.getDouble(POSE_X_TAG), tag.getDouble(POSE_Y_TAG), tag.getDouble(POSE_Z_TAG)));
		} catch (IllegalArgumentException ignored) {
			return SurgicalLayPose.IDENTITY;
		}
	}

	private static Direction inferLayoutFacing(List<Source> sources) {
		if (sources == null || sources.isEmpty())
			return Direction.NORTH;
		Source nearestOrigin = null;
		double nearestDistance = Double.POSITIVE_INFINITY;
		for (Source candidate : sources) {
			if (candidate == null)
				continue;
			double distance = horizontalDistanceSqr(candidate.originOffset);
			if (distance < nearestDistance) {
				nearestOrigin = candidate;
				nearestDistance = distance;
			}
		}
		return nearestOrigin == null ? Direction.NORTH : horizontal(nearestOrigin.facing);
	}

	private static SurgicalLayPose inferLayoutLayPose(List<Source> sources) {
		if (sources == null || sources.isEmpty())
			return SurgicalLayPose.IDENTITY;
		Source nearestOrigin = null;
		double nearestDistance = Double.POSITIVE_INFINITY;
		for (Source candidate : sources) {
			if (candidate == null)
				continue;
			double distance = horizontalDistanceSqr(candidate.originOffset);
			if (distance < nearestDistance) {
				nearestOrigin = candidate;
				nearestDistance = distance;
			}
		}
		return nearestOrigin == null ? SurgicalLayPose.IDENTITY : nearestOrigin.layPose;
	}

	private static double horizontalDistanceSqr(Vec3 vector) {
		return vector == null ? Double.POSITIVE_INFINITY : vector.x * vector.x + vector.z * vector.z;
	}

	private static int clockwiseTurns(Direction from, Direction to) {
		Direction rotated = horizontal(from);
		Direction target = horizontal(to);
		for (int turns = 0; turns < 4; turns++) {
			if (rotated == target)
				return turns;
			rotated = rotated.getClockWise();
		}
		return 0;
	}

	private static Direction rotateClockwise(Direction direction, int turns) {
		Direction rotated = horizontal(direction);
		for (int step = 0; step < turns; step++)
			rotated = rotated.getClockWise();
		return rotated;
	}

	private static Vec3 rotateClockwise(Vec3 vector, int turns) {
		Vec3 rotated = vector;
		for (int step = 0; step < turns; step++)
			rotated = new Vec3(-rotated.z, rotated.y, rotated.x);
		return rotated;
	}

	private static Direction horizontal(Direction direction) {
		return direction != null && direction.getAxis().isHorizontal() ? direction : Direction.NORTH;
	}

	private static Map<Integer, Vec3> sanitizeOffsets(Map<Integer, Vec3> offsets, int cubeCount,
		BitSet presentCubes) {
		if (offsets == null || offsets.size() > cubeCount)
			return Map.of();
		Map<Integer, Vec3> sanitized = new HashMap<>();
		for (Map.Entry<Integer, Vec3> entry : offsets.entrySet()) {
			Integer cube = entry.getKey();
			if (cube == null || cube < 0 || cube >= cubeCount || !presentCubes.get(cube)
				|| !finiteVector(entry.getValue()))
				return Map.of();
			if (!entry.getValue().equals(Vec3.ZERO))
				sanitized.put(cube, entry.getValue());
		}
		return Map.copyOf(sanitized);
	}

	private static Map<Integer, SurgicalCubeRotation> sanitizeRotations(
		Map<Integer, SurgicalCubeRotation> rotations, int cubeCount, BitSet presentCubes) {
		if (rotations == null || rotations.size() > cubeCount)
			return Map.of();
		Map<Integer, SurgicalCubeRotation> sanitized = new HashMap<>();
		for (Map.Entry<Integer, SurgicalCubeRotation> entry : rotations.entrySet()) {
			Integer cube = entry.getKey();
			SurgicalCubeRotation rotation = entry.getValue();
			if (cube == null || cube < 0 || cube >= cubeCount || !presentCubes.get(cube) || rotation == null)
				return Map.of();
			if (!rotation.isIdentity())
				sanitized.put(cube, rotation);
		}
		return Map.copyOf(sanitized);
	}

	private static void writeOffsets(CompoundTag tag, Map<Integer, Vec3> offsets) {
		if (offsets.isEmpty())
			return;
		List<Map.Entry<Integer, Vec3>> entries = offsets.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList();
		int[] cubes = new int[entries.size()];
		long[] x = new long[entries.size()];
		long[] y = new long[entries.size()];
		long[] z = new long[entries.size()];
		for (int index = 0; index < entries.size(); index++) {
			cubes[index] = entries.get(index).getKey();
			x[index] = Double.doubleToRawLongBits(entries.get(index).getValue().x);
			y[index] = Double.doubleToRawLongBits(entries.get(index).getValue().y);
			z[index] = Double.doubleToRawLongBits(entries.get(index).getValue().z);
		}
		tag.putIntArray(OFFSET_CUBES_TAG, cubes);
		tag.putLongArray(OFFSET_X_TAG, x);
		tag.putLongArray(OFFSET_Y_TAG, y);
		tag.putLongArray(OFFSET_Z_TAG, z);
	}

	private static Map<Integer, Vec3> readOffsets(CompoundTag tag, int cubeCount, BitSet present) {
		if (!tag.contains(OFFSET_CUBES_TAG, Tag.TAG_INT_ARRAY)
			|| !tag.contains(OFFSET_X_TAG, Tag.TAG_LONG_ARRAY)
			|| !tag.contains(OFFSET_Z_TAG, Tag.TAG_LONG_ARRAY))
			return Map.of();
		int[] cubes = tag.getIntArray(OFFSET_CUBES_TAG);
		long[] x = tag.getLongArray(OFFSET_X_TAG);
		long[] z = tag.getLongArray(OFFSET_Z_TAG);
		long[] y = tag.contains(OFFSET_Y_TAG, Tag.TAG_LONG_ARRAY)
			? tag.getLongArray(OFFSET_Y_TAG) : new long[cubes.length];
		if (cubes.length != x.length || cubes.length != y.length || cubes.length != z.length
			|| cubes.length > cubeCount)
			return Map.of();
		Map<Integer, Vec3> offsets = new HashMap<>();
		for (int index = 0; index < cubes.length; index++) {
			Vec3 offset = new Vec3(Double.longBitsToDouble(x[index]), Double.longBitsToDouble(y[index]),
				Double.longBitsToDouble(z[index]));
			if (cubes[index] < 0 || cubes[index] >= cubeCount || !present.get(cubes[index])
				|| !finiteVector(offset) || offsets.put(cubes[index], offset) != null)
				return Map.of();
		}
		return Map.copyOf(offsets);
	}

	private static void writeRotations(CompoundTag tag, Map<Integer, SurgicalCubeRotation> rotations) {
		if (rotations.isEmpty())
			return;
		ListTag encoded = new ListTag();
		for (Map.Entry<Integer, SurgicalCubeRotation> entry : rotations.entrySet().stream()
			.sorted(Map.Entry.comparingByKey()).toList()) {
			CompoundTag value = new CompoundTag();
			value.putInt(ROTATION_CUBE_TAG, entry.getKey());
			value.put(ROTATION_VALUE_TAG, entry.getValue().save());
			encoded.add(value);
		}
		tag.put(ROTATIONS_TAG, encoded);
	}

	private static Map<Integer, SurgicalCubeRotation> readRotations(CompoundTag tag, int cubeCount,
		BitSet present) {
		if (!tag.contains(ROTATIONS_TAG, Tag.TAG_LIST))
			return Map.of();
		ListTag encoded = tag.getList(ROTATIONS_TAG, Tag.TAG_COMPOUND);
		if (encoded.size() > cubeCount)
			return Map.of();
		Map<Integer, SurgicalCubeRotation> rotations = new HashMap<>();
		for (int index = 0; index < encoded.size(); index++) {
			CompoundTag value = encoded.getCompound(index);
			int cube = value.getInt(ROTATION_CUBE_TAG);
			SurgicalCubeRotation rotation = value.contains(ROTATION_VALUE_TAG, Tag.TAG_COMPOUND)
				? SurgicalCubeRotation.load(value.getCompound(ROTATION_VALUE_TAG)) : null;
			if (cube < 0 || cube >= cubeCount || !present.get(cube) || rotation == null
				|| rotations.putIfAbsent(cube, rotation) != null)
				return Map.of();
		}
		return sanitizeRotations(rotations, cubeCount, present);
	}
}
