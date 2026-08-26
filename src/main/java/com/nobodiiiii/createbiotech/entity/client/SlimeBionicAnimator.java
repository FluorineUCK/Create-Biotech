package com.nobodiiiii.createbiotech.entity.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalConnectionGraph;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalCubeRotation;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalGait;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalLimbType;
import com.nobodiiiii.createbiotech.content.surgery.client.SurgicalModelRenderContext;
import com.nobodiiiii.createbiotech.entity.SlimeBionicEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a stitched body from its installed anatomical joints.
 *
 * <p>The captured source geometry stays a still frame. Every joint instead contributes a rigid
 * rotation of one cube group around a pivot, expressed through the per-cube offset and rotation
 * maps the surgical render path already understands, so no part of the capture pipeline has to run
 * again per frame. Angles come straight from the vanilla humanoid model a zombie uses, so walking,
 * attacking and head tracking all match, but its raised-arm pose is deliberately left out: limbs
 * rest where the surgery put them.</p>
 */
public final class SlimeBionicAnimator {
	private static final int AXIS_X = 0;
	private static final int AXIS_Y = 1;
	private static final int AXIS_Z = 2;
	private static final double GEOMETRY_EPSILON = 1.0e-10d;
	private static final double PRINCIPAL_AXIS_SHARE = 0.55d;
	private static final Basis BODY_SPACE = Basis.bodySpace();
	@Nullable
	private static HumanoidModel<LivingEntity> zombieModel;

	private SlimeBionicAnimator() {}

	public static void clearCache() {
		zombieModel = null;
	}

	/**
	 * Reduces one source's captured cubes to what the joint maths needs.
	 *
	 * <p>Snapshots are measured in the body's yaw-zero render frame. Keeping this rest geometry out
	 * of world space makes the selected side, hinge and animation axes stable while the creature
	 * turns.</p>
	 */
	public static Map<Integer, CubeBox> measure(SurgicalModelRenderContext.Snapshot snapshot) {
		Map<Integer, CubeBox> boxes = new HashMap<>();
		for (SurgicalModelRenderContext.CubeGeometry cube : snapshot.cubes()) {
			CubeBox box = CubeBox.of(cube.corners(), BODY_SPACE);
			if (box != null)
				boxes.put(cube.cubeId(), box);
		}
		return Map.copyOf(boxes);
	}

	/**
	 * Resolves one animation frame into per-source cube transforms.
	 *
	 * <p>{@code sources} is indexed like {@link SurgicalAssembly#sources()}; each returned frame only
	 * contains the cubes an installed joint actually moves.</p>
	 */
	public static List<Frame> resolve(SlimeBionicEntity entity, SurgicalAssembly assembly,
		List<SourceState> sources, float partialTick) {
		int sourceCount = assembly.sources().size();
		List<Frame> frames = new ArrayList<>(sourceCount);
		for (int source = 0; source < sourceCount; source++)
			frames.add(Frame.EMPTY);
		if (assembly.limbs().isEmpty() || sources.size() != sourceCount)
			return frames;
		List<ResolvedLimb> limbs = resolveLimbs(assembly, sources);
		if (limbs.isEmpty())
			return frames;
		Pose pose = pose(entity, partialTick, effectiveLegLength(limbs, sources));
		if (pose == null)
			return frames;

		Map<Integer, Map<Integer, Vec3>> offsets = new HashMap<>();
		Map<Integer, Map<Integer, SurgicalCubeRotation>> rotations = new HashMap<>();
		for (ResolvedLimb limb : limbs) {
			ModelPart driver = pose.driver(limb);
			if (driver == null)
				continue;
			SurgicalCubeRotation rotation = BODY_SPACE.reframe(limb.restAlignment(),
				driver.zRot, driver.yRot, driver.xRot);
			if (rotation.isIdentity())
				continue;
			for (Member member : limb.members()) {
				if (member.source() < 0 || member.source() >= sources.size())
					continue;
				SourceState state = sources.get(member.source());
				CubeBox box = state.boxes().get(member.cube());
				if (box == null)
					continue;
				// The rest centre already carries the static offset, so rotating around the pivot only
				// adds the lever arm the pivot swings the cube along.
				Vec3 lever = limb.pivot().subtract(box.center());
				Vec3 staticOffset = state.offsets().getOrDefault(member.cube(), Vec3.ZERO);
				SurgicalCubeRotation staticRotation = state.rotations()
					.getOrDefault(member.cube(), SurgicalCubeRotation.IDENTITY);
				offsets.computeIfAbsent(member.source(), ignored -> new HashMap<>())
					.put(member.cube(), staticOffset.add(lever).subtract(rotation.rotate(lever)));
				rotations.computeIfAbsent(member.source(), ignored -> new HashMap<>())
					.put(member.cube(), staticRotation.then(rotation));
			}
		}
		for (int source = 0; source < sourceCount; source++) {
			Map<Integer, Vec3> sourceOffsets = offsets.get(source);
			Map<Integer, SurgicalCubeRotation> sourceRotations = rotations.get(source);
			if (sourceOffsets != null || sourceRotations != null)
				frames.set(source, new Frame(
					sourceOffsets == null ? Map.of() : Map.copyOf(sourceOffsets),
					sourceRotations == null ? Map.of() : Map.copyOf(sourceRotations)));
		}
		return frames;
	}

	/**
	 * Groups every limb with the cubes that move as one part and works out where it hinges.
	 *
	 * <p>Honey combinations still move as one rigid part. Their physical hinge comes only from the
	 * two cubes that really share the boundary seam or glue joint, while the pose direction follows
	 * the centre of the complete driven group. That distinction matters for an asymmetric limb whose
	 * selected connection cube lies on the opposite side of the hinge from most of its visible mass.</p>
	 */
	private static List<ResolvedLimb> resolveLimbs(SurgicalAssembly assembly, List<SourceState> sources) {
		SurgicalConnectionGraph<Integer> connections = connectionGraph(assembly);
		if (connections == null)
			return List.of();
		double bodyCenterX = bodyCenter(sources, AXIS_X);
		Map<SurgicalLimbType, List<ResolvedLimb>> byType = new EnumMap<>(SurgicalLimbType.class);
		for (SurgicalAssembly.Limb limb : assembly.limbs()) {
			Member selectedChild = new Member(limb.childSource(), limb.childCube());
			Member selectedParent = new Member(limb.parentSource(), limb.parentCube());
			List<Member> childMembers = group(assembly, selectedChild.source(), selectedChild.cube());
			List<Member> parentMembers = group(assembly, selectedParent.source(), selectedParent.cube());
			Connection connection = connection(connections, selectedChild, selectedParent,
				childMembers, parentMembers);
			if (connection == null)
				continue;
			CubeBox child = box(sources, connection.child());
			CubeBox parent = box(sources, connection.parent());
			if (child == null || parent == null)
				continue;
			Vec3 pivot = pivot(limb.type(), child, parent);
			Vec3 drivenCenter = groupCenter(childMembers, sources);
			Vec3 restDirection = (drivenCenter == null ? child.center() : drivenCenter).subtract(pivot);
			if (restDirection.lengthSqr() < GEOMETRY_EPSILON)
				continue;
			byType.computeIfAbsent(limb.type(), ignored -> new ArrayList<>())
				.add(new ResolvedLimb(limb.type(), childMembers, pivot,
					BODY_SPACE.project(child.center(), AXIS_X) - bodyCenterX,
					BODY_SPACE.restAlignment(limb.type(), restDirection), LimbDriver.NONE));
		}
		List<ResolvedLimb> resolved = new ArrayList<>();
		byType.forEach((type, limbs) -> {
			limbs.sort((first, second) -> Double.compare(first.side(), second.side()));
			if (type == SurgicalLimbType.NECK) {
				if (!limbs.isEmpty())
					resolved.add(limbs.getFirst().withDriver(LimbDriver.HEAD));
				return;
			}
			if (limbs.size() == 1) {
				ResolvedLimb limb = limbs.getFirst();
				resolved.add(limb.withDriver(limb.side() > 0.0d ? LimbDriver.LEFT : LimbDriver.RIGHT));
				return;
			}
			for (int slot = 0; slot < limbs.size(); slot++)
				resolved.add(limbs.get(slot).withDriver(slot == 0 ? LimbDriver.RIGHT
					: slot == 1 ? LimbDriver.LEFT : LimbDriver.NONE));
		});
		return resolved;
	}

	/**
	 * Measures the effective leg length used by movement and animation retargeting.
	 *
	 * <p>A vanilla humanoid's hip is twelve model pixels above its sole. For each installed hip we
	 * instead measure from the resolved hinge down to the lowest point of the complete rotating leg
	 * group. Averaging both sides keeps an asymmetric body on one shared alternating gait.</p>
	 */
	public static float effectiveLegLength(SurgicalAssembly assembly, List<SourceState> sources) {
		if (assembly == null || sources == null || sources.size() != assembly.sources().size())
			return 0.0f;
		return effectiveLegLength(resolveLimbs(assembly, sources), sources);
	}

	private static float effectiveLegLength(List<ResolvedLimb> limbs, List<SourceState> sources) {
		double totalLength = 0.0d;
		int measuredLegs = 0;
		for (ResolvedLimb limb : limbs) {
			if (limb.type() != SurgicalLimbType.HIP)
				continue;
			double pivotHeight = BODY_SPACE.project(limb.pivot(), AXIS_Y);
			double soleHeight = Double.NEGATIVE_INFINITY;
			for (Member member : limb.members()) {
				CubeBox box = box(sources, member);
				if (box != null)
					soleHeight = Math.max(soleHeight, box.max()[AXIS_Y]);
			}
			double legLength = soleHeight - pivotHeight;
			if (!Double.isFinite(legLength) || legLength <= GEOMETRY_EPSILON)
				continue;
			totalLength += legLength;
			measuredLegs++;
		}
		if (measuredLegs == 0)
			return 0.0f;
		return (float) (totalLength / measuredLegs);
	}

	/** Centre of the complete rigid part that an installed joint rotates. */
	@Nullable
	private static Vec3 groupCenter(List<Member> members, List<SourceState> sources) {
		Vec3 sum = Vec3.ZERO;
		int count = 0;
		for (Member member : members) {
			CubeBox box = box(sources, member);
			if (box == null)
				continue;
			sum = sum.add(box.center());
			count++;
		}
		return count == 0 ? null : sum.scale(1.0d / count);
	}

	/**
	 * Finds the same geometric centre the renderer later moves onto the entity origin.
	 *
	 * <p>A limb's side belongs to its position in the complete body, not to the direction from an
	 * arbitrarily placed parent cube.</p>
	 */
	private static double bodyCenter(List<SourceState> sources, int axis) {
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;
		for (SourceState source : sources)
			for (CubeBox box : source.boxes().values()) {
				min = Math.min(min, box.min()[axis]);
				max = Math.max(max, box.max()[axis]);
			}
		return Double.isFinite(min) && Double.isFinite(max) ? (min + max) * 0.5d : 0.0d;
	}

	/** The cubes that rotate with {@code cube}: its honey combination, or the cube on its own. */
	private static List<Member> group(SurgicalAssembly assembly, int source, int cube) {
		for (SurgicalAssembly.Combination combination : assembly.combinations()) {
			boolean contains = false;
			for (SurgicalAssembly.CombinationMember member : combination.members())
				if (member.source() == source && member.cube() == cube) {
					contains = true;
					break;
				}
			if (!contains)
				continue;
			List<Member> members = new ArrayList<>(combination.members().size());
			for (SurgicalAssembly.CombinationMember member : combination.members())
				members.add(new Member(member.source(), member.cube()));
			return List.copyOf(members);
		}
		return List.of(new Member(source, cube));
	}

	@Nullable
	private static CubeBox box(List<SourceState> sources, Member member) {
		return member.source() < 0 || member.source() >= sources.size() ? null
			: sources.get(member.source()).boxes().get(member.cube());
	}

	/** Resolves old arbitrary combination endpoints as well as newly stored physical endpoints. */
	@Nullable
	private static Connection connection(SurgicalConnectionGraph<Integer> connections, Member selectedChild,
		Member selectedParent, List<Member> childMembers, List<Member> parentMembers) {
		if (directlyConnected(connections, selectedChild, selectedParent))
			return new Connection(selectedChild, selectedParent);
		for (Member child : childMembers)
			for (Member parent : parentMembers)
				if (directlyConnected(connections, child, parent))
					return new Connection(child, parent);
		return null;
	}

	private static boolean directlyConnected(SurgicalConnectionGraph<Integer> connections,
		Member first, Member second) {
		return connections.directConnections(first.source(), first.cube())
			.contains(second.source(), second.cube());
	}

	@Nullable
	private static SurgicalConnectionGraph<Integer> connectionGraph(SurgicalAssembly assembly) {
		List<SurgicalConnectionGraph.Body<Integer>> bodies = new ArrayList<>(assembly.sources().size());
		for (int sourceId = 0; sourceId < assembly.sources().size(); sourceId++) {
			SurgicalAssembly.Source source = assembly.sources().get(sourceId);
			bodies.add(new SurgicalConnectionGraph.Body<>(sourceId, source.cubeCount(), source.presentCubes(),
				source.seams(), source.cutSeams()));
		}
		List<SurgicalConnectionGraph.Link<Integer>> links = assembly.joints().stream()
			.map(joint -> new SurgicalConnectionGraph.Link<>(joint.firstSource(), joint.firstCube(),
				joint.secondSource(), joint.secondCube()))
			.toList();
		return SurgicalConnectionGraph.create(bodies, links);
	}

	/**
	 * Places the hinge on the child end that meets its parent.
	 *
	 * <p>Elongated parts use their principal geometric axis, so the calculation follows an arm or leg
	 * after the player lays it flat or points it upward. Cube-like parts use the surface reached by a
	 * ray toward the parent, which is the stable choice for heads and other compact pieces.</p>
	 */
	private static Vec3 pivot(SurgicalLimbType type, CubeBox child, CubeBox parent) {
		// Head pitch and yaw must originate at the neck connection itself. Using an endpoint of an
		// elongated or unusually shaped head makes it orbit around its own centre instead of nodding.
		if (type == SurgicalLimbType.NECK)
			return child.contactCenter(parent, BODY_SPACE);
		Vec3 principal = child.principalAxis(BODY_SPACE);
		if (principal == null)
			return child.surfaceToward(parent.center(), type, BODY_SPACE);

		double radius = child.supportRadius(principal);
		Vec3 first = child.center().add(principal.scale(radius));
		Vec3 second = child.center().subtract(principal.scale(radius));
		double firstDistance = parent.distanceToSqr(first, BODY_SPACE);
		double secondDistance = parent.distanceToSqr(second, BODY_SPACE);
		if (Math.abs(firstDistance - secondDistance) > GEOMETRY_EPSILON)
			return firstDistance < secondDistance ? first : second;

		// A vertical arm can run beside the whole torso, making both ends equally close. In that
		// genuinely ambiguous case, choose the end facing the parent's vertical centre; a centred
		// shoulder/hip uses its upper end, matching the canonical humanoid rest pose.
		double childY = BODY_SPACE.project(child.center(), AXIS_Y);
		double parentY = BODY_SPACE.project(parent.center(), AXIS_Y);
		double firstY = BODY_SPACE.project(first, AXIS_Y);
		double secondY = BODY_SPACE.project(second, AXIS_Y);
		double targetY = childY < parentY ? Math.max(firstY, secondY) : Math.min(firstY, secondY);
		if (type == SurgicalLimbType.NECK && Math.abs(childY - parentY) <= GEOMETRY_EPSILON)
			targetY = Math.max(firstY, secondY);
		return Math.abs(firstY - targetY) <= Math.abs(secondY - targetY) ? first : second;
	}


	/** Runs one vanilla zombie animation frame and exposes the resulting joint angles. */
	@Nullable
	private static Pose pose(SlimeBionicEntity entity, float partialTick, float legLength) {
		HumanoidModel<LivingEntity> model = zombieModel();
		if (model == null)
			return null;
		float bodyRot = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
		float headRot = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot);
		float netHeadYaw = Mth.wrapDegrees(headRot - bodyRot);
		float headPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
		float ageInTicks = entity.tickCount + partialTick;
		float limbSwing = 0.0f;
		float limbSwingAmount = 0.0f;
		if (!entity.isPassenger() && entity.isAlive()) {
			// WalkAnimation.position keeps accumulating at the full movement-derived speed. Capping only
			// the amount therefore converts speed beyond this point into faster steps, not wider swings.
			limbSwingAmount = Math.min(entity.walkAnimation.speed(partialTick),
				SurgicalGait.maximumHumanoidSwingAmount(legLength));
			limbSwing = entity.walkAnimation.position(partialTick)
				* SurgicalGait.animationFrequencyScale(legLength);
		}

		model.attackTime = entity.getAttackAnim(partialTick);
		model.riding = entity.isPassenger();
		model.young = false;
		model.crouching = false;
		model.swimAmount = entity.getSwimAmount(partialTick);
		model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
		model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
		// Deliberately stopping at the shared humanoid pass. AbstractZombieModel would layer its
		// raised-arm pose on top, which both forces a resting angle onto every shoulder and discards
		// the walk swing computed here.
		model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
		int attackAnimationTick = entity.getAttackAnimationTick();
		if (attackAnimationTick > 0) {
			float attackArmPitch = -2.0f
				+ 1.5f * Mth.triangleWave(attackAnimationTick - partialTick, 10.0f);
			model.rightArm.xRot = attackArmPitch;
			model.leftArm.xRot = attackArmPitch;
		}
		return new Pose(model);
	}

	@Nullable
	private static HumanoidModel<LivingEntity> zombieModel() {
		if (zombieModel != null)
			return zombieModel;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getEntityModels() == null)
			return null;
		zombieModel = new HumanoidModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.ZOMBIE));
		return zombieModel;
	}

	private record Pose(HumanoidModel<LivingEntity> model) {
		@Nullable
		private ModelPart driver(ResolvedLimb limb) {
			return switch (limb.driver()) {
			case HEAD -> model.head;
			case RIGHT -> limb.type() == SurgicalLimbType.SHOULDER ? model.rightArm : model.rightLeg;
			case LEFT -> limb.type() == SurgicalLimbType.SHOULDER ? model.leftArm : model.leftLeg;
			case NONE -> null;
			};
		}
	}

	private record Member(int source, int cube) {}
	private record Connection(Member child, Member parent) {}
	private enum LimbDriver { NONE, HEAD, RIGHT, LEFT }

	private record ResolvedLimb(SurgicalLimbType type, List<Member> members, Vec3 pivot, double side,
		SurgicalCubeRotation restAlignment, LimbDriver driver) {
		private ResolvedLimb withDriver(LimbDriver driver) {
			return new ResolvedLimb(type, members, pivot, side, restAlignment, driver);
		}
	}

	/** The rest state of one source, exactly as the visible render receives it. */
	public record SourceState(Map<Integer, CubeBox> boxes, Map<Integer, Vec3> offsets,
		Map<Integer, SurgicalCubeRotation> rotations) {}

	/** One source's cube transforms for the current frame, in the yaw-zero body frame. */
	public record Frame(Map<Integer, Vec3> offsets, Map<Integer, SurgicalCubeRotation> rotations) {
		public static final Frame EMPTY = new Frame(Map.of(), Map.of());

		public boolean isEmpty() {
			return offsets.isEmpty() && rotations.isEmpty();
		}

		public Map<Integer, Vec3> mergeOffsets(Map<Integer, Vec3> base) {
			if (offsets.isEmpty())
				return base;
			Map<Integer, Vec3> merged = new HashMap<>(base);
			merged.putAll(offsets);
			return merged;
		}

		public Map<Integer, SurgicalCubeRotation> mergeRotations(Map<Integer, SurgicalCubeRotation> base) {
			if (rotations.isEmpty())
				return base;
			Map<Integer, SurgicalCubeRotation> merged = new HashMap<>(base);
			merged.putAll(rotations);
			return merged;
		}
	}

	/**
	 * The body-local frame the capture baked into its vertices.
	 *
	 * <p>{@code LivingEntityRenderer} turns the model by {@code 180° - yaw} and then mirrors it with
	 * {@code scale(-1, -1, 1)}, so vanilla model space arrives rotated and flipped: model {@code +X}
	 * is the body's left, {@code +Y} points down and {@code +Z} points backwards. Reproducing that
	 * exact frame is what lets zombie joint angles apply unchanged.</p>
	 */
	private record Basis(Quaternionf modelToWorld, Vec3 modelX, Vec3 modelY, Vec3 modelZ) {
		private static Basis bodySpace() {
			return new Basis(new Quaternionf().rotateY((float) Math.PI).rotateZ((float) Math.PI),
				new Vec3(1.0d, 0.0d, 0.0d), new Vec3(0.0d, -1.0d, 0.0d),
				new Vec3(0.0d, 0.0d, -1.0d));
		}

		private Vec3 axis(int index) {
			return index == AXIS_Y ? modelY : index == AXIS_Z ? modelZ : modelX;
		}

		private double project(Vec3 vector, int index) {
			return vector.dot(axis(index));
		}

		private Vec3 point(double x, double y, double z) {
			return modelX.scale(x).add(modelY.scale(y)).add(modelZ.scale(z));
		}

		/** Builds the rest-frame rotation that maps a canonical humanoid limb onto this child. */
		private SurgicalCubeRotation restAlignment(SurgicalLimbType type, Vec3 restDirection) {
			Vec3 actual = new Vec3(project(restDirection, AXIS_X), project(restDirection, AXIS_Y),
				project(restDirection, AXIS_Z)).normalize();
			Vector3f canonical = new Vector3f(0.0f,
				type == SurgicalLimbType.NECK ? -1.0f : 1.0f, 0.0f);
			Vector3f actualVector = new Vector3f((float) actual.x, (float) actual.y, (float) actual.z);
			Quaternionf alignment = new Quaternionf().rotationTo(canonical, actualVector);

			// rotationTo fixes the long direction but leaves twist underdetermined. Define the
			// swing axis geometrically: it is perpendicular to the installed limb and body forward,
			// just as canonical humanoid X is perpendicular to a hanging limb and model Z.
			Vec3 targetX = actual.cross(new Vec3(0.0d, 0.0d, 1.0d))
				.scale(type == SurgicalLimbType.NECK ? -1.0d : 1.0d);
			if (targetX.lengthSqr() < GEOMETRY_EPSILON)
				targetX = new Vec3(1.0d, 0.0d, 0.0d)
					.subtract(actual.scale(actual.x));
			targetX = targetX.normalize();
			Vector3f mapped = alignment.transform(new Vector3f(1.0f, 0.0f, 0.0f));
			Vec3 mappedX = new Vec3(mapped.x, mapped.y, mapped.z).normalize();
			double sine = actual.dot(mappedX.cross(targetX));
			double cosine = Mth.clamp(mappedX.dot(targetX), -1.0d, 1.0d);
			float twistAngle = (float) Math.atan2(sine, cosine);
			if (Math.abs(twistAngle) > 1.0e-6f) {
				Quaternionf twist = new Quaternionf().rotationAxis(twistAngle, actualVector);
				alignment = twist.mul(alignment);
			}
			return new SurgicalCubeRotation(alignment.x(), alignment.y(), alignment.z(), alignment.w());
		}

		/** Retargets vanilla {@link ModelPart} Euler angles through the measured rest frame. */
		private SurgicalCubeRotation reframe(SurgicalCubeRotation restAlignment,
			float zRot, float yRot, float xRot) {
			if (zRot == 0.0f && yRot == 0.0f && xRot == 0.0f)
				return SurgicalCubeRotation.IDENTITY;
			Quaternionf alignment = new Quaternionf((float) restAlignment.x(),
				(float) restAlignment.y(), (float) restAlignment.z(), (float) restAlignment.w());
			Quaternionf modelRotation = new Quaternionf(alignment)
				.mul(new Quaternionf().rotationZYX(zRot, yRot, xRot))
				.mul(new Quaternionf(alignment).conjugate());
			Quaternionf world = new Quaternionf(modelToWorld)
				.mul(modelRotation)
				.mul(new Quaternionf(modelToWorld).conjugate());
			return new SurgicalCubeRotation(world.x(), world.y(), world.z(), world.w());
		}
	}

	/**
	 * One rest-state cube: its world centre plus its span along the body-local axes.
	 *
	 * <p>The centre is the mean of the transformed corners, which is exactly the point the render
	 * path rotates a cube around once its static offset is included.</p>
	 */
	public record CubeBox(Vec3 center, double[] min, double[] max, List<Vec3> points) {
		public CubeBox {
			points = List.copyOf(points);
		}

		@Nullable
		private static CubeBox of(List<Vec3> corners, Basis basis) {
			if (corners.isEmpty())
				return null;
			double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
			double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
			Vec3 sum = Vec3.ZERO;
			for (Vec3 corner : corners) {
				for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
					double projected = basis.project(corner, axis);
					min[axis] = Math.min(min[axis], projected);
					max[axis] = Math.max(max[axis], projected);
				}
				sum = sum.add(corner);
			}
			return new CubeBox(sum.scale(1.0d / corners.size()), min, max, corners);
		}

		/** Longest covariance axis, or null when the group is too cube-like to define one. */
		@Nullable
		private Vec3 principalAxis(Basis basis) {
			if (points.size() < 2)
				return null;
			Vec3 mean = Vec3.ZERO;
			for (Vec3 point : points)
				mean = mean.add(point);
			mean = mean.scale(1.0d / points.size());
			double xx = 0.0d;
			double xy = 0.0d;
			double xz = 0.0d;
			double yy = 0.0d;
			double yz = 0.0d;
			double zz = 0.0d;
			for (Vec3 point : points) {
				Vec3 delta = point.subtract(mean);
				xx += delta.x * delta.x;
				xy += delta.x * delta.y;
				xz += delta.x * delta.z;
				yy += delta.y * delta.y;
				yz += delta.y * delta.z;
				zz += delta.z * delta.z;
			}
			double trace = xx + yy + zz;
			if (trace < GEOMETRY_EPSILON)
				return null;
			int seedAxis = AXIS_X;
			for (int axis = AXIS_Y; axis <= AXIS_Z; axis++)
				if (max[axis] - min[axis] > max[seedAxis] - min[seedAxis])
					seedAxis = axis;
			Vec3 axis = basis.axis(seedAxis);
			for (int iteration = 0; iteration < 16; iteration++) {
				Vec3 next = new Vec3(xx * axis.x + xy * axis.y + xz * axis.z,
					xy * axis.x + yy * axis.y + yz * axis.z,
					xz * axis.x + yz * axis.y + zz * axis.z);
				if (next.lengthSqr() < GEOMETRY_EPSILON)
					return null;
				axis = next.normalize();
			}
			Vec3 multiplied = new Vec3(xx * axis.x + xy * axis.y + xz * axis.z,
				xy * axis.x + yy * axis.y + yz * axis.z,
				xz * axis.x + yz * axis.y + zz * axis.z);
			double eigenvalue = axis.dot(multiplied);
			return eigenvalue / trace >= PRINCIPAL_AXIS_SHARE ? axis : null;
		}

		private double supportRadius(Vec3 axis) {
			double radius = 0.0d;
			for (Vec3 point : points)
				radius = Math.max(radius, Math.abs(point.subtract(center).dot(axis)));
			return radius;
		}

		private double distanceToSqr(Vec3 point, Basis basis) {
			double distance = 0.0d;
			for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
				double projected = basis.project(point, axis);
				double outside = projected < min[axis] ? min[axis] - projected
					: projected > max[axis] ? projected - max[axis] : 0.0d;
				distance += outside * outside;
			}
			return distance;
		}

		/** Centre of the nearest shared/connecting region between this cube and another. */
		private Vec3 contactCenter(CubeBox other, Basis basis) {
			double[] coordinate = new double[3];
			for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
				if (max[axis] < other.min[axis])
					coordinate[axis] = (max[axis] + other.min[axis]) * 0.5d;
				else if (other.max[axis] < min[axis])
					coordinate[axis] = (min[axis] + other.max[axis]) * 0.5d;
				else
					coordinate[axis] = (Math.max(min[axis], other.min[axis])
						+ Math.min(max[axis], other.max[axis])) * 0.5d;
			}
			return basis.point(coordinate[AXIS_X], coordinate[AXIS_Y], coordinate[AXIS_Z]);
		}

		private Vec3 surfaceToward(Vec3 target, SurgicalLimbType type, Basis basis) {
			Vec3 direction = target.subtract(center);
			if (direction.lengthSqr() < GEOMETRY_EPSILON)
				direction = basis.axis(AXIS_Y).scale(type == SurgicalLimbType.NECK ? 1.0d : -1.0d);
			double scale = Double.POSITIVE_INFINITY;
			for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
				double component = basis.project(direction, axis);
				if (Math.abs(component) <= GEOMETRY_EPSILON)
					continue;
				double centerProjection = basis.project(center, axis);
				double boundary = component > 0.0d ? max[axis] : min[axis];
				scale = Math.min(scale, (boundary - centerProjection) / component);
			}
			return Double.isFinite(scale) ? center.add(direction.scale(scale)) : center;
		}
	}
}
