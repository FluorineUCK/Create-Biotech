package com.nobodiiiii.createbiotech.content.surgery;

import java.util.ArrayDeque;
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
	public SurgicalSubject getSubjectByPersistentId(UUID persistentId) {
		for (SurgicalSubject subject : subjects)
			if (subject.persistentId().equals(persistentId))
				return subject;
		return null;
	}

	public void includeClientRenderBounds(AABB bounds) {
		clientRenderBounds = clientRenderBounds == null ? bounds : clientRenderBounds.minmax(bounds);
	}

	public boolean tryPlaceSubject(ItemStack box, SurgicalTablePlane.Plane plane, Direction placementFacing,
		SurgicalLayPose layPose, double placedOriginOffsetX, double placedOriginOffsetZ,
		SurgicalTableLayout.Proposal proposal) {
		return tryPlaceSubject(box, plane, placementFacing, layPose, placedOriginOffsetX, placedOriginOffsetZ,
			proposal, List.of());
	}

	public boolean tryPlaceSubject(ItemStack box, SurgicalTablePlane.Plane plane, Direction placementFacing,
		SurgicalLayPose layPose, double placedOriginOffsetX, double placedOriginOffsetZ,
		SurgicalTableLayout.Proposal proposal,
		List<SurgicalTableLayout.Proposal> sourceLayouts) {
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane, -1);
		if (level == null || level.isClientSide || subjects.size() >= MAX_SUBJECTS
			|| !worldPosition.equals(plane.source()) || !(box.getItem() instanceof CapturedEntityBoxItem)
			|| !CapturedEntityBoxHelper.hasCapturedEntity(box) || occupied == null
			|| sourceLayouts == null || layPose == null || !layPose.valid()
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
				return tryPlaceComposite(box, plane, placementFacing, placedOriginOffsetX, placedOriginOffsetZ,
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

		SurgicalSubject subject = new SurgicalSubject(allocateSubjectId(), profile, placementFacing, layPose, cubeCount,
			present, seams, cuts, cutOrder, placedOriginOffsetX, placedOriginOffsetZ, java.util.Map.of(),
			proposal.footprints());
		subjects.add(subject);
		clientRenderBounds = null;
		CapturedEntityBoxHelper.clearCapturedEntity(box);
		setChangedAndSync();
		level.playSound(null, worldPosition, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8f, 0.9f);
		return true;
	}

	private boolean tryPlaceComposite(ItemStack box, SurgicalTablePlane.Plane plane, Direction placementFacing,
		double placedOriginOffsetX, double placedOriginOffsetZ, SurgicalTableLayout.Proposal envelopeProposal,
		List<SurgicalTableLayout.Proposal> sourceLayouts,
		List<SurgicalTableLayout.Footprint> occupied, SurgicalAssembly assembly) {
		List<SurgicalAssembly.Source> assemblySources = assembly.sources();
		List<SurgicalAssembly.PlacedSource> placedSources = assembly.placedSources(placementFacing);
		if (sourceLayouts.size() != assemblySources.size()
			|| placedSources.size() != assemblySources.size()
			|| subjects.size() > MAX_SUBJECTS - assemblySources.size())
			return false;
		SurgicalTableLayout.Footprint envelope = envelopeProposal.footprints().getFirst();
		for (int sourceId = 0; sourceId < assemblySources.size(); sourceId++) {
			SurgicalAssembly.PlacedSource placed = placedSources.get(sourceId);
			SurgicalAssembly.Source source = placed.source();
			SurgicalTableLayout.Proposal sourceLayout = sourceLayouts.get(sourceId);
			double originX = placedOriginOffsetX + placed.originOffset().x;
			double originZ = placedOriginOffsetZ + placed.originOffset().z;
			if (!validStoredOrigin(originX) || !validStoredOrigin(originZ)
				|| !validStoredOrigin(placed.originOffset().y)
				|| !matchesSourceOffsets(placed, sourceLayout)
				|| !SurgicalTableLayout.validateCompositeComponents(plane, source.cubeCount(),
					source.presentCubes(), source.seams(), source.cutSeams(), sourceLayout, occupied, envelope))
				return false;
		}

		// Restore sources as normal table subjects so each source keeps its own model topology and
		// automatically participates in the existing ray selection and shears workflow.
		List<SurgicalSubject> restored = new ArrayList<>(assemblySources.size());
		for (int sourceId = 0; sourceId < assemblySources.size(); sourceId++) {
			SurgicalAssembly.PlacedSource placed = placedSources.get(sourceId);
			SurgicalAssembly.Source source = placed.source();
			SurgicalSubject subject = new SurgicalSubject(allocateSubjectId(), source.profile(), placed.facing(),
				placed.layPose(),
				source.cubeCount(), source.presentCubes(), source.seams(), source.cutSeams(), source.cutOrder(),
				placedOriginOffsetX + placed.originOffset().x,
				placedOriginOffsetZ + placed.originOffset().z, restoredOffsets(placed),
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

	private static boolean matchesSourceOffsets(SurgicalAssembly.PlacedSource placed,
		SurgicalTableLayout.Proposal layout) {
		SurgicalAssembly.Source source = placed.source();
		if (layout.offsets().size() != source.presentCubes().cardinality())
			return false;
		Map<Integer, SurgicalTableLayout.CubeOffset> proposed = new HashMap<>();
		for (SurgicalTableLayout.CubeOffset offset : layout.offsets())
			if (proposed.putIfAbsent(offset.cubeId(), offset) != null)
				return false;
		BitSet present = source.presentCubes();
		for (int cube = present.nextSetBit(0); cube >= 0; cube = present.nextSetBit(cube + 1)) {
			Vec3 expected = placed.cubeOffsets().getOrDefault(cube, Vec3.ZERO);
			SurgicalTableLayout.CubeOffset actual = proposed.get(cube);
			if (actual == null || Math.abs(actual.x() - expected.x) > 1.0e-6d
				|| Math.abs(actual.z() - expected.z) > 1.0e-6d)
				return false;
		}
		return true;
	}

	private static Map<Integer, Vec3> restoredOffsets(SurgicalAssembly.PlacedSource placed) {
		SurgicalAssembly.Source source = placed.source();
		Map<Integer, Vec3> restored = new HashMap<>();
		BitSet present = source.presentCubes();
		for (int cube = present.nextSetBit(0); cube >= 0; cube = present.nextSetBit(cube + 1)) {
			Vec3 offset = placed.cubeOffsets().getOrDefault(cube, Vec3.ZERO)
				.add(0.0d, placed.originOffset().y, 0.0d);
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
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane,
			glueConnectedSubjectIds(subjectId));
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

	public boolean cutGlueJoint(Player player, ItemStack shears, InteractionHand hand, int subjectId,
		int glueJointId, int observedCubeCount, List<SurgicalAssembly.Seam> observedSeams,
		SurgicalTablePlane.Plane plane, double moveX, double moveZ) {
		SurgicalSubject subject = getSubject(subjectId);
		if (subject == null || !subject.initializeOrMatchTopology(observedCubeCount, observedSeams)
			|| !plane.valid())
			return false;
		GlueCutState cut = glueCutState(subject, glueJointId);
		if (cut == null || !validCutDelta(moveX, moveZ)
			|| !cut.separates && (Math.abs(moveX) > 1.0e-9d || Math.abs(moveZ) > 1.0e-9d))
			return false;
		Vec3 delta = new Vec3(moveX, 0.0d, moveZ);
		if (cut.separates && !canPlaceSeparatedGroup(cut.moving, delta, plane))
			return false;

		Set<SurgicalGlueJoint> removed = Set.of(cut.joint);
		for (SurgicalSubject connected : subjects)
			connected.removeGlueJoints(removed);
		if (cut.separates)
			for (Map.Entry<UUID, BitSet> entry : cut.moving.components.entrySet()) {
				SurgicalSubject moved = getSubjectByPersistentId(entry.getKey());
				if (moved != null)
					moved.translateComponent(entry.getValue(), delta);
			}
		shears.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
		clientRenderBounds = null;
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
		int seamCutCount = 0;
		for (int seamId = 0; seamId < subject.seams.size(); seamId++) {
			if (subject.cutSeams.get(seamId))
				continue;
			SurgicalAssembly.Seam seam = subject.seams.get(seamId);
			if (seam.first() != cubeId && seam.second() != cubeId
				|| !subject.validPresentCube(seam.first()) || !subject.validPresentCube(seam.second()))
				continue;
			proposedCuts.set(seamId);
			updatedOrder.add(seamId);
			seamCutCount++;
		}
		Set<SurgicalGlueJoint> glueCuts = new HashSet<>();
		for (SurgicalGlueJoint joint : subject.glueJoints())
			if (joint.touches(subject.persistentId(), cubeId))
				glueCuts.add(joint);
		int cutCount = seamCutCount + glueCuts.size();
		if (cutCount == 0)
			return false;
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane,
			glueConnectedSubjectIds(subjectId));
		if (occupied == null || !SurgicalTableLayout.validateComponents(plane, subject.cubeCount,
			subject.presentCubes, subject.seams, proposedCuts, proposal, occupied))
			return false;

		subject.cutSeams = proposedCuts;
		subject.cutOrder = SurgicalAssembly.normalizeCutOrder(updatedOrder, subject.cutSeams,
			subject.seams.size());
		subject.applyLayout(proposal);
		if (!glueCuts.isEmpty())
			for (SurgicalSubject connected : subjects)
				connected.removeGlueJoints(glueCuts);
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
		SurgicalLayPose targetPose, double groundLiftY, List<SurgicalTableGluePacket.Move> moves,
		List<SurgicalTableGluePacket.AnchorMove> anchorMoves,
		SurgicalTablePlane.Plane plane) {
		SurgicalSubject first = getSubject(firstSubjectId);
		SurgicalSubject second = getSubject(secondSubjectId);
		if (first == null || second == null || !first.validPresentCube(firstCubeId)
			|| !second.validPresentCube(secondCubeId) || targetPose == null
			|| !targetPose.equals(second.layPose()) || !plane.valid()
			|| !validGlueLift(groundLiftY))
			return false;

		ComponentGroup moving = gluedGroup(first, firstCubeId);
		ComponentGroup anchored = gluedGroup(second, secondCubeId);
		Map<UUID, ValidatedGlueMove> validated = validateGlueMoves(moving, anchored, moves, plane);
		Map<UUID, Map<Integer, Vec3>> validatedAnchors = validateGlueAnchors(anchored, anchorMoves);
		if (moving.intersects(anchored) || validated == null || validatedAnchors == null)
			return false;

		Set<SurgicalGlueJoint> existingJoints = allGlueJoints();
		Map<UUID, ExtractedSubject> extracted = new HashMap<>();
		for (Map.Entry<UUID, BitSet> entry : moving.components.entrySet()) {
			SurgicalSubject original = getSubjectByPersistentId(entry.getKey());
			ValidatedGlueMove move = validated.get(entry.getKey());
			if (original == null || move == null)
				return false;
			BitSet selected = entry.getValue();
			SurgicalSubject moved = original;
			if (!selected.equals(original.presentCubes)) {
				moved = original.extract(allocateSubjectId(), selected);
				subjects.add(moved);
				extracted.put(original.persistentId(), new ExtractedSubject((BitSet) selected.clone(), moved));
			}
			moved.applyGlueMove(second.placementFacing(), targetPose, move.offsets,
				move.layout.footprints());
		}
		for (Map.Entry<UUID, Map<Integer, Vec3>> entry : validatedAnchors.entrySet()) {
			SurgicalSubject anchoredSubject = getSubjectByPersistentId(entry.getKey());
			BitSet selected = anchored.components.get(entry.getKey());
			if (anchoredSubject != null && selected != null)
				anchoredSubject.applyComponentOffsets(selected, entry.getValue());
		}

		List<SurgicalGlueJoint> remappedJoints = existingJoints.stream()
			.map(joint -> remapJoint(joint, extracted)).toList();
		for (SurgicalSubject subject : subjects)
			subject.replaceGlueJoints(List.of());
		for (SurgicalGlueJoint joint : remappedJoints)
			attachJoint(joint);

		SurgicalSubject movedFirst = remappedSubject(first, firstCubeId, extracted);
		SurgicalGlueJoint joint = SurgicalGlueJoint.of(
			new SurgicalGlueJoint.Endpoint(movedFirst.persistentId(), firstCubeId),
			new SurgicalGlueJoint.Endpoint(second.persistentId(), secondCubeId));
		attachJoint(joint);
		glue.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
		clientRenderBounds = null;
		setChangedAndSync();
		if (level != null) {
			level.playSound(null, worldPosition, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.75f, 1.0f);
			level.playSound(null, worldPosition, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.5f, 0.9f);
		}
		return true;
	}

	private static boolean validGlueLift(double liftY) {
		return Double.isFinite(liftY) && liftY >= 0.0d
			&& liftY <= SurgicalTablePlane.MAX_TILES + 2.0d;
	}

	@Nullable
	private Map<UUID, ValidatedGlueMove> validateGlueMoves(ComponentGroup moving, ComponentGroup anchored,
		List<SurgicalTableGluePacket.Move> moves, SurgicalTablePlane.Plane plane) {
		if (moves == null || moves.size() != moving.components.size())
			return null;
		int requiredSplits = 0;
		Map<UUID, ValidatedGlueMove> validated = new HashMap<>();
		List<SurgicalTableLayout.Footprint> obstacles = glueMoveObstacles(moving, anchored);
		for (SurgicalTableGluePacket.Move move : moves) {
			SurgicalSubject subject = getSubject(move.subjectId());
			if (subject == null || validated.containsKey(subject.persistentId()))
				return null;
			BitSet selected = moving.components.get(subject.persistentId());
			if (selected == null || selected.isEmpty())
				return null;
			Map<Integer, Vec3> offsets = new HashMap<>();
			for (SurgicalTableGluePacket.CubeTranslation translation : move.translations())
				if (!translation.valid() || !selected.get(translation.cubeId())
					|| offsets.putIfAbsent(translation.cubeId(), translation.offset()) != null)
					return null;
			if (offsets.size() != selected.cardinality()
				|| !layoutMatchesTranslations(move.layout(), offsets)
				|| !componentYTranslationsMatch(subject, selected, offsets)
				|| !SurgicalTableLayout.validateGlueComponents(plane, subject.cubeCount, selected,
					subject.seams, subject.cutSeams, move.layout(), obstacles))
				return null;
			if (!selected.equals(subject.presentCubes))
				requiredSplits++;
			validated.put(subject.persistentId(), new ValidatedGlueMove(Map.copyOf(offsets), move.layout()));
		}
		return validated.keySet().equals(moving.components.keySet())
			&& subjects.size() <= MAX_SUBJECTS - requiredSplits ? Map.copyOf(validated) : null;
	}

	@Nullable
	private Map<UUID, Map<Integer, Vec3>> validateGlueAnchors(ComponentGroup anchored,
		List<SurgicalTableGluePacket.AnchorMove> moves) {
		if (moves == null || moves.size() != anchored.components.size())
			return null;
		Map<UUID, Map<Integer, Vec3>> validated = new HashMap<>();
		for (SurgicalTableGluePacket.AnchorMove move : moves) {
			SurgicalSubject subject = getSubject(move.subjectId());
			if (subject == null || validated.containsKey(subject.persistentId()))
				return null;
			BitSet selected = anchored.components.get(subject.persistentId());
			if (selected == null || selected.isEmpty())
				return null;
			Map<Integer, Vec3> offsets = new HashMap<>();
			for (SurgicalTableGluePacket.CubeTranslation translation : move.translations()) {
				Vec3 existing = subject.componentOffsets.getOrDefault(translation.cubeId(), Vec3.ZERO);
				if (!translation.valid() || !selected.get(translation.cubeId())
					|| Math.abs(translation.offset().x - existing.x) > 1.0e-6d
					|| Math.abs(translation.offset().z - existing.z) > 1.0e-6d
					|| offsets.putIfAbsent(translation.cubeId(), translation.offset()) != null)
					return null;
			}
			if (offsets.size() != selected.cardinality()
				|| !componentYTranslationsMatch(subject, selected, offsets))
				return null;
			validated.put(subject.persistentId(), Map.copyOf(offsets));
		}
		return validated.keySet().equals(anchored.components.keySet()) ? Map.copyOf(validated) : null;
	}

	private static boolean layoutMatchesTranslations(SurgicalTableLayout.Proposal layout,
		Map<Integer, Vec3> offsets) {
		if (layout.offsets().size() != offsets.size())
			return false;
		Set<Integer> seen = new HashSet<>();
		for (SurgicalTableLayout.CubeOffset proposed : layout.offsets()) {
			Vec3 expected = offsets.get(proposed.cubeId());
			if (expected == null || !seen.add(proposed.cubeId())
				|| Math.abs(expected.x - proposed.x()) > 1.0e-6d
				|| Math.abs(expected.z - proposed.z()) > 1.0e-6d)
				return false;
		}
		return true;
	}

	private static boolean componentYTranslationsMatch(SurgicalSubject subject, BitSet selected,
		Map<Integer, Vec3> offsets) {
		for (BitSet component : SurgicalAssembly.components(subject.cubeCount, selected,
			subject.seams, subject.cutSeams)) {
			double expected = offsets.get(component.nextSetBit(0)).y;
			for (int cube = component.nextSetBit(0); cube >= 0; cube = component.nextSetBit(cube + 1))
				if (Math.abs(offsets.get(cube).y - expected) > 1.0e-6d)
					return false;
		}
		return true;
	}

	private List<SurgicalTableLayout.Footprint> glueMoveObstacles(ComponentGroup moving,
		ComponentGroup anchored) {
		List<SurgicalTableLayout.Footprint> obstacles = new ArrayList<>();
		for (SurgicalSubject subject : subjects) {
			BitSet moved = moving.components.get(subject.persistentId());
			BitSet fixed = anchored.components.get(subject.persistentId());
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints())
				if (!subject.containsFootprint(moved, footprint)
					&& !subject.containsFootprint(fixed, footprint))
					obstacles.add(footprint);
		}
		return List.copyOf(obstacles);
	}

	private static SurgicalGlueJoint remapJoint(SurgicalGlueJoint joint,
		Map<UUID, ExtractedSubject> extracted) {
		return SurgicalGlueJoint.of(remapEndpoint(joint.first(), extracted),
			remapEndpoint(joint.second(), extracted));
	}

	private static SurgicalGlueJoint.Endpoint remapEndpoint(SurgicalGlueJoint.Endpoint endpoint,
		Map<UUID, ExtractedSubject> extracted) {
		ExtractedSubject moved = extracted.get(endpoint.subjectKey());
		return moved != null && moved.cubes.get(endpoint.cubeId())
			? new SurgicalGlueJoint.Endpoint(moved.subject.persistentId(), endpoint.cubeId()) : endpoint;
	}

	private static SurgicalSubject remappedSubject(SurgicalSubject original, int cubeId,
		Map<UUID, ExtractedSubject> extracted) {
		ExtractedSubject moved = extracted.get(original.persistentId());
		return moved != null && moved.cubes.get(cubeId) ? moved.subject : original;
	}

	private void attachJoint(SurgicalGlueJoint joint) {
		SurgicalSubject first = getSubjectByPersistentId(joint.first().subjectKey());
		SurgicalSubject second = getSubjectByPersistentId(joint.second().subjectKey());
		if (first == null || second == null)
			return;
		first.addGlueJoint(joint);
		if (second != first)
			second.addGlueJoint(joint);
	}

	private record ValidatedGlueMove(Map<Integer, Vec3> offsets,
		SurgicalTableLayout.Proposal layout) {}

	private record ExtractedSubject(BitSet cubes, SurgicalSubject subject) {}

	boolean prepareGlueLayout(SurgicalSubject subject, SurgicalTablePlane.Plane plane,
		SurgicalTableLayout.Proposal proposal) {
		List<SurgicalTableLayout.Footprint> occupied = occupiedForValidation(plane,
			glueConnectedSubjectIds(subject.id()));
		if (occupied == null || !SurgicalTableLayout.validateComponents(plane, subject.cubeCount,
			subject.presentCubes, subject.seams, subject.cutSeams, proposal, occupied))
			return false;
		subject.applyLayout(proposal);
		return true;
	}

	private ComponentGroup gluedGroup(SurgicalSubject startSubject, int startCube) {
		return gluedGroup(startSubject, startCube, null);
	}

	private ComponentGroup gluedGroup(SurgicalSubject startSubject, int startCube,
		@Nullable SurgicalGlueJoint excluded) {
		Map<UUID, BitSet> components = new HashMap<>();
		components.put(startSubject.persistentId(), startSubject.componentContaining(startCube));
		boolean changed;
		do {
			changed = false;
			for (SurgicalGlueJoint joint : allGlueJoints()) {
				if (joint.equals(excluded))
					continue;
				changed |= followJoint(joint.first(), joint.second(), components);
				changed |= followJoint(joint.second(), joint.first(), components);
			}
		} while (changed);
		return new ComponentGroup(components);
	}

	/** Native components joined transitively through surgical glue, keyed by current subject id. */
	public Map<Integer, BitSet> connectedComponents(int subjectId, int cubeId) {
		SurgicalSubject start = getSubject(subjectId);
		if (start == null || !start.validPresentCube(cubeId))
			return Map.of();
		ComponentGroup group = gluedGroup(start, cubeId);
		Map<Integer, BitSet> result = new HashMap<>();
		for (SurgicalSubject subject : subjects) {
			BitSet included = group.components.get(subject.persistentId());
			if (included != null && !included.isEmpty())
				result.put(subject.id(), (BitSet) included.clone());
		}
		return Map.copyOf(result);
	}

	/** Client-side topology-aware variant for untouched subjects that are still lazily initialized. */
	public Map<Integer, BitSet> connectedComponents(int subjectId, int cubeId, int observedCubeCount,
		List<SurgicalAssembly.Seam> observedSeams) {
		SurgicalSubject start = getSubject(subjectId);
		if (start == null || !start.initializeOrMatchTopology(observedCubeCount, observedSeams))
			return Map.of();
		return connectedComponents(subjectId, cubeId);
	}

	@Nullable
	public GlueCutPlan glueCutPlan(int subjectId, int glueJointId) {
		SurgicalSubject subject = getSubject(subjectId);
		GlueCutState state = subject == null ? null : glueCutState(subject, glueJointId);
		return state == null ? null : new GlueCutPlan(state.joint,
			componentsBySubjectId(state.moving), state.separates);
	}

	@Nullable
	private GlueCutState glueCutState(SurgicalSubject subject, int glueJointId) {
		if (glueJointId < 0 || glueJointId >= subject.glueJoints().size())
			return null;
		SurgicalGlueJoint joint = subject.glueJoints().get(glueJointId);
		if (!joint.touches(subject.persistentId()))
			return null;
		SurgicalSubject firstSubject = getSubjectByPersistentId(joint.first().subjectKey());
		SurgicalSubject secondSubject = getSubjectByPersistentId(joint.second().subjectKey());
		if (firstSubject == null || secondSubject == null
			|| !firstSubject.validPresentCube(joint.first().cubeId())
			|| !secondSubject.validPresentCube(joint.second().cubeId()))
			return null;
		ComponentGroup first = gluedGroup(firstSubject, joint.first().cubeId(), joint);
		ComponentGroup second = gluedGroup(secondSubject, joint.second().cubeId(), joint);
		if (first.intersects(second))
			return new GlueCutState(joint, new ComponentGroup(Map.of()), false);
		ComponentGroup moving = componentSize(first) < componentSize(second) ? first : second;
		return new GlueCutState(joint, moving, true);
	}

	private Map<Integer, BitSet> componentsBySubjectId(ComponentGroup group) {
		Map<Integer, BitSet> result = new HashMap<>();
		for (SurgicalSubject subject : subjects) {
			BitSet component = group.components.get(subject.persistentId());
			if (component != null && !component.isEmpty())
				result.put(subject.id(), (BitSet) component.clone());
		}
		return Map.copyOf(result);
	}

	private static int componentSize(ComponentGroup group) {
		int size = 0;
		for (BitSet component : group.components.values())
			size += component.cardinality();
		return size;
	}

	private boolean canPlaceSeparatedGroup(ComponentGroup moving, Vec3 delta,
		SurgicalTablePlane.Plane plane) {
		List<SurgicalTableLayout.Footprint> obstacles = new ArrayList<>();
		List<SurgicalTableLayout.Footprint> translated = new ArrayList<>();
		for (SurgicalSubject subject : subjects) {
			if (subject.occupiedFootprints().isEmpty())
				return false;
			BitSet moved = moving.components.get(subject.persistentId());
			for (SurgicalTableLayout.Footprint footprint : subject.occupiedFootprints()) {
				if (!subject.containsFootprint(moved, footprint)) {
					obstacles.add(footprint);
					continue;
				}
				SurgicalTableLayout.Footprint placed = new SurgicalTableLayout.Footprint(
					footprint.componentRoot(), footprint.minX() + delta.x, footprint.minZ() + delta.z,
					footprint.maxX() + delta.x, footprint.maxZ() + delta.z,
					SurgicalTableLayout.UNSNAPPED, SurgicalTableLayout.UNSNAPPED);
				if (!plane.workArea().contains(placed.minX(), placed.minZ(), placed.maxX(), placed.maxZ(), 1.0e-6d))
					return false;
				translated.add(placed);
			}
		}
		if (translated.isEmpty())
			return false;
		for (SurgicalTableLayout.Footprint placed : translated)
			for (SurgicalTableLayout.Footprint obstacle : obstacles)
				if (placed.overlapsStrictly(obstacle))
					return false;
		return true;
	}

	private static boolean validCutDelta(double x, double z) {
		double bound = SurgicalTablePlane.MAX_TILES + 2.0d;
		return Double.isFinite(x) && Double.isFinite(z) && Math.abs(x) <= bound && Math.abs(z) <= bound;
	}

	private boolean followJoint(SurgicalGlueJoint.Endpoint from, SurgicalGlueJoint.Endpoint to,
		Map<UUID, BitSet> components) {
		BitSet included = components.get(from.subjectKey());
		if (included == null || !included.get(from.cubeId()))
			return false;
		SurgicalSubject target = getSubjectByPersistentId(to.subjectKey());
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

	/** Subject ids that currently form one logical editing group through glue joints. */
	public Set<Integer> glueConnectedSubjectIds(int subjectId) {
		SurgicalSubject start = getSubject(subjectId);
		if (start == null)
			return Set.of();
		Map<UUID, List<UUID>> adjacency = new HashMap<>();
		for (SurgicalGlueJoint joint : allGlueJoints()) {
			adjacency.computeIfAbsent(joint.first().subjectKey(), ignored -> new ArrayList<>())
				.add(joint.second().subjectKey());
			adjacency.computeIfAbsent(joint.second().subjectKey(), ignored -> new ArrayList<>())
				.add(joint.first().subjectKey());
		}
		Set<UUID> connected = new HashSet<>();
		connected.add(start.persistentId());
		ArrayDeque<UUID> pending = new ArrayDeque<>();
		pending.add(start.persistentId());
		while (!pending.isEmpty())
			for (UUID neighbor : adjacency.getOrDefault(pending.removeFirst(), List.of()))
				if (connected.add(neighbor))
					pending.addLast(neighbor);
		Set<Integer> ids = new HashSet<>();
		for (SurgicalSubject subject : subjects)
			if (connected.contains(subject.persistentId()))
				ids.add(subject.id());
		return Set.copyOf(ids);
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
				grouped.layPose(),
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
		return SurgicalAssembly.createComposite(sources, encodedJoints, anchor.placementFacing(),
			anchor.layPose());
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

	private record GlueCutState(SurgicalGlueJoint joint, ComponentGroup moving, boolean separates) {}

	public record GlueCutPlan(SurgicalGlueJoint joint, Map<Integer, BitSet> movingComponents,
		boolean separates) {
		public GlueCutPlan {
			Map<Integer, BitSet> frozen = new HashMap<>();
			movingComponents.forEach((key, value) -> frozen.put(key, (BitSet) value.clone()));
			movingComponents = Map.copyOf(frozen);
		}
	}

	@Nullable
	private List<SurgicalTableLayout.Footprint> occupiedForValidation(SurgicalTablePlane.Plane plane,
		int excludedSubjectId) {
		return level == null ? null : SurgicalTablePlane.occupiedFootprints(level, plane, excludedSubjectId);
	}

	@Nullable
	private List<SurgicalTableLayout.Footprint> occupiedForValidation(SurgicalTablePlane.Plane plane,
		Set<Integer> excludedSubjectIds) {
		return level == null ? null : SurgicalTablePlane.occupiedFootprints(level, plane, excludedSubjectIds);
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
		boolean previouslyHadSubjects = hasSubjects();
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
			if (previouslyHadSubjects != hasSubjects() && level != null)
				level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 16);
		}
	}

	@Override
	public AABB getRenderBoundingBox() {
		AABB bounds = new AABB(worldPosition);
		if (level != null && hasSubjects()) {
			SurgicalTablePlane.Plane plane = SurgicalTablePlane.scan(level, worldPosition);
			for (BlockPos tablePos : plane.tiles())
				bounds = bounds.minmax(new AABB(tablePos));
		}
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
