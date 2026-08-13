package com.nobodiiiii.createbiotech.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nobodiiiii.createbiotech.content.sonicdogcannon.SonicConeWaveParticleOption;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class SonicDogCannonClientEffects {

	private static final int PARTICLE_COUNT = 10;
	private static final int PARTICLE_DELAY_TICKS = 1;

	private static final double FIRST_PERSON_HAND_SIDE = 0.56d;
	private static final double FIRST_PERSON_HAND_DOWN = 0.52d;
	private static final double FIRST_PERSON_HAND_FORWARD = 0.72d;
	private static final double MUZZLE_FORWARD_FROM_ITEM_CENTRE = 22.0d / 16.0d;
	private static final double FIRST_PERSON_MUZZLE_UP_FROM_HAND = (7.25d - 2.4d) / 16.0d;

	private static final float MODEL_ROOT_HEIGHT = 1.501f;
	private static final float ARM_PIVOT_SIDE = 5.0f / 16.0f;
	private static final float ARM_PIVOT_HEIGHT = 2.0f / 16.0f;
	private static final float CROUCHING_ARM_PIVOT_HEIGHT = 5.2f / 16.0f;
	private static final float CROUCHING_ARM_ROTATION = 0.4f;
	private static final float ITEM_HAND_SIDE = 1.0f / 16.0f;
	private static final float ITEM_HAND_UP = 0.125f;
	private static final float ITEM_HAND_FORWARD = -0.625f;
	private static final Vector4f MODEL_MUZZLE = new Vector4f(0.0f, -2.4f / 16.0f, -22.0f / 16.0f, 1.0f);

	private SonicDogCannonClientEffects() {}

	public static void fire(LocalPlayer localPlayer, int shooterId, InteractionHand hand, Vec3 suppliedDirection) {
		ClientLevel level = localPlayer.clientLevel;
		Entity entity = level.getEntity(shooterId);
		if (!(entity instanceof Player shooter) || !isFinite(suppliedDirection)
			|| suppliedDirection.lengthSqr() < 1.0e-6d)
			return;

		Vec3 direction = suppliedDirection.normalize();
		Minecraft minecraft = Minecraft.getInstance();
		boolean localFirstPerson = shooter == localPlayer
			&& minecraft.getCameraEntity() == shooter
			&& minecraft.options.getCameraType() == CameraType.FIRST_PERSON;
		Vec3 muzzle = localFirstPerson
			? getFirstPersonMuzzle(shooter, hand, direction)
			: getThirdPersonMuzzle(shooter, hand);

		for (int i = 0; i < PARTICLE_COUNT; i++) {
			SonicConeWaveParticleOption particle = new SonicConeWaveParticleOption(
				direction.toVector3f(), i * PARTICLE_DELAY_TICKS);
			level.addParticle(particle, muzzle.x, muzzle.y, muzzle.z, 0.0d, 0.0d, 0.0d);
		}
	}

	private static Vec3 getFirstPersonMuzzle(Player shooter, InteractionHand hand, Vec3 direction) {
		Vec3 up = shooter.getUpVector(1.0f).normalize();
		Vec3 right = direction.cross(up).normalize();
		double side = getHoldingArm(shooter, hand) == HumanoidArm.RIGHT
			? FIRST_PERSON_HAND_SIDE
			: -FIRST_PERSON_HAND_SIDE;
		Vec3 handPosition = shooter.getEyePosition()
			.add(right.scale(side))
			.add(up.scale(-FIRST_PERSON_HAND_DOWN))
			.add(direction.scale(FIRST_PERSON_HAND_FORWARD));
		return handPosition
			.add(direction.scale(MUZZLE_FORWARD_FROM_ITEM_CENTRE))
			.add(up.scale(FIRST_PERSON_MUZZLE_UP_FROM_HAND));
	}

	private static Vec3 getThirdPersonMuzzle(Player shooter, InteractionHand hand) {
		HumanoidArm arm = getHoldingArm(shooter, hand);
		boolean leftHand = arm == HumanoidArm.LEFT;
		float bodyYaw = shooter.yBodyRot;
		float headYaw = Mth.wrapDegrees(shooter.getYRot() - bodyYaw) * Mth.DEG_TO_RAD;
		float armPitch = (-60.0f + shooter.getXRot()) * Mth.DEG_TO_RAD;
		float armRoll = 0.0f;
		float armPivotY = ARM_PIVOT_HEIGHT;
		if (shooter.isCrouching()) {
			armPitch += CROUCHING_ARM_ROTATION;
			armPivotY = CROUCHING_ARM_PIVOT_HEIGHT;
		}

		PoseStack poseStack = new PoseStack();
		poseStack.translate(shooter.getX(), shooter.getY(), shooter.getZ());
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - bodyYaw));
		poseStack.scale(-1.0f, -1.0f, 1.0f);
		poseStack.translate(0.0f, -MODEL_ROOT_HEIGHT, 0.0f);
		poseStack.translate(leftHand ? ARM_PIVOT_SIDE : -ARM_PIVOT_SIDE, armPivotY, 0.0f);
		poseStack.mulPose(new Quaternionf().rotationZYX(armRoll, headYaw, armPitch));
		poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));
		poseStack.translate(leftHand ? -ITEM_HAND_SIDE : ITEM_HAND_SIDE,
			ITEM_HAND_UP, ITEM_HAND_FORWARD);

		if (leftHand) {
			poseStack.translate(0.0f, 0.0f, -4.0f / 16.0f);
			poseStack.mulPose(Axis.YP.rotationDegrees(15.0f));
		} else {
			poseStack.translate(0.0f, 6.0f / 16.0f, 5.5f / 16.0f);
			poseStack.mulPose(Axis.XP.rotationDegrees(30.0f));
		}

		Vector4f muzzle = new Vector4f(MODEL_MUZZLE).mul(poseStack.last().pose());
		return new Vec3(muzzle.x, muzzle.y, muzzle.z);
	}

	private static HumanoidArm getHoldingArm(Player shooter, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? shooter.getMainArm() : shooter.getMainArm().getOpposite();
	}

	private static boolean isFinite(Vec3 vector) {
		return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
	}
}
