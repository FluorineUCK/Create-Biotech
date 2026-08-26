package com.nobodiiiii.createbiotech.entity.client.animation;

import java.util.EnumMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * Samples the complete client-side pose for a bionic slime.
 *
 * <p>This module deliberately knows nothing about surgical cubes, connections, pivots or rendering.
 * Its sole contract is an animation context in and one local rotation per anatomical bone out. The
 * solver can therefore keep using these poses if the animation catalogue later grows into state
 * machines or authored keyframes.</p>
 *
 * <p>The two-level arm and leg layout follows Maledictus's exact 1.21.1 model and walk-animation
 * structure in
 * {@code ref/1.21.1/Cataclysm/src/main/java/com/github/L_Ender/cataclysm/client/model/entity/Maledictus_Model.java}
 * and
 * {@code ref/1.21.1/Cataclysm/src/main/java/com/github/L_Ender/cataclysm/client/animation/Maledictus_Animation.java}:
 * front arms are children of upper arms, and front legs are children of upper legs. The lightweight
 * bend curves below preserve that hierarchy without copying Cataclysm's boss-specific animation
 * catalogue.</p>
 */
public final class SlimeBionicAnimations {
	private static final float WALK_PHASE_SCALE = 0.6662f;
	private static final float MIN_WALK_ELBOW_DEGREES = 15.0f;
	private static final float MAX_WALK_ELBOW_DEGREES = 37.5f;
	private static final float MIN_WALK_KNEE_DEGREES = 3.0f;
	private static final float MAX_WALK_KNEE_DEGREES = 51.0f;
	@Nullable
	private static HumanoidModel<LivingEntity> humanoidModel;

	private SlimeBionicAnimations() {}

	public static void clearCache() {
		humanoidModel = null;
	}

	public static Pose sample(Context context) {
		if (context == null || context.entity() == null)
			return Pose.EMPTY;
		HumanoidModel<LivingEntity> model = humanoidModel();
		if (model == null)
			return Pose.EMPTY;

		model.attackTime = context.attackTime();
		model.riding = context.riding();
		model.young = false;
		model.crouching = false;
		model.swimAmount = context.swimAmount();
		model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
		model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
		// Stop at the shared humanoid pass: AbstractZombieModel's raised-arm layer would erase the
		// walking shoulder swing and force a non-neutral rest pose onto every installed arm.
		model.setupAnim(context.entity(), context.limbSwing(), context.limbSwingAmount(),
			context.ageInTicks(), context.netHeadYaw(), context.headPitch());
		if (context.attackAnimationTick() > 0) {
			float attackArmPitch = -2.0f + 1.5f * Mth.triangleWave(
				context.attackAnimationTick() - context.partialTick(), 10.0f);
			model.rightArm.xRot = attackArmPitch;
			model.leftArm.xRot = attackArmPitch;
		}

		EnumMap<Bone, Rotation> rotations = new EnumMap<>(Bone.class);
		rotations.put(Bone.HEAD, Rotation.of(model.head));
		rotations.put(Bone.RIGHT_SHOULDER, Rotation.of(model.rightArm));
		rotations.put(Bone.LEFT_SHOULDER, Rotation.of(model.leftArm));
		rotations.put(Bone.RIGHT_HIP, Rotation.of(model.rightLeg));
		rotations.put(Bone.LEFT_HIP, Rotation.of(model.leftLeg));
		addLowerLimbPose(rotations, context);
		return new Pose(rotations);
	}

	/** Maledictus-inspired alternating flexion, expressed locally beneath each upper limb. */
	private static void addLowerLimbPose(EnumMap<Bone, Rotation> rotations, Context context) {
		float weight = Mth.clamp(context.walkWeight(), 0.0f, 1.0f);
		if (weight <= 0.0f) {
			rotations.put(Bone.RIGHT_ELBOW, Rotation.IDENTITY);
			rotations.put(Bone.LEFT_ELBOW, Rotation.IDENTITY);
			rotations.put(Bone.RIGHT_KNEE, Rotation.IDENTITY);
			rotations.put(Bone.LEFT_KNEE, Rotation.IDENTITY);
			return;
		}

		float phase = context.limbSwing() * WALK_PHASE_SCALE;
		float alternating = Mth.cos(phase);
		float rightElbowDegrees = Mth.lerp((1.0f - alternating) * 0.5f,
			MIN_WALK_ELBOW_DEGREES, MAX_WALK_ELBOW_DEGREES);
		float leftElbowDegrees = Mth.lerp((1.0f + alternating) * 0.5f,
			MIN_WALK_ELBOW_DEGREES, MAX_WALK_ELBOW_DEGREES);
		float rightKneeDegrees = Mth.lerp(Math.max(0.0f, Mth.sin(phase)),
			MIN_WALK_KNEE_DEGREES, MAX_WALK_KNEE_DEGREES);
		float leftKneeDegrees = Mth.lerp(Math.max(0.0f, -Mth.sin(phase)),
			MIN_WALK_KNEE_DEGREES, MAX_WALK_KNEE_DEGREES);

		rotations.put(Bone.RIGHT_ELBOW, Rotation.x(-rightElbowDegrees * Mth.DEG_TO_RAD * weight));
		rotations.put(Bone.LEFT_ELBOW, Rotation.x(-leftElbowDegrees * Mth.DEG_TO_RAD * weight));
		rotations.put(Bone.RIGHT_KNEE, Rotation.x(rightKneeDegrees * Mth.DEG_TO_RAD * weight));
		rotations.put(Bone.LEFT_KNEE, Rotation.x(leftKneeDegrees * Mth.DEG_TO_RAD * weight));
	}

	@Nullable
	private static HumanoidModel<LivingEntity> humanoidModel() {
		if (humanoidModel != null)
			return humanoidModel;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.getEntityModels() == null)
			return null;
		humanoidModel = new HumanoidModel<>(minecraft.getEntityModels().bakeLayer(ModelLayers.ZOMBIE));
		return humanoidModel;
	}

	public enum Bone {
		HEAD,
		RIGHT_SHOULDER,
		LEFT_SHOULDER,
		RIGHT_ELBOW,
		LEFT_ELBOW,
		RIGHT_HIP,
		LEFT_HIP,
		RIGHT_KNEE,
		LEFT_KNEE
	}

	/** All time-varying inputs needed to sample one pose; no assembly or renderer state leaks in. */
	public record Context(LivingEntity entity, float limbSwing, float limbSwingAmount,
		float walkWeight, float ageInTicks, float netHeadYaw, float headPitch, float attackTime,
		boolean riding, float swimAmount, int attackAnimationTick, float partialTick) {}

	/** Euler rotation in the same Z-Y-X order used by vanilla model parts. */
	public record Rotation(float x, float y, float z) {
		public static final Rotation IDENTITY = new Rotation(0.0f, 0.0f, 0.0f);

		private static Rotation of(ModelPart part) {
			return new Rotation(part.xRot, part.yRot, part.zRot);
		}

		private static Rotation x(float radians) {
			return new Rotation(radians, 0.0f, 0.0f);
		}
	}

	public record Pose(Map<Bone, Rotation> rotations) {
		public static final Pose EMPTY = new Pose(Map.of());

		public Pose {
			rotations = rotations == null || rotations.isEmpty() ? Map.of() : Map.copyOf(rotations);
		}

		public Rotation rotation(@Nullable Bone bone) {
			return bone == null ? Rotation.IDENTITY : rotations.getOrDefault(bone, Rotation.IDENTITY);
		}
	}
}
