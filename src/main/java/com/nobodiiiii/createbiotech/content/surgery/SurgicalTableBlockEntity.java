package com.nobodiiiii.createbiotech.content.surgery;

import java.util.BitSet;
import java.util.List;

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

public class SurgicalTableBlockEntity extends SmartBlockEntity {
	private static final String PROFILE_TAG = "MimicProfile";
	private static final String CUBE_COUNT_TAG = "CubeCount";
	private static final String PRESENT_CUBES_TAG = "PresentCubes";
	private static final String SEAMS_TAG = "Seams";
	private static final String CUT_SEAMS_TAG = "CutSeams";
	private static final String LAST_CUT_SEAM_TAG = "LastCutSeam";
	private static final String CUT_ORDER_TAG = "CutOrder";

	@Nullable
	private MimicProfile profile;
	private int cubeCount;
	private BitSet presentCubes = new BitSet();
	private List<SurgicalAssembly.Seam> seams = List.of();
	private BitSet cutSeams = new BitSet();
	private List<Integer> cutOrder = List.of();
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

	public int getClientRenderRevision() {
		return clientRenderRevision;
	}

	public void setClientRenderBounds(AABB bounds) {
		clientRenderBounds = bounds;
	}

	public boolean isSeamCut(int seamId) {
		return seamId >= 0 && seamId < seams.size() && cutSeams.get(seamId);
	}

	public boolean tryPlaceSubject(ItemStack box) {
		if (level == null || level.isClientSide || hasSubject()
			|| !(box.getItem() instanceof CapturedEntityBoxItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(box))
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
		clientRenderBounds = null;
		CapturedEntityBoxHelper.clearCapturedEntity(box);
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 0.9f);
		return true;
	}

	public boolean cutSeam(Player player, ItemStack shears, InteractionHand hand, int seamId, int observedCubeCount,
		List<SurgicalAssembly.Seam> observedSeams) {
		if (!initializeOrMatchTopology(observedCubeCount, observedSeams)
			|| seamId < 0 || seamId >= seams.size() || cutSeams.get(seamId))
			return false;
		SurgicalAssembly.Seam seam = seams.get(seamId);
		if (!validPresentCube(seam.first()) || !validPresentCube(seam.second()))
			return false;

		cutSeams.set(seamId);
		List<Integer> updatedOrder = new java.util.ArrayList<>(cutOrder);
		updatedOrder.add(seamId);
		cutOrder = SurgicalAssembly.normalizeCutOrder(updatedOrder, cutSeams, seams.size());
		shears.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
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

	private void clearSubject() {
		profile = null;
		cubeCount = 0;
		presentCubes.clear();
		seams = List.of();
		cutSeams.clear();
		cutOrder = List.of();
	}

	private void setChangedAndSync() {
		setChanged();
		sendData();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (profile != null)
			tag.put(PROFILE_TAG, profile.save());
		if (cubeCount > 0) {
			tag.putInt(CUBE_COUNT_TAG, cubeCount);
			tag.putLongArray(PRESENT_CUBES_TAG, presentCubes.toLongArray());
			tag.putIntArray(SEAMS_TAG, SurgicalAssembly.encodeSeams(seams));
			if (!cutSeams.isEmpty())
				tag.putLongArray(CUT_SEAMS_TAG, cutSeams.toLongArray());
			if (!cutOrder.isEmpty())
				tag.putIntArray(CUT_ORDER_TAG, cutOrder.stream().mapToInt(Integer::intValue).toArray());
		}
		super.write(tag, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		profile = tag.contains(PROFILE_TAG, Tag.TAG_COMPOUND)
			? MimicProfile.load(tag.getCompound(PROFILE_TAG)) : null;
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

	@Override
	public AABB getRenderBoundingBox() {
		return clientRenderBounds == null ? new AABB(worldPosition).inflate(8.0d)
			: clientRenderBounds.minmax(new AABB(worldPosition)).inflate(0.25d);
	}
}
