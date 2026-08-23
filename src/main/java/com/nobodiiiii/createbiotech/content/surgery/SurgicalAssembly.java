package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Immutable, side-safe cube topology and visible-component state. */
public final class SurgicalAssembly {
	public static final int MAX_CUBES = 1024;
	public static final int MAX_SEAMS = 4096;
	private static final int CURRENT_VERSION = 2;
	private static final String VERSION_TAG = "Version";
	private static final String PROFILE_TAG = "MimicProfile";
	private static final String CUBE_COUNT_TAG = "CubeCount";
	private static final String PRESENT_CUBES_TAG = "PresentCubes";
	private static final String SEAMS_TAG = "Seams";
	private static final String CUT_SEAMS_TAG = "CutSeams";
	private static final String CUT_ORDER_TAG = "CutOrder";

	private final MimicProfile profile;
	private final int cubeCount;
	private final BitSet presentCubes;
	private final List<Seam> seams;
	private final BitSet cutSeams;
	private final List<Integer> cutOrder;

	private SurgicalAssembly(MimicProfile profile, int cubeCount, BitSet presentCubes, List<Seam> seams,
		BitSet cutSeams, List<Integer> cutOrder) {
		this.profile = profile;
		this.cubeCount = cubeCount;
		this.presentCubes = normalize(presentCubes, cubeCount);
		this.seams = List.copyOf(seams);
		this.cutSeams = normalize(cutSeams, seams.size());
		this.cutOrder = normalizeCutOrder(cutOrder, this.cutSeams, seams.size());
	}

	@Nullable
	public static SurgicalAssembly create(MimicProfile profile, int cubeCount, BitSet presentCubes,
		List<Seam> seams, BitSet cutSeams) {
		return create(profile, cubeCount, presentCubes, seams, cutSeams, List.of());
	}

	@Nullable
	public static SurgicalAssembly create(MimicProfile profile, int cubeCount, BitSet presentCubes,
		List<Seam> seams, BitSet cutSeams, List<Integer> cutOrder) {
		if (profile == null || !validTopology(cubeCount, seams))
			return null;
		SurgicalAssembly assembly = new SurgicalAssembly(profile, cubeCount, presentCubes, seams, cutSeams,
			cutOrder);
		return assembly.presentCubes.isEmpty() ? null : assembly;
	}

	@Nullable
	public static SurgicalAssembly load(CompoundTag tag) {
		int version = tag.getInt(VERSION_TAG);
		if ((version != 1 && version != CURRENT_VERSION)
			|| !tag.contains(PROFILE_TAG, Tag.TAG_COMPOUND)
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
		tag.put(PROFILE_TAG, profile.save());
		tag.putInt(CUBE_COUNT_TAG, cubeCount);
		tag.putLongArray(PRESENT_CUBES_TAG, presentCubes.toLongArray());
		tag.putIntArray(SEAMS_TAG, encodeSeams(seams));
		if (!cutSeams.isEmpty())
			tag.putLongArray(CUT_SEAMS_TAG, cutSeams.toLongArray());
		if (!cutOrder.isEmpty())
			tag.putIntArray(CUT_ORDER_TAG, cutOrder.stream().mapToInt(Integer::intValue).toArray());
		return tag;
	}

	public MimicProfile profile() {
		return profile;
	}

	public int cubeCount() {
		return cubeCount;
	}

	public boolean containsCube(int cubeId) {
		return validCubeId(cubeId) && presentCubes.get(cubeId);
	}

	public boolean isSeamCut(int seamId) {
		return seamId >= 0 && seamId < seams.size() && cutSeams.get(seamId);
	}

	public BitSet presentCubes() {
		return (BitSet) presentCubes.clone();
	}

	public List<Seam> seams() {
		return seams;
	}

	public BitSet cutSeams() {
		return (BitSet) cutSeams.clone();
	}

	public List<Integer> cutOrder() {
		return cutOrder;
	}

	public boolean validCubeId(int cubeId) {
		return cubeId >= 0 && cubeId < cubeCount;
	}

	public BitSet componentContaining(int cubeId) {
		return componentContaining(cubeCount, presentCubes, seams, cutSeams, cubeId);
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

	public record Seam(int first, int second) {
		public static Seam of(int first, int second) {
			return first <= second ? new Seam(first, second) : new Seam(second, first);
		}
	}
}
