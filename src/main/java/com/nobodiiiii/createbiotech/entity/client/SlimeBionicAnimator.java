package com.nobodiiiii.createbiotech.entity.client;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

import com.nobodiiiii.createbiotech.content.surgery.SurgicalAssembly;
import com.nobodiiiii.createbiotech.content.surgery.SurgicalCubeRotation;
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
	@Nullable
	private static HumanoidModel<LivingEntity> zombieModel;

	private SlimeBionicAnimator() {}

	public static void clearCache() {
		zombieModel = null;
	}

	/**
	 * Reduces one source's captured cubes to what the joint maths needs.
	 *
	 * <p>The snapshot must come from the same pose stack and the same static offsets and rotations
	 * the visible render uses, because both the pivot and the resulting translations live in the
	 * render path's world axes.</p>
	 */
	public static Map<Integer, CubeBox> measure(SurgicalModelRenderContext.Snapshot snapshot, float yaw) {
		Basis basis = Basis.of(yaw);
		Map<Integer, CubeBox> boxes = new HashMap<>();
		for (SurgicalModelRenderContext.CubeGeometry cube : snapshot.cubes()) {
			CubeBox box = CubeBox.of(cube.corners(), basis);
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
		List<SourceState> sources, float yaw, float partialTick) {
		int sourceCount = assembly.sources().size();
		List<Frame> frames = new ArrayList<>(sourceCount);
		for (int source = 0; source < sourceCount; source++)
			frames.add(Frame.EMPTY);
		if (assembly.limbs().isEmpty() || sources.size() != sourceCount)
			return frames;
		Basis basis = Basis.of(yaw);
		List<ResolvedLimb> limbs = resolveLimbs(assembly, sources, basis);
		if (limbs.isEmpty())
			return frames;
		Pose pose = pose(entity, partialTick);
		if (pose == null)
			return frames;

		Map<Integer, Map<Integer, Vec3>> offsets = new HashMap<>();
		Map<Integer, Map<Integer, SurgicalCubeRotation>> rotations = new HashMap<>();
		for (ResolvedLimb limb : limbs) {
			ModelPart driver = pose.driver(limb);
			if (driver == null)
				continue;
			SurgicalCubeRotation rotation = basis.reframe(driver.zRot, driver.yRot, driver.xRot);
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
	 * <p>Honey combinations behave as a single rigid cube, and paired limbs are ordered left to
	 * right across the body so two legs stride in opposite phase instead of together.</p>
	 */
	private static List<ResolvedLimb> resolveLimbs(SurgicalAssembly assembly, List<SourceState> sources,
		Basis basis) {
		Map<SurgicalLimbType, List<ResolvedLimb>> byType = new EnumMap<>(SurgicalLimbType.class);
		for (SurgicalAssembly.Limb limb : assembly.limbs()) {
			List<Member> childMembers = group(assembly, limb.childSource(), limb.childCube());
			CubeBox child = union(sources, childMembers, basis);
			CubeBox parent = union(sources, group(assembly, limb.parentSource(), limb.parentCube()), basis);
			if (child == null || parent == null)
				continue;
			byType.computeIfAbsent(limb.type(), ignored -> new ArrayList<>())
				.add(new ResolvedLimb(limb.type(), childMembers, pivot(child, parent, basis),
					child.projected(AXIS_X), 0));
		}
		List<ResolvedLimb> resolved = new ArrayList<>();
		byType.forEach((type, limbs) -> {
			limbs.sort((first, second) -> Double.compare(first.side(), second.side()));
			for (int slot = 0; slot < limbs.size(); slot++)
				resolved.add(limbs.get(slot).withSlot(slot));
		});
		return resolved;
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
	private static CubeBox union(List<SourceState> sources, List<Member> members, Basis basis) {
		CubeBox union = null;
		for (Member member : members) {
			if (member.source() < 0 || member.source() >= sources.size())
				continue;
			CubeBox box = sources.get(member.source()).boxes().get(member.cube());
			if (box == null)
				continue;
			union = union == null ? box : union.merge(box, basis);
		}
		return union;
	}

	/**
	 * Places the hinge where the limb meets the body.
	 *
	 * <p>A neck, a shoulder and a hip are all vertical hinges in every vanilla model, so the axis is
	 * the body's own vertical one and the pivot sits on the limb's centre line — which is exactly
	 * where vanilla puts its head, arm and leg pivots. Only which <em>end</em> hinges is left to
	 * geometry.</p>
	 *
	 * <p>Limbs hang, so the upper end is the joint. The one exception is a part the body sits
	 * entirely below — a head on a torso, or a horn on a head — which is jointed at its underside
	 * instead. Asking instead which end lies nearer the parent cannot decide this: an arm spans its
	 * torso's exact height, so both of its ends are equally near and any such measure ties.</p>
	 */
	private static Vec3 pivot(CubeBox child, CubeBox parent, Basis basis) {
		// Projections onto the body-local vertical axis grow downwards, so the larger value is lower.
		double top = child.min(AXIS_Y);
		double bottom = child.max(AXIS_Y);
		double parentMiddle = (parent.min(AXIS_Y) + parent.max(AXIS_Y)) * 0.5d;
		double reach = parentMiddle > bottom ? bottom : top;
		return child.center().add(basis.axis(AXIS_Y)
			.scale(reach - basis.project(child.center(), AXIS_Y)));
	}


	/** Runs one vanilla zombie animation frame and exposes the resulting joint angles. */
	@Nullable
	private static Pose pose(SlimeBionicEntity entity, float partialTick) {
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
			limbSwingAmount = Math.min(entity.walkAnimation.speed(partialTick), 1.0f);
			limbSwing = entity.walkAnimation.position(partialTick);
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
			return switch (limb.type()) {
			case NECK -> limb.slot() == 0 ? model.head : null;
			case SHOULDER -> limb.slot() == 0 ? model.rightArm : limb.slot() == 1 ? model.leftArm : null;
			case HIP -> limb.slot() == 0 ? model.rightLeg : limb.slot() == 1 ? model.leftLeg : null;
			};
		}
	}

	private record Member(int source, int cube) {}

	private record ResolvedLimb(SurgicalLimbType type, List<Member> members, Vec3 pivot, double side,
		int slot) {
		private ResolvedLimb withSlot(int slot) {
			return new ResolvedLimb(type, members, pivot, side, slot);
		}
	}

	/** The rest state of one source, exactly as the visible render receives it. */
	public record SourceState(Map<Integer, CubeBox> boxes, Map<Integer, Vec3> offsets,
		Map<Integer, SurgicalCubeRotation> rotations) {}

	/** One source's cube transforms for the current frame, in the render path's world axes. */
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
		private static Basis of(float yaw) {
			double radians = Math.toRadians(yaw);
			Quaternionf modelToWorld = new Quaternionf()
				.rotateY((float) Math.toRadians(180.0d - yaw))
				.rotateZ((float) Math.PI);
			return new Basis(modelToWorld,
				new Vec3(Math.cos(radians), 0.0d, Math.sin(radians)),
				new Vec3(0.0d, -1.0d, 0.0d),
				new Vec3(Math.sin(radians), 0.0d, -Math.cos(radians)));
		}

		private Vec3 axis(int index) {
			return index == AXIS_Y ? modelY : index == AXIS_Z ? modelZ : modelX;
		}

		private double project(Vec3 vector, int index) {
			return vector.dot(axis(index));
		}

		private Vec3 toWorld(double x, double y, double z) {
			return modelX.scale(x).add(modelY.scale(y)).add(modelZ.scale(z));
		}

		/** Converts vanilla {@link ModelPart} Euler angles into a world-axis rotation. */
		private SurgicalCubeRotation reframe(float zRot, float yRot, float xRot) {
			if (zRot == 0.0f && yRot == 0.0f && xRot == 0.0f)
				return SurgicalCubeRotation.IDENTITY;
			Quaternionf world = new Quaternionf(modelToWorld)
				.mul(new Quaternionf().rotationZYX(zRot, yRot, xRot))
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
	public record CubeBox(Vec3 center, double[] min, double[] max) {
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
			return new CubeBox(sum.scale(1.0d / corners.size()), min, max);
		}

		/** Merged groups take the enclosing box, so the centre has to be rebuilt from its corners. */
		private CubeBox merge(CubeBox other, Basis basis) {
			double[] min = new double[3];
			double[] max = new double[3];
			for (int axis = AXIS_X; axis <= AXIS_Z; axis++) {
				min[axis] = Math.min(this.min[axis], other.min[axis]);
				max[axis] = Math.max(this.max[axis], other.max[axis]);
			}
			return new CubeBox(basis.toWorld((min[AXIS_X] + max[AXIS_X]) * 0.5d,
				(min[AXIS_Y] + max[AXIS_Y]) * 0.5d, (min[AXIS_Z] + max[AXIS_Z]) * 0.5d), min, max);
		}

		private double min(int axis) {
			return min[axis];
		}

		private double max(int axis) {
			return max[axis];
		}

		private double projected(int axis) {
			return (min[axis] + max[axis]) * 0.5d;
		}
	}
}
