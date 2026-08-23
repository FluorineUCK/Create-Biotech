package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** The canonical corner block entity for all surgical subjects on one connected table plane. */
public class SurgicalTableBlockEntity extends SmartBlockEntity {
	public static final int MAX_SUBJECTS = SurgicalTablePlane.MAX_TILES * SurgicalTableLayout.SLOTS_PER_TILE;
	private static final String SUBJECTS_TAG = "SurgicalSubjects";
	private static final String NEXT_SUBJECT_ID_TAG = "NextSubjectId";
	private static final String LEGACY_PROFILE_TAG = "MimicProfile";

	private List<SurgicalSubject> subjects = new ArrayList<>();
	private int nextSubjectId;
	private int clientDataRevision;
	@Nullable
	private AABB clientRenderBounds;

	public SurgicalTableBlockEntity(BlockPos pos, BlockState state) {
		super(CBBlockEntityTypes.SURGICAL_TABLE.get(), pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level == null || level.isClientSide || isRemoved() || !hasSubjects())
			return;
		SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, worldPosition);
		if (plane.valid())
			consolidatePlane(level, plane);
	}

	public boolean hasSubjects() {
		return !subjects.isEmpty();
	}

	public boolean hasSubject(int subjectId) {
		return getSubject(subjectId) != null;
	}

	public List<SurgicalSubject> getSubjects() {
		return List.copyOf(subjects);
	}

	@Nullable
	public SurgicalSubject getSubject(int subjectId) {
		for (SurgicalSubject subject : subjects)
			if (subject.id() == subjectId)
				return subject;
		return null;
	}

	public void includeClientRenderBounds(AABB bounds) {
		clientRenderBounds = clientRenderBounds == null ? bounds : clientRenderBounds.minmax(bounds);
	}

	public boolean tryPlaceSubject(ItemStack box, SurgicalTablePlane.Plane plane, Direction placementFacing,
		double placedOriginOffsetX, double placedOriginOffsetZ, SurgicalTableLayout.Proposal proposal) {
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane, -1);
		if (level == null || level.isClientSide || subjects.size() >= MAX_SUBJECTS
			|| !worldPosition.equals(plane.source()) || !(box.getItem() instanceof CapturedEntityBoxItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(box) || occupied == null
			|| !SurgicalTableLayout.validatePlacement(plane, placedOriginOffsetX, placedOriginOffsetZ, proposal,
				occupied))
			return false;

		Entity captured = CapturedEntityBoxHelper.createCapturedEntity(box, level);
		MimicProfile profile;
		int cubeCount;
		BitSet present;
		List<SurgicalAssembly.Seam> seams;
		BitSet cuts;
		List<Integer> cutOrder;
		if (captured instanceof SlimeBionicEntity bionic) {
			SurgicalAssembly assembly = bionic.getAssembly();
			if (assembly == null)
				return false;
			profile = assembly.profile();
			cubeCount = assembly.cubeCount();
			present = assembly.presentCubes();
			seams = assembly.seams();
			cuts = assembly.cutSeams();
			cutOrder = assembly.cutOrder();
		} else if (captured instanceof LivingEntity living && SlimeMimicHandler.isSlimeMimic(living)) {
			profile = MimicProfile.capture(living);
			if (profile == null)
				return false;
			cubeCount = 0;
			present = new BitSet();
			seams = List.of();
			cuts = new BitSet();
			cutOrder = List.of();
		} else {
			return false;
		}

		SurgicalSubject subject = new SurgicalSubject(allocateSubjectId(), profile, placementFacing, cubeCount,
			present, seams, cuts, cutOrder, placedOriginOffsetX, placedOriginOffsetZ, java.util.Map.of(),
			proposal.footprints());
		subjects.add(subject);
		clientRenderBounds = null;
		CapturedEntityBoxHelper.clearCapturedEntity(box);
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 0.9f);
		return true;
	}

	public boolean cutSeam(Player player, ItemStack shears, InteractionHand hand, int subjectId, int seamId,
		int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams, SurgicalTablePlane.Plane plane,
		SurgicalTableLayout.Proposal proposal) {
		SurgicalSubject subject = getSubject(subjectId);
		if (subject == null || !subject.initializeOrMatchTopology(observedCubeCount, observedSeams)
			|| seamId < 0 || seamId >= subject.seams.size() || subject.cutSeams.get(seamId))
			return false;
		SurgicalAssembly.Seam seam = subject.seams.get(seamId);
		if (!subject.validPresentCube(seam.first()) || !subject.validPresentCube(seam.second()))
			return false;

		BitSet proposedCuts = (BitSet) subject.cutSeams.clone();
		proposedCuts.set(seamId);
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane, subjectId);
		if (occupied == null || !SurgicalTableLayout.validateComponents(plane, subject.cubeCount,
			subject.presentCubes, subject.seams, proposedCuts, proposal, occupied))
			return false;

		subject.cutSeams = proposedCuts;
		List<Integer> updatedOrder = new ArrayList<>(subject.cutOrder);
		updatedOrder.add(seamId);
		subject.cutOrder = SurgicalAssembly.normalizeCutOrder(updatedOrder, subject.cutSeams,
			subject.seams.size());
		subject.applyLayout(proposal);
		shears.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
		setChangedAndSync();
		if (level != null)
			level.playSound(null, worldPosition, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.15f);
		return true;
	}

	public boolean cutCubeConnections(Player player, ItemStack shears, InteractionHand hand, int subjectId,
		int cubeId, int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams,
		SurgicalTablePlane.Plane plane, SurgicalTableLayout.Proposal proposal) {
		SurgicalSubject subject = getSubject(subjectId);
		if (subject == null || !subject.initializeOrMatchTopology(observedCubeCount, observedSeams)
			|| !subject.validPresentCube(cubeId))
			return false;

		List<Integer> updatedOrder = new ArrayList<>(subject.cutOrder);
		BitSet proposedCuts = (BitSet) subject.cutSeams.clone();
		int cutCount = 0;
		for (int seamId = 0; seamId < subject.seams.size(); seamId++) {
			if (subject.cutSeams.get(seamId))
				continue;
			SurgicalAssembly.Seam seam = subject.seams.get(seamId);
			if (seam.first() != cubeId && seam.second() != cubeId
				|| !subject.validPresentCube(seam.first()) || !subject.validPresentCube(seam.second()))
				continue;
			proposedCuts.set(seamId);
			updatedOrder.add(seamId);
			cutCount++;
		}
		if (cutCount == 0)
			return false;
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane, subjectId);
		if (occupied == null || !SurgicalTableLayout.validateComponents(plane, subject.cubeCount,
			subject.presentCubes, subject.seams, proposedCuts, proposal, occupied))
			return false;

		subject.cutSeams = proposedCuts;
		subject.cutOrder = SurgicalAssembly.normalizeCutOrder(updatedOrder, subject.cutSeams,
			subject.seams.size());
		subject.applyLayout(proposal);
		shears.hurtAndBreak(cutCount, player, LivingEntity.getSlotForHand(hand));
		setChangedAndSync();
		if (level != null)
			level.playSound(null, worldPosition, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.8f, 1.15f);
		return true;
	}

	public boolean packComponent(Player player, ItemStack boxes, int subjectId, int cubeId,
		int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams) {
		SurgicalSubject subject = getSubject(subjectId);
		if (subject == null || !subject.initializeOrMatchTopology(observedCubeCount, observedSeams)
			|| !subject.validPresentCube(cubeId) || !CapturedEntityBoxItem.isBox(boxes)
			|| CapturedEntityBoxItem.hasCapturedEntity(boxes))
			return false;

		BitSet component = SurgicalAssembly.componentContaining(subject.cubeCount, subject.presentCubes,
			subject.seams, subject.cutSeams, cubeId);
		if (component.isEmpty() || level == null)
			return false;
		SurgicalAssembly assembly = SurgicalAssembly.create(subject.profile(), subject.cubeCount, component,
			subject.seams, subject.cutSeams, subject.cutOrder);
		if (assembly == null)
			return false;
		SlimeBionicEntity bionic = CBEntityTypes.SLIME_BIONIC.get().create(level);
		if (bionic == null)
			return false;
		bionic.setAssembly(assembly);
		if (!CapturedEntityBoxHelper.captureEntityFromPlayerStack(boxes, player, bionic))
			return false;

		subject.removeComponent(component);
		if (subject.isEmpty())
			subjects.remove(subject);
		clientRenderBounds = null;
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.7f, 0.85f);
		return true;
	}

	@Nullable
	private List<SurgicalTableLayout.Footprint> occupiedForValidation(SurgicalTablePlane.Plane plane,
		int excludedSubjectId) {
		return level == null ? null : SurgicalTablePlane.occupiedFootprints(level, plane, excludedSubjectId);
	}

	private int allocateSubjectId() {
		while (nextSubjectId < 0 || getSubject(nextSubjectId) != null)
			nextSubjectId++;
		return nextSubjectId++;
	}

	private void adoptSubjects(BlockPos previousController, List<SurgicalSubject> migrated) {
		for (SurgicalSubject subject : migrated) {
			subject.rebase(previousController, worldPosition);
			if (subject.id() < 0 || getSubject(subject.id()) != null)
				subject.setId(allocateSubjectId());
			else
				nextSubjectId = Math.max(nextSubjectId, subject.id() + 1);
			subjects.add(subject);
		}
		clientRenderBounds = null;
	}

	private List<SurgicalSubject> detachSubjects() {
		List<SurgicalSubject> detached = subjects;
		subjects = new ArrayList<>();
		clientRenderBounds = null;
		return detached;
	}

	@Nullable
	public static SurgicalTableBlockEntity controller(Level level, SurgicalTablePlane.Plane plane) {
		if (!plane.valid() || plane.source() == null)
			return null;
		if (!level.isClientSide)
			consolidatePlane(level, plane);
		return level.getBlockEntity(plane.source()) instanceof SurgicalTableBlockEntity table ? table : null;
	}

	public static void consolidatePlane(Level level, SurgicalTablePlane.Plane plane) {
		if (level.isClientSide || !plane.valid() || plane.source() == null
			|| !(level.getBlockEntity(plane.source()) instanceof SurgicalTableBlockEntity controller))
			return;
		boolean changed = false;
		for (BlockPos tile : plane.tiles()) {
			if (tile.equals(plane.source())
				|| !(level.getBlockEntity(tile) instanceof SurgicalTableBlockEntity other)
				|| !other.hasSubjects())
				continue;
			controller.adoptSubjects(tile, other.detachSubjects());
			other.setChangedAndSync();
			changed = true;
		}
		if (changed)
			controller.setChangedAndSync();
	}

	public static void transferBeforeRemoval(Level level, BlockPos removedPos, SurgicalTableBlockEntity removed) {
		if (level.isClientSide)
			return;
		List<SurgicalTablePlane.Plane> remainingPlanes = new ArrayList<>();
		Set<BlockPos> scanned = new HashSet<>();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = removedPos.relative(direction);
			if (scanned.contains(neighbor)
				|| !(level.getBlockState(neighbor).getBlock() instanceof SurgicalTableBlock))
				continue;
			SurgicalTablePlane.Plane plane = SurgicalTablePlane.scanExcluding(level, neighbor, removedPos);
			if (!plane.valid() || plane.source() == null)
				continue;
			scanned.addAll(plane.tiles());
			remainingPlanes.add(plane);
		}
		if (remainingPlanes.isEmpty())
			return;

		List<DetachedSubject> detached = new ArrayList<>();
		Set<BlockPos> holderPositions = new HashSet<>();
		holderPositions.add(removedPos);
		for (SurgicalTablePlane.Plane plane : remainingPlanes)
			holderPositions.addAll(plane.tiles());

		Set<SurgicalTableBlockEntity> changedTables = new HashSet<>();
		for (BlockPos holderPos : holderPositions) {
			if (!(level.getBlockEntity(holderPos) instanceof SurgicalTableBlockEntity holder)
				|| !holder.hasSubjects())
				continue;
			for (SurgicalSubject subject : holder.detachSubjects())
				detached.add(new DetachedSubject(holderPos, subject));
			changedTables.add(holder);
		}

		for (DetachedSubject entry : detached) {
			SurgicalTablePlane.Plane target = bestContainingPlane(remainingPlanes, entry.subject());
			if (target == null || !(level.getBlockEntity(target.source()) instanceof SurgicalTableBlockEntity next))
				continue;
			next.adoptSubjects(entry.previousController(), List.of(entry.subject()));
			changedTables.add(next);
		}
		for (SurgicalTableBlockEntity table : changedTables)
			table.setChangedAndSync();
	}

	private record DetachedSubject(BlockPos previousController, SurgicalSubject subject) {}

	@Nullable
	private static SurgicalTablePlane.Plane bestContainingPlane(List<SurgicalTablePlane.Plane> planes,
		SurgicalSubject subject) {
		for (SurgicalTablePlane.Plane plane : planes) {
			boolean contains = !subject.occupiedFootprints().isEmpty();
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints())
				contains &= plane.workArea().contains(footprint.minX(), footprint.minZ(), footprint.maxX(),
					footprint.maxZ(), 1.0e-6d);
			if (contains)
				return plane;
		}
		return planes.stream().max(java.util.Comparator.comparingInt(plane -> plane.workArea().tileArea()))
			.orElse(null);
	}

	private void setChangedAndSync() {
		setChanged();
		sendData();
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		if (!subjects.isEmpty()) {
			ListTag encoded = new ListTag();
			for (SurgicalSubject subject : subjects)
				encoded.add(subject.save());
			tag.put(SUBJECTS_TAG, encoded);
		}
		tag.putInt(NEXT_SUBJECT_ID_TAG, nextSubjectId);
		super.write(tag, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		List<SurgicalSubject> loaded = new ArrayList<>();
		Set<Integer> ids = new HashSet<>();
		if (tag.contains(SUBJECTS_TAG, Tag.TAG_LIST)) {
			ListTag encoded = tag.getList(SUBJECTS_TAG, Tag.TAG_COMPOUND);
			for (int index = 0; index < encoded.size() && loaded.size() < MAX_SUBJECTS; index++) {
				SurgicalSubject subject = SurgicalSubject.load(encoded.getCompound(index), index, Direction.NORTH);
				if (subject == null)
					continue;
				if (subject.id() < 0 || !ids.add(subject.id())) {
					int replacement = 0;
					while (ids.contains(replacement))
						replacement++;
					subject.setId(replacement);
					ids.add(replacement);
				}
				loaded.add(subject);
			}
		} else if (tag.contains(LEGACY_PROFILE_TAG, Tag.TAG_COMPOUND)) {
			SurgicalSubject legacy = SurgicalSubject.load(tag, 0, Direction.NORTH);
			if (legacy != null) {
				loaded.add(legacy);
				ids.add(legacy.id());
			}
		}
		subjects = loaded;
		nextSubjectId = Math.max(tag.getInt(NEXT_SUBJECT_ID_TAG),
			ids.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1);
		if (clientPacket) {
			clientRenderBounds = null;
			clientDataRevision++;
			for (SurgicalSubject subject : subjects)
				subject.setClientRenderRevision(clientDataRevision);
		}
	}

	@Override
	public AABB getRenderBoundingBox() {
		AABB bounds = new AABB(worldPosition);
		if (clientRenderBounds != null)
			return bounds.minmax(clientRenderBounds).inflate(0.25d);
		for (SurgicalSubject subject : subjects) {
			AABB placedCenter = new AABB(worldPosition.getX() + subject.originOffsetX(), worldPosition.getY(),
				worldPosition.getZ() + subject.originOffsetZ(),
				worldPosition.getX() + subject.originOffsetX() + 1.0d, worldPosition.getY() + 1.0d,
				worldPosition.getZ() + subject.originOffsetZ() + 1.0d);
			bounds = bounds.minmax(placedCenter);
		}
		return bounds.inflate(8.0d);
	}
}
