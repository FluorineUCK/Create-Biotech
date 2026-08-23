package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import net.minecraft.world.phys.Vec3;

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

	@Nullable
	private SurgicalSubject getSubject(UUID persistentId) {
		for (SurgicalSubject subject : subjects)
			if (subject.persistentId().equals(persistentId))
				return subject;
		return null;
	}

	public void includeClientRenderBounds(AABB bounds) {
		clientRenderBounds = clientRenderBounds == null ? bounds : clientRenderBounds.minmax(bounds);
	}

	public boolean tryPlaceSubject(ItemStack box, SurgicalTablePlane.Plane plane, Direction placementFacing,
		double placedOriginOffsetX, double placedOriginOffsetZ, SurgicalTableLayout.Proposal proposal) {
		return tryPlaceSubject(box, plane, placementFacing, placedOriginOffsetX, placedOriginOffsetZ,
			proposal, List.of());
	}

	public boolean tryPlaceSubject(ItemStack box, SurgicalTablePlane.Plane plane, Direction placementFacing,
		double placedOriginOffsetX, double placedOriginOffsetZ, SurgicalTableLayout.Proposal proposal,
		List<SurgicalTableLayout.Proposal> sourceLayouts) {
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane, -1);
		if (level == null || level.isClientSide || subjects.size() >= MAX_SUBJECTS
			|| !worldPosition.equals(plane.source()) || !(box.getItem() instanceof CapturedEntityBoxItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(box) || occupied == null
			|| sourceLayouts == null
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
			if (assembly.preservesLayout() || assembly.sources().size() != 1)
				return tryPlaceComposite(box, plane, placedOriginOffsetX, placedOriginOffsetZ,
					proposal, sourceLayouts, occupied, assembly);
			if (!sourceLayouts.isEmpty())
				return false;
			profile = assembly.profile();
			cubeCount = assembly.cubeCount();
			present = assembly.presentCubes();
			seams = assembly.seams();
			cuts = assembly.cutSeams();
			cutOrder = assembly.cutOrder();
		} else if (captured instanceof LivingEntity living && SlimeMimicHandler.isSlimeMimic(living)) {
			if (!sourceLayouts.isEmpty())
				return false;
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

	private boolean tryPlaceComposite(ItemStack box, SurgicalTablePlane.Plane plane,
		double placedOriginOffsetX, double placedOriginOffsetZ, SurgicalTableLayout.Proposal envelopeProposal,
		List<SurgicalTableLayout.Proposal> sourceLayouts,
		List<SurgicalTableLayout.Footprint> occupied, SurgicalAssembly assembly) {
		List<SurgicalAssembly.Source> assemblySources = assembly.sources();
		if (sourceLayouts.size() != assemblySources.size()
			|| subjects.size() > MAX_SUBJECTS - assemblySources.size())
			return false;
		SurgicalTableLayout.Footprint envelope = envelopeProposal.footprints().getFirst();
		for (int sourceId = 0; sourceId < assemblySources.size(); sourceId++) {
			SurgicalAssembly.Source source = assemblySources.get(sourceId);
			SurgicalTableLayout.Proposal sourceLayout = sourceLayouts.get(sourceId);
			double originX = placedOriginOffsetX + source.originOffset().x;
			double originZ = placedOriginOffsetZ + source.originOffset().z;
			if (Math.abs(source.originOffset().y) > 1.0e-6d
				|| !validStoredOrigin(originX) || !validStoredOrigin(originZ)
				|| !matchesSourceOffsets(source, sourceLayout)
				|| !SurgicalTableLayout.validateCompositeComponents(plane, source.cubeCount(),
					source.presentCubes(), source.seams(), source.cutSeams(), sourceLayout, occupied, envelope))
				return false;
		}

		// Restore sources as normal table subjects so each source keeps its own model topology and
		// automatically participates in the existing ray selection and shears workflow.
		List<SurgicalSubject> restored = new ArrayList<>(assemblySources.size());
		for (int sourceId = 0; sourceId < assemblySources.size(); sourceId++) {
			SurgicalAssembly.Source source = assemblySources.get(sourceId);
			SurgicalSubject subject = new SurgicalSubject(allocateSubjectId(), source.profile(), source.facing(),
				source.cubeCount(), source.presentCubes(), source.seams(), source.cutSeams(), source.cutOrder(),
				placedOriginOffsetX + source.originOffset().x,
				placedOriginOffsetZ + source.originOffset().z, restoredOffsets(source),
				sourceLayouts.get(sourceId).footprints());
			restored.add(subject);
		}
		for (SurgicalAssembly.Joint encoded : assembly.joints()) {
			SurgicalSubject first = restored.get(encoded.firstSource());
			SurgicalSubject second = restored.get(encoded.secondSource());
			SurgicalGlueJoint joint = SurgicalGlueJoint.of(
				new SurgicalGlueJoint.Endpoint(first.persistentId(), encoded.firstCube()),
				new SurgicalGlueJoint.Endpoint(second.persistentId(), encoded.secondCube()));
			first.addGlueJoint(joint);
			if (second != first)
				second.addGlueJoint(joint);
		}
		subjects.addAll(restored);
		clientRenderBounds = null;
		CapturedEntityBoxHelper.clearCapturedEntity(box);
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 0.9f);
		return true;
	}

	private static boolean matchesSourceOffsets(SurgicalAssembly.Source source,
		SurgicalTableLayout.Proposal layout) {
		if (layout.offsets().size() != source.presentCubes().cardinality())
			return false;
		Map<Integer, SurgicalTableLayout.CubeOffset> proposed = new HashMap<>();
		for (SurgicalTableLayout.CubeOffset offset : layout.offsets())
			if (proposed.putIfAbsent(offset.cubeId(), offset) != null)
				return false;
		BitSet present = source.presentCubes();
		for (int cube = present.nextSetBit(0); cube >= 0; cube = present.nextSetBit(cube + 1)) {
			Vec3 expected = source.cubeOffsets().getOrDefault(cube, Vec3.ZERO);
			SurgicalTableLayout.CubeOffset actual = proposed.get(cube);
			if (actual == null || Math.abs(actual.x() - expected.x) > 1.0e-6d
				|| Math.abs(actual.z() - expected.z) > 1.0e-6d)
				return false;
		}
		return true;
	}

	private static Map<Integer, Vec3> restoredOffsets(SurgicalAssembly.Source source) {
		Map<Integer, Vec3> restored = new HashMap<>();
		BitSet present = source.presentCubes();
		for (int cube = present.nextSetBit(0); cube >= 0; cube = present.nextSetBit(cube + 1)) {
			Vec3 offset = source.cubeOffsets().getOrDefault(cube, Vec3.ZERO)
				.add(0.0d, source.originOffset().y, 0.0d);
			if (offset.lengthSqr() > 1.0e-24d)
				restored.put(cube, offset);
		}
		return Map.copyOf(restored);
	}

	private static boolean validStoredOrigin(double value) {
		return Double.isFinite(value) && Math.abs(value) <= SurgicalTablePlane.MAX_TILES + 2.0d;
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

		ComponentGroup group = gluedGroup(subject, cubeId);
		BitSet component = group.components.get(subject.persistentId());
		if (component == null || component.isEmpty() || level == null)
			return false;
		Set<SurgicalGlueJoint> groupJoints = jointsWithin(group);
		SurgicalAssembly assembly = groupJoints.isEmpty()
			? SurgicalAssembly.create(subject.profile(), subject.cubeCount, component,
				subject.seams, subject.cutSeams, subject.cutOrder)
			: compositeAssembly(group, groupJoints, subject);
		if (assembly == null)
			return false;
		SlimeBionicEntity bionic = CBEntityTypes.SLIME_BIONIC.get().create(level);
		if (bionic == null)
			return false;
		bionic.setAssembly(assembly);
		if (!CapturedEntityBoxHelper.captureEntityFromPlayerStack(boxes, player, bionic))
			return false;

		for (SurgicalSubject groupedSubject : List.copyOf(subjects)) {
			BitSet removed = group.components.get(groupedSubject.persistentId());
			if (removed != null)
				groupedSubject.removeComponent(removed);
			groupedSubject.removeGlueJoints(groupJoints);
			if (groupedSubject.isEmpty())
				subjects.remove(groupedSubject);
		}
		clientRenderBounds = null;
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.7f, 0.85f);
		return true;
	}

	public boolean glueComponents(Player player, ItemStack glue, InteractionHand hand,
		int firstSubjectId, int firstCubeId, int secondSubjectId, int secondCubeId,
		Vec3 delta, SurgicalTablePlane.Plane plane) {
		SurgicalSubject first = getSubject(firstSubjectId);
		SurgicalSubject second = getSubject(secondSubjectId);
		if (first == null || second == null || !first.validPresentCube(firstCubeId)
			|| !second.validPresentCube(secondCubeId) || !validGlueDelta(delta) || !plane.valid())
			return false;

		ComponentGroup anchored = gluedGroup(first, firstCubeId);
		ComponentGroup moving = gluedGroup(second, secondCubeId);
		if (anchored.intersects(moving) || !canTranslateForGlue(anchored, moving, delta, plane))
			return false;

		for (Map.Entry<UUID, BitSet> entry : moving.components.entrySet()) {
			SurgicalSubject moved = getSubject(entry.getKey());
			if (moved != null)
				moved.translateComponent(entry.getValue(), delta);
		}
		SurgicalGlueJoint joint = SurgicalGlueJoint.of(
			new SurgicalGlueJoint.Endpoint(first.persistentId(), firstCubeId),
			new SurgicalGlueJoint.Endpoint(second.persistentId(), secondCubeId));
		first.addGlueJoint(joint);
		if (second != first)
			second.addGlueJoint(joint);
		glue.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
		clientRenderBounds = null;
		setChangedAndSync();
		if (level != null) {
			level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 1.0f);
			level.playSound(null, worldPosition, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.5f, 0.9f);
		}
		return true;
	}

	boolean prepareGlueLayout(SurgicalSubject subject, SurgicalTablePlane.Plane plane,
		SurgicalTableLayout.Proposal proposal) {
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane, subject.id());
		if (occupied == null || !SurgicalTableLayout.validateComponents(plane, subject.cubeCount,
			subject.presentCubes, subject.seams, subject.cutSeams, proposal, occupied))
			return false;
		subject.applyLayout(proposal);
		return true;
	}

	private ComponentGroup gluedGroup(SurgicalSubject startSubject, int startCube) {
		Map<UUID, BitSet> components = new HashMap<>();
		components.put(startSubject.persistentId(), startSubject.componentContaining(startCube));
		boolean changed;
		do {
			changed = false;
			for (SurgicalGlueJoint joint : allGlueJoints()) {
				changed |= followJoint(joint.first(), joint.second(), components);
				changed |= followJoint(joint.second(), joint.first(), components);
			}
		} while (changed);
		return new ComponentGroup(components);
	}

	private boolean followJoint(SurgicalGlueJoint.Endpoint from, SurgicalGlueJoint.Endpoint to,
		Map<UUID, BitSet> components) {
		BitSet included = components.get(from.subjectKey());
		if (included == null || !included.get(from.cubeId()))
			return false;
		SurgicalSubject target = getSubject(to.subjectKey());
		if (target == null || !target.validPresentCube(to.cubeId()))
			return false;
		BitSet addition = target.componentContaining(to.cubeId());
		BitSet existing = components.computeIfAbsent(to.subjectKey(), ignored -> new BitSet());
		int previous = existing.cardinality();
		existing.or(addition);
		return existing.cardinality() != previous;
	}

	private Set<SurgicalGlueJoint> allGlueJoints() {
		Set<SurgicalGlueJoint> joints = new HashSet<>();
		for (SurgicalSubject subject : subjects)
			joints.addAll(subject.glueJoints());
		return joints;
	}

	private Set<SurgicalGlueJoint> jointsWithin(ComponentGroup group) {
		Set<SurgicalGlueJoint> joints = new HashSet<>();
		for (SurgicalGlueJoint joint : allGlueJoints())
			if (group.contains(joint.first()) && group.contains(joint.second()))
				joints.add(joint);
		return joints;
	}

	@Nullable
	private SurgicalAssembly compositeAssembly(ComponentGroup group, Set<SurgicalGlueJoint> joints,
		SurgicalSubject anchor) {
		List<SurgicalAssembly.Source> sources = new ArrayList<>();
		Map<UUID, Integer> sourceIds = new HashMap<>();
		for (SurgicalSubject grouped : subjects) {
			BitSet included = group.components.get(grouped.persistentId());
			if (included == null || included.isEmpty())
				continue;
			Map<Integer, Vec3> offsets = new HashMap<>();
			for (int cube = included.nextSetBit(0); cube >= 0; cube = included.nextSetBit(cube + 1)) {
				Vec3 offset = grouped.componentOffsets.get(cube);
				if (offset != null)
					offsets.put(cube, offset);
			}
			SurgicalAssembly.Source source = SurgicalAssembly.Source.create(grouped.profile(), grouped.cubeCount,
				included, grouped.seams, grouped.cutSeams, grouped.cutOrder, grouped.placementFacing(),
				new Vec3(grouped.originOffsetX() - anchor.originOffsetX(), 0.0d,
					grouped.originOffsetZ() - anchor.originOffsetZ()), offsets);
			if (source == null)
				return null;
			sourceIds.put(grouped.persistentId(), sources.size());
			sources.add(source);
		}
		List<SurgicalAssembly.Joint> encodedJoints = new ArrayList<>();
		for (SurgicalGlueJoint joint : joints) {
			Integer firstSource = sourceIds.get(joint.first().subjectKey());
			Integer secondSource = sourceIds.get(joint.second().subjectKey());
			if (firstSource == null || secondSource == null)
				return null;
			encodedJoints.add(new SurgicalAssembly.Joint(firstSource, joint.first().cubeId(),
				secondSource, joint.second().cubeId()));
		}
		return SurgicalAssembly.createComposite(sources, encodedJoints);
	}

	private boolean canTranslateForGlue(ComponentGroup anchored, ComponentGroup moving, Vec3 delta,
		SurgicalTablePlane.Plane plane) {
		List<SurgicalTableLayout.Footprint> obstacles = new ArrayList<>();
		for (SurgicalSubject subject : subjects) {
			if (subject.occupiedFootprints().isEmpty())
				return false;
			BitSet ignored = anchored.components.get(subject.persistentId());
			BitSet moved = moving.components.get(subject.persistentId());
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints()) {
				if (ignored != null && ignored.get(footprint.componentRoot())
					|| moved != null && moved.get(footprint.componentRoot()))
					continue;
				obstacles.add(footprint);
			}
		}

		for (Map.Entry<UUID, BitSet> entry : moving.components.entrySet()) {
			SurgicalSubject subject = getSubject(entry.getKey());
			if (subject == null)
				return false;
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints()) {
				if (!entry.getValue().get(footprint.componentRoot()))
					continue;
				SurgicalTableLayout.Footprint translated = new SurgicalTableLayout.Footprint(
					footprint.componentRoot(), footprint.minX() + delta.x, footprint.minZ() + delta.z,
					footprint.maxX() + delta.x, footprint.maxZ() + delta.z,
					SurgicalTableLayout.UNSNAPPED, SurgicalTableLayout.UNSNAPPED);
				if (!plane.workArea().contains(translated.minX(), translated.minZ(), translated.maxX(),
					translated.maxZ(), 1.0e-6d))
					return false;
				for (SurgicalTableLayout.Footprint obstacle : obstacles)
					if (translated.overlapsStrictly(obstacle))
						return false;
			}
		}
		return true;
	}

	private static boolean validGlueDelta(Vec3 delta) {
		double bound = SurgicalTablePlane.MAX_TILES + 2.0d;
		return delta != null && Double.isFinite(delta.x) && Double.isFinite(delta.y) && Double.isFinite(delta.z)
			&& Math.abs(delta.x) <= bound && Math.abs(delta.y) <= 128.0d && Math.abs(delta.z) <= bound;
	}

	private record ComponentGroup(Map<UUID, BitSet> components) {
		private ComponentGroup {
			Map<UUID, BitSet> frozen = new HashMap<>();
			components.forEach((key, value) -> frozen.put(key, (BitSet) value.clone()));
			components = Map.copyOf(frozen);
		}

		private boolean contains(SurgicalGlueJoint.Endpoint endpoint) {
			BitSet cubes = components.get(endpoint.subjectKey());
			return cubes != null && cubes.get(endpoint.cubeId());
		}

		private boolean intersects(ComponentGroup other) {
			for (Map.Entry<UUID, BitSet> entry : components.entrySet()) {
				BitSet otherCubes = other.components.get(entry.getKey());
				if (otherCubes != null && entry.getValue().intersects(otherCubes))
					return true;
			}
			return false;
		}
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
		Set<UUID> persistentIds = new HashSet<>();
		if (tag.contains(SUBJECTS_TAG, Tag.TAG_LIST)) {
			ListTag encoded = tag.getList(SUBJECTS_TAG, Tag.TAG_COMPOUND);
			for (int index = 0; index < encoded.size() && loaded.size() < MAX_SUBJECTS; index++) {
				SurgicalSubject subject = SurgicalSubject.load(encoded.getCompound(index), index, Direction.NORTH);
				if (subject == null || !persistentIds.add(subject.persistentId()))
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
				persistentIds.add(legacy.persistentId());
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
