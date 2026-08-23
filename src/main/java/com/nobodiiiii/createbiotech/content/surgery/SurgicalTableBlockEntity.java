package com.nobodiiiii.createbiotech.content.surgery;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxHelper;
import com.nobodiiiii.createbiotech.content.cardboardbox.CapturedEntityBoxItem;
import com.nobodiiiii.createbiotech.content.slimemimic.MimicProfile;
import com.nobodiiiii.createbiotech.content.slimemimic.SlimeMimicHandler;
import com.nobodiiiii.createbiotech.entity.SlimeBionicEntity;
import com.nobodiiiii.createbiotech.registry.CBBlockEntityTypes;
import com.nobodiiiii.createbiotech.registry.CBEntityTypes;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class SurgicalTableBlockEntity extends SmartBlockEntity {
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

	@Nullable
	private MimicProfile profile;
	private int cubeCount;
	private BitSet presentCubes = new BitSet();
	private List<SurgicalAssembly.Seam> seams = List.of();
	private BitSet cutSeams = new BitSet();
	private List<Integer> cutOrder = List.of();
	private double originOffsetX;
	private double originOffsetZ;
	private Map<Integer, Vec3> componentOffsets = Map.of();
	private List<SurgicalTableLayout.Footprint> occupiedFootprints = List.of();
	private int clientRenderRevision;
	@Nullable
	private AABB clientRenderBounds;

	public SurgicalTableBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.SURGICAL_TABLE.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

	public boolean hasSubject() {
		return profile != null;
	}

	@Nullable
	public MimicProfile getProfile() {
		return profile;
	}

	public int getCubeCount() {
		return cubeCount;
	}

	public boolean matchesObservedTopology(int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams) {
		return SurgicalAssembly.validTopology(observedCubeCount, observedSeams)
			&& (cubeCount == 0 || cubeCount == observedCubeCount && seams.equals(observedSeams));
	}

	public boolean isCubePresentForRender(int cubeId, int observedCubeCount) {
		if (cubeId < 0)
			return false;
		if (cubeCount == 0)
			return cubeId < observedCubeCount;
		return cubeId < cubeCount && presentCubes.get(cubeId);
	}

	public BitSet getPresentCubesForRender(int observedCubeCount) {
		if (cubeCount == 0) {
			BitSet all = new BitSet(observedCubeCount);
			if (observedCubeCount > 0)
				all.set(0, observedCubeCount);
			return all;
		}
		return (BitSet) presentCubes.clone();
	}

	public List<SurgicalAssembly.Seam> getSeams() {
		return seams;
	}

	public BitSet getCutSeamsForRender() {
		return (BitSet) cutSeams.clone();
	}

	public List<Integer> getCutOrderForRender() {
		return cutOrder;
	}

	public double getOriginOffsetX() {
		return originOffsetX;
	}

	public double getOriginOffsetZ() {
		return originOffsetZ;
	}

	public Map<Integer, Vec3> getComponentOffsetsForRender() {
		return componentOffsets;
	}

	public List<SurgicalTableLayout.Footprint> getOccupiedFootprints() {
		return occupiedFootprints;
	}

	public int getClientRenderRevision() {
		return clientRenderRevision;
	}

	public void setClientRenderBounds(AABB bounds) {
		clientRenderBounds = bounds;
	}

	public boolean isSeamCut(int seamId) {
		return seamId >= 0 && seamId < seams.size() && cutSeams.get(seamId);
	}

	public boolean tryPlaceSubject(ItemStack box, SurgicalTablePlane.Plane plane, double placedOriginOffsetX,
		double placedOriginOffsetZ, SurgicalTableLayout.Proposal proposal) {
		List<SurgicalTableLayout.Footprint> otherFootprints = level == null ? null
			: SurgicalTablePlane.occupiedFootprints(level, plane, worldPosition);
		if (level == null || level.isClientSide || hasSubject()
			|| !(box.getItem() instanceof CapturedEntityBoxItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(box)
			|| otherFootprints == null
			|| !SurgicalTableLayout.validatePlacement(plane, placedOriginOffsetX, placedOriginOffsetZ, proposal,
				otherFootprints))
			return false;

		Entity captured = CapturedEntityBoxHelper.createCapturedEntity(box, level);
		if (captured instanceof SlimeBionicEntity bionic) {
			SurgicalAssembly assembly = bionic.getAssembly();
			if (assembly == null)
				return false;
			profile = assembly.profile();
			cubeCount = assembly.cubeCount();
			presentCubes = assembly.presentCubes();
			seams = assembly.seams();
			cutSeams = assembly.cutSeams();
			cutOrder = assembly.cutOrder();
		} else if (captured instanceof LivingEntity living && SlimeMimicHandler.isSlimeMimic(living)) {
			MimicProfile capturedProfile = MimicProfile.capture(living);
			if (capturedProfile == null)
				return false;
			profile = capturedProfile;
			cubeCount = 0;
			presentCubes.clear();
			seams = List.of();
			cutSeams.clear();
			cutOrder = List.of();
		} else {
			return false;
		}
		originOffsetX = placedOriginOffsetX;
		originOffsetZ = placedOriginOffsetZ;
		componentOffsets = Map.of();
		occupiedFootprints = proposal.footprints();
		clientRenderBounds = null;
		CapturedEntityBoxHelper.clearCapturedEntity(box);
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 0.9f);
		return true;
	}

	public boolean cutSeam(Player player, ItemStack shears, InteractionHand hand, int seamId, int observedCubeCount,
		List<SurgicalAssembly.Seam> observedSeams, SurgicalTablePlane.Plane plane,
		SurgicalTableLayout.Proposal proposal) {
		if (!initializeOrMatchTopology(observedCubeCount, observedSeams)
			|| seamId < 0 || seamId >= seams.size() || cutSeams.get(seamId))
			return false;
		SurgicalAssembly.Seam seam = seams.get(seamId);
		if (!validPresentCube(seam.first()) || !validPresentCube(seam.second()))
			return false;

		BitSet proposedCuts = (BitSet) cutSeams.clone();
		proposedCuts.set(seamId);
		List<SurgicalTableLayout.Footprint> otherFootprints = otherOccupiedFootprints(plane);
		if (otherFootprints == null || !SurgicalTableLayout.validateComponents(plane, cubeCount, presentCubes,
			seams, proposedCuts, proposal, otherFootprints))
			return false;

		cutSeams = proposedCuts;
		List<Integer> updatedOrder = new java.util.ArrayList<>(cutOrder);
		updatedOrder.add(seamId);
		cutOrder = SurgicalAssembly.normalizeCutOrder(updatedOrder, cutSeams, seams.size());
		applyLayout(proposal);
		shears.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
		setChangedAndSync();
		if (level != null)
			level.playSound(null, worldPosition, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.15f);
		return true;
	}

	public boolean cutCubeConnections(Player player, ItemStack shears, InteractionHand hand, int cubeId,
		int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams, SurgicalTablePlane.Plane plane,
		SurgicalTableLayout.Proposal proposal) {
		if (!initializeOrMatchTopology(observedCubeCount, observedSeams) || !validPresentCube(cubeId))
			return false;

		List<Integer> updatedOrder = new java.util.ArrayList<>(cutOrder);
		BitSet proposedCuts = (BitSet) cutSeams.clone();
		int cutCount = 0;
		for (int seamId = 0; seamId < seams.size(); seamId++) {
			if (cutSeams.get(seamId))
				continue;
			SurgicalAssembly.Seam seam = seams.get(seamId);
			if (seam.first() != cubeId && seam.second() != cubeId
				|| !validPresentCube(seam.first()) || !validPresentCube(seam.second()))
				continue;
			proposedCuts.set(seamId);
			updatedOrder.add(seamId);
			cutCount++;
		}
		if (cutCount == 0)
			return false;
		List<SurgicalTableLayout.Footprint> otherFootprints = otherOccupiedFootprints(plane);
		if (otherFootprints == null || !SurgicalTableLayout.validateComponents(plane, cubeCount, presentCubes,
			seams, proposedCuts, proposal, otherFootprints))
			return false;

		cutSeams = proposedCuts;
		cutOrder = SurgicalAssembly.normalizeCutOrder(updatedOrder, cutSeams, seams.size());
		applyLayout(proposal);
		shears.hurtAndBreak(cutCount, player, LivingEntity.getSlotForHand(hand));
		setChangedAndSync();
		if (level != null)
			level.playSound(null, worldPosition, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.15f);
		return true;
	}

	public boolean packComponent(Player player, ItemStack boxes, int cubeId, int observedCubeCount,
		List<SurgicalAssembly.Seam> observedSeams) {
		if (!initializeOrMatchTopology(observedCubeCount, observedSeams) || !validPresentCube(cubeId)
			|| !CapturedEntityBoxItem.isBox(boxes) || CapturedEntityBoxItem.hasCapturedEntity(boxes))
			return false;

		BitSet component = SurgicalAssembly.componentContaining(cubeCount, presentCubes, seams, cutSeams, cubeId);
		if (component.isEmpty() || profile == null)
			return false;

		SurgicalAssembly assembly = SurgicalAssembly.create(profile, cubeCount, component, seams, cutSeams,
			cutOrder);
		if (assembly == null || level == null)
			return false;
		SlimeBionicEntity bionic = CBEntityTypes.SLIME_BIONIC.get().create(level);
		if (bionic == null)
			return false;
		bionic.setAssembly(assembly);
		if (!CapturedEntityBoxHelper.captureEntityFromPlayerStack(boxes, player, bionic))
			return false;

		presentCubes.andNot(component);
		int packedRoot = component.nextSetBit(0);
		if (!occupiedFootprints.isEmpty())
			occupiedFootprints = occupiedFootprints.stream()
				.filter(footprint -> footprint.componentRoot() != packedRoot)
				.toList();
		if (!componentOffsets.isEmpty()) {
			Map<Integer, Vec3> retainedOffsets = new HashMap<>(componentOffsets);
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1))
				retainedOffsets.remove(cube);
			componentOffsets = Map.copyOf(retainedOffsets);
		}
		if (presentCubes.isEmpty())
			clearSubject();
		setChangedAndSync();
		if (level != null)
			level.playSound(null, worldPosition, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.7f, 0.85f);
		return true;
	}

	private boolean initializeOrMatchTopology(int observedCubeCount,
		List<SurgicalAssembly.Seam> observedSeams) {
		if (!hasSubject() || !SurgicalAssembly.validTopology(observedCubeCount, observedSeams))
			return false;
		if (cubeCount != 0)
			return cubeCount == observedCubeCount && seams.equals(observedSeams);

		cubeCount = observedCubeCount;
		seams = List.copyOf(observedSeams);
		presentCubes.clear();
		presentCubes.set(0, cubeCount);
		cutSeams.clear();
		cutOrder = List.of();
		clientRenderBounds = null;
		return true;
	}

	private boolean validPresentCube(int cubeId) {
		return cubeId >= 0 && cubeId < cubeCount && presentCubes.get(cubeId);
	}

	private void applyLayout(SurgicalTableLayout.Proposal proposal) {
		Map<Integer, Vec3> offsets = new HashMap<>();
		for (SurgicalTableLayout.CubeOffset offset : proposal.offsets()) {
			if (Math.abs(offset.x()) <= 1.0e-12d && Math.abs(offset.z()) <= 1.0e-12d)
				continue;
			offsets.put(offset.cubeId(), new Vec3(offset.x(), 0.0d, offset.z()));
		}
		componentOffsets = Map.copyOf(offsets);
		occupiedFootprints = proposal.footprints();
	}

	@Nullable
	private List<SurgicalTableLayout.Footprint> otherOccupiedFootprints(SurgicalTablePlane.Plane plane) {
		return level == null ? null : SurgicalTablePlane.occupiedFootprints(level, plane, worldPosition);
	}

	private void clearSubject() {
		profile = null;
		cubeCount = 0;
		presentCubes.clear();
		seams = List.of();
		cutSeams.clear();
		cutOrder = List.of();
		originOffsetX = 0.0d;
		originOffsetZ = 0.0d;
		componentOffsets = Map.of();
		occupiedFootprints = List.of();
		clientRenderBounds = null;
	}

	private void setChangedAndSync() {
		setChanged();
		sendData();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (profile != null)
			tag.put(PROFILE_TAG, profile.save());
		if (profile != null && (originOffsetX != 0.0d || originOffsetZ != 0.0d)) {
			tag.putDouble(ORIGIN_OFFSET_X_TAG, originOffsetX);
			tag.putDouble(ORIGIN_OFFSET_Z_TAG, originOffsetZ);
		}
		if (profile != null && !occupiedFootprints.isEmpty())
			tag.put(FOOTPRINTS_TAG, writeFootprints());
		if (cubeCount > 0) {
			tag.putInt(CUBE_COUNT_TAG, cubeCount);
			tag.putLongArray(PRESENT_CUBES_TAG, presentCubes.toLongArray());
			tag.putIntArray(SEAMS_TAG, SurgicalAssembly.encodeSeams(seams));
			if (!cutSeams.isEmpty())
				tag.putLongArray(CUT_SEAMS_TAG, cutSeams.toLongArray());
			if (!cutOrder.isEmpty())
				tag.putIntArray(CUT_ORDER_TAG, cutOrder.stream().mapToInt(Integer::intValue).toArray());
			if (!componentOffsets.isEmpty()) {
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
		}
		super.write(tag, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		profile = tag.contains(PROFILE_TAG, Tag.TAG_COMPOUND)
			? MimicProfile.load(tag.getCompound(PROFILE_TAG)) : null;
		originOffsetX = profile != null && tag.contains(ORIGIN_OFFSET_X_TAG, Tag.TAG_ANY_NUMERIC)
			? tag.getDouble(ORIGIN_OFFSET_X_TAG) : 0.0d;
		originOffsetZ = profile != null && tag.contains(ORIGIN_OFFSET_Z_TAG, Tag.TAG_ANY_NUMERIC)
			? tag.getDouble(ORIGIN_OFFSET_Z_TAG) : 0.0d;
		if (!Double.isFinite(originOffsetX) || !Double.isFinite(originOffsetZ)
			|| Math.abs(originOffsetX) > SurgicalTablePlane.MAX_TILES + 2.0d
			|| Math.abs(originOffsetZ) > SurgicalTablePlane.MAX_TILES + 2.0d) {
			originOffsetX = 0.0d;
			originOffsetZ = 0.0d;
		}
		cubeCount = profile == null ? 0 : tag.getInt(CUBE_COUNT_TAG);
		List<SurgicalAssembly.Seam> loadedSeams = tag.contains(SEAMS_TAG, Tag.TAG_INT_ARRAY)
			? SurgicalAssembly.decodeSeams(tag.getIntArray(SEAMS_TAG)) : null;
		if (loadedSeams == null || !SurgicalAssembly.validTopology(cubeCount, loadedSeams)) {
			cubeCount = 0;
			seams = List.of();
		} else {
			seams = loadedSeams;
		}
		presentCubes = cubeCount > 0 && tag.contains(PRESENT_CUBES_TAG, Tag.TAG_LONG_ARRAY)
			? BitSet.valueOf(tag.getLongArray(PRESENT_CUBES_TAG)) : new BitSet();
		cutSeams = cubeCount > 0 && tag.contains(CUT_SEAMS_TAG, Tag.TAG_LONG_ARRAY)
			? BitSet.valueOf(tag.getLongArray(CUT_SEAMS_TAG)) : new BitSet();
		List<Integer> loadedCutOrder = new java.util.ArrayList<>();
		if (cubeCount > 0 && tag.contains(CUT_ORDER_TAG, Tag.TAG_INT_ARRAY)) {
			for (int seamId : tag.getIntArray(CUT_ORDER_TAG))
				loadedCutOrder.add(seamId);
		} else if (cubeCount > 0 && tag.contains(LAST_CUT_SEAM_TAG, Tag.TAG_ANY_NUMERIC)) {
			int legacyLast = tag.getInt(LAST_CUT_SEAM_TAG);
			for (int seamId = cutSeams.nextSetBit(0); seamId >= 0; seamId = cutSeams.nextSetBit(seamId + 1))
				if (seamId != legacyLast)
					loadedCutOrder.add(seamId);
			loadedCutOrder.add(legacyLast);
		}
		cutOrder = SurgicalAssembly.normalizeCutOrder(loadedCutOrder, cutSeams, seams.size());
		componentOffsets = readComponentOffsets(tag);
		occupiedFootprints = profile == null ? List.of() : readFootprints(tag);
		if (cubeCount > 0) {
			if (presentCubes.length() > cubeCount)
				presentCubes.clear(cubeCount, presentCubes.length());
			if (cutSeams.length() > seams.size())
				cutSeams.clear(seams.size(), cutSeams.length());
		} else {
			cutOrder = List.of();
		}
		if (clientPacket) {
			clientRenderBounds = null;
			clientRenderRevision++;
		}
	}

	private ListTag writeFootprints() {
		ListTag encoded = new ListTag();
		for (SurgicalTableLayout.Footprint footprint : occupiedFootprints) {
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

	private List<SurgicalTableLayout.Footprint> readFootprints(CompoundTag tag) {
		if (!tag.contains(FOOTPRINTS_TAG, Tag.TAG_LIST))
			return List.of();
		ListTag encoded = tag.getList(FOOTPRINTS_TAG, Tag.TAG_COMPOUND);
		if (encoded.isEmpty() || encoded.size() > SurgicalAssembly.MAX_CUBES)
			return List.of();
		List<SurgicalTableLayout.Footprint> loaded = new java.util.ArrayList<>(encoded.size());
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

	private Map<Integer, Vec3> readComponentOffsets(CompoundTag tag) {
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
			if (cubes[index] < 0 || cubes[index] >= cubeCount || !presentCubes.get(cubes[index])
				|| !Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) > SurgicalTablePlane.MAX_TILES + 2.0d
				|| Math.abs(z) > SurgicalTablePlane.MAX_TILES + 2.0d || loaded.put(cubes[index], new Vec3(x, 0.0d, z)) != null)
				return Map.of();
		}
		return Map.copyOf(loaded);
	}

	@Override
	public AABB getRenderBoundingBox() {
		if (clientRenderBounds != null)
			return clientRenderBounds.minmax(new AABB(worldPosition)).inflate(0.25d);
		AABB owner = new AABB(worldPosition);
		AABB placedCenter = new AABB(worldPosition.getX() + originOffsetX, worldPosition.getY(),
			worldPosition.getZ() + originOffsetZ, worldPosition.getX() + originOffsetX + 1.0d,
			worldPosition.getY() + 1.0d, worldPosition.getZ() + originOffsetZ + 1.0d);
		return owner.minmax(placedCenter).inflate(8.0d);
	}
}
