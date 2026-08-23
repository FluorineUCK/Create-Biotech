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
	private static final int CURRENT_VERSION = 3;
	private static final String VERSION_TAG = "Version";
	private static final String PROFILE_TAG = "MimicProfile";
	private static final String CUBE_COUNT_TAG = "CubeCount";
	private static final String PRESENT_CUBES_TAG = "PresentCubes";
	private static final String SEAMS_TAG = "Seams";
	private static final String CUT_SEAMS_TAG = "CutSeams";
	private static final String CUT_ORDER_TAG = "CutOrder";
	private static final String SOURCES_TAG = "Sources";
	private static final String JOINTS_TAG = "GlueJoints";
	private static final String PRESERVE_LAYOUT_TAG = "PreserveLayout";
	private static final String FACING_TAG = "Facing";
	private static final String ORIGIN_X_TAG = "OriginX";
	private static final String ORIGIN_Y_TAG = "OriginY";
	private static final String ORIGIN_Z_TAG = "OriginZ";
	private static final String OFFSET_CUBES_TAG = "OffsetCubes";
	private static final String OFFSET_X_TAG = "OffsetX";
	private static final String OFFSET_Y_TAG = "OffsetY";
	private static final String OFFSET_Z_TAG = "OffsetZ";
	private static final String FIRST_SOURCE_TAG = "FirstSource";
	private static final String FIRST_CUBE_TAG = "FirstCube";
	private static final String SECOND_SOURCE_TAG = "SecondSource";
	private static final String SECOND_CUBE_TAG = "SecondCube";

	private final List<Source> sources;
	private final List<Joint> joints;
	private final boolean preserveLayout;

	private SurgicalAssembly(List<Source> sources, List<Joint> joints, boolean preserveLayout) {
		this.sources = List.copyOf(sources);
		this.joints = List.copyOf(joints);
		this.preserveLayout = preserveLayout;
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
			Direction.NORTH, Vec3.ZERO, Map.of());
		return source == null ? null : new SurgicalAssembly(List.of(source), List.of(), false);
	}

	@Nullable
	public static SurgicalAssembly createComposite(List<Source> sources, List<Joint> joints) {
		if (sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES || joints == null
			|| joints.size() > MAX_SEAMS)
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
		return new SurgicalAssembly(frozenSources, frozenJoints, true);
	}

	@Nullable
	public static SurgicalAssembly load(CompoundTag tag) {
		int version = tag.getInt(VERSION_TAG);
		if (version == 1 || version == 2)
			return loadLegacy(tag, version);
		if (version != CURRENT_VERSION || !tag.contains(SOURCES_TAG, Tag.TAG_LIST))
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
		SurgicalAssembly assembly = createComposite(sources, joints);
		if (assembly == null)
			return null;
		return tag.getBoolean(PRESERVE_LAYOUT_TAG) ? assembly
			: new SurgicalAssembly(assembly.sources, assembly.joints, false);
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
		if (preserveLayout)
			tag.putBoolean(PRESERVE_LAYOUT_TAG, true);
		return tag;
	}

	public List<Source> sources() { return sources; }
	public List<Joint> joints() { return joints; }
	public boolean preservesLayout() { return preserveLayout; }

	/** Legacy single-source view retained for ordinary assemblies. */
	public MimicProfile profile() { return sources.getFirst().profile; }
	public int cubeCount() { return sources.getFirst().cubeCount; }
	public boolean containsCube(int cubeId) { return sources.getFirst().containsCube(cubeId); }
	public boolean isSeamCut(int seamId) { return sources.getFirst().isSeamCut(seamId); }
	public BitSet presentCubes() { return sources.getFirst().presentCubes(); }
	public List<Seam> seams() { return sources.getFirst().seams; }
	public BitSet cutSeams() { return sources.getFirst().cutSeams(); }
	public List<Integer> cutOrder() { return sources.getFirst().cutOrder; }
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
		private final Vec3 originOffset;
		private final Map<Integer, Vec3> cubeOffsets;

		private Source(MimicProfile profile, int cubeCount, BitSet presentCubes, List<Seam> seams,
			BitSet cutSeams, List<Integer> cutOrder, Direction facing, Vec3 originOffset,
			Map<Integer, Vec3> cubeOffsets) {
			this.profile = profile;
			this.cubeCount = cubeCount;
			this.presentCubes = normalize(presentCubes, cubeCount);
			this.seams = List.copyOf(seams);
			this.cutSeams = normalize(cutSeams, seams.size());
			this.cutOrder = normalizeCutOrder(cutOrder, this.cutSeams, seams.size());
			this.facing = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
			this.originOffset = originOffset;
			this.cubeOffsets = Map.copyOf(cubeOffsets);
		}

		@Nullable
		public static Source create(MimicProfile profile, int cubeCount, BitSet presentCubes,
			List<Seam> seams, BitSet cutSeams, List<Integer> cutOrder, Direction facing,
			Vec3 originOffset, Map<Integer, Vec3> cubeOffsets) {
			if (profile == null || presentCubes == null || seams == null || cutSeams == null
				|| cubeOffsets == null || cubeOffsets.size() > cubeCount)
				return null;
			for (Map.Entry<Integer, Vec3> entry : cubeOffsets.entrySet()) {
				Integer cube = entry.getKey();
				if (cube == null || cube < 0 || cube >= cubeCount || !presentCubes.get(cube)
					|| !finiteVector(entry.getValue()))
					return null;
			}
			Map<Integer, Vec3> sanitized = sanitizeOffsets(cubeOffsets, cubeCount, presentCubes);
			Source source = new Source(profile, cubeCount, presentCubes, seams, cutSeams, cutOrder, facing,
				originOffset == null ? Vec3.ZERO : originOffset, sanitized);
			return source.valid() ? source : null;
		}

		private boolean valid() {
			if (profile == null || !validTopology(cubeCount, seams) || presentCubes.isEmpty()
				|| !finiteVector(originOffset))
				return false;
			for (Map.Entry<Integer, Vec3> entry : cubeOffsets.entrySet())
				if (entry.getKey() == null || !containsCube(entry.getKey()) || !finiteVector(entry.getValue()))
					return false;
			return true;
		}

		private Source copy() {
			return new Source(profile, cubeCount, presentCubes, seams, cutSeams, cutOrder, facing,
				originOffset, cubeOffsets);
		}

		public MimicProfile profile() { return profile; }
		public int cubeCount() { return cubeCount; }
		public BitSet presentCubes() { return (BitSet) presentCubes.clone(); }
		public List<Seam> seams() { return seams; }
		public BitSet cutSeams() { return (BitSet) cutSeams.clone(); }
		public List<Integer> cutOrder() { return cutOrder; }
		public Direction facing() { return facing; }
		public Vec3 originOffset() { return originOffset; }
		public Map<Integer, Vec3> cubeOffsets() { return cubeOffsets; }
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
			if (!originOffset.equals(Vec3.ZERO)) {
				tag.putDouble(ORIGIN_X_TAG, originOffset.x);
				tag.putDouble(ORIGIN_Y_TAG, originOffset.y);
				tag.putDouble(ORIGIN_Z_TAG, originOffset.z);
			}
			writeOffsets(tag, cubeOffsets);
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
			Vec3 origin = new Vec3(tag.getDouble(ORIGIN_X_TAG), tag.getDouble(ORIGIN_Y_TAG),
				tag.getDouble(ORIGIN_Z_TAG));
			return create(profile, cubeCount, present, seams, cuts, order, facing, origin,
				readOffsets(tag, cubeCount, present));
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
}
